package com.example.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * On-device sunrise and sunset calculator using the NOAA Solar Calculation algorithm.
 * Returns local times in Europe/Copenhagen (DST aware) without requiring network access.
 * Also provides the shared arrivalClock calculation for NavigationScreen and NavigationTrackingService.
 */
@android.annotation.SuppressLint("NewApi")
object SunCalculator {

    val COPENHAGEN_ZONE: ZoneId = ZoneId.of("Europe/Copenhagen")
    const val DEFAULT_COPENHAGEN_LAT = 55.6761
    const val DEFAULT_COPENHAGEN_LNG = 12.5683
    private const val SOLAR_ZENITH_DEG = 90.833

    data class SunTimes(
        val sunrise: String,
        val sunset: String,
        val sunriseMinutesOfDay: Int,
        val sunsetMinutesOfDay: Int
    )

    /**
     * Shared arrival time calculation for NavigationScreen (top card + stats strip) and NavigationTrackingService notification.
     * arrival = current time + (km left today / effective pace)
     */
    fun computeArrivalClock(
        remainingTodayKm: Double,
        speedKmh: Double,
        configuredPaceKmh: Double,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val safePace = configuredPaceKmh.takeIf { it > 0.0 && !it.isNaN() } ?: 4.0
        val effectivePaceKmh = (if (speedKmh >= 1.5 && !speedKmh.isNaN()) speedKmh else safePace.coerceAtLeast(1.0))
            .coerceAtLeast(0.5)
        val safeRemainingKm = remainingTodayKm.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
        val durationMillis = ((safeRemainingKm / effectivePaceKmh) * 3_600_000.0).roundToLong()
        val arrivalZdt = Instant.ofEpochMilli(nowMillis + durationMillis).atZone(zoneId)
        return String.format(Locale.GERMANY, "%02d:%02d", arrivalZdt.hour, arrivalZdt.minute)
    }

    fun calculateSunrise(
        date: LocalDate = LocalDate.now(COPENHAGEN_ZONE),
        lat: Double = DEFAULT_COPENHAGEN_LAT,
        lng: Double = DEFAULT_COPENHAGEN_LNG,
        zoneId: ZoneId = COPENHAGEN_ZONE
    ): String = calculateSunTimes(date, lat, lng, zoneId).sunrise

    fun calculateSunset(
        date: LocalDate = LocalDate.now(COPENHAGEN_ZONE),
        lat: Double = DEFAULT_COPENHAGEN_LAT,
        lng: Double = DEFAULT_COPENHAGEN_LNG,
        zoneId: ZoneId = COPENHAGEN_ZONE
    ): String = calculateSunTimes(date, lat, lng, zoneId).sunset

    fun calculateSunset(
        epochMillis: Long,
        lat: Double = DEFAULT_COPENHAGEN_LAT,
        lng: Double = DEFAULT_COPENHAGEN_LNG,
        zoneId: ZoneId = COPENHAGEN_ZONE
    ): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return calculateSunset(date, lat, lng, zoneId)
    }

    fun calculateSunrise(
        epochMillis: Long,
        lat: Double = DEFAULT_COPENHAGEN_LAT,
        lng: Double = DEFAULT_COPENHAGEN_LNG,
        zoneId: ZoneId = COPENHAGEN_ZONE
    ): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return calculateSunrise(date, lat, lng, zoneId)
    }

    fun calculateSunTimes(
        date: LocalDate = LocalDate.now(COPENHAGEN_ZONE),
        lat: Double = DEFAULT_COPENHAGEN_LAT,
        lng: Double = DEFAULT_COPENHAGEN_LNG,
        zoneId: ZoneId = COPENHAGEN_ZONE
    ): SunTimes {
        val safeLat = lat.coerceIn(-89.8, 89.8)
        val safeLng = lng.coerceIn(-180.0, 180.0)
        val jd0 = julianDayAtMidnightUtc(date.year, date.monthValue, date.dayOfMonth)

        // First pass at 12:00 UTC (JD + 0.5)
        val tNoon = julianCentury(jd0 + 0.5)
        val (eqTimeNoon, declNoon) = solarPosition(tNoon)
        val haNoon = hourAngleDeg(safeLat, declNoon, SOLAR_ZENITH_DEG)

        val sunriseUtcMin1 = 720.0 - 4.0 * (safeLng + haNoon) - eqTimeNoon
        val sunsetUtcMin1 = 720.0 - 4.0 * (safeLng - haNoon) - eqTimeNoon

        // Second pass refined at the estimated sunrise and sunset UTC instants
        val tSunrise = julianCentury(jd0 + sunriseUtcMin1 / 1440.0)
        val (eqTimeRise, declRise) = solarPosition(tSunrise)
        val haRise = hourAngleDeg(safeLat, declRise, SOLAR_ZENITH_DEG)
        val sunriseUtcMin = 720.0 - 4.0 * (safeLng + haRise) - eqTimeRise

        val tSunset = julianCentury(jd0 + sunsetUtcMin1 / 1440.0)
        val (eqTimeSet, declSet) = solarPosition(tSunset)
        val haSet = hourAngleDeg(safeLat, declSet, SOLAR_ZENITH_DEG)
        val sunsetUtcMin = 720.0 - 4.0 * (safeLng - haSet) - eqTimeSet

        val offsetMinutes = zoneOffsetMinutes(date, zoneId)
        val sunriseLocalMin = (((sunriseUtcMin + offsetMinutes).roundToInt()) % 1440 + 1440) % 1440
        val sunsetLocalMin = (((sunsetUtcMin + offsetMinutes).roundToInt()) % 1440 + 1440) % 1440

        val sunriseStr = String.format(Locale.GERMANY, "%02d:%02d", sunriseLocalMin / 60, sunriseLocalMin % 60)
        val sunsetStr = String.format(Locale.GERMANY, "%02d:%02d", sunsetLocalMin / 60, sunsetLocalMin % 60)

        return SunTimes(
            sunrise = sunriseStr,
            sunset = sunsetStr,
            sunriseMinutesOfDay = sunriseLocalMin,
            sunsetMinutesOfDay = sunsetLocalMin
        )
    }

    private fun zoneOffsetMinutes(date: LocalDate, zoneId: ZoneId): Double {
        val noonLocal = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0, 0, 0, zoneId)
        return noonLocal.offset.totalSeconds / 60.0
    }

    private fun julianDayAtMidnightUtc(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun julianCentury(jd: Double): Double {
        return (jd - 2451545.0) / 36525.0
    }

    /**
     * Returns Pair(equationOfTimeMinutes, solarDeclinationRad) for Julian century T.
     */
    private fun solarPosition(t: Double): Pair<Double, Double> {
        val l0Deg = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0 + 360.0) % 360.0
        val mDeg = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val mRad = Math.toRadians(mDeg)
        val l0Rad = Math.toRadians(l0Deg)

        val sinM = sin(mRad)
        val sin2M = sin(2.0 * mRad)
        val sin3M = sin(3.0 * mRad)
        val eqCenterDeg = sinM * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin2M * (0.019993 - 0.000101 * t) +
            sin3M * 0.000289

        val trueLongDeg = l0Deg + eqCenterDeg
        val omegaDeg = 125.04 - 1934.136 * t
        val omegaRad = Math.toRadians(omegaDeg)
        val apparentLongDeg = trueLongDeg - 0.00569 - 0.00478 * sin(omegaRad)
        val apparentLongRad = Math.toRadians(apparentLongDeg)

        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val meanObliqDeg = 23.0 + (26.0 + (seconds / 60.0)) / 60.0
        val obliqCorrDeg = meanObliqDeg + 0.00256 * cos(omegaRad)
        val obliqCorrRad = Math.toRadians(obliqCorrDeg)

        val declRad = asin(sin(obliqCorrRad) * sin(apparentLongRad))

        val y = tan(obliqCorrRad / 2.0) * tan(obliqCorrRad / 2.0)
        val eqTimeRad = y * sin(2.0 * l0Rad) -
            2.0 * e * sinM +
            4.0 * e * y * sinM * cos(2.0 * l0Rad) -
            0.5 * y * y * sin(4.0 * l0Rad) -
            1.25 * e * e * sin2M
        val eqTimeMinutes = 4.0 * Math.toDegrees(eqTimeRad)

        return Pair(eqTimeMinutes, declRad)
    }

    private fun hourAngleDeg(latDeg: Double, declRad: Double, zenithDeg: Double): Double {
        val latRad = Math.toRadians(latDeg)
        val zenithRad = Math.toRadians(zenithDeg)
        val cosHa = (cos(zenithRad) / (cos(latRad) * cos(declRad))) - tan(latRad) * tan(declRad)
        val clamped = cosHa.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clamped))
    }
}
