package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.util.OvernightCandidate
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailProjection
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// --- TRAIL Mode Tap, Detour Actions & Map Tap Editing ---

fun RouteBuilderViewModel.onTrailLineTapped(lat: Double, lng: Double) {
    val state = _uiState.value
    if (state.mode != PlannerMode.TRAIL || state.baseTrailSegments.isEmpty()) return
    val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse) ?: return
    _uiState.update { it.copy(tappedTrailPoint = proj) }
}

fun RouteBuilderViewModel.dismissTrailTapSheet() {
    _uiState.update { it.copy(tappedTrailPoint = null) }
}

fun RouteBuilderViewModel.dismissTrailLineTapSheet() = dismissTrailTapSheet()

fun RouteBuilderViewModel.trailTapStartHere() {
    val proj = _uiState.value.tappedTrailPoint ?: return
    dismissTrailTapSheet()
    val state = _uiState.value
    val wps = state.waypoints.toMutableList()
    val startIdx = wps.indexOfFirst { it.role == WaypointRole.START }
    if (startIdx == -1) return
    moveWaypointOnTrailToKm(wps[startIdx].id, proj.trailKm, finishDrag = true)
}

fun RouteBuilderViewModel.onTrailTapStartHer() = trailTapStartHere()

fun RouteBuilderViewModel.trailTapEndHere() {
    val proj = _uiState.value.tappedTrailPoint ?: return
    dismissTrailTapSheet()
    val state = _uiState.value
    val wps = state.waypoints.toMutableList()
    val endIdx = wps.indexOfLast { it.role == WaypointRole.END }
    if (endIdx == -1) return
    moveWaypointOnTrailToKm(wps[endIdx].id, proj.trailKm, finishDrag = true)
}

fun RouteBuilderViewModel.onTrailTapSlutHer() = trailTapEndHere()

/**
 * Plans a specific section [startKm, endKm] of the base trail (including long routes > 300 km).
 */
fun RouteBuilderViewModel.setTrailSection(startKm: Double, endKm: Double) {
    val state = _uiState.value
    if (state.mode != PlannerMode.TRAIL || state.baseTrailSegments.isEmpty()) return
    val totalKm = TrailSlicer.totalTrailKm(state.baseTrailSegments)
    val clampedStart = startKm.coerceIn(0.0, (totalKm - 0.1).coerceAtLeast(0.0))
    val clampedEnd = endKm.coerceIn(clampedStart + 0.1, totalKm.coerceAtLeast(clampedStart + 0.1))
    val startId = state.waypoints.firstOrNull { it.role == WaypointRole.START }?.id
    if (startId != null) {
        moveWaypointOnTrailToKm(startId, clampedStart, finishDrag = true)
    }
    val endId = _uiState.value.waypoints.lastOrNull { it.role == WaypointRole.END }?.id
    if (endId != null) {
        moveWaypointOnTrailToKm(endId, clampedEnd, finishDrag = true)
    }
}

fun RouteBuilderViewModel.trailTapOvernightHere() {
    val proj = _uiState.value.tappedTrailPoint ?: return
    dismissTrailTapSheet()
    insertWaypointOnTrailAtKm(
        trailKm = proj.trailKm,
        role = WaypointRole.OVERNIGHT,
        name = String.format(Locale.GERMAN, "Overnatning (km %.1f)", proj.trailKm)
    )
}

fun RouteBuilderViewModel.onTrailTapOvernatHer() = trailTapOvernightHere()

fun RouteBuilderViewModel.trailTapAddViaHere() {
    val proj = _uiState.value.tappedTrailPoint ?: return
    dismissTrailTapSheet()
    insertWaypointOnTrailAtKm(
        trailKm = proj.trailKm,
        role = WaypointRole.VIA,
        name = String.format(Locale.GERMAN, "Punkt (km %.1f)", proj.trailKm)
    )
}

fun RouteBuilderViewModel.onTrailTapTilfoejPunkt() = trailTapAddViaHere()

internal fun RouteBuilderViewModel.insertWaypointOnTrailAtKm(
    trailKm: Double,
    role: WaypointRole,
    name: String?,
    linkedPoiId: String? = null
) {
    val state = _uiState.value
    val segments = state.baseTrailSegments
    if (segments.isEmpty()) return

    pushHistory()
    val wps = state.waypoints.toMutableList()
    val pt = TrailSlicer.pointAtTrailKm(segments, trailKm, state.isReverse) ?: return

    // Find where trailKm falls among existing trailKm waypoints
    var insertIndex = wps.size - 1
    for (i in 0 until wps.size - 1) {
        val k0 = wps[i].trailKm
        val k1 = wps[i + 1].trailKm
        if (k0 != null && k1 != null && trailKm >= min(k0, k1) && trailKm <= max(k0, k1)) {
            insertIndex = i + 1
            break
        }
    }
    insertIndex = insertIndex.coerceIn(1, max(1, wps.size - 1))

    val newWp = RouteWaypoint(
        lat = pt.lat,
        lng = pt.lng,
        role = role,
        legModeToNext = LegMode.TRAIL,
        name = name,
        linkedPoiId = linkedPoiId,
        trailKm = trailKm
    )

    val prevWp = wps[insertIndex - 1]
    val nextWp = wps[insertIndex]
    wps.add(insertIndex, newWp)
    normalizeRoles(wps)

    val updatedLegs = state.legs.toMutableMap()
    updatedLegs.remove("${prevWp.id}_to_${nextWp.id}")

    val leg1 = TrailSlicer.buildTrailLegGeometry(
        fromWaypointId = prevWp.id,
        toWaypointId = newWp.id,
        segments = segments,
        fromKm = prevWp.trailKm ?: 0.0,
        toKm = trailKm,
        isReverse = state.isReverse
    )
    val leg2 = TrailSlicer.buildTrailLegGeometry(
        fromWaypointId = newWp.id,
        toWaypointId = nextWp.id,
        segments = segments,
        fromKm = trailKm,
        toKm = nextWp.trailKm ?: TrailSlicer.totalTrailKm(segments),
        isReverse = state.isReverse
    )
    updatedLegs[leg1.legKey] = leg1
    updatedLegs[leg2.legKey] = leg2

    _uiState.update {
        it.copy(
            waypoints = wps,
            legs = updatedLegs
        )
    }

    viewModelScope.launch(Dispatchers.IO) {
        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg1)
        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg2)
    }
    updateRouteStats()
}

fun RouteBuilderViewModel.addDetourPoiToRoute(
    lat: Double,
    lng: Double,
    name: String,
    role: WaypointRole,
    poiId: String? = null,
    poiType: String? = null
) {
    _uiState.update { it.copy(selectedNearbyPoiForSheet = null, selectedMapPoiForSheet = null) }
    addStopOrDetour(
        lat = lat,
        lng = lng,
        role = role,
        name = name,
        linkedPoiId = poiId,
        spotType = poiType
    )
}

fun RouteBuilderViewModel.addOvernightDetourPoi(
    lat: Double,
    lng: Double,
    name: String,
    spotType: String = "SHELTER",
    poiId: String? = null,
    bookingRequired: Boolean = false,
    hasWater: Boolean = false,
    hasToilet: Boolean = false
) {
    addStopOrDetour(
        lat = lat,
        lng = lng,
        role = WaypointRole.OVERNIGHT,
        name = name,
        linkedPoiId = poiId,
        spotType = spotType,
        bookingRequired = bookingRequired,
        hasWater = hasWater,
        hasToilet = hasToilet
    )
}

fun RouteBuilderViewModel.selectNearbyPoiForSheet(poi: PlannerMapPoi?) {
    _uiState.update { it.copy(selectedNearbyPoiForSheet = poi) }
}

fun RouteBuilderViewModel.onPoiTapped(poi: PlannerMapPoi) {
    val distMeters = if (poi.distanceToRouteMeters > 0.0) {
        poi.distanceToRouteMeters
    } else {
        computeDistanceToRouteMeters(poi.lat, poi.lng)
    }
    val booking = poi.bookingRequired || _uiState.value.nearbyShelters.any {
        it.id == poi.id && it.bookingRequired == true
    }
    val enriched = poi.copy(
        distanceToRouteMeters = distMeters,
        bookingRequired = booking
    )
    _uiState.update {
        it.copy(
            selectedNearbyPoiForSheet = enriched,
            selectedWaypointId = null,
            selectedLegKey = null
        )
    }
}

fun RouteBuilderViewModel.onPoiTapped(
    id: String,
    name: String,
    type: String,
    lat: Double,
    lng: Double,
    bookingRequired: Boolean = false,
    hasWater: Boolean = false,
    hasToilet: Boolean = false
) {
    onPoiTapped(
        PlannerMapPoi(
            id = id,
            name = name,
            type = type,
            lat = lat,
            lng = lng,
            distanceToRouteMeters = computeDistanceToRouteMeters(lat, lng),
            bookingRequired = bookingRequired,
            hasWater = hasWater,
            hasToilet = hasToilet
        )
    )
}

fun RouteBuilderViewModel.confirmPoiAsOvernight() {
    val poi = _uiState.value.selectedNearbyPoiForSheet ?: return
    addDetourPoiToRoute(
        lat = poi.lat,
        lng = poi.lng,
        name = poi.name,
        role = WaypointRole.OVERNIGHT,
        poiId = poi.id,
        poiType = poi.type
    )
}

fun RouteBuilderViewModel.confirmPoiAsStop() {
    val poi = _uiState.value.selectedNearbyPoiForSheet ?: return
    addDetourPoiToRoute(
        lat = poi.lat,
        lng = poi.lng,
        name = poi.name,
        role = WaypointRole.VIA,
        poiId = poi.id,
        poiType = poi.type
    )
}

internal fun RouteBuilderViewModel.computeDistanceToRouteMeters(lat: Double, lng: Double): Double {
    val state = _uiState.value
    var minDist = Double.MAX_VALUE
    for (leg in state.legs.values) {
        for (pt in leg.points) {
            val d = haversineM(lat, lng, pt.lat, pt.lng)
            if (d < minDist) minDist = d
        }
    }
    if (minDist == Double.MAX_VALUE && state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
        if (proj != null) minDist = proj.distanceMeters
    }
    if (minDist == Double.MAX_VALUE) {
        for (wp in state.waypoints) {
            val d = haversineM(lat, lng, wp.lat, wp.lng)
            if (d < minDist) minDist = d
        }
    }
    return if (minDist == Double.MAX_VALUE) 0.0 else minDist
}

fun RouteBuilderViewModel.addStopOrDetour(
    lat: Double,
    lng: Double,
    role: WaypointRole,
    name: String?,
    linkedPoiId: String? = null,
    spotType: String? = null,
    bookingRequired: Boolean = false,
    hasWater: Boolean = false,
    hasToilet: Boolean = false
) {
    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
        if (proj != null && proj.distanceMeters <= 50.0 && linkedPoiId == null) {
            insertWaypointOnTrailAtKm(
                trailKm = proj.trailKm,
                role = role,
                name = name ?: String.format(Locale.GERMAN, "Punkt (km %.1f)", proj.trailKm)
            )
            return
        }

        // Off-trail detour (> 50m or explicit POI)
        val pendingDetourKey = "detour_routing_${UUID.randomUUID()}"
        _uiState.update { current ->
            current.copy(loadingLegKeys = current.loadingLegKeys + pendingDetourKey)
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                pushHistory()
                val wps = _uiState.value.waypoints.toMutableList()
                val segments = _uiState.value.baseTrailSegments
                val isRev = _uiState.value.isReverse
                val projKm = proj?.trailKm ?: (TrailSlicer.totalTrailKm(segments) / 2.0)

                var prevTrailIdx = 0
                var nextTrailIdx = wps.lastIndex
                for (i in wps.indices) {
                    val km = wps[i].trailKm ?: continue
                    if (km <= projKm) prevTrailIdx = i
                    if (km >= projKm && i > prevTrailIdx) {
                        nextTrailIdx = i
                        break
                    }
                }
                if (nextTrailIdx <= prevTrailIdx && wps.size >= 2) {
                    prevTrailIdx = max(0, wps.lastIndex - 1)
                    nextTrailIdx = wps.lastIndex
                }

                val prevTrailWp = wps[prevTrailIdx]
                val nextTrailWp = wps[nextTrailIdx]

                val rawPin = RouteWaypoint(
                    lat = lat,
                    lng = lng,
                    role = role,
                    legModeToNext = LegMode.SNAP,
                    name = name ?: if (role == WaypointRole.OVERNIGHT) "Overnatning" else "Omvejspunkt",
                    linkedPoiId = linkedPoiId,
                    trailKm = null,
                    spotType = spotType,
                    bookingRequired = bookingRequired,
                    hasWaterNearby = hasWater,
                    hasToiletNearby = hasToilet
                )

                val detourPlan = TrailSlicer.buildTrailDetour(
                    segments = segments,
                    prevWp = prevTrailWp,
                    nextWp = nextTrailWp,
                    pinWp = rawPin,
                    routingEngine = routingEngine,
                    options = _uiState.value.options,
                    isReverse = isRev
                )

                val updatedLegs = _uiState.value.legs.toMutableMap()
                updatedLegs.remove("${prevTrailWp.id}_to_${nextTrailWp.id}")

                wps[prevTrailIdx] = prevTrailWp.copy(legModeToNext = LegMode.TRAIL)
                wps.addAll(
                    prevTrailIdx + 1,
                    listOf(detourPlan.exitHelper, detourPlan.detourWaypoint, detourPlan.rejoinHelper)
                )
                normalizeRoles(wps)

                for (leg in detourPlan.legs) {
                    updatedLegs[leg.legKey] = leg
                }

                updateRouteStats { current ->
                    val mergedFailed = current.failedLegs.toMutableMap().apply {
                        putAll(detourPlan.failedLegs)
                    }
                    current.copy(
                        waypoints = wps,
                        legs = updatedLegs,
                        failedLegs = mergedFailed,
                        loadingLegKeys = current.loadingLegKeys - pendingDetourKey,
                        errorMessage = detourPlan.failedLegs.values.firstOrNull() ?: current.errorMessage,
                        selectedWaypointId = detourPlan.detourWaypoint.id
                    )
                }

                withContext(Dispatchers.IO) {
                    for (leg in detourPlan.legs) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
                    }
                }

                val detourLabel = TrailSlicer.formatDetourCost(
                    detourPlan.detourWaypoint.name,
                    detourPlan.detourCostKm,
                    detourPlan.detourAscentM
                )
                _userMessage.emit(detourLabel)
            } finally {
                _uiState.update { current ->
                    if (pendingDetourKey in current.loadingLegKeys) {
                        current.copy(loadingLegKeys = current.loadingLegKeys - pendingDetourKey)
                    } else {
                        current
                    }
                }
            }
        }
        return
    }

    // FREE mode: insert as OVERNIGHT or VIA along the closest leg (or at end)
    val wps = state.waypoints
    if (wps.size < 2) {
        addPointAtEnd(lat, lng, name)
        return
    }
    val cand = OvernightCandidate(
        id = linkedPoiId ?: UUID.randomUUID().toString(),
        name = name ?: if (role == WaypointRole.OVERNIGHT) "Overnatning" else "Stop",
        type = spotType ?: "SHELTER",
        lat = lat,
        lng = lng,
        walkKm = 0.0,
        detourMeters = 0.0,
        bookingRequired = bookingRequired,
        hasWaterNearby = hasWater,
        hasToiletNearby = hasToilet
    )
    if (role == WaypointRole.OVERNIGHT) {
        insertOvernightCandidate(cand)
    } else {
        insertViaPointOnLine(lat, lng)
    }
}

fun RouteBuilderViewModel.selectMapPoi(poi: PlannerMapPoiSelection?) {
    _uiState.update { it.copy(selectedMapPoiForSheet = poi) }
}

fun RouteBuilderViewModel.addSelectedPoiAsOvernight() {
    val poi = _uiState.value.selectedMapPoiForSheet ?: return
    _uiState.update { it.copy(selectedMapPoiForSheet = null) }
    addStopOrDetour(
        lat = poi.lat,
        lng = poi.lng,
        role = WaypointRole.OVERNIGHT,
        name = poi.name,
        linkedPoiId = poi.id
    )
}

fun RouteBuilderViewModel.addSelectedPoiAsStop() {
    val poi = _uiState.value.selectedMapPoiForSheet ?: return
    _uiState.update { it.copy(selectedMapPoiForSheet = null) }
    addStopOrDetour(
        lat = poi.lat,
        lng = poi.lng,
        role = WaypointRole.VIA,
        name = poi.name,
        linkedPoiId = poi.id
    )
}

fun RouteBuilderViewModel.onWaypointDragging(waypointId: String, fingerLat: Double, fingerLng: Double): TrailProjection? {
    val state = _uiState.value
    if (state.mode != PlannerMode.TRAIL || state.baseTrailSegments.isEmpty()) return null
    val wp = state.waypoints.find { it.id == waypointId } ?: return null
    if (wp.trailKm == null) return null

    val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, fingerLat, fingerLng, state.isReverse) ?: return null
    _uiState.update { it.copy(draggingWaypointId = waypointId) }
    moveWaypointOnTrailToKm(waypointId, proj.trailKm, finishDrag = false)
    return proj
}

fun RouteBuilderViewModel.onDraggingWaypoint(waypointId: String, fingerLat: Double, fingerLng: Double) {
    onWaypointDragging(waypointId, fingerLat, fingerLng)
}

fun RouteBuilderViewModel.onWaypointDragged(waypointId: String, lat: Double, lng: Double) {
    moveWaypoint(waypointId, lat, lng)
}

fun RouteBuilderViewModel.moveWaypointOnTrailToKm(waypointId: String, rawTargetKm: Double, finishDrag: Boolean = true) {
    val state = _uiState.value
    val segments = state.baseTrailSegments
    if (segments.isEmpty()) return
    val wps = state.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1) return

    val totalTrailKm = TrailSlicer.totalTrailKm(segments)
    val minAllowedKm = (index - 1 downTo 0).firstNotNullOfOrNull { wps[it].trailKm }?.plus(0.05) ?: 0.0
    val maxAllowedKm = (index + 1 until wps.size).firstNotNullOfOrNull { wps[it].trailKm }?.minus(0.05) ?: totalTrailKm
    val clampedKm = rawTargetKm.coerceIn(min(minAllowedKm, maxAllowedKm), max(minAllowedKm, maxAllowedKm))

    val pt = TrailSlicer.pointAtTrailKm(segments, clampedKm, state.isReverse) ?: return
    if (finishDrag) {
        pushHistory()
    }

    val oldWp = wps[index]
    val updatedName = when (oldWp.role) {
        WaypointRole.START -> String.format(Locale.GERMAN, "Start (km %.1f)", clampedKm)
        WaypointRole.END -> String.format(Locale.GERMAN, "Slut (km %.1f)", clampedKm)
        else -> oldWp.name
    }
    val updatedWp = oldWp.copy(
        lat = pt.lat,
        lng = pt.lng,
        trailKm = clampedKm,
        name = updatedName
    )
    wps[index] = updatedWp

    val updatedLegs = state.legs.toMutableMap()
    val legsToPersist = mutableListOf<LegGeometryData>()

    if (index > 0) {
        val prevWp = wps[index - 1]
        if (prevWp.trailKm != null) {
            val prevLeg = TrailSlicer.buildTrailLegGeometry(
                fromWaypointId = prevWp.id,
                toWaypointId = updatedWp.id,
                segments = segments,
                fromKm = prevWp.trailKm,
                toKm = clampedKm,
                isReverse = state.isReverse
            )
            updatedLegs[prevLeg.legKey] = prevLeg
            legsToPersist.add(prevLeg)
        }
    }

    if (index < wps.size - 1) {
        val nextWp = wps[index + 1]
        if (nextWp.trailKm != null) {
            val nextLeg = TrailSlicer.buildTrailLegGeometry(
                fromWaypointId = updatedWp.id,
                toWaypointId = nextWp.id,
                segments = segments,
                fromKm = clampedKm,
                toKm = nextWp.trailKm,
                isReverse = state.isReverse
            )
            updatedLegs[nextLeg.legKey] = nextLeg
            legsToPersist.add(nextLeg)
        }
    }

    val kmLabel = if (finishDrag) null else String.format(Locale.GERMAN, "km %.1f", clampedKm)
    _uiState.update {
        it.copy(
            waypoints = wps,
            legs = updatedLegs,
            draggingWaypointId = if (finishDrag) null else waypointId,
            draggingPinKmLabel = kmLabel,
            movingWaypointId = if (finishDrag) null else it.movingWaypointId
        )
    }

    if (finishDrag && legsToPersist.isNotEmpty()) {
        viewModelScope.launch(Dispatchers.IO) {
            for (leg in legsToPersist) {
                RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
            }
        }
    }
    updateRouteStats()
}

fun RouteBuilderViewModel.toggleNextLegMode() {
    val current = _uiState.value.nextLegMode
    val next = if (current == LegMode.SNAP) LegMode.STRAIGHT else LegMode.SNAP
    val wps = _uiState.value.waypoints.toMutableList()
    if (wps.isNotEmpty()) {
        val lastIdx = wps.lastIndex
        wps[lastIdx] = wps[lastIdx].copy(legModeToNext = next)
    }
    _uiState.update {
        it.copy(
            nextLegMode = next,
            waypoints = wps
        )
    }
}

fun RouteBuilderViewModel.toggleOvernightTool() {
    val nextActive = !_uiState.value.isOvernightToolActive
    _uiState.update { it.copy(isOvernightToolActive = nextActive) }
}

fun RouteBuilderViewModel.setInteractionMode(mode: PlannerInteractionMode) {
    _uiState.update {
        it.copy(
            interactionMode = mode,
            isCrosshairMode = if (mode == PlannerInteractionMode.KIG) false else it.isCrosshairMode
        )
    }
}

fun RouteBuilderViewModel.onMapTapped(lat: Double, lng: Double) {
    val state = _uiState.value
    if (state.selectedWaypointId != null || state.selectedLegKey != null || state.selectedNearbyPoiForSheet != null) {
        _uiState.update {
            it.copy(
                selectedWaypointId = null,
                selectedLegKey = null,
                selectedNearbyPoiForSheet = null
            )
        }
        if (state.interactionMode == PlannerInteractionMode.KIG) return
    }
    if (state.interactionMode == PlannerInteractionMode.KIG) {
        return
    }
    addPointAtEnd(lat, lng)
}

fun RouteBuilderViewModel.addPointAtEnd(lat: Double, lng: Double, name: String? = null) {
    addPointAtEndInternal(lat, lng, name)
}

internal fun RouteBuilderViewModel.addPointAtEndInternal(lat: Double, lng: Double, name: String? = null) {
    val movingId = _uiState.value.movingWaypointId
    if (movingId != null) {
        _uiState.update { it.copy(movingWaypointId = null) }
        moveWaypoint(movingId, lat, lng)
        return
    }

    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val targetRole = if (state.isOvernightToolActive) WaypointRole.OVERNIGHT else WaypointRole.VIA
        val defaultLabel = if (state.isOvernightToolActive) "Overnatning" else "Stop"
        addStopOrDetour(
            lat = lat,
            lng = lng,
            role = targetRole,
            name = name ?: defaultLabel
        )
        return
    }

    val currentWps = state.waypoints.toMutableList()

    if (state.waitingForStartPointTap || (currentWps.size == 1 && currentWps.first().role == WaypointRole.END)) {
        if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
            val startWp = currentWps.firstOrNull { it.role == WaypointRole.START }
            if (proj != null && startWp != null) {
                _uiState.update {
                    it.copy(
                        waitingForStartPointTap = false,
                        staleStartLocationMessage = null,
                        errorMessage = null
                    )
                }
                moveWaypointOnTrailToKm(startWp.id, proj.trailKm, finishDrag = true)
                return
            }
        }
        pushHistory()
        val startWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = WaypointRole.START,
            legModeToNext = state.nextLegMode,
            name = name ?: "Start"
        )
        val updatedLegs = state.legs.toMutableMap()
        if (currentWps.isNotEmpty() && currentWps.first().role == WaypointRole.START) {
            val oldStartId = currentWps.first().id
            updatedLegs.keys.removeAll { it.startsWith("${oldStartId}_to_") }
            currentWps[0] = startWp
        } else {
            currentWps.add(0, startWp)
        }
        normalizeRoles(currentWps)
        _uiState.update {
            it.copy(
                waypoints = currentWps,
                legs = updatedLegs,
                waitingForStartPointTap = false,
                staleStartLocationMessage = null,
                errorMessage = null
            )
        }
        if (currentWps.size >= 2) {
            computeSingleLeg(currentWps[0], currentWps[1])
        } else {
            updateRouteStats()
        }
        return
    }

    pushHistory()
    val role = when {
        currentWps.isEmpty() -> WaypointRole.START
        state.isOvernightToolActive -> WaypointRole.OVERNIGHT
        else -> WaypointRole.END
    }

    if (currentWps.isNotEmpty()) {
        val lastIdx = currentWps.lastIndex
        val prevWpUpdated = currentWps[lastIdx].copy(
            role = if (currentWps[lastIdx].role == WaypointRole.END) WaypointRole.VIA else currentWps[lastIdx].role,
            legModeToNext = state.nextLegMode
        )
        currentWps[lastIdx] = prevWpUpdated
    }

    val newWp = RouteWaypoint(
        lat = lat,
        lng = lng,
        role = role,
        legModeToNext = state.nextLegMode,
        name = name
    )
    currentWps.add(newWp)

    _uiState.update { it.copy(waypoints = currentWps) }

    if (currentWps.size >= 2) {
        val prevWp = currentWps[currentWps.size - 2]
        computeSingleLeg(prevWp, newWp)
    } else {
        fetchSunsetForRoute(lat, lng)
        updateRouteStats()
    }
}
