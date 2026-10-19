package com.example.util

import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GpxImportResult(
    val name: String?,
    val originalPoints: List<RoutePoint3D>,
    val waypoints: List<RouteWaypoint>,
    val totalOriginalKm: Double
)

object GpxImporter {

    /**
     * Parses a GPX stream, extracting original track points and simplifying to ~1 point per 500 m.
     */
    fun parseAndSimplifyGpx(inputStream: InputStream, targetSpacingMeters: Double = 500.0): GpxImportResult {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val rawPoints = mutableListOf<RoutePoint3D>()
        var routeName: String? = null
        var inTrk = false
        var inName = false
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "trk" -> inTrk = true
                        "name" -> {
                            if (routeName == null) inName = true
                        }
                        "trkpt" -> {
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentEle = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        if (currentTag == "name" && inName && routeName == null) {
                            routeName = text
                        } else if (currentTag == "ele" && currentLat != null && currentLon != null) {
                            currentEle = text.toDoubleOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "trk" -> inTrk = false
                        "name" -> inName = false
                        "trkpt" -> {
                            if (currentLat != null && currentLon != null) {
                                rawPoints.add(RoutePoint3D(currentLat, currentLon, currentEle))
                            }
                            currentLat = null
                            currentLon = null
                            currentEle = null
                        }
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }

        if (rawPoints.isEmpty()) {
            return GpxImportResult(routeName, emptyList(), emptyList(), 0.0)
        }

        // Calculate total original length
        var totalDistM = 0.0
        for (i in 0 until rawPoints.size - 1) {
            val p1 = rawPoints[i]
            val p2 = rawPoints[i + 1]
            totalDistM += haversineMeters(p1.lat, p1.lng, p2.lat, p2.lng)
        }

        // Simplify track to ~1 waypoint every 500 meters
        val waypoints = mutableListOf<RouteWaypoint>()
        val first = rawPoints.first()
        waypoints.add(
            RouteWaypoint(
                lat = first.lat,
                lng = first.lng,
                role = WaypointRole.START,
                legModeToNext = LegMode.SNAP,
                name = "Start"
            )
        )

        var accumulatedM = 0.0
        for (i in 1 until rawPoints.size - 1) {
            val prev = rawPoints[i - 1]
            val curr = rawPoints[i]
            accumulatedM += haversineMeters(prev.lat, prev.lng, curr.lat, curr.lng)

            if (accumulatedM >= targetSpacingMeters) {
                waypoints.add(
                    RouteWaypoint(
                        lat = curr.lat,
                        lng = curr.lng,
                        role = WaypointRole.VIA,
                        legModeToNext = LegMode.SNAP,
                        name = "Via ${waypoints.size}"
                    )
                )
                accumulatedM = 0.0
            }
        }

        val last = rawPoints.last()
        waypoints.add(
            RouteWaypoint(
                lat = last.lat,
                lng = last.lng,
                role = WaypointRole.END,
                legModeToNext = LegMode.SNAP,
                name = "Slut"
            )
        )

        return GpxImportResult(
            name = routeName,
            originalPoints = rawPoints,
            waypoints = waypoints,
            totalOriginalKm = totalDistM / 1000.0
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
