package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannerDraftEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripPlanEntity
import com.example.data.remote.OverpassClient
import com.example.data.repository.TrailGeometryRepository
import com.example.service.OfflineDownloadWorker
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.addPointAtEnd
import com.example.ui.viewmodel.computePlannerDraftKey
import com.example.ui.viewmodel.continueDraft
import com.example.ui.viewmodel.deleteWaypoint
import com.example.ui.viewmodel.routeToPlace
import com.example.ui.viewmodel.saveDraftToRoomNow
import com.example.util.DanskStiTileSource
import com.example.util.GpxExporter
import com.example.util.OfflineDownloadManager
import com.example.util.routing.LegMode
import com.example.util.routing.RouteWaypoint
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound16Test {

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = { coords -> coords.map { 15.0 } }
        OfflineDownloadWorker.customTileFetcher = null
        OfflineDownloadWorker.shouldInterruptAfterTileCount = null
        OfflineDownloadManager.freeBytesProviderOverride = { 10L * 1024L * 1024L * 1024L }
        OfflineDownloadManager.networkStatusProviderOverride = { true to true }
        runBlocking {
            app.database.danskStiDao().clearPlannerDraft()
        }
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
        OfflineDownloadWorker.customTileFetcher = null
        OfflineDownloadWorker.shouldInterruptAfterTileCount = null
        OfflineDownloadManager.freeBytesProviderOverride = null
        OfflineDownloadManager.networkStatusProviderOverride = null
        runBlocking {
            app.database.danskStiDao().clearPlannerDraft()
        }
    }

    // =========================================================================
    // ITEM 3: Planner drafts keyed per context, never offered for "Rute hertil"
    // or specific saved plan, TRAIL draft loads geometry before restoring
    // waypoints, and deleting all points removes the draft.
    // =========================================================================

    @Test
    fun item3_draftFromTrailA_isNotOfferedInTrailB_norInRuteHertil_norInSpecificSavedPlan(): Unit = runBlocking {
        val dao = app.database.danskStiDao()
        val trailA = "trail_ohavsstien"
        val trailB = "trail_gendarmstien"

        assertEquals("trail_trail_ohavsstien", computePlannerDraftKey(null, trailA))
        assertEquals("trail_trail_gendarmstien", computePlannerDraftKey(null, trailB))
        assertEquals("42", computePlannerDraftKey(42L, trailA))
        assertEquals("free_new", computePlannerDraftKey(null, null))

        // Save a draft belonging to Trail A
        val draftWaypoints = listOf(
            RouteWaypoint(
                id = "wp_start",
                lat = 55.0590,
                lng = 10.6068,
                role = WaypointRole.START,
                legModeToNext = LegMode.TRAIL,
                name = "Svendborg",
                trailKm = 0.0
            ),
            RouteWaypoint(
                id = "wp_end",
                lat = 55.0950,
                lng = 10.2415,
                role = WaypointRole.END,
                legModeToNext = LegMode.TRAIL,
                name = "Faaborg",
                trailKm = 25.0
            )
        )
        dao.upsertPlannerDraft(
            PlannerDraftEntity(
                id = computePlannerDraftKey(null, trailA),
                routeId = null,
                baseTrailId = trailA,
                routeName = "Øhavsstien Kladde",
                mode = PlannerMode.TRAIL.name,
                waypointsJson = RouteWaypoint.listToJson(draftWaypoints),
                optionsJson = "{}",
                isReverse = false,
                updatedAt = System.currentTimeMillis()
            )
        )

        // 1. Open Trail B's planner -> Draft from Trail A MUST NOT be offered!
        val vmTrailB = RouteBuilderViewModel(app)
        vmTrailB.initialize(baseTrailId = trailB)
        assertNull(
            "A draft from trail A must NOT be offered in trail B's planner",
            vmTrailB.uiState.value.pendingDraftToRestore
        )

        // 2. Open free new route planner -> Draft from Trail A MUST NOT be offered!
        val vmFree = RouteBuilderViewModel(app)
        vmFree.initialize()
        assertNull(
            "A draft from trail A must NOT be offered in a new free route planner",
            vmFree.uiState.value.pendingDraftToRestore
        )

        // 3. Open Trail A's planner -> Draft from Trail A MUST be offered!
        val vmTrailA = RouteBuilderViewModel(app)
        vmTrailA.initialize(baseTrailId = trailA)
        assertNotNull(
            "Draft from trail A must be offered when opening trail A's planner",
            vmTrailA.uiState.value.pendingDraftToRestore
        )
        assertEquals("trail_trail_ohavsstien", vmTrailA.uiState.value.pendingDraftToRestore?.id)

        // 4. Also save a free_new draft and verify "Rute hertil" and specific saved plan NEVER offer a draft
        dao.upsertPlannerDraft(
            PlannerDraftEntity(
                id = "free_new",
                routeId = null,
                baseTrailId = null,
                routeName = "Fri Kladde",
                mode = PlannerMode.FREE.name,
                waypointsJson = RouteWaypoint.listToJson(draftWaypoints),
                optionsJson = "{}",
                isReverse = false,
                updatedAt = System.currentTimeMillis()
            )
        )

        val vmRuteHertil = RouteBuilderViewModel(app)
        vmRuteHertil.initialize(destLat = 55.1, destLng = 10.2, destName = "Shelter")
        assertNull(
            "Never offer a draft when opened via Rute hertil (destLat/destLng)",
            vmRuteHertil.uiState.value.pendingDraftToRestore
        )

        val vmRouteToPlace = RouteBuilderViewModel(app)
        vmRouteToPlace.routeToPlace(destLat = 55.1, destLng = 10.2, destName = "Shelter", currentLat = 55.0, currentLng = 10.0)
        assertNull(
            "Never offer a draft when routeToPlace is invoked",
            vmRouteToPlace.uiState.value.pendingDraftToRestore
        )

        val savedPlanId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = trailA,
                trailName = "Gemt Øhavsstien Plan",
                startKm = 0.0,
                endKm = 20.0,
                isReverse = false,
                totalDistanceKm = 20.0,
                targetDays = 1,
                overnightType = "SHELTER",
                preferredDailyKm = 20.0,
                stagesJson = "[]"
            )
        )
        val vmSavedPlan = RouteBuilderViewModel(app)
        vmSavedPlan.initialize(baseTrailId = trailA, existingPlanId = savedPlanId)
        assertNull(
            "Never offer a draft when opened via a specific saved plan",
            vmSavedPlan.uiState.value.pendingDraftToRestore
        )
    }

    @Test
    fun item3_continueTrailDraft_loadsTrailGeometryBeforeRestoringWaypoints_andShowsCorrectTrailSections(): Unit = runBlocking {
        val dao = app.database.danskStiDao()
        val geomRepo = app.repository.geometryRepository
        val trailId = "trail_draft_r16_${System.currentTimeMillis()}"

        dao.insertTrail(
            TrailEntity(
                id = trailId,
                name = "Round 16 Sti",
                description = "Teststi",
                region = "Fyn",
                parentRegion = "Fyn",
                osmRelationId = 161616L,
                computedWalkingKm = 20.0,
                isGeometryDownloaded = false
            )
        )
        geomRepo.getGeometryFile(trailId).delete()

        // Mock Overpass to return a ~10 km trail from (55.00, 10.00) to (55.09, 10.00)
        OverpassClient.customTransport = { _, _ ->
            OverpassClient.HttpResult(
                200,
                """
                {
                  "elements": [
                    {
                      "type": "relation",
                      "id": 161616,
                      "tags": {"name": "Round 16 Sti", "route": "hiking"},
                      "members": [
                        {
                          "type": "way",
                          "ref": 10,
                          "role": "",
                          "geometry": [
                            {"lat": 55.000, "lon": 10.000},
                            {"lat": 55.030, "lon": 10.000},
                            {"lat": 55.060, "lon": 10.000},
                            {"lat": 55.090, "lon": 10.000}
                          ]
                        }
                      ]
                    },
                    {
                      "type": "way",
                      "id": 10,
                      "tags": {"highway": "path", "surface": "gravel"}
                    }
                  ]
                }
                """.trimIndent()
            )
        }

        // Save a 2-section TRAIL draft (Start at 0 km -> Overnight at 4 km -> End at 9 km)
        val draftWps = listOf(
            RouteWaypoint(
                id = "draft_start",
                lat = 55.000,
                lng = 10.000,
                role = WaypointRole.START,
                legModeToNext = LegMode.TRAIL,
                name = "Start",
                trailKm = 0.0
            ),
            RouteWaypoint(
                id = "draft_camp",
                lat = 55.036,
                lng = 10.000,
                role = WaypointRole.OVERNIGHT,
                legModeToNext = LegMode.TRAIL,
                name = "Midtvejs Shelter",
                trailKm = 4.0
            ),
            RouteWaypoint(
                id = "draft_end",
                lat = 55.081,
                lng = 10.000,
                role = WaypointRole.END,
                legModeToNext = LegMode.TRAIL,
                name = "Slut",
                trailKm = 9.0
            )
        )
        dao.upsertPlannerDraft(
            PlannerDraftEntity(
                id = computePlannerDraftKey(null, trailId),
                routeId = null,
                baseTrailId = trailId,
                routeName = "Round 16 Sti Kladde",
                mode = PlannerMode.TRAIL.name,
                waypointsJson = RouteWaypoint.listToJson(draftWps),
                optionsJson = "{}",
                isReverse = false,
                updatedAt = System.currentTimeMillis()
            )
        )

        val vm = RouteBuilderViewModel(app)
        vm.initialize(baseTrailId = trailId)
        assertNotNull("Draft for trail must be offered", vm.uiState.value.pendingDraftToRestore)

        // Tap "Fortsæt kladde" while geometry has to be downloaded
        vm.continueDraft()

        val deadline = System.currentTimeMillis() + 5_000L
        while ((vm.uiState.value.isDownloadingTrailGeometry || vm.uiState.value.legs.size < 2) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(30L)
        }

        val state = vm.uiState.value
        assertFalse("Trail geometry download must finish", state.isDownloadingTrailGeometry)
        assertTrue("Base trail segments must be loaded before restoring waypoints", state.baseTrailSegments.isNotEmpty())
        assertEquals("All 3 draft waypoints must be restored", 3, state.waypoints.size)
        assertEquals("Midtvejs Shelter", state.waypoints[1].name)
        assertEquals("Both trail sections (legs) must be built", 2, state.legs.size)
        assertTrue("First trail section must have points", state.legs["draft_start_to_draft_camp"]?.points?.isNotEmpty() == true)
        assertTrue("Second trail section must have points", state.legs["draft_camp_to_draft_end"]?.points?.isNotEmpty() == true)
        assertEquals("2 days/stages must be shown for the 2 trail sections", 2, state.plannedStages.size)

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item3_deletingAllPoints_removesTheDraftForThatKey(): Unit = runBlocking {
        val dao = app.database.danskStiDao()
        val vm = RouteBuilderViewModel(app)
        vm.initialize() // free_new context

        vm.addPointAtEnd(55.05, 10.60)
        vm.addPointAtEnd(55.06, 10.61)
        vm.saveDraftToRoomNow()

        assertEquals(2, vm.uiState.value.waypoints.size)
        assertNotNull("Draft must exist for free_new after adding points", dao.getPlannerDraftByKey("free_new"))

        // Delete first point -> 1 point left, draft still exists
        val firstId = vm.uiState.value.waypoints.first().id
        vm.deleteWaypoint(firstId)
        assertEquals(1, vm.uiState.value.waypoints.size)

        // Delete last remaining point -> 0 points left, draft for free_new MUST be deleted!
        val secondId = vm.uiState.value.waypoints.first().id
        vm.deleteWaypoint(secondId)
        assertTrue("Waypoints must now be empty", vm.uiState.value.waypoints.isEmpty())
        assertNull(
            "When all points are deleted, the draft for that key must be deleted from Room",
            dao.getPlannerDraftByKey("free_new")
        )
    }

    // =========================================================================
    // ITEM 4 & 5: OfflineDownloadWorker returns Result.retry() with exponential
    // backoff on network errors keeping partial archive, keeps Result.failure()
    // for real errors, and uses monochrome ic_notif_navigation.
    // =========================================================================

    @Test
    fun item4_and_item5_offlineDownloadWorker_retriesOnNetworkErrorsKeepingPartialArchive_failsOnRealErrors_andUsesMonochromeIcon(): Unit = runBlocking {
        val dao = app.database.danskStiDao()
        val geomRepo = app.repository.geometryRepository
        val trailId = "offline_retry_r16"

        // 1. Verify exponential backoff is configured on OfflineDownloadManager.buildWorkRequest
        val workReq = OfflineDownloadManager.buildWorkRequest(
            trailId = trailId,
            trailName = "Retry Rute",
            corridorKm = 0.5,
            wifiOnly = false
        )
        assertEquals(BackoffPolicy.EXPONENTIAL, workReq.workSpec.backoffPolicy)

        // 2. Real error: trail not found -> Result.failure()
        val missingRes = OfflineDownloadWorker.executeDownload(
            context = app,
            trailId = "non_existent_trail_r16",
            trailName = "Ukendt",
            corridorKm = 0.5,
            wifiOnly = false
        )
        assertTrue("Trail not found must return Result.failure()", missingRes is ListenableWorker.Result.Failure)

        // Seed a small valid trail with cached geometry so tiles can be calculated
        dao.insertTrail(
            TrailEntity(
                id = trailId,
                name = "Retry Rute",
                description = "Test",
                region = "Fyn",
                parentRegion = "Fyn",
                osmRelationId = 99916L,
                computedWalkingKm = 1.0,
                isGeometryDownloaded = true
            )
        )
        geomRepo.saveGeometry(
            TrailGeometryDto(
                trailId = trailId,
                osmRelationId = 99916L,
                osmRelationName = "Retry Rute",
                totalWalkingKm = 1.0,
                gapsOver200mCount = 0,
                bboxNorth = 55.01,
                bboxSouth = 55.00,
                bboxEast = 10.01,
                bboxWest = 10.00,
                segments = listOf(
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = listOf(
                            GeoPointDto(55.000, 10.000, 10.0),
                            GeoPointDto(55.005, 10.005, 12.0)
                        ),
                        lengthKm = 1.0
                    )
                )
            )
        )

        val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(app, trailId)
        archiveFile.delete()

        // 3. Real error: not enough storage -> Result.failure()
        OfflineDownloadManager.freeBytesProviderOverride = { 100L } // only 100 bytes free
        val noSpaceRes = OfflineDownloadWorker.executeDownload(
            context = app,
            trailId = trailId,
            trailName = "Retry Rute",
            corridorKm = 0.5,
            wifiOnly = false
        )
        assertTrue("Not enough storage must return Result.failure()", noSpaceRes is ListenableWorker.Result.Failure)
        OfflineDownloadManager.freeBytesProviderOverride = { 10L * 1024L * 1024L * 1024L }

        // 4. Network error (SocketTimeoutException / IOException) mid-download after saving 3 tiles:
        // Must return Result.retry(), keeping the partial SQLite archive on disk!
        val fetchedTileKeysAttempt1 = mutableSetOf<Long>()
        val fakeTileBytes = ByteArray(256) { 0x42 }
        OfflineDownloadWorker.customTileFetcher = { _, tileIdx ->
            if (fetchedTileKeysAttempt1.size >= 3) {
                throw SocketTimeoutException("Simulated timeout after 3 tiles")
            }
            fetchedTileKeysAttempt1.add(tileIdx)
            fakeTileBytes
        }

        val attempt1Result = OfflineDownloadWorker.executeDownload(
            context = app,
            trailId = trailId,
            trailName = "Retry Rute",
            corridorKm = 0.5,
            wifiOnly = false
        )
        assertEquals(
            "Network error / timeout during download must return Result.retry()",
            ListenableWorker.Result.retry(),
            attempt1Result
        )
        assertTrue("Partial archive file must be kept on disk after network failure", archiveFile.exists())
        val savedAfterAttempt1 = OfflineDownloadManager.getExistingTileKeysInArchive(
            archiveFile,
            DanskStiTileSource.instance.name()
        )
        assertEquals("Partial archive must contain the 3 tiles downloaded before timeout", 3, savedAfterAttempt1.size)

        // 5. Connection comes back -> second run resumes from partial archive (never re-fetching the first 3 tiles) and succeeds!
        val fetchedTileKeysAttempt2 = mutableSetOf<Long>()
        OfflineDownloadWorker.customTileFetcher = { _, tileIdx ->
            fetchedTileKeysAttempt2.add(tileIdx)
            fakeTileBytes
        }
        val attempt2Result = OfflineDownloadWorker.executeDownload(
            context = app,
            trailId = trailId,
            trailName = "Retry Rute",
            corridorKm = 0.5,
            wifiOnly = false
        )
        assertTrue("Resumed worker must succeed once connection returns", attempt2Result is ListenableWorker.Result.Success)
        assertTrue(
            "Resumed worker must skip tiles already in the partial archive",
            fetchedTileKeysAttempt1.intersect(fetchedTileKeysAttempt2).isEmpty()
        )
        assertNotNull("Completed trail must be recorded in downloaded_trails", dao.getDownloadedTrail(trailId))

        // 6. Item 5: Verify offline download notifications use monochrome ic_notif_navigation, not ic_launcher_foreground
        val offlineWorkerSrc = (File("src/main/java/com/example/service/OfflineDownloadWorker.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/service/OfflineDownloadWorker.kt")).readText()
        val brouterWorkerSrc = (File("src/main/java/com/example/service/BRouterSegmentDownloadWorker.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/service/BRouterSegmentDownloadWorker.kt")).readText()
        assertFalse("OfflineDownloadWorker must not use ic_launcher_foreground", offlineWorkerSrc.contains("ic_launcher_foreground"))
        assertTrue("OfflineDownloadWorker must use ic_notif_navigation", offlineWorkerSrc.contains("ic_notif_navigation"))
        assertFalse("BRouterSegmentDownloadWorker must not use ic_launcher_foreground", brouterWorkerSrc.contains("ic_launcher_foreground"))

        archiveFile.delete()
        geomRepo.getGeometryFile(trailId).delete()
    }

    // =========================================================================
    // ITEM 6: GPX export safe file-name helper
    // =========================================================================

    @Test
    fun item6_gpxExportSafeFileNameHelper_andExportingSvendborgSlashFaaborgWorks() {
        // 1. Replace / \ : * ? " < > | and control characters with "_"
        assertEquals(
            "Svendborg_Faaborg.gpx",
            GpxExporter.buildSafeGpxFileName("Svendborg/Faaborg")
        )
        assertEquals(
            "A_B_C_D_E_F_G_H_I_J_K.gpx",
            GpxExporter.buildSafeGpxFileName("A/B\\C:D*E?F\"G<H>I|J\nK")
        )

        // 2. Trim to 80 characters
        val longName = "X".repeat(120)
        val sanitizedBase = GpxExporter.sanitizeGpxBaseName(longName)
        assertEquals(80, sanitizedBase.length)
        assertEquals("${"X".repeat(80)}.gpx", GpxExporter.buildSafeGpxFileName(longName))

        // 3. Fall back to "rute" if empty
        assertEquals("rute.gpx", GpxExporter.buildSafeGpxFileName(""))
        assertEquals("rute.gpx", GpxExporter.buildSafeGpxFileName("   "))
        assertEquals("rute.gpx", GpxExporter.buildSafeGpxFileName("///:::***"))

        // 4. Exporting a route named "Svendborg/Faaborg" writes cache/exports/Svendborg_Faaborg.gpx and succeeds
        val exportDir = File(app.cacheDir, "exports")
        val expectedFile = File(exportDir, "Svendborg_Faaborg.gpx")
        expectedFile.delete()

        val gpxXml = GpxExporter.generateCustomRouteGpx(
            routeName = "Svendborg/Faaborg",
            description = "Test",
            routePoints = listOf(
                GeoPointDto(55.059, 10.606, 10.0),
                GeoPointDto(55.095, 10.241, 15.0)
            )
        )
        GpxExporter.exportAndShareGpxWithSummary(
            context = app,
            fileName = GpxExporter.buildSafeGpxFileName("Svendborg/Faaborg"),
            gpxContent = gpxXml,
            shareTitle = "Del rute: Svendborg/Faaborg",
            textSummary = "Svendborg/Faaborg"
        )
        assertTrue(
            "Exporting 'Svendborg/Faaborg' must create cache/exports/Svendborg_Faaborg.gpx",
            expectedFile.exists() && expectedFile.length() > 100L
        )
        expectedFile.delete()
    }
}
