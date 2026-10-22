package com.example.ui.map

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
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
import com.example.ui.components.DropPinBottomSheet
import com.example.ui.components.MapAttributionPill
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.navigation.PoiClusterOverlay
import com.example.ui.navigation.PoiItem
import com.example.ui.theme.ForestGreenPrimary
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
 * Fix Round 3, Part 3 — Requirement A:
 * Single shared osmdroid composable (`ui/map/DanskStiMap.kt`) used by all 5 map screens:
 * 1. Udforsk (`TrailBrowserScreen`)
 * 2. Trail page header (`TrailDetailScreen`)
 * 3. Ruteplanlægger (`RouteBuilderScreen`)
 * 4. Navigation (`NavigationScreen`)
 * 5. Full-screen map (`FullscreenMapScreen`)
 *
 * Responsibilities owned by `DanskStiMap`:
 * - Tile source and layers (Topografisk Skærmkortet + dæmpet dark variant, Udendørs, Luftfoto,
 *   DHM Terræn hillshade + contours, Overpass Vand water overlay, Adgang, Afmærkede ruter, 7 POI layers)
 * - Dark mode following "Kort i mørke" (`MapDarkMode`: SYSTEM / LIGHT / DARK)
 * - Lifecycle (`onResume` / `onPause` / `onDetach`)
 * - Location dot with heading cone and accuracy circle (registered under stable key `"location"`)
 * - Scale bar at bottom-left (registered under stable key `"scale_bar"`)
 * - Attribution for every active layer (bottom-left pill)
 * - Standard map buttons: Lag, Kompas/nord op, Min position
 * - Stable overlay registration (`"route"`, `"pois"`, `"access"`, `"water"`, etc.) updated in place;
 *   never calls `overlays.clear()`
 * - Long-press anywhere on any map opens the onX-style `DropPinBottomSheet`
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
    isSystemDarkOverride: Boolean? = null,
    userLocation: GeoPoint? = null,
    userHeadingDegrees: Float? = null,
    userAccuracyMeters: Float? = null,
    mapOrientationDegrees: Float? = null,
    shelters: List<ShelterEntity>? = null,
    friTeltning: List<FriTeltningEntity>? = null,
    pois: List<PoiEntity>? = null,
    customWaypoints: List<UserCustomWaypointEntity>? = null,
    routeIdForDropPinSave: Long? = null,
    controlsTopPadding: Dp = 12.dp,
    controlsBottomPadding: Dp = 12.dp,
    controlsEndPadding: Dp = 12.dp,
    attributionBottomPadding: Dp = 8.dp,
    attributionStartPadding: Dp = 10.dp,
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

    val settingsRepository = remember { SettingsRepository(context) }
    val mapLayersState by settingsRepository.mapLayersFlow.collectAsState(initial = MapLayersState())

    val configuration = LocalConfiguration.current
    val systemDark = isSystemDarkOverride
        ?: ((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    val effectiveIsDark = remember(mapLayersState.mapDarkMode, systemDark) {
        mapLayersState.resolveIsDarkMap(systemDark)
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

    // Fallback device location when screen does not pass live location
    var fallbackDeviceLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var fallbackDeviceAccuracy by remember { mutableStateOf<Float?>(null) }
    var fallbackDeviceHeading by remember { mutableStateOf<Float?>(null) }

    val refreshDeviceLocation: () -> GeoPoint? = remember(context) {
        {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (loc != null) {
                        val gp = GeoPoint(loc.latitude, loc.longitude)
                        fallbackDeviceLocation = gp
                        fallbackDeviceAccuracy = if (loc.hasAccuracy()) loc.accuracy else 20f
                        if (loc.hasBearing()) fallbackDeviceHeading = loc.bearing
                        gp
                    } else null
                } catch (_: SecurityException) {
                    null
                }
            } else null
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceLocation()
    }

    val effectiveUserLocation = userLocation ?: fallbackDeviceLocation
    val effectiveAccuracy = userAccuracyMeters ?: fallbackDeviceAccuracy ?: 20f
    val effectiveHeading = userHeadingDegrees ?: fallbackDeviceHeading

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var currentBearing by remember { mutableFloatStateOf(mapOrientationDegrees ?: 0f) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var selectedMarkedRoute by remember { mutableStateOf<MarkedRouteDto?>(null) }
    var droppedPinLocation by remember { mutableStateOf<GeoPoint?>(null) }

    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnLongPressInterceptor by rememberUpdatedState(onMapLongPressInterceptor)
    val latestOnPoiTapped by rememberUpdatedState(onPoiTapped)
    val latestOnAccessTapped by rememberUpdatedState(onAccessPolygonTapped)
    val latestOnCameraChanged by rememberUpdatedState(onCameraChanged)
    val latestOnUpdateOverlays by rememberUpdatedState(onUpdateOverlays)

    val overlayManager = remember(context) {
        MapLayersOverlayManager(
            context = context,
            scope = scope,
            onMarkedRouteTapped = { route -> selectedMarkedRoute = route },
            onPoiTapped = { item -> latestOnPoiTapped?.invoke(item) },
            onAccessPolygonTapped = { poly -> latestOnAccessTapped?.invoke(poly) }
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
            val loc = refreshDeviceLocation()
            if (loc != null) {
                mapViewRef?.controller?.animateTo(loc, 14.5, 600L)
            }
            onMyLocationClick?.invoke(loc)
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

    // Update user location dot + heading + accuracy circle in place
    LaunchedEffect(mapViewRef, effectiveUserLocation, effectiveHeading, effectiveAccuracy) {
        val map = mapViewRef ?: return@LaunchedEffect
        locationDotOverlay.updateLocation(
            location = effectiveUserLocation,
            headingDegrees = effectiveHeading,
            accuracyMeters = effectiveAccuracy
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
                title = "Valgt punkt"
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

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag(mapTestTag)
                .semantics { contentDescription = "Kort" },
            factory = { ctx ->
                MapView(ctx).apply {
                    DanskStiTileSource.configureOsmdroid(ctx)
                    DanskStiTileSource.applyBaseMapTo(this, mapLayersState.baseMap, effectiveIsDark)
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
                        accuracyMeters = effectiveAccuracy
                    )
                    overlayManager.registerOverlay(MapLayersOverlayManager.KEY_LOCATION, locationDotOverlay)

                    // 3. Register scale bar (bottom-left) under stable key "scale_bar"
                    val scaleBar = ScaleBarOverlay(this).apply {
                        setCentred(false)
                        setAlignBottom(true)
                        setAlignRight(false)
                        setScaleBarOffset(
                            (12f * density).toInt(),
                            (34f * density).toInt()
                        )
                        textPaint.color = if (effectiveIsDark) Color.WHITE else Color.BLACK
                        barPaint.color = if (effectiveIsDark) Color.WHITE else Color.BLACK
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
                // Keep scale bar readable in dark/light mode
                val sb = overlayManager.getOverlay(MapLayersOverlayManager.KEY_SCALE_BAR) as? ScaleBarOverlay
                if (sb != null) {
                    val fg = if (effectiveIsDark) Color.WHITE else Color.BLACK
                    sb.textPaint.color = fg
                    sb.barPaint.color = fg
                }
            }
        )

        // Bottom-left: Scale indicator & active layer attribution
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = attributionStartPadding, bottom = attributionBottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MapAttributionPill(
                layersState = mapLayersState,
                isDarkMode = effectiveIsDark,
                modifier = Modifier.testTag("map_attribution_pill")
            )
        }

        // Standard Map Buttons (Lag, Kompas/nord op, Min position)
        if (showStandardButtons) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = controlsTopPadding, end = controlsEndPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // 1. Lag (Layers)
                SmallFloatingActionButton(
                    onClick = { showLayersSheet = true },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    contentColor = ForestGreenPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(layersButtonTestTag)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Kortlag"
                    )
                }

                // 2. Kompas / nord op
                SmallFloatingActionButton(
                    onClick = {
                        mapViewRef?.let { mv ->
                            mv.mapOrientation = 0f
                            currentBearing = 0f
                            mv.postInvalidate()
                        }
                        onCompassClick?.invoke()
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    contentColor = if (currentBearing != 0f) ForestGreenPrimary else MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(compassButtonTestTag)
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "Kompas - nord op",
                        modifier = Modifier.rotate(-currentBearing)
                    )
                }

                // 3. Min position
                SmallFloatingActionButton(
                    onClick = {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasPerm) {
                            val target = effectiveUserLocation ?: refreshDeviceLocation()
                            if (target != null) {
                                mapViewRef?.controller?.animateTo(target, 14.5, 600L)
                            }
                            onMyLocationClick?.invoke(target)
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    contentColor = ForestGreenPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(myLocationButtonTestTag)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Min position"
                    )
                }
            }
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

    // Requirement F: Long-press anywhere on any map (onX-style "drop pin")
    droppedPinLocation?.let { pin ->
        DropPinBottomSheet(
            point = pin,
            userLocation = effectiveUserLocation,
            routeIdForSave = routeIdForDropPinSave,
            onDismiss = { droppedPinLocation = null },
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

/**
 * Renders the user's GPS position dot with a translucent accuracy circle and directional heading cone.
 */
class UserLocationHeadingOverlay : Overlay() {

    private var location: GeoPoint? = null
    private var headingDegrees: Float? = null
    private var accuracyMeters: Float = 15f

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(42, 30, 136, 229)
    }
    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(115, 30, 136, 229)
        strokeWidth = 2f
    }
    private val headingConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 30, 136, 229)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(25, 118, 210)
    }

    private val screenPt = Point()
    private val conePath = Path()

    fun updateLocation(
        location: GeoPoint?,
        headingDegrees: Float?,
        accuracyMeters: Float
    ) {
        this.location = location
        this.headingDegrees = headingDegrees
        this.accuracyMeters = accuracyMeters.coerceAtLeast(3f)
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null || !isEnabled) return
        val loc = location ?: return
        val proj = mapView.projection ?: return

        proj.toPixels(loc, screenPt)
        val cx = screenPt.x.toFloat()
        val cy = screenPt.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density

        // 1. Accuracy circle (meters -> screen pixels at current latitude & zoom)
        val metersPerPixel = proj.metersToPixels(accuracyMeters)
        if (metersPerPixel > 6f * density && metersPerPixel < canvas.width * 1.5f) {
            canvas.drawCircle(cx, cy, metersPerPixel, accuracyFillPaint)
            canvas.drawCircle(cx, cy, metersPerPixel, accuracyStrokePaint)
        }

        // 2. Heading cone if bearing is available
        val heading = headingDegrees
        if (heading != null && !heading.isNaN()) {
            val coneLen = 26f * density
            val halfSpread = 13f * density
            canvas.save()
            canvas.rotate(heading, cx, cy)
            conePath.reset()
            conePath.moveTo(cx, cy)
            conePath.lineTo(cx - halfSpread, cy - coneLen)
            conePath.lineTo(cx + halfSpread, cy - coneLen)
            conePath.close()
            canvas.drawPath(conePath, headingConePaint)
            canvas.restore()
        }

        // 3. White halo + blue location dot
        canvas.drawCircle(cx, cy, 8.5f * density, haloPaint)
        canvas.drawCircle(cx, cy, 6.2f * density, dotPaint)
    }
}
