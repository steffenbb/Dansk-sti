package com.example.util

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * Shared Danish Skærmkortet tile provider for all MapViews in Dansk Sti.
 * Uses official Dataforsyningen / SDFI WMS in Web Mercator (EPSG:3857).
 * API key is loaded securely from BuildConfig via .env / Secrets Gradle plugin.
 */
class DanskStiTileSource(
    private val apiKey: String
) : OnlineTileSourceBase(
    "DanskSti_Skaermkort",
    7,
    18,
    256,
    ".jpg",
    arrayOf("https://api.dataforsyningen.dk/topo_skaermkort_DAF")
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        // Web Mercator (EPSG:3857) resolution and bounding box computation
        val originShift = 2.0 * Math.PI * 6378137.0 / 2.0
        val res = (2.0 * Math.PI * 6378137.0) / (256.0 * (1 shl zoom))
        val minX = x * 256.0 * res - originShift
        val maxY = originShift - y * 256.0 * res
        val maxX = (x + 1) * 256.0 * res - originShift
        val minY = originShift - (y + 1) * 256.0 * res

        val bbox = "$minX,$minY,$maxX,$maxY"
        return "https://api.dataforsyningen.dk/topo_skaermkort_DAF?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/jpeg&LAYERS=dtk_skaermkort&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
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
 * Phase 13 Friluftskort tile provider:
 * OSM-based outdoor map tailored for trekking and hiking.
 *
 * Evaluation in Danish forests at zoom 16:
 * 1. Gribskov: Skærmkortet (dtk_skaermkort) displays broad skovveje but lacks small walking singletracks,
 *    informal footpaths, and route trail markings. Friluftskort displays complete walking paths with dashed lines.
 * 2. Rold Skov: Skærmkortet omits multiple footpaths and singletracks around Store Økssø and Rebild Bakker.
 * 3. Silkeborg Vesterskov: Fine-grained steep singletracks are omitted in Skærmkortet.
 *
 * Provider Choice:
 * Chosen Thunderforest Outdoors (with OpenTopoMap / OSM standard fallback).
 * Why: Thunderforest terms of service explicitly permit mobile application integration, offline tile caching,
 * and outdoor trek navigation, providing high-contrast trail rendering and SRTM elevation contours.
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
    private val isThunderforest = apiKey.isNotBlank() && apiKey != "DEFAULT_KEY"

    init {
        Log.i("DanskStiTileSource", "Initialized Friluftskort with provider: ${if (isThunderforest) "Thunderforest Outdoors" else "OpenTopoMap"}")
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
 * Phase 13 Luftfoto tile provider:
 * Official GeoDanmark Ortofoto (forår) from Dataforsyningen / Klimadatastyrelsen.
 * Uses WMS 1.1.1 in Web Mercator (EPSG:3857) with layer "orto_foraar".
 * Uses the same Dataforsyningen API key as Skærmkortet.
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
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        val originShift = 2.0 * Math.PI * 6378137.0 / 2.0
        val res = (2.0 * Math.PI * 6378137.0) / (256.0 * (1 shl zoom))
        val minX = x * 256.0 * res - originShift
        val maxY = originShift - y * 256.0 * res
        val maxX = (x + 1) * 256.0 * res - originShift
        val minY = originShift - (y + 1) * 256.0 * res

        val bbox = "$minX,$minY,$maxX,$maxY"
        return "https://api.dataforsyningen.dk/orto_foraar_DAF?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&FORMAT=image/jpeg&LAYERS=orto_foraar&STYLES=&WIDTH=256&HEIGHT=256&SRS=EPSG:3857&BBOX=$bbox&token=$apiKey"
    }
}

object DanskStiTileSourceHelper {
    const val ATTRIBUTION_TEXT = "Klimadatastyrelsen / Skærmkortet • © OpenStreetMap-bidragydere"

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

    val skaermkortInstance: DanskStiTileSource by lazy {
        DanskStiTileSource(sharedKey)
    }

    val friluftskortInstance: DanskStiFriluftskortTileSource by lazy {
        DanskStiFriluftskortTileSource(outdoorKey)
    }

    val luftfotoInstance: DanskStiLuftfotoTileSource by lazy {
        DanskStiLuftfotoTileSource(sharedKey)
    }

    var userConfirmedOsmFallback: Boolean = false

    /**
     * Applies the designated base map, night-mode filter, and attribution to any MapView.
     */
    fun applyTo(
        mapView: MapView,
        baseMap: BaseMapType = BaseMapType.SKAERMKORT,
        isDarkMode: Boolean = false,
        allowOsmFallback: Boolean = userConfirmedOsmFallback
    ) {
        when (baseMap) {
            BaseMapType.SKAERMKORT -> {
                if (hasValidKey) {
                    mapView.setTileSource(skaermkortInstance)
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyNightFilter(mapView, isDarkMode)
                } else if (allowOsmFallback) {
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyNightFilter(mapView, isDarkMode)
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay())
                    }
                }
            }

            BaseMapType.FRILUFTSKORT -> {
                mapView.setTileSource(friluftskortInstance)
                mapView.overlayManager.tilesOverlay.isEnabled = true
                mapView.overlays.removeAll { it is NoKeyOverlay }
                applyNightFilter(mapView, isDarkMode)
            }

            BaseMapType.LUFTFOTO -> {
                if (hasValidKey) {
                    mapView.setTileSource(luftfotoInstance)
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    // Do NOT apply night color inversion to aerial photography
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                } else if (allowOsmFallback) {
                    // Fall back to OpenStreetMap if no aerial key
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    mapView.overlayManager.tilesOverlay.isEnabled = true
                    mapView.overlays.removeAll { it is NoKeyOverlay }
                    applyNightFilter(mapView, isDarkMode)
                } else {
                    mapView.overlayManager.tilesOverlay.isEnabled = false
                    if (mapView.overlays.none { it is NoKeyOverlay }) {
                        mapView.overlays.add(NoKeyOverlay("Luftfoto kræver Dataforsyningen API-nøgle"))
                    }
                }
            }
        }

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

    private fun applyNightFilter(mapView: MapView, isDarkMode: Boolean) {
        if (isDarkMode) {
            val matrix = ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 230f,
                    0f, -0.85f, 0f, 0f, 230f,
                    0f, 0f, -0.85f, 0f, 230f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
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
        val overlays = mutableListOf<String>()
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
 * Dynamically displays attribution for the active base map and active overlay layers.
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
