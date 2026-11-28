package com.example.service

import android.content.Context
import com.example.data.remote.TrailWeatherInfo
import com.example.data.remote.WeatherInterpreter
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.postNavigationRainAlertNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Rain alerts during navigation, owned by the tracking service (audit fix batch 3). They used to be
 * posted by the navigation screen's viewmodel, so they stopped when Android closed the app's UI.
 * Uses the same refresh rules as the screen's forecast (every hour or after moving a few km,
 * retry 10 min after a failure) and the same alert rule (≥ 1 mm/h within an hour, at most every 3 h).
 */
class NavigationRainWatcher(
    private val context: Context,
    private val fetch: suspend (lat: Double, lng: Double, nowMs: Long) -> TrailWeatherInfo?
) {
    private var lastFetchMs = 0L
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastFailureMs = 0L
    internal var lastAlertMs = 0L
        private set
    private var job: Job? = null
    var alertsPostedForTesting = 0
        private set

    /** Called for each GPS fix while tracking (not paused). Fetches at most when the rules allow. */
    fun onFix(scope: CoroutineScope, lat: Double, lng: Double, enabled: Boolean, nowMs: Long): Job? {
        if (!enabled || job?.isActive == true) return null
        val due = WeatherInterpreter.shouldRefreshNavigationWeather(
            lastFetchTimeMs = lastFetchMs, lastFetchLat = lastLat, lastFetchLng = lastLng,
            nowMs = nowMs, currentLat = lat, currentLng = lng
        )
        if (!due) return null
        if (lastFailureMs > 0L && nowMs - lastFailureMs < WeatherInterpreter.NAV_RETRY_INTERVAL_MS) return null
        return scope.launch {
            val weather = runCatching { fetch(lat, lng, nowMs) }.getOrNull()
            if (weather == null || weather.hourly48h.isEmpty()) {
                lastFailureMs = nowMs
                return@launch
            }
            lastFetchMs = nowMs
            lastLat = lat
            lastLng = lng
            lastFailureMs = 0L
            WeatherInterpreter.shouldPostRainAlert(
                rainAlertEnabled = true, lastAlertAtMs = lastAlertMs, nowMs = nowMs, hourlyPoints = weather.hourly48h
            )?.let { block ->
                lastAlertMs = nowMs
                alertsPostedForTesting++
                postNavigationRainAlertNotification(
                    appCtx = context.applicationContext ?: context,
                    channelId = NavigationViewModel.CHANNEL_RAIN_ALERT_ID,
                    notificationId = NavigationViewModel.RAIN_ALERT_NOTIFICATION_ID,
                    precipMm = block.precipitationMm,
                    timeLabel = block.hourLabel
                )
            }
        }.also { job = it }
    }
}
