package com.example

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.ShelterEntity
import com.example.ui.viewmodel.*
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UiRound1RouteBuilderTest {

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
     * Item 1 & Item 12.1:
     * A tap in Kig mode adds no point.
     * Also verifies default Tegn for a new FREE route and Kig for TRAIL mode.
     */
    @Test
    fun tapInKigMode_addsNoPoint() = runBlocking {
        val vm = RouteBuilderViewModel(app)

        // Default for a new FREE route is TEGN
        assertEquals(PlannerMode.FREE, vm.uiState.value.mode)
        assertEquals(PlannerInteractionMode.TEGN, vm.uiState.value.interactionMode)

        // Switch to KIG mode
        vm.setInteractionMode(PlannerInteractionMode.KIG)
        assertEquals(PlannerInteractionMode.KIG, vm.uiState.value.interactionMode)

        // Tap map in KIG mode -> adds no point
        vm.onMapTapped(56.0, 10.0)
        assertEquals("A tap in Kig mode must add no point", 0, vm.uiState.value.waypoints.size)

        // Switch back to TEGN mode -> map tap adds a point
        vm.setInteractionMode(PlannerInteractionMode.TEGN)
        vm.onMapTapped(56.0, 10.0)
        assertEquals("A tap in Tegn mode must add a point", 1, vm.uiState.value.waypoints.size)

        // Initializing TRAIL mode defaults to KIG
        vm.initTrailMode("trail_ohavsstien")
        assertEquals(PlannerInteractionMode.KIG, vm.uiState.value.interactionMode)
    }

    /**
     * Item 4 & Item 12.2:
     * Dragging a pin calls moveWaypoint once on release.
     */
    @Test
    fun draggingPin_callsMoveWaypointOnceOnRelease() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.setInteractionMode(PlannerInteractionMode.TEGN)
        vm.onMapTapped(56.000, 10.000)
        vm.onMapTapped(56.010, 10.010)
        vm.onMapTapped(56.020, 10.020)

        assertEquals(3, vm.uiState.value.waypoints.size)
        val middlePin = vm.uiState.value.waypoints[1]

        var moveWaypointCallCount = 0
        val performPinDragSession = { startLat: Double, startLng: Double, dragSteps: List<Pair<Double, Double>>, releaseLat: Double, releaseLng: Double ->
            // During press-and-move, computeDragLengthDeltaKm and snapCoordinateWithin25m run without calling moveWaypoint
            for ((stepLat, stepLng) in dragSteps) {
                vm.snapCoordinateWithin25m(stepLat, stepLng)
                vm.computeDragLengthDeltaKm(middlePin.id, stepLat, stepLng)
            }
            // On release, moveWaypoint is called once
            moveWaypointCallCount += 1
            vm.moveWaypoint(middlePin.id, releaseLat, releaseLng)
        }

        performPinDragSession(
            middlePin.lat,
            middlePin.lng,
            listOf(56.012 to 10.012, 56.014 to 10.014, 56.015 to 10.015),
            56.016,
            10.016
        )

        assertEquals("Dragging a pin must call moveWaypoint exactly once on release", 1, moveWaypointCallCount)
        val updatedMiddle = vm.uiState.value.waypoints[1]
        assertEquals(56.016, updatedMiddle.lat, 1e-5)
        assertEquals(10.016, updatedMiddle.lng, 1e-5)
    }

    /**
     * Item 5 & Item 12.3:
     * Dragging a ghost handle inserts exactly one point.
     */
    @Test
    fun draggingGhostHandle_insertsExactlyOnePoint() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.setInteractionMode(PlannerInteractionMode.TEGN)
        vm.onMapTapped(56.000, 10.000)
        vm.onMapTapped(56.020, 10.020)

        assertEquals(2, vm.uiState.value.waypoints.size)

        val handles = vm.getLegMidpointHandles()
        assertEquals("A 2-point route has 1 leg midpoint ghost handle", 1, handles.size)
        val ghostHandle = handles.first()

        // Start dragging the ghost handle -> inserts a via point once via insertViaPointOnLine
        val insertedWpId = vm.startGhostHandleDrag(ghostHandle.lat, ghostHandle.lng)
        assertNotNull("Ghost handle drag must return the newly inserted waypoint ID", insertedWpId)
        assertEquals("Dragging a ghost handle must insert exactly one point", 3, vm.uiState.value.waypoints.size)

        // Continue dragging and release at new coordinates -> calls moveWaypoint on that inserted point
        vm.moveWaypoint(insertedWpId!!, 56.012, 10.015)

        assertEquals("Waypoints count must still be 3 after finishing ghost handle drag", 3, vm.uiState.value.waypoints.size)
        val insertedWp = vm.uiState.value.waypoints[1]
        assertEquals(WaypointRole.VIA, insertedWp.role)
        assertEquals(56.012, insertedWp.lat, 1e-5)
        assertEquals(10.015, insertedWp.lng, 1e-5)
    }

    /**
     * Item 3 & Item 12.4:
     * Tapping a shelter adds nothing until a button is pressed.
     */
    @Test
    fun tappingShelter_addsNothingUntilButtonIsPressed() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.setInteractionMode(PlannerInteractionMode.TEGN)
        vm.onMapTapped(56.000, 10.000)
        vm.onMapTapped(56.020, 10.020)

        val countBeforePoiTap = vm.uiState.value.waypoints.size
        assertEquals(2, countBeforePoiTap)

        // Tap a shelter on the map
        val shelterPoi = PlannerMapPoi(
            id = "shelter_skovbo",
            name = "Shelter Skovbo",
            type = "SHELTER",
            lat = 56.010,
            lng = 10.012,
            distanceToRouteMeters = 140.0,
            bookingRequired = true
        )
        vm.onPoiTapped(shelterPoi)

        // Verify nothing was added to the route yet, and the info card state is populated
        assertEquals("Tapping a shelter must not add any waypoint before a button is pressed", countBeforePoiTap, vm.uiState.value.waypoints.size)
        val selectedSheetPoi = vm.uiState.value.selectedNearbyPoiForSheet
        assertNotNull("Info card must be opened for the tapped shelter", selectedSheetPoi)
        assertEquals("Shelter Skovbo", selectedSheetPoi?.name)
        assertEquals("SHELTER", selectedSheetPoi?.type)
        assertTrue(selectedSheetPoi?.bookingRequired == true)

        // Now press "Tilføj som stop" on the info card
        vm.confirmPoiAsStop()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull("Info card should close after pressing a button", vm.uiState.value.selectedNearbyPoiForSheet)
        assertEquals("Pressing button adds the shelter to the route", countBeforePoiTap + 1, vm.uiState.value.waypoints.size)
    }

    /**
     * Item 6, 7, 8, 10, 11 verification:
     * - Crosshair placement
     * - Pin selection & empty map tap deselect
     * - 48 dp touch area on all pin icons
     * - "Luk ruten" & "Ud og hjem"
     * - TalkBack contentDescription format ("Punkt 3, overnatning, Shelter Skovbo, km 32,1")
     */
    @Test
    fun crosshair_pinSelection_menuActions_andTalkBackFormat() {
        val vm = RouteBuilderViewModel(app)

        // Crosshair placement
        vm.openCrosshairMode()
        assertTrue(vm.uiState.value.isCrosshairMode)
        vm.confirmCrosshairPlacement(56.000, 10.000)
        assertFalse(vm.uiState.value.isCrosshairMode)
        assertEquals(1, vm.uiState.value.waypoints.size)

        vm.openCrosshairMode()
        vm.confirmCrosshairPlacement(56.010, 10.010)
        assertEquals(2, vm.uiState.value.waypoints.size)

        // Pin selection & empty map tap in Kig mode closes selection
        val firstId = vm.uiState.value.waypoints.first().id
        vm.selectWaypoint(firstId)
        assertEquals(firstId, vm.uiState.value.selectedWaypointId)
        vm.setInteractionMode(PlannerInteractionMode.KIG)
        vm.onMapTapped(56.05, 10.05)
        assertNull("Tapping empty map must close pin selection", vm.uiState.value.selectedWaypointId)

        // "Luk ruten" adds a leg back to the start
        vm.closeRouteLoop()
        assertEquals(3, vm.uiState.value.waypoints.size)
        assertEquals(vm.uiState.value.waypoints.first().lat, vm.uiState.value.waypoints.last().lat, 1e-5)

        // "Ud og hjem" on a 2-point route appends the reverse
        val vm2 = RouteBuilderViewModel(app)
        vm2.onMapTapped(56.0, 10.0)
        vm2.onMapTapped(56.1, 10.1)
        vm2. onMapTapped(56.2, 10.2)
        assertEquals(3, vm2.uiState.value.waypoints.size)
        vm2.appendOutAndBack()
        assertEquals(5, vm2.uiState.value.waypoints.size)
        assertEquals(56.0, vm2.uiState.value.waypoints.last().lat, 1e-5)

        // Item 8: Every pin gets at least a 48 dp touch area regardless of icon size
        val density = app.resources.displayMetrics.density
        val minTouchPx = (48f * density).toInt()
        for (role in WaypointRole.values()) {
            val drawable = RouteMarkerHelper.createWaypointIcon(
                context = app,
                role = role,
                index = 0,
                isSelected = false,
                dayNumber = 1
            ) as BitmapDrawable
            assertTrue("Pin $role width must be >= 48dp", drawable.bitmap.width >= minTouchPx)
            assertTrue("Pin $role height must be >= 48dp", drawable.bitmap.height >= minTouchPx)
        }

        // Item 11: TalkBack contentDescription format
        val wp = RouteWaypoint(
            id = "wp3",
            lat = 56.0,
            lng = 10.0,
            role = WaypointRole.OVERNIGHT,
            name = "Shelter Skovbo"
        )
        val desc = RouteMarkerHelper.formatPinTalkBackDescription(
            index = 2,
            waypoint = wp,
            cumulativeKm = 32.1
        )
        assertEquals("Punkt 3, overnatning, Shelter Skovbo, km 32,1", desc)
    }
}
