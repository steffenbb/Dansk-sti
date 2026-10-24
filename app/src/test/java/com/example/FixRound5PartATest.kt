package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.local.PreseededData
import com.example.data.model.AccessPolygonEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripPlanEntity
import com.example.data.model.WaterFeatureEntity
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WayInfo
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FixRound5PartATest {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase
    private lateinit var repository: DanskStiRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "geometries").deleteRecursively()
        DanskStiDatabase.appFilesDir = app.filesDir
        database = Room.inMemoryDatabaseBuilder(app, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DanskStiRepository(database.dao(), app).apply {
            isOnlineOverrideForTests = false
        }
    }

    @After
    fun tearDown() {
        database.close()
        File(app.filesDir, "geometries").deleteRecursively()
    }

    @Test
    fun `item 1 and 2 - loadGeometry returns null for a trail with no downloaded file and PreseededData has no invented geometry or stats`() = runBlocking {
        // Verify PreseededData.getFallbackTrailGeometry does not exist
        val methods = PreseededData::class.java.declaredMethods.map { it.name }
        assertFalse(
            "PreseededData.getFallbackTrailGeometry must be deleted",
            methods.contains("getFallbackTrailGeometry")
        )

        val initialTrails = PreseededData.getInitialTrails()
        assertTrue("Initial trails list should not be empty", initialTrails.isNotEmpty())

        val geomRepo = TrailGeometryRepository(app)
        for (trail in initialTrails) {
            assertEquals("Trail ${trail.id} computedWalkingKm must be 0.0 before download", 0.0, trail.computedWalkingKm, 0.0001)
            assertFalse("Trail ${trail.id} isGeometryDownloaded must be false before download", trail.isGeometryDownloaded)
            assertNull("Trail ${trail.id} bboxNorth must be null before download", trail.bboxNorth)
            assertNull("Trail ${trail.id} bboxSouth must be null before download", trail.bboxSouth)
            assertNull("Trail ${trail.id} bboxEast must be null before download", trail.bboxEast)
            assertNull("Trail ${trail.id} bboxWest must be null before download", trail.bboxWest)

            assertFalse(
                "isGeometryCached must be false when no downloaded file exists for ${trail.id}",
                geomRepo.isGeometryCached(trail.id)
            )
            assertNull(
                "loadGeometry must return null when no downloaded file exists for ${trail.id}",
                geomRepo.loadGeometry(trail.id)
            )
        }
    }

    @Test
    fun `item 3 - freshly created database has no POIs, access polygons, water features or marked routes except bundled GeoFA and OSM assets`() = runBlocking {
        val dao = database.dao()

        repository.initializePreseededDataIfEmpty()

        val allPois = dao.getAllPoisList()
        assertTrue("Bundled GeoFA and OSM POIs should be loaded", allPois.isNotEmpty())
        assertFalse(
            "No dk_shop_ POIs should exist in a freshly seeded DB",
            allPois.any { it.id.startsWith("dk_shop_") }
        )
        assertFalse(
            "No dk_transit_ POIs should exist in a freshly seeded DB",
            allPois.any { it.id.startsWith("dk_transit_") }
        )
        val expectedBundledPoiIds = (
            com.example.data.repository.GeoFaRepository(app).loadBundledPois().map { it.id } +
            com.example.data.repository.OsmPoiRepository(app).loadBundledPois("trail_ohavsstien").pois.map { it.id }
        ).toSet()
        assertEquals(
            "All POIs in a fresh DB must equal the exact set of POIs from the bundled GeoFA and Øhavsstien OSM asset files",
            expectedBundledPoiIds,
            allPois.map { it.id }.toSet()
        )

        val allAccessPolygons = dao.getAccessPolygonsInBoundingBox(-90.0, 90.0, -180.0, 180.0)
        assertTrue(
            "Freshly created DB must have 0 preseeded access polygons, found ${allAccessPolygons.size}",
            allAccessPolygons.isEmpty()
        )

        val allWaterFeatures = dao.getWaterFeaturesInBbox(-90.0, 90.0, -180.0, 180.0)
        assertTrue(
            "Freshly created DB must have 0 preseeded water features, found ${allWaterFeatures.size}",
            allWaterFeatures.isEmpty()
        )

        val allMarkedRoutes = dao.getMarkedRoutesInBbox(-90.0, 90.0, -180.0, 180.0)
        assertTrue(
            "Freshly created DB must have 0 preseeded marked routes, found ${allMarkedRoutes.size}",
            allMarkedRoutes.isEmpty()
        )
    }

    @Test
    fun `item 4 - Room migration 22 to 23 deletes invented rows, resets trails without files, and strips route_ prefix from trail plans`() = runBlocking {
        val dao = database.dao()
        dao.insertTrails(PreseededData.getInitialTrails())

        // Insert legacy invented rows into DB
        dao.insertPois(
            listOf(
                PoiEntity(id = "dk_shop_fake", type = "shop", name = "Fake Shop", lat = 56.0, lng = 10.0, trailCorridorId = "geofa_all_dk"),
                PoiEntity(id = "dk_transit_fake", type = "station", name = "Fake Station", lat = 56.0, lng = 10.0, trailCorridorId = "geofa_all_dk"),
                PoiEntity(id = "geofa_real_water", type = "water", name = "Real Water", lat = 55.1, lng = 10.2, trailCorridorId = "geofa_all_dk")
            )
        )
        dao.insertAccessPolygons(
            listOf(
                AccessPolygonEntity("nst_forest_fake", "Fake Forest", "PUBLIC_FOREST", "[]", 55.0, 56.0, 10.0, 11.0, "osm", null, 0L, ""),
                AccessPolygonEntity("fredning_fake", "Fake Fredning", "PROTECTED_AREA", "[]", 55.0, 56.0, 10.0, 11.0, "osm", null, 0L, "")
            )
        )
        dao.insertWaterFeatures(
            listOf(
                WaterFeatureEntity("water_fake", 1L, "Fake River", "RIVER", "river", "[]", 55.0, 56.0, 10.0, 11.0, "tile_1")
            )
        )
        dao.insertMarkedRoutes(
            listOf(
                MarkedRouteEntity(900001L, "Fake Marked Route", 10.0, null, "hiking", "nwn", null, "#FF0000", "[]", 55.0, 56.0, 10.0, 11.0)
            )
        )
        val trail = dao.getTrailById("trail_ohavsstien")!!
        dao.insertTrails(
            listOf(trail.copy(isGeometryDownloaded = true, computedWalkingKm = 220.0))
        )
        val badPlanId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = "route_trail_ohavsstien",
                trailName = "Øhavsstien",
                startKm = 0.0,
                endKm = 220.0,
                isReverse = false,
                totalDistanceKm = 220.0,
                targetDays = 5,
                overnightType = "BOTH",
                preferredDailyKm = 20.0,
                stagesJson = "[]"
            )
        )

        // Execute the exact SQL statements from MIGRATION_22_23 against the open SupportSQLiteDatabase
        val sqliteDb = database.openHelper.writableDatabase
        DanskStiDatabase.MIGRATION_22_23.migrate(sqliteDb)

        val remainingPois = dao.getAllPoisList()
        assertFalse(remainingPois.any { it.id.startsWith("dk_shop_") || it.id.startsWith("dk_transit_") })
        assertTrue(remainingPois.any { it.id == "geofa_real_water" })
        assertTrue(dao.getAccessPolygonsInBoundingBox(-90.0, 90.0, -180.0, 180.0).isEmpty())
        assertTrue(dao.getWaterFeaturesInBbox(-90.0, 90.0, -180.0, 180.0).isEmpty())
        assertTrue(dao.getMarkedRoutesInBbox(-90.0, 90.0, -180.0, 180.0).none { it.osmRelationId >= 900000L })

        val updatedTrail = dao.getTrailById("trail_ohavsstien")!!
        assertFalse("isGeometryDownloaded must be reset to false when geometry file does not exist", updatedTrail.isGeometryDownloaded)
        assertEquals("computedWalkingKm must be reset to 0.0 when geometry file does not exist", 0.0, updatedTrail.computedWalkingKm, 0.0001)

        val fixedPlan = dao.getTripPlanById(badPlanId)!!
        assertEquals("trail_ohavsstien", fixedPlan.trailId)
    }

    @Test
    fun `item 7 - saving updates existing plan with same id and deletes older plans with same customRouteId`() = runBlocking {
        val dao = database.dao()

        val firstPlanId = repository.saveTripPlanForRoute(
            routeId = "route_42",
            routeName = "Min Tur v1",
            totalKm = 12.0,
            days = 1,
            overnightType = OvernightType.BOTH,
            stages = emptyList(),
            customRouteId = 42L
        )
        // Insert an extra older duplicate plan for the same customRouteId
        val duplicatePlanId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = "route_42",
                trailName = "Old Duplicate",
                startKm = 0.0,
                endKm = 10.0,
                isReverse = false,
                totalDistanceKm = 10.0,
                targetDays = 1,
                overnightType = "BOTH",
                preferredDailyKm = 10.0,
                stagesJson = "[]",
                customRouteId = 42L
            )
        )

        // Save again updating firstPlanId
        val updatedPlanId = repository.saveTripPlanForRoute(
            routeId = "route_42",
            routeName = "Min Tur v2",
            totalKm = 18.5,
            days = 2,
            overnightType = OvernightType.SHELTER,
            stages = emptyList(),
            customRouteId = 42L,
            existingPlanId = firstPlanId
        )

        assertEquals("Saving must reuse the existing plan ID", firstPlanId, updatedPlanId)
        assertNull("Duplicate older plan with same customRouteId must be deleted", dao.getTripPlanById(duplicatePlanId))
        val savedPlan = dao.getTripPlanById(firstPlanId)
        assertNotNull(savedPlan)
        assertEquals("Min Tur v2", savedPlan!!.trailName)
        assertEquals(18.5, savedPlan.totalDistanceKm, 0.01)
    }

    @Test
    fun `item 8 and 9 - no WayInfo produced by TrailSlicer has highway path unless it came from OSM tags`() {
        val untaggedSegments = listOf(
            TrailSegmentDto(
                type = TrailSegmentType.WALK,
                points = listOf(
                    GeoPointDto(55.000, 10.000, 5.0),
                    GeoPointDto(55.010, 10.010, 10.0),
                    GeoPointDto(55.020, 10.020, 8.0)
                ),
                highway = null,
                surface = null
            )
        )

        val sliceUntagged = TrailSlicer.sliceTrail(untaggedSegments, 0.0, 2.0)
        assertTrue("Untagged slice should have wayInfo", sliceUntagged.wayInfo.isNotEmpty())
        assertTrue(
            "No WayInfo from untagged segments may have highway = 'path'",
            sliceUntagged.wayInfo.all { it.highway == null && it.surface == null }
        )

        val legUntagged = TrailSlicer.buildTrailLegGeometry(
            fromWaypointId = "a",
            toWaypointId = "b",
            segments = untaggedSegments,
            fromKm = 0.0,
            toKm = 2.0
        )
        assertTrue(
            "No WayInfo in buildTrailLegGeometry from untagged segments may have highway = 'path'",
            legUntagged.wayInfo.all { it.highway == null && it.surface == null }
        )

        // Now verify that when OSM tags explicitly provide highway="path", it is preserved
        val taggedSegments = listOf(
            TrailSegmentDto(
                type = TrailSegmentType.WALK,
                points = listOf(
                    GeoPointDto(55.000, 10.000, 5.0),
                    GeoPointDto(55.010, 10.010, 10.0)
                ),
                highway = "path",
                surface = "gravel"
            )
        )
        val sliceTagged = TrailSlicer.sliceTrail(taggedSegments, 0.0, 1.0)
        assertTrue(sliceTagged.wayInfo.isNotEmpty())
        assertTrue(sliceTagged.wayInfo.all { it.highway == "path" && it.surface == "gravel" })
    }

    @Test
    fun `item 8 - buildTrailDetour chooses only candidates that routed successfully and records failedLegs when none succeed`() = runBlocking {
        val segments = listOf(
            TrailSegmentDto(
                type = TrailSegmentType.WALK,
                points = (0..10).map { i ->
                    GeoPointDto(55.000 + i * 0.010, 10.000, 5.0 + i)
                },
                highway = null,
                surface = null
            )
        )
        val totalKm = TrailSlicer.totalTrailKm(segments)
        val startWp = RouteWaypoint(id = "start", lat = 55.000, lng = 10.000, role = WaypointRole.START, trailKm = 0.0)
        val endWp = RouteWaypoint(id = "end", lat = 55.100, lng = 10.000, role = WaypointRole.END, trailKm = totalKm)
        val pinWp = RouteWaypoint(id = "pin", lat = 55.050, lng = 10.008, role = WaypointRole.OVERNIGHT, name = "Shelter")

        // Engine where every candidate fails
        val alwaysFailingEngine = object : RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: RouteOptions,
                alternative: Int
            ): RouteLegResult {
                return RouteLegResult.Failure("Ingen forbindelse til rutetjenesten")
            }
        }

        val failedDetour = TrailSlicer.buildTrailDetour(
            segments = segments,
            prevWp = startWp,
            nextWp = endWp,
            pinWp = pinWp,
            routingEngine = alwaysFailingEngine,
            options = RouteOptions()
        )
        assertNull("legExitToPin must be null when all exit candidates fail", failedDetour.legExitToPin)
        assertNull("legPinToRejoin must be null when all rejoin candidates fail", failedDetour.legPinToRejoin)
        assertEquals("Both exit->pin and pin->rejoin must be recorded in failedLegs", 2, failedDetour.failedLegs.size)
        assertTrue(
            "No WayInfo in failedDetour legs may have highway = 'path'",
            failedDetour.legs.flatMap { it.wayInfo }.none { it.highway == "path" }
        )

        // Engine where the 1st candidate fails for each direction, and the remaining candidates succeed
        var callCount = 0
        val selectiveSuccessEngine = object : RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: RouteOptions,
                alternative: Int
            ): RouteLegResult {
                callCount++
                return if (callCount == 1 || callCount == 4) {
                    RouteLegResult.Failure("First candidate failed")
                } else {
                    RouteLegResult.Success(
                        points = listOf(
                            RoutePoint3D(from.lat, from.lng, 5.0),
                            RoutePoint3D(to.lat, to.lng, 6.0)
                        ),
                        lengthKm = 0.6,
                        ascentM = 2.0,
                        descentM = 1.0,
                        perPointWayInfo = listOf(
                            WayInfo(highway = "track", surface = "compacted"),
                            WayInfo(highway = "track", surface = "compacted")
                        ),
                        sourceName = "TestEngine"
                    )
                }
            }
        }

        val partialSuccessDetour = TrailSlicer.buildTrailDetour(
            segments = segments,
            prevWp = startWp,
            nextWp = endWp,
            pinWp = pinWp,
            routingEngine = selectiveSuccessEngine,
            options = RouteOptions()
        )
        assertNotNull("legExitToPin must be chosen from successful candidate", partialSuccessDetour.legExitToPin)
        assertNotNull("legPinToRejoin must be chosen from successful candidate", partialSuccessDetour.legPinToRejoin)
        assertTrue("failedLegs must be empty when a candidate succeeded for both legs", partialSuccessDetour.failedLegs.isEmpty())
    }

    @Test
    fun `item 5 and 6 - syncCorridorPoisForRoute skips when offline and StagePlanner never invents fallback coordinates`() = runBlocking {
        // Verify syncCorridorPoisForRoute takes only (routeId, points) and skips when offline
        repository.isOnlineOverrideForTests = false
        val syncResult = repository.syncCorridorPoisForRoute(
            routeId = "99",
            points = listOf(GeoPointDto(55.0, 10.0), GeoPointDto(55.05, 10.05))
        )
        assertTrue("syncCorridorPoisForRoute must return failure when offline", syncResult.isFailure)

        // Verify StagePlanner never invents 56.0/10.0 or 55.0/10.0 coordinates when route has no points/waypoints
        val emptyPlannedRoute = com.example.data.model.PlannedRoute(
            planId = 1L,
            trailId = "trail_test",
            trailName = "Test",
            startTrailKm = 0.0,
            endTrailKm = 0.0,
            isReverse = false,
            totalWalkKm = 0.0,
            points = emptyList(),
            stages = emptyList()
        )
        val stages = com.example.util.StagePlanner.calculateStages(
            plannedRoute = emptyPlannedRoute,
            routeName = "Test",
            waypoints = emptyList(),
            overnightWaypoints = emptyList(),
            numDays = 3,
            dailyTargetKm = 15.0
        )
        assertTrue("StagePlanner must not invent stages with fallback coordinates when route points are empty", stages.isEmpty())
    }
}
