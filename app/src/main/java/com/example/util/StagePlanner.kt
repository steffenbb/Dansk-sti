package com.example.util

import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripStage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class OvernightCandidate(
    val id: String,
    val name: String,
    val type: String, // "SHELTER", "FRI_TELTNING", "CAMPSITE"
    val lat: Double,
    val lng: Double,
    val walkKm: Double,
    val detourMeters: Double,
    val bookingRequired: Boolean,
    val hasWaterNearby: Boolean,
    val hasToiletNearby: Boolean,
    val sourceEntity: Any? = null
)

object StagePlanner {

    private val moshi = Moshi.Builder().build()
    private val geoPointListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
    private val geoPointListAdapter = moshi.adapter<List<GeoPointDto>>(geoPointListType)

    private data class ParsedPolygon(
        val points: List<GeoPointDto>,
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    )

    private val polygonCache = java.util.concurrent.ConcurrentHashMap<String, ParsedPolygon>()

    private fun getParsedPolygon(id: String, polygonJson: String): ParsedPolygon {
        val key = "${id}_${polygonJson.hashCode()}"
        polygonCache[key]?.let { return it }
        val pts = parsePolygonPoints(polygonJson)
        val parsed = if (pts.isEmpty()) {
            ParsedPolygon(emptyList(), 0.0, 0.0, 0.0, 0.0)
        } else {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE
            for (p in pts) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lng < minLng) minLng = p.lng
                if (p.lng > maxLng) maxLng = p.lng
            }
            ParsedPolygon(pts, minLat, maxLat, minLng, maxLng)
        }
        polygonCache[key] = parsed
        return parsed
    }

    /**
     * Requirement B: Extract candidates along the route within maxDetour.
     * - Skip every candidate outside the route's bounding box expanded by maxDetourKm before projecting.
     * - For fri teltning areas, check the polygon's bounding box first and project only corners inside the expanded box.
     */
    fun extractCandidates(
        plannedRoute: PlannedRoute,
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        waterPois: List<PoiEntity>,
        toiletPois: List<PoiEntity>,
        overnightType: OvernightType,
        maxDetourKm: Double = 1.5
    ): List<OvernightCandidate> {
        if (plannedRoute.points.isEmpty()) return emptyList()
        val maxDetourMeters = maxDetourKm * 1000.0
        val bbox = plannedRoute.boundingBox()
        val routeMinLat = bbox[0]
        val routeMaxLat = bbox[1]
        val routeMinLng = bbox[2]
        val routeMaxLng = bbox[3]

        val midLatRad = Math.toRadians((routeMinLat + routeMaxLat) / 2.0)
        val cosMidLat = kotlin.math.cos(midLatRad).coerceAtLeast(0.1)
        val latMargin = (maxDetourKm / 110.54) + 0.002
        val lngMargin = (maxDetourKm / (111.32 * cosMidLat)) + 0.002

        val expMinLat = routeMinLat - latMargin
        val expMaxLat = routeMaxLat + latMargin
        val expMinLng = routeMinLng - lngMargin
        val expMaxLng = routeMaxLng + lngMargin

        val localWaterPois = waterPois.filter {
            it.lat in (expMinLat - 0.006)..(expMaxLat + 0.006) &&
                it.lng in (expMinLng - 0.01)..(expMaxLng + 0.01)
        }
        val localToiletPois = toiletPois.filter {
            it.lat in (expMinLat - 0.006)..(expMaxLat + 0.006) &&
                it.lng in (expMinLng - 0.01)..(expMaxLng + 0.01)
        }

        val candidates = mutableListOf<OvernightCandidate>()

        // 1. Shelters and Campsites
        if (overnightType != OvernightType.FRI_TELTNING) {
            for (sh in shelters) {
                if (sh.lat < expMinLat || sh.lat > expMaxLat || sh.lng < expMinLng || sh.lng > expMaxLng) {
                    continue
                }
                val isCampsite = sh.shelterType.contains("telt", ignoreCase = true) ||
                        sh.shelterType.contains("camp", ignoreCase = true) ||
                        sh.shelterType.contains("lejr", ignoreCase = true)
                val candType = if (isCampsite) "CAMPSITE" else "SHELTER"

                val matchesPreference = when (overnightType) {
                    OvernightType.SHELTER -> candType == "SHELTER"
                    OvernightType.CAMPSITE -> candType == "CAMPSITE"
                    OvernightType.BOTH -> candType == "SHELTER" || candType == "CAMPSITE"
                    OvernightType.FRI_TELTNING -> false
                }
                if (!matchesPreference) continue

                val (walkKm, detourMeters) = plannedRoute.projectPoint(sh.lat, sh.lng)
                if (detourMeters <= maxDetourMeters) {
                    val hasWater = sh.hasWaterNearby || localWaterPois.any {
                        GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0
                    }
                    val hasToilet = sh.hasToiletNearby || localToiletPois.any {
                        GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0
                    }

                    candidates.add(
                        OvernightCandidate(
                            id = sh.id,
                            name = sh.name,
                            type = candType,
                            lat = sh.lat,
                            lng = sh.lng,
                            walkKm = walkKm,
                            detourMeters = detourMeters,
                            bookingRequired = sh.bookingRequired,
                            hasWaterNearby = hasWater,
                            hasToiletNearby = hasToilet,
                            sourceEntity = sh
                        )
                    )
                }
            }
        }

        // 2. Fri Teltning areas (nearest point of polygon, not the centre - Requirement B)
        if (overnightType == OvernightType.FRI_TELTNING || overnightType == OvernightType.BOTH) {
            for (ft in friTeltnings) {
                val parsedPoly = getParsedPolygon(ft.id, ft.polygonJson)
                val polyPoints = parsedPoly.points
                val bestProjection: Triple<Double, Double, GeoPointDto>? = if (polyPoints.isNotEmpty()) {
                    // Check the polygon's bounding box against the expanded route bounding box first
                    if (parsedPoly.maxLat < expMinLat || parsedPoly.minLat > expMaxLat ||
                        parsedPoly.maxLng < expMinLng || parsedPoly.minLng > expMaxLng
                    ) {
                        null
                    } else {
                        var minDetour = Double.MAX_VALUE
                        var bestWalk = 0.0
                        var nearestPt: GeoPointDto? = null
                        // Project only corners inside the expanded box
                        for (pt in polyPoints) {
                            if (pt.lat < expMinLat || pt.lat > expMaxLat || pt.lng < expMinLng || pt.lng > expMaxLng) {
                                continue
                            }
                            val (wKm, dM) = plannedRoute.projectPoint(pt.lat, pt.lng)
                            if (dM < minDetour) {
                                minDetour = dM
                                bestWalk = wKm
                                nearestPt = pt
                            }
                        }
                        if (nearestPt != null) Triple(bestWalk, minDetour, nearestPt) else null
                    }
                } else {
                    if (ft.centerLat < expMinLat || ft.centerLat > expMaxLat ||
                        ft.centerLng < expMinLng || ft.centerLng > expMaxLng
                    ) {
                        null
                    } else {
                        val (wKm, dM) = plannedRoute.projectPoint(ft.centerLat, ft.centerLng)
                        Triple(wKm, dM, GeoPointDto(ft.centerLat, ft.centerLng))
                    }
                }

                if (bestProjection == null) continue

                val walkKm = bestProjection.first
                val detourMeters = bestProjection.second
                val chosenPt = bestProjection.third

                if (detourMeters <= maxDetourMeters) {
                    val hasWater = ft.waterDistanceMeters <= 500 || localWaterPois.any {
                        GeoUtils.distanceMeters(chosenPt.lat, chosenPt.lng, it.lat, it.lng) <= 500.0
                    }
                    val hasToilet = ft.toiletDistanceMeters <= 500 || localToiletPois.any {
                        GeoUtils.distanceMeters(chosenPt.lat, chosenPt.lng, it.lat, it.lng) <= 500.0
                    }

                    candidates.add(
                        OvernightCandidate(
                            id = ft.id,
                            name = ft.name,
                            type = "FRI_TELTNING",
                            lat = chosenPt.lat,
                            lng = chosenPt.lng,
                            walkKm = walkKm,
                            detourMeters = detourMeters,
                            bookingRequired = false,
                            hasWaterNearby = hasWater,
                            hasToiletNearby = hasToilet,
                            sourceEntity = ft
                        )
                    )
                }
            }
        }

        return candidates.sortedBy { it.walkKm }
    }

    /**
     * Requirement C: Scoring Formula
     * Score = |walkKm - target| + detour x 2 + (booking required ? 1 km : 0) + (no water within 500 m ? 2 km : 0)
     */
    fun calculateScore(candidate: OvernightCandidate, targetKm: Double): Double {
        val distanceDiff = abs(candidate.walkKm - targetKm)
        val detourKm = candidate.detourMeters / 1000.0
        val bookingPenalty = if (candidate.bookingRequired) 1.0 else 0.0
        val noWaterPenalty = if (!candidate.hasWaterNearby) 2.0 else 0.0
        return distanceDiff + (detourKm * 2.0) + bookingPenalty + noWaterPenalty
    }

    fun computePrimaryWindow(targetKm: Double, dailyTargetKm: Double): Pair<Double, Double> {
        val minusWindow = max(4.0, dailyTargetKm * 0.26)
        val plusWindow = max(3.0, dailyTargetKm * 0.20)
        return (targetKm - minusWindow) to (targetKm + plusWindow)
    }

    fun computeAdaptiveFallbackWindow(targetKm: Double, dailyTargetKm: Double): Pair<Double, Double> {
        val minusWindow = max(4.2, dailyTargetKm * 0.35)
        val plusWindow = max(3.5, dailyTargetKm * 0.30)
        return (targetKm - minusWindow) to (targetKm + plusWindow)
    }

    fun wouldArriveAfterSunset(
        stageKm: Double,
        paceKmh: Double,
        startTime: String,
        sunsetTime: String
    ): Boolean {
        if (sunsetTime.isBlank() || sunsetTime == "Solnedgang ukendt") return false
        val sunsetParts = sunsetTime.split(":").mapNotNull { it.toIntOrNull() }
        if (sunsetParts.size < 2) return false
        val startParts = startTime.split(":").mapNotNull { it.toIntOrNull() }
        val startMinutes = (startParts.getOrNull(0) ?: 9) * 60 + (startParts.getOrNull(1) ?: 0)
        val effectivePace = paceKmh.coerceAtLeast(1.0)
        val walkMinutes = ((stageKm / effectivePace) * 60.0).roundToInt()
        val arrivalMinutes = startMinutes + walkMinutes + 60
        val sunsetMinutes = sunsetParts[0] * 60 + sunsetParts[1]
        return arrivalMinutes > sunsetMinutes
    }

    /**
     * Multi-criteria smart stage score factoring in base score, toilet access,
     * after-sunset arrival penalty, and 1-day look-ahead availability.
     */
    fun calculateSmartStageScore(
        candidate: OvernightCandidate,
        targetKm: Double,
        dayStartKm: Double = 0.0,
        dailyTargetKm: Double = 15.0,
        paceKmh: Double = 4.0,
        startTime: String = "09:00",
        dayNumber: Int = 1,
        startDateEpochMs: Long? = null,
        sunsetTimeOverride: String? = null,
        allCandidates: List<OvernightCandidate> = emptyList(),
        isSecondToLastDayOrEarlier: Boolean = false,
        totalWalkKm: Double = 0.0
    ): Double {
        val baseScore = calculateScore(candidate, targetKm)
        val noToiletPenalty = if (!candidate.hasToiletNearby) 0.5 else 0.0
        val stageKm = max(0.0, candidate.walkKm - dayStartKm)
        val daySunset = resolveDaySunset(dayNumber, candidate.lat, candidate.lng, startDateEpochMs, sunsetTimeOverride)
        val afterSunsetPenalty = if (wouldArriveAfterSunset(stageKm, paceKmh, startTime, daySunset)) 3.5 else 0.0

        val lookAheadPenalty = if (isSecondToLastDayOrEarlier && allCandidates.isNotEmpty()) {
            val nextTargetKm = candidate.walkKm + dailyTargetKm
            if (totalWalkKm > nextTargetKm + 3.0) {
                val (nextMin, nextMax) = computeAdaptiveFallbackWindow(nextTargetKm, dailyTargetKm)
                val hasNextDayCandidate = allCandidates.any {
                    it.walkKm in nextMin..nextMax && it.walkKm > candidate.walkKm + 0.5
                }
                if (!hasNextDayCandidate) 5.0 else 0.0
            } else {
                0.0
            }
        } else {
            0.0
        }

        return baseScore + noToiletPenalty + afterSunsetPenalty + lookAheadPenalty
    }

    /**
     * Round 10B Item 3: Every day uses its own date (trip start date + day index)
     * and that night's overnight coordinates.
     */
    fun computeSunsetForStageDay(
        dayNumber: Int,
        stopLat: Double,
        stopLng: Double,
        startDateEpochMs: Long? = null
    ): String {
        val baseEpochMs = startDateEpochMs ?: System.currentTimeMillis()
        val dayIndex = max(0, dayNumber - 1).toLong()
        val dayEpochMs = baseEpochMs + dayIndex * 86_400_000L
        val validLat = stopLat.takeIf { it != 0.0 } ?: SunCalculator.DEFAULT_COPENHAGEN_LAT
        val validLng = stopLng.takeIf { it != 0.0 } ?: SunCalculator.DEFAULT_COPENHAGEN_LNG
        return SunCalculator.calculateSunset(
            epochMillis = dayEpochMs,
            lat = validLat,
            lng = validLng
        )
    }

    private fun resolveDaySunset(
        dayNumber: Int,
        stopLat: Double,
        stopLng: Double,
        startDateEpochMs: Long?,
        sunsetTimeOverride: String?
    ): String {
        if (startDateEpochMs == null && !sunsetTimeOverride.isNullOrBlank() && sunsetTimeOverride != "Solnedgang ukendt") {
            return sunsetTimeOverride
        }
        return computeSunsetForStageDay(
            dayNumber = dayNumber,
            stopLat = stopLat,
            stopLng = stopLng,
            startDateEpochMs = startDateEpochMs
        )
    }

    /**
     * Round 10C Item 6: Single unified stage planning function used by trails, custom routes,
     * overnight suggestions, and single-night recomputation.
     * - When [overnightWaypoints] is non-empty (custom routes with OVERNIGHT pins), OVERNIGHT pins define
     *   exact day boundaries in order and the final day ends at END.
     * - When [changedStageIndex] is provided (with [changedStageCandidate] or [changedStageEndKm]), keeps
     *   earlier stages unchanged, updates [changedStageIndex], and plans remaining days with the unified loop.
     * - Otherwise (trails and suggestions), plans [numDays] stages using [dailyTargetKm] and candidate scoring.
     */
    fun planStages(
        plannedRoute: PlannedRoute,
        trail: TrailEntity? = null,
        routeName: String = trail?.name ?: plannedRoute.trailName,
        waypoints: List<com.example.util.routing.RouteWaypoint> = emptyList(),
        overnightWaypoints: List<com.example.util.routing.RouteWaypoint> = waypoints.filter {
            it.role == com.example.util.routing.WaypointRole.OVERNIGHT && !it.isHelper
        },
        overnightWalkKms: Map<String, Double> = emptyMap(),
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        numDays: Int = 1,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity> = emptyList(),
        friTeltnings: List<FriTeltningEntity> = emptyList(),
        pois: List<PoiEntity> = emptyList(),
        sunsetTime: String? = null,
        candidatesOverride: List<OvernightCandidate>? = null,
        startDateEpochMs: Long? = null,
        existingStages: List<TripStage> = emptyList(),
        changedStageIndex: Int? = null,
        changedStageCandidate: OvernightCandidate? = null,
        changedStageEndKm: Double? = null
    ): List<TripStage> {
        val totalWalkKm = max(0.1, plannedRoute.totalWalkKm)
        val effectiveRouteName = routeName.ifBlank { trail?.name ?: plannedRoute.trailName.ifBlank { "Rute" } }

        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }
        val shopPois = pois.filter { it.type in listOf("shop", "supermarket", "convenience", "bakery", "farm") }

        // 1. Custom route / Trail planner with explicit OVERNIGHT pins
        if (changedStageIndex == null && overnightWaypoints.isNotEmpty()) {
            val stages = mutableListOf<TripStage>()
            var prevKm = 0.0
            val startWp = waypoints.firstOrNull { !it.isHelper }
            var prevName = startWp?.name?.takeIf { it.isNotBlank() } ?: "Start: $effectiveRouteName"

            for ((index, onWp) in overnightWaypoints.withIndex()) {
                val day = index + 1
                val (projWalkKm, projDetourM) = plannedRoute.projectPoint(onWp.lat, onWp.lng)
                val rawWalkKm = overnightWalkKms[onWp.id] ?: projWalkKm
                val stageEndKm = min(totalWalkKm, max(prevKm + 0.01, rawWalkKm))
                val detourM = onWp.detourCostKm?.let { it * 1000.0 } ?: projDetourM

                val shelterMatch = shelters.find { it.id == onWp.linkedPoiId }
                val friMatch = friTeltnings.find { it.id == onWp.linkedPoiId }

                val stopType = when {
                    !onWp.spotType.isNullOrBlank() -> onWp.spotType
                    friMatch != null || onWp.name?.contains("teltning", ignoreCase = true) == true -> "FRI_TELTNING"
                    else -> "SHELTER"
                }

                val hasWater = onWp.hasWaterNearby || shelterMatch?.hasWaterNearby == true || waterPois.any {
                    GeoUtils.distanceMeters(onWp.lat, onWp.lng, it.lat, it.lng) <= 500.0
                }
                val hasToilet = onWp.hasToiletNearby || shelterMatch?.hasToiletNearby == true ||
                    friMatch?.toiletDistanceMeters?.let { it <= 500 } == true || toiletPois.any {
                        GeoUtils.distanceMeters(onWp.lat, onWp.lng, it.lat, it.lng) <= 500.0
                    }
                val booking = onWp.bookingRequired || shelterMatch?.bookingRequired == true

                val daySunset = resolveDaySunset(day, onWp.lat, onWp.lng, startDateEpochMs, sunsetTime)
                val stage = createTripStage(
                    dayNumber = day,
                    startKm = prevKm,
                    endKm = stageEndKm,
                    fromName = prevName,
                    toName = onWp.name?.takeIf { it.isNotBlank() } ?: "Lejr $day",
                    stopType = stopType,
                    stopLat = onWp.lat,
                    stopLng = onWp.lng,
                    bookingRequired = booking,
                    hasWaterNearby = hasWater,
                    hasToiletNearby = hasToilet,
                    detourMeters = detourM,
                    plannedRoute = plannedRoute,
                    paceKmh = paceKmh,
                    startTime = startTime,
                    sunsetTime = daySunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = onWp.linkedPoiId
                )
                stages.add(stage)
                prevKm = stageEndKm
                prevName = stage.stopName
            }

            // Last day ends at END
            val finalDay = overnightWaypoints.size + 1
            val endWp = waypoints.lastOrNull { !it.isHelper }
            val endLat = endWp?.lat ?: plannedRoute.points.lastOrNull()?.lat
            val endLng = endWp?.lng ?: plannedRoute.points.lastOrNull()?.lng
            val destName = endWp?.name?.takeIf { it.isNotBlank() } ?: "Mål: $effectiveRouteName"

            if (endLat != null && endLng != null) {
                val finalDaySunset = resolveDaySunset(finalDay, endLat, endLng, startDateEpochMs, sunsetTime)
                val finalStage = createTripStage(
                    dayNumber = finalDay,
                    startKm = prevKm,
                    endKm = totalWalkKm,
                    fromName = prevName,
                    toName = destName,
                    stopType = "DESTINATION",
                    stopLat = endLat,
                    stopLng = endLng,
                    bookingRequired = false,
                    hasWaterNearby = waterPois.any { GeoUtils.distanceMeters(endLat, endLng, it.lat, it.lng) <= 500.0 },
                    hasToiletNearby = toiletPois.any { GeoUtils.distanceMeters(endLat, endLng, it.lat, it.lng) <= 500.0 },
                    detourMeters = 0.0,
                    plannedRoute = plannedRoute,
                    paceKmh = paceKmh,
                    startTime = startTime,
                    sunsetTime = finalDaySunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = null
                )
                stages.add(finalStage)
            }
            return stages
        }

        // 2. Candidate extraction for trails, suggestions, and stage recomputations
        val candidates = candidatesOverride ?: extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = waterPois,
            toiletPois = toiletPois,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm
        )

        val stages = mutableListOf<TripStage>()
        var prevEndWalkKm = 0.0
        var prevStopName = "Start: $effectiveRouteName"
        val fallbackTotalTrailKm = trail?.computedWalkingKm ?: totalWalkKm

        val daysCount: Int
        val firstLoopDay: Int

        if (changedStageIndex != null) {
            if (changedStageIndex !in existingStages.indices) return existingStages
            for (i in 0 until changedStageIndex) {
                stages.add(existingStages[i])
            }
            val prevEnd = if (changedStageIndex > 0) stages[changedStageIndex - 1].endKm else 0.0
            val prevName = if (changedStageIndex > 0) stages[changedStageIndex - 1].stopName else "Start: $effectiveRouteName"
            val changedDayNum = changedStageIndex + 1

            val resolvedCandidate: OvernightCandidate = changedStageCandidate ?: run {
                val targetEndKm = changedStageEndKm ?: existingStages[changedStageIndex].endKm
                val clampedEnd = targetEndKm.coerceIn(
                    if (changedStageIndex > 0) existingStages[changedStageIndex - 1].endKm + 0.5 else 0.5,
                    totalWalkKm
                )
                val nearCand = candidates.minByOrNull { abs(it.walkKm - clampedEnd) }
                if (nearCand != null && abs(nearCand.walkKm - clampedEnd) <= 0.5) {
                    nearCand
                } else {
                    val ptAtKm = plannedRoute.points.find { it.walkKm >= clampedEnd }
                        ?: plannedRoute.points.lastOrNull()
                        ?: return existingStages
                    OvernightCandidate(
                        id = "dragged_$changedStageIndex",
                        name = "Km ${String.format(Locale.US, "%.1f", clampedEnd)}",
                        type = "NONE",
                        lat = ptAtKm.lat,
                        lng = ptAtKm.lng,
                        walkKm = clampedEnd,
                        detourMeters = 0.0,
                        bookingRequired = false,
                        hasWaterNearby = false,
                        hasToiletNearby = false
                    )
                }
            }

            val changedDaySunset = resolveDaySunset(
                changedDayNum,
                resolvedCandidate.lat,
                resolvedCandidate.lng,
                startDateEpochMs,
                sunsetTime
            )
            val updatedChangedStage = createTripStage(
                dayNumber = changedDayNum,
                startKm = prevEnd,
                endKm = min(totalWalkKm, resolvedCandidate.walkKm),
                fromName = prevName,
                toName = resolvedCandidate.name,
                stopType = resolvedCandidate.type,
                stopLat = resolvedCandidate.lat,
                stopLng = resolvedCandidate.lng,
                bookingRequired = resolvedCandidate.bookingRequired,
                hasWaterNearby = resolvedCandidate.hasWaterNearby,
                hasToiletNearby = resolvedCandidate.hasToiletNearby,
                detourMeters = resolvedCandidate.detourMeters,
                plannedRoute = plannedRoute,
                paceKmh = paceKmh,
                startTime = startTime,
                sunsetTime = changedDaySunset,
                waterPois = waterPois,
                shopPois = shopPois,
                spotId = resolvedCandidate.id
            )
            stages.add(updatedChangedStage)
            prevEndWalkKm = updatedChangedStage.endKm
            prevStopName = updatedChangedStage.stopName
            daysCount = existingStages.size
            firstLoopDay = changedStageIndex + 2
        } else {
            daysCount = max(1, min(30, numDays))
            firstLoopDay = 1
        }

        // Unified day planning loop for trails, suggestions, and remaining recomputed days
        for (day in firstLoopDay..daysCount) {
            val isLastDay = (day == daysCount)
            val targetKm = prevEndWalkKm + dailyTargetKm
            val reachesRouteEnd = isLastDay && (totalWalkKm <= (targetKm + 3.0))

            if (reachesRouteEnd) {
                val stageStartKm = prevEndWalkKm
                val stageEndKm = totalWalkKm
                val endWp = waypoints.lastOrNull { !it.isHelper }
                val goalPt = plannedRoute.points.lastOrNull()
                    ?: endWp?.let { RoutePoint(it.lat, it.lng, 0.0, totalWalkKm, fallbackTotalTrailKm) }
                    ?: continue

                val windowCandidates = candidates.filter {
                    it.walkKm >= (stageEndKm - 4.0) &&
                        it.walkKm <= (stageEndKm + 3.0) &&
                        it.walkKm > (prevEndWalkKm + 0.1)
                }
                val bestAtEnd = windowCandidates.minByOrNull { calculateScore(it, stageEndKm) }
                    ?: candidates.filter { it.walkKm > (prevEndWalkKm + 0.1) }
                        .minByOrNull { abs(it.walkKm - stageEndKm) }

                val (stopName, stopType, stopLat, stopLng, bookingReq, detourM, spotId) =
                    if (bestAtEnd != null && abs(bestAtEnd.walkKm - stageEndKm) <= 3.5) {
                        StopInfo(
                            bestAtEnd.name,
                            bestAtEnd.type,
                            bestAtEnd.lat,
                            bestAtEnd.lng,
                            bestAtEnd.bookingRequired,
                            bestAtEnd.detourMeters,
                            bestAtEnd.id
                        )
                    } else {
                        val nearGoalShelter = shelters.find { GeoUtils.distanceMeters(goalPt.lat, goalPt.lng, it.lat, it.lng) <= 1500.0 }
                        if (nearGoalShelter != null) {
                            StopInfo(nearGoalShelter.name, "SHELTER", nearGoalShelter.lat, nearGoalShelter.lng, nearGoalShelter.bookingRequired, 0.0, nearGoalShelter.id)
                        } else {
                            StopInfo("Mål: $effectiveRouteName slutpunkt", "DESTINATION", goalPt.lat, goalPt.lng, false, 0.0, null)
                        }
                    }

                val hasWater = waterPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }
                val hasToilet = toiletPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }
                val daySunset = resolveDaySunset(day, stopLat, stopLng, startDateEpochMs, sunsetTime)

                val stage = createTripStage(
                    dayNumber = day,
                    startKm = stageStartKm,
                    endKm = stageEndKm,
                    fromName = prevStopName,
                    toName = stopName,
                    stopType = stopType,
                    stopLat = stopLat,
                    stopLng = stopLng,
                    bookingRequired = bookingReq,
                    hasWaterNearby = hasWater,
                    hasToiletNearby = hasToilet,
                    detourMeters = detourM,
                    plannedRoute = plannedRoute,
                    paceKmh = paceKmh,
                    startTime = startTime,
                    sunsetTime = daySunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = spotId
                )
                stages.add(stage)
                prevEndWalkKm = stageEndKm
                prevStopName = stopName
            } else {
                val (primaryMinKm, primaryMaxKm) = computePrimaryWindow(targetKm, dailyTargetKm)
                val primaryWindowCandidates = candidates.filter {
                    it.walkKm >= primaryMinKm &&
                        it.walkKm <= primaryMaxKm &&
                        it.walkKm > (prevEndWalkKm + 0.1)
                }
                val windowCandidates = if (primaryWindowCandidates.isNotEmpty()) {
                    primaryWindowCandidates
                } else {
                    val (fallbackMinKm, fallbackMaxKm) = computeAdaptiveFallbackWindow(targetKm, dailyTargetKm)
                    candidates.filter {
                        it.walkKm >= fallbackMinKm &&
                            it.walkKm <= fallbackMaxKm &&
                            it.walkKm > (prevEndWalkKm + 0.1)
                    }
                }

                if (windowCandidates.isNotEmpty()) {
                    val best = windowCandidates.minByOrNull { cand ->
                        calculateSmartStageScore(
                            candidate = cand,
                            targetKm = targetKm,
                            dayStartKm = prevEndWalkKm,
                            dailyTargetKm = dailyTargetKm,
                            paceKmh = paceKmh,
                            startTime = startTime,
                            dayNumber = day,
                            startDateEpochMs = startDateEpochMs,
                            sunsetTimeOverride = sunsetTime,
                            allCandidates = candidates,
                            isSecondToLastDayOrEarlier = day < daysCount,
                            totalWalkKm = totalWalkKm
                        )
                    }!!
                    val stageStartKm = prevEndWalkKm
                    val stageEndKm = min(totalWalkKm, best.walkKm)
                    val daySunset = resolveDaySunset(day, best.lat, best.lng, startDateEpochMs, sunsetTime)

                    val stage = createTripStage(
                        dayNumber = day,
                        startKm = stageStartKm,
                        endKm = stageEndKm,
                        fromName = prevStopName,
                        toName = best.name,
                        stopType = best.type,
                        stopLat = best.lat,
                        stopLng = best.lng,
                        bookingRequired = best.bookingRequired,
                        hasWaterNearby = best.hasWaterNearby,
                        hasToiletNearby = best.hasToiletNearby,
                        detourMeters = best.detourMeters,
                        plannedRoute = plannedRoute,
                        paceKmh = paceKmh,
                        startTime = startTime,
                        sunsetTime = daySunset,
                        waterPois = waterPois,
                        shopPois = shopPois,
                        spotId = best.id
                    )
                    stages.add(stage)
                    prevEndWalkKm = stageEndKm
                    prevStopName = best.name
                } else {
                    val stageStartKm = prevEndWalkKm
                    val stageEndKm = min(totalWalkKm, targetKm)
                    val kmLabel = String.format(Locale.US, "%.1f", targetKm)
                    val warnMsg = "Ingen overnatning fundet nær km $kmLabel"

                    val ptAtKm = plannedRoute.points.find { it.walkKm >= stageEndKm } ?: plannedRoute.points.lastOrNull()
                    val endWp = waypoints.lastOrNull { !it.isHelper }
                    val lat = ptAtKm?.lat ?: endWp?.lat ?: continue
                    val lng = ptAtKm?.lng ?: endWp?.lng ?: continue
                    val daySunset = resolveDaySunset(day, lat, lng, startDateEpochMs, sunsetTime)

                    val stage = createTripStage(
                        dayNumber = day,
                        startKm = stageStartKm,
                        endKm = stageEndKm,
                        fromName = prevStopName,
                        toName = warnMsg,
                        stopType = "NONE",
                        stopLat = lat,
                        stopLng = lng,
                        bookingRequired = false,
                        hasWaterNearby = false,
                        hasToiletNearby = false,
                        detourMeters = 0.0,
                        plannedRoute = plannedRoute,
                        paceKmh = paceKmh,
                        startTime = startTime,
                        sunsetTime = daySunset,
                        waterPois = waterPois,
                        shopPois = shopPois,
                        isWarningNoOvernight = true,
                        warningMessage = warnMsg
                    )
                    stages.add(stage)
                    prevEndWalkKm = stageEndKm
                    prevStopName = "Km $kmLabel"
                }
            }
        }

        return stages
    }

    fun calculateStages(
        plannedRoute: PlannedRoute,
        trail: TrailEntity? = null,
        routeName: String = trail?.name ?: "",
        waypoints: List<com.example.util.routing.RouteWaypoint> = emptyList(),
        overnightWaypoints: List<com.example.util.routing.RouteWaypoint> = waypoints.filter {
            it.role == com.example.util.routing.WaypointRole.OVERNIGHT && !it.isHelper
        },
        overnightWalkKms: Map<String, Double> = emptyMap(),
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        numDays: Int = 1,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity> = emptyList(),
        friTeltnings: List<FriTeltningEntity> = emptyList(),
        pois: List<PoiEntity> = emptyList(),
        sunsetTime: String? = null,
        candidatesOverride: List<OvernightCandidate>? = null,
        startDateEpochMs: Long? = null
    ): List<TripStage> = planStages(
        plannedRoute = plannedRoute,
        trail = trail,
        routeName = routeName,
        waypoints = waypoints,
        overnightWaypoints = overnightWaypoints,
        overnightWalkKms = overnightWalkKms,
        paceKmh = paceKmh,
        dailyTargetKm = dailyTargetKm,
        numDays = numDays,
        overnightType = overnightType,
        maxDetourKm = maxDetourKm,
        startTime = startTime,
        shelters = shelters,
        friTeltnings = friTeltnings,
        pois = pois,
        sunsetTime = sunsetTime,
        candidatesOverride = candidatesOverride,
        startDateEpochMs = startDateEpochMs
    )

    fun recomputeStagesFrom(
        currentStages: List<TripStage>,
        changedStageIndex: Int,
        newCandidate: OvernightCandidate,
        plannedRoute: PlannedRoute,
        trail: TrailEntity,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        sunsetTime: String? = null,
        startDateEpochMs: Long? = null
    ): List<TripStage> = planStages(
        plannedRoute = plannedRoute,
        trail = trail,
        routeName = trail.name,
        paceKmh = paceKmh,
        dailyTargetKm = dailyTargetKm,
        numDays = currentStages.size,
        overnightType = overnightType,
        maxDetourKm = maxDetourKm,
        startTime = startTime,
        shelters = shelters,
        friTeltnings = friTeltnings,
        pois = pois,
        sunsetTime = sunsetTime,
        startDateEpochMs = startDateEpochMs,
        existingStages = currentStages,
        changedStageIndex = changedStageIndex,
        changedStageCandidate = newCandidate
    )

    fun recomputeStagesFromKm(
        currentStages: List<TripStage>,
        changedStageIndex: Int,
        newEndKm: Double,
        plannedRoute: PlannedRoute,
        trail: TrailEntity,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        sunsetTime: String? = null,
        startDateEpochMs: Long? = null
    ): List<TripStage> = planStages(
        plannedRoute = plannedRoute,
        trail = trail,
        routeName = trail.name,
        paceKmh = paceKmh,
        dailyTargetKm = dailyTargetKm,
        numDays = currentStages.size,
        overnightType = overnightType,
        maxDetourKm = maxDetourKm,
        startTime = startTime,
        shelters = shelters,
        friTeltnings = friTeltnings,
        pois = pois,
        sunsetTime = sunsetTime,
        startDateEpochMs = startDateEpochMs,
        existingStages = currentStages,
        changedStageIndex = changedStageIndex,
        changedStageEndKm = newEndKm
    )

    /**
     * Finds candidates sorted by proximity to targetKm (for offering the list of nearest spots).
     */
    fun findNearestCandidates(
        plannedRoute: PlannedRoute,
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        waterPois: List<PoiEntity>,
        toiletPois: List<PoiEntity>,
        targetKm: Double,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 3.0,
        count: Int = 8
    ): List<OvernightCandidate> {
        val candidates = extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = waterPois,
            toiletPois = toiletPois,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm
        )
        return candidates.sortedBy { abs(it.walkKm - targetKm) }.take(count)
    }

    /**
     * Builds TripStage with all metrics: elevation ascent/descent, walking time (+1 min per 10m ascent),
     * amenities passed, and sunset warning.
     */
    private fun createTripStage(
        dayNumber: Int,
        startKm: Double,
        endKm: Double,
        fromName: String,
        toName: String,
        stopType: String,
        stopLat: Double,
        stopLng: Double,
        bookingRequired: Boolean,
        hasWaterNearby: Boolean,
        hasToiletNearby: Boolean,
        detourMeters: Double,
        plannedRoute: PlannedRoute,
        paceKmh: Double,
        startTime: String,
        sunsetTime: String,
        waterPois: List<PoiEntity>,
        shopPois: List<PoiEntity>,
        spotId: String? = null,
        isWarningNoOvernight: Boolean = false,
        warningMessage: String? = null
    ): TripStage {
        val stageKm = max(0.0, endKm - startKm)

        // Elevation ascent & descent (Phase 2 elevation)
        val stagePoints = plannedRoute.points.filter {
            it.walkKm >= (startKm - 0.05) && it.walkKm <= (endKm + 0.05)
        }
        var ascentM = 0.0
        var descentM = 0.0
        for (i in 0 until stagePoints.size - 1) {
            val e1 = stagePoints[i].ele
            val e2 = stagePoints[i + 1].ele
            if (e1 != null && e2 != null) {
                val diff = e2 - e1
                if (diff > 0) ascentM += diff else descentM += abs(diff)
            }
        }

        // Estimated walking time from pace (+1 min per 10 m ascent - Requirement D)
        val effectivePace = if (paceKmh > 0.5) {
            paceKmh
        } else {
            com.example.data.local.SettingsRepository.cachedMitTempoKmh.coerceAtLeast(1.0)
        }
        val flatMinutes = (stageKm / effectivePace) * 60.0
        val elevationMinutes = ascentM / 10.0
        val totalWalkMinutes = (flatMinutes + elevationMinutes).roundToInt()

        // Water and shops passed that day
        var waterPassed = 0
        var shopsPassed = 0
        if (plannedRoute.points.isNotEmpty()) {
            val bbox = plannedRoute.boundingBox()
            val poiLatMargin = (1.5 / 110.54) + 0.002
            val poiLngMargin = (1.5 / (111.32 * kotlin.math.cos(Math.toRadians((bbox[0] + bbox[1]) / 2.0)).coerceAtLeast(0.1))) + 0.002
            val pMinLat = bbox[0] - poiLatMargin
            val pMaxLat = bbox[1] + poiLatMargin
            val pMinLng = bbox[2] - poiLngMargin
            val pMaxLng = bbox[3] + poiLngMargin

            for (w in waterPois) {
                if (w.lat < pMinLat || w.lat > pMaxLat || w.lng < pMinLng || w.lng > pMaxLng) continue
                val (wKm, dM) = plannedRoute.projectPoint(w.lat, w.lng)
                if (dM <= 1500.0 && wKm >= startKm && wKm <= endKm) {
                    waterPassed++
                }
            }
            for (s in shopPois) {
                if (s.lat < pMinLat || s.lat > pMaxLat || s.lng < pMinLng || s.lng > pMaxLng) continue
                val (wKm, dM) = plannedRoute.projectPoint(s.lat, s.lng)
                if (dM <= 1500.0 && wKm >= startKm && wKm <= endKm) {
                    shopsPassed++
                }
            }
        }

        // Sunset warning: start time + walking time + 1 hour breaks > sunset (Requirement D)
        val startParts = startTime.split(":").mapNotNull { it.toIntOrNull() }
        val startHour = startParts.getOrNull(0) ?: 9
        val startMin = startParts.getOrNull(1) ?: 0
        val startTotalMinutes = startHour * 60 + startMin

        val arrivalMinutes = startTotalMinutes + totalWalkMinutes + 60 // +1 hour breaks

        val isAfterSunset = if (sunsetTime.isBlank() || sunsetTime == "Solnedgang ukendt") {
            false
        } else {
            val sunsetParts = sunsetTime.split(":").mapNotNull { it.toIntOrNull() }
            if (sunsetParts.size >= 2) {
                val sunsetHour = sunsetParts[0]
                val sunsetMin = sunsetParts[1]
                val sunsetTotalMinutes = sunsetHour * 60 + sunsetMin
                arrivalMinutes > sunsetTotalMinutes
            } else {
                false
            }
        }

        return TripStage(
            dayNumber = dayNumber,
            startKm = startKm,
            endKm = endKm,
            stageKm = stageKm,
            stopName = toName,
            stopType = stopType,
            stopLat = stopLat,
            stopLng = stopLng,
            bookingRequired = bookingRequired,
            hasWaterNearby = hasWaterNearby,
            hasToiletNearby = hasToiletNearby,
            startTrailKm = startKm,
            endTrailKm = endKm,
            fromName = fromName,
            toName = toName,
            detourMeters = detourMeters,
            ascentMeters = ascentM,
            descentMeters = descentM,
            estimatedWalkMinutes = totalWalkMinutes,
            waterCount = waterPassed,
            shopCount = shopsPassed,
            sunsetTime = sunsetTime,
            isAfterSunset = isAfterSunset,
            spotId = spotId,
            isWarningNoOvernight = isWarningNoOvernight,
            warningMessage = warningMessage
        )
    }

    private fun parsePolygonPoints(polygonJson: String): List<GeoPointDto> {
        return try {
            geoPointListAdapter.fromJson(polygonJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private data class StopInfo(
        val name: String,
        val type: String,
        val lat: Double,
        val lng: Double,
        val bookingRequired: Boolean,
        val detourMeters: Double,
        val spotId: String?
    )

    /**
     * Requirement C: Find overnight candidates near each day's target km within max detour,
     * using Phase 5 scoring formula.
     */
    fun findCandidatesForDayTargets(
        plannedRoute: PlannedRoute,
        dailyTargetKm: Double,
        maxDetourKm: Double = 3.0,
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        waterPois: List<PoiEntity>,
        toiletPois: List<PoiEntity>
    ): Map<Int, List<OvernightCandidate>> {
        val totalKm = plannedRoute.totalWalkKm
        if (totalKm <= 0.0) return emptyMap()

        val allCandidates = extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = waterPois,
            toiletPois = toiletPois,
            overnightType = OvernightType.BOTH,
            maxDetourKm = maxDetourKm
        )

        val estimatedDays = max(1, kotlin.math.ceil(totalKm / dailyTargetKm).toInt())
        val result = mutableMapOf<Int, List<OvernightCandidate>>()

        for (day in 1 until estimatedDays) {
            val targetKm = day * dailyTargetKm
            // Find candidates near this day's target
            val scored = allCandidates.filter {
                abs(it.walkKm - targetKm) <= (dailyTargetKm * 0.75) && (it.detourMeters / 1000.0) <= maxDetourKm
            }.sortedBy { calculateScore(it, targetKm) }

            if (scored.isNotEmpty()) {
                result[day] = scored
            } else {
                val fallback = allCandidates.sortedBy { calculateScore(it, targetKm) }.take(5)
                if (fallback.isNotEmpty()) {
                    result[day] = fallback
                }
            }
        }

        return result
    }
}
