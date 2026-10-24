package com.example.util.routing

import com.example.data.model.GeoPointDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

typealias TrailProjection = TrailSlicer.TrailProjection
typealias TrailKmMarker = TrailSlicer.TrailKmMarker
typealias TrailLegSlicer = TrailSlicer

data class SlicedTrailResult(
    val trailSegments: List<TrailSegmentDto>,
    val points: List<RoutePoint3D>,
    val wayInfo: List<WayInfo>,
    val lengthKm: Double,
    val ascentM: Double,
    val descentM: Double
)

object TrailSlicer {

    data class TrailProjection(
        val lat: Double,
        val lng: Double,
        val elevationM: Double?,
        val trailKm: Double,
        val distanceMeters: Double
    )

    data class TrailKmMarker(
        val km: Int,
        val lat: Double,
        val lng: Double,
        val label: String = "km $km"
    )

    /**
     * Computes the total walking distance (in km) across all WALK segments of a trail.
     */
    fun totalTrailWalkKm(segments: List<TrailSegmentDto>): Double {
        var totalMeters = 0.0
        for (seg in segments) {
            if (seg.type == TrailSegmentType.FERRY) continue
            val pts = seg.points
            for (i in 0 until pts.size - 1) {
                totalMeters += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            }
        }
        return totalMeters / 1000.0
    }

    fun totalTrailKm(segments: List<TrailSegmentDto>): Double = totalTrailWalkKm(segments)

    /**
     * Slices a trail between [fromKm] and [toKm] (canonical walking km from trail start).
     * If [fromKm] > [toKm], slices between [toKm] and [fromKm] and reverses the geometry.
     * Respects segment boundaries: FERRY segments stay FERRY, gaps between WALK segments stay separate segments.
     * Does not make any network or routing calls.
     */
    fun sliceTrail(
        segments: List<TrailSegmentDto>,
        fromKm: Double,
        toKm: Double
    ): SlicedTrailResult {
        if (segments.isEmpty()) {
            return SlicedTrailResult(
                trailSegments = emptyList(),
                points = emptyList(),
                wayInfo = emptyList(),
                lengthKm = 0.0,
                ascentM = 0.0,
                descentM = 0.0
            )
        }

        val totalKm = totalTrailWalkKm(segments)
        val isReversed = fromKm > toKm
        val minKm = min(fromKm, toKm).coerceIn(0.0, totalKm)
        val maxKm = max(fromKm, toKm).coerceIn(0.0, totalKm)

        if (maxKm - minKm < 1e-6) {
            val pt = pointAtTrailKm(segments, minKm)
            val singlePoint = if (pt != null) {
                listOf(RoutePoint3D(pt.lat, pt.lng, pt.elevationM))
            } else {
                emptyList()
            }
            val singleGeo = if (pt != null) {
                listOf(GeoPointDto(pt.lat, pt.lng, pt.elevationM))
            } else {
                emptyList()
            }
            return SlicedTrailResult(
                trailSegments = if (singleGeo.isNotEmpty()) listOf(TrailSegmentDto(TrailSegmentType.WALK, singleGeo, 0.0)) else emptyList(),
                points = singlePoint,
                wayInfo = emptyList(),
                lengthKm = 0.0,
                ascentM = 0.0,
                descentM = 0.0
            )
        }

        val slicedForward = mutableListOf<TrailSegmentDto>()
        var cumWalkKm = 0.0

        for (seg in segments) {
            val isFerry = seg.type == TrailSegmentType.FERRY
            val pts = seg.points
            if (pts.size < 2) continue

            if (isFerry) {
                // Keep FERRY segment if its walkKm position falls within [minKm, maxKm]
                if (cumWalkKm >= minKm - 1e-6 && cumWalkKm <= maxKm + 1e-6) {
                    var ferryDistM = 0.0
                    for (i in 0 until pts.size - 1) {
                        ferryDistM += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
                    }
                    slicedForward.add(
                        TrailSegmentDto(
                            type = TrailSegmentType.FERRY,
                            points = pts,
                            lengthKm = ferryDistM / 1000.0
                        )
                    )
                }
            } else {
                val slicedPts = mutableListOf<GeoPointDto>()
                var segWalkMeters = 0.0
                for (i in 0 until pts.size - 1) {
                    val p0 = pts[i]
                    val p1 = pts[i + 1]
                    val edgeKm = haversineMeters(p0.lat, p0.lng, p1.lat, p1.lng) / 1000.0
                    val edgeStartKm = cumWalkKm
                    val edgeEndKm = cumWalkKm + edgeKm
                    cumWalkKm = edgeEndKm

                    if (edgeEndKm <= minKm + 1e-9 || edgeStartKm >= maxKm - 1e-9) {
                        continue
                    }

                    val clipStartKm = max(minKm, edgeStartKm)
                    val clipEndKm = min(maxKm, edgeEndKm)

                    val startPt = if (edgeKm > 1e-9 && clipStartKm > edgeStartKm + 1e-9) {
                        val t = ((clipStartKm - edgeStartKm) / edgeKm).coerceIn(0.0, 1.0)
                        interpolate(p0, p1, t)
                    } else {
                        p0
                    }

                    val endPt = if (edgeKm > 1e-9 && clipEndKm < edgeEndKm - 1e-9) {
                        val t = ((clipEndKm - edgeStartKm) / edgeKm).coerceIn(0.0, 1.0)
                        interpolate(p0, p1, t)
                    } else {
                        p1
                    }

                    if (slicedPts.isEmpty()) {
                        slicedPts.add(startPt)
                    } else {
                        val last = slicedPts.last()
                        if (haversineMeters(last.lat, last.lng, startPt.lat, startPt.lng) > 0.5) {
                            slicedPts.add(startPt)
                        }
                    }
                    val prev = slicedPts.last()
                    segWalkMeters += haversineMeters(prev.lat, prev.lng, endPt.lat, endPt.lng)
                    slicedPts.add(endPt)
                }

                if (slicedPts.size >= 2) {
                    slicedForward.add(
                        TrailSegmentDto(
                            type = TrailSegmentType.WALK,
                            points = slicedPts,
                            lengthKm = segWalkMeters / 1000.0,
                            highway = seg.highway,
                            surface = seg.surface
                        )
                    )
                }
            }
        }

        val orderedSegments = if (isReversed) {
            slicedForward.reversed().map { seg ->
                seg.copy(points = seg.points.reversed())
            }
        } else {
            slicedForward
        }

        var walkMeters = 0.0
        var ascentM = 0.0
        var descentM = 0.0
        val allPoints = mutableListOf<RoutePoint3D>()
        val wayInfos = mutableListOf<WayInfo>()

        for (seg in orderedSegments) {
            val isFerry = seg.type == TrailSegmentType.FERRY
            for (i in seg.points.indices) {
                val p = seg.points[i]
                allPoints.add(RoutePoint3D(p.lat, p.lng, p.ele))
                wayInfos.add(
                    WayInfo(
                        highway = if (isFerry) "ferry" else seg.highway,
                        surface = if (isFerry) "ferry" else seg.surface
                    )
                )
                if (i > 0) {
                    val prev = seg.points[i - 1]
                    val d = haversineMeters(prev.lat, prev.lng, p.lat, p.lng)
                    if (!isFerry) {
                        walkMeters += d
                        val e0 = prev.ele
                        val e1 = p.ele
                        if (e0 != null && e1 != null) {
                            val diff = e1 - e0
                            if (diff > 0) ascentM += diff else descentM += -diff
                        }
                    }
                }
            }
        }

        return SlicedTrailResult(
            trailSegments = orderedSegments,
            points = allPoints,
            wayInfo = wayInfos,
            lengthKm = walkMeters / 1000.0,
            ascentM = ascentM,
            descentM = descentM
        )
    }

    /**
     * Projects [lat], [lng] onto the nearest point on the trail's WALK segments.
     * Optionally restricts the search to the interval between [boundKmA] and [boundKmB].
     */
    fun projectOntoTrail(
        segments: List<TrailSegmentDto>,
        lat: Double,
        lng: Double,
        boundKmA: Double? = null,
        boundKmB: Double? = null
    ): TrailProjection? {
        if (segments.isEmpty()) return null
        val minBound = if (boundKmA != null && boundKmB != null) min(boundKmA, boundKmB) else boundKmA
        val maxBound = if (boundKmA != null && boundKmB != null) max(boundKmA, boundKmB) else boundKmB

        var bestProj: TrailProjection? = null
        var cumWalkKm = 0.0

        for (seg in segments) {
            if (seg.type == TrailSegmentType.FERRY) continue
            val pts = seg.points
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val edgeMeters = haversineMeters(p0.lat, p0.lng, p1.lat, p1.lng)
                val edgeKm = edgeMeters / 1000.0
                val edgeStartKm = cumWalkKm
                val edgeEndKm = cumWalkKm + edgeKm
                cumWalkKm = edgeEndKm

                if (minBound != null && edgeEndKm < minBound - 1e-6) continue
                if (maxBound != null && edgeStartKm > maxBound + 1e-6) continue

                val cosLat = cos(Math.toRadians(lat))
                val dx = (p1.lng - p0.lng) * cosLat
                val dy = p1.lat - p0.lat
                val lenSq = dx * dx + dy * dy

                var t = if (lenSq > 1e-14) {
                    val px = (lng - p0.lng) * cosLat
                    val py = lat - p0.lat
                    ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }

                if (edgeKm > 1e-9) {
                    if (minBound != null && edgeStartKm < minBound) {
                        val tMin = ((minBound - edgeStartKm) / edgeKm).coerceIn(0.0, 1.0)
                        if (t < tMin) t = tMin
                    }
                    if (maxBound != null && edgeEndKm > maxBound) {
                        val tMax = ((maxBound - edgeStartKm) / edgeKm).coerceIn(0.0, 1.0)
                        if (t > tMax) t = tMax
                    }
                }

                val projPt = interpolate(p0, p1, t)
                val projKm = edgeStartKm + t * edgeKm
                val distM = haversineMeters(lat, lng, projPt.lat, projPt.lng)

                if (bestProj == null || distM < bestProj.distanceMeters) {
                    bestProj = TrailProjection(
                        lat = projPt.lat,
                        lng = projPt.lng,
                        elevationM = projPt.ele,
                        trailKm = projKm,
                        distanceMeters = distM
                    )
                }
            }
        }
        return bestProj
    }

    fun projectOntoTrail(
        segments: List<TrailSegmentDto>,
        lat: Double,
        lng: Double,
        isReverse: Boolean
    ): TrailProjection? {
        val raw = projectOntoTrail(segments, lat, lng, null, null) ?: return null
        if (!isReverse) return raw
        val total = totalTrailWalkKm(segments)
        return raw.copy(trailKm = (total - raw.trailKm).coerceIn(0.0, total))
    }

    /**
     * Returns the interpolated point on the trail at canonical walking distance [targetKm].
     */
    fun pointAtTrailKm(
        segments: List<TrailSegmentDto>,
        targetKm: Double,
        isReverse: Boolean = false
    ): TrailProjection? {
        val walkSegments = segments.filter { it.type != TrailSegmentType.FERRY && it.points.isNotEmpty() }
        if (walkSegments.isEmpty()) {
            val firstPt = segments.firstOrNull()?.points?.firstOrNull() ?: return null
            return TrailProjection(firstPt.lat, firstPt.lng, firstPt.ele, 0.0, 0.0)
        }

        val total = totalTrailWalkKm(segments)
        val canonicalKm = if (isReverse) (total - targetKm).coerceIn(0.0, total) else targetKm

        if (canonicalKm <= 0.0) {
            val firstPt = walkSegments.first().points.first()
            return TrailProjection(firstPt.lat, firstPt.lng, firstPt.ele, if (isReverse) total else 0.0, 0.0)
        }

        var cumWalkKm = 0.0
        for (seg in walkSegments) {
            val pts = seg.points
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val edgeKm = haversineMeters(p0.lat, p0.lng, p1.lat, p1.lng) / 1000.0
                val nextKm = cumWalkKm + edgeKm
                if (canonicalKm <= nextKm || (seg === walkSegments.last() && i == pts.size - 2)) {
                    val t = if (edgeKm > 1e-9) ((canonicalKm - cumWalkKm) / edgeKm).coerceIn(0.0, 1.0) else 1.0
                    val interp = interpolate(p0, p1, t)
                    val actualCanonical = cumWalkKm + t * edgeKm
                    val reportedKm = if (isReverse) (total - actualCanonical).coerceIn(0.0, total) else actualCanonical
                    return TrailProjection(interp.lat, interp.lng, interp.ele, reportedKm, 0.0)
                }
                cumWalkKm = nextKm
            }
        }

        val lastPt = walkSegments.last().points.last()
        return TrailProjection(lastPt.lat, lastPt.lng, lastPt.ele, if (isReverse) 0.0 else cumWalkKm, 0.0)
    }

    fun pointAtKm(
        segments: List<TrailSegmentDto>,
        targetKm: Double,
        isReverse: Boolean = false
    ): GeoPointDto? {
        val proj = pointAtTrailKm(segments, targetKm, isReverse) ?: return null
        return GeoPointDto(proj.lat, proj.lng, proj.elevationM)
    }

    /**
     * Generates km markers every [stepKm] (e.g. 5 km: 5, 10, 15, ...) along the trail.
     */
    fun generateKmMarkers(
        segments: List<TrailSegmentDto>,
        stepKm: Double = 5.0,
        isReverse: Boolean = false
    ): List<TrailKmMarker> {
        val totalKm = totalTrailWalkKm(segments)
        if (totalKm < stepKm) return emptyList()
        val result = mutableListOf<TrailKmMarker>()
        var km = stepKm
        while (km < totalKm - 0.05) {
            val pt = pointAtTrailKm(segments, km, isReverse)
            if (pt != null) {
                val kmInt = km.toInt()
                result.add(TrailKmMarker(km = kmInt, lat = pt.lat, lng = pt.lng, label = "km $kmInt"))
            }
            km += stepKm
        }
        return result
    }

    private fun interpolate(p0: GeoPointDto, p1: GeoPointDto, t: Double): GeoPointDto {
        val lat = p0.lat + (p1.lat - p0.lat) * t
        val lng = p0.lng + (p1.lng - p0.lng) * t
        val ele = if (p0.ele != null && p1.ele != null) {
            p0.ele + (p1.ele - p0.ele) * t
        } else {
            p0.ele ?: p1.ele
        }
        return GeoPointDto(lat = lat, lng = lng, ele = ele)
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    fun formatDetourCost(name: String?, detourCostKm: Double?, detourAscentM: Double?): String {
        val costKm = detourCostKm ?: 0.0
        val labelName = name?.takeIf { it.isNotBlank() } ?: "punkt"
        val kmStr = String.format(java.util.Locale.GERMANY, "%.1f", costKm)
        val ascM = kotlin.math.round(detourAscentM ?: 0.0).toInt()
        return "Omvej til $labelName: +$kmStr km, +$ascM m"
    }

    data class DetourBuildResult(
        val exitHelper: RouteWaypoint,
        val detourWaypoint: RouteWaypoint,
        val rejoinHelper: RouteWaypoint,
        val legToExit: LegGeometryData,
        val legExitToPin: LegGeometryData?,
        val legPinToRejoin: LegGeometryData?,
        val legRejoinToNext: LegGeometryData,
        val detourCostKm: Double,
        val detourAscentM: Double,
        val failedLegs: Map<String, String> = emptyMap()
    ) {
        val legs: List<LegGeometryData>
            get() = listOfNotNull(legToExit, legExitToPin, legPinToRejoin, legRejoinToNext)
    }

    /**
     * Requirement D & Fix Round 5 Item 8: Builds a TRAIL -> SNAP (exit to pin) -> SNAP (pin to rejoin) -> TRAIL detour
     * between [prevWp] and [nextWp] (both on the trail with trailKm != null).
     * Tests candidate exit/rejoin points on the trail (projected point, 500 m before, 500 m after)
     * with [routingEngine] and chooses ONLY among candidates that routed successfully.
     * If none did, records a failed leg so the UI renders a red dashed line with "Prøv igen" / "Tegn lige linje".
     */
    suspend fun buildTrailDetour(
        segments: List<TrailSegmentDto>,
        prevWp: RouteWaypoint,
        nextWp: RouteWaypoint,
        pinWp: RouteWaypoint,
        routingEngine: RoutingEngine,
        options: RouteOptions = RouteOptions(),
        isReverse: Boolean = false
    ): DetourBuildResult {
        val total = totalTrailWalkKm(segments)
        val rawPrevKm = prevWp.trailKm ?: projectOntoTrail(segments, prevWp.lat, prevWp.lng, isReverse)?.trailKm ?: 0.0
        val rawNextKm = nextWp.trailKm ?: projectOntoTrail(segments, nextWp.lat, nextWp.lng, isReverse)?.trailKm ?: total

        val canonicalPrevKm = if (isReverse) (total - rawPrevKm).coerceIn(0.0, total) else rawPrevKm
        val canonicalNextKm = if (isReverse) (total - rawNextKm).coerceIn(0.0, total) else rawNextKm

        val isCanonicalReversed = canonicalPrevKm > canonicalNextKm
        val minBoundKm = min(canonicalPrevKm, canonicalNextKm)
        val maxBoundKm = max(canonicalPrevKm, canonicalNextKm)

        val baseProj = projectOntoTrail(segments, pinWp.lat, pinWp.lng, minBoundKm, maxBoundKm)
            ?: pointAtTrailKm(segments, (canonicalPrevKm + canonicalNextKm) / 2.0, isReverse = false)!!

        val pinLatLng = LatLng(pinWp.lat, pinWp.lng)
        val dirSign = if (isCanonicalReversed) -1.0 else 1.0
        val candidateKms = listOf(
            (baseProj.trailKm - 0.5 * dirSign).coerceIn(minBoundKm, maxBoundKm),
            baseProj.trailKm.coerceIn(minBoundKm, maxBoundKm),
            (baseProj.trailKm + 0.5 * dirSign).coerceIn(minBoundKm, maxBoundKm)
        ).distinctBy { (it * 1000.0).toInt() }

        val candidateProjections = candidateKms.mapNotNull { pointAtTrailKm(segments, it, isReverse = false) }
            .ifEmpty { listOf(baseProj) }

        data class RoutedCandidate(
            val proj: TrailProjection,
            val points: List<RoutePoint3D>,
            val wayInfo: List<WayInfo>,
            val lengthKm: Double,
            val ascentM: Double,
            val descentM: Double,
            val sourceName: String
        )

        fun extractErrorMessage(res: RouteLegResult): String = when (res) {
            is RouteLegResult.Offline -> res.message
            is RouteLegResult.NoRoute -> res.message
            is RouteLegResult.ServerBusy -> res.message
            is RouteLegResult.Failure -> res.message
            is RouteLegResult.Success -> ""
        }.ifBlank { "Kunne ikke beregne rute til punktet" }

        // 1. Find best exit candidate among candidates that routed successfully
        val exitCandidates = mutableListOf<RoutedCandidate>()
        var lastExitError = "Kunne ikke beregne rute til punktet"
        for (cand in candidateProjections) {
            val res = routingEngine.routeLeg(
                from = LatLng(cand.lat, cand.lng),
                to = pinLatLng,
                options = options,
                alternative = 0
            )
            when (res) {
                is RouteLegResult.Success -> {
                    exitCandidates.add(
                        RoutedCandidate(
                            proj = cand,
                            points = res.points,
                            wayInfo = res.perPointWayInfo,
                            lengthKm = res.lengthKm,
                            ascentM = res.ascentM,
                            descentM = res.descentM,
                            sourceName = res.sourceName
                        )
                    )
                }
                else -> {
                    lastExitError = extractErrorMessage(res)
                }
            }
        }
        val bestExit = exitCandidates.minByOrNull { it.lengthKm }
        val exitProj = bestExit?.proj ?: baseProj

        // 2. Find best rejoin candidate after or at exit among candidates that routed successfully
        val validRejoinProjs = candidateProjections.filter {
            if (!isCanonicalReversed) it.trailKm >= exitProj.trailKm - 1e-6
            else it.trailKm <= exitProj.trailKm + 1e-6
        }.ifEmpty { listOf(exitProj) }

        val rejoinCandidates = mutableListOf<RoutedCandidate>()
        var lastRejoinError = lastExitError
        for (cand in validRejoinProjs) {
            val matchedExit = exitCandidates.find { kotlin.math.abs(it.proj.trailKm - cand.trailKm) < 1e-5 }
            if (matchedExit != null) {
                rejoinCandidates.add(
                    RoutedCandidate(
                        proj = cand,
                        points = matchedExit.points.reversed(),
                        wayInfo = matchedExit.wayInfo.reversed(),
                        lengthKm = matchedExit.lengthKm,
                        ascentM = matchedExit.descentM,
                        descentM = matchedExit.ascentM,
                        sourceName = matchedExit.sourceName
                    )
                )
            } else {
                val res = routingEngine.routeLeg(
                    from = pinLatLng,
                    to = LatLng(cand.lat, cand.lng),
                    options = options,
                    alternative = 0
                )
                when (res) {
                    is RouteLegResult.Success -> {
                        rejoinCandidates.add(
                            RoutedCandidate(
                                proj = cand,
                                points = res.points,
                                wayInfo = res.perPointWayInfo,
                                lengthKm = res.lengthKm,
                                ascentM = res.ascentM,
                                descentM = res.descentM,
                                sourceName = res.sourceName
                            )
                        )
                    }
                    else -> {
                        lastRejoinError = extractErrorMessage(res)
                    }
                }
            }
        }
        val bestRejoin = rejoinCandidates.minByOrNull { it.lengthKm }
        val rejoinProj = bestRejoin?.proj ?: exitProj

        // 3. Calculate detour cost compared to staying on the trail between exit and rejoin (only when routed)
        val bypassedSlice = sliceTrail(segments, exitProj.trailKm, rejoinProj.trailKm)
        val detourCostKm = if (bestExit != null && bestRejoin != null) {
            max(0.001, (bestExit.lengthKm + bestRejoin.lengthKm) - bypassedSlice.lengthKm)
        } else {
            0.0
        }
        val detourAscentM = if (bestExit != null && bestRejoin != null) {
            max(0.0, (bestExit.ascentM + bestRejoin.ascentM) - bypassedSlice.ascentM)
        } else {
            0.0
        }

        val exitReportedKm = if (isReverse) (total - exitProj.trailKm).coerceIn(0.0, total) else exitProj.trailKm
        val rejoinReportedKm = if (isReverse) (total - rejoinProj.trailKm).coerceIn(0.0, total) else rejoinProj.trailKm

        val exitHelper = RouteWaypoint(
            id = "exit_${pinWp.id}",
            lat = exitProj.lat,
            lng = exitProj.lng,
            role = WaypointRole.VIA,
            legModeToNext = LegMode.SNAP,
            trailKm = exitReportedKm,
            isHelper = true,
            parentDetourWaypointId = pinWp.id,
            name = "Exit"
        )

        val updatedPinWp = pinWp.copy(
            legModeToNext = LegMode.SNAP,
            trailKm = null,
            isHelper = false,
            detourCostKm = if (bestExit != null && bestRejoin != null) detourCostKm else null,
            detourAscentM = if (bestExit != null && bestRejoin != null) detourAscentM else null
        )

        val rejoinHelper = RouteWaypoint(
            id = "rejoin_${pinWp.id}",
            lat = rejoinProj.lat,
            lng = rejoinProj.lng,
            role = WaypointRole.VIA,
            legModeToNext = LegMode.TRAIL,
            trailKm = rejoinReportedKm,
            isHelper = true,
            parentDetourWaypointId = pinWp.id,
            name = "Rejoin"
        )

        val sliceToExit = sliceTrail(segments, canonicalPrevKm, exitProj.trailKm)
        val (toExitPathShare, toExitRoadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(sliceToExit.points, sliceToExit.wayInfo)
        val legToExit = LegGeometryData(
            fromWaypointId = prevWp.id,
            toWaypointId = exitHelper.id,
            legMode = LegMode.TRAIL,
            points = sliceToExit.points,
            lengthKm = sliceToExit.lengthKm,
            ascentM = sliceToExit.ascentM,
            descentM = sliceToExit.descentM,
            pathSharePct = toExitPathShare,
            roadKm = toExitRoadKm ?: 0.0,
            sourceName = "Trail",
            wayInfo = sliceToExit.wayInfo,
            trailSegments = sliceToExit.trailSegments
        )

        val failedLegs = mutableMapOf<String, String>()
        val exitLegKey = "${exitHelper.id}_to_${updatedPinWp.id}"
        val legExitToPin = if (bestExit != null) {
            val (exitPathShare, exitRoadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(bestExit.points, bestExit.wayInfo)
            LegGeometryData(
                fromWaypointId = exitHelper.id,
                toWaypointId = updatedPinWp.id,
                legMode = LegMode.SNAP,
                points = bestExit.points,
                lengthKm = bestExit.lengthKm,
                ascentM = bestExit.ascentM,
                descentM = bestExit.descentM,
                pathSharePct = exitPathShare,
                roadKm = exitRoadKm ?: 0.0,
                sourceName = bestExit.sourceName,
                wayInfo = bestExit.wayInfo
            )
        } else {
            failedLegs[exitLegKey] = lastExitError
            null
        }

        val rejoinLegKey = "${updatedPinWp.id}_to_${rejoinHelper.id}"
        val legPinToRejoin = if (bestRejoin != null) {
            val (rejoinPathShare, rejoinRoadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(bestRejoin.points, bestRejoin.wayInfo)
            LegGeometryData(
                fromWaypointId = updatedPinWp.id,
                toWaypointId = rejoinHelper.id,
                legMode = LegMode.SNAP,
                points = bestRejoin.points,
                lengthKm = bestRejoin.lengthKm,
                ascentM = bestRejoin.ascentM,
                descentM = bestRejoin.descentM,
                pathSharePct = rejoinPathShare,
                roadKm = rejoinRoadKm ?: 0.0,
                sourceName = bestRejoin.sourceName,
                wayInfo = bestRejoin.wayInfo
            )
        } else {
            failedLegs[rejoinLegKey] = lastRejoinError
            null
        }

        val sliceFromRejoin = sliceTrail(segments, rejoinProj.trailKm, canonicalNextKm)
        val (fromRejoinPathShare, fromRejoinRoadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(sliceFromRejoin.points, sliceFromRejoin.wayInfo)
        val legRejoinToNext = LegGeometryData(
            fromWaypointId = rejoinHelper.id,
            toWaypointId = nextWp.id,
            legMode = LegMode.TRAIL,
            points = sliceFromRejoin.points,
            lengthKm = sliceFromRejoin.lengthKm,
            ascentM = sliceFromRejoin.ascentM,
            descentM = sliceFromRejoin.descentM,
            pathSharePct = fromRejoinPathShare,
            roadKm = fromRejoinRoadKm ?: 0.0,
            sourceName = "Trail",
            wayInfo = sliceFromRejoin.wayInfo,
            trailSegments = sliceFromRejoin.trailSegments
        )

        return DetourBuildResult(
            exitHelper = exitHelper,
            detourWaypoint = updatedPinWp,
            rejoinHelper = rejoinHelper,
            legToExit = legToExit,
            legExitToPin = legExitToPin,
            legPinToRejoin = legPinToRejoin,
            legRejoinToNext = legRejoinToNext,
            detourCostKm = detourCostKm,
            detourAscentM = detourAscentM,
            failedLegs = failedLegs
        )
    }

    /**
     * Builds a LegGeometryData for a pure TRAIL leg between two trail waypoints.
     */
    fun buildTrailLegGeometry(
        segments: List<TrailSegmentDto>,
        fromWp: RouteWaypoint,
        toWp: RouteWaypoint
    ): LegGeometryData {
        val fromKm = fromWp.trailKm ?: projectOntoTrail(segments, fromWp.lat, fromWp.lng)?.trailKm ?: 0.0
        val toKm = toWp.trailKm ?: projectOntoTrail(segments, toWp.lat, toWp.lng)?.trailKm ?: 0.0
        return sliceTrailLeg(
            fromWaypointId = fromWp.id,
            toWaypointId = toWp.id,
            segments = segments,
            fromKm = fromKm,
            toKm = toKm,
            isReverse = false
        )
    }

    fun buildTrailLegGeometry(
        fromWaypointId: String,
        toWaypointId: String,
        segments: List<TrailSegmentDto>,
        fromKm: Double,
        toKm: Double,
        isReverse: Boolean = false
    ): LegGeometryData {
        return sliceTrailLeg(
            fromWaypointId = fromWaypointId,
            toWaypointId = toWaypointId,
            segments = segments,
            fromKm = fromKm,
            toKm = toKm,
            isReverse = isReverse
        )
    }

    fun sliceTrailLeg(
        fromWaypointId: String,
        toWaypointId: String,
        segments: List<TrailSegmentDto>,
        fromKm: Double,
        toKm: Double,
        isReverse: Boolean = false
    ): LegGeometryData {
        val total = totalTrailWalkKm(segments)
        val canonicalFrom = if (isReverse) (total - fromKm).coerceIn(0.0, total) else fromKm
        val canonicalTo = if (isReverse) (total - toKm).coerceIn(0.0, total) else toKm
        val sliced = sliceTrail(segments, canonicalFrom, canonicalTo)
        val (pathShare, roadKm) = RouteGeometryStorageManager.computeWaySectionMetrics(sliced.points, sliced.wayInfo)
        return LegGeometryData(
            fromWaypointId = fromWaypointId,
            toWaypointId = toWaypointId,
            legMode = LegMode.TRAIL,
            points = sliced.points,
            lengthKm = sliced.lengthKm,
            ascentM = sliced.ascentM,
            descentM = sliced.descentM,
            pathSharePct = pathShare,
            roadKm = roadKm ?: 0.0,
            sourceName = "Trail",
            wayInfo = sliced.wayInfo,
            trailSegments = sliced.trailSegments
        )
    }

    /**
     * Requirement D: Removing a pin removes its helpers and restores the plain TRAIL leg.
     */
    fun removeDetourAndRestoreTrailLeg(
        waypoints: List<RouteWaypoint>,
        legs: Map<String, LegGeometryData>,
        removedWaypointId: String,
        segments: List<TrailSegmentDto>,
        isReverse: Boolean = false
    ): Pair<List<RouteWaypoint>, Map<String, LegGeometryData>> {
        val removedIds = waypoints
            .filter { it.id == removedWaypointId || it.parentDetourWaypointId == removedWaypointId }
            .map { it.id }
            .toSet()

        val remainingWps = waypoints.filterNot { it.id in removedIds }.toMutableList()
        val updatedLegs = legs.filterKeys { key ->
            val parts = key.split("_to_")
            parts.size == 2 && parts[0] !in removedIds && parts[1] !in removedIds
        }.toMutableMap()

        for (i in 0 until remainingWps.size - 1) {
            val from = remainingWps[i]
            val to = remainingWps[i + 1]
            val legKey = "${from.id}_to_${to.id}"
            if (!updatedLegs.containsKey(legKey) && from.trailKm != null && to.trailKm != null) {
                remainingWps[i] = from.copy(legModeToNext = LegMode.TRAIL)
                val restoredLeg = sliceTrailLeg(
                    fromWaypointId = from.id,
                    toWaypointId = to.id,
                    segments = segments,
                    fromKm = from.trailKm,
                    toKm = to.trailKm,
                    isReverse = isReverse
                )
                updatedLegs[legKey] = restoredLeg
            }
        }

        return Pair(remainingWps, updatedLegs)
    }
}
