package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.HapticKind
import com.example.ui.components.hapticClick
import com.example.ui.components.hapticToggle
import com.example.ui.theme.AnimatedBanner
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.springClickable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** UI audit batch 2 – motion and haptics. Behaviour is unchanged; these check the feedback is wired. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class UiBatch2Test {

    @get:Rule
    val compose = createComposeRule()

    private val ui: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example/ui")

    @Test
    fun hapticClickAndToggle_tickAndStillRunTheAction() {
        HapticFeedbackHelper.resetForTesting()
        var clicks = 0
        var toggled: Boolean? = null
        compose.setContent {
            DanskStiTheme {
                Column {
                    val click = hapticClick(onClick = { clicks++ })
                    val toggle = hapticToggle { toggled = it }
                    Box(Modifier.size(40.dp).testTag("btn").springClickable(onClick = click))
                    Box(Modifier.size(40.dp).testTag("sw").springClickable { toggle(true) })
                }
            }
        }
        compose.onNodeWithTag("btn").performClick()
        assertEquals(1, clicks)
        assertEquals(HapticKind.TICK, HapticFeedbackHelper.lastKindForTesting)
        HapticFeedbackHelper.resetForTesting()
        compose.onNodeWithTag("sw").performClick()
        assertEquals(true, toggled)
        assertEquals(HapticKind.TICK, HapticFeedbackHelper.lastKindForTesting)
    }

    @Test
    fun animatedBanner_showsAndHidesWithItsValue() {
        var message by mutableStateOf<String?>(null)
        compose.setContent { DanskStiTheme { Column { AnimatedBanner(message) { Text(it) } } } }
        compose.onNodeWithText("Militært område").assertDoesNotExist()
        message = "Militært område"
        compose.waitForIdle()
        compose.onNodeWithText("Militært område").assertExists()
        message = null
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Militært område").assertDoesNotExist()
    }

    @Test
    fun hapticMoments_areWired() {
        fun src(path: String) = ui.resolve(path).readText()
        assertTrue(src("screens/NavigationBatteryAndSosOverlays.kt").contains("HapticKind.HEAVY"))
        assertTrue(src("screens/RouteBuilderDialogs.kt").contains("HapticKind.CONFIRM"))
        assertTrue(src("components/PoiInfoSheet.kt").contains("HapticKind.CONFIRM, \"route_here\""))
        assertTrue(src("screens/TrailDetailScreen.kt").contains("HapticKind.REJECT, \"route_retry_failed\""))
        assertTrue(src("screens/TrailBrowserCards.kt").contains("HapticKind.CONFIRM, \"download_done\""))
        assertTrue(src("screens/SavedPlansLibraryCards.kt").contains("HapticKind.THRESHOLD"))
        assertEquals("all four library cards sink on press", 4, Regex("""springClickable\(0\.98f""").findAll(src("screens/SavedPlansLibraryCards.kt")).count())
        val toggles = ui.walkTopDown().filter { it.extension == "kt" }.sumOf { Regex("""hapticClick\(|hapticToggle[\s({]""").findAll(it.readText()).count() }
        assertTrue("chips and switches tick ($toggles)", toggles >= 20)
    }

    @Test
    fun navigationBanners_animateInsteadOfPopping() {
        val banners = ui.resolve("screens/NavigationCueAndOffRouteBanners.kt").readText()
        assertTrue(Regex("""Motion\.BannerEnter""").findAll(banners).count() >= 4)
        assertTrue(Regex("""AnimatedBanner\(""").findAll(banners).count() >= 3)
        assertTrue(banners.contains("animateColorAsState"))
        assertFalse("banners must not be duplicated by an AnimatedContent copy", banners.contains("AnimatedContent("))
    }
}
