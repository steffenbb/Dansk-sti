package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.data.model.TripStageEntity
import com.example.data.repository.PlannedRouteRepository
import com.example.util.GpxImporter
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteSmartTools
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// GPX import (moved out of RouteBuilderViewModelLegs to keep that file under the 800-line limit)
/**
 * Imports a GPX file. [openStream] is called on the IO thread and the stream is closed here after
 * parsing; the caller must not open (and close) it itself, since parsing happens after this returns.
 */
fun RouteBuilderViewModel.importGpx(openStream: () -> InputStream?) {
    gpxRoutingJob?.cancel()
    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val stream = openStream() ?: throw java.io.FileNotFoundException("GPX")
                stream.use {
                    GpxImporter.parseAndSimplifyGpx(
                        inputStream = it,
                        targetSpacingMeters = 500.0,
                        maxViaPoints = GpxImporter.MAX_VIA_POINTS
                    )
                }
            }

            if (result.waypoints.size < 2) {
                _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_gpx_too_few_points))
                return@launch
            }

            pushHistory()
            val suggestedName = result.name ?: com.example.util.AppLocaleHelper.str(
                getApplication(),
                com.example.R.string.planner_gpx_imported_route_name_fmt,
                "${String.format(Locale.US, "%.1f", result.totalOriginalKm)} km"
            )
            val initialGpxLegs = GpxImporter.buildOriginalGpxLegs(
                waypoints = result.waypoints,
                originalPoints = result.originalPoints,
                waypointSourceIndices = result.waypointSourceIndices
            )
            val totalLegs = (result.waypoints.size - 1).coerceAtLeast(1)

            _uiState.update {
                it.copy(
                    routeName = suggestedName,
                    waypoints = result.waypoints,
                    legs = initialGpxLegs,
                    failedLegs = emptyMap(),
                    loadingLegKeys = emptySet(),
                    gpxOriginalPoints = result.originalPoints,
                    gpxOriginalIndices = result.waypointSourceIndices,
                    isRoutingGpxLegs = true,
                    gpxLegsCompletedCount = 0,
                    gpxLegsTotalCount = totalLegs,
                    isUsingOriginalGpxGeometry = false,
                    hasUnsavedChanges = true
                )
            }
            withContext(Dispatchers.IO) {
                RouteGeometryStorageManager.saveAllLegs(getApplication(), geometryFileName, initialGpxLegs)
            }
            updateRouteStats()
            scheduleDraftAutosave()

            _userMessage.emit(
                com.example.util.AppLocaleHelper.str(
                    getApplication(),
                    com.example.R.string.planner_gpx_loaded_calculating_fmt,
                    result.totalOriginalKm,
                    result.waypoints.size
                )
            )

            gpxRoutingJob = viewModelScope.launch {
                val wps = result.waypoints
                val canRouteOfflineOrOnline = repository.isDeviceOnline() ||
                    brouterStorageManager.areAllDenmarkSegmentsDownloaded()
                if (!canRouteOfflineOrOnline) {
                    _uiState.update {
                        it.copy(
                            isRoutingGpxLegs = false,
                            gpxLegsCompletedCount = totalLegs
                        )
                    }
                    return@launch
                }

                for (i in 0 until wps.size - 1) {
                    if (_uiState.value.isUsingOriginalGpxGeometry) break
                    val from = wps[i]
                    val to = wps[i + 1]
                    val legKey = "${from.id}_to_${to.id}"
                    try {
                        val res = routingEngine.routeLeg(
                            LatLng(from.lat, from.lng),
                            LatLng(to.lat, to.lng),
                            _uiState.value.options,
                            alternative = 0
                        )
                        if (res is RouteLegResult.Success && !_uiState.value.isUsingOriginalGpxGeometry) {
                            val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                                res.points,
                                res.perPointWayInfo
                            )
                            val legData = LegGeometryData(
                                fromWaypointId = from.id,
                                toWaypointId = to.id,
                                legMode = LegMode.SNAP,
                                points = res.points,
                                lengthKm = res.lengthKm,
                                ascentM = res.ascentM,
                                descentM = res.descentM,
                                pathSharePct = pathShare,
                                roadKm = roadKm,
                                hasAccessWarning = false,
                                sourceName = res.sourceName,
                                fetchedAt = res.fetchedAt,
                                wayInfo = res.perPointWayInfo,
                                turnCues = res.turnCues
                            )
                            _uiState.update { current ->
                                val updatedLegs = current.legs.toMutableMap().apply { put(legKey, legData) }
                                current.copy(
                                    legs = updatedLegs,
                                    gpxLegsCompletedCount = i + 1
                                )
                            }
                            withContext(Dispatchers.IO) {
                                RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, legData)
                            }
                        } else {
                            _uiState.update { current ->
                                current.copy(gpxLegsCompletedCount = i + 1)
                            }
                        }
                    } catch (_: Exception) {
                        _uiState.update { current ->
                            current.copy(gpxLegsCompletedCount = i + 1)
                        }
                    }
                }
                if (!_uiState.value.isUsingOriginalGpxGeometry) {
                    _uiState.update {
                        it.copy(
                            isRoutingGpxLegs = false,
                            gpxLegsCompletedCount = totalLegs
                        )
                    }
                    updateRouteStats()
                    scheduleDraftAutosave()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing GPX: ${e.message}")
            _userMessage.emit(
                com.example.util.AppLocaleHelper.str(
                    getApplication(),
                    com.example.R.string.planner_gpx_import_error_fmt,
                    com.example.util.userMessageFor(e)
                )
            )
        }
    }
}

/**
 * "Behold GPX-linjen" — uses the original GPX geometry directly without routing legs.
 */
fun RouteBuilderViewModel.keepOriginalGpxGeometry() {
    gpxRoutingJob?.cancel()
    gpxRoutingJob = null
    val current = _uiState.value
    val wps = current.waypoints
    val origPts = current.gpxOriginalPoints
    if (wps.size < 2 || origPts.size < 2) return

    val gpxLegs = GpxImporter.buildOriginalGpxLegs(
        waypoints = wps,
        originalPoints = origPts,
        waypointSourceIndices = current.gpxOriginalIndices
    )
    _uiState.update {
        it.copy(
            legs = gpxLegs,
            loadingLegKeys = emptySet(),
            failedLegs = emptyMap(),
            isRoutingGpxLegs = false,
            gpxLegsCompletedCount = gpxLegs.size,
            gpxLegsTotalCount = gpxLegs.size,
            isUsingOriginalGpxGeometry = true,
            errorMessage = null,
            hasUnsavedChanges = true
        )
    }
    viewModelScope.launch(Dispatchers.IO) {
        RouteGeometryStorageManager.saveAllLegs(getApplication(), geometryFileName, gpxLegs)
    }
    updateRouteStats()
    scheduleDraftAutosave()
}
