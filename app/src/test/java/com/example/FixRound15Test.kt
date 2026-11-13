package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripStage
import com.example.data.remote.OverpassClient
import com.example.data.repository.GeoFaRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.data.repository.loadBundledFriTeltningFromDiskOrAssets
import com.example.data.repository.loadBundledPoisFromDiskOrAssets
import com.example.data.repository.loadBundledSheltersFromDiskOrAssets
import com.example.util.RouteTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound15Test {

    @Before
    fun setUp() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
    }

    private fun buildThreeDayRoute(): PlannedRoute {
        val totalKm = 9.0
        val stepKm = 0.05 // 50 m spacing, 181 points
        val baseLat = 55.0
        val baseLng = 10.0
        val points = (0..180).map { idx ->
            val walkKm = idx * stepKm
            RoutePoint(
                lat = baseLat + (walkKm / 111.0),
                lng = baseLng,
                ele = 12.0,
                walkKm = walkKm,
                trailKm = walkKm,
                segmentIndex = 0
            )
        }
        val stages = listOf(
            TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 3.0,
                stageKm = 3.0,
                stopName = "Dag 1 Lejr",
                stopType = "SHELTER",
                stopLat = baseLat + (3.0 / 111.0),
                stopLng = baseLng
            ),
            TripStage(
                dayNumber = 2,
                startKm = 3.0,
                endKm = 6.0,
                stageKm = 3.0,
                stopName = "Dag 2 Lejr",
                stopType = "TENT",
                stopLat = baseLat + (6.0 / 111.0),
                stopLng = baseLng
            ),
            TripStage(
                dayNumber = 3,
                startKm = 6.0,
                endKm = 9.0,
                stageKm = 3.0,
                stopName = "Dag 3 Mål",
                stopType = "DESTINATION",
                stopLat = baseLat + (9.0 / 111.0),
                stopLng = baseLng
            )
        )
        return PlannedRoute(
            planId = 15L,
            trailId = "round15_3day_trail",
            trailName = "Tredages Rute",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = totalKm,
            totalWalkKm = totalKm,
            points = points,
            stages = stages
        )
    }

    /**
     * Round 15 Item 1:
     * Replace hasAutoAdvancedPastCampWithoutStopping with the day number it was set for,
     * so walking more than 500 m past each camp without pressing "Stop for i dag" advances
     * exactly one day per camp.
     * Test:
     * - A 3-day plan walked straight through without stopping ends on day 3 (1 -> 2 -> 3);
     * - With "Stop for i dag" each evening it also goes 1 -> 2 -> 3.
     */
    @Test
    fun item1_threeDayPlanWalkedStraightThroughWithoutStopping_endsOnDay3_andWithStopForTodayGoes1To2To3() {
        val route = buildThreeDayRoute()

        // Part A: Walk straight through all 3 days without pressing "Stop for i dag"
        val straightTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        val observedDays = mutableListOf(straightTracker.currentDay)

        for (pt in route.points) {
            val res = straightTracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
            if (observedDays.last() != res.currentDay) {
                observedDays.add(res.currentDay)
            }
            when {
                // Before > 500 m past Camp 1 (0.0 .. 3.50 km): must be Day 1
                pt.walkKm <= 3.50 -> assertEquals(
                    "At ${pt.walkKm} km (<= 500 m past Camp 1), day must be 1",
                    1,
                    res.currentDay
                )
                // From > 500 m past Camp 1 up to 500 m past Camp 2 (3.55 .. 6.50 km): must be Day 2
                pt.walkKm in 3.55..6.50 -> assertEquals(
                    "At ${pt.walkKm} km (> 500 m past Camp 1, <= 500 m past Camp 2), day must be 2",
                    2,
                    res.currentDay
                )
                // Beyond 500 m past Camp 2 (6.55 .. 9.00 km): must be Day 3
                pt.walkKm >= 6.55 -> assertEquals(
                    "At ${pt.walkKm} km (> 500 m past Camp 2), day must be 3",
                    3,
                    res.currentDay
                )
            }
        }

        assertEquals("Walking straight through must progress 1 -> 2 -> 3", listOf(1, 2, 3), observedDays)
        assertEquals("Final day after walking straight through must be Day 3", 3, straightTracker.currentDay)
        assertTrue("Route must be marked completed at the end of Day 3", straightTracker.isCompleted)

        // Part B: Walk the same 3-day plan pressing "Stop for i dag" each evening -> 1 -> 2 -> 3
        val stopTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        for (pt in route.points.filter { it.walkKm <= 3.0 }) {
            stopTracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
        }
        assertEquals(1, stopTracker.currentDay)
        assertTrue(stopTracker.hasArrivedAtTonightCamp)

        val afterEvening1 = stopTracker.stopForToday()
        assertEquals("After Evening 1 Stop for i dag, day must be 2", 2, afterEvening1.currentDay)

        for (pt in route.points.filter { it.walkKm in 3.05..6.0 }) {
            stopTracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
        }
        assertEquals(2, stopTracker.currentDay)
        assertTrue(stopTracker.hasArrivedAtTonightCamp)

        val afterEvening2 = stopTracker.stopForToday()
        assertEquals("After Evening 2 Stop for i dag, day must be 3", 3, afterEvening2.currentDay)

        for (pt in route.points.filter { it.walkKm > 6.0 }) {
            stopTracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
        }
        assertEquals("Final day after Evening 1 & 2 stops must be Day 3", 3, stopTracker.currentDay)
        assertTrue("Route must be completed at end of Day 3", stopTracker.isCompleted)
    }

    /**
     * Round 15 Item 2:
     * Remove every Build.FINGERPRINT == "robolectric" check from app code
     * (TrailGeometryRepository elevation lookups, OverpassClient.performHttpCall).
     * Tests must use injectable fakes instead (OverpassClient.customTransport already exists;
     * add the same kind of hook for the Open-Meteo elevation client).
     */
    @Test
    fun item2_noRobolectricChecksInAppCode_andInjectableElevationHookWorks(): Unit = runBlocking {
        val mainDir = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        assertTrue("mainDir must exist", mainDir.exists())

        val offendingFiles = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val text = file.readText()
                if (text.contains("robolectric", ignoreCase = true) || text.contains("FINGERPRINT")) {
                    offendingFiles.add(file.path)
                }
            }
        assertTrue(
            "No file in app/src/main may check Build.FINGERPRINT or 'robolectric', found: $offendingFiles",
            offendingFiles.isEmpty()
        )

        // Verify injectable Open-Meteo elevation hook on TrailGeometryRepository
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val elevationCalls = AtomicInteger(0)

        TrailGeometryRepository.customElevationFetcher = { coords ->
            elevationCalls.incrementAndGet()
            coords.mapIndexed { index, _ -> 25.0 + index * 3.5 }
        }

        // 1) Endpoint elevation lookup uses the injectable fake
        val (startEle, endEle) = geomRepo.fetchEndpointElevations(55.0, 10.0, 55.01, 10.01)
        assertEquals(25.0, startEle ?: -1.0, 0.001)
        assertEquals(28.5, endEle ?: -1.0, 0.001)
        assertEquals(1, elevationCalls.get())

        // 2) Trail geometry processing uses both OverpassClient.customTransport and TrailGeometryRepository.customElevationFetcher
        val trailId = "round15_elev_hook_${System.currentTimeMillis()}"
        OverpassClient.customTransport = { _, query ->
            if (query.contains("out tags bb")) {
                OverpassClient.HttpResult(
                    200,
                    """{"elements":[{"type":"relation","id":151515,"bounds":{"minlat":55.0,"minlon":10.0,"maxlat":55.05,"maxlon":10.05},"tags":{"name":"R15 Rute","route":"hiking"}}]}"""
                )
            } else {
                OverpassClient.HttpResult(
                    200,
                    """
                    {
                      "elements": [
                        {
                          "type": "relation",
                          "id": 151515,
                          "tags": {"name": "R15 Rute", "route": "hiking"},
                          "members": [
                            {
                              "type": "way",
                              "ref": 1,
                              "role": "",
                              "geometry": [
                                {"lat": 55.000, "lon": 10.000},
                                {"lat": 55.005, "lon": 10.005}
                              ]
                            }
                          ]
                        },
                        {
                          "type": "way",
                          "id": 1,
                          "tags": {"highway": "path", "surface": "dirt"}
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }
        }

        val dto = geomRepo.fetchAndProcessTrail(trailId, "R15 Rute", 151515L)
        assertNotNull("TrailGeometryDto must be built using injectable fakes", dto)
        assertTrue("Elevation hook must have been invoked during trail processing", elevationCalls.get() >= 2)
        val firstEle = dto!!.segments.first().points.first().ele
        assertNotNull("Interpolated elevation from injectable fake must be populated", firstEle)
        assertTrue("Elevation must come from customElevationFetcher (>= 25.0)", firstEle!! >= 25.0)
        geomRepo.getGeometryFile(trailId).delete()
    }

    /**
     * Round 15 Item 3:
     * GeoFaRepository: remove the fallback that reads from src/main/assets or app/src/main/assets on disk.
     * Only context.assets is allowed in app code; move any disk-reading helper to src/test.
     */
    @Test
    fun item3_geoFaRepositoryOnlyUsesContextAssetsInAppCode(): Unit = runBlocking {
        val mainDir = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val offendingFiles = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val text = file.readText()
                if (text.contains("src/main") || text.contains("app/src")) {
                    offendingFiles.add(file.path)
                }
            }
        assertTrue(
            "No file in app/src/main may read from src/main/assets or app/src/main/assets on disk, found: $offendingFiles",
            offendingFiles.isEmpty()
        )

        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val repo = GeoFaRepository(app)
        assertTrue("context.assets or test helper must load shelters", repo.loadBundledSheltersFromDiskOrAssets().size > 1000)
        assertTrue("context.assets or test helper must load fri teltning", repo.loadBundledFriTeltningFromDiskOrAssets().size > 200)
        assertTrue("context.assets or test helper must load POIs", repo.loadBundledPoisFromDiskOrAssets().isNotEmpty())
    }

    /**
     * Round 15 Item 4:
     * TrailGeometryRepository.cancelledTrailIds: remove it, or make it test-only and bounded.
     */
    @Test
    fun item4_cancelledTrailIdsRemovedFromTrailGeometryRepository() {
        val repoFields = TrailGeometryRepository::class.java.declaredFields.map { it.name }
        val companionFields = TrailGeometryRepository.Companion::class.java.declaredFields.map { it.name }
        assertFalse(
            "cancelledTrailIds must be removed from TrailGeometryRepository",
            "cancelledTrailIds" in repoFields || "cancelledTrailIds" in companionFields
        )
    }
}
