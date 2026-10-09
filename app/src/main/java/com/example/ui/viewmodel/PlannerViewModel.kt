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
import com.example.data.model.TripStage
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class PlannerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository
    private val settingsRepository: SettingsRepository = (application as DanskStiApp).settingsRepository
    private val plannedRouteRepo = PlannedRouteRepository(application)

    private val _trail = MutableStateFlow<TrailEntity?>(null)
    val trail: StateFlow<TrailEntity?> = _trail.asStateFlow()

    private val _trailPoints = MutableStateFlow<List<GeoPointDto>>(emptyList())

    private val _startKm = MutableStateFlow(0.0)
    val startKm: StateFlow<Double> = _startKm.asStateFlow()

    private val _endKm = MutableStateFlow(25.0)
    val endKm: StateFlow<Double> = _endKm.asStateFlow()

    private val _isReverse = MutableStateFlow(false)
    val isReverse: StateFlow<Boolean> = _isReverse.asStateFlow()

    private val _dailyTargetKm = MutableStateFlow(15.0)
    val dailyTargetKm: StateFlow<Double> = _dailyTargetKm.asStateFlow()

    private val _days = MutableStateFlow(2)
    val days: StateFlow<Int> = _days.asStateFlow()

    private val _overnightType = MutableStateFlow(OvernightType.BOTH)
    val overnightType: StateFlow<OvernightType> = _overnightType.asStateFlow()

    // Settings from DataStore
    val settings = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.local.AppSettings())

    private val _stages = MutableStateFlow<List<TripStage>>(emptyList())
    val stages: StateFlow<List<TripStage>> = _stages.asStateFlow()

    private val _plannedRoute = MutableStateFlow<PlannedRoute?>(null)
    val plannedRoute: StateFlow<PlannedRoute?> = _plannedRoute.asStateFlow()

    private val _corridorShelters = MutableStateFlow<List<ShelterEntity>>(emptyList())
    private val _corridorFriTeltning = MutableStateFlow<List<FriTeltningEntity>>(emptyList())
    private val _corridorPois = MutableStateFlow<List<PoiEntity>>(emptyList())

    private val _isCalculating = MutableStateFlow(false)
    val isCalculating: StateFlow<Boolean> = _isCalculating.asStateFlow()

    private var currentSunset: String = "18:50"

    fun initTrail(trailId: String) {
        viewModelScope.launch {
            _isCalculating.value = true
            withContext(Dispatchers.IO) {
                val t = repository.getTrailById(trailId) ?: return@withContext
                _trail.value = t
                val points = repository.getTrailPoints(t)
                _trailPoints.value = points

                val totalLength = if (t.lengthKm > 0) t.lengthKm else 25.0
                _startKm.value = 0.0
                _endKm.value = totalLength
                _dailyTargetKm.value = 15.0
                _days.value = max(1, (totalLength / 15.0).toInt())

                _corridorShelters.value = repository.getSheltersForTrail(trailId)
                _corridorFriTeltning.value = repository.getFriTeltningForTrail(trailId)
                _corridorPois.value = repository.getPoisForTrail(trailId)

                val weather = repository.getTrailWeather(points.firstOrNull()?.lat ?: 55.0, points.firstOrNull()?.lng ?: 10.0)
                currentSunset = weather.sunset ?: "18:50"

                calculateStagesInternal()
            }
            _isCalculating.value = false
        }
    }

    private suspend fun calculateStagesInternal() {
        val t = _trail.value ?: return
        val points = _trailPoints.value
        val currentSettings = settings.value

        val route = plannedRouteRepo.buildPlannedRoute(
            planId = 0L,
            trail = t,
            rawTrailPoints = points,
            startKm = _startKm.value,
            endKm = _endKm.value,
            isReverse = _isReverse.value,
            stages = emptyList()
        )
        _plannedRoute.value = route

        val calculated = StagePlanner.calculateStages(
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
        _stages.value = calculated
    }

    fun setPlannerRange(start: Double, end: Double) {
        _startKm.value = start
        _endKm.value = end
        recalculate()
    }

    fun setReverse(reverse: Boolean) {
        _isReverse.value = reverse
        recalculate()
    }

    fun setDailyTargetKm(dailyKm: Double) {
        _dailyTargetKm.value = dailyKm
        val dist = kotlin.math.abs(_endKm.value - _startKm.value)
        _days.value = max(1, kotlin.math.ceil(dist / dailyKm).toInt())
        recalculate()
    }

    fun setDays(daysCount: Int) {
        _days.value = max(1, daysCount)
        val dist = kotlin.math.abs(_endKm.value - _startKm.value)
        _dailyTargetKm.value = max(5.0, dist / _days.value)
        recalculate()
    }

    fun setOvernightType(type: OvernightType) {
        _overnightType.value = type
        recalculate()
    }

    fun setPaceKmh(pace: Double) {
        viewModelScope.launch {
            settingsRepository.setMitTempoKmh(pace)
            recalculate()
        }
    }

    fun setMaxDetourKm(detour: Double) {
        viewModelScope.launch {
            settingsRepository.setMaxDetourKm(detour)
            recalculate()
        }
    }

    fun setStartTime(time: String) {
        viewModelScope.launch {
            settingsRepository.setStartTime(time)
            recalculate()
        }
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
        repository.saveTripPlan(
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
}
