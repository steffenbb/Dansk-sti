package com.example.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GearChecklistItem
import com.example.data.model.GeoPointDto
import com.example.data.model.HazardType
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.ResupplyPoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSurfaceStats
import com.example.data.model.TransitStation
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNote
import com.example.data.remote.TrailWeatherInfo
import com.example.data.repository.DanskStiRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.DownloadProgress
import com.example.util.GeoUtils
import com.example.util.GpxExporter
import com.example.util.OfflineTileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen {
    TRAIL_BROWSER,
    TRAIL_DETAIL,
    TRIP_PLANNER,
    NAVIGATION_MODE,
    SAVED_PLANS
}

data class NearestPoiResult(
    val poiName: String,
    val type: String,
    val distanceMeters: Double,
    val lat: Double,
    val lng: Double,
    val details: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    // Screen navigation stack
    private val _currentScreen = MutableStateFlow(AppScreen.TRAIL_BROWSER)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // Trail Browser state
    val allTrails: StateFlow<List<TrailEntity>> = repository.getAllTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedRegion = MutableStateFlow("Alle")
    val selectedRegion: StateFlow<String> = _selectedRegion.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val filteredTrails: StateFlow<List<TrailEntity>> = combine(allTrails, _searchQuery, _selectedRegion) { trails, query, region ->
        trails.filter { trail ->
            val matchesQuery = query.isBlank() ||
                    trail.name.contains(query, ignoreCase = true) ||
                    trail.description.contains(query, ignoreCase = true) ||
                    trail.region.contains(query, ignoreCase = true)
            val matchesRegion = region == "Alle" || trail.region.equals(region, ignoreCase = true)
            matchesQuery && matchesRegion
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Trail & Detail
    private val _selectedTrail = MutableStateFlow<TrailEntity?>(null)
    val selectedTrail: StateFlow<TrailEntity?> = _selectedTrail.asStateFlow()

    private val _selectedTrailPoints = MutableStateFlow<List<GeoPointDto>>(emptyList())
    val selectedTrailPoints: StateFlow<List<GeoPointDto>> = _selectedTrailPoints.asStateFlow()

    private val _corridorShelters = MutableStateFlow<List<ShelterEntity>>(emptyList())
    val corridorShelters: StateFlow<List<ShelterEntity>> = _corridorShelters.asStateFlow()

    private val _corridorFriTeltning = MutableStateFlow<List<FriTeltningEntity>>(emptyList())
    val corridorFriTeltning: StateFlow<List<FriTeltningEntity>> = _corridorFriTeltning.asStateFlow()

    private val _corridorPois = MutableStateFlow<List<PoiEntity>>(emptyList())
    val corridorPois: StateFlow<List<PoiEntity>> = _corridorPois.asStateFlow()

    // Trip Planner State
    private val _plannerStartKm = MutableStateFlow(0.0)
    val plannerStartKm: StateFlow<Double> = _plannerStartKm.asStateFlow()

    private val _plannerEndKm = MutableStateFlow(100.0)
    val plannerEndKm: StateFlow<Double> = _plannerEndKm.asStateFlow()

    private val _plannerWalkDistanceKm = MutableStateFlow(25.0)
    val plannerWalkDistanceKm: StateFlow<Double> = _plannerWalkDistanceKm.asStateFlow()

    private val _isWalkingReverse = MutableStateFlow(false)
    val isWalkingReverse: StateFlow<Boolean> = _isWalkingReverse.asStateFlow()

    private val _plannerDays = MutableStateFlow(3)
    val plannerDays: StateFlow<Int> = _plannerDays.asStateFlow()

    private val _plannerOvernightType = MutableStateFlow(OvernightType.MIXED)
    val plannerOvernightType: StateFlow<OvernightType> = _plannerOvernightType.asStateFlow()

    private val _plannerDailyKm = MutableStateFlow(15.0)
    val plannerDailyKm: StateFlow<Double> = _plannerDailyKm.asStateFlow()

    private val _plannerStages = MutableStateFlow<List<TripStage>>(emptyList())
    val plannerStages: StateFlow<List<TripStage>> = _plannerStages.asStateFlow()

    private val _isCalculatingStages = MutableStateFlow(false)
    val isCalculatingStages: StateFlow<Boolean> = _isCalculatingStages.asStateFlow()

    val tileDownloadProgress: StateFlow<DownloadProgress> = OfflineTileDownloader.progress

    // Saved Plans
    val savedPlans: StateFlow<List<TripPlanEntity>> = repository.getAllTripPlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeTripPlan = MutableStateFlow<TripPlanEntity?>(null)
    val activeTripPlan: StateFlow<TripPlanEntity?> = _activeTripPlan.asStateFlow()

    // Navigation Live State from Service
    val navLiveState: StateFlow<NavigationLiveState> = NavigationTrackingService.liveState

    // Map layer visibility
    private val _showShelters = MutableStateFlow(true)
    val showShelters: StateFlow<Boolean> = _showShelters.asStateFlow()

    private val _showFriTeltning = MutableStateFlow(true)
    val showFriTeltning: StateFlow<Boolean> = _showFriTeltning.asStateFlow()

    private val _showToilets = MutableStateFlow(true)
    val showToilets: StateFlow<Boolean> = _showToilets.asStateFlow()

    private val _showWater = MutableStateFlow(true)
    val showWater: StateFlow<Boolean> = _showWater.asStateFlow()

    // Resupply & Provisioning Radar (New Feature 4)
    private val _showResupply = MutableStateFlow(true)
    val showResupply: StateFlow<Boolean> = _showResupply.asStateFlow()

    private val _corridorResupply = MutableStateFlow<List<ResupplyPoint>>(emptyList())
    val corridorResupply: StateFlow<List<ResupplyPoint>> = _corridorResupply.asStateFlow()

    // Transit Hub Connectors (Enhancement 13)
    private val _showTransit = MutableStateFlow(true)
    val showTransit: StateFlow<Boolean> = _showTransit.asStateFlow()

    private val _corridorTransit = MutableStateFlow<List<TransitStation>>(emptyList())
    val corridorTransit: StateFlow<List<TransitStation>> = _corridorTransit.asStateFlow()

    // Trail Hazards (Enhancement 7)
    private val _showHazards = MutableStateFlow(true)
    val showHazards: StateFlow<Boolean> = _showHazards.asStateFlow()

    private val _corridorHazards = MutableStateFlow<List<UserHazardNote>>(emptyList())
    val corridorHazards: StateFlow<List<UserHazardNote>> = _corridorHazards.asStateFlow()

    // Trail Surface & Footwear Advisor (New Feature 1)
    private val _trailSurfaceStats = MutableStateFlow<TrailSurfaceStats?>(null)
    val trailSurfaceStats: StateFlow<TrailSurfaceStats?> = _trailSurfaceStats.asStateFlow()

    // Danish 112 SOS & Rescue Post (Enhancement 14)
    private val _emergencyInfo = MutableStateFlow<DanishEmergencyInfo?>(null)
    val emergencyInfo: StateFlow<DanishEmergencyInfo?> = _emergencyInfo.asStateFlow()

    // Trekking Gear Checklist (Enhancement 15)
    private val _gearChecklist = MutableStateFlow<List<GearChecklistItem>>(emptyList())
    val gearChecklist: StateFlow<List<GearChecklistItem>> = _gearChecklist.asStateFlow()

    // Interactive Elevation Scrubbing (Enhancement 1)
    private val _scrubbedElevationKm = MutableStateFlow<Double?>(null)
    val scrubbedElevationKm: StateFlow<Double?> = _scrubbedElevationKm.asStateFlow()

    // Battery Saver HUD Mode (Enhancement 6)
    private val _isBatterySaverMode = MutableStateFlow(false)
    val isBatterySaverMode: StateFlow<Boolean> = _isBatterySaverMode.asStateFlow()

    // Day & Night Map Theme (Enhancement 8)
    private val _isDarkMapMode = MutableStateFlow(false)
    val isDarkMapMode: StateFlow<Boolean> = _isDarkMapMode.asStateFlow()

    // DMI Rain Radar (Enhancement 11)
    private val _showRainRadar = MutableStateFlow(false)
    val showRainRadar: StateFlow<Boolean> = _showRainRadar.asStateFlow()

    private val _rainRadarTimeMinutes = MutableStateFlow(0) // 0 to 90 min
    val rainRadarTimeMinutes: StateFlow<Int> = _rainRadarTimeMinutes.asStateFlow()

    // Granular Offline Buffer & Preview (Enhancement 10)
    private val _offlineCorridorBufferKm = MutableStateFlow(1.0)
    val offlineCorridorBufferKm: StateFlow<Double> = _offlineCorridorBufferKm.asStateFlow()

    private val _estimatedDownloadMb = MutableStateFlow(145)
    val estimatedDownloadMb: StateFlow<Int> = _estimatedDownloadMb.asStateFlow()

    // Stage Pace & Cadence Adjuster (Enhancement 9)
    private val _hikingSpeedKmh = MutableStateFlow(4.2)
    val hikingSpeedKmh: StateFlow<Double> = _hikingSpeedKmh.asStateFlow()

    private val _packWeightKg = MutableStateFlow(12.0)
    val packWeightKg: StateFlow<Double> = _packWeightKg.asStateFlow()

    // Selected POI for bottom sheet display
    private val _selectedPoiDetails = MutableStateFlow<Any?>(null) // ShelterEntity, FriTeltningEntity, or PoiEntity
    val selectedPoiDetails: StateFlow<Any?> = _selectedPoiDetails.asStateFlow()

    private val _nearestPoiResult = MutableStateFlow<NearestPoiResult?>(null)
    val nearestPoiResult: StateFlow<NearestPoiResult?> = _nearestPoiResult.asStateFlow()

    // Weather & Severe Meteorological Alerts for Route
    private val _trailWeather = MutableStateFlow<TrailWeatherInfo?>(null)
    val trailWeather: StateFlow<TrailWeatherInfo?> = _trailWeather.asStateFlow()

    // Interactive Map Route Distance Selection (Start / End selection directly on map)
    private val _tappedMapPointKm = MutableStateFlow<Double?>(null)
    val tappedMapPointKm: StateFlow<Double?> = _tappedMapPointKm.asStateFlow()

    // Fri Teltning Disclaimer Dialog state
    private val _showFriTeltningDisclaimer = MutableStateFlow(false)
    val showFriTeltningDisclaimer: StateFlow<Boolean> = _showFriTeltningDisclaimer.asStateFlow()

    val stagesMissingFriTeltning: StateFlow<List<TripStage>> = _plannerStages.map { stages ->
        stages.filter { it.stopType != "FRI_TELTNING" && it.dayNumber < stages.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.initializePreseededDataIfEmpty()
        }
    }

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onRegionSelected(region: String) {
        _selectedRegion.value = region
    }

    fun selectTrail(trail: TrailEntity) {
        _selectedTrail.value = trail
        viewModelScope.launch {
            val points = repository.parseTrailPoints(trail)
            _selectedTrailPoints.value = points
            _plannerStartKm.value = 0.0
            _plannerWalkDistanceKm.value = trail.lengthKm
            _plannerEndKm.value = trail.lengthKm
            _isWalkingReverse.value = false
            _plannerDays.value = maxOf(1, (trail.lengthKm / 15.0).toInt())
            _tappedMapPointKm.value = null

            // Load corridor data
            _corridorShelters.value = repository.getSheltersForTrail(trail.id)
            _corridorFriTeltning.value = repository.getFriTeltningForTrail(trail.id)
            _corridorPois.value = repository.getPoisForTrail(trail.id)
            _corridorResupply.value = repository.getResupplyPointsForTrail(trail.id)
            _corridorTransit.value = repository.getTransitStationsForTrail(trail.id)
            _corridorHazards.value = repository.getHazardsForTrail(trail.id)
            _trailSurfaceStats.value = repository.getSurfaceStatsForTrail(trail.id)

            // Fetch live weather & severe weather alerts for trail coordinates
            val firstPt = points.firstOrNull() ?: GeoPointDto(55.67, 12.56)
            val weather = repository.getTrailWeather(firstPt.lat, firstPt.lng)
            _trailWeather.value = weather

            _emergencyInfo.value = repository.getEmergencyInfo(firstPt.lat, firstPt.lng, trail.name)
            _gearChecklist.value = repository.getDefaultGearChecklist(_plannerOvernightType.value, weather.tempC)

            // Trigger POI corridor sync from Overpass in background
            repository.syncCorridorPois(trail)
            _corridorShelters.value = repository.getSheltersForTrail(trail.id)
            _corridorPois.value = repository.getPoisForTrail(trail.id)

            recalculateStages()
        }
        _currentScreen.value = AppScreen.TRAIL_DETAIL
    }

    // --- Enhanced Feature Controls ---
    fun setScrubbedElevationKm(km: Double?) {
        _scrubbedElevationKm.value = km
    }

    fun toggleResupply() {
        _showResupply.value = !_showResupply.value
    }

    fun toggleBatterySaverMode() {
        _isBatterySaverMode.value = !_isBatterySaverMode.value
    }

    fun toggleDarkMapMode() {
        _isDarkMapMode.value = !_isDarkMapMode.value
    }

    fun toggleRainRadar() {
        _showRainRadar.value = !_showRainRadar.value
    }

    fun setRainRadarTime(minutes: Int) {
        _rainRadarTimeMinutes.value = minutes.coerceIn(0, 90)
    }

    fun setOfflineBufferKm(km: Double) {
        _offlineCorridorBufferKm.value = km
        _estimatedDownloadMb.value = when {
            km <= 0.6 -> 75
            km <= 1.5 -> 145
            else -> 320
        }
    }

    fun setHikingSpeedKmh(speed: Double) {
        _hikingSpeedKmh.value = speed.coerceIn(2.5, 6.5)
    }

    fun setPackWeightKg(weight: Double) {
        _packWeightKg.value = weight.coerceIn(0.0, 30.0)
    }

    fun startAtTransitStation(station: TransitStation) {
        setPlannerStartKm(station.kmAlongTrail)
        Toast.makeText(getApplication(), "Start rute sat ved: ${station.name} (km ${String.format("%.1f", station.kmAlongTrail)})", Toast.LENGTH_SHORT).show()
    }

    fun addHazard(type: HazardType, title: String, description: String, lat: Double, lng: Double) {
        val trail = _selectedTrail.value ?: return
        val note = UserHazardNote(trailId = trail.id, lat = lat, lng = lng, type = type, title = title, description = description)
        repository.addHazardNote(note)
        _corridorHazards.value = repository.getHazardsForTrail(trail.id)
        Toast.makeText(getApplication(), "Farepunkt rapporteret og tilføjet kortet!", Toast.LENGTH_SHORT).show()
    }

    fun removeHazard(id: String) {
        val trail = _selectedTrail.value ?: return
        repository.removeHazardNote(id)
        _corridorHazards.value = repository.getHazardsForTrail(trail.id)
        Toast.makeText(getApplication(), "Farepunkt fjernet.", Toast.LENGTH_SHORT).show()
    }

    fun toggleGearItem(itemId: String) {
        _gearChecklist.value = _gearChecklist.value.map {
            if (it.id == itemId) it.copy(isChecked = !it.isChecked) else it
        }
    }

    fun updateStageEndKm(index: Int, newEndKm: Double) {
        val trail = _selectedTrail.value ?: return
        val currentStages = _plannerStages.value.toMutableList()
        if (index in currentStages.indices) {
            val clamped = newEndKm.coerceIn(currentStages[index].startKm + 1.0, trail.lengthKm)
            val updated = currentStages[index].copy(endKm = clamped, stageKm = clamped - currentStages[index].startKm)
            currentStages[index] = updated
            if (index + 1 < currentStages.size) {
                val next = currentStages[index + 1]
                val nextUpdated = next.copy(startKm = clamped, stageKm = maxOf(1.0, next.endKm - clamped))
                currentStages[index + 1] = nextUpdated
            }
            _plannerStages.value = currentStages
        }
    }

    fun reportWaterStatus(isWorking: Boolean) {
        Toast.makeText(
            getApplication(),
            if (isWorking) "Mange tak! Status opdateret: Drikkevandspost bekræftet aktiv i dag." else "Tak for melding! Status opdateret: Vandpost meldt lukket / ude af drift.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun exportTripGpx(context: android.content.Context) {
        val trail = _selectedTrail.value ?: return
        val stages = _plannerStages.value
        val points = _selectedTrailPoints.value
        val shelters = _corridorShelters.value
        val hazards = _corridorHazards.value

        val gpxXml = GpxExporter.generateMultiSegmentGpx(
            trail = trail,
            plan = _activeTripPlan.value,
            stages = stages,
            trailPoints = points,
            shelters = shelters,
            hazards = hazards
        )

        try {
            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TITLE, "${trail.name}.gpx")
                putExtra(android.content.Intent.EXTRA_TEXT, gpxXml)
                type = "application/gpx+xml"
            }
            val shareIntent = android.content.Intent.createChooser(sendIntent, "Eksporter GPX til Garmin, Suunto, Coros eller Apple Watch")
            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "GPX genereret (${gpxXml.length} tegn)", Toast.LENGTH_SHORT).show()
        }
    }

    fun onTrailMapTapped(lat: Double, lng: Double) {
        val points = _selectedTrailPoints.value
        if (points.isEmpty()) return
        val userPt = GeoPointDto(lat, lng)
        val snapped = GeoUtils.snapPointToPolyline(userPt, points)
        val distToTrailM = GeoUtils.distanceMeters(userPt, snapped)
        if (distToTrailM <= 2500.0) {
            val kmAlong = GeoUtils.distanceAlongPolylineKm(points, snapped)
            _tappedMapPointKm.value = kmAlong
        }
    }

    fun clearTappedMapPoint() {
        _tappedMapPointKm.value = null
    }

    fun setStartFromTappedPoint() {
        val km = _tappedMapPointKm.value ?: return
        setPlannerStartKm(km)
        _tappedMapPointKm.value = null
    }

    fun setEndFromTappedPoint() {
        val km = _tappedMapPointKm.value ?: return
        setPlannerEndKm(km)
        _tappedMapPointKm.value = null
    }

    fun requestStartNavigation() {
        val missing = _plannerStages.value.filter { it.stopType != "FRI_TELTNING" && it.dayNumber < _plannerStages.value.size }
        val overnight = _plannerOvernightType.value
        if (missing.isNotEmpty() && (overnight == OvernightType.TENT || overnight == OvernightType.MIXED)) {
            _showFriTeltningDisclaimer.value = true
        } else {
            saveCurrentPlanAndStart(startNavigation = true)
        }
    }

    fun dismissFriTeltningDisclaimer() {
        _showFriTeltningDisclaimer.value = false
    }

    fun confirmStartHikingWithDisclaimer() {
        _showFriTeltningDisclaimer.value = false
        saveCurrentPlanAndStart(startNavigation = true)
    }

    fun syncLiveTrails() {
        viewModelScope.launch {
            _isSyncing.value = true
            val res = repository.syncTrailsFromOverpass()
            _isSyncing.value = false
            res.onSuccess { count ->
                Toast.makeText(getApplication(), "Opdateret: $count nye stier fra Overpass", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(getApplication(), "Kunne ikke hente live stier: offline data bevaret", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Trip Planner Controls
    fun setPlannerStartKm(km: Double) {
        val trail = _selectedTrail.value ?: return
        val clampedStart = km.coerceIn(0.0, trail.lengthKm)
        _plannerStartKm.value = clampedStart
        val dist = _plannerWalkDistanceKm.value
        if (_isWalkingReverse.value) {
            _plannerEndKm.value = maxOf(0.0, clampedStart - dist)
        } else {
            _plannerEndKm.value = minOf(trail.lengthKm, clampedStart + dist)
        }
        recalculateStages()
    }

    fun setPlannerWalkDistanceKm(distKm: Double) {
        val trail = _selectedTrail.value ?: return
        val clampedDist = distKm.coerceIn(1.0, trail.lengthKm)
        _plannerWalkDistanceKm.value = clampedDist
        val start = _plannerStartKm.value
        if (_isWalkingReverse.value) {
            _plannerEndKm.value = maxOf(0.0, start - clampedDist)
        } else {
            _plannerEndKm.value = minOf(trail.lengthKm, start + clampedDist)
        }
        val days = maxOf(1, kotlin.math.ceil(clampedDist / _plannerDailyKm.value).toInt())
        _plannerDays.value = days
        recalculateStages()
    }

    fun setWalkingDirection(reverse: Boolean) {
        val trail = _selectedTrail.value ?: return
        _isWalkingReverse.value = reverse
        val start = _plannerStartKm.value
        val dist = _plannerWalkDistanceKm.value
        if (reverse) {
            _plannerEndKm.value = maxOf(0.0, start - dist)
        } else {
            _plannerEndKm.value = minOf(trail.lengthKm, start + dist)
        }
        recalculateStages()
    }

    fun setPlannerEndKm(km: Double) {
        val trail = _selectedTrail.value ?: return
        val clampedEnd = km.coerceIn(0.0, trail.lengthKm)
        _plannerEndKm.value = clampedEnd
        val dist = kotlin.math.abs(clampedEnd - _plannerStartKm.value)
        _plannerWalkDistanceKm.value = dist
        _isWalkingReverse.value = clampedEnd < _plannerStartKm.value
        recalculateStages()
    }

    fun setPlannerRange(start: Double, end: Double) {
        val trail = _selectedTrail.value ?: return
        val clampedStart = start.coerceIn(0.0, trail.lengthKm)
        val clampedEnd = end.coerceIn(0.0, trail.lengthKm)
        _plannerStartKm.value = clampedStart
        _plannerEndKm.value = clampedEnd
        val dist = kotlin.math.abs(clampedEnd - clampedStart)
        _plannerWalkDistanceKm.value = maxOf(0.5, dist)
        _isWalkingReverse.value = clampedEnd < clampedStart
        val days = maxOf(1, kotlin.math.ceil(maxOf(1.0, dist) / _plannerDailyKm.value).toInt())
        _plannerDays.value = days
        recalculateStages()
    }

    fun setPlannerDays(days: Int) {
        _plannerDays.value = days
        val dist = kotlin.math.abs(_plannerEndKm.value - _plannerStartKm.value)
        _plannerDailyKm.value = if (days > 0) dist / days else 15.0
        recalculateStages()
    }

    fun setPlannerOvernightType(type: OvernightType) {
        _plannerOvernightType.value = type
        recalculateStages()
    }

    fun setPlannerDailyKm(km: Double) {
        _plannerDailyKm.value = km
        val dist = kotlin.math.abs(_plannerEndKm.value - _plannerStartKm.value)
        if (km > 0) {
            _plannerDays.value = maxOf(1, kotlin.math.ceil(dist / km).toInt())
        }
        recalculateStages()
    }

    fun recalculateStages() {
        val trail = _selectedTrail.value ?: return
        viewModelScope.launch {
            _isCalculatingStages.value = true
            val stages = repository.calculateStages(
                trail = trail,
                startKm = _plannerStartKm.value,
                endKm = _plannerEndKm.value,
                isReverse = _plannerStartKm.value > _plannerEndKm.value,
                days = _plannerDays.value,
                overnightType = _plannerOvernightType.value,
                preferredDailyKm = _plannerDailyKm.value
            )
            _plannerStages.value = stages
            _isCalculatingStages.value = false
        }
    }

    fun updateStageStop(index: Int, newName: String, newType: String, lat: Double, lng: Double, bookingReq: Boolean) {
        val current = _plannerStages.value.toMutableList()
        if (index in current.indices) {
            val s = current[index]
            current[index] = s.copy(
                stopName = newName,
                stopType = newType,
                stopLat = lat,
                stopLng = lng,
                bookingRequired = bookingReq
            )
            _plannerStages.value = current
        }
    }

    fun addPlannerStage() {
        val current = _plannerStages.value
        val newDays = current.size + 1
        setPlannerDays(newDays)
    }

    fun removePlannerStage() {
        val current = _plannerStages.value
        if (current.size > 1) {
            setPlannerDays(current.size - 1)
        }
    }

    fun saveCurrentPlanAndStart(startNavigation: Boolean) {
        val trail = _selectedTrail.value ?: return
        viewModelScope.launch {
            val planId = repository.saveTripPlan(
                trail = trail,
                startKm = _plannerStartKm.value,
                endKm = _plannerEndKm.value,
                isReverse = _plannerStartKm.value > _plannerEndKm.value,
                days = _plannerDays.value,
                overnightType = _plannerOvernightType.value,
                preferredDailyKm = _plannerDailyKm.value,
                stages = _plannerStages.value
            )
            val savedPlan = repository.getTripPlanById(planId)
            _activeTripPlan.value = savedPlan

            if (startNavigation) {
                startNavigationService(planId, trail, _plannerStages.value)
                _currentScreen.value = AppScreen.NAVIGATION_MODE
            } else {
                Toast.makeText(getApplication(), "Vandreplan gemt!", Toast.LENGTH_SHORT).show()
                _currentScreen.value = AppScreen.SAVED_PLANS
            }
        }
    }

    fun startOfflineCacheDownload() {
        val points = _selectedTrailPoints.value
        if (points.isEmpty()) {
            Toast.makeText(getApplication(), "Vælg en sti først", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            Toast.makeText(getApplication(), "Henter offline kortfelter...", Toast.LENGTH_SHORT).show()
            OfflineTileDownloader.downloadRouteTiles(getApplication(), points)
            Toast.makeText(getApplication(), "Offline kort gemt for ruten!", Toast.LENGTH_SHORT).show()
        }
    }

    // Navigation Controls
    fun startNavigationService(planId: Long, trail: TrailEntity, stages: List<TripStage>) {
        val pointsJson = trail.pointsJson
        val stagesJson = repository.serializeStages(stages)
        NavigationTrackingService.start(
            context = getApplication(),
            planId = planId,
            trailName = trail.name,
            currentDay = 1,
            totalTripKm = kotlin.math.abs(_plannerEndKm.value - _plannerStartKm.value),
            todayEndKm = stages.firstOrNull()?.endKm ?: 15.0,
            pointsJson = pointsJson,
            stagesJson = stagesJson
        )
    }

    fun stopTrackingToday() {
        NavigationTrackingService.stopForToday(getApplication())
        Toast.makeText(getApplication(), "Dagens etape afsluttet. God hvile!", Toast.LENGTH_LONG).show()
    }

    fun resumeTripNavigation(plan: TripPlanEntity) {
        viewModelScope.launch {
            _activeTripPlan.value = plan
            val trail = repository.getTrailById(plan.trailId) ?: return@launch
            _selectedTrail.value = trail
            _selectedTrailPoints.value = repository.parseTrailPoints(trail)
            _corridorShelters.value = repository.getSheltersForTrail(trail.id)
            _corridorFriTeltning.value = repository.getFriTeltningForTrail(trail.id)
            _corridorPois.value = repository.getPoisForTrail(trail.id)

            val stages = repository.parseStages(plan.stagesJson)
            _plannerStages.value = stages
            _plannerStartKm.value = plan.startKm
            _plannerEndKm.value = plan.endKm
            _plannerDays.value = plan.targetDays
            _plannerWalkDistanceKm.value = plan.totalDistanceKm

            val state = navLiveState.value
            val progress = repository.resumeTrip(plan.id, state.currentLat, state.currentLng)

            NavigationTrackingService.start(
                context = getApplication(),
                planId = plan.id,
                trailName = plan.trailName,
                currentDay = progress?.currentDay ?: 1,
                totalTripKm = plan.totalDistanceKm,
                todayEndKm = stages.getOrNull((progress?.currentDay ?: 1) - 1)?.endKm ?: 15.0,
                pointsJson = trail.pointsJson,
                stagesJson = plan.stagesJson
            )
            _currentScreen.value = AppScreen.NAVIGATION_MODE
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            repository.deleteTripPlan(planId)
            Toast.makeText(getApplication(), "Plan slettet", Toast.LENGTH_SHORT).show()
        }
    }

    // Layer Toggles
    fun toggleShelters() { _showShelters.value = !_showShelters.value }
    fun toggleFriTeltning() { _showFriTeltning.value = !_showFriTeltning.value }
    fun toggleToilets() { _showToilets.value = !_showToilets.value }
    fun toggleWater() { _showWater.value = !_showWater.value }
    fun toggleTransit() { _showTransit.value = !_showTransit.value }
    fun toggleHazards() { _showHazards.value = !_showHazards.value }

    fun selectPoiForDetails(item: Any?) {
        _selectedPoiDetails.value = item
    }

    fun clearPoiDetails() {
        _selectedPoiDetails.value = null
        _nearestPoiResult.value = null
    }

    // Find nearest POI (Toilet, Water, Shelter)
    fun findNearest(type: String) {
        val userLat = navLiveState.value.currentLat
        val userLng = navLiveState.value.currentLng

        when (type.lowercase()) {
            "toilet" -> {
                val toilets = _corridorPois.value.filter { it.type == "toilet" }
                if (toilets.isEmpty()) {
                    Toast.makeText(getApplication(), "Ingen toiletter fundet langs denne del af ruten", Toast.LENGTH_SHORT).show()
                    return
                }
                val nearest = toilets.minByOrNull { GeoUtils.distanceMeters(userLat, userLng, it.lat, it.lng) }
                if (nearest != null) {
                    val dist = GeoUtils.distanceMeters(userLat, userLng, nearest.lat, nearest.lng)
                    _nearestPoiResult.value = NearestPoiResult(
                        poiName = nearest.name,
                        type = "Toilet",
                        distanceMeters = dist,
                        lat = nearest.lat,
                        lng = nearest.lng,
                        details = "Adgang: ${nearest.access} • Gebyr: ${if (nearest.fee) "Ja" else "Nej"}"
                    )
                }
            }
            "water" -> {
                val waters = _corridorPois.value.filter { it.type == "water" }
                if (waters.isEmpty()) {
                    Toast.makeText(getApplication(), "Ingen drikkevandsposter fundet langs ruten", Toast.LENGTH_SHORT).show()
                    return
                }
                val nearest = waters.minByOrNull { GeoUtils.distanceMeters(userLat, userLng, it.lat, it.lng) }
                if (nearest != null) {
                    val dist = GeoUtils.distanceMeters(userLat, userLng, nearest.lat, nearest.lng)
                    _nearestPoiResult.value = NearestPoiResult(
                        poiName = nearest.name,
                        type = "Drikkevand",
                        distanceMeters = dist,
                        lat = nearest.lat,
                        lng = nearest.lng,
                        details = "Drikkevand godkendt. Husk altid vandfilter/klor hvis i tvivl."
                    )
                }
            }
            "shelter" -> {
                val shelters = _corridorShelters.value
                if (shelters.isEmpty()) {
                    Toast.makeText(getApplication(), "Ingen shelters i nærheden", Toast.LENGTH_SHORT).show()
                    return
                }
                val nearest = shelters.minByOrNull { GeoUtils.distanceMeters(userLat, userLng, it.lat, it.lng) }
                if (nearest != null) {
                    val dist = GeoUtils.distanceMeters(userLat, userLng, nearest.lat, nearest.lng)
                    _nearestPoiResult.value = NearestPoiResult(
                        poiName = nearest.name,
                        type = "Shelter",
                        distanceMeters = dist,
                        lat = nearest.lat,
                        lng = nearest.lng,
                        details = "Ejer: ${nearest.ownership} • Booking påkrævet: ${if (nearest.bookingRequired) "Ja" else "Nej"} • Kapacitet: ${nearest.capacity} pers."
                    )
                }
            }
        }
    }
}
