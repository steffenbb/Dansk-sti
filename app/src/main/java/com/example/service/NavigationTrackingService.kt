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
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.DanskStiApp
import com.example.MainActivity
import com.example.data.model.GeoPointDto
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.util.GeoUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NavigationLiveState(
    val isTracking: Boolean = false,
    val currentLat: Double = 55.6761,
    val currentLng: Double = 12.5683,
    val bearing: Float = 0f,
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
    val currentStage: TripStage? = null
)

class NavigationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var trailPoints: List<GeoPointDto> = emptyList()
    private var planId: Long = 0L
    private var trailName: String = ""
    private var currentDay: Int = 1
    private var stages: List<TripStage> = emptyList()
    private var totalTripLengthKm: Double = 0.0
    private var targetTodayEndKm: Double = 0.0

    // Speed smoothing over rolling 10 seconds
    private val speedHistory = ArrayDeque<Pair<Long, Double>>() // timestamp, speed m/s
    private var lastLocation: Location? = null
    private var lastVibrationTime = 0L

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DanskSti::NavWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                parseIntentData(intent)
                startForegroundTracking()
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

    private fun parseIntentData(intent: Intent?) {
        if (intent == null) return
        planId = intent.getLongExtra(EXTRA_PLAN_ID, 0L)
        trailName = intent.getStringExtra(EXTRA_TRAIL_NAME) ?: "Dansk Sti"
        currentDay = intent.getIntExtra(EXTRA_CURRENT_DAY, 1)
        totalTripLengthKm = intent.getDoubleExtra(EXTRA_TOTAL_TRIP_KM, 0.0)
        targetTodayEndKm = intent.getDoubleExtra(EXTRA_TODAY_END_KM, 15.0)

        val pointsJson = intent.getStringExtra(EXTRA_TRAIL_POINTS_JSON)
        if (!pointsJson.isNullOrEmpty()) {
            try {
                val moshi = Moshi.Builder().build()
                val pointListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
                trailPoints = moshi.adapter<List<GeoPointDto>>(pointListType).fromJson(pointsJson) ?: emptyList()
            } catch (e: Exception) {
                Log.e("NavService", "Failed to parse trail points", e)
            }
        }

        val stagesJson = intent.getStringExtra(EXTRA_STAGES_JSON)
        if (!stagesJson.isNullOrEmpty()) {
            try {
                val moshi = Moshi.Builder().build()
                val stageListType = Types.newParameterizedType(List::class.java, TripStage::class.java)
                stages = moshi.adapter<List<TripStage>>(stageListType).fromJson(stagesJson) ?: emptyList()
            } catch (e: Exception) {
                Log.e("NavService", "Failed to parse stages", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundTracking() {
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
        val notification = buildNotification("Starter sporing...", 0.0, 0.0, false)

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
        _liveState.value = _liveState.value.copy(
            isTracking = true,
            planId = planId,
            trailName = trailName,
            currentDay = currentDay,
            currentStage = stages.getOrNull(currentDay - 1)
        )
    }

    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()
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

        // Add to 10s speed smoothing window
        speedHistory.addLast(now to currentSpeedMps)
        while (speedHistory.isNotEmpty() && (now - speedHistory.first().first) > 10000L) {
            speedHistory.removeFirst()
        }
        val smoothedSpeedMps = if (speedHistory.isNotEmpty()) {
            speedHistory.map { it.second }.average()
        } else 0.0
        val speedKmh = smoothedSpeedMps * 3.6

        // Route snapping and off-trail calculation
        val userPt = GeoPointDto(location.latitude, location.longitude)
        var offTrailDist = 0.0
        var isOffTrail = false
        var snappedPt: GeoPointDto? = null
        var totalWalkedKm = _liveState.value.totalDistanceWalkedKm
        var walkedTodayKm = _liveState.value.distanceWalkedTodayKm
        var remainingTodayKm = _liveState.value.distanceRemainingTodayKm
        var remainingTripKm = _liveState.value.distanceRemainingTripKm

        if (trailPoints.isNotEmpty()) {
            offTrailDist = GeoUtils.distanceToPolylineMeters(userPt, trailPoints)
            isOffTrail = offTrailDist > 100.0
            snappedPt = GeoUtils.snapPointToPolyline(userPt, trailPoints)

            val currentProgressAlongTrailKm = GeoUtils.distanceAlongPolylineKm(trailPoints, snappedPt)
            totalWalkedKm = currentProgressAlongTrailKm
            val stageStartKm = stages.getOrNull(currentDay - 1)?.startKm ?: 0.0
            walkedTodayKm = maxOf(0.0, currentProgressAlongTrailKm - stageStartKm)

            val currentStageEndKm = stages.getOrNull(currentDay - 1)?.endKm ?: targetTodayEndKm
            remainingTodayKm = maxOf(0.0, currentStageEndKm - currentProgressAlongTrailKm)
            remainingTripKm = maxOf(0.0, totalTripLengthKm - currentProgressAlongTrailKm)

            if (isOffTrail && (now - lastVibrationTime) > 30000L) { // alert at most once every 30s
                triggerOffTrailVibration()
                lastVibrationTime = now
            }
        }

        _liveState.value = _liveState.value.copy(
            isTracking = true,
            currentLat = location.latitude,
            currentLng = location.longitude,
            bearing = location.bearing,
            speedKmh = speedKmh,
            distanceWalkedTodayKm = walkedTodayKm,
            totalDistanceWalkedKm = totalWalkedKm,
            distanceRemainingTodayKm = remainingTodayKm,
            distanceRemainingTripKm = remainingTripKm,
            offTrailDistanceMeters = offTrailDist,
            isOffTrailWarning = isOffTrail,
            currentSnappedPoint = snappedPt
        )

        // Update Foreground Notification
        val notif = buildNotification(
            trailName = trailName,
            speedKmh = speedKmh,
            remainingTripKm = remainingTripKm,
            isOffTrail = isOffTrail
        )
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifManager?.notify(NOTIFICATION_ID, notif)

        // Persist progress periodically
        if (planId != 0L) {
            serviceScope.launch {
                val progress = TripProgressEntity(
                    planId = planId,
                    currentDay = currentDay,
                    distanceWalkedTodayKm = walkedTodayKm,
                    totalDistanceWalkedKm = totalWalkedKm,
                    currentSnappedLat = snappedPt?.lat ?: location.latitude,
                    currentSnappedLng = snappedPt?.lng ?: location.longitude,
                    remainingDistanceTripKm = remainingTripKm,
                    remainingDistanceTodayKm = remainingTodayKm,
                    isPaused = false,
                    isCompleted = remainingTripKm <= 0.05,
                    lastActiveTimestamp = now
                )
                DanskStiApp.instance.database.dao().saveTripProgress(progress)
            }
        }
    }

    private fun triggerOffTrailVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 400), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 300, 200, 400), -1)
            }
        } catch (e: Exception) {
            Log.e("NavService", "Vibration failed: ${e.message}")
        }
    }

    private fun buildNotification(
        trailName: String,
        speedKmh: Double,
        remainingTripKm: Double,
        isOffTrail: Boolean
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

        val title = if (isOffTrail) "⚠️ Kursafvigelse! >100m fra ruten" else "Vandring: $trailName"
        val content = String.format("Hastighed: %.1f km/t  •  Mangler: %.1f km", speedKmh, remainingTripKm)

        val channelId = if (isOffTrail) DanskStiApp.CHANNEL_ALERTS_ID else DanskStiApp.CHANNEL_NAVIGATION_ID

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun stopForToday() {
        serviceScope.launch {
            if (planId != 0L) {
                val current = _liveState.value
                val progress = TripProgressEntity(
                    planId = planId,
                    currentDay = minOf(stages.size, currentDay + 1),
                    distanceWalkedTodayKm = 0.0,
                    totalDistanceWalkedKm = current.totalDistanceWalkedKm,
                    currentSnappedLat = current.currentSnappedPoint?.lat ?: current.currentLat,
                    currentSnappedLng = current.currentSnappedPoint?.lng ?: current.currentLng,
                    remainingDistanceTripKm = current.distanceRemainingTripKm,
                    remainingDistanceTodayKm = stages.getOrNull(currentDay)?.stageKm ?: 15.0,
                    isPaused = true,
                    isCompleted = false,
                    lastActiveTimestamp = System.currentTimeMillis()
                )
                DanskStiApp.instance.database.dao().saveTripProgress(progress)
            }
            stopTracking()
        }
    }

    private fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        _liveState.value = _liveState.value.copy(isTracking = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 4040
        const val ACTION_START = "com.example.dansksti.ACTION_START"
        const val ACTION_STOP = "com.example.dansksti.ACTION_STOP"
        const val ACTION_STOP_TODAY = "com.example.dansksti.ACTION_STOP_TODAY"

        const val EXTRA_PLAN_ID = "extra_plan_id"
        const val EXTRA_TRAIL_NAME = "extra_trail_name"
        const val EXTRA_CURRENT_DAY = "extra_current_day"
        const val EXTRA_TOTAL_TRIP_KM = "extra_total_trip_km"
        const val EXTRA_TODAY_END_KM = "extra_today_end_km"
        const val EXTRA_TRAIL_POINTS_JSON = "extra_trail_points_json"
        const val EXTRA_STAGES_JSON = "extra_stages_json"

        private val _liveState = MutableStateFlow(NavigationLiveState())
        val liveState: StateFlow<NavigationLiveState> = _liveState.asStateFlow()

        fun start(
            context: Context,
            planId: Long,
            trailName: String,
            currentDay: Int,
            totalTripKm: Double,
            todayEndKm: Double,
            pointsJson: String,
            stagesJson: String
        ) {
            val intent = Intent(context, NavigationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PLAN_ID, planId)
                putExtra(EXTRA_TRAIL_NAME, trailName)
                putExtra(EXTRA_CURRENT_DAY, currentDay)
                putExtra(EXTRA_TOTAL_TRIP_KM, totalTripKm)
                putExtra(EXTRA_TODAY_END_KM, todayEndKm)
                putExtra(EXTRA_TRAIL_POINTS_JSON, pointsJson)
                putExtra(EXTRA_STAGES_JSON, stagesJson)
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
