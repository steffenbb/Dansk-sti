package com.example

import android.app.Notification
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripStage
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.data.remote.PlaceSearchResult
import com.example.data.repository.DanskStiRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.components.DropPinBottomSheet
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.computeOffRouteDirectionOrArrow
import com.example.ui.screens.planner.PlannerDragGestureSession
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.SunCalculator
import com.example.util.access.AccessGeometryUtils
import com.example.util.access.AccessWarningType
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound9ATest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp
    private lateinit var repository: DanskStiRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repository = app.repository
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
    }

    @After
    fun tearDown() {
        repository.photonPlaceSearchService.customPhotonFetcher = null
        repository.photonPlaceSearchService.photonRequestCount.set(0)
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
    }

    // ============================================================================================
    // 1. NavigationScreen arrivalClock: arrival = current time + (km left today / effective pace).
    //    Remove the 09:00 base. Use the same function for top card, stats strip and notification.
    // ============================================================================================
    @Test
    fun item1_arrivalClock_usesCurrentTimePlusRemainingOverEffectivePaceEverywhere() {
        val cphZone = ZoneId.of("Europe/Copenhagen")
        // Fixed reference time: 14:15 in Europe/Copenhagen (not 09:00!)
        val refTime = ZonedDateTime.of(2026, 6, 21, 14, 15, 0, 0, cphZone)
        val refMillis = refTime.toInstant().toEpochMilli()

        // 9.0 km left today at 4.5 km/h = +2 hours -> 16:15 (with old 09:00 base it would have been 11:00)
        val computed = SunCalculator.computeArrivalClock(
            remainingTodayKm = 9.0,
            speedKmh = 4.5,
            configuredPaceKmh = 4.0,
            nowMillis = refMillis,
            zoneId = cphZone
        )
        assertEquals("16:15", computed)
        assertNotEquals("11:00", computed)

        // When standing still (speed < 1.5 km/h), uses configuredPaceKmh (4.0 km/h): 8.0 km / 4.0 = +2h -> 16:15
        val computedStandingStill = SunCalculator.computeArrivalClock(
            remainingTodayKm = 8.0,
            speedKmh = 0.0,
            configuredPaceKmh = 4.0,
            nowMillis = refMillis,
            zoneId = cphZone
        )
        assertEquals("16:15", computedStandingStill)

        // Verify NavigationScreen top card, stats strip, and notification all match the current clock
        val stage = TripStage(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 8.0,
            stageKm = 8.0,
            stopName = "Shelter Mål",
            stopType = "SHELTER",
            stopLat = 56.072,
            stopLng = 9.5,
            detourMeters = 0.0,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        val planned = PlannedRoute(
            planId = 1L,
            trailId = "trail_ohavsstien",
            trailName = "Øhavsstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 8.0,
            totalWalkKm = 8.0,
            points = listOf(
                RoutePoint(lat = 56.0, lng = 9.5, ele = 20.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 56.072, lng = 9.5, ele = 25.0, walkKm = 8.0, trailKm = 8.0)
            ),
            stages = listOf(stage)
        )
        val liveState = NavigationLiveState(
            isTracking = true,
            planId = 1L,
            trailName = "Øhavsstien",
            currentLat = 56.0,
            currentLng = 9.5,
            speedKmh = 4.0,
            totalDistanceWalkedKm = 0.0,
            distanceRemainingTodayKm = 8.0,
            currentDay = 1,
            currentStage = stage,
            plannedRoute = planned
        )
        NavigationTrackingService.updateLiveStateForTest(liveState)

        val expectedClockNow = SunCalculator.computeArrivalClock(
            remainingTodayKm = 8.0,
            speedKmh = 4.0,
            configuredPaceKmh = 4.0
        )

        // Check notification text from NavigationTrackingService
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val notification = service.buildNotification(
            trailName = "Øhavsstien",
            stageName = "Shelter Mål",
            speedKmh = 4.0,
            remainingTodayKm = 8.0,
            remainingTripKm = 8.0,
            isOffRoute = false,
            offRouteMeters = 0.0
        )
        val notifText = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(
            "Expected notification text '$notifText' to contain 'kl. $expectedClockNow'",
            notifText.contains("kl. $expectedClockNow")
        )

        val navVm = NavigationViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                NavigationScreen(
                    viewModel = navVm,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Both top card headline and bottom stats strip show the exact same arrival time
        composeTestRule.onNodeWithTag("nav_top_headline_text", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stat_arrival_time", useUnmergedTree = true).assertIsDisplayed()
        val matchingArrivalNodes = composeTestRule.onAllNodesWithText(expectedClockNow, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(
            "Expected both top card and stats strip to display arrival time $expectedClockNow",
            matchingArrivalNodes.size >= 2
        )
    }

    // ============================================================================================
    // 2. Sunset: remove every "20:30" fallback. Add SunCalculator: on-device sunrise/sunset with
    //    the NOAA solar algorithm for a date, latitude and longitude, in local time
    //    (Europe/Copenhagen, DST aware). Unit test: Copenhagen 21 June and 21 December within
    //    2 minutes of published values. Works in airplane mode.
    // ============================================================================================
    @Test
    fun item2_sunCalculator_copenhagenSolsticesWithin2Minutes_andAirplaneModeWarningWorks() {
        val cphLat = 55.6761
        val cphLng = 12.5683
        val cphZone = ZoneId.of("Europe/Copenhagen")

        // Summer solstice: 21 June at Copenhagen (published sunset ~21:58 CEST, sunrise ~04:25 CEST)
        val summerDate = LocalDate.of(2026, 6, 21)
        val summerTimes = SunCalculator.calculateSunTimes(summerDate, cphLat, cphLng, cphZone)
        val summerSunsetDiff = abs(summerTimes.sunsetMinutesOfDay - (21 * 60 + 58))
        val summerSunriseDiff = abs(summerTimes.sunriseMinutesOfDay - (4 * 60 + 25))
        assertTrue(
            "Expected Copenhagen 21 June sunset (${summerTimes.sunset}) within 2 min of 21:58, diff=$summerSunsetDiff",
            summerSunsetDiff <= 2
        )
        assertTrue(
            "Expected Copenhagen 21 June sunrise (${summerTimes.sunrise}) within 2 min of 04:25, diff=$summerSunriseDiff",
            summerSunriseDiff <= 2
        )

        // Winter solstice: 21 December at Copenhagen (published sunset ~15:38 CET, sunrise ~08:37 CET)
        val winterDate = LocalDate.of(2026, 12, 21)
        val winterTimes = SunCalculator.calculateSunTimes(winterDate, cphLat, cphLng, cphZone)
        val winterSunsetDiff = abs(winterTimes.sunsetMinutesOfDay - (15 * 60 + 38))
        val winterSunriseDiff = abs(winterTimes.sunriseMinutesOfDay - (8 * 60 + 37))
        assertTrue(
            "Expected Copenhagen 21 December sunset (${winterTimes.sunset}) within 2 min of 15:38, diff=$winterSunsetDiff",
            winterSunsetDiff <= 2
        )
        assertTrue(
            "Expected Copenhagen 21 December sunrise (${winterTimes.sunrise}) within 2 min of 08:37, diff=$winterSunriseDiff",
            winterSunriseDiff <= 2
        )

        // Verify no "20:30" fallback exists in source files
        val filesToCheck = listOf(
            "src/main/java/com/example/service/NavigationTrackingService.kt",
            "src/main/java/com/example/ui/viewmodel/NavigationViewModel.kt",
            "src/main/java/com/example/ui/viewmodel/RouteBuilderViewModel.kt",
            "src/main/java/com/example/util/access/AccessGeometryUtils.kt",
            "src/main/java/com/example/util/StagePlanner.kt",
            "src/main/java/com/example/util/routing/RouteSmartTools.kt",
            "src/main/java/com/example/ui/screens/NavigationScreen.kt"
        )
        for (relPath in filesToCheck) {
            val file = File(relPath).takeIf { it.exists() } ?: File("app/$relPath")
            assertTrue("Expected file ${file.path} to exist", file.exists())
            val content = file.readText()
            assertFalse(
                "Expected ${file.name} not to contain hard-coded \"20:30\" fallback",
                content.contains("\"20:30\"")
            )
        }

        // Verify airplane mode (offline, no Open-Meteo) sunset warning works using on-device SunCalculator
        val privateForestPolygon = AccessPolygon(
            id = "priv_forest_1",
            name = "Privat Skov",
            type = AccessLandType.PRIVATE_FOREST,
            coordinates = listOf(
                LatLng(55.60, 12.50),
                LatLng(55.60, 12.65),
                LatLng(55.75, 12.65),
                LatLng(55.75, 12.50)
            ),
            source = "TEST"
        )
        val wpStart = RouteWaypoint(lat = 55.60, lng = 12.50, role = WaypointRole.START)
        val wpEnd = RouteWaypoint(lat = 55.68, lng = 12.58, role = WaypointRole.END)
        val legKey = "${wpStart.id}_to_${wpEnd.id}"
        // Segment from (55.60, 12.50) to (55.68, 12.58) is ~10.2 km; starting at 14:30 in winter reaches it after ~15:38 winter sunset
        val legData = LegGeometryData(
            fromWaypointId = wpStart.id,
            toWaypointId = wpEnd.id,
            legMode = LegMode.SNAP,
            points = listOf(
                RoutePoint3D(55.60, 12.50, 10.0),
                RoutePoint3D(55.68, 12.58, 10.0)
            ),
            lengthKm = 10.2,
            ascentM = 0.0,
            descentM = 0.0,
            pathSharePct = 100.0,
            roadKm = 0.0,
            sourceName = "Test"
        )
        val warnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = listOf(wpStart, wpEnd),
            legs = mapOf(legKey to legData),
            accessPolygons = listOf(privateForestPolygon),
            startTimeStr = "14:30",
            pacingSpeedKmh = 4.0,
            sunsetTimeStr = SunCalculator.calculateSunset(winterDate, cphLat, cphLng, cphZone)
        )
        val afterSunsetWarning = warnings.find { it.type == AccessWarningType.BEFORE_06_OR_AFTER_SUNSET }
        assertNotNull("Expected private forest after-sunset warning in airplane mode without Open-Meteo", afterSunsetWarning)
        assertTrue(
            "Expected warning message '${afterSunsetWarning!!.message}' to include on-device winter sunset ${winterTimes.sunset}",
            afterSunsetWarning.message.contains(winterTimes.sunset)
        )
    }

    // ============================================================================================
    // 3. Kig mode: only plain map taps are ignored. addPointAtEnd and every action from the
    //    long-press sheet, the search results, the POI card and the trail chooser must work in
    //    both modes. Unit test: in Kig mode, "Tilføj som næste punkt" adds exactly one point.
    // ============================================================================================
    @Test
    fun item3_kigMode_plainTapIgnored_tilfoejSomNaestePunktAddsExactlyOnePoint() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.setInteractionMode(PlannerInteractionMode.KIG)
        assertEquals(PlannerInteractionMode.KIG, vm.uiState.value.interactionMode)

        // 1. Plain map tap in Kig mode is ignored (0 points)
        vm.onMapTapped(56.10, 9.50)
        assertEquals(0, vm.uiState.value.visibleWaypoints.size)

        // 2. Long-press sheet "Tilføj som næste punkt" in Kig mode adds exactly 1 point
        var dropPinInvokedCount = 0
        composeTestRule.setContent {
            DanskStiTheme {
                DropPinBottomSheet(
                    point = GeoPoint(56.1234, 9.5678),
                    userLocation = null,
                    onDismiss = {},
                    onRouteHere = { _, _, _ -> },
                    onAddToRoute = { lat, lng, label ->
                        dropPinInvokedCount++
                        vm.addPointAtEnd(lat, lng, label)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drop_pin_add_next_point_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tilføj som næste punkt").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drop_pin_add_next_point_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, dropPinInvokedCount)
        assertEquals(
            "In Kig mode, 'Tilføj som næste punkt' must add exactly one point",
            1,
            vm.uiState.value.visibleWaypoints.size
        )

        // 3. Search result action ("Tilføj som næste punkt" / "Rute hertil") works in Kig mode
        vm.addPointAtEnd(56.15, 9.60, "Silkeborg")
        assertEquals(2, vm.uiState.value.visibleWaypoints.size)

        // 4. POI card action (addDetourPoiToRoute) works in Kig mode
        vm.addDetourPoiToRoute(56.13, 9.58, "Shelter Skovro", WaypointRole.OVERNIGHT)
        assertEquals(3, vm.uiState.value.visibleWaypoints.size)

        // 5. Trail chooser (initTrailMode) works in Kig mode
        vm.initTrailMode("trail_ohavsstien")
        assertEquals("trail_ohavsstien", vm.uiState.value.baseTrailId)
    }

    // ============================================================================================
    // 4. Off-route arrow: read the device heading from the rotation vector sensor.
    //    In north-up mode rotate the arrow by the absolute bearing to the route point ahead;
    //    in heading-up mode by (bearing - device heading).
    //    Without a heading, show the direction as text ("mod NØ") and no arrow.
    // ============================================================================================
    @Test
    fun item4_offRouteArrow_northUp_headingUp_andNoHeadingTextFallback() {
        // 1. Without a heading: arrow rotation is null, direction text is "mod NØ" for 45°
        val (noHeadingArrow, noHeadingText) = computeOffRouteDirectionOrArrow(
            bearingToPointAhead = 45f,
            deviceHeadingDegrees = null,
            mapMode = NavigationMapMode.FOLLOW_NORTH_UP
        )
        assertNull("Without a heading, arrow rotation must be null", noHeadingArrow)
        assertEquals("mod NØ", noHeadingText)

        // 2. North-up mode with heading: arrow rotates by absolute bearing to route point ahead (60°)
        val (northUpArrow, northUpText) = computeOffRouteDirectionOrArrow(
            bearingToPointAhead = 60f,
            deviceHeadingDegrees = 150f,
            mapMode = NavigationMapMode.FOLLOW_NORTH_UP
        )
        assertNotNull(northUpArrow)
        assertEquals(60f, northUpArrow!!, 0.01f)
        assertEquals("mod NØ", northUpText)

        // 3. Heading-up mode with heading: arrow rotates by (bearing - device heading)
        // e.g. bearing 45°, device heading 10° -> +35°
        val (headingUpArrow, _) = computeOffRouteDirectionOrArrow(
            bearingToPointAhead = 45f,
            deviceHeadingDegrees = 10f,
            mapMode = NavigationMapMode.FOLLOW_HEADING_UP
        )
        assertNotNull(headingUpArrow)
        assertEquals(35f, headingUpArrow!!, 0.01f)

        // Verify UI rendering in NavigationScreen when off-route without heading vs with heading
        val planId = 99L
        val offRouteStateNoHeading = NavigationLiveState(
            isTracking = true,
            planId = planId,
            trailName = "Hærvejen",
            currentLat = 56.01,
            currentLng = 9.52,
            isOffTrailWarning = true,
            offTrailDistanceMeters = 140.0,
            bearingToPointAhead = 45f,
            deviceHeadingDegrees = null
        )
        NavigationTrackingService.updateLiveStateForTest(offRouteStateNoHeading)
        val navVm = NavigationViewModel(app)
        navVm.updateDeviceHeading(null)

        composeTestRule.setContent {
            DanskStiTheme {
                NavigationScreen(
                    viewModel = navVm,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Without heading: shows "mod NØ" and NO arrow
        composeTestRule.onNodeWithTag("nav_off_route_direction_text", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("mod NØ", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("nav_off_route_arrow", useUnmergedTree = true).fetchSemanticsNodes().size
        )

        // Now provide a device heading from rotation vector sensor: arrow is shown
        navVm.updateDeviceHeading(10f)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_off_route_arrow", useUnmergedTree = true).assertIsDisplayed()
    }

    // ============================================================================================
    // 5. routeMeBack: if routing fails, remove the temporary line and show
    //    "Kunne ikke beregne vej tilbage". Never leave a straight line on the map.
    // ============================================================================================
    @Test
    fun item5_routeMeBack_onFailure_removesLineAndShowsErrorMessage() = runBlocking {
        val planId = 77L
        val routePts = listOf(
            RoutePoint(lat = 56.0, lng = 9.5, ele = 20.0, walkKm = 0.0, trailKm = 0.0),
            RoutePoint(lat = 56.05, lng = 9.5, ele = 25.0, walkKm = 5.0, trailKm = 5.0)
        )
        val planned = PlannedRoute(
            planId = planId,
            trailId = "trail_ohavsstien",
            trailName = "Øhavsstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 5.0,
            totalWalkKm = 5.0,
            points = routePts,
            stages = emptyList()
        )
        NavigationTrackingService.updateLiveStateForTest(
            NavigationLiveState(
                isTracking = true,
                planId = planId,
                trailName = "Øhavsstien",
                currentLat = 56.01,
                currentLng = 9.52,
                isOffTrailWarning = true,
                offTrailDistanceMeters = 150.0,
                plannedRoute = planned
            )
        )

        val navVm = NavigationViewModel(app)
        // Simulate routing failure (returns null)
        navVm.customBackToRouteRouter = { _, _ -> null }

        navVm.routeMeBack()
        val deadline = System.currentTimeMillis() + 3000L
        while (navVm.isRoutingBackToRoute.value && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(20)
        }

        assertTrue(
            "Expected backToRoutePoints to be empty when routing fails (never leave a straight line)",
            navVm.backToRoutePoints.value.isEmpty()
        )
        assertEquals("Kunne ikke beregne vej tilbage", navVm.routeBackErrorMessage.value)
    }

    // ============================================================================================
    // 6. Place search: debounce 400 ms, use collectLatest, and make exactly one Photon request
    //    per query (searchPlacesInDenmark must reuse the grouped result instead of calling it again).
    // ============================================================================================
    @Test
    fun item6_placeSearch_debounce400msCollectLatest_andSinglePhotonRequestPerQuery() = runBlocking {
        repository.photonPlaceSearchService.photonRequestCount.set(0)
        repository.photonPlaceSearchService.customPhotonFetcher = { query ->
            listOf(
                PlaceSearchResult(
                    displayName = "Silkeborg ($query)",
                    lat = 56.1697,
                    lng = 9.5451,
                    type = "By",
                    subtitle = "By · Midtjylland",
                    osmKey = "place",
                    osmValue = "city"
                )
            )
        }

        val mainLooperShadow = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        val vm = RouteBuilderViewModel(app)
        mainLooperShadow.idle()

        // Rapidly type intermediate queries within 400 ms window
        vm.setPlannerSearchQuery("Si")
        mainLooperShadow.idleFor(java.time.Duration.ofMillis(100))
        vm.setPlannerSearchQuery("Silk")
        mainLooperShadow.idleFor(java.time.Duration.ofMillis(100))
        vm.setPlannerSearchQuery("Silkeborg")
        mainLooperShadow.idleFor(java.time.Duration.ofMillis(100))

        // Before 400 ms has elapsed since the last keystroke, Photon should not have been called yet
        assertEquals(0, repository.photonPlaceSearchService.photonRequestCount.get())

        // Advance MainLooper past the 400 ms debounce window and wait for IO completion
        mainLooperShadow.idleFor(java.time.Duration.ofMillis(450))
        val deadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.plannerSearchResults.places.isEmpty() && System.currentTimeMillis() < deadline) {
            mainLooperShadow.idleFor(java.time.Duration.ofMillis(50))
            delay(20)
        }

        assertFalse("Expected place search results for 'Silkeborg'", vm.uiState.value.plannerSearchResults.places.isEmpty())
        assertEquals(
            "Expected exactly 1 Photon request for the debounced query (reusing grouped result in searchPlacesInDenmark)",
            1,
            repository.photonPlaceSearchService.photonRequestCount.get()
        )
    }

    // ============================================================================================
    // 7. Ghost handles: insert the via point only when the drag passes touch slop;
    //    if the gesture is cancelled, remove it again.
    // ============================================================================================
    @Test
    fun item7_ghostHandles_insertOnlyAfterTouchSlop_andRemoveOnCancel() {
        val vm = RouteBuilderViewModel(app)
        vm.addPointAtEnd(56.0, 9.5, "Start")
        vm.addPointAtEnd(56.1, 9.6, "Slut")
        assertEquals(2, vm.uiState.value.visibleWaypoints.size)

        val w0 = vm.uiState.value.visibleWaypoints[0]
        val w1 = vm.uiState.value.visibleWaypoints[1]
        val handle = LegMidpointHandle(
            legKey = "${w0.id}_to_${w1.id}",
            legIndex = 0,
            fromWaypointId = w0.id,
            toWaypointId = w1.id,
            lat = 56.05,
            lng = 9.55
        )

        val session = PlannerDragGestureSession(
            viewModel = vm,
            touchSlopPx = 18f,
            hitGhostHandle = handle
        )

        // Small movement below touchSlopPx (10 px <= 18 px) -> must NOT insert via point yet
        val startedBelowSlop = session.onMoveDistance(10f)
        assertFalse(startedBelowSlop)
        assertNull("Via point must not be inserted before passing touch slop", session.insertedGhostWpId)
        assertEquals(2, vm.uiState.value.visibleWaypoints.size)

        // Movement exceeds touchSlopPx (24 px > 18 px) -> inserts via point now
        val startedAboveSlop = session.onMoveDistance(24f)
        assertTrue(startedAboveSlop)
        assertNotNull("Via point must be inserted once drag passes touch slop", session.insertedGhostWpId)
        assertEquals(3, vm.uiState.value.visibleWaypoints.size)

        // Gesture is cancelled -> removes the inserted via point again
        session.onSecondFingerDownOrCancel()
        assertEquals(
            "Cancelled ghost handle drag must remove the temporary via point again",
            2,
            vm.uiState.value.visibleWaypoints.size
        )
    }

    // ============================================================================================
    // 8. Pin drag: if a second finger goes down during a pin drag, cancel the drag
    //    (pin returns to its old place) and let the map zoom.
    // ============================================================================================
    @Test
    fun item8_pinDrag_secondFingerDownCancelsDragAndRestoresPinToOriginalPosition() {
        val vm = RouteBuilderViewModel(app)
        vm.addPointAtEnd(56.0000, 9.5000, "Start")
        vm.addPointAtEnd(56.1000, 9.6000, "Slut")

        val firstWp = vm.uiState.value.visibleWaypoints[0]
        val origLat = firstWp.lat
        val origLng = firstWp.lng

        val session = PlannerDragGestureSession(
            viewModel = vm,
            touchSlopPx = 16f,
            hitWaypoint = firstWp,
            hitWaypointIdx = 0
        )

        // Start dragging the pin with 1 finger
        assertTrue(session.onMoveDistance(30f))
        assertTrue(session.isDragging)
        // Simulate intermediate pin displacement before second finger goes down
        vm.moveWaypoint(firstWp.id, 56.0450, 9.5450)
        val movedWp = vm.uiState.value.visibleWaypoints.first { it.id == firstWp.id }
        assertEquals(56.0450, movedWp.lat, 1e-4)
        assertEquals(9.5450, movedWp.lng, 1e-4)

        // Second finger goes down (pinch-to-zoom) -> cancels the drag and restores pin to original place
        session.onSecondFingerDownOrCancel()
        assertTrue(session.isCancelled)
        assertNull(vm.uiState.value.draggingWaypointId)

        val restoredWp = vm.uiState.value.visibleWaypoints.first { it.id == firstWp.id }
        assertEquals("Pin latitude must return to its old place on 2-finger pinch", origLat, restoredWp.lat, 1e-6)
        assertEquals("Pin longitude must return to its old place on 2-finger pinch", origLng, restoredWp.lng, 1e-6)
    }

    // ============================================================================================
    // 9. Strings: verify Round 9A user-facing strings are present in strings.xml
    // ============================================================================================
    @Test
    fun item9_stringsXml_containsRound9AUserFacingStrings() {
        assertEquals("Tilføj som næste punkt", app.getString(R.string.drop_pin_add_next_point))
        assertEquals("Kunne ikke beregne vej tilbage", app.getString(R.string.nav_route_back_failed))
        assertEquals("mod NØ", app.getString(R.string.nav_direction_mod_format, "NØ"))
        assertEquals("Fortsæt", app.getString(R.string.nav_continue_action))
        assertEquals("Navigationsmenu", app.getString(R.string.nav_menu_content_desc))
        assertEquals("Start nu", app.getString(R.string.trail_detail_start_now))
    }
}
