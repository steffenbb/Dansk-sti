package com.example

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.data.remote.OverpassClient
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.RouteTracker
import com.example.util.SunCalculator
import com.example.util.access.AccessGeometryUtils
import com.example.util.routing.LatLng
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class Stress2NavigationEndurance500FixesTest {

    @Before
    fun setUp() {
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        OverpassClient.customTransport = null
    }

    @After
    fun tearDown() {
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        OverpassClient.customTransport = null
    }

    private fun buildEndurancePlannedRoute(): PlannedRoute {
        // 5 km route with a 90-degree turn at 0.8 km so TurnCueGenerator produces turn cues
        val points = mutableListOf<RoutePoint>()
        val baseLat = 55.5000
        val baseLng = 9.5000
        // First 800 m heading north (~0.0072 deg lat)
        for (i in 0..80) {
            val walkKm = i * 0.01 // 10 m steps
            points.add(
                RoutePoint(
                    lat = baseLat + i * 0.00009,
                    lng = baseLng,
                    ele = 30.0,
                    walkKm = walkKm,
                    trailKm = walkKm,
                    segmentType = TrailSegmentType.WALK,
                    segmentIndex = 0
                )
            )
        }
        val cornerLat = points.last().lat
        // Next 1200 m heading east (~0.00016 deg lng per 10 m)
        for (i in 1..120) {
            val walkKm = 0.80 + i * 0.01
            points.add(
                RoutePoint(
                    lat = cornerLat,
                    lng = baseLng + i * 0.00016,
                    ele = 30.0,
                    walkKm = walkKm,
                    trailKm = walkKm,
                    segmentType = TrailSegmentType.WALK,
                    segmentIndex = 0
                )
            )
        }
        return PlannedRoute(
            planId = 500_002L,
            trailId = "stress2_trail",
            trailName = "Stress2 Endurance Rute",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 2.0,
            totalWalkKm = 2.0,
            points = points,
            stages = listOf(
                TripStage(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = 2.0,
                    startTrailKm = 0.0,
                    endTrailKm = 2.0,
                    stageKm = 2.0,
                    estimatedWalkMinutes = 30,
                    stopName = "Aftenlejr",
                    stopType = "SHELTER",
                    stopLat = points.last().lat,
                    stopLng = points.last().lng
                )
            )
        )
    }

    private fun interpolateRouteLocation(route: PlannedRoute, walkKm: Double, timestampMs: Long): Location {
        val targetKm = walkKm.coerceIn(0.0, route.totalWalkKm)
        val idx = (targetKm / 0.01).toInt().coerceIn(0, route.points.lastIndex)
        val pt = route.points[idx]
        return Location("gps").apply {
            latitude = pt.lat
            longitude = pt.lng
            time = timestampMs
            accuracy = 7.0f
            speed = 1.3f
            bearing = if (idx < 80) 0f else 90f
        }
    }

    @Test
    fun stress2_500FixesOutsideSunsetWindow_0SunsetQueries0SunsetNetworkCallsAnd1TurnCueComputation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        dao.clearAllAccessPolygons()

        val overpassCalls = AtomicInteger(0)
        OverpassClient.customTransport = { _, _ ->
            overpassCalls.incrementAndGet()
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }

        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildEndurancePlannedRoute()
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        // Midday timestamp: 12:00 local time on June 15 (far outside sunset - 45 min .. sunset + 60 min)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.JUNE)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t0 = cal.timeInMillis

        // Feed Fix 0 so initial corridor prefetch runs, then record network count to isolate sunset check network calls
        service.handleNewLocation(interpolateRouteLocation(route, 0.0, t0))
        service.corridorPrefetchJob?.join()
        val prefetchCalls = overpassCalls.get()
        assertTrue("Initial corridor prefetch should fetch <= 10 tiles", prefetchCalls in 1..10)

        // Feed remaining 499 fixes (total 500 fixes over 1,000 s at 1.3 m/s -> 1.3 km walked)
        for (i in 1 until 500) {
            val fixTime = t0 + i * 2_000L
            val walkKm = (i * 2.6) / 1000.0 // 1.3 m/s * 2 s = 2.6 m per fix
            service.handleNewLocation(interpolateRouteLocation(route, walkKm, fixTime))
        }
        service.sunsetCheckJob?.join()

        val sunsetNetworkCalls = overpassCalls.get() - prefetchCalls
        println(
            "Stress2 outside sunset window (500 fixes): " +
                "sunsetPolygonQueryCount=${service.sunsetPolygonQueryCount}, " +
                "sunsetCalcCount=${service.sunsetCalcCount}, " +
                "turnCueComputeCount=${service.turnCueComputeCount}, " +
                "sunsetNetworkCalls=$sunsetNetworkCalls"
        )

        // 1. 500 fixes outside the sunset window -> 0 polygon queries
        assertEquals("Outside sunset window must run 0 polygon queries", 0, service.sunsetPolygonQueryCount)
        // 2. Sunset calculated once per day per ~10 km
        assertEquals("Sunset must be calculated once for < 10 km on same day", 1, service.sunsetCalcCount)
        // 3. 0 network calls from sunset check
        assertEquals("Sunset check must make 0 network calls", 0, sunsetNetworkCalls)
        // 4. Turn cues computed once across all 500 fixes for the loaded PlannedRoute instance
        assertEquals("computeTurnCuesForPlannedRoute must be called once across 500 fixes", 1, service.turnCueComputeCount)
        assertNull(NavigationTrackingService.liveState.value.privateForestSunsetWarning)

        service.stopTracking()
        controller.destroy()
    }

    @Test
    fun stress2_500FixesInsideSunsetWindow_atMost17CachedQueriesAnd0SunsetNetworkCalls(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        dao.clearAllAccessPolygons()

        val route = buildEndurancePlannedRoute()
        val startPt = route.points.first()

        // Seed a cached PRIVATE_FOREST polygon covering the route so cache-only sunset checks find it
        val privateForest = AccessPolygon(
            id = "stress2_private_forest",
            name = "Privat Skov",
            type = AccessLandType.PRIVATE_FOREST,
            coordinates = listOf(
                LatLng(55.49, 9.49),
                LatLng(55.55, 9.49),
                LatLng(55.55, 9.55),
                LatLng(55.49, 9.55),
                LatLng(55.49, 9.49)
            ),
            source = "OpenStreetMap",
            operatorOrOwner = "Privat"
        )
        val tiles = com.example.data.repository.AccessLandRepository.getTileIdsForBounds(55.49, 55.55, 9.49, 9.55)
        for (tile in tiles) {
            dao.insertAccessPolygons(listOf(app.repository.accessLandRepo.modelToEntity(privateForest, tile.tileId)))
        }

        val overpassCalls = AtomicInteger(0)
        OverpassClient.customTransport = { _, _ ->
            overpassCalls.incrementAndGet()
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }

        // Compute today's sunset at startPt and set t0 = sunset - 30 min so all 500 fixes (1,000 s = 16.6 min) are inside [sunset - 45m, sunset + 60m]
        val baseDayCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.JUNE)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sunsetStr = SunCalculator.calculateSunset(epochMillis = baseDayCal.timeInMillis, lat = startPt.lat, lng = startPt.lng)
        val sunsetMins = AccessGeometryUtils.parseTimeToMinutes(sunsetStr)
        val startMins = sunsetMins - 30
        val insideWindowCal = (baseDayCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, startMins / 60)
            set(Calendar.MINUTE, startMins % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val t0 = insideWindowCal.timeInMillis

        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        // Feed 500 fixes over 1,000 s (1 fix every 2 s at 1.3 m/s) inside the sunset window
        for (i in 0 until 500) {
            val fixTime = t0 + i * 2_000L
            val walkKm = (i * 2.6) / 1000.0
            service.handleNewLocation(interpolateRouteLocation(route, walkKm, fixTime))
            service.sunsetCheckJob?.join()
        }
        service.corridorPrefetchJob?.join()

        println(
            "Stress2 inside sunset window (500 fixes / 1000s): " +
                "sunsetPolygonQueryCount=${service.sunsetPolygonQueryCount}, " +
                "overpassCalls=${overpassCalls.get()}, " +
                "turnCueComputeCount=${service.turnCueComputeCount}, " +
                "warning=${NavigationTrackingService.liveState.value.privateForestSunsetWarning}"
        )

        // 1. 1,000 s of fixes inside the window at 1.3 m/s -> at most 1 query per 60 s (<= 17 queries)
        assertTrue(
            "Expected 1..17 sunset polygon queries over 1,000 s inside window, got ${service.sunsetPolygonQueryCount}",
            service.sunsetPolygonQueryCount in 1..17
        )
        // 2. 0 network calls from sunset check (tiles were cached, and sunset check uses getCachedAccessPolygons)
        assertEquals("Expected 0 network calls when tiles are cached and sunset check is cache-only", 0, overpassCalls.get())
        // 3. Private forest sunset warning was populated from the cached polygon
        assertNotNull(
            "Expected privateForestSunsetWarning to be set when inside PRIVATE_FOREST during sunset window",
            NavigationTrackingService.liveState.value.privateForestSunsetWarning
        )
        // 4. Turn cues computed once
        assertEquals(1, service.turnCueComputeCount)

        service.stopTracking()
        controller.destroy()
    }
}
