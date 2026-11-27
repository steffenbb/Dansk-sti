package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.remote.TrailWeatherInfo
import com.example.data.remote.WeatherInterpreter
import com.example.util.AppLocaleHelper
import com.example.util.GeoUtils
import com.example.util.WindAssessment
import com.example.util.WindRelation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Phase D7: extra weather lines for one planner day; null fields are simply not shown. */
data class DayWeatherExtras(
    val windLine: String? = null,
    val feelsLikeSuffix: String? = null,
    val uvLine: String? = null
)

internal object PlannerDayWeather {

    /** The part of the combined route between [startKm] and [endKm]. */
    fun stretchPoints(routePts: List<GeoPointDto>, startKm: Double, endKm: Double): List<GeoPointDto> {
        val out = mutableListOf<GeoPointDto>()
        var km = 0.0
        for (i in routePts.indices) {
            if (i > 0) km += GeoUtils.distanceMeters(routePts[i - 1].lat, routePts[i - 1].lng, routePts[i].lat, routePts[i].lng) / 1000.0
            if (km in startKm..endKm) out.add(routePts[i])
        }
        return out
    }

    private fun dateString(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Copenhagen") }.format(Date(epochMs))

    /**
     * Wind relative to the day's walking direction at the midday ETA (hourly, else the date's
     * dominant daily wind), the night feels-like suffix when apparent_temperature_min is at least
     * 2° below [shownTempC], and a UV line when that date's UV index is ≥ 6.
     */
    fun compute(
        weather: TrailWeatherInfo,
        dayIndex: Int,
        startDateMillis: Long,
        startHour: Int,
        walkMinutes: Int,
        stretch: List<GeoPointDto>,
        shownTempC: Double?,
        context: Context?,
        locale: Locale
    ): DayWeatherExtras {
        val middayMs = com.example.util.CopenhagenTime.localMidnightPlus(startDateMillis, dayIndex, startHour * 60 + walkMinutes / 2)
        val day = weather.daily.firstOrNull { it.date == dateString(middayMs) }

        val windLine = run {
            val bearing = WindAssessment.dayTravelBearing(stretch) ?: return@run null
            val f = WeatherInterpreter.forecastAt(weather, middayMs)
            val speed = f?.windMs ?: return@run null
            val dir = f.windDirectionDeg ?: return@run null
            val res = when (WindAssessment.assess(dir, bearing, speed)) {
                WindRelation.HEADWIND -> com.example.R.string.planner_wind_headwind_fmt
                WindRelation.TAILWIND -> com.example.R.string.planner_wind_tailwind_fmt
                WindRelation.CROSSWIND -> com.example.R.string.planner_wind_crosswind_fmt
                WindRelation.NONE -> return@run null
            }
            AppLocaleHelper.strOrNull(context, res, String.format(locale, "%.0f", speed), WindAssessment.compassDanish(dir))
        }

        val feelsLike = run {
            val shown = shownTempC ?: return@run null
            val apparentMin = day?.apparentMinC ?: return@run null
            if (apparentMin > shown - 2.0) return@run null
            AppLocaleHelper.strOrNull(context, com.example.R.string.planner_night_feels_like_fmt, apparentMin.roundToInt())
        }

        val uvLine = day?.uvMax?.takeIf { it >= 6.0 }?.let { uv ->
            val res = if (uv >= 8.0) com.example.R.string.weather_uv_very_high_fmt else com.example.R.string.weather_uv_high_fmt
            AppLocaleHelper.strOrNull(context, res, String.format(locale, "%.0f", uv))
        }
        return DayWeatherExtras(windLine, feelsLike, uvLine)
    }

    /** The temperature the day card shows: the afternoon hourly point, else that date's daily max. */
    fun shownTemp(weather: TrailWeatherInfo, dayIndex: Int, startDateMillis: Long): Double? {
        val date = dateString(com.example.util.CopenhagenTime.localMidnightPlus(startDateMillis, dayIndex, 12 * 60))
        return WeatherInterpreter.findAfternoonForecastForDate(weather, date)?.tempC
            ?: weather.daily.firstOrNull { it.date == date }?.tempMaxC
    }
}

/** Phase D7: adds wind, night feels-like and UV to a day card from the (cached) camp forecast. */
internal suspend fun RouteBuilderViewModel.withDayWeatherExtras(summary: PlannerDaySummary, dayIndex: Int): PlannerDaySummary {
    val state = _uiState.value
    val weather = runCatching {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.getTrailWeather(lat = summary.campLat, lng = summary.campLng, forceNetworkRefresh = false)
        }
    }.getOrNull() ?: return summary
    val ctx = AppLocaleHelper.getLocalizedContext(getApplication())
    val locale = AppLocaleHelper.getCurrentLocale(ctx)
    val unavailable = runCatching { ctx.getString(com.example.R.string.day_weather_unavailable) }.getOrNull()
    val hasNightForecast = summary.nightWeatherSummary.isNotBlank() && summary.nightWeatherSummary != unavailable
    val stretch = PlannerDayWeather.stretchPoints(PlannerDayWarnings.combinedRoutePoints(state, state.waypoints), summary.startKm, summary.endKm)
    val extras = PlannerDayWeather.compute(
        weather = weather,
        dayIndex = dayIndex,
        startDateMillis = state.startDateMillis,
        startHour = state.startHour,
        walkMinutes = (summary.estimatedHours * 60.0).toInt(),
        stretch = stretch,
        shownTempC = if (hasNightForecast) PlannerDayWeather.shownTemp(weather, dayIndex, state.startDateMillis) else null,
        context = ctx,
        locale = locale
    )
    return summary.copy(
        nightWeatherSummary = summary.nightWeatherSummary + (extras.feelsLikeSuffix?.let { ", $it" } ?: ""),
        windLine = extras.windLine,
        uvLine = extras.uvLine
    )
}
