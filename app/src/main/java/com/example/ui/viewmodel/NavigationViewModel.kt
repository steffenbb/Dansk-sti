package com.example.ui.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

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

    // Requirement 21: Route corridor POIs for overlayManager.updatePois
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
                    loadRouteCorridorPois(state.plannedRoute)
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

                if (state.isTracking && state.currentLat != null && state.currentLng != null && state.currentLat != 0.0) {
                    _emergencyInfo.value = repository.getEmergencyInfo(state.currentLat, state.currentLng)
                }
            }
        }
    }

    private fun loadRouteCorridorPois(route: PlannedRoute) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trailId = route.trailId
                val geoPoints = route.points.map { GeoPointDto(it.lat, it.lng, it.ele) }

                var shelters = repository.getSheltersForTrail(trailId)
                var pois = repository.getPoisForTrail(trailId)
                if (shelters.isEmpty() && pois.isEmpty() && geoPoints.isNotEmpty()) {
                    if (trailId.startsWith("route_")) {
                        repository.syncCorridorPoisForRoute(trailId.removePrefix("route_"), geoPoints)
                    } else {
                        repository.getTrailById(trailId)?.let { repository.syncCorridorPois(it) }
                    }
                    shelters = repository.getSheltersForTrail(trailId)
                    pois = repository.getPoisForTrail(trailId)
                }

                _routeShelters.value = shelters
                _routeWaters.value = pois.filter { it.type.equals("water", ignoreCase = true) }
                _routeToilets.value = pois.filter { it.type.equals("toilet", ignoreCase = true) }
                _routeShops.value = pois.filter { it.type.equals("shop", ignoreCase = true) }

                // Filter Fri Teltning to route corridor (<= 3000m) and parse boundaries
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
                    ft to parsePolygonCoords(ft.polygonJson)
                }

                val hazards = repository.getHazardNotesForTrail(trailId).firstOrNull() ?: emptyList()
                _routeHazards.value = hazards
            } catch (_: Exception) {}
        }
    }

    private fun parsePolygonCoords(polygonJson: String?): List<GeoPointDto> {
        if (polygonJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(polygonJson)
            val pts = mutableListOf<GeoPointDto>()
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                if (item is JSONArray && item.length() >= 2) {
                    val v0 = item.getDouble(0)
                    val v1 = item.getDouble(1)
                    // GeoJSON coordinates are [lng, lat] or [lat, lng]; Denmark lat is 54..58, lng is 8..16
                    if (v0 in 53.0..59.0) {
                        pts.add(GeoPointDto(v0, v1))
                    } else {
                        pts.add(GeoPointDto(v1, v0))
                    }
                }
            }
            pts
        } catch (_: Exception) {
            emptyList()
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
                    loadRouteCorridorPois(route)
                }
                val plan = repository.getTripPlanById(targetId)
                if (plan != null) {
                    _activePlan.value = plan
                }
                val stages = repository.getStagesForPlan(targetId)
                if (stages.isNotEmpty()) {
                    _activeStages.value = stages
                }

                // If tracking is not active, start it (startTrip checks location permission first)
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
                val route = plannedRouteRepo.loadPlannedRoute(planId)
                if (route != null) {
                    _plannedRoute.value = route
                    loadRouteCorridorPois(route)
                }
                val plan = repository.getTripPlanById(planId) ?: return@withContext
                _activePlan.value = plan
                val stages = repository.getStagesForPlan(planId)
                _activeStages.value = stages

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

                // Start background tracking service (service restores existing progress from DB)
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

    // --- Phase 7d & Requirement 12: Share my current coordinates & map link (disable until GPS fix exists) ---
    fun shareCurrentLocation(context: android.content.Context) {
        val state = liveState.value
        val lat = state.currentLat
        val lng = state.currentLng
        if (lat == null || lng == null) {
            Toast.makeText(context, "Venter på GPS-position", Toast.LENGTH_SHORT).show()
            return
        }
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
