package com.example.util

import com.example.data.model.GeoPointDto
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Douglas-Peucker line simplification with [epsilon] in metres (moved unchanged out of
 * TrailGeometryRepository so the restricted-area layer can reuse it).
 */
object DouglasPeucker {
    fun simplify(points: List<GeoPointDto>, epsilon: Double): List<GeoPointDto> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true

        val stack = java.util.ArrayDeque<Pair<Int, Int>>()
        stack.push(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (startIndex, endIndex) = stack.pop()
            if (endIndex <= startIndex + 1) continue

            val a = points[startIndex]
            val b = points[endIndex]
            val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
            val dy = (b.lat - a.lat) * 110540.0
            val denom = dx * dx + dy * dy

            var dMax = 0.0
            var maxIdx = startIndex

            for (i in startIndex + 1 until endIndex) {
                val p = points[i]
                val d = if (denom == 0.0) {
                    distanceMeters(p, a)
                } else {
                    val x = (p.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
                    val y = (p.lat - a.lat) * 110540.0
                    val t = max(0.0, min(1.0, (x * dx + y * dy) / denom))
                    val projLon = a.lng + t * (b.lng - a.lng)
                    val projLat = a.lat + t * (b.lat - a.lat)
                    distanceMeters(p.lat, p.lng, projLat, projLon)
                }
                if (d > dMax) {
                    maxIdx = i
                    dMax = d
                }
            }

            if (dMax > epsilon) {
                keep[maxIdx] = true
                stack.push(startIndex to maxIdx)
                stack.push(maxIdx to endIndex)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun distanceMeters(p1: GeoPointDto, p2: GeoPointDto): Double = distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * asin(sqrt(a))
    }
}
