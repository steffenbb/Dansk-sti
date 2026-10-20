package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.data.model.AccessPolygonEntity
import com.example.data.model.access.AccessClassifier
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.routing.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class AccessLandRepository(
    private val dao: DanskStiDao,
    private val client: OkHttpClient = defaultClient()
) {
    private val TAG = "AccessLandRepo"

    companion object {
        object DiagnosticsState {
            @Volatile var wfsAnswered: Boolean = false
            @Volatile var wfsPolygonCount: Int = 0
            @Volatile var lastWfsTimestamp: Long = 0L
            @Volatile var lastError: String? = null
            @Volatile var isDataUnavailable: Boolean = false
        }

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Retrieves access polygons covering the given bounding box.
     * Looks in Room database first; if empty or older than 30 days, fetches live from
     * Danmarks Miljøportal WFS (Naturstyrelsens arealoversigt) and OpenStreetMap Overpass API,
     * merging them so official state land complements OSM land cover polygons, and caches the result in Room.
     */
    suspend fun getAccessPolygons(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> = withContext(Dispatchers.IO) {
        try {
            val cachedEntities = dao.getAccessPolygonsInBoundingBox(minLat, maxLat, minLng, maxLng)
            val isCacheValid = cachedEntities.isNotEmpty() && cachedEntities.all {
                (System.currentTimeMillis() - it.fetchedAt) < 30L * 24 * 3600 * 1000L
            }
            if (isCacheValid) {
                Log.d(TAG, "Loaded ${cachedEntities.size} access polygons from Room cache")
                return@withContext cachedEntities.map { entityToModel(it) }
            }

            // 1. Fetch live official state land from Danmarks Miljøportal WFS
            val wfsStatePolygons = try {
                val statePolys = fetchFromMiljoeportalWfs(minLat, maxLat, minLng, maxLng)
                DiagnosticsState.wfsAnswered = true
                DiagnosticsState.wfsPolygonCount = statePolys.size
                DiagnosticsState.lastWfsTimestamp = System.currentTimeMillis()
                statePolys
            } catch (e: Exception) {
                Log.w(TAG, "Miljøportal WFS fetch failed: ${e.message}")
                DiagnosticsState.wfsAnswered = false
                DiagnosticsState.lastError = e.message
                emptyList()
            }

            // 2. Fetch land cover polygons from OpenStreetMap Overpass
            val osmPolygons = try {
                fetchFromOverpass(minLat, maxLat, minLng, maxLng)
            } catch (e: Exception) {
                Log.w(TAG, "Overpass access fetch failed: ${e.message}")
                emptyList()
            }

            // 3. Promote an OSM forest to PUBLIC only if at least 50 % of its area lies inside
            // a Naturstyrelsen polygon (real polygon intersection), not by centroid distance.
            val complementedOsm = osmPolygons.map { osmPoly ->
                val isForest = osmPoly.type == AccessLandType.PUBLIC_FOREST ||
                        osmPoly.type == AccessLandType.PRIVATE_FOREST ||
                        osmPoly.type == AccessLandType.PRIVATE_OR_UNKNOWN

                if (isForest) {
                    val candidateStatePolys = if (wfsStatePolygons.isNotEmpty()) {
                        wfsStatePolygons.filter { sp ->
                            val osmMinLat = osmPoly.coordinates.minOf { it.lat }
                            val osmMaxLat = osmPoly.coordinates.maxOf { it.lat }
                            val osmMinLng = osmPoly.coordinates.minOf { it.lng }
                            val osmMaxLng = osmPoly.coordinates.maxOf { it.lng }

                            val sMinLat = sp.coordinates.minOf { it.lat }
                            val sMaxLat = sp.coordinates.maxOf { it.lat }
                            val sMinLng = sp.coordinates.minOf { it.lng }
                            val sMaxLng = sp.coordinates.maxOf { it.lng }

                            osmMinLat <= sMaxLat && osmMaxLat >= sMinLat && osmMinLng <= sMaxLng && osmMaxLng >= sMinLng
                        }
                    } else emptyList()

                    if (candidateStatePolys.isNotEmpty()) {
                        val fraction = com.example.util.access.AccessGeometryUtils.computeIntersectionAreaFraction(
                            osmPoly.coordinates,
                            candidateStatePolys.map { it.coordinates }
                        )
                        if (fraction >= 0.50) {
                            val matchingState = candidateStatePolys.firstOrNull()
                            osmPoly.copy(
                                type = AccessLandType.PUBLIC_FOREST,
                                source = "Danmarks Miljøportal (Naturstyrelsens arealoversigt)",
                                operatorOrOwner = matchingState?.operatorOrOwner ?: "Naturstyrelsen"
                            )
                        } else {
                            osmPoly.copy(type = AccessLandType.PRIVATE_OR_UNKNOWN)
                        }
                    } else {
                        osmPoly.copy(type = AccessLandType.PRIVATE_OR_UNKNOWN)
                    }
                } else {
                    osmPoly
                }
            }

            // Merge WFS state polygons and complemented OSM polygons (deduplicating by ID)
            val combinedMap = mutableMapOf<String, AccessPolygon>()
            for (p in wfsStatePolygons) combinedMap[p.id] = p
            for (p in complementedOsm) {
                if (!combinedMap.containsKey(p.id)) {
                    combinedMap[p.id] = p
                }
            }
            val combinedList = combinedMap.values.toList()

            if (combinedList.isNotEmpty()) {
                Log.d(TAG, "Caching ${combinedList.size} access polygons (WFS: ${wfsStatePolygons.size}, OSM: ${complementedOsm.size}) in Room")
                val entities = combinedList.map { modelToEntity(it) }
                dao.insertAccessPolygons(entities)
                DiagnosticsState.isDataUnavailable = false
                return@withContext combinedList
            } else if (cachedEntities.isNotEmpty()) {
                Log.d(TAG, "Live fetch returned empty, using existing cached access polygons")
                DiagnosticsState.isDataUnavailable = false
                return@withContext cachedEntities.map { entityToModel(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Live access fetch failed: ${e.message}, checking cache")
            val fallbackCache = dao.getAccessPolygonsInBoundingBox(minLat, maxLat, minLng, maxLng)
            if (fallbackCache.isNotEmpty()) {
                DiagnosticsState.isDataUnavailable = false
                return@withContext fallbackCache.map { entityToModel(it) }
            }
        }

        // If live data fails and there is no cache, show "Adgangsdata ikke tilgængelig her" and treat forests as PRIVATE_OR_UNKNOWN
        val fallbackCache = dao.getAccessPolygonsInBoundingBox(minLat, maxLat, minLng, maxLng)
        if (fallbackCache.isNotEmpty()) {
            DiagnosticsState.isDataUnavailable = false
            return@withContext fallbackCache.map { entityToModel(it) }
        }

        Log.w(TAG, "Adgangsdata ikke tilgængelig her (live fetch failed and no cache available)")
        DiagnosticsState.isDataUnavailable = true
        emptyList()
    }

    /**
     * Live WFS download from Danmarks Miljøportal:
     * Endpoint: https://wfs2-miljoegis.mim.dk/skovdrift/ows
     * FeatureType: skovdrift:Naturstyrelsens arealoversigt
     * Output format: GeoJSON (application/json)
     * Coordinates in WGS84: srsName=EPSG:4326
     */
    suspend fun fetchFromMiljoeportalWfs(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> {
        val url = "https://wfs2-miljoegis.mim.dk/skovdrift/ows?service=WFS&version=2.0.0" +
                "&request=GetFeature&typeNames=skovdrift:Naturstyrelsens+arealoversigt" +
                "&outputFormat=application/json&srsName=EPSG:4326" +
                "&bbox=$minLat,$minLng,$maxLat,$maxLng,urn:ogc:def:crs:EPSG::4326"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DanskSti/1.8 (Danmarks Miljoepris Vandring)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Miljøportal WFS HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: return emptyList()
            if (bodyStr.trimStart().startsWith("<")) {
                Log.w(TAG, "Miljøportal WFS returned XML/HTML instead of GeoJSON: ${bodyStr.take(150)}")
                return emptyList()
            }
            return parseMiljoeportalWfsJson(bodyStr)
        }
    }

    fun parseMiljoeportalWfsJson(jsonStr: String): List<AccessPolygon> {
        val result = mutableListOf<AccessPolygon>()
        val root = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Miljøportal WFS GeoJSON: ${e.message}")
            return emptyList()
        }
        val features = root.optJSONArray("features") ?: return emptyList()

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val skovnavn = props.optString("skovnavn").takeIf { it.isNotBlank() }
            val distnavn = props.optString("distnavn").takeIf { it.isNotBlank() }
            val unikid = props.optString("unikid", "id_$i")

            val geom = feature.optJSONObject("geometry") ?: continue
            val geomType = geom.optString("type")
            val coordsArr = geom.optJSONArray("coordinates") ?: continue

            val rawPolygons = mutableListOf<List<LatLng>>()

            if (geomType == "Polygon") {
                val outerRing = coordsArr.optJSONArray(0)
                if (outerRing != null) {
                    val ringPts = extractRingPoints(outerRing)
                    if (ringPts.size >= 3) rawPolygons.add(ringPts)
                }
            } else if (geomType == "MultiPolygon") {
                for (p in 0 until coordsArr.length()) {
                    val polyRingArr = coordsArr.optJSONArray(p) ?: continue
                    val outerRing = polyRingArr.optJSONArray(0) ?: continue
                    val ringPts = extractRingPoints(outerRing)
                    if (ringPts.size >= 3) rawPolygons.add(ringPts)
                }
            }

            for ((partIdx, ringPts) in rawPolygons.withIndex()) {
                val polyId = "wfs_nst_${unikid}_$partIdx"
                val name = if (!skovnavn.isNullOrBlank()) "$skovnavn (Statsskov)" else "Naturstyrelsen Statsareal"
                val operatorOwner = if (!distnavn.isNullOrBlank()) "Naturstyrelsen ($distnavn)" else "Naturstyrelsen"

                result.add(
                    AccessPolygon(
                        id = polyId,
                        name = name,
                        type = AccessLandType.PUBLIC_FOREST,
                        coordinates = ringPts,
                        source = "Danmarks Miljøportal (Naturstyrelsens arealoversigt)",
                        operatorOrOwner = operatorOwner,
                        rawTags = mapOf(
                            "operator" to "Naturstyrelsen",
                            "ownership" to "national",
                            "source" to "arealdata.miljoeportal.dk",
                            "skovnavn" to (skovnavn ?: ""),
                            "distnavn" to (distnavn ?: "")
                        )
                    )
                )
            }
        }
        return result
    }

    private fun extractRingPoints(ringArr: JSONArray): List<LatLng> {
        val pts = mutableListOf<LatLng>()
        val total = ringArr.length()
        // If ring is very detailed (> 400 pts), downsample gracefully
        val step = if (total > 400) kotlin.math.max(1, total / 300) else 1

        for (j in 0 until total step step) {
            val pt = ringArr.optJSONArray(j) ?: continue
            if (pt.length() >= 2) {
                val lng = pt.getDouble(0)
                val lat = pt.getDouble(1)
                pts.add(LatLng(lat, lng))
            }
        }
        // Ensure closed ring
        if (pts.size >= 3 && pts.first() != pts.last()) {
            pts.add(pts.first())
        }
        return pts
    }

    /**
     * Overpass query fetching landuse=forest, natural=wood, natural=heath, natural=scrub,
     * natural=grassland, natural=beach, natural=sand, landuse=farmland, landuse=meadow, leisure=nature_reserve.
     */
    private suspend fun fetchFromOverpass(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> {
        val query = """
            [out:json][timeout:20];
            (
              way["landuse"~"^(forest|farmland|meadow)$"]($minLat,$minLng,$maxLat,$maxLng);
              relation["landuse"~"^(forest|farmland|meadow)$"]($minLat,$minLng,$maxLat,$maxLng);
              way["natural"~"^(wood|heath|scrub|grassland|beach|sand)$"]($minLat,$minLng,$maxLat,$maxLng);
              relation["natural"~"^(wood|heath|scrub|grassland|beach|sand)$"]($minLat,$minLng,$maxLat,$maxLng);
              way["leisure"="nature_reserve"]($minLat,$minLng,$maxLat,$maxLng);
              relation["leisure"="nature_reserve"]($minLat,$minLng,$maxLat,$maxLng);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()

        val requestBody = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "DanskSti/1.7 (contact: easysidedk.brydskov@gmail.com)")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Overpass HTTP ${response.code}")
            }
            val bodyStr = response.body?.string() ?: return emptyList()
            return parseOverpassJson(bodyStr)
        }
    }

    private suspend fun parseOverpassJson(jsonStr: String): List<AccessPolygon> {
        val json = JSONObject(jsonStr)
        val elements = json.optJSONArray("elements") ?: return emptyList()

        // Index nodes: id -> LatLng
        val nodeMap = mutableMapOf<Long, LatLng>()
        // Index ways: id -> List<LatLng>
        val wayMap = mutableMapOf<Long, List<LatLng>>()
        val areaList = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val type = el.optString("type")
            val id = el.optLong("id")
            when (type) {
                "node" -> {
                    nodeMap[id] = LatLng(el.getDouble("lat"), el.getDouble("lon"))
                }
                "way" -> {
                    val nodesArr = el.optJSONArray("nodes")
                    val pts = mutableListOf<LatLng>()
                    if (nodesArr != null) {
                        for (j in 0 until nodesArr.length()) {
                            nodeMap[nodesArr.getLong(j)]?.let { pts.add(it) }
                        }
                    }
                    wayMap[id] = pts
                    if (el.has("tags") && pts.size >= 3) {
                        areaList.add(el)
                    }
                }
                "relation" -> {
                    if (el.has("tags")) {
                        areaList.add(el)
                    }
                }
            }
        }

        val result = mutableListOf<AccessPolygon>()

        for (area in areaList) {
            val tags = mutableMapOf<String, String>()
            val tagsObj = area.optJSONObject("tags")
            if (tagsObj != null) {
                val keys = tagsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tags[k] = tagsObj.getString(k)
                }
            }

            val areaId = "${area.getString("type")}_${area.getLong("id")}"
            val name = tags["name"]
            val operator = tags["operator"] ?: tags["owner"]

            var coords: List<LatLng> = emptyList()
            if (area.getString("type") == "way") {
                coords = wayMap[area.getLong("id")] ?: emptyList()
            } else if (area.getString("type") == "relation") {
                val members = area.optJSONArray("members")
                if (members != null) {
                    val outerPts = mutableListOf<LatLng>()
                    for (m in 0 until members.length()) {
                        val member = members.getJSONObject(m)
                        if (member.optString("role") == "outer" && member.optString("type") == "way") {
                            wayMap[member.getLong("ref")]?.let { outerPts.addAll(it) }
                        }
                    }
                    coords = outerPts
                }
            }

            if (coords.size < 3) continue

            // Classify OSM polygon from tags; promotion to PUBLIC for forests is done via real polygon intersection with Naturstyrelsen WFS
            val (type, source) = AccessClassifier.classify(tags)

            result.add(
                AccessPolygon(
                    id = areaId,
                    name = name,
                    type = type,
                    coordinates = coords,
                    source = source,
                    operatorOrOwner = operator,
                    rawTags = tags
                )
            )
        }

        return result
    }

    private fun modelToEntity(model: AccessPolygon): AccessPolygonEntity {
        val coordsArr = JSONArray()
        for (c in model.coordinates) {
            val p = JSONArray()
            p.put(c.lat)
            p.put(c.lng)
            coordsArr.put(p)
        }

        val lats = model.coordinates.map { it.lat }
        val lngs = model.coordinates.map { it.lng }

        return AccessPolygonEntity(
            id = model.id,
            name = model.name,
            type = model.type.name,
            coordinatesJson = coordsArr.toString(),
            minLat = lats.minOrNull() ?: 0.0,
            maxLat = lats.maxOrNull() ?: 0.0,
            minLng = lngs.minOrNull() ?: 0.0,
            maxLng = lngs.maxOrNull() ?: 0.0,
            source = model.source,
            operatorOrOwner = model.operatorOrOwner,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun entityToModel(entity: AccessPolygonEntity): AccessPolygon {
        val pts = mutableListOf<LatLng>()
        try {
            val arr = JSONArray(entity.coordinatesJson)
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                pts.add(LatLng(p.getDouble(0), p.getDouble(1)))
            }
        } catch (_: Exception) {}

        val type = runCatching { AccessLandType.valueOf(entity.type) }.getOrDefault(AccessLandType.UNCULTIVATED_LAND)

        return AccessPolygon(
            id = entity.id,
            name = entity.name,
            type = type,
            coordinates = pts,
            source = entity.source,
            operatorOrOwner = entity.operatorOrOwner
        )
    }



    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }
}
