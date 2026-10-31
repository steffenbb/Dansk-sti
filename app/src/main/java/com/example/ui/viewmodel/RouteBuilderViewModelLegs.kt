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

/**
 * Computes a single leg between two waypoints.
 */
internal fun RouteBuilderViewModel.computeSingleLeg(from: RouteWaypoint, to: RouteWaypoint) {
    val legKey = "${from.id}_to_${to.id}"
    val state = _uiState.value

    if (from.trailKm != null && to.trailKm != null && state.baseTrailSegments.isNotEmpty()) {
        val trailLeg = TrailSlicer.buildTrailLegGeometry(
            fromWaypointId = from.id,
            toWaypointId = to.id,
            segments = state.baseTrailSegments,
            fromKm = from.trailKm,
            toKm = to.trailKm,
            isReverse = state.isReverse
        )
        _uiState.update { current ->
            val updatedLegs = current.legs.toMutableMap().apply { put(legKey, trailLeg) }
            val updatedFailed = current.failedLegs.toMutableMap().apply { remove(legKey) }
            val updatedLoading = current.loadingLegKeys.toMutableSet().apply { remove(legKey) }
            current.copy(
                legs = updatedLegs,
                failedLegs = updatedFailed,
                loadingLegKeys = updatedLoading
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, trailLeg)
        }
        updateRouteStats()
        return
    }

    val legMode = from.legModeToNext

    if (legMode == LegMode.STRAIGHT) {
        val distKm = haversineM(from.lat, from.lng, to.lat, to.lng) / 1000.0
        val initialStraightLeg = LegGeometryData(
            fromWaypointId = from.id,
            toWaypointId = to.id,
            legMode = LegMode.STRAIGHT,
            points = listOf(RoutePoint3D(from.lat, from.lng, null), RoutePoint3D(to.lat, to.lng, null)),
            lengthKm = distKm,
            ascentM = 0.0,
            descentM = 0.0,
            pathSharePct = null,
            roadKm = 0.0,
            hasAccessWarning = false,
            sourceName = "Straight",
            wayInfo = emptyList()
        )
        _uiState.update { current ->
            val updatedLegs = current.legs.toMutableMap().apply { put(legKey, initialStraightLeg) }
            val updatedFailed = current.failedLegs.toMutableMap().apply { remove(legKey) }
            val doneLoading = current.loadingLegKeys.toMutableSet().apply { remove(legKey) }
            current.copy(
                legs = updatedLegs,
                loadingLegKeys = doneLoading,
                failedLegs = updatedFailed
            )
        }
        updateRouteStats()

        viewModelScope.launch {
            val (fromEle, toEle) = withContext(Dispatchers.IO) {
                try {
                    val resp = weatherService.getElevations(
                        latitudes = "${from.lat},${to.lat}",
                        longitudes = "${from.lng},${to.lng}"
                    )
                    val list = resp.elevation
                    if (list != null && list.size >= 2) {
                        Pair(list[0], list[1])
                    } else {
                        Pair(null, null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Open-Meteo elevation lookup failed for straight leg: ${e.message}")
                    Pair(null, null)
                }
            }

            if (fromEle != null || toEle != null) {
                val pts = listOf(
                    RoutePoint3D(from.lat, from.lng, fromEle),
                    RoutePoint3D(to.lat, to.lng, toEle)
                )
                val ascent = if (fromEle != null && toEle != null && toEle > fromEle) toEle - fromEle else 0.0
                val descent = if (fromEle != null && toEle != null && fromEle > toEle) fromEle - toEle else 0.0

                val straightLeg = initialStraightLeg.copy(
                    points = pts,
                    ascentM = ascent,
                    descentM = descent
                )

                _uiState.update { current ->
                    val updatedLegs = current.legs.toMutableMap().apply { put(legKey, straightLeg) }
                    current.copy(
                        legs = updatedLegs,
                        isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse()
                    )
                }
                withContext(Dispatchers.IO) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, straightLeg)
                }
                updateRouteStats()
            } else {
                withContext(Dispatchers.IO) {
                    RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, initialStraightLeg)
                }
            }
        }
        return
    }

    _uiState.update { current ->
        val currentLoading = current.loadingLegKeys.toMutableSet().apply { add(legKey) }
        current.copy(loadingLegKeys = currentLoading)
    }

    viewModelScope.launch {
        try {
            val fromCoord = LatLng(from.lat, from.lng)
            val toCoord = LatLng(to.lat, to.lng)
            val options = _uiState.value.options

            val result = routingEngine.routeLeg(fromCoord, toCoord, options, alternative = 0)

            val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
            updatedLoading.remove(legKey)

            when (result) {
                is RouteLegResult.Success -> {
                    val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(
                        result.points,
                        result.perPointWayInfo
                    )

                    val legData = LegGeometryData(
                        fromWaypointId = from.id,
                        toWaypointId = to.id,
                        legMode = LegMode.SNAP,
                        points = result.points,
                        lengthKm = result.lengthKm,
                        ascentM = result.ascentM,
                        descentM = result.descentM,
                        pathSharePct = pathShare,
                        roadKm = roadKm,
                        hasAccessWarning = false,
                        sourceName = result.sourceName,
                        fetchedAt = result.fetchedAt,
                        wayInfo = result.perPointWayInfo,
                        turnCues = result.turnCues
                    )

                    _uiState.update { current ->
                        val updatedLegs = current.legs.toMutableMap().apply { put(legKey, legData) }
                        val updatedFailed = current.failedLegs.toMutableMap().apply { remove(legKey) }
                        val doneLoading = current.loadingLegKeys.toMutableSet().apply { remove(legKey) }
                        current.copy(
                            legs = updatedLegs,
                            failedLegs = updatedFailed,
                            loadingLegKeys = doneLoading,
                            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse(),
                            errorMessage = if (updatedFailed.isEmpty()) null else current.errorMessage
                        )
                    }

                    withContext(Dispatchers.IO) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, legData)
                    }

                    updateRouteStats()
                }
                is RouteLegResult.ServerBusy -> {
                    recordFailedLeg(legKey, result.message, updatedLoading)
                }
                is RouteLegResult.Offline -> {
                    recordFailedLeg(legKey, result.message, updatedLoading)
                }
                is RouteLegResult.NoRoute -> {
                    recordFailedLeg(legKey, result.message, updatedLoading)
                }
                is RouteLegResult.Failure -> {
                    recordFailedLeg(legKey, result.message, updatedLoading)
                }
            }
        } catch (e: Exception) {
            val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
            updatedLoading.remove(legKey)
            val daMsg = BRouterOnlineEngine.translateBRouterError(e.message)
            recordFailedLeg(legKey, daMsg, updatedLoading)
        }
    }
}

internal suspend fun RouteBuilderViewModel.recordFailedLeg(
    legKey: String,
    danishMessage: String,
    updatedLoading: Set<String>
) {
    _uiState.update { current ->
        val updatedLegs = current.legs.toMutableMap().apply { remove(legKey) }
        val updatedFailed = current.failedLegs.toMutableMap().apply { put(legKey, danishMessage) }
        val doneLoading = current.loadingLegKeys.toMutableSet().apply { remove(legKey) }
        current.copy(
            legs = updatedLegs,
            failedLegs = updatedFailed,
            loadingLegKeys = doneLoading,
            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse(),
            errorMessage = danishMessage
        )
    }
    _userMessage.emit(danishMessage)
    updateRouteStats()
}

internal fun RouteBuilderViewModel.recomputeAllLegs() {
    val wps = _uiState.value.waypoints
    if (wps.size < 2) return

    for (i in 0 until wps.size - 1) {
        computeSingleLeg(wps[i], wps[i + 1])
    }
}

internal fun RouteBuilderViewModel.updateRouteStats(transform: ((RouteBuilderState) -> RouteBuilderState)? = null) {
    val currentState = transform?.invoke(_uiState.value) ?: _uiState.value
    val wps = currentState.waypoints
    val orderedLegs = mutableListOf<LegGeometryData>()
    val dayBoundaries = mutableListOf<Double>()
    var cumulativeKm = 0.0
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        val leg = currentState.legs[key]
        if (leg != null) {
            orderedLegs.add(leg)
            cumulativeKm += leg.lengthKm
        }
        if (wps[i + 1].role == WaypointRole.OVERNIGHT && !wps[i + 1].isHelper) {
            dayBoundaries.add(cumulativeKm)
        }
    }

    val stats = RouteGeometryStorageManager.calculateStats(orderedLegs)

    val speed = if (pacingSpeedKmh > 0.5) pacingSpeedKmh else 4.5
    val travelHours = (stats.totalKm / speed) + (stats.ascentM / 10.0 / 60.0)
    val totalMinutes = (travelHours * 60).roundToInt()

    val timeStr = if (totalMinutes >= 60) {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        "ca. $h t $m min"
    } else {
        "ca. $totalMinutes min"
    }

    val isOffline = if (currentState.mode == PlannerMode.TRAIL && orderedLegs.all { it.legMode == LegMode.TRAIL }) {
        true
    } else if (orderedLegs.isNotEmpty()) {
        orderedLegs.all { it.sourceName.contains("offline", ignoreCase = true) || it.legMode == LegMode.TRAIL }
    } else if (wps.size >= 2) {
        (0 until wps.size - 1).all { i ->
            brouterStorageManager.areSegmentsAvailableForLeg(
                LatLng(wps[i].lat, wps[i].lng),
                LatLng(wps[i + 1].lat, wps[i + 1].lng)
            )
        }
    } else {
        brouterStorageManager.areAllDenmarkSegmentsDownloaded()
    }

    val engineLabel = if (isOffline) "Offline rute" else "Online rute"

    val refWp = wps.lastOrNull { !it.isHelper } ?: wps.firstOrNull()
    if (refWp != null) {
        sunsetTimeStr = com.example.util.SunCalculator.calculateSunset(
            epochMillis = currentState.startDateMillis,
            lat = refWp.lat,
            lng = refWp.lng
        )
    }

    val quality = RouteSmartTools.analyzeRouteQuality(orderedLegs)
    val (syncStages, syncDaySummaries) = if (wps.size >= 2 && orderedLegs.isNotEmpty()) {
        val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
        val numDays = max(1, overnightWps.size + 1)
        val (builtStages, _) = PlannedRouteRepository.buildStagesAndPlannedRoute(
            planId = 0L,
            trailId = currentState.baseTrailId ?: "route_preview",
            trailName = currentState.routeName.ifBlank { "Tur" },
            isReverse = currentState.isReverse,
            waypoints = wps,
            orderedLegs = orderedLegs,
            paceKmh = currentState.paceKmh,
            dailyTargetKm = currentState.dailyTargetKm,
            numDays = numDays,
            overnightType = currentState.overnightPreference,
            maxDetourKm = currentState.maxDetourKm,
            startTime = currentState.startTimeStr,
            shelters = currentState.nearbyShelters,
            friTeltnings = currentState.nearbyFriTeltning,
            pois = currentState.nearbyPois,
            sunsetTime = sunsetTimeStr
        )
        val summaries = buildDaySummariesSync(
            state = currentState,
            wps = wps,
            stages = builtStages,
            corridorShelters = currentState.nearbyShelters,
            corridorFriTeltning = currentState.nearbyFriTeltning
        )
        builtStages.map { it.toEntity(planId = 0L) } to summaries
    } else {
        emptyList<TripStageEntity>() to emptyList<PlannerDaySummary>()
    }

    var updatedForSig: RouteBuilderState? = null
    _uiState.update { latest ->
        val base = transform?.invoke(latest) ?: latest
        val next = base.copy(
            stats = stats,
            timeEstimateFormatted = timeStr,
            routeQuality = quality,
            dayBoundaryWalkKms = dayBoundaries,
            plannedStages = syncStages,
            daySummaries = syncDaySummaries,
            isOfflineRoute = isOffline,
            routeEngineModeLabel = engineLabel,
            isBuiltinFallbackInUse = BRouterOnlineEngine.isBuiltinFallbackInUse()
        )
        updatedForSig = next
        next
    }
    if (syncStages.isNotEmpty()) {
        updatedForSig?.let { lastComputedStagesSignature = computeStagesSignature(it) }
    } else {
        lastComputedStagesSignature = null
    }
    loadAccessPolygonsForRouteCorridor()
    evaluateAccessRules()
    analyzeSupplyGaps()
    updateStagesAndNearbyPois()
}

internal fun RouteBuilderViewModel.loadAccessPolygonsForRouteCorridor() {
    val wps = _uiState.value.waypoints
    val allPts = mutableListOf<RoutePoint3D>()
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        _uiState.value.legs[key]?.points?.let { allPts.addAll(it) }
    }
    if (allPts.isEmpty()) {
        wps.forEach { allPts.add(RoutePoint3D(it.lat, it.lng)) }
    }
    if (allPts.isEmpty()) return

    val minLat = allPts.minOf { it.lat } - 0.009
    val maxLat = allPts.maxOf { it.lat } + 0.009
    val minLng = allPts.minOf { it.lng } - 0.016
    val maxLng = allPts.maxOf { it.lng } + 0.016

    corridorAccessJob?.cancel()
    corridorAccessJob = viewModelScope.launch {
        try {
            val corridorPolys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
            _uiState.update { current ->
                val merged = (current.accessPolygons + corridorPolys).distinctBy { it.id }
                current.copy(accessPolygons = merged)
            }
            evaluateAccessRules()
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading corridor access polygons: ${e.message}")
        }
    }
}

fun RouteBuilderViewModel.onMapCameraIdle(
    minLat: Double,
    maxLat: Double,
    minLng: Double,
    maxLng: Double,
    zoom: Double,
    isMapLayerAccessEnabled: Boolean = true
) {
    mapCameraAccessJob?.cancel()
    if (!_uiState.value.isAccessLayerVisible || !isMapLayerAccessEnabled || zoom < 11.0) return

    mapCameraAccessJob = viewModelScope.launch {
        delay(600L)
        try {
            val visiblePolys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
            _uiState.update { current ->
                val merged = (current.accessPolygons + visiblePolys).distinctBy { it.id }
                current.copy(accessPolygons = merged)
            }
            evaluateAccessRules()
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading visible map access polygons: ${e.message}")
        }
    }
}

fun RouteBuilderViewModel.fetchAlternatives() {
    val wps = _uiState.value.waypoints
    if (wps.size < 2) return

    _uiState.update { it.copy(isComparingAlternatives = true) }

    viewModelScope.launch {
        val altList = mutableListOf<RouteAlternativeInfo>()
        val fromCoord = LatLng(wps.first().lat, wps.first().lng)
        val toCoord = LatLng(wps.last().lat, wps.last().lng)

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

        _uiState.update {
            it.copy(
                alternatives = altList,
                isComparingAlternatives = false
            )
        }
    }
}

fun RouteBuilderViewModel.selectAlternative(alt: RouteAlternativeInfo) {
    pushHistory()
    val wps = _uiState.value.waypoints
    if (wps.size < 2) return

    val startWp = wps.first()
    val endWp = wps.last()

    val newLeg = LegGeometryData(
        fromWaypointId = startWp.id,
        toWaypointId = endWp.id,
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

    val updatedWps = listOf(startWp, endWp)
    val updatedLegs = mapOf(newLeg.legKey to newLeg)

    _uiState.update {
        it.copy(
            waypoints = updatedWps,
            legs = updatedLegs,
            failedLegs = emptyMap(),
            alternatives = emptyList()
        )
    }

    viewModelScope.launch(Dispatchers.IO) {
        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
    }
    updateRouteStats()
}

fun RouteBuilderViewModel.importGpx(inputStream: InputStream) {
    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                GpxImporter.parseAndSimplifyGpx(inputStream, targetSpacingMeters = 500.0)
            }

            if (result.waypoints.size < 2) {
                _userMessage.emit("Kunne ikke indlæse rute fra GPX: For få punkter")
                return@launch
            }

            pushHistory()
            val suggestedName = result.name ?: "Importeret rute (${String.format(Locale.US, "%.1f", result.totalOriginalKm)} km)"

            _uiState.update {
                it.copy(
                    routeName = suggestedName,
                    waypoints = result.waypoints,
                    legs = emptyMap(),
                    failedLegs = emptyMap(),
                    gpxOriginalPoints = result.originalPoints
                )
            }

            _userMessage.emit("GPX indlæst: ${result.waypoints.size} ruteelementer. Beregner stier...")
            recomputeAllLegs()
        } catch (e: Exception) {
            Log.e(TAG, "Error importing GPX: ${e.message}")
            _userMessage.emit("Fejl ved GPX-import: ${e.message}")
        }
    }
}
