package com.example.util

import java.util.Calendar
import java.util.TimeZone

/**
 * Europe/Copenhagen calendar fields via java.util.Calendar, which (unlike java.time) works on
 * the app's minSdk 24 without core library desugaring.
 */
object CopenhagenTime {
    private val TZ: TimeZone = TimeZone.getTimeZone("Europe/Copenhagen")

    fun calendar(epochMs: Long): Calendar = Calendar.getInstance(TZ).apply { timeInMillis = epochMs }

    /** ISO weekday: Monday = 1 … Sunday = 7. */
    fun isoWeekday(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    /** 1-based month. */
    fun month(cal: Calendar): Int = cal.get(Calendar.MONTH) + 1

    /** Epoch ms of local midnight [dayOffset] days after the Copenhagen day containing [epochMs], plus [minutes]. */
    fun localMidnightPlus(epochMs: Long, dayOffset: Int, minutes: Int): Long = calendar(epochMs).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, dayOffset)
        add(Calendar.MINUTE, minutes)
    }.timeInMillis
}
