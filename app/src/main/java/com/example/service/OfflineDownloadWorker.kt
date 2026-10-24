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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trailId = inputData.getString(KEY_TRAIL_ID) ?: return@withContext Result.failure()
        val trailName = inputData.getString(KEY_TRAIL_NAME) ?: "Vandrerute"
        val corridorKm = inputData.getDouble(KEY_CORRIDOR_KM, 1.0)

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo(trailName, 0, 100))
        } catch (_: Exception) {}

        val app = context.applicationContext as DanskStiApp
        val repo = app.repository
        val db = app.database

        var archiveWriter: SqliteArchiveTileWriter? = null
        try {
            // 1. Download & cache trail geometry (Phase 1)
            val trail = repo.getTrailById(trailId)
                ?: return@withContext Result.failure(workDataOf("error" to "Rute ikke fundet for $trailName"))
            val points = repo.getTrailPoints(trail).flatMap { it.points }
            if (points.isEmpty()) {
                return@withContext Result.failure(workDataOf("error" to "Ingen rutepunkter fundet for $trailName"))
            }

            // 2. Download & cache TrailPoi items (Phase 2)
            repo.syncCorridorPois(trail)

            // 2b. Download & cache Access polygons (Danmarks Miljøportal WFS + OSM access areas)
            try {
                val minLat = points.minOf { it.lat } - 0.01
                val maxLat = points.maxOf { it.lat } + 0.01
                val minLng = points.minOf { it.lng } - 0.015
                val maxLng = points.maxOf { it.lng } + 0.015
                repo.getAccessPolygons(minLat, maxLat, minLng, maxLng)
            } catch (_: Exception) {
                // Non-fatal if access polygons download encounters network issues
            }

            // 3. Compute tile coordinates (Phase 4): Zoom 8-13 bbox + Zoom 14-16 corridor
            val tileIndices = OfflineDownloadManager.calculateTiles(points, corridorKm).toList()
            val totalTiles = tileIndices.size

            if (totalTiles == 0) {
                return@withContext Result.failure(workDataOf("error" to "Ingen kortfliser at hente"))
            }

            // Requirement 18: Write every offline download into its own SQLite tile archive
            // in filesDir/offline/<id>.sqlite
            val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(context, trailId)
            if (archiveFile.exists()) {
                archiveFile.delete()
            }
            archiveWriter = SqliteArchiveTileWriter(archiveFile.absolutePath)

            val settingsRepo = com.example.data.local.SettingsRepository(context)
            val layersState = try {
                settingsRepo.mapLayersFlow.firstOrNull() ?: com.example.data.local.MapLayersState()
            } catch (_: Exception) {
                com.example.data.local.MapLayersState()
            }

            val tileSource = DanskStiTileSource.instance
            val hillshadeSource = if (layersState.showTerrain) com.example.util.DanskStiTileSourceHelper.dhmHillshadeInstance else null
            val contourSource = if (layersState.showTerrain) com.example.util.DanskStiTileSourceHelper.dhmContourInstance else null

            var downloadedCount = 0
            var totalBytes = 0L
            val buffer = ByteArray(8192)

            fun downloadAndSaveTile(source: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase, tileIdx: Long) {
                val tileUrlStr = source.getTileURLString(tileIdx)
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(tileUrlStr)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                    }

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val stream: InputStream = conn.inputStream
                        val out = ByteArrayOutputStream()
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        val tileData = out.toByteArray()
                        totalBytes += tileData.size
                        archiveWriter?.saveFile(source, tileIdx, tileData.inputStream(), null)
                        downloadedCount++
                    }
                } catch (_: Exception) {
                    // Continue downloading next tile on transient failures
                } finally {
                    conn?.disconnect()
                }
            }

            for ((index, tileIndex) in tileIndices.withIndex()) {
                if (isStopped) {
                    archiveWriter?.onDetach()
                    archiveWriter = null
                    archiveFile.delete()
                    return@withContext Result.failure(workDataOf("error" to "Download afbrudt"))
                }

                // 1. Base map tile
                downloadAndSaveTile(tileSource, tileIndex)

                // 2. Requirement C: Include hillshade and contour (zoom 14+) tiles when "Terræn" layer is on
                if (hillshadeSource != null) {
                    downloadAndSaveTile(hillshadeSource, tileIndex)
                }
                if (contourSource != null && org.osmdroid.util.MapTileIndex.getZoom(tileIndex) >= 14) {
                    downloadAndSaveTile(contourSource, tileIndex)
                }

                // Update notification progress every 25 tiles or on completion
                if (index % 25 == 0 || index == totalTiles - 1) {
                    val percent = ((index + 1) * 100 / totalTiles).coerceIn(0, 100)
                    setProgress(workDataOf(
                        KEY_PROGRESS_PERCENT to percent,
                        KEY_DOWNLOADED_COUNT to index + 1,
                        KEY_TOTAL_COUNT to totalTiles
                    ))
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(trailName, index + 1, totalTiles, percent)
                    )
                }
            }

            archiveWriter?.onDetach()
            archiveWriter = null

            val finalBytes = if (archiveFile.exists() && archiveFile.length() > 0L) archiveFile.length() else totalBytes

            // 4. Save metadata record to Room
            val downloadedEntity = DownloadedTrailEntity(
                trailId = trailId,
                trailName = trailName,
                downloadedAt = System.currentTimeMillis(),
                sizeBytes = finalBytes,
                tileCount = totalTiles,
                corridorKm = corridorKm,
                minZoom = 8,
                maxZoom = 16
            )
            db.dao().insertDownloadedTrail(downloadedEntity)

            // Show completion notification
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("$trailName klar offline")
                    .setContentText("Kort, rute og $totalTiles kortfliser (${String.format("%.1f", finalBytes / (1024.0 * 1024.0))} MB) er gemt.")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
            )

            Result.success(workDataOf(
                KEY_DOWNLOADED_COUNT to totalTiles,
                KEY_BYTES_DOWNLOADED to finalBytes
            ))
        } catch (e: Exception) {
            try {
                archiveWriter?.onDetach()
            } catch (_: Exception) {}
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Fejl under offline download")
                    .setContentText(e.message ?: "Ukendt fejl under download")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
            Result.failure(workDataOf("error" to (e.message ?: "Ukendt fejl")))
        }
    }

    private fun createForegroundInfo(trailName: String, progress: Int, total: Int): ForegroundInfo {
        val notification = buildProgressNotification(trailName, progress, total, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(trailName: String, current: Int, total: Int, percent: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Henter offline-kort: $trailName")
            .setContentText("Henter kortfliser ($current / $total) • $percent%")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline kort downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Viser fremgang under hentning af offline kort og ruter"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "dansk_sti_offline_downloads"
        const val NOTIFICATION_ID = 2001

        const val KEY_TRAIL_ID = "trail_id"
        const val KEY_TRAIL_NAME = "trail_name"
        const val KEY_CORRIDOR_KM = "corridor_km"

        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_COUNT = "downloaded_count"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
    }
}
