package com.example.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
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
import java.io.File
import java.util.concurrent.TimeUnit
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
 * Supports both standard ("dtk_skaermkort") and muted/grey dark-mode variant ("dtk_skaermkort_daempet").
 */
class DanskStiTileSource(
    private val apiKey: String,
    val wmsLayerName: String = "dtk_skaermkort",
    tileSourceName: String = if (wmsLayerName == "dtk_skaermkort") "DanskSti_Skaermkort" else "DanskSti_Skaermkort_$wmsLayerName"
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
    private val apiKey: String
) : OnlineTileSourceBase(
    "DanskSti_Friluftskort",
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
}

/**
 * Luftfoto tile provider:
 * Official GeoDanmark Ortofoto (forår) from Dataforsyningen / Klimadatastyrelsen in EPSG:3857.
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
 * Requirement C: Dataforsyningen DHM Terrain Hillshade tile source ("dhm_terraen_skyggekort").
 * Uses WMS 1.1.1 in EPSG:3857 with transparent PNG.
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
 * Requirement C: Custom TilesOverlay for DHM Hillshade (alpha 30% light / 45% dark)
 * and DHM Contours (from zoom 14 with alpha 60%).
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

    // Hillshade relief ColorMatrix: converts neutral flat grey (189,189,189) to transparent
    // and shaded slopes (<185) to crisp shadow relief so base map water/forest colors stay vivid.
    private val hillshadeReliefFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                -0.8f, -0.8f, -0.8f, 0f, 455f
            )
        )
    )

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
            setColorFilter(hillshadeReliefFilter)
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
        DanskStiTileSource(sharedKey, wmsLayerName = "dtk_skaermkort")
    }

    val skaermkortDarkInstance: DanskStiTileSource
        get() = DanskStiTileSource(
            apiKey = sharedKey,
            wmsLayerName = selectedDarkSkaermkortLayer.ifBlank { "dtk_skaermkort_daempet" }
        )

    val friluftskortInstance: DanskStiFriluftskortTileSource by lazy {
        DanskStiFriluftskortTileSource(outdoorKey)
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
     * and picks a muted or grey variant (name containing "daempet" or "graa") for dark mode.
     */
    fun parseSkaermkortCapabilitiesXml(xml: String): SkaermkortCapabilities {
        val layerNames = extractWmsLayerNames(xml)
        layerNames.forEach { name ->
            Log.i(TAG, "Skærmkort WMS GetCapabilities Layer: $name")
        }
        val supports3857 = xml.contains("EPSG:3857", ignoreCase = true)

        // Pick muted or grey variant (name containing "daempet" or "graa"), preferring non-print base layers
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

        // Pick terrain hillshade layer ("skyggekort", terrain not surface/overflade)
        val rawHillshade = layerNames.firstOrNull { name ->
            name.contains("skyggekort", ignoreCase = true) &&
                (name.contains("terraen", ignoreCase = true) || !name.contains("overflade", ignoreCase = true))
        }
        if (rawHillshade == null) {
            Log.w(TAG, "DHM WMS missing terrain hillshade ('skyggekort') layer; leaving hillshade out")
        }

        // Pick contour layer ("kurve"), preferring dhm_kurve_traditionel (visible from zoom 14+)
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
        // Extract every <Name> that is a direct child of a <Layer> (not a <Style><Name>)
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

    /**
     * Fetches Skærmkortet WMS GetCapabilities from Dataforsyningen on IO dispatcher.
     */
    suspend fun fetchSkaermkortCapabilities(
        apiKey: String = sharedKey,
        client: OkHttpClient = httpClient
    ): SkaermkortCapabilities? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val url = "https://api.dataforsyningen.dk/topo_skaermkort_DAF?SERVICE=WMS&REQUEST=GetCapabilities&token=$apiKey"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "DanskSti/1.8 (Android WMS Capabilities)")
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

    /**
     * Fetches DHM ("dhm" / "dhm_DAF") WMS GetCapabilities from Dataforsyningen on IO dispatcher.
     */
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
                    .header("User-Agent", "DanskSti/1.8 (Android DHM Capabilities)")
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

    /**
     * Requirement E: Hue-preserving dark filter:
     * 1. Invert brightness (R' = 255 - R, G' = 255 - G, B' = 255 - B)
     * 2. Rotate hue 180 degrees (standard CSS hue-rotate(180deg) color matrix)
     * 3. Multiply contrast by 0.85 and brightness by 0.9
     * Preserves blue water and green forests while darkening white land to near-black.
     */
    fun createHuePreservingDarkColorMatrix(): ColorMatrix {
        // 1. Invert RGB brightness
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // 2. Rotate hue 180 degrees (standard W3C / CSS hue-rotate(180deg) matrix)
        // lumR = 0.213, lumG = 0.715, lumB = 0.072; cos(180) = -1, sin(180) = 0
        val hueRotate180 = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // 3. Multiply contrast by 0.85 (around midpoint 127.5) and brightness by 0.9
        val contrast = 0.85f
        val contrastTranslate = 127.5f * (1f - contrast) // 19.125f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, contrastTranslate,
                0f, contrast, 0f, 0f, contrastTranslate,
                0f, 0f, contrast, 0f, contrastTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val brightness = 0.9f
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val combined = ColorMatrix()
        combined.set(invertMatrix)
        combined.postConcat(hueRotate180)
        combined.postConcat(contrastMatrix)
        combined.postConcat(brightnessMatrix)
        return combined
    }

    /**
     * Transforms an ARGB color through the hue-preserving dark color matrix (used for verification & tests).
     */
    fun transformColorForDarkMap(color: Int): Int {
        val m = createHuePreservingDarkColorMatrix().array
        val r = android.graphics.Color.red(color).toFloat()
        val g = android.graphics.Color.green(color).toFloat()
        val b = android.graphics.Color.blue(color).toFloat()
        val a = android.graphics.Color.alpha(color).toFloat()

        val outR = (m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]).roundToInt().coerceIn(0, 255)
        val outG = (m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]).roundToInt().coerceIn(0, 255)
        val outB = (m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]).roundToInt().coerceIn(0, 255)
        val outA = (m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]).roundToInt().coerceIn(0, 255)

        return android.graphics.Color.argb(outA, outR, outG, outB)
    }

    /**
     * Applies the designated base map, hue-preserving dark filter, and attribution to any MapView.
     */
    fun applyTo(
        mapView: MapView,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT,
        isDarkMode: Boolean = false,
        allowOsmFallback: Boolean = userConfirmedOsmFallback
    ) {
        ensureCapabilitiesLoadedAsync()

        when (baseMap) {
            BaseMapType.SKAERMKORT -> {
                if (hasValidKey) {
                    val targetSource = if (isDarkMode) skaermkortDarkInstance else skaermkortInstance
                    if (mapView.tileProvider.tileSource?.name() != targetSource.name()) {
                        mapView.setTileSource(targetSource)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyDarkMapFilter(mapView, isDarkMode)
                } else if (allowOsmFallback) {
                    if (mapView.tileProvider.tileSource?.name() != TileSourceFactory.MAPNIK.name()) {
                        mapView.setTileSource(TileSourceFactory.MAPNIK)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyDarkMapFilter(mapView, isDarkMode)
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay())
                    }
                }
            }

            BaseMapType.FRILUFTSKORT -> {
                if (mapView.tileProvider.tileSource?.name() != friluftskortInstance.name()) {
                    mapView.setTileSource(friluftskortInstance)
                }
                mapView.overlayManager.tilesOverlay.isEnabled = true
                mapView.overlays.removeAll { it is NoKeyOverlay }
                applyDarkMapFilter(mapView, isDarkMode)
            }

            BaseMapType.LUFTFOTO -> {
                if (hasValidKey) {
                    if (mapView.tileProvider.tileSource?.name() != luftfotoInstance.name()) {
                        mapView.setTileSource(luftfotoInstance)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    // Do NOT apply dark color filter to aerial photography
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                } else if (allowOsmFallback) {
                    if (mapView.tileProvider.tileSource?.name() != TileSourceFactory.MAPNIK.name()) {
                        mapView.setTileSource(TileSourceFactory.MAPNIK)
                    }
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyDarkMapFilter(mapView, isDarkMode)
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay("Luftfoto kræver Dataforsyningen API-nøgle"))
                    }
                }
            }
        }

        // Requirement 18: Add downloaded SQLite tile archives in filesDir/offline/<id>.sqlite
        // to the map's tile provider ahead of the online source.
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
        config.userAgentValue = context.packageName.ifBlank { "com.example.dansksti" }
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

    @Suppress("UNCHECKED_CAST")
    fun attachOfflineSqliteArchives(context: Context, tileProvider: MapTileProviderBase?) {
        try {
            val providerArray = tileProvider as? MapTileProviderArray ?: return
            val tileSource = providerArray.tileSource ?: return
            val offlineDir = File(context.filesDir, "offline")
            val sqliteFiles = offlineDir.listFiles { file ->
                file.isFile && file.name.endsWith(".sqlite", ignoreCase = true) && file.length() > 0L
            } ?: emptyArray()

            val field = MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
            field.isAccessible = true
            val providerList = field.get(providerArray) as? MutableList<MapTileModuleProviderBase> ?: return

            providerList.removeAll { it is MapTileFileArchiveProvider }

            if (sqliteFiles.isNotEmpty()) {
                val archives = sqliteFiles.mapNotNull { file ->
                    try {
                        DatabaseFileArchive.getDatabaseFileArchive(file)?.apply {
                            setIgnoreTileSource(false)
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

    fun applyDarkMapFilter(mapView: MapView, isDarkMode: Boolean) {
        if (isDarkMode) {
            val matrix = createHuePreservingDarkColorMatrix()
            mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
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
                Color.Black.copy(alpha = 0.70f),
                RoundedCornerShape(topEnd = 8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

// Backwards compatibility alias for existing code
typealias DanskStiTileSourceCompanion = DanskStiTileSourceHelper
