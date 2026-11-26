package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.util.network.NetworkConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class TrailPhotoInfo(
    val title: String,
    val imageUrl: String,
    val author: String,
    val license: String,
    val commonsPageUrl: String
)

class TrailPhotoRepository(private val context: Context) {

    companion object {
        const val MAX_CACHE_BYTES = 50L * 1024L * 1024L // 50 MB
        private const val CACHE_DIR_NAME = "trail_photos"
        private const val ASSET_PATH = "catalogue/trail_photos.json"
    }

    val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /**
     * Reads bundled `trail_photos.json` asset for [trailId] (pre-resolved photo objects if present).
     */
    fun getBundledTrailPhotos(trailId: String): List<TrailPhotoInfo> {
        return try {
            val jsonText = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseBundledPhotosJson(jsonText, trailId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Reads bundled `trail_photos.json` asset for explicit Wikimedia Commons `"File:..."` titles.
     */
    fun getBundledPhotoFileTitles(trailId: String): List<String> {
        return try {
            val jsonText = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseBundledPhotoTitlesJson(jsonText, trailId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseBundledPhotoTitlesJson(jsonText: String, trailId: String): List<String> {
        return try {
            val root = JSONObject(jsonText)
            val trailsArr = root.optJSONArray("trails") ?: return emptyList()
            for (i in 0 until trailsArr.length()) {
                val trailObj = trailsArr.optJSONObject(i) ?: continue
                if (trailObj.optString("id") != trailId) continue
                val photosArr = trailObj.optJSONArray("photos") ?: return emptyList()
                val titles = mutableListOf<String>()
                for (p in 0 until photosArr.length()) {
                    val strItem = photosArr.optString(p)
                    val objItem = photosArr.optJSONObject(p)
                    val candidate = if (objItem != null) {
                        objItem.optString("title")
                    } else {
                        strItem
                    }
                    if (candidate.isNotBlank() && candidate.startsWith("File:", ignoreCase = true)) {
                        titles.add(candidate.trim())
                    }
                }
                return titles.take(2)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseBundledPhotosJson(jsonText: String, trailId: String): List<TrailPhotoInfo> {
        return try {
            val root = JSONObject(jsonText)
            val trailsArr = root.optJSONArray("trails") ?: return emptyList()
            for (i in 0 until trailsArr.length()) {
                val trailObj = trailsArr.optJSONObject(i) ?: continue
                if (trailObj.optString("id") != trailId) continue
                val photosArr = trailObj.optJSONArray("photos") ?: return emptyList()
                val list = mutableListOf<TrailPhotoInfo>()
                for (p in 0 until photosArr.length()) {
                    val pObj = photosArr.optJSONObject(p) ?: continue
                    val imageUrl = pObj.optString("imageUrl")
                    val author = pObj.optString("author")
                    val license = pObj.optString("license")
                    val commonsPageUrl = pObj.optString("commonsPageUrl")
                    val title = pObj.optString("title", imageUrl)
                    if (imageUrl.isNotBlank() && author.isNotBlank() && license.isNotBlank()) {
                        list.add(
                            TrailPhotoInfo(
                                title = title,
                                imageUrl = imageUrl,
                                author = author,
                                license = license,
                                commonsPageUrl = commonsPageUrl.ifBlank { imageUrl }
                            )
                        )
                    }
                }
                return list.take(2)
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves 1–2 photos for a trail from `trail_photos.json`:
     * first checks pre-resolved bundled entries, and if explicit `"File:..."` titles are configured,
     * resolves their image URL, author, and license from Wikimedia Commons using [NetworkConstants.USER_AGENT].
     */
    suspend fun getPhotosForTrail(
        trailId: String,
        connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
    ): List<TrailPhotoInfo> = withContext(Dispatchers.IO) {
        val bundled = getBundledTrailPhotos(trailId)
        if (bundled.isNotEmpty()) return@withContext bundled.take(2)

        val fileTitles = getBundledPhotoFileTitles(trailId)
        if (fileTitles.isEmpty()) return@withContext emptyList()

        fetchCommonsPhotosForTitles(fileTitles, connectionFactory).take(2)
    }

    suspend fun fetchCommonsPhotosForTitles(
        fileTitles: List<String>,
        connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
    ): List<TrailPhotoInfo> = withContext(Dispatchers.IO) {
        val validTitles = fileTitles.map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        if (validTitles.isEmpty()) return@withContext emptyList()
        try {
            val encodedTitles = URLEncoder.encode(validTitles.joinToString("|"), "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$encodedTitles&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=1080&format=json"
            val conn = connectionFactory(URL(apiUrl)).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", NetworkConstants.USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseCommonsApiQueryResponse(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseCommonsApiQueryResponse(jsonBody: String): List<TrailPhotoInfo> {
        return try {
            val root = JSONObject(jsonBody)
            val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            val results = mutableListOf<TrailPhotoInfo>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val page = pages.optJSONObject(key) ?: continue
                val title = page.optString("title")
                val imageInfoArr = page.optJSONArray("imageinfo") ?: continue
                val info = imageInfoArr.optJSONObject(0) ?: continue
                val thumbUrl = info.optString("thumburl").ifBlank { info.optString("url") }
                val descUrl = info.optString("descriptionurl").ifBlank { thumbUrl }
                val ext = info.optJSONObject("extmetadata") ?: continue
                val rawArtist = ext.optJSONObject("Artist")?.optString("value").orEmpty()
                val cleanArtist = rawArtist.replace(Regex("<[^>]*>"), "").trim()
                val license = ext.optJSONObject("LicenseShortName")?.optString("value").orEmpty().trim()
                if (thumbUrl.isNotBlank() && cleanArtist.isNotBlank() && license.isNotBlank()) {
                    results.add(
                        TrailPhotoInfo(
                            title = title,
                            imageUrl = thumbUrl,
                            author = cleanArtist,
                            license = license,
                            commonsPageUrl = descUrl
                        )
                    )
                }
            }
            results.take(2)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads or reads a cached photo file from `cacheDir/trail_photos` using [NetworkConstants.USER_AGENT],
     * and enforces the 50 MB LRU cap.
     */
    suspend fun loadOrFetchPhotoBitmap(
        imageUrl: String,
        connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = getCacheFileForUrl(imageUrl)
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext BitmapFactory.decodeFile(file.absolutePath)
        }
        try {
            val conn = connectionFactory(URL(imageUrl)).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", NetworkConstants.USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return@withContext null
            file.writeBytes(bytes)
            enforceCacheCap(MAX_CACHE_BYTES)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun getCacheFileForUrl(imageUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(cacheDir, "$hex.img")
    }

    fun enforceCacheCap(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (f in sortedOldestFirst) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
        }
    }
}
