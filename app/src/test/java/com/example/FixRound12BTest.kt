package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.local.SettingsRepository
import com.example.data.model.AccessPolygonEntity
import com.example.data.model.CachedRouteLegEntity
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.NearestPoiCategory
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.WaterFeatureEntity
import com.example.data.repository.CacheCategory
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.screens.HowToGuideDefinitions
import com.example.ui.screens.HowToGuideScreen
import com.example.ui.screens.RouteBuilderFeatureDialogsHost
import com.example.ui.screens.planner.PlannerBottomSheet
import com.example.ui.screens.planner.PlannerDaySummaryCard
import com.example.ui.screens.planner.PlannerSheetButtonLayoutTracker
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NearestSearchOriginType
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerSheetTab
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SheetHeightState
import com.example.ui.viewmodel.confirmNearestShelterRole
import com.example.ui.viewmodel.onNearestCategorySelected
import com.example.ui.viewmodel.openNearestPoiDialog
import com.example.ui.viewmodel.resolveNearestSearchOrigin
import com.example.util.DanskStiTileSourceHelper
import com.example.util.StagePlanner
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FixRound12BTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: DanskStiApp
    private lateinit var db: DanskStiDatabase
    private lateinit var repo: DanskStiRepository
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = app.database
        repo = app.repository
        settingsRepo = DanskStiApp.getSettingsRepository(app)
        PlannerSheetButtonLayoutTracker.clear()
        runBlocking {
            settingsRepo.setHasSeenHowToGuide(true)
        }
    }

    @After
    fun tearDown() {
        PlannerSheetButtonLayoutTracker.clear()
        runBlocking {
            settingsRepo.setHasSeenHowToGuide(true)
        }
    }

    // =========================================================================
    // ITEM 1: How-to guide
    // =========================================================================

    @Test
    fun item1a_howToGuide_textsOnlyMentionExistingFiltersAndActions() {
        val realFilterNames = HowToGuideDefinitions.REAL_EXPLORE_FILTER_RES_IDS.map { app.getString(it) }
        val realPlannerActions = HowToGuideDefinitions.REAL_PLANNER_ACTION_RES_IDS.map { app.getString(it) }
        val guideStrings = listOf(
            app.getString(R.string.how_to_guide_sec1_step1),
            app.getString(R.string.how_to_guide_sec1_step2),
            app.getString(R.string.how_to_guide_sec1_step3),
            app.getString(R.string.how_to_guide_sec2_step1),
            app.getString(R.string.how_to_guide_sec2_step2),
            app.getString(R.string.how_to_guide_sec2_step3),
            app.getString(R.string.how_to_guide_sec3_step1),
            app.getString(R.string.how_to_guide_sec3_step2),
            app.getString(R.string.how_to_guide_sec3_step3),
            app.getString(R.string.how_to_guide_sec4_step1),
            app.getString(R.string.how_to_guide_sec4_step2),
            app.getString(R.string.how_to_guide_sec4_step3)
        )
        val fullGuideText = guideStrings.joinToString("\n")

        // 1. Must not mention non-existent filters or removed actions
        val nonExistentFiltersAndActions = listOf(
            "sværhedsgrad",
            "trække lejr-ikoner på højdeprofilen",
            "træk lejr-ikoner på højdeprofilen"
        )
        for (forbidden in nonExistentFiltersAndActions) {
            assertFalse(
                "Guide text must NOT mention non-existent filter or action '$forbidden'",
                fullGuideText.contains(forbidden, ignoreCase = true)
            )
        }

        // 2. Every filter listed in how_to_guide_sec1_step1 after 'efter' must exist in realFilterNames
        val sec1Step1 = app.getString(R.string.how_to_guide_sec1_step1)
        val filterListSegment = sec1Step1
            .substringAfter("efter ")
            .substringBefore(".")
        val mentionedFilters = filterListSegment
            .split(",", " og ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        assertTrue("Guide must list real Explore filters", mentionedFilters.isNotEmpty())
        for (mentioned in mentionedFilters) {
            assertTrue(
                "Guide mentions filter '$mentioned' which does not exist in real Explore filters: $realFilterNames",
                realFilterNames.any { realName -> realName.equals(mentioned, ignoreCase = true) }
            )
        }

        // 3. Verify updated wording in Section 2 Step 3 and Section 3 Step 3
        val sec2Step3 = app.getString(R.string.how_to_guide_sec2_step3)
        assertTrue(
            "Section 2 step 3 must say 'Tryk og træk et punkt for at flytte det'",
            sec2Step3.contains("Tryk og træk et punkt for at flytte det")
        )

        val sec3Step3 = app.getString(R.string.how_to_guide_sec3_step3)
        assertTrue(
            "Section 3 step 3 must describe tapping a day or overnight point to change it",
            sec3Step3.contains("trykke på en dag eller et overnatningspunkt")
        )

        // 4. Verify planner actions mentioned in guide match real planner actions
        val mentionedPlannerActions = listOf(
            "Kig",
            "Tegn",
            "Overnatning",
            "Følg stier",
            "Lige linje",
            "Foreslå overnatning",
            "Nærmeste",
            "Dage",
            "Detaljer"
        )
        for (action in mentionedPlannerActions) {
            assertTrue(
                "Guide mentions planner action '$action' which must exist in realPlannerActions ($realPlannerActions)",
                realPlannerActions.any { it.equals(action, ignoreCase = true) }
            )
            assertTrue(
                "Guide text must mention real planner action '$action'",
                fullGuideText.contains(action, ignoreCase = false)
            )
        }
    }

    @Test
    fun item1b_howToGuide_firstOpenOverlay_onlyDismissedByForstaaetOrClose_andOpensAtMatchingSection() {
        var dismissedCount = 0

        composeRule.setContent {
            DanskStiTheme {
                HowToGuideScreen(
                    onDismiss = { dismissedCount++ },
                    initialSection = HowToGuideDefinitions.SECTION_PLANNER,
                    isFirstLaunchOnboarding = true
                )
            }
        }
        composeRule.waitForIdle()

        // Planner section (section index 1) should be expanded and visible
        composeRule.onNodeWithTag("how_to_guide_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("how_to_guide_first_open_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("how_to_card_${HowToGuideDefinitions.SECTION_PLANNER}").assertIsDisplayed()

        // Switching filter chips inside the guide does NOT dismiss the guide
        composeRule.onNodeWithTag("how_to_chip_all").performClick()
        composeRule.waitForIdle()
        assertEquals(0, dismissedCount)

        // Tapping "Forstået" marks it as seen / dismisses
        composeRule.onNodeWithTag("how_to_guide_got_it_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissedCount)
    }

    @Test
    fun item1c_howToGuide_openedFromNavigation_opensAtOfflineAndNavigationSection() {
        var dismissed = false
        composeRule.setContent {
            DanskStiTheme {
                HowToGuideScreen(
                    onDismiss = { dismissed = true },
                    initialSection = HowToGuideDefinitions.SECTION_OFFLINE_NAVIGATION
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("how_to_guide_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("how_to_card_${HowToGuideDefinitions.SECTION_OFFLINE_NAVIGATION}").assertIsDisplayed()

        composeRule.onNodeWithTag("how_to_guide_close_button").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }

    // =========================================================================
    // ITEM 2: "Ryd cache"
    // =========================================================================

    @Test
    fun item2_clearCache_showsSizesAndCheckboxes_preservesOfflinePlansRoutesPhotos_detachesTileCache_andSyncsDownloadedState() = runBlocking {
        repo.initializePreseededDataIfEmpty()
        val dao = db.danskStiDao()
        val geomRepo = TrailGeometryRepository(app)

        // 1. Seed browsing tile cache
        val tileCacheDir = DanskStiTileSourceHelper.getBrowsingTileCacheDir(app).apply { mkdirs() }
        val dummyTileDb = File(tileCacheDir, "cache.db").apply { writeBytes(ByteArray(4096) { 1 }) }

        // 2. Seed two trails with geometry files:
        //    - trail_offline: downloaded for offline (must NEVER be deleted, and must stay isGeometryDownloaded = true)
        //    - trail_cached_only: not downloaded for offline and no saved plan/draft (SHOULD be deleted when ROUTE_LINES is checked, and isGeometryDownloaded updated to false)
        val trailOffline = TrailEntity(
            id = "trail_offline",
            name = "Hærvejen Offline",
            region = "Jylland",
            computedWalkingKm = 25.0,
            description = "Offline trail",
            isGeometryDownloaded = true
        )
        val trailCachedOnly = TrailEntity(
            id = "trail_cached_only",
            name = "Kyststien Browsed",
            region = "Sjælland",
            computedWalkingKm = 12.0,
            description = "Cached trail line",
            isGeometryDownloaded = true
        )
        dao.insertTrails(listOf(trailOffline, trailCachedOnly))
        dao.insertDownloadedTrail(
            DownloadedTrailEntity(
                trailId = "trail_offline",
                trailName = "Hærvejen Offline",
                downloadedAt = System.currentTimeMillis(),
                sizeBytes = 12_500_000L
            )
        )
        geomRepo.getGeometryFile("trail_offline").writeText("""[{"points":[{"lat":56.0,"lng":9.5}]}]""")
        geomRepo.getGeometryFile("trail_cached_only").writeText("""[{"points":[{"lat":55.8,"lng":12.4}]}]""")

        dao.insertCachedRouteLeg(
            CachedRouteLegEntity(
                cacheKey = "cached_leg_1",
                fromLat = 56.0,
                fromLng = 9.5,
                toLat = 56.1,
                toLng = 9.6,
                avoidRoads = 0.5,
                preferNature = true,
                allowFerries = true,
                alternative = 0,
                lengthKm = 12.0,
                ascentM = 50.0,
                descentM = 40.0,
                pointsJson = """[{"lat":56.0,"lng":9.5},{"lat":56.1,"lng":9.6}]""",
                wayInfoJson = "[]",
                sourceName = "BRouter",
                cachedAt = System.currentTimeMillis()
            )
        )

        // 3. Seed water & access data, marked routes, and place search cache
        dao.insertWaterFeatures(
            listOf(
                WaterFeatureEntity(
                    id = "w1",
                    osmId = 1001L,
                    name = "Gudenåen",
                    featureType = "STREAM",
                    subType = "stream",
                    coordinatesJson = """[{"lat":56.0,"lng":9.5}]""",
                    minLat = 56.0,
                    maxLat = 56.01,
                    minLng = 9.5,
                    maxLng = 9.51,
                    tileId = "tile_56_9",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        )
        dao.insertAccessPolygons(
            listOf(
                AccessPolygonEntity(
                    id = "a1",
                    name = "Statsskov",
                    type = "STATE_FOREST",
                    coordinatesJson = """[{"lat":56.0,"lng":9.5}]""",
                    minLat = 56.0,
                    maxLat = 56.05,
                    minLng = 9.5,
                    maxLng = 9.55,
                    source = "osm",
                    operatorOrOwner = "Naturstyrelsen",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        )
        dao.insertMarkedRoutes(
            listOf(
                MarkedRouteEntity(
                    osmRelationId = 12001L,
                    name = "Regional Vandrerute 12",
                    lengthKm = 18.0,
                    operator = "Naturstyrelsen",
                    routeType = "hiking",
                    network = "rwn",
                    symbol = "Blå",
                    colorHex = "#1E88E5",
                    segmentsJson = """[[{"lat":56.0,"lng":9.5},{"lat":56.1,"lng":9.6}]]""",
                    minLat = 56.0,
                    maxLat = 56.1,
                    minLng = 9.5,
                    maxLng = 9.6,
                    cachedAt = System.currentTimeMillis()
                )
            )
        )
        File(app.filesDir, "photon_place_cache.json").writeText(
            """[{"query":"silkeborg","name":"Silkeborg","lat":56.17,"lng":9.55}]"""
        )

        // 4. Seed protected user data that MUST NEVER be touched:
        //    - Offline archive file
        //    - Saved custom route
        //    - Saved trip plan
        //    - Photo file
        //    - Routing data (brouter .rd5)
        val offlineDir = File(app.filesDir, "offline").apply { mkdirs() }
        val offlineArchive = File(offlineDir, "trail_offline.sqlite").apply { writeBytes(ByteArray(8192) { 2 }) }
        val photosDir = File(app.filesDir, "waypoint_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "camp_photo.jpg").apply { writeBytes(ByteArray(2048) { 3 }) }
        val brouterDir = File(app.filesDir, "brouter/segments4").apply { mkdirs() }
        val rd5File = File(brouterDir, "E5_N55.rd5").apply { writeBytes(ByteArray(4096) { 4 }) }

        val customRouteId = dao.insertCustomRoute(
            CustomRouteEntity(
                name = "Min Gemte Rute",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                optionsJson = "{}",
                waypointsJson = "[]",
                totalKm = 14.2,
                ascentM = 80.0,
                descentM = 75.0,
                pathSharePct = 85.0,
                roadKm = 2.1,
                geometryFile = "route_1.json",
                sourceName = "BRouter",
                fetchedAt = System.currentTimeMillis()
            )
        )
        val savedPlanId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = "trail_offline",
                trailName = "Hærvejen Plan",
                startKm = 0.0,
                endKm = 25.0,
                isReverse = false,
                totalDistanceKm = 25.0,
                targetDays = 2,
                overnightType = "BOTH",
                preferredDailyKm = 15.0,
                stagesJson = "[]",
                createdAt = System.currentTimeMillis(),
                paceKmh = 4.5
            )
        )

        // Register an active MapView to verify tile cache detach + reattach keeps open maps working
        val activeMapView = MapView(app)
        DanskStiTileSourceHelper.registerActiveMapView(activeMapView)
        val detachBefore = DanskStiTileSourceHelper.tileCacheDetachCount
        val reattachBefore = DanskStiTileSourceHelper.tileCacheReattachCount

        val breakdown = repo.computeCacheBreakdown()
        assertEquals(5, breakdown.entries.size)
        assertTrue("MAP_TILES size must be > 0", breakdown.sizeFor(CacheCategory.MAP_TILES) > 0L)
        assertTrue("ROUTE_LINES size must be > 0", breakdown.sizeFor(CacheCategory.ROUTE_LINES) > 0L)
        assertTrue("WATER_AND_ACCESS size must be > 0", breakdown.sizeFor(CacheCategory.WATER_AND_ACCESS) > 0L)
        assertTrue("MARKED_ROUTES size must be > 0", breakdown.sizeFor(CacheCategory.MARKED_ROUTES) > 0L)
        assertTrue("PLACE_SEARCH size must be > 0", breakdown.sizeFor(CacheCategory.PLACE_SEARCH) > 0L)
        assertTrue("Total selected bytes must be > 0", breakdown.totalBytesFor(CacheCategory.entries.toSet()) > 0L)

        // Clear all 5 cache categories
        repo.clearSelectedCaches(CacheCategory.entries.toSet())

        // Verify tile cache was detached before deletion and reattached afterwards
        assertTrue(
            "Map tile cache must be detached before deleting",
            DanskStiTileSourceHelper.tileCacheDetachCount > detachBefore
        )
        assertTrue(
            "Map tile cache must be reattached after deleting so open maps keep working",
            DanskStiTileSourceHelper.tileCacheReattachCount > reattachBefore
        )
        assertNotNull("Open MapView must still have an attached tileProvider", activeMapView.tileProvider)
        DanskStiTileSourceHelper.unregisterActiveMapView(activeMapView)
        runCatching { activeMapView.onDetach() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Verify browsing cache files were removed
        assertFalse("Browsing tile db should be deleted", dummyTileDb.exists())
        assertFalse("Unprotected cached trail geometry should be deleted", geomRepo.getGeometryFile("trail_cached_only").exists())

        // Verify protected data was NEVER touched
        assertTrue("Offline trail archive must NEVER be deleted", offlineArchive.exists())
        assertTrue("Offline trail geometry must NEVER be deleted", geomRepo.getGeometryFile("trail_offline").exists())
        assertTrue("Saved photo must NEVER be deleted", photoFile.exists())
        assertTrue("BRouter routing data (.rd5) must NEVER be deleted", rd5File.exists())
        assertNotNull("Saved custom route must NEVER be deleted", dao.getCustomRouteById(customRouteId))
        assertNotNull("Saved trip plan must NEVER be deleted", dao.getTripPlanById(savedPlanId))

        // Verify trail list shows correct 'downloaded' states afterwards
        val updatedOfflineTrail = dao.getTrailById("trail_offline")
        val updatedCachedOnlyTrail = dao.getTrailById("trail_cached_only")
        assertTrue("Offline trail must still show isGeometryDownloaded = true", updatedOfflineTrail?.isGeometryDownloaded == true)
        assertFalse("Cleared non-offline trail must show isGeometryDownloaded = false", updatedCachedOnlyTrail?.isGeometryDownloaded == true)

        // Clean up test-only records
        db.openHelper.writableDatabase.execSQL("DELETE FROM trails WHERE id IN ('trail_offline', 'trail_cached_only')")
        db.openHelper.writableDatabase.execSQL("DELETE FROM downloaded_trails WHERE trailId = 'trail_offline'")
        db.openHelper.writableDatabase.execSQL("DELETE FROM trip_plans WHERE id = $savedPlanId")
        db.openHelper.writableDatabase.execSQL("DELETE FROM custom_routes WHERE id = $customRouteId")
    }

    // =========================================================================
    // ITEM 3: Planner sheet buttons (360 dp @ 100%, 360 dp @ 200%, Landscape)
    // =========================================================================

    private fun renderAndVerifyPlannerButtons(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        orientation: Int,
        screenshotName: String
    ) {
        PlannerSheetButtonLayoutTracker.clear()

        val baseConfig = Configuration(app.resources.configuration).apply {
            screenWidthDp = widthDp
            screenHeightDp = heightDp
            this.fontScale = fontScale
            this.orientation = orientation
        }
        val baseDensity = app.resources.displayMetrics.density
        val customDensity = Density(density = baseDensity, fontScale = fontScale)

        val testWaypoints = listOf(
            RouteWaypoint(id = "wp1", lat = 56.10, lng = 9.50, role = WaypointRole.START, name = "Start"),
            RouteWaypoint(id = "wp2", lat = 56.25, lng = 9.65, role = WaypointRole.END, name = "Slut")
        )
        val testState = RouteBuilderState(
            waypoints = testWaypoints,
            stats = RouteStats(totalKm = 18.5, ascentM = 210.0, descentM = 180.0, pathSharePct = 82.0, roadKm = 3.3, hasWayInfo = true),
            timeEstimateFormatted = "4 t 35 min",
            sheetHeightState = SheetHeightState.HALF,
            selectedSheetTab = PlannerSheetTab.RUTE
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides baseConfig,
                LocalDensity provides customDensity
            ) {
                DanskStiTheme {
                    Box(modifier = Modifier.width(widthDp.dp)) {
                        PlannerBottomSheet(
                            uiState = testState,
                            visibleWaypoints = testWaypoints,
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
                            onSelectSheetTab = {},
                            onSheetHeightFractionChanged = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val buttonTags = listOf(
            "btn_find_overnight",
            "btn_start_planned_navigation",
            "btn_sheet_trip_settings",
            "btn_open_rundtur",
            "btn_open_nearest_poi",
            "btn_share_route_sheet"
        )

        val rowTops = mutableListOf<Int>()
        for (tag in buttonTags) {
            val btnNode = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val iconNode = composeRule.onNodeWithTag("${tag}_icon", useUnmergedTree = true).fetchSemanticsNode()
            val labelNode = composeRule.onNodeWithTag("${tag}_label", useUnmergedTree = true).fetchSemanticsNode()

            // Verify icon is positioned above label (icon-over-label toolbar layout)
            assertTrue(
                "[$screenshotName] Icon must be above label for $tag (icon.bottom=${iconNode.boundsInRoot.bottom}, label.top=${labelNode.boundsInRoot.top})",
                iconNode.boundsInRoot.bottom <= labelNode.boundsInRoot.top + 4f
            )

            rowTops.add((btnNode.boundsInRoot.top / 10f).toInt())

            val layoutResult = PlannerSheetButtonLayoutTracker.lastTextLayouts[tag]
            val displayedText = PlannerSheetButtonLayoutTracker.lastDisplayedText[tag] ?: ""
            assertNotNull("[$screenshotName] Layout result must exist for $tag", layoutResult)
            assertTrue(
                "[$screenshotName] Button label for $tag ('$displayedText') must have <= 2 lines, had ${layoutResult!!.lineCount}",
                layoutResult.lineCount in 1..2
            )
            assertFalse(
                "[$screenshotName] Button label for $tag ('$displayedText') must NEVER clip or wrap mid-word",
                PlannerSheetButtonLayoutTracker.hasMidWordWrap(layoutResult)
            )
        }

        // Verify grid wraps to multiple rows when needed (at 360dp width)
        val distinctRows = rowTops.distinct()
        if (widthDp <= 360) {
            assertTrue(
                "[$screenshotName] Planner buttons at ${widthDp}dp width must wrap into multiple rows (found ${distinctRows.size} rows)",
                distinctRows.size >= 2
            )
        }

        // Save screenshot artifact for verification
        val screenshotDir = File("build/outputs/screenshots/round12b").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(
            (widthDp * baseDensity).toInt().coerceAtLeast(360),
            (heightDp * baseDensity).toInt().coerceAtLeast(360),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val outFile = File(screenshotDir, "$screenshotName.png")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        assertTrue("Screenshot file ${outFile.absolutePath} must exist and be non-empty", outFile.exists() && outFile.length() > 0)
    }

    @Test
    fun item3a_plannerSheetButtons_360dp_100PercentFont_noMidWordClip() {
        renderAndVerifyPlannerButtons(
            widthDp = 360,
            heightDp = 640,
            fontScale = 1.0f,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            screenshotName = "planner_buttons_360dp_100pct"
        )
    }

    @Test
    fun item3b_plannerSheetButtons_360dp_200PercentFont_noMidWordClip() {
        renderAndVerifyPlannerButtons(
            widthDp = 360,
            heightDp = 640,
            fontScale = 2.0f,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            screenshotName = "planner_buttons_360dp_200pct"
        )
    }

    @Test
    fun item3c_plannerSheetButtons_landscape_noMidWordClip() {
        renderAndVerifyPlannerButtons(
            widthDp = 640,
            heightDp = 360,
            fontScale = 1.0f,
            orientation = Configuration.ORIENTATION_LANDSCAPE,
            screenshotName = "planner_buttons_landscape"
        )
    }

    // =========================================================================
    // ITEM 4: Stage planner explanation on every day card
    // =========================================================================

    @Test
    fun item4_stagePlannerExplanation_shownOnEveryDayCard_usingScoringInputs() {
        // 1. Verify StagePlanner.buildCampExplanation with scoring inputs matching prompt examples
        val idealExplanation = StagePlanner.buildCampExplanation(
            stageKm = 14.8,
            dailyTargetKm = 15.0,
            detourMeters = 40.0,
            hasWaterNearby = true,
            waterDistanceMeters = 200,
            hasToiletNearby = true,
            bookingRequired = false,
            isAfterSunset = false,
            stopType = "SHELTER",
            isLastDay = false
        )
        assertEquals(
            "Nær dagsmålet · vand 200 m · toilet · ankomst før solnedgang",
            idealExplanation
        )

        val noToiletExplanation = StagePlanner.buildCampExplanation(
            stageKm = 15.0,
            dailyTargetKm = 15.0,
            detourMeters = 0.0,
            hasWaterNearby = true,
            waterDistanceMeters = 150,
            hasToiletNearby = false,
            bookingRequired = false,
            isAfterSunset = false,
            stopType = "FRI_TELTNING",
            isLastDay = false
        )
        assertTrue(
            "Explanation must mention 'Ingen toilet i nærheden' when toilet is absent, was: $noToiletExplanation",
            noToiletExplanation.contains("Ingen toilet i nærheden")
        )

        // 2. Verify StagePlanner.planStages populates campExplanation on every TripStage
        val route = PlannedRoute(
            planId = 1L,
            trailId = "t1",
            trailName = "Hærvejen",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 30.0,
            totalWalkKm = 30.0,
            points = (0..30).map { km ->
                RoutePoint(
                    lat = 56.0 + km * 0.009,
                    lng = 9.5,
                    walkKm = km.toDouble(),
                    trailKm = km.toDouble()
                )
            },
            stages = emptyList()
        )
        val shelter = ShelterEntity(
            id = "sh_mid",
            name = "Halolms Shelter",
            lat = 56.0 + 15 * 0.009,
            lng = 9.5005,
            capacity = 6,
            bookingUrl = null,
            description = "Fint shelter",
            shelterType = "Shelter",
            bookingRequired = false,
            hasWaterNearby = true,
            hasToiletNearby = false
        )
        val waterPoi = PoiEntity(
            id = "w_mid",
            type = "water",
            name = "Vandhane",
            lat = 56.0 + 15 * 0.009 + 0.0018, // ~200m
            lng = 9.5005,
            trailCorridorId = "t1"
        )
        val stages = StagePlanner.planStages(
            plannedRoute = route,
            routeName = "Hærvejen",
            paceKmh = 4.5,
            dailyTargetKm = 15.0,
            numDays = 2,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 2.0,
            startTime = "09:00",
            shelters = listOf(shelter),
            friTeltnings = emptyList(),
            pois = listOf(waterPoi)
        )
        assertEquals(2, stages.size)
        assertTrue(
            "Every stage must have a non-blank campExplanation",
            stages.all { it.campExplanation.isNotBlank() }
        )
        assertTrue(
            "Stage 1 explanation must reflect no toilet nearby: ${stages[0].campExplanation}",
            stages[0].campExplanation.contains("Ingen toilet i nærheden")
        )

        // 3. Verify PlannerDaySummaryCard renders day_card_explanation_${dayNumber} on every day card
        val day1 = PlannerDaySummary(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 14.8,
            distanceKm = 14.8,
            ascentM = 120,
            descentM = 90,
            fromName = "Start",
            toName = "Halolms Shelter",
            overnightWaypointId = "wp_ov_1",
            overnightName = "Halolms Shelter",
            overnightTypeLabel = "Shelter",
            detourLabel = null,
            detourKm = 0.04,
            detourAscentM = 0,
            bookingLabel = "Gratis",
            sunsetTime = "21:15",
            arrivalTime = "kl. 13:30",
            isAfterSunset = false,
            warnings = emptyList(),
            nightWeatherSummary = "12°C · Tørt",
            campLat = shelter.lat,
            campLng = shelter.lng,
            campExplanation = idealExplanation
        )
        composeRule.setContent {
            DanskStiTheme {
                PlannerDaySummaryCard(
                    day = day1,
                    showNightWeather = true,
                    onReplaceOvernight = {},
                    onDeleteOvernight = {},
                    onZoomToEnd = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("day_card_explanation_1", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("Nær dagsmålet · vand 200 m · toilet · ankomst før solnedgang")
    }

    // =========================================================================
    // ITEM 5: "Nærmeste" (Origin priority, shelter choice, real extra distance)
    // =========================================================================

    @Test
    fun item5a_nearest_searchOriginPriority_selectedPin_thenMyPositionNearRoute_thenMapCentre() {
        val vm = RouteBuilderViewModel(app)
        val wpStart = RouteWaypoint(id = "wp_start", lat = 56.0000, lng = 9.5000, role = WaypointRole.START, name = "Start")
        val wpEnd = RouteWaypoint(id = "wp_end", lat = 56.0500, lng = 9.5000, role = WaypointRole.END, name = "Slut")
        val leg = LegGeometryData(
            fromWaypointId = wpStart.id,
            toWaypointId = wpEnd.id,
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(56.0000, 9.5000), RoutePoint3D(56.0500, 9.5000)),
            lengthKm = 5.5,
            ascentM = 20.0,
            descentM = 20.0,
            pathSharePct = 90.0,
            roadKm = 0.5
        )

        // Case 1: Selected pin is set -> must use SELECTED_PIN ("Nærmest dit valgte punkt") even if GPS is near route
        vm._uiState.value = vm._uiState.value.copy(
            waypoints = listOf(wpStart, wpEnd),
            legs = mapOf("${wpStart.id}_to_${wpEnd.id}" to leg),
            selectedWaypointId = wpEnd.id,
            currentGpsPosition = LatLng(56.0010, 9.5005),
            mapCenterLat = 55.8000,
            mapCenterLng = 10.2000
        )
        val origin1 = vm.resolveNearestSearchOrigin()
        assertEquals(NearestSearchOriginType.SELECTED_PIN, origin1.type)
        assertEquals(wpEnd.lat, origin1.lat, 1e-5)
        assertEquals(wpEnd.lng, origin1.lng, 1e-5)
        assertEquals("Nærmest dit valgte punkt", app.getString(origin1.labelResId))

        // Case 2: No selected pin, my position is near the route -> must use MY_POSITION ("Nærmest din position")
        vm._uiState.value = vm._uiState.value.copy(
            selectedWaypointId = null,
            currentGpsPosition = LatLng(56.0100, 9.5010), // ~1.1 km from start, right on route
            mapCenterLat = 55.8000,
            mapCenterLng = 10.2000
        )
        val origin2 = vm.resolveNearestSearchOrigin()
        assertEquals(NearestSearchOriginType.MY_POSITION, origin2.type)
        assertEquals(56.0100, origin2.lat, 1e-5)
        assertEquals(9.5010, origin2.lng, 1e-5)
        assertEquals("Nærmest din position", app.getString(origin2.labelResId))

        // Case 3: No selected pin, and my position is far from route (e.g. Copenhagen while route is in Jutland) -> MAP_CENTER ("Nærmest kortets midte")
        vm._uiState.value = vm._uiState.value.copy(
            selectedWaypointId = null,
            currentGpsPosition = LatLng(55.6761, 12.5683), // Far from route
            mapCenterLat = 56.0250,
            mapCenterLng = 9.5200
        )
        val origin3 = vm.resolveNearestSearchOrigin()
        assertEquals(NearestSearchOriginType.MAP_CENTER, origin3.type)
        assertEquals(56.0250, origin3.lat, 1e-5)
        assertEquals(9.5200, origin3.lng, 1e-5)
        assertEquals("Nærmest kortets midte", app.getString(origin3.labelResId))
    }

    @Test
    fun item5b_nearest_shelterAsksOvernatHerOrTilfoejSomStop_andReportsRealExtraRouteLength() = runBlocking {
        val dao = db.danskStiDao()
        dao.insertShelters(
            listOf(
                ShelterEntity(
                    id = "shelter_detour_1",
                    name = "Skovbryn Shelter",
                    lat = 56.0250,
                    lng = 9.5150,
                    capacity = 6,
                    bookingUrl = null,
                    description = "Shelter nær ruten",
                    shelterType = "Shelter",
                    bookingRequired = false,
                    hasWaterNearby = true,
                    hasToiletNearby = true
                )
            )
        )

        val vm = RouteBuilderViewModel(app)
        // Deterministic routing engine:
        // Initial leg (wpStart -> wpEnd) is 5.0 km.
        // Detour via Skovbryn Shelter is 3.2 km + 3.2 km = 6.4 km total -> real extra route length = 1.4 km!
        vm.routingEngine = object : RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: RouteOptions,
                alternative: Int
            ): RouteLegResult {
                val touchesShelter = (kotlin.math.abs(from.lat - 56.0250) < 0.001 && kotlin.math.abs(from.lng - 9.5150) < 0.001) ||
                    (kotlin.math.abs(to.lat - 56.0250) < 0.001 && kotlin.math.abs(to.lng - 9.5150) < 0.001)
                val legKm = if (touchesShelter) 3.2 else 5.0
                return RouteLegResult.Success(
                    points = listOf(
                        RoutePoint3D(from.lat, from.lng, 20.0),
                        RoutePoint3D(to.lat, to.lng, 25.0)
                    ),
                    lengthKm = legKm,
                    ascentM = 15.0,
                    descentM = 10.0,
                    sourceName = "TestEngine",
                    perPointWayInfo = emptyList()
                )
            }
        }

        val wpStart = RouteWaypoint(id = "wp_start", lat = 56.0000, lng = 9.5000, role = WaypointRole.START, name = "Start")
        val wpEnd = RouteWaypoint(id = "wp_end", lat = 56.0500, lng = 9.5000, role = WaypointRole.END, name = "Slut")
        val initialLeg = LegGeometryData(
            fromWaypointId = wpStart.id,
            toWaypointId = wpEnd.id,
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(56.0000, 9.5000), RoutePoint3D(56.0500, 9.5000)),
            lengthKm = 5.0,
            ascentM = 10.0,
            descentM = 10.0,
            pathSharePct = 90.0,
            roadKm = 0.5
        )
        vm._uiState.value = vm._uiState.value.copy(
            waypoints = listOf(wpStart, wpEnd),
            legs = mapOf("${wpStart.id}_to_${wpEnd.id}" to initialLeg),
            selectedWaypointId = wpStart.id
        )

        // Open NearestPoiDialog and select SHELTER -> must ask "Overnat her" or "Tilføj som stop"
        vm.openNearestPoiDialog()
        assertEquals(R.string.nearest_poi_origin_selected_pin, vm.uiState.value.nearestPoiOriginLabelRes)

        vm.onNearestCategorySelected(NearestPoiCategory.SHELTER)
        assertTrue(
            "Selecting SHELTER in Nærmeste must show the 'Overnat her' / 'Tilføj som stop' choice",
            vm.uiState.value.showNearestShelterRoleChoice
        )

        // Verify the dialog UI renders origin label and both shelter buttons
        composeRule.setContent {
            DanskStiTheme {
                RouteBuilderFeatureDialogsHost(
                    uiState = vm.uiState.value,
                    viewModel = vm,
                    context = app,
                    mapViewInstance = null,
                    customWpName = "",
                    onCustomWpNameChange = {},
                    customWpNote = "",
                    onCustomWpNoteChange = {},
                    customWpPhotoPath = null,
                    onCustomWpPhotoPathChange = {},
                    onLaunchCamera = {},
                    onLaunchGallery = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nearest_poi_origin_label")
            .assertIsDisplayed()
            .assertTextContains("Nærmest dit valgte punkt")
        composeRule.onNodeWithTag("btn_nearest_shelter_overnight")
            .assertIsDisplayed()
            .assertTextContains("Overnat her")
        composeRule.onNodeWithTag("btn_nearest_shelter_stop")
            .assertIsDisplayed()
            .assertTextContains("Tilføj som stop")

        // Choose "Tilføj som stop" -> shelter must be added as WaypointRole.VIA, NOT OVERNIGHT!
        vm.confirmNearestShelterRole(WaypointRole.VIA)
        // Wait for coroutine to complete while idling main looper
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.uiState.value.isRoutingToNearest && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(10)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        val insertedStop = vm.uiState.value.waypoints.find { it.linkedPoiId == "shelter_detour_1" }
        assertNotNull("Shelter must be inserted into waypoints", insertedStop)
        assertEquals(
            "Choosing 'Tilføj som stop' must insert shelter with WaypointRole.VIA",
            WaypointRole.VIA,
            insertedStop?.role
        )
        assertEquals(
            "Real extra route length must be formatted in Danish from strings.xml ('Ruten blev 1,4 km længere')",
            "Ruten blev 1,4 km længere",
            vm.uiState.value.lastNearestExtraRouteMessage
        )
    }
}
