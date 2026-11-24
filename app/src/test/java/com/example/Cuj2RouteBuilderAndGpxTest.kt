package com.example

import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentType
import com.example.ui.viewmodel.*
import com.example.util.GpxImporter
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class Cuj2RouteBuilderAndGpxTest {

    @Test
    fun cuj2_multiDayStagePlanningCalculatesStagesAndSunTimes() {
        val points = (0..450).map { idx ->
            val walkKm = idx * 0.1 // 45.0 km total
            val frac = idx / 450.0
            RoutePoint(
                lat = 55.0 + frac * 0.35,
                lng = 10.0 + frac * 0.25,
                ele = 15.0 + (idx % 10) * 2.0,
                walkKm = walkKm,
                trailKm = walkKm,
                segmentType = TrailSegmentType.WALK,
                segmentIndex = 0
            )
        }
        val plannedRoute = PlannedRoute(
            planId = 202_001L,
            trailId = "cuj2_trail",
            trailName = "Øhavsstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 45.0,
            totalWalkKm = 45.0,
            points = points,
            stages = emptyList()
        )
        val candidates = listOf(
            OvernightCandidate(
                id = "stop_1",
                name = "Dag 1 Shelter",
                type = "SHELTER",
                lat = 55.12,
                lng = 10.08,
                walkKm = 15.0,
                detourMeters = 120.0,
                bookingRequired = false,
                hasWaterNearby = true,
                hasToiletNearby = true
            ),
            OvernightCandidate(
                id = "stop_2",
                name = "Dag 2 Teltplads",
                type = "FRI_TELTNING",
                lat = 55.24,
                lng = 10.16,
                walkKm = 30.0,
                detourMeters = 90.0,
                bookingRequired = false,
                hasWaterNearby = true,
                hasToiletNearby = false
            )
        )

        val stages = StagePlanner.planStages(
            plannedRoute = plannedRoute,
            routeName = "Øhavsstien",
            paceKmh = 4.5,
            dailyTargetKm = 15.0,
            numDays = 3,
            overnightType = OvernightType.BOTH,
            candidatesOverride = candidates
        )

        assertEquals(3, stages.size)
        val totalStageKm = stages.sumOf { it.stageKm }
        assertEquals(plannedRoute.totalWalkKm, totalStageKm, 0.5)
        for (stage in stages) {
            assertTrue("Stage sunset must be formatted HH:mm", stage.sunsetTime.matches(Regex("\\d{2}:\\d{2}")))
        }
    }

    @Test
    fun cuj2_gpxExportImportRoundTripPreservesWaypointsAndTrackPoints() {
        val trackPoints = listOf(
            GeoPointDto(lat = 55.6761, lng = 12.5683, ele = 12.5),
            GeoPointDto(lat = 55.6805, lng = 12.5750, ele = 18.0),
            GeoPointDto(lat = 55.6850, lng = 12.5820, ele = 24.0),
            GeoPointDto(lat = 55.6900, lng = 12.5900, ele = 19.5)
        )
        val trkptsXml = trackPoints.joinToString("") { "<trkpt lat=\"${it.lat}\" lon=\"${it.lng}\"><ele>${it.ele}</ele></trkpt>" }
        val gpxXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<gpx version=\"1.1\" creator=\"DanskStiTest\" xmlns=\"http://www.topografix.com/GPX/1/1\">" +
            "<metadata><name>København Nord Rute</name></metadata>" +
            "<trk><name>København Nord Rute</name><trkseg>$trkptsXml</trkseg></trk></gpx>"

        val parsed = GpxImporter.parseAndSimplifyGpx(gpxXml.byteInputStream())
        assertEquals("København Nord Rute", parsed.name)
        assertEquals(4, parsed.originalPoints.size)
        assertTrue("Waypoints should include at least start and end", parsed.waypoints.size >= 2)
        assertEquals(12.5, parsed.originalPoints[0].ele ?: 0.0, 0.01)
        assertEquals(24.0, parsed.originalPoints[2].ele ?: 0.0, 0.01)
        assertTrue("Total distance should be positive", parsed.totalOriginalKm > 1.5)
    }

    @Test
    fun cuj2_tegnVsKigTap10StepUndoRedoAndDraftSaveRestoreAcrossViewModelRecreation(): Unit = kotlinx.coroutines.runBlocking {
        com.example.data.remote.OverpassClient.customTransport = { _, _ ->
            com.example.data.remote.OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }
        try {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<DanskStiApp>()
            val dao = app.database.dao()
            dao.deletePlannerDraftByKey("free_new")

            val vm = com.example.ui.viewmodel.RouteBuilderViewModel(app)
            // Use fast deterministic routing engine so leg computations never hit the network
            vm.routingEngine = object : com.example.util.routing.RoutingEngine {
                override suspend fun routeLeg(
                    from: com.example.util.routing.LatLng,
                    to: com.example.util.routing.LatLng,
                    options: com.example.util.routing.RouteOptions,
                    alternative: Int
                ): com.example.util.routing.RouteLegResult {
                    return com.example.util.routing.RouteLegResult.Success(
                        points = listOf(
                            com.example.util.routing.RoutePoint3D(from.lat, from.lng, 10.0),
                            com.example.util.routing.RoutePoint3D(to.lat, to.lng, 15.0)
                        ),
                        lengthKm = 1.2,
                        ascentM = 5.0,
                        descentM = 0.0,
                        sourceName = "TestEngine",
                        perPointWayInfo = emptyList()
                    )
                }
            }

            // 1. Tegn vs Kig tap behaviour:
            // In KIG mode, plain map taps must NOT add any waypoints
            vm.setInteractionMode(com.example.ui.viewmodel.PlannerInteractionMode.KIG)
            assertEquals(com.example.ui.viewmodel.PlannerInteractionMode.KIG, vm.uiState.value.interactionMode)
            vm.onMapTapped(55.7000, 12.5000)
            vm.onMapTapped(55.7100, 12.5100)
            assertEquals("Tapping map in KIG mode must not add waypoints", 0, vm.uiState.value.waypoints.size)

            // Switch to TEGN mode: 10 taps add 10 waypoints (10-step history)
            vm.setInteractionMode(com.example.ui.viewmodel.PlannerInteractionMode.TEGN)
            assertEquals(com.example.ui.viewmodel.PlannerInteractionMode.TEGN, vm.uiState.value.interactionMode)

            for (step in 1..10) {
                vm.onMapTapped(55.7000 + step * 0.005, 12.5000 + step * 0.005)
            }
            val expectedWaypointsAfter10Steps = vm.uiState.value.waypoints
            assertEquals(10, expectedWaypointsAfter10Steps.size)

            // Verify switching back to KIG ignores taps and preserves the 10 waypoints
            vm.setInteractionMode(com.example.ui.viewmodel.PlannerInteractionMode.KIG)
            vm.onMapTapped(56.0000, 10.0000)
            assertEquals(10, vm.uiState.value.waypoints.size)

            // 2. 10-step undo followed by 10-step redo returns the exact same waypoints
            for (u in 9 downTo 0) {
                assertTrue("Expected canUndo at step ${u + 1}", vm.uiState.value.canUndo)
                vm.undo()
                assertEquals("After undo down to $u waypoints", u, vm.uiState.value.waypoints.size)
            }
            assertEquals(0, vm.uiState.value.waypoints.size)

            for (r in 1..10) {
                assertTrue("Expected canRedo at step $r", vm.uiState.value.canRedo)
                vm.redo()
                assertEquals("After redo up to $r waypoints", r, vm.uiState.value.waypoints.size)
            }
            assertEquals(
                "10-step undo/redo must return the exact same waypoints",
                expectedWaypointsAfter10Steps,
                vm.uiState.value.waypoints
            )

            // 3. Draft saved to Room and restored after recreating the ViewModel
            vm.updateRouteName("CUJ2 Kladde Rute")
            vm.saveDraftToRoomNow()

            val savedDraft = dao.getPlannerDraftByKey("free_new")
            org.junit.Assert.assertNotNull("Planner draft must be persisted in Room under 'free_new'", savedDraft)

            val recreatedVm = com.example.ui.viewmodel.RouteBuilderViewModel(app)
            recreatedVm.checkForExistingDraft("free_new")
            org.junit.Assert.assertNotNull(
                "Recreated ViewModel must detect pendingDraftToRestore",
                recreatedVm.uiState.value.pendingDraftToRestore
            )
            recreatedVm.continueDraft()

            assertEquals("CUJ2 Kladde Rute", recreatedVm.uiState.value.routeName)
            assertEquals(
                "Restored draft after ViewModel recreation must preserve exact waypoints",
                expectedWaypointsAfter10Steps,
                recreatedVm.uiState.value.waypoints
            )
            vm.corridorAccessJob?.cancel()
            recreatedVm.corridorAccessJob?.cancel()
        } finally {
            com.example.data.remote.OverpassClient.customTransport = null
        }
    }
}
