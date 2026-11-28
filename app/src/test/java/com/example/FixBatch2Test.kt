package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SettingsRepository
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TripStage
import com.example.service.NavigationLiveState
import com.example.ui.screens.NavigationFollowMeChip
import com.example.ui.screens.NavigationReplaceTripDialog
import com.example.ui.screens.navWakeKey
import com.example.ui.screens.overviewBounds
import com.example.ui.screens.sameRefs
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.HEADING_MIN_CHANGE_DEG
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.ReplaceTripPrompt
import com.example.ui.viewmodel.headingDeltaDegrees
import com.example.ui.viewmodel.replaceTripPromptFor
import com.example.util.GeoUtils
import com.example.util.RouteTracker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Audit fix batch 2 – navigation map and battery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixBatch2Test {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ui: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example/ui")

    /** A wiggly 30 km route, a point every ~50 m, so brute force and the index can be compared. */
    private fun longRoute(): PlannedRoute {
        val pts = ArrayList<RoutePoint>()
        var km = 0.0
        var prev: Pair<Double, Double>? = null
        for (i in 0..600) {
            val lat = 55.0 + i * 0.00045
            val lng = 10.0 + kotlin.math.sin(i / 25.0) * 0.01
            prev?.let { km += GeoUtils.distanceMeters(it.first, it.second, lat, lng) / 1000.0 }
            pts.add(RoutePoint(lat = lat, lng = lng, walkKm = km, trailKm = km))
            prev = lat to lng
        }
        return PlannedRoute(
            planId = 1L, trailId = "t", trailName = "T", isReverse = false, startTrailKm = 0.0, endTrailKm = km,
            totalWalkKm = km, points = pts, stages = emptyList()
        )
    }

    private fun bruteForceDistanceM(route: PlannedRoute, lat: Double, lng: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until route.points.size - 1) {
            val a = route.points[i]; val b = route.points[i + 1]
            val cosLat = kotlin.math.cos(Math.toRadians((a.lat + lat) / 2.0))
            val px = (lng - a.lng) * cosLat * 111320.0; val py = (lat - a.lat) * 110540.0
            val bx = (b.lng - a.lng) * cosLat * 111320.0; val by = (b.lat - a.lat) * 110540.0
            val t = (((px * bx + py * by) / (bx * bx + by * by))).coerceIn(0.0, 1.0)
            best = minOf(best, GeoUtils.distanceMeters(lat, lng, a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng)))
        }
        return best
    }

    @Test
    fun offRoute_distanceFromTheIndexedSearch_matchesTheFullScan() {
        val route = longRoute()
        val tracker = RouteTracker(route)
        tracker.onLocationUpdate(55.0, 10.0, 0f, 5f, 1_000L)
        // Far from the expected window (about 20 km on) and 120 m beside the line: global search path
        val probes = listOf(55.0 + 400 * 0.00045 to 10.0 + kotlin.math.sin(400 / 25.0) * 0.01 + 0.0019, 55.12 to 10.03, 55.05 to 9.99)
        probes.forEachIndexed { n, (lat, lng) ->
            val r = tracker.onLocationUpdate(lat, lng, 0f, 5f, 2_000L + n * 2_000L)
            assertEquals("probe $n", bruteForceDistanceM(route, lat, lng), r.offRouteDistanceM, 1.0)
        }
    }

    @Test
    fun jumpAlongTheRoute_isStillAcceptedAfterThreeAgreeingFixes() {
        val route = longRoute()
        val tracker = RouteTracker(route)
        tracker.onLocationUpdate(55.0, 10.0, 0f, 5f, 1_000L)
        val i = 450
        val lat = 55.0 + i * 0.00045
        val lng = 10.0 + kotlin.math.sin(i / 25.0) * 0.01
        repeat(3) { tracker.onLocationUpdate(lat, lng, 0f, 5f, 3_000L + it * 2_000L) }
        assertEquals(route.points[i].walkKm, tracker.currentWalkKm, 0.05)
    }

    @Test
    fun compassHeading_smallJitterIsNotPublished() {
        assertEquals(2f, HEADING_MIN_CHANGE_DEG)
        assertEquals(4f, headingDeltaDegrees(358f, 2f), 1e-4f)
        assertEquals(180f, headingDeltaDegrees(0f, 180f), 1e-4f)
        val vm = NavigationViewModel(app)
        vm.updateDeviceHeading(100f)
        vm.updateDeviceHeading(101.5f)
        assertEquals(100f, vm.deviceHeadingDegrees.value!!, 0f)
        vm.updateDeviceHeading(102.5f)
        assertEquals(102.5f, vm.deviceHeadingDegrees.value!!, 0f)
        vm.updateDeviceHeading(361f) // wraps to 1°: a big change
        assertEquals(1f, vm.deviceHeadingDegrees.value!!, 1e-4f)
    }

    @Test
    fun compass_isTiedToTheLifecycle() {
        val src = ui.resolve("screens/NavigationCueAndOffRouteBanners.kt").readText()
        assertTrue(src.contains("Lifecycle.Event.ON_PAUSE -> unregister()"))
        assertTrue(src.contains("Lifecycle.Event.ON_RESUME -> register()"))
    }

    @Test
    fun camera_leavesTheMapAloneOnceTheUserMovesIt() {
        val follow = ui.resolve("screens/NavigationMapFollow.kt").readText()
        assertTrue(follow.contains("if (mapMode == NavigationMapMode.OVERVIEW || !isFollowingUser) return@LaunchedEffect"))
        val nav = ui.resolve("screens/NavigationScreen.kt").readText()
        assertFalse("the old always-follow camera effect is gone", nav.contains("NavigationMapMode.FOLLOW_NORTH_UP -> {\n                isFollowingUser = true"))
        assertTrue(nav.contains("NavigationCameraEffects("))
    }

    @Test
    fun overview_fitsTheDaysStretch() {
        val route = longRoute()
        val stage = TripStage(dayNumber = 1, startKm = 0.0, endKm = 5.0, stageKm = 5.0, stopName = "A", stopType = "SHELTER", stopLat = 55.0, stopLng = 10.0)
        val box = overviewBounds(route, stage, 1)!!
        assertTrue(box.latNorth < 55.0 + 600 * 0.00045)
        assertTrue(box.latSouth <= 55.0)
        assertNull(overviewBounds(null, null, 1))
    }

    @Test
    fun followMeChip_showsAndRefollows() {
        var clicked = false
        compose.setContent { DanskStiTheme { NavigationFollowMeChip(visible = true, onClick = { clicked = true }) } }
        compose.onNodeWithText("Følg mig").assertExists().performClick()
        assertTrue(clicked)
    }

    @Test
    fun replaceTrip_isOnlyAskedForADifferentTrackedTrip() {
        val live = NavigationLiveState(isTracking = true, planId = 7L, trailName = "Øhavsstien")
        assertEquals(ReplaceTripPrompt(7L, "Øhavsstien", 9L), replaceTripPromptFor(9L, live))
        assertNull("same trip", replaceTripPromptFor(7L, live))
        assertNull("nothing tracked", replaceTripPromptFor(9L, live.copy(isTracking = false)))
        var replaced = false
        var kept = false
        compose.setContent {
            DanskStiTheme { NavigationReplaceTripDialog(ReplaceTripPrompt(7L, "Øhavsstien", 9L), onReplace = { replaced = true }, onKeepActive = { kept = true }) }
        }
        compose.onNodeWithText("Øhavsstien spores lige nu. Vil du afslutte den og starte den nye tur?").assertExists()
        compose.onNodeWithTag("nav_replace_trip_keep").performClick()
        assertTrue(kept)
        compose.onNodeWithTag("nav_replace_trip_confirm").performClick()
        assertTrue(replaced)
    }

    @Test
    fun dimming_isOffByDefault_andWakesForCuesAndWarnings() = runBlocking {
        val repo = SettingsRepository(app)
        assertFalse(repo.settingsFlow.first().dimScreenWhenIdle)
        repo.setDimScreenWhenIdle(true)
        assertTrue(repo.settingsFlow.first { it.dimScreenWhenIdle }.dimScreenWhenIdle)
        assertNull(navWakeKey(NavigationLiveState()))
        assertNotNull(navWakeKey(NavigationLiveState(isOffTrailWarning = true)))
        assertNotNull(navWakeKey(NavigationLiveState(hasArrivedAtTonightCamp = true, currentDay = 2)))
        val nav = ui.resolve("screens/NavigationScreen.kt").readText()
        assertFalse("no more forced 15 %", nav.contains("0.15f"))
    }

    @Test
    fun overlayPois_areOnlyResentWhenTheyChange() {
        val a = listOf<Any?>("x", 1)
        assertTrue(sameRefs(a, a.toList()))
        assertFalse(sameRefs(a, listOf("x", 2)))
        assertFalse(sameRefs(a, null))
        assertTrue(ui.resolve("screens/NavigationScreen.kt").readText().contains("if (!sameRefs(poiInputs, lastOverlayPoiInputs[0]))"))
    }
}
