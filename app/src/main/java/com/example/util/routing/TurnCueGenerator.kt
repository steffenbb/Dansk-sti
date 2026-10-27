package com.example.util.routing

import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TurnCue
import com.example.data.model.WayTagRangeDto
import com.example.util.GeoUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes and formats turn-by-turn navigation cues for both BRouter (SNAP) legs
 * and geometry-based (TRAIL) legs.
 */
object TurnCueGenerator {

    const val TURN_ANGLE_THRESHOLD_DEG = 35.0
    const val TURN_WINDOW_METERS = 30.0
    const val CUE_FIRST_ALERT_METERS = 150.0
    const val CUE_SECOND_ALERT_METERS = 30.0
    const val CUE_MAX_DISPLAY_METERS = 500.0

    /**
     * Parses BRouter GeoJSON `voicehints` array (produced when `timode=2` is set)
     * into [TurnCue] items along the leg.
     * Falls back to geometry-based turn cue detection if `voiceHints` is null or empty.
     */
    fun parseBRouterVoiceHints(
        voiceHints: JSONArray?,
        points: List<RoutePoint3D>,
        perPointWayInfo: List<WayInfo> = emptyList()
    ): List<TurnCue> {
        if (points.size < 2) return emptyList()

        val cumulativeKm = DoubleArray(points.size)
        var accM = 0.0
        for (i in 1 until points.size) {
            accM += GeoUtils.distanceMeters(
                points[i - 1].lat,
                points[i - 1].lng,
                points[i].lat,
                points[i].lng
            )
            cumulativeKm[i] = accM / 1000.0
        }

        if (voiceHints != null && voiceHints.length() > 0) {
            val cues = mutableListOf<TurnCue>()
            for (i in 0 until voiceHints.length()) {
                val hint = voiceHints.optJSONArray(i) ?: continue
                if (hint.length() < 2) continue
                val ptIdx = hint.optInt(0, -1).coerceIn(0, points.lastIndex)
                val cmdRaw = hint.opt(1)
                val angle = if (hint.length() > 3) hint.optDouble(3, 0.0) else 0.0

                val (dir, text) = mapBRouterCommand(cmdRaw, angle) ?: continue
                val pt = points[ptIdx]
                cues.add(
                    TurnCue(
                        lat = pt.lat,
                        lng = pt.lng,
                        walkKm = cumulativeKm[ptIdx],
                        direction = dir,
                        angleDegrees = angle,
                        instructionText = text,
                        pointIndex = ptIdx
                    )
                )
            }
            if (cues.isNotEmpty()) {
                return cues
            }
        }

        return computeTurnCuesFromPointsAndWayInfo(points, perPointWayInfo)
    }

    private fun mapBRouterCommand(cmdRaw: Any?, angle: Double): Pair<String, String>? {
        return when (cmdRaw) {
            is Number -> when (cmdRaw.toInt()) {
                2 -> "LEFT" to "Drej til venstre"
                3, 8 -> "SLIGHT_LEFT" to "Hold til venstre"
                4 -> "SHARP_LEFT" to "Drej skarpt til venstre"
                5 -> "RIGHT" to "Drej til højre"
                6, 9 -> "SLIGHT_RIGHT" to "Hold til højre"
                7 -> "SHARP_RIGHT" to "Drej skarpt til højre"
                10 -> "UTURN" to "Vend om"
                else -> null
            }
            is String -> when (cmdRaw.uppercase()) {
                "TL" -> "LEFT" to "Drej til venstre"
                "TSLL", "KL" -> "SLIGHT_LEFT" to "Hold til venstre"
                "TSHL" -> "SHARP_LEFT" to "Drej skarpt til venstre"
                "TR" -> "RIGHT" to "Drej til højre"
                "TSLR", "KR" -> "SLIGHT_RIGHT" to "Hold til højre"
                "TSHR" -> "SHARP_RIGHT" to "Drej skarpt til højre"
                "TU", "TRU" -> "UTURN" to "Vend om"
                else -> null
            }
            else -> {
                if (abs(angle) > TURN_ANGLE_THRESHOLD_DEG) {
                    classifyAngle(angle)
                } else null
            }
        }
    }

    /**
     * Requirement 2: For TRAIL legs, compute cues from the geometry:
     * a cue where the route turns more than 35 degrees within 30 m and
     * the way (or its highway tag range) changes.
     */
    fun computeTrailTurnCues(
        segments: List<TrailSegmentDto>,
        points: List<RoutePoint3D> = emptyList(),
        wayInfo: List<WayInfo> = emptyList()
    ): List<TurnCue> {
        val walkSegments = segments.filter { it.type == TrailSegmentType.WALK && it.points.size >= 2 }
        if (walkSegments.isEmpty()) {
            return if (points.size >= 3) computeTurnCuesFromPointsAndWayInfo(points, wayInfo) else emptyList()
        }

        data class TaggedVertex(
            val lat: Double,
            val lng: Double,
            val walkKm: Double,
            val incomingWayKey: String,
            val outgoingWayKey: String,
            val pointIndex: Int
        )

        // Build per-edge way keys across all WALK segments
        data class EdgeItem(
            val p0: GeoPointDto,
            val p1: GeoPointDto,
            val lengthM: Double,
            val wayKey: String
        )

        val edges = mutableListOf<EdgeItem>()
        for ((segIdx, seg) in walkSegments.withIndex()) {
            val pts = seg.points
            var segLocalM = 0.0
            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val dM = GeoUtils.distanceMeters(p0.lat, p0.lng, p1.lat, p1.lng)
                if (dM < 0.05) continue
                val midLocalKm = (segLocalM + dM / 2.0) / 1000.0
                segLocalM += dM

                val wayKey = resolveWayKeyForEdge(segIdx, seg, midLocalKm, edges.size, wayInfo)
                edges.add(EdgeItem(p0 = p0, p1 = p1, lengthM = dM, wayKey = wayKey))
            }
        }

        if (edges.size < 2) return emptyList()

        // Build vertex chain with cumulative walkKm
        val vertices = mutableListOf<TaggedVertex>()
        var cumM = 0.0
        vertices.add(
            TaggedVertex(
                lat = edges[0].p0.lat,
                lng = edges[0].p0.lng,
                walkKm = 0.0,
                incomingWayKey = edges[0].wayKey,
                outgoingWayKey = edges[0].wayKey,
                pointIndex = 0
            )
        )

        for (i in edges.indices) {
            val e = edges[i]
            cumM += e.lengthM
            val nextEdge = edges.getOrNull(i + 1)
            // If next edge is disconnected by > 20m (gap), keep same key so gap isn't a turn cue
            val isConnected = nextEdge != null &&
                GeoUtils.distanceMeters(e.p1.lat, e.p1.lng, nextEdge.p0.lat, nextEdge.p0.lng) <= 20.0
            val outKey = if (isConnected && nextEdge != null) nextEdge.wayKey else e.wayKey

            vertices.add(
                TaggedVertex(
                    lat = e.p1.lat,
                    lng = e.p1.lng,
                    walkKm = cumM / 1000.0,
                    incomingWayKey = e.wayKey,
                    outgoingWayKey = outKey,
                    pointIndex = i + 1
                )
            )
        }

        val coords = vertices.map { it.lat to it.lng }
        val edgeKeys = Array(edges.size) { edges[it].wayKey }

        val cues = mutableListOf<TurnCue>()
        for (i in 1 until vertices.size - 1) {
            val v = vertices[i]

            // Check if the way (or its highway tag range) changes within 30 m of vertex i
            val wayChangesNearVertex = wayChangesAroundIndex(coords, edgeKeys, i, TURN_WINDOW_METERS)
            if (!wayChangesNearVertex) continue

            // Measure turn angle within 30 m window around vertex i
            val (inLat, inLng) = pointAlongChainBackward(coords, i, TURN_WINDOW_METERS)
            val (outLat, outLng) = pointAlongChainForward(coords, i, TURN_WINDOW_METERS)

            val dIn = GeoUtils.distanceMeters(inLat, inLng, v.lat, v.lng)
            val dOut = GeoUtils.distanceMeters(v.lat, v.lng, outLat, outLng)
            if (dIn < 0.5 || dOut < 0.5) continue

            val bearingIn = GeoUtils.bearing(inLat, inLng, v.lat, v.lng).toDouble()
            val bearingOut = GeoUtils.bearing(v.lat, v.lng, outLat, outLng).toDouble()
            val turnAngle = normalizeSignedAngle(bearingOut - bearingIn)

            if (abs(turnAngle) > TURN_ANGLE_THRESHOLD_DEG) {
                val (dir, instruction) = classifyAngle(turnAngle)
                val lastCue = cues.lastOrNull()
                if (lastCue != null && (v.walkKm - lastCue.walkKm) * 1000.0 < 25.0) {
                    if (abs(turnAngle) > abs(lastCue.angleDegrees)) {
                        cues[cues.lastIndex] = TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = v.walkKm,
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = v.pointIndex
                        )
                    }
                } else {
                    cues.add(
                        TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = v.walkKm,
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = v.pointIndex
                        )
                    )
                }
            }
        }

        return cues
    }

    private fun resolveWayKeyForEdge(
        segIdx: Int,
        seg: TrailSegmentDto,
        midLocalKm: Double,
        globalEdgeIdx: Int,
        wayInfo: List<WayInfo>
    ): String {
        val ranges: List<WayTagRangeDto> = seg.wayTagRanges
        if (ranges.isNotEmpty()) {
            val eps = 1e-6
            val idx = ranges.indexOfFirst { midLocalKm >= it.fromKm - eps && midLocalKm <= it.toKm + eps }
            if (idx >= 0) {
                val r = ranges[idx]
                return "seg${segIdx}_r${idx}_${r.highway.orEmpty()}_${r.surface.orEmpty()}"
            }
        }
        val wi = wayInfo.getOrNull(globalEdgeIdx)
        if (wi != null && (!wi.highway.isNullOrBlank() || !wi.surface.isNullOrBlank() || wi.rawTags.isNotEmpty())) {
            val wayIdTag = wi.rawTags["id"] ?: wi.rawTags["way_id"] ?: wi.rawTags["name"] ?: ""
            return "wi_${wi.highway.orEmpty()}_${wi.surface.orEmpty()}_${wi.tracktype.orEmpty()}_$wayIdTag"
        }
        return "seg${segIdx}_${seg.highway.orEmpty()}_${seg.surface.orEmpty()}"
    }

    fun computeTurnCuesFromPointsAndWayInfo(
        points: List<RoutePoint3D>,
        wayInfo: List<WayInfo>
    ): List<TurnCue> {
        if (points.size < 3) return emptyList()
        val cumKm = DoubleArray(points.size)
        var accM = 0.0
        for (i in 1 until points.size) {
            accM += GeoUtils.distanceMeters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
            cumKm[i] = accM / 1000.0
        }

        val edgeKeys = Array(points.size - 1) { i ->
            val w = wayInfo.getOrNull(i)
            if (w != null && (!w.highway.isNullOrBlank() || !w.surface.isNullOrBlank() || w.rawTags.isNotEmpty())) {
                "${w.highway.orEmpty()}_${w.surface.orEmpty()}_${w.tracktype.orEmpty()}_${w.rawTags["name"].orEmpty()}"
            } else {
                "edge_$i"
            }
        }

        val coords = points.map { it.lat to it.lng }
        val cues = mutableListOf<TurnCue>()

        for (i in 1 until points.size - 1) {
            val wayChanged = if (wayInfo.isNotEmpty()) {
                wayChangesAroundIndex(coords, edgeKeys, i, TURN_WINDOW_METERS)
            } else {
                true
            }
            if (!wayChanged) continue

            val (inLat, inLng) = pointAlongChainBackward(coords, i, TURN_WINDOW_METERS)
            val (outLat, outLng) = pointAlongChainForward(coords, i, TURN_WINDOW_METERS)
            val v = points[i]

            val dIn = GeoUtils.distanceMeters(inLat, inLng, v.lat, v.lng)
            val dOut = GeoUtils.distanceMeters(v.lat, v.lng, outLat, outLng)
            if (dIn < 0.5 || dOut < 0.5) continue

            val bearingIn = GeoUtils.bearing(inLat, inLng, v.lat, v.lng).toDouble()
            val bearingOut = GeoUtils.bearing(v.lat, v.lng, outLat, outLng).toDouble()
            val turnAngle = normalizeSignedAngle(bearingOut - bearingIn)

            if (abs(turnAngle) > TURN_ANGLE_THRESHOLD_DEG) {
                val (dir, instruction) = classifyAngle(turnAngle)
                val lastCue = cues.lastOrNull()
                if (lastCue != null && (cumKm[i] - lastCue.walkKm) * 1000.0 < 25.0) {
                    if (abs(turnAngle) > abs(lastCue.angleDegrees)) {
                        cues[cues.lastIndex] = TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = cumKm[i],
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = i
                        )
                    }
                } else {
                    cues.add(
                        TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = cumKm[i],
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = i
                        )
                    )
                }
            }
        }
        return cues
    }

    /**
     * Computes turn cues from a [PlannedRoute] if `route.turnCues` is empty.
     */
    fun computeTurnCuesForPlannedRoute(route: PlannedRoute): List<TurnCue> {
        if (route.turnCues.isNotEmpty()) return route.turnCues
        val pts = route.points
        if (pts.size < 3) return emptyList()

        val coords = pts.map { it.lat to it.lng }
        val edgeKeys = Array(pts.size - 1) { i ->
            "seg_${pts[i].segmentIndex}_${pts[i].segmentType}"
        }
        val hasMultipleSegments = pts.map { it.segmentIndex }.distinct().size > 1

        val cues = mutableListOf<TurnCue>()
        for (i in 1 until pts.size - 1) {
            if (pts[i].segmentType == TrailSegmentType.FERRY) continue
            val wayChanged = if (hasMultipleSegments) {
                wayChangesAroundIndex(coords, edgeKeys, i, TURN_WINDOW_METERS)
            } else {
                true
            }
            if (!wayChanged) continue

            val (inLat, inLng) = pointAlongChainBackward(coords, i, TURN_WINDOW_METERS)
            val (outLat, outLng) = pointAlongChainForward(coords, i, TURN_WINDOW_METERS)
            val v = pts[i]

            val dIn = GeoUtils.distanceMeters(inLat, inLng, v.lat, v.lng)
            val dOut = GeoUtils.distanceMeters(v.lat, v.lng, outLat, outLng)
            if (dIn < 0.5 || dOut < 0.5) continue

            val bearingIn = GeoUtils.bearing(inLat, inLng, v.lat, v.lng).toDouble()
            val bearingOut = GeoUtils.bearing(v.lat, v.lng, outLat, outLng).toDouble()
            val turnAngle = normalizeSignedAngle(bearingOut - bearingIn)

            if (abs(turnAngle) > TURN_ANGLE_THRESHOLD_DEG) {
                val (dir, instruction) = classifyAngle(turnAngle)
                val lastCue = cues.lastOrNull()
                if (lastCue != null && (v.walkKm - lastCue.walkKm) * 1000.0 < 25.0) {
                    if (abs(turnAngle) > abs(lastCue.angleDegrees)) {
                        cues[cues.lastIndex] = TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = v.walkKm,
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = i
                        )
                    }
                } else {
                    cues.add(
                        TurnCue(
                            lat = v.lat,
                            lng = v.lng,
                            walkKm = v.walkKm,
                            direction = dir,
                            angleDegrees = turnAngle,
                            instructionText = instruction,
                            pointIndex = i
                        )
                    )
                }
            }
        }
        return cues
    }

    private fun wayChangesAroundIndex(
        coords: List<Pair<Double, Double>>,
        edgeKeys: Array<String>,
        vertexIdx: Int,
        windowMeters: Double
    ): Boolean {
        val inKey = edgeKeys[(vertexIdx - 1).coerceIn(0, edgeKeys.lastIndex)]
        val outKey = edgeKeys[vertexIdx.coerceIn(0, edgeKeys.lastIndex)]
        if (inKey != outKey) return true

        // Check backward within windowMeters
        var backDist = 0.0
        var k = vertexIdx - 1
        while (k >= 0 && backDist <= windowMeters) {
            if (edgeKeys[k] != outKey) return true
            val d = GeoUtils.distanceMeters(coords[k].first, coords[k].second, coords[k + 1].first, coords[k + 1].second)
            backDist += d
            k--
        }

        // Check forward within windowMeters
        var fwdDist = 0.0
        var f = vertexIdx
        while (f < edgeKeys.size && fwdDist <= windowMeters) {
            if (edgeKeys[f] != inKey) return true
            val d = GeoUtils.distanceMeters(coords[f].first, coords[f].second, coords[f + 1].first, coords[f + 1].second)
            fwdDist += d
            f++
        }
        return false
    }

    internal fun pointAlongChainBackward(
        coords: List<Pair<Double, Double>>,
        fromIndex: Int,
        maxMeters: Double
    ): Pair<Double, Double> {
        var rem = maxMeters
        var idx = fromIndex
        while (idx > 0 && rem > 0.0) {
            val cur = coords[idx]
            val prev = coords[idx - 1]
            val segM = GeoUtils.distanceMeters(prev.first, prev.second, cur.first, cur.second)
            if (segM >= rem && segM > 1e-6) {
                val t = (rem / segM).coerceIn(0.0, 1.0)
                val lat = cur.first + (prev.first - cur.first) * t
                val lng = cur.second + (prev.second - cur.second) * t
                return lat to lng
            }
            rem -= segM
            idx--
        }
        return coords[idx.coerceAtLeast(0)]
    }

    internal fun pointAlongChainForward(
        coords: List<Pair<Double, Double>>,
        fromIndex: Int,
        maxMeters: Double
    ): Pair<Double, Double> {
        var rem = maxMeters
        var idx = fromIndex
        while (idx < coords.lastIndex && rem > 0.0) {
            val cur = coords[idx]
            val next = coords[idx + 1]
            val segM = GeoUtils.distanceMeters(cur.first, cur.second, next.first, next.second)
            if (segM >= rem && segM > 1e-6) {
                val t = (rem / segM).coerceIn(0.0, 1.0)
                val lat = cur.first + (next.first - cur.first) * t
                val lng = cur.second + (next.second - cur.second) * t
                return lat to lng
            }
            rem -= segM
            idx++
        }
        return coords[idx.coerceAtMost(coords.lastIndex)]
    }

    internal fun normalizeSignedAngle(angleDeg: Double): Double {
        var a = angleDeg % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    internal fun classifyAngle(signedAngleDeg: Double): Pair<String, String> {
        return when {
            signedAngleDeg <= -115.0 -> "SHARP_LEFT" to "Drej skarpt til venstre"
            signedAngleDeg <= -65.0 -> "LEFT" to "Drej til venstre"
            signedAngleDeg < -35.0 -> "SLIGHT_LEFT" to "Hold til venstre"
            signedAngleDeg >= 115.0 -> "SHARP_RIGHT" to "Drej skarpt til højre"
            signedAngleDeg >= 65.0 -> "RIGHT" to "Drej til højre"
            signedAngleDeg > 35.0 -> "SLIGHT_RIGHT" to "Hold til højre"
            else -> "STRAIGHT" to "Fortsæt ligeud"
        }
    }

    /**
     * Formats the top cue banner text, e.g. "Hold til venstre om 120 m"
     */
    fun formatCueBannerText(cue: TurnCue, distanceMeters: Double): String {
        val roundedMeters = when {
            distanceMeters >= 100.0 -> ((distanceMeters / 10.0).roundToInt() * 10).coerceAtLeast(10)
            distanceMeters >= 10.0 -> ((distanceMeters / 5.0).roundToInt() * 5).coerceAtLeast(5)
            else -> distanceMeters.roundToInt().coerceAtLeast(1)
        }
        return "${cue.instructionText} om $roundedMeters m"
    }

    fun toJsonArray(cues: List<TurnCue>): JSONArray {
        val arr = JSONArray()
        for (c in cues) {
            val obj = JSONObject()
            obj.put("lat", c.lat)
            obj.put("lng", c.lng)
            obj.put("walkKm", c.walkKm)
            obj.put("direction", c.direction)
            obj.put("angleDegrees", c.angleDegrees)
            obj.put("instructionText", c.instructionText)
            obj.put("pointIndex", c.pointIndex)
            arr.put(obj)
        }
        return arr
    }

    fun fromJsonArray(arr: JSONArray?): List<TurnCue> {
        if (arr == null) return emptyList()
        val list = ArrayList<TurnCue>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                TurnCue(
                    lat = obj.optDouble("lat", 0.0),
                    lng = obj.optDouble("lng", 0.0),
                    walkKm = obj.optDouble("walkKm", 0.0),
                    direction = obj.optString("direction", "STRAIGHT"),
                    angleDegrees = obj.optDouble("angleDegrees", 0.0),
                    instructionText = obj.optString("instructionText", "Fortsæt ligeud"),
                    pointIndex = obj.optInt("pointIndex", 0)
                )
            )
        }
        return list
    }
}
