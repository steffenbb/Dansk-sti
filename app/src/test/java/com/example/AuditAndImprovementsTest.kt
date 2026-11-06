package com.example

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SettingsRepository
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.NearestPoiCategory
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.WindowSizeClass
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.insertViaPointOnLine
import com.example.ui.viewmodel.openReplaceOvernightSheet
import com.example.ui.viewmodel.routeToNearest
import com.example.util.DanskStiTileSourceHelper
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditAndImprovementsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: DanskStiApp
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        settingsRepository = DanskStiApp.getSettingsRepository(app)
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @Test
    fun test1_howToGuideOpensOnFirstOpen_persistsDismissal_andReopensFromProfileAndPlanner() {
        runBlocking {
            settingsRepository.setHasSeenHowToGuide(false)
        }
        composeTestRule.setContent {
            val wsc = WindowSizeClass.compute(380.dp, 760.dp)
            DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                DanskStiAppRoot()
            }
        }
        composeTestRule.waitForIdle()

        // 1. On first open, HowToGuideScreen is automatically displayed
        composeTestRule.onNodeWithTag("how_to_guide_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("how_to_chip_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("how_to_chip_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("how_to_card_1").assertIsDisplayed()

        // 2. Dismiss the first-launch guide and verify hasSeenHowToGuide is persisted
        composeTestRule.onNodeWithTag("how_to_guide_got_it_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(
            "Expected hasSeenHowToGuide to be true after dismissing first-open guide",
            settingsRepository.currentSettingsSnapshot().hasSeenHowToGuide
        )
        assertTrue(
            "HowToGuideScreen should be dismissed",
            composeTestRule.onAllNodesWithTag("how_to_guide_screen").fetchSemanticsNodes().isEmpty()
        )

        // 3. Navigate to Profil and verify the unused Imperial toggle is removed and HowToGuide reopens
        composeTestRule.onNodeWithTag("nav_profile_tab").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Unused imperial units toggle must be removed from ProfileScreen",
            composeTestRule.onAllNodesWithTag("profile_units_chip_imperial").fetchSemanticsNodes().isEmpty()
        )

        composeTestRule.onNodeWithTag("profile_screen_list")
            .performScrollToNode(hasTestTag("profile_how_to_guide_button"))
        composeTestRule.onNodeWithTag("profile_how_to_guide_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("how_to_guide_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("how_to_guide_close_button").performClick()
        composeTestRule.waitForIdle()

        // 4. Open Ruteplanlægger and verify the quick-help (?) button opens the guide at section 2
        composeTestRule.onNodeWithTag("nav_map_tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kort_tool_plan_route").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_planner_help_guide").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("how_to_guide_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("how_to_card_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("how_to_guide_got_it_button").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun test2_clearCacheResetsTrailGeometryDownloadedInRoom() = runBlocking {
        val trailId = "trail_camonoen_test"
        app.database.dao().insertTrails(
            listOf(
                TrailEntity(
                    id = trailId,
                    name = "Camønoen",
                    isGeometryDownloaded = true
                )
            )
        )
        val geom = TrailGeometryDto(
            trailId = trailId,
            osmRelationId = 12345L,
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = listOf(GeoPointDto(54.98, 12.28, 5.0), GeoPointDto(54.99, 12.29, 8.0)),
                    lengthKm = 1.5
                )
            ),
            totalWalkingKm = 1.5,
            gapsOver200mCount = 0,
            bboxNorth = 54.99,
            bboxSouth = 54.98,
            bboxEast = 12.29,
            bboxWest = 12.28
        )
        TrailGeometryRepository(app).saveGeometry(geom)
        assertTrue(app.database.dao().getTrailById(trailId)?.isGeometryDownloaded == true)

        app.repository.clearCachedTrailGeometries()

        val updatedTrail = app.database.dao().getTrailById(trailId)
        assertNotNull(updatedTrail)
        assertFalse(
            "Expected clearCachedTrailGeometries() to reset isGeometryDownloaded in Room",
            updatedTrail!!.isGeometryDownloaded
        )
    }

    @Test
    fun test3_plannerBottomSheetSpacious2RowGrid_48dpTouchTargets_andNearestPoiInTrailMode() {
        val wps = listOf(
            RouteWaypoint(id = "wp1", lat = 56.0, lng = 10.0, role = WaypointRole.START, name = "Start", trailKm = 0.0),
            RouteWaypoint(id = "wp2", lat = 56.1, lng = 10.1, role = WaypointRole.END, name = "Slut", trailKm = 15.0)
        )
        val currentState = RouteBuilderState(
            mode = PlannerMode.TRAIL,
            baseTrailId = "trail_ohavsstien",
            waypoints = wps
        )

        composeTestRule.setContent {
            val wsc = WindowSizeClass.compute(380.dp, 760.dp)
            DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                Box(modifier = Modifier.size(width = 380.dp, height = 760.dp)) {
                    PlannerBottomSheet(
                        uiState = currentState,
                        visibleWaypoints = currentState.visibleWaypoints,
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
                        availableHeightDp = 760f,
                        isSidePanel = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val density = composeTestRule.activity.resources.displayMetrics.density
        val buttonTags = listOf(
            "btn_find_overnight",
            "btn_start_planned_navigation",
            "btn_sheet_trip_settings",
            "btn_open_nearest_poi",
            "btn_share_route_sheet"
        )
        for (tag in buttonTags) {
            val bounds = composeTestRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val heightDp = bounds.height / density
            assertTrue(
                "Expected $tag to have >= 47dp height in spacious 2-row grid, got ${heightDp}dp",
                heightDp >= 47f
            )
        }
    }

    @Test
    fun test4_stagePlannerAdaptiveWindow_toiletAndSunsetScoring_andLookAheadBalancing() {
        val points = (0..60).map { km ->
            RoutePoint(lat = 56.0 + km * 0.009, lng = 9.5, ele = 20.0, walkKm = km.toDouble(), trailKm = km.toDouble())
        }
        val route = PlannedRoute(
            planId = 1L,
            trailId = "test_trail",
            trailName = "Test Sti",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 60.0,
            totalWalkKm = 60.0,
            points = points,
            stages = emptyList()
        )

        // 1. Toilet & Sunset Scoring: Candidate with toilet and before-sunset arrival beats candidate without toilet or after sunset
        val candNoToilet = OvernightCandidate(
            id = "c_no_toilet",
            name = "Uden Toilet",
            type = "SHELTER",
            lat = 56.135,
            lng = 9.5,
            walkKm = 15.0,
            detourMeters = 100.0,
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = false
        )
        val candWithToilet = candNoToilet.copy(
            id = "c_with_toilet",
            name = "Med Toilet",
            hasToiletNearby = true
        )
        val scoreNoToilet = StagePlanner.calculateSmartStageScore(
            candidate = candNoToilet,
            targetKm = 15.0,
            dayStartKm = 0.0,
            dailyTargetKm = 15.0,
            paceKmh = 4.0,
            startTime = "09:00",
            sunsetTimeOverride = "21:00"
        )
        val scoreWithToilet = StagePlanner.calculateSmartStageScore(
            candidate = candWithToilet,
            targetKm = 15.0,
            dayStartKm = 0.0,
            dailyTargetKm = 15.0,
            paceKmh = 4.0,
            startTime = "09:00",
            sunsetTimeOverride = "21:00"
        )
        assertTrue("Candidate with toilet must have a better (lower) smart score", scoreWithToilet < scoreNoToilet)

        val scoreAfterSunset = StagePlanner.calculateSmartStageScore(
            candidate = candWithToilet,
            targetKm = 15.0,
            dayStartKm = 0.0,
            dailyTargetKm = 15.0,
            paceKmh = 3.0,
            startTime = "14:00",
            sunsetTimeOverride = "18:00"
        )
        assertTrue("Arriving after sunset must add a penalty to smart score", scoreAfterSunset > scoreWithToilet + 3.0)

        // 2. Adaptive window & 1-day look-ahead:
        // Day 1 target = 20 km. Candidate A at 19.5 km leaves Day 2 target (39.5 km) in a shelter-free gap;
        // Candidate B at 16.0 km has a Day 2 shelter at 31.5 km (within Day 2 window around 36.0 km).
        val candDay1Gap = OvernightCandidate(
            id = "c_day1_gap",
            name = "Lejr Blindgyde",
            type = "SHELTER",
            lat = 56.17,
            lng = 9.5,
            walkKm = 19.5,
            detourMeters = 50.0,
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        val candDay1GoodLookAhead = OvernightCandidate(
            id = "c_day1_good",
            name = "Lejr God Fortsættelse",
            type = "SHELTER",
            lat = 56.16,
            lng = 9.5,
            walkKm = 16.0,
            detourMeters = 50.0,
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        val candDay2ReachableFromGood = OvernightCandidate(
            id = "c_day2_ok",
            name = "Lejr Dag 2",
            type = "SHELTER",
            lat = 56.31,
            lng = 9.5,
            walkKm = 31.5,
            detourMeters = 50.0,
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        val stages = StagePlanner.planStages(
            plannedRoute = route,
            dailyTargetKm = 20.0,
            numDays = 3,
            paceKmh = 4.5,
            startTime = "08:00",
            sunsetTime = "21:30",
            candidatesOverride = listOf(candDay1GoodLookAhead, candDay1Gap, candDay2ReachableFromGood)
        )
        assertEquals(3, stages.size)
        assertEquals(
            "Look-ahead should pick Candidate B (16.0 km) so Day 2 reaches Lejr Dag 2 (31.5 km) instead of getting stranded",
            "Lejr God Fortsættelse",
            stages[0].stopName
        )
        assertEquals("Lejr Dag 2", stages[1].stopName)
    }

    @Test
    fun test5_routeToNearestPreservesExistingRoute_andReplaceOvernightComputesAmenities() = runBlocking {
        val vm = RouteBuilderViewModel(app)
        vm.routingEngine = object : RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: RouteOptions,
                alternative: Int
            ): RouteLegResult {
                return RouteLegResult.Success(
                    points = listOf(RoutePoint3D(from.lat, from.lng, 10.0), RoutePoint3D(to.lat, to.lng, 12.0)),
                    lengthKm = 1.4,
                    ascentM = 2.0,
                    descentM = 0.0,
                    perPointWayInfo = emptyList(),
                    sourceName = "TestStub"
                )
            }
        }

        val wp1 = RouteWaypoint(id = "wp_start", lat = 56.000, lng = 10.000, role = WaypointRole.START, name = "Start")
        val wp2 = RouteWaypoint(id = "wp_end", lat = 56.020, lng = 10.020, role = WaypointRole.END, name = "Slut")

        // Seed a nearby water POI and Fri Teltning with water/toilet within 500 m
        val nearWaterPoi = PoiEntity(
            id = "test_water_nearest",
            type = "water",
            name = "Kildevæld Vandpost",
            lat = 56.010,
            lng = 10.011
        )
        val nearFriTeltning = FriTeltningEntity(
            id = "test_ft_amenities",
            name = "Skovbryn Fri Teltning",
            centerLat = 56.0102,
            centerLng = 10.0112,
            polygonJson = """[{"lat":56.0100,"lng":10.0110},{"lat":56.0105,"lng":10.0115},{"lat":56.0100,"lng":10.0115}]""",
            toiletDistanceMeters = 200,
            waterDistanceMeters = 150,
            rulesSummary = "1-2-3 reglen"
        )
        app.database.dao().insertPois(listOf(nearWaterPoi))
        app.database.dao().insertFriTeltning(listOf(nearFriTeltning))

        // Set up an existing 2-point route in the ViewModel
        vm.insertViaPointOnLine(wp1.lat, wp1.lng, wp1.name)
        vm.insertViaPointOnLine(wp2.lat, wp2.lng, wp2.name)
        composeTestRule.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.uiState.value.visibleWaypoints.size == 2
        }

        // Invoke routeToNearest(WATER) on the existing 2-waypoint route: it must ADD a stop without wiping Start & End
        vm.routeToNearest(NearestPoiCategory.WATER, startLat = 56.010, startLng = 10.010)
        composeTestRule.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !vm.uiState.value.isRoutingToNearest && vm.uiState.value.visibleWaypoints.size >= 3
        }

        val updatedWps = vm.uiState.value.visibleWaypoints
        assertEquals("Existing 2-point route should now have 3 waypoints after adding nearest water stop", 3, updatedWps.size)
        assertEquals(WaypointRole.START, updatedWps.first().role)
        assertEquals(WaypointRole.END, updatedWps.last().role)

        // Verify openReplaceOvernightSheet computes real water/toilet flags for FriTeltning
        val middleWpId = updatedWps[1].id
        vm.openReplaceOvernightSheet(middleWpId)
        composeTestRule.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.uiState.value.replaceOvernightCandidates.isNotEmpty()
        }
        val ftCand = vm.uiState.value.replaceOvernightCandidates.find { it.id == "test_ft_amenities" }
        assertNotNull("Expected Skovbryn Fri Teltning in replaceOvernightCandidates", ftCand)
        assertTrue("FriTeltning candidate with waterDistanceMeters=150 must have hasWaterNearby=true", ftCand!!.hasWaterNearby)
        assertTrue("FriTeltning candidate with toiletDistanceMeters=200 must have hasToiletNearby=true", ftCand.hasToiletNearby)
    }
}
