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

    init {
        loadOvernightCountsAndGeometries()
    }

    private fun loadOvernightCountsAndGeometries() {
        viewModelScope.launch {
            allTrails.collect { trails ->
                if (trails.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        val countsMap = mutableMapOf<String, TrailOvernightCounts>()
                        val geomMap = mutableMapOf<String, List<GeoPointDto>>()

                        for (trail in trails) {
                            try {
                                val shelters = repository.getSheltersForTrail(trail.id)
                                val friTeltning = repository.getFriTeltningForTrail(trail.id)
                                countsMap[trail.id] = TrailOvernightCounts(
                                    sheltersCount = shelters.size,
                                    friTeltningCount = friTeltning.size
                                )
                            } catch (_: Exception) {}

                            try {
                                val pts = repository.getTrailPoints(trail)
                                if (pts.isNotEmpty()) {
                                    geomMap[trail.id] = pts
                                }
                            } catch (_: Exception) {}
                        }
                        _trailCounts.value = countsMap
                        _allTrailGeometries.value = geomMap
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
}
