package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TripStage
import com.example.data.remote.TrailWeatherInfo
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlannerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository
    private val settingsRepository: SettingsRepository = (application as DanskStiApp).settingsRepository
    private val plannedRouteRepo = PlannedRouteRepository(application)

    val allTrails: StateFlow<List<TrailEntity>> = repository.getAllTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _trail = MutableStateFlow<TrailEntity?>(null)
    val trail: StateFlow<TrailEntity?> = _trail.asStateFlow()
    val selectedTrail: StateFlow<TrailEntity?> = _trail.asStateFlow()

    private val _trailSegments = MutableStateFlow<List<TrailSegmentDto>>(emptyList())
    val trailSegments: StateFlow<List<TrailSegmentDto>> = _trailSegments.asStateFlow()

    private val _trailPoints = MutableStateFlow<List<GeoPointDto>>(emptyList())

    private val _startKm = MutableStateFlow(0.0)
    val startKm: StateFlow<Double> = _startKm.asStateFlow()
    val plannerStartKm: StateFlow<Double> = _startKm.asStateFlow()

    private val _endKm = MutableStateFlow(25.0)
    val endKm: StateFlow<Double> = _endKm.asStateFlow()
    val plannerEndKm: StateFlow<Double> = _endKm.asStateFlow()

    private val _isReverse = MutableStateFlow(false)
    val isReverse: StateFlow<Boolean> = _isReverse.asStateFlow()
    val isWalkingReverse: StateFlow<Boolean> = _isReverse.asStateFlow()

    private val _dailyTargetKm = MutableStateFlow(15.0)
    val dailyTargetKm: StateFlow<Double> = _dailyTargetKm.asStateFlow()
    val plannerDailyKm: StateFlow<Double> = _dailyTargetKm.asStateFlow()

    private val _days = MutableStateFlow(2)
    val days: StateFlow<Int> = _days.asStateFlow()
    val plannerDays: StateFlow<Int> = _days.asStateFlow()

    private val _overnightType = MutableStateFlow(OvernightType.BOTH)
    val overnightType: StateFlow<OvernightType> = _overnightType.asStateFlow()
    val plannerOvernightType: StateFlow<OvernightType> = _overnightType.asStateFlow()

    // Settings from DataStore
    val settings = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.local.AppSettings())

    val mitTempoKmh: StateFlow<Double> = settingsRepository.settingsFlow
        .map { it.mitTempoKmh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4.0)

    val plannerMaxDetourKm: StateFlow<Double> = settingsRepository.settingsFlow
        .map { it.maxDetourKm }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.5)

    val plannerStartTime: StateFlow<String> = settingsRepository.settingsFlow
        .map { it.startTime }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "09:00")

    private val _stages = MutableStateFlow<List<TripStage>>(emptyList())
    val stages: StateFlow<List<TripStage>> = _stages.asStateFlow()
    val plannerStages: StateFlow<List<TripStage>> = _stages.asStateFlow()

    private val _plannedRoute = MutableStateFlow<PlannedRoute?>(null)
    val plannedRoute: StateFlow<PlannedRoute?> = _plannedRoute.asStateFlow()

    private val _corridorShelters = MutableStateFlow<List<ShelterEntity>>(emptyList())
    private val _corridorFriTeltning = MutableStateFlow<List<FriTeltningEntity>>(emptyList())
    private val _corridorPois = MutableStateFlow<List<PoiEntity>>(emptyList())

    private val _isCalculating = MutableStateFlow(false)
    val isCalculating: StateFlow<Boolean> = _isCalculating.asStateFlow()
    val isCalculatingStages: StateFlow<Boolean> = _isCalculating.asStateFlow()

    private val _isTrailNotFound = MutableStateFlow(false)
    val isTrailNotFound: StateFlow<Boolean> = _isTrailNotFound.asStateFlow()

    private val _weather = MutableStateFlow<TrailWeatherInfo?>(null)
    val trailWeather: StateFlow<TrailWeatherInfo?> = _weather.asStateFlow()

    private val _walkDistanceKm = MutableStateFlow(25.0)
    val plannerWalkDistanceKm: StateFlow<Double> = _walkDistanceKm.asStateFlow()

    private var currentSunset: String? = null

    fun selectTrail(t: TrailEntity) {
        initTrail(t.id)
    }

    fun initTrail(trailId: String) {
        if (trailId.isBlank()) return
        if (_trail.value?.id == trailId && _stages.value.isNotEmpty()) {
            return
        }
        _isTrailNotFound.value = false
        if (_trail.value?.id != trailId) {
            _trail.value = null
            _stages.value = emptyList()
            _plannedRoute.value = null
        }
        _isCalculating.value = true
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) {
                    repository.getTrailById(trailId)
                }
                if (t == null) {
                    _isTrailNotFound.value = true
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    _trail.value = t
                    val segments = repository.getTrailPoints(t)
                    _trailSegments.value = segments
                    val points = segments.flatMap { it.points }
                    _trailPoints.value = points

                    val totalLength = if (t.lengthKm > 0) t.lengthKm else 25.0
                    _startKm.value = 0.0
                    _endKm.value = totalLength
                    _walkDistanceKm.value = totalLength
                    _dailyTargetKm.value = 15.0
                    _days.value = max(1, (totalLength / 15.0).toInt())

                    _corridorShelters.value = repository.getSheltersForTrail(trailId)
                    _corridorFriTeltning.value = repository.getFriTeltningForTrail(trailId)
                    _corridorPois.value = repository.getPoisForTrail(trailId)

                    val weather = repository.getTrailWeather(points.firstOrNull()?.lat ?: 55.0, points.firstOrNull()?.lng ?: 10.0)
                    _weather.value = weather
                    currentSunset = weather.sunset

                    calculateStagesInternal()
                }
            } catch (e: Exception) {
                _isTrailNotFound.value = true
            } finally {
                _isCalculating.value = false
            }
        }
    }

    private suspend fun calculateStagesInternal() {
        val t = _trail.value ?: return
        val currentSettings = settings.value

        val route = plannedRouteRepo.buildPlannedRoute(
            planId = 0L,
            trail = t,
            segments = _trailSegments.value,
            startKm = _startKm.value,
            endKm = _endKm.value,
            isReverse = _isReverse.value,
            stages = emptyList()
        )
        _plannedRoute.value = route

        val customRouteId = t.id.removePrefix("route_").toLongOrNull()
        val customRoute = if (customRouteId != null) repository.getCustomRoute(customRouteId) else null
        val wps = if (customRoute != null) com.example.util.routing.RouteWaypoint.listFromJson(customRoute.waypointsJson) else emptyList()
        val overnightWps = wps.filter { it.role == com.example.util.routing.WaypointRole.OVERNIGHT }

        val calculated = if (overnightWps.isNotEmpty()) {
            val plannedDays = overnightWps.size + 1
            _days.value = plannedDays
            StagePlanner.calculateStagesForCustomRoute(
                plannedRoute = route,
                routeName = t.name,
                waypoints = wps,
                overnightWaypoints = overnightWps,
                paceKmh = currentSettings.mitTempoKmh,
                dailyTargetKm = _dailyTargetKm.value,
                numDays = plannedDays,
                overnightType = _overnightType.value,
                maxDetourKm = currentSettings.maxDetourKm,
                startTime = currentSettings.startTime,
                shelters = _corridorShelters.value,
                friTeltnings = _corridorFriTeltning.value,
                pois = _corridorPois.value,
                sunsetTime = currentSunset
            )
        } else {
            StagePlanner.calculateStages(
                plannedRoute = route,
                trail = t,
                paceKmh = currentSettings.mitTempoKmh,
                dailyTargetKm = _dailyTargetKm.value,
                numDays = _days.value,
                overnightType = _overnightType.value,
                maxDetourKm = currentSettings.maxDetourKm,
                startTime = currentSettings.startTime,
                shelters = _corridorShelters.value,
                friTeltnings = _corridorFriTeltning.value,
                pois = _corridorPois.value,
                sunsetTime = currentSunset
            )
        }
        _stages.value = calculated
    }

    fun setPlannerRange(start: Double, end: Double) {
        _startKm.value = start
        _endKm.value = end
        _walkDistanceKm.value = abs(end - start)
        recalculate()
    }

    fun setReverse(reverse: Boolean) {
        _isReverse.value = reverse
        recalculate()
    }

    fun setWalkingDirection(reverse: Boolean) {
        setReverse(reverse)
    }

    fun setDailyTargetKm(dailyKm: Double) {
        _dailyTargetKm.value = dailyKm
        val dist = abs(_endKm.value - _startKm.value)
        _days.value = max(1, kotlin.math.ceil(dist / dailyKm).toInt())
        recalculate()
    }

    fun setPlannerDailyKm(km: Double) {
        setDailyTargetKm(km)
    }

    fun setDays(daysCount: Int) {
        _days.value = max(1, daysCount)
        val dist = abs(_endKm.value - _startKm.value)
        _dailyTargetKm.value = max(5.0, dist / _days.value)
        recalculate()
    }

    fun addPlannerStage() {
        setDays(_days.value + 1)
    }

    fun removePlannerStage() {
        setDays(max(1, _days.value - 1))
    }

    fun setOvernightType(type: OvernightType) {
        _overnightType.value = type
        recalculate()
    }

    fun setPlannerOvernightType(type: OvernightType) {
        setOvernightType(type)
    }

    fun setPaceKmh(pace: Double) {
        viewModelScope.launch {
            settingsRepository.setMitTempoKmh(pace)
            recalculate()
        }
    }

    fun setMitTempoKmh(tempo: Double) {
        setPaceKmh(tempo)
    }

    fun setMaxDetourKm(detour: Double) {
        viewModelScope.launch {
            settingsRepository.setMaxDetourKm(detour)
            recalculate()
        }
    }

    fun setPlannerMaxDetourKm(detour: Double) {
        setMaxDetourKm(detour)
    }

    fun setStartTime(time: String) {
        viewModelScope.launch {
            settingsRepository.setStartTime(time)
            recalculate()
        }
    }

    fun setPlannerStartTime(time: String) {
        setStartTime(time)
    }

    private fun recalculate() {
        viewModelScope.launch {
            _isCalculating.value = true
            withContext(Dispatchers.IO) {
                calculateStagesInternal()
            }
            _isCalculating.value = false
        }
    }

    fun changeStageCandidate(stageIndex: Int, candidate: OvernightCandidate) {
        val route = _plannedRoute.value ?: return
        val t = _trail.value ?: return
        val currentSettings = settings.value

        val recomputed = StagePlanner.recomputeStagesFrom(
            currentStages = _stages.value,
            changedStageIndex = stageIndex,
            newCandidate = candidate,
            plannedRoute = route,
            trail = t,
            paceKmh = currentSettings.mitTempoKmh,
            dailyTargetKm = _dailyTargetKm.value,
            overnightType = _overnightType.value,
            maxDetourKm = currentSettings.maxDetourKm,
            startTime = currentSettings.startTime,
            shelters = _corridorShelters.value,
            friTeltnings = _corridorFriTeltning.value,
            pois = _corridorPois.value,
            sunsetTime = currentSunset
        )
        _stages.value = recomputed
    }

    fun adjustStageEndKm(stageIndex: Int, newEndKm: Double) {
        val current = _stages.value.toMutableList()
        if (stageIndex !in current.indices) return
        val cand = OvernightCandidate(
            id = "custom_$stageIndex",
            name = "Manuelt valg (km ${String.format(java.util.Locale.US, "%.1f", newEndKm)})",
            type = "SHELTER",
            lat = current[stageIndex].stopLat,
            lng = current[stageIndex].stopLng,
            walkKm = newEndKm,
            detourMeters = 0.0,
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        changeStageCandidate(stageIndex, cand)
    }

    suspend fun savePlan(): Long = withContext(Dispatchers.IO) {
        val t = _trail.value ?: return@withContext 0L
        val currentSettings = settings.value
        val planId = repository.saveTripPlan(
            trail = t,
            startKm = _startKm.value,
            endKm = _endKm.value,
            isReverse = _isReverse.value,
            days = _days.value,
            overnightType = _overnightType.value,
            preferredDailyKm = _dailyTargetKm.value,
            stages = _stages.value,
            paceKmh = currentSettings.mitTempoKmh,
            maxDetourKm = currentSettings.maxDetourKm,
            startTime = currentSettings.startTime
        )
        val finalRoute = plannedRouteRepo.buildPlannedRoute(
            planId = planId,
            trail = t,
            segments = _trailSegments.value,
            startKm = _startKm.value,
            endKm = _endKm.value,
            isReverse = _isReverse.value,
            stages = _stages.value
        )
        plannedRouteRepo.savePlannedRoute(finalRoute)
        planId
    }

    suspend fun getAvailableCandidatesForStage(stageIndex: Int): List<OvernightCandidate> = withContext(Dispatchers.IO) {
        val route = _plannedRoute.value ?: return@withContext emptyList()
        val currentSettings = settings.value
        StagePlanner.extractCandidates(
            plannedRoute = route,
            shelters = _corridorShelters.value,
            friTeltnings = _corridorFriTeltning.value,
            waterPois = _corridorPois.value.filter { it.type == "water" },
            toiletPois = _corridorPois.value.filter { it.type == "toilet" },
            overnightType = _overnightType.value,
            maxDetourKm = currentSettings.maxDetourKm
        )
    }

    suspend fun getNearestCandidatesForStage(stageIndex: Int): List<OvernightCandidate> {
        return getAvailableCandidatesForStage(stageIndex)
    }
}
