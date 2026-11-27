package com.example.util

import com.example.data.model.GeoPointDto
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class WindRelation { HEADWIND, TAILWIND, CROSSWIND, NONE }

/** Phase D6: wind relative to the walking direction (pure Kotlin). */
object WindAssessment {
    const val MIN_SPEED_MS = 5.0

    /**
     * [windFromDeg] is the direction the wind comes FROM (meteorological convention); walking towards
     * it is headwind. ≤ 45° → HEADWIND, ≥ 135° → TAILWIND, otherwise CROSSWIND; NONE below 5 m/s.
     */
    fun assess(windFromDeg: Int, travelBearingDeg: Double, speedMs: Double): WindRelation {
        if (speedMs < MIN_SPEED_MS) return WindRelation.NONE
        val diff = abs(((windFromDeg - travelBearingDeg) % 360.0 + 540.0) % 360.0 - 180.0)
        return when {
            diff <= 45.0 -> WindRelation.HEADWIND
            diff >= 135.0 -> WindRelation.TAILWIND
            else -> WindRelation.CROSSWIND
        }
    }

    private val COMPASS = listOf("N", "NØ", "Ø", "SØ", "S", "SV", "V", "NV")

    /** Danish 8-point compass label. */
    fun compassDanish(deg: Int): String = COMPASS[(((deg % 360) + 360) % 360 + 22) / 45 % 8]

    /** Initial bearing from a to b in degrees 0..360. */
    fun bearing(a: GeoPointDto, b: GeoPointDto): Double {
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Bearing from the point [distanceM] back along [recentPoints] (oldest first) to the newest point;
     * null when the track is shorter than that.
     */
    fun recentBearing(recentPoints: List<GeoPointDto>, distanceM: Double = 200.0): Double? {
        if (recentPoints.size < 2) return null
        var walked = 0.0
        for (i in recentPoints.size - 1 downTo 1) {
            walked += GeoUtils.distanceMeters(recentPoints[i - 1].lat, recentPoints[i - 1].lng, recentPoints[i].lat, recentPoints[i].lng)
            if (walked >= distanceM) return bearing(recentPoints[i - 1], recentPoints.last())
        }
        return null
    }

    /**
     * The length-weighted vector average of the bearings of 1 km pieces of the stretch. Null when
     * the stretch loops back on itself (resultant shorter than 30 % of the total length).
     */
    fun dayTravelBearing(points: List<GeoPointDto>, pieceKm: Double = 1.0): Double? {
        if (points.size < 2) return null
        var sumX = 0.0
        var sumY = 0.0
        var total = 0.0
        var pieceStart = points[0]
        var pieceLen = 0.0
        fun closePiece(end: GeoPointDto) {
            if (pieceLen <= 0.0) return
            val br = Math.toRadians(bearing(pieceStart, end))
            sumX += sin(br) * pieceLen
            sumY += cos(br) * pieceLen
            total += pieceLen
        }
        for (i in 1 until points.size) {
            pieceLen += GeoUtils.distanceMeters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng) / 1000.0
            if (pieceLen >= pieceKm) {
                closePiece(points[i])
                pieceStart = points[i]
                pieceLen = 0.0
            }
        }
        closePiece(points.last())
        if (total <= 0.0) return null
        if (hypot(sumX, sumY) < 0.3 * total) return null
        return (Math.toDegrees(atan2(sumX, sumY)) + 360.0) % 360.0
    }
}
