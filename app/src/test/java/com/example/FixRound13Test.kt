package com.example

import android.content.Intent
import android.location.Location
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DanskStiDatabase
import com.example.data.model.CustomRouteEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.remote.OverpassClient
import com.example.data.repository.CacheCategory
import com.example.data.repository.OsmPoiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.service.NavigationTrackingService
import com.example.ui.map.FreshLocationResolution
import com.example.ui.screens.TripPlanLibraryCard
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.SavedViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.ui.viewmodel.openNearestPoiDialog
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.robolectric.annotation.Config
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound13Test {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DanskStiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before
    fun setUp() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = { coords -> coords.map { 12.0 } }
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
    }

    // =========================================================================
    // ITEM 1: Database v27, MIGRATION_25_26 (planner_drafts only), MIGRATION_26_27,
    // schemas/27.json, MigrationTestHelper 25 -> 26 -> 27 & 26 -> 27, and v26 upgrade test
    // =========================================================================

    @Test
    fun item1_schemas26And27_andMigrations25To26To27_and26To27() {
        val schema26File = File("schemas/com.example.data.local.DanskStiDatabase/26.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/com.example.data.local.DanskStiDatabase/26.json")
        val schema27File = File("schemas/com.example.data.local.DanskStiDatabase/27.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/com.example.data.local.DanskStiDatabase/27.json")

        assertTrue("26.json must exist", schema26File.exists())
        assertTrue("27.json must exist", schema27File.exists())

        val schema26Json = schema26File.readText()
        val schema27Json = schema27File.readText()

        assertTrue("26.json must be version 26", schema26Json.contains("\"version\": 26"))
        assertTrue("26.json must contain planner_drafts", schema26Json.contains("\"tableName\": \"planner_drafts\""))
        assertFalse(
            "26.json must NOT contain offline_downloads_in_progress (belongs to v27)",
            schema26Json.contains("\"tableName\": \"offline_downloads_in_progress\"")
        )

        assertTrue("27.json must be version 27", schema27Json.contains("\"version\": 27"))
        assertTrue("27.json must contain planner_drafts", schema27Json.contains("\"tableName\": \"planner_drafts\""))
        assertTrue(
            "27.json must contain offline_downloads_in_progress",
            schema27Json.contains("\"tableName\": \"offline_downloads_in_progress\"")
        )

        // 1) MigrationTestHelper: 25 -> 26 -> 27
        val dbName25 = "round13_mig_25_26_27_${System.currentTimeMillis()}.db"
        migrationHelper.createDatabase(dbName25, 25).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'gendarmstien', 'Gendarmstien', 'Kyststi', 'Sønderjylland', 'Jylland',
                    99999, 84.0, 0, NULL,
                    55.0, 54.8, 10.0, 9.4,
                    1, 1700000000000, 'OSM wiki Denmark Walking Routes'
                )
                """.trimIndent()
            )
            close()
        }

        val db26 = migrationHelper.runMigrationsAndValidate(
            dbName25,
            26,
            true,
            DanskStiDatabase.MIGRATION_25_26
        )
        // At version 26, planner_drafts exists, offline_downloads_in_progress does NOT exist
        db26.execSQL(
            """
            INSERT INTO `planner_drafts` (
                `id`, `routeId`, `baseTrailId`, `routeName`, `mode`,
                `waypointsJson`, `optionsJson`, `isReverse`, `updatedAt`
            ) VALUES (
                'active_draft', NULL, 'gendarmstien', 'Min kladde', 'TRAIL',
                '[]', '{}', 0, 1700000005000
            )
            """.trimIndent()
        )
        db26.close()

        val db27From25 = migrationHelper.runMigrationsAndValidate(
            dbName25,
            27,
            true,
            DanskStiDatabase.MIGRATION_25_26,
            DanskStiDatabase.MIGRATION_26_27
        )
        db27From25.query("SELECT id, name FROM trails WHERE id = 'gendarmstien'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Gendarmstien", c.getString(1))
        }
        db27From25.query("SELECT routeName FROM planner_drafts WHERE id = 'active_draft'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Min kladde", c.getString(0))
        }
        db27From25.execSQL(
            "INSERT INTO `offline_downloads_in_progress` (`trailId`, `startedAt`) VALUES ('gendarmstien', 1700000010000)"
        )
        db27From25.query("SELECT trailId, startedAt FROM offline_downloads_in_progress WHERE trailId = 'gendarmstien'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("gendarmstien", c.getString(0))
            assertEquals(1700000010000L, c.getLong(1))
        }
        db27From25.close()

        // 2) MigrationTestHelper: 26 -> 27 directly
        val dbName26 = "round13_mig_26_27_${System.currentTimeMillis()}.db"
        migrationHelper.createDatabase(dbName26, 26).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'haervejen', 'Hærvejen', 'Historisk rute', 'Midtjylland', 'Jylland',
                    88888, 280.0, 0, NULL,
                    57.5, 54.8, 9.6, 9.1,
                    1, 1700000000000, 'OSM wiki Denmark Walking Routes'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO `planner_drafts` (
                    `id`, `routeId`, `baseTrailId`, `routeName`, `mode`,
                    `waypointsJson`, `optionsJson`, `isReverse`, `updatedAt`
                ) VALUES (
                    'active_draft', NULL, 'haervejen', 'Hærvejskladde', 'TRAIL',
                    '[]', '{}', 0, 1700000006000
                )
                """.trimIndent()
            )
            close()
        }

        val db27From26 = migrationHelper.runMigrationsAndValidate(
            dbName26,
            27,
            true,
            DanskStiDatabase.MIGRATION_26_27
        )
        db27From26.query("SELECT name, computedWalkingKm FROM trails WHERE id = 'haervejen'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Hærvejen", c.getString(0))
            assertEquals(280.0, c.getDouble(1), 1e-4)
        }
        db27From26.query("SELECT routeName FROM planner_drafts WHERE id = 'active_draft'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Hærvejskladde", c.getString(0))
        }
        db27From26.execSQL(
            "INSERT INTO `offline_downloads_in_progress` (`trailId`, `startedAt`) VALUES ('haervejen', 1700000020000)"
        )
        db27From26.query("SELECT trailId FROM offline_downloads_in_progress WHERE trailId = 'haervejen'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("haervejen", c.getString(0))
        }
        db27From26.close()
    }

    @Test
    fun item1_installPreviousReleaseV26_createData_updateToV27_appOpensAndKeepsData(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val upgradeDbName = "upgrade_prev_release_v26_${System.currentTimeMillis()}.db"

        // Step 1: Simulate the previous release's build (v26) creating the database and saving user data
        migrationHelper.createDatabase(upgradeDbName, 26).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'camino_fyn', 'Øhavsstien', 'Sydfynske Øhav', 'Fyn', 'Fyn & Øerne',
                    357566, 220.0, 0, NULL,
                    55.2, 54.7, 10.9, 9.9,
                    1, 1700000000000, 'OSM wiki Denmark Walking Routes'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO `trip_plans` (
                    `id`, `trailId`, `trailName`, `startKm`, `endKm`, `isReverse`,
                    `totalDistanceKm`, `targetDays`, `overnightType`, `preferredDailyKm`,
                    `stagesJson`, `createdAt`, `paceKmh`, `maxDetourKm`, `startTime`, `customRouteId`
                ) VALUES (
                    77, 'camino_fyn', 'Sommer på Øhavsstien', 0.0, 60.0, 0,
                    60.0, 3, 'SHELTER', 20.0,
                    '[]', 1700000050000, 4.0, 1.5, '09:00', NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO `planner_drafts` (
                    `id`, `routeId`, `baseTrailId`, `routeName`, `mode`,
                    `waypointsJson`, `optionsJson`, `isReverse`, `updatedAt`
                ) VALUES (
                    'active_planner_draft', NULL, 'camino_fyn', 'Gemt kladde fra v26', 'TRAIL',
                    '[]', '{}', 0, 1700000060000
                )
                """.trimIndent()
            )
            close()
        }

        // Step 2: Open with this release's v27 Room database builder + migrations
        val upgradedDb = Room.databaseBuilder(app, DanskStiDatabase::class.java, upgradeDbName)
            .addMigrations(
                DanskStiDatabase.MIGRATION_24_25,
                DanskStiDatabase.MIGRATION_25_26,
                DanskStiDatabase.MIGRATION_26_27
            )
            .build()

        try {
            val dao = upgradedDb.dao()
            val trail = dao.getTrailById("camino_fyn")
            assertNotNull("Trail from v26 must still exist after updating to v27", trail)
            assertEquals("Øhavsstien", trail?.name)

            val plans = dao.getAllTripPlansList()
            assertEquals("Trip plan from v26 must be preserved", 1, plans.size)
            assertEquals("Sommer på Øhavsstien", plans.first().trailName)

            val draft = dao.getPlannerDraft()
            assertNotNull("Planner draft from v26 must be preserved", draft)
            assertEquals("Gemt kladde fra v26", draft?.routeName)

            // Verify new v27 table works seamlessly
            dao.upsertOfflineDownloadInProgress(
                com.example.data.model.OfflineDownloadInProgressEntity("camino_fyn", 1700000090000L)
            )
            val inProg = dao.getOfflineDownloadInProgress("camino_fyn")
            assertNotNull(inProg)
            assertEquals("camino_fyn", inProg?.trailId)
        } finally {
            upgradedDb.close()
            app.deleteDatabase(upgradeDbName)
        }
    }

    // =========================================================================
    // ITEM 2: Trail download stuck at 5 %
    // 2a. De-duplicate (one download per trail at a time; progress cleared on last waiter)
    // 2b. Real progress ("Venter på OpenStreetMap (n foran i køen)", "Henter rute fra OpenStreetMap…")
    // 2c. Timeouts ([timeout:90] / 105 s, [timeout:120] / 135 s, no retry on same server on read timeout)
    // 2d. Priority (USER_INITIATED ahead of BACKGROUND in OverpassClient queue)
    // 2e. Superroutes (relation(r); out tags bb; skip outside Denmark; single way(r); out tags; request)
    // 2f. "Annuller" button & error within 60 s when no server answers
    // 2g. Tests for single-section trail moving past 5 %, all mirrors failing < 60 s, and deduplication
    // =========================================================================

    private fun buildSingleSectionOverpassGeomJson(relationId: Long, name: String = "Kyststien"): String {
        return """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": $relationId,
                  "tags": { "name": "$name", "type": "route", "route": "hiking" },
                  "members": [
                    {
                      "type": "way",
                      "ref": 5001,
                      "role": "",
                      "geometry": [
                        { "lat": 55.6761, "lon": 12.5683 },
                        { "lat": 55.6800, "lon": 12.5750 },
                        { "lat": 55.6850, "lon": 12.5820 }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun item2a_2g_deduplicateTrailDownload_openingTrailDuringOfflineDownloadStartsOnlyOneDownload_andClearsProgressOnLastWaiter(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val repo = app.repository
        val geomRepo = repo.geometryRepository

        val trailId = "dedup_trail_${System.currentTimeMillis()}"
        val trail = TrailEntity(
            id = trailId,
            name = "Dedup Teststi",
            description = "",
            region = "Sjælland",
            parentRegion = "Sjælland",
            osmRelationId = 445566L,
            isGeometryDownloaded = false
        )
        app.database.dao().insertTrail(trail)
        geomRepo.getGeometryFile(trailId).delete()

        val networkGate = CompletableDeferred<Unit>()
        val startedGate = CompletableDeferred<Unit>()
        val transportRelationGeomCalls = AtomicInteger(0)

        OverpassClient.customTransport = { _, query ->
            if (query.contains("out geom")) {
                transportRelationGeomCalls.incrementAndGet()
                startedGate.complete(Unit)
                networkGate.await()
                OverpassClient.HttpResult(200, buildSingleSectionOverpassGeomJson(445566L, "Dedup Teststi"))
            } else {
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        val waiter1SlowCallbackGate = CompletableDeferred<Unit>()
        val waiter2EnteredCallback = CompletableDeferred<Unit>()

        // Waiter 1: Offline download (getTrailPoints)
        val offlineDownloadDeferred = async(Dispatchers.IO) {
            repo.getTrailPoints(trail)
        }

        startedGate.await()
        assertNotNull(
            "Global progress must be active while download is in flight",
            TrailGeometryRepository.globalDownloadProgress.value[trailId]
        )

        // Waiter 2: User opens trail while its offline download is running
        // Use an onProgress callback that pauses at 1.0f ("Færdig") so we can verify progress stays until last waiter finishes
        val openTrailDeferred = async(Dispatchers.IO) {
            geomRepo.fetchAndProcessTrail(
                trailId = trailId,
                trailName = trail.name,
                osmRelationId = trail.osmRelationId,
                onProgress = { prog ->
                    if (prog.fraction >= 1.0f) {
                        waiter2EnteredCallback.complete(Unit)
                        runBlocking { waiter1SlowCallbackGate.await() }
                    }
                }
            )
        }

        // Wait until Waiter 2 has joined the in-flight download before releasing Overpass response
        val joinDeadline = System.currentTimeMillis() + 2_000L
        while (TrailGeometryRepository.getActiveWaiterCount(trailId) < 2 && System.currentTimeMillis() < joinDeadline) {
            delay(15L)
        }

        // Release Overpass response
        networkGate.complete(Unit)
        waiter2EnteredCallback.await()

        // Only 1 download was started!
        assertEquals(
            "Opening a trail while its offline download runs must start only ONE download",
            1,
            TrailGeometryRepository.getDownloadStartCount(trailId)
        )
        assertEquals(
            "Overpass out geom must only be requested once for the deduplicated trail",
            1,
            transportRelationGeomCalls.get()
        )

        waiter1SlowCallbackGate.complete(Unit)

        val segs1 = offlineDownloadDeferred.await()
        val dto2 = openTrailDeferred.await()

        assertTrue("Waiter 1 (offline download) must receive the trail segments", segs1.isNotEmpty())
        assertNotNull("Waiter 2 (opened trail) must receive the same trail geometry", dto2)
        assertNull(
            "Progress must be cleared once the last waiter finishes",
            TrailGeometryRepository.globalDownloadProgress.value[trailId]
        )

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item2b_2g_realProgress_showsQueueAndIndeterminateRunning_neverSitsAt5Percent_andMovesPast5PercentWithinSeconds(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "progress_trail_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val bgSlot1Gate = CompletableDeferred<Unit>()
        val bgSlot2Gate = CompletableDeferred<Unit>()
        val bgSlotsAcquired = AtomicInteger(0)
        val bothBgRunning = CompletableDeferred<Unit>()
        val trailRunningGate = CompletableDeferred<Unit>()

        OverpassClient.customTransport = { _, query ->
            when {
                query.contains("bg_1") -> {
                    if (bgSlotsAcquired.incrementAndGet() == 2) bothBgRunning.complete(Unit)
                    bgSlot1Gate.await()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
                query.contains("bg_2") -> {
                    if (bgSlotsAcquired.incrementAndGet() == 2) bothBgRunning.complete(Unit)
                    bgSlot2Gate.await()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
                query.contains("out geom") -> {
                    trailRunningGate.await()
                    OverpassClient.HttpResult(200, buildSingleSectionOverpassGeomJson(778899L, "Enkeltsektion Sti"))
                }
                else -> OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        // Fill both OverpassClient concurrency slots (1 BACKGROUND + 1 USER_INITIATED) so our trail request enters the queue first
        val bg1 = async(Dispatchers.IO) { OverpassClient.queryRawJson("bg_1", "bg1", OverpassClient.Priority.BACKGROUND) }
        val bg2 = async(Dispatchers.IO) { OverpassClient.queryRawJson("bg_2", "bg2", OverpassClient.Priority.USER_INITIATED) }
        bothBgRunning.await()

        val observedStages = CopyOnWriteArrayList<TrailDownloadProgress>()
        val queuedObserved = CompletableDeferred<TrailDownloadProgress>()
        val runningObserved = CompletableDeferred<TrailDownloadProgress>()

        val startMs = System.currentTimeMillis()
        val downloadJob = async(Dispatchers.IO) {
            geomRepo.fetchAndProcessTrail(
                trailId = trailId,
                trailName = "Enkeltsektion Sti",
                osmRelationId = 778899L,
                onProgress = { prog ->
                    observedStages.add(prog)
                    if (prog.stageMessage.startsWith("Venter på OpenStreetMap (") && !queuedObserved.isCompleted) {
                        queuedObserved.complete(prog)
                    }
                    if (prog.stageMessage == "Henter rute fra OpenStreetMap…" && !runningObserved.isCompleted) {
                        runningObserved.complete(prog)
                    }
                }
            )
        }

        // 1. Verify "Venter på OpenStreetMap (n foran i køen)" while queued
        val queuedState = queuedObserved.await()
        assertTrue(
            "Queued message must match 'Venter på OpenStreetMap (n foran i køen)', got: ${queuedState.stageMessage}",
            Regex("""Venter på OpenStreetMap \(\d+ foran i køen\)""").matches(queuedState.stageMessage)
        )
        assertTrue("Queued progress must be indeterminate", queuedState.isIndeterminate)

        // Release one background slot so the trail download starts running
        bgSlot1Gate.complete(Unit)
        val runningState = runningObserved.await()
        assertEquals("Henter rute fra OpenStreetMap…", runningState.stageMessage)
        assertTrue("Running request progress must be indeterminate", runningState.isIndeterminate)

        // Release the trail download Overpass call
        trailRunningGate.complete(Unit)
        bgSlot2Gate.complete(Unit)

        val dto = downloadJob.await()
        bg1.await()
        bg2.await()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertNotNull("Single-section trail must download successfully", dto)
        assertTrue("Single-section trail must move past 5% within seconds (took ${elapsedMs}ms)", elapsedMs < 5_000L)

        // Verify it NEVER emitted a fixed 5 % (0.05f) stage
        assertFalse(
            "Progress must never emit a fixed 5% stage: $observedStages",
            observedStages.any { kotlin.math.abs(it.fraction - 0.05f) < 0.001f }
        )
        // Verify determinate stages after Overpass returns moved past 5% (0.70f -> 1.0f)
        assertTrue(
            "Must include Sammensætter rutegeometri stage at 70%",
            observedStages.any { it.stageMessage.contains("Sammensætter rutegeometri") && it.fraction >= 0.70f && !it.isIndeterminate }
        )
        assertTrue(
            "Must reach 100% Færdig stage",
            observedStages.any { it.fraction >= 1.0f && it.stageMessage.startsWith("Færdig") }
        )

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item2c_timeouts_serverPlus15s_andReadTimeoutDoesNotRetrySameServerMovesNextMirrorOnceThenFails(): Unit = runBlocking {
        // 1. Verify server timeout + 15 s calculation for relation geometry ([timeout:90] -> 105 s)
        // and corridor POIs ([timeout:120] -> 135 s)
        val relGeomQuery = """
            [out:json][timeout:90];
            relation(12345);
            out geom;
        """.trimIndent()
        assertEquals(90L, OverpassClient.parseServerTimeoutSeconds(relGeomQuery))
        assertEquals(105L, OverpassClient.computeClientReadTimeoutSeconds(relGeomQuery))

        val osmPoiRepo = OsmPoiRepository(ApplicationProvider.getApplicationContext())
        val corridorPolylineQuery = osmPoiRepo.buildOverpassPolylineQuery(
            listOf(GeoPointDto(55.0, 10.0), GeoPointDto(55.1, 10.1))
        )
        assertTrue(
            "Corridor POI query must specify [timeout:120]",
            corridorPolylineQuery.contains("[timeout:120]")
        )
        assertEquals(120L, OverpassClient.parseServerTimeoutSeconds(corridorPolylineQuery))
        assertEquals(135L, OverpassClient.computeClientReadTimeoutSeconds(corridorPolylineQuery))

        // 2. Verify read timeout does NOT retry on the same server; moves to next mirror ONCE, then fails
        val triedMirrors = CopyOnWriteArrayList<String>()
        OverpassClient.maxAttemptsPerMirror = 3 // Even if maxAttemptsPerMirror is 3, read timeout must NOT retry same server!
        OverpassClient.backoffBaseDelayMs = 10L
        OverpassClient.customTransport = { mirrorUrl, _ ->
            triedMirrors.add(mirrorUrl)
            throw SocketTimeoutException("Read timed out from $mirrorUrl")
        }

        val failedWithTimeout = runCatching {
            OverpassClient.queryRawJson(relGeomQuery, label = "ReadTimeoutTest")
        }
        assertTrue("Query must fail after moving to next mirror once", failedWithTimeout.isFailure)
        assertEquals(
            "Read timeout must NOT retry on the same server and must move to the next mirror ONCE (total 2 calls across 2 distinct mirrors)",
            2,
            triedMirrors.size
        )
        assertTrue(
            "The two attempts must be on two different mirrors: $triedMirrors",
            triedMirrors[0] != triedMirrors[1]
        )
        assertEquals(105L, OverpassClient.observedReadTimeoutsByLabel["ReadTimeoutTest"])
    }

    @Test
    fun item2d_priority_userInitiatedTrailDownloadsGoAheadOfBackgroundRequestsInQueue(): Unit = runBlocking {
        val slot1Gate = CompletableDeferred<Unit>()
        val slot2Gate = CompletableDeferred<Unit>()
        val slotsOccupied = AtomicInteger(0)
        val bothBusy = CompletableDeferred<Unit>()
        val executionOrder = CopyOnWriteArrayList<String>()

        OverpassClient.customTransport = { _, query ->
            executionOrder.add(query)
            when (query) {
                "busy_1" -> {
                    if (slotsOccupied.incrementAndGet() == 2) bothBusy.complete(Unit)
                    slot1Gate.await()
                }
                "busy_2" -> {
                    if (slotsOccupied.incrementAndGet() == 2) bothBusy.complete(Unit)
                    slot2Gate.await()
                }
            }
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        // Occupy both concurrency slots (1 BACKGROUND + 1 USER_INITIATED)
        val b1 = async(Dispatchers.IO) { OverpassClient.queryRawJson("busy_1", "busy_1", OverpassClient.Priority.BACKGROUND) }
        val b2 = async(Dispatchers.IO) { OverpassClient.queryRawJson("busy_2", "busy_2", OverpassClient.Priority.USER_INITIATED) }
        bothBusy.await()

        // Enqueue 3 background requests (water, access, POI sync) followed by 1 user-initiated trail download
        val bgWater = async(Dispatchers.IO) { OverpassClient.queryRawJson("bg_water", "water", OverpassClient.Priority.BACKGROUND) }
        val bgAccess = async(Dispatchers.IO) { OverpassClient.queryRawJson("bg_access", "access", OverpassClient.Priority.BACKGROUND) }
        val bgPoi = async(Dispatchers.IO) { OverpassClient.queryRawJson("bg_poi", "poi", OverpassClient.Priority.BACKGROUND) }
        delay(30L) // Ensure background requests are in the queue first

        val userTrail = async(Dispatchers.IO) {
            OverpassClient.queryRawJson("user_trail_download", "trail", OverpassClient.Priority.USER_INITIATED)
        }
        delay(30L) // Ensure userTrail is queued

        // Release one active slot so the highest-priority queued waiter runs next, then release the second slot
        slot2Gate.complete(Unit)
        userTrail.await()
        slot1Gate.complete(Unit)

        bgWater.await()
        bgAccess.await()
        bgPoi.await()
        b1.await()
        b2.await()

        val queuedExecutionOrder = executionOrder.drop(2)
        assertEquals(
            "User-initiated trail download must execute BEFORE queued background requests: $queuedExecutionOrder",
            "user_trail_download",
            queuedExecutionOrder.first()
        )
    }

    @Test
    fun item2e_superroutes_fetchesChildTagsBbInOneRequestSkipsForeignChildren_andFetchesWayTagsInOneRequest(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "superroute_test_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val executedQueries = CopyOnWriteArrayList<String>()

        OverpassClient.customTransport = { _, query ->
            executedQueries.add(query)
            when {
                // 1. Parent superroute 9000 with 3 children: 9001 (DK), 9002 (DK), 9003 (Germany)
                query.contains("relation(9000);") && query.contains("out geom;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 9000,
                              "tags": { "name": "Hærvejen Superrute", "type": "superroute" },
                              "members": [
                                { "type": "relation", "ref": 9001, "role": "" },
                                { "type": "relation", "ref": 9002, "role": "" },
                                { "type": "relation", "ref": 9003, "role": "" }
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                // 2. Batch child tags + bounding boxes request: relation(9000); relation(r); out tags bb;
                query.contains("relation(9000);") && query.contains("relation(r);") && query.contains("out tags bb;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 9001,
                              "bounds": { "minlat": 55.2, "minlon": 9.2, "maxlat": 55.8, "maxlon": 9.5 },
                              "tags": { "name": "Hærvejen Etape 1" }
                            },
                            {
                              "type": "relation",
                              "id": 9002,
                              "bounds": { "minlat": 55.8, "minlon": 9.3, "maxlat": 56.4, "maxlon": 9.6 },
                              "tags": { "name": "Hærvejen Etape 2" }
                            },
                            {
                              "type": "relation",
                              "id": 9003,
                              "bounds": { "minlat": 53.5, "minlon": 9.5, "maxlat": 54.1, "maxlon": 10.1 },
                              "tags": { "name": "E1 Schleswig-Holstein", "country": "DE" }
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains("relation(9001);") && query.contains("out geom;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 9001,
                              "tags": { "name": "Hærvejen Etape 1" },
                              "members": [
                                {
                                  "type": "way",
                                  "ref": 101,
                                  "role": "",
                                  "geometry": [
                                    { "lat": 55.20, "lon": 9.20 },
                                    { "lat": 55.50, "lon": 9.30 }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains("relation(9002);") && query.contains("out geom;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 9002,
                              "tags": { "name": "Hærvejen Etape 2" },
                              "members": [
                                {
                                  "type": "way",
                                  "ref": 102,
                                  "role": "",
                                  "geometry": [
                                    { "lat": 55.50, "lon": 9.30 },
                                    { "lat": 55.80, "lon": 9.40 }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains("way(r);") && query.contains("out tags;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            { "type": "way", "id": 101, "tags": { "highway": "path", "surface": "gravel" } },
                            { "type": "way", "id": 102, "tags": { "highway": "track", "surface": "unpaved" } }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                else -> OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        val dto = geomRepo.fetchAndProcessTrail(
            trailId = trailId,
            trailName = "Hærvejen Superrute",
            osmRelationId = 9000L
        )
        assertNotNull("Superroute must be processed into TrailGeometryDto", dto)

        val childTagsBbQueries = executedQueries.filter { it.contains("relation(r);") && it.contains("out tags bb;") }
        assertEquals(
            "Must fetch children's tags and bounding boxes in ONE request",
            1,
            childTagsBbQueries.size
        )

        val foreignChildGeomQueries = executedQueries.filter { it.contains("relation(9003);") && it.contains("out geom;") }
        assertEquals(
            "Must skip child relation 9003 outside Denmark before downloading its geometry",
            0,
            foreignChildGeomQueries.size
        )

        val wayTagQueries = executedQueries.filter { it.contains("way(r);") && it.contains("out tags;") }
        assertEquals(
            "Must fetch member way tags in ONE request for all relations, not one per relation",
            1,
            wayTagQueries.size
        )

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item2f_2g_cancelButtonCancelsDownload_andAllMirrorsFailingShowsErrorWithin60s(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val trailId = "fail_or_cancel_trail_${System.currentTimeMillis()}"
        val trail = TrailEntity(
            id = trailId,
            name = "Fejlende Rute",
            description = "",
            region = "Jylland",
            parentRegion = "Jylland",
            osmRelationId = 667788L,
            isGeometryDownloaded = false
        )
        app.database.dao().insertTrail(trail)
        app.repository.geometryRepository.getGeometryFile(trailId).delete()

        // Part A: With all mirrors failing, the error shows within 60 s
        OverpassClient.backoffBaseDelayMs = 10L
        OverpassClient.customTransport = { mirrorUrl, _ ->
            OverpassClient.HttpResult(504, "Gateway Timeout from $mirrorUrl")
        }

        val vm = TrailViewModel(app)
        val failStartMs = System.currentTimeMillis()
        vm.loadTrail(trailId)

        var errorMsg: String? = null
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            errorMsg = vm.routeError.value
            if (errorMsg != null) break
            delay(25L)
        }
        val failElapsedMs = System.currentTimeMillis() - failStartMs
        assertNotNull("Route error must be shown when all mirrors fail", errorMsg)
        assertTrue(
            "Error must show within 60 s when all mirrors fail (took ${failElapsedMs}ms)",
            failElapsedMs < 60_000L
        )

        // Part B: Hanging server / user clicks "Annuller"
        val hangGate = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<Unit>()
        OverpassClient.customTransport = { _, query ->
            if (query.contains("667788")) {
                requestStarted.complete(Unit)
                hangGate.await()
            }
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        val vmCancel = TrailViewModel(app)
        vmCancel.loadTrail(trailId)
        requestStarted.await()

        vmCancel.cancelTrailDownload()
        val cancelDeadline = System.currentTimeMillis() + 2_000L
        while (TrailGeometryRepository.isDownloadInFlight(trailId) && System.currentTimeMillis() < cancelDeadline) {
            delay(20L)
        }
        assertFalse(
            "Shared download must stop when no one else is waiting",
            TrailGeometryRepository.isDownloadInFlight(trailId)
        )
        assertFalse("isFetchingLiveRoute must be false after cancelling", vmCancel.isFetchingLiveRoute.value)
        assertEquals("Download annulleret", vmCancel.routeError.value)
        hangGate.cancel()
    }

    // =========================================================================
    // ITEM 3: Trip completion and "Stop for i dag" write final progress before stopSelf;
    // after completing a trip the plan shows as finished in Mine ture
    // =========================================================================

    @Test
    fun item3_tripCompletionAndStopForToday_writeFinalProgressBeforeStopSelf_andPlanShowsAsFinishedInMineTure() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val dao = app.database.dao()
        val nowMs = System.currentTimeMillis()

        val (planId, customRouteId, planEntity) = runBlocking {
            val stagePoints = listOf(
                GeoPointDto(55.6761, 12.5683),
                GeoPointDto(55.6770, 12.5690),
                GeoPointDto(55.6780, 12.5700)
            )
            val stage = TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 0.25,
                stageKm = 0.25,
                stopName = "Mål Lejr",
                stopType = "SHELTER",
                stopLat = 55.6780,
                stopLng = 12.5700,
                spotId = "shelter_end"
            )
            val pid = dao.insertTripPlan(
                TripPlanEntity(
                    trailId = "route_finish_test",
                    trailName = "Gennemført Vandretur",
                    startKm = 0.0,
                    endKm = 0.25,
                    isReverse = false,
                    totalDistanceKm = 0.25,
                    targetDays = 1,
                    overnightType = "SHELTER",
                    preferredDailyKm = 15.0,
                    stagesJson = app.repository.serializeStages(listOf(stage))
                )
            )
            val cid = dao.insertCustomRoute(
                CustomRouteEntity(
                    name = "Gennemført Vandretur",
                    createdAt = nowMs,
                    updatedAt = nowMs,
                    waypointsJson = "[]",
                    optionsJson = "{}",
                    totalKm = 0.25,
                    ascentM = 0.0,
                    descentM = 0.0,
                    pathSharePct = 100.0,
                    roadKm = 0.0,
                    geometryFile = "finish_test.json",
                    sourceName = "Test",
                    fetchedAt = nowMs
                )
            )
            val plannedRoute = com.example.data.model.PlannedRoute(
                planId = pid,
                trailId = "route_finish_test",
                trailName = "Gennemført Vandretur",
                isReverse = false,
                startTrailKm = 0.0,
                endTrailKm = 0.25,
                totalWalkKm = 0.25,
                points = listOf(
                    com.example.data.model.RoutePoint(55.6761, 12.5683, 10.0, 0.0, 0.0),
                    com.example.data.model.RoutePoint(55.6770, 12.5690, 10.0, 0.12, 0.12),
                    com.example.data.model.RoutePoint(55.6780, 12.5700, 10.0, 0.25, 0.25)
                ),
                stages = listOf(stage)
            )
            com.example.data.repository.PlannedRouteRepository(app).savePlannedRoute(plannedRoute)

            val controller = Robolectric.buildService(NavigationTrackingService::class.java)
            val service = controller.create().get()

            val startIntent = Intent(app, NavigationTrackingService::class.java).apply {
                action = NavigationTrackingService.ACTION_START
                putExtra(NavigationTrackingService.EXTRA_PLAN_ID, pid)
            }
            service.onStartCommand(startIntent, 0, 1)

            val deadline = System.currentTimeMillis() + 3_000L
            while (!NavigationTrackingService.liveState.value.isTracking && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(20L)
            }

            // 1. Test "Stop for i dag" writes progress before stopSelf (await the coroutine on the phone code path)
            val stopTodayIntent = Intent(app, NavigationTrackingService::class.java).apply {
                action = NavigationTrackingService.ACTION_STOP_TODAY
            }
            service.onStartCommand(stopTodayIntent, 0, 2)
            service.lastPersistProgressJob?.join()
            val pausedProgress = dao.getTripProgress(pid).first()
            assertNotNull("Progress must be written to DB when Stop for i dag is triggered", pausedProgress)
            assertTrue("Progress must be marked paused after Stop for i dag", pausedProgress!!.isPaused)

            // 2. Restart and walk to end of trip -> verify completion is persisted before stopSelf
            service.onStartCommand(startIntent, 0, 3)
            val deadline2 = System.currentTimeMillis() + 3_000L
            while (!NavigationTrackingService.liveState.value.isTracking && System.currentTimeMillis() < deadline2) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(20L)
            }

            val endLoc = Location("gps").apply {
                latitude = 55.6780
                longitude = 12.5700
                accuracy = 4f
                time = System.currentTimeMillis()
            }
            service.handleNewLocation(endLoc)
            service.lastPersistProgressJob?.join()

            val finalProgress = dao.getTripProgress(pid).first()
            assertNotNull("Final completed progress must be persisted before service stops", finalProgress)
            assertTrue("Final progress must have isCompleted = true", finalProgress!!.isCompleted)

            val entity = dao.getTripPlanById(pid)!!
            Triple(pid, cid, entity)
        }

        // 3. Verify the plan shows as finished in Mine ture (SavedViewModel + TripPlanLibraryCard UI)
        val savedVm = SavedViewModel(app)
        val completedWaitDeadline = System.currentTimeMillis() + 3_000L
        while (planId !in savedVm.completedPlanIds.value && System.currentTimeMillis() < completedWaitDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        assertTrue(
            "SavedViewModel.completedPlanIds must contain the finished planId ($planId)",
            planId in savedVm.completedPlanIds.value
        )

        composeRule.setContent {
            DanskStiTheme {
                TripPlanLibraryCard(
                    plan = planEntity,
                    isCompleted = planEntity.id in savedVm.completedPlanIds.value,
                    onOpenInPlanner = {},
                    onStartNavigation = {},
                    onExportGpx = {},
                    onShare = {},
                    onDelete = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("plan_completed_badge_${planEntity.id}", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Gennemført", useUnmergedTree = true).assertIsDisplayed()

        runBlocking {
            dao.deleteCustomRoute(
                CustomRouteEntity(
                    id = customRouteId,
                    name = "",
                    createdAt = nowMs,
                    updatedAt = nowMs,
                    waypointsJson = "",
                    optionsJson = "",
                    totalKm = 0.0,
                    ascentM = 0.0,
                    descentM = 0.0,
                    pathSharePct = 0.0,
                    roadKm = 0.0,
                    geometryFile = "",
                    sourceName = "",
                    fetchedAt = nowMs
                )
            )
        }
    }

    // =========================================================================
    // ITEM 4: "Nærmeste" uses the same fresh-position helper as Round 12A item 6
    // instead of getLastKnownLocation
    // =========================================================================

    @Test
    fun item4_naermeste_usesFreshPositionHelperInsteadOfGetLastKnownLocation(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val vm = RouteBuilderViewModel(app)

        val resolverInvocations = AtomicInteger(0)
        val freshGpsLocation = Location("gps").apply {
            latitude = 56.1520
            longitude = 9.5450
            time = System.currentTimeMillis()
        }
        vm.customLocationResolver = { _ ->
            resolverInvocations.incrementAndGet()
            FreshLocationResolution(
                location = freshGpsLocation,
                isOldFix = false,
                ageMinutes = 0L,
                staleMessage = null
            )
        }

        // Set up a 2-waypoint route near (56.1520, 9.5450) with no selected pin
        val wp1 = RouteWaypoint(id = "wp_start", lat = 56.1500, lng = 9.5400, role = WaypointRole.START, name = "Start")
        val wp2 = RouteWaypoint(id = "wp_end", lat = 56.1600, lng = 9.5500, role = WaypointRole.END, name = "Slut")
        vm._uiState.value = vm._uiState.value.copy(
            waypoints = listOf(wp1, wp2),
            selectedWaypointId = null,
            currentGpsPosition = null
        )

        // Call openNearestPoiDialog without passing userLat/userLng (just like RouteBuilderScreen now does)
        vm.openNearestPoiDialog(mapCenterLat = 55.0, mapCenterLng = 10.0)

        assertTrue(
            "openNearestPoiDialog must invoke the Round 12A item 6 fresh-position helper (resolveCurrentOrFallbackLocation)",
            resolverInvocations.get() >= 1
        )
        assertEquals(
            "Since fresh GPS position is near the route and no pin is selected, origin must be 'Nærmest din position'",
            R.string.nearest_poi_origin_my_position,
            vm.uiState.value.nearestPoiOriginLabelRes
        )
        assertEquals(56.1520, vm.uiState.value.currentGpsPosition?.lat ?: 0.0, 1e-4)
        assertEquals(9.5450, vm.uiState.value.currentGpsPosition?.lng ?: 0.0, 1e-4)
    }

    // =========================================================================
    // ITEM 5: "Ryd cache" also protects trail geometry used by saved custom routes
    // (CustomRouteEntity.baseTrailId)
    // =========================================================================

    @Test
    fun item5_rydCache_protectsTrailGeometryUsedBySavedCustomRoutesBaseTrailId(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val repo = app.repository
        val dao = app.database.dao()

        val protectedBaseTrailId = "custom_route_base_trail_${System.currentTimeMillis()}"
        val unprotectedTrailId = "unprotected_browsed_trail_${System.currentTimeMillis()}"

        val trailsDir = File(app.filesDir, "trails").apply { mkdirs() }
        val protectedFile = File(trailsDir, "$protectedBaseTrailId.json.gz").apply {
            writeBytes(ByteArray(2048) { 1 })
        }
        val unprotectedFile = File(trailsDir, "$unprotectedTrailId.json.gz").apply {
            writeBytes(ByteArray(2048) { 2 })
        }

        val nowMs = System.currentTimeMillis()
        val customRouteId = dao.insertCustomRoute(
            CustomRouteEntity(
                name = "Min tilpassede rute på eksisterende vandrerute",
                createdAt = nowMs,
                updatedAt = nowMs,
                waypointsJson = "[]",
                optionsJson = "{}",
                totalKm = 42.0,
                ascentM = 0.0,
                descentM = 0.0,
                pathSharePct = 100.0,
                roadKm = 0.0,
                geometryFile = "custom_base_test.json",
                sourceName = "Test",
                fetchedAt = nowMs,
                baseTrailId = protectedBaseTrailId
            )
        )

        try {
            val protectedIds = repo.getProtectedTrailIdsForRouteLinesCache()
            assertTrue(
                "Protected trail IDs must include CustomRouteEntity.baseTrailId ($protectedBaseTrailId)",
                protectedBaseTrailId in protectedIds
            )

            // Clear ROUTE_LINES cache ("Rutelinjer") via Ryd cache
            repo.clearSelectedCaches(setOf(CacheCategory.ROUTE_LINES))

            assertTrue(
                "Trail geometry file referenced by CustomRouteEntity.baseTrailId must NOT be deleted by Ryd cache",
                protectedFile.exists()
            )
            assertFalse(
                "Unprotected browsed trail geometry file must be deleted by Ryd cache",
                unprotectedFile.exists()
            )
        } finally {
            protectedFile.delete()
            unprotectedFile.delete()
            dao.deleteCustomRoute(
                CustomRouteEntity(
                    id = customRouteId,
                    name = "",
                    createdAt = nowMs,
                    updatedAt = nowMs,
                    waypointsJson = "",
                    optionsJson = "",
                    totalKm = 0.0,
                    ascentM = 0.0,
                    descentM = 0.0,
                    pathSharePct = 0.0,
                    roadKm = 0.0,
                    geometryFile = "",
                    sourceName = "",
                    fetchedAt = nowMs
                )
            )
        }
    }
}
