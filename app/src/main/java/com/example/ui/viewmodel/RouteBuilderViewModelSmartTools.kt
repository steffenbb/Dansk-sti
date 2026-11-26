package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.data.model.GeoPointDto
import com.example.data.model.NearestPoiCategory
import com.example.data.model.PoiEntity
import com.example.data.model.RundturCandidate
import com.example.util.GeoUtils
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteSmartTools
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// Phase 10: Access rules & layer methods (Naturbeskyttelsesloven kapitel 4)

fun RouteBuilderViewModel.toggleAccessLayer() {
    _uiState.update { it.copy(isAccessLayerVisible = !it.isAccessLayerVisible) }
}

fun RouteBuilderViewModel.selectAccessPolygon(poly: com.example.data.model.access.AccessPolygon?) {
    _uiState.update { it.copy(selectedAccessPolygonForDetails = poly) }
}

fun RouteBuilderViewModel.loadAccessPolygonsForBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
    viewModelScope.launch {
        try {
            val polys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
            _uiState.update { current ->
                val merged = (current.accessPolygons + polys).distinctBy { it.id }
                current.copy(accessPolygons = merged)
            }
            evaluateAccessRules()
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading access polygons: ${e.message}")
        }
    }
}

internal fun RouteBuilderViewModel.fetchSunsetForRoute(lat: Double, lng: Double) {
    sunsetTimeStr = com.example.util.SunCalculator.calculateSunset(
        epochMillis = _uiState.value.startDateMillis,
        lat = lat,
        lng = lng
    )
    evaluateAccessRules()
}

fun RouteBuilderViewModel.evaluateAccessRules() {
    val wps = _uiState.value.waypoints
    val legs = _uiState.value.legs
    val polygons = _uiState.value.accessPolygons

    val warnings = com.example.util.access.AccessGeometryUtils.evaluateRouteAccess(
        waypoints = wps,
        legs = legs,
        accessPolygons = polygons,
        startTimeStr = startTimeStr,
        pacingSpeedKmh = pacingSpeedKmh,
        sunsetTimeStr = sunsetTimeStr
    )

    val updatedLegs = legs.toMutableMap()
    for ((key, leg) in legs) {
        val hasWarning = warnings.any { w ->
            val loc = w.location
            leg.points.any { pt -> kotlin.math.abs(pt.lat - loc.lat) < 0.005 && kotlin.math.abs(pt.lng - loc.lng) < 0.005 }
        }
        if (leg.hasAccessWarning != hasWarning) {
            updatedLegs[key] = leg.copy(hasAccessWarning = hasWarning)
        }
    }

    _uiState.update {
        it.copy(
            accessWarnings = warnings,
            legs = updatedLegs
        )
    }
}

/**
 * "Rut uden om": Re-routes the affected leg with the problem polygon sent as a no-go polygon.
 */
fun RouteBuilderViewModel.rerouteAroundPolygon(warning: com.example.util.access.RouteAccessWarning) {
    val poly = warning.polygon ?: return
    val wps = _uiState.value.waypoints
    val legIdx = warning.legIndex ?: return
    if (legIdx < 0 || legIdx >= wps.size - 1) return

    val fromWp = wps[legIdx]
    val toWp = wps[legIdx + 1]
    val legKey = "${fromWp.id}_to_${toWp.id}"

    viewModelScope.launch {
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
        val areaName = poly.name ?: localizedCtx.getString(com.example.R.string.planner_area_fallback)
        _userMessage.emit(localizedCtx.getString(com.example.R.string.planner_rerouting_around_fmt, areaName))
        val newNoGo = _uiState.value.options.noGoAreas + listOf(poly.coordinates)
        val detourOptions = _uiState.value.options.copy(noGoAreas = newNoGo)

        val fromCoord = LatLng(fromWp.lat, fromWp.lng)
        val toCoord = LatLng(toWp.lat, toWp.lng)

        val result = routingEngine.routeLeg(fromCoord, toCoord, detourOptions, alternative = 0)
        when (result) {
            is RouteLegResult.Success -> {
                val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                    result.points,
                    result.perPointWayInfo
                )

                val newLeg = LegGeometryData(
                    fromWaypointId = fromWp.id,
                    toWaypointId = toWp.id,
                    legMode = LegMode.SNAP,
                    points = result.points,
                    lengthKm = result.lengthKm,
                    ascentM = result.ascentM,
                    descentM = result.descentM,
                    pathSharePct = pathShare,
                    roadKm = roadKm,
                    hasAccessWarning = false,
                    sourceName = result.sourceName,
                    wayInfo = result.perPointWayInfo,
                    turnCues = result.turnCues
                )

                _uiState.update { current ->
                    val updatedLegs = current.legs.toMutableMap().apply { put(legKey, newLeg) }
                    current.copy(
                        legs = updatedLegs,
                        options = detourOptions
                    )
                }
                withContext(Dispatchers.IO) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
                }
                updateRouteStats()
                _userMessage.emit(
                    localizedCtx.getString(com.example.R.string.planner_rerouted_around_online_fmt, areaName)
                )
            }
            is RouteLegResult.Offline -> {
                _userMessage.emit(result.message)
            }
            else -> {
                _userMessage.emit(localizedCtx.getString(com.example.R.string.planner_reroute_around_failed_fmt, areaName))
            }
        }
    }
}

// --- Vand og mad (Supply gaps) ---

fun RouteBuilderViewModel.analyzeSupplyGaps() {
    val wps = _uiState.value.waypoints
    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        _uiState.value.legs[key]?.let { orderedLegs.add(it) }
    }
    val points = orderedLegs.flatMap { it.points }
    if (points.size < 2) {
        supplyGapsJob?.cancel()
        _uiState.update {
            it.copy(
                supplyGapWarnings = emptyList(),
                isLoadingCorridorPois = false
            )
        }
        return
    }

    supplyGapsJob?.cancel()
    supplyGapsJob = viewModelScope.launch {
        val online = repository.isDeviceOnline()
        val offlineNotice = if (!online) {
            com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_offline_skip_corridor_pois)
        } else {
            null
        }
        _uiState.update {
            it.copy(
                isLoadingCorridorPois = true,
                corridorPoisOfflineNotice = offlineNotice
            )
        }
        try {
            val rId = _uiState.value.routeId?.toString()
            val corridorPois: List<PoiEntity> = withContext(Dispatchers.IO) {
                if (rId != null) {
                    var existing = repository.getPoisForRoute(rId)
                    if (existing.isEmpty()) {
                        val geoPts = points.map { GeoPointDto(it.lat, it.lng, it.ele) }
                        repository.syncCorridorPoisForRoute(rId, geoPts)
                        existing = repository.getPoisForRoute(rId)
                    }
                    existing
                } else {
                    val geoPts = points.map { GeoPointDto(it.lat, it.lng, it.ele) }
                    val minLat = points.minOf { it.lat } - 0.03
                    val maxLat = points.maxOf { it.lat } + 0.03
                    val minLng = points.minOf { it.lng } - 0.05
                    val maxLng = points.maxOf { it.lng } + 0.05
                    repository.getAllPoisList().filter { poi ->
                        poi.lat in minLat..maxLat &&
                            poi.lng in minLng..maxLng &&
                            GeoUtils.projectPointOnPolyline(GeoPointDto(poi.lat, poi.lng), geoPts).second <= 3000.0
                    }
                }
            }

            val warnings = RouteSmartTools.analyzeSupplyGaps(
                routePoints = points,
                corridorPois = corridorPois,
                maxWaterGapKm = _uiState.value.maxWaterGapKm,
                maxShopGapKm = _uiState.value.maxShopGapKm,
                tripDateMillis = _uiState.value.startDateMillis
            )
            _uiState.update {
                it.copy(
                    supplyGapWarnings = warnings,
                    isLoadingCorridorPois = false,
                    corridorPoisOfflineNotice = offlineNotice
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed analyzing supply gaps: ${e.message}")
            _uiState.update {
                it.copy(
                    isLoadingCorridorPois = false,
                    corridorPoisOfflineNotice = offlineNotice
                )
            }
        }
    }
}

fun RouteBuilderViewModel.setMaxWaterGapKm(km: Double) {
    _uiState.update { it.copy(maxWaterGapKm = km) }
    analyzeSupplyGaps()
}

fun RouteBuilderViewModel.setMaxShopGapKm(km: Double) {
    _uiState.update { it.copy(maxShopGapKm = km) }
    analyzeSupplyGaps()
}

// --- Rundtur ---

fun RouteBuilderViewModel.openRundturDialog() {
    _uiState.update { it.copy(showRundturDialog = true) }
}

fun RouteBuilderViewModel.dismissRundturDialog() {
    _uiState.update { it.copy(showRundturDialog = false) }
}

fun RouteBuilderViewModel.setRundturTargetKm(km: Double) {
    _uiState.update { it.copy(rundturTargetKm = km.coerceIn(5.0, 60.0)) }
}

fun RouteBuilderViewModel.generateRundtur(startLat: Double? = null, startLng: Double? = null) {
    val firstWp = _uiState.value.waypoints.firstOrNull()
    val startPt = when {
        firstWp != null -> LatLng(firstWp.lat, firstWp.lng)
        startLat != null && startLng != null -> LatLng(startLat, startLng)
        else -> null
    }

    if (startPt == null) {
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
        val msg = localizedCtx.getString(com.example.R.string.planner_select_start_point_first)
        _uiState.update {
            it.copy(
                showRundturDialog = false,
                errorMessage = msg
            )
        }
        viewModelScope.launch {
            _userMessage.emit(msg)
        }
        return
    }

    _uiState.update { it.copy(isGeneratingRundtur = true, showRundturDialog = true) }
    viewModelScope.launch {
        try {
            val shelters = repository.getAllSheltersList()
            val friTeltning = repository.getAllFriTeltningDirect()
            val candidates = RouteSmartTools.generateRundturCandidates(
                start = startPt,
                targetKm = _uiState.value.rundturTargetKm,
                routingEngine = routingEngine,
                shelters = shelters,
                friTeltning = friTeltning
            )
            _uiState.update {
                it.copy(
                    isGeneratingRundtur = false,
                    rundturCandidates = candidates
                )
            }
            if (candidates.isEmpty()) {
                val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
                _userMessage.emit(
                    localizedCtx.getString(
                        com.example.R.string.planner_no_loop_within_target_fmt,
                        _uiState.value.rundturTargetKm.toInt()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating rundtur: ${e.message}", e)
            _uiState.update { it.copy(isGeneratingRundtur = false) }
            _userMessage.emit(
                com.example.util.AppLocaleHelper.str(
                    getApplication(),
                    com.example.R.string.planner_loop_failed_fmt,
                    com.example.util.userMessageFor(e)
                )
            )
        }
    }
}

fun RouteBuilderViewModel.applyRundtur(candidate: RundturCandidate) {
    pushHistory()
    _uiState.update {
        it.copy(
            waypoints = candidate.waypoints,
            legs = candidate.legs,
            failedLegs = emptyMap(),
            showRundturDialog = false,
            routeName = candidate.title
        )
    }
    updateRouteStats()
    viewModelScope.launch {
        _userMessage.emit(
            com.example.util.AppLocaleHelper.str(
                getApplication(),
                com.example.R.string.planner_loop_selected_fmt,
                String.format(Locale.US, "%.1f", candidate.actualKm)
            )
        )
    }
}

// --- Phase 12 Feature 3: Vejr og dagslys ---

fun RouteBuilderViewModel.fetchWeatherAndSunset() {
    val wps = _uiState.value.waypoints
    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        _uiState.value.legs[key]?.let { orderedLegs.add(it) }
    }
    if (wps.size < 2) return

    _uiState.update { it.copy(isLoadingWeather = true) }
    viewModelScope.launch {
        try {
            val etas = RouteSmartTools.calculateWaypointWeatherEtas(
                waypoints = wps,
                orderedLegs = orderedLegs,
                startTimeStr = startTimeStr,
                pacingSpeedKmh = pacingSpeedKmh,
                weatherService = weatherService,
                startDateEpochMs = _uiState.value.startDateMillis
            )
            _uiState.update {
                it.copy(
                    isLoadingWeather = false,
                    waypointWeatherEtas = etas
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching weather ETA: ${e.message}")
            _uiState.update { it.copy(isLoadingWeather = false) }
        }
    }
}

// --- Rute til nærmeste ("Nærmeste") ---

private const val NEAR_ROUTE_THRESHOLD_METERS = 2000.0

fun RouteBuilderViewModel.isPositionOnOrNearRoute(
    lat: Double,
    lng: Double,
    state: RouteBuilderState = _uiState.value
): Boolean {
    val visibleWps = state.waypoints.filter { !it.isHelper }
    val legPoints = state.legs.values.flatMap { it.points }
    val baseTrailPts = state.baseTrailPoints
    if (visibleWps.isEmpty() && legPoints.isEmpty() && baseTrailPts.isEmpty()) {
        return false
    }
    if (visibleWps.any { GeoUtils.distanceMeters(lat, lng, it.lat, it.lng) <= NEAR_ROUTE_THRESHOLD_METERS }) {
        return true
    }
    if (legPoints.any { GeoUtils.distanceMeters(lat, lng, it.lat, it.lng) <= NEAR_ROUTE_THRESHOLD_METERS }) {
        return true
    }
    if (baseTrailPts.any { GeoUtils.distanceMeters(lat, lng, it.lat, it.lng) <= NEAR_ROUTE_THRESHOLD_METERS }) {
        return true
    }
    return false
}

/**
 * Search from, in order:
 * 1. The selected pin ("Nærmest dit valgte punkt")
 * 2. Otherwise my position if I am on or near the route ("Nærmest din position")
 * 3. Otherwise the centre of the visible map ("Nærmest kortets midte")
 */
fun RouteBuilderViewModel.resolveNearestSearchOrigin(
    userLat: Double? = null,
    userLng: Double? = null,
    mapCenterLat: Double? = null,
    mapCenterLng: Double? = null
): NearestSearchOrigin {
    val state = _uiState.value
    val visibleWps = state.waypoints.filter { !it.isHelper }

    // 1. Selected pin
    val selectedWp = state.selectedWaypointId?.let { selId ->
        visibleWps.find { it.id == selId }
    }
    if (selectedWp != null) {
        return NearestSearchOrigin(
            type = NearestSearchOriginType.SELECTED_PIN,
            lat = selectedWp.lat,
            lng = selectedWp.lng,
            labelResId = com.example.R.string.nearest_poi_origin_selected_pin
        )
    }

    // 2. My position if on or near the route
    val gpsPos = when {
        userLat != null && userLng != null -> LatLng(userLat, userLng)
        state.currentGpsPosition != null -> state.currentGpsPosition
        else -> null
    }
    if (gpsPos != null && isPositionOnOrNearRoute(gpsPos.lat, gpsPos.lng, state)) {
        return NearestSearchOrigin(
            type = NearestSearchOriginType.MY_POSITION,
            lat = gpsPos.lat,
            lng = gpsPos.lng,
            labelResId = com.example.R.string.nearest_poi_origin_my_position
        )
    }

    // 3. Centre of the visible map
    val centerLat = mapCenterLat ?: state.mapCenterLat
    val centerLng = mapCenterLng ?: state.mapCenterLng
    return NearestSearchOrigin(
        type = NearestSearchOriginType.MAP_CENTER,
        lat = centerLat,
        lng = centerLng,
        labelResId = com.example.R.string.nearest_poi_origin_map_center
    )
}

fun RouteBuilderViewModel.cancelTrailGeometryDownload() {
    // Only cancel this screen's wait.
    // The shared download stops only if no one else is waiting for it.
    val cancelMsg = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_download_cancelled)
    trailGeometryDownloadJob?.cancel(kotlinx.coroutines.CancellationException(cancelMsg))
    trailGeometryDownloadJob = null
    _uiState.update {
        it.copy(
            isDownloadingTrailGeometry = false,
            trailGeometryDownloadFailed = true,
            errorMessage = cancelMsg
        )
    }
}

fun RouteBuilderViewModel.openNearestPoiDialog(
    userLat: Double? = null,
    userLng: Double? = null,
    mapCenterLat: Double? = null,
    mapCenterLng: Double? = null
) {
    if (userLat != null && userLng != null) {
        _uiState.update { it.copy(currentGpsPosition = LatLng(userLat, userLng)) }
    }
    if (mapCenterLat != null && mapCenterLng != null) {
        _uiState.update { it.copy(mapCenterLat = mapCenterLat, mapCenterLng = mapCenterLng) }
    }
    val initialOrigin = resolveNearestSearchOrigin(userLat, userLng, mapCenterLat, mapCenterLng)
    _uiState.update {
        it.copy(
            showNearestPoiDialog = true,
            showNearestShelterRoleChoice = false,
            nearestPoiOriginLabelRes = initialOrigin.labelResId
        )
    }
    if (userLat == null || userLng == null) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val res = resolveCurrentOrFallbackLocation()
            val loc = res.location
            if (loc != null) {
                _uiState.update { it.copy(currentGpsPosition = LatLng(loc.latitude, loc.longitude)) }
                val updatedOrigin = resolveNearestSearchOrigin(
                    userLat = loc.latitude,
                    userLng = loc.longitude,
                    mapCenterLat = mapCenterLat,
                    mapCenterLng = mapCenterLng
                )
                _uiState.update { it.copy(nearestPoiOriginLabelRes = updatedOrigin.labelResId) }
            }
        }
    }
}

fun RouteBuilderViewModel.dismissNearestPoiDialog() {
    _uiState.update {
        it.copy(
            showNearestPoiDialog = false,
            showNearestShelterRoleChoice = false
        )
    }
}

/**
 * For a shelter, ask "Overnat her" or "Tilføj som stop" instead of always making it an overnight stop.
 * Uses the fresh-position helper (resolveCurrentOrFallbackLocation) when userLat/userLng are not explicitly passed.
 */
fun RouteBuilderViewModel.onNearestCategorySelected(
    category: NearestPoiCategory,
    userLat: Double? = null,
    userLng: Double? = null,
    mapCenterLat: Double? = null,
    mapCenterLng: Double? = null
) {
    if (userLat != null && userLng != null) {
        _uiState.update { it.copy(currentGpsPosition = LatLng(userLat, userLng)) }
    }
    if (mapCenterLat != null && mapCenterLng != null) {
        _uiState.update { it.copy(mapCenterLat = mapCenterLat, mapCenterLng = mapCenterLng) }
    }
    if (category == NearestPoiCategory.SHELTER) {
        if (userLat == null && userLng == null && _uiState.value.currentGpsPosition == null) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val res = resolveCurrentOrFallbackLocation()
                res.location?.let { loc ->
                    _uiState.update { it.copy(currentGpsPosition = LatLng(loc.latitude, loc.longitude)) }
                }
            }
        }
        _uiState.update {
            it.copy(
                showNearestPoiDialog = true,
                showNearestShelterRoleChoice = true
            )
        }
    } else {
        if (userLat == null && userLng == null && _uiState.value.currentGpsPosition == null) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val res = resolveCurrentOrFallbackLocation()
                val loc = res.location
                if (loc != null) {
                    _uiState.update { it.copy(currentGpsPosition = LatLng(loc.latitude, loc.longitude)) }
                }
                routeToNearest(
                    category = category,
                    startLat = loc?.latitude,
                    startLng = loc?.longitude,
                    roleOverride = WaypointRole.VIA
                )
            }
        } else {
            routeToNearest(
                category = category,
                startLat = userLat,
                startLng = userLng,
                roleOverride = WaypointRole.VIA
            )
        }
    }
}

fun RouteBuilderViewModel.confirmNearestShelterRole(role: WaypointRole) {
    _uiState.update { it.copy(showNearestShelterRoleChoice = false) }
    routeToNearest(
        category = NearestPoiCategory.SHELTER,
        roleOverride = role
    )
}

internal fun RouteBuilderViewModel.computeCurrentRouteTotalLengthKm(state: RouteBuilderState = _uiState.value): Double {
    val wps = state.waypoints
    if (wps.size < 2) return 0.0
    var sumKm = 0.0
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        val leg = state.legs[key]
        sumKm += leg?.lengthKm ?: (GeoUtils.distanceMeters(wps[i].lat, wps[i].lng, wps[i + 1].lat, wps[i + 1].lng) / 1000.0)
    }
    return sumKm
}

fun RouteBuilderViewModel.formatExtraRouteLengthDanish(extraKm: Double): String {
    val clamped = extraKm.coerceAtLeast(0.0)
    val formattedKm = String.format(Locale("da", "DK"), "%.1f", clamped)
    return app.getString(com.example.R.string.nearest_route_extra_length_format, formattedKm)
}

fun RouteBuilderViewModel.routeToNearest(
    category: NearestPoiCategory,
    startLat: Double? = null,
    startLng: Double? = null,
    roleOverride: WaypointRole? = null
) {
    val origin = resolveNearestSearchOrigin(userLat = startLat, userLng = startLng)
    val startPt = LatLng(origin.lat, origin.lng)

    _uiState.update {
        it.copy(
            isRoutingToNearest = true,
            nearestPoiOriginLabelRes = origin.labelResId,
            showNearestShelterRoleChoice = false
        )
    }
    dismissNearestPoiDialog()
    viewModelScope.launch {
        try {
            val shelters = repository.getAllSheltersList()
            val friTeltning = repository.getAllFriTeltningDirect()
            val pois = repository.getAllPoisList()

            val match = RouteSmartTools.routeToNearestPoi(
                start = startPt,
                category = category,
                routingEngine = routingEngine,
                shelters = shelters,
                friTeltning = friTeltning,
                pois = pois
            )

            if (match != null) {
                val (poi, legResult) = match
                val beforeTotalKm = computeCurrentRouteTotalLengthKm(_uiState.value)
                val currentVisibleWps = _uiState.value.waypoints.filter { !it.isHelper }
                if (currentVisibleWps.size >= 2) {
                    val targetRole = roleOverride ?: if (category == NearestPoiCategory.SHELTER) {
                        WaypointRole.OVERNIGHT
                    } else {
                        WaypointRole.VIA
                    }
                    pushHistory()
                    addStopOrDetourSuspend(poi.lat, poi.lng, targetRole, poi.name, linkedPoiId = poi.id)
                    updateRouteStats()
                    val extraKm = (computeCurrentRouteTotalLengthKm(_uiState.value) - beforeTotalKm).coerceAtLeast(0.0)
                    val extraMsg = formatExtraRouteLengthDanish(extraKm)
                    _uiState.update { it.copy(isRoutingToNearest = false, lastNearestExtraRouteMessage = extraMsg) }
                    _userMessage.emit(extraMsg)
                } else {
                    pushHistory()
                    val wpStart = RouteWaypoint(id = "start_nearest", lat = startPt.lat, lng = startPt.lng, role = WaypointRole.START, name = "Start")
                    val wpDest = RouteWaypoint(id = "dest_${poi.id}", lat = poi.lat, lng = poi.lng, role = WaypointRole.END, name = poi.name, linkedPoiId = poi.id)
                    val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(legResult.points, legResult.perPointWayInfo)
                    val leg = LegGeometryData(
                        fromWaypointId = wpStart.id, toWaypointId = wpDest.id, legMode = LegMode.SNAP,
                        points = legResult.points, lengthKm = legResult.lengthKm,
                        ascentM = legResult.ascentM, descentM = legResult.descentM,
                        pathSharePct = pathShare, roadKm = roadKm,
                        wayInfo = legResult.perPointWayInfo, turnCues = legResult.turnCues
                    )
                    val extraMsg = formatExtraRouteLengthDanish(legResult.lengthKm)
                    _uiState.update {
                        it.copy(
                            isRoutingToNearest = false,
                            waypoints = listOf(wpStart, wpDest),
                            legs = mapOf("${wpStart.id}_to_${wpDest.id}" to leg),
                            failedLegs = emptyMap(),
                            routeName = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_route_to_fmt, poi.name),
                            lastNearestExtraRouteMessage = extraMsg
                        )
                    }
                    updateRouteStats()
                    _userMessage.emit(extraMsg)
                }
            } else {
                val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
                _uiState.update { it.copy(isRoutingToNearest = false) }
                _userMessage.emit(localizedCtx.getString(com.example.R.string.planner_no_category_found_nearby_fmt, category.label.lowercase()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing to nearest: ${e.message}", e)
            val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
            _uiState.update { it.copy(isRoutingToNearest = false) }
            _userMessage.emit(localizedCtx.getString(com.example.R.string.planner_route_search_error_fmt, com.example.util.userMessageFor(e)))
        }
    }
}

internal suspend fun RouteBuilderViewModel.fetchNightWeatherForDay(
    dayIndex: Int, startDateMillis: Long, lat: Double, lng: Double
): String {
    val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
    val appLocale = com.example.util.AppLocaleHelper.getAppLocale(getApplication())
    val unavailableMsg = runCatching { localizedCtx.getString(com.example.R.string.day_weather_unavailable) }.getOrDefault("Forecast not available yet")
    val targetMillis = startDateMillis + java.util.concurrent.TimeUnit.DAYS.toMillis(dayIndex.toLong())
    val nowMillis = System.currentTimeMillis()
    val diffHours = (targetMillis - nowMillis) / 3_600_000.0
    if (diffHours < -18.0 || diffHours > 48.0) return unavailableMsg
    val targetDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(targetMillis))
    val cacheKey = "${String.format(Locale.US, "%.2f_%.2f", lat, lng)}_$targetDateStr"
    nightWeatherCache[cacheKey]?.let { return it }

    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val weather = repository.getTrailWeather(lat = lat, lng = lng, forceNetworkRefresh = false) ?: return@withContext unavailableMsg
            val updatedAt = weather.formatUpdatedClock()
            val staleSuffix = if (weather.isStale(nowMillis)) runCatching { " " + localizedCtx.getString(com.example.R.string.day_weather_stale_suffix) }.getOrDefault("") else ""
            fun formatForecast(tempC: Double, windMs: Double, precipMm: Double): String {
                val precipStr = String.format(appLocale, "%.1f", precipMm)
                return runCatching {
                    localizedCtx.getString(com.example.R.string.day_weather_afternoon_fmt, Math.round(tempC).toInt(), Math.round(windMs).toInt(), precipStr, updatedAt) + staleSuffix
                }.getOrDefault("${Math.round(tempC)}°C · ${Math.round(windMs)} m/s · $precipStr mm · $updatedAt$staleSuffix")
            }
            val afternoon = com.example.data.remote.WeatherInterpreter.findAfternoonForecastForDate(weather, targetDateStr)
            if (afternoon != null) {
                val summary = formatForecast(afternoon.tempC, afternoon.windSpeedMs, afternoon.precipitationMm)
                nightWeatherCache[cacheKey] = summary
                return@withContext summary
            }
            val daily = weatherService.getTrailWeather(lat = lat, lon = lng).daily
            val idx = (daily?.time ?: emptyList()).indexOf(targetDateStr)
            if (idx == -1) unavailableMsg else {
                val sunsetRaw = daily?.sunset?.getOrNull(idx)
                if (!sunsetRaw.isNullOrBlank() && sunsetRaw.contains("T")) sunsetTimeStr = sunsetRaw.substringAfter("T")
                val tempToShow = daily?.tempMax?.getOrNull(idx) ?: daily?.tempMin?.getOrNull(idx) ?: 0.0
                val summary = formatForecast(tempToShow, daily?.windSpeedMax?.getOrNull(idx) ?: 0.0, daily?.precipitationSum?.getOrNull(idx) ?: 0.0)
                nightWeatherCache[cacheKey] = summary
                summary
            }
        } catch (_: Exception) {
            unavailableMsg
        }
    }
}
