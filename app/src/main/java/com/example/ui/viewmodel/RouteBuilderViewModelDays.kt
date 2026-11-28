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

/**
 * "Foreslå overnatninger" runs the stage planner on the current route
 * and inserts suggested OVERNIGHT pins as detours where needed.
 */
fun RouteBuilderViewModel.suggestOvernightStops() {
    val currentState = _uiState.value
    val wps = currentState.waypoints
    if (wps.size < 2) {
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
        viewModelScope.launch { _userMessage.emit(localizedCtx.getString(com.example.R.string.planner_add_two_points_for_overnights)) }
        return
    }

    _uiState.update { it.copy(isFindingOvernight = true) }

    viewModelScope.launch {
        try {
            pushHistory()

            var cleanWps = currentState.waypoints.toList()
            var cleanLegs = currentState.legs.toMap()
            val existingOvernightIds = cleanWps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }.map { it.id }

            if (currentState.mode == PlannerMode.TRAIL && currentState.baseTrailSegments.isNotEmpty()) {
                for (ovId in existingOvernightIds) {
                    val (nextWps, nextLegs) = TrailSlicer.removeDetourAndRestoreTrailLeg(
                        waypoints = cleanWps,
                        legs = cleanLegs,
                        removedWaypointId = ovId,
                        segments = currentState.baseTrailSegments,
                        isReverse = currentState.isReverse
                    )
                    cleanWps = nextWps
                    cleanLegs = nextLegs
                }
            } else {
                cleanWps = cleanWps.filter { it.role != WaypointRole.OVERNIGHT }
            }

            val orderedLegs = mutableListOf<LegGeometryData>()
            for (i in 0 until cleanWps.size - 1) {
                val key = "${cleanWps[i].id}_to_${cleanWps[i + 1].id}"
                val leg = cleanLegs[key]
                if (leg != null) {
                    orderedLegs.add(leg)
                } else if (currentState.mode == PlannerMode.TRAIL && cleanWps[i].trailKm != null && cleanWps[i + 1].trailKm != null) {
                    val sliced = TrailSlicer.buildTrailLegGeometry(
                        fromWaypointId = cleanWps[i].id,
                        toWaypointId = cleanWps[i + 1].id,
                        segments = currentState.baseTrailSegments,
                        fromKm = cleanWps[i].trailKm!!,
                        toKm = cleanWps[i + 1].trailKm!!,
                        isReverse = currentState.isReverse
                    )
                    orderedLegs.add(sliced)
                }
            }

            val basePlannedRoute = PlannedRouteRepository.buildFromComposedLegs(
                planId = 0L,
                trailId = currentState.baseTrailId ?: "route_preview",
                trailName = currentState.routeName.ifBlank { "Tur" },
                isReverse = currentState.isReverse,
                waypoints = cleanWps,
                orderedLegs = orderedLegs,
                stages = emptyList()
            )

            val shelters = currentState.nearbyShelters.ifEmpty { repository.getAllSheltersList() }
            val friTeltning = currentState.nearbyFriTeltning.ifEmpty { repository.getAllFriTeltningList() }
            val pois = currentState.nearbyPois.ifEmpty { repository.getAllPoisList() }

            val targetKm = currentState.dailyTargetKm.coerceAtLeast(5.0)
            val autoNumDays = max(2, kotlin.math.ceil(basePlannedRoute.totalWalkKm / targetKm).toInt())

            val suggestedStages = StagePlanner.planStages(
                plannedRoute = basePlannedRoute,
                routeName = currentState.routeName.ifBlank { "Tur" },
                waypoints = cleanWps,
                overnightWaypoints = emptyList(),
                paceKmh = currentState.paceKmh,
                dailyTargetKm = targetKm,
                numDays = autoNumDays,
                overnightType = currentState.overnightPreference,
                maxDetourKm = currentState.maxDetourKm,
                startTime = currentState.startTimeStr,
                shelters = shelters,
                friTeltnings = friTeltning,
                pois = pois,
                sunsetTime = null,
                startDateEpochMs = currentState.startDateMillis
            )

            _uiState.update {
                it.copy(
                    waypoints = cleanWps,
                    legs = cleanLegs
                )
            }

            val intermediateStages = suggestedStages.dropLast(1)
            for (stage in intermediateStages) {
                if (stage.stopType != "NONE" && stage.stopType.isNotBlank() && stage.spotId != null) {
                    addStopOrDetourSuspend(
                        lat = stage.stopLat,
                        lng = stage.stopLng,
                        role = WaypointRole.OVERNIGHT,
                        name = stage.stopName,
                        linkedPoiId = stage.spotId
                    )
                } else {
                    val ptAtEnd = basePlannedRoute.points.minByOrNull { abs(it.walkKm - stage.endKm) }
                    if (ptAtEnd != null) {
                        if (currentState.mode == PlannerMode.TRAIL && currentState.baseTrailSegments.isNotEmpty()) {
                            val proj = TrailSlicer.projectOntoTrail(
                                currentState.baseTrailSegments,
                                ptAtEnd.lat,
                                ptAtEnd.lng,
                                currentState.isReverse
                            )
                            if (proj != null) {
                                insertWaypointOnTrailAtKm(
                                    trailKm = proj.trailKm,
                                    role = WaypointRole.OVERNIGHT,
                                    name = String.format(Locale.GERMAN, "Etapeslut Dag %d (km %.1f)", stage.dayNumber, proj.trailKm)
                                )
                            }
                        } else {
                            addStopOrDetourSuspend(
                                lat = ptAtEnd.lat,
                                lng = ptAtEnd.lng,
                                role = WaypointRole.OVERNIGHT,
                                name = "Etapeslut Dag ${stage.dayNumber}",
                                linkedPoiId = null
                            )
                        }
                    }
                }
            }

            val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
            _uiState.update { it.copy(isFindingOvernight = false) }
            _userMessage.emit(
                localizedCtx.resources.getQuantityString(
                    com.example.R.plurals.planner_overnights_suggested,
                    intermediateStages.size,
                    intermediateStages.size,
                    intermediateStages.size + 1
                )
            )
            updateRouteStats()
        } catch (e: Exception) {
            Log.e(TAG, "Failed suggesting overnights: ${e.message}", e)
            val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
            _uiState.update { it.copy(isFindingOvernight = false) }
            _userMessage.emit(
                localizedCtx.getString(
                    com.example.R.string.planner_overnights_suggest_failed_fmt,
                    com.example.util.userMessageFor(e)
                )
            )
        }
    }
}

internal suspend fun RouteBuilderViewModel.addStopOrDetourSuspend(
    lat: Double,
    lng: Double,
    role: WaypointRole,
    name: String?,
    linkedPoiId: String?
) {
    val state = _uiState.value
    if (state.mode == PlannerMode.TRAIL && state.baseTrailSegments.isNotEmpty()) {
        val proj = TrailSlicer.projectOntoTrail(state.baseTrailSegments, lat, lng, state.isReverse)
        if (proj != null && proj.distanceMeters <= 50.0 && linkedPoiId == null) {
            insertWaypointOnTrailAtKm(
                trailKm = proj.trailKm,
                role = role,
                name = name
            )
            return
        }

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
            name = name,
            linkedPoiId = linkedPoiId,
            trailKm = null
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

        _uiState.update { current ->
            val mergedFailed = current.failedLegs.toMutableMap().apply {
                putAll(detourPlan.failedLegs)
            }
            current.copy(
                waypoints = wps,
                legs = updatedLegs,
                failedLegs = mergedFailed,
                errorMessage = detourPlan.failedLegs.values.firstOrNull() ?: current.errorMessage
            )
        }
        withContext(Dispatchers.IO) {
            for (leg in detourPlan.legs) {
                RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, leg)
            }
        }
        return
    }

    val wps = _uiState.value.waypoints.toMutableList()
    if (wps.size < 2) return

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

    if (bestIndex == -1) {
        bestIndex = wps.size - 1
    }

    val newWp = RouteWaypoint(
        lat = lat,
        lng = lng,
        role = role,
        legModeToNext = LegMode.SNAP,
        name = name ?: if (role == WaypointRole.OVERNIGHT) "Overnatning" else "Via",
        linkedPoiId = linkedPoiId
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

    computeSingleLegSuspend(prevWp, newWp)
    computeSingleLegSuspend(newWp, nextWp)
}

fun RouteBuilderViewModel.planStages(onNavigateToPlanner: (String) -> Unit) {
    saveAndStartNavigation(onNavigateToPlanner)
}

internal fun RouteBuilderViewModel.updateStagesAndNearbyPois() {
    val state = _uiState.value
    val wps = state.waypoints
    if (wps.size < 2) {
        lastComputedStagesSignature = null
        _uiState.update {
            it.copy(
                plannedStages = emptyList(),
                daySummaries = emptyList(),
                nearbyShelters = emptyList(),
                nearbyFriTeltning = emptyList(),
                nearbyPois = emptyList()
            )
        }
        return
    }

    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        state.legs[key]?.let { orderedLegs.add(it) }
    }
    if (orderedLegs.isEmpty()) return

    stagesAndPoisJob?.cancel()
    stagesAndPoisJob = viewModelScope.launch(Dispatchers.Default) {
        try {
            val shelters = withContext(Dispatchers.IO) { repository.getAllSheltersList() }
            val friTeltning = withContext(Dispatchers.IO) { repository.getAllFriTeltningList() }
            val allPois = withContext(Dispatchers.IO) { repository.getAllPoisList() }

            val allPts = orderedLegs.flatMap { it.points }
            if (allPts.isEmpty()) return@launch

            val minLat = allPts.minOf { it.lat } - 0.025
            val maxLat = allPts.maxOf { it.lat } + 0.025
            val minLng = allPts.minOf { it.lng } - 0.04
            val maxLng = allPts.maxOf { it.lng } + 0.04
            val samplePts = if (allPts.size > 200) {
                val step = max(1, allPts.size / 200)
                allPts.filterIndexed { idx, _ -> idx % step == 0 || idx == allPts.lastIndex }
            } else {
                allPts
            }

            fun isNearPlannedSection(lat: Double, lng: Double, maxMeters: Double = 2500.0): Boolean {
                if (lat !in minLat..maxLat || lng !in minLng..maxLng) return false
                return samplePts.any { pt -> haversineM(lat, lng, pt.lat, pt.lng) <= maxMeters }
            }

            val corridorShelters = shelters.filter { isNearPlannedSection(it.lat, it.lng, 2500.0) }
            val corridorFriTeltning = friTeltning.filter { isNearPlannedSection(it.centerLat, it.centerLng, 2500.0) }
            val corridorPois = allPois.filter { poi ->
                val t = poi.type.lowercase()
                // water_likely (churchyards) is never confirmed water; cafés and highlights feed the day cards (Phase C7)
                ((t.contains("water") && t != "water_likely") || t.contains("toilet") || t.contains("shop") || t.contains("supermarket") || t.contains("grocer") ||
                    t in PlannerDayPlaces.DAY_CARD_EXTRA_TYPES) &&
                    isNearPlannedSection(poi.lat, poi.lng, 2000.0)
            }

            val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
            val numDays = max(1, overnightWps.size + 1)

            val (stages, _) = PlannedRouteRepository.buildStagesAndPlannedRoute(
                planId = 0L,
                trailId = state.baseTrailId ?: "route_preview",
                trailName = state.routeName.ifBlank { "Tur" },
                isReverse = state.isReverse,
                waypoints = wps,
                orderedLegs = orderedLegs,
                paceKmh = state.paceKmh,
                dailyTargetKm = state.dailyTargetKm,
                numDays = numDays,
                overnightType = state.overnightPreference,
                maxDetourKm = state.maxDetourKm,
                startTime = state.startTimeStr,
                shelters = corridorShelters,
                friTeltnings = corridorFriTeltning,
                pois = corridorPois,
                sunsetTime = null,
                startDateEpochMs = state.startDateMillis
            )

            val initialDaySummaries = buildDaySummariesSync(
                state = _uiState.value,
                wps = wps,
                stages = stages,
                corridorShelters = corridorShelters,
                corridorFriTeltning = corridorFriTeltning
            )
            val stageEntities = stages.map { it.toEntity(planId = 0L) }

            var updatedForSig: RouteBuilderState? = null
            _uiState.update {
                val next = it.copy(
                    plannedStages = stageEntities,
                    daySummaries = initialDaySummaries,
                    nearbyShelters = corridorShelters,
                    nearbyFriTeltning = corridorFriTeltning,
                    nearbyPois = corridorPois
                )
                updatedForSig = next
                next
            }
            if (stageEntities.isNotEmpty()) {
                updatedForSig?.let { lastComputedStagesSignature = computeStagesSignature(it) }
            }

            val weatherEnrichedSummaries = initialDaySummaries.mapIndexed { idx, summary ->
                val nightWeather = fetchNightWeatherForDay(
                    dayIndex = idx,
                    startDateMillis = _uiState.value.startDateMillis,
                    lat = summary.campLat,
                    lng = summary.campLng
                )
                withDayTides(withDayWeatherExtras(summary.copy(nightWeatherSummary = nightWeather), idx), idx)
            }
            _uiState.update { it.copy(daySummaries = weatherEnrichedSummaries) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating stages and nearby POIs: ${e.message}")
        }
    }
}

internal fun RouteBuilderViewModel.buildDaySummariesSync(
    state: RouteBuilderUiState,
    wps: List<RouteWaypoint>,
    stages: List<com.example.data.model.TripStage>,
    corridorShelters: List<ShelterEntity>,
    corridorFriTeltning: List<FriTeltningEntity>
): List<PlannerDaySummary> {
    val visibleWps = wps.filter { !it.isHelper }
    if (visibleWps.isEmpty()) return emptyList()
    val overnightWps = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
    val dayBoundaryWps = mutableListOf<RouteWaypoint>()
    dayBoundaryWps.add(visibleWps.first())
    dayBoundaryWps.addAll(overnightWps)
    dayBoundaryWps.add(visibleWps.last())

    val daySummaries = mutableListOf<PlannerDaySummary>()
    // Phase C7: project food places and highlights onto the route once for all days
    val combinedRoutePts = PlannerDayWarnings.combinedRoutePoints(state, wps)
    val projectedFood = PlannerDayPlaces.projectFood(state.nearbyPois, combinedRoutePts)
    val projectedHighlights = PlannerDayPlaces.projectHighlights(state.nearbyPois, combinedRoutePts)
    for ((idx, stage) in stages.withIndex()) {
        val fromWp = dayBoundaryWps.getOrNull(idx)
        val toWp = dayBoundaryWps.getOrNull(idx + 1)
        val isLastDay = idx == stages.lastIndex

        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(getApplication())
        val fromName = fromWp?.name?.ifBlank { null } ?: if (idx == 0) {
            localizedCtx.getString(com.example.R.string.planner_day_start_label)
        } else {
            localizedCtx.getString(com.example.R.string.planner_day_camp_fmt, idx)
        }
        val toName = toWp?.name?.ifBlank { null } ?: stage.stopName.ifBlank {
            if (isLastDay) {
                localizedCtx.getString(com.example.R.string.planner_day_end_label)
            } else {
                localizedCtx.getString(com.example.R.string.planner_day_camp_fmt, stage.dayNumber)
            }
        }

        val detourKm = toWp?.detourCostKm ?: (stage.detourMeters / 1000.0)
        val detourAscent = toWp?.detourAscentM?.roundToInt() ?: 0
        val detourLabel = if (detourKm > 0.05) {
            TrailSlicer.formatDetourCost(toName, detourKm, detourAscent.toDouble())
        } else null

        val matchedShelter = corridorShelters.find { it.id == toWp?.linkedPoiId || it.id == stage.spotId }
        val matchedFt = corridorFriTeltning.find { it.id == toWp?.linkedPoiId || it.id == stage.spotId }

        val overnightTypeLabel = when {
            isLastDay -> localizedCtx.getString(com.example.R.string.day_type_goal)
            matchedShelter != null || stage.stopType == "SHELTER" || toWp?.spotType == "SHELTER" -> localizedCtx.getString(com.example.R.string.poi_type_shelter)
            matchedFt != null || stage.stopType == "FRI_TELTNING" || toWp?.spotType == "FRI_TELTNING" -> localizedCtx.getString(com.example.R.string.poi_type_free_tent)
            toWp?.role == WaypointRole.OVERNIGHT -> localizedCtx.getString(com.example.R.string.poi_type_overnight)
            else -> localizedCtx.getString(com.example.R.string.day_type_open_end)
        }
        val bookingLabel = when {
            isLastDay -> null
            matchedShelter != null -> when {
                !matchedShelter.bookingUrl.isNullOrBlank() || matchedShelter.bookingRequired ->
                    localizedCtx.getString(com.example.R.string.poi_booking_required)
                matchedShelter.fee == false ->
                    localizedCtx.getString(com.example.R.string.poi_fac_free)
                else -> null
            }
            matchedFt != null -> localizedCtx.getString(com.example.R.string.planner_free_123_rule)
            stage.bookingRequired || toWp?.bookingRequired == true -> localizedCtx.getString(com.example.R.string.poi_booking_required)
            else -> null
        }

        val campLat = toWp?.lat ?: stage.stopLat.takeIf { it != 0.0 } ?: visibleWps.last().lat
        val campLng = toWp?.lng ?: stage.stopLng.takeIf { it != 0.0 } ?: visibleWps.last().lng
        val daySunsetTime = StagePlanner.computeSunsetForStageDay(stage.dayNumber, campLat, campLng, state.startDateMillis)

        val dayWarnings = mutableListOf<String>()
        stage.warningMessage?.takeIf { it.isNotBlank() }?.let { dayWarnings.add(it) }
        val arrivalTimeStr = if (stage.estimatedWalkMinutes > 0) {
            val totalMinutes = state.startHour * 60 + stage.estimatedWalkMinutes
            String.format(Locale.GERMAN, "kl. %02d:%02d", (totalMinutes / 60) % 24, totalMinutes % 60)
        } else "kl. --:--"

        if (stage.isAfterSunset) {
            val sunsetMsg = localizedCtx.getString(
                com.example.R.string.planner_arrival_after_sunset_fmt,
                arrivalTimeStr,
                daySunsetTime
            )
            if (dayWarnings.none { it.contains("solnedgang", ignoreCase = true) || it.contains("sunset", ignoreCase = true) || it.contains("sonnenuntergang", ignoreCase = true) }) {
                dayWarnings.add(sunsetMsg)
            }
        }
        state.supplyGapWarnings.forEach { gap ->
            if (gap.endKm > stage.startKm && gap.startKm < stage.endKm && dayWarnings.none { it.contains(gap.displayTitle) }) {
                dayWarnings.add(gap.displayTitle)
            }
        }

        val dayEpochMs = state.startDateMillis + idx.toLong() * 86_400_000L
        PlannerDayWarnings.appendAccessWarnings(state, combinedRoutePts, stage, idx, isLastDay, dayWarnings)
        PlannerDayWarnings.appendAreaCrossings(state, stage, isLastDay, localizedCtx, dayWarnings)
        PlannerDayWarnings.appendPathWarnings(
            state, stage, idx, isLastDay, dayEpochMs,
            com.example.util.AppLocaleHelper.getCurrentLocale(getApplication()), localizedCtx, dayWarnings
        )

        val allWaterPois = state.nearbyPois.filter { it.type.lowercase().let { t -> t.contains("water") && t != "water_likely" } }
        val waterPois = allWaterPois.filter { !com.example.util.SeasonalWaterEvaluator.isWaterPoiPotentiallyClosed(it, dayEpochMs) }
        val closedWaterPoisNearby = allWaterPois.filter {
            com.example.util.SeasonalWaterEvaluator.isWaterPoiPotentiallyClosed(it, dayEpochMs) && haversineM(campLat, campLng, it.lat, it.lng) <= 500.0
        }
        val toiletPois = state.nearbyPois.filter { it.type.lowercase().contains("toilet") }
        val nearestWaterPoiM = waterPois.minOfOrNull { haversineM(campLat, campLng, it.lat, it.lng) }?.roundToInt()
        val hasOpenWaterPoiWithin500m = nearestWaterPoiM != null && nearestWaterPoiM <= 500
        if (!hasOpenWaterPoiWithin500m && closedWaterPoisNearby.isNotEmpty()) {
            dayWarnings.add(localizedCtx.getString(com.example.R.string.day_warning_seasonal_water_closed))
        }
        val waterDistM = nearestWaterPoiM?.takeIf { it <= 1000 }
        val hasWater = hasOpenWaterPoiWithin500m || ((matchedShelter?.hasWaterNearby == true || stage.hasWaterNearby) && closedWaterPoisNearby.isEmpty())
        val hasToilet = matchedShelter?.hasToiletNearby == true || stage.hasToiletNearby || toiletPois.any { haversineM(campLat, campLng, it.lat, it.lng) <= 500.0 }
        val isBookingReq = matchedShelter?.bookingRequired == true || !matchedShelter?.bookingUrl.isNullOrBlank() || stage.bookingRequired || toWp?.bookingRequired == true

        val campExplanation = StagePlanner.buildCampExplanation(
            stageKm = stage.stageKm,
            dailyTargetKm = state.dailyTargetKm,
            detourMeters = (detourKm * 1000.0).coerceAtLeast(stage.detourMeters),
            hasWaterNearby = hasWater,
            waterDistanceMeters = waterDistM,
            hasToiletNearby = hasToilet,
            bookingRequired = isBookingReq,
            isAfterSunset = stage.isAfterSunset,
            stopType = when { isLastDay -> "END"; matchedFt != null -> "FRI_TELTNING"; matchedShelter != null -> "SHELTER"; else -> stage.stopType },
            isLastDay = isLastDay
        )

        daySummaries.add(
            PlannerDaySummary(
                dayNumber = stage.dayNumber, startKm = stage.startKm, endKm = stage.endKm,
                distanceKm = stage.stageKm, ascentM = stage.ascentMeters.toInt(), descentM = stage.descentMeters.toInt(),
                fromName = fromName, toName = toName,
                overnightWaypointId = if (!isLastDay && toWp?.role == WaypointRole.OVERNIGHT) toWp.id else null,
                overnightName = toName, overnightTypeLabel = overnightTypeLabel,
                detourLabel = detourLabel, detourKm = detourKm, detourAscentM = detourAscent,
                bookingLabel = bookingLabel, sunsetTime = daySunsetTime, arrivalTime = arrivalTimeStr,
                isAfterSunset = stage.isAfterSunset, warnings = dayWarnings,
                nightWeatherSummary = localizedCtx.getString(com.example.R.string.day_weather_loading),
                campLat = campLat, campLng = campLng, estimatedHours = stage.estimatedWalkMinutes / 60.0,
                overnightType = when { matchedFt != null -> "FRI_TELTNING"; matchedShelter != null -> "SHELTER"; else -> stage.stopType.ifBlank { "SHELTER" } },
                bookingRequired = isBookingReq, campExplanation = campExplanation,
                foodStops = PlannerDayPlaces.foodStopsForDay(
                    projectedFood, stage, idx, state.startHour, state.startDateMillis, localizedCtx
                ),
                highlights = PlannerDayPlaces.highlightsForDay(
                    projectedHighlights, stage,
                    com.example.util.AppLocaleHelper.getCurrentLocale(getApplication())
                ) { localizedCtx.getString(it) }
            )
        )
    }
    return daySummaries
}

