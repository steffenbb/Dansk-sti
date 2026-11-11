package com.example

import android.app.Application
import android.content.Intent
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.data.model.UserCustomWaypointEntity
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.components.formatScrubbedElevationLabel
import com.example.ui.navigation.MapLayersPoiBuilder
import com.example.ui.viewmodel.PlannerMapPoi
import com.example.ui.viewmodel.computeNavigationSunsetForState
import com.example.util.StagePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class FixRound11Test {

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DanskStiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun item1_trailGeometryRepository_neverCallsOpenStreetMapEditingApi_usesOverpassClient() {
        val repoFile = File("src/main/java/com/example/data/repository/TrailGeometryRepository.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/data/repository/TrailGeometryRepository.kt")
        assertTrue("TrailGeometryRepository.kt must exist", repoFile.exists())
        val content = repoFile.readText()
        assertFalse(
            "TrailGeometryRepository must not reference api.openstreetmap.org/api/0.6",
            content.contains("api.openstreetmap.org")
        )
        assertFalse(
            "TrailGeometryRepository must not have downloadRelationDirectOsm",
            content.contains("downloadRelationDirectOsm")
        )
        assertTrue(
            "TrailGeometryRepository must use OverpassClient.queryRawJson",
            content.contains("OverpassClient.queryRawJson")
        )

        val healthFile = File("src/main/java/com/example/util/network/ApiHealthChecker.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/util/network/ApiHealthChecker.kt")
        assertTrue("ApiHealthChecker.kt must exist", healthFile.exists())
        assertFalse(
            "ApiHealthChecker must not reference api.openstreetmap.org",
            healthFile.readText().contains("api.openstreetmap.org")
        )
    }

    @Test
    fun item2_roomSchema26Exported_andMigration25To26CreatesPlannerDraftsAndPreservesTrails() {
        val schemaFile = File("schemas/com.example.data.local.DanskStiDatabase/26.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/com.example.data.local.DanskStiDatabase/26.json")
        assertTrue("Room schema 26.json must exist at schemas/.../26.json", schemaFile.exists())
        val schemaJson = schemaFile.readText()
        assertTrue("Schema 26.json must declare version 26", schemaJson.contains("\"version\": 26"))
        assertTrue("Schema 26.json must include planner_drafts table", schemaJson.contains("\"tableName\": \"planner_drafts\""))
        assertTrue("Schema 26.json must include offline_downloads_in_progress table", schemaJson.contains("\"tableName\": \"offline_downloads_in_progress\""))

        val dbName = "migration_test_25_26_${System.currentTimeMillis()}.db"
        migrationHelper.createDatabase(dbName, 25).apply {
            execSQL(
                """
                INSERT INTO `trails` (
                    `id`, `name`, `description`, `region`, `parentRegion`,
                    `osmRelationId`, `computedWalkingKm`, `gapsOver200m`, `osmTimestamp`,
                    `bboxNorth`, `bboxSouth`, `bboxEast`, `bboxWest`,
                    `isGeometryDownloaded`, `lastUpdated`, `source`
                ) VALUES (
                    'camino_fyn', 'Øhavsstien', 'Vandrerute', 'Fyn', 'Fyn & Øerne',
                    12345, 220.0, 0, NULL,
                    55.5, 54.8, 10.9, 9.9,
                    0, 1700000000000, 'OSM wiki Denmark Walking Routes'
                )
                """.trimIndent()
            )
            close()
        }

        val db = migrationHelper.runMigrationsAndValidate(
            dbName,
            26,
            true,
            DanskStiDatabase.MIGRATION_25_26
        )

        // Verify existing trails row is preserved
        db.query("SELECT id, name, computedWalkingKm FROM trails WHERE id = 'camino_fyn'").use { cursor ->
            assertTrue("Migrated trail row must exist", cursor.moveToFirst())
            assertEquals("camino_fyn", cursor.getString(0))
            assertEquals("Øhavsstien", cursor.getString(1))
            assertEquals(220.0, cursor.getDouble(2), 1e-4)
        }

        // Verify planner_drafts table was created by MIGRATION_25_26 and accepts inserts/queries
        db.execSQL(
            """
            INSERT INTO `planner_drafts` (
                `id`, `routeId`, `baseTrailId`, `routeName`, `mode`,
                `waypointsJson`, `optionsJson`, `isReverse`, `updatedAt`
            ) VALUES (
                'active_draft', NULL, 'camino_fyn', 'Kladde Fyn', 'TRAIL',
                '[]', '{}', 0, 1700000001000
            )
            """.trimIndent()
        )
        db.query("SELECT id, routeName, baseTrailId, isReverse FROM planner_drafts WHERE id = 'active_draft'").use { cursor ->
            assertTrue("Inserted planner_drafts row must exist", cursor.moveToFirst())
            assertEquals("active_draft", cursor.getString(0))
            assertEquals("Kladde Fyn", cursor.getString(1))
            assertEquals("camino_fyn", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }

        // Verify offline_downloads_in_progress table was created by MIGRATION_25_26
        db.execSQL(
            """
            INSERT INTO `offline_downloads_in_progress` (`trailId`, `startedAt`)
            VALUES ('camino_fyn', 1700000002000)
            """.trimIndent()
        )
        db.query("SELECT trailId, startedAt FROM offline_downloads_in_progress WHERE trailId = 'camino_fyn'").use { cursor ->
            assertTrue("Inserted offline_downloads_in_progress row must exist", cursor.moveToFirst())
            assertEquals("camino_fyn", cursor.getString(0))
            assertEquals(1700000002000L, cursor.getLong(1))
        }

        db.close()
    }

    @Test
    fun item3_orphanFilesCleanup_deletesUnreferencedPlansRoutesOfflineAndPhotos_keepsReferenced(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val repo = app.repository
        val dao = app.database.dao()

        val tempBaseDir = File(app.cacheDir, "orphan_test_${System.currentTimeMillis()}").apply { mkdirs() }
        val plansDir = File(tempBaseDir, "plans").apply { mkdirs() }
        val routesDir = File(tempBaseDir, "routes").apply { mkdirs() }
        val offlineDir = File(tempBaseDir, "offline").apply { mkdirs() }
        val photosDir = File(tempBaseDir, "photos").apply { mkdirs() }

        // Insert valid entities into DB
        val validPlanId = dao.insertTripPlan(
            TripPlanEntity(
                trailId = "trail_1",
                trailName = "Test Trail",
                startKm = 0.0,
                endKm = 20.0,
                isReverse = false,
                totalDistanceKm = 20.0,
                targetDays = 2,
                overnightType = "SHELTER",
                preferredDailyKm = 10.0,
                stagesJson = "[]"
            )
        )
        val validCustomRouteId = dao.insertCustomRoute(
            CustomRouteEntity(
                id = 0L,
                name = "Keep Route",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                optionsJson = "{}",
                waypointsJson = "[]",
                totalKm = 12.0,
                ascentM = 50.0,
                descentM = 45.0,
                pathSharePct = 80.0,
                roadKm = 1.0,
                geometryFile = "custom_keep_1.json",
                sourceName = "brouter",
                fetchedAt = System.currentTimeMillis()
            )
        )
        assertTrue(validCustomRouteId > 0L)

        val validOfflineFile = File(offlineDir, "trail_keep.sqlite").apply { writeText("tiles") }
        val orphanOfflineFile = File(offlineDir, "trail_orphan.sqlite").apply {
            writeText("orphan_tiles")
            setLastModified(System.currentTimeMillis() - 25L * 3600L * 1000L)
        }
        dao.insertDownloadedTrail(
            DownloadedTrailEntity(
                trailId = "trail_keep",
                trailName = "Keep Offline",
                corridorKm = 1.0,
                tileCount = 100,
                sizeBytes = 2048L,
                downloadedAt = System.currentTimeMillis()
            )
        )
        val validPhotoFile = File(photosDir, "point_keep.jpg").apply { writeText("jpg") }
        val orphanPhotoFile = File(photosDir, "point_orphan.jpg").apply { writeText("orphan_jpg") }
        dao.insertUserCustomWaypoint(
            UserCustomWaypointEntity(
                id = "pt_keep",
                name = "Keep Point",
                lat = 56.0,
                lng = 9.5,
                note = "Udsigt",
                photoPath = validPhotoFile.absolutePath
            )
        )

        val validPlanFile = File(plansDir, "plan_$validPlanId.json").apply { writeText("{}") }
        val orphanPlanFile = File(plansDir, "plan_999999.json").apply { writeText("{}") }
        val validRouteFile = File(routesDir, "custom_keep_1.json").apply { writeText("{}") }
        val orphanRouteFile = File(routesDir, "custom_orphan_999.json").apply { writeText("{}") }

        // Execute cleanup
        repo.cleanupOrphanFiles(tempBaseDir)

        assertTrue("Valid plan file must be kept", validPlanFile.exists())
        assertFalse("Orphan plan file must be deleted", orphanPlanFile.exists())

        assertTrue("Valid route file must be kept", validRouteFile.exists())
        assertFalse("Orphan route file must be deleted", orphanRouteFile.exists())

        assertTrue("Valid offline archive must be kept", validOfflineFile.exists())
        assertFalse("Orphan offline archive must be deleted", orphanOfflineFile.exists())

        assertTrue("Valid photo file must be kept", validPhotoFile.exists())
        assertFalse("Orphan photo file must be deleted", orphanPhotoFile.exists())

        tempBaseDir.deleteRecursively()
    }

    @Test
    fun item4_navigationSunset_usesCurrentPositionOrTonightOvernightPoint_neverMidpoint_andUpdatesOnDayChange() {
        val startCal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Copenhagen")).apply {
            set(2026, Calendar.JUNE, 10, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startEpochMs = startCal.timeInMillis

        val plan = TripPlanEntity(
            id = 42L,
            trailId = "coast_to_coast",
            trailName = "Coast to Coast",
            startKm = 0.0,
            endKm = 40.0,
            isReverse = false,
            totalDistanceKm = 40.0,
            targetDays = 2,
            overnightType = "SHELTER",
            preferredDailyKm = 20.0,
            stagesJson = "[]",
            createdAt = startEpochMs
        )
        // Day 1 overnight in western Jutland (lng 8.12), Day 2 overnight in eastern Zealand (lng 12.60)
        val stages = listOf(
            TripStage(
                dayNumber = 1,
                startKm = 0.0,
                endKm = 20.0,
                stageKm = 20.0,
                stopName = "Blåvand Shelter",
                stopType = "Shelter",
                stopLat = 55.55,
                stopLng = 8.12
            ),
            TripStage(
                dayNumber = 2,
                startKm = 20.0,
                endKm = 40.0,
                stageKm = 20.0,
                stopName = "Kastrup Shelter",
                stopType = "Shelter",
                stopLat = 55.63,
                stopLng = 12.60
            )
        )
        // Route midpoint is around lng 10.36
        val route = PlannedRoute(
            planId = 42L,
            trailId = "coast_to_coast",
            trailName = "Coast to Coast",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 40.0,
            totalWalkKm = 40.0,
            points = listOf(
                RoutePoint(lat = 55.55, lng = 8.12, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 55.59, lng = 10.36, walkKm = 20.0, trailKm = 20.0),
                RoutePoint(lat = 55.63, lng = 12.60, walkKm = 40.0, trailKm = 40.0)
            ),
            stages = stages
        )

        // 1) When GPS position is unknown, uses tonight's overnight point (stage 1: lng 8.12), NOT midpoint (10.36)
        val sunsetDay1Overnight = computeNavigationSunsetForState(
            liveState = NavigationLiveState(currentDay = 1, currentLat = null, currentLng = null),
            currentDay = 1,
            activePlan = plan,
            activeStages = stages,
            plannedRoute = route,
            fallbackStartDateEpochMs = startEpochMs
        )
        val expectedDay1Overnight = StagePlanner.computeSunsetForStageDay(1, 55.55, 8.12, startEpochMs)
        val midpointSunset = StagePlanner.computeSunsetForStageDay(1, 55.59, 10.36, startEpochMs)
        assertEquals(expectedDay1Overnight, sunsetDay1Overnight)
        assertNotEquals("Must not use route midpoint for sunset", midpointSunset, sunsetDay1Overnight)

        // 2) When day changes to Day 2, sunset updates to Day 2's date and Day 2's overnight point
        val sunsetDay2Overnight = computeNavigationSunsetForState(
            liveState = NavigationLiveState(currentDay = 2, currentLat = null, currentLng = null),
            currentDay = 2,
            activePlan = plan,
            activeStages = stages,
            plannedRoute = route,
            fallbackStartDateEpochMs = startEpochMs
        )
        val expectedDay2Overnight = StagePlanner.computeSunsetForStageDay(2, 55.63, 12.60, startEpochMs)
        assertEquals(expectedDay2Overnight, sunsetDay2Overnight)
        assertNotEquals("Sunset must update when day changes", sunsetDay1Overnight, sunsetDay2Overnight)

        // 3) When current GPS position is known (e.g. Skagen 57.72, 10.58), uses current GPS position
        val sunsetWithLiveGps = computeNavigationSunsetForState(
            liveState = NavigationLiveState(currentDay = 1, currentLat = 57.72, currentLng = 10.58),
            currentDay = 1,
            activePlan = plan,
            activeStages = stages,
            plannedRoute = route,
            fallbackStartDateEpochMs = startEpochMs
        )
        val expectedLiveGps = StagePlanner.computeSunsetForStageDay(1, 57.72, 10.58, startEpochMs)
        assertEquals(expectedLiveGps, sunsetWithLiveGps)
    }

    @Test
    fun item5_plannerDeduplicatesShelterAndPoiMarkers_oneMarkerPerPlace() {
        val dbShelter = ShelterEntity(
            id = "shelter_100",
            name = "Skovbryn Shelter",
            lat = 56.12000,
            lng = 9.54000,
            capacity = 6,
            hasWaterNearby = true,
            hasToiletNearby = true,
            bookingRequired = false,
            bookingUrl = ""
        )
        // Planner also has the same shelter (slightly different ID or same coordinates)
        val plannerMarkerSameCoords = PlannerMapPoi(
            id = "planner_shelter_100",
            name = "Skovbryn Shelter",
            type = "SHELTER",
            lat = 56.12005, // ~5m away
            lng = 9.54005,
            distanceToRouteMeters = 45.0
        )
        val plannerUniqueWater = PlannerMapPoi(
            id = "planner_water_200",
            name = "Skovkilde Vandpost",
            type = "WATER",
            lat = 56.15000,
            lng = 9.58000,
            distanceToRouteMeters = 10.0
        )

        val items = MapLayersPoiBuilder.buildClusterItems(
            state = MapLayersState(
                showShelters = true,
                showFriTeltning = true,
                showWater = true,
                showToilets = true,
                showShops = true,
                showTransport = true,
                showMyPoints = true
            ),
            shelters = listOf(dbShelter),
            friTeltning = emptyList(),
            pois = emptyList(),
            customWaypoints = emptyList(),
            plannerPois = listOf(plannerMarkerSameCoords, plannerUniqueWater)
        )

        val sheltersNearSkovbryn = items.filter { it.name.contains("Skovbryn") }
        assertEquals("Must only produce ONE marker for Skovbryn Shelter", 1, sheltersNearSkovbryn.size)
        assertEquals("Must include the unique planner water marker", 2, items.size)
    }

    @Test
    fun item6_mainActivity_removesOpenNavigationAndPlanIdExtrasAfterHandling() {
        val intent = Intent().apply {
            putExtra(NavigationTrackingService.EXTRA_OPEN_NAVIGATION, true)
            putExtra(NavigationTrackingService.EXTRA_PLAN_ID, 77L)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()

        // Extras should already be removed from activity.intent during onCreate(handleNavigationIntent)
        assertFalse(
            "EXTRA_OPEN_NAVIGATION must be removed from intent after handling",
            activity.intent.hasExtra(NavigationTrackingService.EXTRA_OPEN_NAVIGATION)
        )
        assertFalse(
            "EXTRA_PLAN_ID must be removed from intent after handling",
            activity.intent.hasExtra(NavigationTrackingService.EXTRA_PLAN_ID)
        )
        assertEquals(77L, activity.pendingNavigationPlanId.value)
        activity.consumePendingNavigationPlanId()
        assertNull("After consumePendingNavigationPlanId, pendingNavigationPlanId must be null", activity.pendingNavigationPlanId.value)
        controller.destroy()
    }

    @Test
    fun item7_navigationViewModel_loadRouteCorridorPois_hasErrorMessageAndRetry() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val expectedError = app.getString(R.string.nav_corridor_pois_error)
        assertEquals("Kunne ikke hente steder langs ruten - Prøv igen", expectedError)

        val vmFile = File("src/main/java/com/example/ui/viewmodel/NavigationViewModel.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/ui/viewmodel/NavigationViewModel.kt")
        val vmSource = vmFile.readText()
        assertFalse(
            "NavigationViewModel must not swallow exceptions with empty catch (_: Exception) {}",
            vmSource.contains("catch (_: Exception) {}")
        )
        assertTrue(
            "NavigationViewModel must expose retryLoadRouteCorridorPois()",
            vmSource.contains("fun retryLoadRouteCorridorPois()")
        )
    }

    @Test
    fun item8_routeElevationProfileChart_scrubbedPointWithoutElevation_showsHoejdeUkendt() {
        val labelMissing = formatScrubbedElevationLabel(scrubbedKm = 4.2f, ele = null)
        assertTrue(
            "Scrubbed point without elevation must show 'Højde ukendt', got: $labelMissing",
            labelMissing.contains("Højde ukendt")
        )
        assertFalse(
            "Scrubbed point without elevation must NOT show '0 m', got: $labelMissing",
            labelMissing.contains("0 m")
        )

        val labelZeroPresent = formatScrubbedElevationLabel(scrubbedKm = 4.2f, ele = 45.0)
        assertTrue(
            "Scrubbed point with elevation 45.0 must show '45 m', got: $labelZeroPresent",
            labelZeroPresent.contains("45 m")
        )
    }

    @Test
    fun item10_splitFilesAreAllUnder800Lines() {
        val targetFiles = listOf(
            "ui/components/PoiInfoSheet.kt",
            "ui/screens/OfflineTrailsScreen.kt",
            "ui/map/DanskStiMap.kt",
            "ui/viewmodel/NavigationViewModel.kt",
            "ui/navigation/MapLayersOverlayManager.kt"
        )
        for (relPath in targetFiles) {
            val file = File("src/main/java/com/example/$relPath")
                .takeIf { it.exists() }
                ?: File("app/src/main/java/com/example/$relPath")
            assertTrue("File $relPath must exist", file.exists())
            val lineCount = file.readLines().size
            assertTrue(
                "File $relPath must be under 800 lines, but was $lineCount lines",
                lineCount < 800
            )
        }
    }
}
