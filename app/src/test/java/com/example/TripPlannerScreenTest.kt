package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.RouteBuilderScreen
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteBuilderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripPlannerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPlannerLoadsTrailInTrailMode() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }

        val viewModel = RouteBuilderViewModel(app)

        composeTestRule.setContent {
            RouteBuilderScreen(
                baseTrailId = "trail_ohavsstien",
                viewModel = viewModel,
                onBack = {},
                onSavedSuccessfully = {},
                onStartNavigation = {}
            )
        }

        composeTestRule.waitForIdle()
        assertEquals(PlannerMode.TRAIL, viewModel.uiState.value.plannerMode)
        assertEquals("trail_ohavsstien", viewModel.uiState.value.baseTrailId)
        composeTestRule.onNodeWithTag("planner_top_rename_chip").assertExists()
        composeTestRule.onNodeWithTag("planner_top_stats_chip").assertExists()
        composeTestRule.onNodeWithText("Øhavsstien", substring = true).assertExists()
    }

    @Test
    fun testPlannerOpensInFreeModeWhenNoTrailSpecified() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val viewModel = RouteBuilderViewModel(app)

        composeTestRule.setContent {
            RouteBuilderScreen(
                viewModel = viewModel,
                onBack = {},
                onSavedSuccessfully = {},
                onStartNavigation = {}
            )
        }

        composeTestRule.waitForIdle()
        assertEquals(PlannerMode.FREE, viewModel.uiState.value.plannerMode)
        composeTestRule.onNodeWithTag("planner_top_rename_chip").assertExists()
        composeTestRule.onNodeWithTag("planner_top_stats_chip").assertExists()
        composeTestRule.onNodeWithText("Ny rute", substring = true).assertExists()
    }
}

