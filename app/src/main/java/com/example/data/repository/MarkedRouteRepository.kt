package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.local.PreseededData
import com.example.data.model.DiscoveredHikingRouteDto
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteCacheBoxEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.SavedTrailEntity
import com.example.data.model.TrailDownloadProgress
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 13 Requirement B & Round 9 C Requirement 2:
 * Repository for marked hiking and foot routes ("Afmærkede ruter" & "Flere afmærkede ruter").
 */
class MarkedRouteRepository(
    private val context: Context,
    private val dao: DanskStiDao,
    private val trailGeometryRepository: TrailGeometryRepository
) {
    companion object {
        private const val TAG = "MarkedRouteRepo"
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days in milliseconds
        const val MORE_MARKED_ROUTES_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days in milliseconds

        /**
         * Round 9 C Requirement 2 Overpass query:
         * relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area Denmark); out tags;
         */
        val MORE_MARKED_ROUTES_OVERPASS_QUERY = """
            [out:json][timeout:30];
            area["ISO3166-1"="DK"]->.denmark;
            relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area.denmark);
            out tags;
        """.trimIndent()

        @Volatile
        var customMoreMarkedRoutesFetcher: (() -> String?)? = null

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
    var lastFetchFailed: Boolean = false
        private set

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val effectiveFilesDir: File
        get() = try {
            val currentDir = com.example.DanskStiApp.instance.filesDir
            if (currentDir.exists()) currentDir else context.filesDir
        } catch (_: Exception) {
            context.filesDir
        }

    fun getMoreMarkedRoutesCacheFile(): File {
        return File(effectiveFilesDir, "more_marked_routes_cache.json")
    }

    /**
     * Round 9 C Requirement 2:
     * Fetches "Flere afmærkede ruter" from Overpass:
     * relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area Denmark); out tags;
     * Cached for 30 days on disk, excluding relations already in the catalogue.
     */
    suspend fun getMoreMarkedHikingRoutes(
        forceRefresh: Boolean = false
    ): List<DiscoveredHikingRouteDto> = withContext(Dispatchers.IO) {
        val catalogueRelationIds = buildSet<Long> {
            PreseededData.getInitialTrails().forEach { t ->
                t.osmRelationId?.let { add(it) }
            }
            try {
                dao.getAllTrails().firstOrNull()?.forEach { t ->
                    t.osmRelationId?.let { add(it) }
                }
            } catch (_: Exception) {}
        }

        val cacheFile = getMoreMarkedRoutesCacheFile()
        val now = System.currentTimeMillis()

        if (!forceRefresh && cacheFile.exists()) {
            try {
                val root = JSONObject(cacheFile.readText())
                val fetchedAt = root.optLong("fetchedAt", 0L)
                if (fetchedAt > 0L && (now - fetchedAt) < MORE_MARKED_ROUTES_CACHE_TTL_MS) {
                    val cachedRoutes = parseDiscoveredRoutesArray(root.optJSONArray("routes"))
                    return@withContext cachedRoutes
                        .filter { it.osmRelationId !in catalogueRelationIds }
                        .sortedBy { it.name.lowercase() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading more_marked_routes_cache.json: ${e.message}")
            }
        }

        val freshJson = fetchMoreMarkedRoutesOverpassJson()
        if (freshJson != null) {
            val parsed = parseOverpassTagsResponse(freshJson)
            if (parsed.isNotEmpty()) {
                saveDiscoveredRoutesCache(cacheFile, now, parsed)
                return@withContext parsed
                    .filter { it.osmRelationId !in catalogueRelationIds }
                    .sortedBy { it.name.lowercase() }
            }
        }

        // Fallback to expired cache if offline and cache exists
        if (cacheFile.exists()) {
            try {
                val root = JSONObject(cacheFile.readText())
                val cachedRoutes = parseDiscoveredRoutesArray(root.optJSONArray("routes"))
                return@withContext cachedRoutes
                    .filter { it.osmRelationId !in catalogueRelationIds }
                    .sortedBy { it.name.lowercase() }
            } catch (_: Exception) {}
        }

        emptyList()
    }

    private suspend fun fetchMoreMarkedRoutesOverpassJson(): String? {
        val customFetcher = customMoreMarkedRoutesFetcher
        if (customFetcher != null) {
            return customFetcher.invoke()
        }

        return try {
            com.example.data.remote.OverpassClient.queryRawJson(
                query = MORE_MARKED_ROUTES_OVERPASS_QUERY,
                label = "MoreMarkedRoutes"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Overpass failed for Flere afmærkede ruter: ${e.message}")
            null
        }
    }

    fun parseOverpassTagsResponse(jsonStr: String): List<DiscoveredHikingRouteDto> {
        val result = LinkedHashMap<Long, DiscoveredHikingRouteDto>()
        try {
            val root = JSONObject(jsonStr)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                if (el.optString("type") != "relation") continue
                val id = el.optLong("id", 0L)
                if (id <= 0L) continue
                val tags = el.optJSONObject("tags") ?: continue
                val routeType = tags.optString("route", "")
                if (routeType != "hiking" && routeType != "foot") continue
                val network = tags.optString("network", "").lowercase()
                if (network !in setOf("iwn", "nwn", "rwn")) continue

                val rawName = tags.optString("name:da").ifBlank {
                    tags.optString("name").ifBlank {
                        tags.optString("ref")
                    }
                }.trim()
                if (rawName.isBlank()) continue

                val ref = tags.optString("ref").takeIf { it.isNotBlank() }
                val operator = tags.optString("operator").takeIf { it.isNotBlank() }
                val distStr = tags.optString("distance").replace(",", ".").replace(Regex("[^0-9.]"), "")
                val distKm = distStr.toDoubleOrNull()

                result[id] = DiscoveredHikingRouteDto(
                    osmRelationId = id,
                    name = rawName,
                    ref = ref,
                    network = network,
                    operator = operator,
                    distanceTagKm = distKm
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Overpass tags response: ${e.message}")
        }
        return result.values.toList()
    }

    private fun saveDiscoveredRoutesCache(
        cacheFile: File,
        fetchedAt: Long,
        routes: List<DiscoveredHikingRouteDto>
    ) {
        try {
            val arr = JSONArray()
            for (r in routes) {
                val obj = JSONObject()
                obj.put("osmRelationId", r.osmRelationId)
                obj.put("name", r.name)
                if (r.ref != null) obj.put("ref", r.ref)
                obj.put("network", r.network)
                if (r.operator != null) obj.put("operator", r.operator)
                if (r.distanceTagKm != null) obj.put("distanceTagKm", r.distanceTagKm)
                arr.put(obj)
            }
            val root = JSONObject()
            root.put("fetchedAt", fetchedAt)
            root.put("routes", arr)
            cacheFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving more_marked_routes_cache.json: ${e.message}")
        }
    }

    private fun parseDiscoveredRoutesArray(arr: JSONArray?): List<DiscoveredHikingRouteDto> {
        if (arr == null) return emptyList()
        val list = mutableListOf<DiscoveredHikingRouteDto>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("osmRelationId", 0L)
            val name = obj.optString("name", "").trim()
            if (id <= 0L || name.isBlank()) continue
            list.add(
                DiscoveredHikingRouteDto(
                    osmRelationId = id,
                    name = name,
                    ref = obj.optString("ref").takeIf { it.isNotBlank() },
                    network = obj.optString("network", "rwn"),
                    operator = obj.optString("operator").takeIf { it.isNotBlank() },
                    distanceTagKm = if (obj.has("distanceTagKm")) obj.optDouble("distanceTagKm") else null
                )
            )
        }
        return list
    }

    /**
     * Round 9 C Requirement 2:
     * Tapping one route in "Flere afmærkede ruter" saves it as a trail and downloads it with the existing pipeline.
     */
    suspend fun saveAndDownloadDiscoveredRoute(
        route: DiscoveredHikingRouteDto,
        onProgress: ((TrailDownloadProgress) -> Unit)? = null
    ): Result<TrailEntity> = withContext(Dispatchers.IO) {
        try {
            val trailId = "trail_osm_${route.osmRelationId}"
            val existing = dao.getTrailById(trailId)
            if (existing != null && existing.isGeometryDownloaded && trailGeometryRepository.isGeometryCached(trailId)) {
                dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))
                return@withContext Result.success(existing)
            }

            // 1. Insert initial catalogue trail entry without typed length/region/description
            val initialTrail = TrailEntity(
                id = trailId,
                name = route.name,
                description = "",
                region = "",
                parentRegion = "",
                osmRelationId = route.osmRelationId,
                computedWalkingKm = 0.0,
                isGeometryDownloaded = false,
                source = "Overpass (${route.network.uppercase()})"
            )
            dao.insertTrail(initialTrail)
            dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))

            // 2. Download real geometry via existing TrailGeometryRepository pipeline
            val processedGeometry = trailGeometryRepository.fetchAndProcessTrail(
                trailId = trailId,
                trailName = route.name,
                osmRelationId = route.osmRelationId,
                onProgress = onProgress
            ) ?: return@withContext Result.failure(
                IOException("Kunne ikke hente rutegeometri for ${route.name} (#${route.osmRelationId})")
            )

            trailGeometryRepository.saveGeometry(processedGeometry)

            val computedRegion = GeoUtils.computeDanishRegionFromBbox(
                south = processedGeometry.bboxSouth,
                west = processedGeometry.bboxWest,
                north = processedGeometry.bboxNorth,
                east = processedGeometry.bboxEast
            )

            val downloadedTrail = initialTrail.copy(
                region = computedRegion,
                parentRegion = computedRegion,
                computedWalkingKm = processedGeometry.totalWalkingKm,
                gapsOver200m = processedGeometry.gapsOver200mCount,
                osmTimestamp = processedGeometry.osmTimestamp,
                bboxNorth = processedGeometry.bboxNorth,
                bboxSouth = processedGeometry.bboxSouth,
                bboxEast = processedGeometry.bboxEast,
                bboxWest = processedGeometry.bboxWest,
                isGeometryDownloaded = true,
                lastUpdated = System.currentTimeMillis()
            )
            dao.insertTrail(downloadedTrail)
            Result.success(downloadedTrail)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save and download discovered route ${route.name}: ${e.message}", e)
            Result.failure(e)
        }
    }

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
        if (zoomLevel < 10.0) {
            lastFetchFailed = false
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
                lastFetchFailed = false
                Log.d(TAG, "Cache hit: Returning ${cachedEntities.size} marked routes from Room (cached within 7 days)")
                return@withContext cachedEntities.map { entityToDto(it) }
            }
        }

        // 2. Query Overpass API for relation[route=hiking] and relation[route=foot]
        var networkFailed = false
        try {
            val liveRoutes = queryOverpassMarkedRoutes(minLat, maxLat, minLng, maxLng)
            lastFetchFailed = false
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
            throw e
        } catch (e: Exception) {
            networkFailed = true
            Log.w(TAG, "Overpass query failed: ${e.message}. Checking offline fallback cache.")
        }

        // 3. Fallback to any previously cached routes in bbox
        val fallback = dao.getMarkedRoutesInBbox(minLat, maxLat, minLng, maxLng)
        lastFetchFailed = networkFailed && fallback.isEmpty()
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

        val jsonStr = com.example.data.remote.OverpassClient.queryRawJson(
            query = query,
            label = "MarkedRoutes($minLat,$minLng)"
        )
        parseOverpassRoutesJson(jsonStr)
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
            if (existing != null && existing.isGeometryDownloaded) {
                dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))
                return@withContext Result.success(existing)
            }

            // Execute Phase 1 pipeline: Fetch relation geometry via direct OSM 0.6 API with Overpass fallback
            val processedGeometry = trailGeometryRepository.fetchAndProcessTrail(
                trailId = trailId,
                trailName = markedRoute.name,
                osmRelationId = markedRoute.osmRelationId
            )

            if (processedGeometry != null) {
                trailGeometryRepository.saveGeometry(processedGeometry)
            }

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

            val finalNorth = processedGeometry?.bboxNorth ?: markedRoute.maxLat
            val finalSouth = processedGeometry?.bboxSouth ?: markedRoute.minLat
            val finalEast = processedGeometry?.bboxEast ?: markedRoute.maxLng
            val finalWest = processedGeometry?.bboxWest ?: markedRoute.minLng
            val computedRegion = GeoUtils.computeDanishRegionFromBbox(
                south = finalSouth,
                west = finalWest,
                north = finalNorth,
                east = finalEast
            )

            val trailEntity = TrailEntity(
                id = trailId,
                name = markedRoute.name,
                description = "",
                region = computedRegion,
                parentRegion = computedRegion,
                osmRelationId = markedRoute.osmRelationId,
                computedWalkingKm = String.format(java.util.Locale.US, "%.1f", computedLength).toDouble(),
                gapsOver200m = processedGeometry?.gaps?.size ?: 0,
                bboxNorth = finalNorth,
                bboxSouth = finalSouth,
                bboxEast = finalEast,
                bboxWest = finalWest,
                isGeometryDownloaded = true,
                lastUpdated = System.currentTimeMillis(),
                source = "Overpass (${markedRoute.network?.uppercase() ?: "RWN"})"
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
