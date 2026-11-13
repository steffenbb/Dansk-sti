package com.example.util

import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class TrackingResult(
    val userLat: Double,
    val userLng: Double,
    val userBearing: Float,
    val accuracyM: Float,
    val currentWalkKm: Double,
    val currentDay: Int,
    val currentStage: TripStage?,
    val walkedTodayKm: Double,
    val remainingTodayKm: Double,
    val remainingTripKm: Double,
    val snappedPoint: GeoPointDto?,
    val isOffRoute: Boolean,
    val offRouteDistanceM: Double,
    val nearestPointAhead: RoutePoint?,
    val bearingToPointAhead: Float,
    val isCompleted: Boolean,
    val ignoredForProgress: Boolean,
    val shouldVibrate: Boolean,
    val shouldPersistToDb: Boolean,
    val isDetourMuted: Boolean = false,
    val hasArrivedAtTonightCamp: Boolean = false,
    val autoAdvancedDayMessage: String? = null
)

data class SegmentProjection(
    val projLat: Double,
    val projLng: Double,
    val distanceM: Double,
    val walkKm: Double,
    val segmentIndex: Int
)

class RouteTracker(
    val plannedRoute: PlannedRoute,
    initialWalkKm: Double = 0.0,
    initialDay: Int = 1
) {
    var currentWalkKm: Double = initialWalkKm
        private set

    var currentDay: Int = initialDay.coerceAtLeast(1)
        private set

    var isOffRoute: Boolean = false
        private set

    var isCompleted: Boolean = false
        private set

    var hasArrivedAtTonightCamp: Boolean = false
        private set

    var autoAdvancedDayMessage: String? = null
        private set

    var lastPersistedWalkKm: Double = initialWalkKm
        private set

    var lastPersistedTimeMs: Long = 0L
        private set

    var lastVibrationTimeMs: Long = 0L
        private set

    var detourMuteUntilMs: Long = 0L
        private set

    private var consecutiveOffRouteCount = 0
    private val consecutiveJumpCandidates = mutableListOf<SegmentProjection>()
    private var hasAutoAdvancedPastCampWithoutStopping = false

    companion object {
        const val DETOUR_MUTE_DURATION_MS = 30L * 60L * 1000L // 30 minutes
        const val CAMP_ARRIVAL_THRESHOLD_M = 150.0
        const val PAST_CAMP_AUTO_ADVANCE_THRESHOLD_M = 500.0
    }

    /**
     * Mutes off-route vibration and banner for 30 minutes or until back within 25 m.
     */
    fun takeDetour(nowMs: Long = System.currentTimeMillis()) {
        detourMuteUntilMs = nowMs + DETOUR_MUTE_DURATION_MS
    }

    fun isDetourMuted(nowMs: Long = System.currentTimeMillis()): Boolean {
        return detourMuteUntilMs > 0L && nowMs < detourMuteUntilMs
    }

    /**
     * Called whenever GPS provides a new location update.
     */
    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        bearing: Float,
        accuracy: Float,
        timestampMs: Long = System.currentTimeMillis()
    ): TrackingResult {
        val points = plannedRoute.points

        if (points.isEmpty()) {
            return TrackingResult(
                userLat = lat,
                userLng = lng,
                userBearing = bearing,
                accuracyM = accuracy,
                currentWalkKm = 0.0,
                currentDay = 1,
                currentStage = null,
                walkedTodayKm = 0.0,
                remainingTodayKm = 0.0,
                remainingTripKm = 0.0,
                snappedPoint = null,
                isOffRoute = false,
                offRouteDistanceM = 0.0,
                nearestPointAhead = null,
                bearingToPointAhead = 0f,
                isCompleted = false,
                ignoredForProgress = true,
                shouldVibrate = false,
                shouldPersistToDb = false,
                hasArrivedAtTonightCamp = false,
                autoAdvancedDayMessage = null
            )
        }

        // 1. Ignore fixes with accuracy > 30 m for progress
        val isAccurate = accuracy <= 30f

        var snappedProj: SegmentProjection? = null
        var shouldVibrate = false

        if (isAccurate) {
            // 2. Search window: [lastWalkKm - 0.3 km, lastWalkKm + 2.0 km]
            val windowMinKm = max(0.0, currentWalkKm - 0.3)
            val windowMaxKm = min(plannedRoute.totalWalkKm, currentWalkKm + 2.0)

            val windowBest = projectOntoWindow(lat, lng, windowMinKm, windowMaxKm)

            if (windowBest != null && windowBest.distanceM <= 60.0) {
                // Valid snap inside window!
                consecutiveJumpCandidates.clear()
                snappedProj = windowBest
                // Update walkKm (prevent jumping backwards unless small adjustment within 50m)
                val targetKm = min(plannedRoute.totalWalkKm, max(currentWalkKm - 0.05, windowBest.walkKm))
                currentWalkKm = targetKm
            } else {
                // Nothing within 60m in the window -> Search whole route!
                val globalBest = projectOntoWindow(lat, lng, 0.0, plannedRoute.totalWalkKm)

                if (globalBest != null && globalBest.distanceM <= 60.0) {
                    // Check if candidate agrees with previous jump candidates
                    val firstCandidate = consecutiveJumpCandidates.firstOrNull()
                    if (firstCandidate == null || abs(firstCandidate.walkKm - globalBest.walkKm) <= 0.15) {
                        consecutiveJumpCandidates.add(globalBest)
                    } else {
                        consecutiveJumpCandidates.clear()
                        consecutiveJumpCandidates.add(globalBest)
                    }

                    if (consecutiveJumpCandidates.size >= 3) {
                        // Accept the jump!
                        currentWalkKm = min(plannedRoute.totalWalkKm, globalBest.walkKm)
                        snappedProj = globalBest
                        consecutiveJumpCandidates.clear()
                    }
                } else {
                    consecutiveJumpCandidates.clear()
                }
            }

            // 3. Off-route warning evaluation
            // Off route = distance to route > max(40m, 1.5 * accuracy) for 3 consecutive accurate fixes
            val distToRoute = snappedProj?.distanceM
                ?: projectOntoWindow(lat, lng, 0.0, plannedRoute.totalWalkKm)?.distanceM
                ?: 0.0

            val offRouteThreshold = max(40.0, 1.5 * accuracy.toDouble())

            // Back within 25 m clears detour mute as well as off-route state
            if (distToRoute <= 25.0) {
                detourMuteUntilMs = 0L
            }
            val muted = isDetourMuted(timestampMs)

            if (!isOffRoute) {
                if (distToRoute > offRouteThreshold) {
                    consecutiveOffRouteCount++
                    if (consecutiveOffRouteCount >= 3) {
                        isOffRoute = true
                        if (!muted) {
                            shouldVibrate = true
                            lastVibrationTimeMs = timestampMs
                        }
                    }
                } else {
                    consecutiveOffRouteCount = 0
                }
            } else {
                // Back on route = within 25 m
                if (distToRoute <= 25.0) {
                    isOffRoute = false
                    consecutiveOffRouteCount = 0
                } else if (!muted) {
                    // Vibrate at most every 2 minutes while off route
                    if (timestampMs - lastVibrationTimeMs >= 120_000L) {
                        shouldVibrate = true
                        lastVibrationTimeMs = timestampMs
                    }
                }
            }
        }

        // 4. Find nearest point AHEAD on route (walkKm >= currentWalkKm)
        val nearestAhead = findNearestPointAhead(lat, lng, currentWalkKm)
        val bearingToAhead = if (nearestAhead != null) {
            GeoUtils.bearing(lat, lng, nearestAhead.lat, nearestAhead.lng)
        } else bearing

        // 5. Stage calculations & Round 14 Item 4 multi-day navigation rules:
        // - Do NOT auto-advance the day when currentWalkKm passes today's stage end.
        //   Instead set "arrived at tonight's camp" and show the arrival card (using today's stage).
        // - If the user keeps walking more than 500 m past tonight's camp without pressing "Stop for i dag",
        //   advance one day once, and say so.
        val stages = plannedRoute.stages
        var todayStage = stages.getOrNull(currentDay - 1) ?: stages.lastOrNull()

        if (todayStage != null && todayStage.endWalkKm > 0.0) {
            val remainingToStageEndM = (todayStage.endWalkKm - currentWalkKm) * 1000.0
            val walkedPastStageEndM = (currentWalkKm - todayStage.endWalkKm) * 1000.0
            val hasNextDay = currentDay < stages.size
            val isCampStage = hasNextDay || !todayStage.stopType.equals("DESTINATION", ignoreCase = true)

            if (hasNextDay && walkedPastStageEndM > PAST_CAMP_AUTO_ADVANCE_THRESHOLD_M) {
                if (!hasAutoAdvancedPastCampWithoutStopping) {
                    val passedCampName = todayStage.stopName.ifBlank { "dagens lejr" }
                    hasAutoAdvancedPastCampWithoutStopping = true
                    currentDay += 1
                    todayStage = stages.getOrNull(currentDay - 1) ?: stages.lastOrNull()
                    hasArrivedAtTonightCamp = false
                    autoAdvancedDayMessage =
                        "Du gik mere end 500 m forbi $passedCampName uden at stoppe for i dag – skiftede til dag $currentDay"
                } else {
                    hasArrivedAtTonightCamp = false
                }
            } else if (isCampStage &&
                remainingToStageEndM <= CAMP_ARRIVAL_THRESHOLD_M &&
                walkedPastStageEndM <= PAST_CAMP_AUTO_ADVANCE_THRESHOLD_M
            ) {
                // Keep currentDay and todayStage on today's stage!
                hasArrivedAtTonightCamp = true
            } else {
                hasArrivedAtTonightCamp = false
            }
        } else {
            hasArrivedAtTonightCamp = false
        }

        val stageStartWalk = todayStage?.startWalkKm ?: 0.0
        val stageEndWalk = todayStage?.endWalkKm ?: plannedRoute.totalWalkKm

        val walkedTodayKm = max(0.0, currentWalkKm - stageStartWalk)
        val remainingTodayKm = max(0.0, stageEndWalk - currentWalkKm)
        val remainingTripKm = max(0.0, plannedRoute.totalWalkKm - currentWalkKm)

        // 6. Completion condition: remainingTripKm < 0.1 km AND within 100m of route end
        val endPoint = points.last()
        val distToEndM = GeoUtils.distanceMeters(lat, lng, endPoint.lat, endPoint.lng)
        if (remainingTripKm < 0.1 && distToEndM <= 100.0) {
            isCompleted = true
        }

        // 7. DB write throttling: at most every 30 s or every 100 m
        val movedSinceLastPersist = abs(currentWalkKm - lastPersistedWalkKm)
        val timeSinceLastPersist = timestampMs - lastPersistedTimeMs
        val shouldPersistToDb = isAccurate && (movedSinceLastPersist >= 0.1 || timeSinceLastPersist >= 30_000L)

        val snappedGeoPoint = if (snappedProj != null) {
            GeoPointDto(snappedProj.projLat, snappedProj.projLng)
        } else null

        val globalMinDistToRoute = snappedProj?.distanceM
            ?: projectOntoWindow(lat, lng, 0.0, plannedRoute.totalWalkKm)?.distanceM
            ?: 0.0

        return TrackingResult(
            userLat = lat,
            userLng = lng,
            userBearing = bearing,
            accuracyM = accuracy,
            currentWalkKm = currentWalkKm,
            currentDay = currentDay,
            currentStage = todayStage,
            walkedTodayKm = walkedTodayKm,
            remainingTodayKm = remainingTodayKm,
            remainingTripKm = remainingTripKm,
            snappedPoint = snappedGeoPoint,
            isOffRoute = isOffRoute,
            offRouteDistanceM = globalMinDistToRoute,
            nearestPointAhead = nearestAhead,
            bearingToPointAhead = bearingToAhead,
            isCompleted = isCompleted,
            ignoredForProgress = !isAccurate,
            shouldVibrate = shouldVibrate,
            shouldPersistToDb = shouldPersistToDb,
            isDetourMuted = isDetourMuted(timestampMs),
            hasArrivedAtTonightCamp = hasArrivedAtTonightCamp,
            autoAdvancedDayMessage = autoAdvancedDayMessage
        )
    }

    fun markPersisted(timestampMs: Long = System.currentTimeMillis()) {
        lastPersistedWalkKm = currentWalkKm
        lastPersistedTimeMs = timestampMs
    }

    /**
     * Round 14 Item 4:
     * stopForToday advances exactly one day from the day being walked.
     */
    fun stopForToday(): TripProgressEntity {
        val stages = plannedRoute.stages
        val nextDay = if (stages.isNotEmpty()) {
            min(stages.size, currentDay + 1)
        } else {
            currentDay + 1
        }
        currentDay = nextDay
        hasArrivedAtTonightCamp = false
        autoAdvancedDayMessage = null
        hasAutoAdvancedPastCampWithoutStopping = false

        val nextStage = stages.getOrNull(currentDay - 1)

        val remainingTripKm = max(0.0, plannedRoute.totalWalkKm - currentWalkKm)
        val remainingTodayKm = nextStage?.stageKm ?: 0.0

        val lastPt = plannedRoute.points.minByOrNull { abs(it.walkKm - currentWalkKm) }

        return TripProgressEntity(
            planId = plannedRoute.planId,
            currentDay = currentDay,
            distanceWalkedTodayKm = 0.0,
            totalDistanceWalkedKm = currentWalkKm,
            currentSnappedLat = lastPt?.lat ?: 0.0,
            currentSnappedLng = lastPt?.lng ?: 0.0,
            remainingDistanceTripKm = remainingTripKm,
            remainingDistanceTodayKm = remainingTodayKm,
            isPaused = true,
            isCompleted = isCompleted,
            lastActiveTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Projects a lat/lng onto segments of the planned route that fall within [minKm, maxKm].
     */
    private fun projectOntoWindow(
        lat: Double,
        lng: Double,
        minKm: Double,
        maxKm: Double
    ): SegmentProjection? {
        val pts = plannedRoute.points
        if (pts.size < 2) return null

        var bestProjection: SegmentProjection? = null
        var bestDistM = Double.MAX_VALUE

        for (i in 0 until pts.size - 1) {
            val p1 = pts[i]
            val p2 = pts[i + 1]

            // Skip gap between separate segments
            if (p1.segmentIndex != p2.segmentIndex) {
                continue
            }

            // Check if segment overlaps [minKm, maxKm]
            val segMinKm = min(p1.walkKm, p2.walkKm)
            val segMaxKm = max(p1.walkKm, p2.walkKm)

            if (segMaxKm < minKm || segMinKm > maxKm) {
                continue
            }

            val proj = projectPointOntoSegment(lat, lng, p1, p2, i)
            if (proj.distanceM < bestDistM) {
                bestDistM = proj.distanceM
                bestProjection = proj
            }
        }

        return bestProjection
    }

    /**
     * Linear orthogonal projection of P onto segment AB using equi-rectangular flat projection.
     */
    private fun projectPointOntoSegment(
        latP: Double,
        lngP: Double,
        a: RoutePoint,
        b: RoutePoint,
        segmentIndex: Int
    ): SegmentProjection {
        val midLatRad = Math.toRadians((a.lat + latP) / 2.0)
        val cosLat = cos(midLatRad)

        // Equi-rectangular meters relative to A
        val px = (lngP - a.lng) * cosLat * 111320.0
        val py = (latP - a.lat) * 110540.0

        val bx = (b.lng - a.lng) * cosLat * 111320.0
        val by = (b.lat - a.lat) * 110540.0

        val segLenSq = bx * bx + by * by
        val t = if (segLenSq <= 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)

        val projLat = a.lat + t * (b.lat - a.lat)
        val projLng = a.lng + t * (b.lng - a.lng)

        val distM = GeoUtils.distanceMeters(latP, lngP, projLat, projLng)
        val walkKm = a.walkKm + t * (b.walkKm - a.walkKm)

        return SegmentProjection(
            projLat = projLat,
            projLng = projLng,
            distanceM = distM,
            walkKm = walkKm,
            segmentIndex = segmentIndex
        )
    }

    /**
     * Searches points with walkKm >= currentWalkKm and finds the one with minimum distance to (lat, lng).
     */
    private fun findNearestPointAhead(lat: Double, lng: Double, currentKm: Double): RoutePoint? {
        val ptsAhead = plannedRoute.points.filter { it.walkKm >= currentKm }
        val searchSet = if (ptsAhead.isNotEmpty()) ptsAhead else plannedRoute.points
        return searchSet.minByOrNull { GeoUtils.distanceMeters(lat, lng, it.lat, it.lng) }
    }
}
