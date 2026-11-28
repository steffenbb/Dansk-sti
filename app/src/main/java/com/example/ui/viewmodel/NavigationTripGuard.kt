package com.example.ui.viewmodel

import com.example.service.NavigationTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/**
 * Audit fix batch 2: starting a trip while another one is being tracked. The navigation screen used
 * to load the new trip's route while the service kept tracking the old one, and then ignored the
 * live updates (they belonged to the other plan), so the screen looked frozen.
 */
data class ReplaceTripPrompt(val activePlanId: Long, val activeTrailName: String, val requestedPlanId: Long)

/** The prompt to show when [requestedPlanId] differs from the trip being tracked now, else null. */
internal fun replaceTripPromptFor(requestedPlanId: Long, live: com.example.service.NavigationLiveState): ReplaceTripPrompt? =
    if (live.isTracking && live.planId > 0L && requestedPlanId > 0L && live.planId != requestedPlanId) {
        ReplaceTripPrompt(live.planId, live.trailName, requestedPlanId)
    } else null

/** "Afslut og start ny": ends the active trip (as the stop button does) and starts the requested one. */
fun NavigationViewModel.confirmReplaceTrip() {
    val prompt = _replaceTripPrompt.value ?: return
    _replaceTripPrompt.value = null
    stopTrip()
    startTrip(prompt.requestedPlanId)
}

/** "Fortsæt aktiv tur": shows the trip that is being tracked instead. */
fun NavigationViewModel.keepActiveTrip() {
    val prompt = _replaceTripPrompt.value ?: return
    _replaceTripPrompt.value = null
    initPlan(prompt.activePlanId)
}
