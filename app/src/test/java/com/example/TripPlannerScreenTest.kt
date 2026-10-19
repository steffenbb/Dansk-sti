package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.TripPlannerScreen
import com.example.ui.viewmodel.PlannerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
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
    fun testTripPlannerLoadsTrailWithoutShowingIngenStiValgt() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }

        val viewModel = PlannerViewModel(app)
        runBlocking {
            viewModel.initTrail("trail_ohavsstien")
        }

        composeTestRule.setContent {
            TripPlannerScreen(
                trailId = "trail_ohavsstien",
                viewModel = viewModel,
                onBack = {},
                onStartTrip = {}
            )
        }

        composeTestRule.waitForIdle()
        assertNotNull("Trail should be selected in PlannerViewModel", viewModel.selectedTrail.value)
        composeTestRule.onNodeWithText("Ingen sti valgt").assertDoesNotExist()
    }

    @Test
    fun testTripPlannerShowsIngenStiValgtWhenTrailIdBlank() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val viewModel = PlannerViewModel(app)

        composeTestRule.setContent {
            TripPlannerScreen(
                trailId = "",
                viewModel = viewModel,
                onBack = {},
                onStartTrip = {}
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Ingen sti valgt").assertExists()
    }
}
