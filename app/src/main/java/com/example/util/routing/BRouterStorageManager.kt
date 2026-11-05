package com.example.util.routing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Metadata for a 5x5 degree BRouter segment file (.rd5).
 */
data class BRouterSegmentInfo(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val sizeMb: Int,
    val name: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val remoteLastModifiedMillis: Long? = null
)

/**
 * Result of an HTTP HEAD check for a remote BRouter segment file.
 */
data class RemoteSegmentHeader(
    val filename: String,
    val contentLength: Long? = null,
    val lastModifiedMillis: Long? = null
)

/**
 * The 5 BRouter segment files covering Denmark (8-15.2 E, 54.5-57.8 N).
 */
object BRouterSegments {
    val DENMARK_SEGMENTS = listOf(
        BRouterSegmentInfo(
            id = "E5_N50",
            filename = "E5_N50.rd5",
            sizeBytes = 182381800L,
            sizeMb = 174,
            name = "Sønderjylland & grænseområde",
            minLat = 50.0,
            maxLat = 55.0,
            minLng = 5.0,
            maxLng = 10.0
        ),
        BRouterSegmentInfo(
            id = "E5_N55",
            filename = "E5_N55.rd5",
            sizeBytes = 33714981L,
            sizeMb = 32,
            name = "Vest- og Nordjylland",
            minLat = 55.0,
            maxLat = 60.0,
            minLng = 5.0,
            maxLng = 10.0
        ),
        BRouterSegmentInfo(
            id = "E10_N50",
            filename = "E10_N50.rd5",
            sizeBytes = 125067655L,
            sizeMb = 119,
            name = "Fyn syd, Lolland, Falster & Møn",
            minLat = 50.0,
            maxLat = 55.0,
            minLng = 10.0,
            maxLng = 15.0
        ),
        BRouterSegmentInfo(
            id = "E10_N55",
            filename = "E10_N55.rd5",
            sizeBytes = 64690139L,
            sizeMb = 62,
            name = "Fyn nord, Sjælland & Hovedstaden",
            minLat = 55.0,
            maxLat = 60.0,
            minLng = 10.0,
            maxLng = 15.0
        ),
        BRouterSegmentInfo(
            id = "E15_N55",
            filename = "E15_N55.rd5",
            sizeBytes = 28022040L,
            sizeMb = 27,
            name = "Østbornholm (Svaneke & Nexø)",
            minLat = 55.0,
            maxLat = 60.0,
            minLng = 15.0,
            maxLng = 20.0
        )
    )

    val TOTAL_SIZE_BYTES: Long = DENMARK_SEGMENTS.sumOf { it.sizeBytes } // 433,876,615 bytes
    const val TOTAL_SIZE_MB: Int = 414
    const val DOWNLOAD_BASE_URL: String = "https://brouter.de/brouter/segments4/"
}

/**
 * Manages offline BRouter storage: segment files (.rd5) and routing profiles (.brf + lookups.dat).
 */
class BRouterStorageManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("brouter_storage_prefs", Context.MODE_PRIVATE)

    val segmentsDir: File = File(appContext.filesDir, "brouter/segments4").apply { mkdirs() }
    val profilesDir: File = File(appContext.filesDir, "brouter/profiles").apply { mkdirs() }

    private val remoteHeadersCache = ConcurrentHashMap<String, RemoteSegmentHeader>()

    private val _versionMismatchFlow = MutableStateFlow(prefs.getBoolean(PREF_VERSION_MISMATCH, false))
    val versionMismatchFlow: StateFlow<Boolean> = _versionMismatchFlow.asStateFlow()

    private val _remoteMetadataUpdatedTrigger = MutableStateFlow(0L)
    val remoteMetadataUpdatedTrigger: StateFlow<Long> = _remoteMetadataUpdatedTrigger.asStateFlow()

    companion object {
        private const val TAG = "BRouterStorageManager"
        private const val PREF_LAST_UPDATED = "brouter_last_updated"
        private const val PREF_WIFI_ONLY = "brouter_wifi_only"
        private const val PREF_VERSION_MISMATCH = "brouter_version_mismatch"
        private const val PREF_HEAD_SIZE_PREFIX = "brouter_head_size_"
        private const val PREF_HEAD_LASTMOD_PREFIX = "brouter_head_lastmod_"

        const val UPDATE_AVAILABLE_LABEL = "Opdatering tilgængelig"
        const val VERSION_MISMATCH_LABEL = "Rutedata skal opdateres"

        /**
         * Test hook for intercepting HEAD requests per segment filename.
         */
        @Volatile
        var customHeadFetcher: ((filename: String) -> RemoteSegmentHeader?)? = null

        @Volatile
        private var instance: BRouterStorageManager? = null

        fun getInstance(context: Context): BRouterStorageManager {
            return instance ?: synchronized(this) {
                instance ?: BRouterStorageManager(context).also { instance = it }
            }
        }

        /**
         * Determines the BRouter 5x5 degree segment filename covering a coordinate.
         */
        fun getSegmentFilenameForCoordinate(lat: Double, lng: Double): String {
            val lonBase = (Math.floor(lng / 5.0) * 5).toInt()
            val latBase = (Math.floor(lat / 5.0) * 5).toInt()
            val lonPrefix = if (lonBase >= 0) "E$lonBase" else "W${-lonBase}"
            val latPrefix = if (latBase >= 0) "N$latBase" else "S${-latBase}"
            return "${lonPrefix}_${latPrefix}.rd5"
        }

        fun parseHttpDateMillis(headerValue: String?): Long? {
            if (headerValue.isNullOrBlank()) return null
            val patterns = listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val date = sdf.parse(headerValue.trim())
                    if (date != null) return date.time
                } catch (_: Exception) {}
            }
            return headerValue.trim().toLongOrNull()
        }
    }

    private val defaultHeadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .apply {
                if (com.example.BuildConfig.DEBUG) {
                    addInterceptor(com.example.util.network.MainThreadNetworkInterceptor())
                }
            }
            .build()
    }

    /**
     * Round 10C Item 7: Read real file sizes and Last-Modified with HEAD requests before download.
     */
    suspend fun fetchRemoteSegmentsMetadata(client: OkHttpClient = defaultHeadClient): List<BRouterSegmentInfo> =
        withContext(Dispatchers.IO) {
            val editor = prefs.edit()
            for (seg in BRouterSegments.DENMARK_SEGMENTS) {
                try {
                    val hookResult = customHeadFetcher?.invoke(seg.filename)
                    val header = if (hookResult != null) {
                        hookResult
                    } else {
                        val url = "${BRouterSegments.DOWNLOAD_BASE_URL}${seg.filename}"
                        val req = Request.Builder()
                            .url(url)
                            .head()
                            .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                            .build()
                        client.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val len = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                                val lastMod = response.headers.getDate("Last-Modified")?.time
                                    ?: parseHttpDateMillis(response.header("Last-Modified"))
                                RemoteSegmentHeader(
                                    filename = seg.filename,
                                    contentLength = len,
                                    lastModifiedMillis = lastMod
                                )
                            } else {
                                null
                            }
                        }
                    }
                    if (header != null) {
                        remoteHeadersCache[seg.filename] = header
                        header.contentLength?.takeIf { it > 0L }?.let {
                            editor.putLong("$PREF_HEAD_SIZE_PREFIX${seg.filename}", it)
                        }
                        header.lastModifiedMillis?.takeIf { it > 0L }?.let {
                            editor.putLong("$PREF_HEAD_LASTMOD_PREFIX${seg.filename}", it)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "HEAD request failed for ${seg.filename}: ${e.message}")
                }
            }
            editor.apply()
            _remoteMetadataUpdatedTrigger.value = System.currentTimeMillis()
            getSegmentsWithRealSizes()
        }

    fun getRemoteSegmentHeader(filename: String): RemoteSegmentHeader? {
        remoteHeadersCache[filename]?.let { return it }
        val size = prefs.getLong("$PREF_HEAD_SIZE_PREFIX$filename", -1L).takeIf { it > 0L }
        val lastMod = prefs.getLong("$PREF_HEAD_LASTMOD_PREFIX$filename", -1L).takeIf { it > 0L }
        if (size == null && lastMod == null) return null
        return RemoteSegmentHeader(filename = filename, contentLength = size, lastModifiedMillis = lastMod)
    }

    /**
     * Returns segment metadata populated with real Content-Length and Last-Modified from HEAD when available.
     */
    fun getSegmentsWithRealSizes(): List<BRouterSegmentInfo> {
        return BRouterSegments.DENMARK_SEGMENTS.map { seg ->
            val remote = getRemoteSegmentHeader(seg.filename)
            val realBytes = remote?.contentLength?.takeIf { it > 0L } ?: seg.sizeBytes
            val realMb = ((realBytes + 512L * 1024L) / (1024L * 1024L)).toInt().coerceAtLeast(1)
            seg.copy(
                sizeBytes = realBytes,
                sizeMb = realMb,
                remoteLastModifiedMillis = remote?.lastModifiedMillis
            )
        }
    }

    fun getTotalExpectedSizeBytes(): Long {
        return getSegmentsWithRealSizes().sumOf { it.sizeBytes }
    }

    fun getTotalExpectedSizeMb(): Int {
        val totalBytes = getTotalExpectedSizeBytes()
        return ((totalBytes + 512L * 1024L) / (1024L * 1024L)).toInt().coerceAtLeast(1)
    }

    /**
     * Round 10C Item 7: Returns true when downloaded local segment files are older than
     * the server's Last-Modified timestamps read via HEAD.
     */
    fun isUpdateAvailable(): Boolean {
        val downloaded = BRouterSegments.DENMARK_SEGMENTS.filter { isSegmentDownloaded(it.filename) }
        if (downloaded.isEmpty()) return false
        val prefLastUpdated = prefs.getLong(PREF_LAST_UPDATED, 0L)
        for (seg in downloaded) {
            val remoteLastMod = getRemoteSegmentHeader(seg.filename)?.lastModifiedMillis ?: continue
            val localFile = File(segmentsDir, seg.filename)
            val fileLastMod = if (localFile.exists()) localFile.lastModified() else 0L
            val localBaseline = when {
                prefLastUpdated > 0L && fileLastMod > 0L -> minOf(prefLastUpdated, fileLastMod)
                prefLastUpdated > 0L -> prefLastUpdated
                else -> fileLastMod
            }
            if (localBaseline > 0L && remoteLastMod > localBaseline) {
                return true
            }
        }
        return false
    }

    fun hasVersionMismatch(): Boolean = _versionMismatchFlow.value

    fun reportVersionMismatch(mismatch: Boolean) {
        prefs.edit().putBoolean(PREF_VERSION_MISMATCH, mismatch).apply()
        _versionMismatchFlow.value = mismatch
        _remoteMetadataUpdatedTrigger.value = System.currentTimeMillis()
    }

    fun clearRemoteMetadataCacheForTests() {
        remoteHeadersCache.clear()
        val editor = prefs.edit()
        for (seg in BRouterSegments.DENMARK_SEGMENTS) {
            editor.remove("$PREF_HEAD_SIZE_PREFIX${seg.filename}")
            editor.remove("$PREF_HEAD_LASTMOD_PREFIX${seg.filename}")
        }
        editor.remove(PREF_VERSION_MISMATCH)
        editor.apply()
        _versionMismatchFlow.value = false
        _remoteMetadataUpdatedTrigger.value = System.currentTimeMillis()
    }

    /**
     * Checks if a specific segment file is downloaded and present on disk.
     */
    fun isSegmentDownloaded(filename: String): Boolean {
        val file = File(segmentsDir, filename)
        return file.exists() && file.length() > 1024L
    }

    /**
     * Checks if both leg endpoints are covered by downloaded segment files.
     */
    fun areSegmentsAvailableForLeg(from: LatLng, to: LatLng): Boolean {
        val segFrom = getSegmentFilenameForCoordinate(from.lat, from.lng)
        val segTo = getSegmentFilenameForCoordinate(to.lat, to.lng)
        return isSegmentDownloaded(segFrom) && isSegmentDownloaded(segTo)
    }

    /**
     * Checks if all 5 Danish segment files are downloaded.
     */
    fun areAllDenmarkSegmentsDownloaded(): Boolean {
        return BRouterSegments.DENMARK_SEGMENTS.all { isSegmentDownloaded(it.filename) }
    }

    /**
     * Returns the set of filenames for all currently downloaded Danish segments.
     */
    fun getDownloadedSegments(): Set<String> {
        return BRouterSegments.DENMARK_SEGMENTS
            .filter { isSegmentDownloaded(it.filename) }
            .map { it.filename }
            .toSet()
    }

    /**
     * Returns total disk bytes occupied by downloaded segments.
     */
    fun getDownloadedSizeBytes(): Long {
        return BRouterSegments.DENMARK_SEGMENTS
            .map { File(segmentsDir, it.filename) }
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    /**
     * Returns the timestamp when routing data was last downloaded or updated.
     */
    fun getLastUpdatedTimestamp(): Long? {
        val ts = prefs.getLong(PREF_LAST_UPDATED, 0L)
        return if (ts > 0L) ts else {
            // Fallback to latest modified file timestamp if present
            val files = BRouterSegments.DENMARK_SEGMENTS.map { File(segmentsDir, it.filename) }.filter { it.exists() }
            if (files.isNotEmpty()) files.maxOf { it.lastModified() } else null
        }
    }

    /**
     * Formats the last updated date string in Danish format.
     */
    fun getFormattedLastUpdatedDate(): String? {
        val ts = getLastUpdatedTimestamp() ?: return null
        val sdf = SimpleDateFormat("d. MMM yyyy", Locale("da", "DK"))
        return sdf.format(Date(ts))
    }

    fun setLastUpdatedTimestamp(timestamp: Long) {
        prefs.edit()
            .putLong(PREF_LAST_UPDATED, timestamp)
            .putBoolean(PREF_VERSION_MISMATCH, false)
            .apply()
        _versionMismatchFlow.value = false
        for (seg in BRouterSegments.DENMARK_SEGMENTS) {
            val f = File(segmentsDir, seg.filename)
            if (f.exists()) {
                runCatching { f.setLastModified(timestamp) }
            }
        }
        _remoteMetadataUpdatedTrigger.value = System.currentTimeMillis()
    }

    fun isWifiOnlyDefault(): Boolean {
        return prefs.getBoolean(PREF_WIFI_ONLY, true)
    }

    fun setWifiOnlyDefault(wifiOnly: Boolean) {
        prefs.edit().putBoolean(PREF_WIFI_ONLY, wifiOnly).apply()
    }

    /**
     * Ensures that dansk-vandring.brf and lookups.dat are copied from assets to the profiles directory
     * whenever their content differs from the copy on disk (comparing SHA-256 hashes), not only when missing.
     */
    fun ensureProfilesCopied(context: Context) {
        copyAssetIfDifferent(context, "brouter/dansk-vandring.brf", File(profilesDir, "dansk-vandring.brf"))
        copyAssetIfDifferent(context, "brouter/lookups.dat", File(profilesDir, "lookups.dat"))
    }

    private fun copyAssetIfDifferent(context: Context, assetPath: String, destFile: File) {
        try {
            val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
            val assetHash = sha256Hex(assetBytes)

            val diskHash = if (destFile.exists() && destFile.length() > 0L) {
                try {
                    sha256Hex(destFile.readBytes())
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            if (assetHash == diskHash) {
                return
            }

            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { output ->
                output.write(assetBytes)
            }
            Log.i(TAG, "Copied asset $assetPath to ${destFile.absolutePath} (hash=$assetHash)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying asset $assetPath", e)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
