package com.example.util

import com.example.data.model.GeoPointDto
import com.example.data.remote.OverpassMember
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Haversine distance between two coordinates in meters.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun distanceMeters(p1: GeoPointDto, p2: GeoPointDto): Double {
        return distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
    }

    /**
     * Total length of a polyline in kilometers.
     */
    fun calculatePolylineLengthKm(points: List<GeoPointDto>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total / 1000.0
    }

    /**
     * Shortest distance from point P to line segment AB in meters.
     */
    fun distanceToSegmentMeters(
        p: GeoPointDto,
        a: GeoPointDto,
        b: GeoPointDto
    ): Double {
        val l2 = distanceMeters(a, b).let { it * it }
        if (l2 == 0.0) return distanceMeters(p, a)

        val x = (p.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val y = p.lat - a.lat
        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = b.lat - a.lat

        val t = ((x * dx + y * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val projLat = a.lat + t * (b.lat - a.lat)
        val projLng = a.lng + t * (b.lng - a.lng)

        return distanceMeters(p.lat, p.lng, projLat, projLng)
    }

    /**
     * Minimum distance from point to polyline in meters, returning min distance and closest segment index.
     */
    fun minDistanceToPolyline(
        point: GeoPointDto,
        polyline: List<GeoPointDto>
    ): Pair<Double, Int> {
        if (polyline.isEmpty()) return Pair(Double.MAX_VALUE, -1)
        if (polyline.size == 1) return Pair(distanceMeters(point, polyline[0]), 0)

        var minDist = Double.MAX_VALUE
        var bestIndex = 0

        for (i in 0 until polyline.size - 1) {
            val dist = distanceToSegmentMeters(point, polyline[i], polyline[i + 1])
            if (dist < minDist) {
                minDist = dist
                bestIndex = i
            }
        }
        return Pair(minDist, bestIndex)
    }

    fun distanceToPolylineMeters(point: GeoPointDto, polyline: List<GeoPointDto>): Double {
        return minDistanceToPolyline(point, polyline).first
    }

    fun pointAtDistanceAlongPolyline(polyline: List<GeoPointDto>, targetKm: Double): GeoPointDto {
        return findPointAtDistanceKm(polyline, targetKm)
    }

    fun distanceAlongPolylineKm(polyline: List<GeoPointDto>, point: GeoPointDto): Double {
        if (polyline.size < 2) return 0.0
        val (_, closestSegmentIndex) = minDistanceToPolyline(point, polyline)
        if (closestSegmentIndex < 0 || closestSegmentIndex >= polyline.size - 1) return 0.0

        var accumulatedKm = 0.0
        for (i in 0 until closestSegmentIndex) {
            accumulatedKm += distanceMeters(polyline[i], polyline[i + 1]) / 1000.0
        }
        accumulatedKm += distanceMeters(polyline[closestSegmentIndex], point) / 1000.0
        return accumulatedKm
    }

    /**
     * Snaps a point to the nearest location on the polyline.
     */
    fun snapPointToPolyline(
        point: GeoPointDto,
        polyline: List<GeoPointDto>
    ): GeoPointDto {
        if (polyline.isEmpty()) return point
        if (polyline.size == 1) return polyline[0]

        var minDist = Double.MAX_VALUE
        var bestPoint = polyline[0]

        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]

            val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
            val dy = b.lat - a.lat
            val denom = dx * dx + dy * dy

            val t = if (denom > 0.0) {
                val x = (point.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
                val y = point.lat - a.lat
                ((x * dx + y * dy) / denom).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val projLat = a.lat + t * (b.lat - a.lat)
            val projLng = a.lng + t * (b.lng - a.lng)
            val dist = distanceMeters(point.lat, point.lng, projLat, projLng)

            if (dist < minDist) {
                minDist = dist
                bestPoint = GeoPointDto(projLat, projLng)
            }
        }
        return bestPoint
    }

    /**
     * Cumulative distance along polyline at each node in km.
     */
    fun getCumulativeDistancesKm(polyline: List<GeoPointDto>): DoubleArray {
        val distances = DoubleArray(polyline.size)
        var acc = 0.0
        distances[0] = 0.0
        for (i in 1 until polyline.size) {
            acc += distanceMeters(polyline[i - 1], polyline[i]) / 1000.0
            distances[i] = acc
        }
        return distances
    }

    /**
     * Finds point on polyline corresponding to given target distance from start (in km).
     */
    fun findPointAtDistanceKm(
        polyline: List<GeoPointDto>,
        targetKm: Double
    ): GeoPointDto {
        if (polyline.isEmpty()) return GeoPointDto(55.6761, 12.5683)
        if (targetKm <= 0.0 || polyline.size == 1) return polyline.first()

        var accumulated = 0.0
        for (i in 0 until polyline.size - 1) {
            val segLen = distanceMeters(polyline[i], polyline[i + 1]) / 1000.0
            if (accumulated + segLen >= targetKm) {
                val ratio = if (segLen > 0) ((targetKm - accumulated) / segLen).coerceIn(0.0, 1.0) else 0.0
                val lat = polyline[i].lat + ratio * (polyline[i + 1].lat - polyline[i].lat)
                val lng = polyline[i].lng + ratio * (polyline[i + 1].lng - polyline[i].lng)
                return GeoPointDto(lat, lng)
            }
            accumulated += segLen
        }
        return polyline.last()
    }

    /**
     * Extracts the sub-polyline between startKm and endKm along the polyline.
     * Accurately includes interpolated exact start and end points and all intermediate nodes.
     */
    fun slicePolylineByKm(
        polyline: List<GeoPointDto>,
        startKm: Double,
        endKm: Double
    ): List<GeoPointDto> {
        if (polyline.isEmpty()) return emptyList()
        if (polyline.size == 1) return polyline

        val totalLen = calculatePolylineLengthKm(polyline)
        val sKm = startKm.coerceIn(0.0, totalLen)
        val eKm = endKm.coerceIn(0.0, totalLen)
        val minKm = minOf(sKm, eKm)
        val maxKm = maxOf(sKm, eKm)

        if (maxKm - minKm < 0.001) {
            return listOf(findPointAtDistanceKm(polyline, minKm))
        }

        val result = mutableListOf<GeoPointDto>()
        val startPt = findPointAtDistanceKm(polyline, minKm)
        result.add(startPt)

        val cumulative = getCumulativeDistancesKm(polyline)
        for (i in polyline.indices) {
            val d = cumulative[i]
            if (d > minKm && d < maxKm) {
                result.add(polyline[i])
            }
        }

        val endPt = findPointAtDistanceKm(polyline, maxKm)
        result.add(endPt)

        return if (startKm > endKm) result.reversed() else result
    }

    /**
     * Stitches member ways of an OSM relation into a continuous polyline.
     */
    fun stitchWaysToPolyline(members: List<OverpassMember>): List<GeoPointDto> {
        val segments = members
            .mapNotNull { it.geometry }
            .filter { it.size >= 2 }
            .map { way -> way.map { GeoPointDto(it.lat, it.lon) } }
            .toMutableList()

        if (segments.isEmpty()) return emptyList()

        val result = mutableListOf<GeoPointDto>()
        val first = segments.removeAt(0)
        result.addAll(first)

        while (segments.isNotEmpty()) {
            val currentEnd = result.last()
            val currentStart = result.first()

            var bestIdx = -1
            var bestDist = Double.MAX_VALUE
            var attachToEnd = true
            var needsReverse = false

            for (i in segments.indices) {
                val seg = segments[i]
                val segStart = seg.first()
                val segEnd = seg.last()

                val distEndNormal = distanceMeters(currentEnd, segStart)
                if (distEndNormal < bestDist) {
                    bestDist = distEndNormal
                    bestIdx = i
                    attachToEnd = true
                    needsReverse = false
                }

                val distEndReversed = distanceMeters(currentEnd, segEnd)
                if (distEndReversed < bestDist) {
                    bestDist = distEndReversed
                    bestIdx = i
                    attachToEnd = true
                    needsReverse = true
                }

                val distStartNormal = distanceMeters(currentStart, segEnd)
                if (distStartNormal < bestDist) {
                    bestDist = distStartNormal
                    bestIdx = i
                    attachToEnd = false
                    needsReverse = false
                }

                val distStartReversed = distanceMeters(currentStart, segStart)
                if (distStartReversed < bestDist) {
                    bestDist = distStartReversed
                    bestIdx = i
                    attachToEnd = false
                    needsReverse = true
                }
            }

            if (bestIdx == -1) break
            val chosenSeg = segments.removeAt(bestIdx)
            val oriented = if (needsReverse) chosenSeg.reversed() else chosenSeg

            if (attachToEnd) {
                if (distanceMeters(currentEnd, oriented.first()) < 50.0) {
                    result.addAll(oriented.drop(1))
                } else {
                    result.addAll(oriented)
                }
            } else {
                if (distanceMeters(currentStart, oriented.last()) < 50.0) {
                    result.addAll(0, oriented.dropLast(1))
                } else {
                    result.addAll(0, oriented)
                }
            }
        }
        return result
    }

    /**
     * Slices polyline points strictly between startKm and endKm along the route.
     */
    fun slicePolylineKm(polyline: List<GeoPointDto>, startKm: Double, endKm: Double): List<GeoPointDto> {
        if (polyline.isEmpty()) return emptyList()
        val s = kotlin.math.min(startKm, endKm)
        val e = kotlin.math.max(startKm, endKm)
        val result = mutableListOf<GeoPointDto>()

        var accumulated = 0.0
        val pStart = findPointAtDistanceKm(polyline, s)
        result.add(pStart)

        for (i in 0 until polyline.size - 1) {
            val seg = distanceMeters(polyline[i], polyline[i + 1]) / 1000.0
            val segStart = accumulated
            val segEnd = accumulated + seg

            if (segEnd > s && segStart < e) {
                result.add(polyline[i + 1])
            }
            accumulated += seg
            if (accumulated >= e) break
        }

        val pEnd = findPointAtDistanceKm(polyline, e)
        if (result.lastOrNull() != pEnd) {
            result.add(pEnd)
        }
        return if (startKm > endKm) result.reversed() else result
    }

    /**
     * Formats coordinates into spoken Danish WGS84 for Danish 112 emergency central:
     * e.g. "55 grader 03 minutter 42 sekunder Nord, 10 grader 36 minutter 18 sekunder Øst"
     */
    fun formatDanishSpokenWgs84(lat: Double, lng: Double): String {
        val latDeg = lat.toInt()
        val latMin = ((lat - latDeg) * 60).toInt()
        val latSec = ((((lat - latDeg) * 60) - latMin) * 60).toInt()

        val lngDeg = lng.toInt()
        val lngMin = ((lng - lngDeg) * 60).toInt()
        val lngSec = ((((lng - lngDeg) * 60) - lngMin) * 60).toInt()

        return "$latDeg grader $latMin minutter $latSec sekunder Nord, $lngDeg grader $lngMin minutter $lngSec sekunder Øst"
    }

    /**
     * Calculates terrain-adjusted walking speed (km/h) for Danish conditions:
     * - Asphalt/gravel: ~4.8 km/h
     * - Forest soil/roots: ~4.3 km/h
     * - Wet meadow / marsh: ~3.6 km/h
     * - Loose coastal sand / dunes: ~2.8 km/h
     */
    fun calculateTerrainAdjustedSpeedKmH(
        forestSoilPct: Int,
        gravelPct: Int,
        asphaltPct: Int,
        sandBeachPct: Int
    ): Double {
        val speed = (asphaltPct * 4.9 + gravelPct * 4.7 + forestSoilPct * 4.3 + sandBeachPct * 2.8) / 100.0
        return speed.coerceIn(2.5, 5.0)
    }
}

