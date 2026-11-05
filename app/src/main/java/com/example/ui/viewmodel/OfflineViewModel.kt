package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.DownloadedTrailEntity
import com.example.data.repository.DanskStiRepository
import com.example.service.BRouterSegmentDownloadWorker
import com.example.util.OfflineDownloadManager
import com.example.util.OfflineEstimateResult
import com.example.util.routing.BRouterSegmentInfo
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

/**
 * UI state for BRouter offline routing data covering Denmark.
 */
data class BRouterDataState(
    val segments: List<BRouterSegmentInfo> = BRouterSegments.DENMARK_SEGMENTS,
    val downloadedSegments: Set<String> = emptySet(),
    val isAllDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val currentSegmentName: String = "",
    val totalDownloadedBytes: Long = 0L,
    val totalBytes: Long = BRouterSegments.TOTAL_SIZE_BYTES,
    val totalSizeMb: Int = BRouterSegments.TOTAL_SIZE_MB,
    val lastUpdatedDate: String? = null,
    val wifiOnly: Boolean = true,
    val isUpdateAvailable: Boolean = false,
    val hasVersionMismatch: Boolean = false,
    val statusNotice: String? = null
)

class OfflineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository
    private val settingsRepository: SettingsRepository = DanskStiApp.getSettingsRepository(application)
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(application)
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(application) }.getOrNull()

    val downloadedTrails: StateFlow<List<DownloadedTrailEntity>> = repository.getAllDownloadedTrails()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgressText = MutableStateFlow("")
    val downloadProgressText: StateFlow<String> = _downloadProgressText.asStateFlow()

    private val _currentEstimate = MutableStateFlow<OfflineEstimateResult?>(null)
    val currentEstimate: StateFlow<OfflineEstimateResult?> = _currentEstimate.asStateFlow()

    private val _downloadRefuseError = MutableStateFlow<String?>(null)
    val downloadRefuseError: StateFlow<String?> = _downloadRefuseError.asStateFlow()

    fun clearDownloadRefuseError() {
        _downloadRefuseError.value = null
    }

    val mapTilesWifiOnly: StateFlow<Boolean> = settingsRepository.offlineDownloadsWifiOnlyFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            settingsRepository.currentSettingsSnapshot().offlineDownloadsWifiOnly
        )

    fun setMapTilesWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOfflineDownloadsWifiOnly(wifiOnly)
        }
    }

    // BRouter offline data state
    private val _brouterWifiOnly = MutableStateFlow(storageManager.isWifiOnlyDefault())
    private val _brouterDiskStateTrigger = MutableStateFlow(System.currentTimeMillis())

    init {
        checkForBRouterUpdates()
    }

    private fun buildCurrentBRouterDataState(
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

        val downloadedSegments = storageManager.getDownloadedSegments()
        val isAllDownloaded = storageManager.areAllDenmarkSegmentsDownloaded()
        val lastUpdatedDate = storageManager.getFormattedLastUpdatedDate()
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
            downloadedSegments = downloadedSegments,
            isAllDownloaded = isAllDownloaded,
            isDownloading = isWorkRunning,
            progressPercent = progressPct,
            currentSegmentName = currentSeg,
            totalDownloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            totalSizeMb = totalSizeMb,
            lastUpdatedDate = lastUpdatedDate,
            wifiOnly = wifiOnly,
            isUpdateAvailable = isUpdateAvailable,
            hasVersionMismatch = hasVersionMismatch,
            statusNotice = notice
        )
    }

    val brouterDataState: StateFlow<BRouterDataState> = combine(
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
        buildCurrentBRouterDataState(workInfos, wifiOnly)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        buildCurrentBRouterDataState()
    )

    fun checkForBRouterUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.fetchRemoteSegmentsMetadata()
            _brouterDiskStateTrigger.value = System.currentTimeMillis()
        }
    }

    fun setBRouterWifiOnly(wifiOnly: Boolean) {
        _brouterWifiOnly.value = wifiOnly
        storageManager.setWifiOnlyDefault(wifiOnly)
    }

    fun downloadBRouterData(wifiOnly: Boolean = _brouterWifiOnly.value, forceUpdate: Boolean = false) {
        setBRouterWifiOnly(wifiOnly)
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.fetchRemoteSegmentsMetadata()
            val storageCheck = BRouterSegmentDownloadWorker.enqueue(
                getApplication(),
                wifiOnly = wifiOnly,
                replace = true,
                forceUpdate = forceUpdate
            )
            if (!storageCheck.hasEnoughSpace) {
                _downloadRefuseError.value = storageCheck.message
            } else {
                _downloadRefuseError.value = null
            }
            _brouterDiskStateTrigger.value = System.currentTimeMillis()
        }
    }

    fun updateBRouterData() {
        downloadBRouterData(wifiOnly = _brouterWifiOnly.value, forceUpdate = true)
    }

    fun cancelBRouterDownload() {
        BRouterSegmentDownloadWorker.cancel(getApplication())
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun refreshBRouterState() {
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
        checkForBRouterUpdates()
    }

    fun estimateSize(trailId: String, corridorKm: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trail = repository.getTrailById(trailId) ?: return@withContext
                val points = repository.getTrailPoints(trail).flatMap { it.points }
                val est = OfflineDownloadManager.estimateDownload(
                    points = points,
                    corridorKm = corridorKm,
                    context = getApplication(),
                    wifiOnly = mapTilesWifiOnly.value
                )
                _currentEstimate.value = est
                if (!est.isAllowed && !est.refuseReason.isNullOrBlank()) {
                    _downloadRefuseError.value = est.refuseReason
                }
            }
        }
    }

    fun startDownload(trailId: String, corridorKm: Double = 1.0) {
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgressText.value = "Starter download af offline kort..."
            withContext(Dispatchers.IO) {
                try {
                    val trail = repository.getTrailById(trailId) ?: return@withContext
                    val points = repository.getTrailPoints(trail).flatMap { it.points }
                    val wifiOnly = mapTilesWifiOnly.value
                    val est = OfflineDownloadManager.estimateDownload(
                        points = points,
                        corridorKm = corridorKm,
                        context = getApplication(),
                        wifiOnly = wifiOnly
                    )
                    _currentEstimate.value = est
                    if (!est.isAllowed) {
                        _downloadRefuseError.value = est.refuseReason
                        return@withContext
                    }

                    val enqueueRes = OfflineDownloadManager.enqueueDownload(
                        context = getApplication(),
                        trailId = trailId,
                        trailName = trail.name,
                        corridorKm = corridorKm,
                        wifiOnly = wifiOnly,
                        requiredBytes = est.estimatedSizeBytes
                    )
                    if (!enqueueRes.isAllowed) {
                        _downloadRefuseError.value = enqueueRes.refuseReason
                    } else {
                        _downloadRefuseError.value = null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OfflineViewModel", "Download failed: ${e.message}", e)
                }
            }
            _isDownloading.value = false
            _downloadProgressText.value = ""
        }
    }

    private val _lastDeletedTrail = MutableStateFlow<DownloadedTrailEntity?>(null)
    val lastDeletedTrail: StateFlow<DownloadedTrailEntity?> = _lastDeletedTrail.asStateFlow()

    fun deleteDownloadedTrail(trailId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = downloadedTrails.value.firstOrNull { it.trailId == trailId }
            if (existing != null) {
                _lastDeletedTrail.value = existing
            }
            repository.deleteDownloadedTrail(trailId)
        }
    }

    fun deleteDownloadedTrailWithUndo(trail: DownloadedTrailEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = _lastDeletedTrail.value
            if (previous != null && previous.trailId != trail.trailId) {
                repository.commitDownloadedTrailPermanentDelete(previous.trailId)
            }
            _lastDeletedTrail.value = trail
            // Round 10C Item 1: Hide in DB first; delete archive only after snackbar closes without Fortryd
            repository.hideDownloadedTrailInDb(trail.trailId)
        }
    }

    fun commitPendingDeletedTrail() {
        val item = _lastDeletedTrail.value ?: return
        _lastDeletedTrail.value = null
        viewModelScope.launch(Dispatchers.IO) {
            repository.commitDownloadedTrailPermanentDelete(item.trailId)
        }
    }

    fun undoLastDeletedTrail() {
        val item = _lastDeletedTrail.value ?: return
        _lastDeletedTrail.value = null
        viewModelScope.launch(Dispatchers.IO) {
            (getApplication<Application>() as DanskStiApp).database.dao().insertDownloadedTrail(item)
        }
    }
}
