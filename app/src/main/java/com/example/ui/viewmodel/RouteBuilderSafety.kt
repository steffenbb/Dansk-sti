package com.example.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.example.util.RestrictedAreaChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase D5: checks the planned route against military areas and wildlife reserves (cached areas
 * first, then after a 30-day refresh) and rebuilds the day cards when the crossings change.
 */
internal fun RouteBuilderViewModel.refreshRestrictedAreaCrossings() {
    val state = _uiState.value
    val points = PlannerDayWarnings.combinedRoutePoints(state, state.waypoints)
    val signature = points.size * 31 + (points.firstOrNull()?.hashCode() ?: 0) * 17 + (points.lastOrNull()?.hashCode() ?: 0)
    if (points.size < 2) {
        if (state.areaCrossings.isNotEmpty()) _uiState.update { it.copy(areaCrossings = emptyList()) }
        return
    }
    viewModelScope.launch(Dispatchers.Default) {
        val areaRepo = repository.restrictedAreaRepository
        suspend fun apply(areas: List<com.example.data.repository.RestrictedArea>) {
            val now = _uiState.value
            val nowPts = PlannerDayWarnings.combinedRoutePoints(now, now.waypoints)
            val nowSig = nowPts.size * 31 + (nowPts.firstOrNull()?.hashCode() ?: 0) * 17 + (nowPts.lastOrNull()?.hashCode() ?: 0)
            if (nowSig != signature) return // route changed meanwhile; a newer refresh will run
            val crossings = RestrictedAreaChecker.findCrossings(points, areas)
            val missing = !areaRepo.hasCache()
            if (crossings != now.areaCrossings || missing != now.restrictedAreaDataMissing) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(areaCrossings = crossings, restrictedAreaDataMissing = missing) }
                    updateStagesAndNearbyPois()
                }
            }
        }
        apply(withContext(Dispatchers.IO) { areaRepo.cachedAreas() })
        if (repository.isDeviceOnline()) apply(areaRepo.getAreas())
    }
}
