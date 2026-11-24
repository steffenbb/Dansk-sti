package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        const val APP_USER_AGENT = "DanskSti/1.0 (Android; https://dansksti.example.com)"
        const val MAX_CACHE_BYTES = 50L * 1024L * 1024L // 50 MB
        private const val CACHE_DIR_NAME = "trail_photos"
        private const val ASSET_PATH = "catalogue/trail_photos.json"
    }

    val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /**
     * Reads bundled `trail_photos.json` asset for [trailId].
     */
    fun getBundledTrailPhotos(trailId: String): List<TrailPhotoInfo> {
        return try {
            val jsonText = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseBundledPhotosJson(jsonText, trailId)
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
     * Resolves 1–2 photos for a trail: first checks bundled `trail_photos.json`,
     * and if empty and [wikipediaTag] or [wikidataTag] is available on the trail,
     * queries Wikimedia Commons via the app's User-Agent.
     */
    suspend fun getPhotosForTrail(
        trailId: String,
        wikipediaTag: String? = null,
        wikidataTag: String? = null
    ): List<TrailPhotoInfo> = withContext(Dispatchers.IO) {
        val bundled = getBundledTrailPhotos(trailId)
        if (bundled.isNotEmpty()) return@withContext bundled.take(2)

        val searchTitle = wikipediaTag?.substringAfter(":")?.takeIf { it.isNotBlank() }
            ?: wikidataTag?.takeIf { it.isNotBlank() }
            ?: return@withContext emptyList()

        fetchCommonsPhotosForQuery(searchTitle).take(2)
    }

    suspend fun fetchCommonsPhotosForQuery(
        queryTitle: String,
        connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
    ): List<TrailPhotoInfo> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(queryTitle, "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$encoded&gsrnamespace=6&gsrlimit=2&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=800&format=json"
            val conn = connectionFactory(URL(apiUrl)).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", APP_USER_AGENT)
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
     * Downloads or reads a cached photo file from `cacheDir/trail_photos` using the app's User-Agent,
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
                setRequestProperty("User-Agent", APP_USER_AGENT)
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
