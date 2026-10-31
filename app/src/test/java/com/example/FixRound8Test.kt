package com.example

import android.app.Notification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SettingsRepository
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.model.WayTagRangeDto
import com.example.data.repository.GeoFaRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.data.repository.loadBundledFriTeltningFromDiskOrAssets
import com.example.data.repository.loadBundledPoisFromDiskOrAssets
import com.example.data.repository.loadBundledSheltersFromDiskOrAssets
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.SurfaceMixBar
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSourceHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound8Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
        NavigationTrackingService.updateLiveStateForTest(NavigationLiveState())
        runBlocking {
            app.settingsRepository.setMitTempoKmh(4.0)
        }
    }

    @Test
    fun item1_trailViewModelCountsUntaggedWaysAsUkendtAndHidesUkendtWhenZero(): Unit = runBlocking {
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "trail_ohavsstien"
        val pts = listOf(
            GeoPointDto(55.0, 10.0, 5.0),
            GeoPointDto(55.05, 10.05, 10.0),
            GeoPointDto(55.10, 10.10, 15.0)
        )

        // Case A: Trail with untagged ways (neither highway nor surface) -> shows Ukendt share
        val geomWithUntagged = TrailGeometryDto(
            trailId = trailId,
            osmRelationId = 1202410L,
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = pts,
                    lengthKm = 100.0,
                    highway = null,
                    surface = null,
                    wayTagRanges = listOf(
                        WayTagRangeDto(fromKm = 0.0, toKm = 40.0, highway = "path", surface = "dirt"),
                        WayTagRangeDto(fromKm = 40.0, toKm = 60.0, highway = "track", surface = "gravel"),
                        WayTagRangeDto(fromKm = 60.0, toKm = 75.0, highway = "tertiary", surface = "asphalt"),
                        WayTagRangeDto(fromKm = 75.0, toKm = 100.0, highway = null, surface = null)
                    )
                )
            ),
            sideBranches = emptyList(),
            gaps = emptyList(),
            totalWalkingKm = 100.0,
            totalAscentM = 100,
            totalDescentM = 100,
            gapsOver200mCount = 0,
            bboxNorth = 55.10,
            bboxSouth = 55.0,
            bboxEast = 10.10,
            bboxWest = 10.0
        )
        geomRepo.saveGeometry(geomWithUntagged)

        val vm = TrailViewModel(app)
        vm.loadTrail(trailId)

        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.surfaceMix.value.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        val mixWithUntagged = vm.surfaceMix.value
        assertEquals(40, mixWithUntagged["Sti"])
        assertEquals(20, mixWithUntagged["Grus"])
        assertEquals(15, mixWithUntagged["Asfalt"])
        assertEquals(25, mixWithUntagged["Ukendt"])

        // Case B: Trail where all ways are tagged (Ukendt is 0%) -> Ukendt is omitted / hidden
        val geomFullyTagged = geomWithUntagged.copy(
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = pts,
                    lengthKm = 100.0,
                    highway = "path",
                    surface = "dirt",
                    wayTagRanges = listOf(
                        WayTagRangeDto(fromKm = 0.0, toKm = 50.0, highway = "path", surface = "dirt"),
                        WayTagRangeDto(fromKm = 50.0, toKm = 80.0, highway = "track", surface = "gravel"),
                        WayTagRangeDto(fromKm = 80.0, toKm = 100.0, highway = "tertiary", surface = "asphalt")
                    )
                )
            )
        )
        geomRepo.saveGeometry(geomFullyTagged)
        vm.loadTrail(trailId)

        val deadline2 = System.currentTimeMillis() + 5_000L
        while (vm.surfaceMix.value["Sti"] != 50 && System.currentTimeMillis() < deadline2) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        val mixFullyTagged = vm.surfaceMix.value
        assertEquals(50, mixFullyTagged["Sti"])
        assertEquals(30, mixFullyTagged["Grus"])
        assertEquals(20, mixFullyTagged["Asfalt"])
        assertNull("Ukendt must be omitted when 0%", mixFullyTagged["Ukendt"])

        // Verify UI rendering in SurfaceMixBar: shows Ukendt when > 0% and hides Ukendt when 0%
        var currentMix by androidx.compose.runtime.mutableStateOf(mixWithUntagged)
        composeTestRule.setContent {
            DanskStiTheme {
                SurfaceMixBar(surfaceMix = currentMix)
            }
        }
        composeTestRule.onNodeWithText("Sti 40%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grus 20%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Asfalt 15%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ukendt 25%").assertIsDisplayed()

        currentMix = mixFullyTagged
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sti 50%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grus 30%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Asfalt 20%").assertIsDisplayed()
        assertTrue(
            "Ukendt must be hidden when 0%",
            composeTestRule.onAllNodesWithText("Ukendt", substring = true).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun item2_etaUsesUserMitTempoWhenSpeedBelow15KmhInNavigationScreenAndServiceNotification(): Unit = runBlocking {
        // Set user's "Mit tempo" to 5.0 km/h
        app.settingsRepository.setMitTempoKmh(5.0)
        assertEquals(5.0, app.settingsRepository.settingsFlow.first().mitTempoKmh, 0.001)

        // 1. Verify NavigationTrackingService notification uses 5.0 km/h when speed is 0.0 km/h (and 1.2 km/h < 1.5 km/h)
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()

        // 10 km remaining at 5.0 km/h = 120 minutes (2 hours)
        val notifStanding: Notification = service.buildNotification(
            trailName = "Øhavsstien",
            stageName = "Lejr 1",
            speedKmh = 1.2, // below 1.5 km/h -> must use Mit tempo (5.0 km/h), NOT 1.2 or 4.2
            remainingTodayKm = 10.0,
            remainingTripKm = 50.0,
            isOffRoute = false,
            offRouteMeters = 0.0
        )
        val expectedEtaMillis = System.currentTimeMillis() + 120 * 60_000L
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val expectedEtaClock = timeFormat.format(Date(expectedEtaMillis))
        val notifText = notifStanding.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(
            "Expected notification ETA at 5.0 km/h for 10 km to be kl. $expectedEtaClock, got: $notifText",
            notifText.contains("kl. $expectedEtaClock")
        )

        // 2. Verify NavigationScreen top card uses user's "Mit tempo" (5.0 km/h) when speed < 1.5 km/h
        // 10.0 km remaining at 5.0 km/h = 2.0 hours -> 09:00 + 2h = 11:00 (whereas 4.2 km/h would be 11:20)
        val plannedRoute = PlannedRoute(
            planId = 1L,
            trailId = "trail_ohavsstien",
            trailName = "Øhavsstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 10.0,
            totalWalkKm = 10.0,
            points = listOf(
                RoutePoint(lat = 55.0, lng = 10.0, ele = 5.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 55.09, lng = 10.0, ele = 5.0, walkKm = 10.0, trailKm = 10.0)
            ),
            stages = listOf(
                TripStage(
                    dayNumber = 1,
                    startKm = 0.0,
                    endKm = 10.0,
                    stageKm = 10.0,
                    stopName = "Lejr 1",
                    stopType = "SHELTER",
                    stopLat = 55.09,
                    stopLng = 10.0,
                    detourMeters = 0.0,
                    hasWaterNearby = true,
                    hasToiletNearby = true
                )
            )
        )
        NavigationTrackingService.updateLiveStateForTest(
            NavigationLiveState(
                isTracking = true,
                speedKmh = 0.0,
                distanceRemainingTodayKm = 10.0,
                currentDay = 1,
                plannedRoute = plannedRoute,
                currentStage = plannedRoute.stages.first()
            )
        )

        val navVm = NavigationViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                NavigationScreen(
                    viewModel = navVm,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_top_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("ankomst ca. $expectedEtaClock", substring = true).assertIsDisplayed()

        // Verify 4.2 constant is removed from NavigationScreen.kt and NavigationTrackingService.kt
        val navScreenSrc = File("src/main/java/com/example/ui/screens/NavigationScreen.kt").readText()
        val navServiceSrc = File("src/main/java/com/example/service/NavigationTrackingService.kt").readText()
        assertFalse("NavigationScreen.kt must not contain 4.2 constant", navScreenSrc.contains("4.2"))
        assertFalse("NavigationTrackingService.kt must not contain 4.2 constant", navServiceSrc.contains("4.2"))
    }

    @Test
    fun item3_noFileInSrcMainReadsFromProjectRelativePathsAndTestHelperWorks() {
        val mainDir = File("src/main")
        val offendingFiles = mutableListOf<String>()
        mainDir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
            val content = file.readText()
            if (content.contains("src/main") ||
                content.contains("loadBundledSheltersFromDiskOrAssets") ||
                content.contains("loadBundledFriTeltningFromDiskOrAssets") ||
                content.contains("loadBundledPoisFromDiskOrAssets") ||
                content.contains("readAssetOrDiskGeoJsonGz")
            ) {
                offendingFiles.add(file.path)
            }
        }
        assertTrue("Expected no files in src/main to read from project-relative paths, found: $offendingFiles", offendingFiles.isEmpty())

        // Verify test helper under src/test loads bundled GeoFA data
        val repo = GeoFaRepository(app)
        assertTrue(repo.loadBundledSheltersFromDiskOrAssets().size > 1000)
        assertTrue(repo.loadBundledFriTeltningFromDiskOrAssets().size > 200)
        assertTrue(repo.loadBundledPoisFromDiskOrAssets().isNotEmpty())
    }

    @Test
    fun item4_stringsMovedToStringsXmlInTrailDetailScreenAndPlannerBottomSheet() {
        val trailDetailSrc = File("src/main/java/com/example/ui/screens/TrailDetailScreen.kt").readText()
        val plannerSheetSrc = File("src/main/java/com/example/ui/screens/planner/PlannerBottomSheet.kt").readText()

        assertFalse("TrailDetailScreen must not hardcode 'Stiandel: ukendt · Vej: ukendt'", trailDetailSrc.contains("Stiandel:"))
        assertFalse("PlannerBottomSheet must not hardcode 'Starter...'", plannerSheetSrc.contains("\"Starter...\""))
        assertFalse("PlannerBottomSheet must not hardcode 'Stiandel:'", plannerSheetSrc.contains("Stiandel:"))

        assertEquals("Starter...", app.getString(R.string.planner_btn_starting))
        assertEquals("Stiandel: ukendt · Vej: ukendt", app.getString(R.string.way_info_unknown_summary))
        assertEquals("Stiandel: 75% · Vej: 12,0 km", app.getString(R.string.way_info_summary_format, "75%", "12,0 km"))
    }
}
