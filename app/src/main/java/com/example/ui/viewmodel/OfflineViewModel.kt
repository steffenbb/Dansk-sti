package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.DownloadedTrailEntity
import com.example.data.repository.DanskStiRepository
import com.example.util.OfflineDownloadManager
import com.example.util.OfflineEstimateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    val downloadedTrails: StateFlow<List<DownloadedTrailEntity>> = repository.getAllDownloadedTrails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgressText = MutableStateFlow("")
    val downloadProgressText: StateFlow<String> = _downloadProgressText.asStateFlow()

    private val _currentEstimate = MutableStateFlow<OfflineEstimateResult?>(null)
    val currentEstimate: StateFlow<OfflineEstimateResult?> = _currentEstimate.asStateFlow()

    fun estimateSize(trailId: String, corridorKm: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trail = repository.getTrailById(trailId) ?: return@withContext
                val points = repository.getTrailPoints(trail)
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
                    val points = repository.getTrailPoints(trail)
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
