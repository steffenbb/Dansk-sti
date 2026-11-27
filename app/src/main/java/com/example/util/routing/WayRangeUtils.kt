package com.example.util.routing

import com.example.data.model.TrailSegmentDto
import com.example.data.model.WayTagRangeDto

object WayRangeUtils {

    fun segmentWalkKm(seg: TrailSegmentDto): Double {
        val pts = seg.points
        if (pts.size < 2) return 0.0
        var meters = 0.0
        for (i in 0 until pts.size - 1) {
            meters += TrailSlicer.haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
        }
        return meters / 1000.0
    }

    fun normalizeSegmentWayRanges(
        seg: TrailSegmentDto,
        segStartWalkKm: Double,
        segTotalLocalKm: Double = segmentWalkKm(seg)
    ): List<WayTagRangeDto> {
        if (seg.wayTagRanges.isNotEmpty()) {
            val firstFrom = seg.wayTagRanges.first().fromKm
            val usesGlobalKm = segStartWalkKm > 1e-3 && firstFrom >= segStartWalkKm - 1e-3
            return seg.wayTagRanges.map { r ->
                if (usesGlobalKm) {
                    r.copy(
                        fromKm = (r.fromKm - segStartWalkKm).coerceAtLeast(0.0),
                        toKm = (r.toKm - segStartWalkKm).coerceAtLeast(0.0)
                    )
                } else {
                    r
                }
            }
        }
        return emptyList()
    }
}
