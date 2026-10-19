package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import com.example.ui.components.ApiKeyPromptDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.remote.TrailWeatherInfo
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.NordicBlue
import com.example.ui.viewmodel.OvernightSpotItem
import com.example.ui.viewmodel.PoiSpotItem
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import com.example.util.GeoUtils
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@Composable
fun TrailDetailScreen(
    trailId: String,
    viewModel: TrailViewModel = viewModel(),
    onBack: () -> Unit,
    onPlanTrip: (String) -> Unit,
    onStartNavigation: (Long) -> Unit,
    onOpenFullscreenMap: (String) -> Unit,
    onDownloadOffline: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenRouteBuilder: (destLat: Double?, destLng: Double?, destName: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trail by viewModel.trail.collectAsState()
    val points by viewModel.trailPoints.collectAsState()
    val trailGeometry by viewModel.trailGeometry.collectAsState()
    val overnightSpots by viewModel.overnightSpots.collectAsState()
    val waterAndToilets by viewModel.waterAndToilets.collectAsState()
    val shops by viewModel.shops.collectAsState()
    val transports by viewModel.transports.collectAsState()
    val elevationGain by viewModel.elevationGain.collectAsState()
    val elevationLoss by viewModel.elevationLoss.collectAsState()
    val surfaceMix by viewModel.surfaceMix.collectAsState()
    val weatherStart by viewModel.weatherStart.collectAsState()
    val weatherEnd by viewModel.weatherEnd.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val routeError by viewModel.routeError.collectAsState()
    val isFetchingLiveRoute by viewModel.isFetchingLiveRoute.collectAsState()

    var showApiKeyPrompt by remember { mutableStateOf(!DanskStiTileSource.hasValidKey && !DanskStiTileSource.userConfirmedOsmFallback) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val isDark = isSystemInDarkTheme()

    if (showApiKeyPrompt) {
        ApiKeyPromptDialog(
            onDismiss = { showApiKeyPrompt = false },
            onUseOsmFallback = {
                DanskStiTileSource.userConfirmedOsmFallback = true
                showApiKeyPrompt = false
            }
        )
    }

    LaunchedEffect(trailId) {
        viewModel.loadTrail(trailId)
    }

    BackHandler {
        onBack()
    }

    if (trail == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ForestGreenPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Henter ruteoplysninger...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = routeError ?: "Ruten blev ikke fundet i databasen.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                    ) {
                        Text("Gå tilbage")
                    }
                }
            }
        }
        return
    }

    val t = trail!!

    val totalKm = t.computedWalkingKm
    val estimatedHours = (totalKm / 4.0).toInt()
    val estimatedDays = (totalKm / 15.0).toInt().coerceAtLeast(1)
    val isCircuit = remember(points) {
        if (points.isEmpty()) false else {
            GeoUtils.distanceMeters(points.first().lat, points.first().lng, points.last().lat, points.last().lng) < 800.0
        }
    }
    val routeType = if (isCircuit) "Rundtur" else "Strækning"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP: 40% height map of the route (no gestures; tap = full-screen map)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.40f)
                .clickable { onOpenFullscreenMap(trailId) }
                .testTag("trail_detail_top_map_box")
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(false) // No gestures!
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        DanskStiTileSource.applyTo(this, isDark)
                    }
                },
                update = { mapView ->
                    DanskStiTileSource.applyTo(mapView, isDark)
                    mapView.overlays.removeAll { it is Polyline }

                    val segments: List<TrailSegmentDto> = trailGeometry?.segments ?: if (points.isNotEmpty()) listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = points)) else emptyList()
                    if (segments.isNotEmpty()) {
                        for (seg in segments) {
                            val polyline = Polyline(mapView).apply {
                                setPoints(seg.points.map { GeoPoint(it.lat, it.lng) })
                                outlinePaint.color = if (seg.type == TrailSegmentType.FERRY) {
                                    AndroidColor.rgb(2, 136, 209)
                                } else {
                                    AndroidColor.rgb(35, 83, 63)
                                }
                                outlinePaint.strokeWidth = if (seg.type == TrailSegmentType.FERRY) 6f else 8f
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                if (seg.type == TrailSegmentType.FERRY) {
                                    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f)
                                }
                            }
                            mapView.overlays.add(polyline)
                        }

                        val allSegmentPoints = segments.flatMap { it.points }.filter { it.lat > 10.0 && it.lng > 0.0 }
                        if (allSegmentPoints.isNotEmpty()) {
                            val minLat = allSegmentPoints.minOf { it.lat }
                            val maxLat = allSegmentPoints.maxOf { it.lat }
                            val minLng = allSegmentPoints.minOf { it.lng }
                            val maxLng = allSegmentPoints.maxOf { it.lng }
                            val bbox = BoundingBox(
                                (maxLat + 0.02).coerceAtMost(85.0),
                                (maxLng + 0.02).coerceAtMost(180.0),
                                (minLat - 0.02).coerceAtLeast(-85.0),
                                (minLng - 0.02).coerceAtLeast(-180.0)
                            )
                            if (mapView.width > 0 && mapView.height > 0) {
                                try {
                                    mapView.zoomToBoundingBox(bbox, false)
                                } catch (e: Exception) {
                                    android.util.Log.w("TrailDetail", "zoomToBoundingBox failed: ${e.message}")
                                }
                            } else {
                                mapView.post {
                                    try {
                                        mapView.zoomToBoundingBox(bbox, false)
                                    } catch (e: Exception) {
                                        android.util.Log.w("TrailDetail", "zoomToBoundingBox post failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Transparent overlay to capture map tap and route to fullscreen (only when no error is displayed)
            if (routeError == null || points.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onOpenFullscreenMap(trailId) }
                )
            }

            // Live fetching indicator from OpenStreetMap
            if (isFetchingLiveRoute) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ForestGreenPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Henter live rutedata fra OpenStreetMap...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Error banner overlay when live OSM route fetching fails
            if (routeError != null && points.isEmpty() && !isLoading && !isFetchingLiveRoute) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Netværksfejl",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Kunne ikke hente live rutedata",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tjek internetforbindelsen for at hente ruten fra OpenStreetMap.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.retryLoadRoute() },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("retry_osm_route_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Prøv igen", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Top action buttons (Back & Fullscreen hint)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("trail_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.clickable { onOpenDiagnostics() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "API Status",
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Netværk",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.clickable { onOpenFullscreenMap(trailId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Fuldskærmskort",
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Fuldskærm",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Live OpenStreetMap loading & status badge on the map
            val segmentsCount = trailGeometry?.segments?.size ?: 0
            if (segmentsCount == 0 && isLoading) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ForestGreenPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Henter rutedata live fra OpenStreetMap...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (segmentsCount > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Live OSM • $segmentsCount ${if (segmentsCount == 1) "del" else "dele"}",
                            fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            }
        }

        // BOTTOM: 60% height scrollable details & tabs
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.60f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // LazyColumn for the scrollable content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Title Block
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = t.name,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("trail_detail_name")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${t.region} • ${t.parentRegion}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "• Data: 2026",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Stats Row: Længde, Stigning, Estimeret tid, Type
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatCardItem(label = "Længde", value = "${String.format(Locale.US, "%.1f", totalKm)} km")
                        StatCardItem(label = "Stigning", value = "+${elevationGain} m")
                        StatCardItem(label = "Est. tid", value = "$estimatedDays ${if (estimatedDays == 1) "dag" else "dage"}")
                        StatCardItem(label = "Type", value = routeType)
                    }
                }

                // Action Row of 4 Round Buttons: Gem, Hent offline, Del, Navigér
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RoundActionButton(
                            icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            label = if (isSaved) "Gemt" else "Gem",
                            tint = if (isSaved) AmberAccent else MaterialTheme.colorScheme.onSurface,
                            onClick = { viewModel.toggleSave() },
                            testTag = "action_save_trail"
                        )

                        RoundActionButton(
                            icon = Icons.Default.CloudDownload,
                            label = "Offline",
                            tint = ForestGreenPrimary,
                            onClick = { onDownloadOffline(trailId) },
                            testTag = "action_download_offline"
                        )

                        RoundActionButton(
                            icon = Icons.Default.Share,
                            label = "GPX / Del",
                            tint = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                if (points.isNotEmpty()) {
                                    val gpxContent = com.example.util.GpxExporter.generateMultiSegmentGpx(
                                        trail = t,
                                        plan = null,
                                        stages = emptyList(),
                                        trailPoints = points,
                                        shelters = emptyList()
                                    )
                                    com.example.util.GpxExporter.exportAndShareGpxFile(
                                        context = context,
                                        fileName = "${t.name.replace(" ", "_")}.gpx",
                                        gpxContent = gpxContent,
                                        shareTitle = "Eksporter ${t.name} som GPX"
                                    )
                                } else {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Tjek vandreruten ${t.name} ($totalKm km) i Dansk Sti!")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Del rute"))
                                }
                            },
                            testTag = "action_share_trail"
                        )

                        RoundActionButton(
                            icon = Icons.Default.Navigation,
                            label = "Navigér",
                            tint = ForestGreenPrimary,
                            onClick = {
                                viewModel.onNavigateClicked(
                                    trailId = trailId,
                                    onNavigate = onStartNavigation,
                                    onOpenPlanner = { onPlanTrip(trailId) }
                                )
                            },
                            testTag = "action_navigate_trail"
                        )
                    }
                }

                // Tabs: Oversigt, Overnatning, Vand & mad, Vejr, Transport
                item {
                    val tabs = listOf("Oversigt", "Overnatning", "Vand & mad", "Vejr", "Transport")
                    ScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = ForestGreenPrimary,
                        edgePadding = 16.dp,
                        indicator = { tabPositions ->
                            if (selectedTabIndex < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    color = ForestGreenPrimary
                                )
                            }
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Text(
                                        text = title,
                                        fontSize = 14.sp,
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTabIndex == index) ForestGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.testTag("tab_${title.lowercase().replace(" ", "_")}")
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Tab Content
                when (selectedTabIndex) {
                    0 -> { // Oversigt: Description, Elevation Profile, Surface Mix
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Description
                                Text(
                                    text = t.description,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // Elevation Profile
                                if (points.any { it.ele != null }) {
                                    Text(
                                        text = "Højdeprofil",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    ElevationProfileCanvas(
                                        points = points,
                                        gainM = elevationGain,
                                        lossM = elevationLoss,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                    )
                                }

                                // Surface Mix
                                Text(
                                    text = "Underlag",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SurfaceMixBar(surfaceMix = surfaceMix)
                            }
                        }
                    }

                    1 -> { // Overnatning (list sorted by km)
                        if (overnightSpots.isEmpty()) {
                            item {
                                Text(
                                    text = "Ingen overnatningssteder registreret på denne rute.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            items(overnightSpots, key = { it.id }) { spot ->
                                OvernightSpotCard(spot = spot)
                            }
                        }
                    }

                    2 -> { // Vand & mad (water, toilets, shops by km)
                        val combinedPois = (waterAndToilets + shops).sortedBy { it.kmOnRoute }
                        if (combinedPois.isEmpty()) {
                            item {
                                Text(
                                    text = "Ingen vandposter eller butikker registreret.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            items(combinedPois, key = { it.id }) { poi ->
                                PoiItemCard(poi = poi)
                            }
                        }
                    }

                    3 -> { // Vejr (Open-Meteo forecast for start and end)
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Vejrudsigt (Open-Meteo)",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                WeatherForecastCard(
                                    title = "Startsted (${points.firstOrNull()?.let { "km 0" } ?: ""})",
                                    weather = weatherStart
                                )

                                WeatherForecastCard(
                                    title = "Slutsted (${points.lastOrNull()?.let { "km ${String.format(Locale.US, "%.1f", totalKm)}" } ?: ""})",
                                    weather = weatherEnd
                                )
                            }
                        }
                    }

                    4 -> { // Transport (stations & ferry terminals by km)
                        if (transports.isEmpty()) {
                            item {
                                Text(
                                    text = "Ingen togstationer eller færgehavne registreret direkte langs ruten.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            items(transports, key = { it.id }) { trans ->
                                PoiItemCard(poi = trans)
                            }
                        }
                    }
                }
            }

            // Fixed Bottom Bar with Big Green "Planlæg tur" Button (56dp high, glove friendly)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = { onPlanTrip(trailId) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("plan_trip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Planlæg tur",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCardItem(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.width(80.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RoundActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            shadowElevation = 2.dp,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ElevationProfileCanvas(
    points: List<GeoPointDto>,
    gainM: Int,
    lossM: Int,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "▲ +$gainM m stigning", fontSize = 12.sp, color = ForestGreenPrimary, fontWeight = FontWeight.SemiBold)
                Text(text = "▼ -$lossM m fald", fontSize = 12.sp, color = AmberAccent, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(modifier = Modifier.fillMaxSize()) {
                val elePoints = points.mapNotNull { it.ele }
                if (elePoints.size < 2) return@Canvas

                val minEle = elePoints.minOrNull() ?: 0.0
                val maxEle = elePoints.maxOrNull() ?: 0.0
                val dEle = (maxEle - minEle).coerceAtLeast(10.0)

                val step = size.width / (points.size - 1)
                val path = Path()

                points.forEachIndexed { i, pt ->
                    val ele = pt.ele ?: minEle
                    val x = i * step
                    val y = ((1.0 - (ele - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = ForestGreenPrimary,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun SurfaceMixBar(surfaceMix: Map<String, Int>) {
    val stiPct = (surfaceMix["Sti"] ?: 70).coerceAtLeast(0)
    val grusPct = (surfaceMix["Grus"] ?: 20).coerceAtLeast(0)
    val asfaltPct = (surfaceMix["Asfalt"] ?: 10).coerceAtLeast(0)

    val validSti = if (stiPct > 0) stiPct.toFloat() else 0.001f
    val validGrus = if (grusPct > 0) grusPct.toFloat() else 0.001f
    val validAsfalt = if (asfaltPct > 0) asfaltPct.toFloat() else 0.001f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            Box(
                modifier = Modifier
                    .weight(validSti)
                    .background(ForestGreenPrimary)
            )
            Box(
                modifier = Modifier
                    .weight(validGrus)
                    .background(AmberAccent)
            )
            Box(
                modifier = Modifier
                    .weight(validAsfalt)
                    .background(Color.Gray)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(ForestGreenPrimary, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sti $stiPct%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(AmberAccent, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Grus $grusPct%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.Gray, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Asfalt $asfaltPct%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun OvernightSpotCard(spot: OvernightSpotItem) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (spot.type == "SHELTER") ForestGreenPrimary.copy(alpha = 0.15f) else AmberAccent.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (spot.type == "SHELTER") Icons.Default.Cabin else Icons.Default.Forest,
                            contentDescription = null,
                            tint = if (spot.type == "SHELTER") ForestGreenPrimary else AmberAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spot.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (spot.detourMeters > 50) "${spot.detourMeters.toInt()}m afstikker" else "Direkte på ruten",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (spot.bookingRequired) {
                        Text(
                            text = "Kræver booking",
                            fontSize = 12.sp,
                            color = AmberAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "km ${String.format(Locale.US, "%.1f", spot.kmOnRoute)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Phase 7c: Booking action if required
            if (spot.bookingRequired) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tjek ledighed hos udbyder",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    val targetUrl = if (!spot.bookingUrl.isNullOrBlank()) {
                        spot.bookingUrl
                    } else {
                        "https://udinaturen.dk/soeg?q=${android.net.Uri.encode(spot.name)}"
                    }
                    val buttonLabel = if (!spot.bookingUrl.isNullOrBlank()) "Åbn booking" else "Book via udinaturen.dk"

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.util.Log.e("TrailDetail", "Cannot open booking URL: ${e.message}")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberAccent),
                        border = BorderStroke(1.dp, AmberAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp).testTag("shelter_booking_button_${spot.id}")
                    ) {
                        Text(text = buttonLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PoiItemCard(
    poi: PoiSpotItem,
    onRouteHerTil: (() -> Unit)? = null
) {
    val icon = when (poi.type) {
        "water" -> Icons.Default.LocalDrink
        "toilet" -> Icons.Default.Wc
        "shop" -> Icons.Default.Storefront
        "station" -> Icons.Default.DirectionsTransit
        "ferry" -> Icons.Default.DirectionsTransit
        else -> Icons.Default.Place
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ForestGreenPrimary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (poi.detourMeters > 50) {
                    Text(
                        text = "${poi.detourMeters.toInt()} m afstikker",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "km ${String.format(Locale.US, "%.1f", poi.kmOnRoute)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = ForestGreenPrimary
            )
            if (onRouteHerTil != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onRouteHerTil,
                    modifier = Modifier.size(32.dp).testTag("poi_route_here_${poi.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = "Rute hertil",
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherForecastCard(
    title: String,
    weather: TrailWeatherInfo?
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (weather == null) {
                Text("Venter på vejrdata...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = null,
                            tint = AmberAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${weather.tempC?.toInt() ?: "--"}°C",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = NordicBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${weather.precipitationMm ?: 0.0} mm",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = weather.conditionDescription ?: "Skyet",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
