package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
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
    private val plannedRouteRepo = PlannedRouteRepository(application)

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

    private val _isBatterySaverMode = MutableStateFlow(false)
    val isBatterySaverMode: StateFlow<Boolean> = _isBatterySaverMode.asStateFlow()

    private val _showSosSheet = MutableStateFlow(false)
    val showSosSheet: StateFlow<Boolean> = _showSosSheet.asStateFlow()

    private val _emergencyInfo = MutableStateFlow<DanishEmergencyInfo?>(null)
    val emergencyInfo: StateFlow<DanishEmergencyInfo?> = _emergencyInfo.asStateFlow()

    private val _mapTileMode = MutableStateFlow("topo") // "topo", "offline"
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
        // React to live GPS updates and read progress from service
        viewModelScope.launch {
            NavigationTrackingService.liveState.collect { state ->
                if (state.plannedRoute != null && _plannedRoute.value == null) {
                    _plannedRoute.value = state.plannedRoute
                }
                if (state.currentDay > 0) {
                    _currentDay.value = state.currentDay
                }
                _distanceRemainingToday.value = state.distanceRemainingTodayKm

                if (state.isTracking) {
                    val days = _activePlan.value?.targetDays ?: _plannedRoute.value?.stages?.size ?: 1
                    _todayStageText.value = "Dag ${state.currentDay} af $days · ${String.format(java.util.Locale.US, "%.1f", state.distanceRemainingTodayKm)} km tilbage i dag"
                    state.currentStage?.let { stage ->
                        _nextOvernight.value = NextStopInfo(
                            name = stage.stopName,
                            distanceRemainingKm = state.distanceRemainingTodayKm,
                            type = stage.stopType
                        )
                    }
                }

                if (state.isTracking && state.currentLat != null && state.currentLat != 0.0) {
                    _emergencyInfo.value = repository.getEmergencyInfo(state.currentLat, state.currentLng ?: 0.0)
                }
            }
        }
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

                val route = plannedRouteRepo.loadPlannedRoute(targetId)
                if (route != null) {
                    _plannedRoute.value = route
                }
                val plan = repository.getTripPlanById(targetId)
                if (plan != null) {
                    _activePlan.value = plan
                }
                val stages = repository.getStagesForPlan(targetId)
                if (stages.isNotEmpty()) {
                    _activeStages.value = stages
                }

                // If tracking is not active, start it
                if (!NavigationTrackingService.liveState.value.isTracking) {
                    startTrip(targetId)
                }
            }
        }
    }

    fun startTrip(planId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val route = plannedRouteRepo.loadPlannedRoute(planId)
                if (route != null) {
                    _plannedRoute.value = route
                }
                val plan = repository.getTripPlanById(planId) ?: return@withContext
                _activePlan.value = plan
                val stages = repository.getStagesForPlan(planId)
                _activeStages.value = stages

                plannedRouteRepo.saveLastActivePlanId(planId)

                // Start background tracking service (service restores existing progress from DB)
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
        _plannedRoute.value = null
        _isBatterySaverMode.value = false
        plannedRouteRepo.clearLastActivePlanId()
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
            "topo" -> "offline"
            else -> "topo"
        }
    }

    // --- Phase 7a: Recorded GPS Track & Day Stats ---
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
            val pId = liveState.value.planId
            val trailName = liveState.value.trailName.ifBlank { "Vandretur" }
            if (pId <= 0L) return@launch
            val points = repository.getRecordedTrackPointsForDaySync(pId, dayNumber)
            val stats = repository.getAllRecordedDayStatsSync(pId)
            val gpx = com.example.util.GpxExporter.generateRecordedTrackGpx(
                trailName = trailName,
                planId = pId,
                dayNumber = dayNumber,
                points = points,
                dayStats = stats
            )
            com.example.util.GpxExporter.exportAndShareGpxFile(
                context = context,
                fileName = "${trailName.replace(" ", "_")}_dag_$dayNumber.gpx",
                gpxContent = gpx,
                shareTitle = "Del GPS-spor (Dag $dayNumber)"
            )
        }
    }

    fun exportWholeTripGpx(context: android.content.Context) {
        viewModelScope.launch {
            val pId = liveState.value.planId
            val trailName = liveState.value.trailName.ifBlank { "Vandretur" }
            if (pId <= 0L) return@launch
            val points = repository.getAllRecordedTrackPointsForPlanSync(pId)
            val stats = repository.getAllRecordedDayStatsSync(pId)
            val gpx = com.example.util.GpxExporter.generateRecordedTrackGpx(
                trailName = trailName,
                planId = pId,
                dayNumber = null,
                points = points,
                dayStats = stats
            )
            com.example.util.GpxExporter.exportAndShareGpxFile(
                context = context,
                fileName = "${trailName.replace(" ", "_")}_hel_tur.gpx",
                gpxContent = gpx,
                shareTitle = "Del GPS-spor (Hele turen)"
            )
        }
    }

    // --- Phase 7d: Share my current coordinates & map link (on request only) ---
    fun shareCurrentLocation(context: android.content.Context) {
        val state = liveState.value
        val lat = state.currentLat ?: 55.6761
        val lng = state.currentLng ?: 12.5683
        val trailName = state.trailName.ifBlank { "Dansk Sti" }
        val walkedKm = String.format(java.util.Locale.US, "%.1f", state.distanceWalkedTodayKm)

        val message = """
            Jeg vandrer på $trailName og befinder mig her:
            Kort: https://maps.google.com/?q=$lat,$lng
            Koordinater: ${String.format(java.util.Locale.US, "%.5f", lat)}, ${String.format(java.util.Locale.US, "%.5f", lng)}
            Dagens distance: $walkedKm km gået (Dag ${state.currentDay}).
            Sendt fra Dansk Sti
        """.trimIndent()

        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }
        context.startActivity(android.content.Intent.createChooser(sendIntent, "Del min position"))
    }
}
