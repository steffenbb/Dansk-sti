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
import com.example.data.model.TrailGeometryDto
import com.example.data.remote.TrailWeatherInfo
import com.example.data.repository.DanskStiRepository
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OvernightSpotItem(
    val id: String,
    val name: String,
    val type: String, // SHELTER, FRI_TELTNING
    val kmOnRoute: Double,
    val detourMeters: Double,
    val bookingRequired: Boolean,
    val hasWaterNearby: Boolean,
    val hasToiletNearby: Boolean,
    val lat: Double,
    val lng: Double,
    val description: String? = null
)

data class PoiSpotItem(
    val id: String,
    val name: String,
    val type: String, // water, toilet, shop, station, ferry
    val kmOnRoute: Double,
    val detourMeters: Double,
    val lat: Double,
    val lng: Double
)

class TrailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    private val _trail = MutableStateFlow<TrailEntity?>(null)
    val trail: StateFlow<TrailEntity?> = _trail.asStateFlow()

    private val _trailPoints = MutableStateFlow<List<GeoPointDto>>(emptyList())
    val trailPoints: StateFlow<List<GeoPointDto>> = _trailPoints.asStateFlow()

    private val _trailGeometry = MutableStateFlow<TrailGeometryDto?>(null)
    val trailGeometry: StateFlow<TrailGeometryDto?> = _trailGeometry.asStateFlow()

    private val _overnightSpots = MutableStateFlow<List<OvernightSpotItem>>(emptyList())
    val overnightSpots: StateFlow<List<OvernightSpotItem>> = _overnightSpots.asStateFlow()

    private val _waterAndToilets = MutableStateFlow<List<PoiSpotItem>>(emptyList())
    val waterAndToilets: StateFlow<List<PoiSpotItem>> = _waterAndToilets.asStateFlow()

    private val _shops = MutableStateFlow<List<PoiSpotItem>>(emptyList())
    val shops: StateFlow<List<PoiSpotItem>> = _shops.asStateFlow()

    private val _transports = MutableStateFlow<List<PoiSpotItem>>(emptyList())
    val transports: StateFlow<List<PoiSpotItem>> = _transports.asStateFlow()

    private val _elevationGain = MutableStateFlow(0)
    val elevationGain: StateFlow<Int> = _elevationGain.asStateFlow()

    private val _elevationLoss = MutableStateFlow(0)
    val elevationLoss: StateFlow<Int> = _elevationLoss.asStateFlow()

    private val _surfaceMix = MutableStateFlow<Map<String, Int>>(mapOf("Sti" to 70, "Grus" to 20, "Asfalt" to 10))
    val surfaceMix: StateFlow<Map<String, Int>> = _surfaceMix.asStateFlow()

    private val _weatherStart = MutableStateFlow<TrailWeatherInfo?>(null)
    val weatherStart: StateFlow<TrailWeatherInfo?> = _weatherStart.asStateFlow()

    private val _weatherEnd = MutableStateFlow<TrailWeatherInfo?>(null)
    val weatherEnd: StateFlow<TrailWeatherInfo?> = _weatherEnd.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadTrail(trailId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                val entity = repository.getTrailById(trailId) ?: return@withContext
                _trail.value = entity

                // Observe isSaved
                launch {
                    repository.isTrailSaved(trailId).collect { saved ->
                        _isSaved.value = saved
                    }
                }

                // Points & Geometry
                val points = repository.getTrailPoints(entity)
                _trailPoints.value = points
                val geom = repository.getTrailGeometry(trailId)
                _trailGeometry.value = geom

                // Elevation ascent/descent
                var gain = 0.0
                var loss = 0.0
                for (i in 0 until points.size - 1) {
                    val diff = points[i + 1].ele - points[i].ele
                    if (diff > 0) gain += diff else loss += kotlin.math.abs(diff)
                }
                _elevationGain.value = gain.toInt()
                _elevationLoss.value = loss.toInt()

                // Corridor Overnights & POIs
                val shelters = repository.getSheltersForTrail(trailId)
                val friTeltning = repository.getFriTeltningForTrail(trailId)
                val pois = repository.getPoisForTrail(trailId)

                // Sort overnights by projected km along trail
                val overnightList = mutableListOf<OvernightSpotItem>()
                for (s in shelters) {
                    val (wKm, dM) = projectOnPoints(points, s.lat, s.lng)
                    overnightList.add(
                        OvernightSpotItem(
                            id = s.id,
                            name = s.name,
                            type = "SHELTER",
                            kmOnRoute = wKm,
                            detourMeters = dM,
                            bookingRequired = s.bookingRequired,
                            hasWaterNearby = s.hasWaterNearby,
                            hasToiletNearby = s.hasToiletNearby,
                            lat = s.lat,
                            lng = s.lng,
                            description = s.description
                        )
                    )
                }
                for (ft in friTeltning) {
                    val (wKm, dM) = projectOnPoints(points, ft.centerLat, ft.centerLng)
                    overnightList.add(
                        OvernightSpotItem(
                            id = ft.id,
                            name = ft.name,
                            type = "FRI_TELTNING",
                            kmOnRoute = wKm,
                            detourMeters = dM,
                            bookingRequired = false,
                            hasWaterNearby = true,
                            hasToiletNearby = false,
                            lat = ft.centerLat,
                            lng = ft.centerLng,
                            description = ft.rulesSummary ?: "Fri teltning efter 1-2-3 reglen"
                        )
                    )
                }
                overnightList.sortBy { it.kmOnRoute }
                _overnightSpots.value = overnightList

                // Sort POIs
                val waterToilets = mutableListOf<PoiSpotItem>()
                val shopList = mutableListOf<PoiSpotItem>()
                val transList = mutableListOf<PoiSpotItem>()

                for (p in pois) {
                    val (wKm, dM) = projectOnPoints(points, p.lat, p.lng)
                    val item = PoiSpotItem(
                        id = p.id,
                        name = p.name,
                        type = p.type,
                        kmOnRoute = wKm,
                        detourMeters = dM,
                        lat = p.lat,
                        lng = p.lng
                    )
                    when (p.type) {
                        "water", "toilet" -> waterToilets.add(item)
                        "shop", "supermarket", "convenience", "bakery", "farm" -> shopList.add(item)
                        "station", "ferry", "bus_stop" -> transList.add(item)
                    }
                }
                waterToilets.sortBy { it.kmOnRoute }
                shopList.sortBy { it.kmOnRoute }
                transList.sortBy { it.kmOnRoute }

                _waterAndToilets.value = waterToilets
                _shops.value = shopList
                _transports.value = transList

                // Weather at start and end
                val firstPt = points.firstOrNull()
                val lastPt = points.lastOrNull()
                if (firstPt != null) {
                    _weatherStart.value = repository.getTrailWeather(firstPt.lat, firstPt.lng)
                }
                if (lastPt != null) {
                    _weatherEnd.value = repository.getTrailWeather(lastPt.lat, lastPt.lng)
                }
            }
            _isLoading.value = false
        }
    }

    private fun projectOnPoints(points: List<GeoPointDto>, lat: Double, lng: Double): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, 0.0)
        var minDist = Double.MAX_VALUE
        var bestKm = 0.0
        var cumM = 0.0

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segLen = GeoUtils.distanceMeters(a.lat, a.lng, b.lat, b.lng)
            if (segLen <= 8000.0) {
                val d = GeoUtils.distanceMeters(lat, lng, a.lat, a.lng)
                if (d < minDist) {
                    minDist = d
                    bestKm = cumM / 1000.0
                }
                cumM += segLen
            }
        }
        return Pair(bestKm, minDist)
    }

    fun toggleSave() {
        val t = _trail.value ?: return
        viewModelScope.launch {
            repository.toggleSaveTrail(t.id)
        }
    }
}
