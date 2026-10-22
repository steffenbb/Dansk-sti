package com.example.util

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.DanskStiApp
import com.example.data.model.GeoPointDto
import com.example.service.OfflineDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL
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

object OfflineDownloadManager {

    const val MAX_PROVIDER_ALLOWED_TILES = 15000

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
                    setRequestProperty("User-Agent", "DanskStiApp/1.0 (Android; Offline Size Probe)")
                }

                val contentLength = conn.contentLengthLong
                if (contentLength > 0) {
                    totalBytes += contentLength
                    measuredCount++
                } else {
                    // Try GET if HEAD didn't return content-length
                    conn.disconnect()
                    val getConn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", "DanskStiApp/1.0")
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
            } catch (e: Exception) {
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
     * Computes the real estimate and validates provider quotas before downloading.
     */
    suspend fun estimateDownload(points: List<GeoPointDto>, corridorKm: Double): OfflineEstimateResult =
        withContext(Dispatchers.Default) {
            val tiles = calculateTiles(points, corridorKm).toList()
            val tileCount = tiles.size

            if (tileCount > MAX_PROVIDER_ALLOWED_TILES) {
                return@withContext OfflineEstimateResult(
                    tileCount = tileCount,
                    estimatedSizeBytes = tileCount * 10480L,
                    isAllowed = false,
                    refuseReason = "Ruten kræver $tileCount kortfliser, hvilket overstiger udbyderens grænse på $MAX_PROVIDER_ALLOWED_TILES fliser per download. Vælg en smallere korridor (f.eks. 0,5 km) for at fortsætte."
                )
            }

            val avgSize = measureAverageTileSize(tiles)
            val estimatedTotalBytes = tileCount * avgSize

            OfflineEstimateResult(
                tileCount = tileCount,
                estimatedSizeBytes = estimatedTotalBytes,
                isAllowed = true
            )
        }

    /**
     * Enqueues a background WorkManager job to download the trail offline.
     */
    fun enqueueDownload(
        context: Context,
        trailId: String,
        trailName: String,
        corridorKm: Double
    ) {
        val workRequest = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(
                workDataOf(
                    OfflineDownloadWorker.KEY_TRAIL_ID to trailId,
                    OfflineDownloadWorker.KEY_TRAIL_NAME to trailName,
                    OfflineDownloadWorker.KEY_CORRIDOR_KM to corridorKm
                )
            )
            .addTag("offline_download_$trailId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline_download_$trailId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun observeDownloadProgress(context: Context, trailId: String): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow("offline_download_$trailId")
            .map { it.firstOrNull() }
    }

    fun getOfflineDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "offline")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getOfflineArchiveFile(context: Context, trailId: String): java.io.File {
        val safeId = trailId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return java.io.File(getOfflineDir(context), "$safeId.sqlite")
    }

    suspend fun deleteOfflineTrail(context: Context, trailId: String) = withContext(Dispatchers.IO) {
        val archiveFile = getOfflineArchiveFile(context, trailId)
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        val app = context.applicationContext as DanskStiApp
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
