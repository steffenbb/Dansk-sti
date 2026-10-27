package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.OvernightType
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.components.formatDanishElevationMeters
import com.example.ui.components.formatDanishKm
import com.example.ui.components.formatDownloadedDateDanish
import com.example.ui.screens.KortScreen
import com.example.ui.screens.RouteBuilderScreen
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TABULAR_FIGURES_FEATURE
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SavedViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import com.example.util.DanskStiTileSourceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiRound4PolishTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSource.userConfirmedOsmFallback = true
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
            app.settingsRepository.setPlannerCoachMarksSeen(false)
        }
    }

    @After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
        runBlocking {
            app.settingsRepository.setPlannerCoachMarksSeen(false)
        }
    }

    @Test
    fun test1_firstRunCoachMarksInPlanner_showsOnceAndPersistsInDataStore() {
        val viewModel = RouteBuilderViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                RouteBuilderScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onSavedSuccessfully = {}
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("planner_coach_marks").fetchSemanticsNodes().isNotEmpty()
        }

        // First run: coach marks card is displayed with 3 tips
        composeTestRule.onNodeWithTag("planner_coach_marks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tryk på kortet for at tilføje et punkt", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Træk i et punkt for at flytte det", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Træk i midten af en strækning", substring = true).assertIsDisplayed()

        // Dismiss coach marks
        composeTestRule.onNodeWithTag("coach_marks_dismiss_button").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("planner_coach_marks").fetchSemanticsNodes().isEmpty() &&
                runBlocking { app.settingsRepository.settingsFlow.first().plannerCoachMarksSeen }
        }

        // Card should disappear and DataStore should record plannerCoachMarksSeen = true
        assertEquals(0, composeTestRule.onAllNodesWithTag("planner_coach_marks").fetchSemanticsNodes().size)
        val seenInStore = runBlocking { app.settingsRepository.settingsFlow.first().plannerCoachMarksSeen }
        assertTrue("Expected plannerCoachMarksSeen to be true in DataStore after dismissal", seenInStore)
    }

    @Test
    fun test2_trailPageStartNu_createsPlanFromNearestPointOrStartAndNavigates() {
        val trailViewModel = TrailViewModel(app)
        val trailId = runBlocking {
            app.repository.getAllTrails().first().first().id
        }
        trailViewModel.loadTrail(trailId)

        var createdPlanId: Long? = null
        composeTestRule.setContent {
            DanskStiTheme {
                TrailDetailScreen(
                    trailId = trailId,
                    viewModel = trailViewModel,
                    onBack = {},
                    onPlanTrip = {},
                    onStartNavigation = { planId -> createdPlanId = planId },
                    onOpenFullscreenMap = {},
                    onDownloadOffline = {}
                )
            }
        }

        // Wait for trail details to load
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("start_now_button").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("plan_trip_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_now_button").assertIsDisplayed()

        // Click "Start nu" without GPS -> starts from trail start and creates a plan via StagePlanner
        composeTestRule.onNodeWithTag("start_now_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            createdPlanId != null
        }

        val planId = createdPlanId!!
        assertTrue("Expected valid planId > 0", planId > 0L)
        val savedPlan = runBlocking { app.repository.getTripPlanById(planId) }
        assertNotNull("Expected saved TripPlanEntity in database", savedPlan)
        assertEquals(trailId, savedPlan?.trailId)
    }

    @Test
    fun test3_undoSnackbarForEveryDelete_restoresDeletedItemsOnFortryd() = runBlocking {
        // A. Saved TripPlan delete + Fortryd restore
        val trail = app.repository.getAllTrails().first().first()
        val planId = app.repository.saveTripPlan(
            trail = trail,
            startKm = 0.0,
            endKm = 15.0,
            isReverse = false,
            days = 1,
            overnightType = OvernightType.BOTH,
            preferredDailyKm = 15.0,
            stages = emptyList()
        )
        val savedVm = SavedViewModel(app)
        var attempts = 0
        while (savedVm.savedPlans.value.none { it.id == planId } && attempts < 30) {
            delay(50)
            attempts++
        }
        val planToDelete = app.repository.getTripPlanById(planId)!!
        savedVm.deletePlanWithUndo(planToDelete)
        delay(200)
        assertNull("Plan should be deleted before undo", app.repository.getTripPlanById(planId))
        assertNotNull("lastDeletedItem should be populated", savedVm.lastDeletedItem.value)

        savedVm.undoLastDelete()
        delay(300)
        assertTrue(
            "Plan should be restored after Fortryd",
            app.repository.getAllTripPlans().first().any { it.trailId == trail.id }
        )

        // B. Custom Waypoint delete + Fortryd restore & Waypoint Photo delete + Fortryd restore
        val rbVm = RouteBuilderViewModel(app)
        delay(200)
        val routeId = rbVm.uiState.value.routeId ?: 0L
        val wpId = "test_wp_undo_1"
        val customWp = UserCustomWaypointEntity(
            id = wpId,
            routeId = routeId,
            lat = 56.10,
            lng = 9.55,
            name = "Udsigtspunkt",
            note = "Flot udsigt",
            photoPath = "/storage/emulated/0/test_photo.jpg"
        )
        app.repository.saveCustomWaypoint(customWp)

        // Delete photo & undo
        rbVm.deleteCustomWaypointPhoto(wpId)
        delay(200)
        val afterPhotoDelete = app.repository.getAllCustomWaypoints().find { it.id == wpId }
        assertNull("Photo path should be null after photo delete", afterPhotoDelete?.photoPath)
        assertEquals("Foto slettet · Fortryd", rbVm.uiState.value.deletedPinSnackbarMessage)

        rbVm.undoLastDelete()
        delay(200)
        val afterPhotoRestore = app.repository.getAllCustomWaypoints().find { it.id == wpId }
        assertEquals("/storage/emulated/0/test_photo.jpg", afterPhotoRestore?.photoPath)

        // Delete custom waypoint & undo
        rbVm.deleteCustomWaypoint(wpId)
        delay(200)
        assertNull("Custom waypoint should be deleted", app.repository.getAllCustomWaypoints().find { it.id == wpId })
        assertEquals("Punkt slettet · Fortryd", rbVm.uiState.value.deletedPinSnackbarMessage)

        rbVm.undoLastDelete()
        delay(200)
        assertNotNull("Custom waypoint should be restored on Fortryd", app.repository.getAllCustomWaypoints().find { it.id == wpId })
    }

    @Test
    fun test4_mapControlPositionsAndNoOverlapInPlanner() {
        val viewModel = RouteBuilderViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                RouteBuilderScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onSavedSuccessfully = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        val mapControlsBounds = composeTestRule.onNodeWithTag("map_controls_column")
            .fetchSemanticsNode().boundsInRoot
        val plannerOwnButtonsBounds = composeTestRule.onNodeWithTag("planner_own_buttons_column")
            .fetchSemanticsNode().boundsInRoot
        val modeToggleBounds = composeTestRule.onNodeWithTag("kig_tegn_toggle")
            .fetchSemanticsNode().boundsInRoot

        // Verify planner's own buttons do not overlap map_controls_column
        val overlapsOwnButtons =
            plannerOwnButtonsBounds.right > mapControlsBounds.left &&
                plannerOwnButtonsBounds.left < mapControlsBounds.right &&
                plannerOwnButtonsBounds.bottom > mapControlsBounds.top &&
                plannerOwnButtonsBounds.top < mapControlsBounds.bottom
        assertFalse("Expected planner_own_buttons_column not to overlap map_controls_column", overlapsOwnButtons)

        // Verify mode segmented toggle on top-start does not overlap map_controls_column on top-end
        val overlapsSegmented =
            modeToggleBounds.right > mapControlsBounds.left &&
                modeToggleBounds.bottom > mapControlsBounds.top
        assertFalse("Expected kig_tegn_toggle not to overlap map_controls_column", overlapsSegmented)
    }

    @Test
    fun test5_offlinePillAndDownloadedDateFormatting() {
        // Verify Danish downloaded date formatting ("Hentet <dato>")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Copenhagen")).apply {
            set(2026, Calendar.MAY, 14, 12, 0, 0)
        }
        val formattedDate = formatDownloadedDateDanish(cal.timeInMillis)
        assertTrue("Expected 'Hentet 14. maj 2026', got $formattedDate", formattedDate.startsWith("Hentet") && formattedDate.contains("14."))

        // Verify Offline pill appears on KortScreen when repository is offline
        app.repository.isOnlineOverrideForTests = false
        val exploreVm = ExploreViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                KortScreen(
                    viewModel = exploreVm,
                    onOpenPlanner = { _, _, _ -> },
                    onOpenOfflineMaps = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("offline_pill").assertIsDisplayed()
    }

    @Test
    fun test6_danishNumberFormattingAndTabularFigures() {
        assertEquals("tnum", TABULAR_FIGURES_FEATURE)
        assertEquals("12,4", formatDanishKm(12.4, decimals = 1))
        assertEquals("1.250", formatDanishElevationMeters(1250))
    }

    @Test
    fun test7_largeFontScale200Percent_rendersTrailWithoutClipping() {
        val trailViewModel = TrailViewModel(app)
        val trailId = runBlocking { app.repository.getAllTrails().first().first().id }
        trailViewModel.loadTrail(trailId)

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                DanskStiTheme {
                    TrailDetailScreen(
                        trailId = trailId,
                        viewModel = trailViewModel,
                        onBack = {},
                        onPlanTrip = {},
                        onStartNavigation = {},
                        onOpenFullscreenMap = {},
                        onDownloadOffline = {}
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("start_now_button").fetchSemanticsNodes().isNotEmpty()
        }

        val planBounds = composeTestRule.onNodeWithTag("plan_trip_button").fetchSemanticsNode().boundsInRoot
        val startBounds = composeTestRule.onNodeWithTag("start_now_button").fetchSemanticsNode().boundsInRoot
        assertTrue("Buttons should have non-zero height at 200% font scale", planBounds.height > 40f && startBounds.height > 40f)
    }
}
