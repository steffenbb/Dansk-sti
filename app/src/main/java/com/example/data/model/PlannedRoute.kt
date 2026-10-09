package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val ele: Double = 0.0,
    val walkKm: Double,       // Distance from planned route start (0.0 to totalWalkKm)
    val trailKm: Double,      // Original distance along full trail
    val segmentType: TrailSegmentType = TrailSegmentType.WALK
)

@JsonClass(generateAdapter = true)
data class PlannedRoute(
    val planId: Long,
    val trailId: String,
    val trailName: String,
    val isReverse: Boolean,
    val startTrailKm: Double,
    val endTrailKm: Double,
    val totalWalkKm: Double,
    val points: List<RoutePoint>,
    val stages: List<TripStage>,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Projects a (lat, lng) onto this planned route using orthogonal segment projection.
     * Returns Pair(walkKm, detourMeters)
     */
    fun projectPoint(lat: Double, lng: Double): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, Double.MAX_VALUE)
        if (points.size == 1) {
            val d = com.example.util.GeoUtils.distanceMeters(lat, lng, points[0].lat, points[0].lng)
            return Pair(0.0, d)
        }
        var minDist = Double.MAX_VALUE
        var bestWalkKm = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (com.example.util.GeoUtils.distanceMeters(a.lat, a.lng, b.lat, b.lng) > 8000.0) continue
            val midLatRad = Math.toRadians((a.lat + lat) / 2.0)
            val cosLat = Math.cos(midLatRad)
            val px = (lng - a.lng) * cosLat * 111320.0
            val py = (lat - a.lat) * 110540.0
            val bx = (b.lng - a.lng) * cosLat * 111320.0
            val by = (b.lat - a.lat) * 110540.0
            val segLenSq = bx * bx + by * by
            val t = if (segLenSq <= 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
            val projLat = a.lat + t * (b.lat - a.lat)
            val projLng = a.lng + t * (b.lng - a.lng)
            val dist = com.example.util.GeoUtils.distanceMeters(lat, lng, projLat, projLng)
            if (dist < minDist) {
                minDist = dist
                bestWalkKm = a.walkKm + t * (b.walkKm - a.walkKm)
            }
        }
        return Pair(bestWalkKm, minDist)
    }
}

data class RoutePoiItem(
    val id: String,
    val name: String,
    val type: String, // water, toilet, shop, shelter, tent
    val lat: Double,
    val lng: Double,
    val walkKm: Double,
    val kmAhead: Double,
    val detourMeters: Double,
    val rawEntity: Any? = null
)

