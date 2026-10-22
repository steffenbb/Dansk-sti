package com.example

import android.app.Application
import android.os.Looper
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DanskStiDatabase
import com.example.data.remote.DailyWeatherDto
import com.example.data.remote.OpenMeteoResponse
import com.example.data.remote.WeatherInterpreter
import com.example.data.repository.AccessLandRepository
import com.example.util.OfflineDownloadManager
import com.example.util.network.applyDebugMainThreadCheck
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.LatLng
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.WayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound3Part1Test {

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DanskStiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = androidx.room.Room.inMemoryDatabaseBuilder(app, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Requirement 1 & 2:
     * Call routeLeg from Dispatchers.Main (Robolectric main looper) and assert the
     * main-thread network interceptor is never triggered.
     * Also verify that calling OkHttp execute() directly on the main thread DOES trigger
     * IllegalStateException("Network on main thread").
     */
    @Test
    fun testRouteLegFromMainThread_NeverTriggersMainThreadNetworkInterceptor() = runBlocking {
        // In Robolectric, @Test methods run directly on the Android main thread (Looper.getMainLooper()).
        assertEquals("Test must run on Robolectric main looper", Looper.getMainLooper(), Looper.myLooper())

        // 1. Verify the guard interceptor throws IllegalStateException("Network on main thread")
        // if an OkHttp request is executed on the Robolectric main looper.
        val guardedClient = OkHttpClient.Builder()
            .applyDebugMainThreadCheck()
            .build()

        try {
            val req = Request.Builder().url("https://brouter.de/brouter").build()
            guardedClient.newCall(req).execute()
            fail("Expected IllegalStateException(\"Network on main thread\") when calling execute() on main thread")
        } catch (e: IllegalStateException) {
            assertEquals("Network on main thread", e.message)
        }

        // 2. Call BRouterOnlineEngine.routeLeg from the main thread and verify the interceptor
        // is never triggered (routeLeg switches to Dispatchers.IO internally).
        val engine = BRouterOnlineEngine(
            context = app,
            baseUrl = "http://127.0.0.1:1"
        )

        assertEquals("Caller must be on main looper", Looper.getMainLooper(), Looper.myLooper())
        val result = engine.routeLeg(
            from = LatLng(56.81, 9.85),
            to = LatLng(56.82, 9.86),
            options = RouteOptions()
        )
        assertNotNull(result)
    }

    /**
     * Requirement 4 & 5:
     * Verify BRouter error translation into Danish and profile missing detection.
     */
    @Test
    fun testBRouterDanishErrorTranslationAndMissingProfileDetection() {
        assertEquals(
            "Punktet ligger for langt fra en sti eller vej. Flyt det tættere på en sti.",
            BRouterOnlineEngine.translateBRouterError("from-position not mapped in existing datafile")
        )
        assertEquals(
            "Punktet ligger på en ø eller et område uden forbindelse. Prøv et andet punkt.",
            BRouterOnlineEngine.translateBRouterError("target island detected for section 0")
        )
        assertEquals(
            "Strækningen er for lang til én beregning. Tilføj et punkt imellem.",
            BRouterOnlineEngine.translateBRouterError("operation killed after timeout")
        )
        assertEquals(
            "Ruten kunne ikke beregnes. Prøv igen.",
            BRouterOnlineEngine.translateBRouterError(null)
        )
        assertEquals(
            "Ruten kunne ikke beregnes. Prøv igen.",
            BRouterOnlineEngine.translateBRouterError("null")
        )

        assertTrue(BRouterOnlineEngine.isUnknownProfileError("datafile profiles2/custom_123.brf not found"))
        assertFalse(BRouterOnlineEngine.isUnknownProfileError("from-position not mapped in existing datafile"))
    }

    /**
     * Requirement 9:
     * Access polygons cached per 0.1 x 0.1 degree tile with tileId.
     */
    @Test
    fun testAccessPolygonsTileCalculation() {
        val tiles = AccessLandRepository.getTileIdsForBounds(56.80, 56.95, 9.80, 9.95)
        assertTrue("Expected 0.1x0.1 tiles covering the bounds", tiles.isNotEmpty())
        assertTrue(tiles.all { it.tileId.startsWith("tile_") })
    }

    /**
     * Requirement 14 & 15:
     * - Distance-weighted pathSharePct & roadKm returns null when no wayInfo is present.
     * - calculateSunMetrics returns null on parse failure, never "19:25", and no invented +35 min twilight.
     */
    @Test
    fun testPathShareAndSunMetricsNoFakeFallbacks() {
        val pts = listOf(
            RoutePoint3D(56.80, 9.80, 20.0),
            RoutePoint3D(56.81, 9.80, 25.0)
        )
        val (noWayShare, noWayRoad) = RouteGeometryStorageManager.computeWaySectionMetrics(pts, emptyList())
        assertNull("pathSharePct must be null when there is no way info", noWayShare)
        assertEquals(0.0, noWayRoad, 0.01)

        val (withWayShare, withWayRoad) = RouteGeometryStorageManager.computeWaySectionMetrics(
            pts,
            listOf(WayInfo(highway = "path", surface = "dirt"), WayInfo(highway = "path", surface = "dirt"))
        )
        assertEquals(100.0, withWayShare ?: 0.0, 0.1)
        assertEquals(0.0, withWayRoad, 0.01)

        // Weather sun metrics with invalid / unparseable sunset must return null, never "19:25"
        assertNull(WeatherInterpreter.calculateSunMetrics("invalid", "19:25"))
        val badResponse = OpenMeteoResponse(
            current = null,
            daily = DailyWeatherDto(time = listOf("2026-10-20"), sunset = listOf("invalid"))
        )
        val parsed = WeatherInterpreter.parseWeather(badResponse)
        assertNull("Sunset time must be null on parse failure, never 19:25", parsed.sunset)
        assertNull("Civil twilight must be null when Open-Meteo does not provide it", parsed.twilightEnd)
    }

    /**
     * Requirement 18:
     * Offline downloads use their own SQLite tile archive in filesDir/offline/<id>.sqlite
     * and deleting a download deletes its archive.
     */
    @Test
    fun testOfflineDownloadArchiveFileAndDeletion() = runBlocking {
        val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(app, "trail_rold_test")
        assertEquals(File(File(app.filesDir, "offline"), "trail_rold_test.sqlite").absolutePath, archiveFile.absolutePath)
        archiveFile.parentFile?.mkdirs()
        archiveFile.writeText("dummy sqlite header")
        assertTrue(archiveFile.exists())

        val repo = com.example.data.repository.DanskStiRepository(database.dao(), app)
        repo.deleteDownloadedTrail("trail_rold_test")
        assertFalse("Archive must be deleted when offline download is deleted", archiveFile.exists())
    }

    /**
     * Requirement 19:
     * BRouterStorageManager copies dansk-vandring.brf and lookups.dat whenever content differs (hash comparison),
     * not only when missing.
     */
    @Test
    fun testBRouterStorageManager_OverwritesModifiedAssetFileByHash() {
        val storage = BRouterStorageManager.getInstance(app)
        storage.ensureProfilesCopied(app)

        val profileFile = File(storage.profilesDir, "dansk-vandring.brf")
        assertTrue(profileFile.exists())
        val originalContent = profileFile.readText()

        // Corrupt/modify the file on disk
        profileFile.writeText("# corrupted profile content")
        assertFalse(originalContent == profileFile.readText())

        // Re-running ensureProfilesCopied must detect the hash mismatch and restore the asset
        storage.ensureProfilesCopied(app)
        assertEquals("Disk file must be overwritten when SHA-256 differs from asset", originalContent, profileFile.readText())
    }

    /**
     * Requirement 23:
     * MigrationTestHelper test from Room schema 17 to 20.
     */
    @Test
    fun testRoomMigration17To20() {
        val dbName = "migration_test_17_to_20.db"
        migrationHelper.createDatabase(dbName, 17).apply {
            execSQL(
                """
                INSERT INTO custom_routes (id, name, createdAt, updatedAt, optionsJson, waypointsJson, totalKm, ascentM, descentM, pathSharePct, roadKm, geometryFile, sourceName, fetchedAt)
                VALUES (1, 'Test Rute 17', 1000, 1000, '{}', '[]', 10.5, 50.0, 50.0, 85.0, 1.5, 'test.json', 'Rutebygger', 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO access_polygons (id, name, type, coordinatesJson, minLat, maxLat, minLng, maxLng, source, operatorOrOwner, fetchedAt)
                VALUES ('baseline_old', 'Fake Baseline', 'PRIVATE_WOOD', '[]', 56.8, 56.9, 9.8, 9.9, 'baseline', null, 1000)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            dbName,
            20,
            true,
            DanskStiDatabase.MIGRATION_17_18,
            DanskStiDatabase.MIGRATION_18_19,
            DanskStiDatabase.MIGRATION_19_20
        )

        val cursor = migratedDb.query("SELECT name, totalKm FROM custom_routes WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test Rute 17", cursor.getString(0))
        assertEquals(10.5, cursor.getDouble(1), 0.01)
        cursor.close()

        // Verify baseline_% polygons were deleted by MIGRATION_19_20
        val baselineCursor = migratedDb.query("SELECT id FROM access_polygons WHERE id LIKE 'baseline_%'")
        assertFalse("baseline_% access polygons must be deleted by migration 19->20", baselineCursor.moveToFirst())
        baselineCursor.close()

        // Verify new tileId column on access_polygons works
        migratedDb.execSQL(
            """
            INSERT INTO access_polygons (id, name, type, coordinatesJson, minLat, maxLat, minLng, maxLng, source, operatorOrOwner, fetchedAt, tileId)
            VALUES ('osm_123', 'Rold Skov', 'PUBLIC_WOOD', '[]', 56.8, 56.9, 9.8, 9.9, 'osm', 'Naturstyrelsen', 123456, 'tile_568_98')
            """.trimIndent()
        )
        val tileCursor = migratedDb.query("SELECT tileId FROM access_polygons WHERE id = 'osm_123'")
        assertTrue(tileCursor.moveToFirst())
        assertEquals("tile_568_98", tileCursor.getString(0))
        tileCursor.close()
        migratedDb.close()
    }

    /**
     * Verify full migration chain from schema 17 to current schema 22 (including custom_routes baseTrailId/isReverse
     * and water_features / water_tile_cache tables).
     */
    @Test
    fun testRoomMigration17To22() {
        val dbName = "migration_test_17_to_22.db"
        migrationHelper.createDatabase(dbName, 17).apply {
            execSQL(
                """
                INSERT INTO custom_routes (id, name, createdAt, updatedAt, optionsJson, waypointsJson, totalKm, ascentM, descentM, pathSharePct, roadKm, geometryFile, sourceName, fetchedAt)
                VALUES (2, 'Test Rute 22', 2000, 2000, '{}', '[]', 24.0, 120.0, 110.0, 90.0, 2.4, 'test22.json', 'Rutebygger', 2000)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            dbName,
            22,
            true,
            DanskStiDatabase.MIGRATION_17_18,
            DanskStiDatabase.MIGRATION_18_19,
            DanskStiDatabase.MIGRATION_19_20,
            DanskStiDatabase.MIGRATION_20_21,
            DanskStiDatabase.MIGRATION_21_22
        )

        val cursor = migratedDb.query("SELECT name, totalKm, isReverse FROM custom_routes WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test Rute 22", cursor.getString(0))
        assertEquals(24.0, cursor.getDouble(1), 0.01)
        assertEquals(0, cursor.getInt(2))
        cursor.close()

        migratedDb.execSQL(
            """
            INSERT INTO water_features (id, osmId, name, featureType, subType, coordinatesJson, minLat, maxLat, minLng, maxLng, tileId, fetchedAt)
            VALUES ('water_1', 999, 'Gudenåen', 'WATERWAY_LINE', 'river', '[]', 56.0, 56.1, 9.5, 9.6, 'tile_560_95', 3000)
            """.trimIndent()
        )
        val waterCursor = migratedDb.query("SELECT name, featureType FROM water_features WHERE id = 'water_1'")
        assertTrue(waterCursor.moveToFirst())
        assertEquals("Gudenåen", waterCursor.getString(0))
        assertEquals("WATERWAY_LINE", waterCursor.getString(1))
        waterCursor.close()
        migratedDb.close()
    }
}
