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
                fetchFromMiljoeportalWfs(minLat, maxLat, minLng, maxLng)
            } catch (e: Exception) {
                Log.w(TAG, "Miljøportal WFS fetch failed: ${e.message}")
                emptyList()
            }

            // 2. Fetch land cover polygons from OpenStreetMap Overpass
            val osmPolygons = try {
                fetchFromOverpass(minLat, maxLat, minLng, maxLng)
            } catch (e: Exception) {
                Log.w(TAG, "Overpass access fetch failed: ${e.message}")
                emptyList()
            }

            // 3. Complement OSM with Miljøportal WFS:
            // For any OSM forest initially marked PRIVATE_FOREST, if it overlaps a Miljøportal state forest,
            // promote it to PUBLIC_FOREST with source Danmarks Miljøportal (Naturstyrelsens arealoversigt).
            val complementedOsm = osmPolygons.map { osmPoly ->
                if (osmPoly.type == AccessLandType.PRIVATE_FOREST && wfsStatePolygons.isNotEmpty()) {
                    val centerLat = osmPoly.coordinates.map { it.lat }.average()
                    val centerLng = osmPoly.coordinates.map { it.lng }.average()
                    val centerPt = LatLng(centerLat, centerLng)

                    val matchingState = wfsStatePolygons.find { statePoly ->
                        com.example.util.access.AccessGeometryUtils.isPointInPolygon(centerPt, statePoly.coordinates) ||
                        statePoly.coordinates.any { sc -> haversineM(centerLat, centerLng, sc.lat, sc.lng) < 600.0 }
                    }

                    if (matchingState != null) {
                        osmPoly.copy(
                            type = AccessLandType.PUBLIC_FOREST,
                            source = "Danmarks Miljøportal (Naturstyrelsens arealoversigt)",
                            operatorOrOwner = matchingState.operatorOrOwner ?: "Naturstyrelsen"
                        )
                    } else {
                        osmPoly
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
                return@withContext combinedList
            } else if (cachedEntities.isNotEmpty()) {
                Log.d(TAG, "Live fetch returned empty, using existing cached access polygons")
                return@withContext cachedEntities.map { entityToModel(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Live access fetch failed: ${e.message}, checking cache/baseline")
            val fallbackCache = dao.getAccessPolygonsInBoundingBox(minLat, maxLat, minLng, maxLng)
            if (fallbackCache.isNotEmpty()) {
                return@withContext fallbackCache.map { entityToModel(it) }
            }
        }

        // Fallback to baseline Danish public & private nature polygons
        val baseline = getBaselineDanishAccessPolygons().filter { poly ->
            poly.coordinates.any { it.lat in minLat..maxLat && it.lng in minLng..maxLng }
        }
        if (baseline.isNotEmpty()) {
            val entities = baseline.map { modelToEntity(it) }
            dao.insertAccessPolygons(entities)
        }
        baseline
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

        // Get public fri teltning areas for overlap classification
        val publicTeltning = dao.getAllFriTeltningDirect().filter {
            val cvr = (it.cvrNavn ?: "").lowercase()
            cvr.contains("naturstyrelsen") || cvr.contains("kommune")
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

            // Check if centroid overlaps public GeoFA fri teltning
            val centerLat = coords.map { it.lat }.average()
            val centerLng = coords.map { it.lng }.average()
            val overlapsPublic = publicTeltning.any { ft ->
                val d = haversineM(centerLat, centerLng, ft.centerLat, ft.centerLng)
                d < 1500.0 // within 1.5 km of state fri teltning centroid
            }
            val publicOwner = if (overlapsPublic) "Naturstyrelsen" else null

            val (type, source) = AccessClassifier.classify(tags, overlapsPublic, publicOwner)

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

    /**
     * Bundles baseline major Danish forests and nature reserves:
     * - Rold Skov (State forest / Naturstyrelsen, public)
     * - Lindenborg Skov (Private forest part of Rold Skov complex)
     * - Gribskov (State forest / Naturstyrelsen, public)
     * - Tisvilde Hegn (State forest / Naturstyrelsen, public)
     * - Jægersborg Dyrehave (State forest / Naturstyrelsen, public)
     * - Silkeborgskovene (State forest / Naturstyrelsen, public)
     * - Mols Bjerge Hede (Uncultivated land, public)
     * - Grenen / Skagen Kyst (Beach, public)
     */
    fun getBaselineDanishAccessPolygons(): List<AccessPolygon> {
        return listOf(
            // 1. Rold Skov (Statsskov / Naturstyrelsen) - Public Forest
            AccessPolygon(
                id = "baseline_rold_skov_stat",
                name = "Rold Skov (Statsskov)",
                type = AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    LatLng(56.815, 9.775),
                    LatLng(56.845, 9.780),
                    LatLng(56.850, 9.850),
                    LatLng(56.810, 9.845),
                    LatLng(56.815, 9.775)
                ),
                source = "OSM (operator=Naturstyrelsen)",
                operatorOrOwner = "Naturstyrelsen"
            ),
            // 2. Lindenborg Gods Skov (Privat skov i Rold Skov) - Private Forest
            AccessPolygon(
                id = "baseline_rold_skov_privat",
                name = "Lindenborg Skov (Privat)",
                type = AccessLandType.PRIVATE_FOREST,
                coordinates = listOf(
                    LatLng(56.850, 9.850),
                    LatLng(56.875, 9.855),
                    LatLng(56.880, 9.910),
                    LatLng(56.845, 9.905),
                    LatLng(56.850, 9.850)
                ),
                source = "OSM (privat/ukendt ejer)",
                operatorOrOwner = "Lindenborg Gods"
            ),
            // 3. Gribskov (Nordsjælland) - Public Forest
            AccessPolygon(
                id = "baseline_gribskov",
                name = "Gribskov",
                type = AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    LatLng(55.960, 12.280),
                    LatLng(56.020, 12.290),
                    LatLng(56.035, 12.380),
                    LatLng(55.965, 12.370),
                    LatLng(55.960, 12.280)
                ),
                source = "OSM (operator=Naturstyrelsen)",
                operatorOrOwner = "Naturstyrelsen"
            ),
            // 4. Løvenholm Skov (Djursland) - Private Forest
            AccessPolygon(
                id = "baseline_loevenholm_privat",
                name = "Løvenholm Skov (Privat skov)",
                type = AccessLandType.PRIVATE_FOREST,
                coordinates = listOf(
                    LatLng(56.415, 10.450),
                    LatLng(56.445, 10.460),
                    LatLng(56.440, 10.530),
                    LatLng(56.410, 10.520),
                    LatLng(56.415, 10.450)
                ),
                source = "OSM (privat/ukendt ejer)",
                operatorOrOwner = "Løvenholm Fonden"
            ),
            // 5. Rebild Bakker & Hede - Uncultivated Land
            AccessPolygon(
                id = "baseline_rebild_bakker",
                name = "Rebild Bakker",
                type = AccessLandType.UNCULTIVATED_LAND,
                coordinates = listOf(
                    LatLng(56.828, 9.828),
                    LatLng(56.838, 9.829),
                    LatLng(56.839, 9.845),
                    LatLng(56.826, 9.842),
                    LatLng(56.828, 9.828)
                ),
                source = "OSM (udyrket natur)",
                operatorOrOwner = "Rebild National Park"
            ),
            // 6. Grenen Kyst & Strand - Beach
            AccessPolygon(
                id = "baseline_skagen_strand",
                name = "Skagen Grenen Kyst",
                type = AccessLandType.BEACH,
                coordinates = listOf(
                    LatLng(57.730, 10.610),
                    LatLng(57.750, 10.630),
                    LatLng(57.755, 10.655),
                    LatLng(57.735, 10.640),
                    LatLng(57.730, 10.610)
                ),
                source = "OSM (strand/kyst)"
            ),
            // 7. Dyrkede marker ved Rold - Cultivated Fields
            AccessPolygon(
                id = "baseline_rold_marker",
                name = "Markarealer ved Oplev",
                type = AccessLandType.CULTIVATED_FIELD,
                coordinates = listOf(
                    LatLng(56.800, 9.810),
                    LatLng(56.812, 9.812),
                    LatLng(56.811, 9.835),
                    LatLng(56.798, 9.830),
                    LatLng(56.800, 9.810)
                ),
                source = "OSM (dyrket mark)"
            )
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
