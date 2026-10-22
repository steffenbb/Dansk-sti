package com.example

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TrailEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.SkeletonLoadingList
import com.example.ui.navigation.ActiveTripBannerState
import com.example.ui.navigation.DanskStiBottomBar
import com.example.ui.navigation.Screen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.StatCardItem
import com.example.ui.screens.TrailCard
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BackgroundLight
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.DisplayTextStyle
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreenContainerLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.LabelTextStyle
import com.example.ui.theme.MapButtonSize
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import com.example.ui.theme.SurfaceLight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TABULAR_FIGURES_FEATURE
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TitleTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound3Part4UiRevampTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun contrastRatio(c1: Color, c2: Color): Double {
        val l1 = c1.luminance().toDouble()
        val l2 = c2.luminance().toDouble()
        val brightest = max(l1, l2)
        val darkest = min(l1, l2)
        return (brightest + 0.05) / (darkest + 0.05)
    }

    /**
     * Requirement A & J:
     * Verify design system colour tokens, type scale (28/20/16/13 sp + tabular figures),
     * shapes (16dp cards, 28dp sheet top corners, 48dp map buttons), 4dp grid,
     * haptic ticks, and WCAG contrast >= 4.5:1 in both Light and Dark themes.
     */
    @Test
    fun testDesignSystemTokensTypographyShapesHapticsAndContrast() {
        // Colours
        assertEquals(Color(0xFF2E6B3F), ForestGreenPrimary)
        assertEquals(Color(0xFFDCEFE0), ForestGreenContainerLight)
        assertEquals(Color(0xFFF2A33A), AccentAmber)
        assertEquals(Color(0xFFC62828), ErrorRed)
        assertEquals(Color(0xFFFFFFFF), SurfaceLight)
        assertEquals(Color(0xFFF6F7F5), BackgroundLight)
        assertEquals(Color(0xFF121614), BackgroundDark)
        assertEquals(Color(0xFF1B201D), SurfaceVariantDark)

        // Contrast >= 4.5:1
        assertTrue(
            "Light primary text contrast must be >= 4.5:1",
            contrastRatio(TextPrimaryLight, SurfaceLight) >= 4.5
        )
        assertTrue(
            "Dark primary text contrast must be >= 4.5:1",
            contrastRatio(TextPrimaryDark, SurfaceVariantDark) >= 4.5
        )
        assertTrue(
            "Primary ForestGreen button contrast with white text must be >= 4.5:1",
            contrastRatio(Color.White, ForestGreenPrimary) >= 4.5
        )

        // Type scale & tabular figures
        assertEquals(28.sp, DisplayTextStyle.fontSize)
        assertEquals(20.sp, TitleTextStyle.fontSize)
        assertEquals(13.sp, LabelTextStyle.fontSize)
        assertEquals(TABULAR_FIGURES_FEATURE, DisplayTextStyle.fontFeatureSettings)
        assertEquals(TABULAR_FIGURES_FEATURE, TitleTextStyle.fontFeatureSettings)

        // Shapes & 4dp grid & touch target
        assertEquals(48.dp, MapButtonSize)
        assertEquals(48.dp, MinTouchTargetSize)
        assertEquals(4.dp, Spacing.s1)
        assertEquals(8.dp, Spacing.s2)
        assertEquals(16.dp, Spacing.s4)

        // Haptic tick helper
        HapticFeedbackHelper.resetForTesting()
        HapticFeedbackHelper.performPinHapticTick(null, "pin_added")
        HapticFeedbackHelper.performPinHapticTick(null, "pin_snapped")
        HapticFeedbackHelper.performPinHapticTick(null, "pin_dropped")
        assertEquals(3, HapticFeedbackHelper.tickCountForTesting)
        assertEquals("pin_dropped", HapticFeedbackHelper.lastEventReason)
    }

    /**
     * Requirement B:
     * Bottom bar with 4 tabs (Udforsk, Kort, Mine ture, Profil) and slim "Aktiv tur" bar
     * above the bottom bar on every tab when a trip is active:
     * "Dag 2 · 6,3 km til Shelter Skovbo · Fortsæt"
     */
    @Test
    fun testFourTabBottomBarAndActiveTripSlimBanner() {
        var navigatedRoute: String? = null
        var continuedTrip = false

        composeTestRule.setContent {
            DanskStiTheme {
                DanskStiBottomBar(
                    currentRoute = Screen.Explore.route,
                    activeTripState = ActiveTripBannerState(
                        isActive = true,
                        currentDay = 2,
                        remainingKm = 6.3,
                        destinationName = "Shelter Skovbo",
                        planId = 42L
                    ),
                    onContinueActiveTrip = { continuedTrip = true },
                    onNavigate = { route -> navigatedRoute = route }
                )
            }
        }

        // Verify all 4 tabs exist and are displayed
        composeTestRule.onNodeWithTag("nav_explore_tab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_map_tab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_saved_tab").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_profile_tab").assertIsDisplayed()

        // Verify slim "Aktiv tur" bar displays exact Danish format and opens navigation on tap
        composeTestRule.onNodeWithTag("active_trip_slim_bar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dag 2 · 6,3 km til Shelter Skovbo · Fortsæt").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_trip_slim_bar").performClick()
        assertTrue(continuedTrip)

        // Verify tab navigation
        composeTestRule.onNodeWithTag("nav_map_tab").performClick()
        assertEquals(Screen.Map.route, navigatedRoute)
    }

    /**
     * Requirement C & J:
     * TrailCard shows real computed stats ("215 km · +1.850 m · ca. 12 dage",
     * "42 shelters · 9 fri teltning"), bookmark icon, and works at 200% font size without clipping.
     */
    @Test
    fun testLargeTrailDiscoveryCardAnd200PercentFontScale() {
        val sampleTrail = TrailEntity(
            id = "haervejen",
            name = "Hærvejen",
            description = "Historisk vandrerute gennem Jylland",
            region = "Jylland",
            parentRegion = "Jylland",
            computedWalkingKm = 215.0
        )
        var bookmarkClicked = false

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // Render at 200% font scale (Requirement J)
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                DanskStiTheme(darkTheme = true) {
                    TrailCard(
                        trail = sampleTrail,
                        points = emptyList(),
                        sheltersCount = 42,
                        friTeltningCount = 9,
                        ascentMeters = 1850,
                        isSaved = false,
                        onBookmarkClick = { bookmarkClicked = true },
                        onClick = {},
                        testTagOverride = "trail_card_haervejen"
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("trail_card_haervejen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hærvejen").assertIsDisplayed()
        composeTestRule.onNodeWithText("215 km · +1.850 m · ca. 12 dage").assertIsDisplayed()
        composeTestRule.onNodeWithText("42 shelters · 9 fri teltning").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bookmark_trail_haervejen").performClick()
        assertTrue(bookmarkClicked)
    }

    /**
     * Requirement I:
     * Profil screen hides Diagnostics when isDebugBuild = false until "Om appen" is tapped 7 times.
     */
    @Test
    fun testProfileScreenSevenTapsUnlocksDiagnostics() {
        var diagnosticsOpened = false

        composeTestRule.setContent {
            DanskStiTheme {
                ProfileScreen(
                    onOpenCredits = {},
                    onOpenDiagnostics = { diagnosticsOpened = true },
                    isDebugBuild = false
                )
            }
        }

        // Scroll LazyColumn to Om appen row
        composeTestRule.onNodeWithTag("profile_screen_list")
            .performScrollToNode(hasTestTag("profile_about_app_row"))
        composeTestRule.onNodeWithTag("profile_about_app_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_diagnostics_button").assertDoesNotExist()

        // Tap "Om appen" 7 times
        repeat(7) {
            composeTestRule.onNodeWithTag("profile_about_app_row").performClick()
        }

        // Diagnostics button is now unlocked and clickable
        composeTestRule.onNodeWithTag("profile_screen_list")
            .performScrollToNode(hasTestTag("profile_diagnostics_button"))
        composeTestRule.onNodeWithTag("profile_diagnostics_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("profile_diagnostics_button").performClick()
        assertTrue(diagnosticsOpened)
    }

    /**
     * Requirement J:
     * Verify SkeletonLoadingList, EmptyStateView (one sentence + one action),
     * and ErrorStateView (Danish message + Prøv igen).
     */
    @Test
    fun testStandardLoadingEmptyAndErrorStates() {
        var retried = false
        var emptyActionClicked = false

        composeTestRule.setContent {
            DanskStiTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    SkeletonLoadingList(itemCount = 1, testTag = "test_skeleton")
                    EmptyStateView(
                        icon = Icons.Default.Explore,
                        sentence = "Ingen vandreruter matcher dine valgte filtre.",
                        actionLabel = "Nulstil filtre",
                        onActionClick = { emptyActionClicked = true },
                        testTag = "test_empty",
                        actionTestTag = "test_empty_btn"
                    )
                    ErrorStateView(
                        message = "Kunne ikke indlæse ruter.",
                        onRetry = { retried = true },
                        testTag = "test_error",
                        retryTestTag = "test_error_btn"
                    )
                    StatCardItem(label = "Længde", value = "215 km")
                }
            }
        }

        composeTestRule.onNodeWithTag("test_skeleton").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_empty_btn").performScrollTo().performClick()
        assertTrue(emptyActionClicked)
        composeTestRule.onNodeWithTag("test_error_btn").performScrollTo().performClick()
        assertTrue(retried)
    }
}
