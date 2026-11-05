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
 * Requirement G: Gem builds the TripPlan, the stages (from OVERNIGHT pins) and the PlannedRoute
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
        viewModelScope.launch { _userMessage.emit("Tilføj mindst to punkter for at gemme ruten") }
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

            val startName = wps.first().name ?: "Start"
            val endName = wps.last().name ?: "Slut"
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
                sourceName = "Ruteplanlægger",
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
                app.database.danskStiDao().clearPlannerDraft()
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
                _userMessage.emit("Turen er gemt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save route/plan: ${e.message}")
            val errMsg = "Fejl ved gemning af tur: ${e.message}"
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

fun RouteBuilderViewModel.scheduleDraftAutosave(debounceMs: Long = 2000L) {
    draftAutosaveJob?.cancel()
    draftAutosaveJob = viewModelScope.launch(Dispatchers.IO) {
        if (debounceMs > 0L) {
            kotlinx.coroutines.delay(debounceMs)
        }
        saveDraftToRoomNow()
    }
}

suspend fun RouteBuilderViewModel.saveDraftToRoomNow() {
    val state = _uiState.value
    if (state.waypoints.isEmpty()) return
    try {
        val optionsJson = JSONObject().apply {
            put("avoidRoads", state.options.avoidRoads)
            put("preferNature", state.options.preferNature)
            put("allowFerries", state.options.allowFerries)
        }.toString()
        val draft = com.example.data.model.PlannerDraftEntity(
            id = "active_planner_draft",
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

internal fun RouteBuilderViewModel.checkForExistingDraft() {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val draft = app.database.danskStiDao().getPlannerDraft()
            if (draft != null && RouteWaypoint.listFromJson(draft.waypointsJson).isNotEmpty()) {
                _uiState.update { it.copy(pendingDraftToRestore = draft) }
            }
        } catch (_: Exception) {}
    }
}

fun RouteBuilderViewModel.continueDraft() {
    val draft = _uiState.value.pendingDraftToRestore ?: return
    val restoredWps = RouteWaypoint.listFromJson(draft.waypointsJson)
    val restoredOptions = parseOptionsJson(draft.optionsJson)
    val restoredMode = try {
        PlannerMode.valueOf(draft.mode)
    } catch (_: Exception) {
        PlannerMode.FREE
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

fun RouteBuilderViewModel.discardDraft() {
    draftAutosaveJob?.cancel()
    _uiState.update {
        it.copy(
            pendingDraftToRestore = null,
            showUnsavedChangesDialog = false,
            hasUnsavedChanges = false
        )
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            app.database.danskStiDao().clearPlannerDraft()
        } catch (_: Exception) {}
    }
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
        name = name.ifBlank { "Mit punkt" },
        note = note,
        photoPath = photoPath
    )
    dismissAddCustomWaypointDialog()
    viewModelScope.launch {
        repository.saveCustomWaypoint(entity)
        val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
        _uiState.update { it.copy(customUserWaypoints = updated) }
        _userMessage.emit("Punkt tilføjet: ${entity.name}")
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
                deletedPinSnackbarMessage = "Punkt slettet · Fortryd"
            )
        }
        _userMessage.emit("Punkt slettet")
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
                deletedPinSnackbarMessage = "Foto slettet · Fortryd"
            )
        }
        _userMessage.emit("Foto slettet")
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
    val name = _uiState.value.routeName.ifBlank { "Min rute" }
    val stats = _uiState.value.stats
    val quality = _uiState.value.routeQuality
    val overnightWaypoints = wps.filter { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
    val overnights = overnightWaypoints.size

    val gpxStr = com.example.util.GpxExporter.generateCustomRouteGpx(
        routeName = name,
        description = "Planlagt rute i Dansk Sti (${String.format(Locale.US, "%.1f", stats.totalKm)} km)",
        routePoints = points,
        overnightWaypoints = overnightWaypoints,
        customWaypoints = _uiState.value.customUserWaypoints
    )

    val summary = buildString {
        append("🥾 Rute: $name\n")
        append("📏 Distance: ${String.format(Locale.US, "%.1f", stats.totalKm)} km\n")
        append("⛰️ Højdemeter: +${stats.ascentM.toInt()} m / -${stats.descentM.toInt()} m\n")
        append("⏱️ Estimeret tid: ${_uiState.value.timeEstimateFormatted}\n")
        if (overnights > 0) {
            append("⛺ Planlagte overnatninger: $overnights\n")
        }
        if (stats.hasWayInfo && stats.pathSharePct != null) {
            append("🌲 Underlag: ${quality.trailPct.toInt()}% sti, ${quality.trackPct.toInt()}% skov-/markvej, ${quality.asphaltPct.toInt()}% asfalt\n")
        } else {
            append("🌲 Underlag: ukendt\n")
        }
        append("Del med Dansk Sti")
    }

    com.example.util.GpxExporter.exportAndShareGpxWithSummary(
        context = context,
        fileName = "${name.replace(" ", "_")}.gpx",
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

