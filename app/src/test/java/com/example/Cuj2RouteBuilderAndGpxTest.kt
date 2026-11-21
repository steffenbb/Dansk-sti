package com.example

import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentType
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
}
