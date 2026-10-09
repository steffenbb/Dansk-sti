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
}
