package com.example.ui.screens

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GearChecklistItem
import com.example.data.model.GeoPointDto
import com.example.data.model.HazardType
import com.example.data.model.PoiEntity
import com.example.data.model.ResupplyPoint
import com.example.data.model.ResupplyType
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailSurfaceStats
import com.example.data.model.TransitStation
import com.example.data.model.UserHazardNote
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.components.PoiDetailBottomSheet
import com.example.ui.components.LayerToggleChip
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGlass
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.ShelterBrown
import com.example.util.GeoUtils
import com.example.util.MapIconUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailDetailScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val trail by viewModel.selectedTrail.collectAsState()
    val points by viewModel.selectedTrailPoints.collectAsState()
    val shelters by viewModel.corridorShelters.collectAsState()
    val friTeltnings by viewModel.corridorFriTeltning.collectAsState()
    val pois by viewModel.corridorPois.collectAsState()

    val weather by viewModel.trailWeather.collectAsState()
    val startKm by viewModel.plannerStartKm.collectAsState()
    val endKm by viewModel.plannerEndKm.collectAsState()
    val walkDistanceKm by viewModel.plannerWalkDistanceKm.collectAsState()
    val isWalkingReverse by viewModel.isWalkingReverse.collectAsState()
    val tappedKm by viewModel.tappedMapPointKm.collectAsState()
    val selectedPoi by viewModel.selectedPoiDetails.collectAsState()

    val showShelters by viewModel.showShelters.collectAsState()
    val showFriTeltning by viewModel.showFriTeltning.collectAsState()
    val showToilets by viewModel.showToilets.collectAsState()
    val showWater by viewModel.showWater.collectAsState()
    val showResupply by viewModel.showResupply.collectAsState()
    val showTransit by viewModel.showTransit.collectAsState()
    val showHazards by viewModel.showHazards.collectAsState()
    val resupplyPoints by viewModel.corridorResupply.collectAsState()
    val transitStations by viewModel.corridorTransit.collectAsState()
    val hazards by viewModel.corridorHazards.collectAsState()
    val surfaceStats by viewModel.trailSurfaceStats.collectAsState()
    val emergencyInfo by viewModel.emergencyInfo.collectAsState()
    val gearChecklist by viewModel.gearChecklist.collectAsState()
    val scrubbedKm by viewModel.scrubbedElevationKm.collectAsState()
    val isDarkMapMode by viewModel.isDarkMapMode.collectAsState()
    val showRainRadar by viewModel.showRainRadar.collectAsState()
    val rainRadarTimeMinutes by viewModel.rainRadarTimeMinutes.collectAsState()
    val offlineBufferKm by viewModel.offlineCorridorBufferKm.collectAsState()
    val estimatedDownloadMb by viewModel.estimatedDownloadMb.collectAsState()

    val context = LocalContext.current

    var showReportHazardDialog by remember { mutableStateOf(false) }
    var newHazardTitle by remember { mutableStateOf("") }
    var newHazardDesc by remember { mutableStateOf("") }
    var newHazardType by remember { mutableStateOf(HazardType.FLOODED_BOARDWALK) }

    BackHandler {
        viewModel.navigateTo(AppScreen.TRAIL_BROWSER)
    }

    if (trail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ingen sti valgt", color = Color.White)
        }
        return
    }

    val currentTrail = trail!!
    val totalLength = currentTrail.lengthKm
    val selectedDistance = abs(endKm - startKm)
    val distancePercentage = if (totalLength > 0) (selectedDistance / totalLength * 100).coerceIn(0.0, 100.0) else 100.0

    val toiletsCount = pois.count { it.type == "toilet" }
    val waterCount = pois.count { it.type == "water" }

    // Pre-parse Fri Teltning polygon boundaries to avoid JSON parsing on update
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForestDarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentTrail.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${currentTrail.region} • ${currentTrail.lengthKm.toInt()} km",
                            color = ForestGreenLight,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.TRAIL_BROWSER) },
                        modifier = Modifier.testTag("detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Tilbage til stier",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestSurface)
            )
        },
        bottomBar = {
            Surface(
                color = ForestGlass,
                border = BorderStroke(1.dp, GlassBorderSubtle),
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.requestStartNavigation() },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("direct_start_nav_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ForestGreenLight),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(imageVector = Icons.Default.Navigation, contentDescription = null, tint = ForestGreenLight, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start rute", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { viewModel.navigateTo(AppScreen.TRIP_PLANNER) },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(52.dp)
                            .testTag("plan_trip_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Planlæg (${String.format("%.1f", selectedDistance)} km)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Severe Weather Alert Banner (If Any)
            if (weather?.severeAlert != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("weather_alert_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = AlertRed)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Vejrvarsel",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = weather!!.severeAlert!!,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Interactive Map with Shelters, Fri Teltning, and Segment Selection
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("trail_detail_map_view"),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                            isTilesScaledToDpi = true

                            // Listen for taps on map to select distance/points along trail
                            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                    if (p != null) {
                                        viewModel.onTrailMapTapped(p.latitude, p.longitude)
                                    }
                                    return true
                                }
                                override fun longPressHelper(p: GeoPoint?): Boolean = false
                            }))

                            if (points.isNotEmpty()) {
                                val minLat = points.minOf { it.lat }
                                val maxLat = points.maxOf { it.lat }
                                val minLng = points.minOf { it.lng }
                                val maxLng = points.maxOf { it.lng }
                                post {
                                    zoomToBoundingBox(
                                        BoundingBox(maxLat + 0.02, maxLng + 0.03, minLat - 0.02, minLng - 0.03),
                                        true
                                    )
                                }
                            }
                        }
                    },
                    update = { mapView ->
                        // Clear previous visual overlays except events
                        val eventsOverlay = mapView.overlays.firstOrNull { it is MapEventsOverlay }
                        mapView.overlays.clear()
                        if (eventsOverlay != null) mapView.overlays.add(eventsOverlay)

                        // 1. Draw Fri Teltning Green Polygons & Tent Pins
                        if (showFriTeltning) {
                            parsedFriTeltningPolygons.forEach { (ft, pts) ->
                                if (pts.size >= 3) {
                                    val polygon = Polygon(mapView).apply {
                                        fillPaint.color = AndroidColor.parseColor("#4D2E7D32")
                                        outlinePaint.color = AndroidColor.parseColor("#804CAF50")
                                        outlinePaint.strokeWidth = 3f
                                        this.points = pts.map { GeoPoint(it.lat, it.lng) }
                                        title = ft.name
                                        subDescription = "${ft.zoneType} • ${ft.rulesSummary}"
                                        setOnClickListener { _, _, _ ->
                                            viewModel.selectPoiForDetails(ft)
                                            true
                                        }
                                    }
                                    mapView.overlays.add(polygon)
                                }

                                // Tent Icon Pin ALWAYS placed at the center of the Fri Teltning forest
                                val tentMarker = Marker(mapView).apply {
                                    position = GeoPoint(ft.centerLat, ft.centerLng)
                                    icon = MapIconUtils.getTentIcon(mapView.context)
                                    title = "⛺ ${ft.name}"
                                    subDescription = "${ft.zoneType} • Fri teltning tilladt • ${ft.rulesSummary}"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { _, _ ->
                                        viewModel.selectPoiForDetails(ft)
                                        true
                                    }
                                }
                                mapView.overlays.add(tentMarker)
                            }
                        }

                        // 2. Draw Polyline Segments: Selected distance vs Unselected distance
                        if (points.isNotEmpty()) {
                            val effectiveStart = min(startKm, endKm)
                            val effectiveEnd = max(startKm, endKm)

                            val unselectedLine = Polyline(mapView).apply {
                                outlinePaint.color = AndroidColor.parseColor("#66888888")
                                outlinePaint.strokeWidth = 8f
                                setPoints(points.map { GeoPoint(it.lat, it.lng) })
                            }
                            mapView.overlays.add(unselectedLine)

                            // Extract selected points between startKm and endKm using slicePolylineByKm
                            val selectedSlice = GeoUtils.slicePolylineByKm(points, effectiveStart, effectiveEnd)
                            val selectedPoints = selectedSlice.map { GeoPoint(it.lat, it.lng) }

                            if (selectedPoints.size >= 2) {
                                val highlightedLine = Polyline(mapView).apply {
                                    outlinePaint.color = AndroidColor.parseColor("#F59E0B") // Amber
                                    outlinePaint.strokeWidth = 14f
                                    setPoints(selectedPoints)
                                }
                                mapView.overlays.add(highlightedLine)
                            }

                            // Start Marker (Green A)
                            val startPt = GeoUtils.pointAtDistanceAlongPolyline(points, startKm)
                            val startMarker = Marker(mapView).apply {
                                position = GeoPoint(startPt.lat, startPt.lng)
                                icon = MapIconUtils.getStartIcon(mapView.context)
                                title = "Startpunkt (A) - km ${String.format("%.1f", startKm)}"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            mapView.overlays.add(startMarker)

                            // End Marker (Red B)
                            val endPt = GeoUtils.pointAtDistanceAlongPolyline(points, endKm)
                            val endMarker = Marker(mapView).apply {
                                position = GeoPoint(endPt.lat, endPt.lng)
                                icon = MapIconUtils.getEndIcon(mapView.context)
                                title = "Slutpunkt (B) - km ${String.format("%.1f", endKm)}"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            mapView.overlays.add(endMarker)
                        }

                        // 3. Shelters on Map
                        if (showShelters) {
                            shelters.forEach { sh ->
                                val marker = Marker(mapView).apply {
                                    position = GeoPoint(sh.lat, sh.lng)
                                    icon = MapIconUtils.getShelterIcon(mapView.context)
                                    title = sh.name
                                    subDescription = "${sh.ownership} • Booking: ${if (sh.bookingRequired) "Ja" else "Fri"}"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { _, _ ->
                                        viewModel.selectPoiForDetails(sh)
                                        true
                                    }
                                }
                                mapView.overlays.add(marker)
                            }
                        }

                        // 4. Toilets & Water
                        if (showToilets) {
                            pois.filter { it.type == "toilet" }.forEach { toilet ->
                                val marker = Marker(mapView).apply {
                                    position = GeoPoint(toilet.lat, toilet.lng)
                                    icon = MapIconUtils.getToiletIcon(mapView.context)
                                    title = toilet.name
                                    subDescription = "Offentligt toilet"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { _, _ ->
                                        viewModel.selectPoiForDetails(toilet)
                                        true
                                    }
                                }
                                mapView.overlays.add(marker)
                            }
                        }

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

                        // 5. Resupply Points (New Feature 4)
                        if (showResupply) {
                            resupplyPoints.forEach { resupply ->
                                val marker = Marker(mapView).apply {
                                    position = GeoPoint(resupply.lat, resupply.lng)
                                    icon = MapIconUtils.getResupplyIcon(mapView.context)
                                    title = resupply.name
                                    subDescription = "${resupply.openingHours} • MobilePay: ${if (resupply.hasMobilePay) "Ja" else "Nej"}"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { _, _ ->
                                        viewModel.selectPoiForDetails(resupply)
                                        true
                                    }
                                }
                                mapView.overlays.add(marker)
                            }
                        }

                        // 6. Transit Stations (Enhancement 13)
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

                        // 7. Community Trail Hazards (Enhancement 7)
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

                        // 8. Elevation Scrubbed Cursor on Map (Enhancement 1)
                        if (scrubbedKm != null && points.isNotEmpty()) {
                            val scrubbedPt = GeoUtils.pointAtDistanceAlongPolyline(points, scrubbedKm!!)
                            val scrubMarker = Marker(mapView).apply {
                                position = GeoPoint(scrubbedPt.lat, scrubbedPt.lng)
                                icon = MapIconUtils.getCrosshairIcon(mapView.context)
                                title = "Højdepunkt: km ${String.format("%.1f", scrubbedKm)}"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            }
                            mapView.overlays.add(scrubMarker)
                        }

                        // 9. DMI Rain Radar Overlay (Enhancement 11)
                        if (showRainRadar && points.isNotEmpty()) {
                            val midIdx = points.size / 2
                            val centerPt = points[midIdx]
                            val radarPolygon = Polygon(mapView).apply {
                                val radarPoints = ArrayList<GeoPoint>()
                                val radiusDeg = 0.045 + (rainRadarTimeMinutes * 0.0003)
                                for (i in 0 until 360 step 30) {
                                    val rad = Math.toRadians(i.toDouble())
                                    radarPoints.add(
                                        GeoPoint(
                                            centerPt.lat + radiusDeg * Math.cos(rad) + (rainRadarTimeMinutes * 0.0002),
                                            centerPt.lng + radiusDeg * 1.5 * Math.sin(rad)
                                        )
                                    )
                                }
                                this.points = radarPoints
                                fillPaint.color = AndroidColor.parseColor("#4D0288D1")
                                outlinePaint.color = AndroidColor.parseColor("#8003A9F4")
                                outlinePaint.strokeWidth = 3f
                                title = "DMI Nedbørsradar (+$rainRadarTimeMinutes min)"
                            }
                            mapView.overlays.add(radarPolygon)
                        }

                        mapView.invalidate()
                    }
                )

                // Top Floating Glass Toolbar on Map (Day/Night, Radar, Layer Pills)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter),
                    shape = RoundedCornerShape(14.dp),
                    color = ForestGlass,
                    border = BorderStroke(1.dp, GlassBorderSubtle),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleDarkMapMode() },
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (isDarkMapMode) AmberAccent else ForestSurfaceVariant, CircleShape)
                                .testTag("toggle_dark_map_mode")
                        ) {
                            Icon(
                                imageVector = if (isDarkMapMode) Icons.Default.LightMode else Icons.Default.Bedtime,
                                contentDescription = "Dag/Nat kort",
                                tint = if (isDarkMapMode) Color.Black else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleRainRadar() },
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (showRainRadar) NordicBlue else ForestSurfaceVariant, CircleShape)
                                .testTag("toggle_rain_radar")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Thunderstorm,
                                contentDescription = "DMI Regnradar",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { showReportHazardDialog = true },
                            modifier = Modifier
                                .size(32.dp)
                                .background(ForestSurfaceVariant, CircleShape)
                                .testTag("report_hazard_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReportProblem,
                                contentDescription = "Rapportér fare",
                                tint = AlertRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        LayerToggleChip(label = "Shelter", icon = Icons.Default.Cabin, isActive = showShelters, color = ShelterBrown, onClick = { viewModel.toggleShelters() })
                        LayerToggleChip(label = "Telt", icon = Icons.Default.Forest, isActive = showFriTeltning, color = ForestGreenPrimary, onClick = { viewModel.toggleFriTeltning() })
                        LayerToggleChip(label = "Toilet", icon = Icons.Default.Wc, isActive = showToilets, color = Color(0xFF64B5F6), onClick = { viewModel.toggleToilets() })
                        LayerToggleChip(label = "Vand", icon = Icons.Default.LocalDrink, isActive = showWater, color = NordicBlue, onClick = { viewModel.toggleWater() })
                        LayerToggleChip(label = "Indkøb", icon = Icons.Default.ShoppingBag, isActive = showResupply, color = AmberAccent, onClick = { viewModel.toggleResupply() })
                        LayerToggleChip(label = "Tog/Bus", icon = Icons.Default.DirectionsTransit, isActive = showTransit, color = Color(0xFFC084FC), onClick = { viewModel.toggleTransit() })
                        LayerToggleChip(label = "Farer", icon = Icons.Default.Warning, isActive = showHazards, color = AlertRed, onClick = { viewModel.toggleHazards() })
                    }
                }

                // If user tapped a point along the trail on the map, show prompt above the controller
                if (tappedKm != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ForestSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 145.dp)
                            .testTag("map_point_selection_card"),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📍 Valgt: km ${String.format("%.1f", tappedKm)}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { viewModel.setStartFromTappedPoint() },
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp).testTag("set_start_point_button")
                                ) {
                                    Text("Start (A)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.setEndFromTappedPoint() },
                                    colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp).testTag("set_end_point_button")
                                ) {
                                    Text("Slut (B)", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = { viewModel.clearTappedMapPoint() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Luk", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // REIMAGINED: Modern Interactive Route & Segment Controller directly on the Map!
                val hikingSpeed by viewModel.hikingSpeedKmh.collectAsState()
                val packWeight by viewModel.packWeightKg.collectAsState()
                val effectiveSpeed = (hikingSpeed - (packWeight * 0.03)).coerceAtLeast(2.0)
                val estimatedHoursTotal = selectedDistance / effectiveSpeed
                val estHours = estimatedHoursTotal.toInt()
                val estMins = ((estimatedHoursTotal - estHours) * 60).toInt()

                val startStation = transitStations.minByOrNull { abs(it.kmAlongTrail - startKm) }
                val endStation = transitStations.minByOrNull { abs(it.kmAlongTrail - endKm) }
                val startLabel = if (startStation != null && abs(startStation.kmAlongTrail - startKm) <= 3.5) {
                    "${startStation.name} (km ${String.format("%.1f", startKm)})"
                } else {
                    "km ${String.format("%.1f", startKm)}"
                }
                val endLabel = if (endStation != null && abs(endStation.kmAlongTrail - endKm) <= 3.5) {
                    "${endStation.name} (km ${String.format("%.1f", endKm)})"
                } else {
                    "km ${String.format("%.1f", endKm)}"
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("interactive_segment_controller_card"),
                    shape = RoundedCornerShape(20.dp),
                    color = ForestGlass,
                    border = BorderStroke(1.dp, GlassBorderSubtle),
                    shadowElevation = 14.dp
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        // Top status row: Start A badge, Direction Toggle, End B badge, and Glowing Distance
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Start Pin A Badge
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ForestGreenPrimary.copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, ForestGreenLight.copy(alpha = 0.7f)),
                                    modifier = Modifier.clickable {
                                        viewModel.setPlannerStartKm(0.0)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("A", color = ForestGreenLight, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(startLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                                    }
                                }

                                // Interactive Direction Toggle Arrow Button
                                Surface(
                                    shape = CircleShape,
                                    color = ForestSurfaceVariant,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { viewModel.setWalkingDirection(!isWalkingReverse) }
                                        .testTag("direction_toggle_chip")
                                ) {
                                    Text(
                                        text = if (!isWalkingReverse) "➔" else "⬅",
                                        color = if (!isWalkingReverse) Color.White else AmberAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }

                                // End Pin B Badge
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = AmberAccent.copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.7f)),
                                    modifier = Modifier.clickable {
                                        viewModel.setPlannerEndKm(totalLength)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("B", color = AmberAccent, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(endLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Glowing Selected Distance Pill
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AmberAccent.copy(alpha = 0.25f),
                                border = BorderStroke(1.dp, AmberAccent)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${String.format("%.1f", selectedDistance)} km",
                                        color = AmberAccent,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "~$estHours t $estMins m",
                                        color = Color(0xFFDDDDDD),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Interactive Dual-Thumb RangeSlider synchronized with Map
                        val currentStart = min(startKm, endKm).toFloat()
                        val currentEnd = max(startKm, endKm).toFloat()
                        RangeSlider(
                            value = currentStart..currentEnd,
                            onValueChange = { range ->
                                if (isWalkingReverse) {
                                    viewModel.setPlannerRange(range.endInclusive.toDouble(), range.start.toDouble())
                                } else {
                                    viewModel.setPlannerRange(range.start.toDouble(), range.endInclusive.toDouble())
                                }
                            },
                            valueRange = 0f..totalLength.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("interactive_segment_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = AmberAccent,
                                activeTrackColor = AmberAccent,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Quick Snap Shortcut Chips & Direct Actions Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setPlannerRange(0.0, totalLength) }
                                        .testTag("preset_full_trail"),
                                    color = if (abs(selectedDistance - totalLength) < 0.5) AmberAccent else ForestSurfaceVariant
                                ) {
                                    Text(
                                        text = "Hele (${totalLength.toInt()} km)",
                                        color = if (abs(selectedDistance - totalLength) < 0.5) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }

                                if (totalLength >= 30.0) {
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewModel.setPlannerRange(0.0, totalLength / 2.0) }
                                            .testTag("preset_first_half"),
                                        color = ForestSurfaceVariant
                                    ) {
                                        Text(
                                            text = "1. halvdel",
                                            color = Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewModel.setPlannerRange(totalLength / 2.0, totalLength) }
                                            .testTag("preset_second_half"),
                                        color = ForestSurfaceVariant
                                    ) {
                                        Text(
                                            text = "2. halvdel",
                                            color = Color.LightGray,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            val newEnd = min(totalLength, startKm + 20.0)
                                            viewModel.setPlannerRange(startKm, newEnd)
                                        }
                                        .testTag("preset_20_km"),
                                    color = ForestSurfaceVariant
                                ) {
                                    Text(
                                        text = "+20 km",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // Quick Action Button directly on segment controller
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { viewModel.navigateTo(AppScreen.TRIP_PLANNER) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(28.dp).testTag("quick_plan_trip_button")
                                ) {
                                    Text("Planlæg etaper", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Layer Toggle Chips right below map
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LayerToggleChip(
                    label = "Shelter",
                    icon = Icons.Default.Cabin,
                    isActive = showShelters,
                    color = ShelterBrown,
                    onClick = { viewModel.toggleShelters() },
                    modifier = Modifier.weight(1f)
                )
                LayerToggleChip(
                    label = "Teltning",
                    icon = Icons.Default.Forest,
                    isActive = showFriTeltning,
                    color = ForestGreenPrimary,
                    onClick = { viewModel.toggleFriTeltning() },
                    modifier = Modifier.weight(1f)
                )
                LayerToggleChip(
                    label = "Toilet",
                    icon = Icons.Default.Wc,
                    isActive = showToilets,
                    color = Color(0xFF64B5F6),
                    onClick = { viewModel.toggleToilets() },
                    modifier = Modifier.weight(1f)
                )
                LayerToggleChip(
                    label = "Vand",
                    icon = Icons.Default.LocalDrink,
                    isActive = showWater,
                    color = NordicBlue,
                    onClick = { viewModel.toggleWater() },
                    modifier = Modifier.weight(1f)
                )
            }

            // Layer Toggle Chips Row 2: Indkøb/Vejbod, Tog/Bus, Farer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LayerToggleChip(
                    label = "Indkøb",
                    icon = Icons.Default.ShoppingBag,
                    isActive = showResupply,
                    color = AmberAccent,
                    onClick = { viewModel.toggleResupply() },
                    modifier = Modifier.weight(1f)
                )
                LayerToggleChip(
                    label = "Tog/Bus",
                    icon = Icons.Default.DirectionsTransit,
                    isActive = showTransit,
                    color = Color(0xFFC084FC),
                    onClick = { viewModel.toggleTransit() },
                    modifier = Modifier.weight(1f)
                )
                LayerToggleChip(
                    label = "Farer",
                    icon = Icons.Default.Warning,
                    isActive = showHazards,
                    color = AlertRed,
                    onClick = { viewModel.toggleHazards() },
                    modifier = Modifier.weight(1f)
                )
            }

            // Weather Forecast Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("weather_forecast_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ForestSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = weather?.weatherIcon ?: "🌤️", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Vejret på ruten",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = weather?.conditionDescription ?: "Opdaterer vejr...",
                            color = ForestGreenLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        WeatherMetricItem(
                            icon = Icons.Default.Thermostat,
                            label = "Temperatur",
                            value = "${weather?.tempC?.toInt() ?: 15}°C",
                            color = AmberAccent
                        )
                        WeatherMetricItem(
                            icon = Icons.Default.Air,
                            label = "Middelvind",
                            value = "${String.format("%.1f", weather?.windSpeedMs ?: 4.5)} m/s",
                            color = NordicBlue
                        )
                        WeatherMetricItem(
                            icon = Icons.Default.Air,
                            label = "Vindstød",
                            value = "${String.format("%.1f", weather?.windGustsMs ?: 7.0)} m/s",
                            color = if ((weather?.windGustsMs ?: 0.0) >= 15.0) AlertRed else NordicBlue
                        )
                        WeatherMetricItem(
                            icon = Icons.Default.WaterDrop,
                            label = "Nedbør",
                            value = "${weather?.precipitationMm ?: 0.0} mm",
                            color = NordicBlue
                        )
                    }
                }
            }

            // Facilities Overview
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Faciliteter på stien (3 km korridor)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FacilityBadge(
                        icon = Icons.Default.Cabin,
                        count = shelters.size,
                        label = "Shelters",
                        color = ShelterBrown,
                        modifier = Modifier.weight(1f)
                    )
                    FacilityBadge(
                        icon = Icons.Default.Forest,
                        count = friTeltnings.size,
                        label = "Fri Teltning",
                        color = ForestGreenPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FacilityBadge(
                        icon = Icons.Default.Wc,
                        count = toiletsCount,
                        label = "Toiletter",
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.weight(1f)
                    )
                    FacilityBadge(
                        icon = Icons.Default.LocalDrink,
                        count = waterCount,
                        label = "Vandposter",
                        color = NordicBlue,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Trail Description Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Om ruten",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentTrail.description,
                            color = Color(0xFFCCCCCC),
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )
                    }
                }

                // Shelters & Fri Teltning Explorer Card
                if (shelters.isNotEmpty() || friTeltnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    ShelterAndFriTeltningCard(
                        shelters = shelters,
                        friTeltnings = friTeltnings,
                        onSelectShelter = { viewModel.selectPoiForDetails(it) },
                        onSelectFriTeltning = { viewModel.selectPoiForDetails(it) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 1. Interactive Elevation Profile Scrubbing (Enhancement 1)
                ElevationProfileCard(
                    trail = currentTrail,
                    scrubbedKm = scrubbedKm,
                    onScrubKm = { viewModel.setScrubbedElevationKm(it) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Trail Surface & Footwear Advisor (New Feature 1)
                surfaceStats?.let {
                    SurfaceAndFootwearCard(stats = it)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 3. Resupply & Provisioning Radar (New Feature 4)
                if (resupplyPoints.isNotEmpty()) {
                    ResupplyRadarCard(
                        points = resupplyPoints,
                        onSelectPoint = { viewModel.selectPoiForDetails(it) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 4. Public Transit Hub Connectors (Enhancement 13)
                if (transitStations.isNotEmpty()) {
                    TransitConnectorsCard(
                        stations = transitStations,
                        onStartAtStation = { viewModel.startAtTransitStation(it) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 5. Trail Hazards & Community Reports (Enhancement 7)
                TrailHazardsCard(
                    hazards = hazards,
                    onReportClick = { showReportHazardDialog = true },
                    onSelectHazard = { viewModel.selectPoiForDetails(it) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 6. Danish 112 SOS & Red Rescue Post (Enhancement 14)
                emergencyInfo?.let {
                    EmergencySosCard(info = it, context = context)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 7. Trekking Gear Checklist (Enhancement 15)
                if (gearChecklist.isNotEmpty()) {
                    GearChecklistCard(
                        items = gearChecklist,
                        onToggleItem = { viewModel.toggleGearItem(it) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 8. GPX Export Card (Enhancement 12)
                GpxExportCard(onExportGpx = { viewModel.exportTripGpx(context) })
            }
        }

        // POI Details Bottom Sheet (when user taps a shelter, fri teltning, resupply or hazard on map)
        if (selectedPoi != null) {
            PoiDetailBottomSheet(
                item = selectedPoi!!,
                onDismiss = { viewModel.clearPoiDetails() },
                onStartAtStation = { station -> viewModel.startAtTransitStation(station) },
                onReportWater = { isWorking -> viewModel.reportWaterStatus(isWorking) },
                onDeleteHazard = { id -> viewModel.removeHazard(id) }
            )
        }

        // Report Hazard Dialog (Enhancement 7)
        if (showReportHazardDialog) {
            AlertDialog(
                onDismissRequest = { showReportHazardDialog = false },
                containerColor = ForestSurface,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ReportProblem, contentDescription = null, tint = AlertRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rapportér forhindring", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Vælg type forhindring:", color = Color.LightGray, fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                HazardType.FLOODED_BOARDWALK to "🌊 Vand",
                                HazardType.FALLEN_TREE to "🌲 Træ",
                                HazardType.CATTLE_ENCLOSURE to "🐂 Kvæg",
                                HazardType.TRAIL_DETOUR to "🚧 Omvej"
                            ).forEach { (hType, label) ->
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { newHazardType = hType },
                                    color = if (newHazardType == hType) AlertRed else ForestSurfaceVariant
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = newHazardTitle,
                            onValueChange = { newHazardTitle = it },
                            label = { Text("Titel (f.eks. Oversvømmet træspang)") },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("hazard_title_input")
                        )
                        OutlinedTextField(
                            value = newHazardDesc,
                            onValueChange = { newHazardDesc = it },
                            label = { Text("Beskrivelse") },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("hazard_desc_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val firstPt = points.firstOrNull() ?: GeoPointDto(55.0, 10.0)
                            val lat = if (tappedKm != null && points.isNotEmpty()) GeoUtils.pointAtDistanceAlongPolyline(points, tappedKm!!).lat else firstPt.lat
                            val lng = if (tappedKm != null && points.isNotEmpty()) GeoUtils.pointAtDistanceAlongPolyline(points, tappedKm!!).lng else firstPt.lng
                            viewModel.addHazard(
                                type = newHazardType,
                                title = newHazardTitle.ifBlank { "Forhindring" },
                                description = newHazardDesc.ifBlank { "Forhindring observeret på ruten." },
                                lat = lat,
                                lng = lng
                            )
                            showReportHazardDialog = false
                            newHazardTitle = ""
                            newHazardDesc = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                        modifier = Modifier.testTag("submit_hazard_button")
                    ) {
                        Text("Indsend melding", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showReportHazardDialog = false }) {
                        Text("Annuller", color = Color.White)
                    }
                }
            )
        }
    }
}

@Composable
fun WeatherMetricItem(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
    }
}

@Composable
fun FacilityBadge(
    icon: ImageVector,
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "$count stk",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = label,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// 1. Interactive Elevation Profile Card (Enhancement 1)
@Composable
fun ElevationProfileCard(
    trail: com.example.data.model.TrailEntity,
    scrubbedKm: Double?,
    onScrubKm: (Double?) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("elevation_profile_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        tint = ForestGreenLight,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Højdeprofil & terræn",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForestSurfaceVariant
                ) {
                    Text(
                        text = "Stigning: +${trail.elevationGainM} m",
                        color = AmberAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val totalKm = max(1.0, trail.lengthKm)
            val elevationGain = max(20, trail.elevationGainM)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ForestDarkBg)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    val w = size.width
                    val h = size.height

                    val path = Path()
                    val fillPath = Path()

                    val samplePoints = 40
                    fillPath.moveTo(0f, h)

                    for (i in 0..samplePoints) {
                        val fraction = i.toFloat() / samplePoints
                        val normAlt = (0.25f + 0.35f * kotlin.math.sin(fraction * 3.1415f * 2.5f).toFloat() + 0.2f * kotlin.math.sin(fraction * 3.1415f * 6f).toFloat()).coerceIn(0.08f, 0.92f)
                        val x = fraction * w
                        val y = h - (normAlt * h)

                        if (i == 0) {
                            path.moveTo(x, y)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }

                    fillPath.lineTo(w, h)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                ForestGreenPrimary.copy(alpha = 0.5f),
                                ForestGreenPrimary.copy(alpha = 0.05f)
                            )
                        )
                    )

                    drawPath(
                        path = path,
                        color = ForestGreenLight,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    if (scrubbedKm != null) {
                        val scrubFraction = (scrubbedKm / totalKm).toFloat().coerceIn(0f, 1f)
                        val cursorX = scrubFraction * w
                        drawLine(
                            color = AmberAccent,
                            start = Offset(cursorX, 0f),
                            end = Offset(cursorX, h),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawCircle(
                            color = AmberAccent,
                            radius = 5.dp.toPx(),
                            center = Offset(cursorX, h * 0.45f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (scrubbedKm != null) "Valgt: km ${String.format("%.1f", scrubbedKm)} (~${((scrubbedKm / totalKm) * elevationGain).toInt() + 12} m.o.h.)" else "Træk for at se højde langs ruten:",
                    color = if (scrubbedKm != null) AmberAccent else Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (scrubbedKm != null) {
                    Text(
                        text = "Nulstil",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onScrubKm(null) }
                    )
                }
            }

            Slider(
                value = (scrubbedKm ?: 0.0).toFloat(),
                onValueChange = { onScrubKm(it.toDouble()) },
                valueRange = 0f..totalKm.toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("elevation_scrubber_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = AmberAccent,
                    activeTrackColor = AmberAccent,
                    inactiveTrackColor = ForestSurfaceVariant
                )
            )
        }
    }
}

// 2. Trail Surface & Footwear Advisor Card (New Feature 1)
@Composable
fun SurfaceAndFootwearCard(stats: TrailSurfaceStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("surface_footwear_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = ForestGreenLight,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Underlag & Fodtøjsvejleder",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (stats.blisterRisk.contains("Høj", ignoreCase = true)) AlertRed.copy(alpha = 0.2f) else ForestSurfaceVariant
                ) {
                    Text(
                        text = "Vabelrisiko: ${stats.blisterRisk}",
                        color = if (stats.blisterRisk.contains("Høj", ignoreCase = true)) AlertRed else ForestGreenLight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            ) {
                if (stats.forestSoilPct > 0) {
                    Box(modifier = Modifier.weight(stats.forestSoilPct.toFloat()).fillMaxSize().background(ForestGreenPrimary))
                }
                if (stats.gravelPct > 0) {
                    Box(modifier = Modifier.weight(stats.gravelPct.toFloat()).fillMaxSize().background(AmberAccent))
                }
                if (stats.asphaltPct > 0) {
                    Box(modifier = Modifier.weight(stats.asphaltPct.toFloat()).fillMaxSize().background(Color(0xFF78909C)))
                }
                if (stats.sandBeachPct > 0) {
                    Box(modifier = Modifier.weight(stats.sandBeachPct.toFloat()).fillMaxSize().background(NordicBlue))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SurfaceLegendItem(color = ForestGreenPrimary, label = "Skov ${stats.forestSoilPct}%")
                SurfaceLegendItem(color = AmberAccent, label = "Grus ${stats.gravelPct}%")
                SurfaceLegendItem(color = Color(0xFF78909C), label = "Asfalt ${stats.asphaltPct}%")
                SurfaceLegendItem(color = NordicBlue, label = "Strand ${stats.sandBeachPct}%")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = ForestDarkBg,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "👟", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Anbefalet: ${stats.recommendedFootwear}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stats.footwearTip,
                        color = Color(0xFFDDDDDD),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SurfaceLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = Color.LightGray, fontSize = 10.sp)
    }
}

// 3. Resupply & Provisioning Radar Card (New Feature 4)
@Composable
fun ResupplyRadarCard(
    points: List<ResupplyPoint>,
    onSelectPoint: (ResupplyPoint) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("resupply_radar_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBag,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Forsyningsradar & Vejboder",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AmberAccent.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "${points.size} steder",
                        color = AmberAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Købmænd, supermarkeder og lokale vejboder med MobilePay langs ruten:",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                points.forEach { resupply ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectPoint(resupply) }
                            .testTag("resupply_item_${resupply.id}"),
                        color = ForestDarkBg
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (resupply.type) {
                                    ResupplyType.GROCERY -> "🛒"
                                    ResupplyType.FARM_STALL -> "🍓"
                                    ResupplyType.BAKERY_CAFE -> "☕"
                                },
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = resupply.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "km ${String.format("%.1f", resupply.distanceKmFromStart)} • ${resupply.openingHours}",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            if (resupply.hasMobilePay) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = NordicBlue.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "MobilePay",
                                        color = NordicBlue,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 4. Public Transit Hub Connectors Card (Enhancement 13)
@Composable
fun TransitConnectorsCard(
    stations: List<TransitStation>,
    onStartAtStation: (TransitStation) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transit_connectors_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsTransit,
                        contentDescription = null,
                        tint = Color(0xFFC084FC),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Offentlig Transport & Tog",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFC084FC).copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Grøn ankomst",
                        color = Color(0xFFC084FC),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Tog- og busstationer direkte forbundet med vandrestien:",
                color = Color.LightGray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stations.forEach { station ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = ForestDarkBg,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsTransit,
                                contentDescription = null,
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "km ${String.format("%.1f", station.kmAlongTrail)} • ${station.lines}",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = { onStartAtStation(station) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(34.dp).testTag("start_at_station_${station.id}")
                            ) {
                                Text("Start her", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// 5. Trail Hazards & Community Reports Card (Enhancement 7)
@Composable
fun TrailHazardsCard(
    hazards: List<UserHazardNote>,
    onReportClick: () -> Unit,
    onSelectHazard: (UserHazardNote) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trail_hazards_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Aktuelle Farer & Forhindringer",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AlertRed.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "${hazards.size} meldinger",
                        color = AlertRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (hazards.isEmpty()) {
                Text(
                    text = "✓ Ingen forhindringer eller farer rapporteret på stien lige nu.",
                    color = ForestGreenLight,
                    fontSize = 12.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hazards.forEach { hazard ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectHazard(hazard) }
                                .testTag("hazard_item_${hazard.id}"),
                            color = ForestDarkBg
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (hazard.type) {
                                        HazardType.FLOODED_BOARDWALK -> "🌊"
                                        HazardType.FALLEN_TREE -> "🌲"
                                        HazardType.CATTLE_ENCLOSURE -> "🐂"
                                        HazardType.TRAIL_DETOUR -> "🚧"
                                    },
                                    fontSize = 20.sp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hazard.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = hazard.description,
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onReportClick,
                colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp).testTag("open_report_hazard_dialog")
            ) {
                Icon(Icons.Default.ReportProblem, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rapportér forhindring på stien", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

// 6. Danish 112 SOS & Red Rescue Post Card (Enhancement 14)
@Composable
fun EmergencySosCard(
    info: DanishEmergencyInfo,
    context: android.content.Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("emergency_sos_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AlertRed.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🚨", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Dansk 112 Nødopkald & Rød Pæl",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AlertRed
                ) {
                    Text(
                        text = "112 SOS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = ForestDarkBg,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "📍 Nærmeste Røde Redningspæl: ${info.nearestRedPostCode}",
                        color = AmberAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${info.nearestRedPostLocation} (~${info.distanceToPostMeters} m)",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Oplæs for 112 alarmcentralen:",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    Text(
                        text = info.spokenWgs84,
                        color = ForestGreenLight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val callIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:112")
                    }
                    context.startActivity(callIntent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp).testTag("call_112_button")
            ) {
                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ring 112 Alarmcentralen", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// 7. Trekking Gear Checklist Card (Enhancement 15)
@Composable
fun GearChecklistCard(
    items: List<GearChecklistItem>,
    onToggleItem: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val packedCount = items.count { it.isChecked }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gear_checklist_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎒", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Udstyrs-tjekliste",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "$packedCount af ${items.size} pakket",
                            color = if (packedCount == items.size) ForestGreenLight else Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(14.dp))
                val grouped = items.groupBy { it.category }
                grouped.forEach { (cat, catItems) ->
                    Text(
                        text = cat,
                        color = AmberAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    catItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleItem(item.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (item.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (item.isChecked) ForestGreenLight else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = if (item.isChecked) Color.Gray else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (item.isMandatory) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = item.subtitle,
                                    color = Color.DarkGray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

// 8. GPX Export Card (Enhancement 12)
@Composable
fun GpxExportCard(onExportGpx: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gpx_export_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Eksportér rute til GPS / Ur",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Download GPX med etaper, shelters og waypoints til Garmin, Apple Watch m.fl.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                onClick = onExportGpx,
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(38.dp).testTag("export_gpx_button")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("GPX", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ShelterAndFriTeltningCard(
    shelters: List<ShelterEntity>,
    friTeltnings: List<FriTeltningEntity>,
    onSelectShelter: (ShelterEntity) -> Unit,
    onSelectFriTeltning: (FriTeltningEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(0) } // 0: Alle, 1: Shelters, 2: Fri Teltning

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shelter_fri_teltning_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface),
        border = BorderStroke(1.dp, GlassBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Overnatning på ruten",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${shelters.size} shelters • ${friTeltnings.size} fri teltningsskove",
                        color = ForestGreenLight,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "Alle (${shelters.size + friTeltnings.size})",
                    "Shelters (${shelters.size})",
                    "Fri Teltning (${friTeltnings.size})"
                ).forEachIndexed { index, title ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedFilter = index },
                        color = if (selectedFilter == index) ForestGreenPrimary else ForestSurfaceVariant
                    ) {
                        Text(
                            text = title,
                            color = if (selectedFilter == index) Color.White else Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content List
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedFilter == 0 || selectedFilter == 1) {
                    shelters.forEach { sh ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectShelter(sh) },
                            color = ForestDarkBg
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(ShelterBrown.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cabin,
                                        contentDescription = null,
                                        tint = ShelterBrown,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sh.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${sh.shelterType} • ${sh.capacity} pers • ${if (sh.bookingRequired) "Booking påkrævet" else "Fri afbenyttelse"}",
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                if (sh.fee) {
                                    Text(
                                        text = "${sh.priceDkk} kr.",
                                        color = AmberAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        text = "Gratis",
                                        color = ForestGreenLight,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedFilter == 0 || selectedFilter == 2) {
                    friTeltnings.forEach { ft ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectFriTeltning(ft) },
                            color = ForestDarkBg
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(ForestGreenPrimary.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forest,
                                        contentDescription = null,
                                        tint = ForestGreenPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ft.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${ft.zoneType} • ${ft.forestType}${if (ft.areaHa > 0) " • ${ft.areaHa.toInt()} ha" else ""}",
                                        color = ForestGreenLight,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "1-2-3 regel",
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
