package com.example.util

import android.content.Context

/** Result of evaluating an OSM `opening_hours` value at one moment. */
sealed class OpenState {
    /** Open now. [closesAt] is "HH:mm", or null when open around the clock (24/7). */
    data class Open(val closesAt: String?) : OpenState()

    /** Closed now. [opensAtToday] is the next opening "HH:mm" later the same day, if any. */
    data class Closed(val opensAtToday: String?) : OpenState()

    /** The value is missing or uses syntax this evaluator does not understand. Never guessed. */
    object Unknown : OpenState()
}

/**
 * Phase C3: a deliberately small evaluator for the common subset of OSM `opening_hours`:
 * `24/7`; rules separated by `;`; an optional month range prefix (`Apr-Oct`); weekday selectors
 * (`Mo-Fr`, `Sa,Su`, `Mo,We-Fr`); comma-separated time spans (`08:00-17:00`, also past midnight);
 * `off` / `closed`. Later rules override earlier ones for the same day. Rules starting with
 * `PH` or `SH` (public/school holidays) are ignored. Anything else gives [OpenState.Unknown].
 * Times are evaluated in Europe/Copenhagen.
 */
object OpeningHoursEvaluator {

    private val WEEKDAYS = mapOf("mo" to 1, "tu" to 2, "we" to 3, "th" to 4, "fr" to 5, "sa" to 6, "su" to 7)
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private val TIME_SPAN = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""")
    private val DAY_TOKEN = Regex("""^(mo|tu|we|th|fr|sa|su)(-(mo|tu|we|th|fr|sa|su))?$""")
    private val MONTH_TOKEN = Regex("""^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(-(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec))?$""")

    /** A span in minutes after midnight; [endMin] may exceed 1440 when it runs past midnight. */
    internal data class Span(val startMin: Int, val endMin: Int)

    /** months/weekdays null = every month/day. [spans] empty = closed. */
    internal data class Rule(val months: Set<Int>?, val weekdays: Set<Int>?, val spans: List<Span>)

    fun evaluate(openingHours: String?, atEpochMs: Long): OpenState {
        val raw = openingHours?.trim().orEmpty()
        if (raw.isEmpty()) return OpenState.Unknown
        if (raw == "24/7") return OpenState.Open(closesAt = null)
        val rules = parseRules(raw) ?: return OpenState.Unknown
        if (rules.isEmpty()) return OpenState.Unknown

        val now = CopenhagenTime.calendar(atEpochMs)
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val today = spansFor(rules, CopenhagenTime.month(now), CopenhagenTime.isoWeekday(now))
        val yesterdayDate = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val yesterday = spansFor(rules, CopenhagenTime.month(yesterdayDate), CopenhagenTime.isoWeekday(yesterdayDate))

        // Spans from yesterday that run past midnight into today
        yesterday.firstOrNull { it.endMin > 1440 && minuteOfDay < it.endMin - 1440 }?.let {
            return OpenState.Open(closesAt = formatMinutes(it.endMin - 1440))
        }
        today.firstOrNull { minuteOfDay >= it.startMin && minuteOfDay < it.endMin }?.let {
            return OpenState.Open(closesAt = formatMinutes(it.endMin % 1440))
        }
        val nextToday = today.filter { it.startMin > minuteOfDay }.minByOrNull { it.startMin }
        return OpenState.Closed(opensAtToday = nextToday?.let { formatMinutes(it.startMin) })
    }

    /** Danish-first display text (translated via resources when a context is available). */
    fun formatDanish(state: OpenState, context: Context? = null): String = when (state) {
        is OpenState.Open -> if (state.closesAt == null) {
            AppLocaleHelper.strOrNull(context, com.example.R.string.opening_hours_open_24_7) ?: "Åben døgnet rundt"
        } else {
            AppLocaleHelper.strOrNull(context, com.example.R.string.opening_hours_open_closes_fmt, state.closesAt)
                ?: "Åben · lukker ${state.closesAt}"
        }
        is OpenState.Closed -> if (state.opensAtToday != null) {
            AppLocaleHelper.strOrNull(context, com.example.R.string.opening_hours_closed_opens_fmt, state.opensAtToday)
                ?: "Lukket · åbner ${state.opensAtToday}"
        } else {
            AppLocaleHelper.strOrNull(context, com.example.R.string.opening_hours_closed) ?: "Lukket"
        }
        OpenState.Unknown -> AppLocaleHelper.strOrNull(context, com.example.R.string.opening_hours_unknown) ?: "Åbningstider ukendt"
    }

    private fun spansFor(rules: List<Rule>, month: Int, weekday: Int): List<Span> {
        var result: List<Span> = emptyList()
        for (r in rules) {
            if (r.months != null && month !in r.months) continue
            if (r.weekdays != null && weekday !in r.weekdays) continue
            result = r.spans
        }
        return result
    }

    /** Returns null when any rule uses unsupported syntax. */
    internal fun parseRules(raw: String): List<Rule>? {
        if (raw.contains("||") || raw.contains("\"")) return null
        val rules = mutableListOf<Rule>()
        for (part in raw.split(';')) {
            val ruleText = part.trim()
            if (ruleText.isEmpty()) continue
            val upper = ruleText.uppercase()
            if (upper.startsWith("PH") || upper.startsWith("SH")) continue
            rules.add(parseRule(ruleText) ?: return null)
        }
        return rules
    }

    private fun parseRule(text: String): Rule? {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null

        var months: Set<Int>? = null
        parseMonths(tokens.first())?.let { months = it; tokens.removeAt(0) }
        if (tokens.isEmpty()) return null

        var weekdays: Set<Int>? = null
        parseWeekdays(tokens.first())?.let { weekdays = it; tokens.removeAt(0) }
        if (tokens.size != 1) return null

        val last = tokens.single().lowercase()
        if (last == "off" || last == "closed") return Rule(months, weekdays, emptyList())
        val spans = last.split(',').map { parseSpan(it) ?: return null }
        return Rule(months, weekdays, spans)
    }

    private fun parseMonths(token: String): Set<Int>? {
        val result = mutableSetOf<Int>()
        for (piece in token.lowercase().split(',')) {
            val m = MONTH_TOKEN.matchEntire(piece) ?: return null
            val start = MONTHS.getValue(m.groupValues[1])
            val end = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { MONTHS.getValue(it) } ?: start
            result.addAll(cyclicRange(start, end, 12))
        }
        return result
    }

    private fun parseWeekdays(token: String): Set<Int>? {
        val result = mutableSetOf<Int>()
        for (piece in token.lowercase().split(',')) {
            val m = DAY_TOKEN.matchEntire(piece) ?: return null
            val start = WEEKDAYS.getValue(m.groupValues[1])
            val end = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { WEEKDAYS.getValue(it) } ?: start
            result.addAll(cyclicRange(start, end, 7))
        }
        return result
    }

    private fun cyclicRange(start: Int, end: Int, size: Int): List<Int> =
        if (start <= end) (start..end).toList() else (start..size).toList() + (1..end).toList()

    private fun parseSpan(text: String): Span? {
        val m = TIME_SPAN.matchEntire(text.trim()) ?: return null
        val (sh, sm, eh, em) = m.destructured
        val start = sh.toInt() * 60 + sm.toInt()
        var end = eh.toInt() * 60 + em.toInt()
        if (sh.toInt() > 24 || eh.toInt() > 48 || sm.toInt() > 59 || em.toInt() > 59 || start >= 1440) return null
        if (end <= start) end += 1440 // runs past midnight
        return Span(start, end)
    }

    private fun formatMinutes(min: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", (min / 60) % 24, min % 60)
}
