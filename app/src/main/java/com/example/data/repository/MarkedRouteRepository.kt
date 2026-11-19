package com.example.data.repository

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.data.local.DanskStiDao
import com.example.data.local.PreseededData
import com.example.data.model.DiscoveredHikingRouteDto
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteCacheBoxEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.MarkedRouteHeaderTuple
import com.example.data.model.MarkedRouteRenderItem
import com.example.data.model.MarkedRouteSegmentsTuple
import com.example.data.model.SavedTrailEntity
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sqrt

/**
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

        const val MAX_CACHE_TOTAL_POINTS = 300_000
        const val MAX_BATCH_IDS = 50
        const val OVERSIZED_SEGMENTS_MAX_BYTES = 1_500_000

        const val DENMARK_MIN_LAT = 54.4
        const val DENMARK_MAX_LAT = 57.9
        const val DENMARK_MIN_LNG = 7.8
        const val DENMARK_MAX_LNG = 15.3

        const val LOD1_TOLERANCE_METERS = 35.0
        const val LOD2_TOLERANCE_METERS = 150.0

        private const val PREFS_NAME = "dansk_sti_marked_routes_prefs"
        private const val PREF_KEY_CLEANED_OVERSIZED_V1 = "cleaned_oversized_marked_routes_v1"

        val MORE_MARKED_ROUTES_OVERPASS_QUERY = """
            [out:json][timeout:30];
            area(3600050046)->.denmark;
            relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area.denmark);
            out tags;
        """.trimIndent()

        @VisibleForTesting
        @Volatile
        internal var customMoreMarkedRoutesFetcher: (() -> String?)? = null

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

        /**
         * Process-wide cached entry holding ONLY lod0 (stitched, lossless, nullable when evicted),
         * lod1 (35 m), and lod2 (150 m) as packed DoubleArrays [lat0, lng0, lat1, lng1, ...].
         * Does NOT store originalUnsimplifiedPolylines in memory.
         */
        internal class ProcessCachedRouteEntry(
            val osmRelationId: Long,
            var name: String,
            var lengthKm: Double,
            var operator: String?,
            var routeType: String,
            var network: String?,
            var symbol: String?,
            var colorHex: String,
            var colorInt: Int,
            var minLat: Double,
            var maxLat: Double,
            var minLng: Double,
            var maxLng: Double,
            var cachedAt: Long,
            var lod0: List<DoubleArray>?,
            val lod1: List<DoubleArray>,
            val lod2: List<DoubleArray>
        ) {
            val lod0Points: Int
                get() = lod0?.sumOf { it.size / 2 } ?: 0
            val lod1Points: Int
                get() = lod1.sumOf { it.size / 2 }
            val lod2Points: Int
                get() = lod2.sumOf { it.size / 2 }
            val totalPoints: Int
                get() = lod0Points + lod1Points + lod2Points

            fun toRenderItem(): MarkedRouteRenderItem = MarkedRouteRenderItem(
                osmRelationId = osmRelationId,
                name = name,
                lengthKm = lengthKm,
                operator = operator,
                routeType = routeType,
                network = network,
                symbol = symbol,
                colorHex = colorHex,
                colorInt = colorInt,
                minLat = minLat,
                maxLat = maxLat,
                minLng = minLng,
                maxLng = maxLng,
                cachedAt = cachedAt,
                lod0 = lod0,
                lod1 = lod1,
                lod2 = lod2
            )
        }

        private val cacheLock = Any()

        // Access-ordered LinkedHashMap for process-wide LRU tracking
        private val processWideLruCache = LinkedHashMap<Long, ProcessCachedRouteEntry>(128, 0.75f, true)
        private var currentCachedTotalPoints: Int = 0

        // Tracks unreadable/corrupt/oversized rows (osmRelationId -> cachedAt) so they aren't retried on every frame
        private val unreadableRouteIds = ConcurrentHashMap<Long, Long>()

        @Volatile
        private var oversizedCleanupDoneInProcess: Boolean = false

        // Telemetry counter for tests verifying how many segmentsJson rows were loaded from Room
        val roomSegmentsLoadCount = AtomicInteger(0)

        // Optional test hook to simulate batch failure or custom segment loading
        @VisibleForTesting
        @Volatile
        internal var batchSegmentsQueryInterceptor: ((List<Long>) -> Unit)? = null

        /**
         * Clears the process-wide in-memory marked route cache (called by "Ryd cache" and tests).
         */
        fun clearProcessWideCache() {
            synchronized(cacheLock) {
                processWideLruCache.clear()
                currentCachedTotalPoints = 0
                unreadableRouteIds.clear()
            }
        }

        @VisibleForTesting
        fun resetOversizedCleanupFlagForTests(context: Context? = null) {
            oversizedCleanupDoneInProcess = false
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.remove(PREF_KEY_CLEANED_OVERSIZED_V1)
                ?.commit()
        }

        fun getProcessWideCachedTotalPoints(): Int {
            synchronized(cacheLock) {
                return currentCachedTotalPoints
            }
        }

        fun getProcessWideCachedRouteCount(): Int {
            synchronized(cacheLock) {
                return processWideLruCache.size
            }
        }

        fun getProcessWideCachedRoutesWithLod0Count(): Int {
            synchronized(cacheLock) {
                return processWideLruCache.values.count { it.lod0 != null }
            }
        }

        internal fun parseColorSafe(colorHex: String): Int {
            return try {
                Color.parseColor(colorHex)
            } catch (_: Exception) {
                0xFFF57C00.toInt()
            }
        }

        /**
         * Stitches contiguous OSM way segments that share an endpoint (within ~1e-6 deg)
         * into continuous lossless polylines (packed DoubleArray [lat0, lng0, lat1, lng1, ...]).
         */
        fun stitchWaySegmentsToPackedPolylines(rawPolylines: List<DoubleArray>): List<DoubleArray> {
            val valid = rawPolylines.filter { it.size >= 4 }
            if (valid.isEmpty()) return emptyList()
            if (valid.size == 1) return valid

            val eps = 1e-6
            val chains = ArrayList<DoubleArray>(valid.size)
            var current = valid[0]

            for (i in 1 until valid.size) {
                val next = valid[i]
                val curEndLat = current[current.size - 2]
                val curEndLng = current[current.size - 1]
                val curStartLat = current[0]
                val curStartLng = current[1]

                val nextStartLat = next[0]
                val nextStartLng = next[1]
                val nextEndLat = next[next.size - 2]
                val nextEndLng = next[next.size - 1]

                when {
                    // curEnd == nextStart -> append next[2..end]
                    kotlin.math.abs(curEndLat - nextStartLat) <= eps &&
                        kotlin.math.abs(curEndLng - nextStartLng) <= eps -> {
                        val merged = DoubleArray(current.size + next.size - 2)
                        System.arraycopy(current, 0, merged, 0, current.size)
                        System.arraycopy(next, 2, merged, current.size, next.size - 2)
                        current = merged
                    }
                    // curEnd == nextEnd -> append reversed(next)[2..end]
                    kotlin.math.abs(curEndLat - nextEndLat) <= eps &&
                        kotlin.math.abs(curEndLng - nextEndLng) <= eps -> {
                        val revNext = reversePackedPolyline(next)
                        val merged = DoubleArray(current.size + revNext.size - 2)
                        System.arraycopy(current, 0, merged, 0, current.size)
                        System.arraycopy(revNext, 2, merged, current.size, revNext.size - 2)
                        current = merged
                    }
                    // curStart == nextEnd -> prepend next[0..end-2] to current
                    kotlin.math.abs(curStartLat - nextEndLat) <= eps &&
                        kotlin.math.abs(curStartLng - nextEndLng) <= eps -> {
                        val merged = DoubleArray(next.size + current.size - 2)
                        System.arraycopy(next, 0, merged, 0, next.size)
                        System.arraycopy(current, 2, merged, next.size, current.size - 2)
                        current = merged
                    }
                    // curStart == nextStart -> prepend reversed(next)[0..end-2] to current
                    kotlin.math.abs(curStartLat - nextStartLat) <= eps &&
                        kotlin.math.abs(curStartLng - nextStartLng) <= eps -> {
                        val revNext = reversePackedPolyline(next)
                        val merged = DoubleArray(revNext.size + current.size - 2)
                        System.arraycopy(revNext, 0, merged, 0, revNext.size)
                        System.arraycopy(current, 2, merged, revNext.size, current.size - 2)
                        current = merged
                    }
                    else -> {
                        chains.add(current)
                        current = next
                    }
                }
            }
            chains.add(current)
            return chains
        }

        private fun reversePackedPolyline(coords: DoubleArray): DoubleArray {
            val n = coords.size / 2
            val out = DoubleArray(coords.size)
            for (i in 0 until n) {
                val srcIdx = (n - 1 - i) * 2
                val dstIdx = i * 2
                out[dstIdx] = coords[srcIdx]
                out[dstIdx + 1] = coords[srcIdx + 1]
            }
            return out
        }

        /**
         * Douglas-Peucker simplification in metres (scales longitude by cos(latitude)).
         */
        fun simplifyPackedPolylineMetric(coords: DoubleArray, toleranceMeters: Double): DoubleArray {
            val pointCount = coords.size / 2
            if (pointCount <= 2 || toleranceMeters <= 0.0) return coords

            val refLat = coords[0]
            val cosLat = cos(Math.toRadians(refLat)).coerceAtLeast(0.1)
            val metersPerDegLat = 111_320.0
            val metersPerDegLng = 111_320.0 * cosLat
            val tolSq = toleranceMeters * toleranceMeters

            val keep = BooleanArray(pointCount)
            keep[0] = true
            keep[pointCount - 1] = true

            // Iterative stack to avoid recursion depth issues on long trails
            val stack = IntArray(pointCount * 2)
            var top = 0
            stack[top++] = 0
            stack[top++] = pointCount - 1

            while (top > 0) {
                val endIdx = stack[--top]
                val startIdx = stack[--top]
                if (endIdx - startIdx <= 1) continue

                val startLat = coords[startIdx * 2]
                val startLng = coords[startIdx * 2 + 1]
                val endLat = coords[endIdx * 2]
                val endLng = coords[endIdx * 2 + 1]

                val ax = (startLng - startLng) * metersPerDegLng // 0.0
                val ay = (startLat - startLat) * metersPerDegLat // 0.0
                val bx = (endLng - startLng) * metersPerDegLng
                val by = (endLat - startLat) * metersPerDegLat
                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy

                var maxDistSq = -1.0
                var maxIdx = -1

                for (i in (startIdx + 1) until endIdx) {
                    val px = (coords[i * 2 + 1] - startLng) * metersPerDegLng
                    val py = (coords[i * 2] - startLat) * metersPerDegLat

                    val distSq = if (lenSq <= 1e-12) {
                        px * px + py * py
                    } else {
                        val t = ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
                        val projX = t * dx
                        val projY = t * dy
                        val ex = px - projX
                        val ey = py - projY
                        ex * ex + ey * ey
                    }

                    if (distSq > maxDistSq) {
                        maxDistSq = distSq
                        maxIdx = i
                    }
                }

                if (maxDistSq > tolSq && maxIdx != -1) {
                    keep[maxIdx] = true
                    stack[top++] = startIdx
                    stack[top++] = maxIdx
                    stack[top++] = maxIdx
                    stack[top++] = endIdx
                }
            }

            var keptCount = 0
            for (i in 0 until pointCount) {
                if (keep[i]) keptCount++
            }
            val result = DoubleArray(keptCount * 2)
            var outIdx = 0
            for (i in 0 until pointCount) {
                if (keep[i]) {
                    result[outIdx++] = coords[i * 2]
                    result[outIdx++] = coords[i * 2 + 1]
                }
            }
            return result
        }

        /**
         * Inserts or updates an entry in the process-wide LRU cache and enforces the 300,000-point cap
         * using tiered eviction:
         * 1) First drop lod0 from least-recently-used routes (excluding [protectedIds] if at high zoom).
         * 2) Evict whole entries only if lod1 + lod2 alone exceed the 300,000-point cap.
         */
        internal fun putInProcessWideCache(
            entry: ProcessCachedRouteEntry,
            protectLod0Ids: Set<Long> = emptySet()
        ) {
            synchronized(cacheLock) {
                val existing = processWideLruCache.remove(entry.osmRelationId)
                if (existing != null) {
                    currentCachedTotalPoints -= existing.totalPoints
                }
                processWideLruCache[entry.osmRelationId] = entry
                currentCachedTotalPoints += entry.totalPoints

                enforceTieredEvictionLocked(protectLod0Ids + entry.osmRelationId)
            }
        }

        private fun enforceTieredEvictionLocked(protectLod0Ids: Set<Long>) {
            if (currentCachedTotalPoints <= MAX_CACHE_TOTAL_POINTS) return

            // Pass 1: Drop lod0 from least-recently-used entries (first unprotected, then protected if still over cap)
            for (candidate in processWideLruCache.values) {
                if (currentCachedTotalPoints <= MAX_CACHE_TOTAL_POINTS) break
                if (candidate.lod0 != null && candidate.osmRelationId !in protectLod0Ids) {
                    val droppedPts = candidate.lod0Points
                    candidate.lod0 = null
                    currentCachedTotalPoints -= droppedPts
                }
            }
            if (currentCachedTotalPoints > MAX_CACHE_TOTAL_POINTS) {
                for (candidate in processWideLruCache.values) {
                    if (currentCachedTotalPoints <= MAX_CACHE_TOTAL_POINTS) break
                    if (candidate.lod0 != null) {
                        val droppedPts = candidate.lod0Points
                        candidate.lod0 = null
                        currentCachedTotalPoints -= droppedPts
                    }
                }
            }

            // Pass 2: Only if lod1 + lod2 alone exceed 300,000 points, evict whole LRU entries
            if (currentCachedTotalPoints > MAX_CACHE_TOTAL_POINTS) {
                val iter = processWideLruCache.entries.iterator()
                while (iter.hasNext() && currentCachedTotalPoints > MAX_CACHE_TOTAL_POINTS && processWideLruCache.size > 1) {
                    val eldest = iter.next().value
                    currentCachedTotalPoints -= eldest.totalPoints
                    iter.remove()
                }
            }
            if (currentCachedTotalPoints < 0) currentCachedTotalPoints = 0
        }
    }

    @Volatile
    var lastFetchFailed: Boolean = false
        private set

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
     * One-time cleanup on first run after this update:
     * delete marked_routes rows where LENGTH(segmentsJson) > 1_500_000, plus their cache boxes,
     * so they are fetched again with the Denmark filter.
     */
    suspend fun runOneTimeOversizedRoutesCleanupIfNeeded() = withContext(Dispatchers.IO) {
        if (oversizedCleanupDoneInProcess) return@withContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyCleaned = prefs.getBoolean(PREF_KEY_CLEANED_OVERSIZED_V1, false)
        if (!alreadyCleaned) {
            try {
                dao.deleteCacheBoxesForOversizedMarkedRoutes(OVERSIZED_SEGMENTS_MAX_BYTES)
                val deletedRoutes = dao.deleteOversizedMarkedRoutes(OVERSIZED_SEGMENTS_MAX_BYTES)
                if (deletedRoutes > 0) {
                    clearProcessWideCache()
                    com.example.util.DLog.run {
                        d(TAG, "One-time cleanup deleted $deletedRoutes oversized marked_routes rows (> $OVERSIZED_SEGMENTS_MAX_BYTES bytes) and their cache boxes")
                    }
                }
                prefs.edit().putBoolean(PREF_KEY_CLEANED_OVERSIZED_V1, true).apply()
            } catch (e: Exception) {
                Log.w(TAG, "One-time oversized marked_routes cleanup warning: ${e.message}")
            }
        }
        oversizedCleanupDoneInProcess = true
    }

    /**
     * Fetches "Flere afmærkede ruter" from Overpass, cached for 30 days on disk,
     * excluding relations already in the catalogue.
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
                source = "OpenStreetMap"
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
     * Loads a single route row from Room by [osmRelationId] on Dispatchers.IO and returns
     * its full original unsimplified MarkedRouteDto (used when a user taps a route).
     */
    suspend fun getFullMarkedRouteById(osmRelationId: Long): MarkedRouteDto? = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getMarkedRouteById(osmRelationId) ?: return@withContext null
            entityToDto(entity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load marked route $osmRelationId from Room: ${e.message}")
            null
        }
    }

    /**
     * Retrieves immutable render snapshots for the visible map area using the process-wide LRU cache
     * and two-phase lightweight header + max-50 batched segmentsJson queries.
     * Never executes SELECT * over the whole viewport.
     */
    suspend fun getMarkedRouteRenderSnapshots(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        zoomLevel: Double
    ): List<MarkedRouteRenderItem> = withContext(Dispatchers.IO) {
        runOneTimeOversizedRoutesCleanupIfNeeded()

        val requireLod0 = zoomLevel >= 12.0

        if (zoomLevel < 10.0) {
            lastFetchFailed = false
            return@withContext loadRenderSnapshotsFromDatabaseHeaders(
                minLat = minLat,
                maxLat = maxLat,
                minLng = minLng,
                maxLng = maxLng,
                requireLod0 = requireLod0
            )
        }

        val now = System.currentTimeMillis()
        val expiredThreshold = now - CACHE_TTL_MS

        // 1. Check local Room database cache box
        val existingBoxes = dao.getMarkedRouteCacheBoxes(minLat, maxLat, minLng, maxLng)
        val validBox = existingBoxes.firstOrNull { it.fetchedAt >= expiredThreshold }

        if (validBox != null) {
            val cachedSnapshots = loadRenderSnapshotsFromDatabaseHeaders(
                minLat = minLat,
                maxLat = maxLat,
                minLng = minLng,
                maxLng = maxLng,
                requireLod0 = requireLod0
            )
            if (cachedSnapshots.isNotEmpty()) {
                lastFetchFailed = false
                com.example.util.DLog.run {
                    d(TAG, "Cache hit: Returning ${cachedSnapshots.size} marked routes from Room/Memory (cached within 7 days)")
                }
                return@withContext cachedSnapshots
            }
        }

        // 2. Query Overpass API for relation[route=hiking] and relation[route=foot]
        var networkFailed = false
        try {
            val liveRoutes = queryOverpassMarkedRoutes(minLat, maxLat, minLng, maxLng)
            lastFetchFailed = false
            if (liveRoutes.isNotEmpty()) {
                val cachedAt = System.currentTimeMillis()
                val entities = liveRoutes.map { dtoToEntity(it, cachedAt) }
                dao.insertMarkedRoutes(entities)

                // Populate process-wide cache directly from parsed live routes
                for (dto in liveRoutes) {
                    val rawPacked = dto.polylines.mapNotNull { seg ->
                        if (seg.size < 2) null else {
                            val arr = DoubleArray(seg.size * 2)
                            for (i in seg.indices) {
                                arr[i * 2] = seg[i].lat
                                arr[i * 2 + 1] = seg[i].lng
                            }
                            arr
                        }
                    }
                    if (rawPacked.isNotEmpty()) {
                        val lod0 = stitchWaySegmentsToPackedPolylines(rawPacked)
                        val lod1 = lod0.map { simplifyPackedPolylineMetric(it, LOD1_TOLERANCE_METERS) }
                        val lod2 = lod0.map { simplifyPackedPolylineMetric(it, LOD2_TOLERANCE_METERS) }
                        val entry = ProcessCachedRouteEntry(
                            osmRelationId = dto.osmRelationId,
                            name = dto.name,
                            lengthKm = dto.lengthKm,
                            operator = dto.operator,
                            routeType = dto.routeType,
                            network = dto.network,
                            symbol = dto.symbol,
                            colorHex = dto.colorHex,
                            colorInt = parseColorSafe(dto.colorHex),
                            minLat = dto.minLat,
                            maxLat = dto.maxLat,
                            minLng = dto.minLng,
                            maxLng = dto.maxLng,
                            cachedAt = cachedAt,
                            lod0 = lod0,
                            lod1 = lod1,
                            lod2 = lod2
                        )
                        putInProcessWideCache(entry)
                    }
                }

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
                com.example.util.DLog.run {
                    d(TAG, "Successfully fetched and cached ${liveRoutes.size} marked routes from Overpass")
                }
                return@withContext loadRenderSnapshotsFromDatabaseHeaders(
                    minLat = minLat,
                    maxLat = maxLat,
                    minLng = minLng,
                    maxLng = maxLng,
                    requireLod0 = requireLod0
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            networkFailed = true
            Log.w(TAG, "Overpass query failed: ${e.message}. Checking offline fallback cache.")
        }

        // 3. Fallback to any previously cached routes in bbox
        val fallback = loadRenderSnapshotsFromDatabaseHeaders(
            minLat = minLat,
            maxLat = maxLat,
            minLng = minLng,
            maxLng = maxLng,
            requireLod0 = requireLod0
        )
        lastFetchFailed = networkFailed && fallback.isEmpty()
        fallback
    }

    /**
     * Backward-compatible DTO getter delegating to the lightweight two-step loader.
     */
    suspend fun getMarkedRoutes(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        zoomLevel: Double
    ): List<MarkedRouteDto> = withContext(Dispatchers.IO) {
        val snapshots = getMarkedRouteRenderSnapshots(minLat, maxLat, minLng, maxLng, zoomLevel)
        snapshots.map { item ->
            val activePolylines = (item.lod0 ?: item.lod1).map { arr ->
                val list = ArrayList<GeoPointDto>(arr.size / 2)
                var i = 0
                while (i < arr.size) {
                    list.add(GeoPointDto(arr[i], arr[i + 1]))
                    i += 2
                }
                list
            }
            MarkedRouteDto(
                osmRelationId = item.osmRelationId,
                name = item.name,
                lengthKm = item.lengthKm,
                operator = item.operator,
                routeType = item.routeType,
                network = item.network,
                symbol = item.symbol,
                colorHex = item.colorHex,
                polylines = activePolylines,
                minLat = item.minLat,
                maxLat = item.maxLat,
                minLng = item.minLng,
                maxLng = item.maxLng
            )
        }
    }

    /**
     * Two-step loader:
     * 1) Queries lightweight headers (osmRelationId, cachedAt, bbox, tags, LENGTH(segmentsJson)) without segmentsJson.
     * 2) Loads segmentsJson ONLY for cache misses (or changed cachedAt, or missing lod0 when requireLod0 = true)
     *    in batches of at most 50 IDs, with single-row retry & fault isolation on batch failure.
     */
    private suspend fun loadRenderSnapshotsFromDatabaseHeaders(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        requireLod0: Boolean
    ): List<MarkedRouteRenderItem> {
        val headers = dao.getMarkedRouteHeadersInBbox(minLat, maxLat, minLng, maxLng)
        if (headers.isEmpty()) return emptyList()

        val headerById = LinkedHashMap<Long, MarkedRouteHeaderTuple>(headers.size)
        for (h in headers) {
            headerById[h.osmRelationId] = h
        }

        val missingIds = ArrayList<Long>()
        synchronized(cacheLock) {
            for (header in headers) {
                val badCachedAt = unreadableRouteIds[header.osmRelationId]
                if (badCachedAt != null && badCachedAt == header.cachedAt) {
                    continue
                }
                val cached = processWideLruCache[header.osmRelationId]
                if (cached == null || cached.cachedAt != header.cachedAt) {
                    missingIds.add(header.osmRelationId)
                } else {
                    // Refresh metadata fields in case tags updated
                    cached.name = header.name
                    cached.lengthKm = header.lengthKm
                    cached.operator = header.operator
                    cached.routeType = header.routeType
                    cached.network = header.network
                    cached.symbol = header.symbol
                    cached.colorHex = header.colorHex
                    cached.colorInt = parseColorSafe(header.colorHex)
                    cached.minLat = header.minLat
                    cached.maxLat = header.maxLat
                    cached.minLng = header.minLng
                    cached.maxLng = header.maxLng
                    if (requireLod0 && cached.lod0 == null) {
                        missingIds.add(header.osmRelationId)
                    }
                }
            }
        }

        if (missingIds.isNotEmpty()) {
            val protectIds = if (requireLod0) headerById.keys else emptySet()
            for (batchIds in missingIds.chunked(MAX_BATCH_IDS)) {
                val loadedTuples = fetchSegmentsBatchWithIsolation(batchIds, headerById)
                for (tuple in loadedTuples) {
                    val header = headerById[tuple.osmRelationId] ?: continue
                    val entry = decodeSegmentsIntoCacheEntry(header, tuple)
                    if (entry != null) {
                        putInProcessWideCache(entry, protectLod0Ids = protectIds)
                    } else {
                        unreadableRouteIds[header.osmRelationId] = header.cachedAt
                    }
                }
            }
        }

        val result = ArrayList<MarkedRouteRenderItem>(headers.size)
        synchronized(cacheLock) {
            for (header in headers) {
                val entry = processWideLruCache[header.osmRelationId]
                if (entry != null && entry.cachedAt == header.cachedAt) {
                    result.add(entry.toRenderItem())
                }
            }
        }
        return result
    }

    /**
     * Fetches segmentsJson for [batchIds] (max 50).
     * If the batch query fails (e.g. SQLiteBlobTooBigException on a 3 MB row), retries each ID one-by-one
     * and skips (logs only) any single row that still fails.
     */
    private suspend fun fetchSegmentsBatchWithIsolation(
        batchIds: List<Long>,
        headerById: Map<Long, MarkedRouteHeaderTuple>
    ): List<MarkedRouteSegmentsTuple> {
        return try {
            batchSegmentsQueryInterceptor?.invoke(batchIds)
            // If any single row in the batch is known from LENGTH(segmentsJson) to exceed Android's ~2 MB CursorWindow
            // or if a batch query throws, fall back to one-by-one isolation.
            val hasHugeRowInBatch = batchIds.any { id ->
                (headerById[id]?.segmentsByteLength ?: 0) >= 2_000_000
            }
            if (hasHugeRowInBatch) {
                throw android.database.sqlite.SQLiteBlobTooBigException("Row exceeds 2MB CursorWindow limit in batch")
            }
            val rows = dao.getMarkedRouteSegmentsByIds(batchIds)
            roomSegmentsLoadCount.addAndGet(rows.size)
            rows
        } catch (batchErr: Exception) {
            Log.w(TAG, "Batch segments query failed for ${batchIds.size} routes (${batchErr.message}); retrying one by one")
            val recovered = ArrayList<MarkedRouteSegmentsTuple>(batchIds.size)
            for (singleId in batchIds) {
                try {
                    batchSegmentsQueryInterceptor?.invoke(listOf(singleId))
                    val byteLen = headerById[singleId]?.segmentsByteLength ?: 0
                    if (byteLen >= 2_000_000) {
                        throw android.database.sqlite.SQLiteBlobTooBigException("Single row $singleId exceeds 2MB CursorWindow ($byteLen bytes)")
                    }
                    val singleRows = dao.getMarkedRouteSegmentsByIds(listOf(singleId))
                    roomSegmentsLoadCount.addAndGet(singleRows.size)
                    recovered.addAll(singleRows)
                } catch (singleErr: Exception) {
                    Log.w(TAG, "Skipping unreadable/oversized marked_route row $singleId: ${singleErr.message}")
                    headerById[singleId]?.let { h ->
                        unreadableRouteIds[singleId] = h.cachedAt
                    }
                }
            }
            recovered
        }
    }

    private fun decodeSegmentsIntoCacheEntry(
        header: MarkedRouteHeaderTuple,
        tuple: MarkedRouteSegmentsTuple
    ): ProcessCachedRouteEntry? {
        return try {
            val rawPolylines = parseSegmentsJsonToPackedArrays(tuple.segmentsJson)
            if (rawPolylines.isEmpty()) return null
            val lod0 = stitchWaySegmentsToPackedPolylines(rawPolylines)
            if (lod0.isEmpty()) return null
            val lod1 = lod0.map { simplifyPackedPolylineMetric(it, LOD1_TOLERANCE_METERS) }
            val lod2 = lod0.map { simplifyPackedPolylineMetric(it, LOD2_TOLERANCE_METERS) }

            ProcessCachedRouteEntry(
                osmRelationId = header.osmRelationId,
                name = header.name,
                lengthKm = header.lengthKm,
                operator = header.operator,
                routeType = header.routeType,
                network = header.network,
                symbol = header.symbol,
                colorHex = header.colorHex,
                colorInt = parseColorSafe(header.colorHex),
                minLat = header.minLat,
                maxLat = header.maxLat,
                minLng = header.minLng,
                maxLng = header.maxLng,
                cachedAt = tuple.cachedAt,
                lod0 = lod0,
                lod1 = lod1,
                lod2 = lod2
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding segmentsJson for route ${header.osmRelationId}: ${e.message}")
            null
        }
    }

    internal fun parseSegmentsJsonToPackedArrays(segmentsJson: String): List<DoubleArray> {
        val rootArr = JSONArray(segmentsJson)
        val polylines = ArrayList<DoubleArray>(rootArr.length())
        for (i in 0 until rootArr.length()) {
            val lineArr = rootArr.optJSONArray(i) ?: continue
            val len = lineArr.length()
            if (len < 2) continue
            val temp = DoubleArray(len * 2)
            var count = 0
            for (j in 0 until len) {
                val ptArr = lineArr.optJSONArray(j) ?: continue
                if (ptArr.length() >= 2) {
                    temp[count * 2] = ptArr.getDouble(0)
                    temp[count * 2 + 1] = ptArr.getDouble(1)
                    count++
                }
            }
            if (count >= 2) {
                val packed = if (count == len) temp else temp.copyOf(count * 2)
                polylines.add(packed)
            }
        }
        return polylines
    }

    private suspend fun queryOverpassMarkedRoutes(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<MarkedRouteDto> = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25][maxsize:67108864];
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

    /**
     * Parses Overpass routes JSON.
     * Keeps ONLY ways whose bounding box intersects Denmark (lat 54.4–57.9, lng 7.8–15.3),
     * and recomputes each route's minLat/maxLat/minLng/maxLng from the kept ways before saving to Room.
     */
    fun parseOverpassRoutesJson(jsonStr: String): List<MarkedRouteDto> {
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
                        var wMinLat = Double.POSITIVE_INFINITY
                        var wMaxLat = Double.NEGATIVE_INFINITY
                        var wMinLng = Double.POSITIVE_INFINITY
                        var wMaxLng = Double.NEGATIVE_INFINITY

                        for (n in 0 until nodesArr.length()) {
                            val nodeId = nodesArr.optLong(n)
                            val pt = nodeMap[nodeId]
                            if (pt != null) {
                                pts.add(pt)
                                if (pt.lat < wMinLat) wMinLat = pt.lat
                                if (pt.lat > wMaxLat) wMaxLat = pt.lat
                                if (pt.lng < wMinLng) wMinLng = pt.lng
                                if (pt.lng > wMaxLng) wMaxLng = pt.lng
                            }
                        }
                        // Keep only ways whose bounding box intersects Denmark (lat 54.4–57.9, lng 7.8–15.3)
                        if (pts.size >= 2 &&
                            wMaxLat >= DENMARK_MIN_LAT && wMinLat <= DENMARK_MAX_LAT &&
                            wMaxLng >= DENMARK_MIN_LNG && wMinLng <= DENMARK_MAX_LNG
                        ) {
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

            var rMinLat = Double.POSITIVE_INFINITY
            var rMaxLat = Double.NEGATIVE_INFINITY
            var rMinLng = Double.POSITIVE_INFINITY
            var rMaxLng = Double.NEGATIVE_INFINITY

            if (members != null) {
                for (m in 0 until members.length()) {
                    val mem = members.optJSONObject(m) ?: continue
                    if (mem.optString("type") == "way") {
                        val wayId = mem.optLong("ref")
                        val wayPts = wayMap[wayId]
                        if (!wayPts.isNullOrEmpty()) {
                            polylines.add(wayPts)
                            totalKm += GeoUtils.calculatePolylineLengthKm(wayPts)
                            for (pt in wayPts) {
                                if (pt.lat < rMinLat) rMinLat = pt.lat
                                if (pt.lat > rMaxLat) rMaxLat = pt.lat
                                if (pt.lng < rMinLng) rMinLng = pt.lng
                                if (pt.lng > rMaxLng) rMaxLng = pt.lng
                            }
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

            if (polylines.isNotEmpty() && rMinLat.isFinite() && rMaxLat.isFinite() && rMinLng.isFinite() && rMaxLng.isFinite()) {
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
                        minLat = rMinLat,
                        maxLat = rMaxLat,
                        minLng = rMinLng,
                        maxLng = rMaxLng
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
     * Saves a marked route as a permanent hiking trail in the app's database
     * and downloads its high-resolution geometry.
     * Always uses the original unsimplified geometry stored in Room if available.
     */
    suspend fun saveMarkedRouteAsTrail(markedRoute: MarkedRouteDto): Result<TrailEntity> = withContext(Dispatchers.IO) {
        try {
            // Ensure we always use the original unsimplified geometry from Room if present
            val fullStoredRoute = getFullMarkedRouteById(markedRoute.osmRelationId)
            val effectiveRoute = if (fullStoredRoute != null && fullStoredRoute.polylines.isNotEmpty()) {
                fullStoredRoute
            } else {
                markedRoute
            }

            val trailId = "trail_osm_${effectiveRoute.osmRelationId}"
            val existing = dao.getTrailById(trailId)
            if (existing != null && existing.isGeometryDownloaded) {
                dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))
                return@withContext Result.success(existing)
            }

            val processedGeometry = trailGeometryRepository.fetchAndProcessTrail(
                trailId = trailId,
                trailName = effectiveRoute.name,
                osmRelationId = effectiveRoute.osmRelationId
            )

            if (processedGeometry != null) {
                trailGeometryRepository.saveGeometry(processedGeometry)
            }

            val computedLength = if (processedGeometry != null && processedGeometry.totalWalkingKm > 0) {
                processedGeometry.totalWalkingKm
            } else {
                effectiveRoute.lengthKm
            }

            // If live download returned null, save existing original polylines as geometry fallback
            if (processedGeometry == null && effectiveRoute.polylines.isNotEmpty()) {
                val segments = effectiveRoute.polylines.map { pts ->
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts
                    )
                }
                val fallbackGeom = TrailGeometryDto(
                    trailId = trailId,
                    osmRelationId = effectiveRoute.osmRelationId,
                    segments = segments,
                    sideBranches = emptyList(),
                    gaps = emptyList(),
                    totalWalkingKm = effectiveRoute.lengthKm,
                    gapsOver200mCount = 0,
                    bboxNorth = effectiveRoute.maxLat,
                    bboxSouth = effectiveRoute.minLat,
                    bboxEast = effectiveRoute.maxLng,
                    bboxWest = effectiveRoute.minLng
                )
                trailGeometryRepository.saveGeometry(fallbackGeom)
            }

            val finalNorth = processedGeometry?.bboxNorth ?: effectiveRoute.maxLat
            val finalSouth = processedGeometry?.bboxSouth ?: effectiveRoute.minLat
            val finalEast = processedGeometry?.bboxEast ?: effectiveRoute.maxLng
            val finalWest = processedGeometry?.bboxWest ?: effectiveRoute.minLng
            val computedRegion = GeoUtils.computeDanishRegionFromBbox(
                south = finalSouth,
                west = finalWest,
                north = finalNorth,
                east = finalEast
            )

            val trailEntity = TrailEntity(
                id = trailId,
                name = effectiveRoute.name,
                description = "",
                region = computedRegion,
                parentRegion = computedRegion,
                osmRelationId = effectiveRoute.osmRelationId,
                computedWalkingKm = String.format(java.util.Locale.US, "%.1f", computedLength).toDouble(),
                gapsOver200m = processedGeometry?.gaps?.size ?: 0,
                bboxNorth = finalNorth,
                bboxSouth = finalSouth,
                bboxEast = finalEast,
                bboxWest = finalWest,
                isGeometryDownloaded = true,
                lastUpdated = System.currentTimeMillis(),
                source = "OpenStreetMap"
            )

            dao.insertTrail(trailEntity)
            dao.insertSavedTrail(SavedTrailEntity(trailId = trailId))

            com.example.util.DLog.run { i(TAG, "Saved marked route as trail: ${trailEntity.name} (${trailEntity.id})") }
            Result.success(trailEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save marked route as trail: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun dtoToEntity(dto: MarkedRouteDto, cachedAt: Long = System.currentTimeMillis()): MarkedRouteEntity {
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
            cachedAt = cachedAt
        )
    }

    internal fun entityToDto(entity: MarkedRouteEntity): MarkedRouteDto {
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
