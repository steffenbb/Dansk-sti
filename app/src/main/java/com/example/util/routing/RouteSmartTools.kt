package com.example.util.routing

import com.example.data.model.NearestPoiCategory
import com.example.data.model.PoiEntity
import com.example.data.model.RundturCandidate
import com.example.data.model.RoadSectionWarning
import com.example.data.model.RouteQualityBreakdown
import com.example.data.model.ShelterEntity
import com.example.data.model.FriTeltningEntity
import com.example.data.model.SupplyGapType
import com.example.data.model.SupplyGapWarning
import com.example.data.model.WaypointWeatherEta
import com.example.data.remote.WeatherApiService
import com.example.data.remote.WeatherInterpreter
import com.example.util.GeoUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object RouteSmartTools {

    /**
     * Phase 12 Feature 1: Vand og mad.
     * Finds stretches longer than maxWaterGapKm (default 12 km) with no water
     * and stretches longer than maxShopGapKm (default 25 km) with no shop.
     * Shows nearest detour option and extra distance.
     */
    fun analyzeSupplyGaps(
        routePoints: List<RoutePoint3D>,
        corridorPois: List<PoiEntity>,
        maxWaterGapKm: Double = 12.0,
        maxShopGapKm: Double = 25.0,
        allDbWaterPois: List<PoiEntity> = emptyList(),
        allDbShopPois: List<PoiEntity> = emptyList()
    ): List<SupplyGapWarning> {
        if (routePoints.size < 2) return emptyList()

        val geoRoute = routePoints.map { com.example.data.model.GeoPointDto(it.lat, it.lng) }
        val totalKm = calculateCumulativeKm(routePoints)
        if (totalKm <= 0.0) return emptyList()

        val warnings = mutableListOf<SupplyGapWarning>()

        // 1. Water POIs along route (detour <= 3000m)
        val waterPoisWithKm = corridorPois
            .filter { it.type.equals("water", ignoreCase = true) }
            .map { poi ->
                val (km, detour) = GeoUtils.projectPointOnPolyline(
                    com.example.data.model.GeoPointDto(poi.lat, poi.lng),
                    geoRoute
                )
                Triple(poi, km, detour)
            }
            .filter { it.third <= 3000.0 }
            .sortedBy { it.second }

        // Find water stretches
        findGaps(
            type = SupplyGapType.WATER,
            totalKm = totalKm,
            maxGapKm = maxWaterGapKm,
            pois = waterPoisWithKm,
            geoRoute = geoRoute,
            allCandidates = if (allDbWaterPois.isNotEmpty()) allDbWaterPois else corridorPois.filter { it.type.equals("water", ignoreCase = true) }
        )?.let { warnings.addAll(it) }

        // 2. Shop POIs along route (detour <= 3000m)
        val shopPoisWithKm = corridorPois
            .filter { it.type.equals("shop", ignoreCase = true) }
            .map { poi ->
                val (km, detour) = GeoUtils.projectPointOnPolyline(
                    com.example.data.model.GeoPointDto(poi.lat, poi.lng),
                    geoRoute
                )
                Triple(poi, km, detour)
            }
            .filter { it.third <= 3000.0 }
            .sortedBy { it.second }

        // Find shop stretches
        findGaps(
            type = SupplyGapType.SHOP,
            totalKm = totalKm,
            maxGapKm = maxShopGapKm,
            pois = shopPoisWithKm,
            geoRoute = geoRoute,
            allCandidates = if (allDbShopPois.isNotEmpty()) allDbShopPois else corridorPois.filter { it.type.equals("shop", ignoreCase = true) }
        )?.let { warnings.addAll(it) }

        return warnings
    }

    private fun findGaps(
        type: SupplyGapType,
        totalKm: Double,
        maxGapKm: Double,
        pois: List<Triple<PoiEntity, Double, Double>>,
        geoRoute: List<com.example.data.model.GeoPointDto>,
        allCandidates: List<PoiEntity>
    ): List<SupplyGapWarning> {
        val result = mutableListOf<SupplyGapWarning>()

        if (pois.isEmpty()) {
            if (totalKm > maxGapKm) {
                val detour = findNearestDetourCandidate(totalKm / 2.0, geoRoute, allCandidates)
                result.add(
                    SupplyGapWarning(
                        type = type,
                        startKm = 0.0,
                        endKm = totalKm,
                        gapKm = totalKm,
                        nearestDetourName = detour?.first?.name,
                        detourExtraMeters = detour?.second,
                        detourLat = detour?.first?.lat,
                        detourLng = detour?.first?.lng
                    )
                )
            }
            return result
        }

        // Gap from start to first POI
        val firstKm = pois.first().second
        if (firstKm > maxGapKm) {
            val detour = findNearestDetourCandidate(firstKm / 2.0, geoRoute, allCandidates)
            result.add(
                SupplyGapWarning(
                    type = type,
                    startKm = 0.0,
                    endKm = firstKm,
                    gapKm = firstKm,
                    nearestDetourName = detour?.first?.name,
                    detourExtraMeters = detour?.second,
                    detourLat = detour?.first?.lat,
                    detourLng = detour?.first?.lng
                )
            )
        }

        // Gaps between consecutive POIs
        for (i in 0 until pois.size - 1) {
            val currKm = pois[i].second
            val nextKm = pois[i + 1].second
            val gap = nextKm - currKm
            if (gap > maxGapKm) {
                val detour = findNearestDetourCandidate((currKm + nextKm) / 2.0, geoRoute, allCandidates)
                result.add(
                    SupplyGapWarning(
                        type = type,
                        startKm = currKm,
                        endKm = nextKm,
                        gapKm = gap,
                        nearestDetourName = detour?.first?.name,
                        detourExtraMeters = detour?.second,
                        detourLat = detour?.first?.lat,
                        detourLng = detour?.first?.lng
                    )
                )
            }
        }

        // Gap from last POI to end
        val lastKm = pois.last().second
        if (totalKm - lastKm > maxGapKm) {
            val detour = findNearestDetourCandidate((lastKm + totalKm) / 2.0, geoRoute, allCandidates)
            result.add(
                SupplyGapWarning(
                    type = type,
                    startKm = lastKm,
                    endKm = totalKm,
                    gapKm = totalKm - lastKm,
                    nearestDetourName = detour?.first?.name,
                    detourExtraMeters = detour?.second,
                    detourLat = detour?.first?.lat,
                    detourLng = detour?.first?.lng
                )
            )
        }

        return result
    }

    private fun findNearestDetourCandidate(
        targetKm: Double,
        geoRoute: List<com.example.data.model.GeoPointDto>,
        candidates: List<PoiEntity>
    ): Pair<PoiEntity, Double>? {
        if (candidates.isEmpty() || geoRoute.isEmpty()) return null

        // Approximate coordinate at targetKm
        val targetPt = findPointAtKm(geoRoute, targetKm) ?: return null

        var bestCandidate: PoiEntity? = null
        var minDetourMeters = Double.MAX_VALUE

        for (cand in candidates) {
            val straightDistM = GeoUtils.distanceMeters(targetPt.lat, targetPt.lng, cand.lat, cand.lng)
            if (straightDistM < 10000.0) { // Look within 10 km
                val (_, detourM) = GeoUtils.projectPointOnPolyline(
                    com.example.data.model.GeoPointDto(cand.lat, cand.lng),
                    geoRoute
                )
                if (detourM < minDetourMeters) {
                    minDetourMeters = detourM
                    bestCandidate = cand
                }
            }
        }

        return if (bestCandidate != null && minDetourMeters < 10000.0) {
            Pair(bestCandidate, minDetourMeters)
        } else null
    }

    private fun findPointAtKm(points: List<com.example.data.model.GeoPointDto>, targetKm: Double): com.example.data.model.GeoPointDto? {
        if (points.isEmpty()) return null
        var accumulatedKm = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val segKm = GeoUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng) / 1000.0
            if (accumulatedKm + segKm >= targetKm) {
                val ratio = if (segKm > 0.0) (targetKm - accumulatedKm) / segKm else 0.0
                return com.example.data.model.GeoPointDto(
                    p1.lat + (p2.lat - p1.lat) * ratio,
                    p1.lng + (p2.lng - p1.lng) * ratio
                )
            }
            accumulatedKm += segKm
        }
        return points.last()
    }

    /**
     * Phase 12 Feature 5: Rutekvalitet.
     * Calculates km and % by category:
     * - sti
     * - skov-/markvej
     * - vej uden fortov
     * - vej med fortov
     * - asfalt
     * - færge
     * Flags any section over 1 km on a road without a footway.
     */
    fun analyzeRouteQuality(orderedLegs: List<LegGeometryData>): RouteQualityBreakdown {
        var totalKm = 0.0
        var trailKm = 0.0
        var trackKm = 0.0
        var roadNoSidewalkKm = 0.0
        var roadWithSidewalkKm = 0.0
        var asphaltKm = 0.0
        var ferryKm = 0.0

        val warnings = mutableListOf<RoadSectionWarning>()
        var currentNoFootwaySectionKm = 0.0
        var currentNoFootwayStartKm = 0.0
        var currentNoFootwayRoadName = "Vej uden fortov"
        var currentNoFootwayStartLat = 0.0
        var currentNoFootwayStartLng = 0.0

        for (leg in orderedLegs) {
            if (leg.points.size < 2) continue

            val isFerryLeg = leg.legMode == LegMode.STRAIGHT ||
                    leg.sourceName.contains("ferry", ignoreCase = true)

            for (i in 0 until leg.points.size - 1) {
                val p1 = leg.points[i]
                val p2 = leg.points[i + 1]
                val stepKm = GeoUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng) / 1000.0
                val currentRouteKm = totalKm
                totalKm += stepKm

                if (isFerryLeg) {
                    ferryKm += stepKm
                    // Close any open road section
                    if (currentNoFootwaySectionKm >= 1.0) {
                        warnings.add(
                            RoadSectionWarning(
                                startKm = currentNoFootwayStartKm,
                                endKm = currentRouteKm,
                                lengthKm = currentNoFootwaySectionKm,
                                roadName = currentNoFootwayRoadName,
                                startLat = currentNoFootwayStartLat,
                                startLng = currentNoFootwayStartLng
                            )
                        )
                    }
                    currentNoFootwaySectionKm = 0.0
                    continue
                }

                val wayInfo = if (i < leg.wayInfo.size) leg.wayInfo[i] else WayInfo()
                val highway = wayInfo.highway?.lowercase() ?: ""
                val surface = wayInfo.surface?.lowercase() ?: ""
                val sidewalk = wayInfo.rawTags["sidewalk"]?.lowercase() ?: ""
                val foot = wayInfo.rawTags["foot"]?.lowercase() ?: ""
                val roadName = wayInfo.rawTags["name"] ?: "Vej uden fortov"

                val isTrail = highway in listOf("path", "footway", "pedestrian", "steps", "cycleway")
                val isTrack = highway == "track" || surface in listOf("unpaved", "compacted", "gravel", "fine_gravel", "dirt", "ground", "earth", "grass")
                val isRoad = highway in listOf("residential", "living_street", "service", "unclassified", "tertiary", "secondary", "primary", "trunk")

                val hasSidewalk = sidewalk in listOf("both", "left", "right", "yes", "separate") ||
                        (foot == "yes" && sidewalk != "no")

                when {
                    isTrail -> {
                        trailKm += stepKm
                        if (currentNoFootwaySectionKm >= 1.0) {
                            warnings.add(
                                RoadSectionWarning(
                                    startKm = currentNoFootwayStartKm,
                                    endKm = currentRouteKm,
                                    lengthKm = currentNoFootwaySectionKm,
                                    roadName = currentNoFootwayRoadName,
                                    startLat = currentNoFootwayStartLat,
                                    startLng = currentNoFootwayStartLng
                                )
                            )
                        }
                        currentNoFootwaySectionKm = 0.0
                    }
                    isTrack -> {
                        trackKm += stepKm
                        if (currentNoFootwaySectionKm >= 1.0) {
                            warnings.add(
                                RoadSectionWarning(
                                    startKm = currentNoFootwayStartKm,
                                    endKm = currentRouteKm,
                                    lengthKm = currentNoFootwaySectionKm,
                                    roadName = currentNoFootwayRoadName,
                                    startLat = currentNoFootwayStartLat,
                                    startLng = currentNoFootwayStartLng
                                )
                            )
                        }
                        currentNoFootwaySectionKm = 0.0
                    }
                    isRoad && hasSidewalk -> {
                        roadWithSidewalkKm += stepKm
                        asphaltKm += stepKm
                        if (currentNoFootwaySectionKm >= 1.0) {
                            warnings.add(
                                RoadSectionWarning(
                                    startKm = currentNoFootwayStartKm,
                                    endKm = currentRouteKm,
                                    lengthKm = currentNoFootwaySectionKm,
                                    roadName = currentNoFootwayRoadName,
                                    startLat = currentNoFootwayStartLat,
                                    startLng = currentNoFootwayStartLng
                                )
                            )
                        }
                        currentNoFootwaySectionKm = 0.0
                    }
                    isRoad && !hasSidewalk -> {
                        roadNoSidewalkKm += stepKm
                        asphaltKm += stepKm
                        if (currentNoFootwaySectionKm == 0.0) {
                            currentNoFootwayStartKm = currentRouteKm
                            currentNoFootwayStartLat = p1.lat
                            currentNoFootwayStartLng = p1.lng
                            currentNoFootwayRoadName = roadName
                        }
                        currentNoFootwaySectionKm += stepKm
                    }
                    surface in listOf("asphalt", "paved", "concrete", "paving_stones") -> {
                        asphaltKm += stepKm
                        if (currentNoFootwaySectionKm >= 1.0) {
                            warnings.add(
                                RoadSectionWarning(
                                    startKm = currentNoFootwayStartKm,
                                    endKm = currentRouteKm,
                                    lengthKm = currentNoFootwaySectionKm,
                                    roadName = currentNoFootwayRoadName,
                                    startLat = currentNoFootwayStartLat,
                                    startLng = currentNoFootwayStartLng
                                )
                            )
                        }
                        currentNoFootwaySectionKm = 0.0
                    }
                    else -> {
                        trailKm += stepKm
                        if (currentNoFootwaySectionKm >= 1.0) {
                            warnings.add(
                                RoadSectionWarning(
                                    startKm = currentNoFootwayStartKm,
                                    endKm = currentRouteKm,
                                    lengthKm = currentNoFootwaySectionKm,
                                    roadName = currentNoFootwayRoadName,
                                    startLat = currentNoFootwayStartLat,
                                    startLng = currentNoFootwayStartLng
                                )
                            )
                        }
                        currentNoFootwaySectionKm = 0.0
                    }
                }
            }
        }

        // Trailing road section check
        if (currentNoFootwaySectionKm >= 1.0) {
            warnings.add(
                RoadSectionWarning(
                    startKm = currentNoFootwayStartKm,
                    endKm = totalKm,
                    lengthKm = currentNoFootwaySectionKm,
                    roadName = currentNoFootwayRoadName,
                    startLat = currentNoFootwayStartLat,
                    startLng = currentNoFootwayStartLng
                )
            )
        }

        val effTotal = if (totalKm > 0.0) totalKm else 1.0
        return RouteQualityBreakdown(
            totalKm = totalKm,
            trailKm = trailKm,
            trailPct = (trailKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            trackKm = trackKm,
            trackPct = (trackKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            roadNoSidewalkKm = roadNoSidewalkKm,
            roadNoSidewalkPct = (roadNoSidewalkKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            roadWithSidewalkKm = roadWithSidewalkKm,
            roadWithSidewalkPct = (roadWithSidewalkKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            asphaltKm = asphaltKm,
            asphaltPct = (asphaltKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            ferryKm = ferryKm,
            ferryPct = (ferryKm / effTotal * 100.0).coerceIn(0.0, 100.0),
            roadWithoutFootwayWarnings = warnings
        )
    }

    /**
     * Phase 12 Feature 2: Rundtur.
     * Picks 3 candidate turn points at about 40 % of length in different directions.
     * Routes out, then routes back with outbound leg points as no-go points to ensure different paths.
     * Discards any loop more than 20% off the target.
     */
    suspend fun generateRundturCandidates(
        start: LatLng,
        targetKm: Double,
        routingEngine: RoutingEngine,
        shelters: List<ShelterEntity> = emptyList(),
        friTeltning: List<FriTeltningEntity> = emptyList()
    ): List<RundturCandidate> {
        val clampedTarget = targetKm.coerceIn(5.0, 60.0)
        // Straight-line turn distance: ~40% of target km, factoring in ~1.35 winding road factor
        val turnDistKm = (clampedTarget * 0.40) * 0.74

        val directions = listOf(
            Triple("Nord", 35.0, "Nordlig sløjfe"),
            Triple("Øst / Sydøst", 140.0, "Sydøstlig sløjfe"),
            Triple("Vest / Sydvest", 250.0, "Vestlig sløjfe")
        )

        val candidates = mutableListOf<RundturCandidate>()

        for ((idx, dir) in directions.withIndex()) {
            val (dirLabel, angleDeg, title) = dir
            val rad = Math.toRadians(angleDeg)

            // Calculate turn point coordinate
            val deltaLat = (turnDistKm / 111.0) * cos(rad)
            val deltaLng = (turnDistKm / (111.0 * cos(Math.toRadians(start.lat)))) * sin(rad)
            val turnLatLng = LatLng(start.lat + deltaLat, start.lng + deltaLng)

            // 1. Route out: start -> turn
            val outResult = routingEngine.routeLeg(
                from = start,
                to = turnLatLng,
                options = RouteOptions(preferNature = true, avoidRoads = 0.7)
            )

            if (outResult !is RouteLegResult.Success || outResult.points.isEmpty()) {
                continue
            }

            // 2. Prepare return with different paths:
            // Calculate a perpendicular via point offset to swing the return loop
            val perpAngleRad = rad + Math.PI / 2.0
            val midDistKm = turnDistKm * 0.40
            val midLat = (start.lat + turnLatLng.lat) / 2.0 + (midDistKm / 111.0) * cos(perpAngleRad)
            val midLng = (start.lng + turnLatLng.lng) / 2.0 + (midDistKm / (111.0 * cos(Math.toRadians(start.lat)))) * sin(perpAngleRad)
            val returnViaLatLng = LatLng(midLat, midLng)

            // Sample no-go points along middle section of outbound leg (exclude ends so turn & start can connect)
            val noGoPoints = mutableListOf<NoGoPoint>()
            val sampleCount = 6
            val step = (outResult.points.size / (sampleCount + 2)).coerceAtLeast(1)
            for (i in step until outResult.points.size - step step step) {
                val pt = outResult.points[i]
                noGoPoints.add(NoGoPoint(pt.lat, pt.lng, radiusM = 150.0))
            }

            val returnOptions = RouteOptions(
                preferNature = true,
                avoidRoads = 0.7,
                noGoPoints = noGoPoints
            )

            // Route return leg 1: turn -> returnVia
            val ret1 = routingEngine.routeLeg(
                from = turnLatLng,
                to = returnViaLatLng,
                options = returnOptions
            )

            // Route return leg 2: returnVia -> start
            val ret2 = routingEngine.routeLeg(
                from = returnViaLatLng,
                to = start,
                options = returnOptions
            )

            if (ret1 !is RouteLegResult.Success || ret2 !is RouteLegResult.Success) {
                continue
            }

            val totalActualKm = outResult.lengthKm + ret1.lengthKm + ret2.lengthKm
            val deviation = abs(totalActualKm - clampedTarget) / clampedTarget

            // Discard any loop more than 20% off the target
            if (deviation > 0.20) {
                continue
            }

            // Build waypoints for loop
            val wpStart = RouteWaypoint(id = "rnd_start_$idx", lat = start.lat, lng = start.lng, role = WaypointRole.START, name = "Start")
            val wpTurn = RouteWaypoint(id = "rnd_turn_$idx", lat = turnLatLng.lat, lng = turnLatLng.lng, role = WaypointRole.VIA, name = "Vendepunkt ($dirLabel)")
            val wpMid = RouteWaypoint(id = "rnd_mid_$idx", lat = returnViaLatLng.lat, lng = returnViaLatLng.lng, role = WaypointRole.VIA, name = "Returvej ($dirLabel)")
            val wpEnd = RouteWaypoint(id = "rnd_end_$idx", lat = start.lat, lng = start.lng, role = WaypointRole.END, name = "Slut (Startsted)")

            val legsMap = mapOf(
                "${wpStart.id}_to_${wpTurn.id}" to LegGeometryData(
                    fromWaypointId = wpStart.id,
                    toWaypointId = wpTurn.id,
                    legMode = LegMode.SNAP,
                    points = outResult.points,
                    lengthKm = outResult.lengthKm,
                    ascentM = outResult.ascentM,
                    descentM = outResult.descentM,
                    pathSharePct = 85.0,
                    roadKm = 0.2,
                    wayInfo = outResult.perPointWayInfo
                ),
                "${wpTurn.id}_to_${wpMid.id}" to LegGeometryData(
                    fromWaypointId = wpTurn.id,
                    toWaypointId = wpMid.id,
                    legMode = LegMode.SNAP,
                    points = ret1.points,
                    lengthKm = ret1.lengthKm,
                    ascentM = ret1.ascentM,
                    descentM = ret1.descentM,
                    pathSharePct = 85.0,
                    roadKm = 0.2,
                    wayInfo = ret1.perPointWayInfo
                ),
                "${wpMid.id}_to_${wpEnd.id}" to LegGeometryData(
                    fromWaypointId = wpMid.id,
                    toWaypointId = wpEnd.id,
                    legMode = LegMode.SNAP,
                    points = ret2.points,
                    lengthKm = ret2.lengthKm,
                    ascentM = ret2.ascentM,
                    descentM = ret2.descentM,
                    pathSharePct = 85.0,
                    roadKm = 0.2,
                    wayInfo = ret2.perPointWayInfo
                )
            )

            // Count overnight options within 1 km of the loop
            val allPts = outResult.points + ret1.points + ret2.points
            val geoPts = allPts.map { com.example.data.model.GeoPointDto(it.lat, it.lng) }
            val shelterCount = shelters.count { sh ->
                GeoUtils.projectPointOnPolyline(com.example.data.model.GeoPointDto(sh.lat, sh.lng), geoPts).second <= 1000.0
            }
            val friCount = friTeltning.count { ft ->
                GeoUtils.projectPointOnPolyline(com.example.data.model.GeoPointDto(ft.centerLat, ft.centerLng), geoPts).second <= 1000.0
            }

            val quality = analyzeRouteQuality(legsMap.values.toList())
            val pathShare = quality.trailPct + quality.trackPct

            candidates.add(
                RundturCandidate(
                    id = idx + 1,
                    title = title,
                    directionLabel = dirLabel,
                    targetKm = clampedTarget,
                    actualKm = totalActualKm,
                    ascentM = outResult.ascentM + ret1.ascentM + ret2.ascentM,
                    descentM = outResult.descentM + ret1.descentM + ret2.descentM,
                    pathSharePct = pathShare,
                    overnightCount = shelterCount + friCount,
                    turnPoint = RoutePoint3D(turnLatLng.lat, turnLatLng.lng),
                    waypoints = listOf(wpStart, wpTurn, wpMid, wpEnd),
                    legs = legsMap
                )
            )
        }

        return candidates
    }

    /**
     * Phase 12 Feature 3: Vejr og dagslys.
     * Computes Open-Meteo forecast at ETA and checks sunset warning for OVERNIGHT waypoints and the END point.
     */
    suspend fun calculateWaypointWeatherEtas(
        waypoints: List<RouteWaypoint>,
        orderedLegs: List<LegGeometryData>,
        startTimeStr: String = "09:00",
        pacingSpeedKmh: Double = 4.0,
        weatherService: WeatherApiService
    ): List<WaypointWeatherEta> {
        val targets = waypoints.filterIndexed { idx, wp ->
            wp.role == WaypointRole.OVERNIGHT || (idx == waypoints.lastIndex && waypoints.size >= 2)
        }
        if (targets.isEmpty() || orderedLegs.isEmpty()) return emptyList()

        val startParts = startTimeStr.split(":")
        val startHour = startParts.getOrNull(0)?.toIntOrNull() ?: 9
        val startMin = startParts.getOrNull(1)?.toIntOrNull() ?: 0
        val startTotalMinutes = startHour * 60 + startMin

        val results = mutableListOf<WaypointWeatherEta>()
        val legDistances = orderedLegs.associate { it.legKey to it.lengthKm }

        var accumulatedKm = 0.0
        for (i in 0 until waypoints.size - 1) {
            val curr = waypoints[i]
            val next = waypoints[i + 1]
            val legDist = legDistances["${curr.id}_to_${next.id}"] ?: 0.0
            accumulatedKm += legDist

            if (next.role == WaypointRole.OVERNIGHT || i + 1 == waypoints.lastIndex) {
                val walkMinutes = ((accumulatedKm / pacingSpeedKmh) * 60.0).toInt()
                val totalMinutes = startTotalMinutes + walkMinutes
                val etaHour = (totalMinutes / 60) % 24
                val etaMin = totalMinutes % 60
                val etaFormatted = String.format(Locale.US, "%02d:%02d", etaHour, etaMin)

                // Fetch real Open-Meteo forecast
                var tempC: Double? = null
                var precipMm: Double? = null
                var windMs: Double? = null
                var weatherIcon: String? = null
                var weatherDesc: String? = null
                var sunsetTime: String? = null
                var isAfterSunset = false
                var warningMessage: String? = null

                try {
                    val resp = weatherService.getTrailWeather(lat = next.lat, lon = next.lng)
                    val parsed = WeatherInterpreter.parseWeather(resp)
                    tempC = parsed.tempC
                    precipMm = parsed.precipitationMm
                    windMs = parsed.windSpeedMs
                    weatherIcon = parsed.weatherIcon
                    weatherDesc = parsed.conditionDescription

                    val rawSunset = resp.daily?.sunset?.firstOrNull()
                    if (!rawSunset.isNullOrBlank()) {
                        // "2026-10-17T18:45" -> "18:45"
                        val timePart = rawSunset.substringAfter("T", "")
                        if (timePart.isNotBlank()) {
                            sunsetTime = timePart
                            val sParts = timePart.split(":")
                            val sHour = sParts.getOrNull(0)?.toIntOrNull() ?: 18
                            val sMin = sParts.getOrNull(1)?.toIntOrNull() ?: 30
                            val sunsetMinutes = sHour * 60 + sMin

                            if (totalMinutes > sunsetMinutes) {
                                isAfterSunset = true
                                warningMessage = "⚠️ Ankomst efter solnedgang (ETA $etaFormatted, solnedgang $sunsetTime)"
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Honest empty state: no assumed sunset or invented weather
                }

                results.add(
                    WaypointWeatherEta(
                        waypointId = next.id,
                        waypointName = next.name ?: if (next.role == WaypointRole.OVERNIGHT) "Overnatning" else "Mål",
                        isOvernight = next.role == WaypointRole.OVERNIGHT,
                        isEnd = i + 1 == waypoints.lastIndex,
                        kmFromStart = accumulatedKm,
                        etaFormatted = etaFormatted,
                        etaMinutesFromStart = walkMinutes,
                        tempC = tempC,
                        precipMm = precipMm,
                        windMs = windMs,
                        weatherIcon = weatherIcon,
                        weatherDescription = weatherDesc,
                        sunsetTime = sunsetTime,
                        isAfterSunset = isAfterSunset,
                        warningMessage = warningMessage
                    )
                )
            }
        }

        return results
    }

    /**
     * Phase 12 Feature 4: Rute til nærmeste.
     * From current GPS, routes to nearest POI of given category by routed distance (not straight line).
     */
    suspend fun routeToNearestPoi(
        start: LatLng,
        category: NearestPoiCategory,
        routingEngine: RoutingEngine,
        shelters: List<ShelterEntity>,
        friTeltning: List<FriTeltningEntity>,
        pois: List<PoiEntity>
    ): Pair<PoiEntity, RouteLegResult.Success>? {
        // Collect candidate POIs
        val candidateList = mutableListOf<PoiEntity>()

        when (category) {
            NearestPoiCategory.SHELTER -> {
                shelters.forEach {
                    candidateList.add(PoiEntity(id = it.id, type = "shelter", name = it.name, lat = it.lat, lng = it.lng, source = "geofa"))
                }
                friTeltning.forEach {
                    candidateList.add(PoiEntity(id = it.id, type = "fri_teltning", name = it.name, lat = it.centerLat, lng = it.centerLng, source = "geofa"))
                }
            }
            NearestPoiCategory.WATER -> {
                pois.filter { it.type.equals("water", ignoreCase = true) }.forEach { candidateList.add(it) }
                shelters.filter { it.hasWaterNearby }.forEach {
                    candidateList.add(PoiEntity(id = it.id, type = "water", name = "${it.name} (Vand)", lat = it.lat, lng = it.lng, source = "geofa"))
                }
            }
            NearestPoiCategory.TOILET -> {
                pois.filter { it.type.equals("toilet", ignoreCase = true) }.forEach { candidateList.add(it) }
                shelters.filter { it.hasToiletNearby }.forEach {
                    candidateList.add(PoiEntity(id = it.id, type = "toilet", name = "${it.name} (Toilet)", lat = it.lat, lng = it.lng, source = "geofa"))
                }
            }
            NearestPoiCategory.SHOP -> {
                pois.filter { it.type.equals("shop", ignoreCase = true) }.forEach { candidateList.add(it) }
            }
        }

        if (candidateList.isEmpty()) return null

        // Pick top 4 closest by straight line within 25 km
        val topCandidates = candidateList
            .map { it to GeoUtils.distanceMeters(start.lat, start.lng, it.lat, it.lng) }
            .filter { it.second <= 25000.0 }
            .sortedBy { it.second }
            .take(4)
            .map { it.first }

        var bestMatch: Pair<PoiEntity, RouteLegResult.Success>? = null
        var minRoutedKm = Double.MAX_VALUE

        for (cand in topCandidates) {
            val res = routingEngine.routeLeg(
                from = start,
                to = LatLng(cand.lat, cand.lng),
                options = RouteOptions(preferNature = true, avoidRoads = 0.5)
            )
            if (res is RouteLegResult.Success) {
                if (res.lengthKm < minRoutedKm) {
                    minRoutedKm = res.lengthKm
                    bestMatch = Pair(cand, res)
                }
            }
        }

        return bestMatch
    }

    private fun calculateCumulativeKm(points: List<RoutePoint3D>): Double {
        var sum = 0.0
        for (i in 0 until points.size - 1) {
            sum += GeoUtils.distanceMeters(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng)
        }
        return sum / 1000.0
    }
}
