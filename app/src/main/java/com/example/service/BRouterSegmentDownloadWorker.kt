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
import com.example.util.OfflineDownloadManager
import com.example.util.StorageCheckResult
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Background worker to download the 5 Danish BRouter segment files (.rd5) on demand.
 *
 * Enforces Wi-Fi only by default via WorkManager constraints, checks free storage before downloading,
 * and resumes interrupted downloads using HTTP Range requests and preserved .tmp files.
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
        const val KEY_FORCE_UPDATE = "force_update"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_CURRENT_SEGMENT = "current_segment"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_REQUIRED_BYTES = "required_bytes"

        private const val CHANNEL_ID = "brouter_downloads_channel"
        private const val NOTIFICATION_ID = 2026

        /**
         * Test hook for simulating segment HTTP responses (including HTTP 206 Partial Content).
         * Takes (segmentFilename, rangeStartBytes) and returns Pair(httpStatusCode, responseBodyBytes).
         */
        @Volatile
        var customSegmentFetcher: ((segmentFilename: String, rangeStartBytes: Long) -> Pair<Int, ByteArray>)? = null

        @Volatile
        var shouldInterruptAfterBytes: Long? = null

        fun calculateRemainingBytesNeeded(context: Context, forceUpdate: Boolean = false): Long {
            val storageManager = BRouterStorageManager.getInstance(context)
            val segmentsDir = storageManager.segmentsDir
            var remaining = 0L
            for (seg in storageManager.getSegmentsWithRealSizes()) {
                val targetFile = File(segmentsDir, seg.filename)
                val tmpFile = File(segmentsDir, "${seg.filename}.tmp")
                if (!forceUpdate && targetFile.exists() && targetFile.length() > 1024L) {
                    continue
                }
                val alreadyInTmp = if (tmpFile.exists()) tmpFile.length().coerceAtLeast(0L) else 0L
                remaining += (seg.sizeBytes - alreadyInTmp).coerceAtLeast(0L)
            }
            return remaining
        }

        fun checkStorageForBRouterDownload(context: Context, forceUpdate: Boolean = false): StorageCheckResult {
            val storageManager = BRouterStorageManager.getInstance(context)
            val remainingBytes = calculateRemainingBytesNeeded(context, forceUpdate)
            val requiredBytes = if (remainingBytes > 0L) remainingBytes else storageManager.getTotalExpectedSizeBytes()
            return OfflineDownloadManager.checkFreeStorage(context, requiredBytes)
        }

        fun enqueue(
            context: Context,
            wifiOnly: Boolean = true,
            replace: Boolean = false,
            forceUpdate: Boolean = false
        ): StorageCheckResult {
            val storageCheck = checkStorageForBRouterDownload(context, forceUpdate)
            if (!storageCheck.hasEnoughSpace) {
                return storageCheck
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<BRouterSegmentDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_WIFI_ONLY to wifiOnly,
                        KEY_FORCE_UPDATE to forceUpdate
                    )
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()

            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
            }
            return storageCheck
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            }
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

        val wifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, storageManager.isWifiOnlyDefault())
        val forceUpdate = inputData.getBoolean(KEY_FORCE_UPDATE, false)

        val (netOk, netMsg) = OfflineDownloadManager.checkNetworkRequirement(context, wifiOnly)
        if (!netOk) {
            return@withContext Result.failure(workDataOf("error" to (netMsg ?: "Ingen netværksforbindelse")))
        }

        // Round 10C Item 7: Read real file sizes and Last-Modified with HEAD requests before download
        val realSegments = storageManager.fetchRemoteSegmentsMetadata(client)

        val storageCheck = checkStorageForBRouterDownload(context, forceUpdate)
        if (!storageCheck.hasEnoughSpace) {
            return@withContext Result.failure(
                workDataOf(
                    "error" to (storageCheck.message ?: "Ikke nok fri lagerplads"),
                    KEY_REQUIRED_BYTES to storageCheck.requiredBytes
                )
            )
        }

        createNotificationChannel()
        try {
            setForeground(
                createForegroundInfo(
                    context.getString(R.string.notif_brouter_preparing),
                    0,
                    100
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground service", e)
        }

        val totalBytesExpected = storageManager.getTotalExpectedSizeBytes().coerceAtLeast(1L)
        var accumulatedDownloadedBytes = 0L
        var sessionNewBytes = 0L

        // Ensure assets are present too
        storageManager.ensureProfilesCopied(context)

        for (segment in realSegments) {
            if (isStopped) {
                Log.i(TAG, "Download worker cancelled by user or system.")
                return@withContext Result.failure()
            }

            val targetFile = File(segmentsDir, segment.filename)
            val tmpFile = File(segmentsDir, "${segment.filename}.tmp")

            // Round 10B Item 14: Skip already completed segments when resuming an interrupted multi-segment download
            if (!forceUpdate && targetFile.exists() && targetFile.length() > 1024L && !tmpFile.exists()) {
                accumulatedDownloadedBytes += segment.sizeBytes
                continue
            }

            val existingTmpBytes = if (tmpFile.exists() && tmpFile.length() > 0L) tmpFile.length() else 0L
            val url = "${BRouterSegments.DOWNLOAD_BASE_URL}${segment.filename}"
            Log.i(TAG, "Downloading segment ${segment.filename} from $url (resume offset=$existingTmpBytes)")

            try {
                val customFetcher = customSegmentFetcher
                val statusCode: Int
                val inputStream: InputStream

                if (customFetcher != null) {
                    val (code, bytes) = customFetcher(segment.filename, existingTmpBytes)
                    statusCode = code
                    inputStream = ByteArrayInputStream(bytes)
                } else {
                    val reqBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                        .get()
                    if (existingTmpBytes > 0L) {
                        reqBuilder.header("Range", "bytes=$existingTmpBytes-")
                    }
                    val response = client.newCall(reqBuilder.build()).execute()
                    statusCode = response.code
                    if (statusCode == 416 && tmpFile.exists()) {
                        response.close()
                        if (targetFile.exists()) targetFile.delete()
                        tmpFile.renameTo(targetFile)
                        accumulatedDownloadedBytes += segment.sizeBytes
                        continue
                    }
                    if (!response.isSuccessful && statusCode != 206) {
                        response.close()
                        Log.e(TAG, "Failed downloading ${segment.filename}: HTTP $statusCode")
                        return@withContext Result.failure(
                            workDataOf("error" to "HTTP $statusCode ved hentning af ${segment.filename}")
                        )
                    }
                    val body = response.body ?: run {
                        response.close()
                        return@withContext Result.failure()
                    }
                    inputStream = body.byteStream()
                }

                val isResumingPartial = statusCode == 206 && existingTmpBytes > 0L
                val outputStream = FileOutputStream(tmpFile, isResumingPartial)

                val buffer = ByteArray(32 * 1024)
                var read: Int
                var segBytesRead = if (isResumingPartial) existingTmpBytes else 0L

                inputStream.use { stream ->
                    outputStream.use { out ->
                        while (stream.read(buffer).also { read = it } != -1) {
                            if (isStopped || shouldInterruptAfterBytes?.let { sessionNewBytes >= it } == true) {
                                // Round 10B Item 14: Keep tmpFile on disk so interrupted downloads can resume!
                                out.flush()
                                return@withContext Result.failure(
                                    workDataOf(
                                        "error" to "Download sat på pause",
                                        KEY_BYTES_DOWNLOADED to (accumulatedDownloadedBytes + segBytesRead)
                                    )
                                )
                            }
                            out.write(buffer, 0, read)
                            segBytesRead += read
                            sessionNewBytes += read

                            val totalDownloaded = accumulatedDownloadedBytes + segBytesRead
                            val pct = ((totalDownloaded.toDouble() / totalBytesExpected.toDouble()) * 100)
                                .toInt()
                                .coerceIn(0, 100)

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
                                        buildNotification(
                                            context.getString(
                                                R.string.notif_brouter_progress_text,
                                                segment.filename,
                                                progressMb,
                                                totalMb
                                            ),
                                            pct,
                                            100
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        out.flush()
                    }
                }

                // Atomic rename on completion of segment
                if (tmpFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tmpFile.renameTo(targetFile)
                }

                accumulatedDownloadedBytes += segment.sizeBytes
                Log.i(TAG, "Successfully downloaded ${segment.filename} (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading ${segment.filename} (preserving partial .tmp for resume)", e)
                // Keep tmpFile intact so retry resumes from existing bytes!
                return@withContext Result.retry()
            }
        }

        // All segments downloaded successfully!
        storageManager.setLastUpdatedTimestamp(System.currentTimeMillis())

        try {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.notif_brouter_ready_title))
                    .setContentText(context.getString(R.string.notif_brouter_ready_text))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {}

        Result.success(
            workDataOf(
                KEY_BYTES_DOWNLOADED to accumulatedDownloadedBytes
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_brouter_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_brouter_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_brouter_downloading_title))
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
