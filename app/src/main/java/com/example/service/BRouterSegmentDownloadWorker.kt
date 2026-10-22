package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Background worker to download the 5 Danish BRouter segment files (.rd5) on demand.
 *
 * Enforces Wi-Fi only by default via WorkManager constraints and reports real-time progress.
 */
class BRouterSegmentDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "BRouterSegmentWorker"
        const val UNIQUE_WORK_NAME = "brouter_denmark_download"

        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_CURRENT_SEGMENT = "current_segment"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"

        private const val CHANNEL_ID = "brouter_downloads_channel"
        private const val NOTIFICATION_ID = 2026

        fun enqueue(context: Context, wifiOnly: Boolean = true, replace: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<BRouterSegmentDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_WIFI_ONLY to wifiOnly))
                .addTag(UNIQUE_WORK_NAME)
                .build()

            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (com.example.BuildConfig.DEBUG) {
                addInterceptor(com.example.util.network.MainThreadNetworkInterceptor())
            }
        }
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val storageManager = BRouterStorageManager.getInstance(context)
        val segmentsDir = storageManager.segmentsDir
        segmentsDir.mkdirs()

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo("Forbereder download af rutedata...", 0, 100))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground service", e)
        }

        val totalBytesExpected = BRouterSegments.TOTAL_SIZE_BYTES
        var accumulatedDownloadedBytes = 0L

        // Ensure assets are present too
        storageManager.ensureProfilesCopied(context)

        for (segment in BRouterSegments.DENMARK_SEGMENTS) {
            if (isStopped) {
                Log.i(TAG, "Download worker cancelled by user or system.")
                return@withContext Result.failure()
            }

            val targetFile = File(segmentsDir, segment.filename)
            val tmpFile = File(segmentsDir, "${segment.filename}.tmp")

            val url = "${BRouterSegments.DOWNLOAD_BASE_URL}${segment.filename}"
            Log.i(TAG, "Downloading segment ${segment.filename} from $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DanskSti/1.0 (offline routing sync)")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed downloading ${segment.filename}: HTTP ${response.code}")
                        return@withContext Result.failure(workDataOf("error" to "HTTP ${response.code} ved hentning af ${segment.filename}"))
                    }

                    val body = response.body ?: return@withContext Result.failure()
                    val inputStream: InputStream = body.byteStream()
                    val outputStream = FileOutputStream(tmpFile)

                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var segBytesRead = 0L

                    outputStream.use { out ->
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            if (isStopped) {
                                tmpFile.delete()
                                return@withContext Result.failure()
                            }
                            out.write(buffer, 0, read)
                            segBytesRead += read

                            val totalDownloaded = accumulatedDownloadedBytes + segBytesRead
                            val pct = ((totalDownloaded.toDouble() / totalBytesExpected.toDouble()) * 100).toInt().coerceIn(0, 100)

                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS_PERCENT to pct,
                                    KEY_CURRENT_SEGMENT to segment.filename,
                                    KEY_BYTES_DOWNLOADED to totalDownloaded,
                                    KEY_BYTES_TOTAL to totalBytesExpected
                                )
                            )

                            if (segBytesRead % (1024 * 1024) == 0L || pct % 5 == 0) {
                                try {
                                    val progressMb = totalDownloaded / (1024 * 1024)
                                    val totalMb = totalBytesExpected / (1024 * 1024)
                                    notificationManager.notify(
                                        NOTIFICATION_ID,
                                        buildNotification("Henter ${segment.filename} ($progressMb / $totalMb MB)", pct, 100)
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    // Atomic rename
                    if (tmpFile.exists()) {
                        if (targetFile.exists()) targetFile.delete()
                        tmpFile.renameTo(targetFile)
                    }

                    accumulatedDownloadedBytes += segment.sizeBytes
                    Log.i(TAG, "Successfully downloaded ${segment.filename} (${targetFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${segment.filename}", e)
                if (tmpFile.exists()) tmpFile.delete()
                return@withContext Result.retry()
            }
        }

        // All segments downloaded successfully!
        storageManager.setLastUpdatedTimestamp(System.currentTimeMillis())

        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Rutedata for Danmark er klar")
                    .setContentText("Offline rutebygger kan nu benyttes uden mobildækning.")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {}

        Result.success()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rutedata downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Viser status for download af offline rutesegmenter for Danmark"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Henter rutedata for Danmark")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .build()

    private fun createForegroundInfo(text: String, progress: Int, max: Int): ForegroundInfo {
        val notification = buildNotification(text, progress, max)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
