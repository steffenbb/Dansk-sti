package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.repository.TideEvent
import com.example.util.AppLocaleHelper
import com.example.util.PathFeatureType
import com.example.util.PathFeatureWarning
import com.example.util.TideRelevance
import java.util.Locale

/** Phase E3: tide lines for one planner day (pure apart from string lookup). */
internal object PlannerDayTides {

    /** "Tidevand: højvande 05:42 og 18:03, lavvande 11:58 (DMI, Esbjerg Havn)". */
    fun tideLine(events: List<TideEvent>, stationName: String, context: Context?): String? {
        val highs = events.filter { it.isHigh }
        val lows = events.filter { !it.isHigh }
        if (highs.isEmpty() || lows.isEmpty()) return null
        return AppLocaleHelper.strOrNull(context, com.example.R.string.planner_tide_fmt,
            TideRelevance.times(highs, context), TideRelevance.times(lows, context), stationName)
    }

    /** "Du når km 12,4 omkring højvande (kl. 14:05) – …" for TIDAL warnings reached within ±2 h of a high water. */
    fun arrivalWarnings(
        events: List<TideEvent>,
        tidalWarnings: List<PathFeatureWarning>,
        dayStartKm: Double,
        dayEndKm: Double,
        startHour: Int,
        dayWalkMinutes: Int,
        startDateMillis: Long,
        dayIndex: Int,
        context: Context?,
        locale: Locale
    ): List<String> = tidalWarnings
        .filter { it.type == PathFeatureType.TIDAL && it.startKm >= dayStartKm && it.startKm <= dayEndKm }
        .mapNotNull { w ->
            val span = (dayEndKm - dayStartKm).takeIf { it > 0.0 } ?: return@mapNotNull null
            val etaMin = startHour * 60 + (dayWalkMinutes * ((w.startKm - dayStartKm) / span)).toInt()
            val etaMs = com.example.util.CopenhagenTime.localMidnightPlus(startDateMillis, dayIndex, etaMin)
            val hw = TideRelevance.highWaterNear(events, etaMs) ?: return@mapNotNull null
            AppLocaleHelper.strOrNull(context, com.example.R.string.planner_tide_arrival_warning_fmt,
                com.example.ui.components.formatDanishKm(w.startKm, 1, locale), TideRelevance.clock(hw.timeMs))
        }
        .distinct()
}

/** Phase E3: adds the tide line and high-water arrival warnings to a day card (DMI, cached per station/day). */
internal suspend fun RouteBuilderViewModel.withDayTides(summary: PlannerDaySummary, dayIndex: Int): PlannerDaySummary {
    val state = _uiState.value
    val online = repository.isDeviceOnline()
    val tideRepo = repository.tideRepository
    val stretch: List<GeoPointDto> = PlannerDayWeather.stretchPoints(
        PlannerDayWarnings.combinedRoutePoints(state, state.waypoints), summary.startKm, summary.endKm
    )
    val nearest = runCatching { TideRelevance.nearestStation(stretch, tideRepo.getStations(allowNetwork = online)) }.getOrNull()
        ?: return summary
    val dayMs = com.example.util.CopenhagenTime.localMidnightPlus(state.startDateMillis, dayIndex, 12 * 60)
    val events = runCatching { tideRepo.getPredictions(nearest.station, dayMs, allowNetwork = online) }.getOrDefault(emptyList())
    val tidalOnDay = state.pathFeatureWarnings.filter { it.type == PathFeatureType.TIDAL && it.startKm in summary.startKm..summary.endKm }
    if (!TideRelevance.isRelevant(events, tidalOnDay.isNotEmpty())) return summary
    val ctx = AppLocaleHelper.getLocalizedContext(getApplication())
    val warnings = PlannerDayTides.arrivalWarnings(
        events, tidalOnDay, summary.startKm, summary.endKm, state.startHour, (summary.estimatedHours * 60.0).toInt(),
        state.startDateMillis, dayIndex, ctx, AppLocaleHelper.getCurrentLocale(ctx)
    )
    return summary.copy(
        tideLine = PlannerDayTides.tideLine(events, nearest.station.name, ctx),
        warnings = summary.warnings + warnings.filter { it !in summary.warnings }
    )
}
