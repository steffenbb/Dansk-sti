package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.TurnCue
import com.example.ui.components.PoiInfoSheet
import com.example.ui.components.PoiInfoSheetData
import com.example.ui.components.formatDanishKm
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.NavigationMapOverlayManager
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationMapMode
import com.example.ui.viewmodel.NavigationViewModel
import com.example.util.routing.TurnCueGenerator
import kotlinx.coroutines.delay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.max

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
    val isRoutingBackToRoute by viewModel.isRoutingBackToRoute.collectAsState()
    val routeBackErrorMessage by viewModel.routeBackErrorMessage.collectAsState()
    val deviceHeadingDegrees by viewModel.deviceHeadingDegrees.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val voiceGuidance by viewModel.voiceGuidance.collectAsState()
    val sunsetTime by viewModel.sunsetTime.collectAsState()
    val corridorPoisErrorMessage by viewModel.corridorPoisErrorMessage.collectAsState()

    // Round 9A Item 4 & Round 10B Item 2: Read device heading from the rotation vector sensor
    // and remap coordinate system according to display rotation.
    ObserveCompassSensorHeading(
        context = context,
        activity = activity,
        onHeadingDegrees = { azimuthDeg -> viewModel.updateDeviceHeading(azimuthDeg) }
    )

    // Round 10B Item 2: Unified heading source for heading-up map and off-route arrow
    val unifiedHeadingDegrees = remember(
        liveState.speedKmh,
        liveState.bearing,
        liveState.hasGpsBearing,
        deviceHeadingDegrees,
        liveState.deviceHeadingDegrees
    ) {
        resolveNavigationHeadingDegrees(
            speedKmh = liveState.speedKmh,
            gpsCourseDegrees = liveState.bearing,
            hasGpsCourse = liveState.hasGpsBearing,
            compassHeadingDegrees = deviceHeadingDegrees ?: liveState.deviceHeadingDegrees
        )
    }

    val hasGpsFix = liveState.currentLat != null && liveState.currentLng != null
    val isLocationPermissionMissing = needsLocationPermission || liveState.locationPermissionMissing

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

    var ttsRef by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var lastSpokenKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(voiceGuidance) {
        var engine: TextToSpeech? = null
        if (voiceGuidance) {
            runCatching {
                val daLocale = com.example.util.DanishTtsChecker.DANISH_LOCALE
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS && com.example.util.DanishTtsChecker.isDanishTtsSupported(engine)) {
                        engine?.language = daLocale
                        ttsReady = true
                    } else {
                        ttsReady = false
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

    val allRecordedDayStats by viewModel.allRecordedDayStats.collectAsState()
    val currentDayStat by viewModel.currentDayStat.collectAsState()

    var isDrawerExpanded by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    var showNavMenu by remember { mutableStateOf(false) }
    var dismissedCampArrivalForDay by remember { mutableStateOf(-1) }
    var dismissedCompletedDialog by remember(liveState.planId) { mutableStateOf(false) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var overlayManagerRef by remember { mutableStateOf<NavigationMapOverlayManager?>(null) }
    val isDark = DanskStiTheme.isDark
    val windowSizeClass = DanskStiTheme.windowSizeClass

    LaunchedEffect(isBatterySaverMode) {
        if (isBatterySaverMode) {
            mapViewRef = null
            overlayManagerRef = null
        }
    }

    val targetAutoZoom = remember(liveState.distanceToNextCueMeters, liveState.speedKmh) {
        val nearCue = (liveState.distanceToNextCueMeters ?: Double.MAX_VALUE) <= TurnCueGenerator.CUE_FIRST_ALERT_METERS
        if (nearCue || liveState.speedKmh < 2.0) 17.0 else 16.0
    }

    LaunchedEffect(
        mapMode,
        liveState.currentLat,
        liveState.currentLng,
        unifiedHeadingDegrees,
        liveState.bearing,
        targetAutoZoom,
        plannedRoute,
        currentDay
    ) {
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
                val heading = unifiedHeadingDegrees ?: liveState.bearing
                mv.mapOrientation = -heading
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

    LaunchedEffect(backToRoutePoints, isRoutingBackToRoute) {
        val ptsToShow = if (isRoutingBackToRoute) emptyList() else backToRoutePoints
        overlayManagerRef?.setBackToRoutePoints(ptsToShow)
    }

    LaunchedEffect(routeBackErrorMessage) {
        val msg = routeBackErrorMessage
        if (!msg.isNullOrBlank()) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        onBack()
    }

    val arrivalClock = remember(distanceRemainingToday, liveState.speedKmh, mitTempoKmh, nowClockMs) {
        com.example.util.SunCalculator.computeArrivalClock(
            remainingTodayKm = distanceRemainingToday,
            speedKmh = liveState.speedKmh,
            configuredPaceKmh = mitTempoKmh,
            nowMillis = nowClockMs
        )
    }
    val totalDaysCount = (activePlan?.targetDays ?: plannedRoute?.stages?.size ?: 1).coerceAtLeast(1)
    val remKmFormatted = formatDanishKm(distanceRemainingToday, 1)
    val topCardHeadline = stringResource(
        R.string.nav_top_headline_format,
        currentDay,
        totalDaysCount,
        remKmFormatted,
        arrivalClock
    )

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

    val effectiveSunsetTime = remember(sunsetTime, liveState.currentLat, liveState.currentLng, nowClockMs) {
        sunsetTime.takeIf { it.isNotBlank() && it.contains(":") }
            ?: com.example.util.SunCalculator.calculateSunset(
                epochMillis = nowClockMs,
                lat = liveState.currentLat ?: com.example.util.SunCalculator.DEFAULT_COPENHAGEN_LAT,
                lng = liveState.currentLng ?: com.example.util.SunCalculator.DEFAULT_COPENHAGEN_LNG
            )
    }
    val isDaylightWarningVisible = remember(distanceRemainingToday, mitTempoKmh, effectiveSunsetTime, nowClockMs) {
        if (distanceRemainingToday <= 0.2) {
            false
        } else {
            val defaultSunTimes = com.example.util.SunCalculator.calculateSunTimes(
                lat = liveState.currentLat ?: com.example.util.SunCalculator.DEFAULT_COPENHAGEN_LAT,
                lng = liveState.currentLng ?: com.example.util.SunCalculator.DEFAULT_COPENHAGEN_LNG
            )
            val parts = effectiveSunsetTime.split(":")
            val sunsetHour = parts.getOrNull(0)?.toIntOrNull() ?: (defaultSunTimes.sunsetMinutesOfDay / 60)
            val sunsetMin = parts.getOrNull(1)?.toIntOrNull() ?: (defaultSunTimes.sunsetMinutesOfDay % 60)
            val copenhagenCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Copenhagen")).apply {
                timeInMillis = nowClockMs
            }
            val nowMinutes = copenhagenCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + copenhagenCal.get(java.util.Calendar.MINUTE)
            val sunsetMinutes = sunsetHour * 60 + sunsetMin
            val daylightHoursLeft = (sunsetMinutes - nowMinutes) / 60.0
            val hoursNeededAtMyPace = distanceRemainingToday / mitTempoKmh.coerceAtLeast(1.0)
            daylightHoursLeft in 0.0..10.0 && hoursNeededAtMyPace > daylightHoursLeft
        }
    }

    val campNameForArrival = liveState.currentStage?.stopName ?: nextOvernight?.name
    val isNearTonightCamp = remember(distanceRemainingToday, campNameForArrival, currentDay, dismissedCampArrivalForDay, liveState.isTracking) {
        liveState.isTracking &&
            !campNameForArrival.isNullOrBlank() &&
            distanceRemainingToday > 0.0 &&
            distanceRemainingToday * 1000.0 <= 150.0 &&
            dismissedCampArrivalForDay != currentDay
    }

    if (isBatterySaverMode) {
        NavigationBatterySaverScreen(
            modifier = modifier,
            todayStageText = todayStageText,
            cueBannerPrimaryText = cueBannerPrimaryText,
            distanceRemainingToday = distanceRemainingToday,
            speedKmh = liveState.speedKmh,
            nextOvernight = nextOvernight,
            currentDayStat = currentDayStat,
            hasGpsFix = hasGpsFix,
            emergencyInfo = emergencyInfo,
            liveState = liveState,
            onRerouteBack = { viewModel.routeMeBack() },
            onTakeDetour = { viewModel.takeDetour() },
            onExitBatterySaver = { viewModel.setBatterySaverMode(false) },
            onOpenSos = { viewModel.setSosSheet(true) },
            onShareLocation = { viewModel.shareCurrentLocation(context) }
        )
    } else {
        BoxWithConstraints(
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
            val isLandscape = maxWidth > maxHeight || windowSizeClass.isLandscape
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
            var selectedNavigationPoi by remember { mutableStateOf<PoiInfoSheetData?>(null) }

            val navigationMapSection: @Composable (androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit =
                { topPad, endPad, bottomAttrPad ->
                    DanskStiMap(
                        modifier = Modifier.fillMaxSize(),
                        mapTestTag = "navigation_map_view",
                        initialZoom = 16.0,
                        appIsDark = isDark,
                        userLocation = navUserGeoPoint,
                        userHeadingDegrees = unifiedHeadingDegrees ?: liveState.bearing,
                        userAccuracyMeters = liveState.accuracyM,
                        shelters = routeShelters,
                        friTeltning = navFriTeltningEntities,
                        pois = navCombinedPois,
                        controlsTopPadding = topPad,
                        controlsEndPadding = endPad,
                        attributionBottomPadding = bottomAttrPad,
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
                                onSelectPoi = { corridorPoi ->
                                    selectedNavigationPoi = PoiInfoSheetData.fromNavigationCorridorPoi(
                                        corridorPoi = corridorPoi,
                                        shelters = routeShelters,
                                        friTeltning = navFriTeltningEntities,
                                        pois = navCombinedPois
                                    )
                                }
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
                }

            val topCueOverlay: @Composable (Modifier) -> Unit = { cueMod ->
                NavigationTopCueAndAlertsStack(
                    activeCueWithin500m = activeCueWithin500m,
                    cueBannerPrimaryText = cueBannerPrimaryText,
                    topCardHeadline = topCardHeadline,
                    liveState = liveState,
                    deviceHeadingDegrees = unifiedHeadingDegrees,
                    mapMode = mapMode,
                    routeBackErrorMessage = routeBackErrorMessage,
                    isGpsWeakOrStale = isGpsWeakOrStale,
                    gpsStaleMinutes = gpsStaleMinutes,
                    isDaylightWarningVisible = isDaylightWarningVisible,
                    distanceRemainingToday = distanceRemainingToday,
                    sunsetTime = sunsetTime,
                    isLocationPermissionMissing = isLocationPermissionMissing,
                    onBack = onBack,
                    onRerouteBack = { viewModel.routeMeBack() },
                    onTakeDetour = { viewModel.takeDetour() },
                    onRequestLocationPermission = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    modifier = cueMod,
                    corridorPoisErrorMessage = corridorPoisErrorMessage,
                    onRetryCorridorPois = { viewModel.retryLoadRouteCorridorPois() }
                )
            }

            val statsAndControlsContent: @Composable (Boolean) -> Unit = { isLandscapeRightCol ->
                NavigationBottomOrSideControlsAndStats(
                    viewModel = viewModel,
                    liveState = liveState,
                    currentDay = currentDay,
                    distanceRemainingToday = distanceRemainingToday,
                    arrivalClock = arrivalClock,
                    sunsetTime = sunsetTime,
                    isNearTonightCamp = isNearTonightCamp,
                    campNameForArrival = campNameForArrival,
                    currentDayStat = currentDayStat,
                    allRecordedDayStats = allRecordedDayStats,
                    nextOvernight = nextOvernight,
                    nextWater = nextWater,
                    nextShop = nextShop,
                    dayProgressStops = dayProgressStops,
                    aheadItems = aheadItems,
                    plannedRoute = plannedRoute,
                    mapMode = mapMode,
                    keepScreenOn = keepScreenOn,
                    voiceGuidance = voiceGuidance,
                    hasGpsFix = hasGpsFix,
                    isDrawerExpanded = isDrawerExpanded,
                    onToggleDrawerExpanded = { isDrawerExpanded = !isDrawerExpanded },
                    onSetDrawerExpanded = { isDrawerExpanded = it },
                    showNavMenu = showNavMenu,
                    onSetShowNavMenu = { showNavMenu = it },
                    onDismissCampArrival = { dismissedCampArrivalForDay = currentDay },
                    onBack = onBack,
                    isLandscapeRightColumn = isLandscapeRightCol
                )
            }

            if (isLandscape) {
                // Requirement 4: Navigation screen in landscape: cue banner top-left, stats in a column on the right, map in the middle
                NavigationLandscapeScaffoldBox(
                    mapContent = { navigationMapSection(12.dp, 12.dp, 16.dp) },
                    topLeftCueContent = { mod -> topCueOverlay(mod) },
                    rightStatsColumnContent = { statsAndControlsContent(true) }
                )
            } else {
                navigationMapSection(16.dp, 16.dp, 220.dp)

                topCueOverlay(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 76.dp, top = 8.dp, bottom = 8.dp)
                        .align(Alignment.TopStart)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statsAndControlsContent(false)
                }
            }

            selectedNavigationPoi?.let { poiData ->
                PoiInfoSheet(
                    poiData = poiData,
                    liveUserLocation = navUserGeoPoint,
                    isLocationFresh = !isGpsWeakOrStale,
                    onDismiss = { selectedNavigationPoi = null },
                    onRouteHere = { lat, lng, _ ->
                        selectedNavigationPoi = null
                        mapViewRef?.controller?.animateTo(GeoPoint(lat, lng), 16.0, 600L)
                    },
                    onAddToRoute = { lat, lng, _ ->
                        selectedNavigationPoi = null
                        mapViewRef?.controller?.animateTo(GeoPoint(lat, lng), 16.0, 600L)
                    }
                )
            }
        }
    }

    if (liveState.isCompleted && !dismissedCompletedDialog) {
        val totalKm = max(
            liveState.totalDistanceWalkedKm,
            plannedRoute?.totalWalkKm ?: liveState.plannedRoute?.totalWalkKm ?: 0.0
        )
        val totalMovingSecs = allRecordedDayStats.sumOf { it.movingTimeSeconds }
            .takeIf { it > 0L }
            ?: (currentDayStat?.movingTimeSeconds ?: 0L)
        val totalAscentM = allRecordedDayStats.sumOf { it.ascentMeters }
            .takeIf { it > 0 }
            ?: (currentDayStat?.ascentMeters ?: 0)
        val completedDays = maxOf(currentDay, allRecordedDayStats.size, 1)
        NavigationTripCompletedSummaryDialog(
            trailName = liveState.trailName.ifBlank { activePlan?.trailName ?: "" },
            totalDistanceKm = totalKm,
            totalMovingSeconds = totalMovingSecs,
            totalAscentMeters = totalAscentM,
            totalDays = completedDays,
            onDismiss = { dismissedCompletedDialog = true },
            onFinishAndBack = {
                dismissedCompletedDialog = true
                com.example.service.NavigationTrackingService.stop(context)
                onBack()
            }
        )
    }

    if (showSosSheet) {
        NavigationSosBottomSheet(
            liveState = liveState,
            emergencyInfo = emergencyInfo,
            onDismiss = { viewModel.setSosSheet(false) }
        )
    }
}

