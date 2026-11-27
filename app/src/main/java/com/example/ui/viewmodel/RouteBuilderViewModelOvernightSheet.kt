package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.ShelterEntity
import com.example.data.repository.PlannedRouteRepository
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Replace / insert overnight stops (moved out of RouteBuilderViewModelDays to keep it under 800 lines)
fun RouteBuilderViewModel.openReplaceOvernightDialog(waypointId: String) = openReplaceOvernightSheet(waypointId)
fun RouteBuilderViewModel.dismissReplaceOvernightDialog() = dismissReplaceOvernightSheet()
fun RouteBuilderViewModel.replaceOvernightWaypoint(waypointId: String, candidate: OvernightCandidate) {
    _uiState.update { it.copy(replaceOvernightWaypointId = waypointId) }
    replaceOvernightWithCandidate(candidate)
}
fun RouteBuilderViewModel.replaceOvernightWaypoint(candidate: OvernightCandidate) = replaceOvernightWithCandidate(candidate)

fun RouteBuilderViewModel.openReplaceOvernightSheet(waypointId: String) {
    val state = _uiState.value
    val wp = state.waypoints.find { it.id == waypointId } ?: return
    viewModelScope.launch {
        val shelters = repository.getAllSheltersList()
        val friTeltning = repository.getAllFriTeltningList()
        val pois = state.nearbyPois.ifEmpty { repository.getAllPoisList() }
        val waterPois = pois.filter {
            it.type == "water" &&
                !com.example.util.SeasonalWaterEvaluator.isWaterPoiPotentiallyClosed(it, state.startDateMillis)
        }
        val toiletPois = pois.filter { it.type == "toilet" }
        val maxDetourM = (state.maxDetourKm * 1000.0).coerceAtLeast(1000.0)
        val candidates = mutableListOf<OvernightCandidate>()

        for (s in shelters) {
            val distM = haversineM(wp.lat, wp.lng, s.lat, s.lng)
            if (distM <= maxDetourM * 1.5) {
                val hasWater = s.hasWaterNearby || waterPois.any { haversineM(s.lat, s.lng, it.lat, it.lng) <= 500.0 }
                val hasToilet = s.hasToiletNearby || toiletPois.any { haversineM(s.lat, s.lng, it.lat, it.lng) <= 500.0 }
                candidates.add(OvernightCandidate(s.id, s.name, "SHELTER", s.lat, s.lng, wp.trailKm ?: 0.0, distM, s.bookingRequired || !s.bookingUrl.isNullOrBlank(), hasWater, hasToilet, s))
            }
        }
        for (ft in friTeltning) {
            val distM = haversineM(wp.lat, wp.lng, ft.centerLat, ft.centerLng)
            if (distM <= maxDetourM * 1.5) {
                val hasWater = waterPois.any { haversineM(ft.centerLat, ft.centerLng, it.lat, it.lng) <= 500.0 }
                val hasToilet = toiletPois.any { haversineM(ft.centerLat, ft.centerLng, it.lat, it.lng) <= 500.0 }
                candidates.add(OvernightCandidate(ft.id, ft.name, "FRI_TELTNING", ft.centerLat, ft.centerLng, wp.trailKm ?: 0.0, distM, false, hasWater, hasToilet, ft))
            }
        }

        _uiState.update {
            it.copy(
                replaceOvernightWaypointId = waypointId,
                replaceOvernightCandidates = candidates
                    .sortedWith(compareBy<OvernightCandidate> { c -> c.detourMeters }.thenByDescending { c -> c.hasWaterNearby }.thenByDescending { c -> c.hasToiletNearby })
                    .take(10)
            )
        }
    }
}

fun RouteBuilderViewModel.dismissReplaceOvernightSheet() {
    _uiState.update { it.copy(replaceOvernightWaypointId = null, replaceOvernightCandidates = emptyList()) }
}

fun RouteBuilderViewModel.replaceOvernightWithCandidate(candidate: OvernightCandidate) {
    val oldId = _uiState.value.replaceOvernightWaypointId ?: return
    dismissReplaceOvernightSheet()
    deleteWaypoint(oldId, showUndoSnackbar = false)
    addStopOrDetour(candidate.lat, candidate.lng, WaypointRole.OVERNIGHT, candidate.name, candidate.id)
}

fun RouteBuilderViewModel.moveOvernightPinByChartWalkKm(waypointId: String, targetWalkKm: Double, finishDrag: Boolean = true) {
    val state = _uiState.value
    val wp = state.waypoints.find { it.id == waypointId } ?: return
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty() && wp.trailKm != null) {
        val startTrailKm = state.waypoints.firstOrNull()?.trailKm ?: 0.0
        val newTrailKm = (startTrailKm + targetWalkKm).coerceIn(0.0, TrailSlicer.totalTrailKm(state.baseTrailSegments))
        moveWaypointOnTrailToKm(waypointId, newTrailKm, finishDrag = finishDrag)
        return
    }

    val wps = state.waypoints
    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until wps.size - 1) {
        state.legs["${wps[i].id}_to_${wps[i + 1].id}"]?.let { orderedLegs.add(it) }
    }
    val combined = RouteGeometryStorageManager.getCombinedPoints(orderedLegs)
    if (combined.size < 2) return
    val totalKm = state.stats.totalKm.coerceAtLeast(0.1)
    val fraction = (targetWalkKm / totalKm).coerceIn(0.02, 0.98)
    val ptIdx = (fraction * (combined.size - 1)).toInt().coerceIn(0, combined.lastIndex)
    val pt = combined[ptIdx]
    if (finishDrag) {
        moveWaypoint(waypointId, pt.lat, pt.lng)
    } else {
        _uiState.update { it.copy(scrubbedPointOnMap = pt) }
    }
}

fun RouteBuilderViewModel.findOvernightCandidates(dailyTargetKm: Double = _uiState.value.dailyTargetKm) {
    suggestOvernightStops()
}

fun RouteBuilderViewModel.dismissOvernightDialog() {
    _uiState.update { it.copy(showOvernightDialog = false) }
}

fun RouteBuilderViewModel.insertOvernightCandidate(cand: com.example.util.OvernightCandidate) {
    dismissOvernightDialog()
    if (_uiState.value.mode == PlannerMode.TRAIL && _uiState.value.baseTrailSegments.isNotEmpty()) {
        addStopOrDetour(
            lat = cand.lat,
            lng = cand.lng,
            role = WaypointRole.OVERNIGHT,
            name = cand.name,
            linkedPoiId = cand.id
        )
        return
    }

    pushHistory()
    val wps = _uiState.value.waypoints.toMutableList()
    if (wps.size < 2) return

    var bestIndex = -1
    var minDistanceM = Double.MAX_VALUE

    for (i in 0 until wps.size - 1) {
        val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
        val leg = _uiState.value.legs[legKey]
        val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

        for (pt in pts) {
            val d = haversineM(cand.lat, cand.lng, pt.lat, pt.lng)
            if (d < minDistanceM) {
                minDistanceM = d
                bestIndex = i + 1
            }
        }
    }

    if (bestIndex == -1) {
        bestIndex = wps.size - 1
    }

    val newWp = RouteWaypoint(
        lat = cand.lat,
        lng = cand.lng,
        role = WaypointRole.OVERNIGHT,
        legModeToNext = LegMode.SNAP,
        name = cand.name,
        linkedPoiId = cand.id
    )

    wps.add(bestIndex, newWp)
    normalizeRoles(wps)
    _uiState.update { it.copy(waypoints = wps) }

    val prevWp = wps[bestIndex - 1]
    val nextWp = wps[bestIndex + 1]

    val oldKey = "${prevWp.id}_to_${nextWp.id}"
    val updatedLegs = _uiState.value.legs.toMutableMap()
    updatedLegs.remove(oldKey)
    _uiState.update { it.copy(legs = updatedLegs) }

    computeSingleLeg(prevWp, newWp)
    computeSingleLeg(newWp, nextWp)

    viewModelScope.launch {
        _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_overnight_inserted_fmt, cand.name))
    }
}
