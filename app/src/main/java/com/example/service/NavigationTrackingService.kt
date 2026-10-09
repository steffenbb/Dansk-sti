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
    val currentSnappedPoint: GeoPointDto? = null,
    val planId: Long = 0L,
    val trailName: String = "",
    val currentDay: Int = 1,
    val currentStage: TripStage? = null,
    val nearestPointAhead: RoutePoint? = null,
    val bearingToPointAhead: Float = 0f,
    val isCompleted: Boolean = false,
    val plannedRoute: PlannedRoute? = null
)

class NavigationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var plannedRouteRepo: PlannedRouteRepository
    private var routeTracker: RouteTracker? = null
    private var currentPlannedRoute: PlannedRoute? = null
    private var planId: Long = 0L

    // Speed smoothing over rolling 10 seconds
    private val speedHistory = ArrayDeque<Pair<Long, Double>>() // timestamp, speed m/s
    private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        plannedRouteRepo = PlannedRouteRepository(applicationContext)
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

            _liveState.value = _liveState.value.copy(
                isTracking = true,
                planId = targetPlanId,
                trailName = plannedRoute.trailName,
                currentDay = initialDay,
                currentStage = initialStage,
                totalDistanceWalkedKm = initialWalkKm,
                distanceWalkedTodayKm = walkedToday,
                distanceRemainingTodayKm = remainingToday,
                distanceRemainingTripKm = remainingTrip,
                plannedRoute = plannedRoute
            )

            // Start foreground on main thread
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                startForegroundLocationTracking()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundLocationTracking() {
        val route = currentPlannedRoute
        val notification = buildNotification(
            trailName = route?.trailName ?: "Dansk Sti",
            stageName = route?.stages?.firstOrNull()?.stopName ?: "Overnatning",
            speedKmh = 0.0,
            remainingTodayKm = route?.stages?.firstOrNull()?.stageKm ?: 15.0,
            remainingTripKm = route?.totalWalkKm ?: 0.0,
            isOffRoute = false,
            offRouteMeters = 0.0
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
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

        // 2. Feed fix to RouteTracker
        val trackResult = tracker.onLocationUpdate(
            lat = location.latitude,
            lng = location.longitude,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestampMs = now
        )

        // 3. Trigger vibration if required by off-route logic
        if (trackResult.shouldVibrate) {
            triggerOffTrailVibration()
        }

        // 4. Update Live State
        _liveState.value = NavigationLiveState(
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
            isOffTrailWarning = trackResult.isOffRoute,
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
            isOffRoute = trackResult.isOffRoute,
            offRouteMeters = trackResult.offRouteDistanceM
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
    }

    private fun triggerOffTrailVibration() {
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

    private fun buildNotification(
        trailName: String,
        stageName: String,
        speedKmh: Double,
        remainingTodayKm: Double,
        remainingTripKm: Double,
        isOffRoute: Boolean,
        offRouteMeters: Double
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

        val pace = if (speedKmh > 1.0) speedKmh else 4.2
        val etaMinutes = (remainingTodayKm / pace * 60.0).toInt()
        val etaTimestamp = System.currentTimeMillis() + (etaMinutes * 60000L)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val etaString = "kl. ${timeFormat.format(Date(etaTimestamp))}"

        val title = if (isOffRoute) {
            "⚠️ Kursafvigelse (${offRouteMeters.toInt()} m fra ruten)"
        } else {
            "Vandring: $trailName"
        }

        val content = "Næste lejr: $stageName • ${String.format(Locale.getDefault(), "%.1f", remainingTodayKm)} km ($etaString)"

        val channelId = if (isOffRoute) DanskStiApp.CHANNEL_ALERTS_ID else DanskStiApp.CHANNEL_NAVIGATION_ID

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop for i dag", stopTodayPending)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun stopForToday() {
        val tracker = routeTracker
        if (tracker != null && planId != 0L) {
            serviceScope.launch {
                val progress = tracker.stopForToday()
                DanskStiApp.instance.database.dao().saveTripProgress(progress)
                _liveState.value = _liveState.value.copy(
                    isTracking = false,
                    currentDay = progress.currentDay,
                    distanceWalkedTodayKm = 0.0,
                    distanceRemainingTodayKm = progress.remainingDistanceTodayKm
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

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 4040
        const val ACTION_START = "com.example.dansksti.ACTION_START"
        const val ACTION_STOP = "com.example.dansksti.ACTION_STOP"
        const val ACTION_STOP_TODAY = "com.example.dansksti.ACTION_STOP_TODAY"

        const val EXTRA_PLAN_ID = "extra_plan_id"

        private val _liveState = MutableStateFlow(NavigationLiveState())
        val liveState: StateFlow<NavigationLiveState> = _liveState.asStateFlow()

        /**
         * Starts navigation with ONLY planId in the Intent.
         */
        fun start(context: Context, planId: Long) {
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PLAN_ID, planId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
