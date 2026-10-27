package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val ele: Double? = null,
    val walkKm: Double,       // Distance from planned route start (0.0 to totalWalkKm)
    val trailKm: Double,      // Original distance along full trail
    val segmentType: TrailSegmentType = TrailSegmentType.WALK,
    val segmentIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class TurnCue(
    val lat: Double,
    val lng: Double,
    val walkKm: Double,
    val direction: String, // "LEFT", "SLIGHT_LEFT", "SHARP_LEFT", "RIGHT", "SLIGHT_RIGHT", "SHARP_RIGHT", "STRAIGHT", "UTURN"
    val angleDegrees: Double,
    val instructionText: String, // e.g. "Hold til venstre", "Drej til venstre", "Drej til højre", "Hold til højre"
    val pointIndex: Int = 0
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
    val createdAt: Long = System.currentTimeMillis(),
    val turnCues: List<TurnCue> = emptyList()
) {
    @Transient
    private var cachedCoarseIndex: CoarseRouteIndex? = null

    private fun getOrBuildCoarseIndex(): CoarseRouteIndex {
        val existing = cachedCoarseIndex
        if (existing != null) return existing
        val built = CoarseRouteIndex.build(points)
        cachedCoarseIndex = built
        return built
    }

    fun boundingBox(): DoubleArray = getOrBuildCoarseIndex().bbox

    /**
     * Projects a (lat, lng) onto this planned route.
     * First projects against a coarse copy of the route (~1 point per 100 m)
     * and refines against full-resolution segments only around the best match.
     * Returns Pair(walkKm, detourMeters).
     */
    fun projectPoint(lat: Double, lng: Double): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, Double.MAX_VALUE)
        if (points.size == 1) {
            val d = com.example.util.GeoUtils.distanceMeters(lat, lng, points[0].lat, points[0].lng)
            return Pair(0.0, d)
        }
        return getOrBuildCoarseIndex().project(points, lat, lng)
    }
}

internal class CoarseRouteIndex(
    val bbox: DoubleArray, // [minLat, maxLat, minLng, maxLng]
    private val cosMidLat: Double,
    private val cLat: DoubleArray,
    private val cLng: DoubleArray,
    private val cWalkKm: DoubleArray,
    private val cSegIdx: IntArray,
    private val cFullIdx: IntArray
) {
    fun project(points: List<RoutePoint>, lat: Double, lng: Double): Pair<Double, Double> {
        val n = points.size
        if (n <= 32 || cLat.size < 2) {
            return projectFullRange(points, 0, n - 2, lat, lng, cosMidLat)
        }

        // 1. Coarse pass (~1 point per 100 m)
        val cSize = cLat.size
        var bestCoarseK = 0
        var minCoarseDistSq = Double.MAX_VALUE
        for (k in 0 until cSize - 1) {
            if (cSegIdx[k] != cSegIdx[k + 1]) continue
            val aLat = cLat[k]
            val aLng = cLng[k]
            val bLat = cLat[k + 1]
            val bLng = cLng[k + 1]
            val px = (lng - aLng) * cosMidLat * 111320.0
            val py = (lat - aLat) * 110540.0
            val bx = (bLng - aLng) * cosMidLat * 111320.0
            val by = (bLat - aLat) * 110540.0
            val segLenSq = bx * bx + by * by
            val t = if (segLenSq <= 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val distSq = dx * dx + dy * dy
            if (distSq < minCoarseDistSq) {
                minCoarseDistSq = distSq
                bestCoarseK = k
            }
        }

        // 2. Refine around the best coarse match (and any other coarse segment within +200 m of best)
        val minCoarseDist = kotlin.math.sqrt(minCoarseDistSq)
        val thresholdSq = (minCoarseDist + 200.0) * (minCoarseDist + 200.0)

        var bestWalkKm = 0.0
        var minDist = Double.MAX_VALUE

        var k = 0
        while (k < cSize - 1) {
            if (cSegIdx[k] == cSegIdx[k + 1]) {
                val aLat = cLat[k]
                val aLng = cLng[k]
                val bLat = cLat[k + 1]
                val bLng = cLng[k + 1]
                val px = (lng - aLng) * cosMidLat * 111320.0
                val py = (lat - aLat) * 110540.0
                val bx = (bLng - aLng) * cosMidLat * 111320.0
                val by = (bLat - aLat) * 110540.0
                val segLenSq = bx * bx + by * by
                val t = if (segLenSq <= 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
                val dx = px - t * bx
                val dy = py - t * by
                val distSq = dx * dx + dy * dy

                if (k == bestCoarseK || distSq <= thresholdSq) {
                    val startFull = (if (k > 0 && cSegIdx[k - 1] == cSegIdx[k]) cFullIdx[k - 1] else cFullIdx[k]).coerceAtLeast(0)
                    var endK = k + 1
                    while (endK < cSize - 1 && cSegIdx[endK] == cSegIdx[endK + 1]) {
                        val naLat = cLat[endK]
                        val naLng = cLng[endK]
                        val nbLat = cLat[endK + 1]
                        val nbLng = cLng[endK + 1]
                        val npx = (lng - naLng) * cosMidLat * 111320.0
                        val npy = (lat - naLat) * 110540.0
                        val nbx = (nbLng - naLng) * cosMidLat * 111320.0
                        val nby = (nbLat - naLat) * 110540.0
                        val nLenSq = nbx * nbx + nby * nby
                        val nt = if (nLenSq <= 0.0) 0.0 else ((npx * nbx + npy * nby) / nLenSq).coerceIn(0.0, 1.0)
                        val ndx = npx - nt * nbx
                        val ndy = npy - nt * nby
                        if (ndx * ndx + ndy * ndy <= thresholdSq) {
                            endK++
                        } else {
                            break
                        }
                    }
                    val endFull = (if (endK + 1 < cSize && cSegIdx[endK + 1] == cSegIdx[endK]) cFullIdx[endK + 1] else cFullIdx[endK])
                        .coerceAtMost(n - 1)
                    val (wKm, dM) = projectFullRange(points, startFull, (endFull - 1).coerceAtLeast(startFull), lat, lng, cosMidLat)
                    if (dM < minDist) {
                        minDist = dM
                        bestWalkKm = wKm
                    }
                    k = endK
                }
            }
            k++
        }

        if (minDist == Double.MAX_VALUE) {
            val startFull = cFullIdx[bestCoarseK].coerceAtLeast(0)
            val endFull = cFullIdx[(bestCoarseK + 1).coerceAtMost(cSize - 1)].coerceAtMost(n - 1)
            return projectFullRange(points, startFull, (endFull - 1).coerceAtLeast(startFull), lat, lng, cosMidLat)
        }
        return Pair(bestWalkKm, minDist)
    }

    companion object {
        private const val COARSE_STEP_KM = 0.1 // ~100 m

        fun build(points: List<RoutePoint>): CoarseRouteIndex {
            if (points.isEmpty()) {
                return CoarseRouteIndex(
                    bbox = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
                    cosMidLat = 0.56,
                    cLat = DoubleArray(0),
                    cLng = DoubleArray(0),
                    cWalkKm = DoubleArray(0),
                    cSegIdx = IntArray(0),
                    cFullIdx = IntArray(0)
                )
            }
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE

            val coarseIndices = IntArray(points.size)
            var coarseCount = 0
            var lastKeptWalkKm = -1.0
            var lastKeptSegIdx = -1

            for (i in points.indices) {
                val p = points[i]
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lng < minLng) minLng = p.lng
                if (p.lng > maxLng) maxLng = p.lng

                val isFirstOrLast = (i == 0 || i == points.lastIndex)
                val isSegBoundary = (p.segmentIndex != lastKeptSegIdx) ||
                    (i < points.lastIndex && points[i + 1].segmentIndex != p.segmentIndex)
                val farEnough = (p.walkKm - lastKeptWalkKm) >= COARSE_STEP_KM

                if (isFirstOrLast || isSegBoundary || farEnough) {
                    coarseIndices[coarseCount++] = i
                    lastKeptWalkKm = p.walkKm
                    lastKeptSegIdx = p.segmentIndex
                }
            }

            val cLat = DoubleArray(coarseCount)
            val cLng = DoubleArray(coarseCount)
            val cWalkKm = DoubleArray(coarseCount)
            val cSegIdx = IntArray(coarseCount)
            val cFullIdx = IntArray(coarseCount)

            for (k in 0 until coarseCount) {
                val idx = coarseIndices[k]
                val p = points[idx]
                cLat[k] = p.lat
                cLng[k] = p.lng
                cWalkKm[k] = p.walkKm
                cSegIdx[k] = p.segmentIndex
                cFullIdx[k] = idx
            }

            val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
            val cosMidLat = kotlin.math.cos(midLatRad).coerceAtLeast(0.1)

            return CoarseRouteIndex(
                bbox = doubleArrayOf(minLat, maxLat, minLng, maxLng),
                cosMidLat = cosMidLat,
                cLat = cLat,
                cLng = cLng,
                cWalkKm = cWalkKm,
                cSegIdx = cSegIdx,
                cFullIdx = cFullIdx
            )
        }

        private fun projectFullRange(
            points: List<RoutePoint>,
            startIdx: Int,
            endIdxInclusive: Int,
            lat: Double,
            lng: Double,
            cosMidLat: Double
        ): Pair<Double, Double> {
            var minDistSq = Double.MAX_VALUE
            var bestWalkKm = 0.0
            var bestProjLat = points[startIdx].lat
            var bestProjLng = points[startIdx].lng

            val maxI = endIdxInclusive.coerceAtMost(points.size - 2)
            for (i in startIdx..maxI) {
                val a = points[i]
                val b = points[i + 1]
                if (a.segmentIndex != b.segmentIndex) continue
                val px = (lng - a.lng) * cosMidLat * 111320.0
                val py = (lat - a.lat) * 110540.0
                val bx = (b.lng - a.lng) * cosMidLat * 111320.0
                val by = (b.lat - a.lat) * 110540.0
                val segLenSq = bx * bx + by * by
                val t = if (segLenSq <= 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
                val dx = px - t * bx
                val dy = py - t * by
                val distSq = dx * dx + dy * dy
                if (distSq < minDistSq) {
                    minDistSq = distSq
                    bestWalkKm = a.walkKm + t * (b.walkKm - a.walkKm)
                    bestProjLat = a.lat + t * (b.lat - a.lat)
                    bestProjLng = a.lng + t * (b.lng - a.lng)
                }
            }
            val exactDist = com.example.util.GeoUtils.distanceMeters(lat, lng, bestProjLat, bestProjLng)
            return Pair(bestWalkKm, exactDist)
        }
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

