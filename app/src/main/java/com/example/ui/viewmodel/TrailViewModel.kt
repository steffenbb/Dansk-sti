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

    private val repository: DanskStiRepository
        get() = try { DanskStiApp.instance.repository } catch (_: Exception) { (getApplication<Application>() as DanskStiApp).repository }

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

    private val _osmRelationTags = MutableStateFlow<Map<String, String>>(emptyMap())
    val osmRelationTags: StateFlow<Map<String, String>> = _osmRelationTags.asStateFlow()

    val downloadProgress: StateFlow<Map<String, com.example.data.model.TrailDownloadProgress>> =
        com.example.data.repository.TrailGeometryRepository.globalDownloadProgress

    // Round 10C Item 3: Direct offline download state for this trail page
    private val _offlineEstimate = MutableStateFlow<com.example.util.OfflineEstimateResult?>(null)
    val offlineEstimate: StateFlow<com.example.util.OfflineEstimateResult?> = _offlineEstimate.asStateFlow()

    private val _showOfflineConfirmDialog = MutableStateFlow(false)
    val showOfflineConfirmDialog: StateFlow<Boolean> = _showOfflineConfirmDialog.asStateFlow()

    private val _offlineDownloadError = MutableStateFlow<String?>(null)
    val offlineDownloadError: StateFlow<String?> = _offlineDownloadError.asStateFlow()

    private val _isOfflineMapDownloading = MutableStateFlow(false)
    val isOfflineMapDownloading: StateFlow<Boolean> = _isOfflineMapDownloading.asStateFlow()

    private val _offlineMapProgressPct = MutableStateFlow(0)
    val offlineMapProgressPct: StateFlow<Int> = _offlineMapProgressPct.asStateFlow()

    private var offlineWorkObserverJob: kotlinx.coroutines.Job? = null

    // Round 10C Item 8: "Start nu" fresh GPS fix state & test hooks
    private val _isStartingNow = MutableStateFlow(false)
    val isStartingNow: StateFlow<Boolean> = _isStartingNow.asStateFlow()

    private val _startNowStatusMessage = MutableStateFlow<String?>(null)
    val startNowStatusMessage: StateFlow<String?> = _startNowStatusMessage.asStateFlow()

    private val _startNowUsedOldFix = MutableStateFlow(false)
    val startNowUsedOldFix: StateFlow<Boolean> = _startNowUsedOldFix.asStateFlow()

    var startNowFreshLocationTimeoutMs: Long = com.example.ui.map.LiveMapLocationState.CURRENT_LOCATION_TIMEOUT_MS
    var customFreshLocationFetcher: (suspend (timeoutMs: Long) -> android.location.Location?)? = null
    var customLocationResolver: (suspend (timeoutMs: Long) -> com.example.ui.map.FreshLocationResolution)? = null

    private val settingsRepository = com.example.DanskStiApp.getSettingsRepository(application)

    private var currentTrailId: String? = null

    fun setOsmRelationTagsForDisplay(tags: Map<String, String>) {
        _osmRelationTags.value = tags
    }

    fun clearStartNowStatusMessage() {
        _startNowStatusMessage.value = null
    }

    fun setOfflineMapProgressForTesting(isDownloading: Boolean, progressPct: Int) {
        _isOfflineMapDownloading.value = isDownloading
        _offlineMapProgressPct.value = progressPct.coerceIn(0, 100)
    }

    /**
     * Round 10C Item 3: Estimate offline download size for this trail and show confirmation dialog.
     */
    fun requestOfflineDownloadEstimate(corridorKm: Double = 1.0) {
        val tid = currentTrailId ?: _trail.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val est = run {
                val trailEntity = _trail.value ?: repository.getTrailById(tid) ?: return@launch
                val pts = _trailPoints.value.ifEmpty {
                    repository.getTrailPoints(trailEntity).flatMap { it.points }
                }
                val wifiOnly = settingsRepository.currentSettingsSnapshot().offlineDownloadsWifiOnly
                com.example.util.OfflineDownloadManager.estimateDownload(
                    points = pts,
                    corridorKm = corridorKm,
                    context = getApplication(),
                    wifiOnly = wifiOnly
                )
            }

            _offlineEstimate.value = est
            if (!est.isAllowed) {
                _offlineDownloadError.value = est.refuseReason
                _showOfflineConfirmDialog.value = false
            } else {
                _offlineDownloadError.value = null
                _showOfflineConfirmDialog.value = true
            }
        }
    }

    fun dismissOfflineConfirmDialog() {
        _showOfflineConfirmDialog.value = false
    }

    fun clearOfflineDownloadError() {
        _offlineDownloadError.value = null
    }

    /**
     * Round 10C Item 3: Start the offline map download for this trail directly after confirmation
     * and observe progress for the button.
     */
    fun confirmAndStartOfflineDownload(corridorKm: Double = 1.0) {
        val tid = currentTrailId ?: _trail.value?.id ?: return
        _showOfflineConfirmDialog.value = false
        _offlineDownloadError.value = null
        _isOfflineMapDownloading.value = true
        _offlineMapProgressPct.value = 0
        viewModelScope.launch(Dispatchers.IO) {
            val enqueueRes = run {
                val trailEntity = _trail.value ?: repository.getTrailById(tid) ?: return@launch
                val wifiOnly = settingsRepository.currentSettingsSnapshot().offlineDownloadsWifiOnly
                val est = _offlineEstimate.value ?: run {
                    val pts = _trailPoints.value.ifEmpty {
                        repository.getTrailPoints(trailEntity).flatMap { it.points }
                    }
                    com.example.util.OfflineDownloadManager.estimateDownload(
                        points = pts,
                        corridorKm = corridorKm,
                        context = getApplication(),
                        wifiOnly = wifiOnly
                    ).also { _offlineEstimate.value = it }
                }
                if (!est.isAllowed) {
                    est
                } else {
                    com.example.util.OfflineDownloadManager.enqueueDownload(
                        context = getApplication(),
                        trailId = trailEntity.id,
                        trailName = trailEntity.name,
                        corridorKm = corridorKm,
                        wifiOnly = wifiOnly,
                        requiredBytes = est.estimatedSizeBytes
                    )
                }
            }

            if (!enqueueRes.isAllowed) {
                _offlineDownloadError.value = enqueueRes.refuseReason
                _isOfflineMapDownloading.value = false
                return@launch
            }

            _offlineDownloadError.value = null
            _isOfflineMapDownloading.value = true
            observeOfflineMapWork(tid)
        }
    }

    private fun observeOfflineMapWork(trailId: String) {
        offlineWorkObserverJob?.cancel()
        offlineWorkObserverJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.example.util.OfflineDownloadManager.observeDownloadProgress(getApplication(), trailId)
                    .collect { workInfo ->
                        if (workInfo == null) return@collect
                        when (workInfo.state) {
                            androidx.work.WorkInfo.State.ENQUEUED,
                            androidx.work.WorkInfo.State.RUNNING -> {
                                _isOfflineMapDownloading.value = true
                                val pct = workInfo.progress.getInt(
                                    com.example.service.OfflineDownloadWorker.KEY_PROGRESS_PERCENT,
                                    _offlineMapProgressPct.value
                                ).coerceIn(0, 100)
                                _offlineMapProgressPct.value = pct
                            }
                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                _isOfflineMapDownloading.value = false
                                _offlineMapProgressPct.value = 100
                                _downloadedTrail.value = withContext(Dispatchers.IO) {
                                    repository.getDownloadedTrail(trailId)
                                }
                            }
                            androidx.work.WorkInfo.State.FAILED,
                            androidx.work.WorkInfo.State.CANCELLED -> {
                                _isOfflineMapDownloading.value = false
                                val err = workInfo.outputData.getString("error")
                                if (!err.isNullOrBlank()) {
                                    _offlineDownloadError.value = err
                                }
                            }
                            else -> {}
                        }
                    }
            }
        }
    }

    private var loadTrailJob: kotlinx.coroutines.Job? = null

    fun cancelTrailDownload() {
        val tid = currentTrailId ?: _trail.value?.id
        loadTrailJob?.cancel()
        loadTrailJob = null
        if (!tid.isNullOrBlank()) {
            com.example.data.repository.TrailGeometryRepository.cancelDownload(tid)
            viewModelScope.launch(Dispatchers.IO) {
                com.example.util.OfflineDownloadManager.cancelDownload(getApplication(), tid)
            }
        }
        _isFetchingLiveRoute.value = false
        _isLoading.value = false
        _isOfflineMapDownloading.value = false
        _routeError.value = "Download annulleret"
    }

    fun retryLoadRoute() {
        val tid = currentTrailId ?: _trail.value?.id ?: return
        loadTrail(tid)
    }

    fun loadTrail(trailId: String) {
        currentTrailId = trailId
        observeOfflineMapWork(trailId)
        loadTrailJob?.cancel()
        loadTrailJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _routeError.value = null
            withContext(Dispatchers.IO) {
                val entity = repository.getTrailById(trailId) ?: run {
                    _routeError.value = "Ruten blev ikke fundet i databasen"
                    return@withContext
                }
                _trail.value = entity
                _downloadedTrail.value = repository.getDownloadedTrail(trailId)
                _osmRelationTags.value = repository.getRelationOsmTags(entity.id, entity.osmRelationId)

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
                _osmRelationTags.value = repository.getRelationOsmTags(entity.id, entity.osmRelationId)
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
     * Round 10C Item 8:
     * "Start nu": use a fresh GPS fix (as in Round 10A item 8) to choose the starting point on the trail;
     * if none within 15 s, start from the trail start and say so.
     */
    fun startTrailNow(
        gpsLat: Double? = null,
        gpsLng: Double? = null,
        onPlanCreated: (Long) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isStartingNow.value = true
            try {
                var effectiveLat = gpsLat
                var effectiveLng = gpsLng
                if (effectiveLat == null || effectiveLng == null) {
                    val timeoutMs = startNowFreshLocationTimeoutMs
                    val resolver = customLocationResolver
                    val fetcher = customFreshLocationFetcher
                    val resolution = when {
                        resolver != null -> resolver.invoke(timeoutMs)
                        fetcher != null -> {
                            val fetched = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                                fetcher(timeoutMs)
                            }
                            val now = com.example.ui.map.LiveMapLocationState.currentTimeMillis()
                            if (fetched != null) {
                                val fixTime = fetched.time
                                if (fixTime <= 0L || com.example.ui.map.LiveMapLocationState.isFixFresh(fixTime, now)) {
                                    com.example.ui.map.FreshLocationResolution(fetched, false, 0L, null)
                                } else {
                                    val mins = kotlin.math.max(1L, (now - fixTime) / 60_000L)
                                    com.example.ui.map.FreshLocationResolution(
                                        fetched,
                                        true,
                                        mins,
                                        "Position fra for $mins min siden"
                                    )
                                }
                            } else {
                                val old = com.example.ui.map.LiveMapLocationState.readAnyLastKnownLocation(getApplication())
                                if (old != null) {
                                    val fixTime = old.time.takeIf { it > 0L } ?: (now - 180_000L)
                                    val mins = kotlin.math.max(1L, (now - fixTime) / 60_000L)
                                    com.example.ui.map.FreshLocationResolution(
                                        old,
                                        true,
                                        mins,
                                        "Position fra for $mins min siden"
                                    )
                                } else {
                                    com.example.ui.map.FreshLocationResolution(null, false, 0L, null)
                                }
                            }
                        }
                        else -> com.example.ui.map.LiveMapLocationState.resolveFreshOrFallbackLocation(
                            getApplication(),
                            timeoutMs
                        )
                    }

                    val resolvedLoc = resolution.location
                    if (resolvedLoc != null) {
                        effectiveLat = resolvedLoc.latitude
                        effectiveLng = resolvedLoc.longitude
                        _startNowUsedOldFix.value = resolution.isOldFix
                        if (resolution.isOldFix) {
                            _startNowStatusMessage.value = resolution.staleMessage
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        getApplication(),
                                        resolution.staleMessage ?: "",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else {
                            _startNowStatusMessage.value = null
                        }
                    } else {
                        _startNowUsedOldFix.value = true
                        val fallbackMsg = getApplication<Application>().getString(
                            com.example.R.string.trail_detail_start_now_no_gps_fallback
                        )
                        _startNowStatusMessage.value = fallbackMsg
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                android.widget.Toast.makeText(
                                    getApplication(),
                                    fallbackMsg,
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

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

                    if (effectiveLat != null && effectiveLng != null && segments.isNotEmpty()) {
                        val proj = com.example.util.routing.TrailSlicer.projectOntoTrail(
                            segments = segments,
                            lat = effectiveLat,
                            lng = effectiveLng,
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
                    } ?: settingsRepository.currentSettingsSnapshot()
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

                    val stages = com.example.util.StagePlanner.planStages(
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
            } finally {
                _isStartingNow.value = false
            }
        }
    }

    fun clearCreatedNavigationPlanId() {
        _createdNavigationPlanId.value = null
    }
}
