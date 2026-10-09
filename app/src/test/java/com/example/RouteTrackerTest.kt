package com.example

import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripStage
import com.example.util.GeoUtils
import com.example.util.RouteTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteTrackerTest {

    private fun createLinearRoute(
        totalLengthKm: Double = 50.0,
        startTrailKm: Double = 100.0,
        endTrailKm: Double = 150.0,
        isReverse: Boolean = false,
        numPoints: Int = 500
    ): PlannedRoute {
        val points = mutableListOf<RoutePoint>()
        // Let's create a straight north line from lat 55.0 to approx 55.45
        val baseLat = 55.0
        val baseLng = 10.0
        for (i in 0 until numPoints) {
            val fraction = i.toDouble() / (numPoints - 1)
            val walkKm = fraction * totalLengthKm
            val trailKm = if (isReverse) endTrailKm - fraction * (endTrailKm - startTrailKm)
                          else startTrailKm + fraction * (endTrailKm - startTrailKm)
            val lat = baseLat + (walkKm / 111.0)
            val lng = baseLng
            points.add(
                RoutePoint(
                    lat = lat,
                    lng = lng,
                    walkKm = walkKm,
                    trailKm = trailKm
                )
            )
        }

        val stages = listOf(
            TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 25.0,
                stageKm = 25.0,
                stopName = "Lejr Dag 1",
                stopType = "SHELTER",
                stopLat = points[numPoints / 2].lat,
                stopLng = points[numPoints / 2].lng
            ),
            TripStage(
                dayNumber = 2,
                startKm = 25.0,
                endKm = 50.0,
                stageKm = 25.0,
                stopName = "Slut Lejr",
                stopType = "SHELTER",
                stopLat = points.last().lat,
                stopLng = points.last().lng
            )
        )

        return PlannedRoute(
            planId = 1L,
            trailId = "trail_test",
            trailName = "Test Rute",
            isReverse = isReverse,
            startTrailKm = startTrailKm,
            endTrailKm = endTrailKm,
            totalWalkKm = totalLengthKm,
            points = points,
            stages = stages
        )
    }

    @Test
    fun planFrom100to150_startsAtZeroWalkedAndFiftyRemaining_notMarkedCompleted() {
        val route = createLinearRoute(totalLengthKm = 50.0, startTrailKm = 100.0, endTrailKm = 150.0)
        val tracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)

        val firstPt = route.points.first()
        val result = tracker.onLocationUpdate(
            lat = firstPt.lat,
            lng = firstPt.lng,
            bearing = 0f,
            accuracy = 10f
        )

        assertEquals(0.0, result.currentWalkKm, 0.05)
        assertEquals(0.0, result.walkedTodayKm, 0.05)
        assertEquals(25.0, result.remainingTodayKm, 0.05)
        assertEquals(50.0, result.remainingTripKm, 0.05)
        assertFalse(result.isCompleted)
        assertFalse(result.isOffRoute)
    }

    @Test
    fun walkingReverseDirection_countsUpCorrectly() {
        val reverseRoute = createLinearRoute(
            totalLengthKm = 30.0,
            startTrailKm = 130.0,
            endTrailKm = 100.0,
            isReverse = true,
            numPoints = 300
        )
        val tracker = RouteTracker(reverseRoute, initialWalkKm = 0.0, initialDay = 1)

        // User starts at beginning of the reversed walk
        val startPt = reverseRoute.points.first()
        val res1 = tracker.onLocationUpdate(startPt.lat, startPt.lng, 0f, 8f)
        assertEquals(0.0, res1.currentWalkKm, 0.05)
        assertEquals(30.0, res1.remainingTripKm, 0.05)

        // User walks forward along reversed walk stepping 1 km at a time (within the 2 km window)
        for (km in 1..10) {
            val stepPt = reverseRoute.points.first { it.walkKm >= km.toDouble() }
            tracker.onLocationUpdate(stepPt.lat, stepPt.lng, 0f, 10f)
        }

        val km10Pt = reverseRoute.points.first { it.walkKm >= 10.0 }
        val res2 = tracker.onLocationUpdate(km10Pt.lat, km10Pt.lng, 0f, 10f)
        assertEquals(10.0, res2.currentWalkKm, 0.1)
        assertEquals(20.0, res2.remainingTripKm, 0.1)
        assertFalse(res2.isCompleted)
    }

    @Test
    fun jumpOutsideWindow_acceptedOnlyAfterThreeConsecutiveAgreeingFixes() {
        val route = createLinearRoute(50.0)
        val tracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)

        val pt15 = route.points.first { it.walkKm >= 15.0 }

        // Fix 1 at km 15: outside window [0.0 - 0.3, 0.0 + 2.0]
        val res1 = tracker.onLocationUpdate(pt15.lat, pt15.lng, 0f, 10f)
        assertEquals("Fix 1 outside window does not jump immediately", 0.0, res1.currentWalkKm, 0.05)

        // Fix 2 at km 15: candidate added, still does not jump
        val res2 = tracker.onLocationUpdate(pt15.lat, pt15.lng, 0f, 10f)
        assertEquals("Fix 2 outside window still does not jump", 0.0, res2.currentWalkKm, 0.05)

        // Fix 3 at km 15: 3 consecutive agreeing fixes -> Accept jump!
        val res3 = tracker.onLocationUpdate(pt15.lat, pt15.lng, 0f, 10f)
        assertEquals("Fix 3 outside window accepts the jump", 15.0, res3.currentWalkKm, 0.1)
    }

    @Test
    fun passingSamePlaceTwice_doesNotMakeKmJumpDueToWindow() {
        // Create a route that loops back near the start at km 45 (like Svendborg on Øhavsstien)
        val points = mutableListOf<RoutePoint>()
        // 0 to 5 km: going North
        for (i in 0..50) {
            val km = i * 0.1
            points.add(RoutePoint(55.0 + (km / 111.0), 10.0, 0.0, km, km))
        }
        // Loop East and then back near (55.05, 10.0) at km 45.0
        for (i in 51..450) {
            val km = i * 0.1
            val lat = if (i == 450) 55.05001 else 55.10 + (i * 0.0001)
            val lng = if (i == 450) 10.00001 else 10.20
            points.add(RoutePoint(lat, lng, 0.0, km, km))
        }

        val route = PlannedRoute(
            planId = 2L,
            trailId = "trail_loop",
            trailName = "Loop Trail",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 45.0,
            totalWalkKm = 45.0,
            points = points,
            stages = listOf(TripStage(1, 0.0, 45.0, 45.0, "End", "SHELTER", 55.05, 10.0))
        )

        // User is currently at km 5.0
        val tracker = RouteTracker(route, initialWalkKm = 5.0, initialDay = 1)

        // GPS fix arrives at (55.05001, 10.00001). This position is close to BOTH km 5.0 AND km 45.0!
        val fixResult = tracker.onLocationUpdate(55.05001, 10.00001, 0f, 10f)

        // The snapping window is [5.0 - 0.3, 5.0 + 2.0] = [4.7, 7.0]
        // Therefore, it must snap to km 5.0, NOT jump to km 45.0!
        assertEquals(5.0, fixResult.currentWalkKm, 0.1)
    }

    @Test
    fun poorGpsAccuracyOver30m_isIgnoredForProgressAndNoOffRouteAlarm() {
        val route = createLinearRoute(50.0)
        val tracker = RouteTracker(route, initialWalkKm = 10.0, initialDay = 1)

        // Poor accuracy fix under trees (accuracy = 45m), 60m away from trail
        val fix1 = tracker.onLocationUpdate(
            lat = 55.0 + (10.0 / 111.0),
            lng = 10.001, // ~60m away
            bearing = 0f,
            accuracy = 45f // poor accuracy > 30m!
        )

        assertTrue(fix1.ignoredForProgress)
        assertEquals(10.0, fix1.currentWalkKm, 0.01)
        assertFalse("Standing under trees with poor GPS must NOT give off-route alarm", fix1.isOffRoute)
        assertFalse(fix1.shouldVibrate)
    }

    @Test
    fun offRouteDetection_requiresThreeConsecutiveAccurateFixes() {
        val route = createLinearRoute(50.0)
        val tracker = RouteTracker(route, initialWalkKm = 5.0, initialDay = 1)

        // 80m off trail, accuracy = 15m (accurate). Threshold = max(40, 1.5 * 15) = 40m.
        val offLat = 55.0 + (5.0 / 111.0)
        val offLng = 10.0015 // ~100m East

        val fix1 = tracker.onLocationUpdate(offLat, offLng, 0f, 15f)
        assertFalse(fix1.isOffRoute) // fix 1: not off route yet

        val fix2 = tracker.onLocationUpdate(offLat, offLng, 0f, 15f)
        assertFalse(fix2.isOffRoute) // fix 2: not off route yet

        val fix3 = tracker.onLocationUpdate(offLat, offLng, 0f, 15f)
        assertTrue(fix3.isOffRoute) // fix 3: now OFF ROUTE!
        assertTrue(fix3.shouldVibrate) // vibrations triggered once when it starts
        assertNotNull(fix3.nearestPointAhead)

        // User moves back to within 20m (< 25m) of the route
        val onTrailLat = 55.0 + (5.0 / 111.0)
        val onTrailLng = 10.0001 // ~7m from trail
        val fixBack = tracker.onLocationUpdate(onTrailLat, onTrailLng, 0f, 10f)
        assertFalse("Back on route when under 25m", fixBack.isOffRoute)
    }

    @Test
    fun markCompleted_onlyWhenRemainingTripUnder0_1AndWithin100mOfEnd() {
        val route = createLinearRoute(50.0)
        val tracker = RouteTracker(route, initialWalkKm = 49.95, initialDay = 2)

        val endPt = route.points.last()

        // 1. User is at 49.95 km, within 50m of endPt
        val res = tracker.onLocationUpdate(endPt.lat, endPt.lng, 0f, 5f)
        assertTrue("Remaining trip < 0.1km and within 100m of end -> Completed", res.isCompleted)
    }
}
