package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteCacheBoxEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.SavedTrailEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.remote.OverpassApiService
import com.example.data.remote.OverpassResponse
import com.example.util.GeoUtils
import com.example.util.network.applyDebugMainThreadCheck
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 13 Requirement B:
 * Repository for marked hiking and foot routes ("Afmærkede ruter").
 * - Queries Overpass for relation[route=hiking] and relation[route=foot] inside visible map area when zoomed in to 10 or closer.
 * - Caches results per geographic area in Room for 7 days (168 hours).
 * - Transforms relations and way geometries into lightweight, high-contrast coloured polylines with names.
 * - Allows 1-tap saving as a full trail with the Phase 1 geometry download pipeline.
 */
class MarkedRouteRepository(
    private val context: Context,
    private val dao: DanskStiDao,
    private val trailGeometryRepository: TrailGeometryRepository
) {
    companion object {
        private const val TAG = "MarkedRouteRepo"
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days in milliseconds

        val PALETTE = listOf(
            "#D32F2F", // Red
            "#E64A19", // Deep Orange
            "#F57C00", // Amber
            "#00897B", // Teal
            "#1E88E5", // Blue
            "#7B1FA2", // Purple
            "#2E7D32", // Forest Green
            "#C2185B", // Magenta
            "#0288D1", // Light Blue
            "#6D4C41"  // Brown
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .apply {
            if (com.example.BuildConfig.DEBUG) {
                addInterceptor(com.example.util.network.MainThreadNetworkInterceptor())
            }
        }
        .build()

    @Volatile
    private var activeOverpassCall: okhttp3.Call? = null

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val overpassMirrors = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter"
    )

    /**
     * Retrieves marked hiking routes for the visible map area.
     * Only triggers when zoomed in to 10 or closer (zoom >= 10.0).
     * Cancels any previous in-flight Overpass request when a new one starts.
     */
    suspend fun getMarkedRoutes(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        zoomLevel: Double
    ): List<MarkedRouteDto> = withContext(Dispatchers.IO) {
        activeOverpassCall?.cancel()
        if (zoomLevel < 10.0) {
            val cached = dao.getMarkedRoutesInBbox(minLat, maxLat, minLng, maxLng)
            return@withContext cached.map { entityToDto(it) }
        }

        // Clean up expired cache entries older than 7 days
        val now = System.currentTimeMillis()
        val expiredThreshold = now - CACHE_TTL_MS

        // 1. Check local Room database cache
        val existingBoxes = dao.getMarkedRouteCacheBoxes(minLat, maxLat, minLng, maxLng)
        val validBox = existingBoxes.firstOrNull { it.fetchedAt >= expiredThreshold }

        if (validBox != null) {
            val cachedEntities = dao.getMarkedRoutesInBbox(minLat, maxLat, minLng, maxLng)
            if (cachedEntities.isNotEmpty()) {
                Log.d(TAG, "Cache hit: Returning ${cachedEntities.size} marked routes from Room (cached within 7 days)")
                return@withContext cachedEntities.map { entityToDto(it) }
            }
        }

        // 2. Query Overpass API for relation[route=hiking] and relation[route=foot]
        try {
            val liveRoutes = queryOverpassMarkedRoutes(minLat, maxLat, minLng, maxLng)
            if (liveRoutes.isNotEmpty()) {
                val entities = liveRoutes.map { dtoToEntity(it) }
                dao.insertMarkedRoutes(entities)

                // Record cache box entry
                val boxKey = "box_${(minLat * 100).toInt()}_${(minLng * 100).toInt()}_${(maxLat * 100).toInt()}_${(maxLng * 100).toInt()}"
                dao.insertMarkedRouteCacheBox(
                    MarkedRouteCacheBoxEntity(
                        boxKey = boxKey,
                        minLat = minLat,
                        maxLat = maxLat,
                        minLng = minLng,
                        maxLng = maxLng,
                        fetchedAt = now
                    )
                )
                Log.d(TAG, "Successfully fetched and cached ${liveRoutes.size} marked routes from Overpass")
                return@withContext liveRoutes
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            activeOverpassCall?.cancel()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Overpass query failed: ${e.message}. Checking offline fallback cache.")
        }

        // 3. Fallback to any previously cached routes in bbox
        val fallback = dao.getMarkedRoutesInBbox(minLat, maxLat, minLng, maxLng)
        fallback.map { entityToDto(it) }
    }

    private suspend fun queryOverpassMarkedRoutes(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<MarkedRouteDto> = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            (
              relation["route"~"^(hiking|foot)$"]($minLat,$minLng,$maxLat,$maxLng);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()

        var lastError: Exception? = null

        for (endpoint in overpassMirrors) {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { job ->
                if (!job.isActive) throw kotlinx.coroutines.CancellationException()
            }
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("User-Agent", "DanskStiApp/1.0 (Android; Outdoor Hiking Denmark)")
                    .build()

                val call = client.newCall(request)
                activeOverpassCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} from $endpoint")
                    }
                    val jsonStr = response.body?.string() ?: return@withContext emptyList()
                    val result = parseOverpassRoutesJson(jsonStr)
                    if (result.isNotEmpty()) {
                        return@withContext result
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                activeOverpassCall?.cancel()
                throw e
            } catch (e: Exception) {
                if (activeOverpassCall?.isCanceled() == true) {
                    throw kotlinx.coroutines.CancellationException("Overpass call cancelled")
                }
                lastError = e
                Log.w(TAG, "Overpass mirror $endpoint failed: ${e.message}")
            }
        }

        if (lastError != null) {
            throw lastError
        }
        emptyList()
    }

    private fun parseOverpassRoutesJson(jsonStr: String): List<MarkedRouteDto> {
        val root = JSONObject(jsonStr)
        val elements = root.optJSONArray("elements") ?: return emptyList()

        val nodeMap = mutableMapOf<Long, GeoPointDto>()
        val wayMap = mutableMapOf<Long, List<GeoPointDto>>()
        val relations = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val id = el.optLong("id")

            when (type) {
                "node" -> {
                    val lat = el.optDouble("lat")
                    val lon = el.optDouble("lon")
                    nodeMap[id] = GeoPointDto(lat, lon)
                }
                "way" -> {
                    val nodesArr = el.optJSONArray("nodes")
                    if (nodesArr != null) {
                        val pts = mutableListOf<GeoPointDto>()
                        for (n in 0 until nodesArr.length()) {
                            val nodeId = nodesArr.optLong(n)
                            nodeMap[nodeId]?.let { pts.add(it) }
                        }
                        if (pts.isNotEmpty()) {
                            wayMap[id] = pts
                        }
                    }
                }
                "relation" -> {
                    relations.add(el)
                }
            }
        }

        val result = mutableListOf<MarkedRouteDto>()

        for (rel in relations) {
            val relId = rel.optLong("id")
            val tags = rel.optJSONObject("tags") ?: JSONObject()
            val routeType = tags.optString("route", "hiking")
            val name = tags.optString("name").takeIf { it.isNotBlank() }
                ?: tags.optString("ref").takeIf { it.isNotBlank() }
                ?: "Afmærket rute #$relId"
            val operator = tags.optString("operator").takeIf { it.isNotBlank() }
                ?: tags.optString("network:operator").takeIf { it.isNotBlank() }
            val network = tags.optString("network").takeIf { it.isNotBlank() }
            val symbol = tags.optString("osmc:symbol").takeIf { it.isNotBlank() }
                ?: tags.optString("symbol").takeIf { it.isNotBlank() }

            val rawColor = tags.optString("colour").takeIf { it.isNotBlank() }
                ?: tags.optString("color").takeIf { it.isNotBlank() }
            val colorHex = resolveColorHex(rawColor, relId)

            val members = rel.optJSONArray("members")
            val polylines = mutableListOf<List<GeoPointDto>>()
            var totalKm = 0.0

            if (members != null) {
                for (m in 0 until members.length()) {
                    val mem = members.optJSONObject(m) ?: continue
                    if (mem.optString("type") == "way") {
                        val wayId = mem.optLong("ref")
                        val wayPts = wayMap[wayId]
                        if (!wayPts.isNullOrEmpty()) {
                            polylines.add(wayPts)
                            totalKm += GeoUtils.calculatePolylineLengthKm(wayPts)
                        }
                    }
                }
            }

            // Tagged distance fallback
            val taggedDistance = tags.optString("distance")
                .replace("km", "")
                .trim()
                .toDoubleOrNull()
            if (taggedDistance != null && taggedDistance > 0) {
                totalKm = taggedDistance
            }

            if (polylines.isNotEmpty()) {
                val allPts = polylines.flatten()
                val minLat = allPts.minOf { it.lat }
                val maxLat = allPts.maxOf { it.lat }
                val minLng = allPts.minOf { it.lng }
                val maxLng = allPts.maxOf { it.lng }

                result.add(
                    MarkedRouteDto(
                        osmRelationId = relId,
                        name = name,
                        lengthKm = String.format(java.util.Locale.US, "%.1f", totalKm).toDouble(),
                        operator = operator,
                        routeType = routeType,
                        network = network,
                        symbol = symbol,
                        colorHex = colorHex,
                        polylines = polylines,
                        minLat = minLat,
                        maxLat = maxLat,
                        minLng = minLng,
                        maxLng = maxLng
                    )
                )
            }
        }

        return result
    }

    private fun resolveColorHex(rawColor: String?, id: Long): String {
        if (!rawColor.isNullOrBlank()) {
            if (rawColor.startsWith("#") && (rawColor.length == 7 || rawColor.length == 9)) {
                return rawColor
            }
            when (rawColor.lowercase()) {
                "red" -> return "#D32F2F"
                "orange" -> return "#E64A19"
                "yellow" -> return "#F57C00"
                "blue" -> return "#1976D2"
                "green" -> return "#2E7D32"
                "purple" -> return "#7B1FA2"
                "cyan", "teal" -> return "#00897B"
                "magenta", "pink" -> return "#C2185B"
                "brown" -> return "#5D4037"
                "black" -> return "#212121"
            }
        }
        val paletteIdx = (kotlin.math.abs(id) % PALETTE.size).toInt()
        return PALETTE[paletteIdx]
    }

    /**
     * Requirement B:
     * Saves a marked route as a permanent hiking trail in the app's database
     * and downloads its high-resolution geometry using the Phase 1 pipeline.
     */
    suspend fun saveMarkedRouteAsTrail(markedRoute: MarkedRouteDto): Result<TrailEntity> = withContext(Dispatchers.IO) {
        try {
            val trailId = "trail_osm_${markedRoute.osmRelationId}"
            val existing = dao.getTrailById(trailId)
            if (existing != null) {
                dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))
                return@withContext Result.success(existing)
            }

            // Execute Phase 1 pipeline: Fetch relation geometry via direct OSM 0.6 API with Overpass fallback
            val processedGeometry = trailGeometryRepository.fetchAndProcessTrail(
                trailId = trailId,
                trailName = markedRoute.name,
                osmRelationId = markedRoute.osmRelationId
            )

            val computedLength = if (processedGeometry != null && processedGeometry.totalWalkingKm > 0) {
                processedGeometry.totalWalkingKm
            } else {
                markedRoute.lengthKm
            }

            // If live download returned null, save existing polylines as geometry fallback
            if (processedGeometry == null && markedRoute.polylines.isNotEmpty()) {
                val segments = markedRoute.polylines.map { pts ->
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts
                    )
                }
                val fallbackGeom = TrailGeometryDto(
                    trailId = trailId,
                    osmRelationId = markedRoute.osmRelationId,
                    segments = segments,
                    sideBranches = emptyList(),
                    gaps = emptyList(),
                    totalWalkingKm = markedRoute.lengthKm,
                    gapsOver200mCount = 0,
                    bboxNorth = markedRoute.maxLat,
                    bboxSouth = markedRoute.minLat,
                    bboxEast = markedRoute.maxLng,
                    bboxWest = markedRoute.minLng
                )
                trailGeometryRepository.saveGeometry(fallbackGeom)
            }

            val trailEntity = TrailEntity(
                id = trailId,
                name = markedRoute.name,
                description = "Afmærket ${markedRoute.networkDescription.lowercase()} (${markedRoute.displayOperator})",
                region = "Danmark",
                parentRegion = "Danmark",
                osmRelationId = markedRoute.osmRelationId,
                computedWalkingKm = String.format(java.util.Locale.US, "%.1f", computedLength).toDouble(),
                gapsOver200m = processedGeometry?.gaps?.size ?: 0,
                bboxNorth = markedRoute.maxLat,
                bboxSouth = markedRoute.minLat,
                bboxEast = markedRoute.maxLng,
                bboxWest = markedRoute.minLng,
                isGeometryDownloaded = true,
                lastUpdated = System.currentTimeMillis()
            )

            dao.insertTrail(trailEntity)
            dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))

            Log.i(TAG, "Saved marked route as trail: ${trailEntity.name} (${trailEntity.id})")
            Result.success(trailEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save marked route as trail: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun dtoToEntity(dto: MarkedRouteDto): MarkedRouteEntity {
        val rootArr = JSONArray()
        for (line in dto.polylines) {
            val lineArr = JSONArray()
            for (pt in line) {
                val ptArr = JSONArray()
                ptArr.put(pt.lat)
                ptArr.put(pt.lng)
                lineArr.put(ptArr)
            }
            rootArr.put(lineArr)
        }

        return MarkedRouteEntity(
            osmRelationId = dto.osmRelationId,
            name = dto.name,
            lengthKm = dto.lengthKm,
            operator = dto.operator,
            routeType = dto.routeType,
            network = dto.network,
            symbol = dto.symbol,
            colorHex = dto.colorHex,
            segmentsJson = rootArr.toString(),
            minLat = dto.minLat,
            maxLat = dto.maxLat,
            minLng = dto.minLng,
            maxLng = dto.maxLng,
            cachedAt = System.currentTimeMillis()
        )
    }

    private fun entityToDto(entity: MarkedRouteEntity): MarkedRouteDto {
        val polylines = mutableListOf<List<GeoPointDto>>()
        try {
            val rootArr = JSONArray(entity.segmentsJson)
            for (i in 0 until rootArr.length()) {
                val lineArr = rootArr.optJSONArray(i) ?: continue
                val pts = mutableListOf<GeoPointDto>()
                for (j in 0 until lineArr.length()) {
                    val ptArr = lineArr.optJSONArray(j) ?: continue
                    if (ptArr.length() >= 2) {
                        pts.add(GeoPointDto(ptArr.getDouble(0), ptArr.getDouble(1)))
                    }
                }
                if (pts.isNotEmpty()) {
                    polylines.add(pts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deserializing segmentsJson for route ${entity.osmRelationId}: ${e.message}")
        }

        return MarkedRouteDto(
            osmRelationId = entity.osmRelationId,
            name = entity.name,
            lengthKm = entity.lengthKm,
            operator = entity.operator,
            routeType = entity.routeType,
            network = entity.network,
            symbol = entity.symbol,
            colorHex = entity.colorHex,
            polylines = polylines,
            minLat = entity.minLat,
            maxLat = entity.maxLat,
            minLng = entity.minLng,
            maxLng = entity.maxLng
        )
    }
}
