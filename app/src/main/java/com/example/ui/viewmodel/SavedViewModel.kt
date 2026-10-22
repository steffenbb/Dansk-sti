package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.DanskStiApp
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStageEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.repository.DanskStiRepository
import com.example.service.BRouterSegmentDownloadWorker
import com.example.util.GpxExporter
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class DeletedLibraryItem {
    data class Plan(val plan: TripPlanEntity, val stages: List<TripStageEntity>) : DeletedLibraryItem()
    data class CustomRoute(val route: CustomRouteEntity) : DeletedLibraryItem()
    data class SavedTrail(val trailId: String) : DeletedLibraryItem()
    data class Waypoint(val waypoint: UserCustomWaypointEntity) : DeletedLibraryItem()
    data class Recording(val stat: RecordedDayStatEntity) : DeletedLibraryItem()
    data class OfflineTrail(val item: DownloadedTrailEntity) : DeletedLibraryItem()
}

class SavedViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DanskStiApp
    private val repository: DanskStiRepository = app.repository
    private val dao = app.database.dao()
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(application)
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(application) }.getOrNull()

    // 1. Planer
    val savedPlans: StateFlow<List<TripPlanEntity>> = repository.getAllTripPlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Ruter (Saved trails + Custom routes)
    val savedTrails: StateFlow<List<TrailEntity>> = repository.getSavedTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customRoutes: StateFlow<List<CustomRouteEntity>> = repository.getAllCustomRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. Punkter
    val customWaypoints: StateFlow<List<UserCustomWaypointEntity>> = dao.getAllCustomWaypoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 4. Optagelser
    val recordedDays: StateFlow<List<RecordedDayStatEntity>> = dao.getAllRecordedDayStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 5. Offline
    val downloadedTrails: StateFlow<List<DownloadedTrailEntity>> = repository.getAllDownloadedTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _brouterWifiOnly = MutableStateFlow(storageManager.isWifiOnlyDefault())
    private val _brouterDiskStateTrigger = MutableStateFlow(System.currentTimeMillis())

    val brouterState: StateFlow<BRouterDataState> = combine(
        workManager?.getWorkInfosForUniqueWorkFlow(BRouterSegmentDownloadWorker.UNIQUE_WORK_NAME)
            ?: MutableStateFlow(emptyList()),
        _brouterWifiOnly,
        _brouterDiskStateTrigger
    ) { workInfos, wifiOnly, _ ->
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

        BRouterDataState(
            segments = BRouterSegments.DENMARK_SEGMENTS,
            downloadedSegments = storageManager.getDownloadedSegments(),
            isAllDownloaded = storageManager.areAllDenmarkSegmentsDownloaded(),
            isDownloading = isWorkRunning,
            progressPercent = progressPct,
            currentSegmentName = currentSeg,
            totalDownloadedBytes = downloadedBytes,
            totalBytes = BRouterSegments.TOTAL_SIZE_BYTES,
            lastUpdatedDate = storageManager.getFormattedLastUpdatedDate(),
            wifiOnly = wifiOnly
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        BRouterDataState(
            segments = BRouterSegments.DENMARK_SEGMENTS,
            downloadedSegments = storageManager.getDownloadedSegments(),
            isAllDownloaded = storageManager.areAllDenmarkSegmentsDownloaded(),
            totalDownloadedBytes = storageManager.getDownloadedSizeBytes(),
            lastUpdatedDate = storageManager.getFormattedLastUpdatedDate(),
            wifiOnly = storageManager.isWifiOnlyDefault()
        )
    )

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgressText = MutableStateFlow("")
    val downloadProgressText: StateFlow<String> = _downloadProgressText.asStateFlow()

    private val _lastDeletedItem = MutableStateFlow<DeletedLibraryItem?>(null)
    val lastDeletedItem: StateFlow<DeletedLibraryItem?> = _lastDeletedItem.asStateFlow()

    fun refreshBRouterState() {
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun setBRouterWifiOnly(enabled: Boolean) {
        _brouterWifiOnly.value = enabled
        storageManager.setWifiOnlyDefault(enabled)
    }

    fun downloadBRouterData() {
        BRouterSegmentDownloadWorker.enqueue(getApplication(), wifiOnly = _brouterWifiOnly.value, replace = true)
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun updateBRouterData() {
        downloadBRouterData()
    }

    fun cancelBRouterDownload() {
        BRouterSegmentDownloadWorker.cancel(getApplication())
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun deleteCustomRoute(routeId: Long) {
        viewModelScope.launch {
            repository.deleteCustomRoute(routeId)
        }
    }

    fun deleteCustomRouteWithUndo(route: CustomRouteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _lastDeletedItem.value = DeletedLibraryItem.CustomRoute(route)
            repository.deleteCustomRoute(route.id)
        }
    }

    fun deletePlan(planId: Long) {
        viewModelScope.launch {
            repository.deleteTripPlan(planId)
        }
    }

    fun deletePlanWithUndo(plan: TripPlanEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val stages = repository.getStagesForPlan(plan.id).map { it.toEntity(plan.id) }
            _lastDeletedItem.value = DeletedLibraryItem.Plan(plan, stages)
            repository.deleteTripPlan(plan.id)
        }
    }

    fun unsaveTrail(trailId: String) {
        viewModelScope.launch {
            repository.unsaveTrail(trailId)
        }
    }

    fun unsaveTrailWithUndo(trailId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _lastDeletedItem.value = DeletedLibraryItem.SavedTrail(trailId)
            repository.unsaveTrail(trailId)
        }
    }

    fun deleteWaypointWithUndo(waypoint: UserCustomWaypointEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _lastDeletedItem.value = DeletedLibraryItem.Waypoint(waypoint)
            dao.deleteCustomWaypointById(waypoint.id)
        }
    }

    fun deleteRecordingWithUndo(stat: RecordedDayStatEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _lastDeletedItem.value = DeletedLibraryItem.Recording(stat)
            dao.deleteRecordedDayStat(stat.planId, stat.dayNumber)
        }
    }

    fun deleteDownloadedTrail(trailId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedTrail(trailId)
        }
    }

    fun deleteDownloadedTrailWithUndo(item: DownloadedTrailEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _lastDeletedItem.value = DeletedLibraryItem.OfflineTrail(item)
            repository.deleteDownloadedTrail(item.trailId)
        }
    }

    fun undoLastDelete() {
        val item = _lastDeletedItem.value ?: return
        _lastDeletedItem.value = null
        viewModelScope.launch(Dispatchers.IO) {
            when (item) {
                is DeletedLibraryItem.Plan -> {
                    val newId = dao.insertTripPlan(item.plan)
                    if (item.stages.isNotEmpty()) {
                        dao.insertTripStages(item.stages.map { it.copy(planId = newId) })
                    }
                }
                is DeletedLibraryItem.CustomRoute -> {
                    dao.insertCustomRoute(item.route)
                }
                is DeletedLibraryItem.SavedTrail -> {
                    repository.saveTrail(item.trailId)
                }
                is DeletedLibraryItem.Waypoint -> {
                    dao.insertCustomWaypoint(item.waypoint)
                }
                is DeletedLibraryItem.Recording -> {
                    dao.saveRecordedDayStat(item.stat)
                }
                is DeletedLibraryItem.OfflineTrail -> {
                    dao.insertDownloadedTrail(item.item)
                }
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
                shareTitle = "Eksporter turplan (${plan.trailName}) som GPX"
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
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Turplan: ${plan.trailName}")
                type = "text/plain"
            }
            context.startActivity(android.content.Intent.createChooser(sendIntent, "Del turplan"))
        }
    }
}
