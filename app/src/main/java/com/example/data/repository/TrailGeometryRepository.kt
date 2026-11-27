package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailGapDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.util.network.applyDanskStiDefaults
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class TrailGeometryRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val geometryAdapter = moshi.adapter(TrailGeometryDto::class.java)

    companion object {
        private val _globalDownloadProgress = MutableStateFlow<Map<String, TrailDownloadProgress>>(emptyMap())
        val globalDownloadProgress: StateFlow<Map<String, TrailDownloadProgress>> = _globalDownloadProgress.asStateFlow()

        private class InFlightTrailDownload(
            val trailId: String,
            val deferred: kotlinx.coroutines.Deferred<TrailGeometryDto?>,
            val waiterCount: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(1),
            val progressListeners: CopyOnWriteArrayList<(TrailDownloadProgress) -> Unit> = CopyOnWriteArrayList()
        )

        private val inFlightLock = Any()
        private val inFlightDownloads = java.util.concurrent.ConcurrentHashMap<String, InFlightTrailDownload>()
        private val sharedDownloadScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        @VisibleForTesting
        internal val totalDownloadStarts = java.util.concurrent.atomic.AtomicInteger(0)

        @VisibleForTesting
        internal val downloadStartCountByTrail = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

        @VisibleForTesting
        @Volatile
        internal var customRelationFetcher: ((
            relationId: Long,
            collected: MutableMap<Long, Map<String, Any>>,
            onChildProgress: ((downloaded: Int, total: Int) -> Unit)?
        ) -> Boolean)? = null

        @VisibleForTesting
        @Volatile
        internal var customElevationFetcher: ((coords: List<Pair<Double, Double>>) -> List<Double?>?)? = null

        @VisibleForTesting
        internal fun resetDownloadDeduplicationForTest() {
            synchronized(inFlightLock) {
                inFlightDownloads.values.forEach { it.deferred.cancel() }
                inFlightDownloads.clear()
            }
            totalDownloadStarts.set(0)
            downloadStartCountByTrail.clear()
            _globalDownloadProgress.value = emptyMap()
        }

        @VisibleForTesting
        internal fun getDownloadStartCount(trailId: String): Int =
            downloadStartCountByTrail[trailId]?.get() ?: 0

        fun isDownloadInFlight(trailId: String): Boolean =
            inFlightDownloads[trailId]?.deferred?.isActive == true

        @VisibleForTesting
        internal fun getActiveWaiterCount(trailId: String): Int =
            inFlightDownloads[trailId]?.waiterCount?.get() ?: 0

        fun cancelDownload(trailId: String) {
            val removed = synchronized(inFlightLock) {
                inFlightDownloads.remove(trailId)
            }
            removed?.deferred?.cancel(
                kotlinx.coroutines.CancellationException(
                    com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.planner_download_cancelled) ?: "Download cancelled"
                )
            )
            clearProgress(trailId)
        }

        fun updateProgress(progress: TrailDownloadProgress) {
            _globalDownloadProgress.update { current ->
                current + (progress.trailId to progress)
            }
        }

        fun clearProgress(trailId: String) {
            _globalDownloadProgress.update { current ->
                current - trailId
            }
        }
    }

    val downloadProgress: StateFlow<Map<String, TrailDownloadProgress>> = globalDownloadProgress

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .applyDanskStiDefaults()
        .build()

    private val effectiveFilesDir: File
        get() = try {
            val currentDir = com.example.DanskStiApp.instance.filesDir
            if (currentDir.exists()) currentDir else context.filesDir
        } catch (_: Exception) {
            context.filesDir
        }

    private val trailsDir: File
        get() = File(effectiveFilesDir, "trails").apply { if (!exists()) mkdirs() }

    val routesDir: File
        get() = File(effectiveFilesDir, "routes").apply { if (!exists()) mkdirs() }

    fun getGeometryFile(trailId: String): File {
        val primary = File(trailsDir, "$trailId.json.gz")
        if (primary.exists()) return primary
        val fallback = File(File(context.filesDir, "trails"), "$trailId.json.gz")
        if (fallback.exists()) return fallback
        return primary
    }

    fun getRouteGeometryFile(routeId: String): File {
        val cleanId = routeId.removePrefix("custom_route_").removePrefix("route_")
        val primary = File(routesDir, "$cleanId.json.gz")
        if (primary.exists()) return primary
        val fallback = File(File(context.filesDir, "routes"), "$cleanId.json.gz")
        if (fallback.exists()) return fallback
        return primary
    }

    suspend fun isGeometryCached(trailId: String): Boolean = withContext(com.example.data.remote.OverpassClient.ioDispatcher) {
        getRouteGeometryFile(trailId).exists() ||
            getGeometryFile(trailId).exists() ||
            File(effectiveFilesDir, "trail_geom_$trailId.json").exists() ||
            File(context.filesDir, "trail_geom_$trailId.json").exists()
    }

    suspend fun loadGeometry(trailId: String): TrailGeometryDto? = withContext(com.example.data.remote.OverpassClient.ioDispatcher) {
        // 1. Check custom routes directory first
        val routeFile = getRouteGeometryFile(trailId)
        if (routeFile.exists()) {
            try {
                routeFile.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached route geometry for $trailId", e)
            }
        }

        // 2. Check trails directory (.json.gz)
        val file = getGeometryFile(trailId)
        if (file.exists()) {
            try {
                file.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached geometry for $trailId", e)
            }
        }

        // 3. Check plain JSON file in filesDir (e.g. trail_geom_<trailId>.json)
        val plainFile = File(context.filesDir, "trail_geom_$trailId.json")
        if (plainFile.exists()) {
            try {
                val dto = geometryAdapter.fromJson(plainFile.readText())
                if (dto != null) return@withContext dto
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load plain JSON geometry for $trailId", e)
            }
        }

        null
    }

    fun hasWayTagRanges(geometry: TrailGeometryDto): Boolean {
        val walkSegments = geometry.segments.filter { it.type == TrailSegmentType.WALK && it.points.size >= 2 }
        return walkSegments.isNotEmpty() && walkSegments.any { it.wayTagRanges.isNotEmpty() }
    }

    suspend fun saveGeometry(geometry: TrailGeometryDto) = withContext(com.example.data.remote.OverpassClient.ioDispatcher) {
        if (geometry.segments.all { it.points.isEmpty() }) {
            return@withContext
        }
        val file = getGeometryFile(geometry.trailId)
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tmpFile.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e("TrailGeometry", "Failed to save geometry for ${geometry.trailId}", e)
        }
    }

    suspend fun saveRouteGeometry(routeId: String, geometry: TrailGeometryDto?) = withContext(com.example.data.remote.OverpassClient.ioDispatcher) {
        if (geometry == null || geometry.segments.all { it.points.isEmpty() }) {
            return@withContext
        }
        val file = getRouteGeometryFile(routeId)
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tmpFile.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
            if (tmpFile.exists() && tmpFile.length() > 0L) {
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e("TrailGeometry", "Failed to save route geometry for $routeId", e)
        }
    }

    fun cancelDownload(trailId: String) {
        Companion.cancelDownload(trailId)
    }

    suspend fun fetchAndProcessTrail(
        trailId: String,
        trailName: String,
        osmRelationId: Long?,
        onProgress: ((TrailDownloadProgress) -> Unit)? = null
    ): TrailGeometryDto? = withContext(com.example.data.remote.OverpassClient.ioDispatcher) {
        val inFlight: InFlightTrailDownload
        var joinedExisting = false

        synchronized(inFlightLock) {
            val existing = inFlightDownloads[trailId]
            if (existing != null && existing.deferred.isActive) {
                existing.waiterCount.incrementAndGet()
                if (onProgress != null) {
                    existing.progressListeners.add(onProgress)
                }
                inFlight = existing
                joinedExisting = true
            } else {
                totalDownloadStarts.incrementAndGet()
                downloadStartCountByTrail.computeIfAbsent(trailId) {
                    java.util.concurrent.atomic.AtomicInteger(0)
                }.incrementAndGet()

                val listeners = CopyOnWriteArrayList<(TrailDownloadProgress) -> Unit>()
                if (onProgress != null) {
                    listeners.add(onProgress)
                }
                val deferred = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + com.example.data.remote.OverpassClient.ioDispatcher
                ).async {
                    performSingleTrailDownload(
                        trailId = trailId,
                        trailName = trailName,
                        osmRelationId = osmRelationId,
                        listeners = listeners
                    )
                }
                val created = InFlightTrailDownload(
                    trailId = trailId,
                    deferred = deferred,
                    waiterCount = java.util.concurrent.atomic.AtomicInteger(1),
                    progressListeners = listeners
                )
                inFlightDownloads[trailId] = created
                inFlight = created
            }
        }

        if (joinedExisting && onProgress != null) {
            _globalDownloadProgress.value[trailId]?.let { currentProg ->
                onProgress.invoke(currentProg)
            }
        }

        try {
            inFlight.deferred.await()
        } finally {
            if (onProgress != null) {
                inFlight.progressListeners.remove(onProgress)
            }
            val remainingWaiters = inFlight.waiterCount.decrementAndGet()
            if (remainingWaiters <= 0) {
                synchronized(inFlightLock) {
                    inFlightDownloads.remove(trailId, inFlight)
                }
                if (!inFlight.deferred.isCompleted) {
                    inFlight.deferred.cancel(
                        kotlinx.coroutines.CancellationException(
                            com.example.util.AppLocaleHelper.str(context, com.example.R.string.planner_download_cancelled)
                        )
                    )
                }
                clearProgress(trailId)
            }
        }
    }

    private suspend fun performSingleTrailDownload(
        trailId: String,
        trailName: String,
        osmRelationId: Long?,
        listeners: CopyOnWriteArrayList<(TrailDownloadProgress) -> Unit>
    ): TrailGeometryDto? {
        var lastProgress: TrailDownloadProgress? = null
        var sectionStarted = false

        val emitProgressState: (TrailDownloadProgress) -> Unit = { p ->
            lastProgress = p
            updateProgress(p)
            for (listener in listeners) {
                runCatching { listener.invoke(p) }
            }
        }
        val emitProg: (Float, Int, Int, Double, String) -> Unit = { frac, dl, tot, km, msg ->
            emitProgressState(
                TrailDownloadProgress(
                    trailId = trailId,
                    fraction = frac.coerceIn(0f, 1f),
                    downloadedRelations = dl,
                    totalRelations = tot.coerceAtLeast(1),
                    accumulatedKm = km,
                    stageMessage = msg,
                    isIndeterminate = false,
                    queuePosition = 0
                )
            )
        }
        val onOverpassStatusChange: (com.example.data.remote.OverpassClient.RequestState) -> Unit = { state ->
            val prev = lastProgress
            if (sectionStarted && prev != null) {
                val x = prev.downloadedRelations
                val y = prev.totalRelations
                when (state) {
                    is com.example.data.remote.OverpassClient.RequestState.Queued -> {
                        emitProgressState(
                            prev.copy(
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_waiting_osm_queue_fmt, state.aheadCount),
                                isIndeterminate = false,
                                queuePosition = state.aheadCount
                            )
                        )
                    }
                    is com.example.data.remote.OverpassClient.RequestState.Running -> {
                        emitProgressState(
                            prev.copy(
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_section_fmt, x, y),
                                isIndeterminate = false,
                                queuePosition = 0
                            )
                        )
                    }
                    is com.example.data.remote.OverpassClient.RequestState.Slow -> {
                        emitProgressState(
                            prev.copy(
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_section_slow_fmt, x, y),
                                isIndeterminate = false,
                                queuePosition = 0
                            )
                        )
                    }
                }
            } else {
                when (state) {
                    is com.example.data.remote.OverpassClient.RequestState.Queued -> {
                        emitProgressState(
                            TrailDownloadProgress(
                                trailId = trailId,
                                fraction = 0f,
                                downloadedRelations = 0,
                                totalRelations = 1,
                                accumulatedKm = 0.0,
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_waiting_osm_queue_fmt, state.aheadCount),
                                isIndeterminate = true,
                                queuePosition = state.aheadCount
                            )
                        )
                    }
                    is com.example.data.remote.OverpassClient.RequestState.Running -> {
                        emitProgressState(
                            TrailDownloadProgress(
                                trailId = trailId,
                                fraction = 0f,
                                downloadedRelations = 0,
                                totalRelations = 1,
                                accumulatedKm = 0.0,
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_osm),
                                isIndeterminate = true,
                                queuePosition = 0
                            )
                        )
                    }
                    is com.example.data.remote.OverpassClient.RequestState.Slow -> {
                        emitProgressState(
                            TrailDownloadProgress(
                                trailId = trailId,
                                fraction = 0f,
                                downloadedRelations = 0,
                                totalRelations = 1,
                                accumulatedKm = 0.0,
                                stageMessage = com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_taking_longer),
                                isIndeterminate = true,
                                queuePosition = 0
                            )
                        )
                    }
                }
            }
        }

        val relationId = osmRelationId ?: findRelationByName(trailName, onOverpassStatusChange) ?: return null
        val rawElements = LinkedHashMap<Long, Map<String, Any>>()

        val customFetcher = customRelationFetcher
        if (customFetcher != null) {
            onOverpassStatusChange(com.example.data.remote.OverpassClient.RequestState.Running)
            val ok = customFetcher.invoke(relationId, rawElements) { dl, tot ->
                sectionStarted = true
                val frac = 0.1f + 0.55f * (dl.toFloat() / tot.coerceAtLeast(1).toFloat())
                emitProg(frac, dl, tot, 0.0, com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_section_fmt, dl, tot))
            }
            if (!ok || rawElements.isEmpty()) {
                Log.e("TrailGeometry", "Custom fetcher failed or returned empty elements for relation $relationId ($trailName)")
                return null
            }
        } else {
            val success = downloadTrailStructureAndBatches(
                rootRelationId = relationId,
                collected = rawElements,
                onStatusChange = onOverpassStatusChange,
                onBatchProgress = { batchNum, totalBatches ->
                    sectionStarted = true
                    val frac = 0.1f + 0.55f * (batchNum.toFloat() / totalBatches.coerceAtLeast(1).toFloat())
                    emitProg(frac, batchNum, totalBatches, 0.0, com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_section_fmt, batchNum, totalBatches))
                }
            )
            if (!success || rawElements.isEmpty()) {
                Log.e("TrailGeometry", "Download failed or returned no elements for relation $relationId ($trailName)")
                return null
            }
        }

        emitProg(0.70f, rawElements.size, rawElements.size.coerceAtLeast(1), 0.0, com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_assembling_geometry))
        val dto = processRelationData(trailId, relationId, rawElements) { frac, km, msg ->
            emitProg(frac, rawElements.size, rawElements.size.coerceAtLeast(1), km, msg)
        }

        if (dto.segments.none { it.points.isNotEmpty() }) {
            Log.e("TrailGeometry", "Processed trail $trailId ($trailName) has no geometry points; not saving")
            return null
        }

        saveGeometry(dto)

        emitProg(
            1.0f,
            rawElements.size,
            rawElements.size.coerceAtLeast(1),
            dto.totalWalkingKm,
            com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_finished_km_fmt, dto.totalWalkingKm.toString())
        )
        return dto
    }

    internal fun overpassRegexEscape(name: String): String {
        val sb = StringBuilder(name.length + 16)
        for (ch in name) {
            when (ch) {
                '\\', '.', '^', '$', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|' -> {
                    sb.append("\\\\").append(ch)
                }
                '"' -> {
                    sb.append("\\\"")
                }
                else -> {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    internal fun buildFindRelationQuery(escapedName: String, exact: Boolean): String {
        val pattern = if (exact) "^$escapedName$" else escapedName
        return """
            [out:json][timeout:25][maxsize:67108864];
            (
              relation["type"="route"]["route"~"^(hiking|foot|walking)$"]["name"~"$pattern",i](54.4,7.8,57.9,15.3);
              relation["type"="superroute"]["name"~"$pattern",i](54.4,7.8,57.9,15.3);
            );
            out tags bb;
        """.trimIndent()
    }

    internal suspend fun findRelationByName(
        name: String,
        onStatusChange: ((com.example.data.remote.OverpassClient.RequestState) -> Unit)? = null
    ): Long? {
        val cleanName = name.replace(Regex("\\s*\\(.*\\)"), "").trim()
        val isBornholmKyststi = cleanName.equals("Kyststien Bornholm", ignoreCase = true)
        val searchName = if (isBornholmKyststi) "Kyststien" else cleanName
        val escaped = overpassRegexEscape(searchName)

        fun filterCandidates(rawElements: List<Map<String, Any>>): List<Map<String, Any>> {
            return rawElements
                .filter { el ->
                    isChildRelationInsideDenmark(el) && run {
                        @Suppress("UNCHECKED_CAST")
                        val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                        val type = tags["type"]?.lowercase().orEmpty()
                        val route = tags["route"]?.lowercase().orEmpty()
                        type == "superroute" || route.isEmpty() || route == "hiking" || route == "foot" || route == "walking"
                    }
                }
                .let { candidates ->
                    if (isBornholmKyststi && candidates.size > 1) {
                        candidates.filter { el ->
                            @Suppress("UNCHECKED_CAST")
                            val bounds = el["bounds"] as? Map<String, Any>
                            val minLon = (bounds?.get("minlon") as? Number)?.toDouble() ?: 0.0
                            @Suppress("UNCHECKED_CAST")
                            val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                            minLon >= 14.5 || tags.values.any { it.contains("bornholm", ignoreCase = true) }
                        }.ifEmpty { candidates }
                    } else {
                        candidates
                    }
                }
        }

        return try {
            val query1 = buildFindRelationQuery(escaped, exact = true)
            val jsonStr1 = com.example.data.remote.OverpassClient.queryRawJson(
                query = query1,
                label = "FindRelation($cleanName)",
                priority = com.example.data.remote.OverpassClient.Priority.USER_INITIATED,
                onStatusChange = onStatusChange
            )
            val root1 = moshi.adapter(Map::class.java).fromJson(jsonStr1)
            @Suppress("UNCHECKED_CAST")
            val rawElements1 = root1?.get("elements") as? List<Map<String, Any>> ?: emptyList()
            var candidates = filterCandidates(rawElements1)

            if (candidates.isEmpty()) {
                val query2 = buildFindRelationQuery(escaped, exact = false)
                val jsonStr2 = com.example.data.remote.OverpassClient.queryRawJson(
                    query = query2,
                    label = "FindRelationPartial($cleanName)",
                    priority = com.example.data.remote.OverpassClient.Priority.USER_INITIATED,
                    onStatusChange = onStatusChange
                )
                val root2 = moshi.adapter(Map::class.java).fromJson(jsonStr2)
                @Suppress("UNCHECKED_CAST")
                val rawElements2 = root2?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                candidates = filterCandidates(rawElements2)
            }

            when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> (candidates[0]["id"] as? Number)?.toLong()
                else -> {
                    val superroutes = candidates.filter { el ->
                        @Suppress("UNCHECKED_CAST")
                        val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                        tags["type"]?.equals("superroute", ignoreCase = true) == true
                    }
                    if (superroutes.size == 1) {
                        (superroutes[0]["id"] as? Number)?.toLong()
                    } else {
                        val exactNameMatches = candidates.filter { el ->
                            @Suppress("UNCHECKED_CAST")
                            val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                            tags["name"]?.equals(searchName, ignoreCase = true) == true
                        }
                        if (exactNameMatches.size == 1) {
                            (exactNameMatches[0]["id"] as? Number)?.toLong()
                        } else {
                            val candidateIds = candidates.mapNotNull { (it["id"] as? Number)?.toLong() }
                            Log.w(
                                "TrailGeometry",
                                "Multiple relation matches (${candidates.size}) for \"$name\" (ids=$candidateIds). Returning null."
                            )
                            null
                        }
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("TrailGeometry", "Error searching relation for $name: ${e.message}")
            null
        }
    }

    internal fun buildStructureQuery(rootRelationId: Long): String = """
        [out:json][timeout:25][maxsize:67108864];
        relation($rootRelationId);
        (._; rel(r););
        (._; rel(r););
        (._; rel(r););
        out body bb;
    """.trimIndent()

    internal fun buildGeometryAndWayTagsBatchQuery(relationIds: List<Long>): String = """
        [out:json][timeout:60][maxsize:268435456];
        relation(id:${relationIds.joinToString(",")})->.r;
        .r out geom;
        way(r.r);
        out tags;
    """.trimIndent()

    private val legacyReplacedRelations: Map<Long, Pair<String, List<Long>>> = mapOf(
        4004055L to ("Hærvejen nordvest" to listOf(
            19250349L, 13278796L, 13278797L, 13278798L, 13278799L,
            13278800L, 19250426L, 13278801L, 13278802L, 19250444L
        )),
        4004054L to ("Hærvejen nordøst" to listOf(
            13278738L, 13278739L, 13278740L, 13278741L, 19249982L,
            13278679L, 19249981L
        )),
        4003809L to ("Hærvejen nord" to listOf(
            13278680L, 19252340L, 19252339L, 13278681L
        ))
    )

    private suspend fun executeWithOneRetry(
        label: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            Log.w("TrailGeometry", "Retrying $label once after failure")
            block()
        }
    }

    private suspend fun downloadTrailStructureAndBatches(
        rootRelationId: Long,
        collected: MutableMap<Long, Map<String, Any>>,
        onStatusChange: ((com.example.data.remote.OverpassClient.RequestState) -> Unit)? = null,
        onBatchProgress: ((batchNumber: Int, totalBatches: Int) -> Unit)? = null
    ): Boolean {
        return try {
            val structureQuery = buildStructureQuery(rootRelationId)
            val structureById = LinkedHashMap<Long, Map<String, Any>>()

            executeWithOneRetry("TrailStructure($rootRelationId)") {
                val jsonStr = com.example.data.remote.OverpassClient.queryRawJson(
                    query = structureQuery,
                    label = "TrailStructure($rootRelationId)",
                    priority = com.example.data.remote.OverpassClient.Priority.USER_INITIATED,
                    onStatusChange = onStatusChange
                )
                @Suppress("UNCHECKED_CAST")
                val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                    ?: throw java.io.IOException("Invalid structure JSON")
                @Suppress("UNCHECKED_CAST")
                val elements = root["elements"] as? List<Map<String, Any>>
                    ?: throw java.io.IOException("Missing elements in structure JSON")

                structureById.clear()
                for (el in elements) {
                    if (el["type"] == "relation") {
                        val id = (el["id"] as? Number)?.toLong() ?: continue
                        structureById[id] = el
                    }
                }
            }

            if (structureById.isEmpty()) {
                val legacyReplacement = legacyReplacedRelations[rootRelationId]
                if (legacyReplacement != null) {
                    val (legacyName, stageIds) = legacyReplacement
                    val stageQuery = """
                        [out:json][timeout:25][maxsize:67108864];
                        relation(id:${stageIds.joinToString(",")});
                        out body bb;
                    """.trimIndent()
                    executeWithOneRetry("TrailStructureStages($rootRelationId)") {
                        val jsonStr = com.example.data.remote.OverpassClient.queryRawJson(
                            query = stageQuery,
                            label = "TrailStructureStages($rootRelationId)",
                            priority = com.example.data.remote.OverpassClient.Priority.USER_INITIATED,
                            onStatusChange = onStatusChange
                        )
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                            ?: throw java.io.IOException("Invalid stage structure JSON")
                        @Suppress("UNCHECKED_CAST")
                        val elements = root["elements"] as? List<Map<String, Any>>
                            ?: throw java.io.IOException("Missing elements in stage structure JSON")
                        structureById.clear()
                        structureById[rootRelationId] = mapOf(
                            "type" to "relation",
                            "id" to rootRelationId,
                            "tags" to mapOf("name" to legacyName, "type" to "superroute", "route" to "hiking"),
                            "members" to stageIds.map { cid ->
                                mapOf("type" to "relation", "ref" to cid, "role" to "")
                            }
                        )
                        for (el in elements) {
                            if (el["type"] == "relation") {
                                val id = (el["id"] as? Number)?.toLong() ?: continue
                                structureById[id] = el
                            }
                        }
                    }
                }
            }

            val keptRelationIds = mutableListOf<Long>()
            val keptChildrenByParent = mutableMapOf<Long, List<Long>>()
            val visitedInTree = mutableSetOf<Long>()

            fun traverseStructure(relId: Long, isRoot: Boolean) {
                if (!visitedInTree.add(relId)) return
                val relEl = structureById[relId]
                if (!isRoot && relEl != null) {
                    if (!isChildRelationInsideDenmark(relEl) || !isLikelyDanishChildRelation(relEl)) {
                        return
                    }
                }
                keptRelationIds.add(relId)
                if (relEl == null) return
                @Suppress("UNCHECKED_CAST")
                val members = relEl["members"] as? List<Map<String, Any>> ?: emptyList()
                val keptChildren = mutableListOf<Long>()
                for (m in members) {
                    val mType = m["type"] as? String
                    val mRef = (m["ref"] as? Number)?.toLong()
                    if (mType == "relation" && mRef != null) {
                        val childEl = structureById[mRef]
                        val keepChild = if (childEl != null) {
                            isChildRelationInsideDenmark(childEl) && isLikelyDanishChildRelation(childEl)
                        } else {
                            false
                        }
                        if (keepChild) {
                            keptChildren.add(mRef)
                            traverseStructure(mRef, isRoot = false)
                        }
                    }
                }
                keptChildrenByParent[relId] = keptChildren
            }

            if (structureById.containsKey(rootRelationId)) {
                traverseStructure(rootRelationId, isRoot = true)
            } else {
                keptRelationIds.add(rootRelationId)
            }

            val keptSet = keptRelationIds.toSet()

            // Filter structure elements so rejected child relations and their subtrees are excluded
            val filteredStructureById = LinkedHashMap<Long, Map<String, Any>>()
            for (relId in keptRelationIds) {
                val structEl = structureById[relId] ?: continue
                val copyEl = structEl.toMutableMap()
                @Suppress("UNCHECKED_CAST")
                val members = structEl["members"] as? List<Map<String, Any>>
                if (members != null) {
                    copyEl["members"] = members.filter { m ->
                        val mType = m["type"] as? String
                        val mRef = (m["ref"] as? Number)?.toLong()
                        if (mType == "relation" && mRef != null) {
                            mRef in keptSet
                        } else {
                            true
                        }
                    }
                }
                filteredStructureById[relId] = copyEl
            }

            val batches = keptRelationIds.chunked(15)
            val totalBatches = batches.size.coerceAtLeast(1)
            val downloadedRelationsById = LinkedHashMap<Long, Map<String, Any>>()
            val allWayTags = LinkedHashMap<Long, Map<String, String>>()

            for ((batchIndex, batchIds) in batches.withIndex()) {
                val batchNumber = batchIndex + 1
                onBatchProgress?.invoke(batchNumber, totalBatches)
                val batchQuery = buildGeometryAndWayTagsBatchQuery(batchIds)
                val batchStatusHandler: (com.example.data.remote.OverpassClient.RequestState) -> Unit = { state ->
                    when (state) {
                        is com.example.data.remote.OverpassClient.RequestState.Queued -> onStatusChange?.invoke(state)
                        is com.example.data.remote.OverpassClient.RequestState.Slow -> onStatusChange?.invoke(state)
                        is com.example.data.remote.OverpassClient.RequestState.Running -> {
                            onBatchProgress?.invoke(batchNumber, totalBatches)
                        }
                    }
                }

                executeWithOneRetry("TrailBatch($batchNumber/$totalBatches)") {
                    val jsonStr = com.example.data.remote.OverpassClient.queryRawJson(
                        query = batchQuery,
                        label = "TrailBatch($batchNumber/$totalBatches)",
                        priority = com.example.data.remote.OverpassClient.Priority.USER_INITIATED,
                        onStatusChange = batchStatusHandler
                    )
                    @Suppress("UNCHECKED_CAST")
                    val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        ?: throw java.io.IOException("Invalid batch JSON")
                    @Suppress("UNCHECKED_CAST")
                    val elements = root["elements"] as? List<Map<String, Any>>
                        ?: throw java.io.IOException("Missing elements in batch JSON")

                    val batchRels = LinkedHashMap<Long, Map<String, Any>>()
                    val batchWayTags = LinkedHashMap<Long, Map<String, String>>()

                    for (el in elements) {
                        val id = (el["id"] as? Number)?.toLong() ?: continue
                        when (el["type"] as? String) {
                            "relation" -> {
                                val mutableRel = el.toMutableMap()
                                @Suppress("UNCHECKED_CAST")
                                val members = el["members"] as? List<Map<String, Any>>
                                if (members != null && structureById.isNotEmpty()) {
                                    mutableRel["members"] = members.filter { m ->
                                        val mType = m["type"] as? String
                                        val mRef = (m["ref"] as? Number)?.toLong()
                                        if (mType == "relation" && mRef != null) {
                                            mRef in keptSet
                                        } else {
                                            true
                                        }
                                    }
                                } else if (members == null && filteredStructureById[id]?.get("members") != null) {
                                    mutableRel["members"] = filteredStructureById[id]!!["members"]!!
                                }
                                batchRels[id] = mutableRel
                            }
                            "way" -> {
                                @Suppress("UNCHECKED_CAST")
                                val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                                batchWayTags[id] = tags
                            }
                        }
                    }

                    if (batchRels.isEmpty()) {
                        throw java.io.IOException("Batch $batchNumber/$totalBatches returned no relations")
                    }

                    // Ensure every leaf relation in the batch was returned
                    val expectedLeafIds = batchIds.filter { id ->
                        keptChildrenByParent[id].isNullOrEmpty()
                    }
                    if (expectedLeafIds.isNotEmpty() && batchRels.keys != setOf(rootRelationId) && !batchRels.keys.containsAll(expectedLeafIds)) {
                        val missing = expectedLeafIds - batchRels.keys
                        throw java.io.IOException("Batch $batchNumber/$totalBatches missing relations: $missing")
                    }

                    downloadedRelationsById.putAll(batchRels)
                    allWayTags.putAll(batchWayTags)
                }
            }

            // Populate collected in exact member/stage order from keptRelationIds
            collected.clear()
            for (relId in keptRelationIds) {
                val geomRel = downloadedRelationsById[relId]
                val structRel = filteredStructureById[relId]
                when {
                    geomRel != null && structRel != null -> {
                        val merged = structRel.toMutableMap()
                        merged.putAll(geomRel)
                        @Suppress("UNCHECKED_CAST")
                        val geomMembers = geomRel["members"] as? List<Map<String, Any>>
                        @Suppress("UNCHECKED_CAST")
                        val structMembers = structRel["members"] as? List<Map<String, Any>>
                        if (geomMembers.isNullOrEmpty() && !structMembers.isNullOrEmpty()) {
                            merged["members"] = structMembers
                        }
                        collected[relId] = merged
                    }
                    geomRel != null -> collected[relId] = geomRel
                    structRel != null -> collected[relId] = structRel
                }
            }
            for ((relId, relMap) in downloadedRelationsById) {
                if (!collected.containsKey(relId)) {
                    collected[relId] = relMap
                }
            }

            if (allWayTags.isNotEmpty()) {
                enrichRelationsWithWayTags(collected, allWayTags)
            }

            collected.isNotEmpty()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("TrailGeometry", "Trail download failed for relation $rootRelationId: ${e.message}")
            collected.clear()
            false
        }
    }

    private fun isChildRelationInsideDenmark(relElement: Map<String, Any>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val bounds = relElement["bounds"] as? Map<String, Any> ?: return true
        val minLat = (bounds["minlat"] as? Number)?.toDouble() ?: return true
        val maxLat = (bounds["maxlat"] as? Number)?.toDouble() ?: return true
        val minLon = (bounds["minlon"] as? Number)?.toDouble() ?: return true
        val maxLon = (bounds["maxlon"] as? Number)?.toDouble() ?: return true

        // Denmark bounding box: lat 54.40..58.10, lon 7.50..15.60
        val intersectsLat = maxLat >= 54.40 && minLat <= 58.10
        val intersectsLon = maxLon >= 7.50 && minLon <= 15.60
        return intersectsLat && intersectsLon
    }

    private fun enrichRelationsWithWayTags(
        collected: MutableMap<Long, Map<String, Any>>,
        wayTagsMap: Map<Long, Map<String, String>>
    ) {
        for ((relId, relMap) in collected.entries.toList()) {
            @Suppress("UNCHECKED_CAST")
            val members = relMap["members"] as? List<Map<String, Any>> ?: continue
            val enrichedMembers = members.map { m ->
                val mType = m["type"] as? String
                val mRef = (m["ref"] as? Number)?.toLong()
                if (mType == "way" && mRef != null) {
                    val wTags = wayTagsMap[mRef]
                    if (wTags != null) {
                        val mCopy = m.toMutableMap()
                        mCopy["tags"] = wTags
                        mCopy
                    } else m
                } else m
            }
            val updatedRel = relMap.toMutableMap()
            updatedRel["members"] = enrichedMembers
            collected[relId] = updatedRel
        }
    }

    /**
     * Look up elevation for both ends of a STRAIGHT leg with Open-Meteo;
     * if that fails, return null.
     */
    suspend fun fetchEndpointElevations(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        val customFetcher = customElevationFetcher
        if (customFetcher != null) {
            return@withContext try {
                val res = customFetcher.invoke(listOf(fromLat to fromLng, toLat to toLng))
                if (res != null && res.size >= 2) {
                    Pair(res[0], res[1])
                } else {
                    Pair(null, null)
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Custom endpoint elevation lookup failed: ${e.message}")
                Pair(null, null)
            }
        }
        val url = "https://api.open-meteo.com/v1/elevation?latitude=$fromLat,$toLat&longitude=$fromLng,$toLng"
        try {
            val request = Request.Builder().url(url).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (!jsonStr.isNullOrEmpty()) {
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                        @Suppress("UNCHECKED_CAST")
                        val eleList = root?.get("elevation") as? List<Number?>
                        if (eleList != null && eleList.size >= 2) {
                            return@withContext Pair(eleList[0]?.toDouble(), eleList[1]?.toDouble())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TrailGeometry", "STRAIGHT leg endpoint elevation lookup failed: ${e.message}")
        }
        Pair(null, null)
    }

    private fun isLikelyDanishChildRelation(rel: Map<String, Any>?): Boolean {
        if (rel == null) return true
        @Suppress("UNCHECKED_CAST")
        val tags = rel["tags"] as? Map<String, String> ?: return true
        val country = (tags["country"] ?: tags["ISO3166-1"] ?: tags["addr:country"] ?: "").uppercase()
        if (country.isNotEmpty()) {
            return country.contains("DK") || country.contains("DENMARK") || country.contains("DANMARK")
        }
        val combinedText = listOfNotNull(
            tags["name"],
            tags["name:da"],
            tags["name:en"],
            tags["description"],
            tags["from"],
            tags["to"],
            tags["section"],
            tags["operator"]
        ).joinToString(" ").lowercase()
        if (combinedText.isBlank()) return true

        val foreignMarkers = listOf(
            "deutschland", "germany", "sverige", "sweden", "norge", "norway",
            "italia", "italy", "schweiz", "switzerland", "österreich", "austria",
            "slovenija", "greece", "suomi", "finland", "schleswig-holstein",
            "niedersachsen", "hessen", "baden-württemberg", "bayern", "skåne",
            "halland", "bohuslän", "flensburg -", "hamburg", "frankfurt"
        )
        val danishMarkers = listOf(
            "danmark", "denmark", "dansk", "jylland", "sjælland", "fyn",
            "hærvejen", "gendarmstien", "mols", "kruså", "padborg", "grænse",
            "viborg", "silkeborg", "aarhus", "århus", "grenaa", "frederikshavn",
            "hirtshals", "skagen", "københavn", "helsingør", "korsør", "nyborg",
            "svendborg", "sønderborg", "odense", "roskilde", "køge"
        )
        if (danishMarkers.any { combinedText.contains(it) }) return true
        if (foreignMarkers.any { combinedText.contains(it) }) return false
        return true
    }

    private fun collectWayMembersRecursive(
        relId: Long,
        elements: Map<Long, Map<String, Any>>,
        visited: MutableSet<Long>,
        outWays: MutableList<Map<String, Any>>
    ) {
        if (!visited.add(relId)) return
        val rel = elements[relId] ?: return
        @Suppress("UNCHECKED_CAST")
        val members = rel["members"] as? List<Map<String, Any>> ?: emptyList()
        for (m in members) {
            val mType = m["type"] as? String
            val mRef = (m["ref"] as? Number)?.toLong()
            if (mType == "way") {
                outWays.add(m)
            } else if (mType == "relation" && mRef != null) {
                collectWayMembersRecursive(mRef, elements, visited, outWays)
            }
        }
    }

    private fun isPointInDenmarkBbox(lat: Double, lng: Double): Boolean {
        return lat in 54.40..58.10 && lng in 7.50..15.60
    }

    private fun processRelationData(
        trailId: String,
        rootRelationId: Long,
        elements: Map<Long, Map<String, Any>>,
        onProgress: ((fraction: Float, accumulatedKm: Double, message: String) -> Unit)? = null
    ): TrailGeometryDto {
        val rootEl = elements[rootRelationId]
        @Suppress("UNCHECKED_CAST")
        val rootTags = rootEl?.get("tags") as? Map<String, String> ?: emptyMap()
        val osmRelationName = rootTags["name"]

        val orderedMembers = mutableListOf<Map<String, Any>>()
        collectWayMembersRecursive(
            relId = rootRelationId,
            elements = elements,
            visited = mutableSetOf(),
            outWays = orderedMembers
        )

        val rawWalkWays = mutableListOf<WayData>()
        val rawSideBranchWays = mutableListOf<WayData>()

        for (m in orderedMembers) {
            val mType = m["type"] as? String
            if (mType != "way") continue
            @Suppress("UNCHECKED_CAST")
            val geom = m["geometry"] as? List<Map<String, Double>> ?: continue
            if (geom.size < 2) continue

            val pts = geom.map { GeoPointDto(lat = it["lat"] ?: 0.0, lng = it["lon"] ?: 0.0) }
            val role = m["role"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val tags = m["tags"] as? Map<String, String> ?: emptyMap()

            val isFerry = (role == "ferry") || (tags["route"] == "ferry") || (tags["highway"] == "ferry")
            val segType = if (isFerry) TrailSegmentType.FERRY else TrailSegmentType.WALK

            val wayData = WayData(
                type = segType,
                points = pts,
                role = role,
                highway = tags["highway"],
                surface = tags["surface"],
                pathTags = com.example.util.PathTags.filterPathTags(tags)
            )
            if (role == "alternative" || role == "excursion") {
                rawSideBranchWays.add(wayData)
            } else {
                rawWalkWays.add(wayData)
            }
        }

        // If an international relation (e.g. E1, E6) contains both Danish and non-Danish ways,
        // keep the Danish ways so the Danish route geometry and region are accurate.
        val hasDanishWalkWays = rawWalkWays.any { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        val walkWays = if (hasDanishWalkWays) {
            rawWalkWays.filter { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        } else {
            rawWalkWays
        }
        val sideBranchWays = if (hasDanishWalkWays) {
            rawSideBranchWays.filter { w -> w.points.any { isPointInDenmarkBbox(it.lat, it.lng) } }
        } else {
            rawSideBranchWays
        }

        val (mainSegments, gaps) = stitchWays(walkWays)
        val (sideSegments, _) = stitchWays(sideBranchWays)

        // Simplify with Douglas-Peucker at 4m and scale wayTagRanges proportionally if total segment length changes
        val simplifiedMain = mainSegments.map { seg ->
            val simpPts = com.example.util.DouglasPeucker.simplify(seg.points, 4.0).toMutableList()
            val oldKm = seg.wayTagRanges.lastOrNull()?.toKm ?: 0.0
            val newKm = calculateSegmentKm(simpPts)
            val scale = if (oldKm > 1e-6 && newKm > 0.0) newKm / oldKm else 1.0
            val scaledRanges = seg.wayTagRanges.map { r ->
                r.copy(fromKm = r.fromKm * scale, toKm = r.toKm * scale)
            }
            InternalSegment(seg.type, simpPts, seg.highway, seg.surface, scaledRanges)
        }
        val simplifiedSide = sideSegments.map { seg ->
            val simpPts = com.example.util.DouglasPeucker.simplify(seg.points, 4.0).toMutableList()
            val oldKm = seg.wayTagRanges.lastOrNull()?.toKm ?: 0.0
            val newKm = calculateSegmentKm(simpPts)
            val scale = if (oldKm > 1e-6 && newKm > 0.0) newKm / oldKm else 1.0
            val scaledRanges = seg.wayTagRanges.map { r ->
                r.copy(fromKm = r.fromKm * scale, toKm = r.toKm * scale)
            }
            InternalSegment(seg.type, simpPts, seg.highway, seg.surface, scaledRanges)
        }

        val prelimTotalKm = simplifiedMain
            .filter { it.type == TrailSegmentType.WALK }
            .sumOf { calculateSegmentKm(it.points) }
        onProgress?.invoke(
            0.82f,
            roundTo1Decimal(prelimTotalKm),
            com.example.util.AppLocaleHelper.str(context, com.example.R.string.trail_dl_fetching_elevation_km_fmt, roundTo1Decimal(prelimTotalKm).toString())
        )

        // Keep Douglas-Peucker (4m) points as route geometry; resample for elevation lookup
        val finalSegments = applyElevationToSegments(simplifiedMain)
        val finalSideBranches = applyElevationToSegments(simplifiedSide)

        var totalWalkingKm = 0.0
        var totalAscent = 0.0
        var totalDescent = 0.0
        var hasElevationData = false
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (seg in finalSegments) {
            if (seg.type == TrailSegmentType.WALK) {
                totalWalkingKm += seg.lengthKm
                val pts = seg.points
                for (i in 0 until pts.size - 1) {
                    val e1 = pts[i].ele
                    val e2 = pts[i + 1].ele
                    if (e1 != null && e2 != null) {
                        hasElevationData = true
                        val diff = e2 - e1
                        if (diff > 3.0) {
                            totalAscent += diff
                        } else if (diff < -3.0) {
                            totalDescent += -diff
                        }
                    }
                }
            }
            for (p in seg.points) {
                minLat = min(minLat, p.lat)
                maxLat = max(maxLat, p.lat)
                minLng = min(minLng, p.lng)
                maxLng = max(maxLng, p.lng)
            }
        }

        val gapsOver200mCount = gaps.count { it.gapMeters > 200.0 }
        val osmTimestamp = (rootEl?.get("timestamp") as? String)

        return TrailGeometryDto(
            trailId = trailId,
            osmRelationId = rootRelationId,
            segments = finalSegments,
            sideBranches = finalSideBranches,
            gaps = gaps,
            totalWalkingKm = roundTo1Decimal(totalWalkingKm),
            totalAscentM = if (hasElevationData) totalAscent.toInt() else null,
            totalDescentM = if (hasElevationData) totalDescent.toInt() else null,
            gapsOver200mCount = gapsOver200mCount,
            bboxNorth = if (maxLat > -90.0) maxLat else 0.0,
            bboxSouth = if (minLat < 90.0) minLat else 0.0,
            bboxEast = if (maxLng > -180.0) maxLng else 0.0,
            bboxWest = if (minLng < 180.0) minLng else 0.0,
            osmTimestamp = osmTimestamp,
            osmRelationName = osmRelationName,
            osmTags = rootTags,
            pathTagsVersion = 1
        )
    }

    private data class WayData(
        val type: TrailSegmentType,
        val points: List<GeoPointDto>,
        val role: String,
        val highway: String? = null,
        val surface: String? = null,
        val pathTags: Map<String, String>? = null
    )

    private data class InternalSegment(
        val type: TrailSegmentType,
        val points: MutableList<GeoPointDto>,
        val highway: String? = null,
        val surface: String? = null,
        val wayTagRanges: List<com.example.data.model.WayTagRangeDto> = emptyList()
    )

    private fun stitchWays(ways: List<WayData>): Pair<List<InternalSegment>, List<TrailGapDto>> {
        val segments = mutableListOf<InternalSegment>()
        val gaps = mutableListOf<TrailGapDto>()
        var curPts = mutableListOf<GeoPointDto>()
        var curType = TrailSegmentType.WALK
        var curHighway: String? = null
        var curSurface: String? = null
        var curRanges = mutableListOf<com.example.data.model.WayTagRangeDto>()
        var curKm = 0.0

        for (w in ways) {
            if (curPts.isEmpty()) {
                val wKm = calculateSegmentKm(w.points)
                curPts.addAll(w.points)
                curType = w.type
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface,
                        pathTags = w.pathTags
                    )
                )
                curKm = wKm
                continue
            }

            if (w.type != curType) {
                segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
                val wKm = calculateSegmentKm(w.points)
                curPts = w.points.toMutableList()
                curType = w.type
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface,
                        pathTags = w.pathTags
                    )
                )
                curKm = wKm
                continue
            }

            val last = curPts.last()
            val firstP = w.points.first()
            val lastP = w.points.last()

            val dNorm = distanceMeters(last, firstP)
            val dFlip = distanceMeters(last, lastP)

            val oriented = if (dFlip < dNorm) w.points.reversed() else w.points
            val gap = min(dNorm, dFlip)

            if (gap > 30.0) {
                segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
                gaps.add(TrailGapDto(fromPoint = last, toPoint = oriented.first(), gapMeters = gap))
                val wKm = calculateSegmentKm(oriented)
                curPts = oriented.toMutableList()
                curHighway = w.highway
                curSurface = w.surface
                curRanges = mutableListOf(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = 0.0,
                        toKm = wKm,
                        highway = w.highway,
                        surface = w.surface,
                        pathTags = w.pathTags
                    )
                )
                curKm = wKm
            } else {
                if (curHighway == null && w.highway != null) curHighway = w.highway
                if (curSurface == null && w.surface != null) curSurface = w.surface
                val addedPts = if (gap < 1.0) oriented.drop(1) else oriented
                var addedMeters = 0.0
                var prev = last
                for (p in addedPts) {
                    addedMeters += distanceMeters(prev, p)
                    prev = p
                }
                curPts.addAll(addedPts)
                val startKm = curKm
                val endKm = startKm + (addedMeters / 1000.0)
                curRanges.add(
                    com.example.data.model.WayTagRangeDto(
                        fromKm = startKm,
                        toKm = endKm,
                        highway = w.highway,
                        surface = w.surface,
                        pathTags = w.pathTags
                    )
                )
                curKm = endKm
            }
        }

        if (curPts.isNotEmpty()) {
            segments.add(InternalSegment(curType, curPts, curHighway, curSurface, curRanges))
        }

        return Pair(segments, gaps)
    }

    /**
     * Preserves Douglas-Peucker (4m) points as the real route geometry.
     * Uses 100m resampling ONLY to query elevation from Open-Meteo,
     * then interpolates elevation onto the real points.
     * If elevation lookup fails, stores null and never pads with a value.
     */
    private fun applyElevationToSegments(segments: List<InternalSegment>): List<TrailSegmentDto> {
        if (segments.isEmpty()) return emptyList()
        val resultSegments = MutableList<TrailSegmentDto?>(segments.size) { null }

        val totalWalkKm = segments
            .filter { it.type != TrailSegmentType.FERRY && it.points.size >= 2 }
            .sumOf { calculateSegmentKm(it.points) }
        val targetInterval = max(100.0, (totalWalkKm * 1000.0) / 250.0)

        data class PendingSegmentElevation(
            val index: Int,
            val seg: InternalSegment,
            val km: Double,
            val resampled: List<GeoPointDto>,
            val resampledDistances: List<Double>,
            val startOffset: Int
        )

        val pending = mutableListOf<PendingSegmentElevation>()
        val allResampled = mutableListOf<GeoPointDto>()

        for ((index, seg) in segments.withIndex()) {
            val realPts = seg.points
            if (realPts.isEmpty()) continue
            val km = calculateSegmentKm(realPts)

            if (seg.type == TrailSegmentType.FERRY || realPts.size < 2) {
                resultSegments[index] = TrailSegmentDto(
                    type = seg.type,
                    points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                    lengthKm = km,
                    highway = seg.highway,
                    surface = seg.surface,
                    wayTagRanges = seg.wayTagRanges
                )
                continue
            }

            if (customRelationFetcher != null) {
                resultSegments[index] = TrailSegmentDto(
                    type = seg.type,
                    points = realPts.map { GeoPointDto(it.lat, it.lng, it.ele) },
                    lengthKm = km,
                    highway = seg.highway,
                    surface = seg.surface,
                    wayTagRanges = seg.wayTagRanges
                )
                continue
            }

            val resampled = mutableListOf<GeoPointDto>()
            val resampledDistances = mutableListOf<Double>()
            resampled.add(realPts.first())
            resampledDistances.add(0.0)

            var distFromLastSample = 0.0
            var totalDist = 0.0

            for (i in 0 until realPts.size - 1) {
                val p1 = realPts[i]
                val p2 = realPts[i + 1]
                val segLen = distanceMeters(p1, p2)
                if (segLen <= 0.0) continue

                var segPos = 0.0
                while (distFromLastSample + (segLen - segPos) >= targetInterval) {
                    val needed = targetInterval - distFromLastSample
                    segPos += needed
                    val ratio = (segPos / segLen).coerceIn(0.0, 1.0)
                    val interpLat = p1.lat + ratio * (p2.lat - p1.lat)
                    val interpLng = p1.lng + ratio * (p2.lng - p1.lng)
                    totalDist += needed
                    resampled.add(GeoPointDto(interpLat, interpLng, null))
                    resampledDistances.add(totalDist)
                    distFromLastSample = 0.0
                }
                val remaining = segLen - segPos
                distFromLastSample += remaining
                totalDist += remaining
            }

            if (resampled.size < 2 || resampled.last() != realPts.last()) {
                resampled.add(realPts.last())
                resampledDistances.add(totalDist)
            }

            val startOffset = allResampled.size
            allResampled.addAll(resampled)
            pending.add(
                PendingSegmentElevation(
                    index = index,
                    seg = seg,
                    km = km,
                    resampled = resampled,
                    resampledDistances = resampledDistances,
                    startOffset = startOffset
                )
            )
        }

        if (pending.isNotEmpty() && allResampled.isNotEmpty()) {
            val allElevations = mutableListOf<Double>()
            var lookupFailed = false

            for (chunk in allResampled.chunked(100)) {
                val customFetcher = customElevationFetcher
                if (customFetcher != null) {
                    try {
                        val res = customFetcher.invoke(chunk.map { it.lat to it.lng })
                        if (res != null && res.size == chunk.size && res.all { it != null }) {
                            for (e in res) {
                                allElevations.add(e!!)
                            }
                        } else {
                            lookupFailed = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.w("TrailGeometry", "Custom elevation lookup failed: ${e.message}")
                        lookupFailed = true
                        break
                    }
                    continue
                }
                val lats = chunk.joinToString(",") { it.lat.toString() }
                val lons = chunk.joinToString(",") { it.lng.toString() }
                val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
                try {
                    val request = Request.Builder().url(url).header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string()
                            if (!jsonStr.isNullOrEmpty()) {
                                val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                                @Suppress("UNCHECKED_CAST")
                                val eleList = root?.get("elevation") as? List<Number>
                                if (eleList != null && eleList.size == chunk.size) {
                                    for (e in eleList) {
                                        allElevations.add(e.toDouble())
                                    }
                                } else {
                                    lookupFailed = true
                                }
                            } else {
                                lookupFailed = true
                            }
                        } else {
                            lookupFailed = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TrailGeometry", "Elevation fetch failed: ${e.message}")
                    lookupFailed = true
                }
                if (lookupFailed) break
            }

            val hasAllElevations = !lookupFailed && allElevations.size == allResampled.size
            for (item in pending) {
                val realPts = item.seg.points
                if (!hasAllElevations) {
                    resultSegments[item.index] = TrailSegmentDto(
                        type = item.seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                        lengthKm = item.km,
                        highway = item.seg.highway,
                        surface = item.seg.surface,
                        wayTagRanges = item.seg.wayTagRanges
                    )
                    continue
                }

                val elevations = allElevations.subList(item.startOffset, item.startOffset + item.resampled.size)
                val smoothedElevations = mutableListOf<Double>()
                val windowSize = 5
                for (i in elevations.indices) {
                    val start = max(0, i - windowSize / 2)
                    val end = min(elevations.size - 1, i + windowSize / 2)
                    val avg = elevations.subList(start, end + 1).average()
                    smoothedElevations.add(avg)
                }

                val realDistances = DoubleArray(realPts.size)
                var curDist = 0.0
                realDistances[0] = 0.0
                for (i in 0 until realPts.size - 1) {
                    curDist += distanceMeters(realPts[i], realPts[i + 1])
                    realDistances[i + 1] = curDist
                }

                var rIdx = 0
                val resampledDistances = item.resampledDistances
                val finalRealPoints = realPts.mapIndexed { idx, pt ->
                    val d = realDistances[idx]
                    while (rIdx < resampledDistances.size - 2 && resampledDistances[rIdx + 1] < d) {
                        rIdx++
                    }
                    val d0 = resampledDistances[rIdx]
                    val d1 = resampledDistances[rIdx + 1]
                    val span = d1 - d0
                    val ratio = if (span > 0.0) ((d - d0) / span).coerceIn(0.0, 1.0) else 0.0
                    val ele = if (smoothedElevations.isNotEmpty()) {
                        val e0 = smoothedElevations.getOrElse(rIdx) { smoothedElevations.last() }
                        val e1 = smoothedElevations.getOrElse(rIdx + 1) { e0 }
                        e0 + ratio * (e1 - e0)
                    } else null
                    GeoPointDto(pt.lat, pt.lng, ele)
                }

                resultSegments[item.index] = TrailSegmentDto(
                    type = item.seg.type,
                    points = finalRealPoints,
                    lengthKm = item.km,
                    highway = item.seg.highway,
                    surface = item.seg.surface,
                    wayTagRanges = item.seg.wayTagRanges
                )
            }
        }

        return resultSegments.filterNotNull()
    }

    private fun distanceMeters(p1: GeoPointDto, p2: GeoPointDto): Double {
        return distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    private fun calculateSegmentKm(points: List<GeoPointDto>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total / 1000.0
    }

    private fun roundTo1Decimal(value: Double): Double {
        return Math.round(value * 10.0) / 10.0
    }
}
