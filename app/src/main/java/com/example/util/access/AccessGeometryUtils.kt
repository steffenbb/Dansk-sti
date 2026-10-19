package com.example.util.access

import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class AccessWarningType {
    STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD,
    BEFORE_06_OR_AFTER_SUNSET,
    OVERNIGHT_IN_PRIVATE_FOREST
}

data class RouteAccessWarning(
    val id: String = UUID.randomUUID().toString(),
    val type: AccessWarningType,
    val title: String,
    val message: String,
    val polygon: AccessPolygon?,
    val location: LatLng,
    val legIndex: Int? = null,
    val canRerouteAround: Boolean = true
)

object AccessGeometryUtils {

    /**
     * Ray-casting algorithm to test if a point is inside a polygon.
     */
    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].lng
            val yi = polygon[i].lat
            val xj = polygon[j].lng
            val yj = polygon[j].lat

            val intersect = ((yi > point.lat) != (yj > point.lat)) &&
                    (point.lng < (xj - xi) * (point.lat - yi) / (yj - yi) + xi)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Finds which access polygon contains the point, if any.
     */
    fun findContainingPolygon(point: LatLng, polygons: List<AccessPolygon>): AccessPolygon? {
        for (poly in polygons) {
            if (isPointInPolygon(point, poly.coordinates)) {
                return poly
            }
        }
        return null
    }

    /**
     * Tests if a straight line segment between [from] and [to] passes through restricted land
     * (Private forest or cultivated fields).
     */
    fun checkStraightSegmentRestricted(
        from: LatLng,
        to: LatLng,
        polygons: List<AccessPolygon>
    ): AccessPolygon? {
        val distM = haversineM(from.lat, from.lng, to.lat, to.lng)
        // Sample points every ~50m along segment
        val steps = max(2, (distM / 50.0).roundToInt())
        for (step in 0..steps) {
            val fraction = step.toDouble() / steps.toDouble()
            val sampleLat = from.lat + fraction * (to.lat - from.lat)
            val sampleLng = from.lng + fraction * (to.lng - from.lng)
            val pt = LatLng(sampleLat, sampleLng)

            for (poly in polygons) {
                if (poly.type == AccessLandType.PRIVATE_FOREST || poly.type == AccessLandType.CULTIVATED_FIELD) {
                    if (isPointInPolygon(pt, poly.coordinates)) {
                        return poly
                    }
                }
            }
        }
        return null
    }

    /**
     * Parses a "HH:MM" string into minutes from midnight (0..1439).
     */
    fun parseTimeToMinutes(timeStr: String?): Int {
        if (timeStr.isNullOrBlank()) return 9 * 60 // Default 09:00
        val parts = timeStr.trim().split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val mins = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + mins).coerceIn(0, 1439)
    }

    fun formatMinutesToTime(totalMins: Int): String {
        val normalized = (totalMins % 1440 + 1440) % 1440
        val h = normalized / 60
        val m = normalized % 60
        return String.format("%02d:%02d", h, m)
    }

    /**
     * Evaluates route against Danish access rules:
     * 1. Straight legs crossing private forest or fields ("Kun på veje og stier her").
     * 2. Any point in private forest or uncultivated land reached before 06:00 or after sunset that day.
     * 3. Overnight points in private forest not at designated shelter/fri teltning ("Ikke tilladt at overnatte her").
     */
    fun evaluateRouteAccess(
        waypoints: List<RouteWaypoint>,
        legs: Map<String, LegGeometryData>,
        accessPolygons: List<AccessPolygon>,
        startTimeStr: String = "09:00",
        pacingSpeedKmh: Double = 4.5,
        sunsetTimeStr: String = "20:30"
    ): List<RouteAccessWarning> {
        val warnings = mutableListOf<RouteAccessWarning>()
        if (waypoints.isEmpty()) return warnings

        val startMinutes = parseTimeToMinutes(startTimeStr)
        val sunsetMinutes = parseTimeToMinutes(sunsetTimeStr)
        val morningAllowedMinutes = 6 * 60 // 06:00
        val speed = if (pacingSpeedKmh > 0.5) pacingSpeedKmh else 4.5

        var cumulativeDistKm = 0.0
        var cumulativeAscentM = 0.0

        for (i in 0 until waypoints.size - 1) {
            val wpFrom = waypoints[i]
            val wpTo = waypoints[i + 1]
            val legKey = "${wpFrom.id}_to_${wpTo.id}"
            val legData = legs[legKey]

            // 1. Check STRAIGHT leg crossing private forest or fields
            if (wpFrom.legModeToNext == LegMode.STRAIGHT) {
                val fromCoord = LatLng(wpFrom.lat, wpFrom.lng)
                val toCoord = LatLng(wpTo.lat, wpTo.lng)
                val restrictedPoly = checkStraightSegmentRestricted(fromCoord, toCoord, accessPolygons)
                if (restrictedPoly != null) {
                    val landName = restrictedPoly.name ?: restrictedPoly.type.title
                    warnings.add(
                        RouteAccessWarning(
                            type = AccessWarningType.STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD,
                            title = "Kun på veje og stier her",
                            message = "Lige linje krydser $landName. I privat skov og på dyrkede marker må du kun færdes på anlagte veje og stier.",
                            polygon = restrictedPoly,
                            location = LatLng((wpFrom.lat + wpTo.lat) / 2.0, (wpFrom.lng + wpTo.lng) / 2.0),
                            legIndex = i,
                            canRerouteAround = true
                        )
                    )
                }
            }

            // 2. Walk points along leg with time ETA
            val points = legData?.points ?: listOf(RoutePoint3D(wpFrom.lat, wpFrom.lng), RoutePoint3D(wpTo.lat, wpTo.lng))
            for (pIdx in points.indices) {
                if (pIdx > 0) {
                    val prev = points[pIdx - 1]
                    val curr = points[pIdx]
                    val dM = haversineM(prev.lat, prev.lng, curr.lat, curr.lng)
                    cumulativeDistKm += dM / 1000.0
                    val eleDiff = (curr.ele ?: 0.0) - (prev.ele ?: 0.0)
                    if (eleDiff > 0) cumulativeAscentM += eleDiff
                }

                val travelHours = (cumulativeDistKm / speed) + (cumulativeAscentM / 10.0 / 60.0)
                val etaMinutes = startMinutes + (travelHours * 60).roundToInt()

                val ptCoord = LatLng(points[pIdx].lat, points[pIdx].lng)
                val poly = findContainingPolygon(ptCoord, accessPolygons)

                if (poly != null && (poly.type == AccessLandType.PRIVATE_FOREST || poly.type == AccessLandType.UNCULTIVATED_LAND)) {
                    val etaModDay = etaMinutes % 1440
                    val isTooEarly = etaModDay < morningAllowedMinutes
                    val isAfterSunset = etaModDay > sunsetMinutes

                    if (isTooEarly || isAfterSunset) {
                        // Avoid duplicate warnings for same polygon
                        val alreadyWarned = warnings.any { it.type == AccessWarningType.BEFORE_06_OR_AFTER_SUNSET && it.polygon?.id == poly.id }
                        if (!alreadyWarned) {
                            val timeDesc = if (isAfterSunset) {
                                "efter solnedgang (ankomst kl. ${formatMinutesToTime(etaMinutes)}, solnedgang kl. ${formatMinutesToTime(sunsetMinutes)})"
                            } else {
                                "før kl. 06:00 (ankomst kl. ${formatMinutesToTime(etaMinutes)})"
                            }
                            warnings.add(
                                RouteAccessWarning(
                                    type = AccessWarningType.BEFORE_06_OR_AFTER_SUNSET,
                                    title = "Lukket ved solnedgang",
                                    message = "${poly.name ?: poly.type.title} er nået $timeDesc. I privat skov og udyrkede arealer er adgang kun tilladt fra 06:00 til solnedgang.",
                                    polygon = poly,
                                    location = ptCoord,
                                    legIndex = i,
                                    canRerouteAround = true
                                )
                            )
                        }
                    }
                }
            }

            // 3. Check OVERNIGHT point in private forest
            if (wpFrom.role == WaypointRole.OVERNIGHT) {
                val ptCoord = LatLng(wpFrom.lat, wpFrom.lng)
                val poly = findContainingPolygon(ptCoord, accessPolygons)
                if (poly != null && poly.type == AccessLandType.PRIVATE_FOREST) {
                    val hasDesignatedCamp = !wpFrom.linkedPoiId.isNullOrBlank()
                    if (!hasDesignatedCamp) {
                        warnings.add(
                            RouteAccessWarning(
                                type = AccessWarningType.OVERNIGHT_IN_PRIVATE_FOREST,
                                title = "Ikke tilladt at overnatte her",
                                message = "Overnatningspunktet ligger i privat skov (${poly.name ?: "Privat skov"}). Allemandsretten tillader ikke teltning eller primitiv overnatning i privat skov uden ejerens tilladelse.",
                                polygon = poly,
                                location = ptCoord,
                                legIndex = i,
                                canRerouteAround = false
                            )
                        )
                    }
                }
            }
        }

        // Check last waypoint if it is an OVERNIGHT point
        val lastWp = waypoints.last()
        if (lastWp.role == WaypointRole.OVERNIGHT) {
            val ptCoord = LatLng(lastWp.lat, lastWp.lng)
            val poly = findContainingPolygon(ptCoord, accessPolygons)
            if (poly != null && poly.type == AccessLandType.PRIVATE_FOREST) {
                val hasDesignatedCamp = !lastWp.linkedPoiId.isNullOrBlank()
                if (!hasDesignatedCamp) {
                    warnings.add(
                        RouteAccessWarning(
                            type = AccessWarningType.OVERNIGHT_IN_PRIVATE_FOREST,
                            title = "Ikke tilladt at overnatte her",
                            message = "Slutovernatningen ligger i privat skov (${poly.name ?: "Privat skov"}). Allemandsretten tillader ikke teltning eller primitiv overnatning i privat skov uden ejerens tilladelse.",
                            polygon = poly,
                            location = ptCoord,
                            legIndex = waypoints.size - 1,
                            canRerouteAround = false
                        )
                    )
                }
            }
        }

        return warnings
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
