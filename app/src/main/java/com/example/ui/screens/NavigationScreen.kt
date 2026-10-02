package com.example.ui.screens

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.GeoPointDto
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.components.NearestPoiOverlayCard
import com.example.ui.components.OffTrailBanner
import com.example.ui.components.PoiDetailBottomSheet
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGlass
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.ShelterBrown
import com.example.util.GeoUtils
import com.example.util.MapIconUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun NavigationScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val navState by viewModel.navLiveState.collectAsState()
    val trailPoints by viewModel.selectedTrailPoints.collectAsState()
    val trail by viewModel.selectedTrail.collectAsState()
    val shelters by viewModel.corridorShelters.collectAsState()
    val friTeltnings by viewModel.corridorFriTeltning.collectAsState()
    val pois by viewModel.corridorPois.collectAsState()

    val showShelters by viewModel.showShelters.collectAsState()
    val showFriTeltning by viewModel.showFriTeltning.collectAsState()
    val showToilets by viewModel.showToilets.collectAsState()
    val showWater by viewModel.showWater.collectAsState()

    val selectedPoi by viewModel.selectedPoiDetails.collectAsState()
    val nearestPoi by viewModel.nearestPoiResult.collectAsState()
    val weather by viewModel.trailWeather.collectAsState()
    val isBatterySaverMode by viewModel.isBatterySaverMode.collectAsState()
    val showResupply by viewModel.showResupply.collectAsState()
    val resupplyPoints by viewModel.corridorResupply.collectAsState()
    val showTransit by viewModel.showTransit.collectAsState()
    val transitStations by viewModel.corridorTransit.collectAsState()
    val showHazards by viewModel.showHazards.collectAsState()
    val hazards by viewModel.corridorHazards.collectAsState()
    val emergencyInfo by viewModel.emergencyInfo.collectAsState()

    val startKm by viewModel.plannerStartKm.collectAsState()
    val endKm by viewModel.plannerEndKm.collectAsState()
    val stages by viewModel.plannerStages.collectAsState()
    val activePlan by viewModel.activeTripPlan.collectAsState()

    val currentDay = navState.currentDay.coerceAtLeast(1)
    val currentStage = navState.currentStage ?: stages.getOrNull(currentDay - 1) ?: stages.firstOrNull()

    val effectiveStartKm = activePlan?.startKm ?: startKm
    val effectiveEndKm = activePlan?.endKm ?: endKm

    val stageStartKm = currentStage?.startKm ?: effectiveStartKm
    val stageEndKm = currentStage?.endKm ?: effectiveEndKm

    // Selected corridor of the entire planned multi-day journey
    val userSelectedTripPoints = remember(trailPoints, effectiveStartKm, effectiveEndKm) {
        if (trailPoints.isEmpty()) emptyList()
        else GeoUtils.slicePolylineKm(trailPoints, effectiveStartKm, effectiveEndKm)
    }

    // TODAY'S ACTIVE STAGE that the hiker must walk today
    val todayStagePoints = remember(trailPoints, stageStartKm, stageEndKm) {
        if (trailPoints.isEmpty()) emptyList()
        else GeoUtils.slicePolylineKm(trailPoints, stageStartKm, stageEndKm)
    }

    val tonightCampLat = currentStage?.stopLat ?: todayStagePoints.lastOrNull()?.lat ?: 55.0
    val tonightCampLng = currentStage?.stopLng ?: todayStagePoints.lastOrNull()?.lng ?: 10.0
    val tonightCampName = currentStage?.stopName ?: "Lejrplads for natten"
    val tonightCampType = currentStage?.stopType ?: "SHELTER"
    val tonightBookingRequired = currentStage?.bookingRequired == true

    val context = LocalContext.current

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var followUserLocation by remember { mutableStateOf(false) }
    var isDraggingMap by remember { mutableStateOf(false) }
    var isHudExpanded by remember { mutableStateOf(false) }
    var hasFramedTodayStage by remember { mutableStateOf(false) }

    // Automatically frame map to today's stage when starting navigation
    LaunchedEffect(todayStagePoints, userSelectedTripPoints) {
        val ptsToFrame = if (todayStagePoints.isNotEmpty()) todayStagePoints else userSelectedTripPoints
        if (ptsToFrame.isNotEmpty() && !hasFramedTodayStage) {
            val lats = ptsToFrame.map { it.lat }
            val lngs = ptsToFrame.map { it.lng }
            val minLat = lats.minOrNull() ?: return@LaunchedEffect
            val maxLat = lats.maxOrNull() ?: return@LaunchedEffect
            val minLng = lngs.minOrNull() ?: return@LaunchedEffect
            val maxLng = lngs.maxOrNull() ?: return@LaunchedEffect
            val box = org.osmdroid.util.BoundingBox(
                maxLat + 0.008,
                maxLng + 0.008,
                minLat - 0.008,
                minLng - 0.008
            )
            mapViewRef?.post {
                try {
                    mapViewRef?.zoomToBoundingBox(box, true, 90)
                    hasFramedTodayStage = true
                } catch (e: Exception) {
                    mapViewRef?.controller?.setCenter(GeoPoint(ptsToFrame.first().lat, ptsToFrame.first().lng))
                    mapViewRef?.controller?.setZoom(14.0)
                }
            }
        }
    }

    // Pre-parse Fri Teltning polygon boundaries to prevent JSON deserialization on every GPS tick
    val parsedFriTeltningPolygons = remember(friTeltnings) {
        val moshi = Moshi.Builder().build()
        val ptType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
        val adapter = moshi.adapter<List<GeoPointDto>>(ptType)
        friTeltnings.map { ft ->
            val pts = try {
                adapter.fromJson(ft.polygonJson) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            ft to pts
        }
    }

    BackHandler {
        viewModel.navigateTo(AppScreen.TRAIL_DETAIL)
    }

    Box(modifier = modifier.fillMaxSize().background(ForestDarkBg)) {
        // 1. Full-screen Native OpenStreetMap View
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("navigation_map_view"),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    val initialCenter = todayStagePoints.firstOrNull()
                        ?: userSelectedTripPoints.firstOrNull()
                        ?: navState.currentSnappedPoint
                        ?: GeoPointDto(navState.currentLat, navState.currentLng)
                    controller.setZoom(14.5)
                    controller.setCenter(GeoPoint(initialCenter.lat, initialCenter.lng))

                    // Releasing auto-recenter lock immediately when user touches or drags the map
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                isDraggingMap = true
                                if (followUserLocation) {
                                    followUserLocation = false
                                }
                                controller.stopAnimation(false)
                                controller.stopPanning()
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                if (followUserLocation) {
                                    followUserLocation = false
                                }
                                controller.stopAnimation(false)
                                controller.stopPanning()
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL,
                            android.view.MotionEvent.ACTION_POINTER_UP -> {
                                isDraggingMap = false
                            }
                        }
                        false
                    }

                    mapViewRef = this
                }
            },
            update = { mapView ->
                // If user is currently dragging the map, do not destroy overlays to prevent stuttering
                if (isDraggingMap) {
                    val userMarker = mapView.overlays.filterIsInstance<Marker>().find { it.title == "Din position" }
                    if (userMarker != null) {
                        userMarker.position = GeoPoint(navState.currentLat, navState.currentLng)
                        userMarker.rotation = navState.bearing
                        mapView.invalidate()
                    }
                    return@AndroidView
                }

                mapView.overlays.clear()

                // Overlay that detects user drag/pan/fling gestures and disables auto-centering
                val dragDetectorOverlay = object : org.osmdroid.views.overlay.Overlay() {
                    override fun onScroll(pEvent1: android.view.MotionEvent?, pEvent2: android.view.MotionEvent?, pDistanceX: Float, pDistanceY: Float, pMapView: MapView?): Boolean {
                        if (followUserLocation) {
                            followUserLocation = false
                            pMapView?.controller?.stopAnimation(false)
                            pMapView?.controller?.stopPanning()
                        }
                        return false // Do not consume event; let MapView scroll smoothly!
                    }

                    override fun onFling(pEvent1: android.view.MotionEvent?, pEvent2: android.view.MotionEvent?, pVelocityX: Float, pVelocityY: Float, pMapView: MapView?): Boolean {
                        if (followUserLocation) {
                            followUserLocation = false
                            pMapView?.controller?.stopAnimation(false)
                            pMapView?.controller?.stopPanning()
                        }
                        return false // Do not consume event; let MapView fling smoothly!
                    }
                }
                mapView.overlays.add(dragDetectorOverlay)

                // 1. Draw Fri Teltning Green Polygons & Tent Pins
                if (showFriTeltning) {
                    parsedFriTeltningPolygons.forEach { (ft, pts) ->
                        val polygonPts = if (pts.size >= 3) {
                            pts.map { GeoPoint(it.lat, it.lng) }
                        } else {
                            Polygon.pointsAsCircle(GeoPoint(ft.centerLat, ft.centerLng), 600.0)
                        }
                        val polygon = Polygon(mapView).apply {
                            fillPaint.color = AndroidColor.parseColor("#4D059669")
                            outlinePaint.color = AndroidColor.parseColor("#9910B981")
                            outlinePaint.strokeWidth = 3f
                            points = polygonPts
                            title = ft.name
                            subDescription = "${ft.zoneType} • ${ft.rulesSummary}"
                            setOnClickListener { _, _, _ ->
                                viewModel.selectPoiForDetails(ft)
                                true
                            }
                        }
                        mapView.overlays.add(polygon)

                        val tentMarker = Marker(mapView).apply {
                            position = GeoPoint(ft.centerLat, ft.centerLng)
                            icon = MapIconUtils.getTentIcon(mapView.context)
                            title = ft.name
                            subDescription = "${ft.zoneType} • ${ft.rulesSummary}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(ft)
                                true
                            }
                        }
                        mapView.overlays.add(tentMarker)
                    }
                }

                // 2. Draw Route Polylines: Selected Multi-Day Corridor & Today's Active Stage
                if (trailPoints.isNotEmpty()) {
                    // Full Multi-Day Selected Route Corridor (Soft Forest Slate)
                    if (userSelectedTripPoints.size >= 2) {
                        val corridorLine = Polyline(mapView).apply {
                            outlinePaint.color = AndroidColor.parseColor("#385E46")
                            outlinePaint.strokeWidth = 7f
                            setPoints(userSelectedTripPoints.map { GeoPoint(it.lat, it.lng) })
                        }
                        mapView.overlays.add(corridorLine)
                    }

                    // TODAY'S ACTIVE STAGE (High-Contrast Live Navigation Polyline)
                    val activePoints = if (todayStagePoints.isNotEmpty()) todayStagePoints else userSelectedTripPoints
                    if (activePoints.isNotEmpty()) {
                        val userSnapped = navState.currentSnappedPoint
                        var splitIdx = 0
                        if (userSnapped != null) {
                            var minDist = Double.MAX_VALUE
                            var closestIdx = 0
                            activePoints.forEachIndexed { i, pt ->
                                val d = GeoUtils.distanceMeters(userSnapped, pt)
                                if (d < minDist) {
                                    minDist = d
                                    closestIdx = i
                                }
                            }
                            if (minDist <= 1000.0) {
                                splitIdx = closestIdx
                            }
                        }

                        // Completed path today (Forest Green)
                        if (splitIdx > 0) {
                            val completedPts = activePoints.take(splitIdx + 1).map { GeoPoint(it.lat, it.lng) }
                            val completedLine = Polyline(mapView).apply {
                                outlinePaint.color = AndroidColor.parseColor("#2E7D32")
                                outlinePaint.strokeWidth = 11f
                                setPoints(completedPts)
                            }
                            mapView.overlays.add(completedLine)
                        }

                        // Remaining path for today (Vibrant High-Contrast Glowing Amber)
                        val remainingPts = activePoints.drop(splitIdx).map { GeoPoint(it.lat, it.lng) }
                        if (remainingPts.size >= 2) {
                            val remainingLine = Polyline(mapView).apply {
                                outlinePaint.color = AndroidColor.parseColor("#F59E0B")
                                outlinePaint.strokeWidth = 15f
                                setPoints(remainingPts)
                            }
                            mapView.overlays.add(remainingLine)
                        }

                        // Start Marker for Today's Stage
                        val stageStartPt = activePoints.firstOrNull()
                        if (stageStartPt != null) {
                            val startMarker = Marker(mapView).apply {
                                position = GeoPoint(stageStartPt.lat, stageStartPt.lng)
                                icon = MapIconUtils.getStartIcon(mapView.context)
                                title = "Start: Dag $currentDay (km ${String.format("%.1f", stageStartKm)})"
                                subDescription = "Dagens etapestart"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            mapView.overlays.add(startMarker)
                        }
                    }

                    // HIGHLIGHT TONIGHT'S SLEEPING PLACE (Large Glowing Camp Ring & Night Camp Marker)
                    if (todayStagePoints.isNotEmpty() || currentStage != null) {
                        val campGlowCircle = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(GeoPoint(tonightCampLat, tonightCampLng), 350.0)
                            fillPaint.color = AndroidColor.parseColor("#4DF59E0B")
                            outlinePaint.color = AndroidColor.parseColor("#FFF59E0B")
                            outlinePaint.strokeWidth = 3f
                            title = "⛺ Nattens lejr: $tonightCampName"
                            subDescription = "Tryk for lejrdetaljer"
                            setOnClickListener { _, _, _ ->
                                val matchedShelter = shelters.find { it.name.equals(tonightCampName, ignoreCase = true) || (abs(it.lat - tonightCampLat) < 0.005 && abs(it.lng - tonightCampLng) < 0.005) }
                                val matchedFt = friTeltnings.find { it.name.equals(tonightCampName, ignoreCase = true) || (abs(it.centerLat - tonightCampLat) < 0.005 && abs(it.centerLng - tonightCampLng) < 0.005) }
                                viewModel.selectPoiForDetails(matchedShelter ?: matchedFt ?: currentStage)
                                true
                            }
                        }
                        mapView.overlays.add(campGlowCircle)

                        val campMarker = Marker(mapView).apply {
                            position = GeoPoint(tonightCampLat, tonightCampLng)
                            icon = MapIconUtils.getNightCampIcon(mapView.context)
                            title = "⛺ Nattens overnatning: $tonightCampName"
                            val distToday = currentStage?.stageKm ?: abs(stageEndKm - stageStartKm)
                            subDescription = "Dagens mål (Dag $currentDay) • ${String.format("%.1f", distToday)} km i dag • ${if (tonightBookingRequired) "Kræver booking" else "Først-til-mølle (gratis)"}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                val matchedShelter = shelters.find { it.name.equals(tonightCampName, ignoreCase = true) || (abs(it.lat - tonightCampLat) < 0.005 && abs(it.lng - tonightCampLng) < 0.005) }
                                val matchedFt = friTeltnings.find { it.name.equals(tonightCampName, ignoreCase = true) || (abs(it.centerLat - tonightCampLat) < 0.005 && abs(it.centerLng - tonightCampLng) < 0.005) }
                                viewModel.selectPoiForDetails(matchedShelter ?: matchedFt ?: currentStage)
                                true
                            }
                        }
                        mapView.overlays.add(campMarker)
                    }
                }

                // 3. Shelters
                if (showShelters) {
                    shelters.forEach { sh ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(sh.lat, sh.lng)
                            icon = MapIconUtils.getShelterIcon(mapView.context)
                            title = sh.name
                            subDescription = "${sh.ownership} • Plads: ${sh.capacity} • ${if (sh.bookingRequired) "Kræver booking" else "Fri afbenyttelse"}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(sh)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 4. Toilets
                if (showToilets) {
                    pois.filter { it.type == "toilet" }.forEach { toilet ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(toilet.lat, toilet.lng)
                            icon = MapIconUtils.getToiletIcon(mapView.context)
                            title = toilet.name
                            subDescription = "Toilet (${toilet.access})"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(toilet)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 5. Water
                if (showWater) {
                    pois.filter { it.type == "water" }.forEach { water ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(water.lat, water.lng)
                            icon = MapIconUtils.getWaterIcon(mapView.context)
                            title = water.name
                            subDescription = "Drikkevandspost"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(water)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 6. Resupply Points
                if (showResupply) {
                    resupplyPoints.forEach { resupply ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(resupply.lat, resupply.lng)
                            icon = MapIconUtils.getResupplyIcon(mapView.context)
                            title = resupply.name
                            subDescription = "${resupply.openingHours} • MobilePay"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(resupply)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 7. Transit Stations
                if (showTransit) {
                    transitStations.forEach { station ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(station.lat, station.lng)
                            icon = MapIconUtils.getTransitIcon(mapView.context)
                            title = station.name
                            subDescription = "${station.type} • km ${String.format("%.1f", station.kmAlongTrail)}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(station)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 8. Trail Hazards
                if (showHazards) {
                    hazards.forEach { hazard ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(hazard.lat, hazard.lng)
                            icon = MapIconUtils.getHazardIcon(mapView.context)
                            title = hazard.title
                            subDescription = hazard.description
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                viewModel.selectPoiForDetails(hazard)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                // 9. User Location Marker with Animated Heading
                val userLocationMarker = Marker(mapView).apply {
                    position = GeoPoint(navState.currentLat, navState.currentLng)
                    rotation = navState.bearing
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Din position"
                }
                mapView.overlays.add(userLocationMarker)

                if (followUserLocation) {
                    mapView.controller.animateTo(GeoPoint(navState.currentLat, navState.currentLng))
                }

                mapView.invalidate()
            }
        )

        // 2. Floating Sleek Nordic Glass Top Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Surface(
                color = ForestGlass,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, GlassBorder),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.TRAIL_DETAIL) },
                        modifier = Modifier
                            .size(40.dp)
                            .background(ForestSurfaceVariant, CircleShape)
                            .testTag("nav_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ForestGreenLight)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = trail?.name ?: "Dansk Sti Navigation",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = "Dag ${navState.currentDay} • ${navState.currentStage?.stopName ?: "På ruten"}",
                            color = ForestGreenLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Battery Saver HUD Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleBatterySaverMode() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isBatterySaverMode) AmberAccent else ForestSurfaceVariant,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (isBatterySaverMode) AmberAccent else GlassBorderSubtle,
                                CircleShape
                            )
                            .testTag("toggle_battery_saver_hud")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatterySaver,
                            contentDescription = "Strømsparer HUD",
                            tint = if (isBatterySaverMode) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Recenter on Today's Stage Button
                    IconButton(
                        onClick = {
                            val pts = if (todayStagePoints.isNotEmpty()) todayStagePoints else userSelectedTripPoints
                            if (pts.isNotEmpty()) {
                                followUserLocation = false
                                val lats = pts.map { it.lat }
                                val lngs = pts.map { it.lng }
                                val box = org.osmdroid.util.BoundingBox(
                                    lats.maxOrNull()!! + 0.008,
                                    lngs.maxOrNull()!! + 0.008,
                                    lats.minOrNull()!! - 0.008,
                                    lngs.minOrNull()!! - 0.008
                                )
                                mapViewRef?.zoomToBoundingBox(box, true, 80)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(ForestSurfaceVariant, CircleShape)
                            .border(1.dp, GlassBorderSubtle, CircleShape)
                            .testTag("recenter_stage_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Centrer på dagens etape",
                            tint = AmberAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Recenter GPS Button
                    IconButton(
                        onClick = {
                            followUserLocation = true
                            mapViewRef?.controller?.animateTo(GeoPoint(navState.currentLat, navState.currentLng))
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (followUserLocation) ForestGreenPrimary else ForestSurfaceVariant,
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (followUserLocation) ForestGreenLight else GlassBorderSubtle,
                                CircleShape
                            )
                            .testTag("recenter_gps_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Centrer position",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Floating Tonight's Sleeping Place Banner
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = ForestGlass,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.6f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        followUserLocation = false
                        mapViewRef?.controller?.animateTo(GeoPoint(tonightCampLat, tonightCampLng))
                        mapViewRef?.controller?.setZoom(16.0)
                    }
                    .testTag("tonight_camp_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (tonightCampType == "FRI_TELTNING") "⛺" else "🛖", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "I nat: $tonightCampName",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                            Text(
                                text = if (tonightBookingRequired) "Kræver booking • Tryk for at se lejr på kort" else "Først-til-mølle • Tryk for at se lejr på kort",
                                color = AmberAccent,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AmberAccent.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "Vis lejr ➔",
                            color = AmberAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Severe Weather Alert Ticker
            if (weather?.severeAlert != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_weather_alert_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AlertRed),
                    border = BorderStroke(1.dp, Color(0x66FFFFFF))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "⚠️", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = weather!!.severeAlert!!,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Off-Trail Alert Banner
            if (navState.isOffTrailWarning) {
                Spacer(modifier = Modifier.height(6.dp))
                OffTrailBanner(distanceMeters = navState.offTrailDistanceMeters)
            }

            // Nearest POI Result Banner
            if (nearestPoi != null) {
                Spacer(modifier = Modifier.height(6.dp))
                NearestPoiOverlayCard(
                    result = nearestPoi!!,
                    onDismiss = { viewModel.clearPoiDetails() }
                )
            }
        }

        // 3. Floating Quick Zoom Controls (Right edge of map)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingMapButton(
                icon = Icons.Default.MyLocation,
                contentDescription = "Centrer på min position",
                tint = if (followUserLocation) ForestGreenLight else Color.White,
                onClick = {
                    followUserLocation = true
                    mapViewRef?.controller?.animateTo(GeoPoint(navState.currentLat, navState.currentLng))
                }
            )
            FloatingMapButton(
                icon = Icons.Default.Add,
                contentDescription = "Zoom ind",
                onClick = { mapViewRef?.controller?.zoomIn() }
            )
            FloatingMapButton(
                icon = Icons.Default.Remove,
                contentDescription = "Zoom ud",
                onClick = { mapViewRef?.controller?.zoomOut() }
            )
            FloatingMapButton(
                icon = Icons.Default.Explore,
                contentDescription = "Nord-orienter",
                tint = AmberAccent,
                onClick = {
                    mapViewRef?.mapOrientation = 0f
                }
            )
        }

        // 4. Bottom Controls: Sleek Single-Row Layer Carousel + Interactive Navigation HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // Floating Recenter Pill when map has been dragged away from user location
            if (!followUserLocation) {
                Surface(
                    color = ForestGlass,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, ForestGreenLight),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            followUserLocation = true
                            mapViewRef?.controller?.animateTo(GeoPoint(navState.currentLat, navState.currentLng))
                        }
                        .testTag("floating_recenter_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = ForestGreenLight,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Centrer på min position",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Horizontal Glass Layers Carousel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModernLayerChip(
                    label = "Shelter",
                    count = shelters.size,
                    icon = Icons.Default.Cabin,
                    isActive = showShelters,
                    activeColor = ShelterBrown,
                    onClick = { viewModel.toggleShelters() }
                )
                ModernLayerChip(
                    label = "Teltning",
                    count = friTeltnings.size,
                    icon = Icons.Default.Forest,
                    isActive = showFriTeltning,
                    activeColor = ForestGreenPrimary,
                    onClick = { viewModel.toggleFriTeltning() }
                )
                ModernLayerChip(
                    label = "Vand",
                    count = pois.count { it.type == "water" },
                    icon = Icons.Default.LocalDrink,
                    isActive = showWater,
                    activeColor = NordicBlue,
                    onClick = { viewModel.toggleWater() }
                )
                ModernLayerChip(
                    label = "Toilet",
                    count = pois.count { it.type == "toilet" },
                    icon = Icons.Default.Wc,
                    isActive = showToilets,
                    activeColor = Color(0xFF64B5F6),
                    onClick = { viewModel.toggleToilets() }
                )
                ModernLayerChip(
                    label = "Indkøb",
                    count = resupplyPoints.size,
                    icon = Icons.Default.ShoppingBag,
                    isActive = showResupply,
                    activeColor = AmberAccent,
                    onClick = { viewModel.toggleResupply() }
                )
                ModernLayerChip(
                    label = "Tog & Bus",
                    count = transitStations.size,
                    icon = Icons.Default.DirectionsTransit,
                    isActive = showTransit,
                    activeColor = Color(0xFFC084FC),
                    onClick = { viewModel.toggleTransit() }
                )
                ModernLayerChip(
                    label = "Farer",
                    count = hazards.size,
                    icon = Icons.Default.Warning,
                    isActive = showHazards,
                    activeColor = AlertRed,
                    onClick = { viewModel.toggleHazards() }
                )
            }

            // Interactive Navigation HUD Card
            Surface(
                color = ForestGlass,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = BorderStroke(1.dp, GlassBorder),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_navigation_hud")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drag/Expand Handle Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHudExpanded = !isHudExpanded }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Næste mål: ",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = navState.currentStage?.stopName ?: "På ruten",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isHudExpanded) "Mindre" else "Detaljer & SOS",
                                color = ForestGreenLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isHudExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = ForestGreenLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Persistent Stage Progress Bar
                    val totalStageKm = (navState.distanceWalkedTodayKm + navState.distanceRemainingTodayKm).coerceAtLeast(0.1)
                    val progressFrac = (navState.distanceWalkedTodayKm / totalStageKm).toFloat().coerceIn(0f, 1f)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progressFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = ForestGreenLight,
                        trackColor = ForestSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary 4 High-Contrast Readouts (Speed, Walked, Remaining Today, Total)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ModernLiveStat(
                            value = String.format("%.1f", navState.speedKmh),
                            unit = "km/t",
                            label = "Fart",
                            valueColor = AmberAccent,
                            modifier = Modifier.weight(1f)
                        )
                        ModernLiveStat(
                            value = String.format("%.1f", navState.distanceWalkedTodayKm),
                            unit = "km",
                            label = "Gået i dag",
                            valueColor = ForestGreenLight,
                            modifier = Modifier.weight(1f)
                        )
                        ModernLiveStat(
                            value = String.format("%.1f", navState.distanceRemainingTodayKm),
                            unit = "km",
                            label = "Til lejr",
                            valueColor = NordicBlue,
                            modifier = Modifier.weight(1f)
                        )
                        ModernLiveStat(
                            value = String.format("%.1f", navState.distanceRemainingTripKm),
                            unit = "km",
                            label = "Total rest",
                            valueColor = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Expanded Section: Stage Progress, Quick Amenities, SOS Rescue Post & Pause
                    AnimatedVisibility(
                        visible = isHudExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            // Stage Progress Bar
                            val totalStageKm = (navState.distanceWalkedTodayKm + navState.distanceRemainingTodayKm).coerceAtLeast(0.1)
                            val progressFrac = (navState.distanceWalkedTodayKm / totalStageKm).toFloat().coerceIn(0f, 1f)

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Etape fremdrift: ${(progressFrac * 100).toInt()}%",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Dag ${navState.currentDay} • ${(navState.totalDistanceWalkedKm + navState.distanceRemainingTripKm).toInt()} km rute",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { progressFrac },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = ForestGreenLight,
                                    trackColor = ForestSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 4 Quick "Find Nærmeste" Buttons in a Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickFindCard(
                                    label = "Toilet",
                                    icon = Icons.Default.Wc,
                                    tint = Color(0xFF64B5F6),
                                    onClick = { viewModel.findNearest("toilet") },
                                    modifier = Modifier.weight(1f)
                                )
                                QuickFindCard(
                                    label = "Drikkevand",
                                    icon = Icons.Default.LocalDrink,
                                    tint = NordicBlue,
                                    onClick = { viewModel.findNearest("water") },
                                    modifier = Modifier.weight(1f)
                                )
                                QuickFindCard(
                                    label = "Shelter",
                                    icon = Icons.Default.Cabin,
                                    tint = ShelterBrown,
                                    onClick = { viewModel.findNearest("shelter") },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Danish 112 SOS Red Rescue Post Card
                            if (emergencyInfo != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = AlertRed.copy(alpha = 0.15f)),
                                    border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = "🚨", fontSize = 16.sp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Rød Redningspæl: ${emergencyInfo!!.nearestRedPostCode}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                            }
                                            Text(
                                                text = emergencyInfo!!.spokenWgs84,
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                val callIntent = Intent(Intent.ACTION_DIAL).apply {
                                                    data = Uri.parse("tel:112")
                                                }
                                                context.startActivity(callIntent)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.testTag("quick_sos_112_button")
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("112", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Stop for today button
                            Button(
                                onClick = { viewModel.stopTrackingToday() },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant),
                                border = BorderStroke(1.dp, GlassBorderSubtle),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("stop_for_today_button")
                            ) {
                                Icon(imageVector = Icons.Default.Pause, contentDescription = null, tint = AmberAccent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Afslut vandring for i dag",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // POI Detail Bottom Sheet when tapping a Marker
        if (selectedPoi != null) {
            PoiDetailBottomSheet(
                item = selectedPoi!!,
                onDismiss = { viewModel.clearPoiDetails() },
                onStartAtStation = { station -> viewModel.startAtTransitStation(station) },
                onReportWater = { isWorking -> viewModel.reportWaterStatus(isWorking) },
                onDeleteHazard = { id -> viewModel.removeHazard(id) }
            )
        }

        // AMOLED Minimalist Battery Saver HUD Mode Overlay (Enhancement 6)
        if (isBatterySaverMode) {
            BatterySaverHudScreen(
                navState = navState,
                emergencyInfo = emergencyInfo,
                onExit = { viewModel.toggleBatterySaverMode() },
                onCall112 = {
                    val callIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:112")
                    }
                    context.startActivity(callIntent)
                }
            )
        }
    }
}

@Composable
fun ModernLiveStat(
    value: String,
    unit: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ModernLayerChip(
    label: String,
    count: Int,
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (isActive) activeColor.copy(alpha = 0.22f) else ForestGlass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isActive) activeColor else GlassBorderSubtle
        ),
        shadowElevation = if (isActive) 6.dp else 2.dp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("layer_chip_${label.lowercase()}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) activeColor else Color.Gray,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$label ($count)",
                color = if (isActive) Color.White else Color.LightGray,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun FloatingMapButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Surface(
        color = ForestGlass,
        shape = CircleShape,
        border = BorderStroke(1.dp, GlassBorder),
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun QuickFindCard(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ForestSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GlassBorderSubtle),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("quick_find_${label.lowercase()}")
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Battery Saver HUD Mode Screen (OLED Minimalist)
@Composable
fun BatterySaverHudScreen(
    navState: com.example.service.NavigationLiveState,
    emergencyInfo: DanishEmergencyInfo?,
    onExit: () -> Unit,
    onCall112: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("battery_saver_hud_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Battery Saver Active Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BatterySaver,
                        contentDescription = null,
                        tint = ForestGreenLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STRØMSPARER-HUD (OLED)",
                        color = ForestGreenLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E),
                    border = BorderStroke(1.dp, Color(0xFF333333)),
                    modifier = Modifier.clickable { onExit() }
                ) {
                    Text(
                        text = "Vis kort ✕",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Big Speed and Compass Bearing HUD
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = String.format("%.1f", navState.speedKmh),
                    color = ForestGreenLight,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "KM / T",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = "Kompas",
                        tint = AmberAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val cardinal = when ((((navState.bearing + 22.5) % 360) / 45).toInt()) {
                        0 -> "N"
                        1 -> "NØ"
                        2 -> "Ø"
                        3 -> "SØ"
                        4 -> "S"
                        5 -> "SV"
                        6 -> "V"
                        7 -> "NV"
                        else -> "N"
                    }
                    Text(
                        text = "Kurs: ${navState.bearing.toInt()}° $cardinal",
                        color = AmberAccent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Distance & Stage Stop Stats
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF111111))
                    .border(1.dp, Color(0xFF222222), RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Text(
                    text = "NÆSTE MÅL: ${navState.currentStage?.stopName ?: "På ruten"}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "TIL DAGENS MÅL", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${String.format("%.1f", navState.distanceRemainingTodayKm)} km",
                            color = NordicBlue,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "GÅET I DAG", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${String.format("%.1f", navState.distanceWalkedTodayKm)} km",
                            color = ForestGreenLight,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom Actions: 112 SOS Button & Exit Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCall112,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("battery_saver_call_112_button")
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (emergencyInfo != null) "🚨 Ring 112 (Pæl: ${emergencyInfo.nearestRedPostCode})" else "🚨 Ring 112 SOS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                OutlinedButton(
                    onClick = onExit,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF444444)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("exit_battery_saver_button")
                ) {
                    Text(
                        text = "Vend tilbage til fuldt kort",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
