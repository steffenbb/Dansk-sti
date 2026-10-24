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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Tune
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenDark
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerMapPoi
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteAlternativeInfo
import com.example.ui.viewmodel.RouteBuilderUiState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SheetHeightState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderScreen(
    routeId: Long? = null,
    baseTrailId: String? = null,
    existingPlanId: Long? = null,
    destLat: Double? = null,
    destLng: Double? = null,
    destName: String? = null,
    viewModel: RouteBuilderViewModel,
    onBack: () -> Unit,
    onSavedSuccessfully: () -> Unit,
    onPlanStages: (String) -> Unit = {},
    onStartNavigation: (String) -> Unit = {}
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
    var showLayersSheet by remember { mutableStateOf(false) }
    var hasZoomedToInitialRoute by remember { mutableStateOf(false) }

    // MapView reference for overlay updates and gestures
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    val visibleWaypoints = remember(uiState.waypoints) {
        uiState.waypoints.filter { !it.isHelper }
    }

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

    // Initialize ViewModel in TRAIL or FREE mode
    LaunchedEffect(routeId, baseTrailId, existingPlanId, destLat, destLng) {
        val loc = getLastKnownLocation(context)
        viewModel.initialize(
            routeId = routeId,
            destLat = destLat,
            destLng = destLng,
            destName = destName,
            gpsLat = loc?.latitude,
            gpsLng = loc?.longitude,
            baseTrailId = baseTrailId,
            existingPlanId = existingPlanId
        )
    }

    // Auto-zoom when base trail or existing route loads
    LaunchedEffect(uiState.waypoints, uiState.baseTrailPoints, mapViewInstance) {
        val map = mapViewInstance ?: return@LaunchedEffect
        if (!hasZoomedToInitialRoute) {
            val pts = if (uiState.mode == PlannerMode.TRAIL && uiState.baseTrailPoints.size >= 2) {
                uiState.baseTrailPoints.map { GeoPoint(it.lat, it.lng) }
            } else if (visibleWaypoints.size >= 2 && (routeId != null || existingPlanId != null || baseTrailId != null)) {
                visibleWaypoints.map { GeoPoint(it.lat, it.lng) }
            } else {
                emptyList()
            }
            if (pts.size >= 2) {
                hasZoomedToInitialRoute = true
                map.post {
                    try {
                        val box = BoundingBox.fromGeoPoints(pts)
                        map.zoomToBoundingBox(box.increaseByScale(1.25f), true)
                    } catch (_: Exception) {
                        map.controller.setCenter(pts.first())
                    }
                }
            }
        }
    }

    // Handle toast messages
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (android.os.Looper.myLooper() != null) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var overlayManagerInstance by remember { mutableStateOf<com.example.ui.navigation.MapLayersOverlayManager?>(null) }
    var currentEffectiveDarkMap by remember { mutableStateOf(isDark) }

    // Update map overlays whenever state changes
    LaunchedEffect(
        uiState.waypoints,
        uiState.legs,
        uiState.loadingLegKeys,
        uiState.failedLegs,
        uiState.alternatives,
        uiState.gpxOriginalPoints,
        uiState.scrubbedPointOnMap,
        uiState.baseTrailPoints,
        uiState.baseTrailKmMarkers,
        uiState.draggingWaypointId,
        uiState.draggingTrailKmLabel,
        currentEffectiveDarkMap,
        mapViewInstance,
        overlayManagerInstance
    ) {
        val map = mapViewInstance ?: return@LaunchedEffect
        val mgr = overlayManagerInstance ?: return@LaunchedEffect
        refreshMapOverlays(
            mapView = map,
            overlayManager = mgr,
            isDark = currentEffectiveDarkMap,
            context = context,
            state = uiState,
            viewModel = viewModel,
            onWaypointClicked = { wp ->
                selectedWaypointForSheet = wp
                renameWaypointText = wp.name ?: ""
            }
        )
    }

    val localView = androidx.compose.ui.platform.LocalView.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable {
                                    renameRouteName = uiState.routeName.ifBlank {
                                        if (uiState.mode == PlannerMode.TRAIL) uiState.baseTrail?.name ?: "Vandrerute" else "Min rute"
                                    }
                                    showRenameDialog = true
                                }
                                .testTag("planner_top_rename_chip")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.testTag("route_title_header")
                            ) {
                                Text(
                                    text = uiState.routeName.ifBlank {
                                        if (uiState.mode == PlannerMode.TRAIL) uiState.baseTrail?.name ?: "Ruteplanlægger" else "Ny rute"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Omdøb rute",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = buildString {
                                    append(if (uiState.mode == PlannerMode.TRAIL) "Vandrerute" else "Fri rute")
                                    if (uiState.isReverse) append(" · Omvendt retning")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Requirement F: Top stats chip
                        val topTotalDays = maxOf(1, uiState.daySummaries.size)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, ForestGreenPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier.testTag("planner_top_stats_chip")
                        ) {
                            Text(
                                text = String.format(
                                    Locale("da", "DK"),
                                    "%.1f km · +%.0f m · %d d",
                                    uiState.stats.totalKm,
                                    uiState.stats.ascentM,
                                    topTotalDays
                                ),
                                style = com.example.ui.theme.TabularLabelStyle.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("route_top_stats_chip")
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("btn_back_route_builder")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbage")
                    }
                },
                actions = {
                    // Indstillinger for turen (Requirement A)
                    IconButton(
                        onClick = { viewModel.openTripSettingsSheet() },
                        modifier = Modifier.testTag("btn_open_trip_settings")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Indstillinger for turen")
                    }

                    // Toggle Map Layers Sheet
                    IconButton(
                        onClick = { showLayersSheet = true },
                        modifier = Modifier.testTag("btn_toggle_access_layer")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Kortlag",
                            tint = if (uiState.isAccessLayerVisible) ForestGreenPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (uiState.mode == PlannerMode.FREE) {
                        // Import GPX
                        IconButton(
                            onClick = { gpxPickerLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*")) },
                            modifier = Modifier.testTag("btn_import_gpx")
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Importer GPX")
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
            // Shared DanskStiMap
            com.example.ui.map.DanskStiMap(
                modifier = Modifier.fillMaxSize(),
                mapTestTag = "route_builder_map",
                initialZoom = 7.5,
                isSystemDarkOverride = isDark,
                customWaypoints = uiState.customUserWaypoints,
                routeIdForDropPinSave = uiState.routeId,
                controlsTopPadding = 12.dp,
                attributionBottomPadding = when (uiState.sheetHeightState) {
                    SheetHeightState.PEEK -> 200.dp
                    SheetHeightState.HALF -> 390.dp
                    SheetHeightState.FULL -> 520.dp
                },
                layersButtonTestTag = "route_builder_layers_button",
                compassButtonTestTag = "route_builder_compass_button",
                myLocationButtonTestTag = "btn_my_location",
                onMyLocationClick = { loc ->
                    if (loc != null) {
                        com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(localView, "gps_start")
                        viewModel.setStartAtGps(loc.latitude, loc.longitude)
                    } else {
                        val lastLoc = getLastKnownLocation(context)
                        if (lastLoc != null) {
                            com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(localView, "gps_start")
                            viewModel.setStartAtGps(lastLoc.latitude, lastLoc.longitude)
                            mapViewInstance?.controller?.animateTo(GeoPoint(lastLoc.latitude, lastLoc.longitude))
                        } else {
                            Toast.makeText(context, "GPS-position ikke tilgængelig", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMapTap = { p ->
                    com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(localView, "add_pin")
                    viewModel.addPointAtEnd(p.latitude, p.longitude)
                    true
                },
                onAddToRouteFromDropPin = { lat, lng, label ->
                    com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(localView, "drop_pin_add")
                    if (uiState.mode == PlannerMode.TRAIL) {
                        viewModel.addDetourPoiToRoute(
                            lat = lat,
                            lng = lng,
                            name = label,
                            role = WaypointRole.VIA
                        )
                    } else {
                        viewModel.addPointAtEnd(lat, lng, label)
                    }
                },
                onRouteHereFromDropPin = { lat, lng, label ->
                    com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(localView, "drop_pin_snap")
                    if (uiState.mode == PlannerMode.TRAIL) {
                        viewModel.addDetourPoiToRoute(
                            lat = lat,
                            lng = lng,
                            name = label,
                            role = WaypointRole.VIA
                        )
                    } else {
                        viewModel.insertViaPointOnLine(lat, lng)
                    }
                },
                onPoiTapped = { poiItem ->
                    val role = if (poiItem.type == "shelter" || poiItem.type == "tent") {
                        WaypointRole.OVERNIGHT
                    } else {
                        WaypointRole.VIA
                    }
                    viewModel.addDetourPoiToRoute(
                        lat = poiItem.lat,
                        lng = poiItem.lng,
                        name = poiItem.name,
                        role = role,
                        poiId = poiItem.id
                    )
                },
                onAccessPolygonTapped = { poly ->
                    viewModel.selectAccessPolygon(poly)
                },
                onCameraChanged = { _, zoom, bbox ->
                    if (bbox != null) {
                        viewModel.onMapCameraIdle(
                            minLat = bbox.latSouth,
                            maxLat = bbox.latNorth,
                            minLng = bbox.lonWest,
                            maxLng = bbox.lonEast,
                            zoom = zoom,
                            isMapLayerAccessEnabled = layersState.showAccessLand
                        )
                    }
                },
                onMapReady = { mv ->
                    mapViewInstance = mv
                    val initialLoc = getLastKnownLocation(mv.context)
                    if (initialLoc != null && routeId == null && baseTrailId == null && existingPlanId == null && destLat == null) {
                        mv.controller.setZoom(13.5)
                        mv.controller.setCenter(GeoPoint(initialLoc.latitude, initialLoc.longitude))
                    }
                },
                updateOverlaysKey = Triple(uiState.waypoints, uiState.legs, uiState.baseTrailPoints),
                onUpdateOverlays = { mapView, overlayManager, isDarkMap ->
                    overlayManagerInstance = overlayManager
                    currentEffectiveDarkMap = isDarkMap
                    refreshMapOverlays(
                        mapView = mapView,
                        overlayManager = overlayManager,
                        isDark = isDarkMap,
                        context = context,
                        state = uiState,
                        viewModel = viewModel,
                        onWaypointClicked = { wp ->
                            selectedWaypointForSheet = wp
                            renameWaypointText = wp.name ?: ""
                        }
                    )
                }
            )

            // Live dragging km badge in TRAIL mode (Requirement C: show "km 23,4" next to pin while dragging)
            uiState.draggingTrailKmLabel?.let { kmLabel ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ForestGreenPrimary,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .testTag("dragging_trail_km_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsWalk,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = kmLabel,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Top overlay column:
            // - Amber banner when BRouterOnlineEngine.isBuiltinFallbackInUse() is true
            // - Prompt when waiting for start point tap ("Vælg startpunkt - tryk på kortet")
            // - Failed leg chip with "Prøv igen", "Tegn lige linje", "Flyt punkt"
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 68.dp, top = if (uiState.draggingTrailKmLabel != null) 54.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.isBuiltinFallbackInUse || com.example.util.routing.BRouterOnlineEngine.isBuiltinFallbackInUse()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFF3E0),
                        border = BorderStroke(1.dp, AmberAccent),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_builtin_fallback")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Undgå veje og Foretræk natur virker ikke lige nu",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                if (uiState.waitingForStartPointTap) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ForestGreenPrimary,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_select_start_point")
                    ) {
                        Text(
                            text = "Vælg startpunkt - tryk på kortet",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (uiState.isDownloadingTrailGeometry) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, ForestGreenPrimary),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_downloading_trail_geometry")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ForestGreenPrimary
                            )
                            Text(
                                text = "Henter rutens spor...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (uiState.trailGeometryDownloadFailed) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, Color(0xFFC62828)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_trail_download_failed")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Ruten kunne ikke hentes",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.retryDownloadTrailGeometry() },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("btn_retry_trail_download")
                            ) {
                                Text("Prøv igen", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                uiState.corridorPoisOfflineNotice?.let { notice ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("banner_corridor_pois_offline")
                    ) {
                        Text(
                            text = notice,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                uiState.failedLegs.entries.forEach { (legKey, errorMsg) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.5.dp, Color(0xFFC62828)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("failed_leg_chip_$legKey")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = errorMsg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFC62828),
                                modifier = Modifier.testTag("failed_leg_message_$legKey")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.retryFailedLeg(legKey) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .testTag("btn_retry_leg_$legKey")
                                ) {
                                    Text("Prøv igen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.setFailedLegStraight(legKey) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .testTag("btn_straight_leg_$legKey")
                                ) {
                                    Text("Tegn lige linje", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.activateMovePointForFailedLeg(legKey) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .testTag("btn_move_point_$legKey")
                                ) {
                                    Text("Flyt punkt", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Additional Planner Floating Controls: Zoom to route & "Mit punkt"
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 180.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Zoom to bounds button
                if (visibleWaypoints.size >= 2) {
                    FloatingActionButton(
                        onClick = {
                            val map = mapViewInstance ?: return@FloatingActionButton
                            zoomToRoute(map, visibleWaypoints)
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

                // "Mit punkt" (Mine punkter)
                FloatingActionButton(
                    onClick = {
                        val center = mapViewInstance?.mapCenter
                        if (center != null && (center.latitude != 0.0 || center.longitude != 0.0)) {
                            viewModel.openAddCustomWaypointDialog(center.latitude, center.longitude)
                        } else {
                            Toast.makeText(context, "Kortposition ikke klar endnu", Toast.LENGTH_SHORT).show()
                        }
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

            // Requirement F: Bottom floating toolbar above the sheet + Planner Bottom Sheet (PEEK / HALF / FULL)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // Bottom floating toolbar above the sheet: Fortryd, Gendan, Følg stier / Lige linje, Vend, Overnatning, Gem
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("planner_floating_toolbar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Fortryd
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (uiState.canUndo) 0.7f else 0.3f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = uiState.canUndo) { viewModel.undo() }
                                .testTag("btn_undo")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Fortryd",
                                    tint = if (uiState.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Fortryd",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }

                        // 2. Gendan
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (uiState.canRedo) 0.7f else 0.3f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = uiState.canRedo) { viewModel.redo() }
                                .testTag("btn_redo")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = "Gendan",
                                    tint = if (uiState.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Gendan",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }

                        // 3. Følg stier / Lige linje (toggle for the next leg; active tool highlighted amber)
                        val isStraightNext = uiState.nextLegMode == LegMode.STRAIGHT
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isStraightNext) com.example.ui.theme.AccentAmber else com.example.ui.theme.AccentAmber.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, com.example.ui.theme.AccentAmber),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.toggleNextLegMode() }
                                .testTag("btn_toggle_leg_mode")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isStraightNext) Icons.Default.Timeline else Icons.Default.Route,
                                    contentDescription = if (isStraightNext) "Lige linje" else "Følg stier",
                                    tint = Color(0xFF1F1600),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isStraightNext) "Lige linje" else "Følg stier",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1F1600)
                                )
                            }
                        }

                        // 4. Vend
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (uiState.isReverse) com.example.ui.theme.AccentAmber else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = visibleWaypoints.size >= 2) { viewModel.reverseRoute() }
                                .testTag("btn_reverse_route")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Vend",
                                    tint = if (uiState.isReverse) Color(0xFF1F1600) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Vend",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (uiState.isReverse) FontWeight.Bold else FontWeight.Medium,
                                    color = if (uiState.isReverse) Color(0xFF1F1600) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 5. Overnatning (active tool highlighted amber)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (uiState.isOvernightToolActive) com.example.ui.theme.AccentAmber else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.toggleOvernightTool() }
                                .testTag("btn_overnight_tool")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NightShelter,
                                    contentDescription = "Overnatning",
                                    tint = if (uiState.isOvernightToolActive) Color(0xFF1F1600) else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Overnatning",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (uiState.isOvernightToolActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (uiState.isOvernightToolActive) Color(0xFF1F1600) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 6. Gem
                        Button(
                            onClick = {
                                renameRouteName = uiState.routeName.ifBlank {
                                    if (uiState.mode == PlannerMode.TRAIL) {
                                        uiState.baseTrail?.name ?: "Turplan"
                                    } else {
                                        val s = visibleWaypoints.firstOrNull()?.name ?: "Start"
                                        val e = visibleWaypoints.lastOrNull()?.name ?: "Slut"
                                        "$s - $e"
                                    }
                                }
                                showSaveDialog = true
                            },
                            enabled = visibleWaypoints.size >= 2 && !uiState.isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("btn_save_route")
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Save, contentDescription = "Gem rute", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gem", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Requirement F: Planner Bottom Sheet (3 heights: PEEK / HALF / FULL) with 28 dp top corners
                Card(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("route_bottom_sheet")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                    // Header row: Peek summary "3 dage · 46,2 km · +410 m · ca. 12 t 30 min" + 3-height selector
                    val totalDays = maxOf(1, uiState.daySummaries.size)
                    val peekSummaryText = String.format(
                        Locale.GERMANY,
                        "%d %s · %.1f km · +%.0f m · %s",
                        totalDays,
                        if (totalDays == 1) "dag" else "dage",
                        uiState.stats.totalKm,
                        uiState.stats.ascentM,
                        uiState.timeEstimateFormatted
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val nextState = when (uiState.sheetHeightState) {
                                    SheetHeightState.PEEK -> SheetHeightState.HALF
                                    SheetHeightState.HALF -> SheetHeightState.FULL
                                    SheetHeightState.FULL -> SheetHeightState.PEEK
                                }
                                viewModel.setSheetHeightState(nextState)
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peekSummaryText,
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

                        // 3-Height selector buttons (Peek / Half / Full)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SheetHeightChip(
                                label = "Kort",
                                selected = uiState.sheetHeightState == SheetHeightState.PEEK,
                                onClick = { viewModel.setSheetHeightState(SheetHeightState.PEEK) },
                                testTag = "btn_sheet_peek"
                            )
                            SheetHeightChip(
                                label = "Dage",
                                selected = uiState.sheetHeightState == SheetHeightState.HALF,
                                onClick = { viewModel.setSheetHeightState(SheetHeightState.HALF) },
                                testTag = "btn_sheet_half"
                            )
                            SheetHeightChip(
                                label = "Detaljer",
                                selected = uiState.sheetHeightState == SheetHeightState.FULL,
                                onClick = { viewModel.setSheetHeightState(SheetHeightState.FULL) },
                                testTag = "btn_sheet_full"
                            )
                            IconButton(
                                onClick = {
                                    val nextState = when (uiState.sheetHeightState) {
                                        SheetHeightState.PEEK -> SheetHeightState.HALF
                                        SheetHeightState.HALF -> SheetHeightState.FULL
                                        SheetHeightState.FULL -> SheetHeightState.PEEK
                                    }
                                    viewModel.setSheetHeightState(nextState)
                                },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("btn_toggle_sheet")
                            ) {
                                Icon(
                                    imageVector = if (uiState.sheetHeightState == SheetHeightState.FULL) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Skift ark-højde",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Quick Action Buttons Row
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
                            // Requirement E: "Foreslå overnatninger" runs the unified stage planner on the current route
                            Button(
                                onClick = {
                                    viewModel.suggestOvernightStops()
                                    if (uiState.sheetHeightState == SheetHeightState.PEEK) {
                                        viewModel.setSheetHeightState(SheetHeightState.HALF)
                                    }
                                },
                                enabled = visibleWaypoints.size >= 2 && !uiState.isFindingOvernight,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("btn_find_overnight")
                            ) {
                                if (uiState.isFindingOvernight) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Foreslår...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Foreslå overnatninger", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Requirement G: Start navigation directly from planner
                            Button(
                                onClick = {
                                    viewModel.saveAndStartNavigation { navId ->
                                        onStartNavigation(navId)
                                    }
                                },
                                enabled = visibleWaypoints.size >= 2 && !uiState.isSaving,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .testTag("btn_start_planned_navigation")
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.openTripSettingsSheet() },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1.1f)
                                    .testTag("btn_sheet_trip_settings")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Indstillinger", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            if (uiState.mode == PlannerMode.FREE) {
                                OutlinedButton(
                                    onClick = { viewModel.openRundturDialog() },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(0.9f)
                                        .testTag("btn_open_rundtur")
                                ) {
                                    Icon(Icons.Default.Loop, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rundtur", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { viewModel.openNearestPoiDialog() },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(0.9f)
                                        .testTag("btn_open_nearest_poi")
                                ) {
                                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Nærmeste", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.shareRoute(context) },
                                enabled = visibleWaypoints.size >= 2,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("btn_share_route_sheet")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Del", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // HALF or FULL section: Day list (Requirement F Half)
                    AnimatedVisibility(
                        visible = uiState.sheetHeightState == SheetHeightState.HALF || uiState.sheetHeightState == SheetHeightState.FULL
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    max = if (uiState.sheetHeightState == SheetHeightState.HALF) 290.dp else 460.dp
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Day list header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Dagsplan (${uiState.daySummaries.size} ${if (uiState.daySummaries.size == 1) "dag" else "dage"})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Mål: ${uiState.dailyTargetKm.toInt()} km/dag · ${String.format(Locale.GERMANY, "%.1f", uiState.paceKmh)} km/t",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (uiState.daySummaries.isEmpty()) {
                                Text(
                                    text = "Tilføj mindst 2 punkter på kortet for at se dagsplanen.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    uiState.daySummaries.forEach { day ->
                                        PlannerDaySummaryCard(
                                            day = day,
                                            showNightWeather = uiState.sheetHeightState == SheetHeightState.FULL,
                                            onReplaceOvernight = { wpId ->
                                                viewModel.openReplaceOvernightDialog(wpId)
                                            },
                                            onDeleteOvernight = { wpId ->
                                                viewModel.deleteWaypoint(wpId)
                                            },
                                            onZoomToEnd = { wpId ->
                                                val wp = uiState.waypoints.find { it.id == wpId }
                                                if (wp != null) {
                                                    mapViewInstance?.controller?.animateTo(GeoPoint(wp.lat, wp.lng), 15.0, 500L)
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Supply Gap Warnings Banner
                            if (uiState.supplyGapWarnings.isNotEmpty()) {
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

                            // Access Warnings Banner
                            if (uiState.accessWarnings.isNotEmpty()) {
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

                            // FULL section: Elevation profile with day boundaries & draggable overnight markers, weather per night, route quality, Gem / Start
                            if (uiState.sheetHeightState == SheetHeightState.FULL) {
                                HorizontalDivider()

                                // 1. Elevation Profile Chart with day boundaries & draggable overnight markers
                                val allPoints = remember(uiState.legs, uiState.waypoints) {
                                    val ordered = mutableListOf<com.example.util.routing.LegGeometryData>()
                                    for (i in 0 until uiState.waypoints.size - 1) {
                                        val key = "${uiState.waypoints[i].id}_to_${uiState.waypoints[i + 1].id}"
                                        uiState.legs[key]?.let { ordered.add(it) }
                                    }
                                    com.example.util.routing.RouteGeometryStorageManager.getCombinedPoints(ordered)
                                }
                                val overnightWps = remember(uiState.waypoints) {
                                    uiState.waypoints.filter { !it.isHelper && it.role == WaypointRole.OVERNIGHT }
                                }
                                val dayBoundaryKms = remember(uiState.daySummaries) {
                                    uiState.daySummaries.dropLast(1).map { it.endKm }
                                }

                                Text(
                                    text = "Højdekurve (træk på kurven for at se på kort · træk ⛺ for at flytte overnatning)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                RouteElevationProfileChart(
                                    points = allPoints,
                                    dayBoundaryWalkKms = dayBoundaryKms,
                                    overnightWaypointIds = overnightWps.map { it.id },
                                    onPointScrubbed = { pt, _ ->
                                        viewModel.setScrubbedPoint(pt)
                                    },
                                    onOvernightMarkerDragged = { wpId, targetWalkKm, _ ->
                                        viewModel.moveOvernightPinByChartWalkKm(wpId, targetWalkKm)
                                    },
                                    modifier = Modifier.testTag("planner_elevation_chart")
                                )

                                // 2. Rutekvalitet & Underlag
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Rutekvalitet & Underlag",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (!uiState.stats.hasWayInfo || uiState.stats.pathSharePct == null) {
                                        Text(
                                            text = "Stiandel: ukendt • Vej-km: ukendt",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("way_info_unknown_text")
                                        )
                                    } else {
                                        val q = uiState.routeQuality
                                        val effTotal = if (q.totalKm > 0.0) q.totalKm else 1.0

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
                                }

                                // 3. Routing Options (FREE mode or detours)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Ruteindstillinger (ved ruteberegning)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

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

                                // 4. Waypoints list (visible waypoints only)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Rutens punkter (${visibleWaypoints.size})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    visibleWaypoints.forEachIndexed { idx, wp ->
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

                                // 5. Gem & Start buttons in Full sheet (Requirement F)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            renameRouteName = uiState.routeName.ifBlank {
                                                uiState.baseTrail?.name ?: "Min rute"
                                            }
                                            showSaveDialog = true
                                        },
                                        enabled = visibleWaypoints.size >= 2 && !uiState.isSaving,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("btn_full_sheet_save")
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Gem", fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.saveAndStartNavigation { navId ->
                                                onStartNavigation(navId)
                                            }
                                        },
                                        enabled = visibleWaypoints.size >= 2 && !uiState.isSaving,
                                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("btn_full_sheet_start")
                                    ) {
                                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Start", fontWeight = FontWeight.Bold)
                                    }
                                }
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
                        showSaveDialog = false
                        viewModel.saveRoute(onSuccess = {
                            onSavedSuccessfully()
                        })
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

    // Requirement 20: If the edited custom route's trip plan has walking progress, ask first:
    // "Ruten er ændret - nulstil fremskridt?"
    if (uiState.showResetProgressDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissResetProgressDialog() },
            title = { Text("Ruten er ændret - nulstil fremskridt?") },
            text = {
                Text("Denne rute har registreret vandrefremskridt i din turplan. Vil du nulstille fremskridtet og genberegne turplanen?")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmResetProgressAndSave() },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier.testTag("btn_confirm_reset_progress")
                ) {
                    Text("Ja, nulstil")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissResetProgressDialog() },
                    modifier = Modifier.testTag("btn_cancel_reset_progress")
                ) {
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

    // Dialog / Small sheet: Waypoint details (Requirement C & D)
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
                    // Show trail km if on trail (Requirement C)
                    if (wp.trailKm != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestGreenPrimary.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = String.format(Locale.GERMANY, "På vandreruten: km %.1f", wp.trailKm),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ForestGreenPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Show detour cost on pin and in sheet (Requirement D: "Omvej til <navn>: +1,4 km, +12 m")
                    val detourKm = wp.detourDistanceKm
                    if (detourKm != null && detourKm > 0.01) {
                        val detourAsc = (wp.detourAscentM ?: 0.0).toInt()
                        val displayName = wp.name?.ifBlank { null } ?: "punkt"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AmberAccent.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("waypoint_sheet_detour_info")
                        ) {
                            Text(
                                text = String.format(
                                    Locale.GERMANY,
                                    "Omvej til %s: +%.1f km, +%d m",
                                    displayName,
                                    detourKm,
                                    detourAsc
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Rename field
                    OutlinedTextField(
                        value = renameWaypointText,
                        onValueChange = { renameWaypointText = it },
                        label = { Text("Navn på punkt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_waypoint_name")
                    )

                    // Skift overnatning (Requirement E: lists candidates within max detour)
                    if (wp.role == WaypointRole.OVERNIGHT) {
                        Button(
                            onClick = {
                                val id = wp.id
                                selectedWaypointForSheet = null
                                viewModel.openReplaceOvernightDialog(id)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_replace_overnight_waypoint")
                        ) {
                            Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Skift overnatning")
                        }
                    }

                    // Role: Gør til overnatning / Gør til via
                    if (wp.role != WaypointRole.START && wp.role != WaypointRole.END) {
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
                    }

                    // Leg mode to next: Lige linje herfra / Følg stier herfra
                    if (wp.role != WaypointRole.END && uiState.mode == PlannerMode.FREE) {
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
                    if (!(uiState.mode == PlannerMode.TRAIL && (wp.role == WaypointRole.START || wp.role == WaypointRole.END))) {
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

    // Requirement C: Tapping the trail line opens a small sheet: "Start her", "Slut her", "Overnat her", "Tilføj punkt"
    uiState.trailLineTapTarget?.let { tap ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTrailLineTapSheet() },
            title = {
                Text(
                    text = String.format(Locale.GERMANY, "På sporet ved km %.1f", tap.trailKm),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("trail_line_tap_sheet")
                ) {
                    Text(
                        text = "Vælg handling for dette punkt på vandreruten:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { viewModel.onTrailTapStartHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_trail_tap_start_here")
                    ) {
                        Text("Start her", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.onTrailTapSlutHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_trail_tap_end_here")
                    ) {
                        Text("Slut her", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.onTrailTapOvernatHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_trail_tap_overnight_here")
                    ) {
                        Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Overnat her", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { viewModel.onTrailTapTilfoejPunkt() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_trail_tap_add_point")
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tilføj punkt", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissTrailLineTapSheet() }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Requirement D & E: Tapping a nearby shelter/fri teltning/water/toilet/shop on the map
    uiState.selectedNearbyPoiForSheet?.let { poi ->
        AlertDialog(
            onDismissRequest = { viewModel.selectNearbyPoiForSheet(null) },
            title = {
                Text(
                    text = poi.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.testTag("nearby_poi_sheet")
                ) {
                    val typeLabel = when (poi.type) {
                        "SHELTER" -> "Shelter / lejrplads"
                        "FRI_TELTNING" -> "Fri teltning skov"
                        "WATER" -> "Vandpost"
                        "TOILET" -> "Toilet"
                        "SHOP" -> "Indkøb / butik"
                        else -> poi.type
                    }
                    Text(
                        text = "$typeLabel · ca. ${poi.distanceToRouteMeters.toInt()} m fra ruten",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (poi.type == "SHELTER" || poi.type == "FRI_TELTNING") {
                        Text(
                            text = if (poi.bookingRequired) "📅 Kræver booking" else "✓ Gratis / fri afbenyttelse",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (poi.bookingRequired) AmberAccent else ForestGreenPrimary
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.addDetourPoiToRoute(
                                lat = poi.lat,
                                lng = poi.lng,
                                name = poi.name,
                                role = WaypointRole.OVERNIGHT,
                                poiId = poi.id,
                                poiType = poi.type
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_poi_overnat_her")
                    ) {
                        Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Overnat her", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.addDetourPoiToRoute(
                                lat = poi.lat,
                                lng = poi.lng,
                                name = poi.name,
                                role = WaypointRole.VIA,
                                poiId = poi.id,
                                poiType = poi.type
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_poi_tilfoej_som_stop")
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tilføj som stop", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.selectNearbyPoiForSheet(null) }) {
                    Text("Luk")
                }
            }
        )
    }

    // Requirement E: "Skift overnatning" dialog listing candidates within max detour
    if (uiState.replacingOvernightWaypointId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReplaceOvernightDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, tint = ForestGreenPrimary)
                    Text("Skift overnatning", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                if (uiState.replaceOvernightCandidates.isEmpty()) {
                    Text(
                        text = "Ingen andre shelters eller fri teltning fundet inden for maks. omvej (${String.format(Locale.GERMANY, "%.1f", uiState.maxDetourKm)} km).",
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.replaceOvernightCandidates) { cand ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.replaceOvernightWaypoint(cand) }
                                    .testTag("replace_candidate_${cand.id}")
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
                                            text = "$typeLabel · km ${String.format(Locale.GERMANY, "%.1f", cand.walkKm)} · +${cand.detourMeters.toInt()} m omvej",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { viewModel.replaceOvernightWaypoint(cand) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Vælg", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissReplaceOvernightDialog() }) {
                    Text("Annuller")
                }
            }
        )
    }

    // Requirement A: "Indstillinger for turen" sheet in the planner
    // Pace (Mit tempo), daily target km, start date/time and max detour
    if (uiState.showTripSettingsSheet) {
        TripSettingsDialog(
            uiState = uiState,
            onUpdateSettings = { pace, dailyKm, maxDetourKm, startHour, startEpochMs, pref ->
                viewModel.updateTripSettings(
                    paceKmh = pace,
                    dailyTargetKm = dailyKm,
                    maxDetourKm = maxDetourKm,
                    startHour = startHour,
                    startDateEpochMs = startEpochMs,
                    overnightPref = pref
                )
            },
            onDismiss = { viewModel.dismissTripSettingsSheet() }
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

    if (showLayersSheet) {
        MapLayersBottomSheet(
            state = layersState,
            settingsRepository = settingsRepository,
            onDismiss = { showLayersSheet = false }
        )
    }

    selectedMarkedRoute?.let { markedRoute ->
        val app = context.applicationContext as com.example.DanskStiApp
        MarkedRouteDetailSheet(
            route = markedRoute,
            onDismiss = { selectedMarkedRoute = null },
            onUseAsBaseInRouteBuilder = { r ->
                coroutineScope.launch {
                    app.repository.saveMarkedRouteAsTrail(r)
                }
                selectedMarkedRoute = null
            }
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
private fun SheetHeightChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ForestGreenPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PlannerDaySummaryCard(
    day: PlannerDaySummary,
    showNightWeather: Boolean,
    onReplaceOvernight: (String) -> Unit,
    onDeleteOvernight: (String) -> Unit,
    onZoomToEnd: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(
            1.dp,
            if (day.warnings.isNotEmpty() || day.arrivesAfterSunset) {
                AmberAccent.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("planner_day_card_${day.dayNumber}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: "Dag 1 · 15,1 km · +120 m"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(
                        Locale.GERMANY,
                        "Dag %d · %.1f km · +%.0f m",
                        day.dayNumber,
                        day.distanceKm,
                        day.ascentM
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ForestGreenPrimary,
                    modifier = Modifier.testTag("day_card_header_${day.dayNumber}")
                )

                val h = (day.estimatedHours * 60).toInt() / 60
                val m = (day.estimatedHours * 60).toInt() % 60
                Text(
                    text = if (h > 0) "ca. $h t $m min" else "ca. $m min",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // From -> To
            Text(
                text = "${day.fromName} → ${day.toName}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Overnight name, type, detour, booking flag, sunset at camp
            if (day.overnightWaypointId != null) {
                val typeLabel = when (day.overnightType) {
                    "FRI_TELTNING" -> "Fri teltning"
                    "SHELTER" -> "Shelter / lejrplads"
                    else -> "Overnatningssted"
                }
                val bookingLabel = if (day.bookingRequired) "📅 Booking kræves" else "✓ Fri afbenyttelse"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⛺ ${day.overnightName ?: day.toName} ($typeLabel)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE65100),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = bookingLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (day.bookingRequired) AmberAccent else ForestGreenPrimary
                    )
                }

                // Detour cost if any: "Omvej til <navn>: +1,4 km, +12 m"
                val detourKm = day.detourDistanceKm
                if (detourKm != null && detourKm > 0.01) {
                    Text(
                        text = String.format(
                            Locale.GERMANY,
                            "Omvej til %s: +%.1f km, +%d m",
                            day.overnightName ?: day.toName,
                            detourKm,
                            (day.detourAscentM ?: 0.0).toInt()
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE65100),
                        modifier = Modifier.testTag("day_card_detour_${day.dayNumber}")
                    )
                }
            }

            // Sunset at the camp & Arrival time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!day.arrivalTimeFormatted.isNullOrBlank()) {
                    Text(
                        text = "Ankomst ca. kl. ${day.arrivalTimeFormatted}",
                        fontSize = 11.sp,
                        color = if (day.arrivesAfterSunset) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (day.arrivesAfterSunset) FontWeight.Bold else FontWeight.Normal
                    )
                }
                if (!day.sunsetFormatted.isNullOrBlank()) {
                    Text(
                        text = "🌅 Solnedgang kl. ${day.sunsetFormatted}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Night weather (Requirement F Full: Open-Meteo within forecast range; otherwise "Vejrudsigt ikke tilgængelig endnu")
            if (showNightWeather) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🌙 Nattevejr: ${day.nightWeatherSummary}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("day_card_weather_${day.dayNumber}")
                    )
                }
            }

            // Warnings (access, water gaps, arrival after sunset)
            if (day.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    day.warnings.forEach { w ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = w,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFBF360C)
                                )
                            }
                        }
                    }
                }
            }

            // Actions for OVERNIGHT pin: "Skift overnatning", "Vis", "Slet" (Requirement E)
            if (day.overnightWaypointId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onReplaceOvernight(day.overnightWaypointId) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("btn_day_replace_overnight_${day.dayNumber}")
                    ) {
                        Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Skift overnatning", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onZoomToEnd(day.overnightWaypointId) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Vis", fontSize = 11.sp)
                    }

                    TextButton(
                        onClick = { onDeleteOvernight(day.overnightWaypointId) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("btn_day_delete_overnight_${day.dayNumber}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Fjern", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TripSettingsDialog(
    uiState: com.example.ui.viewmodel.RouteBuilderState,
    onUpdateSettings: (
        paceKmh: Double,
        dailyTargetKm: Double,
        maxDetourKm: Double,
        startHour: Int,
        startDateEpochMs: Long,
        overnightPref: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var pace by remember(uiState.paceKmh) { mutableStateOf(uiState.paceKmh) }
    var dailyKm by remember(uiState.dailyTargetKm) { mutableStateOf(uiState.dailyTargetKm) }
    var maxDetourKm by remember(uiState.maxDetourKm) { mutableStateOf(uiState.maxDetourKm) }
    var startHour by remember(uiState.startHour) { mutableStateOf(uiState.startHour) }
    var startDateOffsetDays by remember(uiState.startDateEpochMs) {
        val diff = ((uiState.startDateEpochMs - System.currentTimeMillis()) / (24L * 3600_000L)).toInt().coerceIn(0, 30)
        mutableStateOf(diff)
    }
    var overnightPref by remember(uiState.overnightPreference) { mutableStateOf(uiState.overnightPreference.name) }

    val selectedEpochMs = remember(startDateOffsetDays) {
        System.currentTimeMillis() + startDateOffsetDays * 24L * 3600_000L
    }
    val formattedStartDate = remember(selectedEpochMs) {
        SimpleDateFormat("d. MMM yyyy", Locale("da", "DK")).format(Date(selectedEpochMs))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = ForestGreenPrimary)
                Text("Indstillinger for turen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("trip_settings_sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Mit tempo (km/t)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mit tempo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "%.1f km/t", pace),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                    }
                    Slider(
                        value = pace.toFloat(),
                        onValueChange = { pace = (Math.round(it * 2f) / 2.0) },
                        valueRange = 2.5f..6.0f,
                        steps = 6,
                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("slider_trip_pace")
                    )
                }

                // 2. Dagsmål (km per dag)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Dagsmål (km/dag)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${dailyKm.toInt()} km",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                    }
                    Slider(
                        value = dailyKm.toFloat(),
                        onValueChange = { dailyKm = it.toInt().toDouble() },
                        valueRange = 5f..35f,
                        steps = 29,
                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("slider_trip_daily_km")
                    )
                }

                // 3. Maks. omvej til overnatning
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Maks. omvej til overnatning", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "%.1f km (%d m)", maxDetourKm, (maxDetourKm * 1000).toInt()),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                    }
                    Slider(
                        value = maxDetourKm.toFloat(),
                        onValueChange = { maxDetourKm = (Math.round(it * 10f) / 10.0) },
                        valueRange = 0.2f..2.5f,
                        steps = 22,
                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("slider_trip_max_detour")
                    )
                }

                // 4. Startdato og starttidspunkt
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Startdato", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (startDateOffsetDays == 0) "I dag ($formattedStartDate)" else "Om $startDateOffsetDays dage ($formattedStartDate)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                    }
                    Slider(
                        value = startDateOffsetDays.toFloat(),
                        onValueChange = { startDateOffsetDays = it.toInt() },
                        valueRange = 0f..14f,
                        steps = 13,
                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("slider_trip_start_date")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Starttidspunkt", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "kl. %02d:00", startHour),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                    }
                    Slider(
                        value = startHour.toFloat(),
                        onValueChange = { startHour = it.toInt() },
                        valueRange = 6f..14f,
                        steps = 7,
                        colors = SliderDefaults.colors(thumbColor = ForestGreenPrimary, activeTrackColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("slider_trip_start_hour")
                    )
                }

                // 5. Overnatningspræference
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Overnatningstype", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "BOTH" to "Begge",
                            "SHELTER" to "Kun shelter",
                            "FRI_TELTNING" to "Fri teltning"
                        ).forEach { (key, label) ->
                            OutlinedButton(
                                onClick = { overnightPref = key },
                                shape = RoundedCornerShape(8.dp),
                                colors = if (overnightPref == key) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = ForestGreenPrimary.copy(alpha = 0.15f),
                                        contentColor = ForestGreenPrimary
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdateSettings(
                        pace,
                        dailyKm,
                        maxDetourKm,
                        startHour,
                        selectedEpochMs,
                        overnightPref
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                modifier = Modifier.testTag("btn_save_trip_settings")
            ) {
                Text("Gem indstillinger", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuller")
            }
        }
    )
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
                val detourDist = waypoint.detourDistanceKm
                val subtitleText = when {
                    detourDist != null && detourDist > 0.01 -> {
                        String.format(
                            Locale.GERMANY,
                            "Omvej til %s: +%.1f km, +%d m",
                            waypoint.name ?: "punkt",
                            detourDist,
                            (waypoint.detourAscentM ?: 0.0).toInt()
                        )
                    }
                    waypoint.trailKm != null -> {
                        String.format(Locale.GERMANY, "På vandreruten · km %.1f", waypoint.trailKm)
                    }
                    waypoint.legModeToNext == LegMode.STRAIGHT -> "Lige linje til næste"
                    else -> "Følger stier"
                }
                Text(
                    text = subtitleText,
                    fontSize = 11.sp,
                    color = if (detourDist != null && detourDist > 0.01) {
                        Color(0xFFE65100)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
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
 * Updates the "route" folder overlay in place without ever calling mapView.overlays.clear().
 * Uses Requirement E light/dark mode route colors (#1E88E5 + white casing in light mode,
 * #64B5F6 + near-black casing in dark mode, red dashed failed legs, muted green TRAIL base line).
 */
private fun refreshMapOverlays(
    mapView: MapView,
    overlayManager: com.example.ui.navigation.MapLayersOverlayManager,
    isDark: Boolean,
    context: Context,
    state: com.example.ui.viewmodel.RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    onWaypointClicked: (RouteWaypoint) -> Unit
) {
    val routeFolder = mutableListOf<org.osmdroid.views.overlay.Overlay>()

    // 0. Requirement C & E: In TRAIL mode, draw the full trail in muted green with km labels every 5 km
    if (state.mode == PlannerMode.TRAIL && state.baseTrailPoints.size >= 2) {
        val trailGeoPoints = state.baseTrailPoints.map { GeoPoint(it.lat, it.lng) }
        val mutedLine = RouteMarkerHelper.createMutedTrailPolyline(mapView, trailGeoPoints, isDark = isDark).apply {
            setOnClickListener { _, _, eventPos ->
                if (eventPos != null) {
                    viewModel.addPointAtEnd(eventPos.latitude, eventPos.longitude)
                }
                true
            }
        }
        routeFolder.add(mutedLine)

        // Draw 5 km labels along the base trail
        state.baseTrailKmMarkers.forEach { kmMarker ->
            val m = Marker(mapView).apply {
                position = GeoPoint(kmMarker.lat, kmMarker.lng)
                icon = RouteMarkerHelper.createKmMarkerBitmap(context, kmMarker.label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
            routeFolder.add(m)
        }
    }

    // 1. Draw original GPX comparison line if present (thin grey)
    if (state.gpxOriginalPoints.isNotEmpty()) {
        val gpxGeoPoints = state.gpxOriginalPoints.map { GeoPoint(it.lat, it.lng) }
        val gpxLine = RouteMarkerHelper.createGpxOriginalPolyline(mapView, gpxGeoPoints)
        routeFolder.add(gpxLine)
    }

    // 2. Draw alternative routes (1-3)
    state.alternatives.forEach { alt ->
        val altGeoPoints = alt.points.map { GeoPoint(it.lat, it.lng) }
        val altLine = RouteMarkerHelper.createAlternativePolyline(mapView, altGeoPoints, alt.index)
        routeFolder.add(altLine)
    }

    // 3. Draw Planned Legs on top in the route colour (Casing first, then core stroke)
    val wps = state.waypoints
    for (i in 0 until wps.size - 1) {
        val wpFrom = wps[i]
        val wpTo = wps[i + 1]
        val legKey = "${wpFrom.id}_to_${wpTo.id}"

        val isLoading = state.loadingLegKeys.contains(legKey)
        val isFailed = state.failedLegs.containsKey(legKey)
        val legData = state.legs[legKey]

        if (isLoading) {
            val linePts = listOf(GeoPoint(wpFrom.lat, wpFrom.lng), GeoPoint(wpTo.lat, wpTo.lng))
            val loadingLine = RouteMarkerHelper.createDashedLegPolyline(mapView, linePts, isLoading = true, isDark = isDark)
            routeFolder.add(loadingLine)
        } else if (isFailed) {
            val linePts = listOf(GeoPoint(wpFrom.lat, wpFrom.lng), GeoPoint(wpTo.lat, wpTo.lng))
            val failedLine = RouteMarkerHelper.createFailedLegPolyline(mapView, linePts)
            routeFolder.add(failedLine)
        } else if (legData != null) {
            val geoPoints = legData.points.map { GeoPoint(it.lat, it.lng) }
            if (legData.legMode == LegMode.STRAIGHT) {
                val straightLine = RouteMarkerHelper.createDashedLegPolyline(mapView, geoPoints, isLoading = false, isDark = isDark)
                routeFolder.add(straightLine)
            } else {
                val casing = RouteMarkerHelper.createCasingPolyline(mapView, geoPoints, isDark = isDark)
                val core = RouteMarkerHelper.createSnappedLegPolyline(mapView, geoPoints, legData.hasAccessWarning, isDark = isDark)
                routeFolder.add(casing)
                routeFolder.add(core)
            }
        }
    }

    // 3b. Draw shelters, fri teltning areas, water, toilets and shops near the planned section (clustered below zoom 13)
    if (state.nearbyPoisForPlan.isNotEmpty()) {
        val currentZoom = mapView.zoomLevelDouble
        if (currentZoom < 13.0 && state.nearbyPoisForPlan.size > 1) {
            val cellSizeDeg = 0.04
            val clusters = state.nearbyPoisForPlan.groupBy { poi ->
                Pair((poi.lat / cellSizeDeg).toInt(), (poi.lng / cellSizeDeg).toInt())
            }
            clusters.values.forEach { group ->
                val first = group.first()
                val poiMarker = Marker(mapView).apply {
                    position = GeoPoint(first.lat, first.lng)
                    icon = RouteMarkerHelper.createNearbyPoiMarkerBitmap(
                        context = context,
                        typeKey = first.type,
                        clusterCount = group.size
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        if (group.size == 1) {
                            viewModel.selectNearbyPoiForSheet(first)
                        } else {
                            mapView.controller.animateTo(GeoPoint(first.lat, first.lng), currentZoom + 2.0, 400L)
                        }
                        true
                    }
                }
                routeFolder.add(poiMarker)
            }
        } else {
            state.nearbyPoisForPlan.forEach { poi ->
                val poiMarker = Marker(mapView).apply {
                    position = GeoPoint(poi.lat, poi.lng)
                    title = poi.name
                    icon = RouteMarkerHelper.createNearbyPoiMarkerBitmap(
                        context = context,
                        typeKey = poi.type,
                        clusterCount = 1
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        viewModel.selectNearbyPoiForSheet(poi)
                        true
                    }
                }
                routeFolder.add(poiMarker)
            }
        }
    }

    // 4. Draw Visible Waypoint Markers (skip hidden detour helper waypoints where wp.isHelper == true)
    val visibleWaypoints = wps.filter { !it.isHelper }
    visibleWaypoints.forEachIndexed { idx, wp ->
        val wpDetourDist = wp.detourDistanceKm
        val badgeText: String? = when {
            state.draggingWaypointId == wp.id && state.draggingTrailKmLabel != null -> state.draggingTrailKmLabel
            wpDetourDist != null && wpDetourDist > 0.01 -> {
                String.format(Locale.GERMANY, "+%.1f km", wpDetourDist)
            }
            wp.trailKm != null && (wp.role == WaypointRole.START || wp.role == WaypointRole.END || wp.role == WaypointRole.OVERNIGHT) -> {
                String.format(Locale.GERMANY, "km %.1f", wp.trailKm)
            }
            else -> null
        }

        val marker = Marker(mapView).apply {
            position = GeoPoint(wp.lat, wp.lng)
            icon = RouteMarkerHelper.createWaypointIcon(
                context = context,
                role = wp.role,
                index = idx,
                isSelected = wp.id == state.selectedWaypointId,
                badgeLabel = badgeText
            )
            setAnchor(
                if (badgeText != null) 0.25f else Marker.ANCHOR_CENTER,
                Marker.ANCHOR_CENTER
            )
            isDraggable = true

            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(m: Marker) {
                    val proj = viewModel.onWaypointDragging(wp.id, m.position.latitude, m.position.longitude)
                    if (proj != null) {
                        m.position = GeoPoint(proj.lat, proj.lng)
                        m.icon = RouteMarkerHelper.createWaypointIcon(
                            context = context,
                            role = wp.role,
                            index = idx,
                            isSelected = true,
                            badgeLabel = String.format(Locale.GERMANY, "km %.1f", proj.trailKm)
                        )
                        mapView.invalidate()
                    }
                }

                override fun onMarkerDragStart(m: Marker) {
                    viewModel.onWaypointDragging(wp.id, m.position.latitude, m.position.longitude)
                }

                override fun onMarkerDragEnd(m: Marker) {
                    viewModel.moveWaypoint(wp.id, m.position.latitude, m.position.longitude)
                }
            })

            setOnMarkerClickListener { _, _ ->
                onWaypointClicked(wp)
                true
            }
        }
        routeFolder.add(marker)
    }

    // 5. Draw scrubbed cursor position marker on map if active
    state.scrubbedPointOnMap?.let { scrub ->
        val cursorMarker = Marker(mapView).apply {
            position = GeoPoint(scrub.lat, scrub.lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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
        routeFolder.add(cursorMarker)
    }

    overlayManager.updateFolderOverlay(com.example.ui.navigation.MapLayersOverlayManager.KEY_ROUTE, routeFolder)
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
