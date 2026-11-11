package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DanskStiApp
import com.example.R
import com.example.data.local.SettingsRepository
import com.example.data.model.MarkedRouteDto
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.screens.planner.CrosshairPlacementOverlay
import com.example.ui.screens.planner.KigTegnSegmentedToggle
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.screens.planner.PlannerCoachMarksOverlay
import com.example.ui.screens.planner.RouteBuilderPinDragOverlay
import com.example.ui.screens.planner.TalkBackRouteSemanticsOverlay
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import java.io.FileOutputStream

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
    val downloadProgressMap by viewModel.downloadProgress.collectAsState()
    val activeDownloadProgress = uiState.baseTrailId?.let { downloadProgressMap[it] }
    val coroutineScope = rememberCoroutineScope()
    val isDark = DanskStiTheme.isDark
    val windowSizeClass = DanskStiTheme.windowSizeClass
    val settingsRepository = remember(context) { com.example.DanskStiApp.getSettingsRepository(context) }
    val layersState by settingsRepository.mapLayersFlow.collectAsState(initial = settingsRepository.currentSettingsSnapshot().mapLayers)
    var selectedMarkedRoute by remember { mutableStateOf<MarkedRouteDto?>(null) }

    var showClearDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameRouteName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRenameWaypointDialog by remember { mutableStateOf(false) }
    var renameWaypointTargetId by remember { mutableStateOf<String?>(null) }
    var renameWaypointText by remember { mutableStateOf("") }
    var showPlannerMenu by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var showHowToGuide by remember { mutableStateOf(false) }
    var hasZoomedToInitialRoute by remember { mutableStateOf(false) }

    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    val visibleWaypoints = remember(uiState.waypoints) {
        uiState.waypoints.filter { !it.isHelper }
    }

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
    var pendingCameraPhotoPath by remember { mutableStateOf<String?>(null) }

    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val path = pendingCameraPhotoPath
        pendingCameraPhotoPath = null
        if (saved && !path.isNullOrBlank()) {
            try {
                val photoFile = File(path)
                if (com.example.util.WaypointPhotoHelper.compressCapturedPhotoFile(photoFile)) {
                    customWpPhotoPath = photoFile.absolutePath
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Kunne ikke gemme foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else if (!path.isNullOrBlank()) {
            runCatching { File(path).delete() }
        }
    }

    val galleryPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val photoFile = com.example.util.WaypointPhotoHelper.createPhotoFile(context)
                    FileOutputStream(photoFile).use { out ->
                        input.copyTo(out)
                    }
                    com.example.util.WaypointPhotoHelper.compressCapturedPhotoFile(photoFile)
                    customWpPhotoPath = photoFile.absolutePath
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Kunne ikke indlæse foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(routeId, baseTrailId, existingPlanId, destLat, destLng) {
        val lastKnown = getLastKnownLocation(context)
        val res = viewModel.resolveCurrentOrFallbackLocation(lastKnown)
        val loc = res.location ?: lastKnown
        val staleMsg = res.staleMessage ?: loc?.time?.takeIf { it > 0L }?.let {
            com.example.ui.map.LiveMapLocationState.formatOldFixMessage(it)
        }
        viewModel.initialize(
            routeId = routeId,
            destLat = destLat,
            destLng = destLng,
            destName = destName,
            gpsLat = loc?.latitude,
            gpsLng = loc?.longitude,
            staleLocationMessage = staleMsg,
            baseTrailId = baseTrailId,
            existingPlanId = existingPlanId
        )
    }

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

    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { msg ->
            withContext(Dispatchers.Main) {
                if (android.os.Looper.myLooper() != null) {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(uiState.mapViewportTarget, uiState.selectedDayNumber, mapViewInstance) {
        val map = mapViewInstance ?: return@LaunchedEffect
        val target = uiState.mapViewportTarget ?: return@LaunchedEffect
        val dayNum = uiState.selectedDayNumber
        map.post {
            try {
                val sectionPts = if (dayNum != null) viewModel.getPointsForDaySection(dayNum) else emptyList()
                if (sectionPts.size >= 2) {
                    val minLat = sectionPts.minOf { it.lat }
                    val maxLat = sectionPts.maxOf { it.lat }
                    val minLng = sectionPts.minOf { it.lng }
                    val maxLng = sectionPts.maxOf { it.lng }
                    val latSpan = (maxLat - minLat).coerceAtLeast(0.01)
                    val sheetRatio = uiState.sheetHeightFraction.coerceIn(0.18f, 0.60f)
                    val extraSouth = latSpan * (sheetRatio / (1f - sheetRatio)).coerceIn(0.25f, 1.5f)
                    val box = BoundingBox(
                        maxLat + latSpan * 0.15,
                        maxLng + 0.01,
                        (minLat - extraSouth).coerceAtLeast(-85.0),
                        minLng - 0.01
                    )
                    map.zoomToBoundingBox(box, true)
                } else {
                    val zoom = map.zoomLevelDouble.coerceAtLeast(14.0)
                    map.controller.animateTo(GeoPoint(target.lat, target.lng), zoom, 350L)
                }
            } catch (_: Exception) {
                map.controller.setCenter(GeoPoint(target.lat, target.lng))
            }
        }
    }

    var overlayManagerInstance by remember { mutableStateOf<MapLayersOverlayManager?>(null) }
    var currentEffectiveDarkMap by remember { mutableStateOf(isDark) }

    LaunchedEffect(
        uiState.waypoints,
        uiState.legs,
        uiState.loadingLegKeys,
        uiState.failedLegs,
        uiState.alternatives,
        uiState.legAlternatives,
        uiState.selectedWaypointId,
        uiState.selectedLegKey,
        uiState.gpxOriginalPoints,
        uiState.scrubbedPointOnMap,
        uiState.baseTrailPoints,
        uiState.baseTrailKmMarkers,
        uiState.nearbyPoisForPlan,
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
                viewModel.selectWaypoint(wp.id)
            }
        )
    }

    val localView = LocalView.current

    val openSaveDialogLambda = {
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
    }

    val handleBackPress = {
        when {
            showHowToGuide -> showHowToGuide = false
            uiState.isCrosshairMode -> viewModel.cancelCrosshairMode()
            uiState.selectedWaypointId != null || uiState.selectedLegKey != null -> {
                viewModel.selectWaypoint(null)
                viewModel.selectLeg(null)
            }
            !windowSizeClass.usePlannerSidePanel && uiState.sheetHeightState != SheetHeightState.PEEK -> {
                viewModel.setSheetHeightState(SheetHeightState.PEEK)
            }
            uiState.hasUnsavedChanges -> {
                viewModel.setShowUnsavedChangesDialog(true)
            }
            else -> onBack()
        }
    }

    BackHandler(onBack = handleBackPress)

    if (showHowToGuide) {
        HowToGuideScreen(
            initialSection = 1,
            onDismiss = { showHowToGuide = false }
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RouteBuilderTopAppBar(
                uiState = uiState,
                visibleWaypoints = visibleWaypoints,
                showPlannerMenu = showPlannerMenu,
                onSetShowPlannerMenu = { showPlannerMenu = it },
                onOpenRenameDialog = {
                    renameRouteName = uiState.routeName.ifBlank {
                        if (uiState.mode == PlannerMode.TRAIL) uiState.baseTrail?.name ?: "Vandrerute" else "Min rute"
                    }
                    showRenameDialog = true
                },
                onBack = handleBackPress,
                onOpenTripSettings = { viewModel.openTripSettingsSheet() },
                onOpenLayersSheet = { showLayersSheet = true },
                onLaunchGpxImport = {
                    gpxPickerLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
                },
                onStartFromMyLocation = {
                    HapticFeedbackHelper.performPinHapticTick(localView, "gps_start")
                    viewModel.startFromMyLocation()
                },
                onCloseRouteLoop = { viewModel.closeRouteLoop() },
                onAppendOutAndBack = { viewModel.appendOutAndBack() },
                onOpenHowToGuide = { showHowToGuide = true }
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            val availableHeightDp = maxHeight.value
            val useSidePanel = windowSizeClass.usePlannerSidePanel || (maxHeight < 480.dp && maxWidth > maxHeight) || maxWidth > 840.dp

            val plannerSheetComposable: @Composable (Modifier, Boolean) -> Unit = { sheetModifier, isSidePanelMode ->
                PlannerBottomSheet(
                    uiState = uiState,
                    visibleWaypoints = visibleWaypoints,
                    onSetSheetHeightState = { viewModel.setSheetHeightState(it) },
                    onSuggestOvernightStops = { viewModel.suggestOvernightStops() },
                    onStartNavigation = {
                        viewModel.saveAndStartNavigation { navId ->
                            onStartNavigation(navId)
                        }
                    },
                    onOpenTripSettings = { viewModel.openTripSettingsSheet() },
                    onOpenRundturDialog = { viewModel.openRundturDialog() },
                    onOpenNearestPoiDialog = {
                        val center = mapViewInstance?.mapCenter
                        viewModel.openNearestPoiDialog(
                            mapCenterLat = center?.latitude,
                            mapCenterLng = center?.longitude
                        )
                    },
                    onShareRoute = { viewModel.shareRoute(context) },
                    onReplaceOvernight = { wpId -> viewModel.openReplaceOvernightDialog(wpId) },
                    onDeleteWaypoint = { wpId -> viewModel.deleteWaypointWithUndoSnackbar(wpId) },
                    onZoomToCoordinate = { lat, lng ->
                        mapViewInstance?.controller?.animateTo(GeoPoint(lat, lng), 15.0, 500L)
                    },
                    onRerouteAroundWarning = { warning -> viewModel.rerouteAroundPolygon(warning) },
                    onScrubbedPointChanged = { pt -> viewModel.setScrubbedPoint(pt) },
                    onOvernightMarkerDragged = { wpId, targetWalkKm ->
                        viewModel.moveOvernightPinByChartWalkKm(wpId, targetWalkKm)
                    },
                    onUpdateOptions = { newOptions -> viewModel.updateOptions(newOptions) },
                    onWaypointClicked = { wp ->
                        viewModel.selectWaypoint(wp.id)
                    },
                    onOpenSaveDialog = openSaveDialogLambda,
                    onSelectSheetTab = { tab -> viewModel.setSelectedSheetTab(tab) },
                    onSheetHeightFractionChanged = { fraction -> viewModel.setSheetHeightFraction(fraction) },
                    onReorderWaypoints = { fromIdx, toIdx -> viewModel.reorderWaypoints(fromIdx, toIdx) },
                    onDayCardClicked = { day -> viewModel.selectDaySection(day.dayNumber) },
                    onCustomWaypointClicked = { wp -> viewModel.selectCustomWaypointForDetails(wp) },
                    onDeleteCustomWaypoint = { wpId -> viewModel.deleteCustomWaypoint(wpId) },
                    onDeleteCustomWaypointPhoto = { wpId -> viewModel.deleteCustomWaypointPhoto(wpId) },
                    availableHeightDp = availableHeightDp,
                    isSidePanel = isSidePanelMode,
                    modifier = sheetModifier
                )
            }

            val mapAndOverlaysSection: @Composable (Modifier, Boolean) -> Unit = { mapBoxModifier, isSidePanelMode ->
                Box(modifier = mapBoxModifier) {
                    DanskStiMap(
                        modifier = Modifier.fillMaxSize(),
                        mapTestTag = "route_builder_map",
                        initialZoom = 7.5,
                        appIsDark = isDark,
                        customWaypoints = uiState.customUserWaypoints,
                        routeIdForDropPinSave = uiState.routeId,
                        controlsTopPadding = 16.dp,
                        controlsEndPadding = 16.dp,
                        attributionBottomPadding = if (isSidePanelMode) {
                            76.dp
                        } else {
                            when (uiState.sheetHeightState) {
                                SheetHeightState.PEEK -> (availableHeightDp * 0.24f).dp
                                SheetHeightState.HALF -> (availableHeightDp * 0.44f).dp
                                SheetHeightState.FULL -> (availableHeightDp * 0.62f).dp
                            }
                        },
                        layersButtonTestTag = "route_builder_layers_button",
                        compassButtonTestTag = "route_builder_compass_button",
                        myLocationButtonTestTag = "btn_my_location",
                        onMyLocationClick = { loc ->
                            // Round 12A Item 4: ONLY centre the map on my position. Never change waypoints.
                            if (loc != null) {
                                viewModel.setCurrentPosition(loc.latitude, loc.longitude)
                                mapViewInstance?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude), 14.5, 500L)
                            } else {
                                val lastLoc = getLastKnownLocation(context)
                                if (lastLoc != null) {
                                    viewModel.setCurrentPosition(lastLoc.latitude, lastLoc.longitude)
                                    mapViewInstance?.controller?.animateTo(GeoPoint(lastLoc.latitude, lastLoc.longitude), 14.5, 500L)
                                } else {
                                    Toast.makeText(context, "GPS-position ikke tilgængelig", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onMapTap = { p ->
                            if (uiState.interactionMode == PlannerInteractionMode.TEGN) {
                                HapticFeedbackHelper.performPinHapticTick(localView, "add_pin")
                            }
                            viewModel.onMapTapped(p.latitude, p.longitude)
                            true
                        },
                        onAddToRouteFromDropPin = { lat, lng, label ->
                            HapticFeedbackHelper.performPinHapticTick(localView, "drop_pin_add")
                            viewModel.addPointAtEnd(lat, lng, label)
                        },
                        onRouteHereFromDropPin = { lat, lng, label ->
                            HapticFeedbackHelper.performPinHapticTick(localView, "drop_pin_snap")
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
                        onStartFromMyLocationFromDropPin = {
                            HapticFeedbackHelper.performPinHapticTick(localView, "gps_start")
                            viewModel.startFromMyLocation()
                        },
                        onOvernightInPlannerFromPoiSheet = { poiData ->
                            viewModel.addDetourPoiToRoute(
                                lat = poiData.lat,
                                lng = poiData.lng,
                                name = poiData.name,
                                role = WaypointRole.OVERNIGHT,
                                poiId = poiData.id,
                                poiType = poiData.rawType
                            )
                        },
                        onPoiTapped = {
                            viewModel.selectNearbyPoiForSheet(null)
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
                            val map = mapViewInstance
                            val mgr = overlayManagerInstance
                            if (map != null && mgr != null) {
                                refreshMapOverlays(
                                    mapView = map,
                                    overlayManager = mgr,
                                    isDark = currentEffectiveDarkMap,
                                    context = context,
                                    state = uiState,
                                    viewModel = viewModel,
                                    onWaypointClicked = { wp ->
                                        viewModel.selectWaypoint(wp.id)
                                    }
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
                        updateOverlaysKey = Triple(uiState.waypoints, uiState.legs, Pair(uiState.selectedWaypointId, uiState.selectedLegKey)),
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
                                    viewModel.selectWaypoint(wp.id)
                                }
                            )
                        }
                    )

                    TalkBackRouteSemanticsOverlay(
                        uiState = uiState,
                        onSelectWaypoint = { wpId -> viewModel.selectWaypoint(wpId) },
                        onSelectLeg = { legKey -> viewModel.selectLeg(legKey) }
                    )

                    RouteBuilderPinDragOverlay(
                        mapView = mapViewInstance,
                        overlayManager = overlayManagerInstance,
                        uiState = uiState,
                        viewModel = viewModel
                    )

                    CrosshairPlacementOverlay(
                        isCrosshairMode = uiState.isCrosshairMode,
                        onOpenCrosshair = { viewModel.openCrosshairMode() },
                        onConfirmHere = {
                            val center = mapViewInstance?.mapCenter
                            if (center != null) {
                                val snap = viewModel.confirmCrosshairPlacement(center.latitude, center.longitude)
                                if (snap.snapped) {
                                    HapticFeedbackHelper.performPinHapticTick(localView, "crosshair_snap")
                                } else {
                                    HapticFeedbackHelper.performPinHapticTick(localView, "crosshair_add")
                                }
                            }
                        },
                        onCancel = { viewModel.cancelCrosshairMode() }
                    )

                    KigTegnSegmentedToggle(
                        interactionMode = uiState.interactionMode,
                        onModeChange = { viewModel.setInteractionMode(it) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 16.dp)
                    )

                    uiState.draggingTrailKmLabel?.let { kmLabel ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primary,
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
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = kmLabel,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    RouteBuilderTopSearchAndBannersOverlay(
                        uiState = uiState,
                        activeDownloadProgress = activeDownloadProgress,
                        context = context,
                        viewModel = viewModel,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 72.dp, top = 72.dp)
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 194.dp)
                            .testTag("planner_own_buttons_column"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                            contentColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_add_custom_waypoint")
                        ) {
                            Icon(Icons.Default.Place, contentDescription = stringResource(R.string.ui_routebuilderscreen_tilf_j_mit_punkt_111), modifier = Modifier.size(20.dp))
                        }

                        if (visibleWaypoints.size >= 2) {
                            FloatingActionButton(
                                onClick = {
                                    val map = mapViewInstance ?: return@FloatingActionButton
                                    zoomToRoute(map, visibleWaypoints)
                                },
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("btn_recenter")
                            ) {
                                Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.ui_routebuilderscreen_zoom_til_rute_110), modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    PlannerCoachMarksOverlay(
                        uiState = uiState,
                        onNextStep = { viewModel.nextCoachMarkStep() },
                        onDismiss = { viewModel.dismissCoachMarks() }
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .then(if (isSidePanelMode) Modifier.navigationBarsPadding() else Modifier)
                    ) {
                        RouteBuilderBottomSelectionAndSnackbarStack(
                            uiState = uiState,
                            visibleWaypoints = visibleWaypoints,
                            viewModel = viewModel,
                            onRenameWaypointRequest = { wpId, currentName ->
                                renameWaypointTargetId = wpId
                                renameWaypointText = currentName
                                showRenameWaypointDialog = true
                            }
                        )

                        RouteBuilderFloatingToolbar(
                            uiState = uiState,
                            visibleWaypoints = visibleWaypoints,
                            viewModel = viewModel,
                            onOpenSaveDialog = openSaveDialogLambda
                        )

                        if (!isSidePanelMode) {
                            plannerSheetComposable(Modifier, false)
                        }
                    }
                }
            }

            if (useSidePanel) {
                Row(modifier = Modifier.fillMaxSize().testTag("route_builder_side_panel_row")) {
                    plannerSheetComposable(Modifier.width(380.dp).widthIn(min = 360.dp, max = 420.dp).fillMaxHeight(), true)
                    mapAndOverlaysSection(Modifier.weight(1f).fillMaxHeight(), true)
                }
            } else {
                mapAndOverlaysSection(Modifier.fillMaxSize(), false)
            }

            RouteBuilderBasicDialogs(
                uiState = uiState, viewModel = viewModel,
                showClearDialog = showClearDialog, onDismissClearDialog = { showClearDialog = false },
                showSaveDialog = showSaveDialog, onDismissSaveDialog = { showSaveDialog = false },
                renameRouteName = renameRouteName, onRenameRouteNameChange = { renameRouteName = it },
                onSavedSuccessfully = onSavedSuccessfully,
                showRenameDialog = showRenameDialog, onDismissRenameDialog = { showRenameDialog = false },
                showRenameWaypointDialog = showRenameWaypointDialog, renameWaypointTargetId = renameWaypointTargetId,
                renameWaypointText = renameWaypointText, onRenameWaypointTextChange = { renameWaypointText = it },
                onDismissRenameWaypointDialog = { showRenameWaypointDialog = false; renameWaypointTargetId = null },
                availableHeightDp = availableHeightDp
            )

            PlannerUnsavedChangesDialog(
                show = uiState.showUnsavedChangesDialog,
                onSave = {
                    viewModel.setShowUnsavedChangesDialog(false)
                    if (uiState.waypoints.size >= 2) viewModel.saveRoute(onSuccess = { onSavedSuccessfully() }) else openSaveDialogLambda()
                },
                onDiscard = { viewModel.discardDraft(); onBack() },
                onContinueEditing = { viewModel.setShowUnsavedChangesDialog(false) }
            )
            RouteBuilderFeatureDialogsHost(
                uiState = uiState, viewModel = viewModel, context = context, mapViewInstance = mapViewInstance,
                customWpName = customWpName, onCustomWpNameChange = { customWpName = it },
                customWpNote = customWpNote, onCustomWpNoteChange = { customWpNote = it },
                customWpPhotoPath = customWpPhotoPath, onCustomWpPhotoPathChange = { customWpPhotoPath = it },
                onLaunchCamera = {
                    try {
                        val (photoFile, photoUri) = com.example.util.WaypointPhotoHelper.createPhotoCaptureTarget(context)
                        pendingCameraPhotoPath = photoFile.absolutePath
                        cameraPhotoLauncher.launch(photoUri)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Kunne ikke åbne kamera: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }, onLaunchGallery = { galleryPhotoLauncher.launch("image/*") },
                availableHeightDp = availableHeightDp
            )
        }
    }
    if (showLayersSheet) {
        MapLayersBottomSheet(state = layersState, settingsRepository = settingsRepository, onDismiss = { showLayersSheet = false })
    }
    selectedMarkedRoute?.let { markedRoute ->
        val app = context.applicationContext as DanskStiApp
        MarkedRouteDetailSheet(
            route = markedRoute,
            onDismiss = { selectedMarkedRoute = null },
            onUseAsBaseInRouteBuilder = { r ->
                coroutineScope.launch { app.repository.saveMarkedRouteAsTrail(r) }
                selectedMarkedRoute = null
            }
        )
    }
}
