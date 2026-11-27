package com.example.util

import com.example.data.model.GeoPointDto
import com.example.data.repository.RestrictedArea
import com.example.util.access.AccessGeometryUtils
import com.example.util.routing.LatLng
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a route enters and leaves a restricted area, or (nearOnly) passes within 100 m of it. */
data class AreaCrossing(
    val area: RestrictedArea,
    val startKm: Double,
    val endKm: Double,
    val lat: Double,
    val lng: Double,
    val nearOnly: Boolean = false
)

/** Phase D2: pure route check against military areas and wildlife reserves. */
object RestrictedAreaChecker {
    const val SAMPLE_STEP_KM = 0.05
    const val MERGE_GAP_KM = 0.1
    const val NEAR_DISTANCE_M = 100.0

    private data class Sample(val km: Double, val lat: Double, val lng: Double)

    /** Samples the polyline every [stepKm], always including the first and last point. */
    private fun resample(points: List<GeoPointDto>, stepKm: Double): List<Sample> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf(Sample(0.0, points[0].lat, points[0].lng))
        var walked = 0.0
        var nextKm = stepKm
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segKm = GeoUtils.distanceMeters(a.lat, a.lng, b.lat, b.lng) / 1000.0
            if (segKm <= 0.0) continue
            while (nextKm <= walked + segKm) {
                val t = (nextKm - walked) / segKm
                out.add(Sample(nextKm, a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t))
                nextKm += stepKm
            }
            walked += segKm
        }
        if (walked - out.last().km > 1e-6) out.add(Sample(walked, points.last().lat, points.last().lng))
        return out
    }

    fun findCrossings(points: List<GeoPointDto>, areas: List<RestrictedArea>): List<AreaCrossing> {
        if (points.size < 2 || areas.isEmpty()) return emptyList()
        val samples = resample(points, SAMPLE_STEP_KM)
        val routeBbox = com.example.data.repository.AreaBbox(
            points.minOf { it.lat }, points.minOf { it.lng }, points.maxOf { it.lat }, points.maxOf { it.lng }
        )
        val nearMarginDeg = NEAR_DISTANCE_M / 111_000.0 * 2.0
        val result = mutableListOf<AreaCrossing>()

        for (area in areas) {
            val b = area.bbox
            // Quick reject: route bbox and area bbox (+ margin) do not overlap
            if (routeBbox.maxLat < b.minLat - nearMarginDeg || routeBbox.minLat > b.maxLat + nearMarginDeg ||
                routeBbox.maxLng < b.minLng - nearMarginDeg || routeBbox.minLng > b.maxLng + nearMarginDeg
            ) continue
            val rings = area.rings.map { ring -> ring.map { LatLng(it.lat, it.lng) } }

            val inside = samples.filter { s ->
                b.contains(s.lat, s.lng) && rings.any { AccessGeometryUtils.isPointInPolygon(LatLng(s.lat, s.lng), it) }
            }
            if (inside.isNotEmpty()) {
                var runStart = inside.first()
                var prev = inside.first()
                for (s in inside.drop(1) + listOf<Sample?>(null)) {
                    // An outside stretch shorter than 100 m between two inside samples counts as inside
                    if (s != null && s.km - prev.km - SAMPLE_STEP_KM < MERGE_GAP_KM - 1e-9) {
                        prev = s
                        continue
                    }
                    result.add(AreaCrossing(area, runStart.km, prev.km, runStart.lat, runStart.lng))
                    if (s != null) { runStart = s; prev = s }
                }
            } else {
                val nearest = samples
                    .filter { b.contains(it.lat, it.lng, nearMarginDeg) }
                    .map { it to distanceToRingsM(it.lat, it.lng, area.rings) }
                    .minByOrNull { it.second }
                if (nearest != null && nearest.second <= NEAR_DISTANCE_M) {
                    val s = nearest.first
                    result.add(AreaCrossing(area, s.km, s.km, s.lat, s.lng, nearOnly = true))
                }
            }
        }
        return result.sortedBy { it.startKm }
    }

    /**
     * Same as [findCrossings] for a trail made of segments: only WALK segments count towards km
     * (as on the trail page), and a crossing that continues into the next segment is merged.
     */
    fun findCrossingsOnSegments(segments: List<com.example.data.model.TrailSegmentDto>, areas: List<RestrictedArea>): List<AreaCrossing> {
        val all = mutableListOf<AreaCrossing>()
        var offsetKm = 0.0
        for (seg in segments) {
            if (seg.type != com.example.data.model.TrailSegmentType.WALK) continue
            findCrossings(seg.points, areas).forEach { c -> all.add(c.copy(startKm = c.startKm + offsetKm, endKm = c.endKm + offsetKm)) }
            offsetKm += seg.points.zipWithNext { p, q -> GeoUtils.distanceMeters(p.lat, p.lng, q.lat, q.lng) }.sum() / 1000.0
        }
        val merged = mutableListOf<AreaCrossing>()
        for (c in all.sortedBy { it.startKm }) {
            val prevIdx = merged.indexOfLast { it.area.id == c.area.id }
            val prev = merged.getOrNull(prevIdx)
            when {
                prev == null -> merged.add(c)
                // An entry already exists: a second near-only hit adds nothing
                c.nearOnly && (prev.nearOnly || c.startKm <= prev.endKm + MERGE_GAP_KM) -> Unit
                !c.nearOnly && !prev.nearOnly && c.startKm - prev.endKm < MERGE_GAP_KM + SAMPLE_STEP_KM -> merged[prevIdx] = prev.copy(endKm = c.endKm)
                !c.nearOnly && prev.nearOnly -> merged[prevIdx] = c
                else -> merged.add(c)
            }
        }
        return merged.sortedBy { it.startKm }
    }

    /** Shortest distance in metres from a point to the edges of [rings] (local flat approximation). */
    private fun distanceToRingsM(lat: Double, lng: Double, rings: List<List<GeoPointDto>>): Double {
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (ring in rings) {
            for (i in 0 until ring.size - 1) {
                val ax = (ring[i].lng - lng) * kx; val ay = (ring[i].lat - lat) * ky
                val bx = (ring[i + 1].lng - lng) * kx; val by = (ring[i + 1].lat - lat) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * dx; val py = ay + t * dy
                val d = sqrt(px * px + py * py)
                if (d < best) best = d
            }
        }
        return best
    }
}
