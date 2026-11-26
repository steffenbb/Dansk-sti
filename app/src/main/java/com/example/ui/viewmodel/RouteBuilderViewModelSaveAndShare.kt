package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.data.model.CustomRouteEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.repository.PlannedRouteRepository
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteOptions
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gem builds the TripPlan, the stages (from OVERNIGHT pins) and the PlannedRoute
 * in ONE place (PlannedRouteRepository) from the composed legs.
 */
fun RouteBuilderViewModel.saveRoute(onSuccess: (Long) -> Unit, forceResetProgress: Boolean = false) {
    saveRouteInternal(
        onSuccess = { savedRouteId, _ -> onSuccess(savedRouteId) },
        forceResetProgress = forceResetProgress,
        isStartingNavigation = false
    )
}

fun RouteBuilderViewModel.saveAndStartNavigation(onNavigateToNavigation: (String) -> Unit) {
    saveRouteInternal(
        onSuccess = { _, navId -> onNavigateToNavigation(navId) },
        forceResetProgress = true,
        isStartingNavigation = true
    )
}

internal fun RouteBuilderViewModel.saveRouteInternal(
    onSuccess: (Long, String) -> Unit,
    forceResetProgress: Boolean = false,
    isStartingNavigation: Boolean = false
) {
    val currentState = _uiState.value
    val wps = currentState.waypoints
    if (wps.size < 2) {
        viewModelScope.launch { _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_add_two_points_to_save)) }
        return
    }

    pendingSaveIsStarting = isStartingNavigation
    pendingSaveCallback = { savedId ->
        val navId = _uiState.value.existingPlanId?.toString()
            ?: _uiState.value.baseTrailId
            ?: "route_$savedId"
        onSuccess(savedId, navId)
    }
    _uiState.update {
        it.copy(
            isSaving = !isStartingNavigation,
            isStarting = isStartingNavigation,
            errorMessage = null
        )
    }

    viewModelScope.launch(Dispatchers.Default) {
        try {
            val existingId = currentState.routeId
            if (existingId != null && existingId > 0L && !forceResetProgress) {
                val hasProgress = repository.hasWalkingProgressForCustomRoute(existingId)
                if (hasProgress) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isStarting = false,
                            showResetProgressDialog = true
                        )
                    }
                    return@launch
                }
            }

            val startName = wps.first().name ?: com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_day_start_label)
            val endName = wps.last().name ?: com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_day_end_label)
            val finalName = when {
                currentState.routeName.isNotBlank() -> currentState.routeName
                currentState.baseTrail != null -> currentState.baseTrail.name
                else -> "$startName - $endName"
            }

            val optionsJson = JSONObject().apply {
                put("avoidRoads", currentState.options.avoidRoads)
                put("preferNature", currentState.options.preferNature)
                put("allowFerries", currentState.options.allowFerries)
            }.toString()

            val waypointsJson = RouteWaypoint.listToJson(wps)
            val now = System.currentTimeMillis()

            val entity = CustomRouteEntity(
                id = existingId ?: 0L,
                name = finalName,
                createdAt = now,
                updatedAt = now,
                optionsJson = optionsJson,
                waypointsJson = waypointsJson,
                totalKm = currentState.stats.totalKm,
                ascentM = currentState.stats.ascentM,
                descentM = currentState.stats.descentM,
                pathSharePct = currentState.stats.pathSharePct ?: 0.0,
                roadKm = currentState.stats.roadKm ?: 0.0,
                geometryFile = geometryFileName,
                sourceName = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_default_title),
                fetchedAt = now,
                baseTrailId = currentState.baseTrailId,
                isReverse = currentState.isReverse
            )

            val orderedLegs = mutableListOf<LegGeometryData>()
            for (i in 0 until wps.size - 1) {
                val key = "${wps[i].id}_to_${wps[i + 1].id}"
                currentState.legs[key]?.let { orderedLegs.add(it) }
            }
            withContext(Dispatchers.IO) {
                RouteGeometryStorageManager.saveAllLegs(
                    getApplication(),
                    geometryFileName,
                    orderedLegs.associateBy { it.legKey }
                )
            }

            val savedId = repository.saveCustomRoute(entity)
            val planTrailKey = currentState.baseTrailId ?: "route_$savedId"

            val currentSignature = computeStagesSignature(currentState)
            val canReuseStages = currentState.plannedStages.isNotEmpty() &&
                lastComputedStagesSignature == currentSignature

            val (stages, builtPlannedRoute) = if (canReuseStages) {
                val reusedStages = currentState.plannedStages.map { it.toModel() }
                val route = PlannedRouteRepository.buildFromComposedLegs(
                    planId = 0L,
                    trailId = planTrailKey,
                    trailName = finalName,
                    isReverse = currentState.isReverse,
                    waypoints = wps,
                    orderedLegs = orderedLegs,
                    stages = reusedStages
                )
                reusedStages to route
            } else {
                val numDays = max(1, wps.count { it.role == WaypointRole.OVERNIGHT && !it.isHelper } + 1)
                PlannedRouteRepository.buildStagesAndPlannedRoute(
                    planId = 0L,
                    trailId = planTrailKey,
                    trailName = finalName,
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
                    sunsetTime = null,
                    startDateEpochMs = currentState.startDateMillis
                )
            }

            val startKm = wps.firstOrNull()?.trailKm ?: 0.0
            val endKm = wps.lastOrNull()?.trailKm ?: currentState.stats.totalKm
            val savedPlanId = repository.saveTripPlanForRoute(
                routeId = planTrailKey,
                routeName = finalName,
                totalKm = currentState.stats.totalKm,
                days = stages.size.coerceAtLeast(1),
                overnightType = currentState.overnightPreference,
                stages = stages,
                customRouteId = savedId,
                baseTrailId = currentState.baseTrailId,
                isReverse = currentState.isReverse,
                startKm = startKm,
                endKm = endKm,
                existingPlanId = currentState.existingPlanId
            )
            PlannedRouteRepository(getApplication()).savePlannedRoute(
                builtPlannedRoute.copy(planId = savedPlanId, stages = stages)
            )

            draftAutosaveJob?.cancel()
            try {
                val draftKey = computePlannerDraftKey(currentState.routeId, currentState.baseTrailId)
                app.database.danskStiDao().deletePlannerDraftByKey(draftKey)
            } catch (_: Exception) {}

            val stageEntities = stages.map { it.toEntity(planId = savedPlanId) }
            var updatedStateForSig: RouteBuilderState? = null
            _uiState.update {
                val next = it.copy(
                    isSaving = false,
                    isStarting = false,
                    showResetProgressDialog = false,
                    showUnsavedChangesDialog = false,
                    hasUnsavedChanges = false,
                    pendingDraftToRestore = null,
                    routeId = savedId,
                    existingPlanId = savedPlanId,
                    plannedStages = stageEntities
                )
                updatedStateForSig = next
                next
            }
            updatedStateForSig?.let { lastComputedStagesSignature = computeStagesSignature(it) }

            withContext(Dispatchers.Main) {
                onSuccess(savedId, savedPlanId.toString())
                _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_trip_saved))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save route/plan: ${e.message}")
            val errMsg = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_trip_save_error_fmt, com.example.util.userMessageFor(e))
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isStarting = false,
                    errorMessage = errMsg
                )
            }
            withContext(Dispatchers.Main) {
                _userMessage.emit(errMsg)
            }
        }
    }
}

fun RouteBuilderViewModel.setShowUnsavedChangesDialog(show: Boolean) {
    _uiState.update { it.copy(showUnsavedChangesDialog = show) }
}

fun computePlannerDraftKey(routeId: Long?, baseTrailId: String?): String = when {
    routeId != null && routeId > 0L -> routeId.toString()
    !baseTrailId.isNullOrBlank() -> "trail_$baseTrailId"
    else -> "free_new"
}

fun RouteBuilderViewModel.currentPlannerDraftKey(): String {
    val s = _uiState.value
    return computePlannerDraftKey(s.routeId, s.baseTrailId)
}

fun RouteBuilderViewModel.deleteCurrentDraftFromRoomNow(explicitKey: String? = null) {
    draftAutosaveJob?.cancel()
    val key = explicitKey ?: currentPlannerDraftKey()
    runCatching {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            app.database.danskStiDao().deletePlannerDraftByKey(key)
        }
    }
}

fun RouteBuilderViewModel.scheduleDraftAutosave(debounceMs: Long = 50L) {
    draftAutosaveJob?.cancel()
    val snapshotState = _uiState.value
    if (snapshotState.waypoints.isEmpty()) {
        val key = computePlannerDraftKey(snapshotState.routeId, snapshotState.baseTrailId)
        deleteCurrentDraftFromRoomNow(key)
        return
    }
    draftAutosaveJob = viewModelScope.launch(Dispatchers.IO) {
        if (debounceMs > 0L) {
            kotlinx.coroutines.delay(debounceMs)
        }
        saveDraftToRoomNow()
    }
}

suspend fun RouteBuilderViewModel.saveDraftToRoomNow() {
    val state = _uiState.value
    val key = computePlannerDraftKey(state.routeId, state.baseTrailId)
    if (state.waypoints.isEmpty()) {
        try {
            app.database.danskStiDao().deletePlannerDraftByKey(key)
        } catch (_: Exception) {}
        return
    }
    try {
        val optionsJson = JSONObject().apply {
            put("avoidRoads", state.options.avoidRoads)
            put("preferNature", state.options.preferNature)
            put("allowFerries", state.options.allowFerries)
        }.toString()
        val draft = com.example.data.model.PlannerDraftEntity(
            id = key,
            routeId = state.routeId,
            baseTrailId = state.baseTrailId,
            routeName = state.routeName,
            mode = state.mode.name,
            waypointsJson = RouteWaypoint.listToJson(state.waypoints),
            optionsJson = optionsJson,
            isReverse = state.isReverse,
            updatedAt = System.currentTimeMillis()
        )
        app.database.danskStiDao().upsertPlannerDraft(draft)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to autosave planner draft: ${e.message}")
    }
}

internal fun RouteBuilderViewModel.checkForExistingDraft(
    expectedKey: String = currentPlannerDraftKey()
) {
    if (suppressDraftRestore) {
        _uiState.update { it.copy(pendingDraftToRestore = null) }
        return
    }
    _uiState.update { it.copy(pendingDraftToRestore = null) }
    val matchingDraft = runCatching {
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            app.database.danskStiDao().getPlannerDraftByKey(expectedKey)
        }
    }.getOrNull()
    if (!suppressDraftRestore &&
        matchingDraft != null &&
        matchingDraft.id == expectedKey &&
        RouteWaypoint.listFromJson(matchingDraft.waypointsJson).isNotEmpty()
    ) {
        _uiState.update { it.copy(pendingDraftToRestore = matchingDraft) }
    }
}

fun RouteBuilderViewModel.continueDraft() {
    val draft = _uiState.value.pendingDraftToRestore ?: return
    val restoredWps = RouteWaypoint.listFromJson(draft.waypointsJson)
    val restoredOptions = parseOptionsJson(draft.optionsJson)
    val restoredMode = try {
        PlannerMode.valueOf(draft.mode)
    } catch (_: Exception) {
        if (!draft.baseTrailId.isNullOrBlank()) PlannerMode.TRAIL else PlannerMode.FREE
    }
    val trailId = draft.baseTrailId?.takeIf { it.isNotBlank() }

    if (restoredMode == PlannerMode.TRAIL && trailId != null) {
        trailGeometryDownloadJob?.cancel()
        val existingSegments = _uiState.value.baseTrailSegments.takeIf {
            _uiState.value.baseTrailId == trailId && it.isNotEmpty()
        }
        if (existingSegments != null) {
            applyRestoredTrailDraft(
                draft = draft,
                trailId = trailId,
                trail = _uiState.value.baseTrail,
                segments = existingSegments,
                restoredWps = restoredWps,
                restoredOptions = restoredOptions
            )
            return
        }

        val preseededTrail = com.example.data.local.PreseededData.getInitialTrails().find { it.id == trailId }
        _uiState.update {
            it.copy(
                mode = PlannerMode.TRAIL,
                routeName = draft.routeName.ifBlank { preseededTrail?.name ?: it.routeName },
                baseTrailId = trailId,
                baseTrail = preseededTrail ?: it.baseTrail,
                routeId = draft.routeId,
                waypoints = emptyList(),
                legs = emptyMap(),
                options = restoredOptions,
                isReverse = draft.isReverse,
                pendingDraftToRestore = null,
                isDownloadingTrailGeometry = true,
                trailGeometryDownloadFailed = false,
                waitingForStartPointTap = false,
                errorMessage = null
            )
        }

        trailGeometryDownloadJob = viewModelScope.launch(Dispatchers.Default) {
            val trail = repository.getTrailById(trailId) ?: preseededTrail
            if (trail == null) {
                _uiState.update {
                    it.copy(
                        isDownloadingTrailGeometry = false,
                        trailGeometryDownloadFailed = true,
                        errorMessage = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_route_fetch_failed)
                    )
                }
                return@launch
            }
            val segments = repository.getTrailPoints(trail)
            if (segments.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isDownloadingTrailGeometry = false,
                        trailGeometryDownloadFailed = true,
                        errorMessage = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_route_fetch_failed)
                    )
                }
                return@launch
            }
            applyRestoredTrailDraft(
                draft = draft,
                trailId = trailId,
                trail = trail,
                segments = segments,
                restoredWps = restoredWps,
                restoredOptions = restoredOptions
            )
        }
        return
    }

    _uiState.update {
        it.copy(
            mode = restoredMode,
            routeName = draft.routeName,
            baseTrailId = draft.baseTrailId,
            routeId = draft.routeId,
            waypoints = restoredWps,
            options = restoredOptions,
            isReverse = draft.isReverse,
            pendingDraftToRestore = null,
            hasUnsavedChanges = true,
            waitingForStartPointTap = false,
            errorMessage = null
        )
    }
    if (restoredWps.size >= 2) {
        recomputeAllLegs()
    } else {
        updateRouteStats()
    }
}

internal fun RouteBuilderViewModel.applyRestoredTrailDraft(
    draft: com.example.data.model.PlannerDraftEntity,
    trailId: String,
    trail: com.example.data.model.TrailEntity?,
    segments: List<com.example.data.model.TrailSegmentDto>,
    restoredWps: List<RouteWaypoint>,
    restoredOptions: RouteOptions
) {
    val kmMarkers = com.example.util.routing.TrailSlicer.generateKmMarkers(
        segments = segments,
        stepKm = 5.0,
        isReverse = draft.isReverse
    )
    val resolvedWps = restoredWps.map { wp ->
        if (wp.trailKm != null) {
            wp
        } else {
            val proj = com.example.util.routing.TrailSlicer.projectOntoTrail(
                segments = segments,
                lat = wp.lat,
                lng = wp.lng,
                isReverse = draft.isReverse
            )
            if (proj != null && (wp.legModeToNext == com.example.util.routing.LegMode.TRAIL || proj.distanceMeters <= 500.0)) {
                wp.copy(trailKm = proj.trailKm)
            } else {
                wp
            }
        }
    }.toMutableList()
    normalizeRoles(resolvedWps)

    val initialLegs = mutableMapOf<String, com.example.util.routing.LegGeometryData>()
    var hasNonTrailLeg = false
    for (i in 0 until resolvedWps.size - 1) {
        val from = resolvedWps[i]
        val to = resolvedWps[i + 1]
        if (from.trailKm != null && to.trailKm != null && from.legModeToNext == com.example.util.routing.LegMode.TRAIL) {
            val leg = com.example.util.routing.TrailSlicer.buildTrailLegGeometry(
                fromWaypointId = from.id,
                toWaypointId = to.id,
                segments = segments,
                fromKm = from.trailKm,
                toKm = to.trailKm,
                isReverse = draft.isReverse
            )
            initialLegs[leg.legKey] = leg
        } else {
            hasNonTrailLeg = true
        }
    }

    updateRouteStats { current ->
        current.copy(
            mode = PlannerMode.TRAIL,
            routeName = draft.routeName.ifBlank { trail?.name ?: current.routeName },
            baseTrailId = trailId,
            baseTrail = trail ?: current.baseTrail,
            baseTrailSegments = segments,
            baseTrailKmMarkers = kmMarkers,
            routeId = draft.routeId,
            waypoints = resolvedWps,
            legs = initialLegs,
            failedLegs = emptyMap(),
            options = restoredOptions,
            isReverse = draft.isReverse,
            pendingDraftToRestore = null,
            isDownloadingTrailGeometry = false,
            trailGeometryDownloadFailed = false,
            hasUnsavedChanges = true,
            waitingForStartPointTap = false,
            errorMessage = null
        )
    }

    if (hasNonTrailLeg && resolvedWps.size >= 2) {
        recomputeAllLegs()
    }
}

fun RouteBuilderViewModel.discardDraft() {
    val draftKey = _uiState.value.pendingDraftToRestore?.id ?: currentPlannerDraftKey()
    draftAutosaveJob?.cancel()
    _uiState.update {
        it.copy(
            pendingDraftToRestore = null,
            showUnsavedChangesDialog = false,
            hasUnsavedChanges = false
        )
    }
    deleteCurrentDraftFromRoomNow(draftKey)
}

fun RouteBuilderViewModel.confirmResetProgressAndSave() {
    _uiState.update { it.copy(showResetProgressDialog = false) }
    val callback = pendingSaveCallback ?: {}
    saveRouteInternal(
        onSuccess = { savedId, _ -> callback(savedId) },
        forceResetProgress = true,
        isStartingNavigation = pendingSaveIsStarting
    )
}

fun RouteBuilderViewModel.dismissResetProgressDialog() {
    _uiState.update { it.copy(showResetProgressDialog = false, isSaving = false, isStarting = false) }
}

// --- Phase 12 Feature 6: Mine punkter ---

fun RouteBuilderViewModel.openAddCustomWaypointDialog(lat: Double, lng: Double) {
    _uiState.update {
        it.copy(
            showAddCustomWaypointDialog = true,
            pendingCustomWaypointLocation = LatLng(lat, lng)
        )
    }
}

fun RouteBuilderViewModel.dismissAddCustomWaypointDialog() {
    _uiState.update {
        it.copy(
            showAddCustomWaypointDialog = false,
            pendingCustomWaypointLocation = null
        )
    }
}

fun RouteBuilderViewModel.saveCustomWaypoint(name: String, note: String, photoPath: String?) {
    val loc = _uiState.value.pendingCustomWaypointLocation ?: return
    val entity = UserCustomWaypointEntity(
        routeId = _uiState.value.routeId,
        lat = loc.lat,
        lng = loc.lng,
        name = name.ifBlank { com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.drop_pin_default_saved_name) },
        note = note,
        photoPath = photoPath
    )
    dismissAddCustomWaypointDialog()
    viewModelScope.launch {
        repository.saveCustomWaypoint(entity)
        val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
        _uiState.update { it.copy(customUserWaypoints = updated) }
        _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_point_added_fmt, entity.name))
    }
}

fun RouteBuilderViewModel.deleteCustomWaypoint(id: String) {
    val target = _uiState.value.customUserWaypoints.find { it.id == id }
        ?: _uiState.value.selectedCustomWaypointForDetails?.takeIf { it.id == id }
    lastDeletedCustomWaypoint = target
    lastDeleteType = RouteBuilderViewModel.LastDeleteType.CUSTOM_WAYPOINT
    viewModelScope.launch(Dispatchers.IO) {
        if (lastDeletedCustomWaypoint == null) {
            lastDeletedCustomWaypoint = repository.getAllCustomWaypoints().find { it.id == id }
        }
        val photoPath = lastDeletedCustomWaypoint?.photoPath
        lastDeletedWaypointPhotoBytes = if (!photoPath.isNullOrBlank()) {
            runCatching { java.io.File(photoPath).takeIf { it.exists() }?.readBytes() }.getOrNull()
        } else null
        repository.deleteCustomWaypoint(id)
        val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
        _uiState.update {
            it.copy(
                customUserWaypoints = updated,
                selectedCustomWaypointForDetails = null,
                deletedPinSnackbarMessage = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_point_deleted_undo)
            )
        }
        _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_point_deleted))
    }
}

fun RouteBuilderViewModel.deleteCustomWaypointPhoto(id: String) {
    val target = _uiState.value.customUserWaypoints.find { it.id == id }
        ?: _uiState.value.selectedCustomWaypointForDetails?.takeIf { it.id == id }
    viewModelScope.launch(Dispatchers.IO) {
        val resolved = target ?: repository.getAllCustomWaypoints().find { it.id == id } ?: return@launch
        val photoPath = resolved.photoPath
        if (photoPath.isNullOrBlank()) return@launch
        lastDeletedWaypointPhotoEntity = resolved
        lastDeletedWaypointPhotoBytes = runCatching {
            val f = java.io.File(photoPath)
            val bytes = if (f.exists()) f.readBytes() else null
            if (f.exists()) f.delete()
            bytes
        }.getOrNull()
        lastDeleteType = RouteBuilderViewModel.LastDeleteType.WAYPOINT_PHOTO
        val withoutPhoto = resolved.copy(photoPath = null)
        repository.saveCustomWaypoint(withoutPhoto)
        val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
        _uiState.update { state ->
            state.copy(
                customUserWaypoints = updated,
                selectedCustomWaypointForDetails = if (state.selectedCustomWaypointForDetails?.id == id) {
                    withoutPhoto
                } else {
                    state.selectedCustomWaypointForDetails
                },
                deletedPinSnackbarMessage = com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_photo_deleted_undo)
            )
        }
        _userMessage.emit(com.example.util.AppLocaleHelper.str(getApplication(), com.example.R.string.planner_photo_deleted))
    }
}

fun RouteBuilderViewModel.selectCustomWaypointForDetails(wp: UserCustomWaypointEntity?) {
    _uiState.update { it.copy(selectedCustomWaypointForDetails = wp) }
}

// --- Phase 12 Feature 7: Del rute ---

fun RouteBuilderViewModel.shareRoute(context: Context) {
    val wps = _uiState.value.waypoints
    val orderedLegs = mutableListOf<LegGeometryData>()
    for (i in 0 until wps.size - 1) {
        val key = "${wps[i].id}_to_${wps[i + 1].id}"
        _uiState.value.legs[key]?.let { orderedLegs.add(it) }
    }
    val points = orderedLegs.flatMap { it.points }.map { GeoPointDto(it.lat, it.lng, it.ele) }
    val name = _uiState.value.routeName.ifBlank { com.example.util.AppLocaleHelper.str(context, com.example.R.string.planner_my_route_default) }
    val stats = _uiState.value.stats
    val quality = _uiState.value.routeQuality
    val overnightWaypoints = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
    val overnights = overnightWaypoints.size

    val gpxStr = com.example.util.GpxExporter.generateCustomRouteGpx(
        routeName = name,
        description = com.example.util.AppLocaleHelper.str(
            context,
            com.example.R.string.gpx_planned_route_desc_fmt,
            String.format(Locale.US, "%.1f", stats.totalKm)
        ),
        routePoints = points,
        overnightWaypoints = overnightWaypoints,
        customWaypoints = _uiState.value.customUserWaypoints
    )

    val lblRoute = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_route)
    val lblDistance = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_distance)
    val lblElevation = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_elevation)
    val lblEstTime = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_estimated_time)
    val lblOvernights = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_planned_overnights)
    val lblSurface = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_label_surface)
    val lblTrail = com.example.util.AppLocaleHelper.str(context, com.example.R.string.planner_quality_trail)
    val lblTrack = com.example.util.AppLocaleHelper.str(context, com.example.R.string.planner_quality_track)
    val lblAsphalt = com.example.util.AppLocaleHelper.str(context, com.example.R.string.planner_quality_asphalt)
    val lblUnknown = com.example.util.AppLocaleHelper.str(context, com.example.R.string.poi_sheet_unknown).lowercase()
    val lblFooter = com.example.util.AppLocaleHelper.str(context, com.example.R.string.share_footer_shared_with)

    val summary = buildString {
        append("🥾 $lblRoute: $name\n")
        append("📏 $lblDistance: ${String.format(Locale.US, "%.1f", stats.totalKm)} km\n")
        append("⛰️ $lblElevation: +${stats.ascentM.toInt()} m / -${stats.descentM.toInt()} m\n")
        append("⏱️ $lblEstTime: ${_uiState.value.timeEstimateFormatted}\n")
        if (overnights > 0) {
            append("⛺ $lblOvernights: $overnights\n")
        }
        if (stats.hasWayInfo && stats.pathSharePct != null) {
            append("🌲 $lblSurface: ${quality.trailPct.toInt()}% $lblTrail, ${quality.trackPct.toInt()}% $lblTrack, ${quality.asphaltPct.toInt()}% $lblAsphalt\n")
        } else {
            append("🌲 $lblSurface: $lblUnknown\n")
        }
        append(lblFooter)
    }

    com.example.util.GpxExporter.exportAndShareGpxWithSummary(
        context = context,
        fileName = com.example.util.GpxExporter.buildSafeGpxFileName(name),
        gpxContent = gpxStr,
        shareTitle = context.getString(com.example.R.string.share_route_title, name),
        textSummary = summary
    )
}

internal fun RouteBuilderViewModel.normalizeRoles(wps: MutableList<RouteWaypoint>) {
    if (wps.isEmpty()) return
    if (wps.size == 1) {
        wps[0] = wps[0].copy(role = WaypointRole.START)
        return
    }
    wps[0] = wps[0].copy(role = WaypointRole.START)
    wps[wps.lastIndex] = wps.last().copy(role = WaypointRole.END)
    for (i in 1 until wps.size - 1) {
        if (wps[i].role == WaypointRole.START || wps[i].role == WaypointRole.END) {
            wps[i] = wps[i].copy(role = WaypointRole.VIA)
        }
    }
}

internal fun RouteBuilderViewModel.parseOptionsJson(json: String): RouteOptions {
    if (json.isBlank()) return RouteOptions()
    return try {
        val obj = JSONObject(json)
        RouteOptions(
            avoidRoads = obj.optDouble("avoidRoads", 0.5),
            preferNature = obj.optBoolean("preferNature", true),
            allowFerries = obj.optBoolean("allowFerries", false)
        )
    } catch (_: Exception) {
        RouteOptions()
    }
}

internal fun RouteBuilderViewModel.haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}



