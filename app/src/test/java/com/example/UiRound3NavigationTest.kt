package com.example

import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.model.TurnCue
import com.example.data.model.WayTagRangeDto
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.updateKeepScreenOnWindowFlag
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationMapMode
import com.example.ui.viewmodel.NavigationViewModel
import com.example.util.DanskStiTileSourceHelper
import com.example.util.RouteTracker
import com.example.util.routing.TurnCueGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiRound3NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        runBlocking {
            app.settingsRepository.setKeepScreenOn(true)
            app.settingsRepository.setVoiceGuidance(false)
        }
    }

    @Test
    fun test1_cueIsProducedAt90DegreeJunctionOnTestTrail() {
        // Build a synthetic TRAIL with a 90-degree left junction where highway changes from track to path
        // Segment goes North for ~100 m (from lat 55.0000 to 55.0009, lng 10.0000)
        // then turns 90 degrees West (lat 55.0009, lng from 10.0000 to 9.9984)
        val junctionLat = 55.0009
        val junctionLng = 10.0000

        val seg = TrailSegmentDto(
            type = TrailSegmentType.WALK,
            points = listOf(
                GeoPointDto(55.0000, 10.0000, 10.0),
                GeoPointDto(55.00045, 10.0000, 10.0),
                GeoPointDto(junctionLat, junctionLng, 10.0),
                GeoPointDto(junctionLat, 9.9992, 10.0),
                GeoPointDto(junctionLat, 9.9984, 10.0)
            ),
            lengthKm = 0.2,
            highway = "track",
            surface = "gravel",
            wayTagRanges = listOf(
                WayTagRangeDto(fromKm = 0.0, toKm = 0.10, highway = "track", surface = "gravel"),
                WayTagRangeDto(fromKm = 0.10, toKm = 0.25, highway = "path", surface = "dirt")
            )
        )

        val cues = TurnCueGenerator.computeTrailTurnCues(listOf(seg))
        assertEquals("Expected exactly 1 cue at the 90-degree junction", 1, cues.size)

        val cue = cues.first()
        assertEquals("LEFT", cue.direction)
        assertEquals("Drej til venstre", cue.instructionText)
        assertEquals(junctionLat, cue.lat, 1e-4)
        assertEquals(junctionLng, cue.lng, 1e-4)
        assertTrue("Expected ~ -90 degree turn, got ${cue.angleDegrees}", cue.angleDegrees < -75.0 && cue.angleDegrees > -105.0)

        // Verify banner formatting
        val bannerAt120m = TurnCueGenerator.formatCueBannerText(cue, 120.0)
        assertEquals("Drej til venstre om 120 m", bannerAt120m)
    }

    @Test
    fun test2_offRouteBannerAppearsAfterRouteTrackerReportsOffRouteAndClearsWithin25m() {
        // Straight 1 km north-south route at lng = 10.0000
        val plannedRoute = PlannedRoute(
            planId = 10L,
            trailId = "trail_test",
            trailName = "Teststien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 1.0,
            totalWalkKm = 1.0,
            points = listOf(
                RoutePoint(lat = 55.0000, lng = 10.0000, ele = 5.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 55.0045, lng = 10.0000, ele = 5.0, walkKm = 0.5, trailKm = 0.5),
                RoutePoint(lat = 55.0090, lng = 10.0000, ele = 5.0, walkKm = 1.0, trailKm = 1.0)
            ),
            stages = listOf(
                TripStage(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = 1.0,
                    stageKm = 1.0,
                    stopName = "Shelter Skovbo",
                    stopType = "SHELTER",
                    stopLat = 55.0090,
                    stopLng = 10.0000,
                    detourMeters = 0.0,
                    hasWaterNearby = true,
                    hasToiletNearby = true
                )
            )
        )

        val tracker = RouteTracker(plannedRoute)
        val t0 = 1_000_000L

        // Fix 1-3: ~140 m East of route (at lat 55.0, 0.0022 deg lng is ~140 m)
        val offLng = 10.0022
        val r1 = tracker.onLocationUpdate(55.0010, offLng, 0f, 8f, t0)
        assertFalse(r1.isOffRoute)
        val r2 = tracker.onLocationUpdate(55.0015, offLng, 0f, 8f, t0 + 5_000L)
        assertFalse(r2.isOffRoute)
        val r3 = tracker.onLocationUpdate(55.0020, offLng, 0f, 8f, t0 + 10_000L)
        assertTrue("RouteTracker must report off-route on 3rd fix > threshold", r3.isOffRoute)
        assertTrue("RouteTracker must trigger vibration on entering off-route", r3.shouldVibrate)

        // Feed off-route state into NavigationScreen and verify red off-route banner appears
        NavigationTrackingService.updateLiveStateForTest(
            NavigationLiveState(
                isTracking = true,
                currentLat = 55.0020,
                currentLng = offLng,
                isOffTrailWarning = r3.isOffRoute,
                offTrailDistanceMeters = r3.offRouteDistanceM,
                isDetourMuted = r3.isDetourMuted,
                distanceRemainingTodayKm = 0.8,
                currentDay = 1,
                plannedRoute = plannedRoute,
                currentStage = plannedRoute.stages.first()
            )
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
        composeTestRule.onNodeWithTag("nav_off_route_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_reroute_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_take_detour_button").assertIsDisplayed()

        // Now move back within 25 m of route (~12 m East: 10.00019)
        val rBack = tracker.onLocationUpdate(55.0025, 10.00019, 0f, 8f, t0 + 20_000L)
        assertTrue("Distance to route should be <= 25 m, got ${rBack.offRouteDistanceM}", rBack.offRouteDistanceM <= 25.0)
        assertFalse("Off-route state must clear when back within 25 m", rBack.isOffRoute)

        NavigationTrackingService.updateLiveStateForTest(
            NavigationLiveState(
                isTracking = true,
                currentLat = 55.0025,
                currentLng = 10.00019,
                isOffTrailWarning = rBack.isOffRoute,
                offTrailDistanceMeters = rBack.offRouteDistanceM,
                isDetourMuted = rBack.isDetourMuted,
                distanceRemainingTodayKm = 0.75,
                currentDay = 1,
                plannedRoute = plannedRoute,
                currentStage = plannedRoute.stages.first()
            )
        )
        composeTestRule.waitForIdle()
        assertTrue(
            "Off-route banner must disappear when back within 25 m",
            composeTestRule.onAllNodesWithTag("nav_off_route_banner").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun test3_jegTagerEnOmvejSuppressesVibrationFor30Minutes() {
        val plannedRoute = PlannedRoute(
            planId = 11L,
            trailId = "trail_test",
            trailName = "Teststien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 1.0,
            totalWalkKm = 1.0,
            points = listOf(
                RoutePoint(lat = 55.0000, lng = 10.0000, ele = 5.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 55.0090, lng = 10.0000, ele = 5.0, walkKm = 1.0, trailKm = 1.0)
            ),
            stages = emptyList()
        )

        val tracker = RouteTracker(plannedRoute)
        val t0 = 1_000_000L
        val offLng = 10.0022 // ~140 m off route

        // Enter off-route
        tracker.onLocationUpdate(55.0010, offLng, 0f, 8f, t0)
        tracker.onLocationUpdate(55.0015, offLng, 0f, 8f, t0 + 5_000L)
        val r3 = tracker.onLocationUpdate(55.0020, offLng, 0f, 8f, t0 + 10_000L)
        assertTrue(r3.isOffRoute)
        assertTrue(r3.shouldVibrate)

        // User taps "Jeg tager en omvej" at t0 + 15_000L
        val detourStartMs = t0 + 15_000L
        tracker.takeDetour(detourStartMs)
        assertTrue(tracker.isDetourMuted(detourStartMs))

        // 5 minutes later (normally would vibrate every 2 minutes while off-route) -> vibration suppressed!
        val rDuringMute1 = tracker.onLocationUpdate(
            55.0025,
            offLng,
            0f,
            8f,
            detourStartMs + 5 * 60_000L
        )
        assertTrue(rDuringMute1.isOffRoute)
        assertTrue(rDuringMute1.isDetourMuted)
        assertFalse("Vibration must be suppressed during 30-minute detour mute", rDuringMute1.shouldVibrate)

        // 29 minutes later -> still muted, no vibration
        val rDuringMute2 = tracker.onLocationUpdate(
            55.0030,
            offLng,
            0f,
            8f,
            detourStartMs + 29 * 60_000L
        )
        assertTrue(rDuringMute2.isDetourMuted)
        assertFalse("Vibration must still be suppressed at 29 minutes", rDuringMute2.shouldVibrate)

        // 30 minutes + 1 second later -> mute expires and vibration resumes!
        val rAfter30Min = tracker.onLocationUpdate(
            55.0035,
            offLng,
            0f,
            8f,
            detourStartMs + 30 * 60_000L + 1_000L
        )
        assertFalse("Detour mute must expire after 30 minutes", rAfter30Min.isDetourMuted)
        assertTrue("Vibration must resume once 30-minute detour mute expires", rAfter30Min.shouldVibrate)
    }

    @Test
    fun test4_keepScreenOnFlagIsSetOnlyWhileNavigating() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val window = activity.window

        // Initially not navigating -> FLAG_KEEP_SCREEN_ON must not be set
        updateKeepScreenOnWindowFlag(window, isNavigating = false, keepScreenOnEnabled = true)
        assertEquals(
            "FLAG_KEEP_SCREEN_ON must not be set when not navigating",
            0,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Start navigating with keepScreenOnEnabled = true -> FLAG_KEEP_SCREEN_ON is set
        updateKeepScreenOnWindowFlag(window, isNavigating = true, keepScreenOnEnabled = true)
        assertNotNull(window)
        assertTrue(
            "FLAG_KEEP_SCREEN_ON must be set while actively navigating with setting enabled",
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        )

        // Toggle keepScreenOnEnabled = false while navigating -> FLAG_KEEP_SCREEN_ON is cleared
        updateKeepScreenOnWindowFlag(window, isNavigating = true, keepScreenOnEnabled = false)
        assertEquals(
            "FLAG_KEEP_SCREEN_ON must be cleared when user disables 'Hold skærmen tændt'",
            0,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Re-enable keepScreenOnEnabled = true while navigating, then stop navigating -> FLAG_KEEP_SCREEN_ON is cleared
        updateKeepScreenOnWindowFlag(window, isNavigating = true, keepScreenOnEnabled = true)
        assertTrue((window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)

        updateKeepScreenOnWindowFlag(window, isNavigating = false, keepScreenOnEnabled = true)
        assertEquals(
            "FLAG_KEEP_SCREEN_ON must be cleared when navigation stops",
            0,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    @Test
    fun test5_navigationScreenDisplaysTurnCueStatsStripAndMapModeCycle() {
        val cue = TurnCue(
            lat = 55.005,
            lng = 10.0,
            walkKm = 0.5,
            direction = "SLIGHT_LEFT",
            angleDegrees = -45.0,
            instructionText = "Hold til venstre",
            pointIndex = 1
        )
        val plannedRoute = PlannedRoute(
            planId = 12L,
            trailId = "trail_ohavsstien",
            trailName = "Øhavsstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 10.0,
            totalWalkKm = 10.0,
            points = listOf(
                RoutePoint(lat = 55.0, lng = 10.0, ele = 5.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 55.005, lng = 10.0, ele = 12.0, walkKm = 0.5, trailKm = 0.5),
                RoutePoint(lat = 55.09, lng = 10.0, ele = 8.0, walkKm = 10.0, trailKm = 10.0)
            ),
            stages = listOf(
                TripStage(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = 10.0,
                    stageKm = 10.0,
                    stopName = "Shelter Skovbo",
                    stopType = "SHELTER",
                    stopLat = 55.09,
                    stopLng = 10.0,
                    detourMeters = 0.0,
                    hasWaterNearby = true,
                    hasToiletNearby = true
                )
            ),
            turnCues = listOf(cue)
        )

        NavigationTrackingService.updateLiveStateForTest(
            NavigationLiveState(
                isTracking = true,
                currentLat = 55.0038,
                currentLng = 10.0,
                speedKmh = 4.5,
                totalDistanceWalkedKm = 0.38,
                distanceWalkedTodayKm = 0.38,
                distanceRemainingTodayKm = 9.6,
                currentDay = 1,
                plannedRoute = plannedRoute,
                currentStage = plannedRoute.stages.first(),
                nextTurnCue = cue,
                distanceToNextCueMeters = 120.0
            )
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

        // 1. Cue banner shows "Hold til venstre om 120 m"
        composeTestRule.onNodeWithTag("nav_cue_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hold til venstre om 120 m").assertIsDisplayed()

        // 2. Day progress bar & 3 large stats strip numbers are displayed
        composeTestRule.onNodeWithTag("nav_day_progress_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stats_strip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stat_km_left_today").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stat_arrival_time").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stat_next_water_km").assertIsDisplayed()

        // 3. Map mode cycles: Følg (nord op) -> Følg (retning op) -> Oversigt
        assertEquals(NavigationMapMode.FOLLOW_NORTH_UP, navVm.mapMode.value)
        composeTestRule.onNodeWithTag("nav_map_mode_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(NavigationMapMode.FOLLOW_HEADING_UP, navVm.mapMode.value)
        composeTestRule.onNodeWithTag("nav_map_mode_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(NavigationMapMode.OVERVIEW, navVm.mapMode.value)
    }
}
