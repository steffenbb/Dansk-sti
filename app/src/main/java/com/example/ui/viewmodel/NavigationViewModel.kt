package com.example.ui.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TripDiaryEntryEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNoteEntity
import com.example.data.remote.TrailWeatherInfo
import com.example.data.remote.WeatherInterpreter
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.AppLocaleHelper
import com.example.util.GeoUtils
import com.example.util.SunCalculator
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val app: DanskStiApp
        get() = try { DanskStiApp.instance } catch (_: Exception) { getApplication() as DanskStiApp }
    internal val repository: DanskStiRepository
        get() = app.repository
    private val plannedRouteRepo
        get() = PlannedRouteRepository(app)

    val liveState: StateFlow<NavigationLiveState> = NavigationTrackingService.liveState

    private val _plannedRoute = MutableStateFlow<PlannedRoute?>(null)
    val plannedRoute: StateFlow<PlannedRoute?> = _plannedRoute.asStateFlow()

    private val _activePlan = MutableStateFlow<TripPlanEntity?>(null)
    val activePlan: StateFlow<TripPlanEntity?> = _activePlan.asStateFlow()

    private val _activeStages = MutableStateFlow<List<TripStage>>(emptyList())
    val activeStages: StateFlow<List<TripStage>> = _activeStages.asStateFlow()

    private val _currentDay = MutableStateFlow(1)
    val currentDay: StateFlow<Int> = _currentDay.asStateFlow()

    private val _distanceRemainingToday = MutableStateFlow(0.0)
    val distanceRemainingToday: StateFlow<Double> = _distanceRemainingToday.asStateFlow()

    private val _todayStageText = MutableStateFlow(application.getString(R.string.nav_no_active_trip))
    val todayStageText: StateFlow<String> = _todayStageText.asStateFlow()

    private val _nextOvernight = MutableStateFlow<NextStopInfo?>(null)
    val nextOvernight: StateFlow<NextStopInfo?> = _nextOvernight.asStateFlow()

    private val _nextWater = MutableStateFlow<NextStopInfo?>(null)
    val nextWater: StateFlow<NextStopInfo?> = _nextWater.asStateFlow()

    private val _nextToilet = MutableStateFlow<NextStopInfo?>(null)
    val nextToilet: StateFlow<NextStopInfo?> = _nextToilet.asStateFlow()

    private val _nextShop = MutableStateFlow<NextStopInfo?>(null)
    val nextShop: StateFlow<NextStopInfo?> = _nextShop.asStateFlow()

    private val _aheadItems = MutableStateFlow<List<AheadOnRouteItem>>(emptyList())
    val aheadItems: StateFlow<List<AheadOnRouteItem>> = _aheadItems.asStateFlow()

    private val _dayProgressStops = MutableStateFlow<List<DayProgressStopIcon>>(emptyList())
    val dayProgressStops: StateFlow<List<DayProgressStopIcon>> = _dayProgressStops.asStateFlow()

    private val _routeShelters = MutableStateFlow<List<ShelterEntity>>(emptyList())
    val routeShelters: StateFlow<List<ShelterEntity>> = _routeShelters.asStateFlow()

    private val _routeFriTeltning = MutableStateFlow<List<Pair<FriTeltningEntity, List<GeoPointDto>>>>(emptyList())
    val routeFriTeltning: StateFlow<List<Pair<FriTeltningEntity, List<GeoPointDto>>>> = _routeFriTeltning.asStateFlow()

    private val _routeWaters = MutableStateFlow<List<PoiEntity>>(emptyList())
    val routeWaters: StateFlow<List<PoiEntity>> = _routeWaters.asStateFlow()

    private val _routeToilets = MutableStateFlow<List<PoiEntity>>(emptyList())
    val routeToilets: StateFlow<List<PoiEntity>> = _routeToilets.asStateFlow()

    private val _routeShops = MutableStateFlow<List<PoiEntity>>(emptyList())
    val routeShops: StateFlow<List<PoiEntity>> = _routeShops.asStateFlow()

    // Phases C8/D5/D7: highlights/cafés ahead, nearest AEDs, military areas, wind relation
    private val routeExtras = NavigationRouteExtras(application)
    val militaryAreaBanner: StateFlow<String?> = routeExtras.militaryAreaBanner
    fun dismissMilitaryAreaBanner() = routeExtras.dismissMilitaryAreaBanner()
    val navWindRelation: StateFlow<com.example.util.WindRelation> = routeExtras.navWindRelation
    val nearestAeds: StateFlow<List<NearbyAed>> = routeExtras.nearestAeds

    private val _routeHazards = MutableStateFlow<List<UserHazardNoteEntity>>(emptyList())
    val routeHazards: StateFlow<List<UserHazardNoteEntity>> = _routeHazards.asStateFlow()

    private val _routePathWarnings = MutableStateFlow<List<com.example.util.PathFeatureWarning>>(emptyList())
    val routePathWarnings: StateFlow<List<com.example.util.PathFeatureWarning>> = _routePathWarnings.asStateFlow()

    // Error state and retry support for corridor POIs
    private val _corridorPoisErrorMessage = MutableStateFlow<String?>(null)
    val corridorPoisErrorMessage: StateFlow<String?> = _corridorPoisErrorMessage.asStateFlow()

    private var lastCorridorPoisRoute: PlannedRoute? = null
    var corridorPoiLoaderOverride: (suspend (PlannedRoute) -> Unit)? = null

    private val _needsLocationPermission = MutableStateFlow(false)
    val needsLocationPermission: StateFlow<Boolean> = _needsLocationPermission.asStateFlow()

    private val _needsNotificationPermission = MutableStateFlow(false)
    val needsNotificationPermission: StateFlow<Boolean> = _needsNotificationPermission.asStateFlow()

    private var pendingStartPlanId: Long? = null
    private var notificationPermissionPromptedThisSession: Boolean = false

    private val _isBatterySaverMode = MutableStateFlow(false)
    val isBatterySaverMode: StateFlow<Boolean> = _isBatterySaverMode.asStateFlow()

    private val _showSosSheet = MutableStateFlow(false)
    val showSosSheet: StateFlow<Boolean> = _showSosSheet.asStateFlow()

    private val _emergencyInfo = MutableStateFlow<DanishEmergencyInfo?>(null)
    val emergencyInfo: StateFlow<DanishEmergencyInfo?> = _emergencyInfo.asStateFlow()

    private val _mapTileMode = MutableStateFlow("topo")
    val mapTileMode: StateFlow<String> = _mapTileMode.asStateFlow()

    private val _mapMode = MutableStateFlow(NavigationMapMode.FOLLOW_NORTH_UP)
    val mapMode: StateFlow<NavigationMapMode> = _mapMode.asStateFlow()
    private var previousFollowMode: NavigationMapMode = NavigationMapMode.FOLLOW_NORTH_UP
    private var overviewReturnJob: Job? = null

    private val _backToRoutePoints = MutableStateFlow<List<RoutePoint3D>>(emptyList())
    val backToRoutePoints: StateFlow<List<RoutePoint3D>> = _backToRoutePoints.asStateFlow()

    private val _isRoutingBackToRoute = MutableStateFlow(false)
    val isRoutingBackToRoute: StateFlow<Boolean> = _isRoutingBackToRoute.asStateFlow()

    private val _routeBackErrorMessage = MutableStateFlow<String?>(null)
    val routeBackErrorMessage: StateFlow<String?> = _routeBackErrorMessage.asStateFlow()

    var customBackToRouteRouter: (suspend (LatLng, LatLng) -> RouteLegResult?)? = null

    internal val _replaceTripPrompt = MutableStateFlow<ReplaceTripPrompt?>(null)
    val replaceTripPrompt: StateFlow<ReplaceTripPrompt?> = _replaceTripPrompt.asStateFlow()
    private val _deviceHeadingDegrees = MutableStateFlow<Float?>(null)
    val deviceHeadingDegrees: StateFlow<Float?> = _deviceHeadingDegrees.asStateFlow()

    /** Publishes the compass heading; changes under [HEADING_MIN_CHANGE_DEG] are ignored (see there). */
    fun updateDeviceHeading(headingDegrees: Float?) {
        val next = headingDegrees?.let { ((it % 360f) + 360f) % 360f }
        val current = _deviceHeadingDegrees.value
        if (next != null && current != null && headingDeltaDegrees(current, next) < HEADING_MIN_CHANGE_DEG) return
        _deviceHeadingDegrees.value = next
    }

    fun clearRouteBackErrorMessage() {
        _routeBackErrorMessage.value = null
    }

    fun clearCorridorPoisErrorMessage() {
        _corridorPoisErrorMessage.value = null
    }

    private val _sunsetTime = MutableStateFlow(SunCalculator.calculateSunset())
    val sunsetTime: StateFlow<String> = _sunsetTime.asStateFlow()

    val isTripActive: StateFlow<Boolean> = MutableStateFlow(false).combineWithLiveState()

    private val settingsRepository = (application as DanskStiApp).settingsRepository
    private val _mitTempoKmh = MutableStateFlow(com.example.data.local.SettingsRepository.cachedMitTempoKmh)
    val mitTempoKmh: StateFlow<Double> = _mitTempoKmh.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _voiceGuidance = MutableStateFlow(false)
    val voiceGuidance: StateFlow<Boolean> = _voiceGuidance.asStateFlow()


    // Part D2: Trip diary entries for active plan
    private val _diaryEntries = MutableStateFlow<List<TripDiaryEntryEntity>>(emptyList())
    val diaryEntries: StateFlow<List<TripDiaryEntryEntity>> = _diaryEntries.asStateFlow()
    private var observedDiaryPlanId: Long = 0L
    private var diaryObserverJob: Job? = null

    // Part E1: Navigation weather (refreshed at most once per 60 min or 5 km) & tonight's camp weather
    internal val _navigationWeather = MutableStateFlow<TrailWeatherInfo?>(null)
    val navigationWeather: StateFlow<TrailWeatherInfo?> = _navigationWeather.asStateFlow()

    internal val _tonightCampWeather = MutableStateFlow<TrailWeatherInfo?>(null)
    val tonightCampWeather: StateFlow<TrailWeatherInfo?> = _tonightCampWeather.asStateFlow()

    internal var lastWeatherFetchMs: Long = 0L
    internal var lastWeatherFetchLat: Double = Double.NaN
    internal var lastWeatherFetchLng: Double = Double.NaN
    internal var lastWeatherFailureMs: Long = 0L
    internal var lastPrefetchedCampDay: Int = -1
    internal var lastCampWeatherFailureMs: Long = 0L
    @Volatile
    private var weatherFetchJob: Job? = null
    @Volatile
    private var campWeatherPrefetchJob: Job? = null

    private fun MutableStateFlow<Boolean>.combineWithLiveState(): StateFlow<Boolean> {
        val flow = MutableStateFlow(false)
        viewModelScope.launch {
            NavigationTrackingService.liveState.collect { state ->
                flow.value = state.isTracking
            }
        }
        return flow.asStateFlow()
    }

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _mitTempoKmh.value = settings.mitTempoKmh
                _keepScreenOn.value = settings.keepScreenOn
                _voiceGuidance.value = settings.voiceGuidance
            }
        }
        viewModelScope.launch {
            NavigationTrackingService.liveState.collect { state ->
                val currentActivePlanId = _activePlan.value?.id
                if (currentActivePlanId != null && state.planId > 0L && state.planId != currentActivePlanId) {
                    return@collect
                }
                val effPlanId = if (state.planId > 0L) state.planId else (currentActivePlanId ?: 0L)
                if (effPlanId > 0L && effPlanId != observedDiaryPlanId) {
                    observedDiaryPlanId = effPlanId
                    observeDiaryEntriesForPlan(effPlanId)
                }

                if (state.plannedRoute != null && _plannedRoute.value == null) {
                    _plannedRoute.value = state.plannedRoute
                    loadRouteCorridorPois(state.plannedRoute)
                }
                if (state.currentDay > 0) {
                    _currentDay.value = state.currentDay
                }
                _distanceRemainingToday.value = state.distanceRemainingTodayKm

                if (!state.isOffTrailWarning && _backToRoutePoints.value.isNotEmpty()) {
                    _backToRoutePoints.value = emptyList()
                }

                if (state.isTracking) {
                    val days = _activePlan.value?.targetDays ?: _plannedRoute.value?.stages?.size ?: 1
                    _todayStageText.value = getApplication<Application>().getString(
                        R.string.nav_today_stage_progress_fmt,
                        state.currentDay,
                        days,
                        state.distanceRemainingTodayKm
                    )
                    state.currentStage?.let { stage ->
                        _nextOvernight.value = NextStopInfo(
                            name = stage.stopName,
                            distanceRemainingKm = state.distanceRemainingTodayKm,
                            type = stage.stopType
                        )
                    }
                }

                recalculateRouteStopsAndAhead(
                    route = _plannedRoute.value ?: state.plannedRoute,
                    currentWalkKm = state.totalDistanceWalkedKm,
                    currentDay = _currentDay.value,
                    currentStage = state.currentStage,
                    remainingTodayKm = state.distanceRemainingTodayKm
                )

                refreshNavigationSunset(state)
                maybePrefetchTonightCampWeather(state)
                maybeUpdateNavigationWeather(state)

                if (state.currentLat != null && state.currentLng != null && state.currentLat != 0.0 && state.isTracking) {
                    _emergencyInfo.value = repository.getEmergencyInfo(state.currentLat, state.currentLng)
                }
            }
        }
    }

    private fun observeDiaryEntriesForPlan(planId: Long) {
        diaryObserverJob?.cancel()
        diaryObserverJob = viewModelScope.launch {
            repository.getDiaryEntriesForPlanFlow(planId).collect { _diaryEntries.value = it }
        }
    }

    fun saveDiaryEntry(planId: Long, dayNumber: Int, note: String, photoUris: List<String>) {
        if (planId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) { repository.saveDiaryEntry(planId, dayNumber, note, photoUris) }
    }

    fun copyAndResizeDiaryPhoto(planId: Long, dayNumber: Int, sourceUri: Uri, onComplete: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val savedPath = com.example.ui.components.TripDiaryPhotoHelper.copyAndResizePhotoToInternalStorage(
                context = getApplication(), sourceUri = sourceUri, planId = planId, dayNumber = dayNumber
            )
            withContext(Dispatchers.Main) { onComplete(savedPath) }
        }
    }

    fun deleteDiaryPhotoFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) { com.example.ui.components.TripDiaryPhotoHelper.deleteDiaryPhotoFile(path) }
    }

    internal fun maybePrefetchTonightCampWeather(state: NavigationLiveState, nowMs: Long = System.currentTimeMillis()) {
        val stage = state.currentStage ?: _plannedRoute.value?.stages?.getOrNull(_currentDay.value - 1) ?: return
        if (stage.stopLat == 0.0 && stage.stopLng == 0.0) return
        val dayNum = if (state.currentDay > 0) state.currentDay else _currentDay.value
        val existing = _tonightCampWeather.value
        val existingIsFresh = existing != null && existing.hourly48h.isNotEmpty() &&
            existing.fetchedAtMillis > 0L && (nowMs - existing.fetchedAtMillis) <= WeatherInterpreter.CAMP_PREFETCH_MAX_AGE_MS
        if (dayNum == lastPrefetchedCampDay && existingIsFresh) return
        if (lastCampWeatherFailureMs > 0L && (nowMs - lastCampWeatherFailureMs) < WeatherInterpreter.NAV_RETRY_INTERVAL_MS) return
        if (campWeatherPrefetchJob?.isActive == true) return
        campWeatherPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            performTonightCampWeatherPrefetch(stage.stopLat, stage.stopLng, dayNum, nowMs)
        }
    }

    internal fun maybeUpdateNavigationWeather(state: NavigationLiveState, nowMs: Long = System.currentTimeMillis()) {
        val route = _plannedRoute.value ?: state.plannedRoute
        val lat = state.currentLat ?: state.currentSnappedPoint?.lat ?: route?.points?.firstOrNull()?.lat ?: return
        val lng = state.currentLng ?: state.currentSnappedPoint?.lng ?: route?.points?.firstOrNull()?.lng ?: return

        val shouldRefresh = WeatherInterpreter.shouldRefreshNavigationWeather(
            lastFetchTimeMs = lastWeatherFetchMs, lastFetchLat = lastWeatherFetchLat, lastFetchLng = lastWeatherFetchLng,
            currentLat = lat, currentLng = lng, nowMs = nowMs
        )
        if (!shouldRefresh && _navigationWeather.value != null) return
        if (shouldRefresh && lastWeatherFailureMs > 0L && (nowMs - lastWeatherFailureMs) < WeatherInterpreter.NAV_RETRY_INTERVAL_MS) return
        if (weatherFetchJob?.isActive == true) return

        weatherFetchJob = viewModelScope.launch(Dispatchers.IO) {
            performNavigationWeatherUpdate(lat, lng, shouldRefresh, state, nowMs)
        }
    }

    companion object {
        /** Used by NavigationRainWatcher in the tracking service. */
        const val CHANNEL_RAIN_ALERT_ID = "dansk_sti_rain_alert"
        const val RAIN_ALERT_NOTIFICATION_ID = 4049
    }

    fun refreshNavigationSunset(state: NavigationLiveState = liveState.value) {
        _sunsetTime.value = computeNavigationSunsetForState(
            liveState = state, currentDay = _currentDay.value, activePlan = _activePlan.value,
            activeStages = _activeStages.value, plannedRoute = _plannedRoute.value ?: state.plannedRoute
        )
    }

    fun setCurrentDayForNavigation(day: Int) {
        _currentDay.value = day.coerceAtLeast(1)
        refreshNavigationSunset()
    }

    fun setActivePlanAndStagesForNavigation(
        plan: TripPlanEntity?, stages: List<TripStage>, route: PlannedRoute? = _plannedRoute.value
    ) {
        _activePlan.value = plan
        _activeStages.value = stages
        if (route != null) _plannedRoute.value = route
        refreshNavigationSunset()
    }

    private fun recalculateRouteStopsAndAhead(
        route: PlannedRoute?, currentWalkKm: Double, currentDay: Int, currentStage: TripStage?, remainingTodayKm: Double
    ) {
        if (route == null || route.points.isEmpty()) return
        val result = computeRouteStopsAndAhead(
            route = route, currentWalkKm = currentWalkKm, currentDay = currentDay, currentStage = currentStage,
            remainingTodayKm = remainingTodayKm, waters = _routeWaters.value, shops = _routeShops.value,
            shelters = _routeShelters.value, previousNextOvernight = _nextOvernight.value,
            pathWarnings = _routePathWarnings.value,
            highlights = routeExtras.highlights.value, cafes = routeExtras.cafes.value,
            areaCrossings = routeExtras.areaCrossings.value
        )
        routeExtras.onProgress(route, currentWalkKm, liveState.value, _navigationWeather.value)
        _nextWater.value = result.nextWater
        _nextShop.value = result.nextShop
        _nextOvernight.value = result.nextOvernight
        _aheadItems.value = (result.aheadItems + routeExtras.tideAheadItems(route, currentWalkKm, _routePathWarnings.value)).sortedBy { it.kmRemaining }
        _dayProgressStops.value = result.dayProgressStops
    }

    private var corridorPoiObserverJob: Job? = null

    private fun applyRouteCorridorSheltersAndPois(
        route: PlannedRoute,
        shelters: List<ShelterEntity>,
        pois: List<PoiEntity>
    ) {
        _routeShelters.value = shelters
        _routeWaters.value = pois.filter { it.type.equals("water", ignoreCase = true) }
        _routeToilets.value = pois.filter { it.type.equals("toilet", ignoreCase = true) }
        _routeShops.value = pois.filter { it.type.equals("shop", ignoreCase = true) }
        routeExtras.applyCorridorPois(pois)

        val st = liveState.value
        recalculateRouteStopsAndAhead(
            route = _plannedRoute.value ?: route,
            currentWalkKm = st.totalDistanceWalkedKm,
            currentDay = _currentDay.value,
            currentStage = st.currentStage,
            remainingTodayKm = st.distanceRemainingTodayKm
        )
    }

    fun loadRouteCorridorPois(route: PlannedRoute) {
        lastCorridorPoisRoute = route
        _corridorPoisErrorMessage.value = null
        corridorPoiObserverJob?.cancel()
        corridorPoiObserverJob = viewModelScope.launch {
            try {
                val customLoader = corridorPoiLoaderOverride
                if (customLoader != null) {
                    withContext(Dispatchers.IO) {
                        customLoader(route)
                    }
                    refreshNavigationSunset()
                    return@launch
                }

                val trailId = route.trailId
                withContext(Dispatchers.IO) {
                    val geom = repository.getTrailGeometry(trailId)
                        ?: repository.geometryRepository.loadGeometry(trailId)
                    if (geom != null) {
                        val analysis = com.example.util.PathFeatureAnalyzer.analyze(
                            ranges = com.example.util.PathRangeBuilder.fromTrailSegments(geom.segments),
                            pointAtKm = { km ->
                                val pt = route.points.minByOrNull { kotlin.math.abs(it.walkKm - km) }
                                if (pt != null) {
                                    GeoPointDto(pt.lat, pt.lng, pt.ele)
                                } else {
                                    geom.segments.firstOrNull()?.points?.firstOrNull()
                                }
                            },
                            context = getApplication()
                        )
                        _routePathWarnings.value = analysis.warnings.filter { w ->
                            val (_, detourM) = route.projectPoint(w.lat, w.lng)
                            detourM <= 50.0
                        }
                    } else {
                        _routePathWarnings.value = emptyList()
                    }

                    routeExtras.loadAreaCrossings(route, repository)
                    runCatching { routeExtras.loadTides(route, repository) }

                    val data = loadInitialCorridorDataForRoute(repository, route)
                    applyRouteCorridorSheltersAndPois(route, data.initialShelters, data.initialPois)
                    if (data.needsTrailCorridorSync) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                repository.getTrailById(trailId)?.let { repository.syncCorridorPois(it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("NavigationViewModel", "Failed to sync corridor POIs for $trailId", e)
                                _corridorPoisErrorMessage.value =
                                    getApplication<Application>().getString(R.string.nav_corridor_pois_error)
                            }
                        }
                    }
                    _routeFriTeltning.value = data.friTeltningWithPolygons
                    _routeHazards.value = data.hazards
                }

                // Compute navigation sunset from current position or tonight's overnight point,
                // never from the route's midpoint.
                refreshNavigationSunset()

                combine(
                    repository.observeSheltersForTrail(trailId),
                    repository.observePoisForTrail(trailId)
                ) { shelters, pois -> shelters to pois }
                    .collect { (shelters, pois) -> applyRouteCorridorSheltersAndPois(route, shelters, pois) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("NavigationViewModel", "Failed to load route corridor POIs for ${route.trailId}", e)
                _corridorPoisErrorMessage.value =
                    getApplication<Application>().getString(R.string.nav_corridor_pois_error)
            }
        }
    }

    fun retryLoadRouteCorridorPois() {
        val route = lastCorridorPoisRoute ?: _plannedRoute.value ?: liveState.value.plannedRoute ?: return
        loadRouteCorridorPois(route)
    }

    fun initPlan(planId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetId = if (planId != null && planId > 0L) planId
            else plannedRouteRepo.getLastActivePlanId().takeIf { it > 0L } ?: return@launch
            replaceTripPromptFor(targetId, NavigationTrackingService.liveState.value)?.let { _replaceTripPrompt.value = it; return@launch }
            repository.getTripPlanById(targetId)?.let { _activePlan.value = it }
            val stages = repository.getStagesForPlan(targetId)
            if (stages.isNotEmpty()) _activeStages.value = stages
            plannedRouteRepo.loadPlannedRoute(targetId)?.let {
                _plannedRoute.value = it
                loadRouteCorridorPois(it)
            }
            refreshNavigationSunset()
            if (!NavigationTrackingService.liveState.value.isTracking) startTrip(targetId)
        }
    }

    fun hasLocationPermission(): Boolean = NavigationTrackingService.hasLocationPermission(getApplication())

    fun hasNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun startTrip(planId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val plan = repository.getTripPlanById(planId) ?: return@launch
            _activePlan.value = plan
            _activeStages.value = repository.getStagesForPlan(planId)
            plannedRouteRepo.loadPlannedRoute(planId)?.let {
                _plannedRoute.value = it
                loadRouteCorridorPois(it)
            }
            refreshNavigationSunset()
            plannedRouteRepo.saveLastActivePlanId(planId)
            pendingStartPlanId = planId

            if (!hasLocationPermission()) {
                _needsLocationPermission.value = true
                return@launch
            }
            _needsLocationPermission.value = false

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission() && !notificationPermissionPromptedThisSession
            ) {
                notificationPermissionPromptedThisSession = true
                _needsNotificationPermission.value = true
                return@launch
            }
            NavigationTrackingService.start(context = getApplication(), planId = planId)
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted && hasLocationPermission()) {
            _needsLocationPermission.value = false
            val targetId = pendingStartPlanId ?: _activePlan.value?.id ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission() && !notificationPermissionPromptedThisSession
            ) {
                notificationPermissionPromptedThisSession = true
                _needsNotificationPermission.value = true
            } else {
                NavigationTrackingService.start(context = getApplication(), planId = targetId)
            }
        } else {
            _needsLocationPermission.value = true
        }
    }

    fun onNotificationPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
        _needsNotificationPermission.value = false
        val targetId = pendingStartPlanId ?: _activePlan.value?.id ?: return
        if (hasLocationPermission()) {
            NavigationTrackingService.start(context = getApplication(), planId = targetId)
        }
    }

    fun stopTrip() {
        NavigationTrackingService.stop(getApplication())
        _activePlan.value = null
        _activeStages.value = emptyList()
        _plannedRoute.value = null
        _isBatterySaverMode.value = false
        plannedRouteRepo.clearLastActivePlanId()
    }

    fun stopForToday() = NavigationTrackingService.stopForToday(getApplication())
    fun togglePause() = NavigationTrackingService.setPaused(getApplication(), !liveState.value.isPaused)
    fun setPaused(paused: Boolean) = NavigationTrackingService.setPaused(getApplication(), paused)

    fun takeDetour(nowMs: Long = System.currentTimeMillis()) {
        NavigationTrackingService.takeDetour(getApplication(), nowMs)
        _backToRoutePoints.value = emptyList()
    }

    fun routeMeBack() {
        val state = liveState.value
        val curLat = state.currentLat ?: return
        val curLng = state.currentLng ?: return
        val route = _plannedRoute.value ?: state.plannedRoute
        val targetPt = state.nearestPointAhead
            ?: route?.points?.filter { it.walkKm >= state.totalDistanceWalkedKm }?.minByOrNull {
                GeoUtils.distanceMeters(curLat, curLng, it.lat, it.lng)
            }
            ?: route?.points?.firstOrNull()
            ?: return

        _routeBackErrorMessage.value = null
        _backToRoutePoints.value = listOf(
            RoutePoint3D(curLat, curLng),
            RoutePoint3D(targetPt.lat, targetPt.lng, targetPt.ele)
        )
        _isRoutingBackToRoute.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pts = computeBackToRouteLegPoints(
                    app = getApplication(),
                    from = LatLng(curLat, curLng),
                    to = LatLng(targetPt.lat, targetPt.lng),
                    customRouter = customBackToRouteRouter
                )
                if (pts != null) {
                    _backToRoutePoints.value = pts
                } else {
                    _backToRoutePoints.value = emptyList()
                    _routeBackErrorMessage.value = getApplication<Application>().getString(R.string.nav_route_back_error)
                }
            } catch (_: Exception) {
                _backToRoutePoints.value = emptyList()
                _routeBackErrorMessage.value = getApplication<Application>().getString(R.string.nav_route_back_error)
            } finally {
                _isRoutingBackToRoute.value = false
            }
        }
    }

    fun cycleMapMode() {
        val next = when (_mapMode.value) {
            NavigationMapMode.FOLLOW_NORTH_UP -> NavigationMapMode.FOLLOW_HEADING_UP
            NavigationMapMode.FOLLOW_HEADING_UP -> {
                previousFollowMode = NavigationMapMode.FOLLOW_HEADING_UP
                NavigationMapMode.OVERVIEW
            }
            NavigationMapMode.OVERVIEW -> NavigationMapMode.FOLLOW_NORTH_UP
        }
        setMapMode(next)
    }

    fun setMapMode(mode: NavigationMapMode) {
        overviewReturnJob?.cancel()
        if (mode != NavigationMapMode.OVERVIEW) previousFollowMode = mode
        _mapMode.value = mode
        if (mode == NavigationMapMode.OVERVIEW) {
            overviewReturnJob = viewModelScope.launch {
                delay(10_000L)
                if (_mapMode.value == NavigationMapMode.OVERVIEW) _mapMode.value = previousFollowMode
            }
        }
    }

    fun onMapTappedInOverview() {
        if (_mapMode.value == NavigationMapMode.OVERVIEW) {
            overviewReturnJob?.cancel()
            _mapMode.value = previousFollowMode
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun setVoiceGuidance(enabled: Boolean) {
        _voiceGuidance.value = enabled
        viewModelScope.launch { settingsRepository.setVoiceGuidance(enabled) }
    }

    fun dismissContinueDayPrompt() = NavigationTrackingService.dismissContinueDayPrompt()

    fun toggleBatterySaver() {
        val next = !_isBatterySaverMode.value
        _isBatterySaverMode.value = next
        NavigationTrackingService.setBatterySaverMode(next)
    }

    fun setBatterySaverMode(enabled: Boolean) {
        _isBatterySaverMode.value = enabled
        NavigationTrackingService.setBatterySaverMode(enabled)
    }

    fun setSosSheet(show: Boolean) {
        _showSosSheet.value = show
        if (show) viewModelScope.launch { routeExtras.refreshNearestAeds(liveState.value, repository) }
    }

    fun cycleMapTileMode() { _mapTileMode.value = if (_mapTileMode.value == "topo") "offline" else "topo" }

    private val _allRecordedDayStats = MutableStateFlow<List<com.example.data.model.RecordedDayStatEntity>>(emptyList())
    val allRecordedDayStats: StateFlow<List<com.example.data.model.RecordedDayStatEntity>> = _allRecordedDayStats.asStateFlow()

    private val _currentDayStat = MutableStateFlow<com.example.data.model.RecordedDayStatEntity?>(null)
    val currentDayStat: StateFlow<com.example.data.model.RecordedDayStatEntity?> = _currentDayStat.asStateFlow()
    private var dayStatsObserverJob: Job? = null

    init {
        viewModelScope.launch {
            liveState.collect { state ->
                val pId = state.planId
                val day = state.currentDay
                dayStatsObserverJob?.cancel()
                if (pId > 0L) {
                    dayStatsObserverJob = viewModelScope.launch {
                        repository.getAllRecordedDayStats(pId).collect { stats ->
                            _allRecordedDayStats.value = stats
                            _currentDayStat.value = stats.find { it.dayNumber == day }
                        }
                    }
                }
            }
        }
    }

    fun exportDayGpx(context: android.content.Context, dayNumber: Int) {
        viewModelScope.launch { exportDayGpxForPlan(context, repository, liveState.value, dayNumber) }
    }

    fun exportWholeTripGpx(context: android.content.Context) {
        viewModelScope.launch { exportWholeTripGpxForPlan(context, repository, liveState.value) }
    }

    fun shareCompletedTripWithDiary(
        context: android.content.Context,
        trailName: String,
        totalDistanceKm: Double,
        totalMovingSeconds: Long,
        totalAscentMeters: Int,
        totalDays: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val pId = if (liveState.value.planId > 0L) liveState.value.planId else (_activePlan.value?.id ?: 0L)
            shareCompletedTripWithDiaryHelper(
                context = context,
                repository = repository,
                planId = pId,
                fallbackEntries = _diaryEntries.value,
                trailName = trailName,
                totalDistanceKm = totalDistanceKm,
                totalMovingSeconds = totalMovingSeconds,
                totalAscentMeters = totalAscentMeters,
                totalDays = totalDays
            )
        }
    }

    fun shareCurrentLocation(context: android.content.Context) {
        shareCurrentNavigationLocation(context, liveState.value)
    }
}

