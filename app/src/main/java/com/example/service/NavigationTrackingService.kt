package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.example.DanskStiApp
import com.example.MainActivity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.repository.AccessLandRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.util.RouteTracker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

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
    val activeGpsIntervalMs: Long = 2_000L,
    val hasArrivedAtTonightCamp: Boolean = false,
    val autoAdvancedDayMessage: String? = null
)

/**
 * GPS power profiles:
 * - HIGH_ACCURACY_2S: High accuracy every 2 s while moving + navigating with the screen on.
 * - HIGH_ACCURACY_5S: High accuracy every 5 s (minUpdateDistance 0 m) while moving + navigating with the screen off.
 * - BALANCED_10S: Balanced accuracy every 10 s only when stationary for 2 min or in battery-saver mode.
 * - LOW_POWER_60S: Low power every 60 s while paused.
 */
enum class GpsPowerProfile(
    val priority: Int,
    val intervalMs: Long,
    val minUpdateIntervalMs: Long,
    val minDistanceM: Float
) {
    HIGH_ACCURACY_2S(Priority.PRIORITY_HIGH_ACCURACY, 2_000L, 1_000L, 1.0f),
    HIGH_ACCURACY_5S(Priority.PRIORITY_HIGH_ACCURACY, 5_000L, 2_500L, 0.0f),
    BALANCED_10S(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L, 5_000L, 2.0f),
    LOW_POWER_60S(Priority.PRIORITY_LOW_POWER, 60_000L, 30_000L, 5.0f)
}

class NavigationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Spoken turn cues (setting "Stemmevejledning"); lives here so it works with the app's UI closed. */
    internal val voiceGuide by lazy { NavigationVoiceGuide(this) }
    @Volatile internal var voiceGuidanceEnabled = false

    /** Rain alerts (setting "Regnvarsel"); here so they work with the app's UI closed. */
    internal val rainWatcher by lazy {
        NavigationRainWatcher(this) { lat, lng, nowMs ->
            DanskStiApp.instance.repository.getTrailWeather(lat, lng, forceNetworkRefresh = true, nowMs = nowMs)
        }
    }
    @Volatile internal var rainAlertEnabled = true
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var plannedRouteRepo: PlannedRouteRepository
    private lateinit var settingsRepository: com.example.data.local.SettingsRepository
    @Volatile
    private var userPaceKmh: Double = com.example.data.local.SettingsRepository.cachedMitTempoKmh
    @Volatile
    internal var watchTurnCueNotificationsEnabled: Boolean = false
    internal var routeTracker: RouteTracker? = null
    internal var currentPlannedRoute: PlannedRoute? = null
    internal var planId: Long = 0L

    // Speed smoothing over rolling 10 seconds
    private val speedHistory = ArrayDeque<Pair<Long, Double>>() // timestamp, speed m/s
    private var lastLocation: Location? = null
    internal var lastAccurateLocation: Location? = null
    internal var pausedAnchorLocation: Location? = null
    private var alerted150CueWalkKm: Double? = null
    private var alerted30CueWalkKm: Double? = null
    internal var activeTurnCueNotificationWalkKm: Double? = null
    internal var serviceTurnCueVibrationCount: Int = 0
    internal var turnCueNotificationPostCount: Int = 0
    internal var lastTurnCueVibrationPattern: LongArray? = null
    internal var lastTurnCueChannelId: String? = null
    internal var lastWidgetUpdateMs: Long = 0L
    internal var lastWidgetDay: Int = -1
    internal var lastWidgetCampName: String = ""
    internal var lastWidgetTracking: Boolean? = null

    // Evidence-based stationary detection & movement confirmation with exponential backoff
    internal var movementAnchorLocation: Location? = null
    internal var movementAnchorTimestampMs: Long = 0L
    internal val accurateFixTimestampsInAnchorWindow = ArrayDeque<Long>()
    @Volatile
    internal var isStationaryFor2Min: Boolean = false
    internal var currentConfirmCooldownMs: Long = INITIAL_CONFIRM_COOLDOWN_MS
    internal var lastMovementConfirmRequestMs: Long = 0L
    internal var failedConfirmCount: Int = 0
    internal var confirmationRequestCount: Int = 0
    @Volatile
    internal var movementConfirmJob: Job? = null
    @Volatile
    internal var pauseAnchorFetchJob: Job? = null
    @Volatile
    internal var activeConfirmTokenSource: CancellationTokenSource? = null
    internal var highAccuracySingleFixFetcher: (suspend (CancellationToken) -> Location?)? = null

    // Screen on/off receiver state
    @Volatile
    internal var isScreenReceiverRegistered: Boolean = false
    @Volatile
    internal var lastBroadcastScreenInteractive: Boolean? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastBroadcastScreenInteractive = false
                    refreshGpsPowerProfile()
                }
                Intent.ACTION_SCREEN_ON -> {
                    lastBroadcastScreenInteractive = true
                    refreshGpsPowerProfile()
                }
            }
        }
    }

    // Turn cue reference cache
    private var cachedTurnCuesRoute: PlannedRoute? = null
    private var cachedTurnCues: List<com.example.data.model.TurnCue> = emptyList()
    internal var turnCueComputeCount: Int = 0

    // Corridor access-polygon prefetch state (next 25 km, max 10 tiles, re-run after +20 km, retry on connectivity or 10 min)
    internal var lastAccessPrefetchWalkKm: Double = Double.NaN
    @Volatile
    internal var corridorPrefetchPendingRetry: Boolean = false
    @Volatile
    internal var lastCorridorPrefetchAttemptMs: Long = 0L
    internal var corridorPrefetchRunCount: Int = 0
    internal var corridorPrefetchTileFetchCount: Int = 0
    @Volatile
    internal var corridorPrefetchJob: Job? = null
    @Volatile
    internal var isCorridorNetworkCallbackRegistered: Boolean = false
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    internal val corridorNetworkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            mainHandler.post {
                if (!isCorridorNetworkCallbackRegistered) return@post
                if (corridorPrefetchPendingRetry && corridorPrefetchJob?.isActive != true) {
                    val route = currentPlannedRoute ?: return@post
                    val walkKm = routeTracker?.currentWalkKm ?: _liveState.value.totalDistanceWalkedKm
                    prefetchAccessPolygonsForRouteCorridor(route, walkKm)
                }
            }
        }
    }

    // Ongoing notification throttling & single off-route alert state
    internal var lastOngoingNotificationMs: Long = 0L
    internal var lastOngoingNotificationTitle: String = ""
    internal var lastOngoingNotificationContent: String = ""
    internal var lastOngoingNotificationPaused: Boolean? = null
    internal var hasSentOffRouteAlert: Boolean = false
    internal var offRouteAlertSentCount: Int = 0
    internal var ongoingNotificationPostCount: Int = 0

    @Volatile
    internal var currentGpsProfile: GpsPowerProfile = GpsPowerProfile.HIGH_ACCURACY_2S
    internal var locationRebindCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        plannedRouteRepo = PlannedRouteRepository(applicationContext)
        settingsRepository = DanskStiApp.getSettingsRepository(applicationContext)
        watchTurnCueNotificationsEnabled = settingsRepository.currentSettingsSnapshot().watchTurnCueNotificationsEnabled
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                userPaceKmh = settings.mitTempoKmh
                watchTurnCueNotificationsEnabled = settings.watchTurnCueNotificationsEnabled
                rainAlertEnabled = settings.rainAlertEnabled
                if (settings.voiceGuidance != voiceGuidanceEnabled) {
                    voiceGuidanceEnabled = settings.voiceGuidance
                    voiceGuide.setEnabled(settings.voiceGuidance)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                // Foreground first: the route load below can take longer than Android's start deadline
                if (!enterForeground()) return START_NOT_STICKY
                voiceGuidanceEnabled = settingsRepository.currentSettingsSnapshot().voiceGuidance
                voiceGuide.setEnabled(voiceGuidanceEnabled)
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

            val allCues = getOrComputeTurnCues(plannedRoute)
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

            maybeUpdateActiveTripWidget(force = true)

            // Prefetch access polygons along the next 25 km route corridor (max 10 tiles, BACKGROUND priority)
            prefetchAccessPolygonsForRouteCorridor(plannedRoute, initialWalkKm)

            // Start foreground on main thread
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                startForegroundLocationTracking()
            }
        }
    }

    internal fun getOrComputeTurnCues(plannedRoute: PlannedRoute): List<com.example.data.model.TurnCue> {
        if (cachedTurnCuesRoute !== plannedRoute) {
            cachedTurnCues = com.example.util.routing.TurnCueGenerator.computeTurnCuesForPlannedRoute(plannedRoute)
            cachedTurnCuesRoute = plannedRoute
            turnCueComputeCount++
        }
        return cachedTurnCues
    }

    internal fun collectNext25KmCorridorTiles(
        plannedRoute: PlannedRoute,
        currentWalkKm: Double
    ): List<AccessLandRepository.TileBounds> {
        if (plannedRoute.points.isEmpty()) return emptyList()
        val endWalkKm = currentWalkKm + CORRIDOR_PREFETCH_WINDOW_KM
        val tilesById = LinkedHashMap<String, AccessLandRepository.TileBounds>()
        var prevPt: RoutePoint? = null

        for (pt in plannedRoute.points) {
            if (pt.walkKm < currentWalkKm) {
                prevPt = pt
                continue
            }
            if (pt.walkKm > endWalkKm && tilesById.isNotEmpty()) {
                break
            }
            val p0 = prevPt?.takeIf { it.walkKm >= currentWalkKm - 0.5 } ?: pt
            val minLat = minOf(p0.lat, pt.lat)
            val maxLat = maxOf(p0.lat, pt.lat)
            val minLng = minOf(p0.lng, pt.lng)
            val maxLng = maxOf(p0.lng, pt.lng)
            val segTiles = AccessLandRepository.getTileIdsForBounds(minLat, maxLat, minLng, maxLng)
            for (tile in segTiles) {
                if (!tilesById.containsKey(tile.tileId)) {
                    tilesById[tile.tileId] = tile
                    if (tilesById.size >= CORRIDOR_PREFETCH_MAX_TILES) {
                        return tilesById.values.toList()
                    }
                }
            }
            prevPt = pt
            if (pt.walkKm > endWalkKm) break
        }
        return tilesById.values.take(CORRIDOR_PREFETCH_MAX_TILES)
    }

    internal fun registerCorridorNetworkCallbackIfNeeded() {
        if (isCorridorNetworkCallbackRegistered) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                isCorridorNetworkCallbackRegistered = true
                cm.registerDefaultNetworkCallback(corridorNetworkCallback)
            }
        } catch (e: Exception) {
            isCorridorNetworkCallbackRegistered = false
            Log.w("NavService", "Failed to register corridor network callback: ${e.message}")
        }
    }

    internal fun unregisterCorridorNetworkCallbackIfNeeded() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!isCorridorNetworkCallbackRegistered) {
            corridorPrefetchPendingRetry = false
            return
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.unregisterNetworkCallback(corridorNetworkCallback)
        } catch (_: Exception) {
        } finally {
            isCorridorNetworkCallbackRegistered = false
            corridorPrefetchPendingRetry = false
        }
    }

    internal fun prefetchAccessPolygonsForRouteCorridor(
        plannedRoute: PlannedRoute,
        currentWalkKm: Double,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val tiles = collectNext25KmCorridorTiles(plannedRoute, currentWalkKm)
        if (tiles.isEmpty()) {
            lastAccessPrefetchWalkKm = currentWalkKm
            corridorPrefetchPendingRetry = false
            return
        }
        corridorPrefetchPendingRetry = false
        lastCorridorPrefetchAttemptMs = nowMs
        corridorPrefetchRunCount++
        corridorPrefetchJob?.cancel()
        val job = serviceScope.launch(Dispatchers.IO) {
            val app = DanskStiApp.instance
            val repo = app.repository
            val dao = app.database.dao()
            var anyTileFreshOrFetched = false
            for (tile in tiles) {
                try {
                    corridorPrefetchTileFetchCount++
                    repo.getAccessPolygons(
                        minLat = tile.minLat,
                        maxLat = tile.maxLat,
                        minLng = tile.minLng,
                        maxLng = tile.maxLng,
                        priority = com.example.data.remote.OverpassClient.Priority.BACKGROUND,
                        updateAvailabilityFlag = false
                    )
                    val checkNow = System.currentTimeMillis()
                    val cachedTile = dao.getAccessPolygonsByTileId(tile.tileId)
                    val isTileFresh = cachedTile.isNotEmpty() && cachedTile.all {
                        (checkNow - it.fetchedAt) < AccessLandRepository.CACHE_TTL_MS
                    }
                    if (isTileFresh) {
                        anyTileFreshOrFetched = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("NavService", "Corridor access prefetch failed for ${tile.tileId}: ${e.message}")
                }
            }
            if (anyTileFreshOrFetched) {
                lastAccessPrefetchWalkKm = currentWalkKm
                corridorPrefetchPendingRetry = false
            } else {
                corridorPrefetchPendingRetry = true
            }
        }
        corridorPrefetchJob = job
        registerCorridorNetworkCallbackIfNeeded()
    }

    private fun isScreenInteractiveNow(): Boolean {
        lastBroadcastScreenInteractive?.let { return it }
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun registerScreenReceiverIfNeeded() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("NavService", "Failed to register screen state receiver: ${e.message}")
        }
    }

    private fun unregisterScreenReceiverIfNeeded() {
        if (!isScreenReceiverRegistered) return
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        } finally {
            isScreenReceiverRegistered = false
            lastBroadcastScreenInteractive = null
        }
    }

    private fun cancelActiveConfirmationRequests() {
        try {
            activeConfirmTokenSource?.cancel()
        } catch (_: Exception) {}
        activeConfirmTokenSource = null
        movementConfirmJob?.cancel()
        movementConfirmJob = null
        pauseAnchorFetchJob?.cancel()
        pauseAnchorFetchJob = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleHighAccuracyFix(token: CancellationToken): Location? {
        val customFetcher = highAccuracySingleFixFetcher
        if (customFetcher != null) {
            return customFetcher(token)
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                // Token cancellation is handled by caller's CancellationTokenSource
            }
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun resetMovementConfirmationBackoff() {
        failedConfirmCount = 0
        currentConfirmCooldownMs = INITIAL_CONFIRM_COOLDOWN_MS
        lastMovementConfirmRequestMs = 0L
    }

    private fun recordFailedMovementConfirmation() {
        failedConfirmCount++
        val capMs = if (_liveState.value.isPaused) PAUSED_CONFIRM_MAX_BACKOFF_MS else STATIONARY_CONFIRM_MAX_BACKOFF_MS
        val shift = (failedConfirmCount - 1).coerceIn(0, 3)
        currentConfirmCooldownMs = (INITIAL_CONFIRM_COOLDOWN_MS shl shift).coerceAtMost(capMs)
    }

    private fun maybeRequestMovementConfirmation(nowMs: Long) {
        if (movementConfirmJob?.isActive == true) return
        if (lastMovementConfirmRequestMs != 0L && (nowMs - lastMovementConfirmRequestMs) < currentConfirmCooldownMs) {
            return
        }
        lastMovementConfirmRequestMs = nowMs
        confirmationRequestCount++

        val tokenSource = CancellationTokenSource()
        activeConfirmTokenSource = tokenSource

        movementConfirmJob = serviceScope.launch(Dispatchers.Main.immediate) {
            try {
                val confirmFix = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                    requestSingleHighAccuracyFix(tokenSource.token)
                }
                if (confirmFix != null && confirmFix.accuracy <= ACCURATE_FIX_MAX_M) {
                    lastAccurateLocation = confirmFix
                    val activeAnchor = if (_liveState.value.isPaused) pausedAnchorLocation else movementAnchorLocation
                    if (activeAnchor == null) {
                        if (_liveState.value.isPaused) {
                            pausedAnchorLocation = confirmFix
                        } else {
                            val fixTime = if (confirmFix.time > 0L) confirmFix.time else nowMs
                            movementAnchorLocation = confirmFix
                            movementAnchorTimestampMs = fixTime
                            accurateFixTimestampsInAnchorWindow.clear()
                            accurateFixTimestampsInAnchorWindow.addLast(fixTime)
                        }
                        recordFailedMovementConfirmation()
                    } else {
                        val distM = confirmFix.distanceTo(activeAnchor).toDouble()
                        if (distM >= MOVEMENT_THRESHOLD_M) {
                            val fixTime = if (confirmFix.time > 0L) confirmFix.time else nowMs
                            resetMovementConfirmationBackoff()
                            movementAnchorLocation = confirmFix
                            movementAnchorTimestampMs = fixTime
                            accurateFixTimestampsInAnchorWindow.clear()
                            accurateFixTimestampsInAnchorWindow.addLast(fixTime)
                            isStationaryFor2Min = false
                            if (_liveState.value.isPaused) {
                                pausedAnchorLocation = null
                                setPausedInternal(false)
                            } else {
                                refreshGpsPowerProfile()
                            }
                        } else {
                            recordFailedMovementConfirmation()
                        }
                    }
                } else {
                    recordFailedMovementConfirmation()
                }
            } finally {
                try {
                    tokenSource.cancel()
                } catch (_: Exception) {}
                if (activeConfirmTokenSource === tokenSource) {
                    activeConfirmTokenSource = null
                }
            }
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
            locationRebindCount++
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
            isMoving = !isStationaryFor2Min
        )
        applyGpsPowerProfile(targetProfile)
    }

    /**
     * Goes foreground with the navigation notification. Called as the very first step of ACTION_START,
     * because Android requires startForeground within a few seconds of startForegroundService,
     * before the (possibly slow) route load; called again once the route is loaded to refresh the text.
     * Returns false (and stops the service) when Android refuses.
     */
    internal fun enterForeground(): Boolean {
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
            return false
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException when restarted from the background
            Log.e("NavService", "startForeground not allowed; stopping cleanly", e)
            _liveState.value = _liveState.value.copy(isTracking = false)
            stopSelf()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    internal fun startForegroundLocationTracking() {
        if (!enterForeground()) return

        registerScreenReceiverIfNeeded()

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
            isMoving = !isStationaryFor2Min
        )
        applyGpsPowerProfile(initialProfile, forceRebind = true)
    }

    internal fun handleNewLocation(location: Location) {
        val tracker = routeTracker ?: return
        val plannedRoute = currentPlannedRoute ?: return
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val isAccurate = location.accuracy <= ACCURATE_FIX_MAX_M
        if (isAccurate) {
            lastAccurateLocation = location
        }

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

        // Prune rolling 2-minute window of accurate fixes near movementAnchorLocation
        while (accurateFixTimestampsInAnchorWindow.isNotEmpty() &&
            (now - accurateFixTimestampsInAnchorWindow.first()) > STATIONARY_DURATION_MS
        ) {
            accurateFixTimestampsInAnchorWindow.removeFirst()
        }

        var effectiveLocation = location

        // Paused handling: low power every 60s; anchor only set from accurate fixes (<= 30m).
        // Auto-resume immediately on an accurate fix >= 20m from pausedAnchorLocation,
        // or request a single high-accuracy confirmation on an inaccurate candidate >= 20m.
        if (_liveState.value.isPaused) {
            var anchor = pausedAnchorLocation
            if (anchor == null && isAccurate) {
                pausedAnchorLocation = location
                anchor = location
            }
            val distFromPauseAnchorM = anchor?.let { location.distanceTo(it).toDouble() } ?: 0.0
            if (anchor != null && distFromPauseAnchorM >= MOVEMENT_THRESHOLD_M) {
                if (isAccurate) {
                    // Immediate confirmed movement without getCurrentLocation request
                    cancelActiveConfirmationRequests()
                    resetMovementConfirmationBackoff()
                    movementAnchorLocation = location
                    movementAnchorTimestampMs = now
                    accurateFixTimestampsInAnchorWindow.clear()
                    accurateFixTimestampsInAnchorWindow.addLast(now)
                    isStationaryFor2Min = false
                    pausedAnchorLocation = null
                    setPausedInternal(false)
                } else {
                    // Candidate only: request high-accuracy confirmation (with backoff)
                    maybeRequestMovementConfirmation(now)
                    if (_liveState.value.isPaused) {
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
                    } else {
                        effectiveLocation = lastAccurateLocation ?: location
                    }
                }
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

        // Active (non-paused) movement & stationary detection
        if (isStationaryFor2Min) {
            val anchor = movementAnchorLocation
            if (anchor == null) {
                if (isAccurate) {
                    movementAnchorLocation = location
                    movementAnchorTimestampMs = now
                    accurateFixTimestampsInAnchorWindow.clear()
                    accurateFixTimestampsInAnchorWindow.addLast(now)
                }
            } else {
                val distFromAnchorM = location.distanceTo(anchor).toDouble()
                if (distFromAnchorM >= MOVEMENT_THRESHOLD_M) {
                    if (isAccurate) {
                        // Immediate confirmed movement on accurate fix >= 20m (0 confirmation requests)
                        cancelActiveConfirmationRequests()
                        resetMovementConfirmationBackoff()
                        movementAnchorLocation = location
                        movementAnchorTimestampMs = now
                        accurateFixTimestampsInAnchorWindow.clear()
                        accurateFixTimestampsInAnchorWindow.addLast(now)
                        isStationaryFor2Min = false
                    } else {
                        // Inaccurate candidate >= 20m: request single high-accuracy confirmation
                        maybeRequestMovementConfirmation(now)
                        if (!isStationaryFor2Min) {
                            effectiveLocation = lastAccurateLocation ?: location
                        }
                    }
                } else if (isAccurate) {
                    accurateFixTimestampsInAnchorWindow.addLast(now)
                }
            }
        } else {
            // Currently in moving state: only accurate fixes (<= 30m) can set/advance the anchor
            // or provide evidence for becoming stationary after 2 minutes (>= 3 accurate fixes within 20m).
            if (isAccurate) {
                val anchor = movementAnchorLocation
                if (anchor == null || location.distanceTo(anchor).toDouble() >= MOVEMENT_THRESHOLD_M) {
                    movementAnchorLocation = location
                    movementAnchorTimestampMs = now
                    accurateFixTimestampsInAnchorWindow.clear()
                    accurateFixTimestampsInAnchorWindow.addLast(now)
                } else {
                    accurateFixTimestampsInAnchorWindow.addLast(now)
                    if ((now - movementAnchorTimestampMs) >= STATIONARY_DURATION_MS &&
                        accurateFixTimestampsInAnchorWindow.size >= MIN_ACCURATE_FIXES_FOR_STATIONARY
                    ) {
                        isStationaryFor2Min = true
                        resetMovementConfirmationBackoff()
                    }
                }
            }
        }

        // Refresh GPS power profile dynamically based on stationary state, screen state, and battery-saver mode
        refreshGpsPowerProfile()

        // 2. Feed fix to RouteTracker
        val trackResult = tracker.onLocationUpdate(
            lat = effectiveLocation.latitude,
            lng = effectiveLocation.longitude,
            bearing = effectiveLocation.bearing,
            accuracy = effectiveLocation.accuracy,
            timestampMs = now
        )

        // 2b. Corridor access-polygon prefetch (initial if not yet attempted, retry after 10 min if previous run failed, or when walked >= 20 km since last successful prefetch)
        if (corridorPrefetchJob?.isActive != true) {
            val shouldRunInitial = lastAccessPrefetchWalkKm.isNaN() && !corridorPrefetchPendingRetry && lastCorridorPrefetchAttemptMs == 0L
            val shouldRetryFailed = corridorPrefetchPendingRetry &&
                lastCorridorPrefetchAttemptMs > 0L &&
                (now - lastCorridorPrefetchAttemptMs) >= CORRIDOR_PREFETCH_RETRY_INTERVAL_MS
            val shouldRunNextStep = !lastAccessPrefetchWalkKm.isNaN() &&
                (trackResult.currentWalkKm - lastAccessPrefetchWalkKm) >= CORRIDOR_PREFETCH_STEP_KM
            if (shouldRunInitial || shouldRetryFailed || shouldRunNextStep) {
                prefetchAccessPolygonsForRouteCorridor(plannedRoute, trackResult.currentWalkKm, now)
            }
        }

        // 3. Trigger vibration if required by off-route logic (once on entering off-route, suppressed when detour muted)
        if (trackResult.shouldVibrate && !trackResult.isDetourMuted && !hasSentOffRouteAlert) {
            triggerOffTrailVibration()
        }

        // 3b. Evaluate next turn cue (cached per PlannedRoute instance) and alert at 150 m and 30 m
        val allCues = getOrComputeTurnCues(plannedRoute)
        val upcomingCue = allCues.firstOrNull { it.walkKm >= trackResult.currentWalkKm - 0.005 }
        val cueDistM = upcomingCue?.let {
            maxOf(0.0, (it.walkKm - trackResult.currentWalkKm) * 1000.0)
        }
        val activeNotifWalkKm = activeTurnCueNotificationWalkKm
        if (activeNotifWalkKm != null && (upcomingCue == null || upcomingCue.walkKm != activeNotifWalkKm)) {
            cancelTurnCueNotification()
        }
        if (upcomingCue != null && cueDistM != null && !trackResult.isOffRoute) {
            if (cueDistM in 30.0..150.0 && alerted150CueWalkKm != upcomingCue.walkKm) {
                alerted150CueWalkKm = upcomingCue.walkKm
                dispatchTurnCueAlert(upcomingCue, cueDistM)
            } else if (cueDistM < 30.0 && alerted30CueWalkKm != upcomingCue.walkKm) {
                alerted30CueWalkKm = upcomingCue.walkKm
                dispatchTurnCueAlert(upcomingCue, cueDistM)
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
            plannedRoute = plannedRoute,
            hasArrivedAtTonightCamp = trackResult.hasArrivedAtTonightCamp,
            autoAdvancedDayMessage = trackResult.autoAdvancedDayMessage
        )

        // Rain alert check (throttled inside the watcher: hourly, or after moving a few km)
        if (!trackResult.isCompleted) {
            rainWatcher.onFix(serviceScope, location.latitude, location.longitude, rainAlertEnabled, now)
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

        // Write the final progress before stopping the service
        // (withContext(NonCancellable) awaited before stopSelf).
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
            persistFinalProgressAndStopService(
                progress = completedProgress,
                onPersisted = { tracker.markPersisted(now) }
            )
            return
        }

        // 5. Off-route single alert on CHANNEL_ALERTS_ID & throttled ongoing notification on CHANNEL_NAVIGATION_ID
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (!trackResult.isOffRoute) {
            if (hasSentOffRouteAlert) {
                hasSentOffRouteAlert = false
                notifManager?.cancel(OFF_ROUTE_ALERT_NOTIFICATION_ID)
            }
        } else if (!trackResult.isDetourMuted && !hasSentOffRouteAlert) {
            hasSentOffRouteAlert = true
            offRouteAlertSentCount++
            val alertNotif = buildOffRouteAlertNotification(trackResult.offRouteDistanceM)
            notifManager?.notify(OFF_ROUTE_ALERT_NOTIFICATION_ID, alertNotif)
        }

        val stageName = trackResult.currentStage?.stopName
            ?: com.example.util.AppLocaleHelper.createLocalizedContext(this).getString(com.example.R.string.notif_nav_default_stage)
        maybePostOngoingNotification(
            trailName = plannedRoute.trailName,
            stageName = stageName,
            speedKmh = speedKmh,
            remainingTodayKm = trackResult.remainingTodayKm,
            remainingTripKm = trackResult.remainingTripKm,
            isOffRoute = trackResult.isOffRoute && !trackResult.isDetourMuted,
            offRouteMeters = trackResult.offRouteDistanceM,
            walkedTodayKm = trackResult.walkedTodayKm,
            isPaused = _liveState.value.isPaused,
            nowMs = now,
            force = false
        )

        // 5b. Update home-screen widget at most every 5 min or on day/camp/tracking state change
        maybeUpdateActiveTripWidget(
            dayNumber = trackResult.currentDay,
            campName = stageName,
            isTracking = !trackResult.isCompleted,
            nowMs = now,
            force = false
        )

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

        // Phase 10: Private forest sunset warning check (windowed, 60s throttled, single-flight, cache-only)
        maybeCheckPrivateForestSunsetWarning(location, now)
    }

    internal fun maybeUpdateActiveTripWidget(
        dayNumber: Int = _liveState.value.currentDay,
        campName: String = _liveState.value.currentStage?.stopName.orEmpty(),
        isTracking: Boolean = _liveState.value.isTracking,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ) {
        val stateChanged = dayNumber != lastWidgetDay ||
            campName != lastWidgetCampName ||
            isTracking != lastWidgetTracking
        val intervalElapsed = lastWidgetUpdateMs == 0L ||
            (nowMs - lastWidgetUpdateMs) >= com.example.widget.ActiveTripAppWidgetProvider.WIDGET_UPDATE_MIN_INTERVAL_MS
        if (force || stateChanged || intervalElapsed) {
            lastWidgetUpdateMs = nowMs
            lastWidgetDay = dayNumber
            lastWidgetCampName = campName
            lastWidgetTracking = isTracking
            try {
                com.example.widget.ActiveTripAppWidgetProvider.updateAllWidgets(
                    context = this,
                    liveState = _liveState.value,
                    nowMs = nowMs
                )
            } catch (_: Exception) {}
        }
    }

    internal fun triggerOffTrailVibration() {
        vibrateWaveform(VIBRATION_PATTERN_OFF_ROUTE)
    }

    internal fun triggerTurnCueDoubleVibration(
        direction: String = "STRAIGHT"
    ) {
        val pattern = vibrationPatternForTurnDirection(direction)
        lastTurnCueVibrationPattern = pattern
        serviceTurnCueVibrationCount++
        vibrateWaveform(pattern)
    }

    private fun vibrateWaveform(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("NavService", "Vibration failed: ${e.message}")
        }
    }

    internal fun canTurnCueChannelVibrate(channelId: String): Boolean {
        val hasPostNotifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!hasPostNotifPermission) return false
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                ?: return false
            var channel = notifManager.getNotificationChannel(channelId)
            if (channel == null) {
                runCatching { (applicationContext as? DanskStiApp)?.createNotificationChannels() }
                channel = notifManager.getNotificationChannel(channelId)
            }
            if (channel == null) return false
            if (channel.importance <= android.app.NotificationManager.IMPORTANCE_NONE) return false
            if (!channel.shouldVibrate()) return false
        }
        return true
    }

    internal fun dispatchTurnCueAlert(
        cue: com.example.data.model.TurnCue,
        distanceMeters: Double
    ) {
        if (voiceGuidanceEnabled) voiceGuide.speakCue(cue, distanceMeters)
        val channelId = channelIdForTurnDirection(cue.direction)
        val shouldUseWatchNotification = watchTurnCueNotificationsEnabled && canTurnCueChannelVibrate(channelId)
        if (shouldUseWatchNotification) {
            val notif = buildTurnCueNotification(cue, distanceMeters, channelId)
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notifManager?.notify(TURN_CUE_NOTIFICATION_ID, notif)
            activeTurnCueNotificationWalkKm = cue.walkKm
            lastTurnCueChannelId = channelId
            turnCueNotificationPostCount++
        } else {
            triggerTurnCueDoubleVibration(cue.direction)
        }
    }

    internal fun buildTurnCueNotification(
        cue: com.example.data.model.TurnCue,
        distanceMeters: Double,
        channelId: String = channelIdForTurnDirection(cue.direction)
    ): Notification {
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
        val launchIntent = buildLaunchNavigationIntent(planId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            20,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val distInt = distanceMeters.toInt().coerceAtLeast(1)
        val instruction = com.example.util.routing.TurnCueGenerator.localizedInstructionForDirection(
            cue.direction,
            localizedCtx,
            cue.instructionText
        )
        val title = localizedCtx.getString(com.example.R.string.turn_cue_in_meters_fmt, instruction, distInt)
        val trailTitle = currentPlannedRoute?.trailName?.takeIf { it.isNotBlank() }
            ?: localizedCtx.getString(com.example.R.string.app_name)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(trailTitle)
            .setSmallIcon(com.example.R.drawable.ic_notif_navigation)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
    }

    internal fun cancelTurnCueNotification() {
        activeTurnCueNotificationWalkKm = null
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifManager?.cancel(TURN_CUE_NOTIFICATION_ID)
    }

    internal fun setPausedInternal(paused: Boolean) {
        cancelActiveConfirmationRequests()
        resetMovementConfirmationBackoff()
        if (paused) {
            pausedAnchorLocation = lastAccurateLocation
            if (pausedAnchorLocation == null) {
                val tokenSource = CancellationTokenSource()
                activeConfirmTokenSource = tokenSource
                pauseAnchorFetchJob = serviceScope.launch(Dispatchers.Main.immediate) {
                    try {
                        val fix = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                            requestSingleHighAccuracyFix(tokenSource.token)
                        }
                        if (fix != null && fix.accuracy <= ACCURATE_FIX_MAX_M) {
                            lastAccurateLocation = fix
                            if (_liveState.value.isPaused && pausedAnchorLocation == null) {
                                pausedAnchorLocation = fix
                            }
                        }
                    } finally {
                        try {
                            tokenSource.cancel()
                        } catch (_: Exception) {}
                        if (activeConfirmTokenSource === tokenSource) {
                            activeConfirmTokenSource = null
                        }
                    }
                }
            }
        } else {
            pausedAnchorLocation = null
            isStationaryFor2Min = false
        }
        _liveState.value = _liveState.value.copy(isPaused = paused)
        refreshGpsPowerProfile()
        val route = currentPlannedRoute
        if (route != null) {
            val st = _liveState.value
            val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
            maybePostOngoingNotification(
                trailName = route.trailName,
                stageName = st.currentStage?.stopName ?: localizedCtx.getString(com.example.R.string.notif_nav_default_stage),
                speedKmh = st.speedKmh,
                remainingTodayKm = st.distanceRemainingTodayKm,
                remainingTripKm = st.distanceRemainingTripKm,
                isOffRoute = st.isOffTrailWarning,
                offRouteMeters = st.offTrailDistanceMeters,
                walkedTodayKm = st.distanceWalkedTodayKm,
                isPaused = paused,
                nowMs = System.currentTimeMillis(),
                force = true
            )
            maybeUpdateActiveTripWidget(force = true)
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
        hasSentOffRouteAlert = true
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifManager?.cancel(OFF_ROUTE_ALERT_NOTIFICATION_ID)
        _liveState.value = _liveState.value.copy(
            isOffTrailWarning = false,
            isDetourMuted = true,
            detourMuteUntilMs = muteUntil
        )
    }

    internal fun buildLaunchNavigationIntent(activePlanId: Long = planId): Intent {
        return buildLaunchNavigationIntent(this, activePlanId)
    }

    internal fun maybePostOngoingNotification(
        trailName: String,
        stageName: String,
        speedKmh: Double,
        remainingTodayKm: Double?,
        remainingTripKm: Double,
        isOffRoute: Boolean,
        offRouteMeters: Double,
        walkedTodayKm: Double = 0.0,
        isPaused: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ) {
        val elapsedSinceLastMs = if (lastOngoingNotificationMs == 0L) Long.MAX_VALUE else (nowMs - lastOngoingNotificationMs)
        val clockReferenceMs = if (elapsedSinceLastMs >= ONGOING_NOTIFICATION_MIN_INTERVAL_MS || lastOngoingNotificationMs == 0L) {
            nowMs
        } else {
            lastOngoingNotificationMs
        }
        val (title, content) = formatOngoingNotificationText(
            trailName = trailName,
            stageName = stageName,
            speedKmh = speedKmh,
            remainingTodayKm = remainingTodayKm,
            isPaused = isPaused,
            nowMs = clockReferenceMs
        )
        val textChanged = title != lastOngoingNotificationTitle ||
            content != lastOngoingNotificationContent ||
            isPaused != lastOngoingNotificationPaused
        val intervalElapsed = elapsedSinceLastMs >= ONGOING_NOTIFICATION_MIN_INTERVAL_MS

        if (force || textChanged || intervalElapsed) {
            lastOngoingNotificationMs = nowMs
            lastOngoingNotificationTitle = title
            lastOngoingNotificationContent = content
            lastOngoingNotificationPaused = isPaused
            ongoingNotificationPostCount++
            val notif = buildNotification(
                trailName = trailName,
                stageName = stageName,
                speedKmh = speedKmh,
                remainingTodayKm = remainingTodayKm,
                remainingTripKm = remainingTripKm,
                isOffRoute = isOffRoute,
                offRouteMeters = offRouteMeters,
                walkedTodayKm = walkedTodayKm,
                isPaused = isPaused,
                nowMs = nowMs
            )
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            notifManager?.notify(NOTIFICATION_ID, notif)
        }
    }

    internal fun formatOngoingNotificationText(
        trailName: String,
        stageName: String,
        speedKmh: Double,
        remainingTodayKm: Double?,
        isPaused: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<String, String> {
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
        val title = localizedCtx.getString(com.example.R.string.notif_nav_hiking_title, trailName)
        val baseContent = if (remainingTodayKm != null) {
            val configuredPace = com.example.data.local.SettingsRepository.cachedMitTempoKmh.takeIf { it > 0.0 } ?: userPaceKmh
            val arrivalClock = com.example.util.SunCalculator.computeArrivalClock(
                remainingTodayKm = remainingTodayKm,
                speedKmh = speedKmh,
                configuredPaceKmh = configuredPace,
                nowMillis = nowMs
            )
            val etaString = localizedCtx.getString(com.example.R.string.notif_nav_eta_prefix, "kl. $arrivalClock")
            localizedCtx.getString(com.example.R.string.notif_nav_next_camp_with_dist, stageName, remainingTodayKm, etaString)
        } else {
            localizedCtx.getString(com.example.R.string.notif_nav_next_camp_only, stageName)
        }
        val pausedPrefix = localizedCtx.getString(com.example.R.string.notif_nav_paused_prefix)
        val content = if (isPaused) "$pausedPrefix • $baseContent" else baseContent
        return title to content
    }

    internal fun buildOffRouteAlertNotification(offRouteMeters: Double): Notification {
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
        val launchIntent = buildLaunchNavigationIntent(planId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            10,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detourIntent = Intent(this, NavigationTrackingService::class.java).apply {
            action = ACTION_TAKE_DETOUR
        }
        val detourPending = PendingIntent.getService(
            this,
            11,
            detourIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = localizedCtx.getString(com.example.R.string.notif_nav_off_route_title, offRouteMeters.toInt())
        val text = localizedCtx.getString(com.example.R.string.notif_nav_off_route_body)
        return NotificationCompat.Builder(this, DanskStiApp.CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(com.example.R.drawable.ic_notif_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                com.example.R.drawable.ic_notif_detour,
                localizedCtx.getString(com.example.R.string.nav_taking_detour),
                detourPending
            )
            .build()
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
        isPaused: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Notification {
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
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

        val (title, content) = formatOngoingNotificationText(
            trailName = trailName,
            stageName = stageName,
            speedKmh = speedKmh,
            remainingTodayKm = remainingTodayKm,
            isPaused = isPaused,
            nowMs = nowMs
        )

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

        // Always post ongoing notification on low-importance navigation channel with setOnlyAlertOnce(true)
        val channelId = DanskStiApp.CHANNEL_NAVIGATION_ID
        val pauseActionLabel = localizedCtx.getString(
            if (isPaused) com.example.R.string.notif_nav_action_resume else com.example.R.string.notif_nav_action_pause
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(com.example.R.drawable.ic_notif_navigation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (isPaused) com.example.R.drawable.ic_notif_play else com.example.R.drawable.ic_notif_pause,
                pauseActionLabel,
                pausePending
            )
            .addAction(
                com.example.R.drawable.ic_notif_stop,
                localizedCtx.getString(com.example.R.string.notif_nav_action_stop_today),
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

    @Volatile
    internal var lastPersistProgressJob: kotlinx.coroutines.Job? = null
        private set

    private fun persistFinalProgressAndStopService(
        progress: TripProgressEntity,
        onPersisted: (() -> Unit)? = null
    ) {
        lastPersistProgressJob = serviceScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try {
                    DanskStiApp.instance.database.dao().saveTripProgress(progress)
                    onPersisted?.invoke()
                } catch (e: Exception) {
                    Log.w("NavService", "Failed to save final trip progress: ${e.message}")
                }
            }
            stopTracking()
        }
    }

    private fun stopForToday() {
        val tracker = routeTracker
        if (tracker != null && planId != 0L) {
            val progress = tracker.stopForToday()
            val nextStage = tracker.plannedRoute.stages.getOrNull(progress.currentDay - 1)
            _liveState.value = _liveState.value.copy(
                isTracking = false,
                isPaused = true,
                currentDay = progress.currentDay,
                currentStage = nextStage,
                distanceWalkedTodayKm = 0.0,
                distanceRemainingTodayKm = progress.remainingDistanceTodayKm,
                hasArrivedAtTonightCamp = false,
                autoAdvancedDayMessage = null,
                showContinueDayPrompt = true
            )
            persistFinalProgressAndStopService(progress = progress)
        } else {
            stopTracking()
        }
    }

    internal fun stopTracking() {
        cancelActiveConfirmationRequests()
        corridorPrefetchJob?.cancel()
        corridorPrefetchJob = null
        sunsetCheckJob?.cancel()
        sunsetCheckJob = null
        cancelTurnCueNotification()
        voiceGuide.shutdown()
        voiceGuidanceEnabled = false
        unregisterCorridorNetworkCallbackIfNeeded()
        unregisterScreenReceiverIfNeeded()
        try {
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        } catch (_: Exception) {}
        _liveState.value = _liveState.value.copy(isTracking = false)
        maybeUpdateActiveTripWidget(isTracking = false, force = true)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private var lastSunsetWarningNotifiedMs: Long = 0L
    internal var cachedSunsetDayKey: Int = -1
    internal var cachedSunsetLat: Double = Double.NaN
    internal var cachedSunsetLng: Double = Double.NaN
    internal var cachedSunsetStr: String = ""
    internal var cachedSunsetMins: Int = 0
    internal var sunsetCalcCount: Int = 0
    internal var lastSunsetPolygonCheckMs: Long = 0L
    internal var sunsetPolygonQueryCount: Int = 0
    @Volatile
    internal var sunsetCheckJob: Job? = null

    internal fun maybeCheckPrivateForestSunsetWarning(location: Location, nowMs: Long) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayKey = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        val currentMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

        val needSunsetRecalc = dayKey != cachedSunsetDayKey ||
            cachedSunsetLat.isNaN() ||
            com.example.util.GeoUtils.haversineMeters(
                location.latitude,
                location.longitude,
                cachedSunsetLat,
                cachedSunsetLng
            ) >= SUNSET_CACHE_DISTANCE_M

        if (needSunsetRecalc) {
            cachedSunsetDayKey = dayKey
            cachedSunsetLat = location.latitude
            cachedSunsetLng = location.longitude
            cachedSunsetStr = com.example.util.SunCalculator.calculateSunset(
                lat = location.latitude,
                lng = location.longitude,
                epochMillis = nowMs
            )
            cachedSunsetMins = com.example.util.access.AccessGeometryUtils.parseTimeToMinutes(cachedSunsetStr)
            sunsetCalcCount++
        }

        // Only run the polygon check when now is between sunset - 45 min and sunset + 60 min
        val diffMins = cachedSunsetMins - currentMins
        if (diffMins !in -60..45) {
            if (_liveState.value.privateForestSunsetWarning != null) {
                _liveState.value = _liveState.value.copy(privateForestSunsetWarning = null)
            }
            return
        }

        // Inside the sunset window: single-flight and at most once per 60 s
        if (sunsetCheckJob?.isActive == true) return
        if (lastSunsetPolygonCheckMs != 0L && (nowMs - lastSunsetPolygonCheckMs) < SUNSET_CHECK_INTERVAL_MS) {
            return
        }

        lastSunsetPolygonCheckMs = nowMs
        val sunsetStrSnapshot = cachedSunsetStr
        sunsetCheckJob = serviceScope.launch(Dispatchers.IO) {
            checkPrivateForestSunsetWarning(location, sunsetStrSnapshot, nowMs)
        }
    }

    internal suspend fun checkPrivateForestSunsetWarning(
        location: Location,
        sunsetStr: String = cachedSunsetStr,
        nowMs: Long = if (location.time > 0L) location.time else System.currentTimeMillis()
    ) {
        try {
            val pt = com.example.util.routing.LatLng(location.latitude, location.longitude)
            val bboxDelta = 0.02
            sunsetPolygonQueryCount++
            // Cache-only read: never fetches from WFS or Overpass
            val polygons = DanskStiApp.instance.repository.getCachedAccessPolygons(
                pt.lat - bboxDelta, pt.lat + bboxDelta,
                pt.lng - bboxDelta, pt.lng + bboxDelta
            )
            val containing = com.example.util.access.AccessGeometryUtils.findContainingPolygon(pt, polygons)
            if (containing != null && containing.type == com.example.data.model.access.AccessLandType.PRIVATE_FOREST) {
                val effectiveSunsetStr = sunsetStr.ifBlank {
                    com.example.util.SunCalculator.calculateSunset(lat = pt.lat, lng = pt.lng, epochMillis = nowMs)
                }
                val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
                val warningText = localizedCtx.getString(com.example.R.string.notif_private_forest_closing_short, effectiveSunsetStr)
                if (_liveState.value.privateForestSunsetWarning != warningText) {
                    _liveState.value = _liveState.value.copy(privateForestSunsetWarning = warningText)
                }

                if (nowMs - lastSunsetWarningNotifiedMs > 30 * 60 * 1000L) {
                    lastSunsetWarningNotifiedMs = nowMs
                    showSunsetWarningNotification(effectiveSunsetStr)
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
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(this)
        val launchIntent = buildLaunchNavigationIntent(planId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            99,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shortText = localizedCtx.getString(com.example.R.string.notif_private_forest_closing_short, sunsetTime)
        val longText = localizedCtx.getString(com.example.R.string.notif_private_forest_closing_long, sunsetTime)
        val notif = NotificationCompat.Builder(this, DanskStiApp.CHANNEL_ALERTS_ID)
            .setContentTitle(localizedCtx.getString(com.example.R.string.notif_private_forest_title))
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
            .setSmallIcon(com.example.R.drawable.ic_notif_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notifManager.notify(SUNSET_WARNING_NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceGuide.shutdown()
        cancelActiveConfirmationRequests()
        unregisterCorridorNetworkCallbackIfNeeded()
        unregisterScreenReceiverIfNeeded()
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
        const val OFF_ROUTE_ALERT_NOTIFICATION_ID = 4042
        const val TURN_CUE_NOTIFICATION_ID = 4043

        // Part B4 distinct vibration patterns
        val VIBRATION_PATTERN_LEFT: LongArray = longArrayOf(0, 150, 120, 150)
        val VIBRATION_PATTERN_RIGHT: LongArray = longArrayOf(0, 150, 120, 150, 120, 150)
        val VIBRATION_PATTERN_OTHER: LongArray = longArrayOf(0, 400)
        val VIBRATION_PATTERN_OFF_ROUTE: LongArray = longArrayOf(0, 400, 150, 400, 150, 400)

        fun vibrationPatternForTurnDirection(direction: String): LongArray {
            return when (direction.trim().uppercase(java.util.Locale.ROOT)) {
                "LEFT", "SLIGHT_LEFT", "SHARP_LEFT" -> VIBRATION_PATTERN_LEFT
                "RIGHT", "SLIGHT_RIGHT", "SHARP_RIGHT" -> VIBRATION_PATTERN_RIGHT
                else -> VIBRATION_PATTERN_OTHER
            }
        }

        fun channelIdForTurnDirection(direction: String): String {
            return when (direction.trim().uppercase(java.util.Locale.ROOT)) {
                "LEFT", "SLIGHT_LEFT", "SHARP_LEFT" -> DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID
                "RIGHT", "SLIGHT_RIGHT", "SHARP_RIGHT" -> DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID
                else -> DanskStiApp.CHANNEL_TURN_CUE_OTHER_ID
            }
        }

        const val ONGOING_NOTIFICATION_MIN_INTERVAL_MS = 10_000L
        const val ACCURATE_FIX_MAX_M = 30.0f
        const val MOVEMENT_THRESHOLD_M = 20.0
        const val STATIONARY_DURATION_MS = 120_000L
        const val MIN_ACCURATE_FIXES_FOR_STATIONARY = 3
        const val INITIAL_CONFIRM_COOLDOWN_MS = 60_000L
        const val STATIONARY_CONFIRM_MAX_BACKOFF_MS = 120_000L
        const val PAUSED_CONFIRM_MAX_BACKOFF_MS = 300_000L
        const val CONFIRMATION_TIMEOUT_MS = 20_000L
        const val SUNSET_CHECK_INTERVAL_MS = 60_000L
        const val SUNSET_CACHE_DISTANCE_M = 10_000.0
        const val CORRIDOR_PREFETCH_WINDOW_KM = 25.0
        const val CORRIDOR_PREFETCH_STEP_KM = 20.0
        const val CORRIDOR_PREFETCH_MAX_TILES = 10
        const val CORRIDOR_PREFETCH_RETRY_INTERVAL_MS = 10 * 60_000L
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
         * Determines the active GPS power profile:
         * - Paused: low power every 60 s (`LOW_POWER_60S`).
         * - Battery-saver mode or stationary (`!isMoving`): balanced accuracy every 10 s (`BALANCED_10S`).
         * - Moving + navigating with the screen off: high accuracy every 5 s (`HIGH_ACCURACY_5S`).
         * - Moving + navigating with the screen on: high accuracy every 2 s (`HIGH_ACCURACY_2S`).
         */
        fun resolveGpsPowerProfile(
            isPaused: Boolean,
            isBatterySaverMode: Boolean,
            isScreenInteractive: Boolean,
            isMoving: Boolean
        ): GpsPowerProfile {
            return when {
                isPaused -> GpsPowerProfile.LOW_POWER_60S
                isBatterySaverMode || !isMoving -> GpsPowerProfile.BALANCED_10S
                !isScreenInteractive -> GpsPowerProfile.HIGH_ACCURACY_5S
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
