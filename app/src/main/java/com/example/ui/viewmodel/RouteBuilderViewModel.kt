package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.CustomRouteEntity
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.NearestPoiCategory
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.RundturCandidate
import com.example.data.model.ShelterEntity
import com.example.data.model.SupplyGapWarning
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TripStageEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaypointWeatherEta
import com.example.data.remote.WeatherApiService
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.util.GeoUtils
import com.example.util.GpxImporter
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.HybridRoutingEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteSmartTools
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.TrailKmMarker
import com.example.util.routing.TrailProjection
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WayInfo
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class PlannerMode {
    TRAIL, // Downloaded trail is the base line
    FREE   // Free route builder
}

enum class SheetHeightState {
    PEEK,
    HALF,
    FULL
}

data class PlannerDaySummary(
    val dayNumber: Int,
    val startKm: Double,
    val endKm: Double,
    val distanceKm: Double,
    val ascentM: Int,
    val descentM: Int,
    val fromName: String,
    val toName: String,
    val overnightWaypointId: String?,
    val overnightName: String,
    val overnightTypeLabel: String,
    val detourLabel: String?,
    val detourKm: Double,
    val detourAscentM: Int,
    val bookingLabel: String?,
    val sunsetTime: String,
    val arrivalTime: String,
    val isAfterSunset: Boolean,
    val warnings: List<String>,
    val nightWeatherSummary: String,
    val campLat: Double,
    val campLng: Double,
    val estimatedHours: Double = 0.0,
    val overnightType: String = "SHELTER",
    val bookingRequired: Boolean = false
) {
    val detourDistanceKm: Double?
        get() = detourKm.takeIf { it > 0.0 }
    val arrivalTimeFormatted: String?
        get() = arrivalTime
    val arrivesAfterSunset: Boolean
        get() = isAfterSunset
    val sunsetFormatted: String?
        get() = sunsetTime
}

data class PlannerMapPoi(
    val id: String,
    val name: String,
    val type: String, // "SHELTER", "FRI_TELTNING", "WATER", "TOILET", "SHOP"
    val lat: Double,
    val lng: Double,
    val distanceToRouteMeters: Double = 0.0,
    val bookingRequired: Boolean = false,
    val hasWater: Boolean = false,
    val hasToilet: Boolean = false
)

data class PlannerMapPoiSelection(
    val id: String,
    val name: String,
    val typeLabel: String,
    val lat: Double,
    val lng: Double,
    val isOvernightCapable: Boolean,
    val bookingRequired: Boolean = false
)

data class RouteAlternativeInfo(
    val index: Int,
    val lengthKm: Double,
    val ascentM: Double,
    val pathSharePct: Double?,
    val roadKm: Double?,
    val points: List<RoutePoint3D>,
    val wayInfo: List<WayInfo> = emptyList()
)

typealias RouteBuilderUiState = RouteBuilderState

data class RouteBuilderState(
    val mode: PlannerMode = PlannerMode.FREE,
    val sheetHeightState: SheetHeightState = SheetHeightState.PEEK,
    val existingPlanId: Long? = null,
    val baseTrailId: String? = null,
    val baseTrail: TrailEntity? = null,
    val baseTrailSegments: List<TrailSegmentDto> = emptyList(),
    val baseTrailKmMarkers: List<TrailKmMarker> = emptyList(),
    val isReverse: Boolean = false,
    val draggingWaypointId: String? = null,
    val draggingPinKmLabel: String? = null,
    val tappedTrailPoint: TrailProjection? = null,
    val showTripSettingsSheet: Boolean = false,
    val paceKmh: Double = 4.5,
    val dailyTargetKm: Double = 15.0,
    val startDateMillis: Long = System.currentTimeMillis(),
    val startTimeStr: String = "09:00",
    val maxDetourKm: Double = 3.0,
    val overnightPreference: OvernightType = OvernightType.BOTH,
    val plannedStages: List<TripStageEntity> = emptyList(),
    val daySummaries: List<PlannerDaySummary> = emptyList(),
    val dayBoundaryWalkKms: List<Double> = emptyList(),
    val nearbyShelters: List<ShelterEntity> = emptyList(),
    val nearbyFriTeltning: List<FriTeltningEntity> = emptyList(),
    val nearbyPois: List<PoiEntity> = emptyList(),
    val nearbyPoisForPlan: List<PlannerMapPoi> = emptyList(),
    val selectedNearbyPoiForSheet: PlannerMapPoi? = null,
    val selectedMapPoiForSheet: PlannerMapPoiSelection? = null,
    val replaceOvernightWaypointId: String? = null,
    val replaceOvernightCandidates: List<OvernightCandidate> = emptyList(),
    val routeId: Long? = null,
    val routeName: String = "",
    val waypoints: List<RouteWaypoint> = emptyList(),
    val legs: Map<String, LegGeometryData> = emptyMap(),
    val loadingLegKeys: Set<String> = emptySet(),
    val failedLegs: Map<String, String> = emptyMap(),
    val waitingForStartPointTap: Boolean = false,
    val movingWaypointId: String? = null,
    val isBuiltinFallbackInUse: Boolean = BRouterOnlineEngine.isBuiltinFallbackInUse(),
    val isLoadingCorridorPois: Boolean = false,
    val corridorPoisOfflineNotice: String? = null,
    val isDownloadingTrailGeometry: Boolean = false,
    val trailGeometryDownloadFailed: Boolean = false,
    val showResetProgressDialog: Boolean = false,
    val options: RouteOptions = RouteOptions(),
    val stats: RouteStats = RouteStats(0.0, 0.0, 0.0, null, 0.0, hasWayInfo = false),
    val timeEstimateFormatted: String = "0 min",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val gpxOriginalPoints: List<RoutePoint3D> = emptyList(),
    val alternatives: List<RouteAlternativeInfo> = emptyList(),
    val isComparingAlternatives: Boolean = false,
    val selectedWaypointId: String? = null,
    val scrubbedPointOnMap: RoutePoint3D? = null,
    val accessPolygons: List<com.example.data.model.access.AccessPolygon> = emptyList(),
    val accessWarnings: List<com.example.util.access.RouteAccessWarning> = emptyList(),
    val isAccessLayerVisible: Boolean = true,
    val selectedAccessPolygonForDetails: com.example.data.model.access.AccessPolygon? = null,
    val overnightCandidatesByDay: Map<Int, List<com.example.util.OvernightCandidate>> = emptyMap(),
    val isFindingOvernight: Boolean = false,
    val showOvernightDialog: Boolean = false,
    val supplyGapWarnings: List<SupplyGapWarning> = emptyList(),
    val maxWaterGapKm: Double = 12.0,
    val maxShopGapKm: Double = 25.0,
    val rundturCandidates: List<RundturCandidate> = emptyList(),
    val isGeneratingRundtur: Boolean = false,
    val showRundturDialog: Boolean = false,
    val rundturTargetKm: Double = 15.0,
    val waypointWeatherEtas: List<WaypointWeatherEta> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val isRoutingToNearest: Boolean = false,
    val showNearestPoiDialog: Boolean = false,
    val routeQuality: com.example.data.model.RouteQualityBreakdown = com.example.data.model.RouteQualityBreakdown(),
    val customUserWaypoints: List<UserCustomWaypointEntity> = emptyList(),
    val showAddCustomWaypointDialog: Boolean = false,
    val pendingCustomWaypointLocation: LatLng? = null,
    val selectedCustomWaypointForDetails: UserCustomWaypointEntity? = null,
    val isOfflineRoute: Boolean = false,
    val routeEngineModeLabel: String = "Online rute",
    val nextLegMode: LegMode = LegMode.SNAP,
    val isOvernightToolActive: Boolean = false
) {
    val visibleWaypoints: List<RouteWaypoint>
        get() = waypoints.filter { !it.isHelper }
    val numDays: Int
        get() = max(1, visibleWaypoints.count { it.role == WaypointRole.OVERNIGHT } + 1)
    val baseTrailPoints: List<GeoPointDto>
        get() = baseTrailSegments.flatMap { it.points }
    val draggingTrailKmLabel: String?
        get() = draggingPinKmLabel
    val trailLineTapTarget: TrailProjection?
        get() = tappedTrailPoint
    val replacingOvernightWaypointId: String?
        get() = replaceOvernightWaypointId
    val startHour: Int
        get() = startTimeStr.substringBefore(":").toIntOrNull() ?: 9
    val startDateEpochMs: Long
        get() = startDateMillis
    val plannerMode: PlannerMode
        get() = mode
    val totalKm: Double
        get() = stats.totalKm
    val isRoutingLegCount: Int
        get() = loadingLegKeys.size
}

class RouteBuilderViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val TAG = "RouteBuilderVM"
    private val app = application as DanskStiApp
    private val repository: DanskStiRepository = app.repository
    private val settingsRepository = SettingsRepository(application)
    private val brouterStorageManager: BRouterStorageManager = BRouterStorageManager.getInstance(application)
    private val onlineEngine: BRouterOnlineEngine = BRouterOnlineEngine(application)
    private val offlineEngine: BRouterOfflineEngine = BRouterOfflineEngine(application)
    private val routingEngine: RoutingEngine = HybridRoutingEngine(
        context = application,
        onlineEngine = onlineEngine,
        offlineEngine = offlineEngine,
        storageManager = brouterStorageManager
    )
    private val weatherService: WeatherApiService = WeatherApiService.create()

    private val _uiState = MutableStateFlow(RouteBuilderState())
    val uiState: StateFlow<RouteBuilderState> = _uiState.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // Undo / Redo stacks (max 50)
    private data class HistorySnapshot(
        val waypoints: List<RouteWaypoint>,
        val legs: Map<String, LegGeometryData>,
        val options: RouteOptions,
        val isReverse: Boolean = false
    )
    private val undoStack = mutableListOf<HistorySnapshot>()
    private val redoStack = mutableListOf<HistorySnapshot>()
    private val MAX_HISTORY = 50

    private var mapCameraAccessJob: Job? = null
    private var corridorAccessJob: Job? = null
    private var supplyGapsJob: Job? = null
    private var stagesAndPoisJob: Job? = null
    private var nightWeatherCache: MutableMap<String, String> = mutableMapOf()
    private var pendingSaveCallback: ((Long) -> Unit)? = null

    private var geometryFileName: String = "route_${UUID.randomUUID()}.json"
    private var pacingSpeedKmh: Double = 4.5
    private var startTimeStr: String = "09:00"
    private var sunsetTimeStr: String = "20:30"

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            pacingSpeedKmh = settings.mitTempoKmh
            startTimeStr = settings.startTime
            _uiState.value = _uiState.value.copy(
                paceKmh = settings.mitTempoKmh,
                startTimeStr = settings.startTime,
                maxDetourKm = settings.maxDetourKm
            )
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
        baseTrailId: String? = null,
        trailId: String? = null,
        existingPlanId: Long? = null
    ) {
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

        if (existingPlanId != null && existingPlanId > 0L) {
            viewModelScope.launch {
                val plan = repository.getTripPlanById(existingPlanId)
                if (plan != null) {
                    val linkedRouteId = plan.customRouteId ?: plan.trailId.removePrefix("route_").toLongOrNull()
                    if (linkedRouteId != null && linkedRouteId > 0L && repository.getCustomRoute(linkedRouteId) != null) {
                        loadExistingRoute(linkedRouteId, existingPlanId)
                    } else {
                        initializeTrailMode(plan.trailId, effGpsLat, effGpsLng, existingPlanId)
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
            initializeTrailMode(effTrailId, effGpsLat, effGpsLng)
            return
        }

        if (effDestLat != null && effDestLng != null) {
            if (_uiState.value.waypoints.isEmpty()) {
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
                    _uiState.value = _uiState.value.copy(
                        mode = PlannerMode.FREE,
                        routeName = effDestName?.let { "Rute til $it" } ?: "Ny rute",
                        waypoints = listOf(startWp, destWp),
                        waitingForStartPointTap = false,
                        errorMessage = null
                    )
                    computeSingleLeg(startWp, destWp)
                } else {
                    _uiState.value = _uiState.value.copy(
                        mode = PlannerMode.FREE,
                        routeName = effDestName?.let { "Rute til $it" } ?: "Ny rute",
                        waypoints = listOf(destWp),
                        waitingForStartPointTap = true,
                        errorMessage = "Vælg startpunkt - tryk på kortet"
                    )
                    viewModelScope.launch {
                        _userMessage.emit("Vælg startpunkt - tryk på kortet")
                    }
                }
            }
        }
    }

    fun retryDownloadTrailGeometry() {
        val tid = _uiState.value.baseTrailId ?: return
        initializeTrailMode(tid, specificPlanId = _uiState.value.existingPlanId)
    }

    private fun initializeTrailMode(
        trailId: String,
        currentGpsLat: Double? = null,
        currentGpsLng: Double? = null,
        specificPlanId: Long? = null
    ) {
        val preseededTrail = com.example.data.local.PreseededData.getInitialTrails().find { it.id == trailId }
        _uiState.value = _uiState.value.copy(
            mode = PlannerMode.TRAIL,
            baseTrailId = trailId,
            baseTrail = preseededTrail ?: _uiState.value.baseTrail,
            routeName = preseededTrail?.name ?: _uiState.value.routeName,
            waitingForStartPointTap = false,
            trailGeometryDownloadFailed = false,
            errorMessage = null
        )
        viewModelScope.launch(Dispatchers.Default) {
            val isCached = repository.geometryRepository.isGeometryCached(trailId)
            if (!isCached) {
                _uiState.value = _uiState.value.copy(isDownloadingTrailGeometry = true)
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
                _uiState.value = _uiState.value.copy(
                    isDownloadingTrailGeometry = false,
                    trailGeometryDownloadFailed = true,
                    errorMessage = "Ruten kunne ikke hentes"
                )
                return@launch
            }

            val segments = repository.getTrailPoints(trail)
            val totalKm = TrailSlicer.totalTrailKm(segments)
            val isReverse = existingPlan?.isReverse ?: false
            val kmMarkers = TrailSlicer.generateKmMarkers(segments, stepKm = 5.0, isReverse = isReverse)

            var startKm = existingPlan?.startKm ?: 0.0
            var endKm = existingPlan?.endKm?.takeIf { it > startKm } ?: totalKm

            if (existingPlan == null && currentGpsLat != null && currentGpsLng != null && segments.isNotEmpty()) {
                val gpsProj = TrailSlicer.projectOntoTrail(segments, currentGpsLat, currentGpsLng, isReverse)
                if (gpsProj != null && gpsProj.distanceMeters <= 2000.0) {
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
                _uiState.value = _uiState.value.copy(
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

    private fun loadExistingRoute(id: Long, specificPlanId: Long? = null) {
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

            _uiState.value = _uiState.value.copy(
                mode = mode,
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
                dailyTargetKm = existingPlan?.preferredDailyKm ?: _uiState.value.dailyTargetKm,
                overnightPreference = parsedPref ?: _uiState.value.overnightPreference,
                startDateMillis = existingPlan?.createdAt ?: _uiState.value.startDateMillis
            )

            updateRouteStats()
        }
    }

    private fun pushHistory() {
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
        updateUndoRedoAvailability()
    }

    private fun updateUndoRedoAvailability() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
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

        _uiState.value = _uiState.value.copy(
            waypoints = previous.waypoints,
            legs = previous.legs,
            failedLegs = emptyMap(),
            options = previous.options,
            isReverse = previous.isReverse
        )
        updateUndoRedoAvailability()
        updateRouteStats()
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

        _uiState.value = _uiState.value.copy(
            waypoints = next.waypoints,
            legs = next.legs,
            failedLegs = emptyMap(),
            options = next.options,
            isReverse = next.isReverse
        )
        updateUndoRedoAvailability()
        updateRouteStats()
    }

    // --- TRAIL Mode Tap & Detour Actions (Requirements C & D) ---

    fun onTrailLineTapped(lat: Double, lng: Double) {
        val state = _uiState.value
        if (state.mode != PlannerMode.TRAIL || state.baseTrailSegments.isEmpty()) return
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse) ?: return
        _uiState.value = state.copy(tappedTrailPoint = proj)
    }

    fun dismissTrailTapSheet() {
        _uiState.value = _uiState.value.copy(tappedTrailPoint = null)
    }

    fun dismissTrailLineTapSheet() = dismissTrailTapSheet()

    fun trailTapStartHere() {
        val proj = _uiState.value.tappedTrailPoint ?: return
        dismissTrailTapSheet()
        val state = _uiState.value
        val wps = state.waypoints.toMutableList()
        val startIdx = wps.indexOfFirst { it.role == WaypointRole.START }
        if (startIdx == -1) return
        moveWaypointOnTrailToKm(wps[startIdx].id, proj.trailKm, finishDrag = true)
    }

    fun onTrailTapStartHer() = trailTapStartHere()

    fun trailTapEndHere() {
        val proj = _uiState.value.tappedTrailPoint ?: return
        dismissTrailTapSheet()
        val state = _uiState.value
        val wps = state.waypoints.toMutableList()
        val endIdx = wps.indexOfLast { it.role == WaypointRole.END }
        if (endIdx == -1) return
        moveWaypointOnTrailToKm(wps[endIdx].id, proj.trailKm, finishDrag = true)
    }

    fun onTrailTapSlutHer() = trailTapEndHere()

    fun trailTapOvernightHere() {
        val proj = _uiState.value.tappedTrailPoint ?: return
        dismissTrailTapSheet()
        insertWaypointOnTrailAtKm(
            trailKm = proj.trailKm,
            role = WaypointRole.OVERNIGHT,
            name = String.format(Locale.GERMAN, "Overnatning (km %.1f)", proj.trailKm)
        )
    }

    fun onTrailTapOvernatHer() = trailTapOvernightHere()

    fun trailTapAddViaHere() {
        val proj = _uiState.value.tappedTrailPoint ?: return
        dismissTrailTapSheet()
        insertWaypointOnTrailAtKm(
            trailKm = proj.trailKm,
            role = WaypointRole.VIA,
            name = String.format(Locale.GERMAN, "Punkt (km %.1f)", proj.trailKm)
        )
    }

    fun onTrailTapTilfoejPunkt() = trailTapAddViaHere()

    private fun insertWaypointOnTrailAtKm(
        trailKm: Double,
        role: WaypointRole,
        name: String?,
        linkedPoiId: String? = null
    ) {
        val state = _uiState.value
        val segments = state.baseTrailSegments
        if (segments.isEmpty()) return

        pushHistory()
        val wps = state.waypoints.toMutableList()
        val pt = TrailSlicer.pointAtTrailKm(segments, trailKm, state.isReverse) ?: return

        // Find where trailKm falls among existing trailKm waypoints
        var insertIndex = wps.size - 1
        for (i in 0 until wps.size - 1) {
            val k0 = wps[i].trailKm
            val k1 = wps[i + 1].trailKm
            if (k0 != null && k1 != null && trailKm >= min(k0, k1) && trailKm <= max(k0, k1)) {
                insertIndex = i + 1
                break
            }
        }
        insertIndex = insertIndex.coerceIn(1, max(1, wps.size - 1))

        val newWp = RouteWaypoint(
            lat = pt.lat,
            lng = pt.lng,
            role = role,
            legModeToNext = LegMode.TRAIL,
            name = name,
            linkedPoiId = linkedPoiId,
            trailKm = trailKm
        )

        val prevWp = wps[insertIndex - 1]
        val nextWp = wps[insertIndex]
        wps.add(insertIndex, newWp)
        normalizeRoles(wps)

        val updatedLegs = state.legs.toMutableMap()
        updatedLegs.remove("${prevWp.id}_to_${nextWp.id}")

        val leg1 = TrailSlicer.buildTrailLegGeometry(
            fromWaypointId = prevWp.id,
            toWaypointId = newWp.id,
            segments = segments,
            fromKm = prevWp.trailKm ?: 0.0,
            toKm = trailKm,
            isReverse = state.isReverse
        )
        val leg2 = TrailSlicer.buildTrailLegGeometry(
            fromWaypointId = newWp.id,
            toWaypointId = nextWp.id,
            segments = segments,
            fromKm = trailKm,
            toKm = nextWp.trailKm ?: TrailSlicer.totalTrailKm(segments),
            isReverse = state.isReverse
        )
        updatedLegs[leg1.legKey] = leg1
        updatedLegs[leg2.legKey] = leg2

        _uiState.value = state.copy(
            waypoints = wps,
            legs = updatedLegs
        )

        viewModelScope.launch(Dispatchers.IO) {
            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg1)
            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg2)
        }
        updateRouteStats()
    }

    /**
     * Requirement D: Adds a stop or overnight pin in TRAIL or FREE mode.
     * In TRAIL mode:
     * - If > 50 m off the trail (or a POI/shelter/fri teltning chosen with "Overnat her" / "Tilføj som stop"),
     *   computes a detour: TRAIL (to exit) -> SNAP (exit to pin) -> SNAP (pin to rejoin) -> TRAIL (onwards),
     *   with hidden helper waypoints (role VIA, isHelper = true) and detour cost on the pin.
     */
    fun addDetourPoiToRoute(
        lat: Double,
        lng: Double,
        name: String,
        role: WaypointRole,
        poiId: String? = null,
        poiType: String? = null
    ) {
        _uiState.value = _uiState.value.copy(selectedNearbyPoiForSheet = null, selectedMapPoiForSheet = null)
        addStopOrDetour(
            lat = lat,
            lng = lng,
            role = role,
            name = name,
            linkedPoiId = poiId,
            spotType = poiType
        )
    }

    fun addOvernightDetourPoi(
        lat: Double,
        lng: Double,
        name: String,
        spotType: String = "SHELTER",
        poiId: String? = null,
        bookingRequired: Boolean = false,
        hasWater: Boolean = false,
        hasToilet: Boolean = false
    ) {
        addStopOrDetour(
            lat = lat,
            lng = lng,
            role = WaypointRole.OVERNIGHT,
            name = name,
            linkedPoiId = poiId,
            spotType = spotType,
            bookingRequired = bookingRequired,
            hasWater = hasWater,
            hasToilet = hasToilet
        )
    }

    fun selectNearbyPoiForSheet(poi: PlannerMapPoi?) {
        _uiState.value = _uiState.value.copy(selectedNearbyPoiForSheet = poi)
    }

    fun addStopOrDetour(
        lat: Double,
        lng: Double,
        role: WaypointRole,
        name: String?,
        linkedPoiId: String? = null,
        spotType: String? = null,
        bookingRequired: Boolean = false,
        hasWater: Boolean = false,
        hasToilet: Boolean = false
    ) {
        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
            if (proj != null && proj.distanceMeters <= 50.0 && linkedPoiId == null) {
                insertWaypointOnTrailAtKm(
                    trailKm = proj.trailKm,
                    role = role,
                    name = name ?: String.format(Locale.GERMAN, "Punkt (km %.1f)", proj.trailKm)
                )
                return
            }

            // Off-trail detour (> 50m or explicit POI)
            viewModelScope.launch(Dispatchers.Default) {
                pushHistory()
                val wps = _uiState.value.waypoints.toMutableList()
                val segments = _uiState.value.baseTrailSegments
                val isRev = _uiState.value.isReverse
                val projKm = proj?.trailKm ?: (TrailSlicer.totalTrailKm(segments) / 2.0)

                var prevTrailIdx = 0
                var nextTrailIdx = wps.lastIndex
                for (i in wps.indices) {
                    val km = wps[i].trailKm ?: continue
                    if (km <= projKm) prevTrailIdx = i
                    if (km >= projKm && i > prevTrailIdx) {
                        nextTrailIdx = i
                        break
                    }
                }
                if (nextTrailIdx <= prevTrailIdx && wps.size >= 2) {
                    prevTrailIdx = max(0, wps.lastIndex - 1)
                    nextTrailIdx = wps.lastIndex
                }

                val prevTrailWp = wps[prevTrailIdx]
                val nextTrailWp = wps[nextTrailIdx]

                val rawPin = RouteWaypoint(
                    lat = lat,
                    lng = lng,
                    role = role,
                    legModeToNext = LegMode.SNAP,
                    name = name ?: if (role == WaypointRole.OVERNIGHT) "Overnatning" else "Omvejspunkt",
                    linkedPoiId = linkedPoiId,
                    trailKm = null,
                    spotType = spotType,
                    bookingRequired = bookingRequired,
                    hasWaterNearby = hasWater,
                    hasToiletNearby = hasToilet
                )

                val detourPlan = TrailSlicer.buildTrailDetour(
                    segments = segments,
                    prevWp = prevTrailWp,
                    nextWp = nextTrailWp,
                    pinWp = rawPin,
                    routingEngine = routingEngine,
                    options = _uiState.value.options,
                    isReverse = isRev
                )

                val updatedLegs = _uiState.value.legs.toMutableMap()
                updatedLegs.remove("${prevTrailWp.id}_to_${nextTrailWp.id}")

                wps[prevTrailIdx] = prevTrailWp.copy(legModeToNext = LegMode.TRAIL)
                wps.addAll(
                    prevTrailIdx + 1,
                    listOf(detourPlan.exitHelper, detourPlan.detourWaypoint, detourPlan.rejoinHelper)
                )
                normalizeRoles(wps)

                for (leg in detourPlan.legs) {
                    updatedLegs[leg.legKey] = leg
                }

                updateRouteStats { current ->
                    val mergedFailed = current.failedLegs.toMutableMap().apply {
                        putAll(detourPlan.failedLegs)
                    }
                    current.copy(
                        waypoints = wps,
                        legs = updatedLegs,
                        failedLegs = mergedFailed,
                        errorMessage = detourPlan.failedLegs.values.firstOrNull() ?: current.errorMessage,
                        selectedWaypointId = detourPlan.detourWaypoint.id
                    )
                }

                withContext(Dispatchers.IO) {
                    for (leg in detourPlan.legs) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                    }
                }

                val detourLabel = TrailSlicer.formatDetourCost(
                    detourPlan.detourWaypoint.name,
                    detourPlan.detourCostKm,
                    detourPlan.detourAscentM
                )
                _userMessage.emit(detourLabel)
            }
            return
        }

        // FREE mode: insert as OVERNIGHT or VIA along the closest leg (or at end)
        val wps = state.waypoints
        if (wps.size < 2) {
            addPointAtEnd(lat, lng, name)
            return
        }
        val cand = OvernightCandidate(
            id = linkedPoiId ?: UUID.randomUUID().toString(),
            name = name ?: if (role == WaypointRole.OVERNIGHT) "Overnatning" else "Stop",
            type = spotType ?: "SHELTER",
            lat = lat,
            lng = lng,
            walkKm = 0.0,
            detourMeters = 0.0,
            bookingRequired = bookingRequired,
            hasWaterNearby = hasWater,
            hasToiletNearby = hasToilet
        )
        if (role == WaypointRole.OVERNIGHT) {
            insertOvernightCandidate(cand)
        } else {
            insertViaPointOnLine(lat, lng)
        }
    }

    fun selectMapPoi(poi: PlannerMapPoiSelection?) {
        _uiState.value = _uiState.value.copy(selectedMapPoiForSheet = poi)
    }

    fun addSelectedPoiAsOvernight() {
        val poi = _uiState.value.selectedMapPoiForSheet ?: return
        _uiState.value = _uiState.value.copy(selectedMapPoiForSheet = null)
        addStopOrDetour(
            lat = poi.lat,
            lng = poi.lng,
            role = WaypointRole.OVERNIGHT,
            name = poi.name,
            linkedPoiId = poi.id
        )
    }

    fun addSelectedPoiAsStop() {
        val poi = _uiState.value.selectedMapPoiForSheet ?: return
        _uiState.value = _uiState.value.copy(selectedMapPoiForSheet = null)
        addStopOrDetour(
            lat = poi.lat,
            lng = poi.lng,
            role = WaypointRole.VIA,
            name = poi.name,
            linkedPoiId = poi.id
        )
    }

    /**
     * Requirement C: While dragging a START/END/OVERNIGHT pin that has trailKm,
     * project the finger position onto the trail and show "km 23,4" next to the pin while dragging.
     * Updates totals instantly without any network request!
     */
    fun onWaypointDragging(waypointId: String, fingerLat: Double, fingerLng: Double): TrailProjection? {
        val state = _uiState.value
        if (state.mode != PlannerMode.TRAIL || state.baseTrailSegments.isEmpty()) return null
        val wp = state.waypoints.find { it.id == waypointId } ?: return null
        if (wp.trailKm == null) return null

        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, fingerLat, fingerLng, state.isReverse) ?: return null
        _uiState.value = _uiState.value.copy(draggingWaypointId = waypointId)
        moveWaypointOnTrailToKm(waypointId, proj.trailKm, finishDrag = false)
        return proj
    }

    fun onDraggingWaypoint(waypointId: String, fingerLat: Double, fingerLng: Double) {
        onWaypointDragging(waypointId, fingerLat, fingerLng)
    }

    fun onWaypointDragged(waypointId: String, lat: Double, lng: Double) {
        moveWaypoint(waypointId, lat, lng)
    }

    fun moveWaypointOnTrailToKm(waypointId: String, rawTargetKm: Double, finishDrag: Boolean = true) {
        val state = _uiState.value
        val segments = state.baseTrailSegments
        if (segments.isEmpty()) return
        val wps = state.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        val totalTrailKm = TrailSlicer.totalTrailKm(segments)
        // Clamp between previous and next trailKm pins so order along the trail is preserved
        val minAllowedKm = (index - 1 downTo 0).firstNotNullOfOrNull { wps[it].trailKm }?.plus(0.05) ?: 0.0
        val maxAllowedKm = (index + 1 until wps.size).firstNotNullOfOrNull { wps[it].trailKm }?.minus(0.05) ?: totalTrailKm
        val clampedKm = rawTargetKm.coerceIn(min(minAllowedKm, maxAllowedKm), max(minAllowedKm, maxAllowedKm))

        val pt = TrailSlicer.pointAtTrailKm(segments, clampedKm, state.isReverse) ?: return
        if (finishDrag) {
            pushHistory()
        }

        val oldWp = wps[index]
        val updatedName = when (oldWp.role) {
            WaypointRole.START -> String.format(Locale.GERMAN, "Start (km %.1f)", clampedKm)
            WaypointRole.END -> String.format(Locale.GERMAN, "Slut (km %.1f)", clampedKm)
            else -> oldWp.name
        }
        val updatedWp = oldWp.copy(
            lat = pt.lat,
            lng = pt.lng,
            trailKm = clampedKm,
            name = updatedName
        )
        wps[index] = updatedWp

        val updatedLegs = state.legs.toMutableMap()
        val legsToPersist = mutableListOf<LegGeometryData>()

        // Re-slice previous leg if both endpoints have trailKm
        if (index > 0) {
            val prevWp = wps[index - 1]
            if (prevWp.trailKm != null) {
                val prevLeg = TrailSlicer.buildTrailLegGeometry(
                    fromWaypointId = prevWp.id,
                    toWaypointId = updatedWp.id,
                    segments = segments,
                    fromKm = prevWp.trailKm,
                    toKm = clampedKm,
                    isReverse = state.isReverse
                )
                updatedLegs[prevLeg.legKey] = prevLeg
                legsToPersist.add(prevLeg)
            }
        }

        // Re-slice next leg if both endpoints have trailKm
        if (index < wps.size - 1) {
            val nextWp = wps[index + 1]
            if (nextWp.trailKm != null) {
                val nextLeg = TrailSlicer.buildTrailLegGeometry(
                    fromWaypointId = updatedWp.id,
                    toWaypointId = nextWp.id,
                    segments = segments,
                    fromKm = clampedKm,
                    toKm = nextWp.trailKm,
                    isReverse = state.isReverse
                )
                updatedLegs[nextLeg.legKey] = nextLeg
                legsToPersist.add(nextLeg)
            }
        }

        val kmLabel = if (finishDrag) null else String.format(Locale.GERMAN, "km %.1f", clampedKm)
        _uiState.value = state.copy(
            waypoints = wps,
            legs = updatedLegs,
            draggingWaypointId = if (finishDrag) null else waypointId,
            draggingPinKmLabel = kmLabel,
            movingWaypointId = if (finishDrag) null else state.movingWaypointId
        )

        if (finishDrag && legsToPersist.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                for (leg in legsToPersist) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                }
            }
        }
        updateRouteStats()
    }

    /**
     * Map tap:
     * - If "Flyt punkt" mode is active (`movingWaypointId != null`), moves that waypoint to the tapped coordinates.
     * - In TRAIL mode:
     *   - Tapping near the trail line (<= 60 m) opens the trail tap sheet ("Start her", "Slut her", "Overnat her", "Tilføj punkt").
     *   - Tapping off the trail (> 60 m) adds a detour stop!
     * - In FREE mode:
     *   - If waiting for start point tap, inserts before destination.
     *   - Otherwise adds a new waypoint at the end.
     */
    fun toggleNextLegMode() {
        val current = _uiState.value.nextLegMode
        val next = if (current == LegMode.SNAP) LegMode.STRAIGHT else LegMode.SNAP
        val wps = _uiState.value.waypoints.toMutableList()
        if (wps.isNotEmpty()) {
            val lastIdx = wps.lastIndex
            wps[lastIdx] = wps[lastIdx].copy(legModeToNext = next)
        }
        _uiState.value = _uiState.value.copy(
            nextLegMode = next,
            waypoints = wps
        )
    }

    fun toggleOvernightTool() {
        val nextActive = !_uiState.value.isOvernightToolActive
        _uiState.value = _uiState.value.copy(isOvernightToolActive = nextActive)
    }

    fun addPointAtEnd(lat: Double, lng: Double, name: String? = null) {
        val movingId = _uiState.value.movingWaypointId
        if (movingId != null) {
            _uiState.value = _uiState.value.copy(movingWaypointId = null)
            moveWaypoint(movingId, lat, lng)
            return
        }

        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
            if (state.isOvernightToolActive) {
                addStopOrDetour(
                    lat = lat,
                    lng = lng,
                    role = WaypointRole.OVERNIGHT,
                    name = name ?: "Overnatning"
                )
                return
            }
            if (proj != null && proj.distanceMeters <= 60.0) {
                _uiState.value = state.copy(tappedTrailPoint = proj)
            } else {
                addStopOrDetour(
                    lat = lat,
                    lng = lng,
                    role = WaypointRole.VIA,
                    name = name ?: "Stop"
                )
            }
            return
        }

        val currentWps = state.waypoints.toMutableList()

        // Requirement 8: Insert the next tap BEFORE the destination if waiting for start point
        if (state.waitingForStartPointTap || (currentWps.size == 1 && currentWps.first().role == WaypointRole.END)) {
            pushHistory()
            val startWp = RouteWaypoint(
                lat = lat,
                lng = lng,
                role = WaypointRole.START,
                legModeToNext = state.nextLegMode,
                name = name ?: "Start"
            )
            currentWps.add(0, startWp)
            normalizeRoles(currentWps)
            _uiState.value = _uiState.value.copy(
                waypoints = currentWps,
                waitingForStartPointTap = false,
                errorMessage = null
            )
            if (currentWps.size >= 2) {
                computeSingleLeg(currentWps[0], currentWps[1])
            }
            return
        }

        pushHistory()
        val role = when {
            currentWps.isEmpty() -> WaypointRole.START
            state.isOvernightToolActive -> WaypointRole.OVERNIGHT
            else -> WaypointRole.END
        }

        if (currentWps.isNotEmpty()) {
            val lastIdx = currentWps.lastIndex
            val prevWpUpdated = currentWps[lastIdx].copy(
                role = if (currentWps[lastIdx].role == WaypointRole.END) WaypointRole.VIA else currentWps[lastIdx].role,
                legModeToNext = state.nextLegMode
            )
            currentWps[lastIdx] = prevWpUpdated
        }

        val newWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = role,
            legModeToNext = state.nextLegMode,
            name = name
        )
        currentWps.add(newWp)

        _uiState.value = _uiState.value.copy(waypoints = currentWps)

        if (currentWps.size >= 2) {
            val prevWp = currentWps[currentWps.size - 2]
            computeSingleLeg(prevWp, newWp)
        } else {
            fetchSunsetForRoute(lat, lng)
            updateRouteStats()
        }
    }

    /**
     * Long-press on the line: Insert a via point at the tapped location.
     */
    fun insertViaPointOnLine(lat: Double, lng: Double) {
        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            addStopOrDetour(lat, lng, WaypointRole.VIA, "Via")
            return
        }

        val wps = state.waypoints
        if (wps.size < 2) {
            addPointAtEnd(lat, lng)
            return
        }

        var bestIndex = -1
        var minDistanceM = Double.MAX_VALUE

        for (i in 0 until wps.size - 1) {
            val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
            val leg = _uiState.value.legs[legKey]
            val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

            for (pt in pts) {
                val d = haversineM(lat, lng, pt.lat, pt.lng)
                if (d < minDistanceM) {
                    minDistanceM = d
                    bestIndex = i + 1
                }
            }
        }

        if (bestIndex != -1) {
            insertPointAtIndex(bestIndex, lat, lng)
        } else {
            addPointAtEnd(lat, lng)
        }
    }

    /**
     * Inserts a via point at a specific index, replacing leg (i-1 -> i) with two legs.
     */
    fun insertPointAtIndex(index: Int, lat: Double, lng: Double) {
        pushHistory()
        val currentWps = _uiState.value.waypoints.toMutableList()
        val newWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = WaypointRole.VIA,
            legModeToNext = LegMode.SNAP,
            name = "Via ${currentWps.size}"
        )

        currentWps.add(index, newWp)
        normalizeRoles(currentWps)
        _uiState.value = _uiState.value.copy(waypoints = currentWps)

        val prevWp = currentWps[index - 1]
        val nextWp = currentWps[index + 1]

        val oldKey = "${prevWp.id}_to_${nextWp.id}"
        val updatedLegs = _uiState.value.legs.toMutableMap()
        updatedLegs.remove(oldKey)
        val updatedFailed = _uiState.value.failedLegs.toMutableMap()
        updatedFailed.remove(oldKey)
        _uiState.value = _uiState.value.copy(legs = updatedLegs, failedLegs = updatedFailed)

        computeSingleLeg(prevWp, newWp)
        computeSingleLeg(newWp, nextWp)
    }

    /**
     * Waypoint dragged/moved:
     * - In TRAIL mode, if the waypoint has `trailKm`, keeps it on the trail (Requirement C) and slices TRAIL legs immediately without network calls.
     * - If the waypoint is an off-trail detour pin in TRAIL mode, recomputes its detour.
     * - In FREE mode, re-routes ONLY the legs touching this changed point.
     */
    fun moveWaypoint(waypointId: String, newLat: Double, newLng: Double) {
        val state = _uiState.value
        val wps = state.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        val oldWp = wps[index]
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            if (oldWp.trailKm != null) {
                val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, newLat, newLng, state.isReverse)
                if (proj != null) {
                    moveWaypointOnTrailToKm(waypointId, proj.trailKm, finishDrag = true)
                    return
                }
            } else {
                // Moving an off-trail detour pin in TRAIL mode: remove old detour and re-add at new position
                val role = oldWp.role
                val name = oldWp.name
                val poiId = oldWp.linkedPoiId
                deleteWaypoint(waypointId)
                addStopOrDetour(newLat, newLng, role, name, poiId)
                return
            }
        }

        pushHistory()
        val updatedWp = oldWp.copy(lat = newLat, lng = newLng)
        wps[index] = updatedWp
        _uiState.value = _uiState.value.copy(
            waypoints = wps,
            movingWaypointId = null,
            draggingPinKmLabel = null
        )

        if (index > 0) {
            val prevWp = wps[index - 1]
            computeSingleLeg(prevWp, updatedWp)
        }
        if (index < wps.size - 1) {
            val nextWp = wps[index + 1]
            computeSingleLeg(updatedWp, nextWp)
        }
    }

    /**
     * Delete waypoint at index.
     * Requirement D: In TRAIL mode, removing a detour pin removes its hidden helpers and restores the plain TRAIL leg.
     */
    fun deleteWaypoint(waypointId: String) {
        val state = _uiState.value
        val wps = state.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()

        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val (newWps, newLegs) = TrailSlicer.removeDetourAndRestoreTrailLeg(
                waypoints = wps,
                legs = state.legs,
                removedWaypointId = waypointId,
                segments = state.baseTrailSegments,
                isReverse = state.isReverse
            )
            val mutableWps = newWps.toMutableList()
            normalizeRoles(mutableWps)
            _uiState.value = state.copy(
                waypoints = mutableWps,
                legs = newLegs,
                selectedWaypointId = null
            )
            updateRouteStats()
            return
        }

        val removed = wps.removeAt(index)
        normalizeRoles(wps)

        val currentLegs = _uiState.value.legs.toMutableMap()
        val keysToRemove = currentLegs.keys.filter { it.contains(removed.id) }
        for (k in keysToRemove) currentLegs.remove(k)

        val currentFailed = _uiState.value.failedLegs.toMutableMap()
        val failedKeysToRemove = currentFailed.keys.filter { it.contains(removed.id) }
        for (k in failedKeysToRemove) currentFailed.remove(k)

        _uiState.value = _uiState.value.copy(
            waypoints = wps,
            legs = currentLegs,
            failedLegs = currentFailed,
            selectedWaypointId = null
        )

        if (index > 0 && index < wps.size) {
            computeSingleLeg(wps[index - 1], wps[index])
        } else {
            updateRouteStats()
        }
    }

    /**
     * Toggle role: VIA <-> OVERNIGHT
     */
    fun toggleWaypointOvernight(waypointId: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        val current = wps[index]
        val newRole = if (current.role == WaypointRole.OVERNIGHT) WaypointRole.VIA else WaypointRole.OVERNIGHT
        wps[index] = current.copy(role = newRole)
        _uiState.value = _uiState.value.copy(waypoints = wps)
        updateRouteStats()
    }

    /**
     * Toggle leg mode from this waypoint to the next: SNAP <-> STRAIGHT
     */
    fun toggleLegMode(waypointId: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1 || index >= wps.size - 1) return

        pushHistory()
        val current = wps[index]
        val newMode = if (current.legModeToNext == LegMode.SNAP) LegMode.STRAIGHT else LegMode.SNAP
        wps[index] = current.copy(legModeToNext = newMode)
        _uiState.value = _uiState.value.copy(waypoints = wps)

        computeSingleLeg(wps[index], wps[index + 1])
    }

    /**
     * Requirement 6: "Prøv igen" re-runs only that failed leg.
     */
    fun retryFailedLeg(legKey: String) {
        val parts = legKey.split("_to_")
        if (parts.size != 2) return
        val wps = _uiState.value.waypoints
        val fromWp = wps.find { it.id == parts[0] } ?: return
        val toWp = wps.find { it.id == parts[1] } ?: return
        computeSingleLeg(fromWp, toWp)
    }

    /**
     * Requirement 6: "Tegn lige linje" sets only that failed leg to STRAIGHT and computes it.
     */
    fun setFailedLegStraight(legKey: String) {
        val parts = legKey.split("_to_")
        if (parts.size != 2) return
        val wps = _uiState.value.waypoints.toMutableList()
        val fromIdx = wps.indexOfFirst { it.id == parts[0] }
        val toIdx = wps.indexOfFirst { it.id == parts[1] }
        if (fromIdx == -1 || toIdx == -1) return

        pushHistory()
        wps[fromIdx] = wps[fromIdx].copy(legModeToNext = LegMode.STRAIGHT)
        val updatedFailed = _uiState.value.failedLegs.toMutableMap()
        updatedFailed.remove(legKey)
        _uiState.value = _uiState.value.copy(
            waypoints = wps,
            failedLegs = updatedFailed,
            errorMessage = null
        )
        computeSingleLeg(wps[fromIdx], wps[toIdx])
    }

    /**
     * Requirement 6: "Flyt punkt" selects the endpoint of the failed leg for moving (by tap or drag).
     */
    fun activateMovePointForFailedLeg(legKey: String) {
        val parts = legKey.split("_to_")
        if (parts.size != 2) return
        val targetId = parts[1]
        _uiState.value = _uiState.value.copy(
            selectedWaypointId = targetId,
            movingWaypointId = targetId,
            errorMessage = "Tryk på kortet eller træk markøren for at flytte punktet"
        )
        viewModelScope.launch {
            _userMessage.emit("Tryk på kortet eller træk markøren for at flytte punktet")
        }
    }

    /**
     * Rename waypoint.
     */
    fun renameWaypoint(waypointId: String, newName: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        wps[index] = wps[index].copy(name = newName.ifBlank { null })
        _uiState.value = _uiState.value.copy(waypoints = wps)
        updateRouteStats()
    }

    /**
     * Requirement C: "Vend rute" - Reverses the direction (`isReverse` in TRAIL mode) and recomputes everything.
     */
    fun reverseRoute() {
        val state = _uiState.value
        val wps = state.waypoints
        if (wps.size < 2) return

        pushHistory()
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val newReverse = !state.isReverse
            val totalTrailKm = TrailSlicer.totalTrailKm(state.baseTrailSegments)
            val reversed = wps.asReversed().mapIndexed { idx, wp ->
                val newTrailKm = wp.trailKm?.let { (totalTrailKm - it).coerceIn(0.0, totalTrailKm) }
                wp.copy(trailKm = newTrailKm)
            }.toMutableList()
            normalizeRoles(reversed)
            // Update legModeToNext for reversed sequence: if both endpoints have trailKm, legMode is TRAIL; otherwise SNAP
            for (i in 0 until reversed.size - 1) {
                val from = reversed[i]
                val to = reversed[i + 1]
                val modeToNext = if (from.trailKm != null && to.trailKm != null) LegMode.TRAIL else LegMode.SNAP
                reversed[i] = from.copy(legModeToNext = modeToNext)
            }

            val newMarkers = TrailSlicer.generateKmMarkers(state.baseTrailSegments, stepKm = 5.0, isReverse = newReverse)
            _uiState.value = state.copy(
                isReverse = newReverse,
                baseTrailKmMarkers = newMarkers,
                waypoints = reversed,
                legs = emptyMap(),
                failedLegs = emptyMap()
            )
            recomputeAllLegs()
            return
        }

        val reversed = wps.asReversed().map { it.copy() }.toMutableList()
        normalizeRoles(reversed)

        _uiState.value = _uiState.value.copy(
            waypoints = reversed,
            legs = emptyMap(),
            failedLegs = emptyMap()
        )

        recomputeAllLegs()
    }

    /**
     * "Ryd" - Clears all waypoints and legs (or resets to full trail in TRAIL mode).
     */
    fun clearRoute() {
        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailId != null) {
            pushHistory()
            initializeTrailMode(state.baseTrailId)
            return
        }

        pushHistory()
        _uiState.value = _uiState.value.copy(
            waypoints = emptyList(),
            legs = emptyMap(),
            loadingLegKeys = emptySet(),
            failedLegs = emptyMap(),
            waitingForStartPointTap = false,
            movingWaypointId = null,
            stats = RouteStats(0.0, 0.0, 0.0, null, 0.0, hasWayInfo = false),
            timeEstimateFormatted = "0 min",
            gpxOriginalPoints = emptyList(),
            alternatives = emptyList(),
            selectedWaypointId = null,
            errorMessage = null,
            plannedStages = emptyList(),
            daySummaries = emptyList(),
            dayBoundaryWalkKms = emptyList()
        )
    }

    /**
     * "Fra min position" - Sets START at current GPS fix.
     */
    fun setStartAtGps(lat: Double, lng: Double) {
        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
            if (proj != null) {
                val startWp = state.waypoints.firstOrNull { it.role == WaypointRole.START }
                if (startWp != null) {
                    moveWaypointOnTrailToKm(startWp.id, proj.trailKm, finishDrag = true)
                    return
                }
            }
        }

        pushHistory()
        val wps = _uiState.value.waypoints.toMutableList()
        val startWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = WaypointRole.START,
            name = "Min position"
        )

        if (wps.isEmpty()) {
            wps.add(startWp)
        } else {
            if (wps.first().role == WaypointRole.START) {
                wps[0] = startWp
            } else {
                wps.add(0, startWp)
            }
        }
        normalizeRoles(wps)
        _uiState.value = _uiState.value.copy(
            waypoints = wps,
            waitingForStartPointTap = false,
            errorMessage = null
        )

        if (wps.size >= 2) {
            computeSingleLeg(wps[0], wps[1])
        } else {
            updateRouteStats()
        }
    }

    fun selectWaypoint(waypointId: String?) {
        _uiState.value = _uiState.value.copy(selectedWaypointId = waypointId)
    }

    fun setScrubbedPoint(point: RoutePoint3D?) {
        _uiState.value = _uiState.value.copy(scrubbedPointOnMap = point)
    }

    fun updateRouteName(name: String) {
        _uiState.value = _uiState.value.copy(routeName = name)
    }

    fun updateOptions(newOptions: RouteOptions) {
        if (_uiState.value.options == newOptions) return
        pushHistory()
        _uiState.value = _uiState.value.copy(options = newOptions)
        recomputeAllLegs()
    }

    // --- Trip Settings Sheet ("Indstillinger for turen") (Requirement A) ---

    fun setSheetHeightState(state: SheetHeightState) {
        _uiState.value = _uiState.value.copy(sheetHeightState = state)
    }

    fun openTripSettingsSheet() {
        _uiState.value = _uiState.value.copy(showTripSettingsSheet = true)
    }

    fun dismissTripSettingsSheet() {
        _uiState.value = _uiState.value.copy(showTripSettingsSheet = false)
    }

    fun updateTripSettings(
        paceKmh: Double,
        dailyTargetKm: Double,
        maxDetourKm: Double,
        startHour: Int,
        startDateEpochMs: Long,
        overnightPref: String
    ) {
        val parsedPref = try {
            OvernightType.valueOf(overnightPref)
        } catch (_: Exception) {
            OvernightType.BOTH
        }
        val formattedTime = String.format(Locale.US, "%02d:00", startHour.coerceIn(0, 23))
        updateTripSettings(
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            startDateMillis = startDateEpochMs,
            startTimeStr = formattedTime,
            maxDetourKm = maxDetourKm,
            overnightPreference = parsedPref
        )
    }

    fun updateTripSettings(
        paceKmh: Double,
        dailyTargetKm: Double,
        startDateMillis: Long,
        startTimeStr: String,
        maxDetourKm: Double,
        overnightPreference: OvernightType = _uiState.value.overnightPreference
    ) {
        pacingSpeedKmh = paceKmh.coerceIn(2.0, 8.0)
        this.startTimeStr = startTimeStr
        _uiState.value = _uiState.value.copy(
            paceKmh = pacingSpeedKmh,
            dailyTargetKm = dailyTargetKm.coerceIn(5.0, 50.0),
            startDateMillis = startDateMillis,
            startTimeStr = startTimeStr,
            maxDetourKm = maxDetourKm.coerceIn(0.2, 10.0),
            overnightPreference = overnightPreference,
            showTripSettingsSheet = false
        )
        viewModelScope.launch {
            settingsRepository.setMitTempoKmh(pacingSpeedKmh)
            settingsRepository.setStartTime(startTimeStr)
            settingsRepository.setMaxDetourKm(maxDetourKm.coerceIn(0.2, 10.0))
            settingsRepository.setDailyTargetKm(dailyTargetKm.coerceIn(5.0, 50.0))
        }
        nightWeatherCache.clear()
        updateRouteStats()
    }

    /**
     * Computes a single leg between two waypoints.
     * Requirement C: Legs between two pins that both have trailKm are TRAIL legs:
     * their geometry is cut from the stored trail geometry between the two km values,
     * respecting segment boundaries (FERRY segments stay FERRY, gaps stay gaps). No routing call!
     */
    private fun computeSingleLeg(from: RouteWaypoint, to: RouteWaypoint) {
        val legKey = "${from.id}_to_${to.id}"
        val state = _uiState.value

        // Requirement C: If both pins have trailKm and we have baseTrailSegments, slice TRAIL leg immediately!
        if (from.trailKm != null && to.trailKm != null && state.baseTrailSegments.isNotEmpty()) {
            val trailLeg = TrailSlicer.buildTrailLegGeometry(
                fromWaypointId = from.id,
                toWaypointId = to.id,
                segments = state.baseTrailSegments,
                fromKm = from.trailKm,
                toKm = to.trailKm,
                isReverse = state.isReverse
            )
            val updatedLegs = _uiState.value.legs.toMutableMap()
            updatedLegs[legKey] = trailLeg
            val updatedFailed = _uiState.value.failedLegs.toMutableMap()
            updatedFailed.remove(legKey)
            val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
            updatedLoading.remove(legKey)

            _uiState.value = _uiState.value.copy(
                legs = updatedLegs,
                failedLegs = updatedFailed,
                loadingLegKeys = updatedLoading
            )
            viewModelScope.launch(Dispatchers.IO) {
                RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, trailLeg)
            }
            updateRouteStats()
            return
        }

        val legMode = from.legModeToNext

        if (legMode == LegMode.STRAIGHT) {
            val currentLoading = _uiState.value.loadingLegKeys.toMutableSet()
            currentLoading.add(legKey)
            val updatedFailed = _uiState.value.failedLegs.toMutableMap()
            updatedFailed.remove(legKey)
            _uiState.value = _uiState.value.copy(
                loadingLegKeys = currentLoading,
                failedLegs = updatedFailed
            )

            viewModelScope.launch {
                val (fromEle, toEle) = withContext(Dispatchers.IO) {
                    try {
                        val resp = weatherService.getElevations(
                            latitudes = "${from.lat},${to.lat}",
                            longitudes = "${from.lng},${to.lng}"
                        )
                        val list = resp.elevation
                        if (list != null && list.size >= 2) {
                            Pair(list[0], list[1])
                        } else {
                            Pair(null, null)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Open-Meteo elevation lookup failed for straight leg: ${e.message}")
                        Pair(null, null)
                    }
                }

                val pts = listOf(
                    RoutePoint3D(from.lat, from.lng, fromEle),
                    RoutePoint3D(to.lat, to.lng, toEle)
                )
                val distKm = haversineM(from.lat, from.lng, to.lat, to.lng) / 1000.0
                val ascent = if (fromEle != null && toEle != null && toEle > fromEle) toEle - fromEle else 0.0
                val descent = if (fromEle != null && toEle != null && fromEle > toEle) fromEle - toEle else 0.0

                val straightLeg = LegGeometryData(
                    fromWaypointId = from.id,
                    toWaypointId = to.id,
                    legMode = LegMode.STRAIGHT,
                    points = pts,
                    lengthKm = distKm,
                    ascentM = ascent,
                    descentM = descent,
                    pathSharePct = null,
                    roadKm = 0.0,
                    hasAccessWarning = false,
                    sourceName = "Straight",
                    wayInfo = emptyList()
                )

                val doneLoading = _uiState.value.loadingLegKeys.toMutableSet()
                doneLoading.remove(legKey)
                val updatedLegs = _uiState.value.legs.toMutableMap()
                updatedLegs[legKey] = straightLeg
                _uiState.value = _uiState.value.copy(
                    legs = updatedLegs,
                    loadingLegKeys = doneLoading,
                    isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse()
                )
                withContext(Dispatchers.IO) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, straightLeg)
                }
                updateRouteStats()
            }
            return
        }

        // Snap mode: Route with RoutingEngine
        val currentLoading = _uiState.value.loadingLegKeys.toMutableSet()
        currentLoading.add(legKey)
        _uiState.value = _uiState.value.copy(loadingLegKeys = currentLoading)

        viewModelScope.launch {
            try {
                val fromCoord = LatLng(from.lat, from.lng)
                val toCoord = LatLng(to.lat, to.lng)
                val options = _uiState.value.options

                val result = routingEngine.routeLeg(fromCoord, toCoord, options, alternative = 0)

                val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
                updatedLoading.remove(legKey)

                when (result) {
                    is RouteLegResult.Success -> {
                        val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                            result.points,
                            result.perPointWayInfo
                        )

                        val legData = LegGeometryData(
                            fromWaypointId = from.id,
                            toWaypointId = to.id,
                            legMode = LegMode.SNAP,
                            points = result.points,
                            lengthKm = result.lengthKm,
                            ascentM = result.ascentM,
                            descentM = result.descentM,
                            pathSharePct = pathShare,
                            roadKm = roadKm,
                            hasAccessWarning = false,
                            sourceName = result.sourceName,
                            fetchedAt = result.fetchedAt,
                            wayInfo = result.perPointWayInfo
                        )

                        val updatedLegs = _uiState.value.legs.toMutableMap()
                        updatedLegs[legKey] = legData
                        val updatedFailed = _uiState.value.failedLegs.toMutableMap()
                        updatedFailed.remove(legKey)

                        _uiState.value = _uiState.value.copy(
                            legs = updatedLegs,
                            failedLegs = updatedFailed,
                            loadingLegKeys = updatedLoading,
                            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse(),
                            errorMessage = if (updatedFailed.isEmpty()) null else _uiState.value.errorMessage
                        )

                        withContext(Dispatchers.IO) {
                            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, legData)
                        }

                        updateRouteStats()
                    }
                    is RouteLegResult.ServerBusy -> {
                        recordFailedLeg(legKey, result.message, updatedLoading)
                    }
                    is RouteLegResult.Offline -> {
                        recordFailedLeg(legKey, result.message, updatedLoading)
                    }
                    is RouteLegResult.NoRoute -> {
                        recordFailedLeg(legKey, result.message, updatedLoading)
                    }
                    is RouteLegResult.Failure -> {
                        recordFailedLeg(legKey, result.message, updatedLoading)
                    }
                }
            } catch (e: Exception) {
                val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
                updatedLoading.remove(legKey)
                val daMsg = BRouterOnlineEngine.translateBRouterError(e.message)
                recordFailedLeg(legKey, daMsg, updatedLoading)
            }
        }
    }

    private suspend fun recordFailedLeg(
        legKey: String,
        danishMessage: String,
        updatedLoading: Set<String>
    ) {
        val updatedLegs = _uiState.value.legs.toMutableMap()
        updatedLegs.remove(legKey)
        val updatedFailed = _uiState.value.failedLegs.toMutableMap()
        updatedFailed[legKey] = danishMessage

        _uiState.value = _uiState.value.copy(
            legs = updatedLegs,
            failedLegs = updatedFailed,
            loadingLegKeys = updatedLoading,
            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse(),
            errorMessage = danishMessage
        )
        _userMessage.emit(danishMessage)
        updateRouteStats()
    }

    private fun recomputeAllLegs() {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        for (i in 0 until wps.size - 1) {
            computeSingleLeg(wps[i], wps[i + 1])
        }
    }

    private fun updateRouteStats(transform: ((RouteBuilderState) -> RouteBuilderState)? = null) {
        val currentState = transform?.invoke(_uiState.value) ?: _uiState.value
        val wps = currentState.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        val dayBoundaries = mutableListOf<Double>()
        var cumulativeKm = 0.0
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            val leg = currentState.legs[key]
            if (leg != null) {
                orderedLegs.add(leg)
                cumulativeKm += leg.lengthKm
            }
            if (wps[i + 1].role == WaypointRole.OVERNIGHT && !wps[i + 1].isHelper) {
                dayBoundaries.add(cumulativeKm)
            }
        }

        val stats = RouteGeometryStorageManager.calculateStats(orderedLegs)

        val speed = if (pacingSpeedKmh > 0.5) pacingSpeedKmh else 4.5
        val travelHours = (stats.totalKm / speed) + (stats.ascentM / 10.0 / 60.0)
        val totalMinutes = (travelHours * 60).roundToInt()

        val timeStr = if (totalMinutes >= 60) {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            "ca. $h t $m min"
        } else {
            "ca. $totalMinutes min"
        }

        val isOffline = if (currentState.mode == PlannerMode.TRAIL && orderedLegs.all { it.legMode == LegMode.TRAIL }) {
            true
        } else if (orderedLegs.isNotEmpty()) {
            orderedLegs.all { it.sourceName.contains("offline", ignoreCase = true) || it.legMode == LegMode.TRAIL }
        } else if (wps.size >= 2) {
            (0 until wps.size - 1).all { i ->
                brouterStorageManager.areSegmentsAvailableForLeg(
                    LatLng(wps[i].lat, wps[i].lng),
                    LatLng(wps[i + 1].lat, wps[i + 1].lng)
                )
            }
        } else {
            brouterStorageManager.areAllDenmarkSegmentsDownloaded()
        }

        val engineLabel = if (isOffline) "Offline rute" else "Online rute"

        val quality = RouteSmartTools.analyzeRouteQuality(orderedLegs)
        val (syncStages, syncDaySummaries) = if (wps.size >= 2 && orderedLegs.isNotEmpty()) {
            val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
            val numDays = max(1, overnightWps.size + 1)
            val (builtStages, _) = PlannedRouteRepository.buildStagesAndPlannedRoute(
                planId = 0L,
                trailId = currentState.baseTrailId ?: "route_preview",
                trailName = currentState.routeName.ifBlank { "Tur" },
                isReverse = currentState.isReverse,
                waypoints = wps,
                orderedLegs = orderedLegs,
                paceKmh = currentState.paceKmh,
                dailyTargetKm = currentState.dailyTargetKm,
                numDays = numDays,
                overnightType = currentState.overnightPreference,
                maxDetourKm = currentState.maxDetourKm,
                startTime = currentState.startTimeStr,
                shelters = currentState.nearbyShelters,
                friTeltnings = currentState.nearbyFriTeltning,
                pois = currentState.nearbyPois,
                sunsetTime = sunsetTimeStr
            )
            val summaries = buildDaySummariesSync(
                state = currentState,
                wps = wps,
                stages = builtStages,
                corridorShelters = currentState.nearbyShelters,
                corridorFriTeltning = currentState.nearbyFriTeltning
            )
            builtStages.map { it.toEntity(planId = 0L) } to summaries
        } else {
            emptyList<TripStageEntity>() to emptyList<PlannerDaySummary>()
        }

        _uiState.value = currentState.copy(
            stats = stats,
            timeEstimateFormatted = timeStr,
            routeQuality = quality,
            dayBoundaryWalkKms = dayBoundaries,
            plannedStages = syncStages,
            daySummaries = syncDaySummaries,
            isOfflineRoute = isOffline,
            routeEngineModeLabel = engineLabel,
            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse()
        )
        loadAccessPolygonsForRouteCorridor()
        evaluateAccessRules()
        analyzeSupplyGaps()
        updateStagesAndNearbyPois()
    }

    /**
     * Requirement 9: Load access polygons in tiles of 0.1 x 0.1 degrees for the route corridor
     * (route bounding box + 1 km) after each route change.
     */
    private fun loadAccessPolygonsForRouteCorridor() {
        val wps = _uiState.value.waypoints
        val allPts = mutableListOf<RoutePoint3D>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.points?.let { allPts.addAll(it) }
        }
        if (allPts.isEmpty()) {
            wps.forEach { allPts.add(RoutePoint3D(it.lat, it.lng)) }
        }
        if (allPts.isEmpty()) return

        // 1 km buffer (~0.009 deg lat, ~0.016 deg lng in Denmark)
        val minLat = allPts.minOf { it.lat } - 0.009
        val maxLat = allPts.maxOf { it.lat } + 0.009
        val minLng = allPts.minOf { it.lng } - 0.016
        val maxLng = allPts.maxOf { it.lng } + 0.016

        corridorAccessJob?.cancel()
        corridorAccessJob = viewModelScope.launch {
            try {
                val corridorPolys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                val merged = (_uiState.value.accessPolygons + corridorPolys).distinctBy { it.id }
                _uiState.value = _uiState.value.copy(accessPolygons = merged)
                evaluateAccessRules()
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading corridor access polygons: ${e.message}")
            }
        }
    }

    /**
     * Requirement 9: Load access polygons in tiles of 0.1 x 0.1 degrees for the visible map area
     * when the Adgang layer is on and zoom >= 11 (debounced 600 ms after the map stops moving).
     */
    fun onMapCameraIdle(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        zoom: Double,
        isMapLayerAccessEnabled: Boolean = true
    ) {
        mapCameraAccessJob?.cancel()
        if (!_uiState.value.isAccessLayerVisible || !isMapLayerAccessEnabled || zoom < 11.0) return

        mapCameraAccessJob = viewModelScope.launch {
            delay(600L)
            try {
                val visiblePolys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                val merged = (_uiState.value.accessPolygons + visiblePolys).distinctBy { it.id }
                _uiState.value = _uiState.value.copy(accessPolygons = merged)
                evaluateAccessRules()
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading visible map access polygons: ${e.message}")
            }
        }
    }

    /**
     * "Sammenlign ruter": Fetches alternative routes 1-3.
     */
    fun fetchAlternatives() {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        _uiState.value = _uiState.value.copy(isComparingAlternatives = true)

        viewModelScope.launch {
            val altList = mutableListOf<RouteAlternativeInfo>()
            val fromCoord = LatLng(wps.first().lat, wps.first().lng)
            val toCoord = LatLng(wps.last().lat, wps.last().lng)

            for (altIdx in 1..3) {
                when (val res = routingEngine.routeLeg(fromCoord, toCoord, _uiState.value.options, alternative = altIdx)) {
                    is RouteLegResult.Success -> {
                        val (share, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                            res.points,
                            res.perPointWayInfo
                        )

                        altList.add(
                            RouteAlternativeInfo(
                                index = altIdx,
                                lengthKm = res.lengthKm,
                                ascentM = res.ascentM,
                                pathSharePct = share,
                                roadKm = roadKm,
                                points = res.points,
                                wayInfo = res.perPointWayInfo
                            )
                        )
                    }
                    else -> {
                        // Alternative not available, skip
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                alternatives = altList,
                isComparingAlternatives = false
            )
        }
    }

    /**
     * User picks an alternative route to replace the current route.
     */
    fun selectAlternative(alt: RouteAlternativeInfo) {
        pushHistory()
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        val startWp = wps.first()
        val endWp = wps.last()

        val newLeg = LegGeometryData(
            fromWaypointId = startWp.id,
            toWaypointId = endWp.id,
            legMode = LegMode.SNAP,
            points = alt.points,
            lengthKm = alt.lengthKm,
            ascentM = alt.ascentM,
            descentM = 0.0,
            pathSharePct = alt.pathSharePct,
            roadKm = alt.roadKm ?: 0.0,
            sourceName = "BRouter Alt ${alt.index}",
            wayInfo = alt.wayInfo
        )

        val updatedWps = listOf(startWp, endWp)
        val updatedLegs = mapOf(newLeg.legKey to newLeg)

        _uiState.value = _uiState.value.copy(
            waypoints = updatedWps,
            legs = updatedLegs,
            failedLegs = emptyMap(),
            alternatives = emptyList()
        )

        viewModelScope.launch(Dispatchers.IO) {
            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
        }
        updateRouteStats()
    }

    /**
     * GPX Import (Requirement F):
     * Parses .gpx, simplifies to ~1 point per 500m, sets original points for comparison, routes with SNAP.
     */
    fun importGpx(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    GpxImporter.parseAndSimplifyGpx(inputStream, targetSpacingMeters = 500.0)
                }

                if (result.waypoints.size < 2) {
                    _userMessage.emit("Kunne ikke indlæse rute fra GPX: For få punkter")
                    return@launch
                }

                pushHistory()
                val suggestedName = result.name ?: "Importeret rute (${String.format(Locale.US, "%.1f", result.totalOriginalKm)} km)"

                _uiState.value = _uiState.value.copy(
                    routeName = suggestedName,
                    waypoints = result.waypoints,
                    legs = emptyMap(),
                    failedLegs = emptyMap(),
                    gpxOriginalPoints = result.originalPoints
                )

                _userMessage.emit("GPX indlæst: ${result.waypoints.size} ruteelementer. Beregner stier...")
                recomputeAllLegs()
            } catch (e: Exception) {
                Log.e(TAG, "Error importing GPX: ${e.message}")
                _userMessage.emit("Fejl ved GPX-import: ${e.message}")
            }
        }
    }

    /**
     * Requirement G: Gem builds the TripPlan, the stages (from OVERNIGHT pins) and the PlannedRoute
     * in ONE place (PlannedRouteRepository) from the composed legs.
     * Navigation, offline download, GPX export and recording use that PlannedRoute.
     */
    fun saveRoute(onSuccess: (Long) -> Unit, forceResetProgress: Boolean = false) {
        saveRouteInternal(
            onSuccess = { savedRouteId, _ -> onSuccess(savedRouteId) },
            forceResetProgress = forceResetProgress
        )
    }

    fun saveAndStartNavigation(onNavigateToNavigation: (String) -> Unit) {
        saveRouteInternal(
            onSuccess = { _, navId -> onNavigateToNavigation(navId) },
            forceResetProgress = true
        )
    }

    private fun saveRouteInternal(
        onSuccess: (Long, String) -> Unit,
        forceResetProgress: Boolean = false
    ) {
        val currentState = _uiState.value
        val wps = currentState.waypoints
        if (wps.size < 2) {
            viewModelScope.launch { _userMessage.emit("Tilføj mindst to punkter for at gemme ruten") }
            return
        }

        pendingSaveCallback = { savedId ->
            val navId = _uiState.value.baseTrailId ?: "route_$savedId"
            onSuccess(savedId, navId)
        }
        _uiState.value = _uiState.value.copy(isSaving = true)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val existingId = currentState.routeId
                if (existingId != null && existingId > 0L && !forceResetProgress) {
                    val hasProgress = repository.hasWalkingProgressForCustomRoute(existingId)
                    if (hasProgress) {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            showResetProgressDialog = true
                        )
                        return@launch
                    }
                }

                val startName = wps.first().name ?: "Start"
                val endName = wps.last().name ?: "Slut"
                val finalName = when {
                    currentState.routeName.isNotBlank() -> currentState.routeName
                    currentState.baseTrail != null -> currentState.baseTrail.name
                    else -> "$startName - $endName"
                }

                val optionsJson = JSONObject().apply {
                    put("avoidRoads", currentState.options.avoidRoads)
                    put("preferNature", currentState.options.preferNature)
                    put("allowFerries", currentState.options.allowFerries)
                }.toString()

                val waypointsJson = RouteWaypoint.listToJson(wps)
                val now = System.currentTimeMillis()

                val entity = CustomRouteEntity(
                    id = existingId ?: 0L,
                    name = finalName,
                    createdAt = now,
                    updatedAt = now,
                    optionsJson = optionsJson,
                    waypointsJson = waypointsJson,
                    totalKm = currentState.stats.totalKm,
                    ascentM = currentState.stats.ascentM,
                    descentM = currentState.stats.descentM,
                    pathSharePct = currentState.stats.pathSharePct ?: 0.0,
                    roadKm = currentState.stats.roadKm ?: 0.0,
                    geometryFile = geometryFileName,
                    sourceName = "Ruteplanlægger",
                    fetchedAt = now,
                    baseTrailId = currentState.baseTrailId,
                    isReverse = currentState.isReverse
                )

                // Ensure all active legs are written to geometryFileName
                val orderedLegs = mutableListOf<LegGeometryData>()
                for (i in 0 until wps.size - 1) {
                    val key = "${wps[i].id}_to_${wps[i + 1].id}"
                    currentState.legs[key]?.let { orderedLegs.add(it) }
                }
                withContext(Dispatchers.IO) {
                    for (leg in orderedLegs) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                    }
                }

                val savedId = repository.saveCustomRoute(entity)
                val savedEntity = entity.copy(id = savedId)

                // Build stages and PlannedRoute in ONE place (PlannedRouteRepository) and save TripPlan
                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningList()
                val pois = repository.getAllPoisList()
                val numDays = max(1, wps.count { it.role == WaypointRole.OVERNIGHT && !it.isHelper } + 1)

                val (stages, builtPlannedRoute) = PlannedRouteRepository.buildStagesAndPlannedRoute(
                    planId = 0L,
                    trailId = currentState.baseTrailId ?: "route_$savedId",
                    trailName = finalName,
                    isReverse = currentState.isReverse,
                    waypoints = wps,
                    orderedLegs = orderedLegs,
                    paceKmh = currentState.paceKmh,
                    dailyTargetKm = currentState.dailyTargetKm,
                    numDays = numDays,
                    overnightType = currentState.overnightPreference,
                    maxDetourKm = currentState.maxDetourKm,
                    startTime = currentState.startTimeStr,
                    shelters = shelters,
                    friTeltnings = friTeltning,
                    pois = pois,
                    sunsetTime = sunsetTimeStr
                )

                val planTrailKey = currentState.baseTrailId ?: "route_$savedId"
                val startKm = wps.firstOrNull()?.trailKm ?: 0.0
                val endKm = wps.lastOrNull()?.trailKm ?: currentState.stats.totalKm
                val savedPlanId = repository.saveTripPlanForRoute(
                    routeId = planTrailKey,
                    routeName = finalName,
                    totalKm = currentState.stats.totalKm,
                    days = stages.size.coerceAtLeast(1),
                    overnightType = currentState.overnightPreference,
                    stages = stages,
                    customRouteId = savedId,
                    baseTrailId = currentState.baseTrailId,
                    isReverse = currentState.isReverse,
                    startKm = startKm,
                    endKm = endKm,
                    existingPlanId = currentState.existingPlanId
                )
                PlannedRouteRepository(getApplication()).savePlannedRoute(
                    builtPlannedRoute.copy(planId = savedPlanId, stages = stages)
                )

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    showResetProgressDialog = false,
                    routeId = savedId,
                    existingPlanId = savedPlanId
                )
                withContext(Dispatchers.Main) {
                    _userMessage.emit("Turen er gemt under Gemte")
                    onSuccess(savedId, savedPlanId.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save route/plan: ${e.message}")
                _uiState.value = _uiState.value.copy(isSaving = false)
                withContext(Dispatchers.Main) {
                    _userMessage.emit("Fejl ved gemning af tur: ${e.message}")
                }
            }
        }
    }

    fun confirmResetProgressAndSave() {
        _uiState.value = _uiState.value.copy(showResetProgressDialog = false)
        val callback = pendingSaveCallback ?: {}
        saveRoute(onSuccess = callback, forceResetProgress = true)
    }

    fun dismissResetProgressDialog() {
        _uiState.value = _uiState.value.copy(showResetProgressDialog = false, isSaving = false)
    }

    /**
     * Requirement E: "Foreslå overnatninger" runs the stage planner on the current route
     * (one StagePlanner function for both modes) and inserts suggested OVERNIGHT pins as detours where needed.
     * The user can drag, replace ("Skift overnatning" lists candidates within max detour) or delete them.
     */
    fun suggestOvernightStops() {
        val currentState = _uiState.value
        val wps = currentState.waypoints
        if (wps.size < 2) {
            viewModelScope.launch { _userMessage.emit("Tilføj mindst to punkter for at foreslå overnatninger") }
            return
        }

        _uiState.value = _uiState.value.copy(isFindingOvernight = true)

        viewModelScope.launch {
            try {
                pushHistory()

                // 1. Strip existing OVERNIGHT pins (and their helper waypoints in TRAIL mode) so we plan cleanly on the current route
                var cleanWps = currentState.waypoints.toList()
                var cleanLegs = currentState.legs.toMap()
                val existingOvernightIds = cleanWps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }.map { it.id }

                if (currentState.mode == PlannerMode.TRAIL && currentState.baseTrailSegments.isNotEmpty()) {
                    for (ovId in existingOvernightIds) {
                        val (nextWps, nextLegs) = TrailSlicer.removeDetourAndRestoreTrailLeg(
                            waypoints = cleanWps,
                            legs = cleanLegs,
                            removedWaypointId = ovId,
                            segments = currentState.baseTrailSegments,
                            isReverse = currentState.isReverse
                        )
                        cleanWps = nextWps
                        cleanLegs = nextLegs
                    }
                } else {
                    cleanWps = cleanWps.filter { it.role != WaypointRole.OVERNIGHT }
                }

                // Build ordered legs for the clean route
                val orderedLegs = mutableListOf<LegGeometryData>()
                for (i in 0 until cleanWps.size - 1) {
                    val key = "${cleanWps[i].id}_to_${cleanWps[i + 1].id}"
                    val leg = cleanLegs[key]
                    if (leg != null) {
                        orderedLegs.add(leg)
                    } else if (currentState.mode == PlannerMode.TRAIL && cleanWps[i].trailKm != null && cleanWps[i + 1].trailKm != null) {
                        val sliced = TrailSlicer.buildTrailLegGeometry(
                            fromWaypointId = cleanWps[i].id,
                            toWaypointId = cleanWps[i + 1].id,
                            segments = currentState.baseTrailSegments,
                            fromKm = cleanWps[i].trailKm!!,
                            toKm = cleanWps[i + 1].trailKm!!,
                            isReverse = currentState.isReverse
                        )
                        orderedLegs.add(sliced)
                    }
                }

                val basePlannedRoute = PlannedRouteRepository.buildFromComposedLegs(
                    planId = 0L,
                    trailId = currentState.baseTrailId ?: "route_preview",
                    trailName = currentState.routeName.ifBlank { "Tur" },
                    isReverse = currentState.isReverse,
                    waypoints = cleanWps,
                    orderedLegs = orderedLegs,
                    stages = emptyList()
                )

                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningList()
                val pois = repository.getAllPoisList()

                val targetKm = currentState.dailyTargetKm.coerceAtLeast(5.0)
                val autoNumDays = max(2, kotlin.math.ceil(basePlannedRoute.totalWalkKm / targetKm).toInt())

                val suggestedStages = StagePlanner.calculateStages(
                    plannedRoute = basePlannedRoute,
                    routeName = currentState.routeName.ifBlank { "Tur" },
                    waypoints = cleanWps,
                    overnightWaypoints = emptyList(),
                    paceKmh = currentState.paceKmh,
                    dailyTargetKm = targetKm,
                    numDays = autoNumDays,
                    overnightType = currentState.overnightPreference,
                    maxDetourKm = currentState.maxDetourKm,
                    startTime = currentState.startTimeStr,
                    shelters = shelters,
                    friTeltnings = friTeltning,
                    pois = pois,
                    sunsetTime = sunsetTimeStr
                )

                // Reset state to cleanWps/cleanLegs before inserting the suggested OVERNIGHT stops
                _uiState.value = _uiState.value.copy(
                    waypoints = cleanWps,
                    legs = cleanLegs
                )

                // Insert OVERNIGHT pins for stages 1..(n-1)
                val intermediateStages = suggestedStages.dropLast(1)
                for (stage in intermediateStages) {
                    if (stage.stopType != "NONE" && stage.stopType.isNotBlank() && stage.spotId != null) {
                        addStopOrDetourSuspend(
                            lat = stage.stopLat,
                            lng = stage.stopLng,
                            role = WaypointRole.OVERNIGHT,
                            name = stage.stopName,
                            linkedPoiId = stage.spotId
                        )
                    } else {
                        // Place an on-route OVERNIGHT pin at stage.endKm
                        val ptAtEnd = basePlannedRoute.points.minByOrNull { abs(it.walkKm - stage.endKm) }
                        if (ptAtEnd != null) {
                            if (currentState.mode == PlannerMode.TRAIL && currentState.baseTrailSegments.isNotEmpty()) {
                                val proj = TrailSlicer.projectOntoTrail(
                                    currentState.baseTrailSegments,
                                    ptAtEnd.lat,
                                    ptAtEnd.lng,
                                    currentState.isReverse
                                )
                                if (proj != null) {
                                    insertWaypointOnTrailAtKm(
                                        trailKm = proj.trailKm,
                                        role = WaypointRole.OVERNIGHT,
                                        name = String.format(Locale.GERMAN, "Etapeslut Dag %d (km %.1f)", stage.dayNumber, proj.trailKm)
                                    )
                                }
                            } else {
                                addStopOrDetourSuspend(
                                    lat = ptAtEnd.lat,
                                    lng = ptAtEnd.lng,
                                    role = WaypointRole.OVERNIGHT,
                                    name = "Etapeslut Dag ${stage.dayNumber}",
                                    linkedPoiId = null
                                )
                            }
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(isFindingOvernight = false)
                _userMessage.emit("${intermediateStages.size} overnatninger foreslået (${intermediateStages.size + 1} dage)")
                updateRouteStats()
            } catch (e: Exception) {
                Log.e(TAG, "Failed suggesting overnights: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isFindingOvernight = false)
                _userMessage.emit("Kunne ikke foreslå overnatninger: ${e.message}")
            }
        }
    }

    private suspend fun addStopOrDetourSuspend(
        lat: Double,
        lng: Double,
        role: WaypointRole,
        name: String?,
        linkedPoiId: String?
    ) {
        val state = _uiState.value
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
            if (proj != null && proj.distanceMeters <= 50.0 && linkedPoiId == null) {
                insertWaypointOnTrailAtKm(
                    trailKm = proj.trailKm,
                    role = role,
                    name = name
                )
                return
            }

            val wps = _uiState.value.waypoints.toMutableList()
            val segments = _uiState.value.baseTrailSegments
            val isRev = _uiState.value.isReverse
            val projKm = proj?.trailKm ?: (TrailSlicer.totalTrailKm(segments) / 2.0)

            var prevTrailIdx = 0
            var nextTrailIdx = wps.lastIndex
            for (i in wps.indices) {
                val km = wps[i].trailKm ?: continue
                if (km <= projKm) prevTrailIdx = i
                if (km >= projKm && i > prevTrailIdx) {
                    nextTrailIdx = i
                    break
                }
            }
            if (nextTrailIdx <= prevTrailIdx && wps.size >= 2) {
                prevTrailIdx = max(0, wps.lastIndex - 1)
                nextTrailIdx = wps.lastIndex
            }

            val prevTrailWp = wps[prevTrailIdx]
            val nextTrailWp = wps[nextTrailIdx]

            val rawPin = RouteWaypoint(
                lat = lat,
                lng = lng,
                role = role,
                legModeToNext = LegMode.SNAP,
                name = name,
                linkedPoiId = linkedPoiId,
                trailKm = null
            )

            val detourPlan = TrailSlicer.buildTrailDetour(
                segments = segments,
                prevWp = prevTrailWp,
                nextWp = nextTrailWp,
                pinWp = rawPin,
                routingEngine = routingEngine,
                options = _uiState.value.options,
                isReverse = isRev
            )

            val updatedLegs = _uiState.value.legs.toMutableMap()
            updatedLegs.remove("${prevTrailWp.id}_to_${nextTrailWp.id}")

            wps[prevTrailIdx] = prevTrailWp.copy(legModeToNext = LegMode.TRAIL)
            wps.addAll(
                prevTrailIdx + 1,
                listOf(detourPlan.exitHelper, detourPlan.detourWaypoint, detourPlan.rejoinHelper)
            )
            normalizeRoles(wps)

            for (leg in detourPlan.legs) {
                updatedLegs[leg.legKey] = leg
            }

            val mergedFailed = _uiState.value.failedLegs.toMutableMap().apply {
                putAll(detourPlan.failedLegs)
            }
            _uiState.value = _uiState.value.copy(
                waypoints = wps,
                legs = updatedLegs,
                failedLegs = mergedFailed,
                errorMessage = detourPlan.failedLegs.values.firstOrNull() ?: _uiState.value.errorMessage
            )
            withContext(Dispatchers.IO) {
                for (leg in detourPlan.legs) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                }
            }
            return
        }

        // FREE mode
        val cand = OvernightCandidate(
            id = linkedPoiId ?: UUID.randomUUID().toString(),
            name = name ?: "Overnatning",
            type = "SHELTER",
            lat = lat,
            lng = lng,
            walkKm = 0.0,
            detourMeters = 0.0,
            bookingRequired = false,
            hasWaterNearby = false,
            hasToiletNearby = false
        )
        insertOvernightCandidate(cand)
    }

    /**
     * Requirement E: "Skift overnatning" lists candidates within max detour for an existing OVERNIGHT pin.
     */
    fun openReplaceOvernightDialog(waypointId: String) {
        openReplaceOvernightSheet(waypointId)
    }

    fun dismissReplaceOvernightDialog() {
        dismissReplaceOvernightSheet()
    }

    fun replaceOvernightWaypoint(waypointId: String, candidate: OvernightCandidate) {
        _uiState.value = _uiState.value.copy(replaceOvernightWaypointId = waypointId)
        replaceOvernightWithCandidate(candidate)
    }

    fun replaceOvernightWaypoint(candidate: OvernightCandidate) {
        replaceOvernightWithCandidate(candidate)
    }

    fun openReplaceOvernightSheet(waypointId: String) {
        val state = _uiState.value
        val wp = state.waypoints.find { it.id == waypointId } ?: return
        viewModelScope.launch {
            val shelters = repository.getAllSheltersList()
            val friTeltning = repository.getAllFriTeltningList()
            val maxDetourM = state.maxDetourKm * 1000.0
            val candidates = mutableListOf<OvernightCandidate>()

            for (s in shelters) {
                val distM = haversineM(wp.lat, wp.lng, s.lat, s.lng)
                if (distM <= maxDetourM * 1.5) {
                    candidates.add(
                        OvernightCandidate(
                            id = s.id,
                            name = s.name,
                            type = "SHELTER",
                            lat = s.lat,
                            lng = s.lng,
                            walkKm = wp.trailKm ?: 0.0,
                            detourMeters = distM,
                            bookingRequired = !s.bookingUrl.isNullOrBlank(),
                            hasWaterNearby = s.hasWaterNearby,
                            hasToiletNearby = s.hasToiletNearby,
                            sourceEntity = s
                        )
                    )
                }
            }
            for (ft in friTeltning) {
                val distM = haversineM(wp.lat, wp.lng, ft.centerLat, ft.centerLng)
                if (distM <= maxDetourM * 1.5) {
                    candidates.add(
                        OvernightCandidate(
                            id = ft.id,
                            name = ft.name,
                            type = "FRI_TELTNING",
                            lat = ft.centerLat,
                            lng = ft.centerLng,
                            walkKm = wp.trailKm ?: 0.0,
                            detourMeters = distM,
                            bookingRequired = false,
                            hasWaterNearby = false,
                            hasToiletNearby = false
                        )
                    )
                }
            }

            _uiState.value = _uiState.value.copy(
                replaceOvernightWaypointId = waypointId,
                replaceOvernightCandidates = candidates.sortedBy { it.detourMeters }.take(10)
            )
        }
    }

    fun dismissReplaceOvernightSheet() {
        _uiState.value = _uiState.value.copy(
            replaceOvernightWaypointId = null,
            replaceOvernightCandidates = emptyList()
        )
    }

    fun replaceOvernightWithCandidate(candidate: OvernightCandidate) {
        val oldId = _uiState.value.replaceOvernightWaypointId ?: return
        dismissReplaceOvernightSheet()
        deleteWaypoint(oldId)
        addStopOrDetour(
            lat = candidate.lat,
            lng = candidate.lng,
            role = WaypointRole.OVERNIGHT,
            name = candidate.name,
            linkedPoiId = candidate.id
        )
    }

    /**
     * Requirement F: Dragging an overnight marker on the elevation profile chart moves that marker on the map.
     */
    fun moveOvernightPinByChartWalkKm(waypointId: String, targetWalkKm: Double, finishDrag: Boolean = true) {
        val state = _uiState.value
        val wp = state.waypoints.find { it.id == waypointId } ?: return
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty() && wp.trailKm != null) {
            val startTrailKm = state.waypoints.firstOrNull()?.trailKm ?: 0.0
            val newTrailKm = (startTrailKm + targetWalkKm).coerceIn(0.0, TrailSlicer.totalTrailKm(state.baseTrailSegments))
            moveWaypointOnTrailToKm(waypointId, newTrailKm, finishDrag = finishDrag)
            return
        }

        // FREE mode or detour: find point along composed legs at targetWalkKm
        val wps = state.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            state.legs["${wps[i].id}_to_${wps[i + 1].id}"]?.let { orderedLegs.add(it) }
        }
        val combined = RouteGeometryStorageManager.getCombinedPoints(orderedLegs)
        if (combined.size < 2) return
        val totalKm = state.stats.totalKm.coerceAtLeast(0.1)
        val fraction = (targetWalkKm / totalKm).coerceIn(0.02, 0.98)
        val ptIdx = (fraction * (combined.size - 1)).toInt().coerceIn(0, combined.lastIndex)
        val pt = combined[ptIdx]
        if (finishDrag) {
            moveWaypoint(waypointId, pt.lat, pt.lng)
        } else {
            _uiState.value = state.copy(scrubbedPointOnMap = pt)
        }
    }

    fun findOvernightCandidates(dailyTargetKm: Double = _uiState.value.dailyTargetKm) {
        suggestOvernightStops()
    }

    fun dismissOvernightDialog() {
        _uiState.value = _uiState.value.copy(showOvernightDialog = false)
    }

    fun insertOvernightCandidate(cand: com.example.util.OvernightCandidate) {
        dismissOvernightDialog()
        if (_uiState.value.mode == PlannerMode.TRAIL && _uiState.value.baseTrailSegments.isNotEmpty()) {
            addStopOrDetour(
                lat = cand.lat,
                lng = cand.lng,
                role = WaypointRole.OVERNIGHT,
                name = cand.name,
                linkedPoiId = cand.id
            )
            return
        }

        pushHistory()
        val wps = _uiState.value.waypoints.toMutableList()
        if (wps.size < 2) return

        var bestIndex = -1
        var minDistanceM = Double.MAX_VALUE

        for (i in 0 until wps.size - 1) {
            val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
            val leg = _uiState.value.legs[legKey]
            val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

            for (pt in pts) {
                val d = haversineM(cand.lat, cand.lng, pt.lat, pt.lng)
                if (d < minDistanceM) {
                    minDistanceM = d
                    bestIndex = i + 1
                }
            }
        }

        if (bestIndex == -1) {
            bestIndex = wps.size - 1
        }

        val newWp = RouteWaypoint(
            lat = cand.lat,
            lng = cand.lng,
            role = WaypointRole.OVERNIGHT,
            legModeToNext = LegMode.SNAP,
            name = cand.name,
            linkedPoiId = cand.id
        )

        wps.add(bestIndex, newWp)
        normalizeRoles(wps)
        _uiState.value = _uiState.value.copy(waypoints = wps)

        val prevWp = wps[bestIndex - 1]
        val nextWp = wps[bestIndex + 1]

        val oldKey = "${prevWp.id}_to_${nextWp.id}"
        val updatedLegs = _uiState.value.legs.toMutableMap()
        updatedLegs.remove(oldKey)
        _uiState.value = _uiState.value.copy(legs = updatedLegs)

        computeSingleLeg(prevWp, newWp)
        computeSingleLeg(newWp, nextWp)

        viewModelScope.launch {
            _userMessage.emit("Overnatning indsat: ${cand.name}")
        }
    }

    fun planStages(onNavigateToPlanner: (String) -> Unit) {
        saveAndStartNavigation(onNavigateToPlanner)
    }

    /**
     * Recomputes `plannedStages`, `daySummaries` (Requirement E & F), and nearby map POIs
     * (shelters, fri teltning, water, toilets, shops near the planned section).
     */
    private fun updateStagesAndNearbyPois() {
        val state = _uiState.value
        val wps = state.waypoints
        if (wps.size < 2) {
            _uiState.value = state.copy(
                plannedStages = emptyList(),
                daySummaries = emptyList(),
                nearbyShelters = emptyList(),
                nearbyFriTeltning = emptyList(),
                nearbyPois = emptyList()
            )
            return
        }

        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            state.legs[key]?.let { orderedLegs.add(it) }
        }
        if (orderedLegs.isEmpty()) return

        stagesAndPoisJob?.cancel()
        stagesAndPoisJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val shelters = withContext(Dispatchers.IO) { repository.getAllSheltersList() }
                val friTeltning = withContext(Dispatchers.IO) { repository.getAllFriTeltningList() }
                val allPois = withContext(Dispatchers.IO) { repository.getAllPoisList() }

                val allPts = orderedLegs.flatMap { it.points }
                if (allPts.isEmpty()) return@launch

                // Filter nearby POIs within ~2.5 km of the planned section bounding box & polyline
                val minLat = allPts.minOf { it.lat } - 0.025
                val maxLat = allPts.maxOf { it.lat } + 0.025
                val minLng = allPts.minOf { it.lng } - 0.04
                val maxLng = allPts.maxOf { it.lng } + 0.04
                val samplePts = if (allPts.size > 200) {
                    val step = max(1, allPts.size / 200)
                    allPts.filterIndexed { idx, _ -> idx % step == 0 || idx == allPts.lastIndex }
                } else {
                    allPts
                }

                fun isNearPlannedSection(lat: Double, lng: Double, maxMeters: Double = 2500.0): Boolean {
                    if (lat !in minLat..maxLat || lng !in minLng..maxLng) return false
                    return samplePts.any { pt -> haversineM(lat, lng, pt.lat, pt.lng) <= maxMeters }
                }

                val corridorShelters = shelters.filter { isNearPlannedSection(it.lat, it.lng, 2500.0) }
                val corridorFriTeltning = friTeltning.filter { isNearPlannedSection(it.centerLat, it.centerLng, 2500.0) }
                val corridorPois = allPois.filter { poi ->
                    val t = poi.type.lowercase()
                    (t.contains("water") || t.contains("toilet") || t.contains("shop") || t.contains("supermarket") || t.contains("grocer")) &&
                        isNearPlannedSection(poi.lat, poi.lng, 2000.0)
                }

                val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
                val numDays = max(1, overnightWps.size + 1)

                val (stages, _) = PlannedRouteRepository.buildStagesAndPlannedRoute(
                    planId = 0L,
                    trailId = state.baseTrailId ?: "route_preview",
                    trailName = state.routeName.ifBlank { "Tur" },
                    isReverse = state.isReverse,
                    waypoints = wps,
                    orderedLegs = orderedLegs,
                    paceKmh = state.paceKmh,
                    dailyTargetKm = state.dailyTargetKm,
                    numDays = numDays,
                    overnightType = state.overnightPreference,
                    maxDetourKm = state.maxDetourKm,
                    startTime = state.startTimeStr,
                    shelters = corridorShelters,
                    friTeltnings = corridorFriTeltning,
                    pois = corridorPois,
                    sunsetTime = sunsetTimeStr
                )

                val initialDaySummaries = buildDaySummariesSync(
                    state = _uiState.value,
                    wps = wps,
                    stages = stages,
                    corridorShelters = corridorShelters,
                    corridorFriTeltning = corridorFriTeltning
                )
                val stageEntities = stages.map { it.toEntity(planId = 0L) }

                _uiState.value = _uiState.value.copy(
                    plannedStages = stageEntities,
                    daySummaries = initialDaySummaries,
                    nearbyShelters = corridorShelters,
                    nearbyFriTeltning = corridorFriTeltning,
                    nearbyPois = corridorPois
                )

                // Enrich with night weather asynchronously without delaying daySummaries availability
                val weatherEnrichedSummaries = initialDaySummaries.mapIndexed { idx, summary ->
                    val nightWeather = fetchNightWeatherForDay(
                        dayIndex = idx,
                        startDateMillis = _uiState.value.startDateMillis,
                        lat = summary.campLat,
                        lng = summary.campLng
                    )
                    summary.copy(nightWeatherSummary = nightWeather)
                }
                _uiState.value = _uiState.value.copy(daySummaries = weatherEnrichedSummaries)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating stages and nearby POIs: ${e.message}")
            }
        }
    }

    private fun buildDaySummariesSync(
        state: RouteBuilderUiState,
        wps: List<RouteWaypoint>,
        stages: List<com.example.data.model.TripStage>,
        corridorShelters: List<ShelterEntity>,
        corridorFriTeltning: List<FriTeltningEntity>
    ): List<PlannerDaySummary> {
        val visibleWps = wps.filter { !it.isHelper }
        if (visibleWps.isEmpty()) return emptyList()
        val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
        val dayBoundaryWps = mutableListOf<RouteWaypoint>()
        dayBoundaryWps.add(visibleWps.first())
        dayBoundaryWps.addAll(overnightWps)
        dayBoundaryWps.add(visibleWps.last())

        val daySummaries = mutableListOf<PlannerDaySummary>()
        for ((idx, stage) in stages.withIndex()) {
            val fromWp = dayBoundaryWps.getOrNull(idx)
            val toWp = dayBoundaryWps.getOrNull(idx + 1)
            val isLastDay = idx == stages.lastIndex

            val fromName = fromWp?.name?.ifBlank { null } ?: if (idx == 0) "Start" else "Dag $idx lejr"
            val toName = toWp?.name?.ifBlank { null } ?: stage.stopName.ifBlank { if (isLastDay) "Slut" else "Dag ${stage.dayNumber} lejr" }

            val detourKm = toWp?.detourCostKm ?: (stage.detourMeters / 1000.0)
            val detourAscent = toWp?.detourAscentM?.roundToInt() ?: 0
            val detourLabel = if (detourKm > 0.05) {
                TrailSlicer.formatDetourCost(toName, detourKm, detourAscent.toDouble())
            } else null

            val matchedShelter = corridorShelters.find { it.id == toWp?.linkedPoiId || it.id == stage.spotId }
            val matchedFt = corridorFriTeltning.find { it.id == toWp?.linkedPoiId || it.id == stage.spotId }

            val overnightTypeLabel = when {
                isLastDay -> "Mål"
                matchedShelter != null || stage.stopType == "SHELTER" || toWp?.spotType == "SHELTER" -> "Shelter"
                matchedFt != null || stage.stopType == "FRI_TELTNING" || toWp?.spotType == "FRI_TELTNING" -> "Fri teltning"
                toWp?.role == WaypointRole.OVERNIGHT -> "Overnatningspunkt"
                else -> "Åben etapeslut"
            }

            val bookingLabel = when {
                isLastDay -> null
                matchedShelter != null -> if (!matchedShelter.bookingUrl.isNullOrBlank() || matchedShelter.bookingRequired) "Kræver booking" else "Gratis / først-til-mølle"
                matchedFt != null -> "Gratis (1-2-3 reglen)"
                stage.bookingRequired || toWp?.bookingRequired == true -> "Kræver booking"
                else -> null
            }

            val campLat = toWp?.lat ?: stage.stopLat.takeIf { it != 0.0 } ?: visibleWps.last().lat
            val campLng = toWp?.lng ?: stage.stopLng.takeIf { it != 0.0 } ?: visibleWps.last().lng

            val dayWarnings = mutableListOf<String>()
            stage.warningMessage?.takeIf { it.isNotBlank() }?.let { dayWarnings.add(it) }
            val arrivalTimeStr = if (stage.estimatedWalkMinutes > 0) {
                val startH = state.startHour
                val totalMinutes = startH * 60 + stage.estimatedWalkMinutes
                val h = (totalMinutes / 60) % 24
                val m = totalMinutes % 60
                String.format(Locale.GERMAN, "kl. %02d:%02d", h, m)
            } else "kl. --:--"

            if (stage.isAfterSunset) {
                val sunsetMsg = "Ankomst ($arrivalTimeStr) er efter solnedgang ($sunsetTimeStr)"
                if (dayWarnings.none { it.contains("solnedgang", ignoreCase = true) }) {
                    dayWarnings.add(sunsetMsg)
                }
            }
            state.supplyGapWarnings.forEach { gap ->
                if (gap.endKm > stage.startKm && gap.startKm < stage.endKm) {
                    if (dayWarnings.none { it.contains(gap.displayTitle) }) {
                        dayWarnings.add(gap.displayTitle)
                    }
                }
            }
            state.accessWarnings.forEach { acc ->
                if (dayWarnings.none { it.contains(acc.message) }) {
                    dayWarnings.add(acc.message)
                }
            }

            daySummaries.add(
                PlannerDaySummary(
                    dayNumber = stage.dayNumber,
                    startKm = stage.startKm,
                    endKm = stage.endKm,
                    distanceKm = stage.stageKm,
                    ascentM = stage.ascentMeters.toInt(),
                    descentM = stage.descentMeters.toInt(),
                    fromName = fromName,
                    toName = toName,
                    overnightWaypointId = if (!isLastDay && toWp?.role == WaypointRole.OVERNIGHT) toWp.id else null,
                    overnightName = toName,
                    overnightTypeLabel = overnightTypeLabel,
                    detourLabel = detourLabel,
                    detourKm = detourKm,
                    detourAscentM = detourAscent,
                    bookingLabel = bookingLabel,
                    sunsetTime = sunsetTimeStr,
                    arrivalTime = arrivalTimeStr,
                    isAfterSunset = stage.isAfterSunset,
                    warnings = dayWarnings,
                    nightWeatherSummary = "Vejrudsigt indlæses...",
                    campLat = campLat,
                    campLng = campLng
                )
            )
        }
        return daySummaries
    }

    /**
     * Requirement F: Weather per night (Open-Meteo, only within its forecast range;
     * otherwise "Vejrudsigt ikke tilgængelig endnu").
     */
    private suspend fun fetchNightWeatherForDay(
        dayIndex: Int,
        startDateMillis: Long,
        lat: Double,
        lng: Double
    ): String {
        val targetMillis = startDateMillis + TimeUnit.DAYS.toMillis(dayIndex.toLong())
        val nowMillis = System.currentTimeMillis()
        val diffDays = TimeUnit.MILLISECONDS.toDays(targetMillis - nowMillis)
        // Open-Meteo forecast range is up to 16 days ahead (0..15 days from today)
        if (diffDays < -1 || diffDays > 15) {
            return "Vejrudsigt ikke tilgængelig endnu"
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val targetDateStr = dateFormat.format(Date(targetMillis))
        val cacheKey = "${String.format(Locale.US, "%.2f_%.2f", lat, lng)}_$targetDateStr"
        nightWeatherCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val response = weatherService.getTrailWeather(
                    lat = lat,
                    lon = lng
                )
                val daily = response.daily
                val times = daily?.time ?: emptyList()
                val idx = times.indexOf(targetDateStr)
                if (idx == -1) {
                    "Vejrudsigt ikke tilgængelig endnu"
                } else {
                    val minTemp = daily?.tempMin?.getOrNull(idx)
                    val maxTemp = daily?.tempMax?.getOrNull(idx)
                    val precip = daily?.precipitationSum?.getOrNull(idx) ?: 0.0
                    val wind = daily?.windSpeedMax?.getOrNull(idx)
                    val sunsetRaw = daily?.sunset?.getOrNull(idx)
                    if (!sunsetRaw.isNullOrBlank() && sunsetRaw.contains("T")) {
                        sunsetTimeStr = sunsetRaw.substringAfter("T")
                    }
                    val summary = buildString {
                        if (minTemp != null && maxTemp != null) {
                            append("Nat ${minTemp.roundToInt()}°C / Dag ${maxTemp.roundToInt()}°C")
                        } else if (minTemp != null) {
                            append("Nat ${minTemp.roundToInt()}°C")
                        }
                        append(" · Nedbør ${String.format(Locale.GERMAN, "%.1f", precip)} mm")
                        if (wind != null) {
                            append(" · Vind ${wind.roundToInt()} m/s")
                        }
                    }
                    nightWeatherCache[cacheKey] = summary
                    summary
                }
            } catch (_: Exception) {
                "Vejrudsigt ikke tilgængelig endnu"
            }
        }
    }

    private fun normalizeRoles(wps: MutableList<RouteWaypoint>) {
        if (wps.isEmpty()) return
        if (wps.size == 1) {
            wps[0] = wps[0].copy(role = WaypointRole.START)
            return
        }
        wps[0] = wps[0].copy(role = WaypointRole.START)
        wps[wps.lastIndex] = wps.last().copy(role = WaypointRole.END)
        for (i in 1 until wps.size - 1) {
            if (wps[i].role == WaypointRole.START || wps[i].role == WaypointRole.END) {
                wps[i] = wps[i].copy(role = WaypointRole.VIA)
            }
        }
    }

    private fun parseOptionsJson(json: String): RouteOptions {
        if (json.isBlank()) return RouteOptions()
        return try {
            val obj = JSONObject(json)
            RouteOptions(
                avoidRoads = obj.optDouble("avoidRoads", 0.5),
                preferNature = obj.optBoolean("preferNature", true),
                allowFerries = obj.optBoolean("allowFerries", false)
            )
        } catch (_: Exception) {
            RouteOptions()
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // Phase 10: Access rules & layer methods (Naturbeskyttelsesloven kapitel 4)

    fun toggleAccessLayer() {
        _uiState.value = _uiState.value.copy(isAccessLayerVisible = !_uiState.value.isAccessLayerVisible)
    }

    fun selectAccessPolygon(poly: com.example.data.model.access.AccessPolygon?) {
        _uiState.value = _uiState.value.copy(selectedAccessPolygonForDetails = poly)
    }

    fun loadAccessPolygonsForBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        viewModelScope.launch {
            try {
                val polys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                val merged = (_uiState.value.accessPolygons + polys).distinctBy { it.id }
                _uiState.value = _uiState.value.copy(accessPolygons = merged)
                evaluateAccessRules()
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading access polygons: ${e.message}")
            }
        }
    }

    private fun fetchSunsetForRoute(lat: Double, lng: Double) {
        viewModelScope.launch {
            try {
                val weather = repository.getTrailWeather(lat, lng)
                val sunset = weather.sunset
                if (!sunset.isNullOrBlank()) {
                    sunsetTimeStr = sunset
                    evaluateAccessRules()
                }
            } catch (_: Exception) {}
        }
    }

    fun evaluateAccessRules() {
        val wps = _uiState.value.waypoints
        val legs = _uiState.value.legs
        val polygons = _uiState.value.accessPolygons

        val warnings = com.example.util.access.AccessGeometryUtils.evaluateRouteAccess(
            waypoints = wps,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = startTimeStr,
            pacingSpeedKmh = pacingSpeedKmh,
            sunsetTimeStr = sunsetTimeStr
        )

        // Mark legs that have access warnings so they render in amber (Requirement C)
        val updatedLegs = legs.toMutableMap()
        for ((key, leg) in legs) {
            val hasWarning = warnings.any { w ->
                val loc = w.location
                leg.points.any { pt -> kotlin.math.abs(pt.lat - loc.lat) < 0.005 && kotlin.math.abs(pt.lng - loc.lng) < 0.005 }
            }
            if (leg.hasAccessWarning != hasWarning) {
                updatedLegs[key] = leg.copy(hasAccessWarning = hasWarning)
            }
        }

        _uiState.value = _uiState.value.copy(
            accessWarnings = warnings,
            legs = updatedLegs
        )
    }

    /**
     * "Rut uden om": Re-routes the affected leg with the problem polygon sent as a no-go polygon.
     */
    fun rerouteAroundPolygon(warning: com.example.util.access.RouteAccessWarning) {
        val poly = warning.polygon ?: return
        val wps = _uiState.value.waypoints
        val legIdx = warning.legIndex ?: return
        if (legIdx < 0 || legIdx >= wps.size - 1) return

        val fromWp = wps[legIdx]
        val toWp = wps[legIdx + 1]
        val legKey = "${fromWp.id}_to_${toWp.id}"

        viewModelScope.launch {
            _userMessage.emit("Beregner rute uden om ${poly.name ?: "området"}...")
            val newNoGo = _uiState.value.options.noGoAreas + listOf(poly.coordinates)
            val detourOptions = _uiState.value.options.copy(noGoAreas = newNoGo)

            val fromCoord = LatLng(fromWp.lat, fromWp.lng)
            val toCoord = LatLng(toWp.lat, toWp.lng)

            val result = routingEngine.routeLeg(fromCoord, toCoord, detourOptions, alternative = 0)
            when (result) {
                is RouteLegResult.Success -> {
                    val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                        result.points,
                        result.perPointWayInfo
                    )

                    val newLeg = LegGeometryData(
                        fromWaypointId = fromWp.id,
                        toWaypointId = toWp.id,
                        legMode = LegMode.SNAP,
                        points = result.points,
                        lengthKm = result.lengthKm,
                        ascentM = result.ascentM,
                        descentM = result.descentM,
                        pathSharePct = pathShare,
                        roadKm = roadKm,
                        hasAccessWarning = false,
                        sourceName = result.sourceName,
                        wayInfo = result.perPointWayInfo
                    )

                    val updatedLegs = _uiState.value.legs.toMutableMap()
                    updatedLegs[legKey] = newLeg
                    _uiState.value = _uiState.value.copy(
                        legs = updatedLegs,
                        options = detourOptions
                    )
                    withContext(Dispatchers.IO) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
                    }
                    updateRouteStats()
                    _userMessage.emit(
                        "Ruten er omlagt online uden om ${poly.name ?: "området"} (no-go områder beregnes online)"
                    )
                }
                is RouteLegResult.Offline -> {
                    _userMessage.emit(result.message)
                }
                else -> {
                    _userMessage.emit("Kunne ikke finde en rute uden om ${poly.name ?: "området"}")
                }
            }
        }
    }

    // --- Phase 12 Feature 1 & Requirement 16: Vand og mad (Supply gaps) ---
    /**
     * Requirement 16: Supply gaps use ONLY the route's own corridor POIs;
     * while they are loading show "Henter data...". Never fall back to every POI in the database.
     */
    fun analyzeSupplyGaps() {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        val points = orderedLegs.flatMap { it.points }
        if (points.size < 2) {
            supplyGapsJob?.cancel()
            _uiState.value = _uiState.value.copy(
                supplyGapWarnings = emptyList(),
                isLoadingCorridorPois = false
            )
            return
        }

        supplyGapsJob?.cancel()
        supplyGapsJob = viewModelScope.launch {
            val online = repository.isDeviceOnline()
            val offlineNotice = if (!online) {
                "Offline – henter ikke interessepunkter langs ruten fra OpenStreetMap"
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                isLoadingCorridorPois = true,
                corridorPoisOfflineNotice = offlineNotice
            )
            try {
                val rId = _uiState.value.routeId?.toString()
                val corridorPois: List<PoiEntity> = withContext(Dispatchers.IO) {
                    if (rId != null) {
                        var existing = repository.getPoisForRoute(rId)
                        if (existing.isEmpty()) {
                            val geoPts = points.map { GeoPointDto(it.lat, it.lng, it.ele) }
                            repository.syncCorridorPoisForRoute(rId, geoPts)
                            existing = repository.getPoisForRoute(rId)
                        }
                        existing
                    } else {
                        // For an unsaved draft route, filter strictly to the route's own 3km corridor
                        val geoPts = points.map { GeoPointDto(it.lat, it.lng, it.ele) }
                        val minLat = points.minOf { it.lat } - 0.03
                        val maxLat = points.maxOf { it.lat } + 0.03
                        val minLng = points.minOf { it.lng } - 0.05
                        val maxLng = points.maxOf { it.lng } + 0.05
                        repository.getAllPoisList().filter { poi ->
                            poi.lat in minLat..maxLat &&
                            poi.lng in minLng..maxLng &&
                            GeoUtils.projectPointOnPolyline(GeoPointDto(poi.lat, poi.lng), geoPts).second <= 3000.0
                        }
                    }
                }

                val warnings = RouteSmartTools.analyzeSupplyGaps(
                    routePoints = points,
                    corridorPois = corridorPois,
                    maxWaterGapKm = _uiState.value.maxWaterGapKm,
                    maxShopGapKm = _uiState.value.maxShopGapKm
                )
                _uiState.value = _uiState.value.copy(
                    supplyGapWarnings = warnings,
                    isLoadingCorridorPois = false,
                    corridorPoisOfflineNotice = offlineNotice
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed analyzing supply gaps: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoadingCorridorPois = false,
                    corridorPoisOfflineNotice = offlineNotice
                )
            }
        }
    }

    fun setMaxWaterGapKm(km: Double) {
        _uiState.value = _uiState.value.copy(maxWaterGapKm = km)
        analyzeSupplyGaps()
    }

    fun setMaxShopGapKm(km: Double) {
        _uiState.value = _uiState.value.copy(maxShopGapKm = km)
        analyzeSupplyGaps()
    }

    // --- Phase 12 Feature 2 & Requirement 12: Rundtur ---
    fun openRundturDialog() {
        _uiState.value = _uiState.value.copy(showRundturDialog = true)
    }

    fun dismissRundturDialog() {
        _uiState.value = _uiState.value.copy(showRundturDialog = false)
    }

    fun setRundturTargetKm(km: Double) {
        _uiState.value = _uiState.value.copy(rundturTargetKm = km.coerceIn(5.0, 60.0))
    }

    /**
     * Requirement 12: Require a start point (either a waypoint on the map or a valid GPS fix).
     * Never fall back to a hardcoded coordinate!
     */
    fun generateRundtur(startLat: Double? = null, startLng: Double? = null) {
        val firstWp = _uiState.value.waypoints.firstOrNull()
        val startPt = when {
            firstWp != null -> LatLng(firstWp.lat, firstWp.lng)
            startLat != null && startLng != null -> LatLng(startLat, startLng)
            else -> null
        }

        if (startPt == null) {
            _uiState.value = _uiState.value.copy(
                showRundturDialog = false,
                errorMessage = "Vælg et startpunkt på kortet først"
            )
            viewModelScope.launch {
                _userMessage.emit("Vælg et startpunkt på kortet først")
            }
            return
        }

        _uiState.value = _uiState.value.copy(isGeneratingRundtur = true, showRundturDialog = true)
        viewModelScope.launch {
            try {
                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningDirect()
                val candidates = RouteSmartTools.generateRundturCandidates(
                    start = startPt,
                    targetKm = _uiState.value.rundturTargetKm,
                    routingEngine = routingEngine,
                    shelters = shelters,
                    friTeltning = friTeltning
                )
                _uiState.value = _uiState.value.copy(
                    isGeneratingRundtur = false,
                    rundturCandidates = candidates
                )
                if (candidates.isEmpty()) {
                    _userMessage.emit("Ingen rundture kunne dannes inden for 20% af målet (${_uiState.value.rundturTargetKm.toInt()} km)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating rundtur: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isGeneratingRundtur = false)
                _userMessage.emit("Kunne ikke danne rundtur: ${e.message}")
            }
        }
    }

    fun applyRundtur(candidate: RundturCandidate) {
        pushHistory()
        _uiState.value = _uiState.value.copy(
            waypoints = candidate.waypoints,
            legs = candidate.legs,
            failedLegs = emptyMap(),
            showRundturDialog = false,
            routeName = candidate.title
        )
        updateRouteStats()
        viewModelScope.launch {
            _userMessage.emit("Rundtur valgt: ${String.format(Locale.US, "%.1f", candidate.actualKm)} km")
        }
    }

    // --- Phase 12 Feature 3: Vejr og dagslys ---
    fun fetchWeatherAndSunset() {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        if (wps.size < 2) return

        _uiState.value = _uiState.value.copy(isLoadingWeather = true)
        viewModelScope.launch {
            try {
                val etas = RouteSmartTools.calculateWaypointWeatherEtas(
                    waypoints = wps,
                    orderedLegs = orderedLegs,
                    startTimeStr = startTimeStr,
                    pacingSpeedKmh = pacingSpeedKmh,
                    weatherService = weatherService
                )
                _uiState.value = _uiState.value.copy(
                    isLoadingWeather = false,
                    waypointWeatherEtas = etas
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching weather ETA: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoadingWeather = false)
            }
        }
    }

    // --- Phase 12 Feature 4 & Requirement 12: Rute til nærmeste ---
    fun openNearestPoiDialog() {
        _uiState.value = _uiState.value.copy(showNearestPoiDialog = true)
    }

    fun dismissNearestPoiDialog() {
        _uiState.value = _uiState.value.copy(showNearestPoiDialog = false)
    }

    /**
     * Requirement 12: Require a start point (either a waypoint on the map or a valid GPS fix).
     * Never fall back to a hardcoded coordinate!
     */
    fun routeToNearest(category: NearestPoiCategory, startLat: Double? = null, startLng: Double? = null) {
        val lastWp = _uiState.value.waypoints.lastOrNull()
        val startPt = when {
            lastWp != null -> LatLng(lastWp.lat, lastWp.lng)
            startLat != null && startLng != null -> LatLng(startLat, startLng)
            else -> null
        }

        if (startPt == null) {
            dismissNearestPoiDialog()
            _uiState.value = _uiState.value.copy(
                errorMessage = "Vælg et startpunkt på kortet først"
            )
            viewModelScope.launch {
                _userMessage.emit("Vælg et startpunkt på kortet først")
            }
            return
        }

        _uiState.value = _uiState.value.copy(isRoutingToNearest = true)
        dismissNearestPoiDialog()
        viewModelScope.launch {
            try {
                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningDirect()
                val pois = repository.getAllPoisList()

                val match = RouteSmartTools.routeToNearestPoi(
                    start = startPt,
                    category = category,
                    routingEngine = routingEngine,
                    shelters = shelters,
                    friTeltning = friTeltning,
                    pois = pois
                )

                if (match != null) {
                    val (poi, legResult) = match
                    pushHistory()
                    val wpStart = RouteWaypoint(id = "start_nearest", lat = startPt.lat, lng = startPt.lng, role = WaypointRole.START, name = "Start")
                    val wpDest = RouteWaypoint(id = "dest_${poi.id}", lat = poi.lat, lng = poi.lng, role = WaypointRole.END, name = poi.name)

                    val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                        legResult.points,
                        legResult.perPointWayInfo
                    )

                    val leg = LegGeometryData(
                        fromWaypointId = wpStart.id,
                        toWaypointId = wpDest.id,
                        legMode = LegMode.SNAP,
                        points = legResult.points,
                        lengthKm = legResult.lengthKm,
                        ascentM = legResult.ascentM,
                        descentM = legResult.descentM,
                        pathSharePct = pathShare,
                        roadKm = roadKm,
                        wayInfo = legResult.perPointWayInfo
                    )

                    val legsMap = mapOf("${wpStart.id}_to_${wpDest.id}" to leg)
                    _uiState.value = _uiState.value.copy(
                        isRoutingToNearest = false,
                        waypoints = listOf(wpStart, wpDest),
                        legs = legsMap,
                        failedLegs = emptyMap(),
                        routeName = "Rute til ${poi.name}"
                    )
                    updateRouteStats()
                    _userMessage.emit("Rute fundet til ${poi.name}: ${String.format(Locale.US, "%.1f", legResult.lengthKm)} km via rute")
                } else {
                    _uiState.value = _uiState.value.copy(isRoutingToNearest = false)
                    _userMessage.emit("Ingen ${category.label.lowercase()} fundet i nærheden.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error routing to nearest: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isRoutingToNearest = false)
                _userMessage.emit("Fejl ved rutesøgning: ${e.message}")
            }
        }
    }

    // --- Phase 12 Feature 6: Mine punkter ---
    fun openAddCustomWaypointDialog(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(
            showAddCustomWaypointDialog = true,
            pendingCustomWaypointLocation = LatLng(lat, lng)
        )
    }

    fun dismissAddCustomWaypointDialog() {
        _uiState.value = _uiState.value.copy(
            showAddCustomWaypointDialog = false,
            pendingCustomWaypointLocation = null
        )
    }

    fun saveCustomWaypoint(name: String, note: String, photoPath: String?) {
        val loc = _uiState.value.pendingCustomWaypointLocation ?: return
        val entity = UserCustomWaypointEntity(
            routeId = _uiState.value.routeId,
            lat = loc.lat,
            lng = loc.lng,
            name = name.ifBlank { "Mit punkt" },
            note = note,
            photoPath = photoPath
        )
        dismissAddCustomWaypointDialog()
        viewModelScope.launch {
            repository.saveCustomWaypoint(entity)
            val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
            _uiState.value = _uiState.value.copy(customUserWaypoints = updated)
            _userMessage.emit("Punkt tilføjet: ${entity.name}")
        }
    }

    fun deleteCustomWaypoint(id: String) {
        viewModelScope.launch {
            repository.deleteCustomWaypoint(id)
            val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
            _uiState.value = _uiState.value.copy(
                customUserWaypoints = updated,
                selectedCustomWaypointForDetails = null
            )
            _userMessage.emit("Punkt slettet")
        }
    }

    fun selectCustomWaypointForDetails(wp: UserCustomWaypointEntity?) {
        _uiState.value = _uiState.value.copy(selectedCustomWaypointForDetails = wp)
    }

    // --- Phase 12 Feature 7: Del rute ---
    fun shareRoute(context: Context) {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        val points = orderedLegs.flatMap { it.points }.map { GeoPointDto(it.lat, it.lng, it.ele) }
        val name = _uiState.value.routeName.ifBlank { "Min rute" }
        val stats = _uiState.value.stats
        val quality = _uiState.value.routeQuality
        val overnightWaypoints = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
        val overnights = overnightWaypoints.size

        val gpxStr = com.example.util.GpxExporter.generateCustomRouteGpx(
            routeName = name,
            description = "Planlagt rute i Dansk Sti (${String.format(Locale.US, "%.1f", stats.totalKm)} km)",
            routePoints = points,
            overnightWaypoints = overnightWaypoints,
            customWaypoints = _uiState.value.customUserWaypoints
        )

        val summary = buildString {
            append("🥾 Rute: $name\n")
            append("📏 Distance: ${String.format(Locale.US, "%.1f", stats.totalKm)} km\n")
            append("⛰️ Højdemeter: +${stats.ascentM.toInt()} m / -${stats.descentM.toInt()} m\n")
            append("⏱️ Estimeret tid: ${_uiState.value.timeEstimateFormatted}\n")
            if (overnights > 0) {
                append("⛺ Planlagte overnatninger: $overnights\n")
            }
            if (stats.hasWayInfo && stats.pathSharePct != null) {
                append("🌲 Underlag: ${quality.trailPct.toInt()}% sti, ${quality.trackPct.toInt()}% skov-/markvej, ${quality.asphaltPct.toInt()}% asfalt\n")
            } else {
                append("🌲 Underlag: ukendt\n")
            }
            append("Del med Dansk Sti")
        }

        com.example.util.GpxExporter.exportAndShareGpxWithSummary(
            context = context,
            fileName = "${name.replace(" ", "_")}.gpx",
            gpxContent = gpxStr,
            shareTitle = "Del rute: $name",
            textSummary = summary
        )
    }
}
