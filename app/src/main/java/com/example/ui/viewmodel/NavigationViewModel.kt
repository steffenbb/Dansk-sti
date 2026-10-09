package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.GeoPointDto
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.repository.DanskStiRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.GeoUtils
import com.example.util.StagePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NextStopInfo(
    val name: String,
    val distanceRemainingKm: Double,
    val type: String
)

data class AheadOnRouteItem(
    val title: String,
    val type: String,
    val kmRemaining: Double,
    val detourMeters: Double
)

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    val liveState: StateFlow<NavigationLiveState> = NavigationTrackingService.liveState

    private val _activePlan = MutableStateFlow<TripPlanEntity?>(null)
    val activePlan: StateFlow<TripPlanEntity?> = _activePlan.asStateFlow()

    private val _activeStages = MutableStateFlow<List<TripStage>>(emptyList())
    val activeStages: StateFlow<List<TripStage>> = _activeStages.asStateFlow()

    private val _currentDay = MutableStateFlow(1)
    val currentDay: StateFlow<Int> = _currentDay.asStateFlow()

    private val _distanceRemainingToday = MutableStateFlow(0.0)
    val distanceRemainingToday: StateFlow<Double> = _distanceRemainingToday.asStateFlow()

    private val _todayStageText = MutableStateFlow("Ingen aktiv tur")
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

    private val _routePoints = MutableStateFlow<List<GeoPointDto>>(emptyList())
    val routePoints: StateFlow<List<GeoPointDto>> = _routePoints.asStateFlow()

    private val _isBatterySaverMode = MutableStateFlow(false)
    val isBatterySaverMode: StateFlow<Boolean> = _isBatterySaverMode.asStateFlow()

    private val _showSosSheet = MutableStateFlow(false)
    val showSosSheet: StateFlow<Boolean> = _showSosSheet.asStateFlow()

    private val _emergencyInfo = MutableStateFlow<DanishEmergencyInfo?>(null)
    val emergencyInfo: StateFlow<DanishEmergencyInfo?> = _emergencyInfo.asStateFlow()

    private val _mapTileMode = MutableStateFlow("topo") // "topo", "satellite", "offline"
    val mapTileMode: StateFlow<String> = _mapTileMode.asStateFlow()

    val isTripActive: StateFlow<Boolean> = MutableStateFlow(false)
        .combineWithLiveState()

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
        // React to live GPS updates
        viewModelScope.launch {
            NavigationTrackingService.liveState.collect { state ->
                if (state.isTracking && state.currentLat != null && state.currentLat != 0.0) {
                    _emergencyInfo.value = repository.getEmergencyInfo(state.currentLat, state.currentLng ?: 0.0)
                    updateStageProgress(state)
                }
            }
        }
    }

    fun startTrip(planId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val plan = repository.getTripPlanById(planId) ?: return@withContext
                _activePlan.value = plan
                val stages = repository.getStagesForPlan(planId)
                _activeStages.value = stages

                val trail = repository.getTrailById(plan.trailId)
                if (trail != null) {
                    _routePoints.value = repository.getTrailPoints(trail)
                }

                // Check or create initial progress
                _currentDay.value = 1
                val stage1 = stages.firstOrNull()
                val remToday = stage1?.stageKm ?: plan.preferredDailyKm
                _distanceRemainingToday.value = remToday
                _todayStageText.value = "Dag 1 af ${plan.targetDays} · ${String.format(java.util.Locale.US, "%.1f", remToday)} km tilbage i dag"

                // Start background tracking service
                NavigationTrackingService.start(
                    context = getApplication(),
                    planId = planId
                )
            }
        }
    }

    fun stopTrip() {
        NavigationTrackingService.stop(getApplication())
        _activePlan.value = null
        _activeStages.value = emptyList()
        _isBatterySaverMode.value = false
    }

    private fun updateStageProgress(state: NavigationLiveState) {
        val plan = _activePlan.value ?: return
        val stages = _activeStages.value
        val day = _currentDay.value
        val stage = stages.getOrNull(day - 1) ?: return

        val stageKm = stage.stageKm
        val walkedToday = state.distanceWalkedTodayKm
        val remainingToday = kotlin.math.max(0.0, stageKm - walkedToday)
        _distanceRemainingToday.value = remainingToday
        _todayStageText.value = "Dag $day af ${plan.targetDays} · ${String.format(java.util.Locale.US, "%.1f", remainingToday)} km tilbage i dag"

        _nextOvernight.value = NextStopInfo(
            name = stage.stopName,
            distanceRemainingKm = remainingToday,
            type = stage.stopType
        )
    }

    fun toggleBatterySaver() {
        _isBatterySaverMode.value = !_isBatterySaverMode.value
    }

    fun setBatterySaverMode(enabled: Boolean) {
        _isBatterySaverMode.value = enabled
    }

    fun setSosSheet(show: Boolean) {
        _showSosSheet.value = show
    }

    fun cycleMapTileMode() {
        _mapTileMode.value = when (_mapTileMode.value) {
            "topo" -> "satellite"
            "satellite" -> "offline"
            else -> "topo"
        }
    }
}
