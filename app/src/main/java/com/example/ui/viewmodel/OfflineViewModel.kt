package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.DanskStiApp
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
    val lastUpdatedDate: String? = null,
    val wifiOnly: Boolean = true
)

class OfflineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(application)
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(application) }.getOrNull()

    val downloadedTrails: StateFlow<List<DownloadedTrailEntity>> = repository.getAllDownloadedTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgressText = MutableStateFlow("")
    val downloadProgressText: StateFlow<String> = _downloadProgressText.asStateFlow()

    private val _currentEstimate = MutableStateFlow<OfflineEstimateResult?>(null)
    val currentEstimate: StateFlow<OfflineEstimateResult?> = _currentEstimate.asStateFlow()

    // BRouter offline data state
    private val _brouterWifiOnly = MutableStateFlow(storageManager.isWifiOnlyDefault())
    private val _brouterDiskStateTrigger = MutableStateFlow(System.currentTimeMillis())

    val brouterDataState: StateFlow<BRouterDataState> = combine(
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

        val downloadedSegments = storageManager.getDownloadedSegments()
        val isAllDownloaded = storageManager.areAllDenmarkSegmentsDownloaded()
        val lastUpdatedDate = storageManager.getFormattedLastUpdatedDate()

        BRouterDataState(
            segments = BRouterSegments.DENMARK_SEGMENTS,
            downloadedSegments = downloadedSegments,
            isAllDownloaded = isAllDownloaded,
            isDownloading = isWorkRunning,
            progressPercent = progressPct,
            currentSegmentName = currentSeg,
            totalDownloadedBytes = downloadedBytes,
            totalBytes = BRouterSegments.TOTAL_SIZE_BYTES,
            lastUpdatedDate = lastUpdatedDate,
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

    fun setBRouterWifiOnly(wifiOnly: Boolean) {
        _brouterWifiOnly.value = wifiOnly
        storageManager.setWifiOnlyDefault(wifiOnly)
    }

    fun downloadBRouterData(wifiOnly: Boolean = _brouterWifiOnly.value) {
        setBRouterWifiOnly(wifiOnly)
        BRouterSegmentDownloadWorker.enqueue(getApplication(), wifiOnly = wifiOnly, replace = true)
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun updateBRouterData() {
        downloadBRouterData(wifiOnly = _brouterWifiOnly.value)
    }

    fun cancelBRouterDownload() {
        BRouterSegmentDownloadWorker.cancel(getApplication())
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun refreshBRouterState() {
        _brouterDiskStateTrigger.value = System.currentTimeMillis()
    }

    fun estimateSize(trailId: String, corridorKm: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trail = repository.getTrailById(trailId) ?: return@withContext
                val points = repository.getTrailPoints(trail).flatMap { it.points }
                val est = OfflineDownloadManager.estimateDownload(points, corridorKm)
                _currentEstimate.value = est
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
                    val est = OfflineDownloadManager.estimateDownload(points, corridorKm)

                    OfflineDownloadManager.enqueueDownload(
                        context = getApplication(),
                        trailId = trailId,
                        trailName = trail.name,
                        corridorKm = corridorKm
                    )
                } catch (e: Exception) {
                    android.util.Log.e("OfflineViewModel", "Download failed: ${e.message}", e)
                }
            }
            _isDownloading.value = false
            _downloadProgressText.value = ""
        }
    }

    fun deleteDownloadedTrail(trailId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                OfflineDownloadManager.deleteOfflineTrail(getApplication(), trailId)
                repository.deleteDownloadedTrail(trailId)
            }
        }
    }
}
