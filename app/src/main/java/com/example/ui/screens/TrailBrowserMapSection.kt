package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.theme.Spacing
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.TrailCardMetrics
import com.example.util.routing.RouteMarkerHelper
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

private val TRAIL_LINE_COLORS = listOf(
    0xFF2E6B3F.toInt(),
    0xFF1565C0.toInt(),
    0xFFE65100.toInt(),
    0xFF6A1B9A.toInt(),
    0xFF00838F.toInt(),
    0xFFAD1457.toInt()
)

@Composable
internal fun TrailBrowserMapContent(
    isDark: Boolean,
    trails: List<TrailEntity>,
    allGeometries: Map<String, List<GeoPointDto>>,
    trailMetrics: Map<String, TrailCardMetrics>,
    shelterCounts: Map<String, Int>,
    friTeltningCounts: Map<String, Int>,
    downloadedTrailMap: Map<String, DownloadedTrailEntity>,
    savedTrailIds: Set<String>,
    selectedTrailForPeek: TrailEntity?,
    viewModel: ExploreViewModel,
    onMapReady: (MapView) -> Unit,
    onCenterDenmark: () -> Unit,
    onOpenRouteBuilder: (Double, Double, String) -> Unit,
    onTrailClick: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var lastExploreRouteSig by remember { mutableIntStateOf(0) }
    var currentZoomTier by remember { mutableIntStateOf(0) }
    val searchedFocus = viewModel.searchedMapFocus.value

    Box(modifier = Modifier.fillMaxSize()) {
        DanskStiMap(
            modifier = Modifier.fillMaxSize(),
            mapTestTag = "explore_full_screen_map",
            initialCenter = searchedFocus?.let { GeoPoint(it.first, it.second) } ?: GeoPoint(55.9, 10.4),
            initialZoom = if (searchedFocus != null) 13.0 else 7.5,
            interceptParentTouchScroll = true,
            appIsDark = isDark,
            controlsTopPadding = 16.dp,
            controlsEndPadding = 16.dp,
            attributionBottomPadding = 24.dp,
            layersButtonTestTag = "open_layers_sheet_button",
            compassButtonTestTag = "center_denmark_button",
            myLocationButtonTestTag = "explore_my_location_button",
            onCompassClick = onCenterDenmark,
            onMapReady = { mv ->
                onMapReady(mv)
                viewModel.searchedMapFocus.value?.let { (fLat, fLng) ->
                    mv.controller.setZoom(13.0)
                    mv.controller.setCenter(GeoPoint(fLat, fLng))
                }
            },
            onAddToRouteFromDropPin = onOpenRouteBuilder,
            onRouteHereFromDropPin = onOpenRouteBuilder,
            onUseMarkedRouteInPlanner = { markedRoute ->
                coroutineScope.launch {
                    viewModel.saveMarkedRouteAsTrail(markedRoute)
                }
            },
            onCameraChanged = { _, zoom, _ ->
                val tier = computeUdforskZoomTier(zoom)
                if (tier != currentZoomTier) {
                    currentZoomTier = tier
                }
            },
            updateOverlaysKey = Pair(Triple(trails, allGeometries, selectedTrailForPeek), currentZoomTier),
            onUpdateOverlays = { mapView, overlayManager, isDarkMap ->
                val tier = computeUdforskZoomTier(mapView.zoomLevelDouble)
                if (tier != currentZoomTier) {
                    currentZoomTier = tier
                }
                var sig = if (isDarkMap) 1231 else 1237
                sig = 31 * sig + tier
                sig = 31 * sig + trails.size
                sig = 31 * sig + allGeometries.size
                sig = 31 * sig + (selectedTrailForPeek?.id?.hashCode() ?: 0)
                if (sig != lastExploreRouteSig) {
                    lastExploreRouteSig = sig
                    val density = mapView.context.resources.displayMetrics.density
                    val children = ArrayList<Overlay>()
                    val defaultCasingColor = if (isDarkMap) {
                        RouteMarkerHelper.COLOR_PLANNED_CASING_DARK
                    } else {
                        RouteMarkerHelper.COLOR_PLANNED_CASING_LIGHT
                    }

                    trails.forEachIndexed { index, trail ->
                        val rawPoints = allGeometries[trail.id] ?: emptyList()
                        if (rawPoints.isNotEmpty()) {
                            val decimatedPoints = decimateCatalogueTrailForZoomTier(rawPoints, tier)
                            val geoPoints = decimatedPoints.map { GeoPoint(it.lat, it.lng) }
                            val isSelected = selectedTrailForPeek?.id == trail.id
                            val casing = Polyline(mapView).apply {
                                setPoints(geoPoints)
                                outlinePaint.color = defaultCasingColor
                                outlinePaint.strokeWidth = (if (isSelected) 7.5f else 5.0f) * density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true
                            }
                            children.add(casing)

                            val polyline = Polyline(mapView).apply {
                                setPoints(geoPoints)
                                outlinePaint.color = if (isSelected) {
                                    if (isDarkMap) RouteMarkerHelper.COLOR_PLANNED_ROUTE_DARK
                                    else RouteMarkerHelper.COLOR_PLANNED_ROUTE_LIGHT
                                } else {
                                    TRAIL_LINE_COLORS[index % TRAIL_LINE_COLORS.size]
                                }
                                outlinePaint.strokeWidth = (if (isSelected) 5.0f else 3.2f) * density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true

                                setOnClickListener { _, _, _ ->
                                    viewModel.selectTrailForPeek(trail)
                                    true
                                }
                            }
                            children.add(polyline)
                        }
                    }
                    overlayManager.updateFolderOverlay(
                        MapLayersOverlayManager.KEY_ROUTE,
                        children
                    )
                }
            }
        )

        selectedTrailForPeek?.let { peekTrail ->
            val metrics = trailMetrics[peekTrail.id]
            val points = allGeometries[peekTrail.id] ?: metrics?.geometryPoints
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(Spacing.s4)
            ) {
                TrailCard(
                    trail = peekTrail,
                    points = points,
                    sheltersCount = shelterCounts[peekTrail.id] ?: metrics?.shelterCount ?: 0,
                    friTeltningCount = friTeltningCounts[peekTrail.id] ?: metrics?.friTeltningCount ?: 0,
                    ascentMeters = metrics?.ascentMeters,
                    downloadedAt = downloadedTrailMap[peekTrail.id]?.downloadedAt,
                    isSaved = savedTrailIds.contains(peekTrail.id),
                    isSelected = true,
                    onBookmarkClick = { viewModel.toggleBookmark(peekTrail.id) },
                    onClick = { onTrailClick(peekTrail.id) }
                )
            }
        }
    }
}

// Zoom tiers for Udforsk map: 0 (zoom < 9.5), 1 (9.5..12.0), 2 (zoom >= 12.0 full geometry)
internal fun computeUdforskZoomTier(zoom: Double): Int = when {
    zoom < 9.5 -> 0
    zoom < 12.0 -> 1
    else -> 2
}

// Decimates catalogue trail points for Udforsk overview map only when zoom < 12.0.
internal fun decimateCatalogueTrailForZoomTier(
    points: List<GeoPointDto>,
    zoomTier: Int
): List<GeoPointDto> {
    if (zoomTier >= 2 || points.size <= 2) return points
    val minSpacingMeters = if (zoomTier == 0) 150.0 else 35.0
    val minSpacingSq = minSpacingMeters * minSpacingMeters
    val refLat = points[0].lat
    val cosLat = kotlin.math.cos(Math.toRadians(refLat)).coerceAtLeast(0.1)
    val metersPerDegLat = 111_320.0
    val metersPerDegLng = 111_320.0 * cosLat
    val result = ArrayList<GeoPointDto>(points.size / 4 + 2)
    var lastKept = points[0]
    result.add(lastKept)
    for (i in 1 until points.size - 1) {
        val pt = points[i]
        val dy = (pt.lat - lastKept.lat) * metersPerDegLat
        val dx = (pt.lng - lastKept.lng) * metersPerDegLng
        if (dx * dx + dy * dy >= minSpacingSq) {
            result.add(pt)
            lastKept = pt
        }
    }
    result.add(points.last())
    return result
}
