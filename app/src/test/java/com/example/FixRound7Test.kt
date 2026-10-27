package com.example

import android.app.Notification
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CustomRouteEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WayTagRangeDto
import com.example.data.repository.GeoFaRepository
import com.example.data.repository.loadBundledFriTeltningFromDiskOrAssets
import com.example.data.repository.loadBundledPoisFromDiskOrAssets
import com.example.data.repository.loadBundledSheltersFromDiskOrAssets
import com.example.data.repository.PlannedRouteRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.service.NavigationTrackingService
import com.example.util.DanskStiTileSourceHelper
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailSlicer
import com.example.util.routing.WaypointRole
import java.io.File
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
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound7Test {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
            // Build a 220 km Øhavsstien geometry with > 2,000 points (~100m spacing)
            val pts = (0..2200).map { idx ->
                GeoPointDto(
                    lat = 55.0200 + idx * 0.0004,
                    lng = 10.2000 + idx * 0.0005,
                    ele = 5.0 + (idx % 20)
                )
            }
            val geom = TrailGeometryDto(
                trailId = "trail_ohavsstien",
                osmRelationId = 1202410L,
                segments = listOf(
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts,
                        lengthKm = 220.0,
                        highway = "path",
                        surface = "dirt",
                        wayTagRanges = listOf(
                            WayTagRangeDto(fromKm = 0.0, toKm = 160.0, highway = "path", surface = "dirt"),
                            WayTagRangeDto(fromKm = 160.0, toKm = 220.0, highway = "tertiary", surface = "asphalt")
                        )
                    )
                ),
                sideBranches = emptyList(),
                gaps = emptyList(),
                totalWalkingKm = 220.0,
                totalAscentM = 450,
                totalDescentM = 440,
                gapsOver200mCount = 0,
                bboxNorth = pts.maxOf { it.lat },
                bboxSouth = pts.minOf { it.lat },
                bboxEast = pts.maxOf { it.lng },
                bboxWest = pts.minOf { it.lng }
            )
            TrailGeometryRepository(app).saveGeometry(geom)
            val trail = app.database.dao().getTrailById("trail_ohavsstien")
            if (trail != null) {
                app.database.dao().insertTrails(
                    listOf(
                        trail.copy(
                            isGeometryDownloaded = true,
                            computedWalkingKm = 220.0,
                            bboxNorth = geom.bboxNorth,
                            bboxSouth = geom.bboxSouth,
                            bboxEast = geom.bboxEast,
                            bboxWest = geom.bboxWest
                        )
                    )
                )
            }
        }
    }

    @After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
    }

    @Test
    fun item1_backupRulesBackUpRoutesAndPlansAndMigrateLegacyPhotosToPhotosDir(): Unit = runBlocking {
        val backupXml = File("src/main/res/xml/backup_rules.xml").readText()
        val extractionXml = File("src/main/res/xml/data_extraction_rules.xml").readText()

        for (xml in listOf(backupXml, extractionXml)) {
            assertTrue(xml.contains("<include domain=\"file\" path=\"routes\" />"))
            assertTrue(xml.contains("<include domain=\"file\" path=\"plans\" />"))
            for (excluded in listOf("trails", "geofa", "osm_pois", "offline", "brouter", "osmdroid", "photos")) {
                assertTrue("Expected $excluded to be excluded", xml.contains("<exclude domain=\"file\" path=\"$excluded\" />"))
            }
            assertFalse("Wildcards are not supported in backup rules", xml.contains("*"))
        }

        // Verify legacy wp_photo_* in filesDir is moved to files/photos/ and DB path updated
        val legacyFile = File(app.filesDir, "wp_photo_legacy_test.jpg")
        legacyFile.writeText("dummy-jpeg-bytes")
        val wpId = "wp_legacy_test_1"
        app.database.dao().insertCustomWaypoint(
            UserCustomWaypointEntity(
                id = wpId,
                name = "Legacy Photo WP",
                lat = 55.5,
                lng = 10.5,
                photoPath = legacyFile.absolutePath
            )
        )

        app.migrateLegacyWaypointPhotosFiles()
        app.migrateLegacyWaypointPhotosInDb(app.database.dao())

        val movedFile = File(File(app.filesDir, "photos"), "wp_photo_legacy_test.jpg")
        assertFalse("Legacy photo should be moved out of root filesDir", legacyFile.exists())
        assertTrue("Legacy photo should exist inside files/photos/", movedFile.exists())

        val updatedWp = app.database.dao().getAllCustomWaypointsList().first { it.id == wpId }
        assertEquals(movedFile.absolutePath, updatedWp.photoPath)
        movedFile.delete()
    }

    @Test
    fun item2_navigationTrackingServiceOmitsRemainingTodayWhenNoStageYet() {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        service.startForegroundLocationTracking()
        val shadowService = shadowOf(service)
        val notification: Notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertFalse("Notification must not show 15.0 fallback before stage data arrives: $text", text.contains("15"))
        assertEquals("Næste lejr: Overnatning", text)
    }

    @Test
    fun item3_trailWithoutWayTagRangesShowsUkendtAndRedownloadsOnlineKeepingOldFileOnFailure(): Unit = runBlocking {
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "trail_no_waytags_test"
        val legacyGeom = TrailGeometryDto(
            trailId = trailId,
            osmRelationId = 999999L,
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = listOf(
                        GeoPointDto(55.0, 10.0, 5.0),
                        GeoPointDto(55.01, 10.01, 8.0),
                        GeoPointDto(55.02, 10.02, 10.0)
                    ),
                    lengthKm = 2.5,
                    highway = "path",
                    surface = "dirt",
                    wayTagRanges = emptyList()
                )
            ),
            sideBranches = emptyList(),
            gaps = emptyList(),
            totalWalkingKm = 2.5,
            totalAscentM = 5,
            totalDescentM = 0,
            gapsOver200mCount = 0,
            bboxNorth = 55.02,
            bboxSouth = 55.0,
            bboxEast = 10.02,
            bboxWest = 10.0
        )
        geomRepo.saveGeometry(legacyGeom)
        val loaded = geomRepo.loadGeometry(trailId)
        assertNotNull(loaded)
        assertFalse("Expected hasWayTagRanges to be false", geomRepo.hasWayTagRanges(loaded!!))

        // Verify TrailSlicer does not use segment-level highway/surface for pathSharePct/roadKm when wayTagRanges is empty
        val (pathSharePct, _) = TrailSlicer.computeTrailRangeMetrics(loaded.segments)
        assertNull("Path share must be null (ukendt) when wayTagRanges is empty", pathSharePct)

        val leg = TrailSlicer.sliceTrailLeg(
            fromWaypointId = "wp_0",
            toWaypointId = "wp_1",
            segments = loaded.segments,
            fromKm = 0.0,
            toKm = 2.5
        )
        assertNull("Sliced leg pathSharePct must be null (ukendt)", leg.pathSharePct)
        val stats = RouteGeometryStorageManager.calculateStats(listOf(leg))
        assertFalse("hasWayInfo must be false when wayTagRanges is empty", stats.hasWayInfo)
        assertNull("RouteStats.pathSharePct must be null (ukendt)", stats.pathSharePct)

        // Verify old file is kept if online re-download fails
        val existingFile = File(File(app.filesDir, "trails"), "$trailId.json.gz")
        assertTrue(existingFile.exists())
        app.repository.isOnlineOverrideForTests = true
        val fallbackGeom = app.repository.getTrailGeometry(trailId)
        assertNotNull("Old geometry file must be kept and returned when online re-download cannot reach Overpass", fallbackGeom)
        assertTrue("Old geometry file must still exist on disk", existingFile.exists())
        app.repository.isOnlineOverrideForTests = false
        existingFile.delete()
    }

    @Test
    fun item4_routeGeometryStorageManagerSkipsSavingEmptyRouteWithoutFakeBbox(): Unit = runBlocking {
        val converted = RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(
            routeId = "empty_route_test",
            orderedLegs = emptyList(),
            customRouteName = "Tom Rute"
        )
        assertNull("Empty route must return null geometry instead of 57/55/12/8 fake bbox", converted)

        val emptyEntity = CustomRouteEntity(
            name = "Tom Rute",
            createdAt = 1000L,
            updatedAt = 1000L,
            optionsJson = "{}",
            waypointsJson = "[]",
            totalKm = 0.0,
            ascentM = 0.0,
            descentM = 0.0,
            pathSharePct = 0.0,
            roadKm = 0.0,
            geometryFile = "",
            sourceName = "Test",
            fetchedAt = 1000L
        )
        val savedId = app.repository.saveCustomRoute(emptyEntity)
        val routeFile = File(File(app.filesDir, "routes"), "route_$savedId.json.gz")
        assertFalse("Empty route must not write a geometry file in files/routes/", routeFile.exists())
    }

    @Test
    fun item5_lintConfigMakesStringFormatIssuesErrorsWithAbortOnError() {
        val gradleKts = File("build.gradle.kts").readText()
        assertTrue(gradleKts.contains("StringFormatMatches"))
        assertTrue(gradleKts.contains("StringFormatInvalid"))
        assertTrue(gradleKts.contains("StringFormatCount"))
        assertTrue(gradleKts.contains("abortOnError = true"))
    }

    @Test
    fun item6e_saving200KmRouteWithAllBundledGeoFaSheltersAndFriTeltningCompletesUnder2Seconds() = runBlocking {
        val geofaRepo = GeoFaRepository(app)
        val allShelters = geofaRepo.loadBundledSheltersFromDiskOrAssets()
        val allFriTeltning = geofaRepo.loadBundledFriTeltningFromDiskOrAssets()
        val allPois = geofaRepo.loadBundledPoisFromDiskOrAssets()

        assertTrue("Expected bundled shelters > 1000, got ${allShelters.size}", allShelters.size > 1000)
        assertTrue("Expected bundled fri teltning > 200, got ${allFriTeltning.size}", allFriTeltning.size > 200)

        // Build a 200 km route with 2,001 points (~100 m spacing) across Fyn / Øhavsstien
        val routePoints3D = (0..2000).map { i ->
            RoutePoint3D(
                lat = 55.0000 + i * 0.00045,
                lng = 10.1000 + i * 0.00055,
                ele = 10.0 + (i % 25)
            )
        }
        val wpStart = RouteWaypoint(
            id = "wp_start",
            lat = routePoints3D.first().lat,
            lng = routePoints3D.first().lng,
            name = "Start",
            role = WaypointRole.START,
            trailKm = 0.0
        )
        val wpEnd = RouteWaypoint(
            id = "wp_end",
            lat = routePoints3D.last().lat,
            lng = routePoints3D.last().lng,
            name = "Slut",
            role = WaypointRole.END,
            trailKm = 200.0
        )
        val legKey = "${wpStart.id}_to_${wpEnd.id}"
        val legData = LegGeometryData(
            fromWaypointId = wpStart.id,
            toWaypointId = wpEnd.id,
            legMode = LegMode.SNAP,
            points = routePoints3D,
            lengthKm = 200.0,
            ascentM = 600.0,
            descentM = 580.0,
            pathSharePct = 75.0,
            roadKm = 50.0,
            sourceName = "Øhavsstien"
        )

        val plannedRouteRepo = PlannedRouteRepository(app)

        val startNs = System.nanoTime()
        val (savedPlanId, stages, plannedRoute) = plannedRouteRepo.saveUnifiedPlanAndRoute(
            existingRouteId = null,
            existingPlanId = null,
            name = "Øhavsstien 200 km Test",
            baseTrailId = "trail_ohavsstien",
            isReverse = false,
            optionsJson = "{}",
            waypoints = listOf(wpStart, wpEnd),
            legs = mapOf(legKey to legData),
            paceKmh = 4.5,
            dailyTargetKm = 25.0,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            startTime = "09:00",
            shelters = allShelters,
            friTeltnings = allFriTeltning,
            pois = allPois
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("Expected savedPlanId > 0, got $savedPlanId", savedPlanId > 0L)
        assertTrue("Expected stages to be generated, got ${stages.size}", stages.isNotEmpty())
        assertTrue("Expected plannedRoute points > 1000, got ${plannedRoute.points.size}", plannedRoute.points.size > 1000)
        assertTrue(
            "Expected saving 200 km route with all bundled GeoFA shelters and fri teltning areas to complete in < 2000 ms, but took ${elapsedMs}ms",
            elapsedMs < 2_000L
        )
    }

    @Test
    fun item6e_planlaegTurOnOhavsstienStartOpensNavigationWithRouteDrawnWithin5Seconds() {
        // 1. Open Øhavsstien from Udforsk
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("trail_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("trail_card_trail_ohavsstien"))
        composeTestRule.onNodeWithTag("trail_card_trail_ohavsstien").performClick()
        composeTestRule.waitForIdle()

        // 2. Tap "Planlæg tur"
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("plan_trip_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("plan_trip_button").performClick()
        composeTestRule.waitForIdle()

        // 3. Wait for Start button to be enabled
        composeTestRule.waitUntil(15_000) {
            val nodes = composeTestRule.onAllNodesWithTag("btn_start_planned_navigation").fetchSemanticsNodes()
            nodes.isNotEmpty() && !nodes.first().config.contains(SemanticsProperties.Disabled)
        }

        // 4. Press Start and verify navigation screen opens with route drawn within 5 seconds
        val startMs = System.currentTimeMillis()
        composeTestRule.onNodeWithTag("btn_start_planned_navigation").performClick()

        composeTestRule.waitUntil(5_000) {
            val mapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
            mapView != null && countRouteOverlayPoints(mapView.overlays) > 100
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        val navMapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
        val routePointCount = navMapView?.let { countRouteOverlayPoints(it.overlays) } ?: 0
        assertTrue(
            "Expected Start on Øhavsstien to open navigation with route drawn (>100 pts) within 5000ms, took ${elapsedMs}ms with $routePointCount pts",
            elapsedMs <= 5_000L && routePointCount > 100
        )
    }

    @Test
    fun item7_routeBuilderViewModelUsesAtomicUpdateOnly() {
        val vmSource = File("src/main/java/com/example/ui/viewmodel/RouteBuilderViewModel.kt").readText()
        assertFalse(
            "RouteBuilderViewModel must not contain _uiState.value = assignments",
            vmSource.contains(Regex("_uiState\\.value\\s*="))
        )
        assertTrue(
            "RouteBuilderViewModel must use _uiState.update",
            vmSource.contains("_uiState.update")
        )
    }

    private fun findMapViewByTag(root: View?, tag: String): MapView? {
        if (root == null) return null
        if (root is MapView && (root.tag == tag || tag.isEmpty())) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findMapViewByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun countRouteOverlayPoints(overlays: List<Overlay>): Int {
        var maxPolylinePoints = 0
        var totalWalkPolylinePoints = 0
        for (overlay in overlays) {
            when (overlay) {
                is Polyline -> {
                    val pts = overlay.actualPoints?.size ?: 0
                    if (pts > maxPolylinePoints) {
                        maxPolylinePoints = pts
                    }
                    totalWalkPolylinePoints += pts
                }
                is FolderOverlay -> {
                    val folderPts = countRouteOverlayPoints(overlay.items)
                    if (folderPts > maxPolylinePoints) {
                        maxPolylinePoints = folderPts
                    }
                }
            }
        }
        return maxOf(maxPolylinePoints, totalWalkPolylinePoints / 2)
    }
}
