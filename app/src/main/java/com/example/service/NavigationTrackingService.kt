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
    val hasGpsBearing: Boolean = false,
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
    val deviceHeadingDegrees: Float? = null,
    val isBatterySaverMode: Boolean = false,
    val activeGpsIntervalMs: Long = 2_000L
)

/**
 * Round 10B Item 10:
 * GPS power profiles:
 * - HIGH_ACCURACY_2S: High accuracy every 2 s only while moving with the screen on.
 * - BALANCED_10S: Balanced accuracy every 10 s (battery-saver screen, screen off, or stationary).
 * - LOW_POWER_60S: Low power every 60 s while paused (resumes normal when movement is detected).
 */
enum class GpsPowerProfile(
    val priority: Int,
    val intervalMs: Long,
    val minUpdateIntervalMs: Long,
    val minDistanceM: Float
) {
    HIGH_ACCURACY_2S(Priority.PRIORITY_HIGH_ACCURACY, 2_000L, 1_000L, 1.0f),
    BALANCED_10S(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L, 5_000L, 2.0f),
    LOW_POWER_60S(Priority.PRIORITY_LOW_POWER, 60_000L, 30_000L, 5.0f)
}

class NavigationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var plannedRouteRepo: PlannedRouteRepository
    private lateinit var settingsRepository: com.example.data.local.SettingsRepository
    @Volatile
    private var userPaceKmh: Double = com.example.data.local.SettingsRepository.cachedMitTempoKmh
    internal var routeTracker: RouteTracker? = null
    internal var currentPlannedRoute: PlannedRoute? = null
    internal var planId: Long = 0L

    // Speed smoothing over rolling 10 seconds
    private val speedHistory = ArrayDeque<Pair<Long, Double>>() // timestamp, speed m/s
    private var lastLocation: Location? = null
    private var pausedAnchorLocation: Location? = null
    private var alerted150CueWalkKm: Double? = null
    private var alerted30CueWalkKm: Double? = null

    @Volatile
    internal var currentGpsProfile: GpsPowerProfile = GpsPowerProfile.HIGH_ACCURACY_2S
    @Volatile
    private var isMovingRecently: Boolean = true

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        plannedRouteRepo = PlannedRouteRepository(applicationContext)
        settingsRepository = DanskStiApp.getSettingsRepository(applicationContext)
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

            val initialWalkKm = if (existingProgress?.isCompleted == true) 0.0 else (existingProgress?.totalDistanceWalkedKm ?: 0.0)
            val initialDay = if (existingProgress?.isCompleted == true) 1 else (existingProgress?.currentDay ?: 1)

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
                !existingProgress.isCompleted &&
                (existingProgress.isPaused || initialDay > 1) &&
                walkedToday < 0.05

            val allCues = com.example.util.routing.TurnCueGenerator.computeTurnCuesForPlannedRoute(plannedRoute)
            val firstCueAhead = allCues.firstOrNull { it.walkKm >= initialWalkKm }
            val firstCueDistM = firstCueAhead?.let { ((it.walkKm - initialWalkKm) * 1000.0).coerceAtLeast(0.0) }

            _liveState.value = _liveState.value.copy(
                isTracking = true,
                isPaused = false,
                isCompleted = false,
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

    private fun isScreenInteractiveNow(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    @SuppressLint("MissingPermission")
    internal fun applyGpsPowerProfile(profile: GpsPowerProfile, forceRebind: Boolean = false) {
        if (!forceRebind && currentGpsProfile == profile && locationCallback != null) return
        currentGpsProfile = profile
        _liveState.value = _liveState.value.copy(activeGpsIntervalMs = profile.intervalMs)
        val cb = locationCallback ?: return
        val locationRequest = LocationRequest.Builder(profile.priority, profile.intervalMs)
            .setMinUpdateIntervalMillis(profile.minUpdateIntervalMs)
            .setMinUpdateDistanceMeters(profile.minDistanceM)
            .build()
        try {
            fusedLocationClient.removeLocationUpdates(cb)
            fusedLocationClient.requestLocationUpdates(locationRequest, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("NavService", "SecurityException updating location profile; stopping cleanly", e)
            _liveState.value = _liveState.value.copy(
                isTracking = false,
                locationPermissionMissing = true
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.w("NavService", "Failed to rebind location updates: ${e.message}")
        }
    }

    internal fun refreshGpsPowerProfile() {
        val targetProfile = resolveGpsPowerProfile(
            isPaused = _liveState.value.isPaused,
            isBatterySaverMode = _liveState.value.isBatterySaverMode,
            isScreenInteractive = isScreenInteractiveNow(),
            isMoving = isMovingRecently
        )
        applyGpsPowerProfile(targetProfile)
    }

    @SuppressLint("MissingPermission")
    internal fun startForegroundLocationTracking() {
        val route = currentPlannedRoute
        val notification = buildNotification(
            trailName = route?.trailName ?: getString(com.example.R.string.app_name),
            stageName = route?.stages?.firstOrNull()?.stopName ?: getString(com.example.R.string.notif_nav_default_overnight),
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

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        val initialProfile = resolveGpsPowerProfile(
            isPaused = _liveState.value.isPaused,
            isBatterySaverMode = _liveState.value.isBatterySaverMode,
            isScreenInteractive = isScreenInteractiveNow(),
            isMoving = true
        )
        applyGpsPowerProfile(initialProfile, forceRebind = true)
    }

    internal fun handleNewLocation(location: Location) {
        val tracker = routeTracker ?: return
        val plannedRoute = currentPlannedRoute ?: return
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()

        // 1. Calculate smoothed speed over 10s
        var currentSpeedMps = location.speed.toDouble()
        val prev = lastLocation
        if (currentSpeedMps <= 0.0 && prev != null) {
            val dist = location.distanceTo(prev).toDouble()
            val dt = ((now - (if (prev.time > 0L) prev.time else now)) / 1000.0).coerceAtLeast(1.0)
            if (dist > 0.5) {
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
        isMovingRecently = speedKmh >= 1.0 || currentSpeedMps >= 0.28

        // Round 10B Item 10: When paused, run low power every 60s, and automatically resume normal
        // tracking when movement is detected!
        if (_liveState.value.isPaused) {
            val anchor = pausedAnchorLocation
            if (anchor == null) {
                pausedAnchorLocation = location
            }
            val distFromPauseAnchorM = pausedAnchorLocation?.let { location.distanceTo(it).toDouble() } ?: 0.0
            val movementDetected = speedKmh >= 1.5 || currentSpeedMps >= 0.42 || distFromPauseAnchorM >= 20.0
            if (movementDetected) {
                pausedAnchorLocation = null
                setPausedInternal(false)
            } else {
                _liveState.value = _liveState.value.copy(
                    currentLat = location.latitude,
                    currentLng = location.longitude,
                    bearing = location.bearing,
                    hasGpsBearing = location.hasBearing(),
                    accuracyM = location.accuracy,
                    speedKmh = speedKmh,
                    lastFixTimestampMs = now
                )
                refreshGpsPowerProfile()
                return
            }
        }

        // Refresh GPS power profile dynamically based on movement, screen state, and battery-saver mode
        refreshGpsPowerProfile()

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
            isTracking = !trackResult.isCompleted,
            currentLat = location.latitude,
            currentLng = location.longitude,
            bearing = location.bearing,
            hasGpsBearing = location.hasBearing(),
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

        // Round 10B Item 9: When RouteTracker reports completed, mark the plan finished in Mine ture,
        // stop location updates and the service, and keep isCompleted = true in liveState so the summary shows.
        if (trackResult.isCompleted) {
            val completedProgress = TripProgressEntity(
                planId = planId,
                currentDay = trackResult.currentDay,
                distanceWalkedTodayKm = trackResult.walkedTodayKm,
                totalDistanceWalkedKm = trackResult.currentWalkKm,
                currentSnappedLat = trackResult.snappedPoint?.lat ?: location.latitude,
                currentSnappedLng = trackResult.snappedPoint?.lng ?: location.longitude,
                remainingDistanceTripKm = 0.0,
                remainingDistanceTodayKm = 0.0,
                isPaused = false,
                isCompleted = true,
                lastActiveTimestamp = now
            )
            serviceScope.launch {
                try {
                    DanskStiApp.instance.database.dao().saveTripProgress(completedProgress)
                    tracker.markPersisted(now)
                } catch (e: Exception) {
                    Log.w("NavService", "Failed to save completed progress: ${e.message}")
                }
            }
            stopTracking()
            return
        }

        // 5. Update Foreground Notification
        val stageName = trackResult.currentStage?.stopName ?: getString(com.example.R.string.notif_nav_default_stage)
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
        if (paused) {
            pausedAnchorLocation = lastLocation
        } else {
            pausedAnchorLocation = null
            isMovingRecently = true
        }
        _liveState.value = _liveState.value.copy(isPaused = paused)
        refreshGpsPowerProfile()
        val route = currentPlannedRoute
        if (route != null) {
            val st = _liveState.value
            val notif = buildNotification(
                trailName = route.trailName,
                stageName = st.currentStage?.stopName ?: getString(com.example.R.string.notif_nav_default_stage),
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

    internal fun setBatterySaverInternal(enabled: Boolean) {
        _liveState.value = _liveState.value.copy(isBatterySaverMode = enabled)
        refreshGpsPowerProfile()
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

    internal fun buildLaunchNavigationIntent(activePlanId: Long = planId): Intent {
        return buildLaunchNavigationIntent(this, activePlanId)
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
        val launchIntent = buildLaunchNavigationIntent(planId)
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
            getString(com.example.R.string.notif_nav_off_route_title, offRouteMeters.toInt())
        } else {
            getString(com.example.R.string.notif_nav_hiking_title, trailName)
        }

        val content = if (remainingTodayKm != null) {
            val configuredPace = com.example.data.local.SettingsRepository.cachedMitTempoKmh.takeIf { it > 0.0 } ?: userPaceKmh
            val arrivalClock = com.example.util.SunCalculator.computeArrivalClock(
                remainingTodayKm = remainingTodayKm,
                speedKmh = speedKmh,
                configuredPaceKmh = configuredPace
            )
            val etaString = getString(com.example.R.string.notif_nav_eta_prefix, arrivalClock)
            getString(com.example.R.string.notif_nav_next_camp_with_dist, stageName, remainingTodayKm, etaString)
        } else {
            getString(com.example.R.string.notif_nav_next_camp_only, stageName)
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
        val pauseActionLabel = getString(
            if (isPaused) com.example.R.string.notif_nav_action_resume else com.example.R.string.notif_nav_action_pause
        )

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
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(com.example.R.string.notif_nav_action_stop_today),
                stopTodayPending
            )
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

    internal fun stopTracking() {
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Exception) {}
        _liveState.value = _liveState.value.copy(isTracking = false)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
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
                    val warningText = getString(com.example.R.string.notif_private_forest_closing_short, sunsetStr)
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
        val launchIntent = buildLaunchNavigationIntent(planId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            99,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shortText = getString(com.example.R.string.notif_private_forest_closing_short, sunsetTime)
        val longText = getString(com.example.R.string.notif_private_forest_closing_long, sunsetTime)
        val notif = NotificationCompat.Builder(this, DanskStiApp.CHANNEL_ALERTS_ID)
            .setContentTitle(getString(com.example.R.string.notif_private_forest_title))
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
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
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Exception) {}
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
        const val EXTRA_OPEN_NAVIGATION = "extra_open_navigation"

        fun buildLaunchNavigationIntent(context: Context, activePlanId: Long = 0L): Intent {
            val resolvedPlanId = if (activePlanId > 0L) activePlanId else _liveState.value.planId
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PLAN_ID, resolvedPlanId)
                putExtra(EXTRA_OPEN_NAVIGATION, true)
            }
        }

        @Volatile
        internal var activeServiceInstance: NavigationTrackingService? = null

        private val _liveState = MutableStateFlow(NavigationLiveState())
        val liveState: StateFlow<NavigationLiveState> = _liveState.asStateFlow()

        /**
         * Round 10B Item 10:
         * Determines the active GPS power profile:
         * - Paused: low power every 60 s (`LOW_POWER_60S`).
         * - Battery-saver screen (or stationary or screen off): balanced accuracy every 10 s (`BALANCED_10S`).
         * - Moving with the screen on: high accuracy every 2 s (`HIGH_ACCURACY_2S`).
         */
        fun resolveGpsPowerProfile(
            isPaused: Boolean,
            isBatterySaverMode: Boolean,
            isScreenInteractive: Boolean,
            isMoving: Boolean
        ): GpsPowerProfile {
            return when {
                isPaused -> GpsPowerProfile.LOW_POWER_60S
                isBatterySaverMode || !isScreenInteractive || !isMoving -> GpsPowerProfile.BALANCED_10S
                else -> GpsPowerProfile.HIGH_ACCURACY_2S
            }
        }

        internal fun updateLiveStateForTest(state: NavigationLiveState) {
            _liveState.value = state
        }

        fun setPaused(context: Context, paused: Boolean) {
            activeServiceInstance?.setPausedInternal(paused) ?: run {
                val targetProfile = resolveGpsPowerProfile(
                    isPaused = paused,
                    isBatterySaverMode = _liveState.value.isBatterySaverMode,
                    isScreenInteractive = true,
                    isMoving = !paused
                )
                _liveState.value = _liveState.value.copy(
                    isPaused = paused,
                    activeGpsIntervalMs = targetProfile.intervalMs
                )
            }
        }

        fun setBatterySaverMode(enabled: Boolean) {
            activeServiceInstance?.setBatterySaverInternal(enabled) ?: run {
                val targetProfile = resolveGpsPowerProfile(
                    isPaused = _liveState.value.isPaused,
                    isBatterySaverMode = enabled,
                    isScreenInteractive = true,
                    isMoving = true
                )
                _liveState.value = _liveState.value.copy(
                    isBatterySaverMode = enabled,
                    activeGpsIntervalMs = targetProfile.intervalMs
                )
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
            activeServiceInstance?.stopTracking() ?: run {
                _liveState.value = _liveState.value.copy(isTracking = false)
            }
            try {
                val intent = Intent(context, NavigationTrackingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stopForToday(context: Context) {
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_STOP_TODAY
            }
            context.startService(intent)
        }
    }
}
