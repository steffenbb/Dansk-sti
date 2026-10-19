package com.example.ui.screens

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.ui.components.ApiKeyPromptDialog
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import com.example.util.DanskStiTileSourceHelper
import com.example.util.MapAttributionPill
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun FullscreenMapScreen(
    trailId: String,
    viewModel: TrailViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trail by viewModel.trail.collectAsState()
    val points by viewModel.trailPoints.collectAsState()
    val trailGeometry by viewModel.trailGeometry.collectAsState()
    val overnightSpots by viewModel.overnightSpots.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val routeError by viewModel.routeError.collectAsState()
    val isFetchingLiveRoute by viewModel.isFetchingLiveRoute.collectAsState()
    val isDark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsRepository = remember { com.example.data.local.SettingsRepository(context) }
    val layersState by settingsRepository.mapLayersFlow.collectAsState(initial = com.example.data.local.MapLayersState())

    var showLayersSheet by remember { mutableStateOf(false) }
    var showApiKeyPrompt by remember { mutableStateOf(!DanskStiTileSource.hasValidKey && !DanskStiTileSource.userConfirmedOsmFallback) }

    LaunchedEffect(trailId) {
        viewModel.loadTrail(trailId)
    }

    BackHandler {
        onBack()
    }

    if (showApiKeyPrompt) {
        ApiKeyPromptDialog(
            onDismiss = { showApiKeyPrompt = false },
            onUseOsmFallback = {
                DanskStiTileSource.userConfirmedOsmFallback = true
                showApiKeyPrompt = false
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    DanskStiTileSourceHelper.applyTo(this, layersState.baseMap, isDark)
                }
            },
            update = { mapView ->
                DanskStiTileSourceHelper.applyTo(mapView, layersState.baseMap, isDark)
                mapView.overlays.removeAll { it is Polyline || it is Marker }

                val segments: List<TrailSegmentDto> = trailGeometry?.segments ?: if (points.isNotEmpty()) listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = points)) else emptyList()
                if (segments.isNotEmpty()) {
                    for (seg in segments) {
                        val polyline = Polyline(mapView).apply {
                            setPoints(seg.points.map { GeoPoint(it.lat, it.lng) })
                            outlinePaint.color = if (seg.type == TrailSegmentType.FERRY) {
                                AndroidColor.rgb(2, 136, 209) // Nordic blue
                            } else {
                                AndroidColor.rgb(35, 83, 63)  // Forest green
                            }
                            outlinePaint.strokeWidth = if (seg.type == TrailSegmentType.FERRY) 7f else 9f
                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            if (seg.type == TrailSegmentType.FERRY) {
                                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 18f), 0f)
                            }
                        }
                        mapView.overlays.add(polyline)
                    }

                    // Add shelter & fri teltning overlay markers if enabled in layer settings
                    if (layersState.showShelters || layersState.showFriTeltning) {
                        for (spot in overnightSpots) {
                            if (spot.type == "SHELTER" && !layersState.showShelters) continue
                            if (spot.type == "FRI_TELTNING" && !layersState.showFriTeltning) continue

                            val marker = Marker(mapView).apply {
                                position = GeoPoint(spot.lat, spot.lng)
                                title = spot.name
                                snippet = if (spot.type == "SHELTER") "Shelter • km ${String.format("%.1f", spot.kmOnRoute)}" else "Fri teltning • km ${String.format("%.1f", spot.kmOnRoute)}"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            mapView.overlays.add(marker)
                        }
                    }

                    val allSegmentPoints = segments.flatMap { it.points }.filter { it.lat > 10.0 && it.lng > 0.0 }
                    if (allSegmentPoints.isNotEmpty()) {
                        val minLat = allSegmentPoints.minOf { it.lat }
                        val maxLat = allSegmentPoints.maxOf { it.lat }
                        val minLng = allSegmentPoints.minOf { it.lng }
                        val maxLng = allSegmentPoints.maxOf { it.lng }
                        val bbox = BoundingBox(
                            (maxLat + 0.03).coerceAtMost(85.0),
                            (maxLng + 0.03).coerceAtMost(180.0),
                            (minLat - 0.03).coerceAtLeast(-85.0),
                            (minLng - 0.03).coerceAtLeast(-180.0)
                        )
                        if (mapView.width > 0 && mapView.height > 0) {
                            try {
                                mapView.zoomToBoundingBox(bbox, false)
                            } catch (e: Exception) {
                                android.util.Log.w("FullscreenMap", "zoomToBoundingBox failed: ${e.message}")
                            }
                        } else {
                            mapView.post {
                                try {
                                    mapView.zoomToBoundingBox(bbox, false)
                                } catch (e: Exception) {
                                    android.util.Log.w("FullscreenMap", "zoomToBoundingBox post failed: ${e.message}")
                                }
                            }
                        }
                    }
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Action Bar: Back button, Title & Layer toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 6.dp,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("fullscreen_map_back_button")
                ) {
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

            // Layer toggle sheet button
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { showLayersSheet = true }
                    .testTag("toggle_map_overlays_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Kortlag",
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Live loading indicator if downloading from OSM
        val segments = trailGeometry?.segments ?: emptyList()
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
                        onClick = { viewModel.retryLoadRoute() },
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

        // Bottom Attribution
        MapAttributionPill(
            mapLayersState = layersState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp, start = 8.dp)
                .testTag("fullscreen_map_attribution")
        )

        // Phase 13 Requirement D: Map Layers Bottom Sheet
        if (showLayersSheet) {
            MapLayersBottomSheet(
                layersState = layersState,
                onBaseMapChanged = { coroutineScope.launch { settingsRepository.setBaseMap(it) } },
                onToggleMarkedRoutes = { coroutineScope.launch { settingsRepository.setOverlayMarkedRoutes(it) } },
                onToggleAccess = { coroutineScope.launch { settingsRepository.setOverlayAccess(it) } },
                onToggleShelters = { coroutineScope.launch { settingsRepository.setOverlayShelters(it) } },
                onToggleFriTeltning = { coroutineScope.launch { settingsRepository.setOverlayFriTeltning(it) } },
                onToggleWater = { coroutineScope.launch { settingsRepository.setOverlayWater(it) } },
                onToggleToilets = { coroutineScope.launch { settingsRepository.setOverlayToilets(it) } },
                onToggleShops = { coroutineScope.launch { settingsRepository.setOverlayShops(it) } },
                onToggleTransport = { coroutineScope.launch { settingsRepository.setOverlayTransport(it) } },
                onToggleMyPoints = { coroutineScope.launch { settingsRepository.setOverlayMyPoints(it) } },
                onDismiss = { showLayersSheet = false }
            )
        }
    }
}
