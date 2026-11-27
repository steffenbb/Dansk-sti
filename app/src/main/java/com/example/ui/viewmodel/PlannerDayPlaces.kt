package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.TripStage
import com.example.util.OpenState
import com.example.util.OpeningHoursEvaluator
import com.example.util.OsmExtraLabels
import java.util.Locale

/** Phase C7: a shop or café on the day's stretch, with arrival time and open state at arrival. */
data class DayFoodStop(
    val name: String,
    val km: Double,
    val etaText: String,
    val statusText: String
)

/** Phase C7: food stops and highlights for the planner day cards (pure apart from string lookup). */
internal object PlannerDayPlaces {

    const val FOOD_MAX_DETOUR_M = 300.0
    const val HIGHLIGHT_MAX_DETOUR_M = 500.0
    const val MAX_FOOD_STOPS = 4
    const val MAX_HIGHLIGHTS = 5

    /** Types the planner keeps in nearbyPois only for the day cards. */
    val DAY_CARD_EXTRA_TYPES = setOf("cafe", "highlight", "beach")

    private val FOOD_TYPES = setOf("shop", "supermarket", "convenience", "bakery", "farm", "cafe")
    private val HIGHLIGHT_TYPES = setOf("highlight", "beach")

    /** Minutes after midnight on the day: startHour*60 + walk minutes × the share of the stage walked. */
    fun etaMinutes(startHour: Int, estimatedWalkMinutes: Int, stageStartKm: Double, stageKm: Double, poiKm: Double): Int {
        val share = if (stageKm > 0.0) ((poiKm - stageStartKm) / stageKm).coerceIn(0.0, 1.0) else 0.0
        return startHour * 60 + (estimatedWalkMinutes * share).toInt()
    }

    /** Epoch ms of [etaMinutes] on the Copenhagen calendar day startDateMillis + [dayIndex] days. */
    fun arrivalEpochMs(startDateMillis: Long, dayIndex: Int, etaMinutes: Int): Long =
        com.example.util.CopenhagenTime.localMidnightPlus(startDateMillis, dayIndex, etaMinutes)

    fun formatClock(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60)

    /** "åben ved ankomst" / "lukket ved ankomst (åbner 10:00)" / "lukket ved ankomst" / "åbningstider ukendt". */
    fun statusText(openingHours: String?, arrivalMs: Long, context: Context?): String =
        when (val st = OpeningHoursEvaluator.evaluate(openingHours, arrivalMs)) {
            is OpenState.Open -> str(context, com.example.R.string.planner_food_open_at_arrival, "åben ved ankomst")
            is OpenState.Closed -> st.opensAtToday?.let {
                com.example.util.AppLocaleHelper.strOrNull(context, com.example.R.string.planner_food_closed_at_arrival_opens_fmt, it)
                    ?: "lukket ved ankomst (åbner $it)"
            } ?: str(context, com.example.R.string.planner_food_closed_at_arrival, "lukket ved ankomst")
            OpenState.Unknown -> str(context, com.example.R.string.planner_food_hours_unknown, "åbningstider ukendt")
        }

    private fun str(context: Context?, resId: Int, fallback: String): String =
        com.example.util.AppLocaleHelper.strOrNull(context, resId) ?: fallback

    /** A POI projected onto the combined route: (km along route, distance to route in m). */
    data class Projected(val poi: PoiEntity, val km: Double, val detourM: Double)

    fun project(pois: List<PoiEntity>, routePts: List<GeoPointDto>, types: Set<String>): List<Projected> {
        if (routePts.size < 2) return emptyList()
        return pois.filter { it.type.lowercase() in types }.map { p ->
            val (km, d) = com.example.util.GeoUtils.projectPointOnPolyline(GeoPointDto(p.lat, p.lng), routePts)
            Projected(p, km, d)
        }
    }

    fun projectFood(pois: List<PoiEntity>, routePts: List<GeoPointDto>) = project(pois, routePts, FOOD_TYPES)
    fun projectHighlights(pois: List<PoiEntity>, routePts: List<GeoPointDto>) = project(pois, routePts, HIGHLIGHT_TYPES)

    fun foodStopsForDay(
        projected: List<Projected>,
        stage: TripStage,
        dayIndex: Int,
        startHour: Int,
        startDateMillis: Long,
        context: Context?
    ): List<DayFoodStop> = projected
        .filter { it.detourM <= FOOD_MAX_DETOUR_M && it.km >= stage.startKm && it.km <= stage.endKm }
        .sortedBy { it.km }
        .distinctBy { it.poi.id }
        .take(MAX_FOOD_STOPS)
        .map { pr ->
            val eta = etaMinutes(startHour, stage.estimatedWalkMinutes, stage.startKm, stage.stageKm, pr.km)
            DayFoodStop(
                name = pr.poi.name,
                km = pr.km,
                etaText = formatClock(eta),
                statusText = statusText(pr.poi.openingHours, arrivalEpochMs(startDateMillis, dayIndex, eta), context)
            )
        }

    /** "Himmelbjerget (udsigtspunkt)", or just the label when the place has no own name. */
    fun highlightsForDay(projected: List<Projected>, stage: TripStage, locale: Locale, labelFor: (Int) -> String): List<String> =
        projected
            .filter { it.detourM <= HIGHLIGHT_MAX_DETOUR_M && it.km >= stage.startKm && it.km <= stage.endKm }
            .sortedBy { it.km }
            .distinctBy { it.poi.id }
            .take(MAX_HIGHLIGHTS)
            .map { pr ->
                val rawLabel = OsmExtraLabels.labelRes(pr.poi.type, pr.poi.subtype)?.let(labelFor)
                // German nouns stay capitalised; Danish/English labels read better in lower case inside brackets
                val label = if (locale.language == "de") rawLabel else rawLabel?.lowercase(locale)
                if (label == null || pr.poi.name.equals(rawLabel, ignoreCase = true)) pr.poi.name else "${pr.poi.name} ($label)"
            }
}
