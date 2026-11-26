package com.example.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.GeoPointDto
import com.example.service.OfflineDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

data class OfflineEstimateResult(
    val tileCount: Int,
    val estimatedSizeBytes: Long,
    val isAllowed: Boolean,
    val refuseReason: String? = null
)

data class StorageCheckResult(
    val hasEnoughSpace: Boolean,
    val availableBytes: Long,
    val requiredBytes: Long,
    val message: String? = null
)

object OfflineDownloadManager {

    const val MAX_PROVIDER_ALLOWED_TILES = 15000
    const val MAX_AREA_DOWNLOAD_TILES = 15000

    const val TYPE_TRAIL = "TRAIL"
    const val TYPE_ROUTE = "ROUTE"
    const val TYPE_PLAN = "PLAN"
    const val TYPE_AREA = "AREA"

    /**
     * Test hook for overriding available storage bytes in unit tests.
     */
    @Volatile
    var freeBytesProviderOverride: (() -> Long)? = null

    /**
     * Test hook for overriding network status in unit tests: Pair(isConnected, isWifiOrUnmetered).
     */
    @Volatile
    var networkStatusProviderOverride: (() -> Pair<Boolean, Boolean>)? = null

    fun formatSizeMbDanish(bytes: Long): String {
        val mb = max(0.1, bytes / (1024.0 * 1024.0))
        return String.format(Locale("da", "DK"), "%.1f MB", mb)
    }

    fun computeGeometryHash(points: List<GeoPointDto>): String {
        if (points.isEmpty()) return ""
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (pt in points) {
                val coordStr = String.format(Locale.US, "%.5f,%.5f;", pt.lat, pt.lng)
                md.update(coordStr.toByteArray(Charsets.UTF_8))
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            points.hashCode().toString()
        }
    }

    fun inferDownloadType(downloadId: String, explicitType: String? = null): String {
        val norm = explicitType?.trim()?.uppercase(Locale.ROOT)
        if (norm == TYPE_ROUTE || norm == TYPE_PLAN || norm == TYPE_AREA) return norm
        return when {
            downloadId.startsWith("route:") || downloadId.startsWith("route_") -> TYPE_ROUTE
            downloadId.startsWith("plan:") || downloadId.startsWith("plan_") -> TYPE_PLAN
            downloadId.startsWith("area:") || downloadId.startsWith("area_") -> TYPE_AREA
            else -> TYPE_TRAIL
        }
    }

    fun getAvailableFreeStorageBytes(context: Context?): Long {
        freeBytesProviderOverride?.let { return it() }
        if (context == null) return Long.MAX_VALUE
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Check free storage before every download (map tiles and routing data)
     * and refuse with the size needed.
     */
    fun checkFreeStorage(context: Context?, requiredBytes: Long): StorageCheckResult {
        val available = getAvailableFreeStorageBytes(context)
        if (requiredBytes > 0L && available < requiredBytes) {
            val neededStr = formatSizeMbDanish(requiredBytes)
            val availStr = String.format(Locale("da", "DK"), "%.1f MB", max(0.0, available / (1024.0 * 1024.0)))
            return StorageCheckResult(
                hasEnoughSpace = false,
                availableBytes = available,
                requiredBytes = requiredBytes,
                message = AppLocaleHelper.strOrNull(
                    context,
                    com.example.R.string.offline_err_not_enough_storage_detail_fmt,
                    neededStr,
                    availStr
                ) ?: "Ikke nok fri lagerplads. Download kræver $neededStr (kun $availStr ledig)."
            )
        }
        return StorageCheckResult(
            hasEnoughSpace = true,
            availableBytes = available,
            requiredBytes = requiredBytes,
            message = null
        )
    }

    /**
     * Map tile downloads require a network connection, with an optional "Kun på Wi-Fi" setting.
     */
    fun checkNetworkRequirement(context: Context?, wifiOnly: Boolean): Pair<Boolean, String?> {
        val status = networkStatusProviderOverride?.invoke() ?: run {
            if (context == null) return@run true to true
            val app = context.applicationContext as? DanskStiApp
            val repoOverride = app?.repository?.isOnlineOverrideForTests
            if (repoOverride == false) return@run false to false
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = cm?.activeNetwork
                val caps = if (network != null) cm.getNetworkCapabilities(network) else null
                if (caps == null) {
                    if (repoOverride == true) true to true else false to false
                } else {
                    val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val unmetered = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    connected to unmetered
                }
            } catch (_: Exception) {
                true to true
            }
        }
        val (isConnected, isWifi) = status
        if (!isConnected) {
            return false to (AppLocaleHelper.strOrNull(context, com.example.R.string.offline_err_no_internet_tiles)
                ?: "Ingen internetforbindelse. Hentning af kortfliser kræver netværksforbindelse.")
        }
        if (wifiOnly && !isWifi) {
            return false to (AppLocaleHelper.strOrNull(context, com.example.R.string.offline_err_wifi_only_enabled)
                ?: "Kun på Wi-Fi er slået til. Forbind til Wi-Fi eller slå 'Kun på Wi-Fi' fra.")
        }
        return true to null
    }

    /**
     * Calculates the exact set of tiles required for a bounding box across zoom levels [minZoom]..[maxZoom].
     */
    fun calculateAreaTiles(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        minZoom: Int = 8,
        maxZoom: Int = 16
    ): Set<Long> {
        if (minLat > maxLat || minLng > maxLng) return emptySet()
        val tiles = LinkedHashSet<Long>()
        for (z in minZoom..maxZoom) {
            val x0 = lon2tile(minLng, z)
            val x1 = lon2tile(maxLng, z)
            val y0 = lat2tile(maxLat, z)
            val y1 = lat2tile(minLat, z)
            val minX = min(x0, x1)
            val maxX = max(x0, x1)
            val minY = min(y0, y1)
            val maxY = max(y0, y1)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    tiles.add(MapTileIndex.getTileIndex(z, x, y))
                }
            }
        }
        return tiles
    }

    /**
     * Estimates an area download (C2), enforcing the 15,000-tile cap with:
     * "Området er for stort – zoom ind på kortet".
     */
    suspend fun estimateAreaDownload(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        minZoom: Int = 8,
        maxZoom: Int = 16,
        includeAerial: Boolean = false,
        includeTerrain: Boolean = false,
        context: Context? = null,
        wifiOnly: Boolean? = null
    ): OfflineEstimateResult = withContext(Dispatchers.Default) {
        val baseTiles = calculateAreaTiles(minLat, maxLat, minLng, maxLng, minZoom, maxZoom)
        val baseCount = baseTiles.size
        val terrainCount = if (includeTerrain) {
            baseCount + baseTiles.count { MapTileIndex.getZoom(it) >= 14 }
        } else 0
        val aerialCount = if (includeAerial) baseCount else 0
        val totalTileCount = baseCount + terrainCount + aerialCount

        if (totalTileCount > MAX_AREA_DOWNLOAD_TILES) {
            return@withContext OfflineEstimateResult(
                tileCount = totalTileCount,
                estimatedSizeBytes = totalTileCount.toLong() * 10480L,
                isAllowed = false,
                refuseReason = AppLocaleHelper.strOrNull(context, com.example.R.string.offline_err_area_too_large_zoom_in)
                    ?: "Området er for stort – zoom ind på kortet"
            )
        }

        val avgSize = 10480L
        val estimatedTotalBytes = totalTileCount.toLong() * avgSize

        val storageCheck = checkFreeStorage(context, estimatedTotalBytes)
        if (!storageCheck.hasEnoughSpace) {
            return@withContext OfflineEstimateResult(
                tileCount = totalTileCount,
                estimatedSizeBytes = estimatedTotalBytes,
                isAllowed = false,
                refuseReason = storageCheck.message
            )
        }

        if (wifiOnly != null && (context != null || networkStatusProviderOverride != null)) {
            val (netOk, netMsg) = checkNetworkRequirement(context, wifiOnly)
            if (!netOk) {
                return@withContext OfflineEstimateResult(
                    tileCount = totalTileCount,
                    estimatedSizeBytes = estimatedTotalBytes,
                    isAllowed = false,
                    refuseReason = netMsg
                )
            }
        }

        OfflineEstimateResult(
            tileCount = totalTileCount,
            estimatedSizeBytes = estimatedTotalBytes,
            isAllowed = true
        )
    }

    /**
     * Calculates the exact set of tiles required:
     * - Zoom 8..13 for the trail's bounding box
     * - Zoom 14..16 within corridor radius (corridorKm) of the trail line
     */
    fun calculateTiles(points: List<GeoPointDto>, corridorKm: Double): Set<Long> {
        if (points.isEmpty()) return emptySet()

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val tiles = HashSet<Long>()

        // 1. Zoom 8-13 Bounding Box
        for (z in 8..13) {
            val x0 = lon2tile(minLng, z)
            val x1 = lon2tile(maxLng, z)
            val y0 = lat2tile(maxLat, z)
            val y1 = lat2tile(minLat, z)

            val minX = min(x0, x1)
            val maxX = max(x0, x1)
            val minY = min(y0, y1)
            val maxY = max(y0, y1)

            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    tiles.add(MapTileIndex.getTileIndex(z, x, y))
                }
            }
        }

        // 2. Zoom 14-16 Corridor
        val corridorMeters = (corridorKm * 1000.0).coerceIn(500.0, 3000.0)
        val avgLat = (minLat + maxLat) / 2.0
        val dLat = corridorMeters / 111320.0
        val dLng = corridorMeters / (111320.0 * cos(Math.toRadians(avgLat)))

        // Sample points every ~150-200m to cover corridor completely
        val sampleStep = max(1, points.size / 600)
        val sampledPoints = points.filterIndexed { index, _ -> index % sampleStep == 0 }

        for (z in 14..16) {
            for (p in sampledPoints) {
                val x0 = lon2tile(p.lng - dLng, z)
                val x1 = lon2tile(p.lng + dLng, z)
                val y0 = lat2tile(p.lat + dLat, z)
                val y1 = lat2tile(p.lat - dLat, z)

                val minX = min(x0, x1)
                val maxX = max(x0, x1)
                val minY = min(y0, y1)
                val maxY = max(y0, y1)

                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        tiles.add(MapTileIndex.getTileIndex(z, x, y))
                    }
                }
            }
        }

        return tiles
    }

    /**
     * Requirement C: When the "Terræn" layer is on, returns (hillshadeTiles, contourTiles)
     * to include in the offline SQLite archive (contours from zoom 14+).
     */
    fun calculateTerrainTilesForOffline(
        points: List<GeoPointDto>,
        corridorKm: Double,
        isTerrainLayerOn: Boolean
    ): Pair<Set<Long>, Set<Long>> {
        if (!isTerrainLayerOn) return emptySet<Long>() to emptySet<Long>()
        val allBaseTiles = calculateTiles(points, corridorKm)
        val contourTiles = allBaseTiles.filterTo(HashSet()) { MapTileIndex.getZoom(it) >= 14 }
        return allBaseTiles to contourTiles
    }

    /**
     * Measures the real average tile size from the first 20 tiles.
     */
    suspend fun measureAverageTileSize(tiles: List<Long>): Long = withContext(Dispatchers.IO) {
        val sampleTiles = tiles.take(20)
        if (sampleTiles.isEmpty()) return@withContext 10240L // Default ~10 KB

        val tileSource = DanskStiTileSource.instance
        var measuredCount = 0
        var totalBytes = 0L

        for (tileIndex in sampleTiles) {
            val urlStr = tileSource.getTileURLString(tileIndex)
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > 0) {
                    totalBytes += contentLength
                    measuredCount++
                } else {
                    conn.disconnect()
                    val getConn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                    }
                    if (getConn.responseCode == 200) {
                        val stream = getConn.inputStream
                        var count = 0
                        val buf = ByteArray(4096)
                        var read: Int
                        while (stream.read(buf).also { read = it } != -1) {
                            count += read
                        }
                        if (count > 0) {
                            totalBytes += count
                            measuredCount++
                        }
                    }
                    getConn.disconnect()
                }
            } catch (_: Exception) {
                // Ignore probe failure
            } finally {
                conn?.disconnect()
            }
        }

        if (measuredCount > 0) {
            totalBytes / measuredCount
        } else {
            10480L // ~10.2 KB average measured from Dataforsyningen Skærmkortet
        }
    }

    /**
     * Computes the real estimate and validates provider quotas and free storage before downloading.
     */
    suspend fun estimateDownload(
        points: List<GeoPointDto>,
        corridorKm: Double,
        context: Context? = null,
        wifiOnly: Boolean? = null,
        includeAerial: Boolean = false,
        includeTerrain: Boolean = false
    ): OfflineEstimateResult =
        withContext(Dispatchers.Default) {
            val baseTiles = calculateTiles(points, corridorKm)
            val tiles = baseTiles.toList()
            val baseCount = tiles.size
            val terrainCount = if (includeTerrain) {
                baseCount + baseTiles.count { MapTileIndex.getZoom(it) >= 14 }
            } else 0
            val aerialCount = if (includeAerial) baseCount else 0
            val tileCount = baseCount + terrainCount + aerialCount

            if (tileCount > MAX_PROVIDER_ALLOWED_TILES) {
                return@withContext OfflineEstimateResult(
                    tileCount = tileCount,
                    estimatedSizeBytes = tileCount * 10480L,
                    isAllowed = false,
                    refuseReason = AppLocaleHelper.strOrNull(
                        context,
                        com.example.R.string.offline_err_provider_tile_limit_fmt,
                        tileCount,
                        MAX_PROVIDER_ALLOWED_TILES
                    ) ?: "Ruten kræver $tileCount kortfliser, hvilket overstiger udbyderens grænse på $MAX_PROVIDER_ALLOWED_TILES fliser per download. Vælg en smallere korridor (f.eks. 0,5 km) for at fortsætte."
                )
            }

            val avgSize = measureAverageTileSize(tiles)
            val estimatedTotalBytes = tileCount * avgSize

            val storageCheck = checkFreeStorage(context, estimatedTotalBytes)
            if (!storageCheck.hasEnoughSpace) {
                return@withContext OfflineEstimateResult(
                    tileCount = tileCount,
                    estimatedSizeBytes = estimatedTotalBytes,
                    isAllowed = false,
                    refuseReason = storageCheck.message
                )
            }

            if (wifiOnly != null && (context != null || networkStatusProviderOverride != null)) {
                val (netOk, netMsg) = checkNetworkRequirement(context, wifiOnly)
                if (!netOk) {
                    return@withContext OfflineEstimateResult(
                        tileCount = tileCount,
                        estimatedSizeBytes = estimatedTotalBytes,
                        isAllowed = false,
                        refuseReason = netMsg
                    )
                }
            }

            OfflineEstimateResult(
                tileCount = tileCount,
                estimatedSizeBytes = estimatedTotalBytes,
                isAllowed = true
            )
        }

    fun buildWorkRequest(
        trailId: String,
        trailName: String,
        corridorKm: Double,
        wifiOnly: Boolean = SettingsRepository.cachedOfflineDownloadsWifiOnly,
        includeAerial: Boolean = false,
        includeTerrain: Boolean = false,
        areaMinLat: Double? = null,
        areaMaxLat: Double? = null,
        areaMinLng: Double? = null,
        areaMaxLng: Double? = null
    ): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val pairs = mutableListOf<Pair<String, Any?>>(
            OfflineDownloadWorker.KEY_TRAIL_ID to trailId,
            OfflineDownloadWorker.KEY_TRAIL_NAME to trailName,
            OfflineDownloadWorker.KEY_CORRIDOR_KM to corridorKm,
            OfflineDownloadWorker.KEY_WIFI_ONLY to wifiOnly,
            OfflineDownloadWorker.KEY_INCLUDE_AERIAL to includeAerial,
            OfflineDownloadWorker.KEY_INCLUDE_TERRAIN to includeTerrain
        )
        if (areaMinLat != null && areaMaxLat != null && areaMinLng != null && areaMaxLng != null) {
            pairs.add(OfflineDownloadWorker.KEY_AREA_MIN_LAT to areaMinLat)
            pairs.add(OfflineDownloadWorker.KEY_AREA_MAX_LAT to areaMaxLat)
            pairs.add(OfflineDownloadWorker.KEY_AREA_MIN_LNG to areaMinLng)
            pairs.add(OfflineDownloadWorker.KEY_AREA_MAX_LNG to areaMaxLng)
        }

        return OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .setInputData(workDataOf(*pairs.toTypedArray()))
            .addTag("offline_download_$trailId")
            .build()
    }

    /**
     * Enqueues a background WorkManager job to download the trail/route/plan/area offline.
     * Checks free storage and network requirements first.
     */
    fun enqueueDownload(
        context: Context,
        trailId: String,
        trailName: String,
        corridorKm: Double,
        wifiOnly: Boolean = SettingsRepository.cachedOfflineDownloadsWifiOnly,
        requiredBytes: Long = 0L,
        includeAerial: Boolean = false,
        includeTerrain: Boolean = false,
        areaMinLat: Double? = null,
        areaMaxLat: Double? = null,
        areaMinLng: Double? = null,
        areaMaxLng: Double? = null,
        maxZoom: Int = 16,
        downloadType: String? = null,
        geometryHash: String? = null
    ): OfflineEstimateResult {
        val checkBytes = if (requiredBytes > 0L) requiredBytes else 1024L * 1024L
        val storageCheck = checkFreeStorage(context, checkBytes)
        if (!storageCheck.hasEnoughSpace) {
            return OfflineEstimateResult(
                tileCount = 0,
                estimatedSizeBytes = checkBytes,
                isAllowed = false,
                refuseReason = storageCheck.message
            )
        }

        val (netOk, netMsg) = checkNetworkRequirement(context, wifiOnly)
        if (!netOk) {
            return OfflineEstimateResult(
                tileCount = 0,
                estimatedSizeBytes = checkBytes,
                isAllowed = false,
                refuseReason = netMsg
            )
        }

        val workRequest = buildWorkRequest(
            trailId = trailId,
            trailName = trailName,
            corridorKm = corridorKm,
            wifiOnly = wifiOnly,
            includeAerial = includeAerial,
            includeTerrain = includeTerrain,
            areaMinLat = areaMinLat,
            areaMaxLat = areaMaxLat,
            areaMinLng = areaMinLng,
            areaMaxLng = areaMaxLng
        )

        val resolvedDownloadType = inferDownloadType(trailId, downloadType)

        runCatching {
            val app = context.applicationContext as? DanskStiApp
            if (app != null) {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    val existing = app.database.dao().getOfflineDownloadInProgress(trailId)
                    app.database.dao().upsertOfflineDownloadInProgress(
                        com.example.data.model.OfflineDownloadInProgressEntity(
                            trailId = trailId,
                            startedAt = existing?.startedAt ?: System.currentTimeMillis(),
                            downloadType = resolvedDownloadType,
                            includeAerial = includeAerial,
                            includeTerrain = includeTerrain,
                            minLat = areaMinLat ?: existing?.minLat,
                            maxLat = areaMaxLat ?: existing?.maxLat,
                            minLng = areaMinLng ?: existing?.minLng,
                            maxLng = areaMaxLng ?: existing?.maxLng
                        )
                    )
                }
            }
        }

        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline_download_$trailId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        return OfflineEstimateResult(
            tileCount = 0,
            estimatedSizeBytes = checkBytes,
            isAllowed = true
        )
    }

    suspend fun cancelDownload(context: Context, trailId: String) = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork("offline_download_$trailId")
        }
        runCatching {
            val app = context.applicationContext as? DanskStiApp
            app?.database?.dao()?.deleteOfflineDownloadInProgress(trailId)
        }
    }

    /**
     * Reads already downloaded tile keys from an existing SQLite tile archive
     * so interrupted map tile downloads can resume without re-downloading existing tiles.
     */
    fun toSqliteArchiveKey(tileIndex: Long): Long {
        val z = MapTileIndex.getZoom(tileIndex).toLong()
        val x = MapTileIndex.getX(tileIndex).toLong()
        val y = MapTileIndex.getY(tileIndex).toLong()
        return (((z shl z.toInt()) + x) shl z.toInt()) + y
    }

    /**
     * Prunes any tiles from [archiveFile] whose `key` is not in the allowed set derived from [allowedTileIndices].
     * Returns the number of deleted tile rows.
     */
    fun pruneArchiveTilesNotInCorridor(archiveFile: File, allowedTileIndices: Collection<Long>): Int {
        if (!archiveFile.exists() || archiveFile.length() == 0L) return 0
        val allowedSqliteKeys = HashSet<Long>(allowedTileIndices.size * 2)
        for (idx in allowedTileIndices) {
            allowedSqliteKeys.add(idx)
            allowedSqliteKeys.add(toSqliteArchiveKey(idx))
        }
        var db: SQLiteDatabase? = null
        var deletedRows = 0
        try {
            db = SQLiteDatabase.openDatabase(archiveFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val existingKeys = mutableListOf<Long>()
            db.rawQuery("SELECT DISTINCT key FROM tiles", null).use { c ->
                while (c.moveToNext()) {
                    existingKeys.add(c.getLong(0))
                }
            }
            val keysToDelete = existingKeys.filter { it !in allowedSqliteKeys }
            if (keysToDelete.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (k in keysToDelete) {
                        deletedRows += db.delete("tiles", "key = ?", arrayOf(k.toString()))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
        }
        return deletedRows
    }

    fun getExistingTileKeysInArchive(archiveFile: File, providerName: String? = null): Set<Long> {
        if (!archiveFile.exists() || archiveFile.length() == 0L) return emptySet()
        val result = HashSet<Long>()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(archiveFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = if (providerName != null) {
                db.rawQuery("SELECT key FROM tiles WHERE provider = ?", arrayOf(providerName))
            } else {
                db.rawQuery("SELECT key FROM tiles", null)
            }
            cursor.use { c ->
                while (c.moveToNext()) {
                    result.add(c.getLong(0))
                }
            }
        } catch (_: Exception) {
            // Table might not exist yet if interrupted before first write
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
        }
        return result
    }

    fun observeDownloadProgress(context: Context, trailId: String): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow("offline_download_$trailId")
            .map { it.firstOrNull() }
    }

    fun getOfflineDir(context: Context): File {
        val dir = File(context.filesDir, "offline")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getOfflineArchiveFile(context: Context, trailId: String): File {
        val safeId = trailId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getOfflineDir(context), "$safeId.sqlite")
    }

    suspend fun deleteOfflineTrail(context: Context, trailId: String) = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork("offline_download_$trailId")
        }
        val archiveFile = getOfflineArchiveFile(context, trailId)
        DanskStiTileSourceHelper.detachOfflineArchiveAndDeleteFile(archiveFile)
        val app = context.applicationContext as DanskStiApp
        app.database.dao().deleteOfflineDownloadInProgress(trailId)
        app.database.dao().deleteDownloadedTrail(trailId)
    }

    private fun lon2tile(lon: Double, z: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    }

    private fun lat2tile(lat: Double, z: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * (1 shl z)).toInt()
    }
}
