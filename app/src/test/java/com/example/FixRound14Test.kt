package com.example

import android.content.Intent
import android.location.Location
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DanskStiDatabase
import com.example.data.model.GeoPointDto
import com.example.data.model.OfflineDownloadInProgressEntity
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.remote.OverpassClient
import com.example.data.repository.TrailGeometryRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.screens.NavigationCampAndContinuePrompts
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.ui.viewmodel.cancelTrailGeometryDownload
import com.example.util.RouteTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound14Test {

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
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
    }

    // =========================================================================
    // ITEM 1: Exported schemas/27.json and MigrationTestHelper (25 -> 26 -> 27, 26 -> 27)
    // =========================================================================

    @Test
    fun item1_schemas27Exported_andMigrationTestHelper_25To26To27_and_26To27() {
        val schema27 = File("schemas/com.example.data.local.DanskStiDatabase/27.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/com.example.data.local.DanskStiDatabase/27.json")
        assertTrue("schemas/27.json must exist and be exported by Room", schema27.exists())
        val content27 = schema27.readText()
        assertTrue(content27.contains("\"version\": 27"))
        assertTrue(content27.contains("\"tableName\": \"offline_downloads_in_progress\""))

        // 25 -> 26 -> 27
        val dbName25 = "r14_mig_25_26_27_${System.currentTimeMillis()}.db"
        migrationHelper.createDatabase(dbName25, 25).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'nordsoestien', 'Nordsøstien', 'Vestkysten', 'Nordjylland', 'Jylland',
                    2869042, 410.0, 0, NULL,
                    57.7, 55.4, 10.6, 8.1,
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
        db26.close()

        val db27From25 = migrationHelper.runMigrationsAndValidate(
            dbName25,
            27,
            true,
            DanskStiDatabase.MIGRATION_26_27
        )
        db27From25.query("SELECT name FROM trails WHERE id = 'nordsoestien'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Nordsøstien", c.getString(0))
        }
        db27From25.close()

        // 26 -> 27
        val dbName26 = "r14_mig_26_27_${System.currentTimeMillis()}.db"
        migrationHelper.createDatabase(dbName26, 26).apply {
            execSQL(
                """
                INSERT INTO `planner_drafts` (
                    `id`, `routeId`, `baseTrailId`, `routeName`, `mode`,
                    `waypointsJson`, `optionsJson`, `isReverse`, `updatedAt`
                ) VALUES (
                    'active_draft', NULL, 'nordsoestien', 'Nordsøstien etaper', 'TRAIL',
                    '[]', '{}', 0, 1700000010000
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
        db27From26.query("SELECT routeName FROM planner_drafts WHERE id = 'active_draft'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Nordsøstien etaper", c.getString(0))
        }
        db27From26.execSQL(
            "INSERT INTO `offline_downloads_in_progress` (`trailId`, `startedAt`) VALUES ('nordsoestien', 1700000099000)"
        )
        db27From26.close()
    }

    // =========================================================================
    // ITEM 2: Trail download time limits
    // - Remove overall withTimeout(noServerAnswerTimeoutMs) around the whole download.
    // - Time spent waiting in OverpassClient queue never counts towards any timeout.
    // - "No answer": if a server sends no response headers within 30 s after request starts,
    //   move to next mirror; if no mirror has answered within 60 s of request starting, show error.
    // - Once a server is answering, let the download finish; only fail if no data arrives for 30 s.
    // - Keep 1 of the 2 Overpass slots reserved for user-initiated requests, so a running
    //   background request can never block a trail download.
    // =========================================================================

    private fun buildNordsoestienGeometryJson(relationId: Long = 2869042L): String {
        return """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": $relationId,
                  "tags": { "name": "Nordsøstien", "type": "route", "route": "hiking" },
                  "members": [
                    {
                      "type": "way",
                      "ref": 7001,
                      "role": "",
                      "geometry": [
                        { "lat": 57.1200, "lon": 8.6000 },
                        { "lat": 57.1500, "lon": 8.6200 },
                        { "lat": 57.1800, "lon": 8.6500 },
                        { "lat": 57.2100, "lon": 8.6800 }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun item2_noOverallWithTimeoutInTrailGeometryRepositorySource() {
        val repoFile = File("src/main/java/com/example/data/repository/TrailGeometryRepository.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/data/repository/TrailGeometryRepository.kt")
        assertTrue(repoFile.exists())
        val source = repoFile.readText()
        assertFalse(
            "TrailGeometryRepository must NOT have noServerAnswerTimeoutMs or an overall withTimeout around the whole download",
            source.contains("noServerAnswerTimeoutMs") || source.contains("withTimeout(")
        )
    }

    @Test
    fun item2_largeRelationTaking90sToTransfer_completesAndIsCached_andQueueWaitDoesNotCount(): Unit = runTest {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "nordsoestien_90s_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        // Keep exact production thresholds: 30 s per-mirror header timeout, 60 s total no-answer, 30 s idle data
        OverpassClient.noAnswerMirrorTimeoutMs = 30_000L
        OverpassClient.noAnswerTotalTimeoutMs = 60_000L
        OverpassClient.idleDataTimeoutMs = 30_000L

        OverpassClient.customSessionTransport = { _, query, session ->
            when {
                query.contains("occupy_slot_1") || query.contains("occupy_slot_2") -> {
                    session.onHeadersReceived()
                    repeat(7) {
                        delay(10_000L)
                        session.onDataChunkReceived()
                    }
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
                query.contains("out geom") -> {
                    // Server answers with response headers after 5 s
                    delay(5_000L)
                    session.onHeadersReceived()
                    // Stream data for 90 s total: 6 chunks spaced 15 s apart (< 30 s idle limit)
                    repeat(6) {
                        delay(15_000L)
                        session.onDataChunkReceived()
                    }
                    OverpassClient.HttpResult(200, buildNordsoestienGeometryJson(2869042L))
                }
                else -> {
                    session.onHeadersReceived()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
            }
        }

        // First: occupy both slots for 70 s so our trail download sits in the queue for 70 s (> 60 s!).
        // This verifies queue wait time NEVER counts towards the 30 s / 60 s timeout.
        val occ1 = async { OverpassClient.queryRawJson("occupy_slot_1", "occ1", OverpassClient.Priority.USER_INITIATED) }
        val occ2 = async { OverpassClient.queryRawJson("occupy_slot_2", "occ2", OverpassClient.Priority.USER_INITIATED) }
        testScheduler.advanceTimeBy(1_000L)

        val downloadDeferred = async {
            geomRepo.fetchAndProcessTrail(
                trailId = trailId,
                trailName = "Nordsøstien",
                osmRelationId = 2869042L
            )
        }

        // Advance through the 70 s queue wait + 95 s active transfer (5 s headers + 90 s body streaming)
        testScheduler.advanceUntilIdle()

        val dto = downloadDeferred.await()
        occ1.await()
        occ2.await()

        assertNotNull("Large relation taking 90 s to transfer must complete without timing out", dto)
        assertTrue(
            "Completed 90 s relation must be cached on disk",
            geomRepo.getGeometryFile(trailId).exists()
        )
        val cached = geomRepo.loadGeometry(trailId)
        assertNotNull("Cached geometry must load cleanly from disk", cached)
        assertTrue("Cached geometry must have walk points", cached!!.segments.any { it.points.isNotEmpty() })

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item2_serverThatNeverAnswers_movesToNextMirrorAt30s_andShowsErrorWithin60s(): Unit = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.noAnswerMirrorTimeoutMs = 30_000L
        OverpassClient.noAnswerTotalTimeoutMs = 60_000L
        OverpassClient.idleDataTimeoutMs = 30_000L

        val mirrorAttemptStartTimes = CopyOnWriteArrayList<Pair<String, Long>>()

        OverpassClient.customSessionTransport = { mirrorUrl, _, _ ->
            mirrorAttemptStartTimes.add(mirrorUrl to testScheduler.currentTime)
            // Never sends response headers (hangs forever)
            CompletableDeferred<Unit>().await()
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        val startTimeMs = testScheduler.currentTime
        val result = runCatching {
            OverpassClient.queryRawJson(
                query = "[out:json][timeout:90];relation(2869042);out geom;",
                label = "DeadServerTest",
                priority = OverpassClient.Priority.USER_INITIATED
            )
        }
        val elapsedVirtualMs = testScheduler.currentTime - startTimeMs

        assertTrue("Request to dead servers must fail", result.isFailure)
        assertTrue(
            "Error must occur within 60 s of request starting (elapsed=${elapsedVirtualMs}ms)",
            elapsedVirtualMs in 59_000L..60_500L
        )
        assertEquals(
            "Must move to the second mirror after 30 s of no response headers on the first mirror, then fail at 60 s",
            2,
            mirrorAttemptStartTimes.size
        )
        assertEquals(0L, mirrorAttemptStartTimes[0].second - startTimeMs)
        assertEquals(30_000L, mirrorAttemptStartTimes[1].second - startTimeMs)
    }

    @Test
    fun item2_onceServerAnswers_failsOnlyIfNoDataArrivesFor30s(): Unit = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.noAnswerMirrorTimeoutMs = 30_000L
        OverpassClient.noAnswerTotalTimeoutMs = 60_000L
        OverpassClient.idleDataTimeoutMs = 30_000L
        OverpassClient.maxAttemptsPerMirror = 1

        OverpassClient.customSessionTransport = { _, _, session ->
            // Server sends headers at 2 s and 1 chunk at 10 s, then stalls forever with no data
            delay(2_000L)
            session.onHeadersReceived()
            delay(8_000L)
            session.onDataChunkReceived()
            CompletableDeferred<Unit>().await()
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        val result = runCatching {
            OverpassClient.queryRawJson(
                query = "[out:json][timeout:90];relation(123);out geom;",
                label = "StalledStreamTest",
                priority = OverpassClient.Priority.USER_INITIATED
            )
        }
        assertTrue("Stream that stops sending data for 30 s must fail", result.isFailure)
    }

    @Test
    fun item2_trailDownloadStartsWithin1s_whileTwoBackgroundRequestsAreRunning(): Unit = runBlocking {
        val bg1Started = CompletableDeferred<Unit>()
        val bg1Release = CompletableDeferred<Unit>()
        val bg2Release = CompletableDeferred<Unit>()
        val trailStartedAtMs = AtomicLong(0L)

        OverpassClient.customTransport = { _, query ->
            when {
                query.contains("bg_req_1") -> {
                    bg1Started.complete(Unit)
                    bg1Release.await()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
                query.contains("bg_req_2") -> {
                    bg2Release.await()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
                query.contains("user_trail_req") -> {
                    trailStartedAtMs.set(System.currentTimeMillis())
                    OverpassClient.HttpResult(200, buildNordsoestienGeometryJson(2869042L))
                }
                else -> OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        // Start two background requests (e.g. water + access data)
        val bgJob1 = async(Dispatchers.IO) {
            OverpassClient.queryRawJson("bg_req_1", "bg1", OverpassClient.Priority.BACKGROUND)
        }
        bg1Started.await()
        val bgJob2 = async(Dispatchers.IO) {
            OverpassClient.queryRawJson("bg_req_2", "bg2", OverpassClient.Priority.BACKGROUND)
        }
        delay(30L) // bgJob1 is running on slot 1; bgJob2 is queued because slot 2 is reserved for USER_INITIATED

        val enqueueTimeMs = System.currentTimeMillis()
        val userTrailJob = async(Dispatchers.IO) {
            OverpassClient.queryRawJson("user_trail_req", "trail", OverpassClient.Priority.USER_INITIATED)
        }

        val trailResponse = userTrailJob.await()
        val startLatencyMs = trailStartedAtMs.get() - enqueueTimeMs

        assertTrue("Trail download must return valid JSON", trailResponse.contains("Nordsøstien"))
        assertTrue(
            "Trail download must start within 1 s (1000 ms) while two background requests are running (started in ${startLatencyMs}ms)",
            startLatencyMs in 0L..1_000L
        )

        bg1Release.complete(Unit)
        bg2Release.complete(Unit)
        bgJob1.await()
        bgJob2.await()
    }

    // =========================================================================
    // ITEM 3: "Annuller" on a trail download
    // - Only cancels this screen's wait. The shared download stops only if no one else is waiting for it
    //   (for example the offline map worker).
    // - Never cancel an offline map download or delete its in-progress record from this button;
    //   offline downloads have their own cancel in the Offline screen.
    // =========================================================================

    @Test
    fun item3_cancelTrailDownload_onlyCancelsThisScreensWait_keepsSharedDownloadAndOfflineInProgressWhenOtherWaiterExists(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val dao = app.database.dao()
        val repo = app.repository
        val geomRepo = repo.geometryRepository

        val trailId = "shared_cancel_trail_${System.currentTimeMillis()}"
        val trail = TrailEntity(
            id = trailId,
            name = "Delt Offline Rute",
            description = "",
            region = "Jylland",
            parentRegion = "Jylland",
            osmRelationId = 556677L,
            isGeometryDownloaded = false
        )
        dao.insertTrail(trail)
        geomRepo.getGeometryFile(trailId).delete()

        // Record an in-progress offline download in Room (as OfflineDownloadWorker / OfflineDownloadManager does)
        dao.upsertOfflineDownloadInProgress(
            OfflineDownloadInProgressEntity(trailId = trailId, startedAt = System.currentTimeMillis())
        )

        val requestStarted = CompletableDeferred<Unit>()
        val releaseNetwork = CompletableDeferred<Unit>()
        val overpassCalls = AtomicInteger(0)

        OverpassClient.customTransport = { _, query ->
            if (query.contains("out geom")) {
                overpassCalls.incrementAndGet()
                requestStarted.complete(Unit)
                releaseNetwork.await()
                OverpassClient.HttpResult(200, buildNordsoestienGeometryJson(556677L))
            } else {
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        // Waiter 1: Offline map worker downloading the trail geometry
        val offlineWorkerWaiter = async(Dispatchers.IO) {
            repo.getTrailPoints(trail)
        }
        requestStarted.await()

        // Waiter 2: User opens TrailDetailScreen (TrailViewModel) and waits for the same trail download
        val trailVm = TrailViewModel(app)
        trailVm.loadTrail(trailId)

        val waitJoinDeadline = System.currentTimeMillis() + 2_000L
        while (TrailGeometryRepository.getActiveWaiterCount(trailId) < 2 && System.currentTimeMillis() < waitJoinDeadline) {
            delay(20L)
        }
        assertEquals("Both offline worker and TrailViewModel must be waiting on the shared download", 2, TrailGeometryRepository.getActiveWaiterCount(trailId))

        // User presses "Annuller" on the trail download in TrailDetailScreen
        trailVm.cancelTrailDownload()
        delay(80L)

        // 1. TrailViewModel's wait is cancelled
        assertFalse("TrailViewModel must stop waiting after Annuller", trailVm.isFetchingLiveRoute.value)
        assertEquals("Download annulleret", trailVm.routeError.value)

        // 2. Because offlineWorkerWaiter is still waiting, the shared download MUST still be running!
        assertTrue(
            "Shared download must NOT stop while offline worker is still waiting for it",
            TrailGeometryRepository.isDownloadInFlight(trailId)
        )
        assertFalse(
            "trailId must NOT be in cancelledTrailIds while another waiter is active",
            TrailGeometryRepository.cancelledTrailIds.contains(trailId)
        )

        // 3. The offline map download's in-progress record in Room must NOT be deleted!
        val inProgressRecord = dao.getOfflineDownloadInProgress(trailId)
        assertNotNull(
            "Offline download in-progress record must NEVER be deleted by trail download Annuller button",
            inProgressRecord
        )

        // Release Overpass response -> offline worker completes normally!
        releaseNetwork.complete(Unit)
        val offlinePoints = offlineWorkerWaiter.await()
        assertTrue("Offline worker must receive the downloaded trail points", offlinePoints.isNotEmpty())
        assertEquals("Only 1 Overpass request must have been made", 1, overpassCalls.get())

        // Also verify RouteBuilderViewModel.cancelTrailGeometryDownload stops the shared download ONLY when it is the sole waiter
        val soloTrailId = "solo_cancel_trail_${System.currentTimeMillis()}"
        val soloStarted = CompletableDeferred<Unit>()
        val soloGate = CompletableDeferred<Unit>()
        OverpassClient.customTransport = { _, query ->
            if (query.contains("998877")) {
                soloStarted.complete(Unit)
                soloGate.await()
            }
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        val rbVm = RouteBuilderViewModel(app)
        rbVm._uiState.value = rbVm._uiState.value.copy(baseTrailId = soloTrailId)
        rbVm.trailGeometryDownloadJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            geomRepo.fetchAndProcessTrail(soloTrailId, "Solo Rute", 998877L)
        }
        soloStarted.await()
        rbVm.cancelTrailGeometryDownload()

        val soloCancelDeadline = System.currentTimeMillis() + 2_000L
        while (soloTrailId !in TrailGeometryRepository.cancelledTrailIds && System.currentTimeMillis() < soloCancelDeadline) {
            delay(20L)
        }
        assertTrue(
            "When no one else is waiting, cancelling the screen's wait stops the shared download",
            soloTrailId in TrailGeometryRepository.cancelledTrailIds
        )
        soloGate.cancel()
        dao.deleteOfflineDownloadInProgress(trailId)
        geomRepo.getGeometryFile(trailId).delete()
        geomRepo.getGeometryFile(soloTrailId).delete()
    }

    // =========================================================================
    // ITEM 4: Multi-day navigation (RouteTracker)
    // - Do not auto-advance the day when currentWalkKm passes today's stage end.
    //   Instead set "arrived at tonight's camp" and show the arrival card.
    // - stopForToday advances exactly one day from the day being walked.
    // - If I keep walking more than 500 m past tonight's camp without pressing "Stop for i dag",
    //   advance one day once, and say so.
    // - The arrival card uses today's stage, not the next day's.
    // - Tests:
    //   1. Arrive at camp, press "Stop for i dag" -> next day is day 2 (and 3-day trip goes day 1 -> 2 -> 3);
    //   2. Walk 600 m past camp without stopping -> day 2, never day 3;
    //   3. The arrival card appears when within 150 m.
    // =========================================================================

    private fun createThreeDayPlannedRoute(): PlannedRoute {
        val totalKm = 30.0
        val numPoints = 601 // 0.05 km (50 m) spacing
        val baseLat = 55.0
        val baseLng = 10.0
        val points = (0 until numPoints).map { idx ->
            val walkKm = idx * 0.05
            RoutePoint(
                lat = baseLat + (walkKm / 111.0),
                lng = baseLng,
                ele = 10.0,
                walkKm = walkKm,
                trailKm = walkKm,
                segmentIndex = 0
            )
        }
        val stages = listOf(
            TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 10.0,
                stageKm = 10.0,
                stopName = "Dag 1 Shelter",
                stopType = "SHELTER",
                stopLat = baseLat + (10.0 / 111.0),
                stopLng = baseLng
            ),
            TripStage(
                dayNumber = 2,
                startKm = 10.0,
                endKm = 20.0,
                stageKm = 10.0,
                stopName = "Dag 2 Teltplads",
                stopType = "TENT",
                stopLat = baseLat + (20.0 / 111.0),
                stopLng = baseLng
            ),
            TripStage(
                dayNumber = 3,
                startKm = 20.0,
                endKm = 30.0,
                stageKm = 10.0,
                stopName = "Dag 3 Mål",
                stopType = "SHELTER",
                stopLat = baseLat + (30.0 / 111.0),
                stopLng = baseLng
            )
        )
        return PlannedRoute(
            planId = 14L,
            trailId = "three_day_trail",
            trailName = "Tredages Rute",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = totalKm,
            totalWalkKm = totalKm,
            points = points,
            stages = stages
        )
    }

    @Test
    fun item4_arriveAtCamp_pressStopForToday_nextDayIsDay2_andThreeDayTripGoes1To2To3() {
        val route = createThreeDayPlannedRoute()
        val tracker = RouteTracker(route, initialWalkKm = 9.0, initialDay = 1)

        // 1. Walk to 9.80 km (200 m before Day 1 camp at 10.0 km) -> not arrived yet
        val pt980 = route.points.first { it.walkKm >= 9.80 }
        val res980 = tracker.onLocationUpdate(pt980.lat, pt980.lng, 0f, 5f)
        assertEquals(1, res980.currentDay)
        assertFalse("200 m before camp must not trigger arrival card yet", res980.hasArrivedAtTonightCamp)

        // 2. Walk to 9.90 km (100 m before Day 1 camp, within 150 m) -> arrival card triggers with Day 1's stage!
        val pt990 = route.points.first { it.walkKm >= 9.90 }
        val res990 = tracker.onLocationUpdate(pt990.lat, pt990.lng, 0f, 5f)
        assertEquals("Day must stay Day 1 within 150 m of camp", 1, res990.currentDay)
        assertTrue("Arrival card flag must be true when within 150 m", res990.hasArrivedAtTonightCamp)
        assertEquals("Arrival card stage must be today's stage (Dag 1 Shelter)", "Dag 1 Shelter", res990.currentStage?.stopName)

        // 3. Walk right onto / slightly past Day 1 camp (10.05 km, 50 m past stage end)
        // Must NOT auto-advance to day 2; must keep hasArrivedAtTonightCamp = true and today's stage!
        val pt1005 = route.points.first { it.walkKm >= 10.05 }
        val res1005 = tracker.onLocationUpdate(pt1005.lat, pt1005.lng, 0f, 5f)
        assertEquals("Passing stage end must NOT auto-advance day", 1, res1005.currentDay)
        assertTrue("Must still be arrived at tonight's camp at 10.05 km", res1005.hasArrivedAtTonightCamp)
        assertEquals("Stage must still be today's stage (Dag 1 Shelter), not next day's", "Dag 1 Shelter", res1005.currentStage?.stopName)

        // 4. Press "Stop for i dag" on Evening 1 -> advances to Day 2 (never Day 3!)
        val progressAfterDay1 = tracker.stopForToday()
        assertEquals("After Stop for i dag on Day 1, next day must be Day 2", 2, progressAfterDay1.currentDay)
        assertEquals(2, tracker.currentDay)
        assertFalse(tracker.hasArrivedAtTonightCamp)

        // 5. Walk Day 2 from 10.05 km to Day 2 camp at 20.0 km (step in 1.5 km increments within window)
        var walkKm = 11.5
        while (walkKm < 20.0) {
            val pt = route.points.first { it.walkKm >= walkKm }
            tracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
            walkKm += 1.5
        }
        val pt2000 = route.points.first { it.walkKm >= 20.0 }
        val resDay2Camp = tracker.onLocationUpdate(pt2000.lat, pt2000.lng, 0f, 5f)
        assertEquals("At Day 2 camp (20.0 km), currentDay must stay 2", 2, resDay2Camp.currentDay)
        assertTrue("At Day 2 camp, hasArrivedAtTonightCamp must be true", resDay2Camp.hasArrivedAtTonightCamp)
        assertEquals("Stage must be Day 2's stage (Dag 2 Teltplads)", "Dag 2 Teltplads", resDay2Camp.currentStage?.stopName)

        // 6. Press "Stop for i dag" on Evening 2 -> advances to Day 3!
        val progressAfterDay2 = tracker.stopForToday()
        assertEquals("After Stop for i dag on Day 2, next day must be Day 3", 3, progressAfterDay2.currentDay)
        assertEquals(3, tracker.currentDay)
    }

    @Test
    fun item4_walk600mPastCampWithoutStopping_advancesToDay2Once_neverDay3_andSaysSo() {
        val route = createThreeDayPlannedRoute()
        val tracker = RouteTracker(route, initialWalkKm = 9.5, initialDay = 1)

        // Walk to Day 1 camp at 10.0 km -> still Day 1
        val pt1000 = route.points.first { it.walkKm >= 10.0 }
        val resAtCamp = tracker.onLocationUpdate(pt1000.lat, pt1000.lng, 0f, 5f)
        assertEquals(1, resAtCamp.currentDay)
        assertTrue(resAtCamp.hasArrivedAtTonightCamp)
        assertNull(resAtCamp.autoAdvancedDayMessage)

        // Walk 400 m past camp (10.40 km) -> still <= 500 m, so still Day 1
        val pt1040 = route.points.first { it.walkKm >= 10.40 }
        val res400mPast = tracker.onLocationUpdate(pt1040.lat, pt1040.lng, 0f, 5f)
        assertEquals("400 m past camp is <= 500 m, so still Day 1", 1, res400mPast.currentDay)

        // Walk 600 m past camp (10.60 km) without pressing "Stop for i dag" -> advances to Day 2 once, and says so!
        val pt1060 = route.points.first { it.walkKm >= 10.60 }
        val res600mPast = tracker.onLocationUpdate(pt1060.lat, pt1060.lng, 0f, 5f)
        assertEquals("Walking 600 m past camp without stopping must advance to Day 2", 2, res600mPast.currentDay)
        assertFalse(res600mPast.hasArrivedAtTonightCamp)
        assertNotNull("Must provide a message explaining the auto-advance past camp", res600mPast.autoAdvancedDayMessage)
        assertTrue(
            "Message must mention 500 m / Dag 1 Shelter / dag 2: ${res600mPast.autoAdvancedDayMessage}",
            res600mPast.autoAdvancedDayMessage!!.contains("500 m") &&
                res600mPast.autoAdvancedDayMessage!!.contains("Dag 1 Shelter") &&
                res600mPast.autoAdvancedDayMessage!!.contains("dag 2")
        )

        // Keep walking further without stopping (even past 20.6 km!) -> must stay Day 2, NEVER Day 3!
        var km = 12.0
        var lastResult = res600mPast
        while (km <= 21.0) {
            val pt = route.points.first { it.walkKm >= km }
            lastResult = tracker.onLocationUpdate(pt.lat, pt.lng, 0f, 5f)
            assertEquals("Without pressing Stop for i dag, day must stay 2 and never advance to 3 (at km=$km)", 2, lastResult.currentDay)
            km += 1.5
        }
        assertEquals(2, tracker.currentDay)
    }

    @Test
    fun item4_arrivalCardAppearsWhenWithin150m_andUsesTodaysStageNotNextDays() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val navVm = NavigationViewModel(app)
        val route = createThreeDayPlannedRoute()
        val tracker = RouteTracker(route, initialWalkKm = 9.5, initialDay = 1)

        // Move to 9.88 km (120 m before Day 1 camp at 10.0 km -> within 150 m)
        val pt988 = route.points.first { it.walkKm >= 9.88 }
        val trackRes = tracker.onLocationUpdate(pt988.lat, pt988.lng, 0f, 5f)

        assertTrue("hasArrivedAtTonightCamp must be true at 120 m before camp", trackRes.hasArrivedAtTonightCamp)
        assertEquals(1, trackRes.currentDay)
        assertEquals("Dag 1 Shelter", trackRes.currentStage?.stopName)

        val liveState = NavigationLiveState(
            isTracking = true,
            currentDay = trackRes.currentDay,
            currentStage = trackRes.currentStage,
            distanceWalkedTodayKm = trackRes.walkedTodayKm,
            distanceRemainingTodayKm = trackRes.remainingTodayKm,
            hasArrivedAtTonightCamp = trackRes.hasArrivedAtTonightCamp,
            autoAdvancedDayMessage = trackRes.autoAdvancedDayMessage,
            plannedRoute = route
        )

        composeRule.setContent {
            DanskStiTheme {
                NavigationCampAndContinuePrompts(
                    viewModel = navVm,
                    liveState = liveState,
                    currentDay = liveState.currentDay,
                    distanceRemainingToday = liveState.distanceRemainingTodayKm,
                    sunsetTime = "21:15",
                    isNearTonightCamp = liveState.hasArrivedAtTonightCamp,
                    campNameForArrival = liveState.currentStage?.stopName,
                    currentDayStat = null,
                    nextWater = null,
                    onDismissCampArrival = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_camp_arrival_card").assertIsDisplayed()
        composeRule.onNodeWithText("Dag 1 Shelter", substring = true).assertIsDisplayed()
    }

    // =========================================================================
    // ITEM 5: NavigationTrackingService: remove Build.FINGERPRINT == "robolectric" branch.
    // Tests must run the same code path as the phone (await the coroutine in the test instead).
    // =========================================================================

    @Test
    fun item5_navigationTrackingService_hasNoRobolectricBranch_andPhoneCodePathPersistsProgress(): Unit = runBlocking {
        val serviceFile = File("src/main/java/com/example/service/NavigationTrackingService.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/service/NavigationTrackingService.kt")
        assertTrue(serviceFile.exists())
        val source = serviceFile.readText()
        assertFalse(
            "NavigationTrackingService must NOT contain a Build.FINGERPRINT == robolectric branch",
            source.lowercase().contains("robolectric")
        )

        // Verify the phone code path in NavigationTrackingService persists progress for a 3-day trip (day 1 -> 2 -> 3)
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val dao = app.database.dao()
        val route = createThreeDayPlannedRoute()

        val planId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = route.trailId,
                trailName = route.trailName,
                startKm = 0.0,
                endKm = 30.0,
                isReverse = false,
                totalDistanceKm = 30.0,
                targetDays = 3,
                overnightType = "SHELTER",
                preferredDailyKm = 10.0,
                stagesJson = app.repository.serializeStages(route.stages)
            )
        )
        com.example.data.repository.PlannedRouteRepository(app).savePlannedRoute(
            route.copy(planId = planId)
        )

        val controller = Robolectric.buildService(NavigationTrackingService::class.java)
        val service = controller.create().get()

        val startIntent = Intent(app, NavigationTrackingService::class.java).apply {
            action = NavigationTrackingService.ACTION_START
            putExtra(NavigationTrackingService.EXTRA_PLAN_ID, planId)
        }
        val stopTodayIntent = Intent(app, NavigationTrackingService::class.java).apply {
            action = NavigationTrackingService.ACTION_STOP_TODAY
        }

        // Day 1 -> Evening 1 "Stop for i dag" -> Day 2
        service.onStartCommand(startIntent, 0, 1)
        val wait1 = System.currentTimeMillis() + 3_000L
        while (!NavigationTrackingService.liveState.value.isTracking && System.currentTimeMillis() < wait1) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(20L)
        }
        assertEquals(1, NavigationTrackingService.liveState.value.currentDay)

        service.onStartCommand(stopTodayIntent, 0, 2)
        service.lastPersistProgressJob?.join()
        val progAfterEvening1 = dao.getTripProgress(planId).first()
        assertNotNull(progAfterEvening1)
        assertEquals("After Evening 1 Stop for i dag, persisted day must be 2", 2, progAfterEvening1!!.currentDay)

        // Day 2 -> Evening 2 "Stop for i dag" -> Day 3
        service.onStartCommand(startIntent, 0, 3)
        val wait2 = System.currentTimeMillis() + 3_000L
        while (!NavigationTrackingService.liveState.value.isTracking && System.currentTimeMillis() < wait2) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(20L)
        }
        assertEquals(2, NavigationTrackingService.liveState.value.currentDay)

        service.onStartCommand(stopTodayIntent, 0, 4)
        service.lastPersistProgressJob?.join()
        val progAfterEvening2 = dao.getTripProgress(planId).first()
        assertNotNull(progAfterEvening2)
        assertEquals("After Evening 2 Stop for i dag, persisted day must be 3", 3, progAfterEvening2!!.currentDay)
    }
}
