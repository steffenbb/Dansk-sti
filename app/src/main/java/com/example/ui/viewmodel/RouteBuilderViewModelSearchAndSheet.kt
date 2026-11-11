package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.data.model.OvernightType
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * UI Round 1 Item 9:
 * "Vis alternativer" (alternatives 1-3 for that leg only).
 */
fun RouteBuilderViewModel.fetchAlternativesForLeg(legKey: String) {
    val parts = legKey.split("_to_")
    if (parts.size != 2) return
    val wps = _uiState.value.waypoints
    val fromWp = wps.find { it.id == parts[0] } ?: return
    val toWp = wps.find { it.id == parts[1] } ?: return

    _uiState.update { it.copy(isFetchingLegAlternatives = true) }

    viewModelScope.launch {
        val altList = mutableListOf<RouteAlternativeInfo>()
        val fromCoord = LatLng(fromWp.lat, fromWp.lng)
        val toCoord = LatLng(toWp.lat, toWp.lng)

        for (altIdx in 1..3) {
            when (val res = routingEngine.routeLeg(fromCoord, toCoord, _uiState.value.options, alternative = altIdx)) {
                is RouteLegResult.Success -> {
                    val (share, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                        res.points,
                        res.perPointWayInfo
                    )
                    altList.add(
                        RouteAlternativeInfo(
                            index = altIdx,
                            lengthKm = res.lengthKm,
                            ascentM = res.ascentM,
                            pathSharePct = share,
                            roadKm = roadKm,
                            points = res.points,
                            wayInfo = res.perPointWayInfo
                        )
                    )
                }
                else -> {}
            }
        }

        _uiState.update { current ->
            val updatedMap = current.legAlternatives.toMutableMap()
            updatedMap[legKey] = altList
            current.copy(
                legAlternatives = updatedMap,
                isFetchingLegAlternatives = false
            )
        }
    }
}

fun RouteBuilderViewModel.selectAlternativeForLeg(legKey: String, alt: RouteAlternativeInfo) {
    val parts = legKey.split("_to_")
    if (parts.size != 2) return
    val wps = _uiState.value.waypoints
    val fromWp = wps.find { it.id == parts[0] } ?: return
    val toWp = wps.find { it.id == parts[1] } ?: return

    pushHistory()
    val newLeg = LegGeometryData(
        fromWaypointId = fromWp.id,
        toWaypointId = toWp.id,
        legMode = LegMode.SNAP,
        points = alt.points,
        lengthKm = alt.lengthKm,
        ascentM = alt.ascentM,
        descentM = 0.0,
        pathSharePct = alt.pathSharePct,
        roadKm = alt.roadKm ?: 0.0,
        sourceName = "BRouter Alt ${alt.index}",
        wayInfo = alt.wayInfo
    )

    _uiState.update { current ->
        val updatedLegs = current.legs.toMutableMap()
        updatedLegs[legKey] = newLeg
        val updatedFailed = current.failedLegs.toMutableMap()
        updatedFailed.remove(legKey)
        current.copy(
            legs = updatedLegs,
            failedLegs = updatedFailed
        )
    }

    viewModelScope.launch(Dispatchers.IO) {
        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
    }
    updateRouteStats()
}

/**
 * UI Round 1 Item 10:
 * Planner menu: "Luk ruten" (adds a leg back to the start)
 */
fun RouteBuilderViewModel.closeRouteLoop() {
    val wps = _uiState.value.waypoints
    if (wps.size < 2) return
    val startWp = wps.first()
    val endWp = wps.last()
    if (haversineM(startWp.lat, startWp.lng, endWp.lat, endWp.lng) < 5.0 && wps.size >= 3) {
        return
    }
    addPointAtEndInternal(startWp.lat, startWp.lng, startWp.name ?: "Start")
}

/**
 * UI Round 1 Item 10:
 * Planner menu: "Ud og hjem" (appends the reverse of the route)
 */
fun RouteBuilderViewModel.appendOutAndBack() {
    val state = _uiState.value
    val wps = state.waypoints
    if (wps.size < 2) return

    pushHistory()
    val updatedWps = wps.toMutableList()
    val updatedLegs = state.legs.toMutableMap()
    val newLegsToPersist = mutableListOf<LegGeometryData>()
    val legsToCompute = mutableListOf<Pair<RouteWaypoint, RouteWaypoint>>()

    val lastIdx = updatedWps.lastIndex
    updatedWps[lastIdx] = updatedWps[lastIdx].copy(
        role = if (updatedWps[lastIdx].role == WaypointRole.END) WaypointRole.VIA else updatedWps[lastIdx].role
    )

    var prevAdded = updatedWps.last()
    for (i in wps.size - 2 downTo 0) {
        val origWp = wps[i]
        val origNextWp = wps[i + 1]
        val isFinalReturn = (i == 0)
        val clonedWp = origWp.copy(
            id = UUID.randomUUID().toString(),
            role = if (isFinalReturn) WaypointRole.END else if (origWp.role == WaypointRole.OVERNIGHT) WaypointRole.OVERNIGHT else WaypointRole.VIA,
            legModeToNext = origWp.legModeToNext
        )
        updatedWps.add(clonedWp)

        val forwardKey = "${origWp.id}_to_${origNextWp.id}"
        val forwardLeg = state.legs[forwardKey]
        if (forwardLeg != null && forwardLeg.points.isNotEmpty()) {
            val revLeg = forwardLeg.copy(
                fromWaypointId = prevAdded.id,
                toWaypointId = clonedWp.id,
                points = forwardLeg.points.asReversed(),
                ascentM = forwardLeg.descentM,
                descentM = forwardLeg.ascentM
            )
            updatedLegs[revLeg.legKey] = revLeg
            newLegsToPersist.add(revLeg)
        } else {
            legsToCompute.add(prevAdded to clonedWp)
        }
        prevAdded = clonedWp
    }

    normalizeRoles(updatedWps)
    _uiState.update {
        it.copy(
            waypoints = updatedWps,
            legs = updatedLegs
        )
    }
    if (newLegsToPersist.isNotEmpty()) {
        viewModelScope.launch(Dispatchers.IO) {
            for (leg in newLegsToPersist) {
                RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
            }
        }
    }
    for ((fromWp, toWp) in legsToCompute) {
        computeSingleLeg(fromWp, toWp)
    }
    updateRouteStats()
}

fun RouteBuilderViewModel.setScrubbedPoint(point: RoutePoint3D?) {
    _uiState.update { it.copy(scrubbedPointOnMap = point) }
}

fun RouteBuilderViewModel.updateRouteName(name: String) {
    _uiState.update { it.copy(routeName = name, hasUnsavedChanges = true) }
    scheduleDraftAutosave()
}

fun RouteBuilderViewModel.updateOptions(newOptions: RouteOptions) {
    if (_uiState.value.options == newOptions) return
    pushHistory()
    _uiState.update { it.copy(options = newOptions) }
    recomputeAllLegs()
}

// --- Trip Settings Sheet & Sheet Tabs / Search ---

fun RouteBuilderViewModel.setSheetHeightState(state: SheetHeightState) {
    val defaultFraction = when (state) {
        SheetHeightState.PEEK -> 0.22f
        SheetHeightState.HALF -> 0.42f
        SheetHeightState.FULL -> 0.60f
    }
    _uiState.update { current ->
        val wp = current.selectedWaypointId?.let { id -> current.waypoints.find { it.id == id } }
        val updatedTarget = if (wp != null) {
            computeSheetAdjustedCenter(wp.lat, wp.lng, zoomLevel = 14.5, sheetHeightFraction = defaultFraction)
        } else {
            current.mapViewportTarget
        }
        val updatedTab = when {
            state == SheetHeightState.FULL && current.sheetHeightState != SheetHeightState.FULL -> PlannerSheetTab.DETALJER
            state == SheetHeightState.HALF && current.sheetHeightState == SheetHeightState.PEEK -> PlannerSheetTab.DAGE
            else -> current.selectedSheetTab
        }
        current.copy(
            sheetHeightState = state,
            sheetHeightFraction = defaultFraction,
            selectedSheetTab = updatedTab,
            mapViewportTarget = updatedTarget
        )
    }
}

fun RouteBuilderViewModel.setSelectedSheetTab(tab: PlannerSheetTab) {
    _uiState.update { current ->
        val nextHeight = if (current.sheetHeightState == SheetHeightState.PEEK) {
            if (tab == PlannerSheetTab.DETALJER) SheetHeightState.FULL else SheetHeightState.HALF
        } else {
            current.sheetHeightState
        }
        val nextFraction = when (nextHeight) {
            SheetHeightState.PEEK -> 0.22f
            SheetHeightState.HALF -> 0.42f
            SheetHeightState.FULL -> 0.60f
        }
        current.copy(
            selectedSheetTab = tab,
            sheetHeightState = nextHeight,
            sheetHeightFraction = nextFraction
        )
    }
}

fun RouteBuilderViewModel.setSheetHeightFraction(fraction: Float) {
    val clamped = fraction.coerceIn(0.12f, 0.60f)
    _uiState.update { current ->
        val wp = current.selectedWaypointId?.let { id -> current.waypoints.find { it.id == id } }
        val updatedTarget = if (wp != null) {
            computeSheetAdjustedCenter(wp.lat, wp.lng, zoomLevel = 14.5, sheetHeightFraction = clamped)
        } else {
            current.mapViewportTarget
        }
        current.copy(
            sheetHeightFraction = clamped,
            mapViewportTarget = updatedTarget
        )
    }
}

fun RouteBuilderViewModel.setCurrentPosition(lat: Double, lng: Double) {
    if (lat.isFinite() && lng.isFinite()) {
        _uiState.update { it.copy(currentGpsPosition = LatLng(lat, lng)) }
    }
}

fun RouteBuilderViewModel.setPlannerSearchQuery(query: String) {
    _uiState.update { it.copy(plannerSearchQuery = query) }
    val trimmed = query.trim()
    if (trimmed.length < 2) {
        plannerSearchQueryFlow.value = ""
        _uiState.update { it.copy(plannerSearchResults = com.example.data.remote.GroupedSearchResults()) }
        return
    }
    plannerSearchQueryFlow.value = query
}

fun RouteBuilderViewModel.clearPlannerSearch() {
    plannerSearchQueryFlow.value = ""
    _uiState.update {
        it.copy(
            plannerSearchQuery = "",
            plannerSearchResults = com.example.data.remote.GroupedSearchResults()
        )
    }
}

fun RouteBuilderViewModel.routeToPlace(
    destLat: Double,
    destLng: Double,
    destName: String,
    currentLat: Double? = null,
    currentLng: Double? = null,
    currentFixTimeMs: Long? = null,
    staleMessage: String? = null
) {
    val computedStale = staleMessage ?: currentFixTimeMs?.let {
        com.example.ui.map.LiveMapLocationState.formatOldFixMessage(it)
    }
    if (currentLat == null && currentLng == null && customLocationResolver != null) {
        viewModelScope.launch {
            val res = resolveCurrentOrFallbackLocation()
            val loc = res.location
            if (loc != null) {
                applyRouteToPlaceWithStart(
                    destLat = destLat,
                    destLng = destLng,
                    destName = destName,
                    startLat = loc.latitude,
                    startLng = loc.longitude,
                    staleMessage = res.staleMessage
                )
            } else {
                val destWp = RouteWaypoint(
                    lat = destLat,
                    lng = destLng,
                    role = WaypointRole.END,
                    legModeToNext = LegMode.SNAP,
                    name = destName.ifBlank { "Destination" }
                )
                _uiState.update {
                    it.copy(
                        mode = PlannerMode.FREE,
                        interactionMode = PlannerInteractionMode.TEGN,
                        routeName = if (destName.isNotBlank()) "Rute til $destName" else "Ny rute",
                        waypoints = listOf(destWp),
                        legs = emptyMap(),
                        failedLegs = emptyMap(),
                        waitingForStartPointTap = true,
                        staleStartLocationMessage = null,
                        plannerSearchQuery = "",
                        plannerSearchResults = com.example.data.remote.GroupedSearchResults(),
                        errorMessage = "Vælg startpunkt - tryk på kortet"
                    )
                }
            }
        }
        return
    }

    val state = _uiState.value
    val startLat = currentLat
        ?: state.currentGpsPosition?.lat
        ?: state.waypoints.firstOrNull()?.lat
        ?: 56.0
    val startLng = currentLng
        ?: state.currentGpsPosition?.lng
        ?: state.waypoints.firstOrNull()?.lng
        ?: 10.2

    applyRouteToPlaceWithStart(
        destLat = destLat,
        destLng = destLng,
        destName = destName,
        startLat = startLat,
        startLng = startLng,
        staleMessage = computedStale ?: state.staleStartLocationMessage
    )
}

internal fun RouteBuilderViewModel.applyRouteToPlaceWithStart(
    destLat: Double,
    destLng: Double,
    destName: String,
    startLat: Double,
    startLng: Double,
    staleMessage: String? = null
) {
    pushHistory()
    val startWp = RouteWaypoint(
        lat = startLat,
        lng = startLng,
        role = WaypointRole.START,
        legModeToNext = LegMode.SNAP,
        name = "Min position"
    )
    val endWp = RouteWaypoint(
        lat = destLat,
        lng = destLng,
        role = WaypointRole.END,
        legModeToNext = LegMode.SNAP,
        name = destName.ifBlank { "Destination" }
    )

    val legKey = "${startWp.id}_to_${endWp.id}"
    val distKm = haversineM(startLat, startLng, destLat, destLng) / 1000.0
    val initialLeg = LegGeometryData(
        fromWaypointId = startWp.id,
        toWaypointId = endWp.id,
        legMode = LegMode.SNAP,
        points = listOf(RoutePoint3D(startLat, startLng, null), RoutePoint3D(destLat, destLng, null)),
        lengthKm = distKm,
        ascentM = 0.0,
        descentM = 0.0,
        pathSharePct = null,
        roadKm = 0.0,
        sourceName = "Pending"
    )

    _uiState.update {
        it.copy(
            mode = PlannerMode.FREE,
            interactionMode = PlannerInteractionMode.KIG,
            routeName = if (destName.isNotBlank()) "Rute til $destName" else "Ny rute",
            waypoints = listOf(startWp, endWp),
            legs = mapOf(legKey to initialLeg),
            failedLegs = emptyMap(),
            waitingForStartPointTap = false,
            selectedWaypointId = null,
            selectedLegKey = null,
            plannerSearchQuery = "",
            plannerSearchResults = com.example.data.remote.GroupedSearchResults(),
            currentGpsPosition = LatLng(startLat, startLng),
            staleStartLocationMessage = staleMessage,
            errorMessage = null
        )
    }
    computeSingleLeg(startWp, endWp)
}

fun RouteBuilderViewModel.routeToPlace(
    place: com.example.data.remote.PlaceSearchResult,
    currentLat: Double? = null,
    currentLng: Double? = null,
    currentFixTimeMs: Long? = null,
    staleMessage: String? = null
) {
    routeToPlace(
        destLat = place.lat,
        destLng = place.lng,
        destName = place.displayName,
        currentLat = currentLat,
        currentLng = currentLng,
        currentFixTimeMs = currentFixTimeMs,
        staleMessage = staleMessage
    )
}

fun RouteBuilderViewModel.reorderWaypoints(fromVisibleIndex: Int, toVisibleIndex: Int) {
    val state = _uiState.value
    val visible = state.visibleWaypoints
    if (fromVisibleIndex !in visible.indices || toVisibleIndex !in visible.indices) return
    if (fromVisibleIndex == toVisibleIndex) return

    pushHistory()

    val reordered = visible.toMutableList()
    val moved = reordered.removeAt(fromVisibleIndex)
    reordered.add(toVisibleIndex, moved)
    normalizeRoles(reordered)

    val validKeys = (0 until reordered.size - 1)
        .map { i -> "${reordered[i].id}_to_${reordered[i + 1].id}" }
        .toSet()

    val retainedLegs = state.legs.filterKeys { it in validKeys }.toMutableMap()
    val retainedFailed = state.failedLegs.filterKeys { it in validKeys }

    _uiState.update {
        it.copy(
            waypoints = reordered,
            legs = retainedLegs,
            failedLegs = retainedFailed
        )
    }

    var launchedAny = false
    for (i in 0 until reordered.size - 1) {
        val fromWp = reordered[i]
        val toWp = reordered[i + 1]
        val key = "${fromWp.id}_to_${toWp.id}"
        if (!retainedLegs.containsKey(key)) {
            launchedAny = true
            if (state.mode == PlannerMode.TRAIL &&
                state.baseTrailSegments.isNotEmpty() &&
                fromWp.trailKm != null &&
                toWp.trailKm != null
            ) {
                val sliced = TrailSlicer.sliceTrail(
                    state.baseTrailSegments,
                    min(fromWp.trailKm, toWp.trailKm),
                    max(fromWp.trailKm, toWp.trailKm)
                )
                val orderedPts = if (fromWp.trailKm > toWp.trailKm) sliced.points.reversed() else sliced.points
                retainedLegs[key] = LegGeometryData(
                    fromWaypointId = fromWp.id,
                    toWaypointId = toWp.id,
                    legMode = LegMode.TRAIL,
                    points = orderedPts,
                    lengthKm = sliced.lengthKm,
                    ascentM = sliced.ascentM,
                    descentM = sliced.descentM,
                    pathSharePct = 100.0,
                    roadKm = 0.0,
                    sourceName = "Vandrerute"
                )
                _uiState.update { it.copy(legs = retainedLegs.toMap()) }
            } else {
                computeSingleLeg(fromWp, toWp)
            }
        }
    }

    if (!launchedAny || state.mode == PlannerMode.TRAIL) {
        updateRouteStats()
    }
}

fun RouteBuilderViewModel.getPointsForDaySection(dayNumber: Int): List<RoutePoint3D> {
    val state = _uiState.value
    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until state.waypoints.size - 1) {
        val key = "${state.waypoints[i].id}_to_${state.waypoints[i + 1].id}"
        state.legs[key]?.let { orderedLegs.add(it) }
    }
    val allPoints = RouteGeometryStorageManager.getCombinedPoints(orderedLegs)
    if (allPoints.isEmpty()) {
        return state.visibleWaypoints.map { RoutePoint3D(it.lat, it.lng, 0.0) }
    }

    val day = state.daySummaries.find { it.dayNumber == dayNumber } ?: return allPoints
    val startM = day.startKm * 1000.0
    val endM = day.endKm * 1000.0
    if (endM <= startM) return allPoints

    val section = mutableListOf<RoutePoint3D>()
    var cumM = 0.0
    if (startM <= 0.0) {
        section.add(allPoints.first())
    }
    for (i in 1 until allPoints.size) {
        val prev = allPoints[i - 1]
        val curr = allPoints[i]
        val segM = haversineM(prev.lat, prev.lng, curr.lat, curr.lng)
        val nextCumM = cumM + segM
        if (nextCumM >= startM && cumM <= endM) {
            if (section.isEmpty()) section.add(prev)
            section.add(curr)
        }
        cumM = nextCumM
    }
    return section.ifEmpty { allPoints }
}

fun RouteBuilderViewModel.selectDaySection(dayNumber: Int): List<RoutePoint3D> {
    val sectionPts = getPointsForDaySection(dayNumber)
    if (sectionPts.isNotEmpty()) {
        val minLat = sectionPts.minOf { it.lat }
        val maxLat = sectionPts.maxOf { it.lat }
        val minLng = sectionPts.minOf { it.lng }
        val maxLng = sectionPts.maxOf { it.lng }
        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0
        val adjusted = computeSheetAdjustedCenter(
            targetLat = centerLat,
            targetLng = centerLng,
            zoomLevel = 12.5,
            sheetHeightFraction = _uiState.value.sheetHeightFraction
        )
        _uiState.update {
            it.copy(
                selectedDayNumber = dayNumber,
                mapViewportTarget = adjusted
            )
        }
    }
    return sectionPts
}

fun RouteBuilderViewModel.panMapAboveSheetTo(lat: Double, lng: Double, zoomLevel: Double = 14.5) {
    val adjusted = computeSheetAdjustedCenter(
        targetLat = lat,
        targetLng = lng,
        zoomLevel = zoomLevel,
        sheetHeightFraction = _uiState.value.sheetHeightFraction
    )
    _uiState.update {
        it.copy(mapViewportTarget = adjusted)
    }
}

fun RouteBuilderViewModel.computeSheetAdjustedCenter(
    targetLat: Double,
    targetLng: Double,
    zoomLevel: Double = 14.0,
    sheetHeightFraction: Float = _uiState.value.sheetHeightFraction
): LatLng {
    val clampedFraction = sheetHeightFraction.coerceIn(0f, 0.60f)
    val fullScreenLatSpan = (180.0 / Math.pow(2.0, zoomLevel.coerceIn(5.0, 19.0))) * 1.2
    val southShiftDeg = fullScreenLatSpan * (clampedFraction / 2.0)
    return LatLng(targetLat - southShiftDeg, targetLng)
}

fun RouteBuilderViewModel.openTripSettingsSheet() {
    _uiState.update { it.copy(showTripSettingsSheet = true) }
}

fun RouteBuilderViewModel.dismissTripSettingsSheet() {
    _uiState.update { it.copy(showTripSettingsSheet = false) }
}

fun RouteBuilderViewModel.updateTripSettings(
    paceKmh: Double,
    dailyTargetKm: Double,
    maxDetourKm: Double,
    startHour: Int,
    startDateEpochMs: Long,
    overnightPref: String
) {
    val parsedPref = try {
        OvernightType.valueOf(overnightPref)
    } catch (_: Exception) {
        OvernightType.BOTH
    }
    val formattedTime = String.format(Locale.US, "%02d:00", startHour.coerceIn(0, 23))
    updateTripSettings(
        paceKmh = paceKmh,
        dailyTargetKm = dailyTargetKm,
        startDateMillis = startDateEpochMs,
        startTimeStr = formattedTime,
        maxDetourKm = maxDetourKm,
        overnightPreference = parsedPref
    )
}

fun RouteBuilderViewModel.updateTripSettings(
    paceKmh: Double,
    dailyTargetKm: Double,
    startDateMillis: Long,
    startTimeStr: String,
    maxDetourKm: Double,
    overnightPreference: OvernightType = _uiState.value.overnightPreference
) {
    val clampedPace = com.example.data.local.HikingSettingsRanges.clampPaceKmh(paceKmh)
    val clampedDailyTarget = com.example.data.local.HikingSettingsRanges.clampDailyTargetKm(dailyTargetKm)
    val normalizedStartTime = com.example.data.local.HikingSettingsRanges.normalizeStartTime(startTimeStr)
    pacingSpeedKmh = clampedPace
    this.startTimeStr = normalizedStartTime
    _uiState.update {
        it.copy(
            paceKmh = clampedPace,
            dailyTargetKm = clampedDailyTarget,
            startDateMillis = startDateMillis,
            startTimeStr = normalizedStartTime,
            maxDetourKm = maxDetourKm.coerceIn(0.2, 10.0),
            overnightPreference = overnightPreference,
            showTripSettingsSheet = false
        )
    }
    viewModelScope.launch {
        settingsRepository.setMitTempoKmh(clampedPace)
        settingsRepository.setStartTime(normalizedStartTime)
        settingsRepository.setMaxDetourKm(maxDetourKm.coerceIn(0.2, 10.0))
        settingsRepository.setDailyTargetKm(clampedDailyTarget)
    }
    nightWeatherCache.clear()
    updateRouteStats()
}
