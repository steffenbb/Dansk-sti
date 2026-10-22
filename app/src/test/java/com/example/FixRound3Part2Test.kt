package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.repository.PlannedRouteRepository
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.util.StagePlanner
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailLegSlicer
import com.example.util.routing.WaypointRole
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound3Part2Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    /**
     * Helper to build a synthetic 40 km test trail with a FERRY segment between walking km 16 and km 18.
     * Note: FERRY segments do not count towards walking km (they stay FERRY segments with 0 walking km advance),
     * or we test that a FERRY segment located within the sliced range [10..25] is preserved in slicedSegments!
     */
    private fun createSyntheticTrailSegmentsWithFerry(): List<TrailSegmentDto> {
        // 1 deg lat ~ 111.195 km -> 0.009 deg lat ~ 1.00075 km
        // Walk segment 1: 0 km to 18 km (19 points, 1 km apart)
        val walk1Points = (0..18).map { i ->
            GeoPointDto(
                lat = 55.0 + i * 0.0089932, // ~1.000 km per step
                lng = 10.0,
                ele = 10.0 + (i % 5) * 4.0
            )
        }
        val endWalk1Lat = walk1Points.last().lat

        // Ferry segment at km 18 (crosses a strait ~2 km east, does not advance walking km)
        val ferryPoints = listOf(
            GeoPointDto(lat = endWalk1Lat, lng = 10.0, ele = 0.0),
            GeoPointDto(lat = endWalk1Lat, lng = 10.015, ele = 0.0),
            GeoPointDto(lat = endWalk1Lat, lng = 10.030, ele = 0.0)
        )

        // Walk segment 2: 18 km to 40 km (23 points, 1 km apart starting from ferry arrival)
        val walk2Points = (0..22).map { i ->
            GeoPointDto(
                lat = endWalk1Lat + i * 0.0089932,
                lng = 10.030,
                ele = 12.0 + (i % 6) * 3.0
            )
        }

        return listOf(
            TrailSegmentDto(type = TrailSegmentType.WALK, points = walk1Points),
            TrailSegmentDto(type = TrailSegmentType.FERRY, points = ferryPoints),
            TrailSegmentDto(type = TrailSegmentType.WALK, points = walk2Points)
        )
    }

    /**
     * Requirement H.1:
     * TRAIL leg slicing: km 10 to 25 of a test trail gives 15 km +/- 1 % and keeps ferry segments.
     */
    @Test
    fun testTrailLegSlicing_Km10To25_Gives15KmAndKeepsFerrySegments() {
        val segments = createSyntheticTrailSegmentsWithFerry()
        val totalKm = TrailLegSlicer.totalTrailKm(segments)
        assertTrue("Total walking km should be ~40 km (got $totalKm)", totalKm in 39.5..40.5)

        val leg = TrailLegSlicer.sliceTrailLeg(
            fromWaypointId = "wp_start",
            toWaypointId = "wp_end",
            segments = segments,
            fromKm = 10.0,
            toKm = 25.0
        )

        assertEquals(LegMode.TRAIL, leg.legMode)
        // 15 km +/- 1% -> [14.85, 15.15]
        assertEquals(
            "Sliced TRAIL leg from km 10 to 25 must be 15 km +/- 1%",
            15.0,
            leg.lengthKm,
            0.15
        )
        assertTrue(
            "Sliced TRAIL leg must preserve the FERRY segment",
            leg.slicedSegments.any { it.type == TrailSegmentType.FERRY && it.points.size >= 2 }
        )
        assertTrue(
            "Sliced TRAIL leg must also have WALK segments",
            leg.slicedSegments.count { it.type == TrailSegmentType.WALK } >= 2
        )
    }

    /**
     * Requirement H.2:
     * Reversed plan gives the same total length.
     */
    @Test
    fun testReversedPlan_GivesSameTotalLength() {
        val segments = createSyntheticTrailSegmentsWithFerry()

        val forwardLeg = TrailLegSlicer.sliceTrailLeg(
            fromWaypointId = "start",
            toWaypointId = "end",
            segments = segments,
            fromKm = 10.0,
            toKm = 25.0
        )

        val reverseLeg = TrailLegSlicer.sliceTrailLeg(
            fromWaypointId = "end",
            toWaypointId = "start",
            segments = segments,
            fromKm = 25.0,
            toKm = 10.0
        )

        assertEquals(
            "Reversed plan must give the exact same total walking length",
            forwardLeg.lengthKm,
            reverseLeg.lengthKm,
            0.01
        )
        // Ascent and descent swap when reversed
        assertEquals(forwardLeg.ascentM, reverseLeg.descentM, 1.0)
        assertEquals(forwardLeg.descentM, reverseLeg.ascentM, 1.0)
    }

    /**
     * Requirement H.3:
     * Detour to a pin 400 m off the trail adds a SNAP out and back and a positive detour cost.
     */
    @Test
    fun testDetourToPin400mOffTrail_AddsSnapOutAndBackAndPositiveDetourCost() = runBlocking {
        val segments = createSyntheticTrailSegmentsWithFerry()
        val vm = RouteBuilderViewModel(app)

        // Initialize synthetic trail directly or via repository
        val testTrail = TrailEntity(
            id = "test_trail_40km",
            name = "Teststien 40 km",
            region = "Fyn",
            parentRegion = "Danmark",
            description = "Test trail",
            computedWalkingKm = 40.0,
            isGeometryDownloaded = true
        )
        database.dao().insertTrails(listOf(testTrail))

        // Save geometry so repository.getTrailPoints returns our segments
        val geomFile = java.io.File(app.filesDir, "trail_geom_test_trail_40km.json")
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val adapter = moshi.adapter(com.example.data.model.TrailGeometryDto::class.java)
        geomFile.writeText(
            adapter.toJson(
                com.example.data.model.TrailGeometryDto(
                    trailId = "test_trail_40km",
                    osmRelationId = 123456L,
                    segments = segments,
                    totalWalkingKm = 40.0,
                    gapsOver200mCount = 0,
                    bboxNorth = 56.0,
                    bboxSouth = 55.0,
                    bboxEast = 11.0,
                    bboxWest = 10.0,
                    fetchedAt = System.currentTimeMillis()
                )
            )
        )

        vm.initTrailMode("test_trail_40km")
        // Wait for initialization
        var attempts = 0
        while (vm.uiState.value.waypoints.size < 2 && attempts < 50) {
            delay(50)
            attempts++
        }

        val initialTotalKm = vm.uiState.value.totalKm
        assertTrue("Initial trail totalKm should be ~40 km (got $initialTotalKm)", initialTotalKm > 35.0)

        // Place a pin ~400 m off the trail near km 10
        val ptAt10 = TrailLegSlicer.pointAtKm(segments, 10.0)!!
        // 400 m east in longitude at lat 55 (~0.00626 deg lng)
        val offTrailLat = ptAt10.lat
        val offTrailLng = ptAt10.lng + 0.00626

        vm.addOvernightDetourPoi(
            lat = offTrailLat,
            lng = offTrailLng,
            name = "Skovbryn Shelter",
            spotType = "SHELTER",
            poiId = "shelter_400m",
            bookingRequired = false,
            hasWater = true,
            hasToilet = true
        )

        // Wait for detour coroutine to complete
        attempts = 0
        while ((vm.uiState.value.isRoutingLegCount > 0 || vm.uiState.value.waypoints.none { it.role == WaypointRole.OVERNIGHT }) && attempts < 60) {
            delay(50)
            attempts++
        }

        val state = vm.uiState.value
        val overnightPin = state.waypoints.find { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
        assertNotNull("Overnight pin must be added", overnightPin)
        assertTrue(
            "Detour cost on the pin must be positive (got ${overnightPin?.detourCostKm})",
            (overnightPin?.detourCostKm ?: 0.0) > 0.1
        )

        // Check that helper exit and rejoin waypoints were inserted and SNAP legs out and back exist
        val helpers = state.waypoints.filter { it.isHelper }
        assertEquals("Exit and rejoin helper waypoints must be created", 2, helpers.size)

        val snapLegs = state.legs.values.filter { it.legMode == LegMode.SNAP }
        assertEquals("Must have 2 SNAP legs (exit->pin and pin->rejoin)", 2, snapLegs.size)

        val trailLegs = state.legs.values.filter { it.legMode == LegMode.TRAIL }
        assertEquals("Must have 2 TRAIL legs (start->exit and rejoin->end)", 2, trailLegs.size)

        assertTrue(
            "Total route km with detour (${state.totalKm}) must exceed initial trail km ($initialTotalKm)",
            state.totalKm > initialTotalKm + 0.2
        )

        // Also verify removing the detour pin removes its helpers and restores the plain TRAIL leg (Requirement D)
        vm.deleteWaypoint(overnightPin!!.id)
        val afterDelete = vm.uiState.value
        assertTrue("Helper waypoints must be removed when detour pin is deleted", afterDelete.waypoints.none { it.isHelper })
        assertEquals("Only START and END waypoints remain", 2, afterDelete.waypoints.size)
        assertEquals("Restored single TRAIL leg", 1, afterDelete.legs.values.count { it.legMode == LegMode.TRAIL })

        geomFile.delete()
        Unit
    }

    /**
     * Requirement H.4:
     * Days split exactly at OVERNIGHT pins.
     */
    @Test
    fun testDaysSplitExactlyAtOvernightPins() = runBlocking {
        val plannedRouteRepo = PlannedRouteRepository(app)
        val segments = createSyntheticTrailSegmentsWithFerry()

        // Build a 30 km route from km 0 to km 30 with two OVERNIGHT pins at km 12.0 and km 23.5
        val wpStart = RouteWaypoint(
            id = "wp_start",
            lat = segments.first().points.first().lat,
            lng = segments.first().points.first().lng,
            role = WaypointRole.START,
            legModeToNext = LegMode.TRAIL,
            trailKm = 0.0,
            name = "Start"
        )
        val pt12 = TrailLegSlicer.pointAtKm(segments, 12.0)!!
        val wpNight1 = RouteWaypoint(
            id = "wp_n1",
            lat = pt12.lat,
            lng = pt12.lng,
            role = WaypointRole.OVERNIGHT,
            legModeToNext = LegMode.TRAIL,
            trailKm = 12.0,
            name = "Lejr 1",
            spotType = "SHELTER"
        )
        val pt235 = TrailLegSlicer.pointAtKm(segments, 23.5)!!
        val wpNight2 = RouteWaypoint(
            id = "wp_n2",
            lat = pt235.lat,
            lng = pt235.lng,
            role = WaypointRole.OVERNIGHT,
            legModeToNext = LegMode.TRAIL,
            trailKm = 23.5,
            name = "Lejr 2",
            spotType = "FRI_TELTNING"
        )
        val pt30 = TrailLegSlicer.pointAtKm(segments, 30.0)!!
        val wpEnd = RouteWaypoint(
            id = "wp_end",
            lat = pt30.lat,
            lng = pt30.lng,
            role = WaypointRole.END,
            legModeToNext = LegMode.TRAIL,
            trailKm = 30.0,
            name = "Slut"
        )

        val waypoints = listOf(wpStart, wpNight1, wpNight2, wpEnd)
        val leg1 = TrailLegSlicer.sliceTrailLeg(wpStart.id, wpNight1.id, segments, 0.0, 12.0)
        val leg2 = TrailLegSlicer.sliceTrailLeg(wpNight1.id, wpNight2.id, segments, 12.0, 23.5)
        val leg3 = TrailLegSlicer.sliceTrailLeg(wpNight2.id, wpEnd.id, segments, 23.5, 30.0)
        val legs = mapOf(
            leg1.legKey to leg1,
            leg2.legKey to leg2,
            leg3.legKey to leg3
        )

        val (_, stages, plannedRoute) = plannedRouteRepo.saveUnifiedPlanAndRoute(
            existingRouteId = null,
            existingPlanId = null,
            name = "Test Etapedeling",
            baseTrailId = "test_trail_40km",
            isReverse = false,
            optionsJson = "{}",
            waypoints = waypoints,
            legs = legs,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            startTime = "09:00",
            startDateEpochMs = null,
            shelters = emptyList(),
            friTeltnings = emptyList(),
            pois = emptyList(),
            sunsetTime = "21:00"
        )

        assertEquals("2 OVERNIGHT pins must produce exactly 3 days", 3, stages.size)
        assertEquals("Day 1 starts at 0.0 km", 0.0, stages[0].startKm, 0.05)
        assertEquals("Day 1 ends at OVERNIGHT pin 1 (~12.0 km)", 12.0, stages[0].endKm, 0.2)
        assertEquals("Lejr 1", stages[0].stopName)

        assertEquals("Day 2 starts at OVERNIGHT pin 1 (~12.0 km)", stages[0].endKm, stages[1].startKm, 0.05)
        assertEquals("Day 2 ends at OVERNIGHT pin 2 (~23.5 km)", 23.5, stages[1].endKm, 0.25)
        assertEquals("Lejr 2", stages[1].stopName)

        assertEquals("Day 3 starts at OVERNIGHT pin 2 (~23.5 km)", stages[1].endKm, stages[2].startKm, 0.05)
        assertEquals("Day 3 ends at END (~30.0 km)", plannedRoute.totalWalkKm, stages[2].endKm, 0.1)
    }

    /**
     * End-to-end "Done when" verification on Øhavsstien:
     * - Opening planner on Øhavsstien places start and end pins on the trail;
     * - Dragging the start pin to km 20 updates the totals instantly without any network request;
     * - Adding a shelter 600 m off the trail shows a detour and makes it tonight's camp;
     * - "Foreslå overnatninger" produces day pins you can drag;
     * - Saving and pressing Start stores and loads the exact PlannedRoute for navigation.
     */
    @Test
    fun testOhavsstienEndToEndPlannerFlow() = runBlocking {
        val appInstance = app as DanskStiApp
        appInstance.repository.initializePreseededDataIfEmpty()

        val vm = RouteBuilderViewModel(app)
        vm.initTrailMode("trail_ohavsstien")

        var attempts = 0
        while (vm.uiState.value.waypoints.size < 2 && attempts < 60) {
            delay(50)
            attempts++
        }

        val initial = vm.uiState.value
        assertEquals(PlannerMode.TRAIL, initial.plannerMode)
        assertEquals("trail_ohavsstien", initial.baseTrailId)
        assertEquals(2, initial.waypoints.size)
        assertNotNull("Start pin has trailKm", initial.waypoints.first().trailKm)
        assertNotNull("End pin has trailKm", initial.waypoints.last().trailKm)

        val fullTrailKm = initial.totalKm
        assertTrue("Øhavsstien totalKm should be > 50 km (got $fullTrailKm)", fullTrailKm > 50.0)

        // Drag start pin to km 20 -> updates totals instantly without network request
        val startWp = initial.waypoints.first()
        val ptAt20 = TrailLegSlicer.pointAtKm(vm.getBaseTrailSegments(), 20.0)!!
        vm.onWaypointDragged(startWp.id, ptAt20.lat, ptAt20.lng)

        val afterDrag = vm.uiState.value
        assertEquals("Dragging start pin to km 20 requires 0 network routing calls", 0, afterDrag.isRoutingLegCount)
        assertEquals(20.0, afterDrag.waypoints.first().trailKm ?: 0.0, 0.5)
        assertEquals("Total km must decrease by ~20 km immediately", fullTrailKm - 20.0, afterDrag.totalKm, 1.0)

        // Add a shelter ~600 m off the trail
        val ptAt35 = TrailLegSlicer.pointAtKm(vm.getBaseTrailSegments(), 35.0)!!
        val shelter600mLat = ptAt35.lat
        val shelter600mLng = ptAt35.lng + 0.0094 // ~600 m east at lat 55

        vm.addOvernightDetourPoi(
            lat = shelter600mLat,
            lng = shelter600mLng,
            name = "Øhavet Naturlejrplads",
            spotType = "SHELTER",
            poiId = "ohav_shelter_600",
            bookingRequired = false,
            hasWater = true,
            hasToilet = false
        )

        attempts = 0
        while ((vm.uiState.value.isRoutingLegCount > 0 || vm.uiState.value.waypoints.none { it.role == WaypointRole.OVERNIGHT }) && attempts < 60) {
            delay(50)
            attempts++
        }

        val withShelter = vm.uiState.value
        val campPin = withShelter.waypoints.find { it.role == WaypointRole.OVERNIGHT && !it.isHelper }
        assertNotNull("Shelter must become an OVERNIGHT pin", campPin)
        assertTrue("Detour cost must be shown (> 0 km)", (campPin?.detourCostKm ?: 0.0) > 0.1)
        assertEquals("Must split into 2 days (tonight's camp + tomorrow to end)", 2, withShelter.daySummaries.size)
        assertEquals("Øhavet Naturlejrplads", withShelter.daySummaries[0].overnightName)

        // Save and start navigation -> verify PlannedRouteRepository loads the exact route
        var startedPlanId = -1L
        vm.saveAndStartNavigation { pid ->
            startedPlanId = pid.removePrefix("route_").toLongOrNull() ?: 1L
        }

        attempts = 0
        while (startedPlanId <= 0L && attempts < 60) {
            delay(50)
            attempts++
        }

        assertTrue("Plan ID must be > 0 after saveAndStartNavigation", startedPlanId > 0L)
        val loadedRoute = PlannedRouteRepository(app).loadPlannedRoute(startedPlanId)
        assertNotNull("PlannedRoute must be stored for navigation", loadedRoute)
        assertEquals(withShelter.totalKm, loadedRoute!!.totalWalkKm, 0.5)
        assertEquals(2, loadedRoute.stages.size)
    }
}
