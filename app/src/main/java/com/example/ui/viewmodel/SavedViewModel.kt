package com.example.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.DanskStiApp
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.PlannedRoute
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStageEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.PlannedRouteRepository
import com.example.service.BRouterSegmentDownloadWorker
import com.example.util.GpxExporter
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.LegGeometryData
import com.example.util.routing.RouteGeometryStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class DeletedLibraryItem {
    data class Plan(
        val plan: TripPlanEntity,
        val stages: List<TripStageEntity>,
        val plannedRoute: PlannedRoute? = null,
        val progress: TripProgressEntity? = null,
        val trackPoints: List<RecordedTrackPointEntity> = emptyList(),
        val dayStats: List<RecordedDayStatEntity> = emptyList()
    ) : DeletedLibraryItem()

    data class CustomRoute(
        val route: CustomRouteEntity,
        val legs: Map<String, LegGeometryData> = emptyMap(),
        val deletedPlans: List<Plan> = emptyList()
    ) : DeletedLibraryItem()

    data class SavedTrail(val trailId: String) : DeletedLibraryItem()
    data class Waypoint(val waypoint: UserCustomWaypointEntity, val photoBytes: ByteArray? = null) : DeletedLibraryItem()
    data class WaypointPhoto(val waypoint: UserCustomWaypointEntity, val photoBytes: ByteArray? = null) : DeletedLibraryItem()
    data class Recording(val stat: RecordedDayStatEntity) : DeletedLibraryItem()
    data class OfflineTrail(val item: DownloadedTrailEntity) : DeletedLibraryItem()
}

class SavedViewModel(application: Application) : AndroidViewModel(application) {

    private val app: DanskStiApp
        get() = try { DanskStiApp.instance } catch (_: Exception) { getApplication() as DanskStiApp }
    private val repository: DanskStiRepository
        get() = app.repository
    private val dao
        get() = app.database.dao()
    private val storageManager: BRouterStorageManager
        get() = BRouterStorageManager.getInstance(app)
    private val workManager: WorkManager?
        get() = runCatching { WorkManager.getInstance(app) }.getOrNull()

    // 1. Planer
    val savedPlans: StateFlow<List<TripPlanEntity>> = repository.getAllTripPlans()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completedPlanIds: StateFlow<Set<Long>> = dao.getCompletedTripProgressFlow()
        .map { list -> list.filter { it.isCompleted }.map { it.planId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // 2. Ruter (Saved trails + Custom routes)
    val savedTrails: StateFlow<List<TrailEntity>> = repository.getSavedTrails()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val customRoutes: StateFlow<List<CustomRouteEntity>> = repository.getAllCustomRoutes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 3. Punkter
    val customWaypoints: StateFlow<List<UserCustomWaypointEntity>> = dao.getAllCustomWaypoints()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 4. Optagelser
    val recordedDays: StateFlow<List<RecordedDayStatEntity>> = dao.getAllRecordedDayStats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 5. Offline
    val downloadedTrails: StateFlow<List<DownloadedTrailEntity>> = repository.getAllDownloadedTrails()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _brouterWifiOnly = MutableStateFlow(storageManager.isWifiOnlyDefault())
    private val _brouterDiskStateTrigger = MutableStateFlow(System.currentTimeMillis())

    private fun buildCurrentBRouterState(
        workInfos: List<WorkInfo> = emptyList(),
        wifiOnly: Boolean = _brouterWifiOnly.value
    ): BRouterDataState {
        val latestWork = workInfos.firstOrNull()
        val isWorkRunning = latestWork != null && latestWork.state == WorkInfo.State.RUNNING

        val progressPct = if (isWorkRunning) {
            latestWork.progress.getInt(BRouterSegmentDownloadWorker.KEY_PROGRESS_PERCENT, 0)
        } else 0

        val currentSeg = if (isWorkRunning) {
            latestWork.progress.getString(BRouterSegmentDownloadWorker.KEY_CURRENT_SEGMENT).orEmpty()
        } else ""

        val downloadedBytes = if (isWorkRunning) {
            latestWork.progress.getLong(BRouterSegmentDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
        } else {
            storageManager.getDownloadedSizeBytes()
        }

        val realSegments = storageManager.getSegmentsWithRealSizes()
        val totalBytes = storageManager.getTotalExpectedSizeBytes()
        val totalSizeMb = storageManager.getTotalExpectedSizeMb()
        val isUpdateAvailable = storageManager.isUpdateAvailable()
        val hasVersionMismatch = storageManager.hasVersionMismatch()
        val notice = when {
            hasVersionMismatch -> BRouterStorageManager.VERSION_MISMATCH_LABEL
            isUpdateAvailable -> BRouterStorageManager.UPDATE_AVAILABLE_LABEL
            else -> null
        }

        return BRouterDataState(
            segments = realSegments,
            downloadedSegments = storageManager.getDownloadedSegments(),
            isAllDownloaded = storageManager.areAllDenmarkSegmentsDownloaded(),
            isDownloading = isWorkRunning,
            progressPercent = progressPct,
            currentSegmentName = currentSeg,
            totalDownloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            totalSizeMb = totalSizeMb,
            lastUpdatedDate = storageManager.getFormattedLastUpdatedDate(),
            wifiOnly = wifiOnly,
            isUpdateAvailable = isUpdateAvailable,
            hasVersionMismatch = hasVersionMismatch,
            statusNotice = notice
        )
    }

    val brouterState: StateFlow<BRouterDataState> = combine(
        (workManager?.getWorkInfosForUniqueWorkFlow(BRouterSegmentDownloadWorker.UNIQUE_WORK_NAME)
            ?: MutableStateFlow(emptyList())).let { flow ->
            kotlinx.coroutines.flow.flow {
                emit(emptyList())
                flow.collect { emit(it) }
            }
        },
        _brouterWifiOnly,
        _brouterDiskStateTrigger,
        storageManager.remoteMetadataUpdatedTrigger,
        storageManager.versionMismatchFlow
    ) { workInfos, wifiOnly, _, _, _ ->
        buildCurrentBRouterState(workInfos, wifiOnly)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        buildCurrentBRouterState()
    )

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgressText = MutableStateFlow("")
    val downloadProgressText: StateFlow<String> = _downloadProgressText.asStateFlow()

    private val _lastDeletedItem = MutableStateFlow<DeletedLibraryItem?>(null)
    val lastDeletedItem: StateFlow<DeletedLibraryItem?> = _lastDeletedItem.asStateFlow()

    fun refreshBRouterState() {
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.fetchRemoteSegmentsMetadata()
            _brouterDiskStateTrigger.value = System.currentTimeMillis()
        }
    }

    fun setBRouterWifiOnly(enabled: Boolean) {
        _brouterWifiOnly.value = enabled
        storageManager.setWifiOnlyDefault(enabled)
    }

    fun downloadBRouterData() {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.fetchRemoteSegmentsMetadata()
            BRouterSegmentDownloadWorker.enqueue(getApplication(), wifiOnly = _brouterWifiOnly.value, replace = true)
            _brouterDiskStateTrigger.value = System.currentTimeMillis()
        }
    }

    fun updateBRouterData() {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.fetchRemoteSegmentsMetadata()
            BRouterSegmentDownloadWorker.enqueue(
                getApplication(),
                wifiOnly = _brouterWifiOnly.value,
                replace = true,
                forceUpdate = true
            )
            _brouterDiskStateTrigger.value = System.currentTimeMillis()
        }
    }

    fun cancelBRouterDownload() {
        BRouterSegmentDownloadWorker.cancel(getApplication())
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    private val undoMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun commitDeletedItemInternal(item: DeletedLibraryItem) {
        when (item) {
            is DeletedLibraryItem.Plan -> {
                repository.commitTripPlanPermanentDelete(item.plan.id)
            }
            is DeletedLibraryItem.CustomRoute -> {
                repository.commitCustomRoutePermanentDelete(
                    item.route,
                    item.deletedPlans.map { it.plan.id }
                )
            }
            is DeletedLibraryItem.SavedTrail -> {
                repository.unsaveTrail(item.trailId)
            }
            is DeletedLibraryItem.Waypoint -> {
                repository.commitCustomWaypointPermanentDelete(item.waypoint.id, item.waypoint.photoPath)
            }
            is DeletedLibraryItem.WaypointPhoto -> {
                val photoPath = item.waypoint.photoPath
                if (!photoPath.isNullOrBlank()) {
                    runCatching {
                        val f = File(photoPath)
                        if (f.exists()) f.delete()
                    }
                }
            }
            is DeletedLibraryItem.Recording -> {
                dao.deleteRecordedTrackPointsForDay(item.stat.planId, item.stat.dayNumber)
                dao.deleteRecordedDayStat(item.stat.planId, item.stat.dayNumber)
            }
            is DeletedLibraryItem.OfflineTrail -> {
                repository.commitDownloadedTrailPermanentDelete(item.item.trailId)
            }
        }
    }

    private suspend fun replacePendingDeletedItem(newItem: DeletedLibraryItem) {
        val previous = _lastDeletedItem.value
        if (previous != null) {
            _lastDeletedItem.value = null
            commitDeletedItemInternal(previous)
        }
        _lastDeletedItem.value = newItem
    }

    private val commitScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /**
     * Round 10C Item 1 & Round 11 Item 3:
     * Called when the undo snackbar closes without "Fortryd", or when Mine ture is left
     * or the app goes to the background with an undo pending.
     * Permanently deletes files, POIs, progress, stages, track points, and offline archives.
     */
    fun commitPendingDelete() {
        commitScope.launch {
            undoMutex.lock()
            try {
                val item = _lastDeletedItem.value ?: return@launch
                _lastDeletedItem.value = null
                commitDeletedItemInternal(item)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    override fun onCleared() {
        commitPendingDelete()
        super.onCleared()
    }

    private suspend fun snapshotPlanForUndo(plan: TripPlanEntity): DeletedLibraryItem.Plan {
        val stages = repository.getStagesForPlan(plan.id).map { it.toEntity(plan.id) }
        val plannedRoute = runCatching { PlannedRouteRepository(app).loadPlannedRoute(plan.id) }.getOrNull()
        val progress = runCatching { dao.getTripProgressSync(plan.id) }.getOrNull()
        val trackPoints = runCatching { dao.getAllRecordedTrackPointsForPlanSync(plan.id) }.getOrDefault(emptyList())
        val dayStats = runCatching { dao.getAllRecordedDayStatsSync(plan.id) }.getOrDefault(emptyList())
        return DeletedLibraryItem.Plan(
            plan = plan,
            stages = stages,
            plannedRoute = plannedRoute,
            progress = progress,
            trackPoints = trackPoints,
            dayStats = dayStats
        )
    }

    private suspend fun restorePlanSnapshot(item: DeletedLibraryItem.Plan) {
        val newId = dao.insertTripPlan(item.plan)
        if (dao.getTripStagesForPlanSync(newId).isEmpty() && item.stages.isNotEmpty()) {
            dao.insertTripStages(item.stages.map { it.copy(planId = newId) })
        }
        val planFile = PlannedRouteRepository(app).getPlanFile(newId)
        if (!planFile.exists() && item.plannedRoute != null) {
            runCatching {
                PlannedRouteRepository(app).savePlannedRoute(item.plannedRoute.copy(planId = newId))
            }
        }
        if (dao.getTripProgressSync(newId) == null && item.progress != null) {
            runCatching {
                dao.saveTripProgress(item.progress.copy(planId = newId))
            }
        }
        if (dao.getAllRecordedTrackPointsForPlanSync(newId).isEmpty() && item.trackPoints.isNotEmpty()) {
            for (tp in item.trackPoints) {
                runCatching {
                    dao.insertRecordedTrackPoint(tp.copy(id = 0L, planId = newId))
                }
            }
        }
        if (dao.getAllRecordedDayStatsSync(newId).isEmpty() && item.dayStats.isNotEmpty()) {
            for (ds in item.dayStats) {
                runCatching {
                    dao.saveRecordedDayStat(ds.copy(planId = newId))
                }
            }
        }
    }

    fun deleteCustomRoute(routeId: Long) {
        viewModelScope.launch {
            repository.deleteCustomRoute(routeId)
        }
    }

    fun deleteCustomRouteWithUndo(route: CustomRouteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                val legs = runCatching {
                    if (route.geometryFile.isNotBlank()) {
                        RouteGeometryStorageManager.loadLegs(app, route.geometryFile)
                    } else emptyMap()
                }.getOrDefault(emptyMap())
                val associatedPlans = dao.getAllTripPlansList().filter {
                    it.customRouteId == route.id || it.trailId == "route_${route.id}" || it.trailId == route.id.toString()
                }
                val planSnapshots = associatedPlans.map { snapshotPlanForUndo(it) }
                replacePendingDeletedItem(
                    DeletedLibraryItem.CustomRoute(
                        route = route,
                        legs = legs,
                        deletedPlans = planSnapshots
                    )
                )
                repository.hideCustomRouteInDb(route.id, associatedPlans.map { it.id })
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            repository.deleteTripPlan(planId)
        }
    }

    fun deletePlanWithUndo(plan: TripPlanEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                replacePendingDeletedItem(snapshotPlanForUndo(plan))
                repository.hideTripPlanInDb(plan.id)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun unsaveTrail(trailId: String) {
        viewModelScope.launch {
            repository.unsaveTrail(trailId)
        }
    }

    fun unsaveTrailWithUndo(trailId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                replacePendingDeletedItem(DeletedLibraryItem.SavedTrail(trailId))
                repository.unsaveTrail(trailId)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun deleteWaypoint(waypoint: UserCustomWaypointEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCustomWaypoint(waypoint.id)
        }
    }

    fun deleteWaypointWithUndo(waypoint: UserCustomWaypointEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                val photoBytes = waypoint.photoPath?.takeIf { it.isNotBlank() }?.let { path ->
                    runCatching {
                        val f = File(path)
                        if (f.exists()) f.readBytes() else null
                    }.getOrNull()
                }
                replacePendingDeletedItem(DeletedLibraryItem.Waypoint(waypoint, photoBytes))
                repository.hideCustomWaypointInDb(waypoint.id)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun deleteWaypointPhotoWithUndo(waypoint: UserCustomWaypointEntity) {
        if (waypoint.photoPath.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                val photoBytes = runCatching {
                    val f = File(waypoint.photoPath)
                    if (f.exists()) f.readBytes() else null
                }.getOrNull()
                replacePendingDeletedItem(DeletedLibraryItem.WaypointPhoto(waypoint, photoBytes))
                dao.insertCustomWaypoint(waypoint.copy(photoPath = null))
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun deleteRecordingWithUndo(stat: RecordedDayStatEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                replacePendingDeletedItem(DeletedLibraryItem.Recording(stat))
                dao.deleteRecordedDayStat(stat.planId, stat.dayNumber)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun deleteDownloadedTrail(trailId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedTrail(trailId)
        }
    }

    fun deleteDownloadedTrailWithUndo(item: DownloadedTrailEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                replacePendingDeletedItem(DeletedLibraryItem.OfflineTrail(item))
                repository.hideDownloadedTrailInDb(item.trailId)
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun undoLastDelete() {
        viewModelScope.launch(Dispatchers.IO) {
            undoMutex.lock()
            try {
                val item = _lastDeletedItem.value ?: return@launch
                _lastDeletedItem.value = null
                when (item) {
                    is DeletedLibraryItem.Plan -> {
                        restorePlanSnapshot(item)
                    }
                    is DeletedLibraryItem.CustomRoute -> {
                        val restoredRouteId = dao.insertCustomRoute(item.route)
                        if (item.route.geometryFile.isNotBlank() && item.legs.isNotEmpty()) {
                            val existingLegs = RouteGeometryStorageManager.loadLegs(app, item.route.geometryFile)
                            if (existingLegs.isEmpty()) {
                                RouteGeometryStorageManager.saveAllLegs(app, item.route.geometryFile, item.legs)
                            }
                        }
                        for (planSnap in item.deletedPlans) {
                            val updatedPlan = planSnap.plan.copy(customRouteId = restoredRouteId)
                            restorePlanSnapshot(planSnap.copy(plan = updatedPlan))
                        }
                    }
                    is DeletedLibraryItem.SavedTrail -> {
                        repository.saveTrail(item.trailId)
                    }
                    is DeletedLibraryItem.Waypoint -> {
                        val photoPath = item.waypoint.photoPath
                        if (!photoPath.isNullOrBlank() && item.photoBytes != null) {
                            runCatching {
                                val f = File(photoPath)
                                if (!f.exists()) {
                                    f.parentFile?.mkdirs()
                                    f.writeBytes(item.photoBytes)
                                }
                            }
                        }
                        dao.insertCustomWaypoint(item.waypoint)
                    }
                    is DeletedLibraryItem.WaypointPhoto -> {
                        val photoPath = item.waypoint.photoPath
                        if (!photoPath.isNullOrBlank() && item.photoBytes != null) {
                            runCatching {
                                val f = File(photoPath)
                                if (!f.exists()) {
                                    f.parentFile?.mkdirs()
                                    f.writeBytes(item.photoBytes)
                                }
                            }
                        }
                        dao.insertCustomWaypoint(item.waypoint)
                    }
                    is DeletedLibraryItem.Recording -> {
                        dao.saveRecordedDayStat(item.stat)
                    }
                    is DeletedLibraryItem.OfflineTrail -> {
                        dao.insertDownloadedTrail(item.item)
                    }
                }
            } finally {
                undoMutex.unlock()
            }
        }
    }

    fun downloadTrailOffline(trailId: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgressText.value = "Forbereder offline pakke..."
            withContext(Dispatchers.IO) {
                repository.downloadTrailOffline(trailId) { progressText ->
                    _downloadProgressText.value = progressText
                }
            }
            _isDownloading.value = false
            _downloadProgressText.value = ""
        }
    }

    fun clearMapAndRouteCache(onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val expiredFuture = System.currentTimeMillis() + 1000L
                dao.deleteExpiredMarkedRoutes(expiredFuture)
                dao.deleteExpiredMarkedRouteCacheBoxes(expiredFuture)
                dao.deleteExpiredWaterFeatures(expiredFuture)
                dao.deleteExpiredWaterTileCache(expiredFuture)
                val osmdroidCache = File(app.cacheDir, "osmdroid")
                if (osmdroidCache.exists()) {
                    osmdroidCache.deleteRecursively()
                }
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun exportTripPlanGpx(context: android.content.Context, plan: TripPlanEntity) {
        viewModelScope.launch {
            val trail = repository.getTrailById(plan.trailId) ?: return@launch
            val stages = repository.getStagesForPlan(plan.id)
            val points = repository.getTrailPoints(trail).flatMap { it.points }
            val shelters = repository.getSheltersForTrail(trail.id)

            val gpxContent = GpxExporter.generateMultiSegmentGpx(
                trail = trail,
                plan = plan,
                stages = stages,
                trailPoints = points,
                shelters = shelters
            )
            val safeName = plan.trailName.replace(" ", "_")
            GpxExporter.exportAndShareGpxFile(
                context = context,
                fileName = "${safeName}_${plan.targetDays}dage.gpx",
                gpxContent = gpxContent,
                shareTitle = context.getString(com.example.R.string.share_trip_plan_gpx_title)
            )
        }
    }

    fun shareTripPlan(context: android.content.Context, plan: TripPlanEntity) {
        viewModelScope.launch {
            val stages = repository.getStagesForPlan(plan.id)
            val totalKm = plan.totalDistanceKm
            val days = plan.targetDays
            val sb = StringBuilder()
            sb.append("Min vandretur: ${plan.trailName}\n")
            sb.append("Samlet distance: ${String.format(java.util.Locale.US, "%.1f", totalKm)} km over $days ${if (days == 1) "dag" else "dage"}\n")
            sb.append("Planlagt tempo: ${String.format(java.util.Locale.US, "%.1f", plan.paceKmh)} km/t\n\n")
            sb.append("Dags-etaper & overnatning:\n")
            if (stages.isNotEmpty()) {
                stages.forEach { stage ->
                    val bookStr = if (stage.bookingRequired) " (Kræver booking)" else ""
                    sb.append("• Dag ${stage.dayNumber}: ${String.format(java.util.Locale.US, "%.1f", stage.stageKm)} km\n")
                    sb.append("   Overnatning: ${stage.stopName} [${stage.stopType}]$bookStr\n")
                }
            } else {
                sb.append("• Overnatningstype: ${plan.overnightType}\n")
                sb.append("• Start: km ${String.format(java.util.Locale.US, "%.1f", plan.startKm)} -> Slut: km ${String.format(java.util.Locale.US, "%.1f", plan.endKm)}\n")
            }
            sb.append("\nPlanlagt med Dansk Sti")

            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
                putExtra(
                    android.content.Intent.EXTRA_SUBJECT,
                    context.getString(com.example.R.string.share_trip_plan_subject, plan.trailName)
                )
                type = "text/plain"
            }
            try {
                context.startActivity(
                    android.content.Intent.createChooser(
                        sendIntent,
                        context.getString(com.example.R.string.share_trip_plan_chooser_title)
                    )
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    context.getString(com.example.R.string.toast_no_app_for_link),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
