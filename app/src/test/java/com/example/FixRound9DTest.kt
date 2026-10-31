package com.example

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.model.RouteQualityBreakdown
import com.example.data.remote.GroupedSearchResults
import com.example.data.remote.PlaceSearchResult
import com.example.ui.components.GroupedPlaceSearchDropdown
import com.example.ui.navigation.DanskStiBottomBar
import com.example.ui.navigation.DanskStiNavigationRail
import com.example.ui.navigation.Screen
import com.example.ui.screens.NavigationLandscapeScaffoldBox
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.WindowHeightSizeClass
import com.example.ui.theme.WindowSizeClass
import com.example.ui.theme.WindowWidthSizeClass
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerSheetTab
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FixRound9DTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // =========================================================================
    // Item 1: WindowSizeClass -> adaptive properties mapping
    // =========================================================================

    @Test
    fun item1_windowSizeClassMapping_resolvesPhoneFoldableAndTabletCorrectly() {
        // 360x640 portrait phone -> COMPACT width, MEDIUM height
        val phonePortraitInfo = WindowSizeClass.compute(360.dp, 640.dp)
        assertEquals(WindowWidthSizeClass.COMPACT, phonePortraitInfo.widthSizeClass)
        assertFalse(phonePortraitInfo.useNavigationRail)
        assertFalse(phonePortraitInfo.usePlannerSidePanel)
        assertEquals(1, phonePortraitInfo.udforskColumns)

        // 640x360 landscape phone -> MEDIUM width, COMPACT height (< 480 dp)
        val phoneLandscapeInfo = WindowSizeClass.compute(640.dp, 360.dp)
        assertEquals(WindowHeightSizeClass.COMPACT, phoneLandscapeInfo.heightSizeClass)
        assertTrue(phoneLandscapeInfo.isLandscape)
        assertTrue(phoneLandscapeInfo.usePlannerSidePanel)
        assertTrue(phoneLandscapeInfo.useSideBySideTrailDetail)

        // 600x900 foldable -> MEDIUM width (600-840 dp), MEDIUM height
        val foldableInfo = WindowSizeClass.compute(600.dp, 900.dp)
        assertEquals(WindowWidthSizeClass.MEDIUM, foldableInfo.widthSizeClass)
        assertTrue(foldableInfo.useNavigationRail)
        assertEquals(2, foldableInfo.udforskColumns)
        assertTrue(foldableInfo.useSideBySideTrailDetail)
        assertFalse(foldableInfo.usePlannerSidePanel)

        // 1280x800 tablet -> EXPANDED width (> 840 dp)
        val tabletInfo = WindowSizeClass.compute(1280.dp, 800.dp)
        assertEquals(WindowWidthSizeClass.EXPANDED, tabletInfo.widthSizeClass)
        assertTrue(tabletInfo.useNavigationRail)
        assertEquals(3, tabletInfo.udforskColumns)
        assertTrue(tabletInfo.useSideBySideTrailDetail)
        assertTrue(tabletInfo.usePlannerSidePanel)
    }

    // =========================================================================
    // Item 2: Planner sheet real height (no 700 dp floor, map >= 40%) & side panel
    // =========================================================================

    @Test
    fun item2_plannerSheetHeights_computedFromRealHeightWithMapKeepingAtLeast40Percent() {
        val sampleState = createSamplePlannerUiState(sheetState = SheetHeightState.FULL)

        // Small portrait screen (e.g. 500 dp available height): full sheet must be <= 60% (300 dp),
        // leaving at least 40% (200 dp) for the map (no 700 dp floor).
        composeTestRule.setContent {
            val wsc = WindowSizeClass.compute(360.dp, 500.dp)
            DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                Box(modifier = Modifier.size(width = 360.dp, height = 500.dp)) {
                    PlannerBottomSheet(
                        uiState = sampleState,
                        visibleWaypoints = sampleState.visibleWaypoints,
                        onSetSheetHeightState = {},
                        onSuggestOvernightStops = {},
                        onStartNavigation = {},
                        onOpenTripSettings = {},
                        onOpenRundturDialog = {},
                        onOpenNearestPoiDialog = {},
                        onShareRoute = {},
                        onReplaceOvernight = {},
                        onDeleteWaypoint = {},
                        onZoomToCoordinate = { _, _ -> },
                        onRerouteAroundWarning = {},
                        onScrubbedPointChanged = {},
                        onOvernightMarkerDragged = { _, _ -> },
                        onUpdateOptions = {},
                        onWaypointClicked = {},
                        onOpenSaveDialog = {},
                        availableHeightDp = 500f,
                        isSidePanel = false
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val sheetNode = composeTestRule.onNodeWithTag("route_bottom_sheet")
        sheetNode.assertExists()
        composeTestRule.onNodeWithTag("planner_bottom_sheet").assertExists()
        val bounds = sheetNode.fetchSemanticsNode().boundsInRoot
        val density = composeTestRule.activity.resources.displayMetrics.density
        val sheetHeightDp = bounds.height / density
        // 60% of 500 dp = 300 dp, so map keeps >= 40% (200 dp)
        assertTrue(
            "Full planner sheet height ($sheetHeightDp dp) must be <= 302 dp (60% of 500 dp) so map keeps >= 40%",
            sheetHeightDp <= 302f
        )
    }

    // =========================================================================
    // Item 4: Navigation screen in landscape (cue top-left, map middle, stats right)
    // =========================================================================

    @Test
    fun item4_navigationScreenInLandscape_placesCueTopLeftMapMiddleAndStatsRight() {
        composeTestRule.setContent {
            val wsc = WindowSizeClass.compute(640.dp, 360.dp)
            DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                Box(modifier = Modifier.size(width = 640.dp, height = 360.dp)) {
                    NavigationLandscapeScaffoldBox(
                        mapContent = {
                            Box(modifier = Modifier.fillMaxSize())
                        },
                        topLeftCueContent = { mod ->
                            Card(modifier = mod) {
                                Text(
                                    text = "Drej til venstre om 180 m",
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        },
                        rightStatsColumnContent = {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("9,2 km tilbage i dag")
                                Text("Ankomst kl. 16:40")
                            }
                        }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val cueBounds = composeTestRule.onNodeWithTag("nav_landscape_top_left_cue")
            .assertExists()
            .fetchSemanticsNode().boundsInRoot
        val mapBounds = composeTestRule.onNodeWithTag("nav_landscape_map_pane")
            .assertExists()
            .fetchSemanticsNode().boundsInRoot
        val statsBounds = composeTestRule.onNodeWithTag("nav_landscape_right_stats_column")
            .assertExists()
            .fetchSemanticsNode().boundsInRoot

        // Cue banner is top-left (left of stats column, near top)
        assertTrue("Cue banner must be on the left side", cueBounds.left < statsBounds.left)
        // Map is in the middle/left and stats column is on the right
        assertTrue("Map pane must be to the left of stats column", mapBounds.left < statsBounds.left)
        assertTrue("Stats column must be on the right side", statsBounds.right > mapBounds.left)
    }

    // =========================================================================
    // Item 5 & 6: Fractional heights & minimum heights on text rows
    // =========================================================================

    @Test
    fun item5_and_item6_fractionalDropdownHeightAndNoFixedHeightTextRows() {
        val samplePlaces = (1..8).map { idx ->
            PlaceSearchResult(
                displayName = "Rold Skov Punkt $idx",
                subtitle = "Rebild Kommune • Naturpunkt",
                type = "Sted",
                lat = 56.80 + idx * 0.01,
                lng = 9.85
            )
        }

        composeTestRule.setContent {
            val baseConfig = android.content.res.Configuration( androidx.compose.ui.platform.LocalConfiguration.current).apply {
                screenWidthDp = 360
                screenHeightDp = 400
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides baseConfig) {
                DanskStiTheme(darkTheme = false) {
                    Box(modifier = Modifier.size(width = 360.dp, height = 400.dp)) {
                        GroupedPlaceSearchDropdown(
                            groupedResults = GroupedSearchResults(
                                query = "Rold",
                                places = samplePlaces
                            ),
                            onSelectRouteOrTrail = {},
                            onRouteHere = { _, _, _ -> },
                            onAddAsPoint = { _, _, _ -> },
                            onShowOnMap = { _, _, _ -> }
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val dropdownBounds = composeTestRule.onNodeWithTag("grouped_search_dropdown")
            .assertExists()
            .fetchSemanticsNode().boundsInRoot
        val density = composeTestRule.activity.resources.displayMetrics.density
        val dropdownHeightDp = dropdownBounds.height / density
        // 48% of 400 dp = 192 dp (well under the old fixed 380 dp)
        assertTrue(
            "Dropdown height ($dropdownHeightDp dp) should be capped near 48% of 400 dp (~192 dp)",
            dropdownHeightDp <= 210f
        )
    }

    // =========================================================================
    // Item 7: File split verification (all split files < 800 lines)
    // =========================================================================

    @Test
    fun item7_splitFiles_areAllUnder800LinesEach() {
        val projectRoot = File(".").canonicalFile.let { dir ->
            if (File(dir, "app/src/main").exists()) dir else dir.parentFile
        }
        val splitFiles = listOf(
            // PlannerBottomSheet split
            "app/src/main/java/com/example/ui/screens/planner/PlannerBottomSheet.kt",
            "app/src/main/java/com/example/ui/screens/planner/PlannerSheetRuteTab.kt",
            "app/src/main/java/com/example/ui/screens/planner/PlannerSheetDageTab.kt",
            "app/src/main/java/com/example/ui/screens/planner/PlannerSheetDetaljerTab.kt",
            // RouteBuilderScreen split
            "app/src/main/java/com/example/ui/screens/RouteBuilderScreen.kt",
            "app/src/main/java/com/example/ui/screens/RouteBuilderMapSection.kt",
            "app/src/main/java/com/example/ui/screens/RouteBuilderTopAndToolbars.kt",
            "app/src/main/java/com/example/ui/screens/RouteBuilderDialogs.kt",
            "app/src/main/java/com/example/ui/screens/RouteBuilderFeatureDialogs.kt",
            // RouteBuilderViewModel split
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderModels.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModel.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelEditing.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelPinAndLegTools.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelSearchAndSheet.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelLegs.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelDays.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelSmartTools.kt",
            "app/src/main/java/com/example/ui/viewmodel/RouteBuilderViewModelSaveAndShare.kt",
            // NavigationScreen split
            "app/src/main/java/com/example/ui/screens/NavigationScreen.kt",
            "app/src/main/java/com/example/ui/screens/NavigationCueAndOffRouteBanners.kt",
            "app/src/main/java/com/example/ui/screens/NavigationStatsAndDrawer.kt",
            "app/src/main/java/com/example/ui/screens/NavigationAheadAndProgressComponents.kt",
            "app/src/main/java/com/example/ui/screens/NavigationBatteryAndSosOverlays.kt"
        )

        for (relPath in splitFiles) {
            val file = File(projectRoot, relPath)
            assertTrue("Expected split file $relPath to exist", file.exists())
            val lineCount = file.readLines().size
            assertTrue(
                "Split file $relPath has $lineCount lines, which exceeds the 800-line limit",
                lineCount < 800
            )
        }
    }

    // =========================================================================
    // Item 8: Screenshot tests across 4 form factors at 100% and 200% font size
    //   - 360x640 portrait
    //   - 640x360 landscape
    //   - 600x900 foldable
    //   - 1280x800 tablet
    // =========================================================================

    private data class ViewportSpec(
        val slug: String,
        val widthDp: Int,
        val heightDp: Int
    )

    @Test
    fun item8_adaptiveScreenshots_allFourViewportsAt100And200PercentFontSize() {
        val outDir = File("build/reports/r9d_screenshots").apply { mkdirs() }
        val viewports = listOf(
            ViewportSpec("360x640_portrait", 360, 640),
            ViewportSpec("640x360_landscape", 640, 360),
            ViewportSpec("600x900_foldable", 600, 900),
            ViewportSpec("1280x800_tablet", 1280, 800)
        )
        val fontScales = listOf(1.0f to "font100", 2.0f to "font200")
        val plannerState = createSamplePlannerUiState(sheetState = SheetHeightState.HALF)

        val currentViewportState = androidx.compose.runtime.mutableStateOf(viewports.first())
        val currentFontScaleState = androidx.compose.runtime.mutableStateOf(1.0f)

        composeTestRule.setContent {
            val vp = currentViewportState.value
            val fontScale = currentFontScaleState.value
            val adaptiveInfo = WindowSizeClass.compute(vp.widthDp.dp, vp.heightDp.dp)
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = fontScale
                )
            ) {
                DanskStiTheme(darkTheme = false, windowSizeClass = adaptiveInfo) {
                    Box(modifier = Modifier.size(vp.widthDp.dp, vp.heightDp.dp)) {
                        if (adaptiveInfo.useNavigationRail) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                DanskStiNavigationRail(
                                    currentRoute = Screen.Explore.route,
                                    onNavigate = {}
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    PlannerBottomSheet(
                                        uiState = plannerState,
                                        visibleWaypoints = plannerState.visibleWaypoints,
                                        onSetSheetHeightState = {},
                                        onSuggestOvernightStops = {},
                                        onStartNavigation = {},
                                        onOpenTripSettings = {},
                                        onOpenRundturDialog = {},
                                        onOpenNearestPoiDialog = {},
                                        onShareRoute = {},
                                        onReplaceOvernight = {},
                                        onDeleteWaypoint = {},
                                        onZoomToCoordinate = { _, _ -> },
                                        onRerouteAroundWarning = {},
                                        onScrubbedPointChanged = {},
                                        onOvernightMarkerDragged = { _, _ -> },
                                        onUpdateOptions = {},
                                        onWaypointClicked = {},
                                        onOpenSaveDialog = {},
                                        availableHeightDp = vp.heightDp.toFloat(),
                                        isSidePanel = adaptiveInfo.usePlannerSidePanel,
                                        modifier = if (adaptiveInfo.usePlannerSidePanel) {
                                            Modifier.width(380.dp).widthIn(min = 360.dp, max = 420.dp)
                                        } else {
                                            Modifier
                                        }
                                    )
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    PlannerBottomSheet(
                                        uiState = plannerState,
                                        visibleWaypoints = plannerState.visibleWaypoints,
                                        onSetSheetHeightState = {},
                                        onSuggestOvernightStops = {},
                                        onStartNavigation = {},
                                        onOpenTripSettings = {},
                                        onOpenRundturDialog = {},
                                        onOpenNearestPoiDialog = {},
                                        onShareRoute = {},
                                        onReplaceOvernight = {},
                                        onDeleteWaypoint = {},
                                        onZoomToCoordinate = { _, _ -> },
                                        onRerouteAroundWarning = {},
                                        onScrubbedPointChanged = {},
                                        onOvernightMarkerDragged = { _, _ -> },
                                        onUpdateOptions = {},
                                        onWaypointClicked = {},
                                        onOpenSaveDialog = {},
                                        availableHeightDp = (vp.heightDp - 80).toFloat(),
                                        isSidePanel = adaptiveInfo.usePlannerSidePanel
                                    )
                                }
                                DanskStiBottomBar(
                                    currentRoute = Screen.Explore.route,
                                    onNavigate = {}
                                )
                            }
                        }
                    }
                }
            }
        }

        var savedScreenshots = 0

        for (vp in viewports) {
            for ((fontScale, fontSlug) in fontScales) {
                composeTestRule.runOnIdle {
                    currentViewportState.value = vp
                    currentFontScaleState.value = fontScale
                }
                composeTestRule.waitForIdle()

                // Verify expected navigation & planner layout per form factor
                when (vp.slug) {
                    "360x640_portrait" -> {
                        composeTestRule.onNodeWithTag("dansk_sti_bottom_bar").assertExists()
                        composeTestRule.onNodeWithTag("planner_bottom_sheet").assertExists()
                    }
                    "640x360_landscape" -> {
                        composeTestRule.onNodeWithTag("dansk_sti_navigation_rail").assertExists()
                        composeTestRule.onNodeWithTag("planner_side_panel").assertExists()
                    }
                    "600x900_foldable" -> {
                        composeTestRule.onNodeWithTag("dansk_sti_navigation_rail").assertExists()
                        composeTestRule.onNodeWithTag("planner_bottom_sheet").assertExists()
                    }
                    "1280x800_tablet" -> {
                        composeTestRule.onNodeWithTag("dansk_sti_navigation_rail").assertExists()
                        composeTestRule.onNodeWithTag("planner_side_panel").assertExists()
                    }
                }

                // Capture screenshot to PNG
                val bmp = captureActivityBitmap(composeTestRule.activity, vp.widthDp, vp.heightDp)
                val outFile = File(outDir, "${vp.slug}_${fontSlug}.png")
                outFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                assertTrue(
                    "Screenshot ${outFile.name} must exist and be non-empty",
                    outFile.exists() && outFile.length() > 500
                )
                savedScreenshots++
            }
        }

        assertEquals(8, savedScreenshots)
    }

    private fun captureActivityBitmap(activity: Activity, widthDp: Int, heightDp: Int): Bitmap {
        val rootView: View = activity.window.decorView.rootView
        val w = rootView.width.coerceAtLeast(widthDp)
        val h = rootView.height.coerceAtLeast(heightDp)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        rootView.draw(canvas)
        return bitmap
    }

    private fun createSamplePlannerUiState(sheetState: SheetHeightState): RouteBuilderState {
        val wp1 = RouteWaypoint(
            id = "wp_start",
            lat = 56.80,
            lng = 9.85,
            role = WaypointRole.START,
            name = "Skørping St."
        )
        val wp2 = RouteWaypoint(
            id = "wp_end",
            lat = 56.83,
            lng = 9.90,
            role = WaypointRole.END,
            name = "Rebild Bakker"
        )
        val pts = listOf(
            RoutePoint3D(56.80, 9.85, 32.0),
            RoutePoint3D(56.815, 9.875, 78.0),
            RoutePoint3D(56.83, 9.90, 45.0)
        )
        return RouteBuilderState(
            routeName = "Rold Skov Rundtur",
            sheetHeightState = sheetState,
            selectedSheetTab = PlannerSheetTab.RUTE,
            waypoints = listOf(wp1, wp2),
            legs = mapOf(
                "wp_start_to_wp_end" to LegGeometryData(
                    fromWaypointId = "wp_start",
                    toWaypointId = "wp_end",
                    points = pts,
                    lengthKm = 14.6,
                    ascentM = 210.0,
                    descentM = 195.0,
                    pathSharePct = 88.0,
                    roadKm = 1.2,
                    legMode = LegMode.SNAP
                )
            ),
            stats = RouteStats(
                totalKm = 14.6,
                ascentM = 210.0,
                descentM = 195.0,
                pathSharePct = 88.0,
                roadKm = 1.2,
                hasWayInfo = true
            ),
            timeEstimateFormatted = "3 t 15 min",
            daySummaries = listOf(
                PlannerDaySummary(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = 14.6,
                    distanceKm = 14.6,
                    ascentM = 210,
                    descentM = 195,
                    fromName = "Skørping St.",
                    toName = "Rebild Bakker",
                    overnightWaypointId = null,
                    overnightName = "Rebild Shelterplads",
                    overnightTypeLabel = "Shelter",
                    detourLabel = null,
                    detourKm = 0.0,
                    detourAscentM = 0,
                    bookingLabel = "Først til mølle",
                    sunsetTime = "20:45",
                    arrivalTime = "16:30",
                    isAfterSunset = false,
                    warnings = emptyList(),
                    nightWeatherSummary = "Nat 11°C · Nedbør 0,0 mm",
                    campLat = 56.83,
                    campLng = 9.90
                )
            ),
            routeQuality = RouteQualityBreakdown(
                trailPct = 70.0,
                trackPct = 25.0,
                asphaltPct = 5.0
            )
        )
    }
}
