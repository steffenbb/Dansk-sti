package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.data.repository.DanskStiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LengthFilter(val label: String) {
    ALL("Alle længder"),
    UNDER_50("Under 50 km"),
    FROM_50_TO_150("50 - 150 km"),
    OVER_150("Over 150 km")
}

enum class OvernightFilter(val label: String) {
    ALL("Alle overnatninger"),
    SHELTER("Shelter"),
    FRI_TELTNING("Fri teltning")
}

data class TrailOvernightCounts(
    val sheltersCount: Int = 0,
    val friTeltningCount: Int = 0
)

class ExploreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    val allTrails: StateFlow<List<TrailEntity>> = repository.getAllTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedRegion = MutableStateFlow<String?>("Alle")
    val selectedRegion: StateFlow<String?> = _selectedRegion.asStateFlow()

    private val _lengthFilter = MutableStateFlow(LengthFilter.ALL)
    val lengthFilter: StateFlow<LengthFilter> = _lengthFilter.asStateFlow()

    private val _overnightFilter = MutableStateFlow(OvernightFilter.ALL)
    val overnightFilter: StateFlow<OvernightFilter> = _overnightFilter.asStateFlow()

    private val _trailCounts = MutableStateFlow<Map<String, TrailOvernightCounts>>(emptyMap())
    val trailCounts: StateFlow<Map<String, TrailOvernightCounts>> = _trailCounts.asStateFlow()

    private val _allTrailGeometries = MutableStateFlow<Map<String, List<GeoPointDto>>>(emptyMap())
    val allTrailGeometries: StateFlow<Map<String, List<GeoPointDto>>> = _allTrailGeometries.asStateFlow()

    private val _selectedTrailForPeek = MutableStateFlow<TrailEntity?>(null)
    val selectedTrailForPeek: StateFlow<TrailEntity?> = _selectedTrailForPeek.asStateFlow()

    // Phase 10: Access Layer (Naturbeskyttelsesloven)
    private val _isAccessLayerVisible = MutableStateFlow(false)
    val isAccessLayerVisible: StateFlow<Boolean> = _isAccessLayerVisible.asStateFlow()

    private val _accessPolygons = MutableStateFlow<List<com.example.data.model.access.AccessPolygon>>(emptyList())
    val accessPolygons: StateFlow<List<com.example.data.model.access.AccessPolygon>> = _accessPolygons.asStateFlow()

    private val _selectedAccessPolygon = MutableStateFlow<com.example.data.model.access.AccessPolygon?>(null)
    val selectedAccessPolygon: StateFlow<com.example.data.model.access.AccessPolygon?> = _selectedAccessPolygon.asStateFlow()

    fun toggleAccessLayer() {
        val next = !_isAccessLayerVisible.value
        _isAccessLayerVisible.value = next
        if (next && _accessPolygons.value.isEmpty()) {
            loadAccessPolygons(54.5, 57.8, 8.0, 15.2)
        }
    }

    fun selectAccessPolygon(poly: com.example.data.model.access.AccessPolygon?) {
        _selectedAccessPolygon.value = poly
    }

    fun loadAccessPolygons(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        viewModelScope.launch {
            try {
                val polys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                _accessPolygons.value = polys
            } catch (e: Exception) {
                android.util.Log.w("ExploreViewModel", "Failed to load access polygons: ${e.message}")
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                repository.initializePreseededDataIfEmpty()
            } catch (e: Exception) {
                android.util.Log.e("ExploreViewModel", "Failed to initialize preseeded data: ${e.message}", e)
            }
        }
        loadOvernightCountsAndGeometries()
    }

    private fun loadOvernightCountsAndGeometries() {
        viewModelScope.launch {
            allTrails.collect { trails ->
                if (trails.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        // 1. Immediately calculate and publish overnight counts from local Room DB
                        val countsMap = mutableMapOf<String, TrailOvernightCounts>()
                        for (trail in trails) {
                            try {
                                val shelters = repository.getSheltersForTrail(trail.id)
                                val friTeltning = repository.getFriTeltningForTrail(trail.id)
                                countsMap[trail.id] = TrailOvernightCounts(
                                    sheltersCount = shelters.size,
                                    friTeltningCount = friTeltning.size
                                )
                            } catch (_: Exception) {}
                        }
                        _trailCounts.value = countsMap

                        // 2. Populate cached trail geometries (do not flood OSM network on startup)
                        val currentGeoms = _allTrailGeometries.value.toMutableMap()
                        for (trail in trails) {
                            if (currentGeoms.containsKey(trail.id)) continue

                            try {
                                val geom = repository.getTrailGeometry(trail.id)
                                val pts = geom?.segments?.flatMap { it.points } ?: emptyList()
                                if (pts.isNotEmpty()) {
                                    currentGeoms[trail.id] = pts
                                    _allTrailGeometries.value = currentGeoms.toMap()
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("ExploreViewModel", "Error loading cached points for ${trail.id}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    private data class TrailFilterParams(
        val query: String,
        val region: String?,
        val length: LengthFilter,
        val overnight: OvernightFilter
    )

    private val _filterParams = combine(
        _searchQuery,
        _selectedRegion,
        _lengthFilter,
        _overnightFilter
    ) { query, region, length, overnight ->
        TrailFilterParams(query, region, length, overnight)
    }

    val filteredTrails: StateFlow<List<TrailEntity>> = combine(
        allTrails,
        _filterParams,
        _trailCounts
    ) { trails: List<TrailEntity>, filter: TrailFilterParams, counts: Map<String, TrailOvernightCounts> ->
        trails.filter { trail ->
            // Search query matches name, description, or region
            val matchesQuery = filter.query.isBlank() ||
                    trail.name.contains(filter.query, ignoreCase = true) ||
                    trail.description.contains(filter.query, ignoreCase = true) ||
                    trail.region.contains(filter.query, ignoreCase = true) ||
                    trail.parentRegion.contains(filter.query, ignoreCase = true)

            // Region filter
            val matchesRegion = filter.region == null || filter.region == "Alle" ||
                    trail.parentRegion.equals(filter.region, ignoreCase = true) ||
                    trail.region.equals(filter.region, ignoreCase = true)

            // Length filter
            val km = trail.computedWalkingKm
            val matchesLength = when (filter.length) {
                LengthFilter.ALL -> true
                LengthFilter.UNDER_50 -> km < 50.0
                LengthFilter.FROM_50_TO_150 -> km in 50.0..150.0
                LengthFilter.OVER_150 -> km > 150.0
            }

            // Overnight filter
            val countInfo = counts[trail.id] ?: TrailOvernightCounts()
            val matchesOvernight = when (filter.overnight) {
                OvernightFilter.ALL -> true
                OvernightFilter.SHELTER -> countInfo.sheltersCount > 0
                OvernightFilter.FRI_TELTNING -> countInfo.friTeltningCount > 0
            }

            matchesQuery && matchesRegion && matchesLength && matchesOvernight
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

    fun setShowNearbySheet(show: Boolean) {
        _showNearbySheet.value = show
    }

    fun findNearbyOvernights(lat: Double, lng: Double) {
        viewModelScope.launch {
            _isSearchingNearby.value = true
            _showNearbySheet.value = true
            val spots = repository.getNearbyOvernightSpots(lat, lng, 10.0)
            _nearbySpots.value = spots
            _isSearchingNearby.value = false
        }
    }

    // --- Phase 13: Map Layers & Marked Routes ---
    val settingsRepository = com.example.data.local.SettingsRepository(getApplication())
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
