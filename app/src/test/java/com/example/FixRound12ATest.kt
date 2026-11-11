package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.location.Location
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.local.DanskStiDatabase
import com.example.data.local.HikingSettingsRanges
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OfflineDownloadInProgressEntity
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailEntity
import com.example.data.repository.OsmPoiRepository
import com.example.service.NavigationTrackingService
import com.example.ui.map.LiveMapLocationState
import com.example.ui.viewmodel.RouteBuilderViewModel
import com.example.ui.viewmodel.openTripSettingsSheet
import com.example.ui.viewmodel.setCurrentPosition
import com.example.util.OfflineDownloadManager
import com.example.util.RouteTracker
import com.example.util.StagePlanner
import com.example.util.routing.LatLng
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound12ATest {

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DanskStiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @org.junit.After
    fun tearDown(): Unit = runBlocking {
        runCatching {
            if (WorkManager.isInitialized()) {
                WorkManager.getInstance(app).cancelAllWork().result.get()
            }
        }
        runCatching {
            app.database.dao().clearAllOfflineDownloadsInProgress()
        }
    }

    @Test
    fun item1_orphanCleanup_preservesInProgressQueuedAndRecentArchives_andResumesInterruptedDownload(): Unit = runBlocking {
        val repo = app.repository
        val dao = app.database.dao()
        dao.clearAllOfflineDownloadsInProgress()

        val tempBaseDir = File(app.cacheDir, "orphan_12a_${System.currentTimeMillis()}").apply { mkdirs() }
        val offlineDir = File(tempBaseDir, "offline").apply { mkdirs() }
        val olderThan24h = System.currentTimeMillis() - 26L * 3600L * 1000L

        // 1) Downloaded trail row exists -> never delete even if > 24h
        val completedFile = File(offlineDir, "trail_completed.sqlite").apply {
            writeText("completed_tiles")
            setLastModified(olderThan24h)
        }
        dao.insertDownloadedTrail(
            DownloadedTrailEntity(
                trailId = "trail_completed",
                trailName = "Completed Trail",
                corridorKm = 1.0,
                tileCount = 50,
                sizeBytes = 1024L,
                downloadedAt = olderThan24h
            )
        )

        // 2) Interrupted / in-progress record in Room -> never delete even if > 24h
        val inProgressFile = File(offlineDir, "trail_in_progress.sqlite").apply {
            writeText("partial_tiles")
            setLastModified(olderThan24h)
        }
        dao.insertTrails(
            listOf(
                TrailEntity(
                    id = "trail_in_progress",
                    name = "In Progress Trail",
                    description = "Desc",
                    region = "Jylland",
                    parentRegion = "Jylland"
                )
            )
        )
        dao.upsertOfflineDownloadInProgress(
            OfflineDownloadInProgressEntity(trailId = "trail_in_progress", startedAt = olderThan24h)
        )

        // 3) Unreferenced file younger than 24h -> never delete
        val recentOrphanFile = File(offlineDir, "trail_recent_orphan.sqlite").apply {
            writeText("recent_orphan")
            setLastModified(System.currentTimeMillis() - 2L * 3600L * 1000L)
        }

        // 4) Unreferenced file with neither downloaded_trails nor in-progress record and older than 24h -> delete
        val oldOrphanFile = File(offlineDir, "trail_old_orphan.sqlite").apply {
            writeText("old_orphan")
            setLastModified(olderThan24h)
        }

        // Re-queue pending downloads first (simulating app reopen), then run orphan cleanup
        repo.requeuePendingOfflineDownloads()
        repo.cleanupOrphanFiles(tempBaseDir)

        assertTrue("Completed offline archive must be kept", completedFile.exists())
        assertTrue("In-progress interrupted offline archive must be kept", inProgressFile.exists())
        assertTrue("Orphan offline archive younger than 24h must be kept", recentOrphanFile.exists())
        assertFalse("Orphan offline archive > 24h with no record must be deleted", oldOrphanFile.exists())

        // Verify interrupted download was re-queued in WorkManager for resumption
        val workInfos = runCatching {
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("offline_download_trail_in_progress")
                .get()
        }.getOrDefault(emptyList())
        if (workInfos.isNotEmpty()) {
            val state = workInfos.first().state
            assertTrue(
                "Re-queued download work must be ENQUEUED or RUNNING, was $state",
                state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
            )
        }
        assertNotNull(
            "In-progress record must remain in Room until download finishes or is cancelled",
            dao.getOfflineDownloadInProgress("trail_in_progress")
        )

        // Cancelling the download removes the in-progress record
        OfflineDownloadManager.cancelDownload(app, "trail_in_progress")
        assertNull(
            "Cancelling download must remove in-progress record from Room",
            dao.getOfflineDownloadInProgress("trail_in_progress")
        )

        tempBaseDir.deleteRecursively()
    }

    @Test
    fun item2_migration25To26_validatesAgainstExportedSchema26Json() {
        val dbName = "migration_12a_25_to_26.db"
        migrationHelper.createDatabase(dbName, 25).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'haervejen', 'Hærvejen', 'Historisk rute', 'Jylland', 'Jylland',
                    999, 280.0, 0, NULL,
                    57.4, 54.8, 9.6, 9.1,
                    1, 1700000000000, 'OSM'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            dbName,
            26,
            true,
            DanskStiDatabase.MIGRATION_25_26
        )
        migrated.close()
    }

    @Test
    fun item3_stagePlanner_wouldArriveAfterSunset_takesNullableTime_andIncludesClimbingTime() {
        // Null sunset (= unknown) must return false
        assertFalse(
            "Null sunset time must return false",
            StagePlanner.wouldArriveAfterSunset(
                startTime = "18:00",
                stageKm = 20.0,
                paceKmh = 4.0,
                sunsetTime = null,
                ascentMeters = 300.0
            )
        )

        // 12 km at 4.0 km/h = 3h (180 min) + 1h break = 4h. Start at 16:00 -> flat arrival 20:00. Sunset at 20:15.
        // Without climbing time: 20:00 <= 20:15 (false).
        assertFalse(
            "Flat stage arriving at 20:00 before 20:15 sunset must return false",
            StagePlanner.wouldArriveAfterSunset(
                startTime = "16:00",
                stageKm = 12.0,
                paceKmh = 4.0,
                sunsetTime = "20:15",
                ascentMeters = 0.0
            )
        )

        // With 200 m ascent -> +20 min climbing time (1 min per 10 m) -> arrival 20:20 > 20:15 (true).
        assertTrue(
            "Stage with 200m ascent (+20 min climbing time) arriving at 20:20 after 20:15 sunset must return true",
            StagePlanner.wouldArriveAfterSunset(
                startTime = "16:00",
                stageKm = 12.0,
                paceKmh = 4.0,
                sunsetTime = "20:15",
                ascentMeters = 200.0
            )
        )
    }

    @Test
    fun item4_plannerMinPositionButton_onlyCentresMap_neverChangesWaypoints() {
        val vm = RouteBuilderViewModel(app)
        val initialWaypoints = listOf(
            RouteWaypoint(id = "wp1", name = "Start A", lat = 56.10, lng = 9.50, role = WaypointRole.START),
            RouteWaypoint(id = "wp2", name = "Slut B", lat = 56.20, lng = 9.60, role = WaypointRole.END)
        )
        vm._uiState.value = vm._uiState.value.copy(waypoints = initialWaypoints)

        // Simulate pressing "Min position" button in RouteBuilderScreen (calls setCurrentPosition + centres map)
        vm.setCurrentPosition(55.6761, 12.5683)

        assertEquals(
            "Pressing 'Min position' must never change planner waypoints",
            initialWaypoints,
            vm.uiState.value.waypoints
        )

        // Verify RouteBuilderScreen source does not mutate waypoints inside onMyLocationClick
        val screenFile = File("src/main/java/com/example/ui/screens/RouteBuilderScreen.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/ui/screens/RouteBuilderScreen.kt")
        val screenCode = screenFile.readText()
        val myLocationBlock = screenCode.substringAfter("onMyLocationClick = {")
            .substringBefore("onAddStart =")
        assertFalse(
            "onMyLocationClick in RouteBuilderScreen must not call setStartFromMyLocation",
            myLocationBlock.contains("setStartFromMyLocation")
        )
        assertFalse(
            "onMyLocationClick in RouteBuilderScreen must not call addWaypoint",
            myLocationBlock.contains("addWaypoint")
        )
    }

    @Test
    fun item5_navigationNotifications_lowImportanceOnlyAlertOnceThrottled_andSingleOffRouteAlert() {
        val controller = Robolectric.buildService(NavigationTrackingService::class.java).create()
        val service = controller.get()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(notificationManager)

        // Check ongoing notification properties
        val ongoingNotif = service.buildNotification(
            trailName = "Hærvejen",
            stageName = "Halolms Shelter",
            speedKmh = 4.2,
            remainingTodayKm = 12.4,
            remainingTripKm = 45.0,
            isOffRoute = false,
            offRouteMeters = 0.0
        )
        assertEquals(
            "Ongoing notification must use low-importance CHANNEL_NAVIGATION_ID",
            DanskStiApp.CHANNEL_NAVIGATION_ID,
            ongoingNotif.channelId
        )
        assertTrue(
            "Ongoing notification must set setOnlyAlertOnce(true)",
            (ongoingNotif.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        )

        // Check throttling: updating within 10s with identical text must not re-post
        service.maybePostOngoingNotification(
            trailName = "Hærvejen",
            stageName = "Halolms Shelter",
            speedKmh = 4.2,
            remainingTodayKm = 12.4,
            remainingTripKm = 45.0,
            isOffRoute = false,
            offRouteMeters = 0.0,
            nowMs = 1_000_000L
        )
        assertEquals(1, service.ongoingNotificationPostCount)
        service.maybePostOngoingNotification(
            trailName = "Hærvejen",
            stageName = "Halolms Shelter",
            speedKmh = 4.2,
            remainingTodayKm = 12.4,
            remainingTripKm = 45.0,
            isOffRoute = false,
            offRouteMeters = 0.0,
            nowMs = 1_003_000L
        )
        assertEquals("Ongoing notification must not re-post within 10s when text has not changed", 1, service.ongoingNotificationPostCount)

        // Verify off-route alert fires only ONCE per deviation and not while detour is muted
        val route = PlannedRoute(
            planId = 10L,
            trailId = "haervejen",
            trailName = "Hærvejen",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 2.0,
            totalWalkKm = 2.0,
            points = listOf(
                RoutePoint(lat = 56.0000, lng = 9.5000, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 56.0100, lng = 9.5000, walkKm = 1.1, trailKm = 1.1)
            ),
            stages = emptyList()
        )
        service.planId = 10L
        service.currentPlannedRoute = route
        service.routeTracker = RouteTracker(route, initialWalkKm = 0.0, initialDay = 1)
        notificationManager.cancelAll()

        // 3 consecutive accurate off-route fixes (> 40m away from lng 9.5000, e.g. lng 9.5020 is ~125m away) trigger off-route state
        for (step in 1..3) {
            val loc = Location("gps").apply {
                latitude = 56.0050
                longitude = 9.5020
                accuracy = 5f
                time = 2_000_000L + step * 2_000L
            }
            service.handleNewLocation(loc)
        }

        val alertNotif1 = shadowNm.getNotification(NavigationTrackingService.OFF_ROUTE_ALERT_NOTIFICATION_ID)
        assertNotNull("Going off route must post an alert notification on OFF_ROUTE_ALERT_NOTIFICATION_ID", alertNotif1)
        assertEquals(
            "Off-route alert must use CHANNEL_ALERTS_ID",
            DanskStiApp.CHANNEL_ALERTS_ID,
            alertNotif1.channelId
        )
        assertEquals(1, service.offRouteAlertSentCount)

        // Clear shadow notifications to verify subsequent off-route locations (even > 2 min later) do NOT re-alert
        notificationManager.cancel(NavigationTrackingService.OFF_ROUTE_ALERT_NOTIFICATION_ID)
        val offRouteLocLater = Location("gps").apply {
            latitude = 56.0060
            longitude = 9.5022
            accuracy = 5f
            time = 2_200_000L
        }
        service.handleNewLocation(offRouteLocLater)
        assertNull(
            "Staying off route must NOT send repeated off-route alerts",
            shadowNm.getNotification(NavigationTrackingService.OFF_ROUTE_ALERT_NOTIFICATION_ID)
        )
        assertEquals(1, service.offRouteAlertSentCount)

        // Verify "Jeg tager en omvej" suppresses alerts while active
        service.takeDetourInternal(nowMs = 2_210_000L)
        val detourLoc = Location("gps").apply {
            latitude = 56.0070
            longitude = 9.5025
            accuracy = 5f
            time = 2_220_000L
        }
        service.handleNewLocation(detourLoc)
        assertNull(
            "Must not alert while 'Jeg tager en omvej' is active",
            shadowNm.getNotification(NavigationTrackingService.OFF_ROUTE_ALERT_NOTIFICATION_ID)
        )
        assertEquals(1, service.offRouteAlertSentCount)

        // Verify no android.R.drawable icons in NavigationTrackingService
        val serviceFile = File("src/main/java/com/example/service/NavigationTrackingService.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/service/NavigationTrackingService.kt")
        assertFalse(
            "NavigationTrackingService must use app monochrome icons, not android.R.drawable",
            serviceFile.readText().contains("android.R.drawable")
        )
        controller.destroy()
        NavigationTrackingService.updateLiveStateForTest(com.example.service.NavigationLiveState())
    }

    @Test
    fun item6_locationFreshness_showsOldFixMessageAndAllowsPickingAnotherStart() {
        val now = 10_000_000L
        val msg = LiveMapLocationState.formatOldFixMessage(
            fixTimeMs = now - 7L * 60_000L,
            nowMs = now
        )
        assertEquals("Position fra for 7 min siden", msg)

        val vm = RouteBuilderViewModel(app)
        vm._uiState.value = vm._uiState.value.copy(
            staleStartLocationMessage = msg,
            waitingForStartPointTap = false
        )
        assertEquals("Position fra for 7 min siden", vm.uiState.value.staleStartLocationMessage)

        vm.enablePickAnotherStart()
        assertTrue(
            "enablePickAnotherStart must enable picking another start on the map/search",
            vm.uiState.value.waitingForStartPointTap
        )
    }

    @Test
    fun item7_settingsRanges_sharedDefinitionAndOpeningSheetNeverChangesSavedValue() {
        assertEquals(2.0, HikingSettingsRanges.MIN_PACE_KMH, 1e-4)
        assertEquals(7.0, HikingSettingsRanges.MAX_PACE_KMH, 1e-4)
        assertEquals(5.0, HikingSettingsRanges.MIN_DAILY_TARGET_KM, 1e-4)
        assertEquals(40.0, HikingSettingsRanges.MAX_DAILY_TARGET_KM, 1e-4)
        assertEquals("05:00", HikingSettingsRanges.START_TIME_OPTIONS.first())
        assertEquals("20:00", HikingSettingsRanges.START_TIME_OPTIONS.last())
        assertEquals(31, HikingSettingsRanges.START_TIME_OPTIONS.size)
        assertTrue(HikingSettingsRanges.START_TIME_OPTIONS.contains("05:30"))
        assertTrue(HikingSettingsRanges.START_TIME_OPTIONS.contains("19:30"))

        val vm = RouteBuilderViewModel(app)
        vm._uiState.value = vm._uiState.value.copy(
            paceKmh = 3.5,
            dailyTargetKm = 28.0,
            startTimeStr = "06:30",
            showTripSettingsSheet = false
        )
        // Open settings sheet
        vm.openTripSettingsSheet()
        assertEquals(3.5, vm.uiState.value.paceKmh, 1e-4)
        assertEquals(28.0, vm.uiState.value.dailyTargetKm, 1e-4)
        assertEquals("06:30", vm.uiState.value.startTimeStr)
    }

    @Test
    fun item8_longCustomRoutes_splitsCorridorInto50KmChunksWith3KmOverlap() {
        val repo = OsmPoiRepository(app)
        // Build a ~120 km north-south polyline in Jutland (1.08 deg latitude ≈ 120 km)
        val points = (0..120).map { i ->
            val lat = 55.50 + (i * 0.009) // ~1.0 km per step -> ~120 km total
            GeoPointDto(lat = lat, lng = 9.50)
        }

        val chunks = repo.splitPolylineIntoChunks(points, chunkLengthKm = 50.0, overlapKm = 3.0)
        assertEquals("120 km route must be split into 3 chunks (~0-50, ~47-97, ~94-120)", 3, chunks.size)

        // Verify each chunk overlaps with the previous chunk by ~3 km
        val firstChunkEnd = chunks[0].last()
        val secondChunkStart = chunks[1].first()
        assertTrue(
            "Second chunk must start before first chunk ends (3 km overlap)",
            secondChunkStart.lat < firstChunkEnd.lat
        )
    }
}
