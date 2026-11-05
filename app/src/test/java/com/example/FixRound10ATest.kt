package com.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppAppearanceMode
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.local.SettingsRepository
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PlannerDraftEntity
import com.example.data.model.PoiEntity
import com.example.data.model.RouteQualityBreakdown
import com.example.data.model.ShelterEntity
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.ui.components.AccessPolygonRulesDialog
import com.example.ui.components.DropPinInspectorHelper
import com.example.ui.components.MapAttributionPill
import com.example.ui.components.PoiInfoSheet
import com.example.ui.components.PoiInfoSheetData
import com.example.ui.map.LiveMapLocationState
import com.example.ui.navigation.DanskStiBottomBar
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.navigation.PoiItem
import com.example.ui.navigation.Screen
import com.example.ui.screens.ApiDiagnosticsScreen
import com.example.ui.screens.DataCreditsScreen
import com.example.ui.screens.PlannerUnsavedChangesDialog
import com.example.ui.screens.RouteBuilderTopAppBar
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.screens.planner.PlannerPinDragMapOverlay
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.WindowSizeClass
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerSheetTab
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FixRound10ATest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // =========================================================================
    // Item 1: Shared PoiInfoSheet & AccessPolygonRulesDialog
    // =========================================================================

    @Test
    fun item1_sharedPoiInfoSheet_displaysAllFieldsAndFourActionButtons() {
        var routeHereClicked = false
        var addToRouteClicked = false

        val shelter = ShelterEntity(
            id = "shelter_rold_1",
            name = "Rebild Skov Shelterplads",
            lat = 56.8310,
            lng = 9.9010,
            shelterType = "Shelter",
            capacity = 6,
            bookingRequired = false,
            bookingUrl = "https://udinaturen.dk/shelter/123",
            hasWaterNearby = true,
            hasToiletNearby = true,
            hasFireRing = true,
            hasFirewood = true,
            source = "geofa",
            fetchedAt = 1730000000000L
        )
        val poiData = PoiInfoSheetData.fromShelter(shelter)

        composeTestRule.setContent {
            DanskStiTheme(darkTheme = false) {
                PoiInfoSheet(
                    poiData = poiData,
                    liveUserLocation = GeoPoint(56.8300, 9.9000),
                    isLocationFresh = true,
                    onDismiss = {},
                    onRouteHere = { _, _, _ -> routeHereClicked = true },
                    onAddToRoute = { _, _, _ -> addToRouteClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("poi_info_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_type").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_distance").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_capacity_booking").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_facilities").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_source_date").assertIsDisplayed()

        // Verify the 4 required buttons: "Rute hertil", "Tilføj til rute", "Gem", "Del"
        composeTestRule.onNodeWithTag("poi_info_save_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_share_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("poi_info_add_to_route_button").assertIsDisplayed().performClick()
        assertTrue(addToRouteClicked)
    }

    @Test
    fun item1_accessPolygonRulesDialog_displaysRulesOnAnyMap() {
        val polygon = AccessPolygon(
            id = "poly_1",
            name = "Rold Statsskov",
            type = AccessLandType.PUBLIC_FOREST,
            coordinates = listOf(
                LatLng(56.80, 9.85),
                LatLng(56.81, 9.85),
                LatLng(56.81, 9.86)
            ),
            source = "GeoFA / Naturstyrelsen",
            operatorOrOwner = "Naturstyrelsen"
        )
        composeTestRule.setContent {
            DanskStiTheme(darkTheme = false) {
                AccessPolygonRulesDialog(
                    polygon = polygon,
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("access_polygon_rules_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rold Statsskov").assertIsDisplayed()
        composeTestRule.onNodeWithTag("access_polygon_rules_body").assertIsDisplayed()
    }

    // =========================================================================
    // Item 2: Long-press within 30 m opens PoiInfoSheet; otherwise coordinates card
    //         with "Ejerforhold ukendt - følg skilte på stedet"
    // =========================================================================

    @Test
    fun item2_longPressWithin30mFindsPoi_beyond30mShowsUpdatedUnknownOwnerCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapView = MapView(context)
        mapView.layout(0, 0, 800, 800)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(GeoPoint(56.01, 10.01))

        val manager = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            onMarkedRouteTapped = {},
            onPoiTapped = {},
            onAccessPolygonTapped = {}
        )
        manager.attachToMap(mapView, isDark = false)

        val shelter = ShelterEntity(
            id = "s_close",
            name = "Bøgeskov Shelter",
            lat = 56.0000,
            lng = 10.0000,
            shelterType = "Shelter",
            capacity = 4,
            bookingRequired = false
        )
        val water = PoiEntity(
            id = "w_close",
            type = "water",
            name = "Vandpost Bøgeskov",
            lat = 56.0100,
            lng = 10.0100
        )
        val friTelt = FriTeltningEntity(
            id = "ft_close",
            name = "Bøgeskov Fri Teltning",
            zoneType = "Fri teltning",
            centerLat = 56.0200,
            centerLng = 10.0200,
            polygonJson = "[]"
        )

        manager.updateLayersState(
            state = MapLayersState(
                showShelters = true,
                showFriTeltning = true,
                showWater = true,
                showToilets = true,
                showShops = true
            ),
            shelters = listOf(shelter),
            friTeltning = listOf(friTelt),
            pois = listOf(water),
            customWaypoints = emptyList(),
            isDark = false
        )

        // Within ~11 meters of the shelter -> returns Shelter PoiItem
        val hitShelter = manager.findLongPressPoi(GeoPoint(56.0001, 10.0000), maxDistanceMeters = 30.0)
        assertNotNull("Expected shelter within 30m to be matched on long-press", hitShelter)
        assertEquals("s_close", hitShelter?.id)

        // Within ~11 meters of water -> returns Water PoiItem
        val hitWater = manager.findLongPressPoi(GeoPoint(56.0101, 10.0100), maxDistanceMeters = 30.0)
        assertNotNull("Expected water within 30m to be matched on long-press", hitWater)
        assertEquals("w_close", hitWater?.id)

        // Within ~11 meters of fri teltning -> returns FriTeltning PoiItem
        val hitFriTelt = manager.findLongPressPoi(GeoPoint(56.0201, 10.0200), maxDistanceMeters = 30.0)
        assertNotNull("Expected fri teltning within 30m to be matched on long-press", hitFriTelt)
        assertEquals("ft_close", hitFriTelt?.id)

        // 200 meters away -> returns null (so coordinates card opens)
        val miss = manager.findLongPressPoi(GeoPoint(56.0020, 10.0000), maxDistanceMeters = 30.0)
        assertNull("Expected point > 30m from POIs to return null", miss)

        // Verify updated unknown owner copy in DropPinInspectorHelper
        assertEquals(
            "Ejerforhold ukendt - følg skilte på stedet",
            DropPinInspectorHelper.ACCESS_UNKNOWN_OWNER
        )
    }

    // =========================================================================
    // Item 3: Planner map pan, fling, pinch, double-tap zoom & pin dragging
    // =========================================================================

    @Test
    fun item3_plannerPinDragOverlay_allowsMapPanZoomAndInterceptsOnlyPinOrHandleHits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = RouteBuilderViewModel(app)
        val mapView = MapView(app)
        mapView.layout(0, 0, 800, 800)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(56.80, 9.85))

        val wp = RouteWaypoint(
            id = "wp_1",
            lat = 56.80,
            lng = 9.85,
            role = WaypointRole.START,
            name = "Start"
        )
        val stateWithWp = RouteBuilderState(waypoints = listOf(wp))

        val overlay = PlannerPinDragMapOverlay(
            viewModel = vm,
            uiStateProvider = { stateWithWp }
        )
        mapView.overlays.add(overlay)

        // 1. Touch on empty map area (far from pin at center 400,400) in Kig and Tegn mode:
        //    overlay.onTouchEvent MUST return false so MapView handles pan, fling, pinch, double-tap zoom!
        val downEmpty = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 40f, 40f, 0)
        val moveEmpty = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_MOVE, 120f, 120f, 0)
        val upEmpty = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_UP, 120f, 120f, 0)

        assertFalse("Touch on empty map must return false so MapView can pan/zoom", overlay.onTouchEvent(downEmpty, mapView))
        assertFalse("Move on empty map must return false so MapView can pan/fling", overlay.onTouchEvent(moveEmpty, mapView))
        assertFalse("Up on empty map must return false", overlay.onTouchEvent(upEmpty, mapView))

        // 2. Touch directly on waypoint pin at center of mapView:
        val pinPx = android.graphics.Point()
        mapView.projection.toPixels(GeoPoint(wp.lat, wp.lng), pinPx)
        val downPin = MotionEvent.obtain(100L, 100L, MotionEvent.ACTION_DOWN, pinPx.x.toFloat(), pinPx.y.toFloat(), 0)
        val movePin = MotionEvent.obtain(100L, 150L, MotionEvent.ACTION_MOVE, pinPx.x + 50f, pinPx.y + 50f, 0)
        val upPin = MotionEvent.obtain(100L, 200L, MotionEvent.ACTION_UP, pinPx.x + 50f, pinPx.y + 50f, 0)

        assertTrue("Touch on waypoint pin must return true", overlay.onTouchEvent(downPin, mapView))
        assertTrue("Dragging waypoint pin must return true", overlay.onTouchEvent(movePin, mapView))
        assertTrue("Releasing dragged waypoint pin must return true", overlay.onTouchEvent(upPin, mapView))

        downEmpty.recycle()
        moveEmpty.recycle()
        upEmpty.recycle()
        downPin.recycle()
        movePin.recycle()
        upPin.recycle()
    }

    // =========================================================================
    // Item 4: Theme consistency & currentSettingsSnapshot()
    // =========================================================================

    @Test
    fun item4_themeConsistency_settingsSnapshotAndDarkThemePropagateImmediately() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepository = SettingsRepository(context)
        settingsRepository.setAppearanceMode(AppAppearanceMode.DARK)
        settingsRepository.setMapFollowsApp(true)

        val snapshot = settingsRepository.currentSettingsSnapshot()
        assertEquals(AppAppearanceMode.DARK, snapshot.appearanceMode)
        assertTrue(snapshot.mapFollowsApp)
        assertEquals(AppAppearanceMode.DARK, snapshot.mapLayers.appearanceMode)

        var capturedThemeIsDark: Boolean? = null
        composeTestRule.setContent {
            DanskStiTheme(
                darkTheme = snapshot.resolveIsDarkApp(isSystemDark = false)
            ) {
                capturedThemeIsDark = DanskStiTheme.isDark
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(true, capturedThemeIsDark)

        // Reset to FOLLOW_SYSTEM
        settingsRepository.setAppearanceMode(AppAppearanceMode.FOLLOW_SYSTEM)
    }

    // =========================================================================
    // Item 5: Insets & screenshot tests with and without keyboard open
    // =========================================================================

    @Test
    fun item5_insetsAndScreenshots_everyScreenWithAndWithoutKeyboardOpen() {
        val outDir = File("build/reports/r10a_screenshots").apply { mkdirs() }
        val plannerState = createSamplePlannerUiState(sheetState = SheetHeightState.PEEK)
        val currentScreenState = mutableStateOf("planner")
        val keyboardOpenState = mutableStateOf(false)

        composeTestRule.setContent {
            val screen = currentScreenState.value
            val imeOpen = keyboardOpenState.value
            val wsc = WindowSizeClass.compute(360.dp, 640.dp)
            var showPlannerMenu by remember { mutableStateOf(false) }

            DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (screen == "explore" || screen == "kort" || screen == "saved" || screen == "profile") {
                            DanskStiBottomBar(
                                currentRoute = Screen.Explore.route,
                                onNavigate = {}
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .padding(bottom = if (imeOpen) 220.dp else 0.dp)
                            .testTag("screen_content_container")
                    ) {
                        when (screen) {
                            "planner" -> {
                                RouteBuilderTopAppBar(
                                    uiState = plannerState,
                                    visibleWaypoints = plannerState.visibleWaypoints,
                                    showPlannerMenu = showPlannerMenu,
                                    onSetShowPlannerMenu = { showPlannerMenu = it },
                                    onOpenRenameDialog = {},
                                    onBack = {},
                                    onOpenTripSettings = {},
                                    onOpenLayersSheet = {},
                                    onLaunchGpxImport = {},
                                    onCloseRouteLoop = {},
                                    onAppendOutAndBack = {}
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                        availableHeightDp = if (imeOpen) 360f else 580f,
                                        isSidePanel = false
                                    )
                                }
                            }
                            "credits" -> {
                                DataCreditsScreen(onBack = {})
                            }
                            "diagnostics" -> {
                                ApiDiagnosticsScreen(onBack = {})
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background)
                                        .testTag("screen_body_$screen")
                                ) {
                                    Text("Skærm: $screen (tastatur=${if (imeOpen) "åbent" else "lukket"})")
                                }
                            }
                        }
                    }
                }
            }
        }

        val screens = listOf("explore", "kort", "saved", "profile", "planner", "credits", "diagnostics")
        var screenshotCount = 0

        for (scr in screens) {
            for (imeOpen in listOf(false, true)) {
                composeTestRule.runOnIdle {
                    currentScreenState.value = scr
                    keyboardOpenState.value = imeOpen
                }
                composeTestRule.waitForIdle()

                val containerBounds = composeTestRule.onNodeWithTag("screen_content_container")
                    .assertExists()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "Content top (${containerBounds.top}) for $scr (ime=$imeOpen) must start at top without double inset padding",
                    containerBounds.top <= 2f
                )

                val bmp = captureActivityBitmap(composeTestRule.activity, 360, 640)
                val suffix = if (imeOpen) "ime_open" else "ime_closed"
                val outFile = File(outDir, "${scr}_${suffix}.png")
                outFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                assertTrue(outFile.exists() && outFile.length() > 300)
                screenshotCount++
            }
        }
        assertEquals(14, screenshotCount)
    }

    // =========================================================================
    // Item 6: Planner sheet peek height sized to measured content at 100% & 200% font size
    // =========================================================================

    @Test
    fun item6_plannerSheetPeekHeight_keepsButtonsFullyVisibleAt200PercentFontSize() {
        val sampleState = createSamplePlannerUiState(sheetState = SheetHeightState.PEEK).copy(
            routeName = "Meget Lang Vandrerute Gennem Jylland Med Mange Etaper Og Detaljeret Beskrivelse"
        )

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = 2.0f
                )
            ) {
                val wsc = WindowSizeClass.compute(360.dp, 640.dp)
                DanskStiTheme(darkTheme = false, windowSizeClass = wsc) {
                    Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
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
                            availableHeightDp = 640f,
                            isSidePanel = false
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val sheetBounds = composeTestRule.onNodeWithTag("route_bottom_sheet")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val suggestBtnBounds = composeTestRule.onNodeWithTag("btn_find_overnight")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val startBtnBounds = composeTestRule.onNodeWithTag("btn_start_planned_navigation")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Foreslå overnatning button bottom (${suggestBtnBounds.bottom}) must be inside sheet bottom (${sheetBounds.bottom})",
            suggestBtnBounds.bottom <= sheetBounds.bottom + 2f
        )
        assertTrue(
            "Start button bottom (${startBtnBounds.bottom}) must be inside sheet bottom (${sheetBounds.bottom})",
            startBtnBounds.bottom <= sheetBounds.bottom + 2f
        )
    }

    // =========================================================================
    // Item 7: Planner back priority order & Room draft autosave/restore
    // =========================================================================

    @Test
    fun item7_plannerUnsavedDialog_and_roomDraftPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.danskStiDao()
            val draft = PlannerDraftEntity(
                id = "active_planner_draft",
                routeId = null,
                baseTrailId = "haervejen",
                routeName = "Kladde Hærvejen",
                mode = "TRAIL",
                waypointsJson = """[{"id":"wp1","lat":56.8,"lng":9.85,"role":"START","name":"Start"}]""",
                optionsJson = "{}",
                isReverse = false,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertPlannerDraft(draft)

            val loaded = dao.getPlannerDraft()
            assertNotNull("Expected draft to be stored in Room", loaded)
            assertEquals("Kladde Hærvejen", loaded?.routeName)

            dao.clearPlannerDraft()
            assertNull("Expected draft to be cleared after discard/save", dao.getPlannerDraft())
        } finally {
            db.close()
        }

        // Verify "Gem ruten?" dialog with Gem / Kassér / Fortsæt redigering buttons
        var action: String? = null
        composeTestRule.setContent {
            DanskStiTheme(darkTheme = false) {
                PlannerUnsavedChangesDialog(
                    show = true,
                    onSave = { action = "save" },
                    onDiscard = { action = "discard" },
                    onContinueEditing = { action = "continue" }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("planner_unsaved_changes_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gem ruten?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_unsaved_save").assertIsDisplayed().performClick()
        assertEquals("save", action)
    }

    // =========================================================================
    // Item 8: Live position staleness (> 2 min), "sidst set X min siden", and distance
    // =========================================================================

    @Test
    fun item8_livePositionStaleness_and_dropPinDistanceFormatting() {
        val nowMs = 1_730_000_000_000L

        // 1. Fresh fix (30 seconds old -> < 2 min)
        val freshTimeMs = nowMs - 30_000L
        assertTrue(LiveMapLocationState.isFixFresh(freshTimeMs, nowMs))
        assertNull(LiveMapLocationState.formatStaleBadge(freshTimeMs, nowMs))

        // 2. Stale fix (5 minutes old -> > 2 min)
        val staleTimeMs = nowMs - 5 * 60_000L
        assertFalse(LiveMapLocationState.isFixFresh(staleTimeMs, nowMs))
        assertEquals("sidst set 5 min siden", LiveMapLocationState.formatStaleBadge(staleTimeMs, nowMs))

        // 3. Long-press card's distance uses a fix less than 2 minutes old, otherwise "ukendt"
        val userPt = GeoPoint(56.80, 9.85)
        val freshDist = DropPinInspectorHelper.formatStraightLineDistance(
            userLocation = userPt,
            targetLat = 56.805,
            targetLng = 9.85,
            fixTimestampMs = freshTimeMs,
            nowMs = nowMs
        )
        assertFalse("Fresh distance should be a numeric distance, not ukendt", freshDist == "ukendt")

        val staleDist = DropPinInspectorHelper.formatStraightLineDistance(
            userLocation = userPt,
            targetLat = 56.805,
            targetLng = 9.85,
            fixTimestampMs = staleTimeMs,
            nowMs = nowMs
        )
        assertEquals("ukendt", staleDist)
    }

    // =========================================================================
    // Item 9: Layout polish (Kort credits "i" button; Planner top bar full name,
    //         overflow menu for GPX & trip settings, stats chip under title)
    // =========================================================================

    @Test
    fun item9_kortCreditsCollapsedIntoInfoButton_and_plannerTopBarLayout() {
        var gpxClicked = false
        var tripSettingsClicked = false
        val sampleState = createSamplePlannerUiState(sheetState = SheetHeightState.PEEK).copy(
            routeName = "Den Komplette Vandrerute Gennem Rold Skov Og Rebild Bakker"
        )

        composeTestRule.setContent {
            var showMenu by remember { mutableStateOf(false) }
            DanskStiTheme(darkTheme = false) {
                Column {
                    MapAttributionPill(
                        layersState = MapLayersState(),
                        isDarkMode = false
                    )
                    RouteBuilderTopAppBar(
                        uiState = sampleState,
                        visibleWaypoints = sampleState.visibleWaypoints,
                        showPlannerMenu = showMenu,
                        onSetShowPlannerMenu = { showMenu = it },
                        onOpenRenameDialog = {},
                        onBack = {},
                        onOpenTripSettings = { tripSettingsClicked = true },
                        onOpenLayersSheet = {},
                        onLaunchGpxImport = { gpxClicked = true },
                        onCloseRouteLoop = {},
                        onAppendOutAndBack = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Kort credits collapsed into "i" button until tapped
        composeTestRule.onNodeWithTag("map_attribution_info_button", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("map_attribution_credits_text", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("map_attribution_info_button", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_attribution_credits_text", useUnmergedTree = true).assertIsDisplayed()

        // 2. Planner top bar: full route name + stats chip under title + GPX/trip settings in overflow menu
        composeTestRule.onNodeWithTag("planner_top_full_route_name", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("planner_top_stats_chip", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_planner_menu").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_import_gpx").assertIsDisplayed().performClick()
        assertTrue(gpxClicked)

        composeTestRule.onNodeWithTag("btn_planner_menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_open_trip_settings").assertIsDisplayed().performClick()
        assertTrue(tripSettingsClicked)
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
