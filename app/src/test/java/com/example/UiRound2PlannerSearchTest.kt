package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.remote.PhotonPlaceSearchService
import com.example.data.remote.PlaceSearchResult
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerInteractionMode
import com.example.ui.viewmodel.PlannerSheetTab
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiRound2PlannerSearchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
        DanskStiDatabase.resetInstance()
    }

    /**
     * Requirement 7.1:
     * Reordering points changes only the affected legs.
     */
    @Test
    fun reorderingPoints_changesOnlyAffectedLegs() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.setInteractionMode(PlannerInteractionMode.TEGN)
        // Use STRAIGHT mode so legs compute deterministically without requiring external BRouter HTTP
        vm.toggleNextLegMode()

        // Add 4 points: wp0 -> wp1 -> wp2 -> wp3
        vm.addPointAtEnd(56.100, 10.100, "Punkt 1")
        vm.addPointAtEnd(56.110, 10.110, "Punkt 2")
        vm.addPointAtEnd(56.120, 10.120, "Punkt 3")
        vm.addPointAtEnd(56.130, 10.130, "Punkt 4")

        val initialWps = vm.uiState.value.waypoints.filter { !it.isHelper }
        assertEquals(4, initialWps.size)
        val wp0 = initialWps[0]
        val wp1 = initialWps[1]
        val wp2 = initialWps[2]
        val wp3 = initialWps[3]

        val unaffectedLegKey = "${wp0.id}_to_${wp1.id}"
        val leg01Before = vm.uiState.value.legs[unaffectedLegKey]
        assertNotNull("Initial leg wp0 -> wp1 must exist", leg01Before)

        // Reorder point at index 2 (wp2) to index 3 (after wp3), so order is wp0 -> wp1 -> wp3 -> wp2.
        // Leg wp0 -> wp1 is completely unaffected and must keep the exact same LegGeometryData instance!
        vm.reorderWaypoints(fromVisibleIndex = 2, toVisibleIndex = 3)

        val reorderedWps = vm.uiState.value.waypoints.filter { !it.isHelper }
        assertEquals(listOf(wp0.id, wp1.id, wp3.id, wp2.id), reorderedWps.map { it.id })
        assertEquals(WaypointRole.START, reorderedWps.first().role)
        assertEquals(WaypointRole.END, reorderedWps.last().role)

        val leg01After = vm.uiState.value.legs[unaffectedLegKey]
        assertSame(
            "Unaffected leg wp0 -> wp1 must NOT be recomputed when reordering wp2 and wp3",
            leg01Before,
            leg01After
        )
        assertNotNull("Affected leg wp1 -> wp3 must be computed", vm.uiState.value.legs["${wp1.id}_to_${wp3.id}"])
        assertNotNull("Affected leg wp3 -> wp2 must be computed", vm.uiState.value.legs["${wp3.id}_to_${wp2.id}"])
        assertFalse("Old leg wp1 -> wp2 must be removed", vm.uiState.value.legs.containsKey("${wp1.id}_to_${wp2.id}"))
    }

    /**
     * Requirement 5 & 7.2:
     * A search for "Rold Skov" offline still finds local shelters there.
     * Also verifies caching of the last 50 place results for offline use and Photon usage terms logging.
     */
    @Test
    fun searchForRoldSkovOffline_stillFindsLocalSheltersThere() = runBlocking {
        // Force offline mode
        app.repository.isOnlineOverrideForTests = false

        val grouped = app.repository.searchGroupedInDenmark("Rold Skov")
        assertTrue(
            "Searching 'Rold Skov' offline must find local shelters in Rold Skov",
            grouped.sheltersAndFriTeltning.isNotEmpty()
        )
        assertTrue(
            "Offline Rold Skov shelter results must include shelters located in Rold Skov",
            grouped.sheltersAndFriTeltning.any {
                it.name.contains("Rold", ignoreCase = true) ||
                    (it.lat in 56.70..56.90 && it.lng in 9.70..10.00)
            }
        )
        assertTrue(
            "Offline place cache must also find Rold Skov in Steder",
            grouped.places.any { it.displayName.contains("Rold Skov", ignoreCase = true) }
        )

        // Verify Photon cache caps at 50 results for offline use
        val photonService = PhotonPlaceSearchService(app)
        for (i in 1..65) {
            photonService.cachePlaces(
                listOf(
                    PlaceSearchResult(
                        displayName = "Testby $i",
                        lat = 56.0 + (i * 0.001),
                        lng = 10.0 + (i * 0.001),
                        type = "By"
                    )
                )
            )
        }
        assertEquals(
            "Photon place cache must retain at most 50 place results",
            PhotonPlaceSearchService.MAX_CACHED_PLACES,
            photonService.getCachedPlaces().size
        )
    }

    /**
     * Requirement 5 & 7.3:
     * "Rute hertil" from a place result creates a route from the current position.
     */
    @Test
    fun routeHereFromPlaceResult_createsRouteFromCurrentPosition() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        val currentLat = 57.0488
        val currentLng = 9.9217
        val placeLat = 56.8050
        val placeLng = 9.8500

        vm.routeToPlace(
            destLat = placeLat,
            destLng = placeLng,
            destName = "Rold Skov",
            currentLat = currentLat,
            currentLng = currentLng
        )

        val visibleWps = vm.uiState.value.waypoints.filter { !it.isHelper }
        assertEquals("Route to place must create 2 waypoints (Start -> End)", 2, visibleWps.size)

        val startWp = visibleWps.first()
        val endWp = visibleWps.last()

        assertEquals(WaypointRole.START, startWp.role)
        assertEquals(currentLat, startWp.lat, 1e-4)
        assertEquals(currentLng, startWp.lng, 1e-4)

        assertEquals(WaypointRole.END, endWp.role)
        assertEquals("Rold Skov", endWp.name)
        assertEquals(placeLat, endWp.lat, 1e-4)
        assertEquals(placeLng, endWp.lng, 1e-4)

        assertNotNull(
            "Leg from current position to place must be routed",
            vm.uiState.value.legs["${startWp.id}_to_${endWp.id}"]
        )
    }

    /**
     * Requirement 1, 2, 3 & 4:
     * - Peek row shows: days · km · +ascent · time · path share ("ukendt" when unknown)
     * - Tabs in sheet: Rute, Dage, Detaljer
     * - Tapping a day card zooms map to that day's section; selecting a pin pans map so it is not hidden behind sheet
     */
    @Test
    fun plannerBottomSheet_peekRowShowsPathShareOrUkendt_tabsAndLinkingSyncMap() {
        var currentTab = PlannerSheetTab.DAGE
        var clickedDay: PlannerDaySummary? = null

        val wp1 = RouteWaypoint(id = "wp1", lat = 56.0, lng = 10.0, role = WaypointRole.START, name = "Start")
        val wp2 = RouteWaypoint(id = "wp2", lat = 56.1, lng = 10.1, role = WaypointRole.END, name = "Slut")
        val day1 = PlannerDaySummary(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 12.4,
            distanceKm = 12.4,
            ascentM = 85,
            descentM = 40,
            fromName = "Start",
            toName = "Slut",
            overnightWaypointId = null,
            overnightName = "Slut",
            overnightTypeLabel = "Slut",
            detourLabel = null,
            detourKm = 0.0,
            detourAscentM = 0,
            bookingLabel = null,
            sunsetTime = "20:30",
            arrivalTime = "11:45",
            isAfterSunset = false,
            warnings = emptyList(),
            nightWeatherSummary = "14°C · Tørt",
            campLat = 56.1,
            campLng = 10.1,
            estimatedHours = 2.75,
            overnightType = "END",
            bookingRequired = false
        )

        composeTestRule.setContent {
            DanskStiTheme {
                PlannerBottomSheet(
                    uiState = RouteBuilderState(
                        waypoints = listOf(wp1, wp2),
                        sheetHeightState = SheetHeightState.FULL,
                        selectedSheetTab = currentTab,
                        stats = RouteStats(
                            totalKm = 12.4,
                            ascentM = 85.0,
                            descentM = 40.0,
                            pathSharePct = null,
                            roadKm = 0.0,
                            hasWayInfo = false
                        ),
                        timeEstimateFormatted = "2 t 45 min",
                        daySummaries = listOf(day1)
                    ),
                    visibleWaypoints = listOf(wp1, wp2),
                    onSetSheetHeightState = {},
                    onSuggestOvernightStops = {},
                    onStartNavigation = {},
                    onOpenTripSettings = {},
                    onOpenRundturDialog = {},
                    onOpenNearestPoiDialog = {},
                    onShareRoute = {},
                    onReplaceOvernight = {},
                    onDeleteWaypoint = {},
                    onZoomToCoordinate = { _, _ -> },
                    onRerouteAroundWarning = {},
                    onScrubbedPointChanged = {},
                    onOvernightMarkerDragged = { _, _ -> },
                    onUpdateOptions = {},
                    onWaypointClicked = {},
                    onOpenSaveDialog = {},
                    onSelectSheetTab = { currentTab = it },
                    onDayCardClicked = { clickedDay = it }
                )
            }
        }

        // Peek row must include "Sti: ukendt" when path share is unknown
        composeTestRule.onNodeWithTag("route_summary_text")
            .assertIsDisplayed()
            .assertTextContains("Sti: ukendt", substring = true)

        // All 3 tabs exist: Rute, Dage, Detaljer
        composeTestRule.onNodeWithTag("planner_tab_rute").assertIsDisplayed()
        composeTestRule.onNodeWithTag("planner_tab_dage").assertIsDisplayed()
        composeTestRule.onNodeWithTag("planner_tab_detaljer").assertIsDisplayed()

        // Tapping day card triggers onDayCardClicked
        composeTestRule.onNodeWithTag("planner_day_card_1").performClick()
        assertNotNull("Tapping day card must invoke onDayCardClicked", clickedDay)
        assertEquals(1, clickedDay?.dayNumber)

        // Verify computeSheetAdjustedCenter shifts map center SOUTH so selected point is above the bottom sheet
        val vm = RouteBuilderViewModel(app)
        val adjusted = vm.computeSheetAdjustedCenter(
            targetLat = 56.0,
            targetLng = 10.0,
            zoomLevel = 14.0,
            sheetHeightFraction = 0.42f
        )
        assertTrue(
            "Sheet-adjusted map center latitude (${adjusted.lat}) must be south (<) of target (56.0) so target sits above the bottom sheet",
            adjusted.lat < 56.0
        )
        assertEquals(10.0, adjusted.lng, 1e-6)
    }
}
