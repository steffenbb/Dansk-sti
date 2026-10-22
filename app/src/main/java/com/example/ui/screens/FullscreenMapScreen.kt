package com.example.ui.screens

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.TrailSegmentType
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.routing.RouteMarkerHelper
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

@Composable
fun FullscreenMapScreen(
    trailId: String,
    onBack: () -> Unit,
    onOpenRouteBuilder: ((lat: Double, lng: Double, label: String) -> Unit)? = null,
    viewModel: TrailViewModel = viewModel()
) {
    val systemDark = isSystemInDarkTheme()

    LaunchedEffect(trailId) {
        viewModel.loadTrail(trailId)
    }

    val trail by viewModel.trail.collectAsState()
    val trailGeometry by viewModel.trailGeometry.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isFetchingLiveRoute by viewModel.isFetchingLiveRoute.collectAsState()
    val routeError by viewModel.routeError.collectAsState()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var hasZoomedToRoute by remember(trailId) { mutableStateOf(false) }
    var lastRouteSignature by remember(trailId) { mutableIntStateOf(0) }

    val segments = trailGeometry?.segments ?: emptyList()

    // Fit map bounding box when route geometry arrives
    LaunchedEffect(segments, trail, mapViewRef) {
        val mv = mapViewRef ?: return@LaunchedEffect
        if (hasZoomedToRoute) return@LaunchedEffect
        val allPoints = segments.flatMap { seg -> seg.points.map { GeoPoint(it.lat, it.lng) } }
        if (allPoints.size >= 2) {
            try {
                val box = BoundingBox.fromGeoPoints(allPoints)
                mv.post {
                    try {
                        mv.zoomToBoundingBox(box, true, 72)
                        hasZoomedToRoute = true
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        } else {
            val t = trail
            if (t?.bboxNorth != null && t.bboxSouth != null && t.bboxEast != null && t.bboxWest != null) {
                try {
                    val box = BoundingBox(t.bboxNorth, t.bboxEast, t.bboxSouth, t.bboxWest)
                    mv.post {
                        try {
                            mv.zoomToBoundingBox(box, false, 72)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
    }

    DanskStiMap(
        modifier = Modifier.fillMaxSize(),
        mapTestTag = "fullscreen_osmdroid_map",
        initialCenter = GeoPoint(56.0, 10.2),
        initialZoom = 9.0,
        isSystemDarkOverride = systemDark,
        controlsTopPadding = 16.dp,
        layersButtonTestTag = "toggle_map_overlays_button",
        onRouteHereFromDropPin = { lat, lng, label ->
            onOpenRouteBuilder?.invoke(lat, lng, label)
        },
        onMapReady = { mv -> mapViewRef = mv },
        updateOverlaysKey = segments,
        onUpdateOverlays = { mapView, overlayManager, isDarkMap ->
            var sig = if (isDarkMap) 1231 else 1237
            sig = 31 * sig + segments.size
            for (seg in segments) {
                sig = 31 * sig + seg.points.size
            }
            if (sig != lastRouteSignature) {
                lastRouteSignature = sig
                val density = mapView.context.resources.displayMetrics.density
                val children = ArrayList<Overlay>()
                val routeColor = if (isDarkMap) {
                    RouteMarkerHelper.COLOR_PLANNED_ROUTE_DARK
                } else {
                    RouteMarkerHelper.COLOR_PLANNED_ROUTE_LIGHT
                }
                val casingColor = if (isDarkMap) {
                    RouteMarkerHelper.COLOR_PLANNED_CASING_DARK
                } else {
                    RouteMarkerHelper.COLOR_PLANNED_CASING_LIGHT
                }

                for (seg in segments) {
                    val geoPoints = seg.points.map { GeoPoint(it.lat, it.lng) }
                    if (geoPoints.size >= 2) {
                        if (seg.type == TrailSegmentType.WALK) {
                            val casing = Polyline().apply {
                                setPoints(geoPoints)
                                outlinePaint.color = casingColor
                                outlinePaint.strokeWidth = 7.5f * density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true
                            }
                            children.add(casing)
                        }
                        val line = Polyline().apply {
                            setPoints(geoPoints)
                            if (seg.type == TrailSegmentType.FERRY) {
                                outlinePaint.color = 0xFF607D8B.toInt()
                                outlinePaint.strokeWidth = 3.5f * density
                                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
                            } else {
                                outlinePaint.color = routeColor
                                outlinePaint.strokeWidth = 4.5f * density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                            }
                            outlinePaint.isAntiAlias = true
                        }
                        children.add(line)
                    }
                }
                overlayManager.updateFolderOverlay(MapLayersOverlayManager.KEY_ROUTE, children)
            }
        }
    ) { _, _ ->
        // Top Header Overlay (Back button + Trail Title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 16.dp, end = 76.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onBack() }
                    .testTag("fullscreen_map_back_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Tilbage",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            trail?.let { t ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = t.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Live loading indicator if downloading from OSM
        if (segments.isEmpty() && (isLoading || isFetchingLiveRoute)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ForestGreenPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Henter rutedata live fra OpenStreetMap...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Error card overlay with retry button when route fetch fails
        if (segments.isEmpty() && routeError != null && !isLoading && !isFetchingLiveRoute) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Netværksfejl",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Kunne ikke hente rutedata",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Ruten kunne ikke hentes fra OpenStreetMap. Tjek din internetforbindelse eller prøv igen.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { viewModel.loadTrail(trailId) },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("fullscreen_retry_osm_route_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Prøv igen", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
