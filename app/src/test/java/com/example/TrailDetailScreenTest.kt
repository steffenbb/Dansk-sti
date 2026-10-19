package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.viewmodel.TrailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrailDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testTrailDetailScreenRendersWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
        val viewModel = TrailViewModel(app)
        runBlocking {
            viewModel.loadTrail("trail_ohavsstien")
        }

        composeTestRule.setContent {
            TrailDetailScreen(
                trailId = "trail_ohavsstien",
                viewModel = viewModel,
                onBack = {},
                onPlanTrip = {},
                onStartNavigation = {},
                onOpenFullscreenMap = {},
                onDownloadOffline = {}
            )
        }

        composeTestRule.waitForIdle()
    }
}
