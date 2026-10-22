package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapDarkMode
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.TripPlanEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SavedPlansScreen
import com.example.ui.screens.TrailBrowserScreen
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.SavedViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import com.example.util.GpxExporter
import com.example.util.routing.LegMode
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionReadinessCujTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp
    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = app.database
        DanskStiTileSource.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    /**
     * CUJ 1: Udforsk -> TrailDetail (all detail tabs) -> Planlæg -> Custom GPX Export
     */
    @Test
    fun testExploreToTrailDetailAndCustomGpxExportCuj() {
        val exploreViewModel = ExploreViewModel(app)
        val trailViewModel = TrailViewModel(app)
        var clickedTrailId by mutableStateOf<String?>(null)
        var planClickedTrailId: String? = null

        composeTestRule.setContent {
            DanskStiTheme {
                val activeTrailId = clickedTrailId
                if (activeTrailId == null) {
                    TrailBrowserScreen(
                        viewModel = exploreViewModel,
                        onTrailClick = { id ->
                            trailViewModel.loadTrail(id)
                            clickedTrailId = id
                        }
                    )
                } else {
                    TrailDetailScreen(
                        trailId = activeTrailId,
                        viewModel = trailViewModel,
                        onBack = { clickedTrailId = null },
                        onPlanTrip = { id -> planClickedTrailId = id },
                        onStartNavigation = {},
                        onOpenFullscreenMap = {},
                        onDownloadOffline = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        // Verify Udforsk search bar & filter chips exist
        composeTestRule.onNodeWithTag("trail_search_input").assertExists()
        composeTestRule.onNodeWithTag("filter_chip_region").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("filter_region_fyn & øer").performClick()
        composeTestRule.waitForIdle()

        // Scroll to and click Øhavsstien card
        composeTestRule.waitUntil(5_000) {
            exploreViewModel.filteredTrails.value.any { it.id == "trail_ohavsstien" }
        }
        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("trail_card_trail_ohavsstien"))
        composeTestRule.onNodeWithTag("trail_card_trail_ohavsstien").performClick()
        composeTestRule.waitForIdle()
        assertEquals("trail_ohavsstien", clickedTrailId)

        // Wait for TrailDetailScreen to load trail entity
        composeTestRule.waitUntil(5_000) {
            trailViewModel.trail.value?.id == "trail_ohavsstien"
        }
        composeTestRule.waitForIdle()

        // Verify TrailDetailScreen tabs: Oversigt, Overnatning, Vand & mad, Vejr, Transport
        composeTestRule.onNodeWithTag("trail_detail_list")
            .performScrollToNode(hasTestTag("tab_oversigt"))
        composeTestRule.onNodeWithTag("tab_oversigt").assertExists()
        composeTestRule.onNodeWithTag("tab_overnatning").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tab_vand_&_mad").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tab_vejr").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tab_transport").performClick()
        composeTestRule.waitForIdle()

        // Click Planlæg tur CTA
        composeTestRule.onNodeWithTag("plan_trip_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("trail_ohavsstien", planClickedTrailId)

        // Verify GpxExporter.generateCustomRouteGpx produces valid GPX 1.1 with overnight & custom waypoints
        val gpx = GpxExporter.generateCustomRouteGpx(
            routeName = "Øhavsstien Weekendtur",
            description = "Planlagt rute i Dansk Sti (42.5 km)",
            routePoints = listOf(
                GeoPointDto(55.05, 10.60, 12.4),
                GeoPointDto(55.06, 10.62, 18.0)
            ),
            overnightWaypoints = listOf(
                RouteWaypoint(
                    id = "wp_camp_1",
                    lat = 55.055,
                    lng = 10.61,
                    role = WaypointRole.OVERNIGHT,
                    legModeToNext = LegMode.TRAIL,
                    name = "Lundeborg Skov Shelter",
                    spotType = "SHELTER"
                )
            ),
            customWaypoints = listOf(
                UserCustomWaypointEntity(
                    id = "uwp_1",
                    routeId = 1L,
                    lat = 55.052,
                    lng = 10.605,
                    name = "Udsigt over Storebælt",
                    note = "God frokostbænk",
                    photoPath = null,
                    createdAt = 1000L
                )
            )
        )
        assertTrue(gpx.contains("<name>Øhavsstien Weekendtur - DanskSti Ruteplan</name>"))
        assertTrue(gpx.contains("<name>Dag 1 Mål: Lundeborg Skov Shelter</name>"))
        assertTrue(gpx.contains("<name>Udsigt over Storebælt</name>"))
        assertTrue(gpx.contains("<ele>12.4</ele>"))
        assertFalse(gpx.contains("dummy", ignoreCase = true))
    }

    /**
     * CUJ 2: Active GPS Navigation, Battery Saver Mode, and SOS Emergency Flow
     */
    @Test
    fun testActiveNavigationBatterySaverAndSosCuj() {
        val navViewModel = NavigationViewModel(app)
        var planId = 0L
        runBlocking {
            planId = database.dao().insertTripPlan(
                TripPlanEntity(
                    trailId = "trail_ohavsstien",
                    trailName = "Øhavsstien",
                    startKm = 0.0,
                    endKm = 20.0,
                    isReverse = false,
                    totalDistanceKm = 20.0,
                    targetDays = 1,
                    overnightType = OvernightType.SHELTER.name,
                    preferredDailyKm = 20.0,
                    stagesJson = "[]",
                    paceKmh = 4.5
                )
            )
        }

        navViewModel.initPlan(planId)

        composeTestRule.setContent {
            DanskStiTheme {
                NavigationScreen(
                    viewModel = navViewModel,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify top headline and SOS button exist
        composeTestRule.onNodeWithTag("nav_top_headline_text").assertExists()
        composeTestRule.onNodeWithTag("nav_sos_button").assertExists()

        // Open SOS sheet and verify 112 call button
        composeTestRule.onNodeWithTag("nav_sos_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("call_112_button").assertExists()
        composeTestRule.onNodeWithText("Ring 112 nu").assertExists()

        // Close SOS sheet and toggle Battery Saver Mode
        navViewModel.setSosSheet(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("toggle_battery_mode_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("STRØMSPARER").assertExists()
        composeTestRule.onNodeWithTag("battery_mode_sos_button").assertExists()
        composeTestRule.onNodeWithTag("exit_battery_mode_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_top_headline_text").assertExists()
    }

    /**
     * CUJ 3: SavedPlansScreen ("Mine ture") delete + "Fortryd" undo restoration
     */
    @Test
    fun testSavedPlansLibraryDeleteAndUndoRestoreCuj() {
        var planId = 0L
        runBlocking {
            planId = database.dao().insertTripPlan(
                TripPlanEntity(
                    trailId = "trail_haervejen",
                    trailName = "Hærvejen Sommer",
                    startKm = 0.0,
                    endKm = 45.0,
                    isReverse = false,
                    totalDistanceKm = 45.0,
                    targetDays = 3,
                    overnightType = OvernightType.BOTH.name,
                    preferredDailyKm = 15.0,
                    stagesJson = "[]",
                    paceKmh = 4.0
                )
            )
        }

        val savedViewModel = SavedViewModel(app)

        composeTestRule.setContent {
            DanskStiTheme {
                SavedPlansScreen(
                    onTrailClick = {},
                    onStartNavigation = {},
                    viewModel = savedViewModel
                )
            }
        }
        // Wait for SavedViewModel to emit the inserted plan
        composeTestRule.waitUntil(5_000) {
            savedViewModel.savedPlans.value.any { it.id == planId }
        }
        composeTestRule.waitForIdle()

        // Verify plan card is displayed in Planer segment
        composeTestRule.onNodeWithTag("plan_card_$planId", useUnmergedTree = true).assertExists()

        // Click delete button on the plan card
        composeTestRule.onNodeWithTag("delete_plan_$planId", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // Verify Snackbar with "Fortryd" appears and click "Fortryd"
        composeTestRule.onNodeWithText("Fortryd", useUnmergedTree = true).assertExists().performClick()
        composeTestRule.waitUntil(5_000) {
            runBlocking { database.dao().getAllTripPlans().first().any { it.trailName == "Hærvejen Sommer" } }
        }

        // Verify the plan is restored in Room and visible again
        val restoredPlans = runBlocking { database.dao().getAllTripPlans().first() }
        assertTrue("Deleted plan must be restored after clicking Fortryd", restoredPlans.any { it.trailName == "Hærvejen Sommer" })
    }

    /**
     * CUJ 4: 200% Font Scale & Dark Theme Accessibility Verification on ProfileScreen
     */
    @Test
    fun testDarkThemeAnd200PercentFontScaleResilienceCuj() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1.0f, fontScale = 2.0f)
            ) {
                DanskStiTheme(darkTheme = true) {
                    ProfileScreen(
                        onOpenCredits = {},
                        onOpenDiagnostics = {},
                        isDebugBuild = true
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_screen_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_pace_value").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_daily_target_value").assertIsDisplayed()

        // At 200% font scale, FlowRow wraps the start time and dark map chips cleanly
        composeTestRule.onNodeWithTag("profile_start_time_chip_08:00")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            runBlocking { app.settingsRepository.settingsFlow.first().startTime == "08:00" }
        }
        val updatedSettings = runBlocking { app.settingsRepository.settingsFlow.first() }
        assertEquals("08:00", updatedSettings.startTime)

        composeTestRule.onNodeWithTag("profile_screen_list")
            .performScrollToNode(hasTestTag("profile_dark_map_chip_always_dark"))
        composeTestRule.onNodeWithTag("profile_dark_map_chip_always_dark")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitUntil(5_000) {
            runBlocking { app.settingsRepository.settingsFlow.first().mapDarkMode == MapDarkMode.ALWAYS_DARK }
        }
        val updatedDarkMap = runBlocking { app.settingsRepository.settingsFlow.first() }
        assertEquals(MapDarkMode.ALWAYS_DARK, updatedDarkMap.mapDarkMode)
    }
}
