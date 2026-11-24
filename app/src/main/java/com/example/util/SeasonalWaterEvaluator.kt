package com.example.util

import com.example.data.model.PoiEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Evaluates whether a seasonal water post is potentially closed on a given trip date
 * and excludes potentially closed water posts from "Længste stræk uden vand" (Part E2).
 */
object SeasonalWaterEvaluator {

    private val MONTH_MAP = mapOf(
        "jan" to 1, "januar" to 1, "january" to 1,
        "feb" to 2, "februar" to 2, "february" to 2,
        "mar" to 3, "marts" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5, "maj" to 5,
        "jun" to 6, "juni" to 6, "june" to 6,
        "jul" to 7, "juli" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "okt" to 10, "oktober" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12
    )

    private val MONTH_RANGE_REGEX = Regex(
        "\\b([A-Za-zÆØÅæøå]{3,9})\\s*[-–—]\\s*([A-Za-zÆØÅæøå]{3,9})\\b"
    )

    /**
     * Extracts 1-based month (1 = January .. 12 = December) in Europe/Copenhagen time zone.
     */
    fun getMonth1To12(epochMillis: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Copenhagen"), Locale.ROOT).apply {
            timeInMillis = epochMillis
        }
        return cal.get(Calendar.MONTH) + 1
    }

    /**
     * Parses an OSM `opening_hours` or `seasonal` string for a month range (e.g., "Apr-Oct", "May-Sep")
     * and returns the set of open months (1..12), or null if no explicit month range is found.
     */
    fun parseOpenMonths(text: String?): Set<Int>? {
        if (text.isNullOrBlank()) return null
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower == "24/7" || lower == "always") return (1..12).toSet()

        val match = MONTH_RANGE_REGEX.find(lower)
        if (match != null) {
            val startMonth = MONTH_MAP[match.groupValues[1]]
            val endMonth = MONTH_MAP[match.groupValues[2]]
            if (startMonth != null && endMonth != null) {
                return buildMonthRangeSet(startMonth, endMonth)
            }
        }
        return null
    }

    private fun buildMonthRangeSet(startMonth: Int, endMonth: Int): Set<Int> {
        val months = mutableSetOf<Int>()
        if (startMonth <= endMonth) {
            for (m in startMonth..endMonth) months.add(m)
        } else {
            for (m in startMonth..12) months.add(m)
            for (m in 1..endMonth) months.add(m)
        }
        return months
    }

    /**
     * Returns true if the water post is potentially closed on the given month (1..12):
     * - If `openingHours` (or `seasonal`) specifies an explicit month range (e.g. `Apr-Oct`),
     *   returns true when `month1To12` is outside that range.
     * - Otherwise, if `seasonal` is set (and not `"no"` / `"false"`), returns true in November–March (11, 12, 1, 2, 3).
     */
    fun isWaterPoiPotentiallyClosedForMonth(
        seasonal: String?,
        openingHours: String?,
        month1To12: Int
    ): Boolean {
        val explicitOpenMonths = parseOpenMonths(openingHours) ?: parseOpenMonths(seasonal)
        if (explicitOpenMonths != null) {
            return month1To12 !in explicitOpenMonths
        }

        val seasonalNorm = seasonal?.trim()?.lowercase(Locale.ROOT)
        val isSeasonalFlag = !seasonalNorm.isNullOrBlank() &&
            seasonalNorm != "no" &&
            seasonalNorm != "false" &&
            seasonalNorm != "0"

        if (isSeasonalFlag) {
            // Default seasonal closure in Denmark: November through March
            return month1To12 in listOf(11, 12, 1, 2, 3)
        }
        return false
    }

    fun isWaterPoiPotentiallyClosed(
        poi: PoiEntity,
        tripEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!poi.type.equals("water", ignoreCase = true)) return false
        val month = getMonth1To12(tripEpochMillis)
        return isWaterPoiPotentiallyClosedForMonth(
            seasonal = poi.seasonal,
            openingHours = poi.openingHours,
            month1To12 = month
        )
    }

    fun isWaterPoiPotentiallyClosed(
        poi: PoiEntity,
        tripEpochMillis: Long?
    ): Boolean {
        return isWaterPoiPotentiallyClosed(poi, tripEpochMillis ?: System.currentTimeMillis())
    }

    /**
     * Computes the longest distance without water (in km) along a route of length [totalWalkKm],
     * ignoring any water POI that is potentially closed on [tripEpochMillis].
     */
    fun computeLongestWaterGapKm(
        totalWalkKm: Double,
        waterPoisWithRouteKm: List<Pair<PoiEntity, Double>>,
        overnightWaterRouteKms: List<Double> = emptyList(),
        tripEpochMillis: Long = System.currentTimeMillis()
    ): Double {
        if (totalWalkKm <= 0.0) return 0.0
        val openWaterKms = waterPoisWithRouteKm
            .filter { (poi, _) -> !isWaterPoiPotentiallyClosed(poi, tripEpochMillis) }
            .map { (_, km) -> km.coerceIn(0.0, totalWalkKm) }

        val allWaterPositions = (listOf(0.0) +
            openWaterKms +
            overnightWaterRouteKms.map { it.coerceIn(0.0, totalWalkKm) } +
            listOf(totalWalkKm))
            .distinct()
            .sorted()

        var maxGap = 0.0
        for (i in 0 until allWaterPositions.size - 1) {
            val gap = allWaterPositions[i + 1] - allWaterPositions[i]
            if (gap > maxGap) {
                maxGap = gap
            }
        }
        return maxGap
    }
}
