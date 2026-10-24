package com.example

import android.Manifest
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.util.DanskStiTileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

@RunWith(AndroidJUnit4::class)
class OhavsstienNavigationInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        try {
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.ACCESS_FINE_LOCATION)
            instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (_: Exception) {
        }
        DanskStiTileSource.userConfirmedOsmFallback = true
        val app = instrumentation.targetContext.applicationContext as DanskStiApp
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @Test
    fun openOhavsstienPlanSaveStartAssertsRouteOverlayHasMoreThan100Points() {
        val app = composeTestRule.activity.application as DanskStiApp

        // 1. Open Øhavsstien from Udforsk
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("trail_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("trail_card_trail_ohavsstien"))
        composeTestRule.onNodeWithTag("trail_card_trail_ohavsstien").performClick()
        composeTestRule.waitForIdle()

        // 2. Tap "Planlæg tur" on Øhavsstien detail screen
        composeTestRule.waitUntil(30_000) {
            composeTestRule.onAllNodesWithTag("plan_trip_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("plan_trip_button").performClick()
        composeTestRule.waitForIdle()

        // 3. Wait for Øhavsstien geometry to load in RouteBuilderScreen and tap "Gem" -> "Gem til Gemte"
        composeTestRule.waitUntil(60_000) {
            val nodes = composeTestRule.onAllNodesWithTag("btn_save_route").fetchSemanticsNodes()
            nodes.isNotEmpty() &&
                !nodes.first().config.contains(SemanticsProperties.Disabled) &&
                runBlocking { app.repository.getTrailGeometry("trail_ohavsstien") != null }
        }
        composeTestRule.onNodeWithTag("btn_save_route").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("btn_confirm_save_route").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("btn_confirm_save_route").performClick()
        composeTestRule.waitForIdle()

        // 4. On "Mine ture" (SavedPlansScreen), tap "Start" on the saved Øhavsstien plan
        var savedPlanId = -1L
        composeTestRule.waitUntil(15_000) {
            val plans = runBlocking { app.database.dao().getAllTripPlansList() }
            val ohavsPlan = plans.firstOrNull { it.trailName.contains("Øhavsstien") || it.trailId.contains("ohavsstien") }
                ?: plans.firstOrNull()
            if (ohavsPlan != null) {
                savedPlanId = ohavsPlan.id
                true
            } else {
                false
            }
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("start_plan_$savedPlanId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("start_plan_$savedPlanId").performClick()
        composeTestRule.waitForIdle()

        // 5. Wait for NavigationScreen map view and assert its route overlay has > 100 points
        composeTestRule.waitUntil(20_000) {
            val mapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
            mapView != null && countRouteOverlayPoints(mapView.overlays) > 100
        }

        val navMapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
        val routePointCount = navMapView?.let { countRouteOverlayPoints(it.overlays) } ?: 0
        assertTrue(
            "Expected navigation map route overlay to have > 100 points, but had $routePointCount",
            routePointCount > 100
        )
    }

    private fun findMapViewByTag(root: View?, tag: String): MapView? {
        if (root == null) return null
        if (root is MapView && (root.tag == tag || tag.isEmpty())) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findMapViewByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun countRouteOverlayPoints(overlays: List<Overlay>): Int {
        var maxPolylinePoints = 0
        var totalWalkPolylinePoints = 0
        for (overlay in overlays) {
            when (overlay) {
                is Polyline -> {
                    val pts = overlay.actualPoints?.size ?: 0
                    if (pts > maxPolylinePoints) {
                        maxPolylinePoints = pts
                    }
                    totalWalkPolylinePoints += pts
                }
                is FolderOverlay -> {
                    val folderPts = countRouteOverlayPoints(overlay.items)
                    if (folderPts > maxPolylinePoints) {
                        maxPolylinePoints = folderPts
                    }
                }
            }
        }
        return maxOf(maxPolylinePoints, totalWalkPolylinePoints / 2)
    }
}
