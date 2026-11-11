package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.local.BaseMapType
import com.example.data.local.MapLayersState
import com.example.util.network.applyDebugMainThreadCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.ReusableBitmapDrawable
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.TilesOverlay
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Result of parsing Skærmkortet WMS GetCapabilities (Requirement B).
 */
data class SkaermkortCapabilities(
    val allLayerNames: List<String>,
    val mutedOrGreyLayerName: String?,
    val supportsEpsg3857: Boolean
)

/**
 * Result of parsing Dataforsyningen DHM ("dhm" / "dhm_DAF") WMS GetCapabilities (Requirement C).
 */
data class DhmCapabilities(
    val serviceEndpoint: String,
    val allLayerNames: List<String>,
    val hillshadeLayerName: String?,
    val contourLayerName: String?,
    val supportsEpsg3857: Boolean,
    val supportsTransparentPng: Boolean
)

/**
 * Shared Danish Skærmkortet tile provider for all MapViews in Dansk Sti.
 * Uses official Dataforsyningen / SDFI WMS in Web Mercator (EPSG:3857).
 *
 * Round 9B Item 5:
 * - No `createHuePreservingDarkColorMatrix` on the Skærmkort layer.
 * - In `getDrawable(InputStream)`, when `isDarkMode` is on, pixels are recoloured on a background thread
 *   using reusable bitmaps while keeping the downloaded/cached bytes unchanged:
 *   - colours close to the map's water colour -> dark-mode water blue (`#1F4E6E`)
 *   - light land -> dark neutral grey (`#24282B`)
 *   - dark text and lines -> light grey (`#DCE0E3`)
 */
class DanskStiTileSource(
    private val apiKey: String,
    val wmsLayerName: String = "dtk_skaermkort",
    val isDarkMode: Boolean = false,
    tileSourceName: String = when {
        isDarkMode && wmsLayerName == "dtk_skaermkort" -> "DanskSti_Skaermkort_Dark"
        wmsLayerName == "dtk_skaermkort" -> "DanskSti_Skaermkort"
        isDarkMode -> "DanskSti_Skaermkort_${wmsLayerName}_Dark"
        else -> "DanskSti_Skaermkort_$wmsLayerName"
    }
) : OnlineTileSourceBase(
    tileSourceName,
    7,
    18,
    256,
    ".jpg",
    arrayOf("https://api.dataforsyningen.dk/topo_skaermkort_DAF")
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val bbox = DanskStiTileSourceHelper.computeWebMercatorBbox(pMapTileIndex)
        return "https://api.dataforsyningen.dk/topo_skaermkort_DAF?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/jpeg&LAYERS=$wmsLayerName&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
    }

    override fun getDrawable(aFilePath: String?): Drawable? {
        if (aFilePath.isNullOrBlank()) return null
        val file = File(aFilePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            FileInputStream(file).use { stream ->
                getDrawable(stream)
            }
        } catch (e: Exception) {
            Log.w("DanskStiTileSource", "Failed to decode tile from path $aFilePath: ${e.message}")
            null
        }
    }

    override fun getDrawable(aFileInputStream: InputStream?): Drawable? {
        if (aFileInputStream == null) return null
        return try {
            // Read stream bytes into memory without modifying the underlying cached file or stream source
            val rawBytes = aFileInputStream.readBytes()
            if (rawBytes.isEmpty()) return null

            val processBlock: () -> Drawable? = {
                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (decoded == null) {
                    null
                } else if (!isDarkMode) {
                    ReusableBitmapDrawable(decoded)
                } else {
                    val recolored = DanskStiTileSourceHelper.recolorBitmapForDarkMap(decoded, BaseMapType.SKAERMKORT)
                    if (recolored !== decoded) {
                        DanskStiTileSourceHelper.recycleBitmapToPool(decoded)
                    }
                    ReusableBitmapDrawable(recolored)
                }
            }

            // Ensure pixel recolouring always runs on a background thread
            if (isDarkMode && Looper.myLooper() == Looper.getMainLooper()) {
                runBlocking(Dispatchers.Default) {
                    processBlock()
                }
            } else {
                processBlock()
            }
        } catch (e: Exception) {
            Log.w("DanskStiTileSource", "Failed to decode/recolour Skærmkort tile: ${e.message}")
            null
        }
    }

    companion object {
        const val ATTRIBUTION_TEXT = DanskStiTileSourceHelper.ATTRIBUTION_TEXT

        val sharedKey: String
            get() = DanskStiTileSourceHelper.sharedKey

        val hasValidKey: Boolean
            get() = DanskStiTileSourceHelper.hasValidKey

        val instance: DanskStiTileSource
            get() = DanskStiTileSourceHelper.skaermkortInstance

        var userConfirmedOsmFallback: Boolean
            get() = DanskStiTileSourceHelper.userConfirmedOsmFallback
            set(value) {
                DanskStiTileSourceHelper.userConfirmedOsmFallback = value
            }

        fun applyTo(
            mapView: MapView,
            isDarkMode: Boolean = false,
            allowOsmFallback: Boolean = userConfirmedOsmFallback
        ) {
            DanskStiTileSourceHelper.applyTo(
                mapView = mapView,
                baseMap = BaseMapType.SKAERMKORT,
                isDarkMode = isDarkMode,
                allowOsmFallback = allowOsmFallback
            )
        }

        fun applyTo(
            mapView: MapView,
            baseMap: BaseMapType,
            isDarkMode: Boolean = false,
            allowOsmFallback: Boolean = userConfirmedOsmFallback
        ) {
            DanskStiTileSourceHelper.applyTo(
                mapView = mapView,
                baseMap = baseMap,
                isDarkMode = isDarkMode,
                allowOsmFallback = allowOsmFallback
            )
        }
    }
}

/**
 * Udendørs ("Friluftskort") tile provider:
 * Uses Thunderforest Outdoors when API key is configured, otherwise OpenTopoMap.
 */
class DanskStiFriluftskortTileSource(
    private val apiKey: String,
    val isDarkMode: Boolean = false
) : OnlineTileSourceBase(
    if (isDarkMode) "DanskSti_Friluftskort_Dark" else "DanskSti_Friluftskort",
    5,
    18,
    256,
    ".png",
    if (apiKey.isNotBlank() && apiKey != "DEFAULT_KEY") {
        arrayOf(
            "https://a.tile.thunderforest.com/outdoors/",
            "https://b.tile.thunderforest.com/outdoors/",
            "https://c.tile.thunderforest.com/outdoors/"
        )
    } else {
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        )
    }
) {
    val isThunderforest: Boolean = apiKey.isNotBlank() && apiKey != "DEFAULT_KEY"

    init {
        Log.i("DanskStiTileSource", "Initialized Udendørs/Friluftskort with provider: ${if (isThunderforest) "Thunderforest Outdoors" else "OpenTopoMap"}")
    }

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        return if (isThunderforest) {
            "${getBaseUrl()}$zoom/$x/$y.png?apikey=$apiKey"
        } else {
            "${getBaseUrl()}$zoom/$x/$y.png"
        }
    }

    override fun getDrawable(aFilePath: String?): Drawable? {
        if (aFilePath.isNullOrBlank()) return null
        val file = File(aFilePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            FileInputStream(file).use { getDrawable(it) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getDrawable(aFileInputStream: InputStream?): Drawable? {
        if (aFileInputStream == null) return null
        return try {
            val rawBytes = aFileInputStream.readBytes()
            if (rawBytes.isEmpty()) return null
            val block: () -> Drawable? = {
                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (decoded == null) null
                else if (!isDarkMode) ReusableBitmapDrawable(decoded)
                else {
                    val recolored = DanskStiTileSourceHelper.recolorBitmapForDarkMap(decoded, BaseMapType.FRILUFTSKORT)
                    if (recolored !== decoded) DanskStiTileSourceHelper.recycleBitmapToPool(decoded)
                    ReusableBitmapDrawable(recolored)
                }
            }
            if (isDarkMode && Looper.myLooper() == Looper.getMainLooper()) {
                runBlocking(Dispatchers.Default) { block() }
            } else {
                block()
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Luftfoto tile provider:
 * Official GeoDanmark Ortofoto (forår) from Dataforsyningen / Klimadatastyrelsen in EPSG:3857.
 * Round 9B Item 8: Aerial photos stay completely unfiltered in both light and dark mode.
 */
class DanskStiLuftfotoTileSource(
    private val apiKey: String
) : OnlineTileSourceBase(
    "DanskSti_Luftfoto",
    7,
    19,
    256,
    ".jpg",
    arrayOf("https://api.dataforsyningen.dk/orto_foraar_DAF")
) {
    init {
        Log.i("DanskStiTileSource", "Initialized GeoDanmark Ortofoto WMS in EPSG:3857 (layer=orto_foraar)")
    }

    override fun getTileURLString(pMapTileIndex: Long): String {
        val bbox = DanskStiTileSourceHelper.computeWebMercatorBbox(pMapTileIndex)
        return "https://api.dataforsyningen.dk/orto_foraar_DAF?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/jpeg&LAYERS=orto_foraar&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
    }
}

/**
 * Requirement C & Round 9B Item 7: Dataforsyningen DHM Terrain Hillshade tile source ("dhm_terraen_skyggekort").
 * Uses WMS 1.1.1 in EPSG:3857 with transparent PNG.
 * Skips hillshade pixels with no data / sea / flat grey so the terrain overlay is never opaque over sea.
 */
class DanskStiDhmHillshadeTileSource(
    private val apiKey: String,
    val layerName: String = "dhm_terraen_skyggekort",
    val serviceEndpoint: String = "https://api.dataforsyningen.dk/dhm_DAF"
) : OnlineTileSourceBase(
    "DanskSti_DHM_Hillshade",
    7,
    18,
    256,
    ".png",
    arrayOf(serviceEndpoint)
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val bbox = DanskStiTileSourceHelper.computeWebMercatorBbox(pMapTileIndex)
        return "$serviceEndpoint?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/png&TRANSPARENT=TRUE&LAYERS=$layerName&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
    }

    override fun getDrawable(aFilePath: String?): Drawable? {
        if (aFilePath.isNullOrBlank()) return null
        val file = File(aFilePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            FileInputStream(file).use { getDrawable(it) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getDrawable(aFileInputStream: InputStream?): Drawable? {
        if (aFileInputStream == null) return null
        return try {
            val rawBytes = aFileInputStream.readBytes()
            if (rawBytes.isEmpty()) return null
            val block: () -> Drawable? = {
                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (decoded == null) {
                    null
                } else {
                    val processed = DanskStiTileSourceHelper.processHillshadeBitmap(decoded)
                    if (processed !== decoded) {
                        DanskStiTileSourceHelper.recycleBitmapToPool(decoded)
                    }
                    ReusableBitmapDrawable(processed)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runBlocking(Dispatchers.Default) { block() }
            } else {
                block()
            }
        } catch (e: Exception) {
            Log.w("DanskStiTileSource", "Failed to process DHM hillshade tile: ${e.message}")
            null
        }
    }
}

/**
 * Requirement C: Dataforsyningen DHM Contour tile source ("dhm_kurve_traditionel").
 * Uses WMS 1.1.1 in EPSG:3857 with transparent PNG from zoom 14+.
 */
class DanskStiDhmContourTileSource(
    private val apiKey: String,
    val layerName: String = "dhm_kurve_traditionel",
    val serviceEndpoint: String = "https://api.dataforsyningen.dk/dhm_DAF"
) : OnlineTileSourceBase(
    "DanskSti_DHM_Contours",
    14,
    18,
    256,
    ".png",
    arrayOf(serviceEndpoint)
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val bbox = DanskStiTileSourceHelper.computeWebMercatorBbox(pMapTileIndex)
        return "$serviceEndpoint?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/png&TRANSPARENT=TRUE&LAYERS=$layerName&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
    }
}

/**
 * Requirement C & Round 9B Item 7: Custom TilesOverlay for DHM Hillshade (alpha 30% light / 45% dark)
 * and DHM Contours (from zoom 14 with alpha 60%).
 *
 * Notice: The previous `hillshadeReliefFilter` had `+455f` on the alpha row (`-0.8*R - 0.8*G - 0.8*B + 455`),
 * which turned transparent `(0,0,0,0)` sea pixels into opaque black `ARGB(255,0,0,0)`!
 * Now `DanskStiDhmHillshadeTileSource` skips no-data/sea/flat pixels directly in `processHillshadeBitmap`,
 * and `TerrainTilesOverlay` never applies a blackening ColorMatrix over sea.
 */
class TerrainTilesOverlay(
    tileProvider: MapTileProviderBase,
    context: Context,
    val isHillshade: Boolean,
    val minZoom: Double = 0.0
) : TilesOverlay(tileProvider, context) {

    var overlayAlpha: Float = if (isHillshade) 0.30f else 0.60f
    var isDarkMode: Boolean = false
        set(value) {
            field = value
            updateFilter()
        }

    // In dark mode, brighten brown contour lines slightly so they are clearly legible on dark terrain
    private val darkContourFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1.4f, 0f, 0f, 0f, 70f,
                0f, 1.4f, 0f, 0f, 55f,
                0f, 0f, 1.4f, 0f, 35f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    init {
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
        updateFilter()
    }

    private fun updateFilter() {
        if (isHillshade) {
            overlayAlpha = if (isDarkMode) 0.45f else 0.30f
            setColorFilter(null)
        } else {
            overlayAlpha = 0.60f
            setColorFilter(if (isDarkMode) darkContourFilter else null)
        }
    }

    override fun draw(c: Canvas, pProjection: Projection) {
        if (!isEnabled) return
        if (pProjection.zoomLevel < minZoom) return
        super.draw(c, pProjection)
    }

    override fun onTileReadyToDraw(c: Canvas, currentMapTile: Drawable, tileRect: Rect) {
        val alphaInt = (overlayAlpha * 255f).roundToInt().coerceIn(0, 255)
        currentMapTile.alpha = alphaInt
        super.onTileReadyToDraw(c, currentMapTile, tileRect)
    }
}

object DanskStiTileSourceHelper {
    private const val TAG = "DanskStiTileSource"
    const val ATTRIBUTION_TEXT = "Klimadatastyrelsen / Skærmkortet • © OpenStreetMap-bidragydere"

    /** Theme water colours (Round 9B Items 3, 5, 6 & Round 10B Item 1) */
    const val LIGHT_WATER_COLOR_INT: Int = 0xFFA3CDE3.toInt() // #A3CDE3
    const val DARK_WATER_COLOR_INT: Int = 0xFF1F4E6E.toInt()  // #1F4E6E
    const val DARK_LAND_COLOR_INT: Int = 0xFF24282B.toInt()   // #24282B dark neutral grey (lum 165)
    const val DARK_LAND_MAX_COLOR_INT: Int = 0xFF4A5056.toInt() // #4A5056 lighter dark grey (lum 255)
    const val DARK_TEXT_COLOR_INT: Int = 0xFFDCE0E3.toInt()   // #DCE0E3 light grey for text & lines
    const val DARK_FOREST_COLOR_INT: Int = 0xFF1F3628.toInt() // #1F3628 dark forest green-grey

    fun getThemeWaterColorInt(isDarkMode: Boolean): Int =
        if (isDarkMode) DARK_WATER_COLOR_INT else LIGHT_WATER_COLOR_INT

    private const val MAX_BITMAP_POOL_SIZE = 32
    private val bitmapPool = ArrayDeque<Bitmap>(MAX_BITMAP_POOL_SIZE)
    private val reusedBitmapCounter = AtomicInteger(0)

    val reusedBitmapCount: Int
        get() = reusedBitmapCounter.get()

    private val threadLocalPixels = object : ThreadLocal<IntArray>() {
        override fun initialValue(): IntArray = IntArray(256 * 256)
    }

    fun acquireReusableBitmap(width: Int = 256, height: Int = 256): Bitmap {
        synchronized(bitmapPool) {
            val iterator = bitmapPool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.isRecycled) {
                    iterator.remove()
                } else if (candidate.isMutable && candidate.width == width && candidate.height == height &&
                    candidate.config == Bitmap.Config.ARGB_8888
                ) {
                    iterator.remove()
                    reusedBitmapCounter.incrementAndGet()
                    return candidate
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    fun recycleBitmapToPool(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
            return
        }
        synchronized(bitmapPool) {
            if (bitmapPool.size < MAX_BITMAP_POOL_SIZE && !bitmapPool.contains(bitmap)) {
                bitmapPool.addLast(bitmap)
            }
        }
    }

    /**
     * Round 10B Item 1:
     * Recolours a single ARGB pixel for the dark map with base-map-specific rules:
     * - Colours close to the map's water colour -> dark-mode water blue (`#1F4E6E`)
     * - Forest / green areas -> only when clearly green (saturation above threshold per base map),
     *   so towns on "Udendørs" (`BaseMapType.FRILUFTSKORT`) are never painted as forest.
     * - Saturated colours (roads, coloured areas) -> darker version of the same hue
     *   (brightness ~45%, saturation ~80%).
     * - Light land (`lum 165..255`) -> mapped linearly to `#24282B..#4A5056`, keeping strict order
     *   so road fills stand out against surrounding land.
     * - Dark text and lines -> light grey (`#DCE0E3`).
     */
    fun recolorPixelForDarkMap(
        color: Int,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT
    ): Int {
        val a = (color ushr 24) and 0xFF
        if (a == 0) return 0
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        // 1. Water detection:
        // Sea, fjords, lakes, and streams have blue dominance (B > R and B >= G - 3) with B >= 110.
        val isWater = (b >= 110 && b > r + 4 && b >= g - 3 && (b - r) >= 6) ||
            (r in 140..225 && g in 185..242 && b in 210..255 && b > r + 4 && b >= g - 2)
        if (isWater) {
            return (a shl 24) or (DARK_WATER_COLOR_INT and 0x00FFFFFF)
        }

        val lum = (r * 77 + g * 150 + b * 29) shr 8
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        val sat = if (maxC == 0) 0f else delta.toFloat() / maxC.toFloat()

        // 2. Separate rules per base map for forest detection:
        // Only treat a pixel as forest when it is clearly green (saturation above a threshold),
        // so lightly tinted towns/residential areas on "Udendørs" (FRILUFTSKORT) are not painted as forest.
        val isClearlyForest = when (baseMap) {
            BaseMapType.SKAERMKORT -> {
                g > r + 10 && g > b + 12 && sat >= 0.09f && lum > 65
            }
            BaseMapType.FRILUFTSKORT -> {
                // On OpenTopoMap / Thunderforest Outdoors, towns have warm/yellow-grey tints;
                // require true green dominance (g > r + 18, g > b + 16) and higher saturation (sat >= 0.18f).
                g > r + 18 && g > b + 16 && sat >= 0.18f && lum > 65
            }
            BaseMapType.LUFTFOTO -> false
        }
        if (isClearlyForest) {
            return (a shl 24) or (DARK_FOREST_COLOR_INT and 0x00FFFFFF)
        }

        // 3. Keep saturated colours (roads, coloured areas) as a darker version of the same hue
        // (brightness about 45%, saturation about 80%):
        val minRoadSat = if (baseMap == BaseMapType.FRILUFTSKORT) 0.24f else 0.22f
        if (sat >= minRoadSat && lum >= 65 && delta > 0) {
            val h = when (maxC) {
                r -> {
                    val raw = (g - b).toFloat() / delta.toFloat()
                    if (raw < 0f) raw + 6f else raw
                }
                g -> ((b - r).toFloat() / delta.toFloat()) + 2f
                else -> ((r - g).toFloat() / delta.toFloat()) + 4f
            }
            val targetVal = 0.45f
            val targetSat = 0.80f
            val c = targetVal * targetSat
            val x = c * (1f - kotlin.math.abs((h % 2f) - 1f))
            val m = targetVal - c
            val sector = h.toInt().coerceIn(0, 5)
            val r1: Float
            val g1: Float
            val b1: Float
            when (sector) {
                0 -> { r1 = c; g1 = x; b1 = 0f }
                1 -> { r1 = x; g1 = c; b1 = 0f }
                2 -> { r1 = 0f; g1 = c; b1 = x }
                3 -> { r1 = 0f; g1 = x; b1 = c }
                4 -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }
            val outR = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val outG = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val outB = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        // 4. Light land (lum 165..255) -> map to #24282B..#4A5056, keeping order so road fills stand out
        if (lum >= 165) {
            val offset = (lum - 165).coerceIn(0, 90)
            // #24282B = (36, 40, 43), #4A5056 = (74, 80, 86)
            val outR = 36 + (offset * (74 - 36)) / 90
            val outG = 40 + (offset * (80 - 40)) / 90
            val outB = 43 + (offset * (86 - 43)) / 90
            return (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        // 5. Dark text and lines -> light grey (#DCE0E3)
        if (lum <= 120) {
            return (a shl 24) or (DARK_TEXT_COLOR_INT and 0x00FFFFFF)
        }

        // 6. Anti-aliased transition pixels (120 < lum < 165): interpolate smoothly between
        // light grey (#DCE0E3: 220,224,227) and dark neutral grey (#24282B: 36,40,43)
        val t = (lum - 120) // 1..44 out of 45
        val outR = 220 - (t * (220 - 36)) / 45
        val outG = 224 - (t * (224 - 40)) / 45
        val outB = 227 - (t * (227 - 43)) / 45
        return (a shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    /**
     * Backwards-compatible alias used by tests.
     */
    fun transformColorForDarkMap(
        color: Int,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT
    ): Int = recolorPixelForDarkMap(color, baseMap)

    /**
     * Recolours an entire tile Bitmap for dark mode using the reusable bitmap pool and thread-local pixel buffer.
     */
    fun recolorBitmapForDarkMap(
        source: Bitmap,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT
    ): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = if (count <= 256 * 256) {
            threadLocalPixels.get() ?: IntArray(count)
        } else {
            IntArray(count)
        }
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in 0 until count) {
            pixels[i] = recolorPixelForDarkMap(pixels[i], baseMap)
        }
        val out = acquireReusableBitmap(width, height)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * Round 9B Item 7:
     * Processes a DHM hillshade tile bitmap so that sea / no-data / flat pixels are skipped (transparent `0x00000000`),
     * preventing the terrain overlay from ever turning the sea black or opaque.
     */
    fun processHillshadeBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = if (count <= 256 * 256) {
            threadLocalPixels.get() ?: IntArray(count)
        } else {
            IntArray(count)
        }
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in 0 until count) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8

            // Skip no-data transparent pixels, solid-black sea/no-data pixels (lum < 38),
            // flat/neutral terrain & sea surface (lum >= 175), or blue-tinted water pixels (b > r + 6)
            if (a < 32 || lum < 38 || lum >= 175 || b > r + 6) {
                pixels[i] = 0x00000000
            } else {
                // Proportional slope shadow alpha for genuine hillslopes (38 <= lum < 175)
                val shadowAlpha = (((175 - lum) * 140) / 137).coerceIn(0, 140)
                pixels[i] = (shadowAlpha shl 24) or (20 shl 16) or (24 shl 8) or 22
            }
        }
        val out = acquireReusableBitmap(width, height)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private val capabilitiesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .applyDebugMainThreadCheck()
            .build()
    }

    val sharedKey: String by lazy {
        try {
            val dataKey = BuildConfig.DATAFORSYNINGEN_API_KEY
            val skmKey = BuildConfig.SKM_API_KEY
            when {
                dataKey.isNotBlank() && dataKey != "DEFAULT_KEY" -> dataKey
                skmKey.isNotBlank() && skmKey != "DEFAULT_KEY" -> skmKey
                else -> ""
            }
        } catch (e: Throwable) {
            ""
        }
    }

    val outdoorKey: String by lazy {
        try {
            val tfKey = BuildConfig.THUNDERFOREST_API_KEY
            val outKey = BuildConfig.OUTDOOR_MAP_API_KEY
            when {
                tfKey.isNotBlank() && tfKey != "DEFAULT_KEY" -> tfKey
                outKey.isNotBlank() && outKey != "DEFAULT_KEY" -> outKey
                else -> ""
            }
        } catch (e: Throwable) {
            ""
        }
    }

    val hasValidKey: Boolean
        get() = sharedKey.isNotBlank()

    // Discovered WMS layer names from GetCapabilities (with verified defaults before async discovery finishes)
    @Volatile
    var discoveredSkaermkortLayers: List<String> = emptyList()
        private set

    @Volatile
    var selectedDarkSkaermkortLayer: String = "dtk_skaermkort_daempet"
        private set

    @Volatile
    var discoveredDhmLayers: List<String> = emptyList()
        private set

    @Volatile
    var selectedHillshadeLayer: String? = "dhm_terraen_skyggekort"
        private set

    @Volatile
    var selectedContourLayer: String? = "dhm_kurve_traditionel"
        private set

    @Volatile
    private var capabilitiesRequested: Boolean = false

    val skaermkortInstance: DanskStiTileSource by lazy {
        DanskStiTileSource(sharedKey, wmsLayerName = "dtk_skaermkort", isDarkMode = false)
    }

    val skaermkortDarkInstance: DanskStiTileSource by lazy {
        DanskStiTileSource(
            apiKey = sharedKey,
            wmsLayerName = "dtk_skaermkort",
            isDarkMode = true
        )
    }

    val friluftskortInstance: DanskStiFriluftskortTileSource by lazy {
        DanskStiFriluftskortTileSource(outdoorKey, isDarkMode = false)
    }

    val friluftskortDarkInstance: DanskStiFriluftskortTileSource by lazy {
        DanskStiFriluftskortTileSource(outdoorKey, isDarkMode = true)
    }

    val luftfotoInstance: DanskStiLuftfotoTileSource by lazy {
        DanskStiLuftfotoTileSource(sharedKey)
    }

    val dhmHillshadeInstance: DanskStiDhmHillshadeTileSource?
        get() = selectedHillshadeLayer?.let { DanskStiDhmHillshadeTileSource(sharedKey, it) }

    val dhmContourInstance: DanskStiDhmContourTileSource?
        get() = selectedContourLayer?.let { DanskStiDhmContourTileSource(sharedKey, it) }

    var userConfirmedOsmFallback: Boolean = false

    fun computeWebMercatorBbox(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        val originShift = 2.0 * Math.PI * 6378137.0 / 2.0
        val res = (2.0 * Math.PI * 6378137.0) / (256.0 * (1 shl zoom))
        val minX = x * 256.0 * res - originShift
        val maxY = originShift - y * 256.0 * res
        val maxX = (x + 1) * 256.0 * res - originShift
        val minY = originShift - (y + 1) * 256.0 * res

        return "$minX,$minY,$maxX,$maxY"
    }

    /**
     * Requirement B: Parses Skærmkortet WMS GetCapabilities XML, logs every layer name,
     * and picks a muted or grey variant (name containing "daempet" or "graa") for metadata discovery.
     */
    fun parseSkaermkortCapabilitiesXml(xml: String): SkaermkortCapabilities {
        val layerNames = extractWmsLayerNames(xml)
        layerNames.forEach { name ->
            Log.i(TAG, "Skærmkort WMS GetCapabilities Layer: $name")
        }
        val supports3857 = xml.contains("EPSG:3857", ignoreCase = true)

        val mutedOrGrey = layerNames.firstOrNull {
            it.equals("dtk_skaermkort_daempet", ignoreCase = true)
        } ?: layerNames.firstOrNull {
            it.equals("dtk_skaermkort_graa", ignoreCase = true)
        } ?: layerNames.firstOrNull {
            (it.contains("daempet", ignoreCase = true) || it.contains("graa", ignoreCase = true)) &&
                !it.contains("print", ignoreCase = true)
        } ?: layerNames.firstOrNull {
            it.contains("daempet", ignoreCase = true) || it.contains("graa", ignoreCase = true)
        }

        if (mutedOrGrey != null) {
            Log.i(TAG, "Selected Skærmkort muted/grey dark-mode layer: $mutedOrGrey")
            selectedDarkSkaermkortLayer = mutedOrGrey
        } else {
            Log.w(TAG, "No muted/grey variant ('daempet' or 'graa') found in Skærmkort WMS GetCapabilities")
        }
        discoveredSkaermkortLayers = layerNames

        return SkaermkortCapabilities(
            allLayerNames = layerNames,
            mutedOrGreyLayerName = mutedOrGrey,
            supportsEpsg3857 = supports3857
        )
    }

    /**
     * Requirement C: Parses Dataforsyningen "dhm" WMS GetCapabilities XML, logs all layer names,
     * picks the terrain hillshade layer ("skyggekort", terrain not surface) and a contour layer ("kurve"),
     * and confirms EPSG:3857 and transparent PNG support.
     */
    fun parseDhmCapabilitiesXml(
        xml: String,
        serviceEndpoint: String = "https://api.dataforsyningen.dk/dhm_DAF"
    ): DhmCapabilities {
        val layerNames = extractWmsLayerNames(xml)
        layerNames.forEach { name ->
            Log.i(TAG, "DHM WMS GetCapabilities Layer: $name")
        }

        val supports3857 = xml.contains("EPSG:3857", ignoreCase = true)
        val supportsPng = xml.contains("image/png", ignoreCase = true)
        val supportsTransparency = xml.contains("opaque=\"0\"", ignoreCase = true) || supportsPng
        val validFormatAndCrs = supports3857 && supportsPng && supportsTransparency

        if (!supports3857) {
            Log.w(TAG, "DHM WMS does not advertise EPSG:3857 support; skipping DHM overlays")
        }
        if (!supportsPng) {
            Log.w(TAG, "DHM WMS does not advertise image/png support; skipping DHM overlays")
        }

        val rawHillshade = layerNames.firstOrNull { name ->
            name.contains("skyggekort", ignoreCase = true) &&
                (name.contains("terraen", ignoreCase = true) || !name.contains("overflade", ignoreCase = true))
        }
        if (rawHillshade == null) {
            Log.w(TAG, "DHM WMS missing terrain hillshade ('skyggekort') layer; leaving hillshade out")
        }

        val rawContour = layerNames.firstOrNull { name ->
            name.equals("dhm_kurve_traditionel", ignoreCase = true)
        } ?: layerNames.firstOrNull { name ->
            name.contains("kurve", ignoreCase = true)
        }
        if (rawContour == null) {
            Log.w(TAG, "DHM WMS missing contour ('kurve') layer; leaving contours out")
        }

        val hillshade = if (validFormatAndCrs) rawHillshade else null
        val contour = if (validFormatAndCrs) rawContour else null

        discoveredDhmLayers = layerNames
        selectedHillshadeLayer = hillshade
        selectedContourLayer = contour

        Log.i(
            TAG,
            "DHM WMS selected layers: hillshade=$hillshade, contour=$contour (EPSG:3857=$supports3857, PNG=$supportsPng)"
        )

        return DhmCapabilities(
            serviceEndpoint = serviceEndpoint,
            allLayerNames = layerNames,
            hillshadeLayerName = hillshade,
            contourLayerName = contour,
            supportsEpsg3857 = supports3857,
            supportsTransparentPng = supportsPng && supportsTransparency
        )
    }

    private fun extractWmsLayerNames(xml: String): List<String> {
        val result = mutableListOf<String>()
        val layerRegex = Regex("<Layer\\b[^>]*>([\\s\\S]*?)</Layer>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("<Name>\\s*([^<\\s]+)\\s*</Name>", RegexOption.IGNORE_CASE)
        val withoutStyles = xml.replace(Regex("<Style\\b[^>]*>[\\s\\S]*?</Style>", RegexOption.IGNORE_CASE), "")
        val withoutGetMap = withoutStyles.replace(Regex("<Request\\b[^>]*>[\\s\\S]*?</Request>", RegexOption.IGNORE_CASE), "")
        for (match in nameRegex.findAll(withoutGetMap)) {
            val name = match.groupValues[1].trim()
            if (name.isNotBlank() && !name.equals("WMS", ignoreCase = true) && !result.contains(name)) {
                result.add(name)
            }
        }
        if (result.isEmpty() && layerRegex.containsMatchIn(xml)) {
            for (m in nameRegex.findAll(xml)) {
                val n = m.groupValues[1].trim()
                if (n.isNotBlank() && !result.contains(n)) result.add(n)
            }
        }
        return result
    }

    suspend fun fetchSkaermkortCapabilities(
        apiKey: String = sharedKey,
        client: OkHttpClient = httpClient
    ): SkaermkortCapabilities? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val url = "https://api.dataforsyningen.dk/topo_skaermkort_DAF?SERVICE=WMS&REQUEST=GetCapabilities&token=$apiKey"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Skærmkort GetCapabilities HTTP ${resp.code}")
                    return@withContext null
                }
                val xml = resp.body?.string() ?: return@withContext null
                parseSkaermkortCapabilitiesXml(xml)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skærmkort GetCapabilities failed: ${e.message}")
            null
        }
    }

    suspend fun fetchDhmCapabilities(
        apiKey: String = sharedKey,
        client: OkHttpClient = httpClient
    ): DhmCapabilities? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val endpoints = listOf(
            "https://api.dataforsyningen.dk/dhm_DAF",
            "https://api.dataforsyningen.dk/dhm"
        )
        for (endpoint in endpoints) {
            val url = "$endpoint?SERVICE=WMS&REQUEST=GetCapabilities&token=$apiKey"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val xml = resp.body?.string()
                        if (!xml.isNullOrBlank() && xml.contains("<Layer", ignoreCase = true)) {
                            return@withContext parseDhmCapabilitiesXml(xml, endpoint)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DHM GetCapabilities attempt on $endpoint failed: ${e.message}")
            }
        }
        null
    }

    fun ensureCapabilitiesLoadedAsync() {
        if (capabilitiesRequested || !hasValidKey) return
        capabilitiesRequested = true
        capabilitiesScope.launch {
            fetchSkaermkortCapabilities()
            fetchDhmCapabilities()
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun hasOfflineSqliteArchives(context: Context): Boolean {
        val offlineDir = File(context.filesDir, "offline")
        val sqliteFiles = offlineDir.listFiles { file ->
            file.isFile && file.name.endsWith(".sqlite", ignoreCase = true) && file.length() > 0L
        }
        return !sqliteFiles.isNullOrEmpty()
    }

    /**
     * Round 9B Item 6:
     * Applies the theme's water colour to `tilesOverlay.loadingBackgroundColor`,
     * `tilesOverlay.loadingLineColor`, and the `MapView` background.
     */
    fun applyThemeWaterBackground(mapView: MapView, isDarkMode: Boolean) {
        val waterColor = getThemeWaterColorInt(isDarkMode)
        mapView.setBackgroundColor(waterColor)
        mapView.background = android.graphics.drawable.ColorDrawable(waterColor)
        mapView.overlayManager.tilesOverlay.loadingBackgroundColor = waterColor
        mapView.overlayManager.tilesOverlay.loadingLineColor = waterColor
    }

    fun applyTo(
        mapView: MapView,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT,
        isDarkMode: Boolean = false,
        allowOsmFallback: Boolean = userConfirmedOsmFallback
    ) {
        ensureCapabilitiesLoadedAsync()
        val offlineWithArchives = !isNetworkAvailable(mapView.context) && hasOfflineSqliteArchives(mapView.context)

        // Round 9B Item 6: Always set loadingBackgroundColor, loadingLineColor, and MapView background to water colour
        applyThemeWaterBackground(mapView, isDarkMode)
        // Round 9B Item 5 & 8: Never apply a ColorMatrix filter on the base map TilesOverlay
        mapView.overlayManager.tilesOverlay.setColorFilter(null)

        when (baseMap) {
            BaseMapType.SKAERMKORT -> {
                val targetSource = if (isDarkMode) skaermkortDarkInstance else skaermkortInstance
                if (offlineWithArchives || hasValidKey) {
                    if (mapView.tileProvider.tileSource?.name() != targetSource.name()) {
                        mapView.setTileSource(targetSource)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                } else if (allowOsmFallback) {
                    if (mapView.tileProvider.tileSource?.name() != TileSourceFactory.MAPNIK.name()) {
                        mapView.setTileSource(TileSourceFactory.MAPNIK)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay())
                    }
                }
            }

            BaseMapType.FRILUFTSKORT -> {
                val targetSource = if (isDarkMode) friluftskortDarkInstance else friluftskortInstance
                if (mapView.tileProvider.tileSource?.name() != targetSource.name()) {
                    mapView.setTileSource(targetSource)
                }
                mapView.overlayManager.tilesOverlay.isEnabled = true
                mapView.overlays.removeAll { it is NoKeyOverlay }
            }

            BaseMapType.LUFTFOTO -> {
                if (hasValidKey) {
                    if (mapView.tileProvider.tileSource?.name() != luftfotoInstance.name()) {
                        mapView.setTileSource(luftfotoInstance)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    // Round 9B Item 8: Aerial photos stay unfiltered
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                } else if (allowOsmFallback) {
                    if (mapView.tileProvider.tileSource?.name() != TileSourceFactory.MAPNIK.name()) {
                        mapView.setTileSource(TileSourceFactory.MAPNIK)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay("Luftfoto kræver Dataforsyningen API-nøgle"))
                    }
                }
            }
        }

        // Requirement 18 & Round 10C Item 5: Register open MapView and attach downloaded SQLite tile archives
        registerOpenMapView(mapView)
        attachOfflineSqliteArchives(mapView.context, mapView.tileProvider)

        // Maintain copyright attribution overlay
        val existingCopyright = mapView.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()
        val attributionText = getAttributionForBaseMap(baseMap)
        if (existingCopyright == null) {
            val copyright = CopyrightOverlay(mapView.context).apply {
                setCopyrightNotice(attributionText)
                setAlignRight(false)
                setTextSize(9)
                setTextColor(android.graphics.Color.DKGRAY)
            }
            mapView.overlays.add(copyright)
        } else {
            existingCopyright.setCopyrightNotice(attributionText)
        }
        mapView.invalidate()
    }

    fun configureOsmdroid(context: Context) {
        val config = org.osmdroid.config.Configuration.getInstance()
        config.userAgentValue = com.example.util.network.NetworkConstants.USER_AGENT
        val baseDir = File(context.filesDir, "osmdroid")
        val tileCache = File(context.cacheDir, "osmdroid/tiles")
        baseDir.mkdirs()
        tileCache.mkdirs()
        config.osmdroidBasePath = baseDir
        config.osmdroidTileCache = tileCache
    }

    fun applyBaseMapTo(
        mapView: MapView,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT,
        isDark: Boolean = false,
        allowOsmFallback: Boolean = userConfirmedOsmFallback
    ) {
        applyTo(
            mapView = mapView,
            baseMap = baseMap,
            isDarkMode = isDark,
            allowOsmFallback = allowOsmFallback
        )
    }

    /**
     * Creates a configured TerrainTilesOverlay (hillshade or contour) backed by offline SQLite archives + online WMS.
     */
    fun createTerrainOverlay(
        context: Context,
        isHillshade: Boolean,
        isDarkMode: Boolean
    ): TerrainTilesOverlay? {
        val source = if (isHillshade) dhmHillshadeInstance else dhmContourInstance
        if (source == null) return null

        val provider = MapTileProviderBasic(context.applicationContext, source)
        attachOfflineSqliteArchives(context, provider)

        return TerrainTilesOverlay(
            tileProvider = provider,
            context = context,
            isHillshade = isHillshade,
            minZoom = if (isHillshade) 7.0 else 14.0
        ).apply {
            this.isDarkMode = isDarkMode
        }
    }

    fun createHillshadeOverlay(context: Context, isDark: Boolean): TerrainTilesOverlay? {
        return createTerrainOverlay(context, isHillshade = true, isDarkMode = isDark)
    }

    fun createContourOverlay(context: Context, isDark: Boolean): TerrainTilesOverlay? {
        return createTerrainOverlay(context, isHillshade = false, isDarkMode = isDark)
    }

    fun updateHillshadeDarkMode(overlay: TilesOverlay, isDark: Boolean) {
        if (overlay is TerrainTilesOverlay) {
            overlay.isDarkMode = isDark
        }
    }

    fun updateContourDarkMode(overlay: TilesOverlay, isDark: Boolean) {
        if (overlay is TerrainTilesOverlay) {
            overlay.isDarkMode = isDark
        }
    }

    private val archiveLock = Any()
    private val openMapViews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapView, Boolean>())
    private val openTileProviders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MapTileProviderArray, Boolean>())

    fun registerOpenMapView(mapView: MapView) {
        synchronized(archiveLock) {
            openMapViews.add(mapView)
            (mapView.tileProvider as? MapTileProviderArray)?.let { openTileProviders.add(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun attachOfflineSqliteArchives(context: Context, tileProvider: MapTileProviderBase?) {
        synchronized(archiveLock) {
            try {
                val providerArray = tileProvider as? MapTileProviderArray ?: return
                openTileProviders.add(providerArray)
                val tileSource = providerArray.tileSource ?: return
                val offlineDir = File(context.filesDir, "offline")
                val sqliteFiles = offlineDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".sqlite", ignoreCase = true) && file.length() > 0L
                } ?: emptyArray()

                val field = MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
                field.isAccessible = true
                val providerList = field.get(providerArray) as? MutableList<MapTileModuleProviderBase> ?: return

                // Close any existing archive providers before replacing
                val existingProviders = providerList.filterIsInstance<MapTileFileArchiveProvider>()
                for (existing in existingProviders) {
                    closeArchivesInProvider(existing, targetFile = null)
                }
                providerList.removeAll { it is MapTileFileArchiveProvider }

                if (sqliteFiles.isNotEmpty()) {
                    val archives = sqliteFiles.mapNotNull { file ->
                        try {
                            DatabaseFileArchive.getDatabaseFileArchive(file)?.apply {
                                setIgnoreTileSource(false)
                            }?.let { dbArchive ->
                                FallbackSkaermkortArchiveFile(file, dbArchive)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not open offline archive ${file.name}: ${e.message}")
                            null
                        }
                    }.toTypedArray<IArchiveFile>()

                    if (archives.isNotEmpty()) {
                        val archiveProvider = MapTileFileArchiveProvider(
                            SimpleRegisterReceiver(context.applicationContext),
                            tileSource,
                            archives
                        )
                        providerList.add(0, archiveProvider)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed attaching offline SQLite archives: ${e.message}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun closeArchivesInProvider(
        archiveProvider: MapTileFileArchiveProvider,
        targetFile: File?
    ): Boolean {
        val targetPath = runCatching { targetFile?.canonicalPath }.getOrNull() ?: targetFile?.absolutePath
        var remainingCount = 0
        try {
            val archivesField = MapTileFileArchiveProvider::class.java.getDeclaredField("mArchiveFiles")
            archivesField.isAccessible = true
            val archiveList = archivesField.get(archiveProvider) as? MutableList<IArchiveFile>
            if (archiveList != null) {
                val iterator = archiveList.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    val matches = if (targetPath == null) {
                        true
                    } else if (item is FallbackSkaermkortArchiveFile) {
                        val itemPath = runCatching { item.archiveFile.canonicalPath }.getOrNull() ?: item.archiveFile.absolutePath
                        itemPath == targetPath || item.archiveFile.name.equals(targetFile?.name, ignoreCase = true)
                    } else {
                        true
                    }
                    if (matches) {
                        try {
                            item.close()
                        } catch (_: Exception) {}
                        iterator.remove()
                    }
                }
                remainingCount = archiveList.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting mArchiveFiles: ${e.message}")
        }
        return remainingCount == 0
    }

    /**
     * Round 10C Item 5:
     * Detaches [archiveFile] from every open map's tile provider (closing its SQLite handle)
     * BEFORE deleting the file on disk.
     */
    @Suppress("UNCHECKED_CAST")
    fun detachOfflineArchiveFromAllOpenMaps(archiveFile: File) {
        synchronized(archiveLock) {
            val allProviders = LinkedHashSet<MapTileProviderArray>()
            allProviders.addAll(openTileProviders.filterNotNull())
            for (mv in openMapViews.filterNotNull()) {
                (mv.tileProvider as? MapTileProviderArray)?.let { allProviders.add(it) }
                for (ov in mv.overlays) {
                    if (ov is TilesOverlay) {
                        runCatching {
                            val tpField = TilesOverlay::class.java.getDeclaredField("mTileProvider")
                            tpField.isAccessible = true
                            (tpField.get(ov) as? MapTileProviderArray)?.let { allProviders.add(it) }
                        }
                    }
                }
            }

            for (providerArray in allProviders) {
                try {
                    val field = MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
                    field.isAccessible = true
                    val providerList = field.get(providerArray) as? MutableList<MapTileModuleProviderBase> ?: continue
                    val iterator = providerList.iterator()
                    while (iterator.hasNext()) {
                        val mod = iterator.next()
                        if (mod is MapTileFileArchiveProvider) {
                            val isEmptyNow = closeArchivesInProvider(mod, archiveFile)
                            if (isEmptyNow) {
                                try {
                                    mod.detach()
                                } catch (_: Exception) {}
                                iterator.remove()
                            }
                        }
                    }
                    try {
                        providerArray.clearTileCache()
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.w(TAG, "Failed detaching archive ${archiveFile.name} from provider: ${e.message}")
                }
            }
        }
    }

    /**
     * Round 10C Item 5: Refreshes every open map after an offline archive has been detached and deleted.
     */
    fun refreshAllOpenMaps() {
        val mapsSnapshot = synchronized(archiveLock) {
            openMapViews.filterNotNull().toList()
        }
        for (mv in mapsSnapshot) {
            try {
                mv.tileProvider?.clearTileCache()
                mv.invalidate()
                mv.postInvalidate()
            } catch (_: Exception) {}
        }
    }

    /**
     * Round 10C Item 5: Detaches [archiveFile] from all open map tile providers, deletes the file,
     * and refreshes all open maps.
     */
    fun detachOfflineArchiveAndDeleteFile(archiveFile: File): Boolean {
        detachOfflineArchiveFromAllOpenMaps(archiveFile)
        val deleted = if (archiveFile.exists()) archiveFile.delete() else true
        refreshAllOpenMaps()
        return deleted
    }

    @Suppress("UNCHECKED_CAST")
    fun isArchiveAttachedToMap(mapView: MapView, archiveFile: File): Boolean {
        val providerArray = mapView.tileProvider as? MapTileProviderArray ?: return false
        return isArchiveAttachedToProvider(providerArray, archiveFile)
    }

    @Suppress("UNCHECKED_CAST")
    fun isArchiveAttachedToProvider(providerArray: MapTileProviderArray, archiveFile: File): Boolean {
        synchronized(archiveLock) {
            return try {
                val targetPath = runCatching { archiveFile.canonicalPath }.getOrNull() ?: archiveFile.absolutePath
                val field = MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
                field.isAccessible = true
                val providerList = field.get(providerArray) as? List<MapTileModuleProviderBase> ?: return false
                val archivesField = MapTileFileArchiveProvider::class.java.getDeclaredField("mArchiveFiles").apply {
                    isAccessible = true
                }
                providerList.filterIsInstance<MapTileFileArchiveProvider>().any { mod ->
                    val list = archivesField.get(mod) as? List<IArchiveFile> ?: emptyList()
                    list.any { item ->
                        item is FallbackSkaermkortArchiveFile && (
                            (runCatching { item.archiveFile.canonicalPath }.getOrNull() ?: item.archiveFile.absolutePath) == targetPath ||
                                item.archiveFile.name.equals(archiveFile.name, ignoreCase = true)
                            )
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Wraps a DatabaseFileArchive so that in dark mode (when the active tile source is
     * "DanskSti_Skaermkort_Dark"), if no dark tile exists in the archive,
     * it serves the downloaded light "DanskSti_Skaermkort" tile bytes unchanged so
     * `DanskStiTileSource.getDrawable(InputStream)` can recolour them in memory.
     */
    private class FallbackSkaermkortArchiveFile(
        val archiveFile: File,
        private val delegate: DatabaseFileArchive
    ) : IArchiveFile by delegate {
        override fun getInputStream(
            pTileSource: org.osmdroid.tileprovider.tilesource.ITileSource?,
            pMapTileIndex: Long
        ): InputStream? {
            val direct = delegate.getInputStream(pTileSource, pMapTileIndex)
            if (direct != null) return direct
            val sourceName = pTileSource?.name().orEmpty()
            if (sourceName.startsWith("DanskSti_Skaermkort") && sourceName != skaermkortInstance.name()) {
                return delegate.getInputStream(skaermkortInstance, pMapTileIndex)
            }
            return null
        }

        override fun close() {
            try {
                delegate.close()
            } catch (_: Exception) {}
        }
    }

    fun applyDarkMapFilter(mapView: MapView, isDarkMode: Boolean) {
        applyThemeWaterBackground(mapView, isDarkMode)
        mapView.overlayManager.tilesOverlay.setColorFilter(null)
    }

    fun getAttributionForBaseMap(baseMap: BaseMapType): String {
        return when (baseMap) {
            BaseMapType.SKAERMKORT -> "Klimadatastyrelsen / Skærmkortet • © OpenStreetMap"
            BaseMapType.FRILUFTSKORT -> "© OpenStreetMap-bidragydere • Thunderforest / OpenTopoMap"
            BaseMapType.LUFTFOTO -> "GeoDanmark / Klimadatastyrelsen Ortofoto"
        }
    }

    fun getAttributionSummary(state: MapLayersState?): String {
        if (state == null) return ATTRIBUTION_TEXT
        val base = when (state.baseMap) {
            BaseMapType.SKAERMKORT -> "SDFI Skærmkortet"
            BaseMapType.FRILUFTSKORT -> "© OSM • Friluftskort"
            BaseMapType.LUFTFOTO -> "GeoDanmark Ortofoto"
        }
        val anyFeatureLayerActive = state.showMarkedRoutes || state.showAccess ||
            state.showShelters || state.showFriTeltning || state.showWater ||
            state.showToilets || state.showShops || state.showTransport || state.showMyPoints

        val overlays = mutableListOf<String>()
        if (state.showTerrain && (state.baseMap != BaseMapType.LUFTFOTO || anyFeatureLayerActive)) {
            overlays.add("SDFI DHM")
        }
        if (state.showWaterLayer && (state.baseMap != BaseMapType.LUFTFOTO || anyFeatureLayerActive)) {
            overlays.add("OSM Vand")
        }
        if (state.showMarkedRoutes) overlays.add("OSM Ruter")
        if (state.showAccess) overlays.add("Miljøportalen")
        if (state.showShelters || state.showFriTeltning) overlays.add("GeoFA/NST")
        if (state.showWater || state.showToilets || state.showShops || state.showTransport) overlays.add("OSM POI")

        return if (overlays.isEmpty()) {
            base
        } else {
            "$base + ${overlays.joinToString(", ")}"
        }
    }

    private val activeMapViews = java.util.Collections.synchronizedList(
        mutableListOf<java.lang.ref.WeakReference<MapView>>()
    )

    @Volatile
    var tileCacheDetachCount: Int = 0
        private set

    @Volatile
    var tileCacheReattachCount: Int = 0
        private set

    fun registerActiveMapView(mapView: MapView) {
        synchronized(activeMapViews) {
            activeMapViews.removeAll { it.get() == null || it.get() === mapView }
            activeMapViews.add(java.lang.ref.WeakReference(mapView))
        }
    }

    fun unregisterActiveMapView(mapView: MapView) {
        synchronized(activeMapViews) {
            activeMapViews.removeAll { it.get() == null || it.get() === mapView }
        }
    }

    fun getActiveMapViewsSnapshot(): List<MapView> = synchronized(activeMapViews) {
        activeMapViews.removeAll { it.get() == null }
        activeMapViews.mapNotNull { it.get() }
    }

    fun getBrowsingTileCacheDir(context: Context): File {
        val configured = runCatching {
            org.osmdroid.config.Configuration.getInstance().osmdroidTileCache
        }.getOrNull()
        val fallback = File(context.cacheDir, "osmdroid_tiles")
        val target = if (configured != null && !configured.absolutePath.contains("/files/offline")) {
            configured
        } else {
            fallback
        }
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun getBrowsingTileCacheSizeBytes(context: Context): Long {
        val dir = getBrowsingTileCacheDir(context)
        return computeDirSizeBytes(dir)
    }

    private fun computeDirSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { computeDirSizeBytes(it) } ?: 0L
    }

    /**
     * Round 12B Item 2:
     * Close or detach the map tile cache before deleting it, then reattach, so open maps keep working.
     * Never touches offline downloads (/files/offline).
     */
    fun detachDeleteAndReattachTileCache(context: Context): Long {
        val cacheDir = getBrowsingTileCacheDir(context)
        val freedBytes = computeDirSizeBytes(cacheDir)
        val mapViews = getActiveMapViewsSnapshot()

        val runOnMainIfNeeded: (() -> Unit) -> Unit = { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(Looper.getMainLooper()).post {
                    try {
                        action()
                    } finally {
                        latch.countDown()
                    }
                }
                runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            }
        }

        val savedTileSources = mutableMapOf<MapView, org.osmdroid.tileprovider.tilesource.ITileSource>()
        // 1. Detach and close active tile writers / providers on open maps
        runOnMainIfNeeded {
            for (mv in mapViews) {
                runCatching {
                    val provider = mv.tileProvider
                    if (provider != null) {
                        savedTileSources[mv] = provider.tileSource
                        provider.clearTileCache()
                        provider.tileWriter?.onDetach()
                        provider.detach()
                    }
                }
            }
        }
        // Also close any standalone SqlTileWriter connection before deleting cache.db
        runCatching {
            val writer = org.osmdroid.tileprovider.modules.SqlTileWriter()
            writer.purgeCache()
            writer.onDetach()
        }
        tileCacheDetachCount++

        // 2. Delete only browsing tile cache files (never touching files/offline)
        runCatching {
            cacheDir.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            org.osmdroid.config.Configuration.getInstance().osmdroidTileCache = cacheDir
        }

        // 3. Reattach fresh tile providers to open maps so they keep working
        runOnMainIfNeeded {
            for (mv in mapViews) {
                runCatching {
                    val source = savedTileSources[mv] ?: mv.tileProvider?.tileSource ?: skaermkortInstance
                    val newProvider = org.osmdroid.tileprovider.MapTileProviderBasic(mv.context.applicationContext, source)
                    mv.tileProvider = newProvider
                    mv.invalidate()
                }
            }
        }
        tileCacheReattachCount++
        return freedBytes
    }
}

/**
 * Overlay shown when no Dataforsyningen API key is configured.
 */
class NoKeyOverlay(
    private val customMessage: String? = null
) : org.osmdroid.views.overlay.Overlay() {
    private val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#E61E2E24")
    }
    private val borderPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#4D52B788")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val titlePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subTextPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#D0D0D0")
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun draw(canvas: android.graphics.Canvas, pProjection: org.osmdroid.views.Projection) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0 || height <= 0) return

        val boxWidth = (width - 80f).coerceAtMost(700f)
        val left = (width - boxWidth) / 2f
        val right = left + boxWidth
        val top = height / 2f - 110f
        val bottom = height / 2f + 110f
        val rect = android.graphics.RectF(left, top, right, bottom)

        canvas.drawRoundRect(rect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

        val centerX = width / 2f
        val title = customMessage ?: "Dataforsyningen API-nøgle mangler"
        canvas.drawText(title, centerX, height / 2f - 30f, titlePaint)
        canvas.drawText("Kortfliser kan ikke hentes uden gyldig nøgle.", centerX, height / 2f + 20f, subTextPaint)
        canvas.drawText("Konfigurer DATAFORSYNINGEN_API_KEY i Secrets.", centerX, height / 2f + 65f, subTextPaint)
    }
}

/**
 * Reusable Compose bottom-left attribution pill for maps.
 * Dynamically displays attribution for the active base map and every active overlay layer.
 */
@Composable
fun MapAttributionPill(
    mapLayersState: MapLayersState? = null,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false
) {
    val text = if (isOffline) {
        "Offline-cache • ${DanskStiTileSourceHelper.getAttributionSummary(mapLayersState)}"
    } else {
        DanskStiTileSourceHelper.getAttributionSummary(mapLayersState)
    }

    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.78f),
                RoundedCornerShape(topEnd = 8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

// Backwards compatibility alias for existing code
typealias DanskStiTileSourceCompanion = DanskStiTileSourceHelper
