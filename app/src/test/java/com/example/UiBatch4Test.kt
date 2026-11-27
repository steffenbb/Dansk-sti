package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.CelebrationCheck
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.HapticKind
import com.example.ui.map.UserLocationHeadingOverlay
import com.example.ui.navigation.PoiClusterOverlay
import com.example.ui.theme.DanskStiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/** UI audit batch 4 – map polish and celebrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class UiBatch4Test {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ui: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example/ui")

    @Test
    fun selectedMarker_growsWithOvershoot_thenSettlesAtFullSize() {
        val overlay = PoiClusterOverlay(app)
        assertEquals("nothing selected yet", 1f, overlay.selectedScale(), 0f)
        overlay.selectedPoiId = "shelter_1"
        val start = android.os.SystemClock.uptimeMillis()
        assertEquals(0.8f, overlay.selectedScale(start), 0.01f)
        val peak = (0..PoiClusterOverlay.SELECT_ANIM_MS).maxOf { overlay.selectedScale(start + it) }
        assertTrue("slight overshoot ($peak)", peak > 1f && peak < 1.15f)
        assertEquals(1f, overlay.selectedScale(start + PoiClusterOverlay.SELECT_ANIM_MS), 0f)
    }

    @Test
    fun locationDot_glidesBetweenNearbyFixes_andJumpsOnBigMoves() {
        val dot = UserLocationHeadingOverlay()
        dot.updateLocation(GeoPoint(55.0, 10.0), null, 10f)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        val near = GeoPoint(55.0005, 10.0) // ~55 m on
        dot.updateLocation(near, null, 10f)
        val now = android.os.SystemClock.uptimeMillis()
        val mid = dot.displayedPosition(now + UserLocationHeadingOverlay.GLIDE_MS / 2)!!
        assertTrue("half way it is between the fixes (${mid.latitude})", mid.latitude > 55.0 && mid.latitude < 55.0005)
        assertEquals(55.0005, dot.displayedPosition(now + UserLocationHeadingOverlay.GLIDE_MS)!!.latitude, 1e-9)

        val far = GeoPoint(55.1, 10.0) // ~11 km: no glide
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        dot.updateLocation(far, null, 10f)
        assertEquals(55.1, dot.displayedPosition()!!.latitude, 1e-9)
    }

    @Test
    fun celebration_givesOneConfirmHaptic() {
        HapticFeedbackHelper.resetForTesting()
        compose.setContent { DanskStiTheme { CelebrationCheck(reason = "trip_completed") } }
        compose.waitForIdle()
        compose.onNodeWithTag("celebration_check").assertExists()
        assertEquals(HapticKind.CONFIRM, HapticFeedbackHelper.lastKindForTesting)
        assertEquals("trip_completed", HapticFeedbackHelper.lastEventReason)
    }

    @Test
    fun celebrationsLiveDotAndCoachMarks_areWired() {
        fun src(path: String) = ui.resolve(path).readText()
        val nav = src("screens/NavigationBatteryAndSosOverlays.kt")
        assertTrue(nav.contains("CelebrationCheck(reason = \"trip_completed\")"))
        assertTrue(nav.contains("CelebrationCheck(reason = \"camp_arrival\""))
        assertTrue("trip summary counts up", nav.contains("countUp(totalDistanceKm)"))
        val bar = src("navigation/DanskStiBottomBar.kt")
        assertTrue(bar.contains("LiveDot()") && bar.contains("rememberAnimationsEnabled()"))
        assertTrue(src("screens/planner/RouteBuilderSelectionAndCoachOverlays.kt").contains(".appearOnce()"))
    }
}
