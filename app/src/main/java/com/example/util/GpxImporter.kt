package com.example.util

import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TurnCueGenerator
import com.example.util.routing.WaypointRole
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GpxImportResult(
    val name: String?,
    val originalPoints: List<RoutePoint3D>,
    val waypoints: List<RouteWaypoint>,
    val totalOriginalKm: Double,
    val waypointSourceIndices: List<Int> = emptyList()
)

object GpxImporter {

    const val MAX_VIA_POINTS = 60

    /**
     * Round 10B Item 13:
     * Parses a GPX stream, extracting original track/route points and simplifying with
     * Douglas-Peucker to at most about 60 via points while keeping turns and junctions.
     */
    fun parseAndSimplifyGpx(
        inputStream: InputStream,
        targetSpacingMeters: Double = 500.0,
        maxViaPoints: Int = MAX_VIA_POINTS
    ): GpxImportResult {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val rawPoints = mutableListOf<RoutePoint3D>()
        var routeName: String? = null
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
                        "name" -> {
                            if (routeName == null && currentLat == null) inName = true
                        }
                        "trkpt", "rtept" -> {
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
                        "name" -> inName = false
                        "trkpt", "rtept" -> {
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
            return GpxImportResult(routeName, emptyList(), emptyList(), 0.0, emptyList())
        }

        var totalDistM = 0.0
        for (i in 0 until rawPoints.size - 1) {
            val p1 = rawPoints[i]
            val p2 = rawPoints[i + 1]
            totalDistM += haversineMeters(p1.lat, p1.lng, p2.lat, p2.lng)
        }

        val keptIndices = simplifyDouglasPeuckerWithTurns(
            points = rawPoints,
            maxViaPoints = maxViaPoints,
            minEpsilonMeters = (targetSpacingMeters * 0.04).coerceIn(10.0, 35.0),
            targetSpacingMeters = targetSpacingMeters
        )

        val waypoints = mutableListOf<RouteWaypoint>()
        for ((idxInKept, rawIdx) in keptIndices.withIndex()) {
            val pt = rawPoints[rawIdx]
            val role = when (idxInKept) {
                0 -> WaypointRole.START
                keptIndices.lastIndex -> WaypointRole.END
                else -> WaypointRole.VIA
            }
            val name = when (role) {
                WaypointRole.START -> "Start"
                WaypointRole.END -> "Slut"
                else -> "Via $idxInKept"
            }
            waypoints.add(
                RouteWaypoint(
                    lat = pt.lat,
                    lng = pt.lng,
                    role = role,
                    legModeToNext = LegMode.SNAP,
                    name = name
                )
            )
        }

        return GpxImportResult(
            name = routeName,
            originalPoints = rawPoints,
            waypoints = waypoints,
            totalOriginalKm = totalDistM / 1000.0,
            waypointSourceIndices = keptIndices
        )
    }

    /**
     * Simplifies [points] using Douglas-Peucker combined with turn/junction angle preservation
     * so that the number of interior via points is at most [maxViaPoints] (total points <= maxViaPoints + 2).
     * Returns sorted indices into [points], always starting with 0 and ending with `points.lastIndex`.
     */
    fun simplifyDouglasPeuckerWithTurns(
        points: List<RoutePoint3D>,
        maxViaPoints: Int = MAX_VIA_POINTS,
        minEpsilonMeters: Double = 20.0,
        targetSpacingMeters: Double = 3500.0
    ): List<Int> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(0)
        if (points.size == 2) return listOf(0, 1)

        val effectiveMaxVia = maxViaPoints.coerceAtLeast(1)
        return runIterativeDouglasPeucker(
            points = points,
            maxInteriorPoints = effectiveMaxVia,
            epsilonMeters = minEpsilonMeters,
            targetSpacingMeters = targetSpacingMeters
        )
    }

    private fun runIterativeDouglasPeucker(
        points: List<RoutePoint3D>,
        maxInteriorPoints: Int,
        epsilonMeters: Double,
        targetSpacingMeters: Double = 3500.0
    ): List<Int> {
        val n = points.size
        if (n <= 2) return (0 until n).toList()

        // Precompute turn angles (in degrees, 0..180) at each interior point to boost turns & junctions.
        val turnAngles = DoubleArray(n)
        for (i in 1 until n - 1) {
            val prevIdx = findNeighborAtDistance(points, i, step = -1, targetDistMeters = 35.0)
            val nextIdx = findNeighborAtDistance(points, i, step = 1, targetDistMeters = 35.0)
            val b1 = bearingDeg(points[prevIdx].lat, points[prevIdx].lng, points[i].lat, points[i].lng)
            val b2 = bearingDeg(points[i].lat, points[i].lng, points[nextIdx].lat, points[nextIdx].lng)
            turnAngles[i] = abs(normalizeAngleDeg(b2 - b1))
        }

        data class SegmentSplitCandidate(
            val startIdx: Int,
            val endIdx: Int,
            val splitIdx: Int,
            val maxPerpMeters: Double,
            val weightedScore: Double
        )

        fun evaluateSegment(startIdx: Int, endIdx: Int): SegmentSplitCandidate? {
            if (endIdx - startIdx <= 1) return null
            val a = points[startIdx]
            val b = points[endIdx]
            var bestIdx = -1
            var bestPerp = -1.0
            var bestWeighted = -1.0

            for (i in (startIdx + 1) until endIdx) {
                val p = points[i]
                val perpM = perpendicularDistanceMeters(p.lat, p.lng, a.lat, a.lng, b.lat, b.lng)
                // Boost sharp turns and junctions (>= 35 deg) so corners are preserved first
                val turnDeg = turnAngles[i]
                val turnBoost = if (turnDeg >= 35.0) {
                    1.0 + (turnDeg / 45.0)
                } else {
                    1.0
                }
                val weighted = perpM * turnBoost + (if (turnDeg >= 45.0) turnDeg * 0.5 else 0.0)
                if (weighted > bestWeighted) {
                    bestWeighted = weighted
                    bestPerp = perpM
                    bestIdx = i
                }
            }
            if (bestIdx < 0) return null
            return SegmentSplitCandidate(
                startIdx = startIdx,
                endIdx = endIdx,
                splitIdx = bestIdx,
                maxPerpMeters = bestPerp,
                weightedScore = bestWeighted
            )
        }

        val kept = sortedSetOf(0, n - 1)
        val candidates = mutableListOf<SegmentSplitCandidate>()
        evaluateSegment(0, n - 1)?.let { candidates.add(it) }

        while (kept.size - 2 < maxInteriorPoints && candidates.isNotEmpty()) {
            val best = candidates.maxByOrNull { it.weightedScore } ?: break
            val turnAtSplit = turnAngles[best.splitIdx]
            if (best.maxPerpMeters < epsilonMeters && turnAtSplit < 35.0) {
                break
            }
            candidates.remove(best)
            kept.add(best.splitIdx)

            evaluateSegment(best.startIdx, best.splitIdx)?.let { candidates.add(it) }
            evaluateSegment(best.splitIdx, best.endIdx)?.let { candidates.add(it) }
        }

        // Subdivide long collinear spans so editable routes have intermediate via points
        // while staying strictly <= maxInteriorPoints.
        val cumMeters = DoubleArray(n)
        for (i in 1 until n) {
            cumMeters[i] = cumMeters[i - 1] + haversineMeters(
                points[i - 1].lat,
                points[i - 1].lng,
                points[i].lat,
                points[i].lng
            )
        }
        val totalMeters = cumMeters[n - 1]
        val effectiveSpacing = max(
            targetSpacingMeters.coerceAtLeast(100.0),
            totalMeters / (maxInteriorPoints + 1).coerceAtLeast(1)
        )
        if (effectiveSpacing > 0.0 && kept.size - 2 < maxInteriorPoints) {
            val snapshot = kept.toList()
            for (s in 0 until snapshot.size - 1) {
                if (kept.size - 2 >= maxInteriorPoints) break
                val aIdx = snapshot[s]
                val bIdx = snapshot[s + 1]
                val spanMeters = cumMeters[bIdx] - cumMeters[aIdx]
                if (spanMeters >= effectiveSpacing * 1.4 && bIdx - aIdx > 1) {
                    val steps = (spanMeters / effectiveSpacing).toInt().coerceAtLeast(2)
                    for (k in 1 until steps) {
                        if (kept.size - 2 >= maxInteriorPoints) break
                        val targetDist = cumMeters[aIdx] + (spanMeters * k.toDouble() / steps.toDouble())
                        var bestSplit = aIdx + 1
                        var bestDiff = Double.MAX_VALUE
                        for (idx in (aIdx + 1) until bIdx) {
                            val diff = abs(cumMeters[idx] - targetDist)
                            if (diff < bestDiff) {
                                bestDiff = diff
                                bestSplit = idx
                            }
                        }
                        kept.add(bestSplit)
                    }
                }
            }
        }

        return kept.toList()
    }

    private fun findNeighborAtDistance(
        points: List<RoutePoint3D>,
        fromIdx: Int,
        step: Int,
        targetDistMeters: Double
    ): Int {
        var idx = fromIdx
        var acc = 0.0
        while (true) {
            val next = idx + step
            if (next < 0 || next >= points.size) break
            acc += haversineMeters(points[idx].lat, points[idx].lng, points[next].lat, points[next].lng)
            idx = next
            if (acc >= targetDistMeters) break
        }
        if (idx == fromIdx) {
            return (fromIdx + step).coerceIn(0, points.lastIndex)
        }
        return idx
    }

    /**
     * Builds direct [LegGeometryData] legs from the original GPX geometry without calling BRouter
     * ("Behold GPX-linjen").
     */
    fun buildOriginalGpxLegs(
        waypoints: List<RouteWaypoint>,
        originalPoints: List<RoutePoint3D>,
        waypointSourceIndices: List<Int> = emptyList()
    ): Map<String, LegGeometryData> {
        if (waypoints.size < 2 || originalPoints.size < 2) return emptyMap()

        val resolvedIndices = if (waypointSourceIndices.size == waypoints.size) {
            waypointSourceIndices
        } else {
            resolveNearestSourceIndices(waypoints, originalPoints)
        }

        val legs = mutableMapOf<String, LegGeometryData>()
        for (i in 0 until waypoints.size - 1) {
            val fromWp = waypoints[i]
            val toWp = waypoints[i + 1]
            val startIdx = min(resolvedIndices[i], resolvedIndices[i + 1]).coerceIn(0, originalPoints.lastIndex)
            val endIdx = max(resolvedIndices[i], resolvedIndices[i + 1]).coerceIn(startIdx, originalPoints.lastIndex)

            val subPoints = if (endIdx > startIdx) {
                originalPoints.subList(startIdx, endIdx + 1).toList()
            } else {
                listOf(
                    RoutePoint3D(fromWp.lat, fromWp.lng, originalPoints.getOrNull(startIdx)?.ele),
                    RoutePoint3D(toWp.lat, toWp.lng, originalPoints.getOrNull(endIdx)?.ele)
                )
            }

            var distM = 0.0
            var ascentM = 0.0
            var descentM = 0.0

            for (j in 1 until subPoints.size) {
                val p1 = subPoints[j - 1]
                val p2 = subPoints[j]
                distM += haversineMeters(p1.lat, p1.lng, p2.lat, p2.lng)
                val prevEle = p1.ele
                val currEle = p2.ele
                if (currEle != null && prevEle != null) {
                    val dEle = currEle - prevEle
                    if (dEle > 0.0) ascentM += dEle else if (dEle < 0.0) descentM += -dEle
                }
            }

            val cues = TurnCueGenerator.computeTurnCuesFromPointsAndWayInfo(subPoints, emptyList())
            val leg = LegGeometryData(
                fromWaypointId = fromWp.id,
                toWaypointId = toWp.id,
                legMode = LegMode.SNAP,
                points = subPoints,
                lengthKm = distM / 1000.0,
                ascentM = ascentM,
                descentM = descentM,
                pathSharePct = null,
                roadKm = 0.0,
                hasAccessWarning = false,
                sourceName = "GPX-linje (original)",
                wayInfo = emptyList(),
                turnCues = cues
            )
            legs[leg.legKey] = leg
        }
        return legs
    }

    private fun resolveNearestSourceIndices(
        waypoints: List<RouteWaypoint>,
        originalPoints: List<RoutePoint3D>
    ): List<Int> {
        val result = mutableListOf<Int>()
        var searchStart = 0
        for ((idx, wp) in waypoints.withIndex()) {
            if (idx == 0) {
                result.add(0)
                continue
            }
            if (idx == waypoints.lastIndex) {
                result.add(originalPoints.lastIndex)
                continue
            }
            var bestIdx = searchStart
            var bestDist = Double.MAX_VALUE
            for (j in searchStart until originalPoints.size) {
                val pt = originalPoints[j]
                val d = haversineMeters(wp.lat, wp.lng, pt.lat, pt.lng)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = j
                }
            }
            result.add(bestIdx)
            searchStart = bestIdx
        }
        return result
    }

    private fun perpendicularDistanceMeters(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double
    ): Double {
        val meanLatRad = Math.toRadians((aLat + bLat + pLat) / 3.0)
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(meanLatRad)

        val ax = 0.0
        val ay = 0.0
        val bx = (bLng - aLng) * metersPerDegLng
        val by = (bLat - aLat) * metersPerDegLat
        val px = (pLng - aLng) * metersPerDegLng
        val py = (pLat - aLat) * metersPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-6) {
            return sqrt(px * px + py * py)
        }
        val t = ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = t * dx
        val projY = t * dy
        val distX = px - projX
        val distY = py - projY
        return sqrt(distX * distX + distY * distY)
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun normalizeAngleDeg(delta: Double): Double {
        var d = delta % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
