package com.example.util

import java.time.Instant
import java.time.ZoneId

data class DateRange(
    val startMonth: Int,
    val startDay: Int,
    val endMonth: Int,
    val endDay: Int,
    val endDayWasExplicit: Boolean = true
)

data class ClosureRule(
    val dateRange: DateRange?,
    val timeText: String?,
    val raw: String,
    val isUnparseable: Boolean = false
) {
    fun isClosedOn(epochMs: Long): Boolean {
        val dr = dateRange ?: return false
        val zdt = Instant.ofEpochMilli(epochMs).atZone(COPENHAGEN_ZONE)
        val month = zdt.monthValue
        val day = zdt.dayOfMonth
        val targetVal = month * 100 + day
        val startVal = dr.startMonth * 100 + dr.startDay
        val endVal = dr.endMonth * 100 + dr.endDay
        return if (startVal <= endVal) {
            targetVal in startVal..endVal
        } else {
            // Range crosses new year (e.g. Nov 1 - Feb 28)
            targetVal >= startVal || targetVal <= endVal
        }
    }

    fun displayPeriod(locale: String = "da"): String {
        val dr = dateRange
        if (dr != null) {
            val startMonthName = monthAbbrev(dr.startMonth, locale)
            val endMonthName = monthAbbrev(dr.endMonth, locale)
            return "${dr.startDay}. $startMonthName – ${dr.endDay}. $endMonthName"
        }
        if (!timeText.isNullOrBlank()) {
            return timeText
        }
        return raw
    }

    companion object {
        private val COPENHAGEN_ZONE: ZoneId = ZoneId.of("Europe/Copenhagen")

        private val DA_MONTHS = arrayOf(
            "", "jan", "feb", "mar", "apr", "maj", "jun",
            "jul", "aug", "sep", "okt", "nov", "dec"
        )
        private val EN_MONTHS = arrayOf(
            "", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        private val DE_MONTHS = arrayOf(
            "", "Jan.", "Feb.", "März", "Apr.", "Mai", "Juni",
            "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez."
        )

        private fun monthAbbrev(month: Int, locale: String): String {
            if (month !in 1..12) return ""
            return when {
                locale.startsWith("en", ignoreCase = true) -> EN_MONTHS[month]
                locale.startsWith("de", ignoreCase = true) -> DE_MONTHS[month]
                else -> DA_MONTHS[month]
            }
        }
    }
}

object ConditionalAccessParser {

    private val MONTH_MAP = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5, "maj" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "okt" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12
    )

    private val DAYS_IN_MONTH = intArrayOf(0, 31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    // Matches e.g. "Mar 01 - Jul 15", "Apr 1-Aug 31", "Mar-Jul", "01 Mar - 15 Jul"
    private val DATE_RANGE_REGEX = Regex(
        """^([A-Za-z]{3,9})(?:\s+(\d{1,2}))?\s*[-–]\s*([A-Za-z]{3,9})(?:\s+(\d{1,2}))?$""",
        RegexOption.IGNORE_CASE
    )
    private val DAY_FIRST_DATE_RANGE_REGEX = Regex(
        """^(\d{1,2})\.?\s*([A-Za-z]{3,9})\s*[-–]\s*(\d{1,2})\.?\s*([A-Za-z]{3,9})$""",
        RegexOption.IGNORE_CASE
    )

    // Matches "sunset-sunrise", "dusk-dawn", or clock range like "21:00-06:00" or "22:00-05:00"
    private val SUNSET_SUNRISE_REGEX = Regex(
        """^(sunset|dusk)\s*[-–]\s*(sunrise|dawn)$""",
        RegexOption.IGNORE_CASE
    )
    private val CLOCK_RANGE_REGEX = Regex(
        """^(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})$"""
    )

    fun parse(rawConditional: String?): List<ClosureRule> {
        if (rawConditional.isNullOrBlank()) return emptyList()
        val parts = splitTopLevelSemicolons(rawConditional)
        val results = mutableListOf<ClosureRule>()

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val atIdx = trimmed.indexOf('@')
            if (atIdx <= 0) continue

            val accessValue = trimmed.substring(0, atIdx).trim().lowercase()
            if (accessValue != "no" && accessValue != "private") {
                // Only 'no' and 'private' count as closed
                continue
            }

            var condPart = trimmed.substring(atIdx + 1).trim()
            if (condPart.startsWith("(") && condPart.endsWith(")") && hasMatchingOuterParens(condPart)) {
                condPart = condPart.substring(1, condPart.length - 1).trim()
            }
            if (condPart.isEmpty()) continue

            val parsedRule = parseConditionExpression(condPart, trimmed)
            results.add(parsedRule)
        }

        return results
    }

    private fun parseConditionExpression(cond: String, fullRaw: String): ClosureRule {
        val clean = cond.trim()

        // 1. Check sunset-sunrise
        if (SUNSET_SUNRISE_REGEX.matches(clean)) {
            val normalized = clean.replace(Regex("""\s*[-–]\s*"""), "–").lowercase()
            return ClosureRule(
                dateRange = null,
                timeText = normalized,
                raw = clean
            )
        }

        // 2. Check clock range (e.g. 21:00-06:00)
        val clockMatch = CLOCK_RANGE_REGEX.matchEntire(clean)
        if (clockMatch != null) {
            val t1 = clockMatch.groupValues[1]
            val t2 = clockMatch.groupValues[2]
            return ClosureRule(
                dateRange = null,
                timeText = "$t1–$t2",
                raw = clean
            )
        }

        // 3. Check Month [Day] - Month [Day]
        val dateMatch = DATE_RANGE_REGEX.matchEntire(clean)
        if (dateMatch != null) {
            val m1Str = dateMatch.groupValues[1].lowercase()
            val d1Str = dateMatch.groupValues[2]
            val m2Str = dateMatch.groupValues[3].lowercase()
            val d2Str = dateMatch.groupValues[4]
            val m1 = MONTH_MAP[m1Str]
            val m2 = MONTH_MAP[m2Str]
            if (m1 != null && m2 != null) {
                val d1 = d1Str.toIntOrNull()?.coerceIn(1, DAYS_IN_MONTH[m1]) ?: 1
                val hasExplicitEndDay = d2Str.isNotBlank()
                val d2 = d2Str.toIntOrNull()?.coerceIn(1, DAYS_IN_MONTH[m2]) ?: DAYS_IN_MONTH[m2]
                return ClosureRule(
                    dateRange = DateRange(
                        startMonth = m1,
                        startDay = d1,
                        endMonth = m2,
                        endDay = d2,
                        endDayWasExplicit = hasExplicitEndDay
                    ),
                    timeText = null,
                    raw = clean
                )
            }
        }

        // 4. Check Day Month - Day Month
        val dayFirstMatch = DAY_FIRST_DATE_RANGE_REGEX.matchEntire(clean)
        if (dayFirstMatch != null) {
            val d1Str = dayFirstMatch.groupValues[1]
            val m1Str = dayFirstMatch.groupValues[2].lowercase()
            val d2Str = dayFirstMatch.groupValues[3]
            val m2Str = dayFirstMatch.groupValues[4].lowercase()
            val m1 = MONTH_MAP[m1Str]
            val m2 = MONTH_MAP[m2Str]
            if (m1 != null && m2 != null) {
                val d1 = d1Str.toIntOrNull()?.coerceIn(1, DAYS_IN_MONTH[m1]) ?: 1
                val d2 = d2Str.toIntOrNull()?.coerceIn(1, DAYS_IN_MONTH[m2]) ?: DAYS_IN_MONTH[m2]
                return ClosureRule(
                    dateRange = DateRange(
                        startMonth = m1,
                        startDay = d1,
                        endMonth = m2,
                        endDay = d2,
                        endDayWasExplicit = true
                    ),
                    timeText = null,
                    raw = clean
                )
            }
        }

        // 5. Unparseable closed rule: keep raw condition text
        return ClosureRule(
            dateRange = null,
            timeText = null,
            raw = clean.ifBlank { fullRaw },
            isUnparseable = true
        )
    }

    private fun hasMatchingOuterParens(s: String): Boolean {
        if (!s.startsWith("(") || !s.endsWith(")")) return false
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i < s.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private fun splitTopLevelSemicolons(input: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in input) {
            when (ch) {
                '(' -> {
                    depth++
                    current.append(ch)
                }
                ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(ch)
                }
                ';' -> {
                    if (depth == 0) {
                        result.add(current.toString())
                        current.setLength(0)
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }
}
