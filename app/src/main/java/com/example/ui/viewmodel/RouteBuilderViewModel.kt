package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.OvernightType
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TripStageEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.remote.WeatherApiService
import com.example.data.repository.DanskStiRepository
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.HybridRoutingEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteOptions
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class RouteBuilderViewModel(
    application: Application
) : AndroidViewModel(application) {

    internal val TAG = "RouteBuilderVM"
    internal val app: DanskStiApp
        get() = try { DanskStiApp.instance } catch (_: Exception) { getApplication() as DanskStiApp }
    internal val repository: DanskStiRepository
        get() = app.repository
    internal val settingsRepository: SettingsRepository
        get() = com.example.DanskStiApp.getSettingsRepository(getApplication())
    internal val brouterStorageManager: BRouterStorageManager = BRouterStorageManager.getInstance(application)
    internal val onlineEngine: BRouterOnlineEngine = BRouterOnlineEngine(application)
    internal val offlineEngine: BRouterOfflineEngine = BRouterOfflineEngine(application)
    internal var routingEngine: RoutingEngine = HybridRoutingEngine(
        context = application,
        onlineEngine = onlineEngine,
        offlineEngine = offlineEngine,
        storageManager = brouterStorageManager
    )
    internal val weatherService: WeatherApiService = WeatherApiService.create()

    internal val _uiState = MutableStateFlow(RouteBuilderState())
    val uiState: StateFlow<RouteBuilderState> = _uiState.asStateFlow()

    internal val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    val downloadProgress: StateFlow<Map<String, com.example.data.model.TrailDownloadProgress>> =
        com.example.data.repository.TrailGeometryRepository.globalDownloadProgress

    // Undo / Redo stacks (max 50)
    internal data class HistorySnapshot(
        val waypoints: List<RouteWaypoint>,
        val legs: Map<String, LegGeometryData>,
        val options: RouteOptions,
        val isReverse: Boolean = false
    )
    internal val undoStack = mutableListOf<HistorySnapshot>()
    internal val redoStack = mutableListOf<HistorySnapshot>()
    internal val MAX_HISTORY = 50

    internal var mapCameraAccessJob: Job? = null
    internal var corridorAccessJob: Job? = null
    internal var supplyGapsJob: Job? = null
    internal var stagesAndPoisJob: Job? = null
    internal var draftAutosaveJob: Job? = null
    internal var gpxRoutingJob: Job? = null
    internal var trailGeometryDownloadJob: Job? = null
    internal var nightWeatherCache: MutableMap<String, String> = mutableMapOf()
    internal var pendingSaveCallback: ((Long) -> Unit)? = null
    internal var pendingSaveIsStarting: Boolean = false
    internal var lastComputedStagesSignature: String? = null

    internal enum class LastDeleteType {
        ROUTE_WAYPOINT,
        CUSTOM_WAYPOINT,
        WAYPOINT_PHOTO
    }
    internal var lastDeleteType: LastDeleteType = LastDeleteType.ROUTE_WAYPOINT
    internal var lastDeletedCustomWaypoint: UserCustomWaypointEntity? = null
    internal var lastDeletedWaypointPhotoEntity: UserCustomWaypointEntity? = null
    internal var lastDeletedWaypointPhotoBytes: ByteArray? = null

    internal var geometryFileName: String = "route_${UUID.randomUUID()}.json"
    internal var pacingSpeedKmh: Double = settingsRepository.currentSettingsSnapshot().mitTempoKmh
    internal var startTimeStr: String = "09:00"
    internal var sunsetTimeStr: String = com.example.util.SunCalculator.calculateSunset()
    internal val plannerSearchQueryFlow = MutableStateFlow("")

    var freshLocationTimeoutMs: Long = com.example.ui.map.LiveMapLocationState.CURRENT_LOCATION_TIMEOUT_MS
    var customLocationResolver: (suspend (timeoutMs: Long) -> com.example.ui.map.FreshLocationResolution)? = null

    suspend fun resolveCurrentOrFallbackLocation(
        fallbackLocation: android.location.Location? = null
    ): com.example.ui.map.FreshLocationResolution {
        val resolver = customLocationResolver
        if (resolver != null) {
            return resolver.invoke(freshLocationTimeoutMs)
        }
        return com.example.ui.map.LiveMapLocationState.resolveFreshOrFallbackLocation(
            context = getApplication(),
            timeoutMs = freshLocationTimeoutMs,
            fallbackCandidate = fallbackLocation
        )
    }

    fun enablePickAnotherStart() {
        _uiState.update {
            it.copy(
                interactionMode = PlannerInteractionMode.TEGN,
                waitingForStartPointTap = true,
                staleStartLocationMessage = null,
                errorMessage = "Vælg startpunkt - tryk på kortet"
            )
        }
        viewModelScope.launch {
            _userMessage.emit("Vælg startpunkt - tryk på kortet")
        }
    }

    fun dismissStaleStartLocationMessage() {
        _uiState.update { it.copy(staleStartLocationMessage = null) }
    }

    fun startFromMyLocation() {
        viewModelScope.launch {
            val res = resolveCurrentOrFallbackLocation()
            val loc = res.location
            if (loc != null) {
                setStartAtGps(loc.latitude, loc.longitude, staleMessage = res.staleMessage)
            } else {
                enablePickAnotherStart()
            }
        }
    }

    internal fun computeStagesSignature(state: RouteBuilderState): String {
        val wpSig = state.waypoints.joinToString("|") {
            "${it.id}:${String.format(Locale.US, "%.5f,%.5f", it.lat, it.lng)}:${it.role}:${it.trailKm ?: "null"}:${it.isHelper}"
        }
        val legSig = state.waypoints.zipWithNext { a, b ->
            val key = "${a.id}_to_${b.id}"
            val leg = state.legs[key]
            "$key:${leg?.points?.size ?: 0}:${String.format(Locale.US, "%.3f", leg?.lengthKm ?: 0.0)}"
        }.joinToString("|")
        return "$wpSig#$legSig#${state.paceKmh}#${state.dailyTargetKm}#${state.maxDetourKm}#${state.overnightPreference}#${state.startTimeStr}#${state.startDateMillis}#${state.isReverse}"
    }

    init {
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            plannerSearchQueryFlow
                .debounce(400L)
                .collectLatest { query: String ->
                    val trimmed = query.trim()
                    if (trimmed.length >= 2) {
                        val grouped = repository.searchGroupedInDenmark(trimmed)
                        repository.searchPlacesInDenmark(trimmed, grouped)
                        if (_uiState.value.plannerSearchQuery.trim() == trimmed) {
                            _uiState.update { it.copy(plannerSearchResults = grouped) }
                        }
                    } else {
                        _uiState.update { it.copy(plannerSearchResults = com.example.data.remote.GroupedSearchResults()) }
                    }
                }
        }
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            pacingSpeedKmh = settings.mitTempoKmh
            startTimeStr = settings.startTime
            _uiState.update {
                it.copy(
                    paceKmh = settings.mitTempoKmh,
                    dailyTargetKm = settings.dailyTargetKm,
                    startTimeStr = settings.startTime,
                    maxDetourKm = settings.maxDetourKm,
                    showCoachMarks = !settings.plannerCoachMarksSeen
                )
            }
        }
        checkForExistingDraft()
    }

    fun nextCoachMarkStep() {
        val current = _uiState.value.coachMarkStep
        if (current < 2) {
            _uiState.update { it.copy(coachMarkStep = current + 1) }
        } else {
            dismissCoachMarks()
        }
    }

    fun dismissCoachMarks() {
        _uiState.update { it.copy(showCoachMarks = false) }
        SettingsRepository.cachedPlannerCoachMarksSeen = true
        viewModelScope.launch {
            settingsRepository.setPlannerCoachMarksSeen(true)
        }
    }

    fun showCoachMarksAgain() {
        _uiState.update { it.copy(showCoachMarks = true, coachMarkStep = 0) }
        SettingsRepository.cachedPlannerCoachMarksSeen = false
        viewModelScope.launch {
            settingsRepository.setPlannerCoachMarksSeen(false)
        }
    }

    fun initTrailMode(trailId: String) {
        initializeTrailMode(trailId)
    }

    fun getBaseTrailSegments(): List<TrailSegmentDto> = _uiState.value.baseTrailSegments

    /**
     * Initializes Ruteplanlægger for:
     * - TRAIL mode (`baseTrailId` / `trailId` is a downloaded trail ID, e.g. "trail_ohavsstien")
     * - Existing route / plan (`routeId` / `existingRouteId` > 0, `existingPlanId` > 0, or `trailId` starting with "route_")
     * - FREE mode (`trailId == null && existingRouteId == null`)
     */
    fun initialize(
        routeId: Long? = null,
        existingRouteId: Long? = null,
        destLat: Double? = null,
        initialDestLat: Double? = null,
        destLng: Double? = null,
        initialDestLng: Double? = null,
        destName: String? = null,
        initialDestName: String? = null,
        gpsLat: Double? = null,
        currentGpsLat: Double? = null,
        gpsLng: Double? = null,
        currentGpsLng: Double? = null,
        gpsFixTimeMs: Long? = null,
        staleLocationMessage: String? = null,
        baseTrailId: String? = null,
        trailId: String? = null,
        existingPlanId: Long? = null
    ) {
        checkForExistingDraft()
        val effTrailId = baseTrailId?.takeIf { it.isNotBlank() } ?: trailId?.takeIf { it.isNotBlank() }
        val routeIdFromTrail = effTrailId?.takeIf { it.startsWith("route_") }?.removePrefix("route_")?.toLongOrNull()
        val effectiveRouteId = routeId?.takeIf { it > 0 }
            ?: existingRouteId?.takeIf { it > 0 }
            ?: routeIdFromTrail

        val effDestLat = destLat ?: initialDestLat
        val effDestLng = destLng ?: initialDestLng
        val effDestName = destName ?: initialDestName
        val effGpsLat = gpsLat ?: currentGpsLat
        val effGpsLng = gpsLng ?: currentGpsLng
        val computedStaleMessage = staleLocationMessage ?: gpsFixTimeMs?.let {
            com.example.ui.map.LiveMapLocationState.formatOldFixMessage(it)
        }

        if (existingPlanId != null && existingPlanId > 0L) {
            viewModelScope.launch {
                val plan = repository.getTripPlanById(existingPlanId)
                if (plan != null) {
                    val linkedRouteId = plan.customRouteId ?: plan.trailId.removePrefix("route_").toLongOrNull()
                    if (linkedRouteId != null && linkedRouteId > 0L && repository.getCustomRoute(linkedRouteId) != null) {
                        loadExistingRoute(linkedRouteId, existingPlanId)
                    } else {
                        initializeTrailMode(plan.trailId, effGpsLat, effGpsLng, existingPlanId, computedStaleMessage)
                    }
                }
            }
            return
        }

        if (effectiveRouteId != null && effectiveRouteId > 0) {
            loadExistingRoute(effectiveRouteId)
            return
        }

        if (!effTrailId.isNullOrBlank()) {
            initializeTrailMode(effTrailId, effGpsLat, effGpsLng, null, computedStaleMessage)
            return
        }

        if (effGpsLat != null && effGpsLng != null) {
            _uiState.update {
                it.copy(
                    currentGpsPosition = LatLng(effGpsLat, effGpsLng),
                    staleStartLocationMessage = computedStaleMessage
                )
            }
        }

        if (effDestLat != null && effDestLng != null) {
            val destWp = RouteWaypoint(
                lat = effDestLat,
                lng = effDestLng,
                role = WaypointRole.END,
                name = effDestName ?: "Destination"
            )
            if (effGpsLat != null && effGpsLng != null) {
                val startWp = RouteWaypoint(
                    lat = effGpsLat,
                    lng = effGpsLng,
                    role = WaypointRole.START,
                    legModeToNext = LegMode.SNAP,
                    name = "Min position"
                )
                _uiState.update {
                    it.copy(
                        mode = PlannerMode.FREE,
                        routeName = effDestName?.let { n -> "Rute til $n" } ?: "Ny rute",
                        waypoints = listOf(startWp, destWp),
                        legs = emptyMap(),
                        failedLegs = emptyMap(),
                        waitingForStartPointTap = false,
                        staleStartLocationMessage = computedStaleMessage,
                        errorMessage = null
                    )
                }
                computeSingleLeg(startWp, destWp)
            } else if (customLocationResolver != null) {
                viewModelScope.launch {
                    val res = resolveCurrentOrFallbackLocation()
                    val loc = res.location
                    if (loc != null) {
                        val startWp = RouteWaypoint(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            role = WaypointRole.START,
                            legModeToNext = LegMode.SNAP,
                            name = "Min position"
                        )
                        _uiState.update {
                            it.copy(
                                mode = PlannerMode.FREE,
                                routeName = effDestName?.let { n -> "Rute til $n" } ?: "Ny rute",
                                waypoints = listOf(startWp, destWp),
                                legs = emptyMap(),
                                failedLegs = emptyMap(),
                                waitingForStartPointTap = false,
                                currentGpsPosition = LatLng(loc.latitude, loc.longitude),
                                staleStartLocationMessage = res.staleMessage,
                                errorMessage = null
                            )
                        }
                        computeSingleLeg(startWp, destWp)
                    } else {
                        _uiState.update {
                            it.copy(
                                mode = PlannerMode.FREE,
                                routeName = effDestName?.let { n -> "Rute til $n" } ?: "Ny rute",
                                waypoints = listOf(destWp),
                                legs = emptyMap(),
                                failedLegs = emptyMap(),
                                waitingForStartPointTap = true,
                                staleStartLocationMessage = null,
                                errorMessage = "Vælg startpunkt - tryk på kortet"
                            )
                        }
                        _userMessage.emit("Vælg startpunkt - tryk på kortet")
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        mode = PlannerMode.FREE,
                        routeName = effDestName?.let { n -> "Rute til $n" } ?: "Ny rute",
                        waypoints = listOf(destWp),
                        legs = emptyMap(),
                        failedLegs = emptyMap(),
                        waitingForStartPointTap = true,
                        staleStartLocationMessage = null,
                        errorMessage = "Vælg startpunkt - tryk på kortet"
                    )
                }
                viewModelScope.launch {
                    _userMessage.emit("Vælg startpunkt - tryk på kortet")
                }
            }
        } else if (effGpsLat == null && effGpsLng == null && customLocationResolver != null) {
            viewModelScope.launch {
                val res = resolveCurrentOrFallbackLocation()
                res.location?.let { loc ->
                    _uiState.update {
                        it.copy(
                            currentGpsPosition = LatLng(loc.latitude, loc.longitude),
                            staleStartLocationMessage = res.staleMessage
                        )
                    }
                }
            }
        }
    }

    fun retryDownloadTrailGeometry() {
        val tid = _uiState.value.baseTrailId ?: return
        initializeTrailMode(tid, specificPlanId = _uiState.value.existingPlanId)
    }

    internal fun initializeTrailMode(
        trailId: String,
        currentGpsLat: Double? = null,
        currentGpsLng: Double? = null,
        specificPlanId: Long? = null,
        staleLocationMessage: String? = null
    ) {
        val preseededTrail = com.example.data.local.PreseededData.getInitialTrails().find { it.id == trailId }
        _uiState.update {
            it.copy(
                mode = PlannerMode.TRAIL,
                interactionMode = PlannerInteractionMode.KIG,
                baseTrailId = trailId,
                baseTrail = preseededTrail ?: it.baseTrail,
                routeName = preseededTrail?.name ?: it.routeName,
                waitingForStartPointTap = false,
                trailGeometryDownloadFailed = false,
                errorMessage = null
            )
        }
        trailGeometryDownloadJob?.cancel()
        trailGeometryDownloadJob = viewModelScope.launch(Dispatchers.Default) {
            val isCached = repository.geometryRepository.isGeometryCached(trailId)
            if (!isCached) {
                _uiState.update { it.copy(isDownloadingTrailGeometry = true) }
            }
            val existingPlan = if (specificPlanId != null && specificPlanId > 0L) {
                repository.getTripPlanById(specificPlanId)
            } else {
                repository.getLatestTripPlanForTrail(trailId)
            }
            val linkedRouteId = existingPlan?.customRouteId
            if (linkedRouteId != null && linkedRouteId > 0L) {
                val existingRoute = repository.getCustomRoute(linkedRouteId)
                if (existingRoute != null) {
                    loadExistingRoute(linkedRouteId, existingPlan.id)
                    return@launch
                }
            }

            val trail = repository.getTrailById(trailId)
            if (trail == null) {
                _uiState.update {
                    it.copy(
                        isDownloadingTrailGeometry = false,
                        trailGeometryDownloadFailed = true,
                        errorMessage = "Ruten kunne ikke hentes"
                    )
                }
                return@launch
            }

            val segments = repository.getTrailPoints(trail)
            val totalKm = TrailSlicer.totalTrailKm(segments)
            val isReverse = existingPlan?.isReverse ?: false
            val kmMarkers = TrailSlicer.generateKmMarkers(segments, stepKm = 5.0, isReverse = isReverse)

            var startKm = existingPlan?.startKm ?: 0.0
            var endKm = existingPlan?.endKm?.takeIf { it > startKm } ?: totalKm

            var effectiveLat = currentGpsLat
            var effectiveLng = currentGpsLng
            var effectiveStaleMsg = staleLocationMessage
            if (existingPlan == null && (effectiveLat == null || effectiveLng == null) && customLocationResolver != null) {
                val res = resolveCurrentOrFallbackLocation()
                effectiveLat = res.location?.latitude
                effectiveLng = res.location?.longitude
                effectiveStaleMsg = res.staleMessage
            }

            var usedGpsForStart = false
            if (existingPlan == null && effectiveLat != null && effectiveLng != null && segments.isNotEmpty()) {
                val gpsProj = TrailSlicer.projectOntoTrail(segments, effectiveLat, effectiveLng, isReverse)
                if (gpsProj != null && gpsProj.distanceMeters <= 2000.0) {
                    usedGpsForStart = true
                    if (gpsProj.trailKm < totalKm - 0.5) {
                        startKm = gpsProj.trailKm
                        endKm = totalKm
                    } else {
                        startKm = 0.0
                        endKm = gpsProj.trailKm
                    }
                }
            }

            val startProj = TrailSlicer.pointAtTrailKm(segments, startKm, isReverse)
            val endProj = TrailSlicer.pointAtTrailKm(segments, endKm, isReverse)

            if (segments.isEmpty() || startProj == null || endProj == null) {
                _uiState.update {
                    it.copy(
                        mode = PlannerMode.TRAIL,
                        existingPlanId = existingPlan?.id,
                        baseTrailId = trail.id,
                        baseTrail = trail,
                        baseTrailSegments = emptyList(),
                        baseTrailKmMarkers = emptyList(),
                        waypoints = emptyList(),
                        legs = emptyMap(),
                        isDownloadingTrailGeometry = false,
                        trailGeometryDownloadFailed = true,
                        errorMessage = "Ruten kunne ikke hentes"
                    )
                }
                return@launch
            }

            val startWp = RouteWaypoint(
                id = "trail_start_${trail.id}",
                lat = startProj.lat,
                lng = startProj.lng,
                role = WaypointRole.START,
                legModeToNext = LegMode.TRAIL,
                name = if (startKm > 0.1) String.format(Locale.GERMAN, "Start (km %.1f)", startKm) else "${trail.name} Start",
                trailKm = startKm
            )
            val endWp = RouteWaypoint(
                id = "trail_end_${trail.id}",
                lat = endProj.lat,
                lng = endProj.lng,
                role = WaypointRole.END,
                legModeToNext = LegMode.TRAIL,
                name = if (endKm < totalKm - 0.1) String.format(Locale.GERMAN, "Slut (km %.1f)", endKm) else "${trail.name} Slut",
                trailKm = endKm
            )

            val wps = mutableListOf<RouteWaypoint>()
            wps.add(startWp)
            if (existingPlan != null) {
                val existingStages = repository.getStagesForPlan(existingPlan.id)
                if (existingStages.size > 1) {
                    for (stage in existingStages.dropLast(1)) {
                        val stageKm = stage.endKm.coerceIn(startKm + 0.1, max(startKm + 0.1, endKm - 0.1))
                        val stagePt = TrailSlicer.pointAtTrailKm(segments, stageKm, isReverse)
                        if (stagePt != null) {
                            wps.add(
                                RouteWaypoint(
                                    lat = stagePt.lat,
                                    lng = stagePt.lng,
                                    role = WaypointRole.OVERNIGHT,
                                    legModeToNext = LegMode.TRAIL,
                                    name = stage.stopName.ifBlank { "Overnatning Dag ${stage.dayNumber}" },
                                    linkedPoiId = stage.spotId,
                                    trailKm = stageKm
                                )
                            )
                        }
                    }
                }
            }
            wps.add(endWp)

            val initialLegs = mutableMapOf<String, LegGeometryData>()
            for (i in 0 until wps.size - 1) {
                val from = wps[i]
                val to = wps[i + 1]
                val leg = TrailSlicer.buildTrailLegGeometry(
                    fromWaypointId = from.id,
                    toWaypointId = to.id,
                    segments = segments,
                    fromKm = from.trailKm ?: startKm,
                    toKm = to.trailKm ?: endKm,
                    isReverse = isReverse
                )
                initialLegs[leg.legKey] = leg
            }

            val parsedPref = existingPlan?.overnightType?.let {
                try { OvernightType.valueOf(it) } catch (_: Exception) { null }
            }

            updateRouteStats { current ->
                current.copy(
                    mode = PlannerMode.TRAIL,
                    interactionMode = PlannerInteractionMode.KIG,
                    existingPlanId = existingPlan?.id,
                    baseTrailId = trail.id,
                    baseTrail = trail,
                    baseTrailSegments = segments,
                    baseTrailKmMarkers = kmMarkers,
                    isReverse = isReverse,
                    routeName = trail.name,
                    waypoints = wps,
                    legs = initialLegs,
                    failedLegs = emptyMap(),
                    waitingForStartPointTap = false,
                    staleStartLocationMessage = if (usedGpsForStart) effectiveStaleMsg else current.staleStartLocationMessage,
                    isDownloadingTrailGeometry = false,
                    trailGeometryDownloadFailed = false,
                    errorMessage = null,
                    dailyTargetKm = existingPlan?.preferredDailyKm ?: current.dailyTargetKm,
                    overnightPreference = parsedPref ?: current.overnightPreference,
                    startDateMillis = existingPlan?.createdAt ?: current.startDateMillis
                )
            }

            withContext(Dispatchers.IO) {
                for (leg in initialLegs.values) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                }
            }

            if (wps.isNotEmpty()) {
                fetchSunsetForRoute(wps.first().lat, wps.first().lng)
            }
        }
    }

    internal fun loadExistingRoute(id: Long, specificPlanId: Long? = null) {
        viewModelScope.launch {
            val route = repository.getCustomRoute(id) ?: return@launch
            geometryFileName = route.geometryFile.ifBlank { "route_${id}.json" }

            val waypoints = RouteWaypoint.listFromJson(route.waypointsJson)
            val legs = withContext(Dispatchers.IO) {
                RouteGeometryStorageManager.loadLegs(getApplication(), geometryFileName)
            }.toMutableMap()

            val options = parseOptionsJson(route.optionsJson)
            val baseTrailId = route.baseTrailId
            val isReverse = route.isReverse
            val baseTrail = if (!baseTrailId.isNullOrBlank()) repository.getTrailById(baseTrailId) else null
            val segments = if (baseTrail != null) repository.getTrailPoints(baseTrail) else emptyList()
            val kmMarkers = if (segments.isNotEmpty()) {
                TrailSlicer.generateKmMarkers(segments, stepKm = 5.0, isReverse = isReverse)
            } else {
                emptyList()
            }
            val mode = if (baseTrail != null) PlannerMode.TRAIL else PlannerMode.FREE

            if (mode == PlannerMode.TRAIL && segments.isNotEmpty()) {
                for (i in 0 until waypoints.size - 1) {
                    val from = waypoints[i]
                    val to = waypoints[i + 1]
                    val key = "${from.id}_to_${to.id}"
                    if (!legs.containsKey(key) && from.trailKm != null && to.trailKm != null) {
                        legs[key] = TrailSlicer.buildTrailLegGeometry(
                            fromWaypointId = from.id,
                            toWaypointId = to.id,
                            segments = segments,
                            fromKm = from.trailKm,
                            toKm = to.trailKm,
                            isReverse = isReverse
                        )
                    }
                }
            }

            val planKey = baseTrailId ?: "route_${route.id}"
            val existingPlan = if (specificPlanId != null && specificPlanId > 0L) {
                repository.getTripPlanById(specificPlanId)
            } else {
                repository.getLatestTripPlanForTrail(planKey)
            }
            val parsedPref = existingPlan?.overnightType?.let {
                try { OvernightType.valueOf(it) } catch (_: Exception) { null }
            }

            _uiState.update {
                it.copy(
                    mode = mode,
                    interactionMode = PlannerInteractionMode.KIG,
                    existingPlanId = existingPlan?.id,
                    baseTrailId = baseTrailId,
                    baseTrail = baseTrail,
                    baseTrailSegments = segments,
                    baseTrailKmMarkers = kmMarkers,
                    isReverse = isReverse,
                    routeId = route.id,
                    routeName = route.name,
                    waypoints = waypoints,
                    legs = legs,
                    failedLegs = emptyMap(),
                    waitingForStartPointTap = false,
                    options = options,
                    dailyTargetKm = existingPlan?.preferredDailyKm ?: it.dailyTargetKm,
                    overnightPreference = parsedPref ?: it.overnightPreference,
                    startDateMillis = existingPlan?.createdAt ?: it.startDateMillis
                )
            }

            updateRouteStats()
        }
    }

    internal fun pushHistory() {
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options,
            isReverse = _uiState.value.isReverse
        )
        undoStack.add(current)
        if (undoStack.size > MAX_HISTORY) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        _uiState.update { it.copy(hasUnsavedChanges = true) }
        updateUndoRedoAvailability()
        scheduleDraftAutosave()
    }

    internal fun updateUndoRedoAvailability() {
        _uiState.update {
            it.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.lastIndex)
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options,
            isReverse = _uiState.value.isReverse
        )
        redoStack.add(current)
        if (redoStack.size > MAX_HISTORY) {
            redoStack.removeAt(0)
        }

        _uiState.update {
            it.copy(
                waypoints = previous.waypoints,
                legs = previous.legs,
                failedLegs = emptyMap(),
                options = previous.options,
                isReverse = previous.isReverse,
                hasUnsavedChanges = undoStack.isNotEmpty() || previous.waypoints.isNotEmpty()
            )
        }
        updateUndoRedoAvailability()
        updateRouteStats()
        scheduleDraftAutosave()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options,
            isReverse = _uiState.value.isReverse
        )
        undoStack.add(current)

        _uiState.update {
            it.copy(
                waypoints = next.waypoints,
                legs = next.legs,
                failedLegs = emptyMap(),
                options = next.options,
                isReverse = next.isReverse,
                hasUnsavedChanges = true
            )
        }
        updateUndoRedoAvailability()
        updateRouteStats()
        scheduleDraftAutosave()
    }
}
