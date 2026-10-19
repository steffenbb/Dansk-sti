package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.CustomRouteEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailPoiEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.OsmPoiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.util.GeoUtils
import com.example.util.GpxExporter
import com.example.util.OfflineDownloadManager
import com.example.util.OvernightCandidate
import com.example.util.RouteTracker
import com.example.util.StagePlanner
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoutePhase11Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase
    private lateinit var repository: DanskStiRepository
    private lateinit var geometryRepository: TrailGeometryRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
        repository = DanskStiRepository(database.dao(), app)
        geometryRepository = TrailGeometryRepository(app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    /**
     * Requirement A: Convert a CustomRoute into the same TrailGeometryDto used by trails
     * (segments WALK/FERRY, per-point elevation, cumulative km), stored in filesDir/routes/<id>.json.gz.
     * Do not create a second geometry format.
     */
    @Test
    fun testRequirementA_ConvertCustomRouteToTrailGeometry_AndPersistInGz() = runBlocking {
        val wp1 = RouteWaypoint(id = "wp1", lat = 56.10, lng = 10.10, role = WaypointRole.START)
        val wp2 = RouteWaypoint(id = "wp2", lat = 56.15, lng = 10.15, role = WaypointRole.VIA)
        val wp3 = RouteWaypoint(id = "wp3", lat = 56.20, lng = 10.20, role = WaypointRole.END)

        val walkLeg = LegGeometryData(
            fromWaypointId = wp1.id,
            toWaypointId = wp2.id,
            legMode = LegMode.SNAP,
            points = listOf(
                RoutePoint3D(56.10, 10.10, 25.0),
                RoutePoint3D(56.12, 10.12, 35.0),
                RoutePoint3D(56.15, 10.15, 45.0)
            ),
            lengthKm = 6.2,
            ascentM = 30.0,
            descentM = 10.0,
            pathSharePct = 95.0,
            roadKm = 0.3
        )

        val ferryLeg = LegGeometryData(
            fromWaypointId = wp2.id,
            toWaypointId = wp3.id,
            legMode = LegMode.STRAIGHT,
            points = listOf(
                RoutePoint3D(56.15, 10.15, 0.0),
                RoutePoint3D(56.20, 10.20, 0.0)
            ),
            lengthKm = 5.8,
            ascentM = 0.0,
            descentM = 0.0,
            pathSharePct = 0.0,
            roadKm = 0.0,
            sourceName = "Ferry link"
        )

        val orderedLegs = listOf(walkLeg, ferryLeg)

        // Convert to TrailGeometryDto
        val geom = RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(
            routeId = "custom_test_99",
            orderedLegs = orderedLegs,
            customRouteName = "Mols Bjerge til Samsø"
        )

        // Verification of TrailGeometryDto structure
        assertEquals("custom_test_99", geom.trailId)
        assertEquals(2, geom.segments.size)
        assertEquals(TrailSegmentType.WALK, geom.segments[0].type)
        assertEquals(TrailSegmentType.FERRY, geom.segments[1].type)
        assertEquals(3, geom.segments[0].points.size)
        assertEquals(25.0, geom.segments[0].points[0].ele ?: 0.0, 0.1)
        assertEquals(35.0, geom.segments[0].points[1].ele ?: 0.0, 0.1)
        assertEquals(6.2, geom.segments[0].lengthKm, 0.01)
        assertEquals(5.8, geom.segments[1].lengthKm, 0.01)
        assertEquals(6.2, geom.totalWalkingKm, 0.01)

        // Save into filesDir/routes/<id>.json.gz
        geometryRepository.saveRouteGeometry("custom_test_99", geom)

        val expectedFile = File(geometryRepository.routesDir, "custom_test_99.json.gz")
        assertTrue("routes/<id>.json.gz file must exist", expectedFile.exists())

        // Load back using standard loadGeometry and verify it loaded identically
        val loaded = geometryRepository.loadGeometry("custom_test_99")
        assertNotNull(loaded)
        assertEquals("custom_test_99", loaded!!.trailId)
        assertEquals(2, loaded.segments.size)
        assertEquals(TrailSegmentType.WALK, loaded.segments[0].type)
        assertEquals(TrailSegmentType.FERRY, loaded.segments[1].type)
        assertEquals(25.0, loaded.segments[0].points[0].ele ?: 0.0, 0.1)
    }

    /**
     * Requirement B: POIs along the route:
     * - GeoFA items: from local cache, projected onto route (kmOnRoute, detourMeters)
     * - OSM items: Overpass with polyline corridor simplified to ~1 pt per 200m and around:<m>,lat1,lon1,...
     * - Store in TrailPoi with routeId
     */
    @Test
    fun testRequirementB_PoisAlongRoute_CorridorPolylineAndStoreWithRouteId() = runBlocking {
        val osmRepo = OsmPoiRepository(app)

        // Test polyline simplification: points closer than 200m get simplified
        val inputPts = listOf(
            GeoPointDto(56.000, 10.000),
            GeoPointDto(56.0001, 10.0001), // ~13m away -> skipped
            GeoPointDto(56.002, 10.002),   // ~250m away -> kept
            GeoPointDto(56.005, 10.005)    // ~400m away -> kept
        )
        val simplified = osmRepo.simplifyPointsForCorridor(inputPts, minDistanceMeters = 200.0)
        assertTrue(simplified.size in 3..4)

        // Test Overpass query string contains around:3000,lat1,lon1,... and around:2000 for shops
        val query = osmRepo.buildOverpassPolylineQuery(simplified)
        assertTrue(query.contains("around:2000,"))
        assertTrue(query.contains("around:3000,"))
        assertTrue(query.contains("amenity\"=\"drinking_water"))
        assertTrue(query.contains("amenity\"=\"shelter"))
        assertTrue(query.contains("nwr[\"shop\"~\"^(supermarket|convenience|bakery|farm|kiosk|greengrocer)$\"]"))

        // Test storing in TrailPoi with routeId
        val dao = database.dao()
        val shelter = ShelterEntity(
            id = "sh_phase11_1",
            name = "Skovbryn Shelter",
            lat = 56.105,
            lng = 10.105,
            capacity = 6,
            hasWaterNearby = true,
            hasToiletNearby = true,
            source = "geofa"
        )
        dao.insertShelters(listOf(shelter))

        val waterPoi = PoiEntity(
            id = "poi_phase11_water",
            type = "water",
            name = "Vandpost",
            lat = 56.106,
            lng = 10.106,
            source = "geofa"
        )
        dao.insertPois(listOf(waterPoi))

        val routePoints = listOf(
            GeoPointDto(56.10, 10.10),
            GeoPointDto(56.11, 10.11),
            GeoPointDto(56.12, 10.12)
        )

        val (shKm, shDetour) = GeoUtils.projectPointOnPolyline(GeoPointDto(shelter.lat, shelter.lng), routePoints)
        val (poiKm, poiDetour) = GeoUtils.projectPointOnPolyline(GeoPointDto(waterPoi.lat, waterPoi.lng), routePoints)

        dao.insertTrailPois(
            listOf(
                TrailPoiEntity(
                    trailId = "route_101",
                    poiId = shelter.id,
                    kmOnRoute = shKm,
                    detourMeters = shDetour,
                    routeId = "101"
                ),
                TrailPoiEntity(
                    trailId = "route_101",
                    poiId = waterPoi.id,
                    kmOnRoute = poiKm,
                    detourMeters = poiDetour,
                    routeId = "101"
                )
            )
        )

        // Query by routeId
        val sheltersForRoute = dao.getSheltersForRoute("101", maxDetour = 3000.0)
        assertEquals(1, sheltersForRoute.size)
        assertEquals("Skovbryn Shelter", sheltersForRoute[0].name)

        val poisForRoute = dao.getPoisForRoute("101", maxDetour = 3000.0)
        assertEquals(1, poisForRoute.size)
        assertEquals("Vandpost", poisForRoute[0].name)
    }

    /**
     * Requirement C: "Find overnatning" button in the route builder:
     * for each day (daily target from the planner), list overnight candidates near that day's end
     * within max detour, using the Phase 5 scoring.
     * Tap one = insert it as an OVERNIGHT waypoint and re-route the two legs.
     */
    @Test
    fun testRequirementC_FindOvernightCandidates_And_InsertOvernightWaypoint() = runBlocking {
        val dao = database.dao()

        // Create shelters along a 45 km route (Day 1 end ~15 km, Day 2 end ~30 km)
        val shelterDay1 = ShelterEntity(
            id = "sh_day1",
            name = "Shelter Dag 1",
            lat = 56.13,
            lng = 10.13,
            capacity = 4,
            hasWaterNearby = true,
            hasToiletNearby = true,
            source = "geofa"
        )
        val shelterDay2 = ShelterEntity(
            id = "sh_day2",
            name = "Shelter Dag 2",
            lat = 56.26,
            lng = 10.26,
            capacity = 5,
            hasWaterNearby = true,
            hasToiletNearby = false,
            source = "geofa"
        )
        dao.insertShelters(listOf(shelterDay1, shelterDay2))

        // 45 km route with 46 points from 0 to 45 km
        val routePoints = (0..45).map { km ->
            val fraction = km / 45.0
            RoutePoint(
                lat = 56.0 + (fraction * 0.40),
                lng = 10.0 + (fraction * 0.40),
                ele = 20.0,
                walkKm = km.toDouble(),
                trailKm = km.toDouble()
            )
        }

        val plannedRoute = PlannedRoute(
            planId = 0L,
            trailId = "route_builder_preview",
            trailName = "3 Dages Rute",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 45.0,
            totalWalkKm = 45.0,
            points = routePoints,
            stages = emptyList()
        )

        // Find overnight candidates for daily target 15 km
        val candidatesByDay = StagePlanner.findCandidatesForDayTargets(
            plannedRoute = plannedRoute,
            dailyTargetKm = 15.0,
            maxDetourKm = 3.0,
            shelters = listOf(shelterDay1, shelterDay2),
            friTeltnings = emptyList(),
            waterPois = emptyList(),
            toiletPois = emptyList()
        )

        // Day 1 (15 km) and Day 2 (30 km)
        assertEquals(2, candidatesByDay.size)
        assertTrue(candidatesByDay.containsKey(1))
        assertTrue(candidatesByDay.containsKey(2))

        val day1Candidates = candidatesByDay[1]!!
        assertTrue(day1Candidates.any { it.id == "sh_day1" })

        // Tap one = insert it as an OVERNIGHT waypoint
        val selectedCandidate = day1Candidates.first { it.id == "sh_day1" }

        val waypoints = mutableListOf(
            RouteWaypoint(id = "wp_start", lat = 56.0, lng = 10.0, role = WaypointRole.START),
            RouteWaypoint(id = "wp_end", lat = 56.40, lng = 10.40, role = WaypointRole.END)
        )

        val overnightWp = RouteWaypoint(
            id = "wp_overnight_1",
            lat = selectedCandidate.lat,
            lng = selectedCandidate.lng,
            role = WaypointRole.OVERNIGHT,
            name = selectedCandidate.name,
            linkedPoiId = selectedCandidate.id
        )

        // Insert at index 1
        waypoints.add(1, overnightWp)

        assertEquals(3, waypoints.size)
        assertEquals(WaypointRole.START, waypoints[0].role)
        assertEquals(WaypointRole.OVERNIGHT, waypoints[1].role)
        assertEquals(WaypointRole.END, waypoints[2].role)
        assertEquals("Shelter Dag 1", waypoints[1].name)
    }

    /**
     * Requirement D: "Planlæg dage" turns a custom route into a trip plan with the existing stage planner;
     * OVERNIGHT waypoints are fixed stage ends.
     *
     * Done when: a custom 3-day route gets real shelters along it, splits into 3 stages that end at
     * the chosen OVERNIGHT points, navigates with the route line, and downloads for offline.
     */
    @Test
    fun testRequirementD_3DayRoute_SplitsInto3StagesEndingAtOvernightPoints() = runBlocking {
        // Build 45 km route
        val totalKm = 45.0
        val points = (0..90).map { i ->
            val walkKm = (i / 90.0) * totalKm
            val fraction = i / 90.0
            RoutePoint(
                lat = 56.0 + (fraction * 0.40),
                lng = 10.0 + (fraction * 0.40),
                ele = 25.0,
                walkKm = walkKm,
                trailKm = walkKm
            )
        }

        val plannedRoute = PlannedRoute(
            planId = 10L,
            trailId = "route_custom_3day",
            trailName = "3-dages vandring",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = totalKm,
            totalWalkKm = totalKm,
            points = points,
            stages = emptyList()
        )

        // 2 OVERNIGHT waypoints: Overnight 1 at km 15.0, Overnight 2 at km 30.0
        val ptDay1 = points.find { it.walkKm >= 15.0 }!!
        val ptDay2 = points.find { it.walkKm >= 30.0 }!!

        val wpStart = RouteWaypoint(id = "wp0", lat = 56.0, lng = 10.0, role = WaypointRole.START, name = "Startsted")
        val wpOvernight1 = RouteWaypoint(
            id = "wp_on1",
            lat = ptDay1.lat,
            lng = ptDay1.lng,
            role = WaypointRole.OVERNIGHT,
            name = "Lejrplads Dag 1",
            linkedPoiId = "sh_1"
        )
        val wpOvernight2 = RouteWaypoint(
            id = "wp_on2",
            lat = ptDay2.lat,
            lng = ptDay2.lng,
            role = WaypointRole.OVERNIGHT,
            name = "Shelter Dag 2",
            linkedPoiId = "sh_2"
        )
        val wpEnd = RouteWaypoint(id = "wp_end", lat = 56.40, lng = 10.40, role = WaypointRole.END, name = "Slutdestination")

        val allWaypoints = listOf(wpStart, wpOvernight1, wpOvernight2, wpEnd)
        val overnightWaypoints = listOf(wpOvernight1, wpOvernight2)

        val stages = StagePlanner.calculateStagesForCustomRoute(
            plannedRoute = plannedRoute,
            routeName = "3-dages vandring",
            waypoints = allWaypoints,
            overnightWaypoints = overnightWaypoints,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 3,
            overnightType = OvernightType.BOTH,
            shelters = emptyList(),
            friTeltnings = emptyList(),
            pois = emptyList()
        )

        // Must split into exactly 3 stages ending at the chosen OVERNIGHT points
        assertEquals(3, stages.size)

        // Stage 1: from Start to Lejrplads Dag 1
        assertEquals(1, stages[0].dayNumber)
        assertEquals("Lejrplads Dag 1", stages[0].stopName)
        assertEquals(0.0, stages[0].startKm, 0.2)
        assertEquals(15.0, stages[0].endKm, 0.5)

        // Stage 2: from Lejrplads Dag 1 to Shelter Dag 2
        assertEquals(2, stages[1].dayNumber)
        assertEquals("Shelter Dag 2", stages[1].stopName)
        assertEquals(15.0, stages[1].startKm, 0.5)
        assertEquals(30.0, stages[1].endKm, 0.5)

        // Stage 3: from Shelter Dag 2 to Slutdestination
        assertEquals(3, stages[2].dayNumber)
        assertEquals("Slutdestination", stages[2].stopName)
        assertEquals(30.0, stages[2].startKm, 0.5)
        assertEquals(45.0, stages[2].endKm, 0.5)
    }

    /**
     * Requirement E & F:
     * - Navigation, offline download, GPX export and Record my hike accept a custom route exactly like a trail.
     * - A custom route can be started from Gemte with "Navigér".
     */
    @Test
    fun testRequirementE_and_F_CustomRouteAcceptedEverywhere_NavigationOfflineGpx() = runBlocking {
        val dao = database.dao()

        val wpStart = RouteWaypoint(id = "w0", lat = 56.0, lng = 10.0, role = WaypointRole.START)
        val wpEnd = RouteWaypoint(id = "w1", lat = 56.1, lng = 10.1, role = WaypointRole.END)
        val wpsJson = RouteWaypoint.listToJson(listOf(wpStart, wpEnd))

        // Save a CustomRouteEntity in Room
        val customRoute = CustomRouteEntity(
            id = 42L,
            name = "Silkeborg Søerne Rundt",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            optionsJson = "{}",
            waypointsJson = wpsJson,
            totalKm = 18.5,
            ascentM = 120.0,
            descentM = 115.0,
            pathSharePct = 88.0,
            roadKm = 2.2,
            geometryFile = "route_42.json",
            sourceName = "Rutebygger",
            fetchedAt = System.currentTimeMillis()
        )
        dao.insertCustomRoute(customRoute)

        // 1. getTrailById returns a TrailEntity representing the custom route
        val trailEntity = repository.getTrailById("route_42")
        assertNotNull(trailEntity)
        assertEquals("route_42", trailEntity!!.id)
        assertEquals("Silkeborg Søerne Rundt", trailEntity.name)
        assertEquals(18.5, trailEntity.lengthKm, 0.1)

        // 2. ensureTripPlanForCustomRoute produces a plan ready for Navigation
        val planId = repository.ensureTripPlanForCustomRoute(customRoute)
        assertTrue(planId > 0)

        val plan = dao.getTripPlanById(planId)
        assertNotNull(plan)
        assertEquals("route_42", plan!!.trailId)
        assertEquals("Silkeborg Søerne Rundt", plan.trailName)

        // 3. Offline Download estimate works with custom route
        val points = listOf(
            GeoPointDto(56.0, 10.0),
            GeoPointDto(56.05, 10.05),
            GeoPointDto(56.1, 10.1)
        )
        val offlineEst = OfflineDownloadManager.estimateDownload(points, corridorKm = 1.0)
        assertTrue(offlineEst.tileCount > 0)
        assertTrue(offlineEst.isAllowed)

        // 4. GPX Export works with custom route
        val gpxXml = GpxExporter.generateMultiSegmentGpx(
            trail = trailEntity,
            plan = plan,
            stages = emptyList(),
            trailPoints = points
        )
        assertTrue(gpxXml.contains("Silkeborg Søerne Rundt"))
        assertTrue(gpxXml.contains("<trkpt lat=\"56.0\" lon=\"10.0\""))

        // 5. RouteTracker accepts planned route of custom route
        val plannedRoute = PlannedRoute(
            planId = planId,
            trailId = "route_42",
            trailName = "Silkeborg Søerne Rundt",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 18.5,
            totalWalkKm = 18.5,
            points = points.mapIndexed { i, p ->
                RoutePoint(p.lat, p.lng, 20.0, i * 9.25, i * 9.25)
            },
            stages = emptyList()
        )
        val tracker = RouteTracker(plannedRoute)
        val trackResult = tracker.onLocationUpdate(56.0, 10.0, 0f, 5f)
        assertNotNull(trackResult)
        assertEquals(0.0, trackResult.currentWalkKm, 0.1)
    }
}
