package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.DanskStiApp
import com.example.MainActivity
import com.example.R
import com.example.data.local.DanskStiDatabase
import com.example.data.local.SettingsRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.AppLocaleHelper
import com.example.util.SunCalculator

data class ActiveTripWidgetDisplayData(
    val isActive: Boolean,
    val planId: Long = 0L,
    val trailName: String = "",
    val dayNumber: Int = 1,
    val totalDays: Int = 1,
    val remainingTodayKm: Double = 0.0,
    val nextCampName: String = "",
    val etaTimeStr: String = ""
)

class ActiveTripAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        const val WIDGET_UPDATE_MIN_INTERVAL_MS = 5 * 60_000L

        fun resolveWidgetDisplayData(
            context: Context,
            liveState: NavigationLiveState = NavigationTrackingService.liveState.value,
            nowMs: Long = System.currentTimeMillis()
        ): ActiveTripWidgetDisplayData {
            val localizedContext = AppLocaleHelper.createLocalizedContext(context)
            val configuredPace = SettingsRepository.cachedMitTempoKmh.takeIf { it > 0.0 } ?: 4.0

            if (liveState.isTracking && !liveState.isCompleted && liveState.planId > 0L) {
                val totalDays = (liveState.plannedRoute?.stages?.size ?: liveState.currentDay).coerceAtLeast(1)
                val currentDay = liveState.currentDay.coerceIn(1, totalDays)
                val nextCamp = liveState.currentStage?.stopName?.takeIf { it.isNotBlank() }
                    ?: localizedContext.getString(R.string.notif_nav_default_stage)
                val remainingToday = liveState.distanceRemainingTodayKm.coerceAtLeast(0.0)
                val etaClock = SunCalculator.computeArrivalClock(
                    remainingTodayKm = remainingToday,
                    speedKmh = liveState.speedKmh,
                    configuredPaceKmh = configuredPace,
                    nowMillis = nowMs
                )
                return ActiveTripWidgetDisplayData(
                    isActive = true,
                    planId = liveState.planId,
                    trailName = liveState.trailName.ifBlank { localizedContext.getString(R.string.app_name) },
                    dayNumber = currentDay,
                    totalDays = totalDays,
                    remainingTodayKm = remainingToday,
                    nextCampName = nextCamp,
                    etaTimeStr = etaClock
                )
            }

            // Fallback to persisted TripProgressEntity if not actively tracking in liveState
            return try {
                val db = runCatching { DanskStiApp.instance.database }.getOrNull()
                    ?: DanskStiDatabase.getInstance(context.applicationContext ?: context)
                val dao = db.dao()
                val activeProgress = dao.getAllTripProgressSync()
                    .filter { !it.isCompleted && it.planId > 0L }
                    .maxByOrNull { it.lastActiveTimestamp }
                if (activeProgress != null) {
                    val savedPlan = dao.getSavedPlanByIdSync(activeProgress.planId)
                    if (savedPlan != null) {
                        val stages = dao.getStagesForPlanSync(savedPlan.id)
                        val totalDays = stages.size.coerceAtLeast(1)
                        val currentDay = activeProgress.currentDay.coerceIn(1, totalDays)
                        val currentStage = stages.firstOrNull { it.dayNumber == currentDay } ?: stages.getOrNull(currentDay - 1)
                        val nextCamp = currentStage?.stopName?.takeIf { it.isNotBlank() }
                            ?: localizedContext.getString(R.string.notif_nav_default_stage)
                        val remainingToday = activeProgress.remainingDistanceTodayKm.coerceAtLeast(0.0)
                        val etaClock = SunCalculator.computeArrivalClock(
                            remainingTodayKm = remainingToday,
                            speedKmh = 0.0,
                            configuredPaceKmh = configuredPace,
                            nowMillis = nowMs
                        )
                        return ActiveTripWidgetDisplayData(
                            isActive = true,
                            planId = savedPlan.id,
                            trailName = savedPlan.trailName.ifBlank { localizedContext.getString(R.string.app_name) },
                            dayNumber = currentDay,
                            totalDays = totalDays,
                            remainingTodayKm = remainingToday,
                            nextCampName = nextCamp,
                            etaTimeStr = etaClock
                        )
                    }
                }
                ActiveTripWidgetDisplayData(isActive = false)
            } catch (_: Exception) {
                ActiveTripWidgetDisplayData(isActive = false)
            }
        }

        fun buildRemoteViews(
            context: Context,
            data: ActiveTripWidgetDisplayData
        ): RemoteViews {
            val localizedContext = AppLocaleHelper.createLocalizedContext(context)
            val views = RemoteViews(context.packageName, R.layout.widget_active_trip)

            val clickIntent = if (data.isActive && data.planId > 0L) {
                NavigationTrackingService.buildLaunchNavigationIntent(context, data.planId)
            } else {
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                if (data.isActive) data.planId.toInt() else 0,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            if (data.isActive) {
                val dayText = localizedContext.getString(
                    R.string.widget_day_of_total_fmt,
                    data.dayNumber.toString(),
                    data.totalDays.toString()
                )
                val remainingKmStr = AppLocaleHelper.formatNumber(localizedContext, "%.1f", data.remainingTodayKm)
                val remainingText = localizedContext.getString(
                    R.string.widget_remaining_today_fmt,
                    remainingKmStr
                )
                val campAndEtaText = localizedContext.getString(
                    R.string.widget_next_camp_eta_fmt,
                    data.nextCampName,
                    data.etaTimeStr
                )

                views.setTextViewText(R.id.widget_trail_name, data.trailName)
                views.setTextViewText(R.id.widget_day_badge, dayText)
                views.setViewVisibility(R.id.widget_day_badge, View.VISIBLE)
                views.setTextViewText(R.id.widget_remaining_today, remainingText)
                views.setTextViewText(R.id.widget_next_camp_eta, campAndEtaText)
                views.setViewVisibility(R.id.widget_next_camp_eta, View.VISIBLE)
            } else {
                views.setTextViewText(
                    R.id.widget_trail_name,
                    localizedContext.getString(R.string.widget_no_active_trip)
                )
                views.setViewVisibility(R.id.widget_day_badge, View.GONE)
                views.setTextViewText(
                    R.id.widget_remaining_today,
                    localizedContext.getString(R.string.widget_tap_to_explore)
                )
                views.setViewVisibility(R.id.widget_next_camp_eta, View.GONE)
            }

            return views
        }

        fun updateAllWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager? = AppWidgetManager.getInstance(context),
            appWidgetIds: IntArray? = null,
            liveState: NavigationLiveState = NavigationTrackingService.liveState.value,
            nowMs: Long = System.currentTimeMillis()
        ) {
            val manager = appWidgetManager ?: return
            val ids = appWidgetIds ?: runCatching {
                manager.getAppWidgetIds(ComponentName(context, ActiveTripAppWidgetProvider::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            val data = resolveWidgetDisplayData(context, liveState, nowMs)
            val views = buildRemoteViews(context, data)
            for (appWidgetId in ids) {
                manager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
