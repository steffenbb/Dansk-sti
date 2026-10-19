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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    // Phase 7a: Recorded GPS Track & Day stats
    val allRecordedDayStats by viewModel.allRecordedDayStats.collectAsState()
    val currentDayStat by viewModel.currentDayStat.collectAsState()

    var isDrawerExpanded by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var overlayManagerRef by remember { mutableStateOf<NavigationMapOverlayManager?>(null) }
    val isDark = isSystemInDarkTheme()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapViewRef?.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onDetach()
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
                            .clickable { viewModel.setBatterySaverMode(false) }
                            .testTag("exit_battery_mode_button")
                    ) {
                        Text(
                            text = "Afslut",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                // Center: Large numbers only
                Column(
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
                        text = "${String.format(Locale.US, "%.1f", distanceRemainingToday)} km",
                        color = Color.White,
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1).sp
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
                                text = "${String.format(Locale.US, "%.1f", liveState.speedKmh)} km/t",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
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
                                    text = "${String.format(Locale.US, "%.1f", next.distanceRemainingKm)} km",
                                    color = AmberAccent,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = next.name,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    maxLines = 1
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
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
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
                                color = ForestGreenLight,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
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
                                .clickable { viewModel.setSosSheet(true) }
                                .testTag("battery_mode_sos_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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

                        // Share position button (Phase 7d)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF222222),
                            border = BorderStroke(1.dp, Color(0xFF444444)),
                            modifier = Modifier
                                .clickable { viewModel.shareCurrentLocation(context) }
                                .testTag("battery_mode_share_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Del position",
                                    color = Color.White,
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
            // 1. Map fills the screen
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        mapViewRef = this
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        DanskStiTileSource.applyTo(this, isDark)
                        controller.setZoom(15.0)

                        setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                                event.action == android.view.MotionEvent.ACTION_MOVE
                            ) {
                                isFollowingUser = false
                            }
                            false
                        }

                        val mgr = NavigationMapOverlayManager(
                            mapView = this,
                            context = ctx,
                            onSelectPoi = { /* POI clicked */ }
                        )
                        overlayManagerRef = mgr
                    }
                },
                update = { mapView ->
                    DanskStiTileSource.applyTo(mapView, isDark)

                    plannedRoute?.let { route ->
                        overlayManagerRef?.setPlannedRoute(route)
                    }

                    overlayManagerRef?.updateTonightCamp(liveState.currentStage, emptyList())
                    overlayManagerRef?.onLocationTick(liveState, isFollowingUser)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("navigation_map_view")
            )

            // 2. Top bar: today's stage "Dag 2 af 7 · 9,4 km tilbage i dag" + SOS + Back
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
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
                            text = todayStageText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
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
                                text = "GPS aktiv • ${String.format(Locale.US, "%.1f", liveState.speedKmh)} km/t",
                                fontSize = 11.sp,
                                color = ForestGreenLight,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Battery Mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleBatterySaver() },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("toggle_battery_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatterySaver,
                            contentDescription = "Strømsparer-tilstand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share current location button (Phase 7d)
                    IconButton(
                        onClick = { viewModel.shareCurrentLocation(context) },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("nav_share_location_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Del min position",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Red small SOS button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AlertRed,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { viewModel.setSosSheet(true) }
                            .testTag("nav_sos_button")
                    ) {
                        Text(
                            text = "SOS",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
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

            // 3. Map buttons on the right: locate/follow, compass, layers
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Locate / Follow button
                Surface(
                    shape = CircleShape,
                    color = if (isFollowingUser) ForestGreenPrimary else MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            isFollowingUser = true
                            val lat = liveState.currentLat
                            val lng = liveState.currentLng
                            if (lat != null && lng != null) {
                                mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
                            }
                        }
                        .testTag("map_button_locate")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Centrer min position",
                            tint = if (isFollowingUser) Color.White else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Compass button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            mapViewRef?.mapOrientation = 0f
                        }
                        .testTag("map_button_compass")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Nulstil kompas mod nord",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Layers button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.cycleMapTileMode() }
                        .testTag("map_button_layers")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Skift kortlag",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 4. Bottom card (Next overnight, Next water/toilet/shop) + Swipe up = "Foran dig på ruten" list + elevation
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Drag/Toggle Pill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDrawerExpanded = !isDrawerExpanded },
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isDrawerExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (isDrawerExpanded) "Skjul foran dig" else "Vis foran dig",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Primary Summary: Next Overnight & Next Amenity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Next overnight
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = ForestGreenPrimary.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Cabin,
                                        contentDescription = null,
                                        tint = ForestGreenPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = nextOvernight?.name ?: "Mål for i dag",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = nextOvernight?.let { "${String.format(Locale.US, "%.1f", it.distanceRemainingKm)} km tilbage" } ?: "Ingen lejr registreret",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Next water/amenity
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = NordicBlue.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.LocalDrink,
                                        contentDescription = null,
                                        tint = NordicBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = nextWater?.let { "${String.format(Locale.US, "%.1f", it.distanceRemainingKm)} km" } ?: "-- km",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Vandpost",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

                                    // Del min position button (Phase 7d)
                                    OutlinedButton(
                                        onClick = { viewModel.shareCurrentLocation(context) },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("share_current_location_button"),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp), tint = NordicBlue)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Del min aktuelle position (kortlink)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
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
                        Text(
                            text = emergencyInfo?.decimalCoords ?: "${liveState.currentLat ?: 55.0}, ${liveState.currentLng ?: 10.0}",
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

