package com.example.ui.map
import com.example.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.LocationManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.local.SettingsRepository
import com.example.data.model.FriTeltningEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.access.AccessPolygon
import com.example.ui.components.AccessPolygonRulesDialog
import com.example.ui.components.DropPinBottomSheet
import com.example.ui.components.MapAttributionPill
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.components.PoiInfoSheet
import com.example.ui.components.PoiInfoSheetData
import androidx.compose.ui.graphics.toArgb
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.navigation.PoiClusterOverlay
import com.example.ui.navigation.PoiItem
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.LightDanskStiColors
import com.example.util.DanskStiTileSourceHelper as DanskStiTileSource
import com.example.util.MapIconUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.cos

/**
 * Shared osmdroid composable (`ui/map/DanskStiMap.kt`) used by all map screens:
 * 1. Kort (`KortScreen`)
 * 2. Udforsk (`TrailBrowserScreen`)
 * 3. Trail page header (`TrailDetailScreen`)
 * 4. Ruteplanlægger (`RouteBuilderScreen`)
 * 5. Navigation (`NavigationScreen`)
 * 6. Full-screen map (`FullscreenMapScreen`)
 */
@Composable
fun DanskStiMap(
    modifier: Modifier = Modifier,
    mapTestTag: String = "dansk_sti_map",
    initialCenter: GeoPoint = GeoPoint(56.0, 10.5),
    initialZoom: Double = 7.5,
    initialBoundingBox: BoundingBox? = null,
    isInteractive: Boolean = true,
    interceptParentTouchScroll: Boolean = false,
    appIsDark: Boolean? = DanskStiTheme.isDark,
    userLocation: GeoPoint? = null,
    userHeadingDegrees: Float? = null,
    userAccuracyMeters: Float? = null,
    mapOrientationDegrees: Float? = null,
    shelters: List<ShelterEntity>? = null,
    friTeltning: List<FriTeltningEntity>? = null,
    pois: List<PoiEntity>? = null,
    customWaypoints: List<UserCustomWaypointEntity>? = null,
    routeIdForDropPinSave: Long? = null,
    controlsTopPadding: Dp = 16.dp,
    controlsBottomPadding: Dp = 16.dp,
    controlsEndPadding: Dp = 16.dp,
    attributionBottomPadding: Dp = 8.dp,
    attributionStartPadding: Dp = 10.dp,
    scaleBarBottomOffsetDp: Float = 38f,
    layersButtonTestTag: String = "layers_fab",
    compassButtonTestTag: String = "compass_reset_button",
    myLocationButtonTestTag: String = "my_location_fab",
    showStandardButtons: Boolean = true,
    onMyLocationClick: ((GeoPoint?) -> Unit)? = null,
    onCompassClick: (() -> Unit)? = null,
    onMapTap: ((GeoPoint) -> Boolean)? = null,
    onMapLongPressInterceptor: ((GeoPoint) -> Boolean)? = null,
    onAddToRouteFromDropPin: ((lat: Double, lng: Double, label: String) -> Unit)? = null,
    onRouteHereFromDropPin: ((lat: Double, lng: Double, label: String) -> Unit)? = null,
    onOvernightInPlannerFromPoiSheet: ((PoiInfoSheetData) -> Unit)? = null,
    onUseMarkedRouteInPlanner: ((MarkedRouteDto) -> Unit)? = null,
    onPoiTapped: ((PoiItem) -> Unit)? = null,
    onAccessPolygonTapped: ((AccessPolygon) -> Unit)? = null,
    onCameraChanged: ((center: GeoPoint, zoom: Double, bounds: BoundingBox?) -> Unit)? = null,
    onMapReady: ((MapView) -> Unit)? = null,
    updateOverlaysKey: Any? = Unit,
    onUpdateOverlays: ((mapView: MapView, overlayManager: MapLayersOverlayManager, isDarkMap: Boolean) -> Unit)? = null,
    overlayContent: (@Composable BoxScope.(isDarkMap: Boolean, layersState: MapLayersState) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val colorScheme = MaterialTheme.colorScheme

    val settingsRepository = remember(context) { com.example.DanskStiApp.getSettingsRepository(context) }
    val initialLayers = remember(settingsRepository) { settingsRepository.currentSettingsSnapshot().mapLayers }
    val mapLayersState by settingsRepository.mapLayersFlow.collectAsState(initial = initialLayers)

    val configuration = LocalConfiguration.current
    val systemDark = appIsDark
        ?: ((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    val resolvedAppDark = appIsDark ?: mapLayersState.resolveIsDarkApp(systemDark)
    val effectiveIsDark = remember(
        mapLayersState.mapDarkMode,
        mapLayersState.mapFollowsApp,
        mapLayersState.mapThemeOverride,
        systemDark,
        resolvedAppDark
    ) {
        mapLayersState.resolveIsDarkMap(isSystemDark = systemDark, isAppDark = resolvedAppDark)
    }
    val themeWaterColorInt = if (effectiveIsDark) {
        DanskStiTileSource.DARK_WATER_COLOR_INT
    } else {
        DanskStiTheme.colors.water.toArgb()
    }

    // Load global POIs from DB if screen didn't pass a pre-filtered list
    var dbShelters by remember { mutableStateOf<List<ShelterEntity>>(emptyList()) }
    var dbFriTeltning by remember { mutableStateOf<List<FriTeltningEntity>>(emptyList()) }
    var dbPois by remember { mutableStateOf<List<PoiEntity>>(emptyList()) }
    var dbCustomWaypoints by remember { mutableStateOf<List<UserCustomWaypointEntity>>(emptyList()) }

    LaunchedEffect(shelters, friTeltning, pois, customWaypoints) {
        if (shelters == null || friTeltning == null || pois == null || customWaypoints == null) {
            withContext(Dispatchers.IO) {
                try {
                    val dao = DanskStiDatabase.getInstance(context).dao()
                    if (shelters == null && dbShelters.isEmpty()) {
                        dbShelters = dao.getAllSheltersList()
                    }
                    if (friTeltning == null && dbFriTeltning.isEmpty()) {
                        dbFriTeltning = dao.getAllFriTeltningList()
                    }
                    if (pois == null && dbPois.isEmpty()) {
                        dbPois = dao.getAllPoisList()
                    }
                    if (customWaypoints == null && dbCustomWaypoints.isEmpty()) {
                        dbCustomWaypoints = dao.getAllCustomWaypointsList()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val effectiveShelters = shelters ?: dbShelters
    val effectiveFriTeltning = friTeltning ?: dbFriTeltning
    val effectivePois = pois ?: dbPois
    val effectiveCustomWaypoints = customWaypoints ?: dbCustomWaypoints

    // Live location updates (FusedLocationProviderClient, balanced accuracy, every 5 s while visible)
    val liveLocationState = rememberLiveMapLocationState(context)

    val effectiveUserLocation: GeoPoint? = userLocation ?: liveLocationState.geoPoint
    val effectiveAccuracy = userAccuracyMeters ?: liveLocationState.accuracyMeters ?: 20f
    val effectiveHeading = userHeadingDegrees ?: liveLocationState.headingDegrees
    val effectiveIsLocationStale = if (userLocation != null) false else liveLocationState.isStale
    val effectiveIsLocationFresh = if (userLocation != null) true else liveLocationState.isFresh

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var currentBearing by remember { mutableFloatStateOf(mapOrientationDegrees ?: 0f) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var selectedMarkedRoute by remember { mutableStateOf<MarkedRouteDto?>(null) }
    var droppedPinLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedPoiForInfoSheet by remember { mutableStateOf<PoiInfoSheetData?>(null) }
    var selectedAccessPolygonForRules by remember { mutableStateOf<AccessPolygon?>(null) }

    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnLongPressInterceptor by rememberUpdatedState(onMapLongPressInterceptor)
    val latestOnPoiTapped by rememberUpdatedState(onPoiTapped)
    val latestOnAccessTapped by rememberUpdatedState(onAccessPolygonTapped)
    val latestOnCameraChanged by rememberUpdatedState(onCameraChanged)
    val latestOnUpdateOverlays by rememberUpdatedState(onUpdateOverlays)
    val latestEffectiveShelters by rememberUpdatedState(effectiveShelters)
    val latestEffectiveFriTeltning by rememberUpdatedState(effectiveFriTeltning)
    val latestEffectivePois by rememberUpdatedState(effectivePois)

    val overlayManager = remember(context) {
        MapLayersOverlayManager(
            context = context,
            scope = scope,
            onMarkedRouteTapped = { route -> selectedMarkedRoute = route },
            onPoiTapped = { item ->
                selectedPoiForInfoSheet = PoiInfoSheetData.fromPoiItem(
                    item = item,
                    shelters = latestEffectiveShelters,
                    friTeltning = latestEffectiveFriTeltning,
                    pois = latestEffectivePois
                )
                latestOnPoiTapped?.invoke(item)
            },
            onAccessPolygonTapped = { poly ->
                selectedAccessPolygonForRules = poly
                latestOnAccessTapped?.invoke(poly)
            }
        )
    }

    val locationDotOverlay = remember {
        UserLocationHeadingOverlay()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            liveLocationState.startLocationUpdates()
            liveLocationState.requestFreshCurrentLocation { freshLoc ->
                val freshGeo = freshLoc?.let { GeoPoint(it.latitude, it.longitude) }
                if (freshGeo != null) {
                    mapViewRef?.controller?.animateTo(freshGeo, 14.5, 600L)
                }
                onMyLocationClick?.invoke(freshGeo)
            }
        }
    }

    // Lifecycle management: onResume / onPause / onDetach
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onPause()
            mapViewRef?.onDetach()
        }
    }

    // Keep layers state & dark mode synchronized
    LaunchedEffect(
        mapViewRef,
        mapLayersState,
        effectiveIsDark,
        effectiveShelters,
        effectiveFriTeltning,
        effectivePois,
        effectiveCustomWaypoints
    ) {
        val map = mapViewRef ?: return@LaunchedEffect
        overlayManager.attachToMap(map, effectiveIsDark)
        overlayManager.updateLayersState(
            state = mapLayersState,
            shelters = effectiveShelters,
            friTeltning = effectiveFriTeltning,
            pois = effectivePois,
            customWaypoints = effectiveCustomWaypoints,
            isDark = effectiveIsDark
        )
        latestOnUpdateOverlays?.invoke(map, overlayManager, effectiveIsDark)
    }

    // Update screen-specific overlays ("route", etc.) when updateOverlaysKey or dark mode changes
    LaunchedEffect(mapViewRef, updateOverlaysKey, effectiveIsDark) {
        val map = mapViewRef ?: return@LaunchedEffect
        latestOnUpdateOverlays?.invoke(map, overlayManager, effectiveIsDark)
    }

    // Update user location dot + heading + accuracy circle + stale state in place
    LaunchedEffect(mapViewRef, effectiveUserLocation, effectiveHeading, effectiveAccuracy, effectiveIsLocationStale) {
        val map = mapViewRef ?: return@LaunchedEffect
        locationDotOverlay.updateLocation(
            location = effectiveUserLocation,
            headingDegrees = effectiveHeading,
            accuracyMeters = effectiveAccuracy,
            isStale = effectiveIsLocationStale
        )
        overlayManager.registerOverlay(MapLayersOverlayManager.KEY_LOCATION, locationDotOverlay)
        map.postInvalidate()
    }

    // Update dropped pin marker under stable key "drop_pin"
    LaunchedEffect(mapViewRef, droppedPinLocation) {
        val map = mapViewRef ?: return@LaunchedEffect
        val pin = droppedPinLocation
        if (pin == null) {
            overlayManager.updateFolderOverlay(MapLayersOverlayManager.KEY_DROP_PIN, emptyList())
        } else {
            val marker = Marker(map).apply {
                position = pin
                title = context.getString(R.string.drop_pin_selected_point)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = MapIconUtils.getCustomWaypointPinIcon(context)
                setOnMarkerClickListener { _, _ -> true }
            }
            overlayManager.updateFolderOverlay(MapLayersOverlayManager.KEY_DROP_PIN, listOf(marker))
        }
    }

    // Synchronize external map orientation if provided (e.g. Navigation heading-up)
    LaunchedEffect(mapViewRef, mapOrientationDegrees) {
        val map = mapViewRef ?: return@LaunchedEffect
        if (mapOrientationDegrees != null && map.mapOrientation != mapOrientationDegrees) {
            map.mapOrientation = mapOrientationDegrees
            currentBearing = mapOrientationDegrees
            map.postInvalidate()
        }
    }

    val mapContentDesc = stringResource(R.string.nav_map)
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag(mapTestTag)
                .semantics { contentDescription = mapContentDesc },
            factory = { ctx ->
                MapView(ctx).apply {
                    tag = mapTestTag
                    DanskStiTileSource.configureOsmdroid(ctx)
                    DanskStiTileSource.applyBaseMapTo(this, mapLayersState.baseMap, effectiveIsDark)
                    setBackgroundColor(themeWaterColorInt)
                    getOverlayManager().tilesOverlay.loadingBackgroundColor = themeWaterColorInt
                    getOverlayManager().tilesOverlay.loadingLineColor = themeWaterColorInt
                    setMultiTouchControls(isInteractive)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = 6.0
                    maxZoomLevel = 20.0
                    controller.setZoom(initialZoom)
                    controller.setCenter(initialCenter)

                    if (interceptParentTouchScroll) {
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                    }

                    // 1. Register tap & long-press events overlay under stable key "events"
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p != null) {
                                return latestOnMapTap?.invoke(p) ?: false
                            }
                            return false
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null) {
                                // Round 10A Item 2: If a shelter, fri teltning area, water, toilet, or shop is
                                // within 30 m (or finger is on its marker), open its PoiInfoSheet!
                                val hitPoi = overlayManager.findLongPressPoi(p, maxDistanceMeters = 30.0)
                                if (hitPoi != null) {
                                    com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(this@apply, "poi_long_press")
                                    selectedPoiForInfoSheet = PoiInfoSheetData.fromPoiItem(
                                        item = hitPoi,
                                        shelters = latestEffectiveShelters,
                                        friTeltning = latestEffectiveFriTeltning,
                                        pois = latestEffectivePois
                                    )
                                    latestOnPoiTapped?.invoke(hitPoi)
                                    return true
                                }
                                val intercepted = latestOnLongPressInterceptor?.invoke(p) ?: false
                                if (!intercepted) {
                                    com.example.ui.components.HapticFeedbackHelper.performPinHapticTick(this@apply, "drop_pin")
                                    droppedPinLocation = p
                                }
                                return true
                            }
                            return false
                        }
                    })
                    overlayManager.attachToMap(this, effectiveIsDark)
                    overlayManager.registerOverlay(MapLayersOverlayManager.KEY_EVENTS, eventsOverlay)

                    // 2. Register location overlay under stable key "location"
                    locationDotOverlay.updateLocation(
                        location = effectiveUserLocation,
                        headingDegrees = effectiveHeading,
                        accuracyMeters = effectiveAccuracy,
                        isStale = effectiveIsLocationStale
                    )
                    overlayManager.registerOverlay(MapLayersOverlayManager.KEY_LOCATION, locationDotOverlay)

                    // 3. Register scale bar (bottom-left) under stable key "scale_bar"
                    val scaleBarFg = colorScheme.onSurface.toArgb()
                    val scaleBar = ScaleBarOverlay(this).apply {
                        setCentred(false)
                        setAlignBottom(true)
                        setAlignRight(false)
                        setScaleBarOffset(
                            (12f * density).toInt(),
                            (scaleBarBottomOffsetDp * density).toInt()
                        )
                        textPaint.color = scaleBarFg
                        barPaint.color = scaleBarFg
                    }
                    overlayManager.registerOverlay(MapLayersOverlayManager.KEY_SCALE_BAR, scaleBar)

                    // 4. Camera listener
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            currentBearing = mapOrientation
                            val c = mapCenter
                            latestOnCameraChanged?.invoke(
                                GeoPoint(c.latitude, c.longitude),
                                zoomLevelDouble,
                                boundingBox
                            )
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            currentBearing = mapOrientation
                            val c = mapCenter
                            latestOnCameraChanged?.invoke(
                                GeoPoint(c.latitude, c.longitude),
                                zoomLevelDouble,
                                boundingBox
                            )
                            return false
                        }
                    })

                    if (initialBoundingBox != null) {
                        post {
                            try {
                                zoomToBoundingBox(initialBoundingBox, false, (36 * density).toInt())
                            } catch (_: Exception) {}
                        }
                    }

                    onResume()
                    mapViewRef = this
                    onMapReady?.invoke(this)
                    latestOnUpdateOverlays?.invoke(this, overlayManager, effectiveIsDark)
                    post { overlayManager.onCameraIdle() }
                }
            },
            update = { mapView ->
                mapViewRef = mapView
                mapView.setBackgroundColor(themeWaterColorInt)
                mapView.overlayManager.tilesOverlay.loadingBackgroundColor = themeWaterColorInt
                mapView.overlayManager.tilesOverlay.loadingLineColor = themeWaterColorInt
                // Keep scale bar readable in dark/light mode and positioned above tool row if specified
                val sb = overlayManager.getOverlay(MapLayersOverlayManager.KEY_SCALE_BAR) as? ScaleBarOverlay
                if (sb != null) {
                    val fg = colorScheme.onSurface.toArgb()
                    sb.textPaint.color = fg
                    sb.barPaint.color = fg
                    sb.setScaleBarOffset(
                        (12f * density).toInt(),
                        (scaleBarBottomOffsetDp * density).toInt()
                    )
                }
            }
        )

        val isDeviceOnline = remember(context) {
            (context.applicationContext as? com.example.DanskStiApp)?.repository?.isDeviceOnline() ?: true
        }
        val layerErrorMessages by overlayManager.layerErrorMessages.collectAsState()

        DanskStiMapBottomAttributionAndBanners(
            isDeviceOnline = isDeviceOnline,
            layerErrorMessages = layerErrorMessages,
            staleText = if (userLocation != null) null else liveLocationState.staleBadgeText,
            locationErrorMessage = liveLocationState.locationErrorMessage,
            onClearLocationError = { liveLocationState.clearErrorMessage() },
            mapLayersState = mapLayersState,
            effectiveIsDark = effectiveIsDark,
            attributionStartPadding = attributionStartPadding,
            attributionBottomPadding = attributionBottomPadding
        )

        if (showStandardButtons) {
            DanskStiMapStandardButtonsColumn(
                controlsTopPadding = controlsTopPadding,
                controlsEndPadding = controlsEndPadding,
                layersButtonTestTag = layersButtonTestTag,
                compassButtonTestTag = compassButtonTestTag,
                myLocationButtonTestTag = myLocationButtonTestTag,
                currentBearing = currentBearing,
                isRequestingCurrentLocation = liveLocationState.isRequestingCurrentLocation,
                onOpenLayersSheet = { showLayersSheet = true },
                onResetCompass = {
                    mapViewRef?.let { mv ->
                        mv.mapOrientation = 0f
                        currentBearing = 0f
                        mv.postInvalidate()
                    }
                    onCompassClick?.invoke()
                },
                onMyLocationButtonClick = {
                    if (liveLocationState.hasLocationPermission()) {
                        effectiveUserLocation?.let { known ->
                            mapViewRef?.controller?.animateTo(known, 14.5, 450L)
                        }
                        liveLocationState.requestFreshCurrentLocation { freshLoc ->
                            val freshGeo = freshLoc?.let { GeoPoint(it.latitude, it.longitude) }
                            val target = freshGeo ?: effectiveUserLocation
                            if (freshGeo != null) {
                                mapViewRef?.controller?.animateTo(freshGeo, 14.5, 600L)
                            }
                            onMyLocationClick?.invoke(target)
                        }
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            )
        }

        // Screen-specific Compose overlay content
        overlayContent?.invoke(this, effectiveIsDark, mapLayersState)
    }

    // Shared Layers Bottom Sheet ("Lag")
    if (showLayersSheet) {
        MapLayersBottomSheet(
            state = mapLayersState,
            settingsRepository = settingsRepository,
            onDismiss = { showLayersSheet = false }
        )
    }

    // Shared Marked Route Detail Sheet
    selectedMarkedRoute?.let { route ->
        MarkedRouteDetailSheet(
            route = route,
            onDismiss = { selectedMarkedRoute = null },
            onUseAsBaseInRouteBuilder = { chosen ->
                selectedMarkedRoute = null
                onUseMarkedRouteInPlanner?.invoke(chosen)
            }
        )
    }

    // Shared PoiInfoSheet for all maps (Round 10A Item 1 & Item 2)
    selectedPoiForInfoSheet?.let { poiData ->
        PoiInfoSheet(
            poiData = poiData,
            liveUserLocation = effectiveUserLocation,
            isLocationFresh = effectiveIsLocationFresh,
            onDismiss = { selectedPoiForInfoSheet = null },
            onRouteHere = { lat, lng, name ->
                selectedPoiForInfoSheet = null
                (onRouteHereFromDropPin ?: onAddToRouteFromDropPin)?.invoke(lat, lng, name)
            },
            onAddToRoute = { lat, lng, name ->
                selectedPoiForInfoSheet = null
                (onAddToRouteFromDropPin ?: onRouteHereFromDropPin)?.invoke(lat, lng, name)
            },
            onOvernightHereInPlanner = onOvernightInPlannerFromPoiSheet?.let { cb ->
                {
                    selectedPoiForInfoSheet = null
                    cb(poiData)
                }
            },
            onSavedPoint = { savedWp ->
                dbCustomWaypoints = dbCustomWaypoints + savedWp
            }
        )
    }

    // Shared AccessPolygonRulesDialog for all maps (Round 10A Item 1)
    selectedAccessPolygonForRules?.let { poly ->
        AccessPolygonRulesDialog(
            polygon = poly,
            onDismiss = { selectedAccessPolygonForRules = null }
        )
    }

    // Requirement F: Long-press anywhere on any map (onX-style "drop pin")
    droppedPinLocation?.let { pin ->
        DropPinBottomSheet(
            point = pin,
            userLocation = effectiveUserLocation,
            isLocationFresh = effectiveIsLocationFresh,
            routeIdForSave = routeIdForDropPinSave,
            onDismiss = { droppedPinLocation = null },
            onAddToRoute = onAddToRouteFromDropPin?.let { cb ->
                { lat, lng, label ->
                    droppedPinLocation = null
                    cb(lat, lng, label)
                }
            },
            onRouteHere = { lat, lng, label ->
                droppedPinLocation = null
                (onRouteHereFromDropPin ?: onAddToRouteFromDropPin)?.invoke(lat, lng, label)
            },
            onPointSaved = { savedWp ->
                dbCustomWaypoints = dbCustomWaypoints + savedWp
            }
        )
    }
}

