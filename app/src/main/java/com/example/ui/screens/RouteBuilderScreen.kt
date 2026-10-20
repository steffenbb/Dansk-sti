package com.example.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.example.data.model.NearestPoiCategory
import com.example.data.model.RoadSectionWarning
import com.example.data.model.RundturCandidate
import com.example.data.model.SupplyGapType
import com.example.data.model.SupplyGapWarning
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaypointWeatherEta
import java.io.File
import java.io.FileOutputStream
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.data.local.BaseMapType
import com.example.data.local.MapLayersState
import com.example.data.local.SettingsRepository
import com.example.data.model.MarkedRouteDto
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.util.DanskStiTileSourceHelper
import com.example.util.MapAttributionPill
import com.example.ui.components.RouteElevationProfileChart
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenDark
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.viewmodel.RouteAlternativeInfo
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.util.DanskStiTileSource
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderScreen(
    routeId: Long? = null,
    destLat: Double? = null,
    destLng: Double? = null,
    destName: String? = null,
    viewModel: RouteBuilderViewModel,
    onBack: () -> Unit,
    onSavedSuccessfully: () -> Unit,
    onPlanStages: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val settingsRepository = remember { SettingsRepository(context) }
    val layersState by settingsRepository.mapLayersFlow.collectAsState(initial = MapLayersState())
    var selectedMarkedRoute by remember { mutableStateOf<MarkedRouteDto?>(null) }
    var markedRoutesInView by remember { mutableStateOf<List<MarkedRouteDto>>(emptyList()) }

    var showClearDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameRouteName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var selectedWaypointForSheet by remember { mutableStateOf<RouteWaypoint?>(null) }
    var renameWaypointText by remember { mutableStateOf("") }
    var isSheetExpanded by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }

    // MapView reference for overlay updates and gestures
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    // GPX file picker
    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    viewModel.importGpx(stream)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Fejl ved åbning af fil: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var customWpName by remember { mutableStateOf("") }
    var customWpNote by remember { mutableStateOf("") }
    var customWpPhotoPath by remember { mutableStateOf<String?>(null) }

    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            try {
                val photoFile = File(context.filesDir, "wp_photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(photoFile).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                customWpPhotoPath = photoFile.absolutePath
            } catch (e: Exception) {
                Toast.makeText(context, "Kunne ikke gemme foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val galleryPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val photoFile = File(context.filesDir, "wp_photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(photoFile).use { out ->
                        input.copyTo(out)
                    }
                    customWpPhotoPath = photoFile.absolutePath
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Kunne ikke indlæse foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Initialize ViewModel once
    LaunchedEffect(routeId, destLat, destLng) {
        viewModel.initialize(routeId, destLat, destLng, destName)
    }

    // Handle toast messages
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Update map overlays whenever state changes
    LaunchedEffect(
        uiState.waypoints,
        uiState.legs,
        uiState.loadingLegKeys,
        uiState.alternatives,
        uiState.gpxOriginalPoints,
        uiState.scrubbedPointOnMap,
        uiState.accessPolygons,
        uiState.isAccessLayerVisible,
        uiState.customUserWaypoints,
        layersState,
        markedRoutesInView,
        mapViewInstance
    ) {
        val map = mapViewInstance ?: return@LaunchedEffect
        refreshMapOverlays(
            mapView = map,
            context = context,
            state = uiState,
            viewModel = viewModel,
            layersState = layersState,
            markedRoutes = markedRoutesInView,
            onWaypointClicked = { wp ->
                selectedWaypointForSheet = wp
                renameWaypointText = wp.name ?: ""
            },
            onAccessPolygonClicked = { poly ->
                viewModel.selectAccessPolygon(poly)
            },
            onMarkedRouteClicked = { route ->
                selectedMarkedRoute = route
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable {
                                renameRouteName = uiState.routeName.ifBlank { "Min rute" }
                                showRenameDialog = true
                            }
                            .testTag("route_title_header")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = uiState.routeName.ifBlank { "Planlæg rute" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Edit, contentDescription = "Rediger navn", modifier = Modifier.size(14.dp))
                        }
                        Text(
                            text = if (uiState.waypoints.isEmpty()) "Tryk på kortet for at tilføje punkter" else "${uiState.waypoints.size} punkter",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_back_route_builder")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbage")
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = uiState.canUndo,
                        modifier = Modifier.testTag("btn_undo")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Fortryd",
                            tint = if (uiState.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Redo
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = uiState.canRedo,
                        modifier = Modifier.testTag("btn_redo")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Annuller fortryd",
                            tint = if (uiState.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Reverse route
                    IconButton(
                        onClick = { viewModel.reverseRoute() },
                        enabled = uiState.waypoints.size >= 2,
                        modifier = Modifier.testTag("btn_reverse_route")
                    ) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Vend rute")
                    }

                    // Clear route
                    IconButton(
                        onClick = { showClearDialog = true },
                        enabled = uiState.waypoints.isNotEmpty(),
                        modifier = Modifier.testTag("btn_clear_route")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Ryd")
                    }

                    // Toggle Access Layer & Open Layers Sheet (Phase 10 Requirement B)
                    IconButton(
                        onClick = {
                            viewModel.toggleAccessLayer()
                            showLayersSheet = true
                        },
                        modifier = Modifier.testTag("btn_toggle_access_layer")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Adgangslag",
                            tint = if (uiState.isAccessLayerVisible) ForestGreenPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Import GPX
                    IconButton(
                        onClick = { gpxPickerLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                        modifier = Modifier.testTag("btn_import_gpx")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Importer GPX")
                    }

                    // Share route (Phase 12 Feature 7)
                    IconButton(
                        onClick = { viewModel.shareRoute(context) },
                        enabled = uiState.waypoints.size >= 2,
                        modifier = Modifier.testTag("btn_share_route")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Del rute")
                    }

                    // Save route
                    Button(
                        onClick = {
                            renameRouteName = uiState.routeName.ifBlank {
                                val s = uiState.waypoints.firstOrNull()?.name ?: "Start"
                                val e = uiState.waypoints.lastOrNull()?.name ?: "Slut"
                                "$s - $e"
                            }
                            showSaveDialog = true
                        },
                        enabled = uiState.waypoints.size >= 2 && !uiState.isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("btn_save_route")
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Gem", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.testTag("route_builder_top_bar")
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // MapView
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("route_builder_map"),
                factory = { ctx ->
                    MapView(ctx).apply {
                        DanskStiTileSourceHelper.applyTo(this, layersState.baseMap, isDark)
                        setMultiTouchControls(true)

                        // Center on Denmark by default (56.0, 11.0)
                        controller.setZoom(10.0)
                        controller.setCenter(GeoPoint(56.15, 11.5))

                        // Events overlay for map taps and long press
                        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                viewModel.addPointAtEnd(p.latitude, p.longitude)
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint): Boolean {
                                viewModel.insertViaPointOnLine(p.latitude, p.longitude)
                                return true
                            }
                        })
                        overlays.add(eventsOverlay)

                        val app = ctx.applicationContext as com.example.DanskStiApp
                        val fetchRoutesInView: () -> Unit = {
                            if (layersState.showMarkedRoutes && zoomLevelDouble >= 10.0) {
                                val bbox = boundingBox
                                if (bbox != null) {
                                    coroutineScope.launch {
                                        try {
                                            val r = app.repository.getMarkedRoutes(
                                                bbox.latSouth, bbox.latNorth, bbox.lonWest, bbox.lonEast, zoomLevelDouble
                                            )
                                            markedRoutesInView = r
                                        } catch (_: Exception) {}
                                    }
                                }
                            } else if (markedRoutesInView.isNotEmpty()) {
                                markedRoutesInView = emptyList()
                            }
                        }

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                fetchRoutesInView()
                                return false
                            }
                            override fun onZoom(event: ZoomEvent?): Boolean {
                                fetchRoutesInView()
                                return false
                            }
                        })

                        mapViewInstance = this
                    }
                },
                update = { map ->
                    DanskStiTileSourceHelper.applyTo(map, layersState.baseMap, isDark)
                }
            )

            // Phase 13 Requirement E: Attribution in bottom-start corner
            MapAttributionPill(
                mapLayersState = layersState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = if (isSheetExpanded) 220.dp else 110.dp, start = 8.dp)
                    .testTag("route_builder_map_attribution")
            )

            // Floating Controls: "Fra min position" & Zoom to route
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // "Fra min position" button
                FloatingActionButton(
                    onClick = {
                        val loc = getLastKnownLocation(context)
                        if (loc != null) {
                            viewModel.setStartAtGps(loc.latitude, loc.longitude)
                            mapViewInstance?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                        } else {
                            Toast.makeText(context, "GPS-position ikke tilgængelig", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = ForestGreenPrimary,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("btn_my_location")
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Fra min position", modifier = Modifier.size(20.dp))
                }

                // Zoom to bounds button
                if (uiState.waypoints.size >= 2) {
                    FloatingActionButton(
                        onClick = {
                            val map = mapViewInstance ?: return@FloatingActionButton
                            zoomToRoute(map, uiState.waypoints)
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("btn_recenter")
                    ) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = "Zoom til rute", modifier = Modifier.size(20.dp))
                    }
                }

                // "Mit punkt" (Phase 12 Feature 6: Mine punkter)
                FloatingActionButton(
                    onClick = {
                        val center = mapViewInstance?.mapCenter
                        val lat = center?.latitude ?: 56.15
                        val lng = center?.longitude ?: 11.5
                        viewModel.openAddCustomWaypointDialog(lat, lng)
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Color(0xFF7B1FA2),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("btn_add_custom_waypoint")
                ) {
                    Icon(Icons.Default.Place, contentDescription = "Tilføj mit punkt", modifier = Modifier.size(20.dp))
                }
            }

            // Bottom Sheet (Requirement D)
            // Collapsed: single clean line "23,4 km · +180 m · ca. 6 t 10 min · 81 % sti"
            // Expanded: detailed elevation profile, stats, options, alternatives
            Card(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .testTag("route_bottom_sheet")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Header row: Collapsed stats summary & toggle expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSheetExpanded = !isSheetExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Single line summary (Requirement D)
                            Text(
                                text = String.format(
                                    Locale.GERMANY,
                                    "%.1f km · +%.0f m · %s · %.0f %% sti",
                                    uiState.stats.totalKm,
                                    uiState.stats.ascentM,
                                    uiState.timeEstimateFormatted,
                                    uiState.stats.pathSharePct ?: 0.0
                                ),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("route_summary_text")
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 3.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (uiState.isOfflineRoute) ForestGreenPrimary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.testTag("route_engine_mode_badge")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.isOfflineRoute) Icons.Default.CloudOff else Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = if (uiState.isOfflineRoute) ForestGreenPrimary else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = uiState.routeEngineModeLabel,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (uiState.isOfflineRoute) ForestGreenPrimary else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("route_engine_mode_label")
                                        )
                                    }
                                }

                                if (uiState.loadingLegKeys.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(12.dp),
                                        color = ForestGreenPrimary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Beregner rute...",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { isSheetExpanded = !isSheetExpanded },
                            modifier = Modifier.testTag("btn_toggle_sheet")
                        ) {
                            Icon(
                                imageVector = if (isSheetExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (isSheetExpanded) "Skjul detaljer" else "Vis detaljer"
                            )
                        }
                    }

                    // Quick Action Buttons: "Find overnatning", "Rundtur", "Nærmeste", "Planlæg dage", "Del rute"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.findOvernightCandidates() },
                                enabled = uiState.waypoints.size >= 2 && !uiState.isFindingOvernight,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_find_overnight")
                            ) {
                                if (uiState.isFindingOvernight) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Søger...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Find overnatning", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = { viewModel.openRundturDialog() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_open_rundtur")
                            ) {
                                Icon(Icons.Default.Loop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rundtur", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.openNearestPoiDialog() },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_open_nearest_poi")
                            ) {
                                Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Nærmeste", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.planStages { trailRouteId ->
                                        onPlanStages(trailRouteId)
                                    }
                                },
                                enabled = uiState.waypoints.size >= 2,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("btn_plan_days")
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Planlæg dage", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.shareRoute(context) },
                                enabled = uiState.waypoints.size >= 2,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("btn_share_route_sheet")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Del", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Phase 12 Feature 1: Vand & Mad Supply Gap Warnings Banner
                    if (uiState.supplyGapWarnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.supplyGapWarnings.forEach { warning ->
                                SupplyGapWarningCard(
                                    warning = warning,
                                    onZoomToDetour = {
                                        if (warning.detourLat != null && warning.detourLng != null) {
                                            mapViewInstance?.controller?.animateTo(
                                                GeoPoint(warning.detourLat, warning.detourLng),
                                                15.0,
                                                500L
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Phase 10: Access Warnings Banner
                    if (uiState.accessWarnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.accessWarnings.forEach { warning ->
                                AccessWarningCard(
                                    warning = warning,
                                    onZoomTo = {
                                        mapViewInstance?.controller?.animateTo(
                                            GeoPoint(warning.location.lat, warning.location.lng),
                                            15.0,
                                            500L
                                        )
                                    },
                                    onRerouteAround = {
                                        viewModel.rerouteAroundPolygon(warning)
                                    }
                                )
                            }
                        }
                    }

                    // Expanded Section
                    AnimatedVisibility(visible = isSheetExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 1. Elevation Profile Chart
                            val allPoints = remember(uiState.legs, uiState.waypoints) {
                                val ordered = mutableListOf<com.example.util.routing.LegGeometryData>()
                                for (i in 0 until uiState.waypoints.size - 1) {
                                    val key = "${uiState.waypoints[i].id}_to_${uiState.waypoints[i + 1].id}"
                                    uiState.legs[key]?.let { ordered.add(it) }
                                }
                                com.example.util.routing.RouteGeometryStorageManager.getCombinedPoints(ordered)
                            }

                            Text(
                                text = "Højdekurve (farvet efter stigning)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            RouteElevationProfileChart(
                                points = allPoints,
                                onPointScrubbed = { pt, _ ->
                                    viewModel.setScrubbedPoint(pt)
                                }
                            )

                            // 2. Rutekvalitet & Underlag (Phase 12 Feature 5)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Rutekvalitet & Underlag",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                val q = uiState.routeQuality
                                val effTotal = if (q.totalKm > 0.0) q.totalKm else 1.0

                                // 6-part visual bar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                ) {
                                    if (q.trailKm > 0.0) Box(modifier = Modifier.weight((q.trailKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFF2E7D32)))
                                    if (q.trackKm > 0.0) Box(modifier = Modifier.weight((q.trackKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFF689F38)))
                                    if (q.roadWithSidewalkKm > 0.0) Box(modifier = Modifier.weight((q.roadWithSidewalkKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFF546E7A)))
                                    if (q.roadNoSidewalkKm > 0.0) Box(modifier = Modifier.weight((q.roadNoSidewalkKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFFE65100)))
                                    if (q.asphaltKm > 0.0) Box(modifier = Modifier.weight((q.asphaltKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFF757575)))
                                    if (q.ferryKm > 0.0) Box(modifier = Modifier.weight((q.ferryKm / effTotal).toFloat()).fillMaxSize().background(Color(0xFF0288D1)))
                                    if (q.totalKm == 0.0) Box(modifier = Modifier.weight(1f).fillMaxSize().background(Color.LightGray))
                                }

                                // 6 categories breakdown grid (2 columns)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        QualityRowItem(color = Color(0xFF2E7D32), label = "Sti", km = q.trailKm, pct = q.trailPct)
                                        QualityRowItem(color = Color(0xFF689F38), label = "Skov-/markvej", km = q.trackKm, pct = q.trackPct)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        QualityRowItem(color = Color(0xFF546E7A), label = "Vej m. fortov", km = q.roadWithSidewalkKm, pct = q.roadWithSidewalkPct)
                                        QualityRowItem(color = Color(0xFFE65100), label = "Vej u. fortov", km = q.roadNoSidewalkKm, pct = q.roadNoSidewalkPct)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        QualityRowItem(color = Color(0xFF757575), label = "Asfalt (i alt)", km = q.asphaltKm, pct = q.asphaltPct)
                                        QualityRowItem(color = Color(0xFF0288D1), label = "Færge / vand", km = q.ferryKm, pct = q.ferryPct)
                                    }
                                }

                                // Flag sections over 1 km on a road without a footway
                                if (q.roadWithoutFootwayWarnings.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠️ Særlig opmærksomhed: Strækning over 1 km på vej uden fortov",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100)
                                    )
                                    q.roadWithoutFootwayWarnings.forEach { rw ->
                                        RoadSectionWarningCard(
                                            warning = rw,
                                            onZoomTo = {
                                                mapViewInstance?.controller?.animateTo(
                                                    GeoPoint(rw.startLat, rw.startLng),
                                                    15.0,
                                                    500L
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // 2b. Vand & Mad forsyning indstillinger (Phase 12 Feature 1)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Vand & Mad forsyning",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${uiState.supplyGapWarnings.size} advarsler",
                                        fontSize = 11.sp,
                                        color = if (uiState.supplyGapWarnings.isEmpty()) ForestGreenPrimary else Color(0xFFE65100),
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Advarsel for vand-gab over", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Text("${uiState.maxWaterGapKm.toInt()} km", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
                                    }
                                    Slider(
                                        value = uiState.maxWaterGapKm.toFloat(),
                                        onValueChange = { viewModel.setMaxWaterGapKm(it.toDouble()) },
                                        valueRange = 5f..30f,
                                        steps = 25,
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFF1E88E5), activeTrackColor = Color(0xFF1E88E5)),
                                        modifier = Modifier.testTag("slider_max_water_gap")
                                    )
                                }

                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Advarsel for mad/butik-gab over", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Text("${uiState.maxShopGapKm.toInt()} km", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ForestGreenPrimary)
                                    }
                                    Slider(
                                        value = uiState.maxShopGapKm.toFloat(),
                                        onValueChange = { viewModel.setMaxShopGapKm(it.toDouble()) },
                                        valueRange = 10f..50f,
                                        steps = 40,
                                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                                        modifier = Modifier.testTag("slider_max_shop_gap")
                                    )
                                }

                                if (uiState.supplyGapWarnings.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = ForestGreenPrimary.copy(alpha = 0.1f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Ingen forsyningshuller over de valgte grænseværdier fundet på ruten.",
                                            fontSize = 12.sp,
                                            color = ForestGreenPrimary,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }

                            // 2c. Vejr & Solnedgang ved ETA (Phase 12 Feature 3)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Vejr & Solnedgang (ved ETA)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    OutlinedButton(
                                        onClick = { viewModel.fetchWeatherAndSunset() },
                                        enabled = uiState.waypoints.size >= 2 && !uiState.isLoadingWeather,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("btn_fetch_weather_eta")
                                    ) {
                                        if (uiState.isLoadingWeather) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Henter...", fontSize = 11.sp)
                                        } else {
                                            Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Hent vejr", fontSize = 11.sp)
                                        }
                                    }
                                }

                                if (uiState.waypointWeatherEtas.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        uiState.waypointWeatherEtas.forEach { wEta ->
                                            WaypointWeatherEtaCard(weather = wEta)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Tryk 'Hent vejr' for at hente Open-Meteo vejrudsigt og solnedgang ved estimeret ankomsttid (ETA) for overnatningspunkter og mål.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 2d. Mine punkter på ruten (Phase 12 Feature 6)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Mine punkter (${uiState.customUserWaypoints.size})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = {
                                            val c = mapViewInstance?.mapCenter
                                            viewModel.openAddCustomWaypointDialog(c?.latitude ?: 56.15, c?.longitude ?: 11.5)
                                        },
                                        modifier = Modifier.testTag("btn_sheet_add_custom_wp")
                                    ) {
                                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF7B1FA2))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Tilføj punkt", fontSize = 11.sp, color = Color(0xFF7B1FA2), fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (uiState.customUserWaypoints.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        uiState.customUserWaypoints.forEach { wp ->
                                            CustomWaypointRow(
                                                waypoint = wp,
                                                onClick = { viewModel.selectCustomWaypointForDetails(wp) },
                                                onDelete = { viewModel.deleteCustomWaypoint(wp.id) }
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Ingen egne punkter endnu. Tilføj punkter med foto og note fra kamera eller galleri.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 3. Routing Options
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Ruteindstillinger",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // "Undgå veje" slider (0.0 to 1.0)
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Undgå veje", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Text("${(uiState.options.avoidRoads * 100).toInt()} %", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ForestGreenPrimary)
                                    }
                                    Slider(
                                        value = uiState.options.avoidRoads.toFloat(),
                                        onValueChange = { newVal ->
                                            viewModel.updateOptions(uiState.options.copy(avoidRoads = newVal.toDouble()))
                                        },
                                        colors = SliderDefaults.colors(
                                            thumbColor = ForestGreenPrimary,
                                            activeTrackColor = ForestGreenPrimary
                                        ),
                                        modifier = Modifier.testTag("slider_avoid_roads")
                                    )
                                }

                                // "Foretræk natur" switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Foretræk natur (skove & rolige stier)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Switch(
                                        checked = uiState.options.preferNature,
                                        onCheckedChange = { checked ->
                                            viewModel.updateOptions(uiState.options.copy(preferNature = checked))
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ForestGreenPrimary),
                                        modifier = Modifier.testTag("switch_prefer_nature")
                                    )
                                }

                                // "Tillad færger" switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Tillad færger & vandoverfarter", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Switch(
                                        checked = uiState.options.allowFerries,
                                        onCheckedChange = { checked ->
                                            viewModel.updateOptions(uiState.options.copy(allowFerries = checked))
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ForestGreenPrimary),
                                        modifier = Modifier.testTag("switch_allow_ferries")
                                    )
                                }
                            }

                            // 4. "Sammenlign ruter" (Alternatives 1-3)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Sammenlign ruter",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    OutlinedButton(
                                        onClick = { viewModel.fetchAlternatives() },
                                        enabled = uiState.waypoints.size >= 2 && !uiState.isComparingAlternatives,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("btn_fetch_alternatives")
                                    ) {
                                        if (uiState.isComparingAlternatives) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Søger...", fontSize = 11.sp)
                                        } else {
                                            Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Find alternativer", fontSize = 11.sp)
                                        }
                                    }
                                }

                                if (uiState.alternatives.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        uiState.alternatives.forEach { alt ->
                                            AlternativeRouteCard(
                                                alt = alt,
                                                onSelect = { viewModel.selectAlternative(alt) }
                                            )
                                        }
                                    }
                                }
                            }

                            // 5. Waypoints list with km
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Rutens punkter (${uiState.waypoints.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                uiState.waypoints.forEachIndexed { idx, wp ->
                                    WaypointRow(
                                        index = idx,
                                        waypoint = wp,
                                        onClick = {
                                            selectedWaypointForSheet = wp
                                            renameWaypointText = wp.name ?: ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog: Clear route confirmation
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Ryd rute") },
            text = { Text("Er du sikker på, at du vil slette alle punkter på ruten?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearRoute()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Ryd")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Dialog: Save route
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Gem rute") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Angiv et navn til din rute:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = renameRouteName,
                        onValueChange = { renameRouteName = it },
                        singleLine = true,
                        placeholder = { Text("f.eks. Rold Skov Rundtur") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_save_route_name")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateRouteName(renameRouteName)
                        viewModel.saveRoute {
                            showSaveDialog = false
                            onSavedSuccessfully()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier.testTag("btn_confirm_save_route")
                ) {
                    Text("Gem til Gemte")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Dialog: Rename Route
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rutenavn") },
            text = {
                OutlinedTextField(
                    value = renameRouteName,
                    onValueChange = { renameRouteName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateRouteName(renameRouteName)
                        showRenameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                ) {
                    Text("Gem")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Dialog / Small sheet: Waypoint details (Requirement C)
    // Slet, Gør til overnatning / Gør til via, Lige linje herfra / Følg stier herfra, Navngiv
    selectedWaypointForSheet?.let { wp ->
        AlertDialog(
            onDismissRequest = { selectedWaypointForSheet = null },
            title = {
                Text(
                    text = when (wp.role) {
                        WaypointRole.START -> "Startpunkt"
                        WaypointRole.END -> "Slutpunkt"
                        WaypointRole.OVERNIGHT -> "Overnatningspunkt"
                        WaypointRole.VIA -> "Viapunkt"
                    } + (if (!wp.name.isNullOrBlank()) " (${wp.name})" else "")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Rename field
                    OutlinedTextField(
                        value = renameWaypointText,
                        onValueChange = { renameWaypointText = it },
                        label = { Text("Navn på punkt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_waypoint_name")
                    )

                    // Role: Gør til overnatning / Gør til via
                    OutlinedButton(
                        onClick = {
                            viewModel.toggleWaypointOvernight(wp.id)
                            selectedWaypointForSheet = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (wp.role == WaypointRole.OVERNIGHT) Icons.Default.DirectionsWalk else Icons.Default.NightShelter,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (wp.role == WaypointRole.OVERNIGHT) "Gør til almindeligt viapunkt" else "Gør til overnatning")
                    }

                    // Leg mode to next: Lige linje herfra / Følg stier herfra
                    if (wp.role != WaypointRole.END) {
                        OutlinedButton(
                            onClick = {
                                viewModel.toggleLegMode(wp.id)
                                selectedWaypointForSheet = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (wp.legModeToNext == LegMode.SNAP) Icons.Default.Straighten else Icons.Default.Route,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (wp.legModeToNext == LegMode.SNAP) "Lige linje herfra" else "Følg stier herfra")
                        }
                    }

                    // Slet punkt
                    Button(
                        onClick = {
                            viewModel.deleteWaypoint(wp.id)
                            selectedWaypointForSheet = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("btn_delete_waypoint")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Slet punkt")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameWaypointText != (wp.name ?: "")) {
                            viewModel.renameWaypoint(wp.id, renameWaypointText)
                        }
                        selectedWaypointForSheet = null
                    }
                ) {
                    Text("Luk")
                }
            }
        )
    }

    // Dialog: Access Area Rules & Details (Phase 10)
    uiState.selectedAccessPolygonForDetails?.let { poly ->
        AlertDialog(
            onDismissRequest = { viewModel.selectAccessPolygon(null) },
            icon = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(poly.type.fillColorArgb), CircleShape)
                        .border(2.dp, Color(poly.type.strokeColorArgb), CircleShape)
                )
            },
            title = {
                Text(
                    text = poly.name ?: poly.type.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Kilde: ${poly.source}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Text(
                        text = poly.type.fullRulesDanish,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.selectAccessPolygon(null) }) {
                    Text("Luk")
                }
            }
        )
    }

    // Phase 13 Requirement D: Map Layers Bottom Sheet
    if (showLayersSheet) {
        MapLayersBottomSheet(
            layersState = layersState,
            onBaseMapChanged = { coroutineScope.launch { settingsRepository.setBaseMap(it) } },
            onToggleMarkedRoutes = { coroutineScope.launch { settingsRepository.setOverlayMarkedRoutes(it) } },
            onToggleAccess = { 
                coroutineScope.launch { settingsRepository.setOverlayAccess(it) }
                if (uiState.isAccessLayerVisible != it) {
                    viewModel.toggleAccessLayer()
                }
            },
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

    // Phase 13 Requirement B: Marked Route Detail Sheet
    selectedMarkedRoute?.let { markedRoute ->
        val app = context.applicationContext as com.example.DanskStiApp
        MarkedRouteDetailSheet(
            route = markedRoute,
            onSaveAsTrail = { r -> app.repository.saveMarkedRouteAsTrail(r) },
            onOpenTrailDetails = {
                selectedMarkedRoute = null
            },
            onDismiss = { selectedMarkedRoute = null }
        )
    }


    // Phase 11 Requirement C: Overnight Candidate Picker Dialog
    if (uiState.showOvernightDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOvernightDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, tint = ForestGreenPrimary)
                    Text("Vælg overnatningssted", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                if (uiState.overnightCandidatesByDay.isEmpty()) {
                    Text("Ingen overnatningssteder fundet inden for ruten og tilladt omvej.", fontSize = 14.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        uiState.overnightCandidatesByDay.forEach { (day, candidates) ->
                            item {
                                Surface(
                                    color = ForestGreenPrimary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                                ) {
                                    Text(
                                        text = "Dag $day (ca. ${day * 15} km mål)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ForestGreenPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            items(candidates) { cand ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.insertOvernightCandidate(cand) }
                                        .testTag("candidate_${cand.id}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(cand.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            val typeLabel = if (cand.type == "FRI_TELTNING") "Fri teltning" else "Shelter"
                                            Text(
                                                text = "$typeLabel · ${String.format(java.util.Locale.US, "%.1f", cand.walkKm)} km · ${cand.detourMeters.toInt()} m omvej",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                if (cand.hasWaterNearby) {
                                                    Text("💧 Vand", fontSize = 10.sp, color = Color(0xFF1E88E5))
                                                }
                                                if (cand.hasToiletNearby) {
                                                    Text("🚻 Toilet", fontSize = 10.sp, color = ForestGreenPrimary)
                                                }
                                                if (cand.bookingRequired) {
                                                    Text("📅 Booking", fontSize = 10.sp, color = AmberAccent)
                                                } else {
                                                    Text("✓ Fri afbenyttelse", fontSize = 10.sp, color = ForestGreenPrimary)
                                                }
                                            }
                                        }
                                        Button(
                                            onClick = { viewModel.insertOvernightCandidate(cand) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary)
                                        ) {
                                            Text("Vælg", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOvernightDialog() }) {
                    Text("Luk")
                }
            }
        )
    }

    // Dialog: Rundtur generator (Phase 12 Feature 2)
    if (uiState.showRundturDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRundturDialog() },
            icon = {
                Icon(
                    Icons.Default.Loop,
                    contentDescription = null,
                    tint = Color(0xFF1E88E5),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Generér rundtur", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Vælg ønsket rutedistance (5 - 60 km). Appen genererer 3 forslag med forskellige retninger og varieret returvej.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mållængde: ${uiState.rundturTargetKm.toInt()} km",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(10.0, 15.0, 20.0, 30.0).forEach { kmVal ->
                                OutlinedButton(
                                    onClick = { viewModel.setRundturTargetKm(kmVal) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("${kmVal.toInt()}k", fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Slider(
                        value = uiState.rundturTargetKm.toFloat(),
                        onValueChange = { viewModel.setRundturTargetKm(it.toDouble()) },
                        valueRange = 5f..60f,
                        steps = 54,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF1E88E5),
                            activeTrackColor = Color(0xFF1E88E5)
                        ),
                        modifier = Modifier.testTag("slider_rundtur_target_km")
                    )

                    Button(
                        onClick = {
                            val loc = getLastKnownLocation(context)
                            viewModel.generateRundtur(loc?.latitude, loc?.longitude)
                        },
                        enabled = !uiState.isGeneratingRundtur,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_generate_rundtur_action")
                    ) {
                        if (uiState.isGeneratingRundtur) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Beregner 3 rundture...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Beregn 3 rundture", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (uiState.rundturCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Fundne rundture:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        uiState.rundturCandidates.forEach { candidate ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF1E88E5).copy(alpha = 0.4f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth().testTag("candidate_card_${candidate.id}")
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = candidate.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${String.format(Locale.GERMANY, "%.1f", candidate.actualKm)} km",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = ForestGreenPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "${candidate.pathSharePct.toInt()} % sti · +${candidate.ascentM.toInt()} m / -${candidate.descentM.toInt()} m",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${candidate.overnightCount} overnatningsmuligheder (shelter/telt)",
                                        fontSize = 11.sp,
                                        color = if (candidate.overnightCount > 0) ForestGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Button(
                                        onClick = { viewModel.applyRundtur(candidate) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.fillMaxWidth().height(32.dp).testTag("btn_select_rundtur_${candidate.id}")
                                    ) {
                                        Text("Vælg denne rundtur", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRundturDialog() }) {
                    Text("Luk")
                }
            }
        )
    }

    // Dialog: Rute til nærmeste (Phase 12 Feature 4)
    if (uiState.showNearestPoiDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNearestPoiDialog() },
            icon = {
                Icon(
                    Icons.Default.Explore,
                    contentDescription = null,
                    tint = ForestGreenPrimary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Rute til nærmeste", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Vælg kategori. Appen beregner stien til det nærmeste punkt via reel rutedistance fra din GPS-position:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.isRoutingToNearest) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(color = ForestGreenPrimary)
                                Text("Beregner rute til nærmeste...", fontSize = 12.sp)
                            }
                        }
                    } else {
                        NearestPoiCategory.values().forEach { cat ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val loc = getLastKnownLocation(context)
                                        viewModel.routeToNearest(cat, loc?.latitude, loc?.longitude)
                                    }
                                    .testTag("btn_nearest_${cat.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(cat.icon, fontSize = 20.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when (cat) {
                                                NearestPoiCategory.SHELTER -> "Nærmeste shelter / teltplads"
                                                NearestPoiCategory.WATER -> "Nærmeste vandpost"
                                                NearestPoiCategory.TOILET -> "Nærmeste toilet"
                                                NearestPoiCategory.SHOP -> "Nærmeste butik / indkøb"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Beregner hurtigste vandrerute",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Default.Route, contentDescription = null, tint = ForestGreenPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNearestPoiDialog() }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Dialog: Tilføj mit punkt (Phase 12 Feature 6)
    if (uiState.showAddCustomWaypointDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissAddCustomWaypointDialog()
                customWpName = ""
                customWpNote = ""
                customWpPhotoPath = null
            },
            icon = {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = Color(0xFF7B1FA2),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("Nyt punkt", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val loc = uiState.pendingCustomWaypointLocation
                    if (loc != null) {
                        Text(
                            text = "Position: ${String.format(Locale.US, "%.5f, %.5f", loc.lat, loc.lng)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = customWpName,
                        onValueChange = { customWpName = it },
                        label = { Text("Navn på punkt") },
                        placeholder = { Text("f.eks. Flot udsigtspunkt") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_custom_wp_name")
                    )

                    OutlinedTextField(
                        value = customWpNote,
                        onValueChange = { customWpNote = it },
                        label = { Text("Note / beskrivelse") },
                        placeholder = { Text("f.eks. Bænk under egetræet") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_custom_wp_note")
                    )

                    Text(
                        text = "Foto (valgfrit):",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )

                    if (!customWpPhotoPath.isNullOrBlank() && File(customWpPhotoPath!!).exists()) {
                        val bitmap = remember(customWpPhotoPath) {
                            try {
                                BitmapFactory.decodeFile(customWpPhotoPath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Valgt foto",
                                    modifier = Modifier
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                        TextButton(
                            onClick = { customWpPhotoPath = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Fjern foto", fontSize = 11.sp)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { cameraPhotoLauncher.launch(null) },
                                modifier = Modifier.weight(1f).testTag("btn_camera_capture")
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Kamera", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { galleryPhotoLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f).testTag("btn_gallery_pick")
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Galleri", fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCustomWaypoint(customWpName, customWpNote, customWpPhotoPath)
                        customWpName = ""
                        customWpNote = ""
                        customWpPhotoPath = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                    modifier = Modifier.testTag("btn_save_custom_wp")
                ) {
                    Text("Gem punkt")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissAddCustomWaypointDialog()
                        customWpName = ""
                        customWpNote = ""
                        customWpPhotoPath = null
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }

    // Dialog: Detaljer for mit punkt (Phase 12 Feature 6)
    uiState.selectedCustomWaypointForDetails?.let { wp ->
        AlertDialog(
            onDismissRequest = { viewModel.selectCustomWaypointForDetails(null) },
            icon = {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = Color(0xFF7B1FA2),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(wp.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!wp.photoPath.isNullOrBlank() && File(wp.photoPath).exists()) {
                        val bitmap = remember(wp.photoPath) {
                            try {
                                BitmapFactory.decodeFile(wp.photoPath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = wp.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    if (wp.note.isNotBlank()) {
                        Text(
                            text = wp.note,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "Position: ${String.format(Locale.US, "%.5f, %.5f", wp.lat, wp.lng)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                mapViewInstance?.controller?.animateTo(GeoPoint(wp.lat, wp.lng), 16.0, 500L)
                                viewModel.selectCustomWaypointForDetails(null)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Vis på kort", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { viewModel.deleteCustomWaypoint(wp.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Slet", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.selectCustomWaypointForDetails(null) }) {
                    Text("Luk")
                }
            }
        )
    }
}

@Composable
private fun WaypointRow(
    index: Int,
    waypoint: RouteWaypoint,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when (waypoint.role) {
                    WaypointRole.START -> Color(0xFF2E7D32)
                    WaypointRole.END -> Color(0xFFC62828)
                    WaypointRole.OVERNIGHT -> Color(0xFFE65100)
                    WaypointRole.VIA -> Color(0xFF1E88E5)
                },
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when (waypoint.role) {
                            WaypointRole.START -> "A"
                            WaypointRole.END -> "B"
                            WaypointRole.OVERNIGHT -> "⛺"
                            WaypointRole.VIA -> "${index + 1}"
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name ?: when (waypoint.role) {
                        WaypointRole.START -> "Start"
                        WaypointRole.END -> "Slut"
                        WaypointRole.OVERNIGHT -> "Overnatning"
                        WaypointRole.VIA -> "Punkt ${index + 1}"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (waypoint.legModeToNext == LegMode.STRAIGHT) "Lige linje til næste" else "Følger stier",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun AlternativeRouteCard(
    alt: RouteAlternativeInfo,
    onSelect: () -> Unit
) {
    val altColor = Color(RouteMarkerHelper.ALTERNATIVE_COLORS.getOrElse(alt.index) { RouteMarkerHelper.COLOR_SOLID_ROUTE })

    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, altColor.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(altColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Alternativ ${alt.index}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = String.format(Locale.GERMANY, "%.1f km · +%.0f m · %.0f %% sti", alt.lengthKm, alt.ascentM, alt.pathSharePct),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = altColor),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Vælg", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AccessWarningCard(
    warning: com.example.util.access.RouteAccessWarning,
    onZoomTo: () -> Unit,
    onRerouteAround: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AmberAccent.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("access_warning_card_${warning.type.name}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = warning.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onZoomTo,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("btn_warning_zoom_${warning.id}")
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Vis", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (warning.canRerouteAround) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onRerouteAround,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp).testTag("btn_warning_reroute_${warning.id}")
                    ) {
                        Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rut uden om", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplyGapWarningCard(
    warning: SupplyGapWarning,
    onZoomToDetour: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        border = BorderStroke(1.dp, Color(0xFFE65100).copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supply_gap_warning_${warning.type.name}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (warning.type == SupplyGapType.WATER) Icons.Default.WaterDrop else Icons.Default.Place,
                    contentDescription = null,
                    tint = Color(0xFFE65100),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBF360C)
                )
            }

            warning.detourOptionText?.let { detour ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detour,
                    fontSize = 12.sp,
                    color = Color(0xFF5D4037)
                )
            }

            if (warning.detourLat != null && warning.detourLng != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onZoomToDetour,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("btn_zoom_detour_${warning.type.name}")
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFE65100))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Vis omvej på kort", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityRowItem(
    color: Color,
    label: String,
    km: Double,
    pct: Double
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = "$label: ${String.format(Locale.GERMANY, "%.1f", km)} km (${pct.toInt()} %)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RoadSectionWarningCard(
    warning: RoadSectionWarning,
    onZoomTo: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0B2).copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, Color(0xFFE65100).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = warning.displayText,
                    fontSize = 11.sp,
                    color = Color(0xFFBF360C),
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(
                onClick = onZoomTo,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFE65100))
                Spacer(modifier = Modifier.width(2.dp))
                Text("Vis", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WaypointWeatherEtaCard(
    weather: WaypointWeatherEta
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (weather.isAfterSunset) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            1.dp,
            if (weather.isAfterSunset) Color(0xFFE65100).copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weather_eta_${weather.waypointId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (weather.isOvernight) "⛺ " else "🏁 ",
                        fontSize = 14.sp
                    )
                    Text(
                        text = weather.waypointName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (weather.isAfterSunset) Color(0xFFE65100) else ForestGreenPrimary,
                    contentColor = Color.White
                ) {
                    Text(
                        text = "ETA ${weather.etaFormatted}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "km ${String.format(Locale.GERMANY, "%.1f", weather.kmFromStart)} fra start (${weather.etaMinutesFromStart / 60} t ${weather.etaMinutesFromStart % 60} min gang)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Forecast at ETA (honest empty state if weather failed)
            if (weather.tempC != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${weather.weatherIcon ?: "🌤️"} ${weather.tempC.toInt()}°C",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (weather.precipMm != null) {
                        Text(
                            text = "🌧️ ${String.format(Locale.US, "%.1f", weather.precipMm)} mm",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (weather.windMs != null) {
                        Text(
                            text = "💨 ${String.format(Locale.US, "%.1f", weather.windMs)} m/s",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "Vejrudsigt ved ankomst ikke tilgængelig",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!weather.sunsetTime.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "🌅 Solnedgang: ${weather.sunsetTime}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (weather.warningMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFE0B2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = weather.warningMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFBF360C),
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomWaypointRow(
    waypoint: UserCustomWaypointEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!waypoint.photoPath.isNullOrBlank() && File(waypoint.photoPath).exists()) {
                val bitmap = remember(waypoint.photoPath) {
                    try {
                        BitmapFactory.decodeFile(waypoint.photoPath)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = waypoint.name,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color(0xFF7B1FA2),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF7B1FA2),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("★", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (waypoint.note.isNotBlank()) {
                    Text(
                        text = waypoint.note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Slet",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Re-draws all markers, polylines, casing, loading lines, alternatives, and GPX reference on the MapView.
 */
private fun refreshMapOverlays(
    mapView: MapView,
    context: Context,
    state: com.example.ui.viewmodel.RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    layersState: MapLayersState,
    markedRoutes: List<MarkedRouteDto>,
    onWaypointClicked: (RouteWaypoint) -> Unit,
    onAccessPolygonClicked: (com.example.data.model.access.AccessPolygon) -> Unit,
    onMarkedRouteClicked: (MarkedRouteDto) -> Unit = {}
) {
    // Preserve base events overlay (index 0)
    val baseEventsOverlay = mapView.overlays.firstOrNull { it is MapEventsOverlay }
    mapView.overlays.clear()
    if (baseEventsOverlay != null) {
        mapView.overlays.add(baseEventsOverlay)
    }

    // 0. Draw Access Polygons Layer (Phase 10 & 13)
    if (state.isAccessLayerVisible && layersState.showAccess) {
        state.accessPolygons.forEach { poly ->
            val polyOverlay = com.example.util.access.AccessMapOverlayHelper.createAccessPolygonOverlay(
                mapView = mapView,
                polygon = poly,
                onPolygonClicked = onAccessPolygonClicked
            )
            mapView.overlays.add(polyOverlay)
        }
    }

    // Phase 13 Requirement B: Draw Marked Routes ("Afmærkede ruter")
    if (layersState.showMarkedRoutes && markedRoutes.isNotEmpty()) {
        markedRoutes.forEach { route ->
            val colorInt = try {
                AndroidColor.parseColor(route.colorHex)
            } catch (_: Exception) {
                AndroidColor.rgb(46, 125, 50)
            }
            for (pts in route.polylines) {
                if (pts.size < 2) continue
                val polyline = org.osmdroid.views.overlay.Polyline(mapView).apply {
                    setPoints(pts.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = colorInt
                    outlinePaint.strokeWidth = 5f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    title = route.name
                    subDescription = "${route.lengthKm} km • ${route.displayOperator}"
                    setOnClickListener { _, _, _ ->
                        onMarkedRouteClicked(route)
                        true
                    }
                }
                mapView.overlays.add(polyline)
            }
        }
    }

    // 1. Draw original GPX comparison line if present (thin grey)
    if (state.gpxOriginalPoints.isNotEmpty()) {
        val gpxGeoPoints = state.gpxOriginalPoints.map { GeoPoint(it.lat, it.lng) }
        val gpxLine = RouteMarkerHelper.createGpxOriginalPolyline(mapView, gpxGeoPoints)
        mapView.overlays.add(gpxLine)
    }

    // 2. Draw alternative routes (1-3)
    state.alternatives.forEach { alt ->
        val altGeoPoints = alt.points.map { GeoPoint(it.lat, it.lng) }
        val altLine = RouteMarkerHelper.createAlternativePolyline(mapView, altGeoPoints, alt.index)
        mapView.overlays.add(altLine)
    }

    // 3. Draw Legs (Casing first, then core stroke)
    val wps = state.waypoints
    for (i in 0 until wps.size - 1) {
        val wpFrom = wps[i]
        val wpTo = wps[i + 1]
        val legKey = "${wpFrom.id}_to_${wpTo.id}"

        val isLoading = state.loadingLegKeys.contains(legKey)
        val legData = state.legs[legKey]

        if (isLoading) {
            // Draw loading leg as thin dashed grey line between endpoints
            val linePts = listOf(GeoPoint(wpFrom.lat, wpFrom.lng), GeoPoint(wpTo.lat, wpTo.lng))
            val loadingLine = RouteMarkerHelper.createDashedLegPolyline(mapView, linePts, isLoading = true)
            mapView.overlays.add(loadingLine)
        } else if (legData != null) {
            val geoPoints = legData.points.map { GeoPoint(it.lat, it.lng) }
            if (legData.legMode == LegMode.STRAIGHT) {
                // Dashed line for straight legs
                val straightLine = RouteMarkerHelper.createDashedLegPolyline(mapView, geoPoints, isLoading = false)
                mapView.overlays.add(straightLine)
            } else {
                // Snapped legs: casing + solid line
                val casing = RouteMarkerHelper.createCasingPolyline(mapView, geoPoints)
                val core = RouteMarkerHelper.createSnappedLegPolyline(mapView, geoPoints, legData.hasAccessWarning)
                mapView.overlays.add(casing)
                mapView.overlays.add(core)
            }
        }
    }

    // 4. Draw Waypoint Markers
    wps.forEachIndexed { idx, wp ->
        val marker = Marker(mapView).apply {
            position = GeoPoint(wp.lat, wp.lng)
            icon = RouteMarkerHelper.createWaypointIcon(context, wp.role, idx, isSelected = wp.id == state.selectedWaypointId)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isDraggable = true

            // Drag listener: Re-routes ONLY adjacent legs when finished
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(m: Marker) {}
                override fun onMarkerDragStart(m: Marker) {}
                override fun onMarkerDragEnd(m: Marker) {
                    viewModel.moveWaypoint(wp.id, m.position.latitude, m.position.longitude)
                }
            })

            // Tap listener: Opens waypoint options sheet
            setOnMarkerClickListener { _, _ ->
                onWaypointClicked(wp)
                true
            }
        }
        mapView.overlays.add(marker)
    }

    // 5. Draw scrubbed cursor position marker on map if active
    state.scrubbedPointOnMap?.let { scrub ->
        val cursorMarker = Marker(mapView).apply {
            position = GeoPoint(scrub.lat, scrub.lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            // Create small high-contrast cursor marker
            val bmp = android.graphics.Bitmap.createBitmap(36, 36, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            cv.drawCircle(18f, 18f, 16f, p)
            p.color = AndroidColor.rgb(46, 125, 50)
            cv.drawCircle(18f, 18f, 11f, p)
            icon = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        }
        mapView.overlays.add(cursorMarker)
    }

    // 6. Draw Custom User Waypoints (Phase 12 Feature 6: Mine punkter)
    state.customUserWaypoints.forEach { customWp ->
        val marker = Marker(mapView).apply {
            position = GeoPoint(customWp.lat, customWp.lng)
            title = customWp.name
            snippet = customWp.note
            icon = RouteMarkerHelper.createCustomWaypointIcon(context)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                viewModel.selectCustomWaypointForDetails(customWp)
                true
            }
        }
        mapView.overlays.add(marker)
    }

    mapView.invalidate()
}

private fun zoomToRoute(mapView: MapView, waypoints: List<RouteWaypoint>) {
    if (waypoints.isEmpty()) return
    var minLat = 90.0
    var maxLat = -90.0
    var minLng = 180.0
    var maxLng = -180.0

    for (wp in waypoints) {
        if (wp.lat < minLat) minLat = wp.lat
        if (wp.lat > maxLat) maxLat = wp.lat
        if (wp.lng < minLng) minLng = wp.lng
        if (wp.lng > maxLng) maxLng = wp.lng
    }

    val latPad = (maxLat - minLat).coerceAtLeast(0.01) * 0.2
    val lngPad = (maxLng - minLng).coerceAtLeast(0.01) * 0.2

    val box = BoundingBox(maxLat + latPad, maxLng + lngPad, minLat - latPad, minLng - lngPad)
    mapView.zoomToBoundingBox(box, true, 100)
}

private fun getLastKnownLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (_: SecurityException) {
        null
    }
}
