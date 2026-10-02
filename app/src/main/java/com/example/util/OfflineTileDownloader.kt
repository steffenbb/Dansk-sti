package com.example.util

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

data class DownloadProgress(
    val isDownloading: Boolean = false,
    val downloadedCount: Int = 0,
    val totalCount: Int = 0,
    val percent: Int = 0,
    val isComplete: Boolean = false,
    val errorMessage: String? = null
)

object OfflineTileDownloader {

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "DanskStiApp/1.0 (Android; Offline Hike Cache)")
                .build()
            chain.proceed(req)
        }
        .build()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /**
     * Downloads OSM tiles along the route corridor for zoom levels 11..16
     */
    suspend fun downloadRouteTiles(
        context: Context,
        points: List<GeoPointDto>
    ): Boolean = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext false

        _progress.value = DownloadProgress(isDownloading = true, downloadedCount = 0, totalCount = 0, percent = 0)

        // Find unique tiles across zoom levels 11 to 16
        val tileCoords = mutableSetOf<Triple<Int, Int, Int>>() // zoom, x, y

        for (z in 11..16) {
            for (p in points) {
                // Convert lat/lng to tile x/y
                val (tx, ty) = getTileXY(p.lat, p.lng, z)
                tileCoords.add(Triple(z, tx, ty))
                // Add adjacent 1-tile margin (~corridor)
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        tileCoords.add(Triple(z, tx + dx, ty + dy))
                    }
                }
            }
        }

        // Limit maximum tiles to download to prevent bandwidth overload (max 300 tiles per trail package)
        val tilesToDownload = tileCoords.take(300)
        val total = tilesToDownload.size
        _progress.value = DownloadProgress(isDownloading = true, downloadedCount = 0, totalCount = total, percent = 0)

        val cacheBase = Configuration.getInstance().osmdroidTileCache ?: File(context.cacheDir, "osmdroid/tiles")
        val mapnikDir = File(cacheBase, "Mapnik")
        if (!mapnikDir.exists()) mapnikDir.mkdirs()

        var downloaded = 0
        var errors = 0

        for (t in tilesToDownload) {
            val z = t.first
            val x = t.second
            val y = t.third

            val tileFile = File(mapnikDir, "$z/$x/$y.png")
            if (tileFile.exists() && tileFile.length() > 0) {
                downloaded++
                val pct = if (total > 0) (downloaded * 100) / total else 100
                _progress.value = _progress.value.copy(downloadedCount = downloaded, percent = pct)
                continue
            }

            val tileUrl = "https://tile.openstreetmap.org/$z/$x/$y.png"
            try {
                val req = Request.Builder().url(tileUrl).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        tileFile.parentFile?.mkdirs()
                        FileOutputStream(tileFile).use { it.write(bytes) }
                    }
                } else {
                    errors++
                }
                resp.close()
            } catch (e: Exception) {
                errors++
                Log.w("TileDownloader", "Failed downloading tile $z/$x/$y: ${e.message}")
            }

            downloaded++
            val pct = if (total > 0) (downloaded * 100) / total else 100
            _progress.value = _progress.value.copy(downloadedCount = downloaded, percent = pct)
        }

        _progress.value = DownloadProgress(
            isDownloading = false,
            downloadedCount = downloaded,
            totalCount = total,
            percent = 100,
            isComplete = true,
            errorMessage = if (errors > 0) "$errors felter kunne ikke hentes" else null
        )
        true
    }

    private fun getTileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val y = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    fun resetProgress() {
        _progress.value = DownloadProgress()
    }
}
