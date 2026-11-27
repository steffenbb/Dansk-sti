package com.example

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.data.repository.TideEvent
import com.example.ui.components.FacilityPill
import com.example.ui.components.FacilityPillRow
import com.example.ui.screens.TrailTideCard
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.countUp
import com.example.ui.viewmodel.TrailTideInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** UI audit batch 3 – data presentation. Same data, shown better. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class UiBatch3Test {

    @get:Rule
    val compose = createComposeRule()

    private val ui: File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }.resolve("java/com/example/ui")

    @Test
    fun countUp_endsOnTheExactValue() {
        compose.setContent {
            DanskStiTheme {
                Text(String.format(java.util.Locale.GERMANY, "%.1f km", countUp(12.34)), modifier = Modifier.testTag("km"))
                Text("+${countUp(487)} m", modifier = Modifier.testTag("asc"))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("km").assertTextEquals("12,3 km")
        compose.onNodeWithTag("asc").assertTextEquals("+487 m")
    }

    @Test
    fun facilityPills_showEachFacility_andReadAsTheFormerLine() {
        compose.setContent {
            DanskStiTheme {
                FacilityPillRow(
                    listOf(FacilityPill(Icons.Default.LocalFireDepartment, "Bålplads"), FacilityPill(Icons.Default.WaterDrop, "Vandhane")),
                    modifier = Modifier.testTag("pills")
                )
            }
        }
        val desc = compose.onNodeWithTag("pills").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Bålplads · Vandhane"), desc)
    }

    @Test
    fun tideCard_showsRealEventsOnTheTimeline_andKeepsTheTextLine() {
        val utc = { iso: String -> java.time.Instant.parse(iso).toEpochMilli() }
        val events = listOf(TideEvent(utc("2026-12-01T06:16:00Z"), true, 88.4), TideEvent(utc("2026-12-01T12:48:00Z"), false, -96.5))
        compose.setContent {
            DanskStiTheme {
                TrailTideCard(TrailTideInfo("Esbjerg Havn", 2.4, listOf(0 to "Højvande 07:16 (+88 cm) · Lavvande 13:48 (−97 cm)"), mapOf(0 to events)))
            }
        }
        compose.onNodeWithTag("trail_tide_timeline").assertExists()
        compose.onNodeWithText("Højvande 07:16 (+88 cm) · Lavvande 13:48 (−97 cm)", substring = true).assertExists()
    }

    @Test
    fun tideCard_withoutEvents_hasNoTimeline() {
        compose.setContent { DanskStiTheme { TrailTideCard(TrailTideInfo("Esbjerg Havn", 2.4, listOf(0 to "Højvande 07:16"))) } }
        compose.onNodeWithTag("trail_tide_timeline").assertDoesNotExist()
    }

    @Test
    fun presentationChanges_areWired() {
        fun src(path: String) = ui.resolve(path).readText()
        assertTrue("trail page reuses the planner's scrubbable chart", src("screens/TrailDetailComponents.kt").contains("RouteElevationProfileChart(points = chartPoints)"))
        assertTrue(src("components/RouteElevationProfileChart.kt").contains("Brush.verticalGradient"))
        assertTrue(src("screens/TrailDetailComponents.kt").contains(".revealOnce()"))
        val safety = src("screens/TrailDetailSafetyCard.kt")
        assertTrue(safety.contains("RestrictedMilitaryColor") && safety.contains("RestrictedReserveColor"))
        val items = ui.walkTopDown().filter { it.extension == "kt" }.sumOf { Regex("""Modifier\.animateItem\(\)""").findAll(it.readText()).count() }
        assertTrue("lazy lists animate their items ($items)", items >= 15)
        assertTrue(src("screens/TrailDetailContentSection.kt").contains("tabFade"))
        assertEquals(5, Regex("IconFactLine\\(").findAll(src("screens/planner/PlannerSheetDageTab.kt")).count())
        assertFalse("no drawn tide curve (RULES.md 1–2)", src("screens/TrailTideCard.kt").contains("cubicTo"))
    }
}
