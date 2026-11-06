package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
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
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNoteEntity
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
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
    private val repository: DanskStiRepository
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

    private val _routeHazards = MutableStateFlow<List<UserHazardNoteEntity>>(emptyList())
    val routeHazards: StateFlow<List<UserHazardNoteEntity>> = _routeHazards.asStateFlow()

    // Round 11 Item 7: Error state and retry support for corridor POIs
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

    private val _deviceHeadingDegrees = MutableStateFlow<Float?>(null)
    val deviceHeadingDegrees: StateFlow<Float?> = _deviceHeadingDegrees.asStateFlow()

    fun updateDeviceHeading(headingDegrees: Float?) {
        _deviceHeadingDegrees.value = headingDegrees?.let { ((it % 360f) + 360f) % 360f }
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

                if (state.currentLat != null && state.currentLng != null && state.currentLat != 0.0 && state.isTracking) {
                    _emergencyInfo.value = repository.getEmergencyInfo(state.currentLat, state.currentLng)
                }
            }
        }
    }

    fun refreshNavigationSunset(state: NavigationLiveState = liveState.value) {
        _sunsetTime.value = computeNavigationSunsetForState(
            liveState = state,
            currentDay = _currentDay.value,
            activePlan = _activePlan.value,
            activeStages = _activeStages.value,
            plannedRoute = _plannedRoute.value ?: state.plannedRoute
        )
    }

    fun setCurrentDayForNavigation(day: Int) {
        _currentDay.value = day.coerceAtLeast(1)
        refreshNavigationSunset()
    }

    fun setActivePlanAndStagesForNavigation(
        plan: TripPlanEntity?,
        stages: List<TripStage>,
        route: PlannedRoute? = _plannedRoute.value
    ) {
        _activePlan.value = plan
        _activeStages.value = stages
        if (route != null) {
            _plannedRoute.value = route
        }
        refreshNavigationSunset()
    }

    private fun recalculateRouteStopsAndAhead(
        route: PlannedRoute?,
        currentWalkKm: Double,
        currentDay: Int,
        currentStage: TripStage?,
        remainingTodayKm: Double
    ) {
        if (route == null || route.points.isEmpty()) return
        val result = computeRouteStopsAndAhead(
            route = route,
            currentWalkKm = currentWalkKm,
            currentDay = currentDay,
            currentStage = currentStage,
            remainingTodayKm = remainingTodayKm,
            waters = _routeWaters.value,
            shops = _routeShops.value,
            shelters = _routeShelters.value,
            previousNextOvernight = _nextOvernight.value
        )
        _nextWater.value = result.nextWater
        _nextShop.value = result.nextShop
        _nextOvernight.value = result.nextOvernight
        _aheadItems.value = result.aheadItems
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
        corridorPoiObserverJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val customLoader = corridorPoiLoaderOverride
                if (customLoader != null) {
                    customLoader(route)
                    refreshNavigationSunset()
                    return@launch
                }

                val trailId = route.trailId
                val geoPoints = route.points.map { GeoPointDto(it.lat, it.lng, it.ele) }

                val initialShelters = repository.getSheltersForTrail(trailId)
                val initialPois = repository.getPoisForTrail(trailId)
                applyRouteCorridorSheltersAndPois(route, initialShelters, initialPois)

                if (initialShelters.isEmpty() && initialPois.isEmpty() && geoPoints.isNotEmpty()) {
                    if (trailId.startsWith("route_")) {
                        val routeId = trailId.removePrefix("route_")
                        if (repository.getActiveRoutePoiSyncJob(routeId)?.isActive != true) {
                            repository.enqueueRouteCorridorPoiSync(routeId, geoPoints)
                        }
                    } else {
                        launch {
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
                }

                val allFri = repository.getAllFriTeltningList()
                val corridorFri = if (geoPoints.isNotEmpty()) {
                    allFri.filter { ft ->
                        val (_, detourM) = GeoUtils.projectPointOnPolyline(
                            GeoPointDto(ft.centerLat, ft.centerLng),
                            geoPoints
                        )
                        detourM <= 3000.0
                    }
                } else emptyList()

                _routeFriTeltning.value = corridorFri.map { ft ->
                    ft to parseFriTeltningPolygonCoords(ft.polygonJson)
                }

                val hazards = repository.getHazardNotesForTrail(trailId).firstOrNull() ?: emptyList()
                _routeHazards.value = hazards

                // Round 11 Item 4: Compute navigation sunset from current position or tonight's overnight point,
                // never from the route's midpoint.
                refreshNavigationSunset()

                combine(
                    repository.observeSheltersForTrail(trailId),
                    repository.observePoisForTrail(trailId)
                ) { shelters, pois ->
                    shelters to pois
                }.collect { (shelters, pois) ->
                    applyRouteCorridorSheltersAndPois(route, shelters, pois)
                }
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val targetId = if (planId != null && planId > 0L) {
                    planId
                } else {
                    val last = plannedRouteRepo.getLastActivePlanId()
                    if (last > 0L) last else null
                } ?: return@withContext

                val plan = repository.getTripPlanById(targetId)
                if (plan != null) {
                    _activePlan.value = plan
                }
                val stages = repository.getStagesForPlan(targetId)
                if (stages.isNotEmpty()) {
                    _activeStages.value = stages
                }
                val route = plannedRouteRepo.loadPlannedRoute(targetId)
                if (route != null) {
                    _plannedRoute.value = route
                    loadRouteCorridorPois(route)
                }
                refreshNavigationSunset()

                if (!NavigationTrackingService.liveState.value.isTracking) {
                    startTrip(targetId)
                }
            }
        }
    }

    fun hasLocationPermission(): Boolean {
        return NavigationTrackingService.hasLocationPermission(getApplication())
    }

    fun hasNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun startTrip(planId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val plan = repository.getTripPlanById(planId) ?: return@withContext
                _activePlan.value = plan
                val stages = repository.getStagesForPlan(planId)
                _activeStages.value = stages
                val route = plannedRouteRepo.loadPlannedRoute(planId)
                if (route != null) {
                    _plannedRoute.value = route
                    loadRouteCorridorPois(route)
                }
                refreshNavigationSunset()

                plannedRouteRepo.saveLastActivePlanId(planId)
                pendingStartPlanId = planId

                if (!hasLocationPermission()) {
                    _needsLocationPermission.value = true
                    return@withContext
                }
                _needsLocationPermission.value = false

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    !hasNotificationPermission() && !notificationPermissionPromptedThisSession
                ) {
                    notificationPermissionPromptedThisSession = true
                    _needsNotificationPermission.value = true
                    return@withContext
                }

                NavigationTrackingService.start(
                    context = getApplication(),
                    planId = planId
                )
            }
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
                NavigationTrackingService.start(
                    context = getApplication(),
                    planId = targetId
                )
            }
        } else {
            _needsLocationPermission.value = true
        }
    }

    fun onNotificationPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
        _needsNotificationPermission.value = false
        val targetId = pendingStartPlanId ?: _activePlan.value?.id ?: return
        if (hasLocationPermission()) {
            NavigationTrackingService.start(
                context = getApplication(),
                planId = targetId
            )
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

    fun stopForToday() {
        NavigationTrackingService.stopForToday(getApplication())
    }

    fun togglePause() {
        val nextPaused = !liveState.value.isPaused
        NavigationTrackingService.setPaused(getApplication(), nextPaused)
    }

    fun setPaused(paused: Boolean) {
        NavigationTrackingService.setPaused(getApplication(), paused)
    }

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
                val from = LatLng(curLat, curLng)
                val to = LatLng(targetPt.lat, targetPt.lng)
                val errText = getApplication<Application>().getString(R.string.nav_route_back_error)
                val customRouter = customBackToRouteRouter
                if (customRouter != null) {
                    val customRes = customRouter(from, to)
                    if (customRes is RouteLegResult.Success && customRes.points.size >= 2) {
                        _backToRoutePoints.value = customRes.points
                    } else {
                        _backToRoutePoints.value = emptyList()
                        _routeBackErrorMessage.value = errText
                    }
                    return@launch
                }
                val offlineEngine = BRouterOfflineEngine(getApplication<Application>())
                val offlineRes = offlineEngine.routeLeg(from, to, RouteOptions())
                if (offlineRes is RouteLegResult.Success && offlineRes.points.size >= 2) {
                    _backToRoutePoints.value = offlineRes.points
                } else {
                    val onlineEngine = BRouterOnlineEngine(getApplication<Application>())
                    val onlineRes = onlineEngine.routeLeg(from, to, RouteOptions())
                    if (onlineRes is RouteLegResult.Success && onlineRes.points.size >= 2) {
                        _backToRoutePoints.value = onlineRes.points
                    } else {
                        _backToRoutePoints.value = emptyList()
                        _routeBackErrorMessage.value = errText
                    }
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
        if (mode != NavigationMapMode.OVERVIEW) {
            previousFollowMode = mode
        }
        _mapMode.value = mode
        if (mode == NavigationMapMode.OVERVIEW) {
            overviewReturnJob = viewModelScope.launch {
                delay(10_000L)
                if (_mapMode.value == NavigationMapMode.OVERVIEW) {
                    _mapMode.value = previousFollowMode
                }
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
        viewModelScope.launch {
            settingsRepository.setKeepScreenOn(enabled)
        }
    }

    fun setVoiceGuidance(enabled: Boolean) {
        _voiceGuidance.value = enabled
        viewModelScope.launch {
            settingsRepository.setVoiceGuidance(enabled)
        }
    }

    fun dismissContinueDayPrompt() {
        NavigationTrackingService.dismissContinueDayPrompt()
    }

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
    }

    fun cycleMapTileMode() {
        _mapTileMode.value = when (_mapTileMode.value) {
            "topo" -> "offline"
            else -> "topo"
        }
    }

    private val _allRecordedDayStats = MutableStateFlow<List<com.example.data.model.RecordedDayStatEntity>>(emptyList())
    val allRecordedDayStats: StateFlow<List<com.example.data.model.RecordedDayStatEntity>> = _allRecordedDayStats.asStateFlow()

    private val _currentDayStat = MutableStateFlow<com.example.data.model.RecordedDayStatEntity?>(null)
    val currentDayStat: StateFlow<com.example.data.model.RecordedDayStatEntity?> = _currentDayStat.asStateFlow()

    init {
        viewModelScope.launch {
            liveState.collect { state ->
                val pId = state.planId
                val day = state.currentDay
                if (pId > 0L) {
                    repository.getAllRecordedDayStats(pId).collect { stats ->
                        _allRecordedDayStats.value = stats
                        _currentDayStat.value = stats.find { it.dayNumber == day }
                    }
                }
            }
        }
    }

    fun exportDayGpx(context: android.content.Context, dayNumber: Int) {
        viewModelScope.launch {
            exportDayGpxForPlan(context, repository, liveState.value, dayNumber)
        }
    }

    fun exportWholeTripGpx(context: android.content.Context) {
        viewModelScope.launch {
            exportWholeTripGpxForPlan(context, repository, liveState.value)
        }
    }

    fun shareCurrentLocation(context: android.content.Context) {
        shareCurrentNavigationLocation(context, liveState.value)
    }
}

