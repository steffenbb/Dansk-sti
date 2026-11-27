package com.example

import android.app.Application
import android.view.HapticFeedbackConstants
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.AppMessages
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.HapticKind
import com.example.ui.navigation.Screen
import com.example.ui.navigation.ScreenTransitions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** UI audit batch 1 – foundations and polish fixes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "da")
class UiBatch1FoundationsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun haptics_mapToNewConstants_withFallbacksOnOlderAndroid() {
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackHelper.constantFor(HapticKind.TICK, 34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackHelper.constantFor(HapticKind.TICK, 24))
        assertEquals(HapticFeedbackConstants.CONFIRM, HapticFeedbackHelper.constantFor(HapticKind.CONFIRM, 30))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackHelper.constantFor(HapticKind.CONFIRM, 29))
        assertEquals(HapticFeedbackConstants.REJECT, HapticFeedbackHelper.constantFor(HapticKind.REJECT, 30))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackHelper.constantFor(HapticKind.REJECT, 26))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, HapticFeedbackHelper.constantFor(HapticKind.THRESHOLD, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackHelper.constantFor(HapticKind.THRESHOLD, 28))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackHelper.constantFor(HapticKind.HEAVY, 34))
    }

    @Test
    fun pinTick_stillCounted_andNoLongerOverridesTheUsersSetting() {
        HapticFeedbackHelper.resetForTesting()
        HapticFeedbackHelper.performPinHapticTick(null, "pin_dropped")
        assertEquals(1, HapticFeedbackHelper.tickCountForTesting)
        assertEquals("pin_dropped", HapticFeedbackHelper.lastEventReason)
        assertEquals(HapticKind.TICK, HapticFeedbackHelper.lastKindForTesting)
        val src = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
            .resolve("java/com/example/ui/components/CommonComponents.kt").readText()
        assertFalse("must respect the phone's touch-feedback setting", src.contains("FLAG_IGNORE_GLOBAL_SETTING"))
    }

    @Test
    fun screenTransitions_tabsFadeThrough_otherScreensSlide() {
        assertTrue(ScreenTransitions.isTabSwitch(Screen.Explore.route, Screen.Saved.route))
        assertTrue(ScreenTransitions.isTabSwitch(Screen.Map.route, Screen.Profile.route))
        assertFalse(ScreenTransitions.isTabSwitch(Screen.Explore.route, Screen.TrailDetail.route))
        assertFalse(ScreenTransitions.isTabSwitch(Screen.RouteBuilder.route, Screen.Explore.route))
        assertFalse(ScreenTransitions.isTabSwitch(null, Screen.Explore.route))
    }

    @Test
    fun appMessages_reachTheSnackbarCollector_andIgnoreBlank() = runBlocking {
        val received = kotlinx.coroutines.CompletableDeferred<com.example.ui.components.AppMessage>()
        val job = launch { received.complete(AppMessages.messages.first()) }
        yield()
        AppMessages.show("   ")
        AppMessages.show("Kunne ikke læse den valgte fil", long = true)
        val msg = received.await()
        assertEquals("Kunne ikke læse den valgte fil", msg.text)
        assertTrue(msg.long)
        job.cancel()
    }

    @Test
    fun formerlyHardcodedTexts_areTranslated() {
        fun ctx(lang: String) = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply { setLocale(Locale(lang)) })
        assertEquals("Hent offline-kort for Øhavsstien (12 MB · 340 kortfliser)?",
            ctx("da").getString(R.string.trail_offline_confirm_question_fmt, "Øhavsstien", "12 MB", 340))
        assertEquals("Download offline maps for Øhavsstien (12 MB · 340 map tiles)?",
            ctx("en").getString(R.string.trail_offline_confirm_question_fmt, "Øhavsstien", "12 MB", 340))
        assertEquals("Camp", ctx("en").getString(R.string.nav_progress_camp_label))
        assertEquals("Teilen", ctx("de").getString(R.string.action_share))
        val src = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example/ui/screens")
        assertFalse(src.resolve("NavigationBatteryAndSosOverlays.kt").readText().contains("Text(\"Del\""))
        assertFalse(src.resolve("TrailDetailComponents.kt").readText().contains("\"Hent offline-kort for"))
    }

    @Test
    fun windowBackground_matchesComposeTheme_inBothModes() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        assertTrue(res.resolve("values/colors.xml").readText().contains("""<color name="window_background">#F6F7F5</color>"""))
        assertTrue(res.resolve("values-night/colors.xml").readText().contains("""<color name="window_background">#121614</color>"""))
        assertEquals(0xFFF6F7F5.toInt(), com.example.ui.theme.BackgroundLight.value.shr(32).toInt())
        assertEquals(0xFF121614.toInt(), com.example.ui.theme.BackgroundDark.value.shr(32).toInt())
    }
}
