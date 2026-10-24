package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.example.ui.navigation.NavigationMapOverlayManager
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.GeoPointDto
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenDark
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.NordicBlue
import com.example.ui.viewmodel.AheadOnRouteItem
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.NextStopInfo
import com.example.util.DanskStiTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    viewModel: NavigationViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val liveState by viewModel.liveState.collectAsState()
    val activePlan by viewModel.activePlan.collectAsState()
    val todayStageText by viewModel.todayStageText.collectAsState()
    val distanceRemainingToday by viewModel.distanceRemainingToday.collectAsState()
    val currentDay by viewModel.currentDay.collectAsState()
    val nextOvernight by viewModel.nextOvernight.collectAsState()
    val nextWater by viewModel.nextWater.collectAsState()
    val nextToilet by viewModel.nextToilet.collectAsState()
    val nextShop by viewModel.nextShop.collectAsState()
    val aheadItems by viewModel.aheadItems.collectAsState()
    val plannedRoute by viewModel.plannedRoute.collectAsState()
    val isBatterySaverMode by viewModel.isBatterySaverMode.collectAsState()
    val showSosSheet by viewModel.showSosSheet.collectAsState()
    val emergencyInfo by viewModel.emergencyInfo.collectAsState()
    val routeShelters by viewModel.routeShelters.collectAsState()
    val routeFriTeltning by viewModel.routeFriTeltning.collectAsState()
    val routeWaters by viewModel.routeWaters.collectAsState()
    val routeToilets by viewModel.routeToilets.collectAsState()
    val routeShops by viewModel.routeShops.collectAsState()
    val routeHazards by viewModel.routeHazards.collectAsState()
    val needsLocationPermission by viewModel.needsLocationPermission.collectAsState()
    val needsNotificationPermission by viewModel.needsNotificationPermission.collectAsState()

    val hasGpsFix = liveState.currentLat != null && liveState.currentLng != null
    val isLocationPermissionMissing = needsLocationPermission || liveState.locationPermissionMissing

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(needsNotificationPermission) {
        if (needsNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Phase 7a: Recorded GPS Track & Day stats
    val allRecordedDayStats by viewModel.allRecordedDayStats.collectAsState()
    val currentDayStat by viewModel.currentDayStat.collectAsState()

    var isDrawerExpanded by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var overlayManagerRef by remember { mutableStateOf<NavigationMapOverlayManager?>(null) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(isBatterySaverMode) {
        if (isBatterySaverMode) {
            mapViewRef = null
            overlayManagerRef = null
        }
    }

    BackHandler {
        onBack()
    }

    if (isBatterySaverMode) {
        // --- BATTERY MODE (Requirement E) ---
        // The SAME screen with a black background and the map hidden, large numbers only.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
                .testTag("battery_mode_screen")
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top row: Battery mode indicator & Exit button
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
                            text = "STRØMSPARER",
                            color = ForestGreenLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Exit battery mode
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF222222),
                        border = BorderStroke(1.dp, Color(0xFF444444)),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { viewModel.setBatterySaverMode(false) }
                            .testTag("exit_battery_mode_button")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Afslut",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Center: Large numbers only (scrollable at 200% font scale)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = todayStageText,
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Large remaining distance
                    Text(
                        text = "${com.example.ui.components.formatDanishKm(distanceRemainingToday, 1)} km",
                        style = com.example.ui.theme.TabularBodyStyle.copy(
                            color = Color.White,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1).sp
                        )
                    )
                    Text(
                        text = "tilbage i dag",
                        color = Color.Gray,
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Secondary numbers: Speed & Next stop
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${com.example.ui.components.formatDanishKm(liveState.speedKmh, 1)} km/t",
                                style = com.example.ui.theme.TabularBodyStyle.copy(
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Gangtempo",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        nextOvernight?.let { next ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${com.example.ui.components.formatDanishKm(next.distanceRemainingKm, 1)} km",
                                    style = com.example.ui.theme.TabularBodyStyle.copy(
                                        color = AmberAccent,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = next.name,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    // Recorded day stats in battery saver mode (Phase 7a)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                            Text(
                                text = formatMovingTime(movSecs),
                                style = com.example.ui.theme.TabularBodyStyle.copy(
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Aktiv tid",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val ascent = currentDayStat?.ascentMeters ?: 0
                            Text(
                                text = "+$ascent m",
                                style = com.example.ui.theme.TabularBodyStyle.copy(
                                    color = ForestGreenLight,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Stigning",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Bottom row: Small red SOS button, Share position & Emergency Coords
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Small red SOS button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AlertRed,
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { viewModel.setSosSheet(true) }
                                .testTag("battery_mode_sos_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "SOS Nødkald",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SOS 112",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Share position button (Phase 7d & Requirement 12)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF222222),
                            border = BorderStroke(1.dp, Color(0xFF444444)),
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(enabled = hasGpsFix) { viewModel.shareCurrentLocation(context) }
                                .testTag("battery_mode_share_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = if (hasGpsFix) Color.White else Color.Gray,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (hasGpsFix) "Del position" else "Venter på GPS-position",
                                    color = if (hasGpsFix) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    emergencyInfo?.let { em ->
                        Text(
                            text = em.decimalCoords,
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    } else {
        // --- NORMAL FULLSCREEN NAVIGATION MODE ---
        Box(modifier = modifier.fillMaxSize()) {
            // 1. Shared DanskStiMap fills the screen
            val navUserGeoPoint = remember(liveState.currentLat, liveState.currentLng) {
                val lat = liveState.currentLat
                val lng = liveState.currentLng
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }
            val navCombinedPois = remember(routeToilets, routeWaters, routeShops) {
                routeToilets + routeWaters + routeShops
            }

            val navFriTeltningEntities = remember(routeFriTeltning) {
                routeFriTeltning.map { it.first }
            }

            com.example.ui.map.DanskStiMap(
                modifier = Modifier.fillMaxSize(),
                mapTestTag = "navigation_map_view",
                initialZoom = 15.0,
                isSystemDarkOverride = isDark,
                userLocation = navUserGeoPoint,
                userHeadingDegrees = liveState.bearing,
                userAccuracyMeters = liveState.accuracyM,
                shelters = routeShelters,
                friTeltning = navFriTeltningEntities,
                pois = navCombinedPois,
                controlsTopPadding = 140.dp,
                attributionBottomPadding = 160.dp,
                layersButtonTestTag = "map_button_layers",
                compassButtonTestTag = "map_button_compass",
                myLocationButtonTestTag = "map_button_locate",
                onMyLocationClick = {
                    isFollowingUser = true
                    val lat = liveState.currentLat
                    val lng = liveState.currentLng
                    if (lat != null && lng != null) {
                        mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
                    }
                },
                onMapReady = { mv ->
                    mapViewRef = mv
                    // Requirement 21: Turn follow mode off only on a real drag (movement beyond ViewConfiguration touch slop), not on ACTION_DOWN.
                    val touchSlop = android.view.ViewConfiguration.get(mv.context).scaledTouchSlop
                    var touchDownX = 0f
                    var touchDownY = 0f
                    mv.setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                touchDownX = event.x
                                touchDownY = event.y
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - touchDownX
                                val dy = event.y - touchDownY
                                if (kotlin.math.hypot(dx, dy) > touchSlop) {
                                    isFollowingUser = false
                                }
                            }
                        }
                        false
                    }

                    val mgr = NavigationMapOverlayManager(
                        mapView = mv,
                        context = mv.context,
                        onSelectPoi = { /* POI clicked */ }
                    )
                    overlayManagerRef = mgr
                    plannedRoute?.let { route ->
                        mgr.setPlannedRoute(route, isDark)
                    }
                },
                updateOverlaysKey = Triple(plannedRoute, liveState, isFollowingUser),
                onUpdateOverlays = { _, _, isDarkMap ->
                    plannedRoute?.let { route ->
                        overlayManagerRef?.setPlannedRoute(route, isDarkMap)
                    }
                    overlayManagerRef?.updatePois(
                        shelters = routeShelters,
                        showShelters = true,
                        toilets = routeToilets,
                        showToilets = true,
                        waters = routeWaters,
                        showWater = true,
                        shops = routeShops,
                        showShops = true,
                        hazards = routeHazards,
                        showHazards = true,
                        parsedFriTeltning = routeFriTeltning,
                        showFriTeltning = true
                    )
                    overlayManagerRef?.updateTonightCamp(liveState.currentStage, emptyList())
                    overlayManagerRef?.onLocationTick(liveState, isFollowingUser)
                }
            )

            // 2. Top card: "Dag 2 af 7 · 9,4 km tilbage i dag · ankomst ca. 16:40" (Requirement G)
            val totalDaysCount = (activePlan?.targetDays ?: plannedRoute?.stages?.size ?: 1).coerceAtLeast(1)
            val remKmFormatted = com.example.ui.components.formatDanishKm(distanceRemainingToday, 1)
            val arrivalClock = remember(distanceRemainingToday, liveState.speedKmh) {
                val effectivePace = if (liveState.speedKmh > 1.5) liveState.speedKmh else 4.2
                val hoursLeft = (distanceRemainingToday / effectivePace).coerceIn(0.0, 24.0)
                val totalMinutesFrom9 = (9 * 60 + (hoursLeft * 60).toInt()) % (24 * 60)
                val hh = totalMinutesFrom9 / 60
                val mm = (totalMinutesFrom9 % 60 / 5) * 5
                String.format(Locale.ROOT, "%02d:%02d", hh, mm)
            }
            val topCardHeadline = "Dag $currentDay af $totalDaysCount · $remKmFormatted km tilbage i dag · ankomst ca. $arrivalClock"

            Surface(
                shape = com.example.ui.theme.CardShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
                    .testTag("nav_top_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("nav_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = topCardHeadline,
                            style = com.example.ui.theme.TabularBodyStyle.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("nav_top_headline_text")
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(ForestGreenLight)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "GPS aktiv • ${com.example.ui.components.formatDanishKm(liveState.speedKmh, 1)} km/t",
                                style = com.example.ui.theme.TabularLabelStyle,
                                color = ForestGreenPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Battery Mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleBatterySaver() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_battery_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatterySaver,
                            contentDescription = "Strømsparer-tilstand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share current location button (Phase 7d & Requirement 12)
                    IconButton(
                        onClick = { viewModel.shareCurrentLocation(context) },
                        enabled = hasGpsFix,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("nav_share_location_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = if (hasGpsFix) "Del min position" else "Venter på GPS-position",
                            tint = if (hasGpsFix) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 2a. Location Permission Explanation Banner when location permission is missing (Item 2)
            if (isLocationPermissionMissing) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, AmberAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 76.dp, start = 16.dp, end = 16.dp)
                        .testTag("location_permission_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.example.R.string.location_permission_required_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = androidx.compose.ui.res.stringResource(com.example.R.string.location_permission_required_explanation),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grant_location_permission_button")
                        ) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.example.R.string.grant_location_permission_action),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 2b. Private Forest Sunset Alert Banner (Phase 10 Requirement D)
            liveState.privateForestSunsetWarning?.let { warningText ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberAccent.copy(alpha = 0.95f),
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                        .testTag("nav_sunset_warning_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = warningText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.Black
                            )
                            Text(
                                text = "I privat skov må du kun færdes på stier fra kl. 06:00 til solnedgang.",
                                fontSize = 11.sp,
                                color = Color.Black.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // 4. Bottom card (Next overnight, Next water, Next shop) + Swipe up = "Foran dig på ruten" + elevation + Stop for i dag + SOS (Requirement G)
            Surface(
                shape = com.example.ui.theme.SheetTopShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .testTag("nav_bottom_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drag/Toggle Pill ("Foran dig på ruten")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDrawerExpanded = !isDrawerExpanded }
                            .testTag("nav_expand_ahead_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Foran dig på ruten",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isDrawerExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (isDrawerExpanded) "Skjul foran dig på ruten" else "Vis foran dig på ruten",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Requirement G: Next overnight, next water, next shop, each with km along the route
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Next overnight
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nav_next_overnight_row")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cabin,
                                    contentDescription = "Næste overnatning",
                                    tint = ForestGreenPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = nextOvernight?.name ?: "Overnatning",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = nextOvernight?.let { "${com.example.ui.components.formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                        style = com.example.ui.theme.TabularLabelStyle,
                                        color = ForestGreenPrimary
                                    )
                                }
                            }
                        }

                        // 2. Next water
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nav_next_water_row")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalDrink,
                                    contentDescription = "Næste vandpost",
                                    tint = NordicBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = nextWater?.name ?: "Vandpost",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = nextWater?.let { "${com.example.ui.components.formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                        style = com.example.ui.theme.TabularLabelStyle,
                                        color = NordicBlue
                                    )
                                }
                            }
                        }

                        // 3. Next shop
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("nav_next_shop_row")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = "Næste indkøb",
                                    tint = AmberAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = nextShop?.name ?: "Indkøb",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = nextShop?.let { "${com.example.ui.components.formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                        style = com.example.ui.theme.TabularLabelStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Expanded: "Foran dig på ruten" list + elevation profile
                    AnimatedVisibility(
                        visible = isDrawerExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Foran dig på ruten",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Elevation profile with current position cursor
                            val routePoints = plannedRoute?.points?.map { GeoPointDto(it.lat, it.lng, it.ele) } ?: emptyList()
                            ElevationPositionCanvas(
                                points = routePoints,
                                progressFraction = if (routePoints.isEmpty()) 0f else (liveState.distanceWalkedTodayKm / (distanceRemainingToday + liveState.distanceWalkedTodayKm).coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                            )

                            // Upcoming amenities list
                            if (aheadItems.isEmpty()) {
                                Text(
                                    text = "Ingen yderligere interessepunkter foran dig på dagens etape.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    aheadItems.take(5).forEach { item ->
                                        AheadItemRow(item = item)
                                    }
                                }
                            }

                            // Phase 7a: Recorded GPS Track & Day Stats Card
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth().testTag("recorded_day_stats_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Optaget GPS-spor (Dag $currentDay)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = ForestGreenPrimary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "${currentDayStat?.pointCount ?: 0} punkter",
                                                fontSize = 11.sp,
                                                color = ForestGreenPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    // 3 Key Stats: Distance, Moving Time, Ascent
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Distance
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Distance",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val dist = currentDayStat?.distanceKm ?: liveState.distanceWalkedTodayKm
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", dist)} km",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        // Moving time
                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Bevægelsestid",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                                            Text(
                                                text = formatMovingTime(movSecs),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        // Ascent
                                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "Stigning",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val ascent = currentDayStat?.ascentMeters ?: 0
                                            Text(
                                                text = "+$ascent m",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ForestGreenPrimary
                                            )
                                        }
                                    }

                                    // GPX Export Buttons (Phase 7a)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.exportDayGpx(context, currentDay) },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f).testTag("export_day_gpx_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Dags-spor GPX", fontSize = 11.sp, maxLines = 1)
                                        }

                                        Button(
                                            onClick = { viewModel.exportWholeTripGpx(context) },
                                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f).testTag("export_whole_trip_gpx_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Hele turen GPX", fontSize = 11.sp, maxLines = 1)
                                        }
                                    }

                                    // Del min position button (Phase 7d & Requirement 12)
                                    OutlinedButton(
                                        onClick = { viewModel.shareCurrentLocation(context) },
                                        enabled = hasGpsFix,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("share_current_location_button"),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (hasGpsFix) NordicBlue else Color.Gray)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (hasGpsFix) "Del min aktuelle position (kortlink)" else "Venter på GPS-position",
                                            fontSize = 12.sp,
                                            color = if (hasGpsFix) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Multi-day history
                                    if (allRecordedDayStats.size > 1) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Tidligere dages optagede spor:",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        allRecordedDayStats.filter { it.dayNumber != currentDay }.forEach { dayStat ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Dag ${dayStat.dayNumber}: ${String.format(Locale.US, "%.1f", dayStat.distanceKm)} km · ${formatMovingTime(dayStat.movingTimeSeconds)} · +${dayStat.ascentMeters}m",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                IconButton(
                                                    onClick = { viewModel.exportDayGpx(context, dayStat.dayNumber) },
                                                    modifier = Modifier.size(28.dp).testTag("export_day_${dayStat.dayNumber}_button")
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = "Eksporter dag ${dayStat.dayNumber}", modifier = Modifier.size(14.dp), tint = ForestGreenPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Requirement G: Large "Stop for i dag" and a small red SOS button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.stopTrip()
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .testTag("stop_for_today_button")
                        ) {
                            Text(
                                text = "Stop for i dag",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AlertRed,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.setSosSheet(true) }
                                .testTag("nav_sos_button")
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "SOS",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- SOS Bottom Sheet (Phase 0 SOS sheet) ---
    if (showSosSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setSosSheet(false) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = AlertRed.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = AlertRed,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Text(
                    text = "🚨 Dansk Nødkald (112)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlertRed
                )

                // Exact Coordinates
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Din nøjagtige GPS-position:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val coordsText = emergencyInfo?.decimalCoords
                            ?: if (liveState.currentLat != null && liveState.currentLng != null) {
                                "${liveState.currentLat}, ${liveState.currentLng}"
                            } else {
                                "Venter på GPS-position"
                            }
                        Text(
                            text = coordsText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        emergencyInfo?.spokenWgs84?.let { spoken ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Læses højt til alarmcentralen: $spoken",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 112 Call Button (Direct dialer)
                Button(
                    onClick = {
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:112")
                        }
                        context.startActivity(dialIntent)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("call_112_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ring 112 nu",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.setSosSheet(false) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Luk nødsystem")
                }
            }
        }
    }
}

@Composable
fun AheadItemRow(item: AheadOnRouteItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (item.type) {
                    "SHELTER" -> Icons.Default.Cabin
                    "WATER" -> Icons.Default.LocalDrink
                    "TOILET" -> Icons.Default.Wc
                    "SHOP" -> Icons.Default.ShoppingBag
                    else -> Icons.Default.Navigation
                },
                contentDescription = null,
                tint = ForestGreenPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = item.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.detourMeters > 30) {
                    Text(
                        text = "${item.detourMeters.toInt()}m afstikker",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "${String.format(Locale.US, "%.1f", item.kmRemaining)} km",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ElevationPositionCanvas(
    points: List<GeoPointDto>,
    progressFraction: Float,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (points.size < 2) return@Canvas

            val validElevations = points.mapNotNull { it.ele }
            if (validElevations.isEmpty()) return@Canvas

            val minEle = validElevations.minOrNull() ?: 0.0
            val maxEle = validElevations.maxOrNull() ?: 0.0
            val dEle = (maxEle - minEle).coerceAtLeast(10.0)

            val step = size.width / (points.size - 1)
            val path = Path()

            points.forEachIndexed { i, pt ->
                val x = i * step
                val eleVal = pt.ele ?: minEle
                val y = ((1.0 - (eleVal - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = ForestGreenPrimary,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Current position dot on profile
            val curX = (size.width * progressFraction).coerceIn(0f, size.width)
            val pointIdx = (progressFraction * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
            val curEle = points[pointIdx].ele ?: minEle
            val curY = ((1.0 - (curEle - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)

            drawCircle(color = NordicBlue, radius = 5.dp.toPx(), center = Offset(curX, curY))
        }
    }
}

private fun formatMovingTime(seconds: Long): String {
    if (seconds <= 0L) return "0 min"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}t ${minutes}m" else "${minutes} min"
}

