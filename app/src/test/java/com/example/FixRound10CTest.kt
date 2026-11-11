package com.example

import android.app.Application
import android.location.Location
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailPoiEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.repository.PlannedRouteRepository
import com.example.service.NavigationTrackingService
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.OfflineViewModel
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SavedViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSourceHelper
import com.example.util.OfflineDownloadManager
import com.example.util.StagePlanner
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.HybridRoutingEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RemoteSegmentHeader
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.delay
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound10CTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: DanskStiApp
    private lateinit var db: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = app.database
        com.example.util.DanskStiTileSource.userConfirmedOsmFallback = true
        BRouterStorageManager.customHeadFetcher = null
        BRouterOfflineEngine.simulateVersionMismatchForTests = false
        OfflineDownloadManager.freeBytesProviderOverride = { 10L * 1024L * 1024L * 1024L }
        OfflineDownloadManager.networkStatusProviderOverride = { true to true }
        BRouterStorageManager.getInstance(app).clearRemoteMetadataCacheForTests()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        BRouterStorageManager.customHeadFetcher = null
        BRouterOfflineEngine.simulateVersionMismatchForTests = false
        OfflineDownloadManager.freeBytesProviderOverride = null
        OfflineDownloadManager.networkStatusProviderOverride = null
        runCatching {
            if (androidx.work.WorkManager.isInitialized()) {
                androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
            }
        }
        runCatching {
            db.dao().clearAllOfflineDownloadsInProgress()
        }
    }

    private suspend fun waitUntilCondition(timeoutMs: Long = 5_000L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            if (predicate()) return
            delay(40L)
        }
        runCatching { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    // =========================================================================
    // Item 1: Undo after delete (Mine ture: custom route, trip plan with progress, offline trail)
    // =========================================================================
    @Test
    fun item1_undoAfterDeleteRestoresCustomRouteTripPlanWithProgressAndOfflineTrailInFullWorkingOrder() = runBlocking {
        val dao = db.dao()
        val repo = app.repository
        val plannedRouteRepo = PlannedRouteRepository(app)
        val savedVm = SavedViewModel(app)

        // 1. Prepare a Custom Route with leg geometry and corridor POIs
        val customRouteTemplate = CustomRouteEntity(
            id = 0L,
            name = "Fynske Kyststi Custom",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            optionsJson = "{}",
            waypointsJson = """[{"id":"wp1","lat":55.39,"lng":10.38,"role":"START"},{"id":"wp2","lat":55.41,"lng":10.42,"role":"END"}]""",
            totalKm = 12.4,
            ascentM = 85.0,
            descentM = 80.0,
            pathSharePct = 78.0,
            roadKm = 2.1,
            geometryFile = "custom_route_10c_undo.json",
            sourceName = "brouter-test",
            fetchedAt = System.currentTimeMillis()
        )
        val routeIdLong = dao.insertCustomRoute(customRouteTemplate)
        val customRoute = dao.getCustomRouteById(routeIdLong)!!
        val routeIdStr = routeIdLong.toString()

        val leg = LegGeometryData(
            fromWaypointId = "wp1",
            toWaypointId = "wp2",
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(55.39, 10.38, 10.0), RoutePoint3D(55.41, 10.42, 22.0)),
            lengthKm = 12.4,
            ascentM = 85.0,
            descentM = 80.0,
            pathSharePct = 78.0,
            roadKm = 2.1,
            hasAccessWarning = false,
            sourceName = "brouter-test",
            fetchedAt = System.currentTimeMillis()
        )
        RouteGeometryStorageManager.saveAllLegs(app, customRoute.geometryFile, mapOf(leg.legKey to leg))
        dao.insertPois(
            listOf(
                PoiEntity(
                    id = "poi_shop_10c",
                    name = "Gårdbutik Fyn",
                    type = "shop",
                    lat = 55.40,
                    lng = 10.40
                )
            )
        )
        dao.insertTrailPois(
            listOf(
                TrailPoiEntity(
                    trailId = "route_$routeIdStr",
                    poiId = "poi_shop_10c",
                    kmOnRoute = 5.0,
                    detourMeters = 120.0,
                    routeId = routeIdStr
                )
            )
        )

        // 2. Prepare a Trip Plan with stages, PlannedRoute file, TripProgress, RecordedTrackPoints & RecordedDayStat
        val trail = TrailEntity(
            id = "trail_10c_plan",
            name = "Øhavsstien Test",
            region = "Fyn",
            computedWalkingKm = 20.0,
            description = "Test",
            isGeometryDownloaded = true
        )
        dao.insertTrails(listOf(trail))
        val stage1 = TripStage(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 10.0,
            stageKm = 10.0,
            stopName = "Shelter 1",
            stopType = "SHELTER",
            stopLat = 55.05,
            stopLng = 10.25,
            spotId = "sh1"
        )
        val planId = repo.saveTripPlan(
            trail = trail,
            startKm = 0.0,
            endKm = 10.0,
            isReverse = false,
            days = 1,
            overnightType = OvernightType.SHELTER,
            preferredDailyKm = 10.0,
            stages = listOf(stage1)
        )
        val pr = plannedRouteRepo.buildPlannedRoute(
            planId = planId,
            trail = trail,
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = listOf(GeoPointDto(55.0, 10.2, 5.0), GeoPointDto(55.05, 10.25, 18.0))
                )
            ),
            startKm = 0.0,
            endKm = 10.0,
            isReverse = false,
            stages = repo.getStagesForPlan(planId)
        )
        plannedRouteRepo.savePlannedRoute(pr)
        dao.saveTripProgress(
            TripProgressEntity(
                planId = planId,
                currentDay = 1,
                distanceWalkedTodayKm = 6.5,
                totalDistanceWalkedKm = 6.5,
                currentSnappedLat = 55.03,
                currentSnappedLng = 10.23,
                remainingDistanceTripKm = 3.5,
                remainingDistanceTodayKm = 3.5,
                isPaused = false,
                isCompleted = false
            )
        )
        dao.insertRecordedTrackPoint(
            RecordedTrackPointEntity(
                planId = planId,
                dayNumber = 1,
                lat = 55.02,
                lng = 10.22,
                altitude = 12.0,
                accuracy = 4.0f,
                speedKmh = 4.5,
                timestamp = System.currentTimeMillis()
            )
        )
        dao.saveRecordedDayStat(
            RecordedDayStatEntity(
                planId = planId,
                dayNumber = 1,
                distanceKm = 6.5,
                ascentMeters = 42,
                movingTimeSeconds = 5400L,
                pointCount = 1,
                lastTimestamp = System.currentTimeMillis()
            )
        )

        // Test Trip Plan delete + Undo ("Fortryd")
        val planEntity = dao.getTripPlanById(planId)!!
        savedVm.deletePlanWithUndo(planEntity)
        waitUntilCondition { dao.getTripPlanById(planId) == null }

        // While snackbar is open (before commit), files, stages, progress, and tracks are still intact!
        assertNotNull("PlannedRoute file must remain until snackbar closes without Fortryd", plannedRouteRepo.loadPlannedRoute(planId))
        assertNotNull("TripProgress must remain until snackbar closes without Fortryd", dao.getTripProgressSync(planId))
        assertEquals(1, dao.getTripStagesForPlanSync(planId).size)
        assertEquals(1, dao.getAllRecordedTrackPointsForPlanSync(planId).size)

        // Tap "Fortryd"
        savedVm.undoLastDelete()
        waitUntilCondition { dao.getTripPlanById(planId) != null }
        assertEquals(planId, dao.getTripPlanById(planId)?.id)
        assertEquals(6.5, dao.getTripProgressSync(planId)?.totalDistanceWalkedKm ?: 0.0, 0.01)
        assertEquals(1, dao.getTripStagesForPlanSync(planId).size)
        assertNotNull(plannedRouteRepo.loadPlannedRoute(planId))

        // Test Custom Route delete + Undo ("Fortryd")
        savedVm.deleteCustomRouteWithUndo(customRoute)
        waitUntilCondition { dao.getCustomRouteById(routeIdLong) == null }

        // Geometry file and corridor POIs must still be intact before commit
        assertTrue(RouteGeometryStorageManager.loadLegs(app, customRoute.geometryFile).isNotEmpty())
        assertTrue(repo.getPoisForRoute(routeIdStr).isNotEmpty())

        // Tap "Fortryd"
        savedVm.undoLastDelete()
        waitUntilCondition { dao.getCustomRouteById(routeIdLong) != null }
        assertEquals("Fynske Kyststi Custom", dao.getCustomRouteById(routeIdLong)?.name)
        assertTrue(RouteGeometryStorageManager.loadLegs(app, customRoute.geometryFile).containsKey(leg.legKey))
        assertEquals(1, repo.getPoisForRoute(routeIdStr).size)

        // 3. Test Offline Trail delete + Undo ("Fortryd")
        val offlineTrailId = "offline_trail_10c"
        val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(app, offlineTrailId)
        archiveFile.parentFile?.mkdirs()
        archiveFile.writeBytes(ByteArray(4096) { 0x2A })
        val dlEntity = DownloadedTrailEntity(
            trailId = offlineTrailId,
            trailName = "Gendarmstien Offline",
            corridorKm = 1.0,
            tileCount = 420,
            sizeBytes = 4096L,
            downloadedAt = System.currentTimeMillis()
        )
        dao.insertDownloadedTrail(dlEntity)

        savedVm.deleteDownloadedTrailWithUndo(dlEntity)
        waitUntilCondition { repo.getDownloadedTrail(offlineTrailId) == null }
        assertTrue("Offline archive file must NOT be deleted before snackbar closes", archiveFile.exists())

        // Tap "Fortryd"
        savedVm.undoLastDelete()
        waitUntilCondition { repo.getDownloadedTrail(offlineTrailId) != null }
        assertTrue("Offline archive file must still exist after Fortryd", archiveFile.exists())
        assertEquals("Gendarmstien Offline", repo.getDownloadedTrail(offlineTrailId)?.trailName)

        // Now verify permanent deletion when snackbar closes WITHOUT "Fortryd"
        savedVm.deleteDownloadedTrailWithUndo(dlEntity)
        waitUntilCondition { repo.getDownloadedTrail(offlineTrailId) == null }
        savedVm.commitPendingDelete()
        waitUntilCondition { !archiveFile.exists() }
        assertFalse(archiveFile.exists())
    }

    // =========================================================================
    // Item 2: Navigation notification opens navigation screen for active plan
    // =========================================================================
    @Test
    fun item2_navigationNotificationIntentOpensNavigationScreenForActivePlan() {
        val activePlanId = 777L
        val launchIntent = NavigationTrackingService.buildLaunchNavigationIntent(app, activePlanId)

        assertEquals(activePlanId, launchIntent.getLongExtra(NavigationTrackingService.EXTRA_PLAN_ID, -1L))
        assertTrue(launchIntent.getBooleanExtra(NavigationTrackingService.EXTRA_OPEN_NAVIGATION, false))

        // Verify MainActivity handles the intent in onCreate and onNewIntent
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent).setup()
        val activity = controller.get()
        assertNotNull(activity)

        val newPlanId = 888L
        val secondIntent = NavigationTrackingService.buildLaunchNavigationIntent(app, newPlanId)
        assertEquals(newPlanId, secondIntent.getLongExtra(NavigationTrackingService.EXTRA_PLAN_ID, -1L))
        controller.newIntent(secondIntent)
        assertFalse(secondIntent.hasExtra(NavigationTrackingService.EXTRA_PLAN_ID))
        controller.pause().stop().destroy()
    }

    // =========================================================================
    // Item 3: Trail page "Hent offline" downloads directly with progress & "Se offline-kort" link
    // =========================================================================
    @Test
    fun item3_trailPageHentOfflineStartsDownloadDirectlyAndOnlySeOfflineKortOpensOfflineScreen() = runBlocking {
        val trailId = "trail_10c_direct_offline"
        val pts = listOf(
            GeoPointDto(55.395, 10.385, 12.0),
            GeoPointDto(55.405, 10.395, 18.0)
        )
        val trail = TrailEntity(
            id = trailId,
            name = "Odense Åsti",
            region = "Fyn",
            computedWalkingKm = 5.0,
            description = "Smuk sti langs Odense Å.",
            isGeometryDownloaded = true
        )
        db.dao().insertTrails(listOf(trail))
        app.repository.geometryRepository.saveGeometry(
            TrailGeometryDto(
                trailId = trailId,
                osmRelationId = 12345L,
                fetchedAt = System.currentTimeMillis(),
                totalWalkingKm = 5.0,
                totalAscentM = 20,
                totalDescentM = 20,
                gapsOver200mCount = 0,
                bboxNorth = 55.405,
                bboxSouth = 55.395,
                bboxEast = 10.395,
                bboxWest = 10.385,
                segments = listOf(
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts,
                        lengthKm = 5.0,
                        wayTagRanges = listOf(com.example.data.model.WayTagRangeDto(0.0, 5.0, "path", "dirt"))
                    )
                )
            )
        )

        val vm = TrailViewModel(app)
        var navigatedToOfflineScreenCount = 0

        composeRule.setContent {
            DanskStiTheme {
                TrailDetailScreen(
                    trailId = trailId,
                    viewModel = vm,
                    onBack = {},
                    onPlanTrip = {},
                    onStartNavigation = {},
                    onOpenFullscreenMap = {},
                    onDownloadOffline = { navigatedToOfflineScreenCount++ }
                )
            }
        }

        waitUntilCondition { vm.trail.value != null && vm.trailPoints.value.isNotEmpty() }
        composeRule.waitForIdle()

        // 1. Tap "Hent offline" on the trail page: must show confirmation dialog with size estimate, NOT open Offline screen
        composeRule.onNodeWithTag("action_download_offline").performClick()
        waitUntilCondition { vm.showOfflineConfirmDialog.value && vm.offlineEstimate.value != null }
        composeRule.waitForIdle()

        assertEquals("Tapping 'Hent offline' must NOT navigate to Offline screen", 0, navigatedToOfflineScreenCount)
        composeRule.onNodeWithTag("trail_offline_confirm_dialog").assertIsDisplayed()

        // 2. Confirm download: starts download directly and shows progress on the button
        composeRule.onNodeWithTag("confirm_trail_offline_download_button").performClick()
        composeRule.waitForIdle()
        vm.setOfflineMapProgressForTesting(isDownloading = true, progressPct = 45)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("action_download_offline_progress", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Henter (45%)", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, navigatedToOfflineScreenCount)

        // 3. Tapping "Se offline-kort" link opens the Offline screen
        composeRule.onNodeWithTag("see_offline_maps_link").performClick()
        composeRule.waitForIdle()
        assertEquals(1, navigatedToOfflineScreenCount)
    }

    // =========================================================================
    // Item 4: POIs for custom routes (cancellable job per route + live Flows in NavigationViewModel)
    // =========================================================================
    @Test
    fun item4_customRouteCorridorPoisSyncIsCancellableAndNavigationViewModelObservesLivePoiFlows() = runBlocking {
        // 1. Verify no fire-and-forget CoroutineScope(Dispatchers.IO).launch in DanskStiRepository.kt
        val repoFile = File("src/main/java/com/example/data/repository/DanskStiRepository.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/data/repository/DanskStiRepository.kt")
        if (repoFile.exists()) {
            val source = repoFile.readText()
            assertFalse(
                "DanskStiRepository must not contain fire-and-forget CoroutineScope(Dispatchers.IO).launch",
                source.contains("CoroutineScope(Dispatchers.IO).launch")
            )
        }

        val dao = db.dao()
        val repo = app.repository
        val plannedRouteRepo = PlannedRouteRepository(app)

        val customRouteId = "77710"
        val customTrailId = "route_$customRouteId"
        val routePoints = listOf(
            GeoPointDto(55.3950, 10.3880, 10.0),
            GeoPointDto(55.4000, 10.3950, 14.0),
            GeoPointDto(55.4050, 10.4020, 18.0)
        )

        // Trigger two rapid saves to verify the second cancels the first per-route job cleanly
        val job1 = repo.enqueueRouteCorridorPoiSync(customRouteId, routePoints)
        val job2 = repo.enqueueRouteCorridorPoiSync(customRouteId, routePoints)
        assertTrue("First POI sync job for same route must be cancelled when a new save occurs", job1.isCancelled || !job1.isActive)

        // Create a plan for this custom route and start NavigationViewModel BEFORE shops/water arrive
        val trailEntity = TrailEntity(
            id = customTrailId,
            name = "Frisklavet Rute",
            region = "Fyn",
            computedWalkingKm = 3.0,
            description = "Custom",
            isGeometryDownloaded = true
        )
        dao.insertTrails(listOf(trailEntity))
        val stage = TripStage(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 3.0,
            stageKm = 3.0,
            stopName = "Mål",
            stopType = "DESTINATION",
            stopLat = 55.4050,
            stopLng = 10.4020
        )
        val planId = repo.saveTripPlan(
            trail = trailEntity,
            startKm = 0.0,
            endKm = 3.0,
            isReverse = false,
            days = 1,
            overnightType = OvernightType.BOTH,
            preferredDailyKm = 3.0,
            stages = listOf(stage)
        )
        val pr = plannedRouteRepo.buildPlannedRoute(
            planId = planId,
            trail = trailEntity,
            segments = listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = routePoints, lengthKm = 3.0)),
            startKm = 0.0,
            endKm = 3.0,
            isReverse = false,
            stages = repo.getStagesForPlan(planId)
        )
        plannedRouteRepo.savePlannedRoute(pr)

        val navVm = NavigationViewModel(app)
        navVm.initPlan(planId)
        waitUntilCondition { navVm.activePlan.value?.id == planId && navVm.plannedRoute.value?.planId == planId }

        // Now insert a shop and water post into trail_pois after navigation has already started
        val shopPoi = PoiEntity(
            id = "live_shop_10c",
            name = "Bageri Undervejs",
            type = "shop",
            lat = 55.4000,
            lng = 10.3950
        )
        val waterPoi = PoiEntity(
            id = "live_water_10c",
            name = "Vandpost Skovbrynet",
            type = "water",
            lat = 55.4020,
            lng = 10.3980
        )
        dao.insertPois(listOf(shopPoi, waterPoi))
        dao.insertTrailPois(
            listOf(
                TrailPoiEntity(
                    trailId = customTrailId,
                    poiId = shopPoi.id,
                    kmOnRoute = 1.2,
                    detourMeters = 25.0,
                    routeId = customRouteId
                ),
                TrailPoiEntity(
                    trailId = customTrailId,
                    poiId = waterPoi.id,
                    kmOnRoute = 1.8,
                    detourMeters = 15.0,
                    routeId = customRouteId
                )
            )
        )

        // NavigationViewModel must automatically receive the new shop and water via Flow without reopening!
        waitUntilCondition {
            navVm.routeShops.value.any { it.name == "Bageri Undervejs" } &&
                navVm.routeWaters.value.any { it.name == "Vandpost Skovbrynet" }
        }
        assertTrue(navVm.routeShops.value.any { it.name == "Bageri Undervejs" })
        assertTrue(navVm.routeWaters.value.any { it.name == "Vandpost Skovbrynet" })
        job2.cancel()
    }

    // =========================================================================
    // Item 5: Deleting an offline map detaches its archive from every open map's tile provider before deleting file
    // =========================================================================
    @Test
    fun item5_deletingOfflineMapDetachesArchiveFromOpenMapsBeforeDeletingFile() = runBlocking {
        val trailId = "offline_detach_test_10c"
        val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(app, trailId)
        archiveFile.parentFile?.mkdirs()

        // Create a valid SQLite tile archive file so osmdroid / FallbackSkaermkortArchiveFile opens it
        val sqliteDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(archiveFile, null)
        sqliteDb.execSQL("CREATE TABLE IF NOT EXISTS tiles (key INTEGER PRIMARY KEY, provider TEXT, tile BLOB)")
        sqliteDb.close()
        assertTrue(archiveFile.exists())

        // Create an open MapView and apply base map so the archive is attached to its tileProvider
        val mapView = MapView(app)
        DanskStiTileSourceHelper.applyTo(mapView)

        assertTrue(
            "Archive should initially be attached to the open map's tile provider",
            DanskStiTileSourceHelper.isArchiveAttachedToMap(mapView, archiveFile)
        )

        // Delete the offline trail via OfflineDownloadManager
        OfflineDownloadManager.deleteOfflineTrail(app, trailId)

        assertFalse(
            "Archive must be detached from the open map's tile provider when deleted",
            DanskStiTileSourceHelper.isArchiveAttachedToMap(mapView, archiveFile)
        )
        assertFalse(
            "Archive file must be deleted from disk after detaching",
            archiveFile.exists()
        )
    }

    // =========================================================================
    // Item 6: StagePlanner unified function tested across all three callers (trails, custom routes, suggestions)
    // =========================================================================
    @Test
    fun item6_stagePlannerSinglePlanningFunctionWorksForTrailsCustomRoutesAndSuggestions() = runBlocking {
        val pts = (0..20).map { i ->
            GeoPointDto(55.30 + i * 0.01, 10.20 + i * 0.01, (10 + i).toDouble())
        }
        val trail = TrailEntity(
            id = "trail_stage_unified",
            name = "Fælles Etape Rute",
            region = "Fyn",
            computedWalkingKm = 24.0,
            description = "Test",
            isGeometryDownloaded = true
        )
        val segments = listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = pts, lengthKm = 24.0))
        val plannedRouteRepo = PlannedRouteRepository(app)
        val basePlannedRoute = plannedRouteRepo.buildPlannedRoute(
            planId = 1L,
            trail = trail,
            segments = segments,
            startKm = 0.0,
            endKm = 24.0,
            isReverse = false,
            stages = emptyList()
        )
        val shelters = listOf(
            ShelterEntity(
                id = "sh_mid",
                name = "Midtvejs Shelter",
                lat = pts[10].lat,
                lng = pts[10].lng,
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                bookingRequired = false
            )
        )

        // Caller 1: Trails (StagePlanner.planStages without explicit overnight waypoints)
        val trailStages = StagePlanner.planStages(
            plannedRoute = basePlannedRoute,
            trail = trail,
            routeName = trail.name,
            paceKmh = 4.0,
            dailyTargetKm = 12.0,
            numDays = 2,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 2.0,
            startTime = "09:00",
            shelters = shelters,
            friTeltnings = emptyList(),
            pois = emptyList(),
            startDateEpochMs = 1781913600000L // June 2026
        )
        assertEquals(2, trailStages.size)
        assertEquals("Midtvejs Shelter", trailStages[0].stopName)
        assertTrue(trailStages[0].sunsetTime.isNotBlank())

        // Caller 2: Custom routes (StagePlanner.planStages with explicit OVERNIGHT waypoints)
        val customWaypoints = listOf(
            RouteWaypoint(id = "w0", lat = pts.first().lat, lng = pts.first().lng, name = "Start", role = WaypointRole.START),
            RouteWaypoint(id = "w1", lat = pts[10].lat, lng = pts[10].lng, name = "Overnatning Dag 1", role = WaypointRole.OVERNIGHT),
            RouteWaypoint(id = "w2", lat = pts.last().lat, lng = pts.last().lng, name = "Slut", role = WaypointRole.END)
        )
        val customStages = StagePlanner.planStages(
            plannedRoute = basePlannedRoute,
            trail = null,
            routeName = "Custom Rute",
            waypoints = customWaypoints,
            paceKmh = 4.0,
            dailyTargetKm = 12.0,
            numDays = 2,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 2.0,
            startTime = "09:00",
            shelters = shelters,
            friTeltnings = emptyList(),
            pois = emptyList()
        )
        assertEquals(2, customStages.size)
        assertEquals("Overnatning Dag 1", customStages[0].stopName)

        // Caller 3: Overnight suggestions & single-stage recomputation via StagePlanner.planStages
        val rbVm = RouteBuilderViewModel(app)
        assertNotNull(rbVm)
        val recomputed = StagePlanner.planStages(
            plannedRoute = basePlannedRoute,
            trail = trail,
            routeName = trail.name,
            paceKmh = 4.0,
            dailyTargetKm = 12.0,
            numDays = 2,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 2.0,
            startTime = "09:00",
            shelters = shelters,
            friTeltnings = emptyList(),
            pois = emptyList(),
            existingStages = trailStages,
            changedStageIndex = 0,
            changedStageEndKm = 10.0
        )
        assertEquals(2, recomputed.size)
        assertEquals(10.0, recomputed[0].endKm, 0.01)
        assertEquals(10.0, recomputed[1].startKm, 0.01)
    }

    // =========================================================================
    // Item 7: Routing data HEAD requests ("Opdatering tilgængelig") & version mismatch ("Rutedata skal opdateres" + online fallback)
    // =========================================================================
    @Test
    fun item7_routingDataReadsHeadSizesAndLastModifiedAndFallsBackOnlineOnVersionMismatch() = runBlocking {
        val storageManager = BRouterStorageManager.getInstance(app)
        storageManager.clearRemoteMetadataCacheForTests()

        // Create local downloaded segments with an older timestamp (e.g., 10 days ago)
        val tenDaysAgo = System.currentTimeMillis() - 10L * 86_400_000L
        for (seg in BRouterSegments.DENMARK_SEGMENTS) {
            val f = File(storageManager.segmentsDir, seg.filename)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(2048) { 1 })
            f.setLastModified(tenDaysAgo)
        }
        storageManager.setLastUpdatedTimestamp(tenDaysAgo)

        // Mock HEAD responses returning real Content-Length and newer Last-Modified (today)
        val newerRemoteTime = System.currentTimeMillis()
        BRouterStorageManager.customHeadFetcher = { filename ->
            RemoteSegmentHeader(
                filename = filename,
                contentLength = 50L * 1024L * 1024L, // 50 MB per segment = 250 MB total
                lastModifiedMillis = newerRemoteTime
            )
        }

        val realSegments = storageManager.fetchRemoteSegmentsMetadata()
        assertEquals(5, realSegments.size)
        assertEquals(50, realSegments.first().sizeMb)
        assertEquals(250, storageManager.getTotalExpectedSizeMb())
        assertTrue("Server files are newer than local files, so isUpdateAvailable() must be true", storageManager.isUpdateAvailable())

        val offlineVm = OfflineViewModel(app)
        offlineVm.refreshBRouterState()
        waitUntilCondition { offlineVm.brouterDataState.value.isUpdateAvailable }
        assertEquals("Opdatering tilgængelig", offlineVm.brouterDataState.value.statusNotice)

        // Part 2: Simulate offline engine data/lookup version mismatch -> "Rutedata skal opdateres" and fallback to online routing
        BRouterOfflineEngine.simulateVersionMismatchForTests = true
        val fakeOnlineEngine = object : RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: RouteOptions,
                alternative: Int
            ): RouteLegResult {
                return RouteLegResult.Success(
                    points = listOf(RoutePoint3D(from.lat, from.lng, 5.0), RoutePoint3D(to.lat, to.lng, 10.0)),
                    lengthKm = 4.2,
                    ascentM = 12.0,
                    descentM = 8.0,
                    perPointWayInfo = emptyList(),
                    sourceName = "brouter.de (online)"
                )
            }
        }
        val hybrid = HybridRoutingEngine(
            context = app,
            onlineEngine = fakeOnlineEngine,
            offlineEngine = BRouterOfflineEngine(app, db.dao(), storageManager),
            storageManager = storageManager
        )

        val result = hybrid.routeLeg(
            from = LatLng(55.39, 10.38),
            to = LatLng(55.41, 10.40),
            options = RouteOptions()
        )
        assertTrue("HybridRoutingEngine must fall back to online routing on version mismatch", result is RouteLegResult.Success)
        val success = result as RouteLegResult.Success
        assertEquals("Rutedata skal opdateres", success.warningMessage)
        assertTrue("BRouterStorageManager must flag version mismatch", storageManager.hasVersionMismatch())

        offlineVm.refreshBRouterState()
        waitUntilCondition { offlineVm.brouterDataState.value.hasVersionMismatch }
        assertEquals("Rutedata skal opdateres", offlineVm.brouterDataState.value.statusNotice)
    }

    // =========================================================================
    // Item 8: "Start nu" uses fresh GPS fix (within 15 s) or starts from trail start and says so
    // =========================================================================
    @Test
    fun item8_startNuUsesFreshGpsFixOrFallsBackToTrailStartAfterTimeoutWithDanishMessage() = runBlocking {
        val trailId = "trail_10c_start_nu"
        val pts = listOf(
            GeoPointDto(55.000, 10.000, 5.0),
            GeoPointDto(55.050, 10.000, 10.0),
            GeoPointDto(55.100, 10.000, 15.0)
        )
        val trail = TrailEntity(
            id = trailId,
            name = "Start Nu Teststi",
            region = "Fyn",
            computedWalkingKm = 11.1,
            description = "Test",
            isGeometryDownloaded = true
        )
        db.dao().insertTrails(listOf(trail))
        app.repository.geometryRepository.saveGeometry(
            TrailGeometryDto(
                trailId = trailId,
                osmRelationId = 999L,
                fetchedAt = System.currentTimeMillis(),
                totalWalkingKm = 11.1,
                totalAscentM = 10,
                totalDescentM = 5,
                gapsOver200mCount = 0,
                bboxNorth = 55.100,
                bboxSouth = 55.000,
                bboxEast = 10.000,
                bboxWest = 10.000,
                segments = listOf(
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts,
                        lengthKm = 11.1,
                        wayTagRanges = listOf(com.example.data.model.WayTagRangeDto(0.0, 11.1, "path", "dirt"))
                    )
                )
            )
        )

        val vm = TrailViewModel(app)
        vm.loadTrail(trailId)
        waitUntilCondition { vm.trail.value?.id == trailId && vm.trailPoints.value.isNotEmpty() }

        // Case A: Fresh GPS fix arrives within timeout near the midpoint (55.050, 10.000 -> ~5.5 km)
        vm.startNowFreshLocationTimeoutMs = 1_000L
        vm.customFreshLocationFetcher = {
            Location("fused").apply {
                latitude = 55.050
                longitude = 10.000
                time = System.currentTimeMillis()
            }
        }
        var createdPlanA: Long? = null
        vm.startTrailNow(onPlanCreated = { createdPlanA = it })
        waitUntilCondition { createdPlanA != null }
        val planA = db.dao().getTripPlanById(createdPlanA!!)!!
        assertTrue("Fresh GPS fix near midpoint must start plan near midpoint (got ${planA.startKm} km)", planA.startKm > 4.0)
        assertNull("No fallback message should be shown when fresh GPS fix succeeds", vm.startNowStatusMessage.value)

        // Case B: No GPS fix within timeout -> starts from trail start (0.0 km) and says so
        vm.startNowFreshLocationTimeoutMs = 150L
        vm.customFreshLocationFetcher = {
            delay(500L) // Exceeds timeout
            null
        }
        var createdPlanB: Long? = null
        vm.startTrailNow(onPlanCreated = { createdPlanB = it })
        waitUntilCondition { createdPlanB != null }
        val planB = db.dao().getTripPlanById(createdPlanB!!)!!
        assertEquals("When no GPS fix arrives within timeout, plan must start from trail start (0.0 km)", 0.0, planB.startKm, 0.01)
        assertNotNull("Fallback message must be set when GPS times out", vm.startNowStatusMessage.value)
        assertTrue(
            "Fallback message must mention starting from trail start",
            vm.startNowStatusMessage.value!!.contains("starter fra rutens start")
        )
    }

    // =========================================================================
    // Item 9: User-facing texts in ViewModels and services moved to strings.xml
    // =========================================================================
    @Test
    fun item9_viewModelAndServiceTextsAreInStringsXml() {
        assertEquals("Eksporter turplan som GPX", app.getString(R.string.share_trip_plan_gpx_title))
        assertEquals("Ingen app fundet til at dele filen", app.getString(R.string.toast_no_app_to_share_file))
        assertEquals("Dansk Sti — Aktiv Vandring", app.getString(R.string.notif_nav_channel_name))
        assertEquals("Pause", app.getString(R.string.notif_nav_action_pause))
        assertEquals("Genoptag", app.getString(R.string.notif_nav_action_resume))
        assertEquals("Stop", app.getString(R.string.notif_nav_action_stop))
        assertEquals("Opdatering tilgængelig", app.getString(R.string.brouter_update_available))
        assertEquals("Rutedata skal opdateres", app.getString(R.string.brouter_version_mismatch_update_needed))
    }
}
