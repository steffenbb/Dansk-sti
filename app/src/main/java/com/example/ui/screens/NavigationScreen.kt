package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.TurnCue
import com.example.ui.components.formatDanishKm
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.NavigationMapOverlayManager
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CardShape
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.SheetTopShape
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.AheadOnRouteItem
import com.example.ui.viewmodel.DayProgressStopIcon
import com.example.ui.viewmodel.NavigationMapMode
import com.example.ui.viewmodel.NavigationViewModel
import com.example.util.routing.TurnCueGenerator
import kotlinx.coroutines.delay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sets or clears [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] so the screen is kept on
 * ONLY while active navigation is running and the "Hold skærmen tændt" setting is enabled.
 */
fun updateKeepScreenOnWindowFlag(
    window: Window?,
    isNavigating: Boolean,
    keepScreenOnEnabled: Boolean
) {
    if (window == null) return
    if (isNavigating && keepScreenOnEnabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        if (params != null && params.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
    }
}

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    viewModel: NavigationViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val liveState by viewModel.liveState.collectAsState()
    val activePlan by viewModel.activePlan.collectAsState()
    val todayStageText by viewModel.todayStageText.collectAsState()
    val distanceRemainingToday by viewModel.distanceRemainingToday.collectAsState()
    val mitTempoKmh by viewModel.mitTempoKmh.collectAsState()
    val currentDay by viewModel.currentDay.collectAsState()
    val nextOvernight by viewModel.nextOvernight.collectAsState()
    val nextWater by viewModel.nextWater.collectAsState()
    val nextShop by viewModel.nextShop.collectAsState()
    val aheadItems by viewModel.aheadItems.collectAsState()
    val dayProgressStops by viewModel.dayProgressStops.collectAsState()
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
    val mapMode by viewModel.mapMode.collectAsState()
    val backToRoutePoints by viewModel.backToRoutePoints.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val voiceGuidance by viewModel.voiceGuidance.collectAsState()
    val sunsetTime by viewModel.sunsetTime.collectAsState()

    val hasGpsFix = liveState.currentLat != null && liveState.currentLng != null
    val isLocationPermissionMissing = needsLocationPermission || liveState.locationPermissionMissing

    // Requirement 5: FLAG_KEEP_SCREEN_ON only while navigating; dim after 30 s without touch
    var lastTouchTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isActivelyNavigating = liveState.isTracking

    LaunchedEffect(isActivelyNavigating, keepScreenOn, activity) {
        updateKeepScreenOnWindowFlag(
            window = activity?.window,
            isNavigating = isActivelyNavigating,
            keepScreenOnEnabled = keepScreenOn
        )
    }

    DisposableEffect(activity) {
        onDispose {
            updateKeepScreenOnWindowFlag(
                window = activity?.window,
                isNavigating = false,
                keepScreenOnEnabled = false
            )
        }
    }

    // Dim screen after 30 s without touch when navigating & keepScreenOn is enabled
    var nowClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isActivelyNavigating, keepScreenOn) {
        while (true) {
            delay(5_000L)
            nowClockMs = System.currentTimeMillis()
            val win = activity?.window
            if (win != null && isActivelyNavigating && keepScreenOn) {
                val idleMs = nowClockMs - lastTouchTimeMs
                val params = win.attributes
                if (idleMs >= 30_000L) {
                    if (params.screenBrightness != 0.15f) {
                        params.screenBrightness = 0.15f
                        win.attributes = params
                    }
                }
            }
        }
    }

    // Requirement 2: Optional Danish voice via TextToSpeech (off by default)
    var ttsRef by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var lastSpokenKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(voiceGuidance) {
        var engine: TextToSpeech? = null
        if (voiceGuidance) {
            runCatching {
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        engine?.language = Locale("da", "DK")
                        ttsReady = true
                    }
                }
                ttsRef = engine
            }
        }
        onDispose {
            runCatching {
                engine?.stop()
                engine?.shutdown()
            }
            ttsRef = null
            ttsReady = false
        }
    }

    LaunchedEffect(liveState.nextTurnCue, liveState.distanceToNextCueMeters, voiceGuidance, ttsReady) {
        if (!voiceGuidance || !ttsReady) return@LaunchedEffect
        val cue = liveState.nextTurnCue ?: return@LaunchedEffect
        val distM = liveState.distanceToNextCueMeters ?: return@LaunchedEffect
        val thresholdBucket = when {
            distM <= TurnCueGenerator.CUE_SECOND_ALERT_METERS -> "30"
            distM <= TurnCueGenerator.CUE_FIRST_ALERT_METERS -> "150"
            else -> null
        }
        if (thresholdBucket != null) {
            val key = "${cue.pointIndex}_${cue.walkKm}_$thresholdBucket"
            if (key != lastSpokenKey) {
                lastSpokenKey = key
                val speechText = TurnCueGenerator.formatCueBannerText(cue, distM)
                runCatching {
                    ttsRef?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, key)
                }
            }
        }
    }

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
    var showNavMenu by remember { mutableStateOf(false) }
    var dismissedCampArrivalForDay by remember { mutableStateOf(-1) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var overlayManagerRef by remember { mutableStateOf<NavigationMapOverlayManager?>(null) }
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(isBatterySaverMode) {
        if (isBatterySaverMode) {
            mapViewRef = null
            overlayManagerRef = null
        }
    }

    // Requirement 4: Map mode & auto-zoom (17 near a cue or below 2 km/h, 16 otherwise)
    val targetAutoZoom = remember(liveState.distanceToNextCueMeters, liveState.speedKmh) {
        val nearCue = (liveState.distanceToNextCueMeters ?: Double.MAX_VALUE) <= TurnCueGenerator.CUE_FIRST_ALERT_METERS
        if (nearCue || liveState.speedKmh < 2.0) 17.0 else 16.0
    }

    LaunchedEffect(mapMode, liveState.currentLat, liveState.currentLng, liveState.bearing, targetAutoZoom, plannedRoute, currentDay) {
        val mv = mapViewRef ?: return@LaunchedEffect
        when (mapMode) {
            NavigationMapMode.FOLLOW_NORTH_UP -> {
                isFollowingUser = true
                mv.mapOrientation = 0f
                if (kotlin.math.abs(mv.zoomLevelDouble - targetAutoZoom) >= 0.4) {
                    mv.controller.setZoom(targetAutoZoom)
                }
                val lat = liveState.currentLat
                val lng = liveState.currentLng
                if (lat != null && lng != null) {
                    mv.controller.animateTo(GeoPoint(lat, lng))
                }
            }
            NavigationMapMode.FOLLOW_HEADING_UP -> {
                isFollowingUser = true
                mv.mapOrientation = -liveState.bearing
                if (kotlin.math.abs(mv.zoomLevelDouble - targetAutoZoom) >= 0.4) {
                    mv.controller.setZoom(targetAutoZoom)
                }
                val lat = liveState.currentLat
                val lng = liveState.currentLng
                if (lat != null && lng != null) {
                    mv.controller.animateTo(GeoPoint(lat, lng))
                }
            }
            NavigationMapMode.OVERVIEW -> {
                isFollowingUser = false
                mv.mapOrientation = 0f
                val route = plannedRoute ?: liveState.plannedRoute
                val stage = liveState.currentStage
                    ?: route?.stages?.getOrNull(currentDay - 1)
                    ?: route?.stages?.firstOrNull()
                val stagePts = if (route != null && stage != null) {
                    route.points.filter { it.walkKm in stage.startWalkKm..stage.endWalkKm }
                        .ifEmpty { route.points }
                } else {
                    route?.points ?: emptyList()
                }
                if (stagePts.size >= 2) {
                    val minLat = stagePts.minOf { it.lat } - 0.005
                    val maxLat = stagePts.maxOf { it.lat } + 0.005
                    val minLng = stagePts.minOf { it.lng } - 0.005
                    val maxLng = stagePts.maxOf { it.lng } + 0.005
                    runCatching {
                        mv.zoomToBoundingBox(BoundingBox(maxLat, maxLng, minLat, minLng), true, 64)
                    }
                }
            }
        }
        mv.invalidate()
    }

    LaunchedEffect(backToRoutePoints) {
        overlayManagerRef?.setBackToRoutePoints(backToRoutePoints)
    }

    BackHandler {
        onBack()
    }

    // Effective pace & arrival time calculation (Requirement 2 of Fix Round 8: use mitTempoKmh when speed < 1.5 km/h)
    val effectivePaceKmh = remember(liveState.speedKmh, mitTempoKmh) {
        (if (liveState.speedKmh >= 1.5) liveState.speedKmh else mitTempoKmh).coerceAtLeast(0.5)
    }
    val arrivalClock = remember(distanceRemainingToday, effectivePaceKmh) {
        val hoursLeft = (distanceRemainingToday / effectivePaceKmh).coerceIn(0.0, 24.0)
        val totalMinutesFrom9 = (9 * 60 + (hoursLeft * 60).toInt()) % (24 * 60)
        val hh = totalMinutesFrom9 / 60
        val mm = (totalMinutesFrom9 % 60 / 5) * 5
        String.format(Locale.ROOT, "%02d:%02d", hh, mm)
    }
    val totalDaysCount = (activePlan?.targetDays ?: plannedRoute?.stages?.size ?: 1).coerceAtLeast(1)
    val remKmFormatted = formatDanishKm(distanceRemainingToday, 1)
    val topCardHeadline = "Dag $currentDay af $totalDaysCount · $remKmFormatted km tilbage i dag · ankomst ca. $arrivalClock"

    // Requirement 2: Cue banner text (next cue within 500 m, else next stop)
    val activeCueWithin500m: Pair<TurnCue, Double>? = remember(
        liveState.nextTurnCue,
        liveState.distanceToNextCueMeters,
        plannedRoute,
        liveState.totalDistanceWalkedKm
    ) {
        val liveCue = liveState.nextTurnCue
        val liveDist = liveState.distanceToNextCueMeters
        if (liveCue != null && liveDist != null && liveDist <= TurnCueGenerator.CUE_MAX_DISPLAY_METERS) {
            liveCue to liveDist
        } else {
            val route = plannedRoute ?: liveState.plannedRoute
            val cues = if (route != null) TurnCueGenerator.computeTurnCuesForPlannedRoute(route) else emptyList()
            val candidate = cues.firstOrNull { c ->
                val dM = (c.walkKm - liveState.totalDistanceWalkedKm) * 1000.0
                dM >= -10.0 && dM <= TurnCueGenerator.CUE_MAX_DISPLAY_METERS
            }
            if (candidate != null) {
                val dM = max(0.0, (candidate.walkKm - liveState.totalDistanceWalkedKm) * 1000.0)
                candidate to dM
            } else {
                null
            }
        }
    }

    val cueBannerPrimaryText = remember(
        activeCueWithin500m,
        nextWater,
        nextShop,
        nextOvernight,
        distanceRemainingToday
    ) {
        if (activeCueWithin500m != null) {
            TurnCueGenerator.formatCueBannerText(activeCueWithin500m.first, activeCueWithin500m.second)
        } else if (nextWater != null) {
            "Næste: vand om ${formatDanishKm(nextWater!!.distanceRemainingKm, 1)} km"
        } else if (nextShop != null) {
            "Næste: indkøb om ${formatDanishKm(nextShop!!.distanceRemainingKm, 1)} km"
        } else if (nextOvernight != null) {
            "Næste: ${nextOvernight!!.name} om ${formatDanishKm(nextOvernight!!.distanceRemainingKm, 1)} km"
        } else {
            "Næste: lejr om ${formatDanishKm(distanceRemainingToday, 1)} km"
        }
    }

    // Requirement 6: GPS weak chip when accuracy > 30 m or no fix for 60 s
    val isGpsWeakOrStale = remember(liveState.accuracyM, liveState.lastFixTimestampMs, nowClockMs) {
        val highAccuracyError = liveState.accuracyM > 30f
        val staleFix = liveState.lastFixTimestampMs > 0L && (nowClockMs - liveState.lastFixTimestampMs) >= 60_000L
        highAccuracyError || staleFix
    }
    val gpsStaleMinutes = remember(liveState.lastFixTimestampMs, nowClockMs) {
        if (liveState.lastFixTimestampMs > 0L) {
            max(1L, (nowClockMs - liveState.lastFixTimestampMs) / 60_000L).toInt()
        } else {
            1
        }
    }

    // Requirement 9: Sunset warning when remaining km at user's pace needs more time than daylight left
    val isDaylightWarningVisible = remember(distanceRemainingToday, mitTempoKmh, sunsetTime, nowClockMs) {
        if (distanceRemainingToday <= 0.2) {
            false
        } else {
            val parts = sunsetTime.split(":")
            val sunsetHour = parts.getOrNull(0)?.toIntOrNull() ?: 20
            val sunsetMin = parts.getOrNull(1)?.toIntOrNull() ?: 30
            val cal = Calendar.getInstance().apply { timeInMillis = nowClockMs }
            val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val sunsetMinutes = sunsetHour * 60 + sunsetMin
            val daylightHoursLeft = (sunsetMinutes - nowMinutes) / 60.0
            val hoursNeededAtMyPace = distanceRemainingToday / mitTempoKmh.coerceAtLeast(1.0)
            daylightHoursLeft in 0.0..10.0 && hoursNeededAtMyPace > daylightHoursLeft
        }
    }

    // Requirement 9: Within 150 m of tonight's camp -> "Du er ved <navn> · Stop for i dag?"
    val campNameForArrival = liveState.currentStage?.stopName ?: nextOvernight?.name
    val isNearTonightCamp = remember(distanceRemainingToday, campNameForArrival, currentDay, dismissedCampArrivalForDay, liveState.isTracking) {
        liveState.isTracking &&
            !campNameForArrival.isNullOrBlank() &&
            distanceRemainingToday > 0.0 &&
            distanceRemainingToday * 1000.0 <= 150.0 &&
            dismissedCampArrivalForDay != currentDay
    }

    if (isBatterySaverMode) {
        // --- BATTERY MODE (Requirement E) ---
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
                            text = stringResource(R.string.nav_battery_saver_label),
                            color = ForestGreenLight,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

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
                                text = stringResource(R.string.nav_battery_saver_exit),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Center: Large numbers only
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

                    Text(
                        text = cueBannerPrimaryText,
                        color = AmberAccent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(distanceRemainingToday, 1)),
                        style = TabularBodyStyle.copy(
                            color = Color.White,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1).sp
                        )
                    )
                    Text(
                        text = stringResource(R.string.nav_remaining_today_label),
                        color = Color.Gray,
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_km_t_57, formatDanishKm(liveState.speedKmh, 1)),
                                style = TabularBodyStyle.copy(
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = stringResource(R.string.nav_walking_pace_label),
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        nextOvernight?.let { next ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(next.distanceRemainingKm, 1)),
                                    style = TabularBodyStyle.copy(
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                            Text(
                                text = formatMovingTime(movSecs),
                                style = TabularBodyStyle.copy(
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = stringResource(R.string.nav_active_time_label),
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val ascent = currentDayStat?.ascentMeters ?: 0
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_m_58, ascent),
                                style = TabularBodyStyle.copy(
                                    color = ForestGreenLight,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = stringResource(R.string.stat_elevation),
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
                                    contentDescription = stringResource(R.string.ui_navigationscreen_sos_n_dkald_59),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.nav_sos_112),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

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
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastTouchTimeMs = System.currentTimeMillis()
                            val win = activity?.window
                            if (win != null) {
                                val params = win.attributes
                                if (params != null && params.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                    win.attributes = params
                                }
                            }
                        }
                    }
                }
        ) {
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

            DanskStiMap(
                modifier = Modifier.fillMaxSize(),
                mapTestTag = "navigation_map_view",
                initialZoom = 16.0,
                isSystemDarkOverride = isDark,
                userLocation = navUserGeoPoint,
                userHeadingDegrees = liveState.bearing,
                userAccuracyMeters = liveState.accuracyM,
                shelters = routeShelters,
                friTeltning = navFriTeltningEntities,
                pois = navCombinedPois,
                controlsTopPadding = 16.dp,
                controlsEndPadding = 16.dp,
                attributionBottomPadding = 220.dp,
                layersButtonTestTag = "map_button_layers",
                compassButtonTestTag = "map_button_compass",
                myLocationButtonTestTag = "map_button_locate",
                onMyLocationClick = {
                    isFollowingUser = true
                    if (mapMode == NavigationMapMode.OVERVIEW) {
                        viewModel.onMapTappedInOverview()
                    }
                    val lat = liveState.currentLat
                    val lng = liveState.currentLng
                    if (lat != null && lng != null) {
                        mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
                    }
                },
                onMapReady = { mv ->
                    mapViewRef = mv
                    val touchSlop = ViewConfiguration.get(mv.context).scaledTouchSlop
                    var touchDownX = 0f
                    var touchDownY = 0f
                    var movedBeyondSlop = false
                    mv.setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                touchDownX = event.x
                                touchDownY = event.y
                                movedBeyondSlop = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - touchDownX
                                val dy = event.y - touchDownY
                                if (kotlin.math.hypot(dx, dy) > touchSlop) {
                                    movedBeyondSlop = true
                                    isFollowingUser = false
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!movedBeyondSlop && mapMode == NavigationMapMode.OVERVIEW) {
                                    viewModel.onMapTappedInOverview()
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
                    if (backToRoutePoints.isNotEmpty()) {
                        mgr.setBackToRoutePoints(backToRoutePoints)
                    }
                },
                updateOverlaysKey = Triple(plannedRoute, liveState, isFollowingUser to backToRoutePoints),
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
                    overlayManagerRef?.setBackToRoutePoints(backToRoutePoints)
                    overlayManagerRef?.onLocationTick(liveState, isFollowingUser)
                }
            )

            // 2. Top Stack: Cue Banner + Off-Route Red Banner + GPS State Chip + Warnings (padded on right so it never overlaps map_controls_column)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 76.dp, top = 8.dp, bottom = 8.dp)
                    .align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 2.1 Top Cue Banner (`nav_top_card` / `nav_cue_banner`)
                val isImmediateTurn = activeCueWithin500m != null &&
                    activeCueWithin500m.second <= TurnCueGenerator.CUE_FIRST_ALERT_METERS
                Surface(
                    shape = CardShape,
                    color = if (isImmediateTurn) ForestGreenPrimary else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_top_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("nav_cue_banner")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    contentDescription = stringResource(R.string.btn_back),
                                    tint = if (isImmediateTurn) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Turn cue arrow icon or next stop icon
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isImmediateTurn) {
                                    Color.White.copy(alpha = 0.2f)
                                } else {
                                    ForestGreenPrimary.copy(alpha = 0.14f)
                                },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    TurnCueDirectionIcon(
                                        cue = activeCueWithin500m?.first,
                                        tint = if (isImmediateTurn) Color.White else ForestGreenPrimary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cueBannerPrimaryText,
                                    style = TabularBodyStyle.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = if (isImmediateTurn) Color.White else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("nav_cue_primary_text")
                                )
                                Text(
                                    text = topCardHeadline,
                                    style = TabularBodyStyle.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (isImmediateTurn) {
                                        Color.White.copy(alpha = 0.9f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("nav_top_headline_text")
                                )
                            }
                        }
                    }
                }

                // 2.2 Requirement 3: Off-Route Red Banner ("Du er 140 m fra ruten") with arrow + 2 buttons
                if (liveState.isOffTrailWarning && !liveState.isDetourMuted) {
                    val offMeters = liveState.offTrailDistanceMeters.roundToInt().coerceAtLeast(1)
                    val relativeArrowDeg = (liveState.bearingToPointAhead - liveState.bearing)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AlertRed,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav_off_route_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowUpward,
                                            contentDescription = "Retning mod ruten",
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(22.dp)
                                                .rotate(relativeArrowDeg)
                                                .testTag("nav_off_route_arrow")
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.nav_off_route_banner_format, offMeters),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("nav_off_route_text")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.routeMeBack() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = AlertRed
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("nav_reroute_back_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_reroute_back),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = { viewModel.takeDetour() },
                                    border = BorderStroke(1.5.dp, Color.White),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("nav_take_detour_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_taking_detour),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // 2.3 Requirement 6: GPS State Chip when accuracy > 30 m or no fix for 60 s
                if (isGpsWeakOrStale) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AmberAccent.copy(alpha = 0.95f),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("nav_gps_weak_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GpsOff,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.nav_gps_weak_minutes_format, gpsStaleMinutes),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                // 2.4 Requirement 9: Sunset daylight warning banner
                if (isDaylightWarningVisible) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AmberAccent.copy(alpha = 0.95f),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav_daylight_warning_banner")
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
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(
                                    R.string.nav_daylight_warning_format,
                                    formatDanishKm(distanceRemainingToday, 1),
                                    sunsetTime
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                // 2.5 Private Forest Sunset Alert Banner
                liveState.privateForestSunsetWarning?.let { warningText ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AmberAccent.copy(alpha = 0.95f),
                        shadowElevation = 4.dp,
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
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
                                    text = stringResource(R.string.nav_private_forest_hours_note),
                                    fontSize = 11.sp,
                                    color = Color.Black.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }

                // 2.6 Location Permission Explanation Banner when location permission is missing
                if (isLocationPermissionMissing) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.dp, AmberAccent),
                        modifier = Modifier
                            .fillMaxWidth()
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
                                    text = stringResource(R.string.location_permission_required_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(R.string.location_permission_required_explanation),
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
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("grant_location_permission_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.grant_location_permission_action),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 3. Bottom Stack: Day Flow Cards + Walking Controls (all in bottom third, >= 48 dp) + Stats Strip & Swipe-Up Sheet
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 3.1 Requirement 9: Within 150 m of tonight's camp -> "Du er ved <navn> · Stop for i dag?"
                if (isNearTonightCamp && campNameForArrival != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.5.dp, ForestGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("nav_camp_arrival_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.nav_camp_arrival_prompt_format, campNameForArrival),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val walkedToday = formatDanishKm(liveState.distanceWalkedTodayKm, 1)
                            val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                            val ascent = currentDayStat?.ascentMeters ?: 0
                            Text(
                                text = "Dag $currentDay: $walkedToday km gået · ${formatMovingTime(movSecs)} · +$ascent m",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.stopForToday() },
                                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("nav_camp_arrival_confirm_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_stop_for_today),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = { dismissedCampArrivalForDay = currentDay },
                                    modifier = Modifier
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("nav_camp_arrival_dismiss_button")
                                ) {
                                    Text("Fortsæt")
                                }
                            }
                        }
                    }
                }

                // 3.2 Requirement 9: When the app opens the next day, show "Fortsæt dag N" with distance, first water stop and sunset
                if (liveState.showContinueDayPrompt) {
                    val stageKm = liveState.currentStage?.stageKm ?: distanceRemainingToday
                    val firstWaterText = nextWater?.let { "${it.name} (${formatDanishKm(it.distanceRemainingKm, 1)} km)" } ?: "Ingen registreret"
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.5.dp, ForestGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("nav_continue_day_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.nav_continue_day_title_format, currentDay),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ForestGreenPrimary
                            )
                            Text(
                                text = "Etape: ${formatDanishKm(stageKm, 1)} km · Første vand: $firstWaterText · Solnedgang: $sunsetTime",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Button(
                                onClick = {
                                    viewModel.setPaused(false)
                                    viewModel.dismissContinueDayPrompt()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("nav_continue_day_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_continue_day_title_format, currentDay),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 3.3 Requirement 4, 5, 7, 10: Walking Control Strip in Bottom Third (all controls >= 48 dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Map mode cycle button: Følg (nord op) -> Følg (retning op) -> Oversigt
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { viewModel.cycleMapMode() }
                            .testTag("nav_map_mode_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = when (mapMode) {
                                    NavigationMapMode.FOLLOW_NORTH_UP -> Icons.Default.Navigation
                                    NavigationMapMode.FOLLOW_HEADING_UP -> Icons.Default.Explore
                                    NavigationMapMode.OVERVIEW -> Icons.Default.MyLocation
                                },
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = mapMode.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pause / Fortsæt button (Requirement 7)
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = if (liveState.isPaused) AmberAccent else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { viewModel.togglePause() }
                                .testTag("nav_pause_resume_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (liveState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = if (liveState.isPaused) Color.Black else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (liveState.isPaused) {
                                        stringResource(R.string.nav_resume)
                                    } else {
                                        stringResource(R.string.nav_pause)
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (liveState.isPaused) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Battery Mode toggle button (in bottom third, >= 48 dp)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 6.dp,
                            shadowElevation = 4.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.toggleBatterySaver() }
                                .testTag("toggle_battery_mode_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.BatterySaver,
                                    contentDescription = stringResource(R.string.ui_navigationscreen_str_msparer_tilstand_61),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Navigation Menu Button ("Hold skærmen tændt", "Stemmevejledning", "Del position")
                        Box {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                tonalElevation = 6.dp,
                                shadowElevation = 4.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable { showNavMenu = true }
                                    .testTag("nav_menu_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Navigationsmenu",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showNavMenu,
                                onDismissRequest = { showNavMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.nav_keep_screen_on),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = keepScreenOn,
                                                onCheckedChange = { viewModel.setKeepScreenOn(it) },
                                                modifier = Modifier.testTag("nav_keep_screen_on_switch")
                                            )
                                        }
                                    },
                                    onClick = { viewModel.setKeepScreenOn(!keepScreenOn) },
                                    modifier = Modifier.testTag("nav_keep_screen_on_menu_item")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.nav_voice_guidance),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = voiceGuidance,
                                                onCheckedChange = { viewModel.setVoiceGuidance(it) },
                                                modifier = Modifier.testTag("nav_voice_guidance_switch")
                                            )
                                        }
                                    },
                                    onClick = { viewModel.setVoiceGuidance(!voiceGuidance) },
                                    modifier = Modifier.testTag("nav_voice_guidance_menu_item")
                                )
                                DropdownMenuItem(
                                    text = { Text(if (hasGpsFix) "Del min position" else "Venter på GPS-position") },
                                    enabled = hasGpsFix,
                                    onClick = {
                                        showNavMenu = false
                                        viewModel.shareCurrentLocation(context)
                                    },
                                    modifier = Modifier.testTag("nav_share_location_top_button")
                                )
                            }
                        }
                    }
                }

                // 3.4 Bottom Card: Thin Day Progress Bar + 3x 28sp Stats Strip + Swipe-Up Sheet
                Surface(
                    shape = SheetTopShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_bottom_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        // Swipe/Tap Handle ("Foran dig på ruten") - >= 48 dp touch target
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { _, dragAmount ->
                                        if (dragAmount < -12f) {
                                            isDrawerExpanded = true
                                        } else if (dragAmount > 12f) {
                                            isDrawerExpanded = false
                                        }
                                    }
                                }
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
                                    text = stringResource(R.string.nav_ahead_on_route),
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

                        // Requirement 1: Above the stats strip, a thin day progress bar with icons for water, shops and tonight's camp
                        val dayProgressFraction = remember(liveState.distanceWalkedTodayKm, distanceRemainingToday) {
                            val totalToday = (liveState.distanceWalkedTodayKm + distanceRemainingToday).coerceAtLeast(0.1)
                            (liveState.distanceWalkedTodayKm / totalToday).toFloat().coerceIn(0f, 1f)
                        }
                        DayProgressBarWithIcons(
                            progressFraction = dayProgressFraction,
                            stops = dayProgressStops,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("nav_day_progress_bar")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Requirement 1 & 10: Stats strip at the bottom with three large 28 sp numbers:
                        // (1) km left today, (2) arrival time at tonight's camp, (3) distance to the next water
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nav_stats_strip"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. km left today
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = formatDanishKm(distanceRemainingToday, 1),
                                    style = TabularBodyStyle.copy(
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("nav_stat_km_left_today")
                                )
                                Text(
                                    text = stringResource(R.string.nav_stat_label_km_left),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }

                            // 2. arrival time at tonight's camp
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = arrivalClock,
                                    style = TabularBodyStyle.copy(
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = ForestGreenPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("nav_stat_arrival_time")
                                )
                                Text(
                                    text = stringResource(R.string.nav_stat_label_arrival),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }

                            // 3. distance to the next water
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                val nextWaterKmText = nextWater?.let { formatDanishKm(it.distanceRemainingKm, 1) } ?: "--"
                                Text(
                                    text = nextWaterKmText,
                                    style = TabularBodyStyle.copy(
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = NordicBlue,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("nav_stat_next_water_km")
                                )
                                Text(
                                    text = stringResource(R.string.nav_stat_label_next_water),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Summary chips for Next overnight, Next water, Next shop
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cabin,
                                        contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_overnatning_62),
                                        tint = ForestGreenPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = nextOvernight?.name ?: "Overnatning",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = nextOvernight?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                            style = TabularLabelStyle,
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocalDrink,
                                        contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_vandpost_63),
                                        tint = NordicBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = nextWater?.name ?: "Vandpost",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = nextWater?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                            style = TabularLabelStyle,
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ShoppingCart,
                                        contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_indk_b_64),
                                        tint = AmberAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = nextShop?.name ?: "Indkøb",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = nextShop?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                            style = TabularLabelStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Expanded Swipe-Up Sheet: "Foran dig på ruten" list + elevation profile with my position
                        AnimatedVisibility(
                            visible = isDrawerExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_ahead_on_route),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // Elevation profile with current position cursor
                                val routePoints = plannedRoute?.points?.map { GeoPointDto(it.lat, it.lng, it.ele) } ?: emptyList()
                                ElevationPositionCanvas(
                                    points = routePoints,
                                    progressFraction = if (routePoints.isEmpty()) 0f else dayProgressFraction,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(90.dp)
                                        .testTag("nav_elevation_profile")
                                )

                                // Upcoming amenities list
                                if (aheadItems.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.ui_navigationscreen_ingen_yderligere_interessepu_65),
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

                                // Recorded GPS Track & Day Stats Card
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("recorded_day_stats_card")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.ui_navigationscreen_optaget_gps_spor_dag_66, currentDay),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = ForestGreenPrimary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.ui_navigationscreen_punkter_67, currentDayStat?.pointCount ?: 0),
                                                    fontSize = 11.sp,
                                                    color = ForestGreenPrimary,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.ui_markedroutedetailsheet_distance_18),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val dist = currentDayStat?.distanceKm ?: liveState.distanceWalkedTodayKm
                                                Text(
                                                    text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(dist, 1)),
                                                    style = TabularBodyStyle.copy(
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.ui_navigationscreen_bev_gelsestid_68),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                                                Text(
                                                    text = formatMovingTime(movSecs),
                                                    style = TabularBodyStyle.copy(
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                horizontalAlignment = Alignment.End
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.stat_elevation),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                val ascent = currentDayStat?.ascentMeters ?: 0
                                                Text(
                                                    text = stringResource(R.string.ui_navigationscreen_m_58, ascent),
                                                    style = TabularBodyStyle.copy(
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = ForestGreenPrimary
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { viewModel.exportDayGpx(context, currentDay) },
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                    .testTag("export_day_gpx_button"),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.ui_navigationscreen_dags_spor_gpx_69), fontSize = 11.sp, maxLines = 1)
                                            }

                                            Button(
                                                onClick = { viewModel.exportWholeTripGpx(context) },
                                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                    .testTag("export_whole_trip_gpx_button"),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.ui_navigationscreen_hele_turen_gpx_70), fontSize = 11.sp, maxLines = 1)
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.shareCurrentLocation(context) },
                                            enabled = hasGpsFix,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag("share_current_location_button"),
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

                                        if (allRecordedDayStats.size > 1) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(R.string.ui_navigationscreen_tidligere_dages_optagede_spo_71),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            allRecordedDayStats.filter { it.dayNumber != currentDay }.forEach { dayStat ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(
                                                            R.string.ui_navigationscreen_dag_km_m_72,
                                                            dayStat.dayNumber,
                                                            formatDanishKm(dayStat.distanceKm, 1),
                                                            formatMovingTime(dayStat.movingTimeSeconds),
                                                            dayStat.ascentMeters
                                                        ),
                                                        style = TabularBodyStyle.copy(fontSize = 16.sp),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    IconButton(
                                                        onClick = { viewModel.exportDayGpx(context, dayStat.dayNumber) },
                                                        modifier = Modifier
                                                            .size(48.dp)
                                                            .testTag("export_day_${dayStat.dayNumber}_button")
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Share,
                                                            contentDescription = stringResource(R.string.ui_navigationscreen_eksporter_dag_73, dayStat.dayNumber),
                                                            modifier = Modifier.size(16.dp),
                                                            tint = ForestGreenPrimary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Large "Stop for i dag" and red SOS button (both >= 48 dp)
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
                                    .sizeIn(minWidth = 48.dp, minHeight = 52.dp)
                                    .testTag("stop_for_today_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_stop_for_today),
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
                                    .sizeIn(minWidth = 48.dp, minHeight = 52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.setSosSheet(true) }
                                    .testTag("nav_sos_button")
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_sos),
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
    }

    // --- SOS Bottom Sheet ---
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
                    text = stringResource(R.string.ui_navigationscreen_dansk_n_dkald_112_74),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AlertRed
                )

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_din_n_jagtige_gps_position_75),
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
                            style = TabularBodyStyle.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        emergencyInfo?.spokenWgs84?.let { spoken ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_l_ses_h_jt_til_alarmcent_76, spoken),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

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
                        .heightIn(min = 56.dp)
                        .testTag("call_112_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ui_navigationscreen_ring_112_nu_77),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.setSosSheet(false) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.ui_navigationscreen_luk_n_dsystem_78))
                }
            }
        }
    }
}

@Composable
private fun TurnCueDirectionIcon(
    cue: TurnCue?,
    tint: Color
) {
    val icon = when (cue?.direction) {
        "LEFT" -> Icons.Default.TurnLeft
        "SLIGHT_LEFT" -> Icons.Default.TurnSlightLeft
        "SHARP_LEFT" -> Icons.Default.TurnSharpLeft
        "RIGHT" -> Icons.Default.TurnRight
        "SLIGHT_RIGHT" -> Icons.Default.TurnSlightRight
        "SHARP_RIGHT" -> Icons.Default.TurnSharpRight
        "UTURN" -> Icons.Default.UTurnLeft
        else -> Icons.AutoMirrored.Filled.ArrowForward
    }
    Icon(
        imageVector = icon,
        contentDescription = cue?.instructionText ?: "Næste på ruten",
        tint = tint,
        modifier = Modifier
            .size(26.dp)
            .testTag("nav_cue_arrow_icon")
    )
}

@Composable
private fun DayProgressBarWithIcons(
    progressFraction: Float,
    stops: List<DayProgressStopIcon>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.height(26.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val barWidth = maxWidth
        // Background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        // Filled progress
        Box(
            modifier = Modifier
                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(ForestGreenPrimary)
        )

        // Icons for water, shops, and tonight's camp along today's stage
        val displayStops = if (stops.isNotEmpty()) {
            stops
        } else {
            listOf(DayProgressStopIcon("CAMP", "Lejr", 1.0f, 0.0))
        }
        displayStops.forEach { stop ->
            val xOffset = (barWidth - 20.dp) * stop.fraction.coerceIn(0f, 1f)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    when (stop.type) {
                        "WATER" -> NordicBlue
                        "SHOP" -> AmberAccent
                        else -> ForestGreenPrimary
                    }
                ),
                modifier = Modifier
                    .offset(x = xOffset)
                    .size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (stop.type) {
                            "WATER" -> Icons.Default.LocalDrink
                            "SHOP" -> Icons.Default.ShoppingCart
                            else -> Icons.Default.Cabin
                        },
                        contentDescription = stop.name,
                        tint = when (stop.type) {
                            "WATER" -> NordicBlue
                            "SHOP" -> AmberAccent
                            else -> ForestGreenPrimary
                        },
                        modifier = Modifier.size(12.dp)
                    )
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
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.detourMeters > 30) {
                    Text(
                        text = stringResource(R.string.ui_navigationscreen_m_afstikker_79, item.detourMeters.toInt()),
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(item.kmRemaining, 1)),
            style = TabularBodyStyle.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            ),
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
