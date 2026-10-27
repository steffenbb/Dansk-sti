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
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.remote.TrailWeatherInfo
import com.example.data.repository.DanskStiRepository
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    val bookingUrl: String? = null,
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

    private val _surfaceMix = MutableStateFlow<Map<String, Int>>(emptyMap())
    val surfaceMix: StateFlow<Map<String, Int>> = _surfaceMix.asStateFlow()

    private val _weatherStart = MutableStateFlow<TrailWeatherInfo?>(null)
    val weatherStart: StateFlow<TrailWeatherInfo?> = _weatherStart.asStateFlow()

    private val _weatherEnd = MutableStateFlow<TrailWeatherInfo?>(null)
    val weatherEnd: StateFlow<TrailWeatherInfo?> = _weatherEnd.asStateFlow()

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    private val _isFetchingLiveRoute = MutableStateFlow(false)
    val isFetchingLiveRoute: StateFlow<Boolean> = _isFetchingLiveRoute.asStateFlow()

    private val _isSyncingPois = MutableStateFlow(false)
    val isSyncingPois: StateFlow<Boolean> = _isSyncingPois.asStateFlow()

    private val _longestGapWithoutWaterKm = MutableStateFlow(0.0)
    val longestGapWithoutWaterKm: StateFlow<Double> = _longestGapWithoutWaterKm.asStateFlow()

    private val _longestGapWithoutOvernightKm = MutableStateFlow(0.0)
    val longestGapWithoutOvernightKm: StateFlow<Double> = _longestGapWithoutOvernightKm.asStateFlow()

    private val _downloadedTrail = MutableStateFlow<com.example.data.model.DownloadedTrailEntity?>(null)
    val downloadedTrail: StateFlow<com.example.data.model.DownloadedTrailEntity?> = _downloadedTrail.asStateFlow()

    private val _createdNavigationPlanId = MutableStateFlow<Long?>(null)
    val createdNavigationPlanId: StateFlow<Long?> = _createdNavigationPlanId.asStateFlow()

    private val settingsRepository = com.example.data.local.SettingsRepository(application)

    private var currentTrailId: String? = null

    fun retryLoadRoute() {
        val tid = currentTrailId ?: _trail.value?.id ?: return
        loadTrail(tid)
    }

    fun loadTrail(trailId: String) {
        currentTrailId = trailId
        viewModelScope.launch {
            _isLoading.value = true
            _routeError.value = null
            withContext(Dispatchers.IO) {
                val entity = repository.getTrailById(trailId) ?: run {
                    _routeError.value = "Ruten blev ikke fundet i databasen"
                    return@withContext
                }
                _trail.value = entity
                _downloadedTrail.value = repository.getDownloadedTrail(trailId)

                // Observe isSaved
                launch {
                    repository.isTrailSaved(trailId).collect { saved ->
                        _isSaved.value = saved
                    }
                }

                // Points & Geometry from OSM live or disk cache
                _isFetchingLiveRoute.value = !repository.geometryRepository.isGeometryCached(trailId)
                val segments = try {
                    repository.getTrailPoints(entity)
                } catch (e: Exception) {
                    android.util.Log.e("TrailViewModel", "Failed to get points for ${entity.name}: ${e.message}", e)
                    emptyList()
                } finally {
                    _isFetchingLiveRoute.value = false
                }

                val points = segments.flatMap { it.points }
                _trailPoints.value = points
                val geom = repository.getTrailGeometry(trailId)
                _trailGeometry.value = geom
                repository.getTrailById(trailId)?.let { _trail.value = it }

                if (points.isEmpty()) {
                    _routeError.value = "Kunne ikke hente rutedata fra OpenStreetMap. Tjek din internetforbindelse eller prøv igen senere."
                } else {
                    _routeError.value = null
                }

                // Elevation ascent/descent
                _elevationGain.value = geom?.totalAscentM ?: 0
                _elevationLoss.value = geom?.totalDescentM ?: 0

                val allRanges = segments.filter { it.type == TrailSegmentType.WALK }.flatMap { it.wayTagRanges }
                if (allRanges.isNotEmpty()) {
                    var stiKm = 0.0
                    var grusKm = 0.0
                    var asfaltKm = 0.0
                    var ukendtKm = 0.0
                    for (r in allRanges) {
                        val rKm = (r.toKm - r.fromKm).coerceAtLeast(0.0)
                        val hw = r.highway?.trim()?.lowercase() ?: ""
                        val surf = r.surface?.trim()?.lowercase() ?: ""
                        when {
                            hw.isEmpty() && surf.isEmpty() -> ukendtKm += rKm
                            surf in listOf("asphalt", "paved", "concrete", "paving_stones") ||
                                hw in listOf("residential", "living_street", "service", "unclassified", "tertiary", "secondary", "primary", "trunk") -> asfaltKm += rKm
                            surf in listOf("gravel", "fine_gravel", "compacted") || hw == "track" -> grusKm += rKm
                            else -> stiKm += rKm
                        }
                    }
                    val sumKm = (stiKm + grusKm + asfaltKm + ukendtKm).takeIf { it > 0.0 } ?: 1.0
                    val rawKm = linkedMapOf(
                        "Sti" to stiKm,
                        "Grus" to grusKm,
                        "Asfalt" to asfaltKm,
                        "Ukendt" to ukendtKm
                    )
                    val pctMap = linkedMapOf<String, Int>()
                    for ((key, km) in rawKm) {
                        pctMap[key] = if (km <= 0.0) 0 else Math.round(km / sumKm * 100.0).toInt().coerceIn(0, 100)
                    }
                    val remainder = 100 - pctMap.values.sum()
                    if (remainder != 0) {
                        val largestKey = rawKm.maxByOrNull { it.value }?.key
                        if (largestKey != null && (rawKm[largestKey] ?: 0.0) > 0.0) {
                            pctMap[largestKey] = ((pctMap[largestKey] ?: 0) + remainder).coerceIn(0, 100)
                        }
                    }
                    val mix = linkedMapOf(
                        "Sti" to (pctMap["Sti"] ?: 0),
                        "Grus" to (pctMap["Grus"] ?: 0),
                        "Asfalt" to (pctMap["Asfalt"] ?: 0)
                    )
                    val ukendtPct = pctMap["Ukendt"] ?: 0
                    if (ukendtPct > 0) {
                        mix["Ukendt"] = ukendtPct
                    }
                    _surfaceMix.value = mix
                } else {
                    _surfaceMix.value = emptyMap()
                }

                // Corridor Overnights & POIs (only route's own corridor POIs, never whole DB)
                var shelters = if (points.isNotEmpty()) repository.getSheltersForTrail(trailId) else emptyList()
                val friTeltning = if (points.isNotEmpty()) repository.getFriTeltningForTrail(trailId) else emptyList()
                var pois = if (points.isNotEmpty()) repository.getPoisForTrail(trailId) else emptyList()
                if (shelters.isEmpty() && pois.isEmpty() && points.isNotEmpty()) {
                    _isSyncingPois.value = true
                    try {
                        repository.syncCorridorPois(entity)
                        shelters = repository.getSheltersForTrail(trailId)
                        pois = repository.getPoisForTrail(trailId)
                    } finally {
                        _isSyncingPois.value = false
                    }
                }

                val hasPoints = points.isNotEmpty()
                val minLat = if (hasPoints) points.minOf { it.lat } - 0.05 else -90.0
                val maxLat = if (hasPoints) points.maxOf { it.lat } + 0.05 else 90.0
                val minLng = if (hasPoints) points.minOf { it.lng } - 0.08 else -180.0
                val maxLng = if (hasPoints) points.maxOf { it.lng } + 0.08 else 180.0

                val nearbyShelters = shelters
                val nearbyFriTeltning = if (hasPoints) {
                    friTeltning.filter { it.centerLat in minLat..maxLat && it.centerLng in minLng..maxLng }
                } else emptyList()
                val nearbyPois = pois

                // Sort overnights by projected km along trail
                val overnightList = mutableListOf<OvernightSpotItem>()
                for (s in nearbyShelters) {
                    val (wKm, dM) = projectOnSegments(segments, s.lat, s.lng)
                    if (!hasPoints || dM <= 5000.0) {
                        overnightList.add(
                            OvernightSpotItem(
                                id = "shelter_${s.id}",
                                name = s.name,
                                type = "SHELTER",
                                kmOnRoute = wKm,
                                detourMeters = dM,
                                bookingRequired = s.bookingRequired,
                                bookingUrl = s.bookingUrl,
                                hasWaterNearby = s.hasWaterNearby,
                                hasToiletNearby = s.hasToiletNearby,
                                lat = s.lat,
                                lng = s.lng,
                                description = s.description
                            )
                        )
                    }
                }
                for (ft in nearbyFriTeltning) {
                    val (wKm, dM) = projectOnSegments(segments, ft.centerLat, ft.centerLng)
                    if (!hasPoints || dM <= 5000.0) {
                        overnightList.add(
                            OvernightSpotItem(
                                id = "friteltning_${ft.id}",
                                name = ft.name,
                                type = "FRI_TELTNING",
                                kmOnRoute = wKm,
                                detourMeters = dM,
                                bookingRequired = false,
                                bookingUrl = ft.rulesUrl,
                                hasWaterNearby = true,
                                hasToiletNearby = false,
                                lat = ft.centerLat,
                                lng = ft.centerLng,
                                description = ft.rulesSummary ?: "Fri teltning efter 1-2-3 reglen"
                            )
                        )
                    }
                }
                overnightList.sortBy { it.kmOnRoute }
                _overnightSpots.value = overnightList.distinctBy { it.id }

                // Sort POIs
                val waterToilets = mutableListOf<PoiSpotItem>()
                val shopList = mutableListOf<PoiSpotItem>()
                val transList = mutableListOf<PoiSpotItem>()

                for (p in nearbyPois) {
                    val (wKm, dM) = projectOnSegments(segments, p.lat, p.lng)
                    if (!hasPoints || dM <= 5000.0) {
                        val item = PoiSpotItem(
                            id = "poi_${p.type}_${p.id}",
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
                }
                waterToilets.sortBy { it.kmOnRoute }
                shopList.sortBy { it.kmOnRoute }
                transList.sortBy { it.kmOnRoute }

                _waterAndToilets.value = waterToilets.distinctBy { it.id }
                _shops.value = shopList.distinctBy { it.id }
                _transports.value = transList.distinctBy { it.id }

                // Compute longest gaps along the trail using only the route's own corridor POIs
                val totalKm = geom?.totalWalkingKm?.takeIf { it > 0.0 } ?: entity.computedWalkingKm
                val waterKms = (listOf(0.0) +
                    waterToilets.filter { it.type == "water" }.map { it.kmOnRoute } +
                    overnightList.filter { it.hasWaterNearby }.map { it.kmOnRoute } +
                    listOf(totalKm)).distinct().sorted()
                var maxWaterGap = 0.0
                for (i in 0 until waterKms.size - 1) {
                    val gap = waterKms[i + 1] - waterKms[i]
                    if (gap > maxWaterGap) maxWaterGap = gap
                }
                _longestGapWithoutWaterKm.value = maxWaterGap

                val overnightKms = (listOf(0.0) + overnightList.map { it.kmOnRoute } + listOf(totalKm)).distinct().sorted()
                var maxOvernightGap = 0.0
                for (i in 0 until overnightKms.size - 1) {
                    val gap = overnightKms[i + 1] - overnightKms[i]
                    if (gap > maxOvernightGap) maxOvernightGap = gap
                }
                _longestGapWithoutOvernightKm.value = maxOvernightGap

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

    private fun projectOnSegments(segments: List<TrailSegmentDto>, lat: Double, lng: Double): Pair<Double, Double> {
        if (segments.isEmpty()) return Pair(0.0, 0.0)
        var minDist = Double.MAX_VALUE
        var bestKm = 0.0
        var cumM = 0.0

        for (seg in segments) {
            val pts = seg.points
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val segLen = GeoUtils.distanceMeters(a.lat, a.lng, b.lat, b.lng)
                val d = GeoUtils.distanceMeters(lat, lng, a.lat, a.lng)
                if (d < minDist) {
                    minDist = d
                    bestKm = cumM / 1000.0
                }
                if (seg.type == TrailSegmentType.WALK) {
                    cumM += segLen
                }
            }
        }
        return Pair(bestKm, minDist)
    }

    fun onNavigateClicked(
        trailId: String? = null,
        onNavigate: (Long) -> Unit,
        onOpenPlanner: () -> Unit
    ) {
        viewModelScope.launch {
            val targetId = trailId ?: _trail.value?.id ?: return@launch
            val plan = repository.getLatestTripPlanForTrail(targetId)
            if (plan != null) {
                onNavigate(plan.id)
            } else {
                onOpenPlanner()
            }
        }
    }

    fun toggleSave() {
        val t = _trail.value ?: return
        viewModelScope.launch {
            repository.toggleSaveTrail(t.id)
        }
    }

    /**
     * UI Round 4 Item 2:
     * "Start nu" next to "Planlæg tur": starts navigation on the whole trail from the trail point
     * nearest my position (or the trail start without GPS), creating a simple plan with the stage planner.
     */
    fun startTrailNow(
        gpsLat: Double? = null,
        gpsLng: Double? = null,
        onPlanCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val savedPlanId = withContext(Dispatchers.IO) {
                val tid = currentTrailId ?: _trail.value?.id ?: return@withContext null
                val trailEntity = _trail.value ?: repository.getTrailById(tid) ?: return@withContext null
                val segments = _trailGeometry.value?.segments?.takeIf { it.isNotEmpty() }
                    ?: repository.geometryRepository.loadGeometry(trailEntity.id)?.segments
                    ?: if (_trailPoints.value.isNotEmpty()) {
                        listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = _trailPoints.value))
                    } else {
                        emptyList()
                    }

                val totalTrailKm = com.example.util.routing.TrailSlicer.totalTrailKm(segments)
                    .takeIf { it > 0.0 }
                    ?: trailEntity.computedWalkingKm.coerceAtLeast(1.0)

                var isReverse = false
                var startKm = 0.0
                val endKm = totalTrailKm

                if (gpsLat != null && gpsLng != null && segments.isNotEmpty()) {
                    val proj = com.example.util.routing.TrailSlicer.projectOntoTrail(
                        segments = segments,
                        lat = gpsLat,
                        lng = gpsLng,
                        isReverse = false
                    )
                    if (proj != null) {
                        if (proj.trailKm >= totalTrailKm - 0.25 && totalTrailKm > 1.0) {
                            isReverse = true
                            startKm = 0.0
                        } else {
                            startKm = proj.trailKm.coerceIn(0.0, (totalTrailKm - 0.1).coerceAtLeast(0.0))
                        }
                    }
                }

                val settings = kotlinx.coroutines.withTimeoutOrNull(250L) {
                    settingsRepository.settingsFlow.firstOrNull()
                } ?: com.example.data.local.AppSettings(
                    mitTempoKmh = com.example.data.local.SettingsRepository.cachedMitTempoKmh,
                    plannerCoachMarksSeen = com.example.data.local.SettingsRepository.cachedPlannerCoachMarksSeen
                )
                val paceKmh = settings.mitTempoKmh.takeIf { it > 0.5 } ?: 4.0
                val dailyTargetKm = settings.dailyTargetKm.takeIf { it > 1.0 } ?: 15.0
                val maxDetourKm = settings.maxDetourKm.takeIf { it > 0.1 } ?: 1.5
                val startTime = settings.startTime

                val plannedRouteRepo = com.example.data.repository.PlannedRouteRepository(getApplication())
                val initialPlannedRoute = plannedRouteRepo.buildPlannedRoute(
                    planId = 0L,
                    trail = trailEntity,
                    segments = segments,
                    startKm = startKm,
                    endKm = endKm,
                    isReverse = isReverse,
                    stages = emptyList()
                )

                val walkKm = initialPlannedRoute.totalWalkKm.takeIf { it > 0.1 }
                    ?: (endKm - startKm).coerceAtLeast(1.0)
                val numDays = kotlin.math.ceil(walkKm / dailyTargetKm).toInt().coerceAtLeast(1)

                val hasRoutePoints = initialPlannedRoute.points.isNotEmpty()
                val dao = (getApplication<Application>() as DanskStiApp).database.danskStiDao()
                val shelters = if (hasRoutePoints) dao.getSheltersForTrail(trailEntity.id) else emptyList()
                val friTeltning = if (hasRoutePoints) {
                    val bbox = initialPlannedRoute.boundingBox()
                    dao.getAllFriTeltningList().filter {
                        it.centerLat in (bbox[0] - 0.05)..(bbox[1] + 0.05) &&
                            it.centerLng in (bbox[2] - 0.08)..(bbox[3] + 0.08)
                    }
                } else emptyList()
                val pois = if (hasRoutePoints) dao.getPoisForTrail(trailEntity.id) else emptyList()

                val stages = com.example.util.StagePlanner.calculateStages(
                    plannedRoute = initialPlannedRoute,
                    trail = trailEntity,
                    routeName = trailEntity.name,
                    paceKmh = paceKmh,
                    dailyTargetKm = dailyTargetKm,
                    numDays = numDays,
                    overnightType = com.example.data.model.OvernightType.BOTH,
                    maxDetourKm = maxDetourKm,
                    startTime = startTime,
                    shelters = shelters,
                    friTeltnings = friTeltning,
                    pois = pois
                )

                val planId = repository.saveTripPlan(
                    trail = trailEntity,
                    startKm = startKm,
                    endKm = endKm,
                    isReverse = isReverse,
                    days = stages.size.coerceAtLeast(1),
                    overnightType = com.example.data.model.OvernightType.BOTH,
                    preferredDailyKm = dailyTargetKm,
                    stages = stages,
                    paceKmh = paceKmh,
                    maxDetourKm = maxDetourKm,
                    startTime = startTime
                )
                val finalPlannedRoute = plannedRouteRepo.buildPlannedRoute(
                    planId = planId,
                    trail = trailEntity,
                    segments = segments,
                    startKm = startKm,
                    endKm = endKm,
                    isReverse = isReverse,
                    stages = stages
                )
                plannedRouteRepo.savePlannedRoute(finalPlannedRoute)
                _createdNavigationPlanId.value = planId
                planId
            }
            if (savedPlanId != null) {
                onPlanCreated(savedPlanId)
            }
        }
    }

    fun clearCreatedNavigationPlanId() {
        _createdNavigationPlanId.value = null
    }
}
