package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.ui.components.ApiKeyPromptDialog
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenDark
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.NordicBlue
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.LengthFilter
import com.example.ui.viewmodel.OvernightFilter
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.util.DanskStiTileSource
import com.example.util.DanskStiTileSourceHelper
import com.example.util.MapAttributionPill
import com.example.util.GeoUtils
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.max

private val TRAIL_LINE_COLORS = listOf(
    AndroidColor.rgb(35, 83, 63),   // ForestGreen
    AndroidColor.rgb(2, 132, 199),  // NordicBlue
    AndroidColor.rgb(217, 119, 6),  // AmberAccent
    AndroidColor.rgb(13, 148, 136), // Teal
    AndroidColor.rgb(124, 58, 237)  // Indigo/Purple
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailBrowserScreen(
    viewModel: ExploreViewModel = viewModel(),
    onTrailClick: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenCredits: () -> Unit = {},
    onOpenRouteBuilder: (destLat: Double?, destLng: Double?, destName: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trails by viewModel.filteredTrails.collectAsState()
    val allGeometries by viewModel.allTrailGeometries.collectAsState()
    val counts by viewModel.trailCounts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val lengthFilter by viewModel.lengthFilter.collectAsState()
    val overnightFilter by viewModel.overnightFilter.collectAsState()
    val selectedTrailForPeek by viewModel.selectedTrailForPeek.collectAsState()

    // Phase 7b: Nearby overnight spots
    val nearbySpots by viewModel.nearbySpots.collectAsState()
    val isSearchingNearby by viewModel.isSearchingNearby.collectAsState()
    val showNearbySheet by viewModel.showNearbySheet.collectAsState()

    // Phase 13: Map Layers & Marked Routes
    val layersState by viewModel.mapLayersState.collectAsState()
    val showLayersSheet by viewModel.showLayersSheet.collectAsState()
    val selectedMarkedRoute by viewModel.selectedMarkedRoute.collectAsState()
    var overlayManagerRef by remember { mutableStateOf<MapLayersOverlayManager?>(null) }

    val fusedClient = remember { com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context) }

    val fetchLocationAndSearch = {
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    viewModel.findNearbyOvernights(loc.latitude, loc.longitude)
                } else {
                    viewModel.findNearbyOvernights(55.6761, 12.5683)
                }
            }.addOnFailureListener {
                viewModel.findNearbyOvernights(55.6761, 12.5683)
            }
        } catch (e: SecurityException) {
            viewModel.findNearbyOvernights(55.6761, 12.5683)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchLocationAndSearch()
        } else {
            viewModel.findNearbyOvernights(55.6761, 12.5683)
        }
    }

    val triggerNearbySearch = {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fetchLocationAndSearch()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )
    val coroutineScope = rememberCoroutineScope()
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    var showApiKeyPrompt by remember { mutableStateOf(false) }
    var longPressedLocation by remember { mutableStateOf<GeoPoint?>(null) }

    if (showApiKeyPrompt) {
        ApiKeyPromptDialog(
            onDismiss = { showApiKeyPrompt = false },
            onUseOsmFallback = {
                DanskStiTileSource.userConfirmedOsmFallback = true
                showApiKeyPrompt = false
                mapViewRef?.let { DanskStiTileSource.applyTo(it, isDark) }
            }
        )
    }

    longPressedLocation?.let { p: org.osmdroid.util.GeoPoint ->
        AlertDialog(
            onDismissRequest = { longPressedLocation = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = ForestGreenPrimary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Rute hertil") },
            text = {
                Text(
                    text = String.format(
                        Locale.GERMANY,
                        "Vil du planlægge en rute til det valgte punkt?\n(%.4f, %.4f)",
                        p.latitude,
                        p.longitude
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val lat = p.latitude
                        val lng = p.longitude
                        longPressedLocation = null
                        onOpenRouteBuilder(lat, lng, "Valgt punkt på kort")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier.testTag("confirm_route_here_button")
                ) {
                    Text("Planlæg rute hertil")
                }
            },
            dismissButton = {
                TextButton(onClick = { longPressedLocation = null }) {
                    Text("Annuller")
                }
            }
        )
    }

    val regions = listOf("Alle", "Fyn & Øerne", "Jylland", "Sjælland", "Bornholm")

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 150.dp,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Header with total count
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Danske Stier",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${trails.size} ruter fundet",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenRouteBuilder(null, null, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("plan_route_button")
                        ) {
                            Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Planlæg rute", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (selectedTrailForPeek != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.clickable {
                                    viewModel.selectTrailForPeek(null)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Vis alle",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Luk valgt sti",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (trails.isEmpty()) {
                    val isDefaultFilter = searchQuery.isBlank() && (selectedRegion == null || selectedRegion == "Alle") && lengthFilter == LengthFilter.ALL && overnightFilter == OvernightFilter.ALL
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDefaultFilter) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = ForestGreenPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "Indlæser danske vandreruter...",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Landscape,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Ingen ruter matcher søgningen",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Prøv at nulstille filtrene for at se alle danske ruter.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        viewModel.setSearchQuery("")
                                        viewModel.setSelectedRegion("Alle")
                                        viewModel.setLengthFilter(LengthFilter.ALL)
                                        viewModel.setOvernightFilter(OvernightFilter.ALL)
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .testTag("reset_filters_button")
                                ) {
                                    Text("Nulstil filtre")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trail_list"),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(trails, key = { it.id }) { trail ->
                            val trailPoints = allGeometries[trail.id]
                            val trailCountInfo = counts[trail.id]
                            val isSelected = selectedTrailForPeek?.id == trail.id

                            TrailCard(
                                trail = trail,
                                points = trailPoints,
                                sheltersCount = trailCountInfo?.sheltersCount ?: 0,
                                friTeltningCount = trailCountInfo?.friTeltningCount ?: 0,
                                isSelected = isSelected,
                                onClick = {
                                    onTrailClick(trail.id)
                                }
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        // Main content: Full-screen map with search bar & filter chips overlay
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        mapViewRef = this
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        DanskStiTileSourceHelper.applyTo(this, layersState.baseMap, isDark)
                        controller.setZoom(8.0)
                        controller.setCenter(GeoPoint(55.6, 10.8)) // Central Denmark

                        val mapEvents = MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                            override fun longPressHelper(p: GeoPoint): Boolean {
                                longPressedLocation = p
                                return true
                            }
                        })
                        overlays.add(mapEvents)
                        val app = ctx.applicationContext as com.example.DanskStiApp
                        val overlayMgr = MapLayersOverlayManager(
                            mapView = this,
                            context = ctx,
                            repository = app.repository,
                            coroutineScope = coroutineScope,
                            onRouteTapped = { route -> viewModel.selectMarkedRoute(route) }
                        )
                        overlayManagerRef = overlayMgr
                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                overlayMgr.onMapMoved()
                                return false
                            }
                            override fun onZoom(event: ZoomEvent?): Boolean {
                                overlayMgr.onMapMoved()
                                return false
                            }
                        })
                    }
                },
                update = { mapView ->
                    overlayManagerRef?.updateState(layersState, isDark)

                    // Draw coloured lines for every trail
                    mapView.overlays.removeAll { it is Polyline }

                    trails.forEachIndexed { index, trail ->
                        val points = allGeometries[trail.id] ?: emptyList()
                        if (points.isNotEmpty()) {
                            val polyline = Polyline(mapView).apply {
                                val geoPoints = points.map { GeoPoint(it.lat, it.lng) }
                                setPoints(geoPoints)
                                val isSelected = selectedTrailForPeek?.id == trail.id
                                outlinePaint.color = if (isSelected) {
                                    AndroidColor.rgb(45, 106, 79) // Highlighted green
                                } else {
                                    TRAIL_LINE_COLORS[index % TRAIL_LINE_COLORS.size]
                                }
                                outlinePaint.strokeWidth = if (isSelected) 10f else 6f
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND

                                setOnClickListener { _, _, _ ->
                                    viewModel.selectTrailForPeek(trail)
                                    // Zoom map to trail bounds safely
                                    if (points.isNotEmpty()) {
                                        val minLat = points.minOf { it.lat }
                                        val maxLat = points.maxOf { it.lat }
                                        val minLng = points.minOf { it.lng }
                                        val maxLng = points.maxOf { it.lng }
                                        mapView.zoomToBoundingBox(
                                            BoundingBox(maxLat + 0.05, maxLng + 0.05, minLat - 0.05, minLng - 0.05),
                                            true
                                        )
                                    }
                                    coroutineScope.launch {
                                        scaffoldState.bottomSheetState.partialExpand()
                                    }
                                    true
                                }
                            }
                            mapView.overlays.add(polyline)
                        }
                    }
                    mapView.invalidate()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("explore_full_screen_map")
            )

            // Floating Search Bar & Filter Chips at the Top
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Search Bar
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Søg",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = {
                                Text(
                                    text = "Søg efter rute eller by...",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("trail_search_input")
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.setSearchQuery("") },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Ryd søgning",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("open_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "API & Netværksdiagnostik",
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onOpenCredits,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("open_credits_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Datakreditter & Licenser",
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Phase 7b: "Nær mig" button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (showNearbySheet) ForestGreenPrimary else MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, ForestGreenPrimary),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { triggerNearbySearch() }
                            .testTag("near_me_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = if (showNearbySheet) Color.White else ForestGreenPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Nær mig",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (showNearbySheet) Color.White else ForestGreenPrimary
                            )
                        }
                    }

                    // Region Chips
                    regions.forEach { region ->
                        val isSelected = (selectedRegion ?: "Alle") == region
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.setSelectedRegion(if (region == "Alle") null else region)
                            },
                            label = { Text(region, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("filter_region_${region.lowercase()}")
                        )
                    }

                    // Length Filter Chips
                    LengthFilter.values().filter { it != LengthFilter.ALL }.forEach { len ->
                        val isSelected = lengthFilter == len
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.setLengthFilter(if (isSelected) LengthFilter.ALL else len)
                            },
                            label = { Text(len.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    // Overnight Filter Chips
                    OvernightFilter.values().filter { it != OvernightFilter.ALL }.forEach { ov ->
                        val isSelected = overnightFilter == ov
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.setOvernightFilter(if (isSelected) OvernightFilter.ALL else ov)
                            },
                            label = { Text(ov.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            // Floating Map Controls: Center Denmark & Layers
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Phase 13: Layers Button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            viewModel.setShowLayersSheet(true)
                        }
                        .testTag("open_layers_sheet_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Kortlag",
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            mapViewRef?.controller?.animateTo(GeoPoint(55.6, 10.8), 8.0, 600L)
                        }
                        .testTag("center_denmark_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Centrer Danmark",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Phase 13 Requirement E: Dynamic Map Attribution in Corner
            MapAttributionPill(
                mapLayersState = layersState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 156.dp)
                    .testTag("map_corner_attribution")
            )
        }
    }

    // Phase 7b: Overnatning nær mig Bottom Sheet
    if (showNearbySheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowNearbySheet(false) },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Overnatning nær mig",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Shelters & fri teltning inden for 10 km",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setShowNearbySheet(false) },
                        modifier = Modifier.size(32.dp).testTag("close_nearby_sheet_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Luk")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearchingNearby) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ForestGreenPrimary)
                    }
                } else if (nearbySpots.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Cabin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Ingen overnatningssteder inden for 10 km",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Der er ingen registrerede shelters eller fri teltningszoner i nærområdet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("nearby_spots_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(nearbySpots, key = { it.id }) { spot ->
                            NearbyOvernightSpotCard(
                                spot = spot,
                                onOpenDirections = {
                                    val uri = Uri.parse("geo:0,0?q=${spot.lat},${spot.lng}(${Uri.encode(spot.name)})")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${spot.lat},${spot.lng}")))
                                    }
                                },
                                onRouteHerTil = {
                                    viewModel.setShowNearbySheet(false)
                                    onOpenRouteBuilder(spot.lat, spot.lng, spot.name)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Phase 13 Requirement D: Map Layers Bottom Sheet
    if (showLayersSheet) {
        MapLayersBottomSheet(
            layersState = layersState,
            onBaseMapChanged = { viewModel.setBaseMap(it) },
            onToggleMarkedRoutes = { viewModel.setOverlayMarkedRoutes(it) },
            onToggleAccess = { viewModel.setOverlayAccess(it) },
            onToggleShelters = { viewModel.setOverlayShelters(it) },
            onToggleFriTeltning = { viewModel.setOverlayFriTeltning(it) },
            onToggleWater = { viewModel.setOverlayWater(it) },
            onToggleToilets = { viewModel.setOverlayToilets(it) },
            onToggleShops = { viewModel.setOverlayShops(it) },
            onToggleTransport = { viewModel.setOverlayTransport(it) },
            onToggleMyPoints = { viewModel.setOverlayMyPoints(it) },
            onDismiss = { viewModel.setShowLayersSheet(false) }
        )
    }

    // Phase 13 Requirement B: Marked Route Detail Sheet
    selectedMarkedRoute?.let { markedRoute ->
        MarkedRouteDetailSheet(
            route = markedRoute,
            onSaveAsTrail = { r -> viewModel.saveMarkedRouteAsTrail(r) },
            onOpenTrailDetails = { trailId ->
                viewModel.selectMarkedRoute(null)
                onTrailClick(trailId)
            },
            onDismiss = { viewModel.selectMarkedRoute(null) }
        )
    }
}

@Composable
fun TrailCard(
    trail: TrailEntity,
    points: List<GeoPointDto>?,
    sheltersCount: Int,
    friTeltningCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalKm = trail.computedWalkingKm
    val estimatedDays = max(1, (totalKm / 15.0).toInt())
    val ascentMeters = remember(points) {
        if (points.isNullOrEmpty()) 0 else {
            var gain = 0.0
            for (i in 0 until points.size - 1) {
                val e1 = points[i].ele
                val e2 = points[i + 1].ele
                if (e1 != null && e2 != null) {
                    val diff = e2 - e1
                    if (diff > 0) gain += diff
                }
            }
            gain.toInt()
        }
    }
    val isCircuit = remember(points) {
        if (points.isNullOrEmpty()) false else {
            val start = points.first()
            val end = points.last()
            GeoUtils.distanceMeters(start.lat, start.lng, end.lat, end.lng) < 800.0
        }
    }
    val routeTypeLabel = if (isCircuit) "Rundtur" else "Strækning"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("trail_card_${trail.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Map thumbnail of the route (no stock photos!)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                RouteThumbnailCanvas(
                    points = points,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Trail Details Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = trail.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = routeTypeLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "${trail.region} • ${trail.parentRegion}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Stats: Length, Ascent, Estimated Days at 15 km/day
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${String.format(Locale.US, "%.1f", totalKm)} km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForestGreenPrimary
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "+${ascentMeters}m",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$estimatedDays ${if (estimatedDays == 1) "dag" else "dage"}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Phase 2 Counts: "23 shelters · 6 fri teltning"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cabin,
                        contentDescription = null,
                        tint = ForestGreenLight,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$sheltersCount shelters",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "·",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Default.Forest,
                        contentDescription = null,
                        tint = ForestGreenLight,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$friTeltningCount fri teltning",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Vis detaljer",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * Clean route thumbnail canvas: normalizes points to bounds and draws the exact polyline.
 * No stock photos used!
 */
@Composable
fun RouteThumbnailCanvas(
    points: List<GeoPointDto>?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.padding(10.dp)) {
        if (points.isNullOrEmpty() || points.size < 2) {
            // Minimal placeholder curve
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.85f)
                cubicTo(
                    size.width * 0.3f, size.height * 0.2f,
                    size.width * 0.7f, size.height * 0.9f,
                    size.width * 0.85f, size.height * 0.15f
                )
            }
            drawPath(
                path = path,
                color = ForestGreenLight.copy(alpha = 0.5f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            return@Canvas
        }

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val dLat = max(1e-5, maxLat - minLat)
        val dLng = max(1e-5, maxLng - minLng)

        // Subsample for fast rendering
        val step = max(1, points.size / 60)
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }

        val path = Path()
        sampled.forEachIndexed { i, pt ->
            val x = ((pt.lng - minLng) / dLng * size.width).toFloat().coerceIn(0f, size.width)
            val y = ((1.0 - (pt.lat - minLat) / dLat) * size.height).toFloat().coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = ForestGreenPrimary,
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw start dot (green) and end dot (amber)
        val startPt = sampled.first()
        val startX = ((startPt.lng - minLng) / dLng * size.width).toFloat().coerceIn(0f, size.width)
        val startY = ((1.0 - (startPt.lat - minLat) / dLat) * size.height).toFloat().coerceIn(0f, size.height)
        drawCircle(color = ForestGreenLight, radius = 4.dp.toPx(), center = Offset(startX, startY))

        val endPt = sampled.last()
        val endX = ((endPt.lng - minLng) / dLng * size.width).toFloat().coerceIn(0f, size.width)
        val endY = ((1.0 - (endPt.lat - minLat) / dLat) * size.height).toFloat().coerceIn(0f, size.height)
        drawCircle(color = AmberAccent, radius = 4.dp.toPx(), center = Offset(endX, endY))
    }
}

@Composable
fun NearbyOvernightSpotCard(
    spot: com.example.data.model.NearbyOvernightSpot,
    onOpenDirections: () -> Unit,
    onRouteHerTil: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isShelter = spot.type == "SHELTER"
    val icon = if (isShelter) Icons.Default.Cabin else Icons.Default.Forest
    val iconTint = if (isShelter) ForestGreenPrimary else AmberAccent
    val distanceText = if (spot.distanceMeters >= 1000.0) {
        "${String.format(Locale.US, "%.1f", spot.distanceMeters / 1000.0)} km væk"
    } else {
        "${spot.distanceMeters.toInt()} m væk"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth().testTag("nearby_spot_card_${spot.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = iconTint.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Column {
                        Text(
                            text = spot.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isShelter) "Shelterplads" else "Fri teltning",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ForestGreenPrimary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = distanceText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Description if available
            spot.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Booking requirement (Phase 7c)
            if (spot.bookingRequired) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AmberAccent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Kræver booking",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberAccent
                            )
                            Text(
                                text = "Tjek ledighed hos udbyder",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val targetUrl = if (!spot.bookingUrl.isNullOrBlank()) {
                            spot.bookingUrl
                        } else {
                            "https://udinaturen.dk/soeg?q=${Uri.encode(spot.name)}"
                        }
                        val buttonLabel = if (!spot.bookingUrl.isNullOrBlank()) "Åbn booking" else "Book via udinaturen.dk"

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("NearbySpot", "Cannot open booking URL: ${e.message}")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp).testTag("nearby_booking_button_${spot.id}")
                        ) {
                            Text(text = buttonLabel, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Action row: Directions in user's map app + Rute hertil
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRouteHerTil,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp).testTag("route_here_button_${spot.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Rute hertil", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onOpenDirections,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp).testTag("directions_button_${spot.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Rutevejledning", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

