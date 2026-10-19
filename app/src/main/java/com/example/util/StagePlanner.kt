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

    /**
     * Requirement B: Extract candidates along the route within maxDetour.
     * - Every overnight item with detourMeters <= max detour, with its walkKm.
     * - Fri teltning areas use the nearest point of the polygon, not the centre.
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
        val maxDetourMeters = maxDetourKm * 1000.0
        val candidates = mutableListOf<OvernightCandidate>()

        // 1. Shelters and Campsites
        if (overnightType != OvernightType.FRI_TELTNING) {
            for (sh in shelters) {
                val (walkKm, detourMeters) = plannedRoute.projectPoint(sh.lat, sh.lng)
                if (detourMeters <= maxDetourMeters) {
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

                    if (matchesPreference) {
                        val hasWater = sh.hasWaterNearby || waterPois.any {
                            GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0
                        }
                        val hasToilet = sh.hasToiletNearby || toiletPois.any {
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
        }

        // 2. Fri Teltning areas (nearest point of polygon, not the centre - Requirement B)
        if (overnightType == OvernightType.FRI_TELTNING || overnightType == OvernightType.BOTH) {
            for (ft in friTeltnings) {
                val polyPoints = parsePolygonPoints(ft.polygonJson)
                val bestProjection: Triple<Double, Double, GeoPointDto> = if (polyPoints.isNotEmpty()) {
                    var minDetour = Double.MAX_VALUE
                    var bestWalk = 0.0
                    var nearestPt = polyPoints[0]
                    for (pt in polyPoints) {
                        val (wKm, dM) = plannedRoute.projectPoint(pt.lat, pt.lng)
                        if (dM < minDetour) {
                            minDetour = dM
                            bestWalk = wKm
                            nearestPt = pt
                        }
                    }
                    Triple(bestWalk, minDetour, nearestPt)
                } else {
                    val (wKm, dM) = plannedRoute.projectPoint(ft.centerLat, ft.centerLng)
                    Triple(wKm, dM, GeoPointDto(ft.centerLat, ft.centerLng))
                }

                val walkKm = bestProjection.first
                val detourMeters = bestProjection.second
                val chosenPt = bestProjection.third

                if (detourMeters <= maxDetourMeters) {
                    val hasWater = ft.waterDistanceMeters <= 500 || waterPois.any {
                        GeoUtils.distanceMeters(chosenPt.lat, chosenPt.lng, it.lat, it.lng) <= 500.0
                    }
                    val hasToilet = ft.toiletDistanceMeters <= 500 || toiletPois.any {
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

    /**
     * Core calculation of trip stages according to Phase 5 Requirements A, B, C, D.
     */
    fun calculateStages(
        plannedRoute: PlannedRoute,
        trail: TrailEntity,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        numDays: Int = 1,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        sunsetTime: String? = null,
        candidatesOverride: List<OvernightCandidate>? = null
    ): List<TripStage> {
        val totalWalkKm = max(0.1, plannedRoute.totalWalkKm)
        val daysCount = max(1, min(30, numDays))

        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }
        val shopPois = pois.filter { it.type in listOf("shop", "supermarket", "convenience", "bakery", "farm") }

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
        var prevStopName = "Start: ${trail.name}"

        val effSunset = sunsetTime ?: "Solnedgang ukendt"

        for (day in 1..daysCount) {
            val isLastDay = (day == daysCount)
            val targetKm = prevEndWalkKm + dailyTargetKm
            // Last day reaches route end if planned route ends within reasonable range of target
            val reachesRouteEnd = isLastDay && (totalWalkKm <= (targetKm + 3.0))

            if (reachesRouteEnd) {
                // Last day ends at route end (Requirement C)
                val stageStartKm = prevEndWalkKm
                val stageEndKm = totalWalkKm
                val goalPt = plannedRoute.points.lastOrNull() ?: RoutePoint(55.0, 10.0, 0.0, totalWalkKm, trail.computedWalkingKm)

                // Check candidate at/near route end
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
                            StopInfo("Mål: ${trail.name} slutpunkt", "DESTINATION", goalPt.lat, goalPt.lng, false, 0.0, null)
                        }
                    }

                // Water and toilet flags computed like any other day, never hard-coded (Requirement C)
                val hasWater = waterPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }
                val hasToilet = toiletPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }

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
                    sunsetTime = effSunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = spotId
                )
                stages.add(stage)
                prevEndWalkKm = stageEndKm
                prevStopName = stopName
            } else {
                // Look at candidates between target - 4 km and target + 3 km (Requirement C)
                val windowCandidates = candidates.filter {
                    it.walkKm >= (targetKm - 4.0) &&
                            it.walkKm <= (targetKm + 3.0) &&
                            it.walkKm > (prevEndWalkKm + 0.1)
                }

                if (windowCandidates.isNotEmpty()) {
                    // Pick the candidate with the lowest score (Requirement C)
                    val best = windowCandidates.minByOrNull { calculateScore(it, targetKm) }!!
                    val stageStartKm = prevEndWalkKm
                    val stageEndKm = min(totalWalkKm, best.walkKm)

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
                        sunsetTime = effSunset,
                        waterPois = waterPois,
                        shopPois = shopPois,
                        spotId = best.id
                    )
                    stages.add(stage)
                    prevEndWalkKm = stageEndKm
                    prevStopName = best.name
                } else {
                    // No candidate found: mark "Ingen overnatning fundet nær km X" in red (Requirement C)
                    val stageStartKm = prevEndWalkKm
                    val stageEndKm = min(totalWalkKm, targetKm)
                    val kmLabel = String.format(Locale.US, "%.1f", targetKm)
                    val warnMsg = "Ingen overnatning fundet nær km $kmLabel"

                    val ptAtKm = plannedRoute.points.find { it.walkKm >= stageEndKm } ?: plannedRoute.points.lastOrNull()
                    val lat = ptAtKm?.lat ?: 55.0
                    val lng = ptAtKm?.lng ?: 10.0

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
                        sunsetTime = effSunset,
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

    /**
     * Requirement E: Recomputing subsequent stages only when a night is edited.
     * E.g. changing night 3 recomputes nights 4-7 only, keeping nights 1-2 unchanged.
     */
    fun recomputeStagesFrom(
        currentStages: List<TripStage>,
        changedStageIndex: Int, // 0-indexed (e.g. index 2 for Night 3)
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
        sunsetTime: String? = null
    ): List<TripStage> {
        if (changedStageIndex !in currentStages.indices) return currentStages

        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }
        val shopPois = pois.filter { it.type in listOf("shop", "supermarket", "convenience", "bakery", "farm") }
        val effSunset = sunsetTime ?: "Solnedgang ukendt"
        val totalWalkKm = max(0.1, plannedRoute.totalWalkKm)

        val newStages = mutableListOf<TripStage>()

        // 1. Keep stages before changedStageIndex completely unchanged
        for (i in 0 until changedStageIndex) {
            newStages.add(currentStages[i])
        }

        // 2. Update changed stage with the new candidate
        val prevEnd = if (changedStageIndex > 0) newStages[changedStageIndex - 1].endKm else 0.0
        val prevName = if (changedStageIndex > 0) newStages[changedStageIndex - 1].stopName else "Start: ${trail.name}"

        val updatedChangedStage = createTripStage(
            dayNumber = changedStageIndex + 1,
            startKm = prevEnd,
            endKm = min(totalWalkKm, newCandidate.walkKm),
            fromName = prevName,
            toName = newCandidate.name,
            stopType = newCandidate.type,
            stopLat = newCandidate.lat,
            stopLng = newCandidate.lng,
            bookingRequired = newCandidate.bookingRequired,
            hasWaterNearby = newCandidate.hasWaterNearby,
            hasToiletNearby = newCandidate.hasToiletNearby,
            detourMeters = newCandidate.detourMeters,
            plannedRoute = plannedRoute,
            paceKmh = paceKmh,
            startTime = startTime,
            sunsetTime = effSunset,
            waterPois = waterPois,
            shopPois = shopPois,
            spotId = newCandidate.id
        )
        newStages.add(updatedChangedStage)

        // 3. Recompute remaining stages starting from new candidate's walkKm
        var curEnd = updatedChangedStage.endKm
        var curName = updatedChangedStage.stopName

        val allCandidates = extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = waterPois,
            toiletPois = toiletPois,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm
        )

        val totalDays = currentStages.size
        for (day in (changedStageIndex + 2)..totalDays) {
            val isLast = (day == totalDays)
            val targetKm = curEnd + dailyTargetKm
            val reachesRouteEnd = isLast && (totalWalkKm <= (targetKm + 3.0))

            if (reachesRouteEnd) {
                val goalPt = plannedRoute.points.lastOrNull() ?: RoutePoint(55.0, 10.0, 0.0, totalWalkKm, trail.computedWalkingKm)
                val windowCandidates = allCandidates.filter {
                    it.walkKm >= (totalWalkKm - 4.0) &&
                            it.walkKm <= (totalWalkKm + 3.0) &&
                            it.walkKm > (curEnd + 0.1)
                }
                val bestAtEnd = windowCandidates.minByOrNull { calculateScore(it, totalWalkKm) }
                    ?: allCandidates.filter { it.walkKm > (curEnd + 0.1) }
                        .minByOrNull { abs(it.walkKm - totalWalkKm) }

                val (stopName, stopType, stopLat, stopLng, bookingReq, detourM, spotId) =
                    if (bestAtEnd != null && abs(bestAtEnd.walkKm - totalWalkKm) <= 3.5) {
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
                            StopInfo("Mål: ${trail.name} slutpunkt", "DESTINATION", goalPt.lat, goalPt.lng, false, 0.0, null)
                        }
                    }

                val hasWater = waterPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }
                val hasToilet = toiletPois.any { GeoUtils.distanceMeters(stopLat, stopLng, it.lat, it.lng) <= 500.0 }

                val stage = createTripStage(
                    dayNumber = day,
                    startKm = curEnd,
                    endKm = totalWalkKm,
                    fromName = curName,
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
                    sunsetTime = effSunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = spotId
                )
                newStages.add(stage)
                curEnd = totalWalkKm
                curName = stopName
            } else {
                val windowCandidates = allCandidates.filter {
                    it.walkKm >= (targetKm - 4.0) &&
                            it.walkKm <= (targetKm + 3.0) &&
                            it.walkKm > (curEnd + 0.1)
                }

                if (windowCandidates.isNotEmpty()) {
                    val best = windowCandidates.minByOrNull { calculateScore(it, targetKm) }!!
                    val stage = createTripStage(
                        dayNumber = day,
                        startKm = curEnd,
                        endKm = min(totalWalkKm, best.walkKm),
                        fromName = curName,
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
                        sunsetTime = effSunset,
                        waterPois = waterPois,
                        shopPois = shopPois,
                        spotId = best.id
                    )
                    newStages.add(stage)
                    curEnd = stage.endKm
                    curName = best.name
                } else {
                    val stageEndKm = min(totalWalkKm, targetKm)
                    val kmLabel = String.format(Locale.US, "%.1f", targetKm)
                    val warnMsg = "Ingen overnatning fundet nær km $kmLabel"
                    val ptAtKm = plannedRoute.points.find { it.walkKm >= stageEndKm } ?: plannedRoute.points.lastOrNull()
                    val lat = ptAtKm?.lat ?: 55.0
                    val lng = ptAtKm?.lng ?: 10.0

                    val stage = createTripStage(
                        dayNumber = day,
                        startKm = curEnd,
                        endKm = stageEndKm,
                        fromName = curName,
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
                        sunsetTime = effSunset,
                        waterPois = waterPois,
                        shopPois = shopPois,
                        isWarningNoOvernight = true,
                        warningMessage = warnMsg
                    )
                    newStages.add(stage)
                    curEnd = stageEndKm
                    curName = "Km $kmLabel"
                }
            }
        }

        return newStages
    }

    /**
     * Requirement E: Drag a stage end along the route; later stages recompute.
     */
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
        sunsetTime: String? = null
    ): List<TripStage> {
        if (changedStageIndex !in currentStages.indices) return currentStages

        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }
        val shopPois = pois.filter { it.type in listOf("shop", "supermarket", "convenience", "bakery", "farm") }
        val effSunset = sunsetTime ?: "Solnedgang ukendt"
        val totalWalkKm = max(0.1, plannedRoute.totalWalkKm)

        val allCandidates = extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = waterPois,
            toiletPois = toiletPois,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm
        )

        val clampedEnd = newEndKm.coerceIn(
            if (changedStageIndex > 0) currentStages[changedStageIndex - 1].endKm + 0.5 else 0.5,
            totalWalkKm
        )

        // Find candidate closest to dragged end point
        val nearCand = allCandidates.minByOrNull { abs(it.walkKm - clampedEnd) }

        val candToUse = if (nearCand != null && abs(nearCand.walkKm - clampedEnd) <= 3.5) {
            nearCand
        } else {
            val ptAtKm = plannedRoute.points.find { it.walkKm >= clampedEnd } ?: plannedRoute.points.lastOrNull()
            OvernightCandidate(
                id = "dragged_$changedStageIndex",
                name = "Km ${String.format(Locale.US, "%.1f", clampedEnd)}",
                type = "NONE",
                lat = ptAtKm?.lat ?: 55.0,
                lng = ptAtKm?.lng ?: 10.0,
                walkKm = clampedEnd,
                detourMeters = 0.0,
                bookingRequired = false,
                hasWaterNearby = false,
                hasToiletNearby = false
            )
        }

        return recomputeStagesFrom(
            currentStages = currentStages,
            changedStageIndex = changedStageIndex,
            newCandidate = candToUse,
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = sunsetTime
        )
    }

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
        val flatMinutes = (stageKm / max(1.0, paceKmh)) * 60.0
        val elevationMinutes = ascentM / 10.0
        val totalWalkMinutes = (flatMinutes + elevationMinutes).roundToInt()

        // Water and shops passed that day
        var waterPassed = 0
        var shopsPassed = 0
        for (w in waterPois) {
            val (wKm, dM) = plannedRoute.projectPoint(w.lat, w.lng)
            if (dM <= 1500.0 && wKm >= startKm && wKm <= endKm) {
                waterPassed++
            }
        }
        for (s in shopPois) {
            val (wKm, dM) = plannedRoute.projectPoint(s.lat, s.lng)
            if (dM <= 1500.0 && wKm >= startKm && wKm <= endKm) {
                shopsPassed++
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
     * Requirement D: Turns a custom route into a trip plan with the existing stage planner;
     * OVERNIGHT waypoints are fixed stage ends.
     */
    fun calculateStagesForCustomRoute(
        plannedRoute: PlannedRoute,
        routeName: String,
        waypoints: List<com.example.util.routing.RouteWaypoint>,
        overnightWaypoints: List<com.example.util.routing.RouteWaypoint>,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        numDays: Int = 1,
        overnightType: OvernightType = OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<ShelterEntity>,
        friTeltnings: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        sunsetTime: String? = null
    ): List<TripStage> {
        val totalWalkKm = max(0.1, plannedRoute.totalWalkKm)
        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }
        val shopPois = pois.filter { it.type in listOf("shop", "supermarket", "convenience", "bakery", "farm") }
        val effSunset = sunsetTime ?: "Solnedgang ukendt"

        // If there are OVERNIGHT waypoints, they are fixed stage ends (Requirement D)
        if (overnightWaypoints.isNotEmpty()) {
            val stages = mutableListOf<TripStage>()
            var prevKm = 0.0
            var prevName = "Start: $routeName"

            for ((index, onWp) in overnightWaypoints.withIndex()) {
                val day = index + 1
                val (wpWalkKm, detourM) = plannedRoute.projectPoint(onWp.lat, onWp.lng)
                val stageEndKm = min(totalWalkKm, max(prevKm + 0.1, wpWalkKm))

                // Determine stop type and amenities
                val shelterMatch = shelters.find { it.id == onWp.linkedPoiId }
                val friMatch = friTeltnings.find { it.id == onWp.linkedPoiId }

                val stopType = if (friMatch != null || onWp.name?.contains("teltning", ignoreCase = true) == true) {
                    "FRI_TELTNING"
                } else {
                    "SHELTER"
                }

                val hasWater = shelterMatch?.hasWaterNearby == true || waterPois.any {
                    GeoUtils.distanceMeters(onWp.lat, onWp.lng, it.lat, it.lng) <= 500.0
                }
                val hasToilet = shelterMatch?.hasToiletNearby == true || friMatch?.toiletDistanceMeters?.let { it <= 500 } == true || toiletPois.any {
                    GeoUtils.distanceMeters(onWp.lat, onWp.lng, it.lat, it.lng) <= 500.0
                }
                val booking = shelterMatch?.bookingRequired == true

                val stage = createTripStage(
                    dayNumber = day,
                    startKm = prevKm,
                    endKm = stageEndKm,
                    fromName = prevName,
                    toName = onWp.name ?: "Lejr $day",
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
                    sunsetTime = effSunset,
                    waterPois = waterPois,
                    shopPois = shopPois,
                    spotId = onWp.linkedPoiId
                )
                stages.add(stage)
                prevKm = stageEndKm
                prevName = stage.stopName
            }

            // Final stage to the route end destination
            val finalDay = overnightWaypoints.size + 1
            val endWp = waypoints.lastOrNull()
            val endLat = endWp?.lat ?: plannedRoute.points.lastOrNull()?.lat ?: 56.0
            val endLng = endWp?.lng ?: plannedRoute.points.lastOrNull()?.lng ?: 10.0
            val destName = endWp?.name?.takeIf { it.isNotBlank() } ?: "Mål: $routeName"

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
                sunsetTime = effSunset,
                waterPois = waterPois,
                shopPois = shopPois,
                spotId = null
            )
            stages.add(finalStage)
            return stages
        }

        // Otherwise, fallback to standard StagePlanner algorithm
        val dummyTrail = TrailEntity(
            id = plannedRoute.trailId,
            name = routeName,
            description = "Brugerskabt rute",
            region = "Danmark",
            parentRegion = "Danmark",
            computedWalkingKm = totalWalkKm
        )
        return calculateStages(
            plannedRoute = plannedRoute,
            trail = dummyTrail,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            numDays = numDays,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = sunsetTime
        )
    }

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
