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
        _userMessage.emit("Beregner rute uden om ${poly.name ?: "området"}...")
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
                    "Ruten er omlagt online uden om ${poly.name ?: "området"} (no-go områder beregnes online)"
                )
            }
            is RouteLegResult.Offline -> {
                _userMessage.emit(result.message)
            }
            else -> {
                _userMessage.emit("Kunne ikke finde en rute uden om ${poly.name ?: "området"}")
            }
        }
    }
}

// --- Phase 12 Feature 1 & Requirement 16: Vand og mad (Supply gaps) ---

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
            "Offline – henter ikke interessepunkter langs ruten fra OpenStreetMap"
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
                maxShopGapKm = _uiState.value.maxShopGapKm
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

// --- Phase 12 Feature 2 & Requirement 12: Rundtur ---

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
        _uiState.update {
            it.copy(
                showRundturDialog = false,
                errorMessage = "Vælg et startpunkt på kortet først"
            )
        }
        viewModelScope.launch {
            _userMessage.emit("Vælg et startpunkt på kortet først")
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
                _userMessage.emit("Ingen rundture kunne dannes inden for 20% af målet (${_uiState.value.rundturTargetKm.toInt()} km)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating rundtur: ${e.message}", e)
            _uiState.update { it.copy(isGeneratingRundtur = false) }
            _userMessage.emit("Kunne ikke danne rundtur: ${e.message}")
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
        _userMessage.emit("Rundtur valgt: ${String.format(Locale.US, "%.1f", candidate.actualKm)} km")
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

// --- Phase 12 Feature 4 & Round 12B Item 5: Rute til nærmeste ("Nærmeste") ---

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
 * Round 12B Item 5:
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
    val origin = resolveNearestSearchOrigin(userLat, userLng, mapCenterLat, mapCenterLng)
    _uiState.update {
        it.copy(
            showNearestPoiDialog = true,
            showNearestShelterRoleChoice = false,
            nearestPoiOriginLabelRes = origin.labelResId
        )
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
 * Round 12B Item 5:
 * For a shelter, ask "Overnat her" or "Tilføj som stop" instead of always making it an overnight stop.
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
        _uiState.update {
            it.copy(
                showNearestPoiDialog = true,
                showNearestShelterRoleChoice = true
            )
        }
    } else {
        routeToNearest(
            category = category,
            roleOverride = WaypointRole.VIA
        )
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
                    addStopOrDetourSuspend(
                        lat = poi.lat,
                        lng = poi.lng,
                        role = targetRole,
                        name = poi.name,
                        linkedPoiId = poi.id
                    )
                    updateRouteStats()
                    val afterTotalKm = computeCurrentRouteTotalLengthKm(_uiState.value)
                    val extraKm = (afterTotalKm - beforeTotalKm).coerceAtLeast(0.0)
                    val extraMsg = formatExtraRouteLengthDanish(extraKm)
                    _uiState.update {
                        it.copy(
                            isRoutingToNearest = false,
                            lastNearestExtraRouteMessage = extraMsg
                        )
                    }
                    _userMessage.emit(extraMsg)
                } else {
                    pushHistory()
                    val wpStart = RouteWaypoint(
                        id = "start_nearest",
                        lat = startPt.lat,
                        lng = startPt.lng,
                        role = WaypointRole.START,
                        name = "Start"
                    )
                    val wpDest = RouteWaypoint(
                        id = "dest_${poi.id}",
                        lat = poi.lat,
                        lng = poi.lng,
                        role = WaypointRole.END,
                        name = poi.name,
                        linkedPoiId = poi.id
                    )

                    val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                        legResult.points,
                        legResult.perPointWayInfo
                    )

                    val leg = LegGeometryData(
                        fromWaypointId = wpStart.id,
                        toWaypointId = wpDest.id,
                        legMode = LegMode.SNAP,
                        points = legResult.points,
                        lengthKm = legResult.lengthKm,
                        ascentM = legResult.ascentM,
                        descentM = legResult.descentM,
                        pathSharePct = pathShare,
                        roadKm = roadKm,
                        wayInfo = legResult.perPointWayInfo,
                        turnCues = legResult.turnCues
                    )

                    val legsMap = mapOf("${wpStart.id}_to_${wpDest.id}" to leg)
                    val extraMsg = formatExtraRouteLengthDanish(legResult.lengthKm)
                    _uiState.update {
                        it.copy(
                            isRoutingToNearest = false,
                            waypoints = listOf(wpStart, wpDest),
                            legs = legsMap,
                            failedLegs = emptyMap(),
                            routeName = "Rute til ${poi.name}",
                            lastNearestExtraRouteMessage = extraMsg
                        )
                    }
                    updateRouteStats()
                    _userMessage.emit(extraMsg)
                }
            } else {
                _uiState.update { it.copy(isRoutingToNearest = false) }
                _userMessage.emit("Ingen ${category.label.lowercase()} fundet i nærheden.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing to nearest: ${e.message}", e)
            _uiState.update { it.copy(isRoutingToNearest = false) }
            _userMessage.emit("Fejl ved rutesøgning: ${e.message}")
        }
    }
}

internal suspend fun RouteBuilderViewModel.fetchNightWeatherForDay(
    dayIndex: Int,
    startDateMillis: Long,
    lat: Double,
    lng: Double
): String {
    val targetMillis = startDateMillis + java.util.concurrent.TimeUnit.DAYS.toMillis(dayIndex.toLong())
    val nowMillis = System.currentTimeMillis()
    val diffDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(targetMillis - nowMillis)
    if (diffDays < -1 || diffDays > 15) {
        return "Vejrudsigt ikke tilgængelig endnu"
    }

    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val targetDateStr = dateFormat.format(java.util.Date(targetMillis))
    val cacheKey = "${String.format(Locale.US, "%.2f_%.2f", lat, lng)}_$targetDateStr"
    nightWeatherCache[cacheKey]?.let { return it }

    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val response = weatherService.getTrailWeather(lat = lat, lon = lng)
            val daily = response.daily
            val times = daily?.time ?: emptyList()
            val idx = times.indexOf(targetDateStr)
            if (idx == -1) {
                "Vejrudsigt ikke tilgængelig endnu"
            } else {
                val minTemp = daily?.tempMin?.getOrNull(idx)
                val maxTemp = daily?.tempMax?.getOrNull(idx)
                val precip = daily?.precipitationSum?.getOrNull(idx) ?: 0.0
                val wind = daily?.windSpeedMax?.getOrNull(idx)
                val sunsetRaw = daily?.sunset?.getOrNull(idx)
                if (!sunsetRaw.isNullOrBlank() && sunsetRaw.contains("T")) {
                    sunsetTimeStr = sunsetRaw.substringAfter("T")
                }
                val summary = buildString {
                    if (minTemp != null && maxTemp != null) {
                        append("Nat ${Math.round(minTemp)}°C / Dag ${Math.round(maxTemp)}°C")
                    } else if (minTemp != null) {
                        append("Nat ${Math.round(minTemp)}°C")
                    }
                    append(" · Nedbør ${String.format(Locale.GERMAN, "%.1f", precip)} mm")
                    if (wind != null) {
                        append(" · Vind ${Math.round(wind)} m/s")
                    }
                }
                nightWeatherCache[cacheKey] = summary
                summary
            }
        } catch (_: Exception) {
            "Vejrudsigt ikke tilgængelig endnu"
        }
    }
}
