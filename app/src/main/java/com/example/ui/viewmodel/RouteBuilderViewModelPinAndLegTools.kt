package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.util.routing.LatLng
import com.example.util.routing.LegMode
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Long-press on the line: Insert a via point at the tapped location.
 */
fun RouteBuilderViewModel.insertViaPointOnLine(
    lat: Double,
    lng: Double,
    name: String? = null,
    linkedPoiId: String? = null
) {
    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        addStopOrDetour(lat, lng, WaypointRole.VIA, name ?: "Via", linkedPoiId)
        return
    }

    val wps = state.waypoints
    if (wps.size < 2) {
        addPointAtEndInternal(lat, lng, name)
        return
    }

    var bestIndex = -1
    var minDistanceM = Double.MAX_VALUE

    for (i in 0 until wps.size - 1) {
        val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
        val leg = _uiState.value.legs[legKey]
        val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

        for (pt in pts) {
            val d = haversineM(lat, lng, pt.lat, pt.lng)
            if (d < minDistanceM) {
                minDistanceM = d
                bestIndex = i + 1
            }
        }
    }

    if (bestIndex != -1) {
        insertPointAtIndex(bestIndex, lat, lng, name = name, linkedPoiId = linkedPoiId)
    } else {
        addPointAtEndInternal(lat, lng, name)
    }
}

/**
 * Inserts a via point at a specific index, replacing leg (i-1 -> i) with two legs.
 */
fun RouteBuilderViewModel.insertPointAtIndex(
    index: Int,
    lat: Double,
    lng: Double,
    name: String? = null,
    linkedPoiId: String? = null
) {
    pushHistory()
    val currentWps = _uiState.value.waypoints.toMutableList()
    val newWp = RouteWaypoint(
        lat = lat,
        lng = lng,
        role = WaypointRole.VIA,
        legModeToNext = LegMode.SNAP,
        name = name ?: "Via ${currentWps.size}",
        linkedPoiId = linkedPoiId
    )

    currentWps.add(index, newWp)
    normalizeRoles(currentWps)
    _uiState.update { it.copy(waypoints = currentWps) }

    val prevWp = currentWps[index - 1]
    val nextWp = currentWps[index + 1]

    val oldKey = "${prevWp.id}_to_${nextWp.id}"
    val updatedLegs = _uiState.value.legs.toMutableMap()
    updatedLegs.remove(oldKey)
    val updatedFailed = _uiState.value.failedLegs.toMutableMap()
    updatedFailed.remove(oldKey)
    _uiState.update { it.copy(legs = updatedLegs, failedLegs = updatedFailed) }

    computeSingleLeg(prevWp, newWp)
    computeSingleLeg(newWp, nextWp)
}

/**
 * Waypoint dragged/moved:
 * - In TRAIL mode, if the waypoint has `trailKm`, keeps it on the trail (Requirement C) and slices TRAIL legs immediately without network calls.
 * - If the waypoint is an off-trail detour pin in TRAIL mode, recomputes its detour.
 * - In FREE mode, re-routes ONLY the legs touching this changed point.
 */
fun RouteBuilderViewModel.moveWaypoint(waypointId: String, newLat: Double, newLng: Double) {
    val state = _uiState.value
    val wps = state.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1) return

    val oldWp = wps[index]
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        if (oldWp.trailKm != null) {
            val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, newLat, newLng, state.isReverse)
            if (proj != null) {
                moveWaypointOnTrailToKm(waypointId, proj.trailKm, finishDrag = true)
                return
            }
        } else {
            val role = oldWp.role
            val name = oldWp.name
            val poiId = oldWp.linkedPoiId
            deleteWaypoint(waypointId, showUndoSnackbar = false)
            addStopOrDetour(newLat, newLng, role, name, poiId)
            return
        }
    }

    pushHistory()
    val updatedWp = oldWp.copy(lat = newLat, lng = newLng)
    wps[index] = updatedWp
    _uiState.update {
        it.copy(
            waypoints = wps,
            movingWaypointId = null,
            draggingPinKmLabel = null
        )
    }

    if (index > 0) {
        val prevWp = wps[index - 1]
        computeSingleLeg(prevWp, updatedWp)
    }
    if (index < wps.size - 1) {
        val nextWp = wps[index + 1]
        computeSingleLeg(updatedWp, nextWp)
    }
}

/**
 * Delete waypoint at index.
 * Requirement D: In TRAIL mode, removing a detour pin removes its hidden helpers and restores the plain TRAIL leg.
 */
fun RouteBuilderViewModel.deleteWaypoint(waypointId: String, showUndoSnackbar: Boolean = true) {
    val state = _uiState.value
    val wps = state.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1) return

    pushHistory()
    if (showUndoSnackbar) {
        lastDeleteType = RouteBuilderViewModel.LastDeleteType.ROUTE_WAYPOINT
    }

    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val (newWps, newLegs) = TrailSlicer.removeDetourAndRestoreTrailLeg(
            waypoints = wps,
            legs = state.legs,
            removedWaypointId = waypointId,
            segments = state.baseTrailSegments,
            isReverse = state.isReverse
        )
        val mutableWps = newWps.toMutableList()
        normalizeRoles(mutableWps)
        _uiState.update {
            it.copy(
                waypoints = mutableWps,
                legs = newLegs,
                selectedWaypointId = null,
                deletedPinSnackbarMessage = if (showUndoSnackbar) "Punkt slettet · Fortryd" else it.deletedPinSnackbarMessage
            )
        }
        updateRouteStats()
        return
    }

    val removed = wps.removeAt(index)
    normalizeRoles(wps)

    val currentLegs = _uiState.value.legs.toMutableMap()
    val keysToRemove = currentLegs.keys.filter { it.contains(removed.id) }
    for (k in keysToRemove) currentLegs.remove(k)

    val currentFailed = _uiState.value.failedLegs.toMutableMap()
    val failedKeysToRemove = currentFailed.keys.filter { it.contains(removed.id) }
    for (k in failedKeysToRemove) currentFailed.remove(k)

    _uiState.update {
        it.copy(
            waypoints = wps,
            legs = currentLegs,
            failedLegs = currentFailed,
            selectedWaypointId = null,
            deletedPinSnackbarMessage = if (showUndoSnackbar) "Punkt slettet · Fortryd" else it.deletedPinSnackbarMessage
        )
    }

    if (index > 0 && index < wps.size) {
        computeSingleLeg(wps[index - 1], wps[index])
    } else {
        updateRouteStats()
    }
}

/**
 * Toggle role: VIA <-> OVERNIGHT
 */
fun RouteBuilderViewModel.toggleWaypointOvernight(waypointId: String) {
    val wps = _uiState.value.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1) return

    pushHistory()
    val current = wps[index]
    val newRole = if (current.role == WaypointRole.OVERNIGHT) WaypointRole.VIA else WaypointRole.OVERNIGHT
    wps[index] = current.copy(role = newRole)
    _uiState.update { it.copy(waypoints = wps) }
    updateRouteStats()
}

/**
 * Toggle leg mode from this waypoint to the next: SNAP <-> STRAIGHT
 */
fun RouteBuilderViewModel.toggleLegMode(waypointId: String) {
    val wps = _uiState.value.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1 || index >= wps.size - 1) return

    pushHistory()
    val current = wps[index]
    val newMode = if (current.legModeToNext == LegMode.SNAP) LegMode.STRAIGHT else LegMode.SNAP
    wps[index] = current.copy(legModeToNext = newMode)
    _uiState.update { it.copy(waypoints = wps) }

    computeSingleLeg(wps[index], wps[index + 1])
}

/**
 * Requirement 6: "Prøv igen" re-runs only that failed leg.
 */
fun RouteBuilderViewModel.retryFailedLeg(legKey: String) {
    val parts = legKey.split("_to_")
    if (parts.size != 2) return
    val wps = _uiState.value.waypoints
    val fromWp = wps.find { it.id == parts[0] } ?: return
    val toWp = wps.find { it.id == parts[1] } ?: return
    computeSingleLeg(fromWp, toWp)
}

/**
 * Requirement 6: "Tegn lige linje" sets only that failed leg to STRAIGHT and computes it.
 */
fun RouteBuilderViewModel.setFailedLegStraight(legKey: String) {
    val parts = legKey.split("_to_")
    if (parts.size != 2) return
    val wps = _uiState.value.waypoints.toMutableList()
    val fromIdx = wps.indexOfFirst { it.id == parts[0] }
    val toIdx = wps.indexOfFirst { it.id == parts[1] }
    if (fromIdx == -1 || toIdx == -1) return

    pushHistory()
    wps[fromIdx] = wps[fromIdx].copy(legModeToNext = LegMode.STRAIGHT)
    val updatedFailed = _uiState.value.failedLegs.toMutableMap()
    updatedFailed.remove(legKey)
    _uiState.update {
        it.copy(
            waypoints = wps,
            failedLegs = updatedFailed,
            errorMessage = null
        )
    }
    computeSingleLeg(wps[fromIdx], wps[toIdx])
}

/**
 * Requirement 6: "Flyt punkt" selects the endpoint of the failed leg for moving (by tap or drag).
 */
fun RouteBuilderViewModel.activateMovePointForFailedLeg(legKey: String) {
    val parts = legKey.split("_to_")
    if (parts.size != 2) return
    val targetId = parts[1]
    _uiState.update {
        it.copy(
            selectedWaypointId = targetId,
            movingWaypointId = targetId,
            errorMessage = "Tryk på kortet eller træk markøren for at flytte punktet"
        )
    }
    viewModelScope.launch {
        _userMessage.emit("Tryk på kortet eller træk markøren for at flytte punktet")
    }
}

/**
 * Rename waypoint.
 */
fun RouteBuilderViewModel.renameWaypoint(waypointId: String, newName: String) {
    val wps = _uiState.value.waypoints.toMutableList()
    val index = wps.indexOfFirst { it.id == waypointId }
    if (index == -1) return

    pushHistory()
    wps[index] = wps[index].copy(name = newName.ifBlank { null })
    _uiState.update { it.copy(waypoints = wps) }
    updateRouteStats()
}

/**
 * Requirement C: "Vend rute" - Reverses the direction (`isReverse` in TRAIL mode) and recomputes everything.
 */
fun RouteBuilderViewModel.reverseRoute() {
    val state = _uiState.value
    val wps = state.waypoints
    if (wps.size < 2) return

    pushHistory()
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val newReverse = !state.isReverse
        val totalTrailKm = TrailSlicer.totalTrailKm(state.baseTrailSegments)
        val reversed = wps.asReversed().mapIndexed { _, wp ->
            val newTrailKm = wp.trailKm?.let { (totalTrailKm - it).coerceIn(0.0, totalTrailKm) }
            wp.copy(trailKm = newTrailKm)
        }.toMutableList()
        normalizeRoles(reversed)
        for (i in 0 until reversed.size - 1) {
            val from = reversed[i]
            val to = reversed[i + 1]
            val modeToNext = if (from.trailKm != null && to.trailKm != null) LegMode.TRAIL else LegMode.SNAP
            reversed[i] = from.copy(legModeToNext = modeToNext)
        }

        val newMarkers = TrailSlicer.generateKmMarkers(state.baseTrailSegments, stepKm = 5.0, isReverse = newReverse)
        _uiState.update {
            it.copy(
                isReverse = newReverse,
                baseTrailKmMarkers = newMarkers,
                waypoints = reversed,
                legs = emptyMap(),
                failedLegs = emptyMap()
            )
        }
        recomputeAllLegs()
        return
    }

    val reversed = wps.asReversed().map { it.copy() }.toMutableList()
    normalizeRoles(reversed)

    _uiState.update {
        it.copy(
            waypoints = reversed,
            legs = emptyMap(),
            failedLegs = emptyMap()
        )
    }

    recomputeAllLegs()
}

/**
 * "Ryd" - Clears all waypoints and legs (or resets to full trail in TRAIL mode).
 */
fun RouteBuilderViewModel.clearRoute() {
    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailId != null) {
        pushHistory()
        initializeTrailMode(state.baseTrailId)
        return
    }

    pushHistory()
    lastComputedStagesSignature = null
    _uiState.update {
        it.copy(
            waypoints = emptyList(),
            legs = emptyMap(),
            loadingLegKeys = emptySet(),
            failedLegs = emptyMap(),
            waitingForStartPointTap = false,
            movingWaypointId = null,
            stats = RouteStats(0.0, 0.0, 0.0, null, 0.0, hasWayInfo = false),
            timeEstimateFormatted = "0 min",
            gpxOriginalPoints = emptyList(),
            alternatives = emptyList(),
            selectedWaypointId = null,
            errorMessage = null,
            plannedStages = emptyList(),
            daySummaries = emptyList(),
            dayBoundaryWalkKms = emptyList()
        )
    }
}

/**
 * "Fra min position" - Sets START at current GPS fix.
 */
fun RouteBuilderViewModel.setStartAtGps(lat: Double, lng: Double) {
    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
        if (proj != null) {
            val startWp = state.waypoints.firstOrNull { it.role == WaypointRole.START }
            if (startWp != null) {
                moveWaypointOnTrailToKm(startWp.id, proj.trailKm, finishDrag = true)
                return
            }
        }
    }

    pushHistory()
    val wps = _uiState.value.waypoints.toMutableList()
    val startWp = RouteWaypoint(
        lat = lat,
        lng = lng,
        role = WaypointRole.START,
        name = "Min position"
    )

    if (wps.isEmpty()) {
        wps.add(startWp)
    } else {
        if (wps.first().role == WaypointRole.START) {
            wps[0] = startWp
        } else {
            wps.add(0, startWp)
        }
    }
    normalizeRoles(wps)
    _uiState.update {
        it.copy(
            waypoints = wps,
            waitingForStartPointTap = false,
            errorMessage = null
        )
    }

    if (wps.size >= 2) {
        computeSingleLeg(wps[0], wps[1])
    } else {
        updateRouteStats()
    }
}

fun RouteBuilderViewModel.selectWaypoint(waypointId: String?) {
    _uiState.update { state ->
        val wp = if (waypointId != null) state.waypoints.find { it.id == waypointId } else null
        val target = if (wp != null) {
            computeSheetAdjustedCenter(wp.lat, wp.lng, zoomLevel = 14.5, sheetHeightFraction = state.sheetHeightFraction)
        } else {
            state.mapViewportTarget
        }
        state.copy(
            selectedWaypointId = waypointId,
            selectedLegKey = if (waypointId != null) null else state.selectedLegKey,
            mapViewportTarget = target
        )
    }
}

fun RouteBuilderViewModel.selectLeg(legKey: String?) {
    _uiState.update { state ->
        val legPts = if (legKey != null) state.legs[legKey]?.points else null
        val target = if (!legPts.isNullOrEmpty()) {
            val mid = legPts[legPts.size / 2]
            computeSheetAdjustedCenter(mid.lat, mid.lng, zoomLevel = 13.5, sheetHeightFraction = state.sheetHeightFraction)
        } else {
            state.mapViewportTarget
        }
        state.copy(
            selectedLegKey = legKey,
            selectedWaypointId = if (legKey != null) null else state.selectedWaypointId,
            mapViewportTarget = target
        )
    }
}

/**
 * UI Round 1 Item 4:
 * Snap to the trail line, a shelter or a fri teltning area within 25 m.
 */
fun RouteBuilderViewModel.snapCoordinateWithin25m(lat: Double, lng: Double): SnapCandidateResult {
    val state = _uiState.value
    val thresholdM = 25.0

    if (state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
        if (proj != null && proj.distanceMeters <= thresholdM) {
            return SnapCandidateResult(
                lat = proj.lat,
                lng = proj.lng,
                snapped = true,
                snapLabel = state.baseTrail?.name ?: "Sti"
            )
        }
    }

    var bestDist = thresholdM
    var bestResult: SnapCandidateResult? = null

    for (shelter in state.nearbyShelters) {
        val d = haversineM(lat, lng, shelter.lat, shelter.lng)
        if (d <= bestDist) {
            bestDist = d
            bestResult = SnapCandidateResult(
                lat = shelter.lat,
                lng = shelter.lng,
                snapped = true,
                snapLabel = shelter.name
            )
        }
    }

    for (fri in state.nearbyFriTeltning) {
        val d = haversineM(lat, lng, fri.centerLat, fri.centerLng)
        if (d <= bestDist) {
            bestDist = d
            bestResult = SnapCandidateResult(
                lat = fri.centerLat,
                lng = fri.centerLng,
                snapped = true,
                snapLabel = fri.name
            )
        }
    }

    for (poi in state.nearbyPoisForPlan) {
        if (poi.type == "SHELTER" || poi.type == "FRI_TELTNING") {
            val d = haversineM(lat, lng, poi.lat, poi.lng)
            if (d <= bestDist) {
                bestDist = d
                bestResult = SnapCandidateResult(
                    lat = poi.lat,
                    lng = poi.lng,
                    snapped = true,
                    snapLabel = poi.name
                )
            }
        }
    }

    return bestResult ?: SnapCandidateResult(lat = lat, lng = lng, snapped = false)
}

/**
 * UI Round 1 Item 4:
 * While dragging, compute the length change ("+0,8 km") for the two neighbouring legs.
 */
fun RouteBuilderViewModel.computeDragLengthDeltaKm(waypointId: String, candidateLat: Double, candidateLng: Double): Double {
    val state = _uiState.value
    val wps = state.waypoints
    val idx = wps.indexOfFirst { it.id == waypointId }
    if (idx == -1) return 0.0

    val prevWp = if (idx > 0) wps[idx - 1] else null
    val nextWp = if (idx < wps.lastIndex) wps[idx + 1] else null

    var oldAdjacentKm = 0.0
    var newAdjacentKm = 0.0

    if (prevWp != null) {
        val prevKey = "${prevWp.id}_to_${wps[idx].id}"
        oldAdjacentKm += state.legs[prevKey]?.lengthKm
            ?: (haversineM(prevWp.lat, prevWp.lng, wps[idx].lat, wps[idx].lng) / 1000.0)
        newAdjacentKm += haversineM(prevWp.lat, prevWp.lng, candidateLat, candidateLng) / 1000.0
    }
    if (nextWp != null) {
        val nextKey = "${wps[idx].id}_to_${nextWp.id}"
        oldAdjacentKm += state.legs[nextKey]?.lengthKm
            ?: (haversineM(wps[idx].lat, wps[idx].lng, nextWp.lat, nextWp.lng) / 1000.0)
        newAdjacentKm += haversineM(candidateLat, candidateLng, nextWp.lat, nextWp.lng) / 1000.0
    }
    return newAdjacentKm - oldAdjacentKm
}

fun RouteBuilderViewModel.deleteWaypointWithUndoSnackbar(waypointId: String) {
    deleteWaypoint(waypointId, showUndoSnackbar = true)
}

fun RouteBuilderViewModel.clearDeletedPinSnackbar() {
    _uiState.update { it.copy(deletedPinSnackbarMessage = null) }
}

fun RouteBuilderViewModel.undoLastDelete() {
    when (lastDeleteType) {
        RouteBuilderViewModel.LastDeleteType.ROUTE_WAYPOINT -> {
            undo()
            clearDeletedPinSnackbar()
        }
        RouteBuilderViewModel.LastDeleteType.CUSTOM_WAYPOINT -> {
            val deleted = lastDeletedCustomWaypoint
            val photoBytes = lastDeletedWaypointPhotoBytes
            lastDeletedCustomWaypoint = null
            lastDeletedWaypointPhotoBytes = null
            clearDeletedPinSnackbar()
            if (deleted != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val photoPath = deleted.photoPath
                    if (!photoPath.isNullOrBlank() && photoBytes != null) {
                        runCatching {
                            val f = java.io.File(photoPath)
                            f.parentFile?.mkdirs()
                            f.writeBytes(photoBytes)
                        }
                    }
                    repository.saveCustomWaypoint(deleted)
                    val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
                    _uiState.update { it.copy(customUserWaypoints = updated) }
                    _userMessage.emit("Punkt gendannet: ${deleted.name}")
                }
            }
        }
        RouteBuilderViewModel.LastDeleteType.WAYPOINT_PHOTO -> {
            val originalEntity = lastDeletedWaypointPhotoEntity
            val photoBytes = lastDeletedWaypointPhotoBytes
            lastDeletedWaypointPhotoEntity = null
            lastDeletedWaypointPhotoBytes = null
            clearDeletedPinSnackbar()
            if (originalEntity != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    val photoPath = originalEntity.photoPath
                    if (!photoPath.isNullOrBlank() && photoBytes != null) {
                        runCatching {
                            val f = java.io.File(photoPath)
                            f.parentFile?.mkdirs()
                            f.writeBytes(photoBytes)
                        }
                    }
                    repository.saveCustomWaypoint(originalEntity)
                    val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
                    _uiState.update { state ->
                        state.copy(
                            customUserWaypoints = updated,
                            selectedCustomWaypointForDetails = if (state.selectedCustomWaypointForDetails?.id == originalEntity.id) {
                                originalEntity
                            } else {
                                state.selectedCustomWaypointForDetails
                            }
                        )
                    }
                    _userMessage.emit("Foto gendannet")
                }
            }
        }
    }
}

fun RouteBuilderViewModel.getLegMidpointHandles(): List<LegMidpointHandle> {
    val state = _uiState.value
    val wps = state.waypoints
    if (wps.size < 2) return emptyList()
    val handles = mutableListOf<LegMidpointHandle>()
    for (i in 0 until wps.size - 1) {
        val fromWp = wps[i]
        val toWp = wps[i + 1]
        if (fromWp.isHelper || toWp.isHelper) continue
        val legKey = "${fromWp.id}_to_${toWp.id}"
        val leg = state.legs[legKey]
        val pts = leg?.points
        val (midLat, midLng) = if (!pts.isNullOrEmpty()) {
            val midIdx = pts.size / 2
            pts[midIdx].lat to pts[midIdx].lng
        } else {
            ((fromWp.lat + toWp.lat) / 2.0) to ((fromWp.lng + toWp.lng) / 2.0)
        }
        handles.add(
            LegMidpointHandle(
                legKey = legKey,
                legIndex = i,
                fromWaypointId = fromWp.id,
                toWaypointId = toWp.id,
                lat = midLat,
                lng = midLng
            )
        )
    }
    return handles
}

fun RouteBuilderViewModel.startGhostHandleDrag(midLat: Double, midLng: Double): String? {
    val existingIds = _uiState.value.waypoints.map { it.id }.toSet()
    insertViaPointOnLine(midLat, midLng)
    val newWp = _uiState.value.waypoints.firstOrNull { it.id !in existingIds && !it.isHelper }
        ?: _uiState.value.waypoints.minByOrNull { haversineM(midLat, midLng, it.lat, it.lng) }
    return newWp?.id
}

fun RouteBuilderViewModel.cancelGhostHandleDrag(insertedWaypointId: String) {
    if (_uiState.value.waypoints.none { it.id == insertedWaypointId }) return
    deleteWaypoint(insertedWaypointId, showUndoSnackbar = false)
    if (undoStack.size >= 2) {
        undoStack.removeAt(undoStack.lastIndex)
        undoStack.removeAt(undoStack.lastIndex)
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty()) }
    }
}

fun RouteBuilderViewModel.cancelPinDrag(
    waypointId: String,
    originalLat: Double,
    originalLng: Double,
    originalTrailKm: Double? = null
) {
    val state = _uiState.value
    val wp = state.waypoints.find { it.id == waypointId }
    _uiState.update {
        it.copy(
            draggingWaypointId = null,
            draggingPinKmLabel = null,
            movingWaypointId = null
        )
    }
    if (wp != null) {
        if (state.mode == PlannerMode.TRAIL && originalTrailKm != null && wp.trailKm != originalTrailKm) {
            moveWaypointOnTrailToKm(waypointId, originalTrailKm, finishDrag = false)
        } else if (kotlin.math.abs(wp.lat - originalLat) > 1e-7 || kotlin.math.abs(wp.lng - originalLng) > 1e-7) {
            val restoredWps = _uiState.value.waypoints.map {
                if (it.id == waypointId) it.copy(lat = originalLat, lng = originalLng, trailKm = originalTrailKm) else it
            }
            _uiState.update { it.copy(waypoints = restoredWps) }
        }
    }
}

fun RouteBuilderViewModel.openCrosshairMode() {
    _uiState.update {
        it.copy(
            isCrosshairMode = true,
            selectedWaypointId = null,
            selectedLegKey = null
        )
    }
}

fun RouteBuilderViewModel.cancelCrosshairMode() {
    _uiState.update { it.copy(isCrosshairMode = false) }
}

fun RouteBuilderViewModel.confirmCrosshairPlacement(centerLat: Double, centerLng: Double): SnapCandidateResult {
    val snap = snapCoordinateWithin25m(centerLat, centerLng)
    _uiState.update { it.copy(isCrosshairMode = false) }
    addPointAtEndInternal(snap.lat, snap.lng)
    return snap
}

fun RouteBuilderViewModel.snapWaypointToNearestTrail(waypointId: String) {
    val state = _uiState.value
    val wp = state.waypoints.find { it.id == waypointId } ?: return
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, wp.lat, wp.lng, state.isReverse)
        if (proj != null) {
            moveWaypoint(waypointId, proj.lat, proj.lng)
            return
        }
    }
    viewModelScope.launch {
        val offset = LatLng(wp.lat + 0.0004, wp.lng + 0.0004)
        when (val res = routingEngine.routeLeg(LatLng(wp.lat, wp.lng), offset, state.options)) {
            is RouteLegResult.Success -> {
                val snappedPt = res.points.firstOrNull()
                if (snappedPt != null) {
                    moveWaypoint(waypointId, snappedPt.lat, snappedPt.lng)
                }
            }
            else -> {}
        }
    }
}
