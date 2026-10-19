package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.model.SavedTrailEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripGearItemEntity
import com.example.data.model.TripPlanEntity
import com.example.data.repository.DanskStiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedTrailItem(
    val trail: TrailEntity,
    val savedAt: Long
)

class SavedViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DanskStiRepository = (application as DanskStiApp).repository

    val savedPlans: StateFlow<List<TripPlanEntity>> = repository.getAllTripPlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _allTrails = repository.getAllTrails()
    private val _savedTrailEntities = repository.getAllSavedTrails()

    val savedTrails: StateFlow<List<SavedTrailItem>> = combine(_allTrails, _savedTrailEntities) { trails, saved ->
        val trailMap = trails.associateBy { it.id }
        saved.mapNotNull { s ->
            trailMap[s.trailId]?.let { t -> SavedTrailItem(t, s.savedAt) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customRoutes: StateFlow<List<com.example.data.model.CustomRouteEntity>> = repository.getAllCustomRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteCustomRoute(id: Long) {
        viewModelScope.launch {
            repository.deleteCustomRoute(id)
        }
    }

    fun startNavigationForCustomRoute(routeId: Long, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cr = repository.getCustomRoute(routeId) ?: return@withContext
                val planId = repository.ensureTripPlanForCustomRoute(cr)
                withContext(Dispatchers.Main) {
                    onReady(planId)
                }
            }
        }
    }

    fun downloadCustomRouteOffline(route: com.example.data.model.CustomRouteEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.example.util.OfflineDownloadManager.enqueueDownload(
                    context = getApplication(),
                    trailId = "route_${route.id}",
                    trailName = route.name,
                    corridorKm = 1.0
                )
            }
        }
    }

    fun exportCustomRouteGpx(route: com.example.data.model.CustomRouteEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cr = repository.getCustomRoute(route.id) ?: return@withContext
                val geom = repository.getTrailGeometry("route_${route.id}")
                val points = geom?.segments?.flatMap { it.points } ?: emptyList()
                val dummyTrail = TrailEntity(
                    id = "route_${cr.id}",
                    name = cr.name,
                    description = "Brugerskabt rute (${String.format(java.util.Locale.US, "%.1f", cr.totalKm)} km)",
                    region = "Danmark",
                    parentRegion = "Danmark",
                    computedWalkingKm = cr.totalKm
                )
                val gpxStr = com.example.util.GpxExporter.generateMultiSegmentGpx(
                    trail = dummyTrail,
                    plan = null,
                    stages = emptyList(),
                    trailPoints = points
                )
                com.example.util.GpxExporter.exportAndShareGpxFile(
                    context = getApplication(),
                    fileName = "${cr.name.replace(" ", "_")}.gpx",
                    gpxContent = gpxStr,
                    shareTitle = "Del rute: ${cr.name}"
                )
            }
        }
    }

    private val _selectedPlanForGear = MutableStateFlow<TripPlanEntity?>(null)
    val selectedPlanForGear: StateFlow<TripPlanEntity?> = _selectedPlanForGear.asStateFlow()

    private val _gearItems = MutableStateFlow<List<TripGearItemEntity>>(emptyList())
    val gearItems: StateFlow<List<TripGearItemEntity>> = _gearItems.asStateFlow()

    private var recentlyDeletedPlan: TripPlanEntity? = null

    fun selectPlanForGear(plan: TripPlanEntity?) {
        _selectedPlanForGear.value = plan
        if (plan != null) {
            viewModelScope.launch {
                repository.getGearItemsForPlan(plan.id).collect { items ->
                    _gearItems.value = items
                }
            }
        } else {
            _gearItems.value = emptyList()
        }
    }

    fun toggleGearItem(item: TripGearItemEntity) {
        viewModelScope.launch {
            repository.updateGearItem(item.copy(isPacked = !item.isPacked))
        }
    }

    fun removeSavedTrail(trailId: String) {
        viewModelScope.launch {
            repository.toggleSaveTrail(trailId)
        }
    }

    fun deletePlan(planId: Long, onUndoAvailable: (TripPlanEntity) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val plan = repository.getTripPlanById(planId)
                if (plan != null) {
                    recentlyDeletedPlan = plan
                    repository.deleteTripPlan(planId)
                    withContext(Dispatchers.Main) {
                        onUndoAvailable(plan)
                    }
                }
            }
        }
    }

    fun undoDeletePlan(plan: TripPlanEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val trail = repository.getTrailById(plan.trailId)
                if (trail != null) {
                    repository.saveTripPlan(
                        trail = trail,
                        startKm = plan.startKm,
                        endKm = plan.endKm,
                        isReverse = plan.isReverse,
                        days = plan.targetDays,
                        overnightType = com.example.data.model.OvernightType.valueOf(plan.overnightType),
                        preferredDailyKm = plan.preferredDailyKm,
                        stages = emptyList(),
                        paceKmh = plan.paceKmh,
                        maxDetourKm = plan.maxDetourKm,
                        startTime = plan.startTime
                    )
                }
            }
        }
    }

    // Phase 7d: Share trip plan as text (stages, nights, dates/tempo)
    fun shareTripPlan(context: android.content.Context, plan: TripPlanEntity) {
        viewModelScope.launch {
            val stages = repository.getStagesForPlan(plan.id)
            val totalKm = plan.totalDistanceKm
            val days = plan.targetDays
            val sb = StringBuilder()
            sb.append("🥾 Min vandretur: ${plan.trailName}\n")
            sb.append("Samlet distance: ${String.format(java.util.Locale.US, "%.1f", totalKm)} km over $days ${if (days == 1) "dag" else "dage"}\n")
            sb.append("Planlagt tempo: ${String.format(java.util.Locale.US, "%.1f", plan.paceKmh)} km/t\n\n")
            sb.append("📍 Dags-etaper & overnatning:\n")
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
