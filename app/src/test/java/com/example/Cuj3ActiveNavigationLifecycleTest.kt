package com.example

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.remote.OverpassClient
import com.example.data.repository.AccessLandRepository
import com.example.service.GpsPowerProfile
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.util.RouteTracker
import com.google.android.gms.tasks.CancellationToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class Cuj3ActiveNavigationLifecycleTest {

    @Before
    fun setUp() {
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        OverpassClient.resetMetricsForTest()
        OverpassClient.customTransport = { _, _ ->
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }
    }

    @After
    fun tearDown() {
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        OverpassClient.resetMetricsForTest()
    }

    private fun buildLongLinearPlannedRoute(totalKm: Int = 50): PlannedRoute {
        // 1 point per km along latitude/longitude so route spans ~50 km across multiple 0.1 deg tiles
        val points = (0..totalKm).map { km ->
            RoutePoint(
                lat = 55.00 + km * 0.015, // ~1.66 km per 0.015 deg lat
                lng = 9.00 + km * 0.015,
                ele = 20.0,
                walkKm = km.toDouble(),
                trailKm = km.toDouble(),
                segmentType = TrailSegmentType.WALK,
                segmentIndex = 0
            )
        }
        return PlannedRoute(
            planId = 303_001L,
            trailId = "cuj3_trail",
            trailName = "Hærvejen Etape",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = totalKm.toDouble(),
            totalWalkKm = totalKm.toDouble(),
            points = points,
            stages = listOf(
                TripStage(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = totalKm.toDouble(),
                    startTrailKm = 0.0,
                    endTrailKm = totalKm.toDouble(),
                    stageKm = totalKm.toDouble(),
                    estimatedWalkMinutes = totalKm * 15,
                    stopName = "Lejrplads",
                    stopType = "SHELTER",
                    stopLat = points.last().lat,
                    stopLng = points.last().lng
                )
            )
        )
    }

    private fun makeLocation(
        lat: Double,
        lng: Double,
        timeMs: Long,
        accuracyM: Float = 8.0f,
        speedMps: Float = 1.3f
    ): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lng
            time = timeMs
            accuracy = accuracyM
            speed = speedMps
        }
    }

    @Test
    fun cuj3_corridorPrefetchFetchesNext25KmMax10TilesAtBackgroundPriorityAndHitsMapLayerCache(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        dao.clearAllAccessPolygons()

        val overpassCalls = AtomicInteger(0)
        val observedPriorities = mutableListOf<OverpassClient.Priority>()
        OverpassClient.customTransport = { _, query ->
            if (query.contains("landuse")) {
                overpassCalls.incrementAndGet()
            }
            OverpassClient.HttpResult(
                code = 200,
                body = """
                    {
                      "elements": [
                        {"type": "node", "id": 1, "lat": 55.01, "lon": 9.01},
                        {"type": "node", "id": 2, "lat": 55.02, "lon": 9.01},
                        {"type": "node", "id": 3, "lat": 55.02, "lon": 9.02},
                        {"type": "node", "id": 4, "lat": 55.01, "lon": 9.02},
                        {"type": "node", "id": 1, "lat": 55.01, "lon": 9.01},
                        {"type": "way", "id": 100, "nodes": [1,2,3,4,1], "tags": {"landuse": "forest", "access": "yes"}}
                      ]
                    }
                """.trimIndent()
            )
        }

        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 50)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)

        // Verify corridor tiles for next 25 km use AccessLandRepository.getTileIdsForBounds and cap at <= 10 tiles
        val tilesAtStart = service.collectNext25KmCorridorTiles(route, currentWalkKm = 0.0)
        assertTrue("Corridor prefetch must collect between 1 and 10 tiles", tilesAtStart.size in 1..10)
        val firstTile = tilesAtStart.first()
        val expectedFirstTile = AccessLandRepository.getTileIdsForBounds(
            route.points.first().lat,
            route.points.first().lat,
            route.points.first().lng,
            route.points.first().lng
        ).first()
        assertEquals(expectedFirstTile.tileId, firstTile.tileId)

        // Trigger initial fix at 0 km -> runs initial corridor prefetch once
        val t0 = 1_750_000_000_000L // daytime outside sunset window
        service.handleNewLocation(makeLocation(route.points[0].lat, route.points[0].lng, t0))
        service.corridorPrefetchJob?.join()

        assertEquals(1, service.corridorPrefetchRunCount)
        assertTrue(
            "Expected <= 10 tile fetches per run, got ${service.corridorPrefetchTileFetchCount}",
            service.corridorPrefetchTileFetchCount in 1..10
        )
        val callsAfterInitialPrefetch = overpassCalls.get()
        assertEquals(tilesAtStart.size, callsAfterInitialPrefetch)

        // Verify a prefetched tile is a 100% cache hit for the map layer afterwards (0 extra network fetches)
        val cachedForMapLayer = app.repository.getAccessPolygons(
            minLat = firstTile.minLat,
            maxLat = firstTile.maxLat,
            minLng = firstTile.minLng,
            maxLng = firstTile.maxLng
        )
        assertTrue("Prefetched tile must return cached access polygons", cachedForMapLayer.isNotEmpty())
        assertEquals(
            "Map layer query for prefetched tile must be a cache hit (0 extra network fetches)",
            callsAfterInitialPrefetch,
            overpassCalls.get()
        )

        // Walk to 15 km (< 20 km since last prefetch at 0 km) -> must NOT trigger a new prefetch run
        for (km in 1..15) {
            val pt = route.points[km]
            service.handleNewLocation(makeLocation(pt.lat, pt.lng, t0 + km * 60_000L))
        }
        service.corridorPrefetchJob?.join()
        assertEquals("Must not trigger a new corridor prefetch before +20 km", 1, service.corridorPrefetchRunCount)

        // Walk to 21 km (>= 20 km since last prefetch) -> triggers second corridor prefetch run
        for (km in 16..21) {
            val pt = route.points[km]
            service.handleNewLocation(makeLocation(pt.lat, pt.lng, t0 + km * 60_000L))
        }
        service.corridorPrefetchJob?.join()
        assertEquals("Expected second corridor prefetch run after walking >= 20 km", 2, service.corridorPrefetchRunCount)

        service.stopTracking()
        controller.destroy()
    }

    @Test
    fun cuj3_gpsPowerProfilesScreenOffAndReceiverUnregisterOnStop(): Unit = runBlocking {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 10)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        assertTrue("Screen receiver must be registered when tracking starts", service.isScreenReceiverRegistered)
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_2S, service.currentGpsProfile)

        val t0 = 1_750_000_000_000L
        val baseLat = route.points[0].lat
        val baseLng = route.points[0].lng

        // 1. Moving + navigating + screen off -> HIGH_ACCURACY_5S (5s interval, 2.5s minUpdateInterval, 0m minDistance)
        service.lastBroadcastScreenInteractive = false
        service.refreshGpsPowerProfile()
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_5S, service.currentGpsProfile)
        assertEquals(5_000L, service.currentGpsProfile.intervalMs)
        assertEquals(2_500L, service.currentGpsProfile.minUpdateIntervalMs)
        assertEquals(0.0f, service.currentGpsProfile.minDistanceM, 0.001f)

        // 2. A 30 s stop keeps HIGH_ACCURACY_5S (not stationary until 2 full minutes with >= 3 accurate fixes)
        service.handleNewLocation(makeLocation(baseLat, baseLng, t0, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(baseLat, baseLng, t0 + 15_000L, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(baseLat, baseLng, t0 + 30_000L, accuracyM = 8f, speedMps = 0f))
        assertFalse("30 s stop must not mark hiker stationary", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_5S, service.currentGpsProfile)

        // 3. Screen off and stationary for 2 min (>= 3 accurate fixes within 20 m) -> BALANCED_10S
        service.handleNewLocation(makeLocation(baseLat, baseLng, t0 + 125_000L, accuracyM = 8f, speedMps = 0f))
        assertTrue("2 min stopped with >= 3 accurate fixes must set isStationaryFor2Min", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)

        val rebindsWhenStationary = service.locationRebindCount

        // 4. Balanced fixes jittering 60 m with 65 m accuracy while standing still do NOT switch back to moving
        // when the confirmation fix shows the hiker is still at the anchor
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(baseLat, baseLng, t0 + 140_000L, accuracyM = 6f, speedMps = 0f)
        }
        // ~60 m north in latitude is ~0.00054 deg
        service.handleNewLocation(
            makeLocation(baseLat + 0.00054, baseLng, t0 + 140_000L, accuracyM = 65f, speedMps = 0f)
        )
        assertTrue("60 m inaccurate jitter with stationary confirmation must remain stationary", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)
        assertEquals("Profile did not change so location updates must not be re-requested", rebindsWhenStationary, service.locationRebindCount)

        // 5. Receiver unregistered after stopTracking
        service.stopTracking()
        assertFalse("Screen receiver must be unregistered when tracking stops", service.isScreenReceiverRegistered)
        controller.destroy()
    }

    @Test
    fun cuj3_screenOffStationaryWithFusedMinDistanceFilteringAnd3To8mJitterSwitchesToBalanced10s(): Unit = runBlocking {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 10)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        // Screen off while moving -> HIGH_ACCURACY_5S with minDistanceM = 0f
        service.lastBroadcastScreenInteractive = false
        service.refreshGpsPowerProfile()
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_5S, service.currentGpsProfile)
        assertEquals(0.0f, service.currentGpsProfile.minDistanceM, 0.001f)

        val t0 = 1_750_000_000_000L
        val baseLat = route.points[0].lat
        val baseLng = route.points[0].lng
        val metersPerDegLat = 111_320.0

        var lastDeliveredFix: Location? = null
        var deliveredFixCount = 0

        fun deliverFixWithFusedMinDistanceFilter(candidate: Location) {
            val minDistM = service.currentGpsProfile.minDistanceM
            val prev = lastDeliveredFix
            if (prev == null || minDistM <= 0.0f || candidate.distanceTo(prev) >= minDistM) {
                lastDeliveredFix = candidate
                deliveredFixCount++
                service.handleNewLocation(candidate)
            }
        }

        // Standing still with 3-8 m jitter for 3 minutes (36 steps of 5 s = 180 s)
        val jitterMetersCycle = doubleArrayOf(0.0, 3.2, 4.1, 3.6, 5.5, 7.8, 4.4, 3.1)
        for (step in 0..36) {
            val jitterM = jitterMetersCycle[step % jitterMetersCycle.size]
            val candidate = makeLocation(
                lat = baseLat + (jitterM / metersPerDegLat),
                lng = baseLng,
                timeMs = t0 + step * 5_000L,
                accuracyM = 9.0f,
                speedMps = 0.0f
            )
            deliverFixWithFusedMinDistanceFilter(candidate)
        }

        assertTrue("Fixes must be delivered while in HIGH_ACCURACY_5S with 3-8 m jitter", deliveredFixCount >= 25)
        assertTrue("Standing still for 3 min with screen off and 3-8 m jitter must detect stationary", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)

        service.corridorPrefetchJob?.join()
        service.stopTracking()
        controller.destroy()
    }

    @Test
    fun cuj3_corridorPrefetchOfflineRetryOnConnectivityReturnPreservesAvailabilityFlagAndZeroAfterStop(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        dao.clearAllAccessPolygons()

        val accessLandRepoField = com.example.data.repository.DanskStiRepository::class.java
            .getDeclaredField("accessLandRepo")
            .apply { isAccessible = true }
        val originalAccessLandRepo = app.repository.accessLandRepo
        val offlineWfsClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { throw java.io.IOException("Simulated offline WFS") }
            .build()
        accessLandRepoField.set(app.repository, AccessLandRepository(dao, offlineWfsClient))

        try {
            // 1. Start offline (Overpass returns 503 and WFS throws IOException) with isAccessDataUnavailable initially false
            AccessLandRepository.isAccessDataUnavailable = false
            val overpassCalls = AtomicInteger(0)
            OverpassClient.customTransport = { _, _ ->
                overpassCalls.incrementAndGet()
                OverpassClient.HttpResult(code = 503, body = "Service Unavailable")
            }

            val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
            val service = controller.get()
            val route = buildLongLinearPlannedRoute(totalKm = 50)
            service.planId = route.planId
            service.currentPlannedRoute = route
            service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)

            val tilesAtStart = service.collectNext25KmCorridorTiles(route, currentWalkKm = 0.0)
            val firstTile = tilesAtStart.first()

            val t0 = 1_750_000_000_000L
            service.handleNewLocation(makeLocation(route.points[0].lat, route.points[0].lng, t0))
            service.corridorPrefetchJob?.join()

            // Verify offline start: 1 run attempted, 0 tiles cached in Room, lastAccessPrefetchWalkKm still NaN,
            // corridorPrefetchPendingRetry == true, and isAccessDataUnavailable is UNCHANGED (still false)
            assertEquals(1, service.corridorPrefetchRunCount)
            assertTrue("Offline start must yield 0 cached polygons for first tile", dao.getAccessPolygonsByTileId(firstTile.tileId).isEmpty())
            assertTrue("lastAccessPrefetchWalkKm must remain NaN when every tile failed", service.lastAccessPrefetchWalkKm.isNaN())
            assertTrue("corridorPrefetchPendingRetry must be true after failed offline prefetch", service.corridorPrefetchPendingRetry)
            assertFalse(
                "Failed corridor prefetch must leave AccessLandRepository.isAccessDataUnavailable unchanged (false)",
                AccessLandRepository.isAccessDataUnavailable
            )

            // Also verify when isAccessDataUnavailable was true, a prefetch does not overwrite it either
            AccessLandRepository.isAccessDataUnavailable = true

            // 2. Connectivity returns: Overpass now succeeds, and network callback onAvailable fires
            OverpassClient.customTransport = { _, _ ->
                overpassCalls.incrementAndGet()
                OverpassClient.HttpResult(
                    code = 200,
                    body = """
                        {
                          "elements": [
                            {"type": "node", "id": 1, "lat": 55.01, "lon": 9.01},
                            {"type": "node", "id": 2, "lat": 55.02, "lon": 9.01},
                            {"type": "node", "id": 3, "lat": 55.02, "lon": 9.02},
                            {"type": "node", "id": 4, "lat": 55.01, "lon": 9.02},
                            {"type": "node", "id": 1, "lat": 55.01, "lon": 9.01},
                            {"type": "way", "id": 100, "nodes": [1,2,3,4,1], "tags": {"landuse": "forest", "access": "yes"}}
                          ]
                        }
                    """.trimIndent()
                )
            }

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: org.robolectric.shadows.ShadowNetwork.newInstance(1)
            service.corridorNetworkCallback.onAvailable(activeNetwork)
            // onAvailable posts to main thread; idle main looper and await prefetch job
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            service.corridorPrefetchJob?.join()

            assertEquals("Connectivity return must trigger exactly 1 retry prefetch run", 2, service.corridorPrefetchRunCount)
            assertFalse("corridorPrefetchPendingRetry must be false after successful retry", service.corridorPrefetchPendingRetry)
            assertEquals(0.0, service.lastAccessPrefetchWalkKm, 0.001)
            assertTrue("First tile must now be cached in Room after connectivity retry", dao.getAccessPolygonsByTileId(firstTile.tileId).isNotEmpty())
            assertTrue(
                "Corridor prefetch with updateAvailabilityFlag=false must leave isAccessDataUnavailable unchanged (true)",
                AccessLandRepository.isAccessDataUnavailable
            )
            AccessLandRepository.isAccessDataUnavailable = false

            // A duplicate onAvailable while no retry is pending must NOT trigger another prefetch run
            service.corridorNetworkCallback.onAvailable(activeNetwork)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            service.corridorPrefetchJob?.join()
            assertEquals("onAvailable when no retry is pending must trigger 0 extra runs", 2, service.corridorPrefetchRunCount)

            // 3. After stopTracking(), even if a retry was pending, a connectivity change triggers 0 prefetches
            service.corridorPrefetchPendingRetry = true
            val runsBeforeStop = service.corridorPrefetchRunCount
            service.stopTracking()
            assertFalse("Corridor network callback must be unregistered on stopTracking()", service.isCorridorNetworkCallbackRegistered)
            service.corridorNetworkCallback.onAvailable(activeNetwork)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            service.corridorPrefetchJob?.join()
            assertEquals("After stopTracking(), connectivity change must trigger 0 prefetches", runsBeforeStop, service.corridorPrefetchRunCount)

            controller.destroy()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } finally {
            accessLandRepoField.set(app.repository, originalAccessLandRepo)
            OverpassClient.customTransport = null
            AccessLandRepository.isAccessDataUnavailable = false
        }
    }

    @Test
    fun cuj3_stationary30MinWithInaccurateFixesThenWalkingAt1_3MpsReturnsToHighAccuracyWithin150s(): Unit = runBlocking {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 10)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        val t0 = 1_750_000_000_000L
        val anchorLat = route.points[0].lat
        val anchorLng = route.points[0].lng
        val metersPerDegLat = 111_320.0

        // Establish stationary state at anchor (3+ accurate fixes over 2 minutes)
        service.handleNewLocation(makeLocation(anchorLat, anchorLng, t0, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(anchorLat, anchorLng, t0 + 40_000L, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(anchorLat, anchorLng, t0 + 80_000L, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(anchorLat, anchorLng, t0 + 120_000L, accuracyM = 8f, speedMps = 0f))
        assertTrue(service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)

        // Stationary for 30 minutes with ONLY inaccurate fixes (> 30 m, e.g. 60 m accuracy jittering 45 m),
        // while single-fix high-accuracy confirmations report the hiker is still at the anchor
        var currentMs = t0 + 130_000L
        val stationaryEndMs = currentMs + 30 * 60_000L
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(anchorLat, anchorLng, currentMs, accuracyM = 9f, speedMps = 0f)
        }
        while (currentMs <= stationaryEndMs) {
            service.handleNewLocation(
                makeLocation(
                    lat = anchorLat + (45.0 / metersPerDegLat),
                    lng = anchorLng,
                    timeMs = currentMs,
                    accuracyM = 60f,
                    speedMps = 0f
                )
            )
            currentMs += 10_000L
        }
        assertTrue("Must still be stationary after 30 min of inaccurate fixes at anchor", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)

        // Now hiker starts walking away from the anchor at 1.3 m/s, still receiving inaccurate (> 30 m) periodic fixes,
        // while high-accuracy confirmation returns the hiker's true walking position along the path
        val walkStartMs = currentMs
        var switchedToHighAccuracyAtMs: Long? = null
        service.highAccuracySingleFixFetcher = { _ ->
            val walkedMeters = ((currentMs - walkStartMs) / 1000.0) * 1.3
            makeLocation(
                lat = anchorLat + (walkedMeters / metersPerDegLat),
                lng = anchorLng,
                timeMs = currentMs,
                accuracyM = 10f,
                speedMps = 1.3f
            )
        }

        while (currentMs <= walkStartMs + 150_000L) {
            val walkedMeters = ((currentMs - walkStartMs) / 1000.0) * 1.3
            service.handleNewLocation(
                makeLocation(
                    lat = anchorLat + (walkedMeters / metersPerDegLat),
                    lng = anchorLng,
                    timeMs = currentMs,
                    accuracyM = 55f,
                    speedMps = 1.3f
                )
            )
            if (service.currentGpsProfile == GpsPowerProfile.HIGH_ACCURACY_2S ||
                service.currentGpsProfile == GpsPowerProfile.HIGH_ACCURACY_5S
            ) {
                switchedToHighAccuracyAtMs = currentMs
                break
            }
            currentMs += 10_000L
        }

        assertNotNull("Must switch back to HIGH_ACCURACY_2S/5S within <= 150 s of walking at 1.3 m/s", switchedToHighAccuracyAtMs)
        val elapsedWalkMs = switchedToHighAccuracyAtMs!! - walkStartMs
        assertTrue(
            "Expected switch back to HIGH_ACCURACY_2S/5S in <= 150 s (got ${elapsedWalkMs / 1000.0} s)",
            elapsedWalkMs <= 150_000L
        )
        assertFalse(service.isStationaryFor2Min)

        service.corridorPrefetchJob?.join()
        service.stopTracking()
        controller.destroy()
    }

    @Test
    fun cuj3_stationaryDetectionRequires3AccurateFixesAndImmediateVsBackedOffMovementConfirmation(): Unit = runBlocking {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 10)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        val t0 = 1_750_000_000_000L
        val baseLat = route.points[0].lat
        val baseLng = route.points[0].lng

        // 1. One initial accurate fix sets the anchor, then 3 minutes of walking with only 45 m-accuracy fixes:
        // fewer than 3 accurate fixes arrived in the last 2 min -> stays HIGH_ACCURACY_2S
        service.handleNewLocation(makeLocation(baseLat, baseLng, t0, accuracyM = 10f, speedMps = 1.2f))
        for (step in 1..36) { // 36 * 5s = 180s (3 min)
            service.handleNewLocation(
                makeLocation(
                    lat = baseLat + step * 0.00005,
                    lng = baseLng,
                    timeMs = t0 + step * 5_000L,
                    accuracyM = 45f,
                    speedMps = 1.2f
                )
            )
        }
        assertFalse("3 min with only 45 m-accuracy fixes must NOT become stationary", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_2S, service.currentGpsProfile)

        // 2. Now hiker stops at a shelter for 30 minutes with accurate fixes -> becomes BALANCED_10S
        val shelterLat = baseLat + 0.0020
        val shelterLng = baseLng
        val shelterStartMs = t0 + 300_000L
        for (min in 0..30) {
            service.handleNewLocation(
                makeLocation(shelterLat, shelterLng, shelterStartMs + min * 60_000L, accuracyM = 10f, speedMps = 0f)
            )
        }
        assertTrue(service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)
        assertEquals(0, service.confirmationRequestCount)

        // 3. Hiker starts walking and first balanced fix has 15 m accuracy (<= 30 m) and is ~35 m away (>= 20 m):
        // switches back to HIGH_ACCURACY_2S on the FIRST such fix with 0 confirmation requests!
        val walkTimeMs = shelterStartMs + 31 * 60_000L
        service.handleNewLocation(
            makeLocation(shelterLat + 0.00035, shelterLng, walkTimeMs, accuracyM = 15f, speedMps = 1.3f)
        )
        assertFalse("Accurate fix >= 20 m from anchor must immediately clear stationary state", service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_2S, service.currentGpsProfile)
        assertEquals("Immediate accurate movement must make 0 getCurrentLocation confirmation requests", 0, service.confirmationRequestCount)

        // 4. Stationary 30 min with only inaccurate jitter fixes (so backoff reaches stationary cap of 120 s),
        // then walking -> back to high accuracy within <= 120 s
        val stop2Lat = shelterLat + 0.0050
        val stop2StartMs = walkTimeMs + 60_000L
        // Establish stationary state with >= 3 accurate fixes within the 2 min (120_000 ms) window
        service.handleNewLocation(makeLocation(stop2Lat, shelterLng, stop2StartMs, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(stop2Lat, shelterLng, stop2StartMs + 40_000L, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(stop2Lat, shelterLng, stop2StartMs + 80_000L, accuracyM = 8f, speedMps = 0f))
        service.handleNewLocation(makeLocation(stop2Lat, shelterLng, stop2StartMs + 120_000L, accuracyM = 8f, speedMps = 0f))
        assertTrue(service.isStationaryFor2Min)
        assertEquals(GpsPowerProfile.BALANCED_10S, service.currentGpsProfile)

        // 30 minutes of inaccurate jitter (65 m accuracy, 50 m offset) where confirmation says still at stop2Lat
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(stop2Lat, shelterLng, stop2StartMs + 200_000L, accuracyM = 8f, speedMps = 0f)
        }
        var currentMs = stop2StartMs + 130_000L
        val endStationaryMs = currentMs + 30 * 60_000L
        while (currentMs <= endStationaryMs) {
            service.handleNewLocation(
                makeLocation(stop2Lat + 0.00050, shelterLng, currentMs, accuracyM = 65f, speedMps = 0f)
            )
            currentMs += 10_000L
        }
        assertTrue(service.isStationaryFor2Min)
        assertEquals("Stationary confirmation backoff cap must be 120 s", 120_000L, service.currentConfirmCooldownMs)

        // Now hiker actually walks away (confirmation returns accurate fix 40 m away), while only inaccurate fixes arrive
        val walkAwayStartMs = endStationaryMs
        var resumedAtMs: Long? = null
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(stop2Lat + 0.00040, shelterLng, currentMs, accuracyM = 10f, speedMps = 1.4f)
        }
        while (currentMs <= walkAwayStartMs + 120_000L) {
            service.handleNewLocation(
                makeLocation(stop2Lat + 0.00040, shelterLng, currentMs, accuracyM = 55f, speedMps = 1.4f)
            )
            if (service.currentGpsProfile == GpsPowerProfile.HIGH_ACCURACY_2S) {
                resumedAtMs = currentMs
                break
            }
            currentMs += 10_000L
        }
        assertNotNull("Must switch back to HIGH_ACCURACY_2S within <= 120 s", resumedAtMs)
        assertTrue(
            "Elapsed time to switch back to high accuracy must be <= 120 s",
            (resumedAtMs!! - walkAwayStartMs) <= 120_000L
        )

        service.corridorPrefetchJob?.join()
        service.stopTracking()
        controller.destroy()
    }

    @Test
    fun cuj3_pausedAnchorFromLastAccurateFixLowPowerJitterBackoffAndConfirmedWalkResume(): Unit = runBlocking {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val route = buildLongLinearPlannedRoute(totalKm = 10)
        service.planId = route.planId
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        service.startForegroundLocationTracking()

        val t0 = 1_750_000_000_000L
        val baseLat = route.points[0].lat
        val baseLng = route.points[0].lng

        // Feed one accurate fix before pausing, followed by an inaccurate fix
        val accurateBeforePause = makeLocation(baseLat, baseLng, t0, accuracyM = 9f, speedMps = 1.2f)
        service.handleNewLocation(accurateBeforePause)
        val inaccurateBeforePause = makeLocation(baseLat + 0.001, baseLng, t0 + 2_000L, accuracyM = 120f, speedMps = 0f)
        service.handleNewLocation(inaccurateBeforePause)

        // Pause -> pausedAnchorLocation MUST be set to the last accurate fix (<= 30 m), NOT the 120 m fix
        service.setPausedInternal(true)
        assertTrue(NavigationTrackingService.liveState.value.isPaused)
        assertEquals(GpsPowerProfile.LOW_POWER_60S, service.currentGpsProfile)
        assertNotNull("pausedAnchorLocation must be set from lastAccurateLocation", service.pausedAnchorLocation)
        assertEquals(baseLat, service.pausedAnchorLocation!!.latitude, 1e-6)

        // 30 minutes paused while low-power fixes with accuracy > 100 m jitter 800 m away,
        // and high-accuracy confirmation reports hiker is still at baseLat/baseLng:
        // - Must NOT resume
        // - Exponential backoff (60 s -> 120 s -> 240 s -> 300 s) results in <= 9 confirmation requests in 30 min
        service.confirmationRequestCount = 0
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(baseLat + 0.00002, baseLng, t0 + 60_000L, accuracyM = 8f, speedMps = 0f)
        }
        val pauseStartMs = t0 + 10_000L
        for (step in 0..30) { // 30 minutes in 60s steps ( plus extra 30s steps to verify <= 1 per 60s )
            val fixTime = pauseStartMs + step * 60_000L
            // 800 m jitter (~0.0072 deg lat) with 250 m accuracy
            service.handleNewLocation(
                makeLocation(baseLat + 0.0072, baseLng, fixTime, accuracyM = 250f, speedMps = 0f)
            )
            // Also send an intermediate jitter fix 30 s later to verify at most 1 confirmation per 60 s
            if (step < 30) {
                service.handleNewLocation(
                    makeLocation(baseLat + 0.0072, baseLng, fixTime + 30_000L, accuracyM = 250f, speedMps = 0f)
                )
            }
        }
        assertTrue("Must remain paused when confirmation fixes show no movement", NavigationTrackingService.liveState.value.isPaused)
        assertTrue(
            "Expected <= 9 confirmation requests in 30 min paused with 800 m jitter, got ${service.confirmationRequestCount}",
            service.confirmationRequestCount in 1..9
        )

        // Now hiker walks 30 m away (~0.00028 deg lat) and confirmation fix has 10 m accuracy at +30 m -> resumes!
        val resumeCandidateMs = pauseStartMs + 36 * 60_000L
        service.highAccuracySingleFixFetcher = { _ ->
            makeLocation(baseLat + 0.00028, baseLng, resumeCandidateMs, accuracyM = 10f, speedMps = 1.3f)
        }
        service.handleNewLocation(
            makeLocation(baseLat + 0.00028, baseLng, resumeCandidateMs, accuracyM = 150f, speedMps = 1.3f)
        )
        assertFalse("Confirmed 30 m walk must auto-resume from pause", NavigationTrackingService.liveState.value.isPaused)
        assertEquals(GpsPowerProfile.HIGH_ACCURACY_2S, service.currentGpsProfile)

        // Also verify CancellationToken is cancelled when tracking stops while a confirmation is in-flight
        service.setPausedInternal(true)
        var capturedToken: CancellationToken? = null
        service.highAccuracySingleFixFetcher = { token ->
            capturedToken = token
            delay(25_000L)
            null
        }
        service.handleNewLocation(
            makeLocation(baseLat + 0.0050, baseLng, resumeCandidateMs + 120_000L, accuracyM = 180f, speedMps = 0f)
        )
        assertNotNull("Expected in-flight confirmation CancellationToken", capturedToken)
        service.corridorPrefetchJob?.join()
        service.stopTracking()
        assertTrue("CancellationTokenSource must be cancelled when tracking stops", capturedToken!!.isCancellationRequested)

        controller.destroy()
    }
}
