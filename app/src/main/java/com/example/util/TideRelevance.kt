package com.example.util

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.repository.TideEvent
import com.example.data.repository.TideStation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The nearest DMI station to a stretch and its distance from the route. */
data class NearestTideStation(val station: TideStation, val distanceKm: Double)

/** Phase E2: when tides matter on a stretch (pure Kotlin apart from string lookup). */
object TideRelevance {
    const val MAX_STATION_KM = 15.0
    const val SAMPLE_KM = 0.5
    const val MIN_RANGE_CM = 80.0
    const val HIGH_WATER_WINDOW_MS = 2L * 3600L * 1000L

    /** The station nearest to any point of the stretch (sampled every 500 m), if within 15 km. */
    fun nearestStation(points: List<GeoPointDto>, stations: List<TideStation>): NearestTideStation? {
        if (points.isEmpty() || stations.isEmpty()) return null
        val samples = mutableListOf(points.first())
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += GeoUtils.distanceMeters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng) / 1000.0
            if (acc >= SAMPLE_KM) { samples.add(points[i]); acc = 0.0 }
        }
        samples.add(points.last())
        var best: NearestTideStation? = null
        for (st in stations) {
            for (s in samples) {
                val d = GeoUtils.distanceMeters(s.lat, s.lng, st.lat, st.lng) / 1000.0
                if (d <= MAX_STATION_KM && (best == null || d < best.distanceKm)) best = NearestTideStation(st, d)
            }
        }
        return best
    }

    /** Highest high minus lowest low that day, in cm; null without both. */
    fun rangeCm(events: List<TideEvent>): Double? {
        val high = events.filter { it.isHigh }.maxOfOrNull { it.levelCm } ?: return null
        val low = events.filter { !it.isHigh }.minOfOrNull { it.levelCm } ?: return null
        return high - low
    }

    /** Shown when the day's range is ≥ 80 cm, or whenever the stretch has a TIDAL path warning. */
    fun isRelevant(events: List<TideEvent>, hasTidalWarning: Boolean): Boolean {
        if (events.isEmpty()) return false
        return hasTidalWarning || (rangeCm(events) ?: 0.0) >= MIN_RANGE_CM
    }

    /** The high water within ±2 h of [etaMs], if any (the closest one). */
    fun highWaterNear(events: List<TideEvent>, etaMs: Long): TideEvent? =
        events.filter { it.isHigh && kotlin.math.abs(it.timeMs - etaMs) <= HIGH_WATER_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.timeMs - etaMs) }

    /** The next high water within 2 h after [nowMs], if any. */
    fun nextHighWaterWithin2h(events: List<TideEvent>, nowMs: Long): TideEvent? =
        events.filter { it.isHigh && it.timeMs >= nowMs && it.timeMs - nowMs <= HIGH_WATER_WINDOW_MS }.minByOrNull { it.timeMs }

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Europe/Copenhagen") }.format(Date(ms))

    /** Rounded half away from zero, so −96.5 cm shows as −97 like +96.5 shows as +97. */
    private fun signedCm(v: Double): String {
        val r = (kotlin.math.abs(v) + 0.5).toInt() * (if (v < 0) -1 else 1)
        return if (r > 0) "+$r" else if (r < 0) "−${-r}" else "0"
    }

    /** " og " (Android strips spaces at the ends of string resources, so they are added here). */
    private fun and(context: Context?): String = " " + (AppLocaleHelper.strOrNull(context, com.example.R.string.tide_and) ?: "og").trim() + " "

    /** "Højvande 05:42 (+142 cm) og 18:03 (+120 cm) · Lavvande 11:58 (−98 cm)". */
    fun dayLine(events: List<TideEvent>, context: Context?): String {
        fun list(ev: List<TideEvent>) = ev.joinToString(and(context)) {
            "${clock(it.timeMs)} (${signedCm(it.levelCm)} cm)"
        }
        val parts = mutableListOf<String>()
        events.filter { it.isHigh }.takeIf { it.isNotEmpty() }?.let {
            parts.add(AppLocaleHelper.strOrNull(context, com.example.R.string.tide_high_water_fmt, list(it)) ?: "Højvande ${list(it)}")
        }
        events.filter { !it.isHigh }.takeIf { it.isNotEmpty() }?.let {
            parts.add(AppLocaleHelper.strOrNull(context, com.example.R.string.tide_low_water_fmt, list(it)) ?: "Lavvande ${list(it)}")
        }
        return parts.joinToString(" · ")
    }

    /** Times only, e.g. "05:42 og 18:03". */
    fun times(events: List<TideEvent>, context: Context?): String =
        events.joinToString(and(context)) { clock(it.timeMs) }
}
