package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.DownloadedTrailEntity
import com.example.util.DanskStiTileSource
import com.example.util.OfflineDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class OfflineDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val settingsRepo = DanskStiApp.getSettingsRepository(context)
        createNotificationChannel(context, notificationManager)
        try {
            setForeground(createForegroundInfo(context, inputData.getString(KEY_TRAIL_NAME) ?: "Vandrerute", 0, 100))
        } catch (_: Exception) {}
        return executeDownload(
            context = context,
            trailId = inputData.getString(KEY_TRAIL_ID),
            trailName = inputData.getString(KEY_TRAIL_NAME) ?: "Vandrerute",
            corridorKm = inputData.getDouble(KEY_CORRIDOR_KM, 1.0),
            wifiOnly = inputData.getBoolean(
                KEY_WIFI_ONLY,
                settingsRepo.currentSettingsSnapshot().offlineDownloadsWifiOnly
            ),
            includeAerial = inputData.getBoolean(KEY_INCLUDE_AERIAL, false),
            includeTerrain = inputData.getBoolean(KEY_INCLUDE_TERRAIN, false),
            areaMinLat = if (inputData.keyValueMap.containsKey(KEY_AREA_MIN_LAT)) inputData.getDouble(KEY_AREA_MIN_LAT, 0.0) else null,
            areaMaxLat = if (inputData.keyValueMap.containsKey(KEY_AREA_MAX_LAT)) inputData.getDouble(KEY_AREA_MAX_LAT, 0.0) else null,
            areaMinLng = if (inputData.keyValueMap.containsKey(KEY_AREA_MIN_LNG)) inputData.getDouble(KEY_AREA_MIN_LNG, 0.0) else null,
            areaMaxLng = if (inputData.keyValueMap.containsKey(KEY_AREA_MAX_LNG)) inputData.getDouble(KEY_AREA_MAX_LNG, 0.0) else null,
            isStoppedProvider = { isStopped },
            onSetProgress = { data ->
                try {
                    setProgress(data)
                } catch (_: Exception) {}
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "dansk_sti_offline_downloads"
        const val NOTIFICATION_ID = 2001

        const val KEY_TRAIL_ID = "trail_id"
        const val KEY_TRAIL_NAME = "trail_name"
        const val KEY_CORRIDOR_KM = "corridor_km"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_INCLUDE_AERIAL = "include_aerial"
        const val KEY_INCLUDE_TERRAIN = "include_terrain"
        const val KEY_AREA_MIN_LAT = "area_min_lat"
        const val KEY_AREA_MAX_LAT = "area_max_lat"
        const val KEY_AREA_MIN_LNG = "area_min_lng"
        const val KEY_AREA_MAX_LNG = "area_max_lng"

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_COUNT = "downloaded_count"
        const val KEY_NEWLY_FETCHED_COUNT = "newly_fetched_count"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_REQUIRED_BYTES = "required_bytes"

        @Volatile
        var customTileFetcher: ((providerName: String, tileIndex: Long) -> ByteArray?)? = null

        @Volatile
        var shouldInterruptAfterTileCount: Int? = null

        internal fun fetchTileBytesFromUrl(
            tileUrlStr: String,
            buffer: ByteArray = ByteArray(8192),
            connectionFactory: (URL) -> HttpURLConnection = { u -> u.openConnection() as HttpURLConnection }
        ): ByteArray? {
            var conn: HttpURLConnection? = null
            return try {
                val url = URL(tileUrlStr)
                conn = connectionFactory(url).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                }

                val code = conn.responseCode
                val contentType = conn.contentType?.trim()?.lowercase(java.util.Locale.ROOT)
                if (code == HttpURLConnection.HTTP_OK) {
                    if (contentType != null && contentType.startsWith("image/")) {
                        val stream: InputStream = conn.inputStream
                        val out = ByteArrayOutputStream()
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        out.toByteArray()
                    } else {
                        null
                    }
                } else if (code >= 500 || code == HttpURLConnection.HTTP_CLIENT_TIMEOUT || code == 429) {
                    throw java.io.IOException("Tile server error HTTP $code")
                } else {
                    null
                }
            } finally {
                conn?.disconnect()
            }
        }

        internal suspend fun executeDownload(
            context: Context,
            trailId: String?,
            trailName: String = "Vandrerute",
            corridorKm: Double = 1.0,
            wifiOnly: Boolean = false,
            includeAerial: Boolean = false,
            includeTerrain: Boolean = false,
            areaMinLat: Double? = null,
            areaMaxLat: Double? = null,
            areaMinLng: Double? = null,
            areaMaxLng: Double? = null,
            isStoppedProvider: () -> Boolean = { false },
            onSetProgress: suspend (androidx.work.Data) -> Unit = {},
            connectionFactory: ((URL) -> HttpURLConnection)? = null
        ): Result = withContext(Dispatchers.IO) {
            if (trailId.isNullOrBlank()) return@withContext Result.failure()

            val (netOk, _) = OfflineDownloadManager.checkNetworkRequirement(context, wifiOnly)
            if (!netOk) {
                return@withContext Result.retry()
            }

            val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(context)
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(localizedCtx, notificationManager)

            val app = context.applicationContext as DanskStiApp
            val repo = app.repository
            val db = app.database
            val settingsRepo = DanskStiApp.getSettingsRepository(context)
            val downloadType = OfflineDownloadManager.inferDownloadType(trailId)

            runCatching {
                val existingInProgress = db.dao().getOfflineDownloadInProgress(trailId)
                db.dao().upsertOfflineDownloadInProgress(
                    com.example.data.model.OfflineDownloadInProgressEntity(
                        trailId = trailId,
                        startedAt = existingInProgress?.startedAt ?: System.currentTimeMillis(),
                        downloadType = downloadType,
                        includeAerial = includeAerial,
                        includeTerrain = includeTerrain,
                        minLat = areaMinLat ?: existingInProgress?.minLat,
                        maxLat = areaMaxLat ?: existingInProgress?.maxLat,
                        minLng = areaMinLng ?: existingInProgress?.minLng,
                        maxLng = areaMaxLng ?: existingInProgress?.maxLng
                    )
                )
            }

            var archiveWriter: SqliteArchiveTileWriter? = null
            try {
                var points: List<com.example.data.model.GeoPointDto> = emptyList()
                var geometryHash: String? = null
                var resolvedAreaBounds: DoubleArray? = null

                when (downloadType) {
                    "AREA" -> {
                        if (areaMinLat != null && areaMaxLat != null && areaMinLng != null && areaMaxLng != null) {
                            resolvedAreaBounds = doubleArrayOf(areaMinLat, areaMaxLat, areaMinLng, areaMaxLng)
                        } else {
                            val existingBbox = db.dao().getOfflineDownloadInProgress(trailId)?.bboxJson
                                ?: db.dao().getDownloadedTrail(trailId)?.bboxJson
                            if (!existingBbox.isNullOrBlank()) {
                                val obj = org.json.JSONObject(existingBbox)
                                resolvedAreaBounds = doubleArrayOf(
                                    obj.getDouble("minLat"),
                                    obj.getDouble("maxLat"),
                                    obj.getDouble("minLng"),
                                    obj.getDouble("maxLng")
                                )
                            }
                        }
                        if (resolvedAreaBounds == null) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_area_coords_missing_fmt, trailName))
                            )
                        }
                    }

                    "ROUTE" -> {
                        val customRouteId = trailId.removePrefix("route:").removePrefix("route_").toLongOrNull()
                        val customRoute = if (customRouteId != null) repo.getCustomRoute(customRouteId) else null
                        if (customRoute == null) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_route_not_found_fmt, trailName))
                            )
                        }
                        val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, customRoute.geometryFile)
                        val waypoints = com.example.util.routing.RouteWaypoint.listFromJson(customRoute.waypointsJson)
                        val orderedPoints = mutableListOf<com.example.data.model.GeoPointDto>()
                        for (i in 0 until waypoints.size - 1) {
                            val key = "${waypoints[i].id}_to_${waypoints[i + 1].id}"
                            legs[key]?.points?.forEach { pt ->
                                orderedPoints.add(com.example.data.model.GeoPointDto(pt.lat, pt.lng, pt.ele))
                            }
                        }
                        if (orderedPoints.isEmpty()) {
                            legs.values.flatMap { it.points }.forEach { pt ->
                                orderedPoints.add(com.example.data.model.GeoPointDto(pt.lat, pt.lng, pt.ele))
                            }
                        }
                        if (orderedPoints.isEmpty()) {
                            waypoints.forEach { wp ->
                                orderedPoints.add(com.example.data.model.GeoPointDto(wp.lat, wp.lng))
                            }
                        }
                        if (orderedPoints.isEmpty()) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_no_route_points_fmt, trailName))
                            )
                        }
                        points = orderedPoints
                        geometryHash = OfflineDownloadManager.computeGeometryHash(points)
                        try {
                            val syncRes = repo.syncCorridorPoisForPolyline(trailId, points)
                            if (syncRes.isSuccess) {
                                repo.setCorridorPoiVersion(trailId, com.example.data.repository.DanskStiRepository.CORRIDOR_POI_VERSION, isRoute = true)
                            }
                        } catch (_: Exception) {}
                    }

                    "PLAN" -> {
                        val planId = trailId.removePrefix("plan:").removePrefix("plan_").toLongOrNull()
                        val plan = if (planId != null) repo.getTripPlanById(planId) else null
                        if (plan == null) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_plan_not_found_fmt, trailName))
                            )
                        }
                        val plannedRoute = runCatching {
                            com.example.data.repository.PlannedRouteRepository(context).loadPlannedRoute(plan.id)
                        }.getOrNull()
                        val orderedPoints = mutableListOf<com.example.data.model.GeoPointDto>()
                        if (plannedRoute != null && plannedRoute.points.isNotEmpty()) {
                            plannedRoute.points.forEach { pt ->
                                orderedPoints.add(com.example.data.model.GeoPointDto(pt.lat, pt.lng, pt.ele))
                            }
                        } else if (plan.customRouteId != null && plan.customRouteId > 0L) {
                            val customRoute = repo.getCustomRoute(plan.customRouteId)
                            if (customRoute != null) {
                                val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, customRoute.geometryFile)
                                legs.values.flatMap { it.points }.forEach { pt ->
                                    orderedPoints.add(com.example.data.model.GeoPointDto(pt.lat, pt.lng, pt.ele))
                                }
                            }
                        } else {
                            val trail = repo.getTrailById(plan.trailId)
                            if (trail != null) {
                                orderedPoints.addAll(repo.getTrailPoints(trail).flatMap { it.points })
                            }
                        }
                        if (orderedPoints.isEmpty()) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_no_route_points_fmt, trailName))
                            )
                        }
                        points = orderedPoints
                        geometryHash = OfflineDownloadManager.computeGeometryHash(points)
                        try {
                            val syncRes = repo.syncCorridorPoisForPolyline(trailId, points)
                            if (syncRes.isSuccess) {
                                repo.setCorridorPoiVersion(trailId, com.example.data.repository.DanskStiRepository.CORRIDOR_POI_VERSION, isRoute = true)
                            }
                        } catch (_: Exception) {}
                    }

                    else -> {
                        // TRAIL
                        val trail = repo.getTrailById(trailId)
                        if (trail == null) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_route_not_found_fmt, trailName))
                            )
                        }
                        val wasGeometryCached = repo.geometryRepository.isGeometryCached(trailId)
                        points = repo.getTrailPoints(trail).flatMap { it.points }
                        if (points.isEmpty()) {
                            if (!wasGeometryCached && !repo.geometryRepository.isGeometryCached(trailId)) {
                                return@withContext Result.retry()
                            }
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf("error" to localizedCtx.getString(R.string.offline_error_no_route_points_fmt, trailName))
                            )
                        }
                        geometryHash = OfflineDownloadManager.computeGeometryHash(points)
                        try {
                            val syncRes = repo.syncCorridorPois(trail)
                            if (syncRes.isSuccess) {
                                repo.setCorridorPoiVersion(trail.id, com.example.data.repository.DanskStiRepository.CORRIDOR_POI_VERSION, isRoute = false)
                            }
                        } catch (_: Exception) {}
                    }
                }

                // 2b. Download & cache Access polygons
                try {
                    if (resolvedAreaBounds != null) {
                        repo.getAccessPolygons(
                            resolvedAreaBounds[0],
                            resolvedAreaBounds[1],
                            resolvedAreaBounds[2],
                            resolvedAreaBounds[3],
                            updateAvailabilityFlag = false
                        )
                    } else if (points.isNotEmpty()) {
                        val minLat = points.minOf { it.lat } - 0.01
                        val maxLat = points.maxOf { it.lat } + 0.01
                        val minLng = points.minOf { it.lng } - 0.015
                        val maxLng = points.maxOf { it.lng } + 0.015
                        repo.getAccessPolygons(minLat, maxLat, minLng, maxLng, updateAvailabilityFlag = false)
                    }
                } catch (_: Exception) {
                }

                // 3. Compute tile coordinates
                val tileIndices = if (resolvedAreaBounds != null) {
                    OfflineDownloadManager.calculateAreaTiles(
                        minLat = resolvedAreaBounds[0],
                        maxLat = resolvedAreaBounds[1],
                        minLng = resolvedAreaBounds[2],
                        maxLng = resolvedAreaBounds[3],
                        minZoom = 8,
                        maxZoom = 16
                    ).toList()
                } else {
                    OfflineDownloadManager.calculateTiles(points, corridorKm).toList()
                }
                val totalTiles = tileIndices.size

                if (totalTiles == 0) {
                    return@withContext Result.failure(
                        workDataOf("error" to localizedCtx.getString(R.string.offline_error_no_tiles_to_fetch))
                    )
                }

                val layersState = try {
                    settingsRepo.mapLayersFlow.firstOrNull() ?: settingsRepo.currentSettingsSnapshot().mapLayers
                } catch (_: Exception) {
                    settingsRepo.currentSettingsSnapshot().mapLayers
                }

                val effectiveIncludeTerrain = includeTerrain || layersState.showTerrain
                val effectiveIncludeAerial = includeAerial

                // Base Skærmkort source (shared name "DanskSti_Skaermkort" for light & dark mode)
                val tileSource = DanskStiTileSource.instance
                // Ortofoto / Luftfoto source: ONLY DanskStiTileSourceHelper.luftfotoInstance
                val luftfotoTileSource = if (effectiveIncludeAerial) {
                    com.example.util.DanskStiTileSourceHelper.luftfotoInstance
                } else null
                // Terrain / DHM sources: ONLY dhmHillshadeInstance and dhmContourInstance (contour from zoom 14)
                val hasDataforsyningenKey = DanskStiTileSource.hasValidKey || customTileFetcher != null || connectionFactory != null
                val hillshadeSource = if (hasDataforsyningenKey && effectiveIncludeTerrain) {
                    com.example.util.DanskStiTileSourceHelper.dhmHillshadeInstance
                } else null
                val contourSource = if (hasDataforsyningenKey && effectiveIncludeTerrain) {
                    com.example.util.DanskStiTileSourceHelper.dhmContourInstance
                } else null

                val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(context, trailId)

                // If re-downloading an edited route/plan/trail, prune tiles that are no longer in the corridor
                if (archiveFile.exists() && archiveFile.length() > 0L) {
                    OfflineDownloadManager.pruneArchiveTilesNotInCorridor(archiveFile, tileIndices)
                }

                val existingBaseTiles = OfflineDownloadManager.getExistingTileKeysInArchive(archiveFile, tileSource.name())
                val existingLuftfotoTiles = if (luftfotoTileSource != null) {
                    OfflineDownloadManager.getExistingTileKeysInArchive(archiveFile, luftfotoTileSource.name())
                } else emptySet()
                val existingHillshadeTiles = if (hillshadeSource != null) {
                    OfflineDownloadManager.getExistingTileKeysInArchive(archiveFile, hillshadeSource.name())
                } else emptySet()
                val existingContourTiles = if (contourSource != null) {
                    OfflineDownloadManager.getExistingTileKeysInArchive(archiveFile, contourSource.name())
                } else emptySet()

                fun isTileSaved(existingKeys: Set<Long>, tileIdx: Long): Boolean {
                    return tileIdx in existingKeys || OfflineDownloadManager.toSqliteArchiveKey(tileIdx) in existingKeys
                }

                val remainingTilesCount = tileIndices.count { !isTileSaved(existingBaseTiles, it) }
                val requiredRemainingBytes = remainingTilesCount.toLong() * 10480L

                val storageCheck = OfflineDownloadManager.checkFreeStorage(context, requiredRemainingBytes)
                if (!storageCheck.hasEnoughSpace) {
                    return@withContext Result.failure(
                        workDataOf(
                            "error" to (storageCheck.message ?: localizedCtx.getString(R.string.offline_error_not_enough_storage)),
                            KEY_REQUIRED_BYTES to requiredRemainingBytes
                        )
                    )
                }

                archiveWriter = SqliteArchiveTileWriter(archiveFile.absolutePath)

                var downloadedCount = existingBaseTiles.size
                var newlyFetchedTiles = 0
                var totalBytes = if (archiveFile.exists()) archiveFile.length() else 0L
                val buffer = ByteArray(8192)

                fun fetchTileBytes(source: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase, tileIdx: Long): ByteArray? {
                    val customFetcher = customTileFetcher
                    if (customFetcher != null) {
                        return customFetcher(source.name(), tileIdx)
                    }
                    val tileUrlStr = source.getTileURLString(tileIdx)
                    return fetchTileBytesFromUrl(
                        tileUrlStr = tileUrlStr,
                        buffer = buffer,
                        connectionFactory = connectionFactory ?: { u -> u.openConnection() as HttpURLConnection }
                    )
                }

                fun downloadAndSaveTile(
                    source: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase,
                    tileIdx: Long
                ) {
                    val tileData = fetchTileBytes(source, tileIdx)
                    if (tileData != null) {
                        totalBytes += tileData.size
                        archiveWriter?.saveFile(source, tileIdx, tileData.inputStream(), null)
                        newlyFetchedTiles++
                        downloadedCount++
                    }
                }

                for ((index, tileIndex) in tileIndices.withIndex()) {
                    if (isStoppedProvider() || shouldInterruptAfterTileCount?.let { newlyFetchedTiles >= it } == true) {
                        archiveWriter?.onDetach()
                        archiveWriter = null
                        val wasExplicitlyCancelled = runCatching {
                            androidx.work.WorkManager.getInstance(context)
                                .getWorkInfosForUniqueWork("offline_download_$trailId")
                                .get()
                                .any { it.state == androidx.work.WorkInfo.State.CANCELLED }
                        }.getOrDefault(false)
                        if (wasExplicitlyCancelled) {
                            runCatching { db.dao().deleteOfflineDownloadInProgress(trailId) }
                            return@withContext Result.failure(
                                workDataOf(
                                    "error" to localizedCtx.getString(R.string.offline_error_download_paused),
                                    KEY_DOWNLOADED_COUNT to downloadedCount,
                                    KEY_NEWLY_FETCHED_COUNT to newlyFetchedTiles
                                )
                            )
                        }
                        return@withContext Result.retry()
                    }

                    // 1. Base Skærmkort tile (shared light & dark provider name "DanskSti_Skaermkort")
                    if (!isTileSaved(existingBaseTiles, tileIndex)) {
                        downloadAndSaveTile(tileSource, tileIndex)
                    }

                    // 2. Optional Aerial / Ortofoto tiles (ONLY luftfotoInstance)
                    if (luftfotoTileSource != null && !isTileSaved(existingLuftfotoTiles, tileIndex)) {
                        downloadAndSaveTile(luftfotoTileSource, tileIndex)
                    }

                    // 3. Optional Terrain / DHM tiles (ONLY dhmHillshadeInstance + dhmContourInstance from zoom 14+)
                    if (hillshadeSource != null && !isTileSaved(existingHillshadeTiles, tileIndex)) {
                        downloadAndSaveTile(hillshadeSource, tileIndex)
                    }
                    if (contourSource != null &&
                        org.osmdroid.util.MapTileIndex.getZoom(tileIndex) >= 14 &&
                        !isTileSaved(existingContourTiles, tileIndex)
                    ) {
                        downloadAndSaveTile(contourSource, tileIndex)
                    }

                    if (index % 25 == 0 || index == totalTiles - 1) {
                        val percent = ((index + 1) * 100 / totalTiles).coerceIn(0, 100)
                        onSetProgress(workDataOf(
                            KEY_PROGRESS_PERCENT to percent,
                            KEY_DOWNLOADED_COUNT to index + 1,
                            KEY_TOTAL_COUNT to totalTiles
                        ))
                        try {
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildProgressNotification(localizedCtx, trailName, index + 1, totalTiles, percent)
                            )
                        } catch (_: Exception) {}
                    }
                }

                archiveWriter?.onDetach()
                archiveWriter = null

                val finalBytes = if (archiveFile.exists() && archiveFile.length() > 0L) archiveFile.length() else totalBytes
                val actualTileCountInArchive = OfflineDownloadManager.getExistingTileKeysInArchive(archiveFile, tileSource.name()).size.takeIf { it > 0 } ?: totalTiles

                // 4. Save metadata record to Room
                val downloadedEntity = DownloadedTrailEntity(
                    trailId = trailId,
                    trailName = trailName,
                    downloadedAt = System.currentTimeMillis(),
                    sizeBytes = finalBytes,
                    tileCount = actualTileCountInArchive,
                    corridorKm = corridorKm,
                    minZoom = 8,
                    maxZoom = 16,
                    downloadType = downloadType,
                    geometryHash = geometryHash,
                    includeAerial = effectiveIncludeAerial,
                    includeTerrain = effectiveIncludeTerrain,
                    minLat = resolvedAreaBounds?.getOrNull(0),
                    maxLat = resolvedAreaBounds?.getOrNull(1),
                    minLng = resolvedAreaBounds?.getOrNull(2),
                    maxLng = resolvedAreaBounds?.getOrNull(3)
                )
                db.dao().insertDownloadedTrail(downloadedEntity)
                db.dao().deleteOfflineDownloadInProgress(trailId)

                // Show completion notification
                try {
                    val mbStr = String.format(com.example.util.AppLocaleHelper.getAppLocale(context), "%.1f", finalBytes / (1024.0 * 1024.0))
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        NotificationCompat.Builder(localizedCtx, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notif_navigation)
                            .setContentTitle(localizedCtx.getString(R.string.notif_offline_ready_title, trailName))
                            .setContentText(localizedCtx.getString(R.string.notif_offline_ready_text, actualTileCountInArchive, mbStr))
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .build()
                    )
                } catch (_: Exception) {}

                Result.success(workDataOf(
                    KEY_DOWNLOADED_COUNT to actualTileCountInArchive,
                    KEY_NEWLY_FETCHED_COUNT to newlyFetchedTiles,
                    KEY_BYTES_DOWNLOADED to finalBytes
                ))
            } catch (e: kotlinx.coroutines.CancellationException) {
                try {
                    archiveWriter?.onDetach()
                } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                try {
                    archiveWriter?.onDetach()
                } catch (_: Exception) {}
                val isNetworkOrTimeout = e is java.io.IOException ||
                    e is kotlinx.coroutines.TimeoutCancellationException ||
                    e.cause is java.io.IOException ||
                    e.cause is kotlinx.coroutines.TimeoutCancellationException
                if (isNetworkOrTimeout) {
                    return@withContext Result.retry()
                }
                val friendlyErr = com.example.util.userMessageFor(e)
                try {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        NotificationCompat.Builder(localizedCtx, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notif_navigation)
                            .setContentTitle(localizedCtx.getString(R.string.notif_offline_error_title))
                            .setContentText(friendlyErr)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .build()
                    )
                } catch (_: Exception) {}
                Result.failure(workDataOf("error" to friendlyErr))
            }
        }

        private fun createForegroundInfo(context: Context, trailName: String, progress: Int, total: Int): ForegroundInfo {
            val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(context)
            val notification = buildProgressNotification(localizedCtx, trailName, progress, total, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        private fun buildProgressNotification(context: Context, trailName: String, current: Int, total: Int, percent: Int) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_navigation)
                .setContentTitle(context.getString(R.string.notif_offline_downloading_title, trailName))
                .setContentText(context.getString(R.string.notif_offline_downloading_text, current, total, percent))
                .setProgress(total, current, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        private fun createNotificationChannel(context: Context, notificationManager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_offline_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_offline_channel_desc)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}

