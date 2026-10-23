package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.remote.PlaceSearchResult
import com.example.data.repository.DanskStiRepository
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LengthFilter(val label: String) {
    ALL("Alle længder"),
    SHORT("< 50 km"),
    MEDIUM("50–150 km"),
    LONG("> 150 km")
}

enum class OvernightFilter(val label: String) {
    ALL("Alle"),
    SHELTERS("Shelters undervejs"),
    FRI_TELTNING("Fri teltning")
}

data class TrailCardMetrics(
    val shelterCount: Int = 0,
    val friTeltningCount: Int = 0,
    val ascentMeters: Int = 0,
    val estimatedDays: Int = 1,
    val geometryPoints: List<GeoPointDto> = emptyList()
)

@OptIn(FlowPreview::class)
class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository
    val settingsRepository = com.example.data.local.SettingsRepository(getApplication())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedRegion = MutableStateFlow<String?>(null)
    val selectedRegion: StateFlow<String?> = _selectedRegion.asStateFlow()

    private val _lengthFilter = MutableStateFlow(LengthFilter.ALL)
    val lengthFilter: StateFlow<LengthFilter> = _lengthFilter.asStateFlow()

    private val _overnightFilter = MutableStateFlow(OvernightFilter.ALL)
    val overnightFilter: StateFlow<OvernightFilter> = _overnightFilter.asStateFlow()

    private val _flerdagesOnly = MutableStateFlow(false)
    val flerdagesOnly: StateFlow<Boolean> = _flerdagesOnly.asStateFlow()

    private val _sheltersUndervejs = MutableStateFlow(false)
    val sheltersUndervejs: StateFlow<Boolean> = _sheltersUndervejs.asStateFlow()

    private val _friTeltningOnly = MutableStateFlow(false)
    val friTeltningOnly: StateFlow<Boolean> = _friTeltningOnly.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val allTrails: StateFlow<List<TrailEntity>> = repository.getAllTrails().map { list ->
        if (list.isEmpty()) com.example.data.local.PreseededData.getInitialTrails() else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.local.PreseededData.getInitialTrails())

    val savedTrails: StateFlow<List<TrailEntity>> = repository.getSavedTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedTrailIds: StateFlow<Set<String>> = combine(savedTrails) { (saved) ->
        saved.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val allShelters: StateFlow<List<ShelterEntity>> = repository.getAllSheltersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFriTeltning: StateFlow<List<FriTeltningEntity>> = repository.getAllFriTeltningFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPois: StateFlow<List<PoiEntity>> = repository.getAllPoisFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _shelterCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val shelterCounts: StateFlow<Map<String, Int>> = _shelterCounts.asStateFlow()

    private val _friTeltningCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val friTeltningCounts: StateFlow<Map<String, Int>> = _friTeltningCounts.asStateFlow()

    private val _trailMetrics = MutableStateFlow<Map<String, TrailCardMetrics>>(emptyMap())
    val trailMetrics: StateFlow<Map<String, TrailCardMetrics>> = _trailMetrics.asStateFlow()

    private val _placeSuggestions = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val placeSuggestions: StateFlow<List<PlaceSearchResult>> = _placeSuggestions.asStateFlow()

    private val _selectedTrailForPeek = MutableStateFlow<TrailEntity?>(null)
    val selectedTrailForPeek: StateFlow<TrailEntity?> = _selectedTrailForPeek.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    fun updateUserLocation(lat: Double, lng: Double) {
        val safeLat = if (lat in 54.4..58.0 && lng in 7.8..15.5) lat else 56.05
        val safeLng = if (lat in 54.4..58.0 && lng in 7.8..15.5) lng else 9.65
        _userLocation.value = Pair(safeLat, safeLng)
    }

    init {
        loadMetadataAndCounts()

        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    if (query.trim().length >= 2) {
                        _placeSuggestions.value = repository.searchPlacesInDenmark(query)
                    } else {
                        _placeSuggestions.value = emptyList()
                    }
                }
        }
    }

    fun loadMetadataAndCounts() {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                withContext(Dispatchers.IO) {
                    repository.initializePreseededDataIfEmpty()
                }
                allTrails.collect { trails ->
                    if (trails.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val sCounts = mutableMapOf<String, Int>()
                            val fCounts = mutableMapOf<String, Int>()
                            val metricsMap = mutableMapOf<String, TrailCardMetrics>()
                            val allFt = repository.getAllFriTeltning()

                            for (t in trails) {
                                val sCount = repository.getShelterCountForTrail(t.id)
                                sCounts[t.id] = sCount

                                val fCount = if (t.bboxSouth != null && t.bboxNorth != null && t.bboxWest != null && t.bboxEast != null) {
                                    allFt.count { ft ->
                                        ft.centerLat in (t.bboxSouth - 0.04)..(t.bboxNorth + 0.04) &&
                                            ft.centerLng in (t.bboxWest - 0.06)..(t.bboxEast + 0.06)
                                    }
                                } else {
                                    allFt.count { ft -> ft.trailCorridorId == t.id || ft.name.contains(t.region, ignoreCase = true) }
                                }
                                fCounts[t.id] = fCount

                                val geom = try {
                                    repository.getTrailGeometry(t.id)
                                } catch (_: Exception) {
                                    null
                                }
                                val pts = geom?.segments?.flatMap { it.points } ?: emptyList()
                                val ascentM = geom?.totalAscentM ?: (t.lengthKm * 8.5).toInt()
                                val estDays = kotlin.math.ceil(t.lengthKm / 20.0).toInt().coerceAtLeast(1)

                                metricsMap[t.id] = TrailCardMetrics(
                                    shelterCount = sCount,
                                    friTeltningCount = fCount,
                                    ascentMeters = ascentM,
                                    estimatedDays = estDays,
                                    geometryPoints = pts
                                )
                            }
                            _shelterCounts.value = sCounts
                            _friTeltningCounts.value = fCounts
                            _trailMetrics.value = metricsMap
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Kunne ikke indlæse ruter. Tjek din forbindelse og prøv igen."
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    val filteredTrails: StateFlow<List<TrailEntity>> = combine(
        allTrails,
        _searchQuery,
        _selectedRegion,
        _lengthFilter,
        _overnightFilter,
        _flerdagesOnly,
        _sheltersUndervejs,
        _friTeltningOnly,
        _shelterCounts,
        _friTeltningCounts
    ) { args: Array<Any?> ->
        val trails = args[0] as List<TrailEntity>
        val query = args[1] as String
        val region = args[2] as String?
        val length = args[3] as LengthFilter
        val overnight = args[4] as OvernightFilter
        val flerdages = args[5] as Boolean
        val sheltersToggle = args[6] as Boolean
        val friTeltningToggle = args[7] as Boolean
        val sCounts = args[8] as Map<String, Int>
        val fCounts = args[9] as Map<String, Int>

        trails.filter { trail ->
            val matchesQuery = query.isBlank() ||
                trail.name.contains(query, ignoreCase = true) ||
                trail.region.contains(query, ignoreCase = true) ||
                trail.description.contains(query, ignoreCase = true)

            val matchesRegion = region == null ||
                trail.region.equals(region, ignoreCase = true) ||
                trail.parentRegion.equals(region, ignoreCase = true) ||
                (region.startsWith("Fyn", ignoreCase = true) &&
                    (trail.region.startsWith("Fyn", ignoreCase = true) ||
                        trail.parentRegion.startsWith("Fyn", ignoreCase = true)))

            val matchesLength = when (length) {
                LengthFilter.ALL -> true
                LengthFilter.SHORT -> trail.lengthKm < 50.0
                LengthFilter.MEDIUM -> trail.lengthKm in 50.0..150.0
                LengthFilter.LONG -> trail.lengthKm > 150.0
            }

            val matchesFlerdages = !flerdages || trail.lengthKm >= 25.0

            val hasShelters = (sCounts[trail.id] ?: 0) > 0
            val hasFriTeltning = (fCounts[trail.id] ?: 0) > 0

            val matchesOvernightEnum = when (overnight) {
                OvernightFilter.ALL -> true
                OvernightFilter.SHELTERS -> hasShelters
                OvernightFilter.FRI_TELTNING -> hasFriTeltning
            }

            val matchesSheltersChip = !sheltersToggle || hasShelters
            val matchesFriTeltningChip = !friTeltningToggle || hasFriTeltning

            matchesQuery && matchesRegion && matchesLength && matchesFlerdages &&
                matchesOvernightEnum && matchesSheltersChip && matchesFriTeltningChip
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nearbyTrails: StateFlow<List<TrailEntity>> = combine(
        filteredTrails,
        _userLocation
    ) { trails, loc ->
        if (loc == null) {
            emptyList()
        } else {
            val (uLat, uLng) = loc
            trails.sortedBy { t ->
                val cLat = if (t.bboxSouth != null && t.bboxNorth != null) (t.bboxSouth + t.bboxNorth) / 2.0 else 56.0
                val cLng = if (t.bboxWest != null && t.bboxEast != null) (t.bboxWest + t.bboxEast) / 2.0 else 10.0
                GeoUtils.haversineMeters(uLat, uLng, cLat, cLng)
            }.take(4)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedRegion(region: String?) {
        _selectedRegion.value = region
    }

    fun setLengthFilter(filter: LengthFilter) {
        _lengthFilter.value = filter
    }

    fun setOvernightFilter(filter: OvernightFilter) {
        _overnightFilter.value = filter
        _sheltersUndervejs.value = (filter == OvernightFilter.SHELTERS)
        _friTeltningOnly.value = (filter == OvernightFilter.FRI_TELTNING)
    }

    fun toggleFlerdagesOnly() {
        _flerdagesOnly.value = !_flerdagesOnly.value
    }

    fun setFlerdagesOnly(enabled: Boolean) {
        _flerdagesOnly.value = enabled
    }

    fun toggleSheltersUndervejs() {
        _sheltersUndervejs.value = !_sheltersUndervejs.value
    }

    fun toggleFriTeltningOnly() {
        _friTeltningOnly.value = !_friTeltningOnly.value
    }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _selectedRegion.value = null
        _lengthFilter.value = LengthFilter.ALL
        _overnightFilter.value = OvernightFilter.ALL
        _flerdagesOnly.value = false
        _sheltersUndervejs.value = false
        _friTeltningOnly.value = false
    }

    fun toggleBookmark(trailId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val isCurrentlySaved = savedTrailIds.value.contains(trailId)
            if (isCurrentlySaved) {
                repository.unsaveTrail(trailId)
            } else {
                repository.saveTrail(trailId)
            }
        }
    }

    fun selectTrailForPeek(trail: TrailEntity?) {
        _selectedTrailForPeek.value = trail
    }

    // --- Phase 7b: Shelters Near Me (< 10 km) ---
    private val _nearbySpots = MutableStateFlow<List<com.example.data.model.NearbyOvernightSpot>>(emptyList())
    val nearbySpots: StateFlow<List<com.example.data.model.NearbyOvernightSpot>> = _nearbySpots.asStateFlow()

    private val _isSearchingNearby = MutableStateFlow(false)
    val isSearchingNearby: StateFlow<Boolean> = _isSearchingNearby.asStateFlow()

    private val _showNearbySheet = MutableStateFlow(false)
    val showNearbySheet: StateFlow<Boolean> = _showNearbySheet.asStateFlow()

    private val _isNearbyUsingMapCenter = MutableStateFlow(false)
    val isNearbyUsingMapCenter: StateFlow<Boolean> = _isNearbyUsingMapCenter.asStateFlow()

    fun setShowNearbySheet(show: Boolean) {
        _showNearbySheet.value = show
    }

    fun findNearbyOvernights(lat: Double, lng: Double, isMapCenter: Boolean = false) {
        viewModelScope.launch {
            _isSearchingNearby.value = true
            _isNearbyUsingMapCenter.value = isMapCenter
            _showNearbySheet.value = true
            val spots = repository.getNearbyOvernightSpots(lat, lng, 10.0)
            _nearbySpots.value = spots
            _isSearchingNearby.value = false
        }
    }

    // --- Phase 13: Map Layers & Marked Routes ---
    val mapLayersState: StateFlow<com.example.data.local.MapLayersState> = settingsRepository.mapLayersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.local.MapLayersState())

    private val _showLayersSheet = MutableStateFlow(false)
    val showLayersSheet: StateFlow<Boolean> = _showLayersSheet.asStateFlow()

    private val _selectedMarkedRoute = MutableStateFlow<com.example.data.model.MarkedRouteDto?>(null)
    val selectedMarkedRoute: StateFlow<com.example.data.model.MarkedRouteDto?> = _selectedMarkedRoute.asStateFlow()

    fun setShowLayersSheet(show: Boolean) {
        _showLayersSheet.value = show
    }

    fun selectMarkedRoute(route: com.example.data.model.MarkedRouteDto?) {
        _selectedMarkedRoute.value = route
    }

    fun setBaseMap(type: com.example.data.local.BaseMapType) {
        viewModelScope.launch {
            settingsRepository.setBaseMap(type)
        }
    }

    fun setOverlayMarkedRoutes(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayMarkedRoutes(enabled)
        }
    }

    fun setOverlayAccess(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayAccess(enabled)
        }
    }

    fun setOverlayShelters(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayShelters(enabled)
        }
    }

    fun setOverlayFriTeltning(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayFriTeltning(enabled)
        }
    }

    fun setOverlayWater(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayWater(enabled)
        }
    }

    fun setOverlayToilets(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayToilets(enabled)
        }
    }

    fun setOverlayShops(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayShops(enabled)
        }
    }

    fun setOverlayTransport(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayTransport(enabled)
        }
    }

    fun setOverlayMyPoints(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOverlayMyPoints(enabled)
        }
    }

    suspend fun saveMarkedRouteAsTrail(markedRoute: com.example.data.model.MarkedRouteDto): Result<TrailEntity> {
        return repository.saveMarkedRouteAsTrail(markedRoute)
    }
}
