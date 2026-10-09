package com.example.util

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
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
        const val ATTRIBUTION_TEXT = "Klimadatastyrelsen / Skærmkortet • © OpenStreetMap-bidragydere"

        val sharedKey: String by lazy {
            val key = try {
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
            if (key.isNotBlank()) {
                key
            } else {
                "5e968b7155c695fe669a75e8120ee325"
            }
        }

        val instance: DanskStiTileSource by lazy {
            DanskStiTileSource(sharedKey)
        }

        /**
         * Applies the shared tile source, night-mode filter, and attribution to any MapView in the app.
         */
        fun applyTo(mapView: MapView, isDarkMode: Boolean = true) {
            mapView.setTileSource(instance)

            // Night map: Apply ColorMatrix filter when in dark mode
            if (isDarkMode) {
                val matrix = ColorMatrix(
                    floatArrayOf(
                        -0.85f, 0f, 0f, 0f, 230f, // Red
                        0f, -0.85f, 0f, 0f, 230f, // Green
                        0f, 0f, -0.85f, 0f, 230f, // Blue
                        0f, 0f, 0f, 1f, 0f        // Alpha
                    )
                )
                mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
            } else {
                mapView.overlayManager.tilesOverlay.setColorFilter(null)
            }

            // Ensure osmdroid CopyrightOverlay is present
            val existingCopyright = mapView.overlays.filterIsInstance<CopyrightOverlay>().firstOrNull()
            if (existingCopyright == null) {
                val copyright = CopyrightOverlay(mapView.context).apply {
                    setCopyrightNotice(ATTRIBUTION_TEXT)
                    setAlignRight(false)
                    setTextSize(9)
                    setTextColor(android.graphics.Color.DKGRAY)
                }
                mapView.overlays.add(copyright)
            }
        }
    }
}

/**
 * Reusable Compose bottom-left attribution pill for maps.
 */
@Composable
fun MapAttributionPill(
    modifier: Modifier = Modifier,
    isOffline: Boolean = false
) {
    Box(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.65f),
                RoundedCornerShape(topEnd = 8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = DanskStiTileSource.ATTRIBUTION_TEXT,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}
