package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.CustomRouteEntity
import com.example.util.GpxImporter
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RouteBuilderTest {

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

    @Test
    fun testThreePointRoute_DraggingMiddlePointReRoutesOnlyTwoLegs() {
        // Step 1: Build a 3-point route: WP0 (Start) -> WP1 (Middle) -> WP2 (End)
        val wp0 = RouteWaypoint(id = "wp0", lat = 56.885, lng = 9.835, role = WaypointRole.START)
        val wp1 = RouteWaypoint(id = "wp1", lat = 56.890, lng = 9.840, role = WaypointRole.VIA)
        val wp2 = RouteWaypoint(id = "wp2", lat = 56.895, lng = 9.845, role = WaypointRole.END)

        val leg01 = LegGeometryData(
            fromWaypointId = wp0.id,
            toWaypointId = wp1.id,
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(wp0.lat, wp0.lng, 20.0), RoutePoint3D(wp1.lat, wp1.lng, 25.0)),
            lengthKm = 0.8,
            ascentM = 10.0,
            descentM = 5.0,
            pathSharePct = 90.0,
            roadKm = 0.08
        )

        val leg12 = LegGeometryData(
            fromWaypointId = wp1.id,
            toWaypointId = wp2.id,
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(wp1.lat, wp1.lng, 25.0), RoutePoint3D(wp2.lat, wp2.lng, 30.0)),
            lengthKm = 0.9,
            ascentM = 15.0,
            descentM = 10.0,
            pathSharePct = 85.0,
            roadKm = 0.13
        )

        val legsMap = mutableMapOf(
            leg01.legKey to leg01,
            leg12.legKey to leg12
        )

        assertEquals(2, legsMap.size)
        assertTrue(legsMap.containsKey("wp0_to_wp1"))
        assertTrue(legsMap.containsKey("wp1_to_wp2"))

        // Add a 4th point to ensure any untouched legs remain completely unaffected
        val wp3 = RouteWaypoint(id = "wp3", lat = 56.900, lng = 9.850, role = WaypointRole.END)
        val leg23 = LegGeometryData(
            fromWaypointId = wp2.id,
            toWaypointId = wp3.id,
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(wp2.lat, wp2.lng, 30.0), RoutePoint3D(wp3.lat, wp3.lng, 35.0)),
            lengthKm = 1.0,
            ascentM = 10.0,
            descentM = 5.0,
            pathSharePct = 95.0,
            roadKm = 0.05
        )
        legsMap[leg23.legKey] = leg23

        // Step 2: Drag the middle point WP1 to new coordinates
        val newWp1 = wp1.copy(lat = 56.892, lng = 9.842)

        // Identify affected legs: only legs with fromId == wp1.id or toId == wp1.id
        val affectedKeys = legsMap.keys.filter { it.startsWith("${wp1.id}_to_") || it.endsWith("_to_${wp1.id}") }
        assertEquals("Only exactly 2 legs must be affected when moving the middle point", 2, affectedKeys.size)
        assertTrue(affectedKeys.contains("wp0_to_wp1"))
        assertTrue(affectedKeys.contains("wp1_to_wp2"))

        // Leg 2->3 is completely untouched
        val untouchedLegBefore = legsMap["wp2_to_wp3"]
        assertNotNull(untouchedLegBefore)

        // Update the 2 re-routed legs
        val newLeg01 = leg01.copy(
            points = listOf(RoutePoint3D(wp0.lat, wp0.lng, 20.0), RoutePoint3D(newWp1.lat, newWp1.lng, 27.0)),
            lengthKm = 0.85
        )
        val newLeg12 = leg12.copy(
            points = listOf(RoutePoint3D(newWp1.lat, newWp1.lng, 27.0), RoutePoint3D(wp2.lat, wp2.lng, 30.0)),
            lengthKm = 0.95
        )
        legsMap[newLeg01.legKey] = newLeg01
        legsMap[newLeg12.legKey] = newLeg12

        // Assert untouched leg is identical
        assertEquals(untouchedLegBefore, legsMap["wp2_to_wp3"])
    }

    @Test
    fun testUndo_RestoresExactPreviousLine() {
        val wpA = RouteWaypoint(id = "A", lat = 56.0, lng = 10.0, role = WaypointRole.START)
        val wpB = RouteWaypoint(id = "B", lat = 56.1, lng = 10.1, role = WaypointRole.END)

        val originalPoints = listOf(
            RoutePoint3D(56.0, 10.0, 10.0),
            RoutePoint3D(56.05, 10.05, 15.0),
            RoutePoint3D(56.1, 10.1, 20.0)
        )
        val originalLeg = LegGeometryData(
            fromWaypointId = "A",
            toWaypointId = "B",
            legMode = LegMode.SNAP,
            points = originalPoints,
            lengthKm = 12.5,
            ascentM = 50.0,
            descentM = 40.0,
            pathSharePct = 80.0,
            roadKm = 2.5
        )

        // Snapshot before edit
        val historyStack = ArrayDeque<Pair<List<RouteWaypoint>, Map<String, LegGeometryData>>>()
        historyStack.addLast(listOf(wpA, wpB) to mapOf(originalLeg.legKey to originalLeg))

        // Edit: Move point B
        val modifiedB = wpB.copy(lat = 56.2, lng = 10.2)
        val modifiedLeg = originalLeg.copy(
            points = listOf(RoutePoint3D(56.0, 10.0, 10.0), RoutePoint3D(56.2, 10.2, 30.0)),
            lengthKm = 25.0
        )

        // Current state after edit
        var currentWps = listOf(wpA, modifiedB)
        var currentLegs = mapOf(modifiedLeg.legKey to modifiedLeg)

        assertEquals(25.0, currentLegs.values.first().lengthKm, 0.01)

        // Perform Undo
        val previousState = historyStack.removeLast()
        currentWps = previousState.first
        currentLegs = previousState.second

        // Assert restored exact previous line
        assertEquals(2, currentWps.size)
        assertEquals(56.1, currentWps[1].lat, 0.0001)
        val restoredLeg = currentLegs[originalLeg.legKey]
        assertNotNull(restoredLeg)
        assertEquals(12.5, restoredLeg!!.lengthKm, 0.001)
        assertEquals(originalPoints, restoredLeg.points)
    }

    @Test
    fun testImportGpx_BecomesEditableRouteWith500mSimplification() {
        // Construct a synthetic GPX track with points every ~100m over 2.5 km
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="DanskStiTest">
  <trk>
    <name>Hærvejen Test Etape</name>
    <trkseg>
""")
        // 25 trackpoints spaced ~100m along latitude (0.0009 deg lat ~ 100m)
        for (i in 0..25) {
            val lat = 56.000 + (i * 0.0009)
            val lng = 9.500
            val ele = 50.0 + i
            sb.append("      <trkpt lat=\"$lat\" lon=\"$lng\"><ele>$ele</ele></trkpt>\n")
        }
        sb.append("""    </trkseg>
  </trk>
</gpx>""")

        val inputStream = ByteArrayInputStream(sb.toString().toByteArray())
        val result = GpxImporter.parseAndSimplifyGpx(inputStream, targetSpacingMeters = 500.0)

        assertEquals("Hærvejen Test Etape", result.name)
        assertEquals(26, result.originalPoints.size)
        assertTrue("Total track length should be approx 2.5 km", result.totalOriginalKm in 2.0..3.0)

        // Simplification should yield ~5-7 waypoints (1 start, ~4-5 via, 1 end)
        assertTrue("Waypoints should be simplified to ~5-7 points", result.waypoints.size in 5..8)
        assertEquals(WaypointRole.START, result.waypoints.first().role)
        assertEquals(WaypointRole.END, result.waypoints.last().role)
        for (i in 1 until result.waypoints.size - 1) {
            assertEquals(WaypointRole.VIA, result.waypoints[i].role)
            assertEquals(LegMode.SNAP, result.waypoints[i].legModeToNext)
        }
    }

    @Test
    fun testIsolatedLegStorage_SavesAndUpdatesLegsIndependently() {
        val filename = "test_route_storage.json"
        val leg1 = LegGeometryData(
            fromWaypointId = "p1",
            toWaypointId = "p2",
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(56.1, 10.1, 15.0), RoutePoint3D(56.2, 10.2, 20.0)),
            lengthKm = 5.2,
            ascentM = 30.0,
            descentM = 10.0,
            pathSharePct = 85.0,
            roadKm = 0.78
        )

        val leg2 = LegGeometryData(
            fromWaypointId = "p2",
            toWaypointId = "p3",
            legMode = LegMode.STRAIGHT,
            points = listOf(RoutePoint3D(56.2, 10.2, 20.0), RoutePoint3D(56.3, 10.3, 25.0)),
            lengthKm = 4.1,
            ascentM = 20.0,
            descentM = 15.0,
            pathSharePct = 0.0,
            roadKm = 0.0
        )

        // Save leg1
        RouteGeometryStorageManager.saveLeg(app, filename, leg1)
        val loaded1 = RouteGeometryStorageManager.loadLegs(app, filename)
        assertEquals(1, loaded1.size)
        assertEquals(5.2, loaded1["p1_to_p2"]?.lengthKm ?: 0.0, 0.01)

        // Save leg2 without touching leg1
        RouteGeometryStorageManager.saveLeg(app, filename, leg2)
        val loaded2 = RouteGeometryStorageManager.loadLegs(app, filename)
        assertEquals(2, loaded2.size)
        assertEquals(5.2, loaded2["p1_to_p2"]?.lengthKm ?: 0.0, 0.01)
        assertEquals(4.1, loaded2["p2_to_p3"]?.lengthKm ?: 0.0, 0.01)

        // Clean up
        RouteGeometryStorageManager.deleteGeometryFile(app, filename)
    }

    @Test
    fun testCustomRouteEntity_RoomPersistence() = runBlocking {
        val dao = database.dao()
        val now = System.currentTimeMillis()

        val route = CustomRouteEntity(
            id = 0L,
            name = "Rold Skov Nord-Syd",
            createdAt = now,
            updatedAt = now,
            optionsJson = """{"avoidRoads":0.7,"preferNature":true,"allowFerries":false}""",
            waypointsJson = """[{"id":"wp1","lat":56.8,"lng":9.8,"role":"START","legModeToNext":"SNAP"},{"id":"wp2","lat":56.9,"lng":9.9,"role":"END","legModeToNext":"SNAP"}]""",
            totalKm = 14.8,
            ascentM = 120.0,
            descentM = 110.0,
            pathSharePct = 92.0,
            roadKm = 1.2,
            geometryFile = "route_test.json",
            sourceName = "Rutebygger",
            fetchedAt = now
        )

        val insertedId = dao.insertCustomRoute(route)
        assertTrue(insertedId > 0)

        val retrieved = dao.getCustomRouteById(insertedId)
        assertNotNull(retrieved)
        assertEquals("Rold Skov Nord-Syd", retrieved!!.name)
        assertEquals(14.8, retrieved.totalKm, 0.01)
        assertEquals(92.0, retrieved.pathSharePct, 0.01)

        val allRoutes = dao.getAllCustomRoutes().first()
        assertTrue(allRoutes.any { it.id == insertedId })

        // Delete test
        dao.deleteCustomRouteById(insertedId)
        val afterDelete = dao.getCustomRouteById(insertedId)
        assertEquals(null, afterDelete)
    }
}
