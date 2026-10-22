package com.example.data.repository

import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.model.AccessPolygonEntity
import com.example.data.model.access.AccessClassifier
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.network.applyDebugMainThreadCheck
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
import java.util.concurrent.TimeUnit
import kotlin.math.floor

class AccessLandRepository(
    private val dao: DanskStiDao,
    private val client: OkHttpClient = defaultClient()
) {
    private val TAG = "AccessLandRepo"

    companion object {
        const val TILE_SIZE_DEG = 0.1
        const val CACHE_TTL_MS = 30L * 24 * 3600 * 1000L

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
                .applyDebugMainThreadCheck()
                .build()
        }

        fun getTileIdsForBounds(
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double
        ): List<TileBounds> {
            val sLat = floor(minLat * 10.0).toInt()
            val eLat = floor((maxLat - 1e-9).coerceAtLeast(minLat) * 10.0).toInt()
            val sLng = floor(minLng * 10.0).toInt()
            val eLng = floor((maxLng - 1e-9).coerceAtLeast(minLng) * 10.0).toInt()

            val tiles = mutableListOf<TileBounds>()
            // Cap tile count if someone passes a huge bounding box
            val maxLatIdx = (sLat + 15).coerceAtMost(eLat)
            val maxLngIdx = (sLng + 15).coerceAtMost(eLng)

            for (latIdx in sLat..maxLatIdx) {
                for (lngIdx in sLng..maxLngIdx) {
                    val tMinLat = latIdx / 10.0
                    val tMaxLat = (latIdx + 1) / 10.0
                    val tMinLng = lngIdx / 10.0
                    val tMaxLng = (lngIdx + 1) / 10.0
                    val tileId = "tile_${latIdx}_${lngIdx}"
                    tiles.add(TileBounds(tileId, tMinLat, tMaxLat, tMinLng, tMaxLng))
                }
            }
            return tiles
        }
    }

    data class TileBounds(
        val tileId: String,
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )

    /**
     * Retrieves access polygons covering the given bounding box by splitting into
     * 0.1 x 0.1 degree tiles and caching per tile (`tileId`) in Room.
     * Cache validity is checked per tile, not per bounding box.
     */
    suspend fun getAccessPolygons(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> = withContext(Dispatchers.IO) {
        val tiles = getTileIdsForBounds(minLat, maxLat, minLng, maxLng)
        val resultById = LinkedHashMap<String, AccessPolygon>()
        val now = System.currentTimeMillis()
        var anyTileFailedWithNoData = false

        for (tile in tiles) {
            try {
                val cachedTile = dao.getAccessPolygonsByTileId(tile.tileId)
                val isTileCacheValid = cachedTile.isNotEmpty() && cachedTile.all {
                    (now - it.fetchedAt) < CACHE_TTL_MS
                }
                if (isTileCacheValid) {
                    for (ent in cachedTile) {
                        if (ent.type == "EMPTY_TILE_SENTINEL") continue
                        val model = entityToModel(ent)
                        resultById[model.id] = model
                    }
                    DiagnosticsState.isDataUnavailable = false
                    continue
                }

                // Fetch live for this 0.1 x 0.1 tile
                val fetchedPolys = fetchAndMergeTile(tile)
                if (fetchedPolys != null) {
                    dao.deleteAccessPolygonsByTileId(tile.tileId)
                    if (fetchedPolys.isNotEmpty()) {
                        val entities = fetchedPolys.map { modelToEntity(it, tile.tileId) }
                        dao.insertAccessPolygons(entities)
                        for (poly in fetchedPolys) {
                            resultById[poly.id] = poly
                        }
                    } else {
                        // Store sentinel so we know this tile was fetched and is empty
                        val sentinel = AccessPolygonEntity(
                            id = "${tile.tileId}__sentinel",
                            name = null,
                            type = "EMPTY_TILE_SENTINEL",
                            coordinatesJson = "[]",
                            minLat = tile.minLat,
                            maxLat = tile.maxLat,
                            minLng = tile.minLng,
                            maxLng = tile.maxLng,
                            source = "cache",
                            operatorOrOwner = null,
                            fetchedAt = now,
                            tileId = tile.tileId
                        )
                        dao.insertAccessPolygons(listOf(sentinel))
                    }
                    DiagnosticsState.isDataUnavailable = false
                } else {
                    // Both WFS and Overpass failed for this tile: use stale tile cache if available
                    val stale = cachedTile.filter { it.type != "EMPTY_TILE_SENTINEL" }
                    if (stale.isNotEmpty()) {
                        for (ent in stale) {
                            val model = entityToModel(ent)
                            resultById[model.id] = model
                        }
                        DiagnosticsState.isDataUnavailable = false
                    } else {
                        val bboxFallback = dao.getAccessPolygonsInBoundingBox(tile.minLat, tile.maxLat, tile.minLng, tile.maxLng)
                            .filter { it.type != "EMPTY_TILE_SENTINEL" }
                        if (bboxFallback.isNotEmpty()) {
                            for (ent in bboxFallback) {
                                val model = entityToModel(ent)
                                resultById[model.id] = model
                            }
                            DiagnosticsState.isDataUnavailable = false
                        } else {
                            anyTileFailedWithNoData = true
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message == "Network on main thread") {
                    throw e
                }
                Log.w(TAG, "Tile ${tile.tileId} access fetch failed: ${e.message}")
                anyTileFailedWithNoData = true
            }
        }

        if (resultById.isEmpty() && anyTileFailedWithNoData) {
            DiagnosticsState.isDataUnavailable = true
        }
        resultById.values.toList()
    }

    /**
     * Fetches WFS + Overpass for a single 0.1 x 0.1 degree tile and merges them.
     * Returns null if both network sources fail with exceptions.
     */
    private suspend fun fetchAndMergeTile(tile: TileBounds): List<AccessPolygon>? {
        var wfsSucceeded = false
        var osmSucceeded = false

        val wfsStatePolygons = try {
            val statePolys = fetchFromMiljoeportalWfs(tile.minLat, tile.maxLat, tile.minLng, tile.maxLng)
            wfsSucceeded = true
            DiagnosticsState.wfsAnswered = true
            DiagnosticsState.wfsPolygonCount = statePolys.size
            DiagnosticsState.lastWfsTimestamp = System.currentTimeMillis()
            statePolys
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message == "Network on main thread") throw e
            Log.w(TAG, "Miljøportal WFS fetch failed for ${tile.tileId}: ${e.message}")
            DiagnosticsState.wfsAnswered = false
            DiagnosticsState.lastError = e.message
            emptyList()
        }

        val osmPolygons = try {
            val polys = fetchFromOverpass(tile.minLat, tile.maxLat, tile.minLng, tile.maxLng)
            osmSucceeded = true
            polys
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message == "Network on main thread") throw e
            Log.w(TAG, "Overpass access fetch failed for ${tile.tileId}: ${e.message}")
            emptyList()
        }

        if (!wfsSucceeded && !osmSucceeded) {
            return null
        }

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

        val combinedMap = LinkedHashMap<String, AccessPolygon>()
        for (p in wfsStatePolygons) combinedMap[p.id] = p
        for (p in complementedOsm) {
            if (!combinedMap.containsKey(p.id)) {
                combinedMap[p.id] = p
            }
        }
        return combinedMap.values.toList()
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
    ): List<AccessPolygon> = withContext(Dispatchers.IO) {
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
            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            if (bodyStr.trimStart().startsWith("<")) {
                Log.w(TAG, "Miljøportal WFS returned XML/HTML instead of GeoJSON: ${bodyStr.take(150)}")
                return@withContext emptyList()
            }
            return@withContext parseMiljoeportalWfsJson(bodyStr)
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
        val step = if (total > 400) kotlin.math.max(1, total / 300) else 1

        for (j in 0 until total step step) {
            val pt = ringArr.optJSONArray(j) ?: continue
            if (pt.length() >= 2) {
                val lng = pt.getDouble(0)
                val lat = pt.getDouble(1)
                pts.add(LatLng(lat, lng))
            }
        }
        if (pts.size >= 3 && pts.first() != pts.last()) {
            pts.add(pts.first())
        }
        return pts
    }

    private suspend fun fetchFromOverpass(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> = withContext(Dispatchers.IO) {
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
            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            return@withContext parseOverpassJson(bodyStr)
        }
    }

    private fun parseOverpassJson(jsonStr: String): List<AccessPolygon> {
        val json = JSONObject(jsonStr)
        val elements = json.optJSONArray("elements") ?: return emptyList()

        val nodeMap = mutableMapOf<Long, LatLng>()
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

    suspend fun getCachedAccessPolygonsInBbox(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<AccessPolygon> = withContext(Dispatchers.IO) {
        dao.getAccessPolygonsInBoundingBox(minLat, maxLat, minLng, maxLng)
            .filter { it.type != "EMPTY_TILE_SENTINEL" }
            .distinctBy { it.id.substringAfter("__", it.id) }
            .map { entityToModel(it) }
    }

    fun modelToEntity(model: AccessPolygon, tileId: String): AccessPolygonEntity {
        val coordsArr = JSONArray()
        for (c in model.coordinates) {
            val p = JSONArray()
            p.put(c.lat)
            p.put(c.lng)
            coordsArr.put(p)
        }

        val lats = model.coordinates.map { it.lat }
        val lngs = model.coordinates.map { it.lng }

        val entityId = if (tileId.isNotBlank()) "${tileId}__${model.id}" else model.id

        return AccessPolygonEntity(
            id = entityId,
            name = model.name,
            type = model.type.name,
            coordinatesJson = coordsArr.toString(),
            minLat = lats.minOrNull() ?: 0.0,
            maxLat = lats.maxOrNull() ?: 0.0,
            minLng = lngs.minOrNull() ?: 0.0,
            maxLng = lngs.maxOrNull() ?: 0.0,
            source = model.source,
            operatorOrOwner = model.operatorOrOwner,
            fetchedAt = System.currentTimeMillis(),
            tileId = tileId
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
        val rawId = entity.id.substringAfter("__", entity.id)

        return AccessPolygon(
            id = rawId,
            name = entity.name,
            type = type,
            coordinates = pts,
            source = entity.source,
            operatorOrOwner = entity.operatorOrOwner
        )
    }
}
