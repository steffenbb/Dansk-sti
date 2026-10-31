package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.DanskStiApp
import com.example.MainActivity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.repository.PlannedRouteRepository
import com.example.util.RouteTracker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NavigationLiveState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val currentLat: Double? = null,
    val currentLng: Double? = null,
    val bearing: Float = 0f,
    val accuracyM: Float = 0f,
    val speedKmh: Double = 0.0,
    val distanceWalkedTodayKm: Double = 0.0,
    val totalDistanceWalkedKm: Double = 0.0,
    val distanceRemainingTodayKm: Double = 0.0,
    val distanceRemainingTripKm: Double = 0.0,
    val offTrailDistanceMeters: Double = 0.0,
    val isOffTrailWarning: Boolean = false,
    val isDetourMuted: Boolean = false,
    val detourMuteUntilMs: Long = 0L,
    val lastFixTimestampMs: Long = 0L,
    val nextTurnCue: com.example.data.model.TurnCue? = null,
    val distanceToNextCueMeters: Double? = null,
    val showContinueDayPrompt: Boolean = false,
    val currentSnappedPoint: GeoPointDto? = null,
    val planId: Long = 0L,
    val trailName: String = "",
    val currentDay: Int = 1,
    val currentStage: TripStage? = null,
    val nearestPointAhead: RoutePoint? = null,
    val bearingToPointAhead: Float = 0f,
    val isCompleted: Boolean = false,
    val plannedRoute: PlannedRoute? = null,
    val privateForestSunsetWarning: String? = null,
    val locationPermissionMissing: Boolean = false,
    val deviceHeadingDegrees: Float? = null
)

class NavigationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var plannedRouteRepo: PlannedRouteRepository
    private lateinit var settingsRepository: com.example.data.local.SettingsRepository
    @Volatile
    private var userPaceKmh: Double = com.example.data.local.SettingsRepository.cachedMitTempoKmh
    private var routeTracker: RouteTracker? = null
    private var currentPlannedRoute: PlannedRoute? = null
    private var planId: Long = 0L

    // Speed smoothing over rolling 10 seconds
    private val speedHistory = ArrayDeque<Pair<Long, Double>>() // timestamp, speed m/s
    private var lastLocation: Location? = null
    private var alerted150CueWalkKm: Double? = null
    private var alerted30CueWalkKm: Double? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        plannedRouteRepo = PlannedRouteRepository(applicationContext)
        settingsRepository = com.example.data.local.SettingsRepository(applicationContext)
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                userPaceKmh = settings.mitTempoKmh
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val intentPlanId = intent?.getLongExtra(EXTRA_PLAN_ID, 0L) ?: 0L
                val targetPlanId = if (intentPlanId != 0L) {
                    intentPlanId
                } else {
                    plannedRouteRepo.getLastActivePlanId()
                }

                if (targetPlanId != 0L) {
                    planId = targetPlanId
                    plannedRouteRepo.saveLastActivePlanId(planId)
                    loadAndStartTracking(planId)
                } else {
                    Log.w("NavService", "No active planId provided or saved in storage")
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                setPausedInternal(true)
            }
            ACTION_RESUME -> {
                setPausedInternal(false)
            }
            ACTION_TOGGLE_PAUSE -> {
                setPausedInternal(!_liveState.value.isPaused)
            }
            ACTION_TAKE_DETOUR -> {
                takeDetourInternal()
            }
            ACTION_STOP_TODAY -> {
                stopForToday()
            }
            ACTION_STOP -> {
                stopTracking()
            }
        }
        return START_STICKY
    }

    private fun loadAndStartTracking(targetPlanId: Long) {
        serviceScope.launch {
            val plannedRoute = plannedRouteRepo.loadPlannedRoute(targetPlanId)
            if (plannedRoute == null) {
                Log.e("NavService", "Could not load PlannedRoute for planId: $targetPlanId")
                stopSelf()
                return@launch
            }

            currentPlannedRoute = plannedRoute

            // Load existing progress from Room if available
            val dao = DanskStiApp.instance.database.dao()
            val existingProgress = dao.getTripProgressSync(targetPlanId)

            val initialWalkKm = existingProgress?.totalDistanceWalkedKm ?: 0.0
            val initialDay = existingProgress?.currentDay ?: 1

            routeTracker = RouteTracker(
                plannedRoute = plannedRoute,
                initialWalkKm = initialWalkKm,
                initialDay = initialDay
            )

            val initialStage = plannedRoute.stages.getOrNull(initialDay - 1)
            val walkedToday = maxOf(0.0, initialWalkKm - (initialStage?.startWalkKm ?: 0.0))
            val remainingToday = maxOf(0.0, (initialStage?.endWalkKm ?: plannedRoute.totalWalkKm) - initialWalkKm)
            val remainingTrip = maxOf(0.0, plannedRoute.totalWalkKm - initialWalkKm)

            val showContinuePrompt = existingProgress != null &&
                (existingProgress.isPaused || initialDay > 1) &&
                walkedToday < 0.05

            val allCues = com.example.util.routing.TurnCueGenerator.computeTurnCuesForPlannedRoute(plannedRoute)
            val firstCueAhead = allCues.firstOrNull { it.walkKm >= initialWalkKm }
            val firstCueDistM = firstCueAhead?.let { ((it.walkKm - initialWalkKm) * 1000.0).coerceAtLeast(0.0) }

            _liveState.value = _liveState.value.copy(
                isTracking = true,
                isPaused = false,
                planId = targetPlanId,
                trailName = plannedRoute.trailName,
                currentDay = initialDay,
                currentStage = initialStage,
                totalDistanceWalkedKm = initialWalkKm,
                distanceWalkedTodayKm = walkedToday,
                distanceRemainingTodayKm = remainingToday,
                distanceRemainingTripKm = remainingTrip,
                plannedRoute = plannedRoute,
                showContinueDayPrompt = showContinuePrompt,
                nextTurnCue = if (firstCueDistM != null && firstCueDistM <= 500.0) firstCueAhead else null,
                distanceToNextCueMeters = if (firstCueDistM != null && firstCueDistM <= 500.0) firstCueDistM else null
            )

            // Start foreground on main thread
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                startForegroundLocationTracking()
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal fun startForegroundLocationTracking() {
        val route = currentPlannedRoute
        val notification = buildNotification(
            trailName = route?.trailName ?: "Dansk Sti",
            stageName = route?.stages?.firstOrNull()?.stopName ?: "Overnatning",
            speedKmh = 0.0,
            remainingTodayKm = route?.stages?.firstOrNull()?.stageKm,
            remainingTripKm = route?.totalWalkKm ?: 0.0,
            isOffRoute = false,
            offRouteMeters = 0.0
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e("NavService", "SecurityException in startForeground; stopping cleanly", e)
            _liveState.value = _liveState.value.copy(
                isTracking = false,
                locationPermissionMissing = true
            )
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("NavService", "SecurityException requesting location updates; stopping cleanly", e)
            _liveState.value = _liveState.value.copy(
                isTracking = false,
                locationPermissionMissing = true
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleNewLocation(location: Location) {
        val tracker = routeTracker ?: return
        val plannedRoute = currentPlannedRoute ?: return
        val now = System.currentTimeMillis()

        // 1. Calculate smoothed speed over 10s
        var currentSpeedMps = location.speed.toDouble()
        val prev = lastLocation
        if (currentSpeedMps <= 0.0 && prev != null) {
            val dist = location.distanceTo(prev).toDouble()
            val dt = (now - prev.time) / 1000.0
            if (dt > 0.0) {
                currentSpeedMps = dist / dt
            }
        }
        lastLocation = location

        speedHistory.addLast(now to currentSpeedMps)
        while (speedHistory.isNotEmpty() && (now - speedHistory.first().first) > 10000L) {
            speedHistory.removeFirst()
        }
        val smoothedSpeedMps = if (speedHistory.isNotEmpty()) {
            speedHistory.map { it.second }.average()
        } else 0.0
        val speedKmh = smoothedSpeedMps * 3.6

        // When paused, update live GPS position on the map without advancing walked-time or writing progress
        if (_liveState.value.isPaused) {
            _liveState.value = _liveState.value.copy(
                currentLat = location.latitude,
                currentLng = location.longitude,
                bearing = location.bearing,
                accuracyM = location.accuracy,
                speedKmh = speedKmh,
                lastFixTimestampMs = now
            )
            return
        }

        // 2. Feed fix to RouteTracker
        val trackResult = tracker.onLocationUpdate(
            lat = location.latitude,
            lng = location.longitude,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestampMs = now
        )

        // 3. Trigger vibration if required by off-route logic (suppressed when detour muted)
        if (trackResult.shouldVibrate && !trackResult.isDetourMuted) {
            triggerOffTrailVibration()
        }

        // 3b. Evaluate next turn cue and double vibration at 150 m and 30 m
        val allCues = com.example.util.routing.TurnCueGenerator.computeTurnCuesForPlannedRoute(plannedRoute)
        val upcomingCue = allCues.firstOrNull { it.walkKm >= trackResult.currentWalkKm - 0.005 }
        val cueDistM = upcomingCue?.let {
            maxOf(0.0, (it.walkKm - trackResult.currentWalkKm) * 1000.0)
        }
        if (upcomingCue != null && cueDistM != null && !trackResult.isOffRoute) {
            if (cueDistM in 30.0..150.0 && alerted150CueWalkKm != upcomingCue.walkKm) {
                alerted150CueWalkKm = upcomingCue.walkKm
                triggerTurnCueDoubleVibration()
            } else if (cueDistM < 30.0 && alerted30CueWalkKm != upcomingCue.walkKm) {
                alerted30CueWalkKm = upcomingCue.walkKm
                triggerTurnCueDoubleVibration()
            }
        }

        // 4. Update Live State
        _liveState.value = _liveState.value.copy(
            isTracking = true,
            currentLat = location.latitude,
            currentLng = location.longitude,
            bearing = location.bearing,
            accuracyM = location.accuracy,
            speedKmh = speedKmh,
            distanceWalkedTodayKm = trackResult.walkedTodayKm,
            totalDistanceWalkedKm = trackResult.currentWalkKm,
            distanceRemainingTodayKm = trackResult.remainingTodayKm,
            distanceRemainingTripKm = trackResult.remainingTripKm,
            offTrailDistanceMeters = trackResult.offRouteDistanceM,
            isOffTrailWarning = trackResult.isOffRoute && !trackResult.isDetourMuted,
            isDetourMuted = trackResult.isDetourMuted,
            detourMuteUntilMs = tracker.detourMuteUntilMs,
            lastFixTimestampMs = now,
            nextTurnCue = if (cueDistM != null && cueDistM <= 500.0) upcomingCue else null,
            distanceToNextCueMeters = if (cueDistM != null && cueDistM <= 500.0) cueDistM else null,
            currentSnappedPoint = trackResult.snappedPoint,
            planId = planId,
            trailName = plannedRoute.trailName,
            currentDay = trackResult.currentDay,
            currentStage = trackResult.currentStage,
            nearestPointAhead = trackResult.nearestPointAhead,
            bearingToPointAhead = trackResult.bearingToPointAhead,
            isCompleted = trackResult.isCompleted,
            plannedRoute = plannedRoute
        )

        // 5. Update Foreground Notification
        val stageName = trackResult.currentStage?.stopName ?: "Dagens etape"
        val notif = buildNotification(
            trailName = plannedRoute.trailName,
            stageName = stageName,
            speedKmh = speedKmh,
            remainingTodayKm = trackResult.remainingTodayKm,
            remainingTripKm = trackResult.remainingTripKm,
            isOffRoute = trackResult.isOffRoute && !trackResult.isDetourMuted,
            offRouteMeters = trackResult.offRouteDistanceM,
            walkedTodayKm = trackResult.walkedTodayKm,
            isPaused = _liveState.value.isPaused
        )
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifManager?.notify(NOTIFICATION_ID, notif)

        // 6. Persist progress to Room throttled (at most every 30s or every 100m)
        if (trackResult.shouldPersistToDb) {
            serviceScope.launch {
                val progress = TripProgressEntity(
                    planId = planId,
                    currentDay = trackResult.currentDay,
                    distanceWalkedTodayKm = trackResult.walkedTodayKm,
                    totalDistanceWalkedKm = trackResult.currentWalkKm,
                    currentSnappedLat = trackResult.snappedPoint?.lat ?: location.latitude,
                    currentSnappedLng = trackResult.snappedPoint?.lng ?: location.longitude,
                    remainingDistanceTripKm = trackResult.remainingTripKm,
                    remainingDistanceTodayKm = trackResult.remainingTodayKm,
                    isPaused = false,
                    isCompleted = trackResult.isCompleted,
                    lastActiveTimestamp = now
                )
                DanskStiApp.instance.database.dao().saveTripProgress(progress)
                tracker.markPersisted(now)
            }
        }

        // Phase 7a: Save actual GPS track point per day (accuracy-filtered <= 30m) in Room
        if (location.accuracy <= 30.0f) {
            serviceScope.launch {
                try {
                    DanskStiApp.instance.repository.recordTrackPoint(
                        planId = planId,
                        dayNumber = trackResult.currentDay,
                        lat = location.latitude,
                        lng = location.longitude,
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        accuracy = location.accuracy,
                        speedKmh = speedKmh,
                        timestamp = now
                    )
                } catch (t: Throwable) {
                    Log.e("NavService", "Failed to record GPS track point: ${t.message}", t)
                }
            }
        }

        // Phase 10: Private forest sunset warning check (Requirement D)
        serviceScope.launch {
            checkPrivateForestSunsetWarning(location)
        }
    }

    internal fun triggerOffTrailVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 450), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 350, 150, 450), -1)
            }
        } catch (e: Exception) {
            Log.e("NavService", "Vibration failed: ${e.message}")
        }
    }

    internal fun triggerTurnCueDoubleVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 180, 120, 180), -1)
            }
        } catch (e: Exception) {
            Log.e("NavService", "Turn cue vibration failed: ${e.message}")
        }
    }

    internal fun setPausedInternal(paused: Boolean) {
        _liveState.value = _liveState.value.copy(isPaused = paused)
        val route = currentPlannedRoute
        if (route != null) {
            val st = _liveState.value
            val notif = buildNotification(
                trailName = route.trailName,
                stageName = st.currentStage?.stopName ?: "Dagens etape",
                speedKmh = st.speedKmh,
                remainingTodayKm = st.distanceRemainingTodayKm,
                remainingTripKm = st.distanceRemainingTripKm,
                isOffRoute = st.isOffTrailWarning,
                offRouteMeters = st.offTrailDistanceMeters,
                walkedTodayKm = st.distanceWalkedTodayKm,
                isPaused = paused
            )
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notifManager?.notify(NOTIFICATION_ID, notif)
        }
    }

    internal fun takeDetourInternal(nowMs: Long = System.currentTimeMillis()) {
        val tracker = routeTracker
        tracker?.takeDetour(nowMs)
        val muteUntil = tracker?.detourMuteUntilMs ?: (nowMs + RouteTracker.DETOUR_MUTE_DURATION_MS)
        _liveState.value = _liveState.value.copy(
            isOffTrailWarning = false,
            isDetourMuted = true,
            detourMuteUntilMs = muteUntil
        )
    }

    internal fun buildNotification(
        trailName: String,
        stageName: String,
        speedKmh: Double,
        remainingTodayKm: Double?,
        remainingTripKm: Double,
        isOffRoute: Boolean,
        offRouteMeters: Double,
        walkedTodayKm: Double = 0.0,
        isPaused: Boolean = false
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop for i dag action
        val stopTodayIntent = Intent(this, NavigationTrackingService::class.java).apply {
            action = ACTION_STOP_TODAY
        }
        val stopTodayPending = PendingIntent.getService(
            this,
            1,
            stopTodayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isOffRoute) {
            "⚠️ Kursafvigelse (${offRouteMeters.toInt()} m fra ruten)"
        } else {
            "Vandring: $trailName"
        }

        val content = if (remainingTodayKm != null) {
            val configuredPace = com.example.data.local.SettingsRepository.cachedMitTempoKmh.takeIf { it > 0.0 } ?: userPaceKmh
            val arrivalClock = com.example.util.SunCalculator.computeArrivalClock(
                remainingTodayKm = remainingTodayKm,
                speedKmh = speedKmh,
                configuredPaceKmh = configuredPace
            )
            val etaString = "kl. $arrivalClock"
            "Næste lejr: $stageName • ${String.format(Locale.getDefault(), "%.1f", remainingTodayKm)} km ($etaString)"
        } else {
            "Næste lejr: $stageName"
        }

        // Pause / Fortsæt action
        val pauseIntent = Intent(this, NavigationTrackingService::class.java).apply {
            action = ACTION_TOGGLE_PAUSE
        }
        val pausePending = PendingIntent.getService(
            this,
            2,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isOffRoute) DanskStiApp.CHANNEL_ALERTS_ID else DanskStiApp.CHANNEL_NAVIGATION_ID
        val pauseActionLabel = if (isPaused) "Fortsæt" else "Pause"

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                pauseActionLabel,
                pausePending
            )
            .addAction(android.R.drawable.ic_media_pause, "Stop for i dag", stopTodayPending)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (remainingTodayKm != null) {
            val totalDayKm = (walkedTodayKm + remainingTodayKm).coerceAtLeast(0.1)
            val progressPct = ((walkedTodayKm / totalDayKm) * 100.0).toInt().coerceIn(0, 100)
            builder.setProgress(100, progressPct, false)
        }

        return builder.build()
    }

    private fun stopForToday() {
        val tracker = routeTracker
        if (tracker != null && planId != 0L) {
            serviceScope.launch {
                val progress = tracker.stopForToday()
                DanskStiApp.instance.database.dao().saveTripProgress(progress)
                _liveState.value = _liveState.value.copy(
                    isTracking = false,
                    isPaused = true,
                    currentDay = progress.currentDay,
                    distanceWalkedTodayKm = 0.0,
                    distanceRemainingTodayKm = progress.remainingDistanceTodayKm,
                    showContinueDayPrompt = true
                )
                stopTracking()
            }
        } else {
            stopTracking()
        }
    }

    private fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        _liveState.value = _liveState.value.copy(isTracking = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var lastSunsetWarningNotifiedMs: Long = 0L

    private suspend fun checkPrivateForestSunsetWarning(location: Location) {
        try {
            val pt = com.example.util.routing.LatLng(location.latitude, location.longitude)
            val bboxDelta = 0.02
            val polygons = DanskStiApp.instance.repository.getAccessPolygons(
                pt.lat - bboxDelta, pt.lat + bboxDelta,
                pt.lng - bboxDelta, pt.lng + bboxDelta
            )
            val containing = com.example.util.access.AccessGeometryUtils.findContainingPolygon(pt, polygons)
            if (containing != null && containing.type == com.example.data.model.access.AccessLandType.PRIVATE_FOREST) {
                val sunsetStr = com.example.util.SunCalculator.calculateSunset(lat = pt.lat, lng = pt.lng)
                val sunsetMins = com.example.util.access.AccessGeometryUtils.parseTimeToMinutes(sunsetStr)

                val cal = java.util.Calendar.getInstance()
                val currentMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                val diffMins = sunsetMins - currentMins

                // Within 45 minutes of sunset (or past sunset up to 60 min)
                if (diffMins in -60..45) {
                    val warningText = "Privat skov lukker ved solnedgang kl. $sunsetStr"
                    if (_liveState.value.privateForestSunsetWarning != warningText) {
                        _liveState.value = _liveState.value.copy(privateForestSunsetWarning = warningText)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastSunsetWarningNotifiedMs > 30 * 60 * 1000L) {
                        lastSunsetWarningNotifiedMs = now
                        showSunsetWarningNotification(sunsetStr)
                    }
                } else {
                    if (_liveState.value.privateForestSunsetWarning != null) {
                        _liveState.value = _liveState.value.copy(privateForestSunsetWarning = null)
                    }
                }
            } else {
                if (_liveState.value.privateForestSunsetWarning != null) {
                    _liveState.value = _liveState.value.copy(privateForestSunsetWarning = null)
                }
            }
        } catch (e: Exception) {
            Log.w("NavService", "Failed checking private forest sunset: ${e.message}")
        }
    }

    private fun showSunsetWarningNotification(sunsetTime: String) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            99,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, DanskStiApp.CHANNEL_ALERTS_ID)
            .setContentTitle("Privat skov")
            .setContentText("Privat skov lukker ved solnedgang kl. $sunsetTime")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Privat skov lukker ved solnedgang kl. $sunsetTime. I henhold til Naturbeskyttelsesloven må offentligheden kun færdes i privat skov fra kl. 06:00 til solnedgang."))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notifManager.notify(SUNSET_WARNING_NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 4040
        const val SUNSET_WARNING_NOTIFICATION_ID = 4041
        const val ACTION_START = "com.example.dansksti.ACTION_START"
        const val ACTION_STOP = "com.example.dansksti.ACTION_STOP"
        const val ACTION_STOP_TODAY = "com.example.dansksti.ACTION_STOP_TODAY"
        const val ACTION_PAUSE = "com.example.dansksti.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.dansksti.ACTION_RESUME"
        const val ACTION_TOGGLE_PAUSE = "com.example.dansksti.ACTION_TOGGLE_PAUSE"
        const val ACTION_TAKE_DETOUR = "com.example.dansksti.ACTION_TAKE_DETOUR"

        const val EXTRA_PLAN_ID = "extra_plan_id"

        @Volatile
        private var activeServiceInstance: NavigationTrackingService? = null

        private val _liveState = MutableStateFlow(NavigationLiveState())
        val liveState: StateFlow<NavigationLiveState> = _liveState.asStateFlow()

        internal fun updateLiveStateForTest(state: NavigationLiveState) {
            _liveState.value = state
        }

        fun setPaused(context: Context, paused: Boolean) {
            activeServiceInstance?.setPausedInternal(paused) ?: run {
                _liveState.value = _liveState.value.copy(isPaused = paused)
            }
        }

        fun takeDetour(context: Context, nowMs: Long = System.currentTimeMillis()) {
            activeServiceInstance?.takeDetourInternal(nowMs) ?: run {
                val muteUntil = nowMs + RouteTracker.DETOUR_MUTE_DURATION_MS
                _liveState.value = _liveState.value.copy(
                    isOffTrailWarning = false,
                    isDetourMuted = true,
                    detourMuteUntilMs = muteUntil
                )
            }
        }

        fun dismissContinueDayPrompt() {
            _liveState.value = _liveState.value.copy(showContinueDayPrompt = false)
        }

        fun hasLocationPermission(context: Context): Boolean {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        /**
         * Starts navigation with ONLY planId in the Intent, after verifying location permission.
         */
        fun start(context: Context, planId: Long): Boolean {
            if (!hasLocationPermission(context)) {
                Log.w("NavService", "Location permission missing; not starting NavigationTrackingService")
                _liveState.value = _liveState.value.copy(
                    isTracking = false,
                    locationPermissionMissing = true
                )
                return false
            }
            _liveState.value = _liveState.value.copy(locationPermissionMissing = false)
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PLAN_ID, planId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun stopForToday(context: Context) {
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_STOP_TODAY
            }
            context.startService(intent)
        }
    }
}
