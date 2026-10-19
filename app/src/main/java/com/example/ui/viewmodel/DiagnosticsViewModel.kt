package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.util.network.ApiCheckResult
import com.example.util.network.ApiCheckStatus
import com.example.util.network.ApiHealthChecker
import com.example.util.network.DiagnosticsReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DanskStiApp
    private val healthChecker = ApiHealthChecker(application, app.database.dao())

    private val _report = MutableStateFlow<DiagnosticsReport?>(null)
    val report: StateFlow<DiagnosticsReport?> = _report.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        runDiagnostics()
    }

    fun runDiagnostics() {
        if (_isTesting.value) return
        viewModelScope.launch {
            _isTesting.value = true
            _statusMessage.value = "Kører forbindelsestest for alle 5 datakilder..."
            try {
                val rep = healthChecker.runFullDiagnostics()
                _report.value = rep
                val errors = rep.results.count { it.status == ApiCheckStatus.ERROR }
                val unconfig = rep.results.count { it.status == ApiCheckStatus.UNCONFIGURED }
                _statusMessage.value = when {
                    errors > 0 -> "Diagnostik fuldført: $errors tjeneste(r) melder fejl."
                    unconfig > 0 -> "Diagnostik fuldført: API-nøgle mangler i Secrets."
                    else -> "Diagnostik fuldført: Alle forbindelser er aktive og klar!"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Fejl under test: ${e.message}"
            } finally {
                _isTesting.value = false
            }
        }
    }

    fun clearTrailCache() {
        viewModelScope.launch {
            try {
                val trailsDir = File(app.filesDir, "trails")
                val files = trailsDir.listFiles() ?: emptyArray()
                for (f in files) {
                    f.delete()
                }
                _statusMessage.value = "Rutedatacache ryddet (${files.size} filer slettet)."
                // Refresh report
                runDiagnostics()
            } catch (e: Exception) {
                _statusMessage.value = "Kunne ikke rydde cache: ${e.message}"
            }
        }
    }
}
