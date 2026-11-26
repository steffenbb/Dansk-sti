package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Vibrator
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.model.TurnCue
import com.example.data.remote.HourlyForecastPoint
import com.example.data.remote.WeatherInterpreter
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.TrailPhotoRepository
import com.example.service.NavigationLiveState
import com.example.service.NavigationTrackingService
import com.example.ui.components.PackingListFormatter
import com.example.ui.components.PoiInfoSheet
import com.example.ui.components.PoiInfoSheetData
import com.example.ui.components.TripDiaryPhotoHelper
import com.example.ui.components.TripDiaryPhotoParser
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.AppLocaleHelper
import com.example.util.DanskStiTileSourceHelper
import com.example.util.GeoUtils
import com.example.util.HikingStatisticsCalculator
import com.example.util.OfflineDownloadManager
import com.example.util.SeasonalWaterEvaluator
import com.example.util.StagePlanner
import com.example.widget.ActiveTripAppWidgetProvider
import com.example.widget.ActiveTripWidgetDisplayData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.util.MapTileIndex
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "da")
class Round21VerificationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DanskStiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private lateinit var context: Context
    private lateinit var db: DanskStiDatabase
    private lateinit var dao: DanskStiDao
    private lateinit var repository: DanskStiRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        repository = DanskStiRepository(dao, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // PART A — Correct information
    // =========================================================================

    @Test
    fun partA1_migration27To28_nullifiesDefaultedShelterAndFriTeltningColumnsAndPreservesCoreFields() {
        val dbFile = File(context.cacheDir, "test_mig_27_28_${System.nanoTime()}.db")
        val callback = object : SupportSQLiteOpenHelper.Callback(27) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `shelters` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL,
                        `description` TEXT, `bookingRequired` INTEGER NOT NULL, `ownership` TEXT,
                        `capacity` INTEGER, `hasWaterNearby` INTEGER NOT NULL, `hasToiletNearby` INTEGER NOT NULL,
                        `source` TEXT NOT NULL, `trailCorridorId` TEXT, `bookingUrl` TEXT,
                        `hasFireRing` INTEGER NOT NULL, `hasFirewood` INTEGER NOT NULL, `fee` INTEGER NOT NULL,
                        `priceDkk` INTEGER NOT NULL, `maxNights` INTEGER NOT NULL, `accessibility` TEXT NOT NULL,
                        `shelterType` TEXT NOT NULL, `hasElectricity` INTEGER NOT NULL, `dogAllowed` INTEGER NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fri_teltning` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `centerLat` REAL NOT NULL, `centerLng` REAL NOT NULL,
                        `polygonJson` TEXT NOT NULL, `rulesSummary` TEXT, `rulesUrl` TEXT NOT NULL,
                        `areaHa` REAL NOT NULL, `trailCorridorId` TEXT, `forestType` TEXT NOT NULL,
                        `maxTents` INTEGER NOT NULL, `maxPersonsPerTent` INTEGER NOT NULL,
                        `openFireAllowed` INTEGER NOT NULL, `waterDistanceMeters` INTEGER NOT NULL,
                        `toiletDistanceMeters` INTEGER NOT NULL, `zoneType` TEXT NOT NULL,
                        `hammockFriendly` INTEGER NOT NULL, `source` TEXT NOT NULL,
                        `cvrNavn` TEXT, `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pois` (
                        `id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `lat` REAL NOT NULL, `lng` REAL NOT NULL, `access` TEXT,
                        `fee` INTEGER NOT NULL, `trailCorridorId` TEXT, `openingHours` TEXT,
                        `operator` TEXT, `website` TEXT, `source` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trails` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL,
                        `region` TEXT NOT NULL, `parentRegion` TEXT NOT NULL, `osmRelationId` INTEGER,
                        `computedWalkingKm` REAL NOT NULL, `gapsOver200m` INTEGER NOT NULL,
                        `osmTimestamp` TEXT, `bboxNorth` REAL, `bboxSouth` REAL, `bboxEast` REAL, `bboxWest` REAL,
                        `isGeometryDownloaded` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL, `source` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloaded_trails` (
                        `trailId` TEXT NOT NULL, `trailName` TEXT NOT NULL, `downloadedAt` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL, `tileCount` INTEGER NOT NULL, `corridorKm` REAL NOT NULL,
                        `minZoom` INTEGER NOT NULL, `maxZoom` INTEGER NOT NULL,
                        PRIMARY KEY(`trailId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_downloads_in_progress` (
                        `trailId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trailId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trip_gear_items` (
                        `id` TEXT NOT NULL, `planId` INTEGER NOT NULL, `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL, `isPacked` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(callback)
                .build()
        )
        val sqliteDb = helper.writableDatabase

        // Insert a v27 GeoFA shelter with old hardcoded non-null defaults and real id/name/lat/lng/hasWaterNearby
        sqliteDb.execSQL(
            """
            INSERT INTO `shelters` (
                `id`, `name`, `lat`, `lng`, `description`, `bookingRequired`, `ownership`, `capacity`,
                `hasWaterNearby`, `hasToiletNearby`, `source`, `trailCorridorId`, `bookingUrl`,
                `hasFireRing`, `hasFirewood`, `fee`, `priceDkk`, `maxNights`, `accessibility`,
                `shelterType`, `hasElectricity`, `dogAllowed`, `fetchedAt`
            ) VALUES (
                'geofa_101', 'Skovbryn Shelter', 56.1234, 9.5678, 'Flot shelter i skoven', 1, 'Naturstyrelsen', 6,
                1, 0, 'geofa', 'trail_haervejen', 'https://example.dk/info',
                1, 0, 0, 0, 2, 'Standard',
                'Klassisk træshelter', 0, 1, 1700000000000
            )
            """.trimIndent()
        )
        // Insert a v27 fri_teltning row with old defaults
        sqliteDb.execSQL(
            """
            INSERT INTO `fri_teltning` (
                `id`, `name`, `centerLat`, `centerLng`, `polygonJson`, `rulesSummary`, `rulesUrl`,
                `areaHa`, `trailCorridorId`, `forestType`, `maxTents`, `maxPersonsPerTent`,
                `openFireAllowed`, `waterDistanceMeters`, `toiletDistanceMeters`, `zoneType`,
                `hammockFriendly`, `source`, `cvrNavn`, `fetchedAt`
            ) VALUES (
                'nst_ft_1', 'Rold Skov Fri Teltning', 56.8123, 9.8456, '[[56.81,9.84],[56.82,9.85],[56.81,9.85]]',
                '1-2-3 reglen', 'https://naturstyrelsen.dk', 45.0, 'trail_haervejen', 'Blandingsskov', 2, 3,
                0, 400, 600, 'Fri teltningsskov (1-2-3 reglen)', 1, 'geofa', 'Naturstyrelsen', 1700000000000
            )
            """.trimIndent()
        )

        // Run MIGRATION_27_28
        DanskStiDatabase.MIGRATION_27_28.migrate(sqliteDb)

        // Verify shelter row preserved id, name, lat, lng, capacity, ownership, hasWaterNearby, trailCorridorId
        // and nullified hasFireRing, hasFirewood, dogAllowed, hasElectricity, fee, maxNights, accessibility
        sqliteDb.query(
            "SELECT id, name, lat, lng, capacity, ownership, trailCorridorId, hasWaterNearby, " +
                "hasFireRing, hasFirewood, dogAllowed, hasElectricity, fee, maxNights, accessibility, infoUrl " +
                "FROM shelters WHERE id = 'geofa_101'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("geofa_101", c.getString(0))
            assertEquals("Skovbryn Shelter", c.getString(1))
            assertEquals(56.1234, c.getDouble(2), 1e-4)
            assertEquals(9.5678, c.getDouble(3), 1e-4)
            assertEquals(6, c.getInt(4))
            assertEquals("Naturstyrelsen", c.getString(5))
            assertEquals("trail_haervejen", c.getString(6))
            assertEquals(1, c.getInt(7))
            for (colIdx in 8..15) {
                assertTrue("Column $colIdx should be NULL after MIGRATION_27_28", c.isNull(colIdx))
            }
        }

        // Verify fri_teltning row preserved id, name, centerLat, centerLng, polygonJson
        // and nullified waterDistanceMeters, toiletDistanceMeters, forestType, hammockFriendly, openFireAllowed
        sqliteDb.query(
            "SELECT id, name, centerLat, centerLng, polygonJson, " +
                "waterDistanceMeters, toiletDistanceMeters, forestType, hammockFriendly, openFireAllowed " +
                "FROM fri_teltning WHERE id = 'nst_ft_1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("nst_ft_1", c.getString(0))
            assertEquals("Rold Skov Fri Teltning", c.getString(1))
            assertEquals(56.8123, c.getDouble(2), 1e-4)
            assertEquals(9.8456, c.getDouble(3), 1e-4)
            assertTrue(c.getString(4).contains("56.81"))
            for (colIdx in 5..9) {
                assertTrue("FriTeltning column $colIdx should be NULL after MIGRATION_27_28", c.isNull(colIdx))
            }
        }

        // Verify new v28 tables exist
        for (tableName in listOf("trip_diary_entries", "weather_cache")) {
            sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'").use { c ->
                assertTrue("Table $tableName must exist after MIGRATION_27_28", c.moveToFirst())
            }
        }

        sqliteDb.close()
        helper.close()
        dbFile.delete()
    }

    @Test
    fun partA1_geofaBookingRefreshOnExistingInstalls_clearsPlainLinkBookingAndPreservesWaterAndCorridor() = runBlocking {
        val parsedGeofa = repository.geoFaRepository.loadBundledShelters()
        val plainLinkSample = parsedGeofa.firstOrNull { !it.bookingRequired && !it.infoUrl.isNullOrBlank() }
        assertNotNull("Bundled GeoFA asset should contain at least one shelter with infoUrl and bookingRequired=false", plainLinkSample)
        val target = plainLinkSample!!

        // Simulate a v27 row on an existing install where bookingRequired was wrongly set to true and bookingUrl set from link1
        val legacyV27Row = target.copy(
            bookingRequired = true,
            bookingUrl = target.infoUrl,
            infoUrl = null,
            hasWaterNearby = true,
            hasToiletNearby = true,
            trailCorridorId = "trail_haervejen"
        )
        dao.insertShelters(listOf(legacyV27Row))

        // Run backfill
        repository.runGeoFaBookingBackfillIfNeeded(force = true)

        val updated = dao.getAllSheltersList().first { it.id == target.id }
        assertFalse("bookingRequired must be updated to false", updated.bookingRequired)
        assertNull("bookingUrl must be cleared to null", updated.bookingUrl)
        assertEquals("infoUrl must be populated from link/link1", target.infoUrl, updated.infoUrl)
        assertTrue("hasWaterNearby must remain unchanged", updated.hasWaterNearby)
        assertTrue("hasToiletNearby must remain unchanged", updated.hasToiletNearby)
        assertEquals("trailCorridorId must remain unchanged", "trail_haervejen", updated.trailCorridorId)
    }

    @Test
    fun partA1_geofaAndOsmShelterDeduplication_mergesMissingGeofaFacilitiesFromMatchedOsmShelter() {
        val osmJson = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 777001,
                  "lat": 56.1000,
                  "lon": 9.5000,
                  "tags": {
                    "amenity": "shelter",
                    "shelter_type": "lean_to",
                    "name": "OSM Skovshelter",
                    "fireplace": "yes",
                    "dog": "leashed",
                    "fee": "no"
                  }
                }
              ]
            }
        """.trimIndent()
        val parsedResult = repository.osmPoiRepository.parseOverpassJson(osmJson)
        val osmShelter = parsedResult.shelters.single()
        assertEquals(true, osmShelter.hasFireRing)
        assertEquals(true, osmShelter.dogAllowed)
        assertEquals(false, osmShelter.fee)

        val geofaShelter = ShelterEntity(
            id = "geofa_merge_test",
            name = "GeoFA Skovshelter",
            lat = 56.10005,
            lng = 9.50005,
            description = "GeoFA beskrivelse",
            bookingRequired = false,
            ownership = "Naturstyrelsen",
            capacity = 5,
            hasWaterNearby = false,
            hasToiletNearby = false,
            source = "geofa",
            trailCorridorId = null,
            bookingUrl = null,
            hasFireRing = null,
            hasFirewood = null,
            fee = null,
            priceDkk = 0,
            maxNights = null,
            accessibility = null,
            shelterType = "Shelter",
            hasElectricity = null,
            dogAllowed = null,
            infoUrl = "https://udinaturen.dk/info/1"
        )

        val merged = repository.mergeGeoFaAndOsmShelter(geofaShelter, osmShelter)
        assertEquals("Null GeoFA hasFireRing must be filled from OSM fireplace=yes", true, merged.hasFireRing)
        assertEquals("Null GeoFA dogAllowed must be filled from OSM dog=leashed", true, merged.dogAllowed)
        assertEquals("Null GeoFA fee must be filled from OSM fee=no", false, merged.fee)
        assertEquals("Non-null GeoFA infoUrl must not be overwritten", "https://udinaturen.dk/info/1", merged.infoUrl)
    }

    @Test
    fun partA1_poiInfoSheet_showsOnlyKnownTrueFacilitiesAndNeverGratisUnlessFeeIsFalse() {
        val unknownShelter = ShelterEntity(
            id = "sh_unknown",
            name = "Ukendt Shelter",
            lat = 56.0,
            lng = 9.5,
            description = "",
            bookingRequired = false,
            ownership = "Ukendt",
            capacity = null,
            hasWaterNearby = false,
            hasToiletNearby = false,
            source = "geofa",
            trailCorridorId = null,
            bookingUrl = null,
            hasFireRing = null,
            hasFirewood = null,
            fee = null,
            priceDkk = 0,
            maxNights = null,
            accessibility = null,
            shelterType = "Shelter",
            hasElectricity = null,
            dogAllowed = null,
            infoUrl = "https://naturstyrelsen.dk/shelter-info"
        )

        val sheetData = PoiInfoSheetData.fromShelter(unknownShelter, context)
        assertEquals(listOf("Faciliteter ikke oplyst"), sheetData.facilities)
        assertFalse(sheetData.facilities.any { it.contains("Gratis", ignoreCase = true) })

        composeTestRule.setContent {
            DanskStiTheme {
                PoiInfoSheet(
                    poiData = sheetData,
                    liveUserLocation = null,
                    onDismiss = {},
                    onRouteHere = { _, _, _ -> },
                    onAddToRoute = { _, _, _ -> }
                )
            }
        }

        val expectedMoreInfoText = context.getString(R.string.poi_sheet_more_info)
        composeTestRule.onNodeWithText("Faciliteter ikke oplyst", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText(expectedMoreInfoText, substring = true, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Gratis", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun partA2_friTeltning_noWaterPoiWithin500m_isNotMarkedAsHavingWaterInSuggestedOvernights() {
        val plannedRoute = PlannedRoute(
            planId = 1L,
            trailId = "trail_test",
            trailName = "Test Sti",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 20.0,
            totalWalkKm = 20.0,
            points = listOf(
                RoutePoint(lat = 56.0000, lng = 9.0000, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 56.0000, lng = 9.1500, walkKm = 10.0, trailKm = 10.0),
                RoutePoint(lat = 56.0000, lng = 9.3000, walkKm = 20.0, trailKm = 20.0)
            ),
            stages = emptyList()
        )
        val friTeltningArea = FriTeltningEntity(
            id = "ft_no_water",
            name = "Tør Skov",
            centerLat = 56.0005,
            centerLng = 9.1500,
            polygonJson = "[{\"lat\":56.000,\"lng\":9.149},{\"lat\":56.001,\"lng\":9.151},{\"lat\":56.000,\"lng\":9.151}]",
            rulesSummary = "1-2-3 reglen",
            waterDistanceMeters = null,
            toiletDistanceMeters = null,
            forestType = null,
            hammockFriendly = null,
            openFireAllowed = null,
            source = "geofa"
        )

        val candidates = StagePlanner.extractCandidates(
            plannedRoute = plannedRoute,
            shelters = emptyList(),
            friTeltnings = listOf(friTeltningArea),
            waterPois = emptyList(),
            toiletPois = emptyList(),
            overnightType = OvernightType.FRI_TELTNING,
            maxDetourKm = 2.0
        )
        val ftCandidate = candidates.firstOrNull { it.id == "ft_no_water" }
        assertNotNull("Fri teltning area along route should be considered", ftCandidate)
        assertFalse("Fri teltning area with no water POI within 500 m must NOT be marked as having water", ftCandidate!!.hasWaterNearby)
        assertFalse("Fri teltning area with no toilet POI within 500 m must NOT be marked as having toilet", ftCandidate.hasToiletNearby)
    }

    @Test
    fun partA3_estimateDays_usesCeilKmDivDailyTargetMin1AndUpdatesWithTarget() {
        assertEquals(1, GeoUtils.estimateDays(0.0, 20.0))
        assertEquals(1, GeoUtils.estimateDays(12.0, 20.0))
        assertEquals(2, GeoUtils.estimateDays(20.1, 20.0))
        assertEquals(15, GeoUtils.estimateDays(220.0, 15.0))
        assertEquals(15, StagePlanner.estimateDays(220.0, 15.0))
        assertEquals(2, GeoUtils.estimateDays(35.0, 20.0))
        assertEquals(3, GeoUtils.estimateDays(35.0, 15.0))
    }

    @Test
    fun partA5_formatOsmTimestampMonthYear_formatsKnownTimestampAndReturnsNullWhenUnknown() {
        val formatted = GeoUtils.formatOsmTimestampMonthYear("2025-10-14T12:00:00Z", Locale.forLanguageTag("da-DK"))
        assertNotNull(formatted)
        assertTrue("Formatted date should contain year 2025 (actual=$formatted)", formatted!!.contains("2025"))
        assertNull(GeoUtils.formatOsmTimestampMonthYear(null))
        assertNull(GeoUtils.formatOsmTimestampMonthYear(""))
    }

    // =========================================================================
    // PART B — Fixes & polish
    // =========================================================================

    @Test
    fun partB3_trailPhotoRepository_usesAppUserAgentOnCommonsRequests() = runBlocking {
        val capturedUserAgents = mutableListOf<String>()
        val capturedUrls = mutableListOf<String>()
        val repo = TrailPhotoRepository(context)
        val sampleJson = """
            {
              "query": {
                "pages": {
                  "100": {
                    "title": "File:Haervejen.jpg",
                    "imageinfo": [
                      {
                        "url": "https://upload.wikimedia.org/orig.jpg",
                        "thumburl": "https://upload.wikimedia.org/thumb.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Haervejen.jpg",
                        "extmetadata": {
                          "Artist": { "value": "Hans Jensen" },
                          "LicenseShortName": { "value": "CC BY-SA 4.0" }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val photos = repo.fetchCommonsPhotosForTitles(
            fileTitles = listOf("File:Haervejen.jpg"),
            connectionFactory = { url: URL ->
                capturedUrls.add(url.toString())
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun setRequestProperty(key: String?, value: String?) {
                        super.setRequestProperty(key, value)
                        if (key.equals("User-Agent", ignoreCase = true) && value != null) {
                            capturedUserAgents.add(value)
                        }
                    }
                    override fun getResponseCode(): Int = 200
                    override fun getInputStream() = ByteArrayInputStream(sampleJson.toByteArray(Charsets.UTF_8))
                }
            }
        )
        assertEquals(1, photos.size)
        assertEquals("Hans Jensen", photos[0].author)
        assertEquals("CC BY-SA 4.0", photos[0].license)
        assertTrue(
            "Commons request must use NetworkConstants.USER_AGENT",
            capturedUserAgents.isNotEmpty() && capturedUserAgents.all { it == com.example.util.network.NetworkConstants.USER_AGENT }
        )
        assertTrue("Commons URL must use titles= and never generator=search", capturedUrls.single().contains("titles=") && !capturedUrls.single().contains("generator=search"))
    }

    @Test
    fun partB4_turnCueAndOffRouteVibrationPatterns_areDistinctFromEachOther() {
        val left = NavigationTrackingService.VIBRATION_PATTERN_LEFT
        val right = NavigationTrackingService.VIBRATION_PATTERN_RIGHT
        val other = NavigationTrackingService.VIBRATION_PATTERN_OTHER
        val offRoute = NavigationTrackingService.VIBRATION_PATTERN_OFF_ROUTE

        assertArrayEquals(longArrayOf(0L, 150L, 120L, 150L), left)
        assertArrayEquals(longArrayOf(0L, 150L, 120L, 150L, 120L, 150L), right)
        assertArrayEquals(longArrayOf(0L, 400L), other)
        assertFalse("Left and right patterns must differ", left.contentEquals(right))
        assertFalse("Left and other patterns must differ", left.contentEquals(other))
        assertFalse("Right and other patterns must differ", right.contentEquals(other))
        assertFalse("Off-route and left patterns must differ", offRoute.contentEquals(left))
        assertFalse("Off-route and right patterns must differ", offRoute.contentEquals(right))
    }

    // =========================================================================
    // PART C — Offline maps for routes, plans and areas
    // =========================================================================

    @Test
    fun partC1ToC4_offlineTileArchive_sharedAcrossLightAndDarkModes_andDoesNotServeTopoTileForAerial() {
        val archiveFile = File(context.cacheDir, "test_c4_archive_${System.nanoTime()}.sqlite")
        try {
            val tileIndex = MapTileIndex.getTileIndex(12, 2158, 1275)
            val fakePngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

            val lightTopoSource = DanskStiTileSourceHelper.skaermkortInstance
            val darkTopoSource = DanskStiTileSourceHelper.skaermkortDarkInstance
            val aerialSource = DanskStiTileSourceHelper.luftfotoInstance

            assertEquals(
                "Light and dark Skaermkort sources must share the exact same tileSourceName",
                lightTopoSource.name(),
                darkTopoSource.name()
            )

            val writer = org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter(archiveFile.absolutePath)
            writer.saveFile(lightTopoSource, tileIndex, ByteArrayInputStream(fakePngBytes), null)
            writer.onDetach()

            val archive = DatabaseFileArchive.getDatabaseFileArchive(archiveFile)
            archive.setIgnoreTileSource(false)

            // (a) Cache hit for both isDarkMode = false and isDarkMode = true
            val lightStream = archive.getInputStream(lightTopoSource, tileIndex)
            assertNotNull("Tile must be a cache hit in light mode (isDarkMode = false)", lightStream)
            assertArrayEquals(fakePngBytes, lightStream!!.readBytes())
            lightStream.close()

            val darkStream = archive.getInputStream(darkTopoSource, tileIndex)
            assertNotNull("Tile must be a cache hit in dark mode (isDarkMode = true)", darkStream)
            assertArrayEquals(fakePngBytes, darkStream!!.readBytes())
            darkStream.close()

            // (b) Archive containing only a dtk_skaermkort tile returns null when orto_foraar_DAF asks for the same tile index
            val aerialStream = archive.getInputStream(aerialSource, tileIndex)
            assertNull("Archive with only dtk_skaermkort tile must return null for orto_foraar_DAF", aerialStream)

            archive.close()
        } finally {
            archiveFile.delete()
        }
    }

    @Test
    fun partC2AndC3_redownloadOfflineCorridor_prunesStaleTiles_sanitizesFileNames_andCapsAreaAt15000Tiles() = runBlocking {
        assertEquals("route_12.sqlite", OfflineDownloadManager.getOfflineArchiveFile(context, "route:12").name)
        assertEquals("plan_5.sqlite", OfflineDownloadManager.getOfflineArchiveFile(context, "plan:5").name)
        assertEquals("area_abc-123.sqlite", OfflineDownloadManager.getOfflineArchiveFile(context, "area:abc-123").name)

        // C3: Verify 15,000-tile cap on area downloads
        val hugeEstimate = OfflineDownloadManager.estimateAreaDownload(
            minLat = 54.5,
            maxLat = 57.8,
            minLng = 8.0,
            maxLng = 12.8,
            minZoom = 8,
            maxZoom = 16
        )
        assertTrue("Full Denmark bbox should exceed 15,000 tiles", hugeEstimate.tileCount > OfflineDownloadManager.MAX_AREA_DOWNLOAD_TILES)
        assertFalse("Area > 15,000 tiles must not be allowed", hugeEstimate.isAllowed)
        assertEquals("Området er for stort – zoom ind på kortet", hugeEstimate.refuseReason)

        // Small area within 15,000 tiles
        val smallEstimate = OfflineDownloadManager.estimateAreaDownload(
            minLat = 56.10,
            maxLat = 56.14,
            minLng = 9.50,
            maxLng = 9.56,
            minZoom = 8,
            maxZoom = 16
        )
        assertTrue(smallEstimate.tileCount in 1..OfflineDownloadManager.MAX_AREA_DOWNLOAD_TILES)
        assertTrue(smallEstimate.isAllowed)

        // C2: Re-download removes tiles no longer in the new corridor
        val archiveFile = File(context.cacheDir, "offline_route_99_${System.nanoTime()}.sqlite")
        try {
            val topoSource = DanskStiTileSourceHelper.skaermkortInstance
            val oldTile = MapTileIndex.getTileIndex(10, 539, 318)
            val newTile = MapTileIndex.getTileIndex(10, 540, 319)
            val bytes = byteArrayOf(1, 2, 3, 4)

            val writer = org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter(archiveFile.absolutePath)
            writer.saveFile(topoSource, oldTile, ByteArrayInputStream(bytes), null)
            writer.saveFile(topoSource, newTile, ByteArrayInputStream(bytes), null)
            writer.onDetach()

            val deletedCount = OfflineDownloadManager.pruneArchiveTilesNotInCorridor(
                archiveFile = archiveFile,
                allowedTileIndices = listOf(newTile)
            )
            assertEquals(1, deletedCount)

            val verifyArchive = DatabaseFileArchive.getDatabaseFileArchive(archiveFile)
            verifyArchive.setIgnoreTileSource(false)
            assertNull("Stale tile from old corridor must be removed on re-download", verifyArchive.getInputStream(topoSource, oldTile))
            val keptStream = verifyArchive.getInputStream(topoSource, newTile)
            assertNotNull("Tile in new corridor must be kept", keptStream)
            keptStream?.close()
            verifyArchive.close()
        } finally {
            archiveFile.delete()
        }
    }

    // =========================================================================
    // PART D — Trip tools (packing list, diary, statistics, emergency sharing)
    // =========================================================================

    @Test
    fun partD1_packingList_seedsDefaultItems_supportsCheckAddDeleteCopyAndSummary() = runBlocking {
        repository.ensureDefaultGearItemsForPlan(planId = 42L, overnightTypeStr = "TENT")
        val seeded = dao.getGearItemsForPlanSync(42L)
        assertTrue("Default packing template should seed items", seeded.size >= 10)
        assertEquals(0, seeded.count { it.isPacked })

        // Check 2 items with weights
        repository.updateGearItem(seeded[0].copy(isPacked = true, weightGrams = 900))
        repository.updateGearItem(seeded[1].copy(isPacked = true, weightGrams = 600))

        // Add custom item
        val customItem = repository.addGearItem(
            planId = 42L,
            title = "Termokande",
            category = "Vand & Mad",
            weightGrams = 350
        )
        repository.updateGearItem(customItem.copy(isPacked = true))

        val afterAdd = dao.getGearItemsForPlanSync(42L)
        assertEquals(seeded.size + 1, afterAdd.size)
        assertEquals(3, afterAdd.count { it.isPacked })
        assertEquals(1850, afterAdd.filter { it.isPacked }.sumOf { it.weightGrams ?: 0 })

        val summary = PackingListFormatter.formatSummary(context, afterAdd, Locale("da", "DK"))
        assertTrue("Summary must contain packed count and weight in kg (actual=$summary)", summary.contains("1,9 kg") || summary.contains("1,8 kg"))

        // Copy to another plan
        repository.copyGearItemsFromPlan(sourcePlanId = 42L, targetPlanId = 99L)
        val copied = dao.getGearItemsForPlanSync(99L)
        assertEquals(afterAdd.size, copied.size)
        assertTrue(copied.any { it.title == "Termokande" && it.weightGrams == 350 })

        // Delete custom item
        repository.deleteGearItem(customItem.id)
        assertEquals(seeded.size, dao.getGearItemsForPlanSync(42L).size)
    }

    @Test
    fun partD2_tripDiary_persistsNotesAndPhotosPerDay_andBuildsShareSummaryText() = runBlocking {
        val samplePhotos = listOf(
            "/data/user/0/com.example/files/photos/day1_a.jpg",
            "/data/user/0/com.example/files/photos/day1_b.jpg"
        )
        repository.saveTripDiaryEntry(
            planId = 7L,
            dayNumber = 1,
            noteText = "Flot solopgang over heden.",
            photoPaths = samplePhotos
        )
        val saved = repository.getDiaryEntriesForPlanSync(7L)
        assertEquals(1, saved.size)
        assertEquals("Flot solopgang over heden.", saved[0].noteText)
        assertEquals(2, TripDiaryPhotoParser.parsePhotoPaths(saved[0].photoPathsJson).size)

        val shareSummary = TripDiaryPhotoHelper.buildTripShareSummaryText(
            trailName = "Hærvejen",
            totalDistanceKm = 42.5,
            totalMovingTimeFormatted = "10t 30m",
            totalAscentMeters = 310,
            totalDays = 2,
            diaryEntries = saved,
            locale = Locale("da", "DK")
        )
        assertTrue(shareSummary.contains("Hærvejen"))
        assertTrue(shareSummary.contains("### Dag 1"))
        assertTrue(shareSummary.contains("Flot solopgang over heden."))
        assertTrue(shareSummary.contains("(2 fotos)"))
    }

    @Test
    fun partD3_myStatistics_computesTotalKmHikingDaysTotalAscentAndCompletedTrailsFromLocalData() {
        val day1Ts = 1715000000000L
        val day2Ts = day1Ts + 86_400_000L
        val dayStats = listOf(
            RecordedDayStatEntity(
                planId = 10L,
                dayNumber = 1,
                distanceKm = 18.5,
                movingTimeSeconds = 16_000L,
                ascentMeters = 210,
                pointCount = 120,
                lastTimestamp = day1Ts
            ),
            RecordedDayStatEntity(
                planId = 10L,
                dayNumber = 2,
                distanceKm = 21.0,
                movingTimeSeconds = 18_000L,
                ascentMeters = 240,
                pointCount = 140,
                lastTimestamp = day2Ts
            )
        )
        val completedPlans = listOf(
            TripPlanEntity(
                id = 10L,
                trailId = "trail_1",
                trailName = "Hærvejen",
                startKm = 0.0,
                endKm = 40.0,
                isReverse = false,
                totalDistanceKm = 40.0,
                targetDays = 2,
                overnightType = "SHELTER",
                preferredDailyKm = 20.0,
                stagesJson = "[]",
                createdAt = day1Ts
            )
        )
        val progress = listOf(
            TripProgressEntity(
                planId = 10L,
                currentDay = 2,
                distanceWalkedTodayKm = 21.0,
                totalDistanceWalkedKm = 39.5,
                currentSnappedLat = 56.2,
                currentSnappedLng = 9.5,
                remainingDistanceTripKm = 0.0,
                remainingDistanceTodayKm = 0.0,
                isPaused = false,
                isCompleted = true,
                lastActiveTimestamp = day2Ts
            )
        )

        val stats = HikingStatisticsCalculator.calculate(
            dayStats = dayStats,
            trackPoints = emptyList<RecordedTrackPointEntity>(),
            plans = completedPlans,
            progressList = progress,
            trails = emptyList()
        )
        assertEquals(39.5, stats.totalKmWalked, 0.01)
        assertEquals(2, stats.hikingDaysCount)
        assertEquals(450, stats.totalAscentMeters)
        assertEquals(1, stats.completedTrails.size)
        assertEquals("Hærvejen", stats.completedTrails[0].trailName)
    }

    // =========================================================================
    // PART E — Weather & seasonal water
    // =========================================================================

    @Test
    fun partE1_weatherRainAlertAndNavigationRefreshThrottle_respects60MinAnd5KmAnd1MmThreshold() {
        val cphZone = java.time.ZoneId.of("Europe/Copenhagen")
        val now1219Ms = java.time.LocalDateTime.parse("2026-07-10T12:19:00").atZone(cphZone).toInstant().toEpochMilli()
        val hourlyPoints = listOf(
            HourlyForecastPoint(
                isoTime = "2026-07-10T12:00",
                hourLabel = "12:00",
                tempC = 18.0,
                precipitationProbabilityPct = 80,
                precipitationMm = 0.4,
                windSpeedMs = 5.0,
                windGustsMs = 8.0,
                weatherCode = 61,
                weatherIcon = "🌧️",
                conditionDescription = "Regn"
            ),
            HourlyForecastPoint(
                isoTime = "2026-07-10T13:00",
                hourLabel = "13:00",
                tempC = 17.5,
                precipitationProbabilityPct = 90,
                precipitationMm = 1.4,
                windSpeedMs = 6.0,
                windGustsMs = 10.0,
                weatherCode = 63,
                weatherIcon = "🌧️",
                conditionDescription = "Regn"
            )
        )

        val rainPoint = WeatherInterpreter.shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = 0L,
            nowMs = now1219Ms,
            hourlyPoints = hourlyPoints
        )
        assertNotNull("1.4 mm/h starting within 60 min must trigger rain alert", rainPoint)
        assertEquals("13:00", rainPoint!!.hourLabel)

        // Throttled within 3 hours
        val throttled = WeatherInterpreter.shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = now1219Ms,
            nowMs = now1219Ms + 30 * 60_000L,
            hourlyPoints = hourlyPoints
        )
        assertNull("Rain alert must not re-fire within 3 hours", throttled)

        // Navigation forecast refresh throttle: at most once per 60 min or after moving 5 km
        assertFalse(
            "Should use weather_cache when < 60 min and < 5 km moved",
            WeatherInterpreter.shouldRefreshNavigationWeather(
                lastFetchTimeMs = 1_000_000L,
                lastFetchLat = 56.0000,
                lastFetchLng = 9.0000,
                nowMs = 1_000_000L + 30 * 60_000L,
                currentLat = 56.0100,
                currentLng = 9.0100
            )
        )
        assertTrue(
            "Should refresh after moving >= 5 km",
            WeatherInterpreter.shouldRefreshNavigationWeather(
                lastFetchTimeMs = 1_000_000L,
                lastFetchLat = 56.0000,
                lastFetchLng = 9.0000,
                nowMs = 1_000_000L + 10 * 60_000L,
                currentLat = 56.0600,
                currentLng = 9.0000
            )
        )
    }

    @Test
    fun partE2_seasonalWaterWarning_flagsClosedWaterInWinterAndExcludesClosedWaterFromLongestWaterGap() {
        val seasonalTap = PoiEntity(
            id = "w_seasonal",
            type = "water",
            name = "Sommervandpost",
            lat = 56.0,
            lng = 9.1,
            openingHours = "Apr-Oct",
            seasonal = "summer",
            source = "osm"
        )
        val yearRoundTap = PoiEntity(
            id = "w_year_round",
            type = "water",
            name = "Helårsvandpost",
            lat = 56.0,
            lng = 9.2,
            openingHours = "24/7",
            seasonal = "no",
            source = "osm"
        )

        // January (month 1) vs July (month 7)
        assertTrue(
            "Apr-Oct tap must be flagged as potentially closed in January",
            SeasonalWaterEvaluator.isWaterPoiPotentiallyClosedForMonth(
                seasonal = seasonalTap.seasonal,
                openingHours = seasonalTap.openingHours,
                month1To12 = 1
            )
        )
        assertFalse(
            "Apr-Oct tap must be open in July",
            SeasonalWaterEvaluator.isWaterPoiPotentiallyClosedForMonth(
                seasonal = seasonalTap.seasonal,
                openingHours = seasonalTap.openingHours,
                month1To12 = 7
            )
        )
        assertFalse(
            "24/7 tap must be open in January",
            SeasonalWaterEvaluator.isWaterPoiPotentiallyClosedForMonth(
                seasonal = yearRoundTap.seasonal,
                openingHours = yearRoundTap.openingHours,
                month1To12 = 1
            )
        )

        // Longest water gap on a 20 km route with seasonal tap at 10 km:
        // In July (2026-07-15T12:00:00Z = 1784116800000L): gap is 10 km
        // In January (2026-01-15T12:00:00Z = 1768478400000L): seasonal tap excluded -> gap is 20 km
        val julyGap = SeasonalWaterEvaluator.computeLongestWaterGapKm(
            totalWalkKm = 20.0,
            waterPoisWithRouteKm = listOf(seasonalTap to 10.0),
            tripEpochMillis = 1784116800000L
        )
        val janGap = SeasonalWaterEvaluator.computeLongestWaterGapKm(
            totalWalkKm = 20.0,
            waterPoisWithRouteKm = listOf(seasonalTap to 10.0),
            tripEpochMillis = 1768478400000L
        )
        assertEquals(10.0, julyGap, 0.01)
        assertEquals(20.0, janGap, 0.01)
    }

    // =========================================================================
    // PART F — Trail catalogue & discovery
    // =========================================================================

    @Test
    fun partF3AndF4_catalogueDiscovery_nearestTransportWithin3Km_andShortWalksNearYouFilter() {
        val shortNearTrail = TrailEntity(
            id = "trail_short_1",
            name = "Mols Bjerge Kort Rute",
            region = "Jylland",
            computedWalkingKm = 12.0,
            startLat = 56.2000,
            startLng = 10.5000,
            endLat = 56.2200,
            endLng = 10.5200
        )
        val longTrail = shortNearTrail.copy(id = "trail_long_1", computedWalkingKm = 45.0)
        val farTrail = shortNearTrail.copy(
            id = "trail_far_1",
            startLat = 55.0000,
            startLng = 12.0000,
            endLat = 55.0200,
            endLng = 12.0200
        )

        // F4: Short walks near you (3-20 km within 15 km of user)
        val matches = ExploreViewModel.filterShortTrailsNearYou(
            trails = listOf(shortNearTrail, longTrail, farTrail),
            userLat = 56.2050,
            userLng = 10.5050
        )
        assertEquals(listOf("trail_short_1"), matches.map { it.id })

        // F3: Nearest public transport within 3 km of start and end
        val transitStops = listOf(
            PoiEntity(
                id = "tr_start",
                type = "station",
                name = "Femmøller Busstop",
                lat = 56.2020,
                lng = 10.5010
            ),
            PoiEntity(
                id = "tr_far",
                type = "station",
                name = "Langt Væk Station",
                lat = 56.3000,
                lng = 10.7000
            )
        )
        val nearestStart = TrailViewModel.findNearestTransportWithin3Km(
            point = GeoPointDto(56.2000, 10.5000),
            pois = transitStops
        )
        val nearestFar = TrailViewModel.findNearestTransportWithin3Km(
            point = GeoPointDto(55.5000, 9.5000),
            pois = transitStops
        )
        assertNotNull("Transit stop ~220 m from start must be matched within 3 km", nearestStart)
        assertEquals("Femmøller Busstop", nearestStart!!.first.name)
        assertNull("Point > 3 km from all stations must return null", nearestFar)
    }

    // =========================================================================
    // PART G — Translations (English & German)
    // =========================================================================

    @Test
    fun partG_danishEnglishGermanResourcesAndLocaleFormatting_resolveKeyScreensAndNumbers() {
        fun contextForLocale(locale: Locale): Context {
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }

        val daCtx = contextForLocale(Locale("da", "DK"))
        val enCtx = contextForLocale(Locale.ENGLISH)
        val deCtx = contextForLocale(Locale.GERMAN)

        assertEquals("Udforsk", daCtx.getString(R.string.nav_explore))
        assertEquals("Explore", enCtx.getString(R.string.nav_explore))
        assertEquals("Entdecken", deCtx.getString(R.string.nav_explore))

        assertEquals("Mine ture", daCtx.getString(R.string.nav_saved))
        assertEquals("My trips", enCtx.getString(R.string.nav_saved))
        assertEquals("Meine Touren", deCtx.getString(R.string.nav_saved))

        assertEquals("Pakkeliste", daCtx.getString(R.string.packing_list_title))
        assertEquals("Packing list", enCtx.getString(R.string.packing_list_title))
        assertEquals("Packliste", deCtx.getString(R.string.packing_list_title))

        // Verify "Fri teltning" includes explanation on first use in EN and DE
        assertTrue(enCtx.getString(R.string.explore_filter_fri_teltning).contains("Fri teltning (free forest camping)"))
        assertTrue(deCtx.getString(R.string.explore_filter_fri_teltning).contains("Fri teltning (freies Zelten im Wald)"))

        // Verify AppLocaleHelper formats numbers with the configuration locale
        assertEquals("12,5", AppLocaleHelper.formatNumber(daCtx, "%.1f", 12.5))
        assertEquals("12.5", AppLocaleHelper.formatNumber(enCtx, "%.1f", 12.5))
        assertEquals("12,5", AppLocaleHelper.formatNumber(deCtx, "%.1f", 12.5))
    }

    // =========================================================================
    // PART H — Home screen widget & watch notifications
    // =========================================================================

    @Test
    fun partH1_activeTripAppWidget_displaysActiveTripDetailsAndIdleState() {
        // 1. Idle state ("Ingen aktiv tur")
        val idleData = ActiveTripWidgetDisplayData(isActive = false)
        val idleViews = ActiveTripAppWidgetProvider.buildRemoteViews(context, idleData)
        val appliedIdle = idleViews.apply(context, null)
        val idleTitle = appliedIdle.findViewById<TextView>(R.id.widget_trail_name)
        assertEquals(context.getString(R.string.widget_no_active_trip), idleTitle.text.toString())

        // 2. Active trip state ("Dag X af Y", remaining km, next camp and ETA)
        val stage2 = TripStage(
            dayNumber = 2,
            startKm = 15.0,
            endKm = 30.0,
            stageKm = 15.0,
            stopName = "Skrårupøre Shelter",
            stopType = "SHELTER",
            stopLat = 55.1,
            stopLng = 10.2
        )
        val plannedRoute = PlannedRoute(
            planId = 15L,
            trailId = "trail_gendarmstien",
            trailName = "Gendarmstien",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 60.0,
            totalWalkKm = 60.0,
            points = listOf(RoutePoint(55.0, 10.0, null, 0.0, 0.0)),
            stages = listOf(
                stage2.copy(dayNumber = 1),
                stage2,
                stage2.copy(dayNumber = 3),
                stage2.copy(dayNumber = 4)
            )
        )
        val liveState = NavigationLiveState(
            isTracking = true,
            isCompleted = false,
            planId = 15L,
            trailName = "Gendarmstien",
            currentDay = 2,
            currentStage = stage2,
            plannedRoute = plannedRoute,
            distanceRemainingTodayKm = 8.4,
            speedKmh = 4.2
        )
        val activeData = ActiveTripAppWidgetProvider.resolveWidgetDisplayData(
            context = context,
            liveState = liveState,
            nowMs = 1715000000000L
        )
        assertTrue(activeData.isActive)
        val activeViews = ActiveTripAppWidgetProvider.buildRemoteViews(context, activeData)
        val appliedActive = activeViews.apply(context, null)

        assertEquals("Gendarmstien", appliedActive.findViewById<TextView>(R.id.widget_trail_name).text.toString())
        assertTrue(appliedActive.findViewById<TextView>(R.id.widget_day_badge).text.toString().contains("2"))
        assertTrue(appliedActive.findViewById<TextView>(R.id.widget_remaining_today).text.toString().contains("8"))
        assertTrue(appliedActive.findViewById<TextView>(R.id.widget_next_camp_eta).text.toString().contains("Skrårupøre Shelter"))
    }

    @Test
    fun partH2_watchTurnCueChannelsAndSingleVibrationSourcePerCue() {
        val app = context as DanskStiApp
        app.createNotificationChannels()
        val nm = context.getSystemService(NotificationManager::class.java)

        val leftCh = nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID)
        val rightCh = nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID)
        val otherCh = nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_OTHER_ID)

        assertNotNull(leftCh)
        assertNotNull(rightCh)
        assertNotNull(otherCh)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, leftCh.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, rightCh.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, otherCh.importance)
        assertNull(leftCh.sound)
        assertNull(rightCh.sound)
        assertNull(otherCh.sound)
        assertArrayEquals(NavigationTrackingService.VIBRATION_PATTERN_LEFT, leftCh.vibrationPattern)
        assertArrayEquals(NavigationTrackingService.VIBRATION_PATTERN_RIGHT, rightCh.vibrationPattern)
        assertArrayEquals(NavigationTrackingService.VIBRATION_PATTERN_OTHER, otherCh.vibrationPattern)

        // Verify channel routing
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID, NavigationTrackingService.channelIdForTurnDirection("LEFT"))
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID, NavigationTrackingService.channelIdForTurnDirection("SLIGHT_LEFT"))
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID, NavigationTrackingService.channelIdForTurnDirection("RIGHT"))
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID, NavigationTrackingService.channelIdForTurnDirection("SHARP_RIGHT"))
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_OTHER_ID, NavigationTrackingService.channelIdForTurnDirection("STRAIGHT"))
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_OTHER_ID, NavigationTrackingService.channelIdForTurnDirection("UTURN"))

        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val service = Robolectric.setupService(NavigationTrackingService::class.java)

        val leftCue = TurnCue(
            lat = 56.0,
            lng = 9.0,
            walkKm = 1.0,
            direction = "LEFT",
            angleDegrees = -90.0,
            instructionText = "Drej til venstre"
        )

        // Mode 1: "Vis sving på ur" ON -> notification vibrates via channel (1 vibration source), service skips own Vibrator
        nm.cancelAll()
        service.watchTurnCueNotificationsEnabled = true
        val notifBeforeOn = service.turnCueNotificationPostCount
        val vibBeforeOn = service.serviceTurnCueVibrationCount
        service.dispatchTurnCueAlert(cue = leftCue, distanceMeters = 55.0)
        assertEquals("Notification must be posted when watch setting is ON", notifBeforeOn + 1, service.turnCueNotificationPostCount)
        assertEquals(DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID, service.lastTurnCueChannelId)
        assertEquals("Service Vibrator must NOT be called when watch notification handles vibration", vibBeforeOn, service.serviceTurnCueVibrationCount)

        // Mode 2: "Vis sving på ur" OFF -> notification is NOT posted, service uses its own Vibrator (1 vibration source)
        val notifBeforeOff = service.turnCueNotificationPostCount
        val vibBeforeOff = service.serviceTurnCueVibrationCount
        service.watchTurnCueNotificationsEnabled = false
        service.dispatchTurnCueAlert(cue = leftCue, distanceMeters = 55.0)
        assertEquals("No turn-cue notification must be posted when watch setting is OFF", notifBeforeOff, service.turnCueNotificationPostCount)
        assertEquals("Service Vibrator must be triggered once when watch setting is OFF", vibBeforeOff + 1, service.serviceTurnCueVibrationCount)
    }

    // =========================================================================
    // ROUND 21 REVIEW — Specific Verification Tests (Items 1–11)
    // =========================================================================

    @Test
    fun reviewItem1_fetchTileBytes_rejects200WithXmlContentType_andAerialStoresOnlyUnderLuftfotoName() = runBlocking {
        // 1. Fake server returning 200 with Content-Type application/vnd.ogc.se_xml stores nothing
        val xmlBytes = "<ServiceExceptionReport>Error</ServiceExceptionReport>".toByteArray(Charsets.UTF_8)
        val xmlResult = com.example.service.OfflineDownloadWorker.fetchTileBytesFromUrl(
            tileUrlStr = "https://api.dataforsyningen.dk/orto_foraar_DAF?SERVICE=WMS",
            connectionFactory = { url ->
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getResponseCode(): Int = 200
                    override fun getContentType(): String = "application/vnd.ogc.se_xml; charset=UTF-8"
                    override fun getInputStream() = ByteArrayInputStream(xmlBytes)
                }
            }
        )
        assertNull("HTTP 200 with application/vnd.ogc.se_xml must return null and not store tile bytes", xmlResult)

        // 2. Aerial download stores tiles only under luftfotoInstance.name() (and base topo under skaermkortInstance.name())
        val areaId = "area:test_aerial_only_${System.nanoTime()}"
        val archiveFile = OfflineDownloadManager.getOfflineArchiveFile(context, areaId)
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        OfflineDownloadManager.networkStatusProviderOverride = { true to true }
        OfflineDownloadManager.freeBytesProviderOverride = { 500L * 1024L * 1024L }
        try {
            val result = com.example.service.OfflineDownloadWorker.executeDownload(
                context = context,
                trailId = areaId,
                trailName = "Test Luftfoto",
                wifiOnly = false,
                includeAerial = true,
                includeTerrain = false,
                areaMinLat = 56.100,
                areaMaxLat = 56.102,
                areaMinLng = 9.500,
                areaMaxLng = 9.502,
                connectionFactory = { url ->
                    object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy(): Boolean = false
                        override fun getResponseCode(): Int = 200
                        override fun getContentType(): String = "image/png"
                        override fun getInputStream() = ByteArrayInputStream(fakePng)
                    }
                }
            )
            assertTrue("executeDownload must return Result.Success (actual=$result)", result is androidx.work.ListenableWorker.Result.Success)
            assertTrue("Archive file must exist after aerial area download", archiveFile.exists())

            val providers = mutableSetOf<String>()
            android.database.sqlite.SQLiteDatabase.openDatabase(
                archiveFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { sqlDb ->
                sqlDb.rawQuery("SELECT DISTINCT provider FROM tiles", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        providers.add(cursor.getString(0))
                    }
                }
            }
            assertTrue(
                "Aerial tiles must be stored under luftfotoInstance.name()",
                providers.contains(DanskStiTileSourceHelper.luftfotoInstance.name())
            )
            assertEquals(
                "Only base topo and luftfotoInstance.name() must be present",
                setOf(DanskStiTileSourceHelper.skaermkortInstance.name(), DanskStiTileSourceHelper.luftfotoInstance.name()),
                providers
            )
        } finally {
            OfflineDownloadManager.networkStatusProviderOverride = null
            OfflineDownloadManager.freeBytesProviderOverride = null
            archiveFile.delete()
        }
    }

    @Test
    fun reviewItem2_weatherRefresh_forcesNetworkInSameCellAfter61Min_dropsPastHours_andChecks60MinRainWindow() = runBlocking {
        // 1. Same 0.05° cell + 61 min -> forceNetworkRefresh = true calls network even when cached entry is < 3h old
        val sampleResponseJson = """
            {
              "current": {
                "temperature_2m": 16.0,
                "precipitation": 0.0,
                "wind_speed_10m": 4.0,
                "wind_gusts_10m": 7.0,
                "weather_code": 1
              },
              "hourly": {
                "time": [
                  "2026-07-10T12:00",
                  "2026-07-10T13:00",
                  "2026-07-10T14:00",
                  "2026-07-10T15:00",
                  "2026-07-10T16:00",
                  "2026-07-10T17:00",
                  "2026-07-10T18:00",
                  "2026-07-10T19:00"
                ],
                "temperature_2m": [14.0, 15.0, 16.0, 17.0, 17.5, 18.0, 17.0, 16.0],
                "precipitation_probability": [10, 10, 10, 20, 20, 80, 85, 90],
                "precipitation": [0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 3.0],
                "wind_speed_10m": [3.0, 3.5, 4.0, 4.0, 4.5, 5.0, 5.0, 4.5],
                "wind_gusts_10m": [5.0, 6.0, 7.0, 7.0, 8.0, 9.0, 9.0, 8.0],
                "weather_code": [1, 1, 1, 2, 2, 61, 3, 63]
              },
              "daily": {
                "sunrise": ["2026-07-10T04:45"],
                "sunset": ["2026-07-10T21:55"]
              }
            }
        """.trimIndent()
        val sampleResponse = WeatherInterpreter.deserializeResponseFromJson(sampleResponseJson)!!

        val cphZone = java.time.ZoneId.of("Europe/Copenhagen")
        val t1200Ms = java.time.LocalDateTime.parse("2026-07-10T12:00:00").atZone(cphZone).toInstant().toEpochMilli()
        val t1301Ms = t1200Ms + 61 * 60_000L

        var networkCalls = 0
        val customWeatherApi = object : com.example.data.remote.WeatherApiService {
            override suspend fun getTrailWeather(
                lat: Double,
                lon: Double,
                current: String,
                hourly: String,
                daily: String,
                forecastHours: Int,
                models: String?,
                windSpeedUnit: String,
                timezone: String
            ): com.example.data.remote.OpenMeteoResponse {
                networkCalls++
                return sampleResponse
            }

            override suspend fun getElevations(
                latitudes: String,
                longitudes: String
            ): com.example.data.remote.OpenMeteoElevationResponse {
                return com.example.data.remote.OpenMeteoElevationResponse()
            }
        }
        val testRepo = DanskStiRepository(dao, context, weatherService = customWeatherApi).apply {
            isOnlineOverrideForTests = true
        }

        // Initial fetch at 12:00
        testRepo.getTrailWeather(56.01, 9.01, forceNetworkRefresh = false, nowMs = t1200Ms)
        assertEquals(1, networkCalls)

        // Same 0.05° cell after 61 min: shouldRefreshNavigationWeather is true, and passing forceNetworkRefresh = true calls network
        assertTrue(
            WeatherInterpreter.shouldRefreshNavigationWeather(
                lastFetchTimeMs = t1200Ms,
                lastFetchLat = 56.01,
                lastFetchLng = 9.01,
                nowMs = t1301Ms,
                currentLat = 56.011,
                currentLng = 9.011
            )
        )
        testRepo.getTrailWeather(56.011, 9.011, forceNetworkRefresh = true, nowMs = t1301Ms)
        assertEquals("Same cell after 61 min with forceNetworkRefresh=true must call the network", 2, networkCalls)

        // 2. Cached forecast fetched 5 h ago (at 12:00, now 17:19) -> first shown hour is current hour (17:00)
        val t1719Ms = java.time.LocalDateTime.parse("2026-07-10T17:19:00").atZone(cphZone).toInstant().toEpochMilli()
        val parsedAt1719 = WeatherInterpreter.parseWeather(
            response = sampleResponse,
            fetchedAtMillis = t1200Ms,
            nowMillis = t1719Ms,
            context = context
        )
        assertEquals("First shown hour in next6Hours must be the current hour 17:00", "17:00", parsedAt1719.next6Hours.first().hourLabel)
        assertEquals("First shown hour in hourly48h must be the current hour 17:00", "17:00", parsedAt1719.hourly48h.first().hourLabel)

        // 3. Rain alert window at 17:19:
        // Case A: 2 mm in the 17:00 hour -> alert
        val alertInCurrentHour = WeatherInterpreter.shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = 0L,
            nowMs = t1719Ms,
            hourlyPoints = parsedAt1719.hourly48h
        )
        assertNotNull("At 17:19, 2.0 mm in the 17:00 hour must trigger rain alert", alertInCurrentHour)
        assertEquals("17:00", alertInCurrentHour!!.hourLabel)

        // Case B: Rain only at 18:00 (starts in 41 min, within 60 min) -> alert
        val rainOnlyAt1800 = parsedAt1719.hourly48h.map { pt ->
            when (pt.hourLabel) {
                "17:00" -> pt.copy(precipitationMm = 0.0)
                "18:00" -> pt.copy(precipitationMm = 1.8)
                else -> pt.copy(precipitationMm = 0.0)
            }
        }
        val alertAt1800 = WeatherInterpreter.shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = 0L,
            nowMs = t1719Ms,
            hourlyPoints = rainOnlyAt1800
        )
        assertNotNull("At 17:19, rain at 18:00 (starts in 41 min) must trigger rain alert", alertAt1800)
        assertEquals("18:00", alertAt1800!!.hourLabel)

        // Case C: Rain only at 19:00 (+101 min from 17:19, or +90 min from 17:30) -> no alert
        val t1730Ms = java.time.LocalDateTime.parse("2026-07-10T17:30:00").atZone(cphZone).toInstant().toEpochMilli()
        val rainOnlyAt1900 = parsedAt1719.hourly48h.map { pt ->
            when (pt.hourLabel) {
                "19:00" -> pt.copy(precipitationMm = 2.5)
                else -> pt.copy(precipitationMm = 0.0)
            }
        }
        assertNull(
            "At 17:19, rain only at 19:00 must NOT trigger rain alert",
            WeatherInterpreter.shouldPostRainAlert(true, 0L, t1719Ms, rainOnlyAt1900)
        )
        assertNull(
            "At 17:30, rain at 19:00 (+90 min) must NOT trigger rain alert",
            WeatherInterpreter.shouldPostRainAlert(true, 0L, t1730Ms, rainOnlyAt1900)
        )
    }

    @Test
    fun reviewItem3_vibrationPatterns_matchExactPlannedArraysAndChannels() {
        assertArrayEquals(
            longArrayOf(0L, 150L, 120L, 150L),
            NavigationTrackingService.VIBRATION_PATTERN_LEFT
        )
        assertArrayEquals(
            longArrayOf(0L, 150L, 120L, 150L, 120L, 150L),
            NavigationTrackingService.VIBRATION_PATTERN_RIGHT
        )
        assertArrayEquals(
            longArrayOf(0L, 400L),
            NavigationTrackingService.VIBRATION_PATTERN_OTHER
        )

        val app = context as DanskStiApp
        app.createNotificationChannels()
        val nm = context.getSystemService(NotificationManager::class.java)
        assertArrayEquals(
            longArrayOf(0L, 150L, 120L, 150L),
            nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_LEFT_ID).vibrationPattern
        )
        assertArrayEquals(
            longArrayOf(0L, 150L, 120L, 150L, 120L, 150L),
            nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID).vibrationPattern
        )
        assertArrayEquals(
            longArrayOf(0L, 400L),
            nm.getNotificationChannel(DanskStiApp.CHANNEL_TURN_CUE_OTHER_ID).vibrationPattern
        )
    }

    @Test
    fun reviewItem4_noInventedWaterDistance_noFreeBookingLine_andPlannerDaySummaryDefault() {
        // 1. Unknown water distance passes null -> "vand i nærheden" without a number
        val explanation = StagePlanner.buildCampExplanation(
            stageKm = 18.0,
            dailyTargetKm = 18.0,
            detourMeters = 0.0,
            hasWaterNearby = true,
            waterDistanceMeters = null,
            hasToiletNearby = false,
            context = context
        )
        assertTrue("Explanation must contain 'vand i nærheden' (actual=$explanation)", explanation.contains("vand i nærheden"))
        assertFalse("Explanation must NOT contain invented '200 m' (actual=$explanation)", explanation.contains("200"))

        // 2. PlannerDaySummary default campExplanation does not invent 200 m water or toilet
        val defaultSummary = com.example.ui.viewmodel.PlannerDaySummary(
            dayNumber = 1,
            startKm = 0.0,
            endKm = 16.0,
            distanceKm = 16.0,
            ascentM = 100,
            descentM = 80,
            fromName = "Start",
            toName = "Skovlejr",
            overnightWaypointId = "wp_1",
            overnightName = "Skovlejr",
            overnightTypeLabel = "Shelter",
            detourLabel = null,
            detourKm = 0.0,
            detourAscentM = 0,
            bookingLabel = null,
            sunsetTime = "21:30",
            arrivalTime = "14:00",
            isAfterSunset = false,
            warnings = emptyList(),
            nightWeatherSummary = "",
            campLat = 56.0,
            campLng = 9.5
        )
        assertFalse("Default PlannerDaySummary campExplanation must not invent 200 m water", defaultSummary.campExplanation.contains("200"))
        assertTrue(
            "Default PlannerDaySummary campExplanation must default to no water nearby (actual=${defaultSummary.campExplanation})",
            defaultSummary.campExplanation.contains(context.getString(R.string.stage_warning_no_water))
        )
        assertTrue(
            "Default PlannerDaySummary campExplanation must default to no toilet nearby (actual=${defaultSummary.campExplanation})",
            defaultSummary.campExplanation.contains(context.getString(R.string.stage_warning_no_toilet))
        )

        // 3. PoiInfoSheetData.fromShelter: when bookingRequired is false, bookingText is null; when true, "Kræver booking"
        val noBookingShelter = ShelterEntity(
            id = "s_no_book",
            name = "Åben Shelter",
            lat = 56.0,
            lng = 9.5,
            description = "",
            bookingRequired = false,
            ownership = "Naturstyrelsen",
            capacity = 6,
            hasWaterNearby = false,
            hasToiletNearby = false,
            source = "geofa",
            trailCorridorId = null
        )
        val noBookingSheet = PoiInfoSheetData.fromShelter(noBookingShelter, context)
        assertNull("When bookingRequired is false, bookingText must be null", noBookingSheet.bookingText)

        val reqBookingSheet = PoiInfoSheetData.fromShelter(noBookingShelter.copy(bookingRequired = true), context)
        assertEquals(context.getString(R.string.poi_booking_required), reqBookingSheet.bookingText)
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-rUS")
    fun reviewItem5_enUsPhoneWithFollowSystem_showsEnglishTrailCard() {
        val enContext = ApplicationProvider.getApplicationContext<Application>()
        val sampleTrail = TrailEntity(
            id = "trail_en_test",
            name = "Gendarmstien",
            description = "Coastal trail",
            region = "Jylland",
            computedWalkingKm = 74.0,
            isGeometryDownloaded = true
        )
        composeTestRule.setContent {
            DanskStiTheme {
                com.example.ui.screens.TrailCard(
                    trail = sampleTrail,
                    points = null,
                    sheltersCount = 5,
                    friTeltningCount = 2,
                    onClick = {}
                )
            }
        }
        val expectedDayPlural = enContext.getString(R.string.day_plural)
        assertEquals("days", expectedDayPlural)
        composeTestRule.onNodeWithText("days", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun reviewItem6_localizedTurnInstructions_englishBannerNotificationTts_andDanishVoiceFallback() {
        val enConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.US)
        }
        val enCtx = context.createConfigurationContext(enConfig)

        val rightCue = TurnCue(
            lat = 56.0,
            lng = 9.0,
            walkKm = 1.2,
            direction = "RIGHT",
            angleDegrees = 90.0,
            instructionText = "Drej til højre"
        )

        // 1. English banner text: "Turn right in 30 m"
        val bannerText = com.example.util.routing.TurnCueGenerator.formatCueBannerText(rightCue, 30.0, enCtx)
        assertEquals("Turn right in 30 m", bannerText)

        // 2. Notification title in English
        val app = context as DanskStiApp
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val service = Robolectric.setupService(NavigationTrackingService::class.java)
        AppLocaleHelper.setApplicationLanguageTag(context, "en")
        try {
            val notification = service.buildTurnCueNotification(
                channelId = DanskStiApp.CHANNEL_TURN_CUE_RIGHT_ID,
                cue = rightCue,
                distanceMeters = 30.0
            )
            val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            assertEquals("Turn right in 30 m", title)

            // 3. TTS text in English when English voice is installed
            val enSpeech = com.example.util.DanishTtsChecker.formatSpeechCueText(
                context = context,
                cue = rightCue,
                distanceMeters = 30.0,
                effectiveVoiceLocale = Locale.ENGLISH
            )
            assertEquals("Turn right in 30 m", enSpeech)

            // 4. English voice missing, Danish voice available -> fallback to Danish voice and Danish text
            val effectiveVoiceLocale = com.example.util.DanishTtsChecker.resolveEffectiveTtsLocale(
                context = enCtx,
                tts = null,
                isLocaleAvailable = { loc -> loc.language == "da" }
            )
            assertNotNull("Should be able to speak via Danish fallback", effectiveVoiceLocale)
            assertEquals("da", effectiveVoiceLocale!!.language)
            assertTrue("Should mark fallback to Danish", com.example.util.DanishTtsChecker.isUsingDanishFallbackVoice.value)
            assertEquals(false, com.example.util.DanishTtsChecker.isDanishVoiceInstalled.value)

            val daFallbackSpeech = com.example.util.DanishTtsChecker.formatSpeechCueText(
                context = context,
                cue = rightCue,
                distanceMeters = 30.0,
                effectiveVoiceLocale = effectiveVoiceLocale
            )
            assertEquals("Drej til højre om 30 m", daFallbackSpeech)
        } finally {
            AppLocaleHelper.setApplicationLanguageTag(context, "")
        }
    }

    @Test
    fun reviewItem8_unifiedCatalogueMetadata_appliedToNonDownloadedTrail_andPreservesDownloadedTrailKmAndBbox() = runBlocking {
        val nonDownloaded = TrailEntity(
            id = "trail_non_dl",
            name = "Ikke Hentet Sti",
            region = "Fyn",
            osmRelationId = 12345L,
            computedWalkingKm = 0.0,
            isGeometryDownloaded = false
        )
        val downloaded = TrailEntity(
            id = "trail_dl",
            name = "Hentet Sti",
            region = "Jylland",
            osmRelationId = 67890L,
            computedWalkingKm = 88.5,
            bboxNorth = 56.5,
            bboxSouth = 56.1,
            bboxEast = 9.8,
            bboxWest = 9.2,
            startLat = 56.1,
            startLng = 9.2,
            endLat = 56.5,
            endLng = 9.8,
            isGeometryDownloaded = true
        )
        dao.insertTrails(listOf(nonDownloaded, downloaded))

        val fixtureJson = """
            {
              "generatedAt": 1700000000000,
              "trails": [
                {
                  "id": "trail_non_dl",
                  "osmRelationId": 12345,
                  "computedWalkingKm": 42.3,
                  "bboxNorth": 55.3,
                  "bboxSouth": 55.0,
                  "bboxEast": 10.6,
                  "bboxWest": 10.1,
                  "startLat": 55.01,
                  "startLng": 10.12,
                  "endLat": 55.29,
                  "endLng": 10.58,
                  "nearestTown": "Faaborg",
                  "osmTimestamp": "2026-03-01T10:00:00Z",
                  "osmTags": { "operator": "Naturstyrelsen", "symbol": "Hvid pil" }
                },
                {
                  "id": "trail_dl",
                  "osmRelationId": 67890,
                  "computedWalkingKm": 999.9,
                  "bboxNorth": 57.9,
                  "bboxSouth": 54.0,
                  "bboxEast": 12.9,
                  "bboxWest": 8.0,
                  "startLat": 54.0,
                  "startLng": 8.0,
                  "endLat": 57.9,
                  "endLng": 12.9,
                  "nearestTown": "Viborg",
                  "osmTimestamp": "2026-03-02T10:00:00Z",
                  "osmTags": { "operator": "Viborg Kommune" }
                }
              ]
            }
        """.trimIndent()

        repository.applyBundledCatalogueMetadataAssetIfNeeded(assetJsonOverride = fixtureJson, force = true)

        val updatedNonDl = dao.getTrailById("trail_non_dl")!!
        assertEquals(42.3, updatedNonDl.computedWalkingKm, 0.01)
        assertEquals(55.3, updatedNonDl.bboxNorth!!, 0.001)
        assertEquals(55.01, updatedNonDl.startLat!!, 0.001)
        assertEquals("Faaborg", updatedNonDl.nearestTown)
        assertEquals("2026-03-01T10:00:00Z", updatedNonDl.osmTimestamp)
        assertEquals("Naturstyrelsen", updatedNonDl.osmOperator)

        val updatedDl = dao.getTrailById("trail_dl")!!
        assertEquals("Downloaded trail must keep geometry-derived computedWalkingKm", 88.5, updatedDl.computedWalkingKm, 0.01)
        assertEquals("Downloaded trail must keep geometry-derived bboxNorth", 56.5, updatedDl.bboxNorth!!, 0.001)
        assertEquals("Downloaded trail must keep geometry-derived startLat", 56.1, updatedDl.startLat!!, 0.001)
        assertEquals("Viborg", updatedDl.nearestTown)
    }

    @Test
    fun reviewItem9_shortMarkedRoutesNearYou_filtersByLengthCatalogueAnd25KmNearestPointAndSortsByNearest() {
        val userLat = 56.1500
        val userLng = 10.2000

        // Route 1: 8 km, nearest geometry point ~1.1 km away (closer than route 2), not in catalogue -> INCLUDED first
        val nearShortRoute = com.example.data.model.MarkedRouteDto(
            osmRelationId = 9001L,
            name = "Brabrandstien Rundt",
            operator = "Aarhus Kommune",
            routeType = "hiking",
            network = "lwn",
            symbol = "Blå",
            colorHex = "#1E88E5",
            lengthKm = 8.0,
            polylines = listOf(listOf(GeoPointDto(56.1600, 10.2000), GeoPointDto(56.1800, 10.2100))),
            minLat = 56.1600,
            maxLat = 56.1800,
            minLng = 10.2000,
            maxLng = 10.2100
        )
        // Route 2: 14 km, nearest geometry point ~5.5 km away -> INCLUDED second
        val midShortRoute = nearShortRoute.copy(
            osmRelationId = 9002L,
            name = "Marselisborg Skovrute",
            lengthKm = 14.0,
            polylines = listOf(listOf(GeoPointDto(56.2000, 10.2000), GeoPointDto(56.2200, 10.2100))),
            minLat = 56.2000,
            maxLat = 56.2200
        )
        // Route 3: 32 km (> 20 km) -> EXCLUDED
        val tooLongRoute = nearShortRoute.copy(osmRelationId = 9003L, name = "Lang Rute", lengthKm = 32.0)
        // Route 4: in catalogue -> EXCLUDED
        val catalogueRoute = nearShortRoute.copy(osmRelationId = 9004L, name = "Katalog Rute")
        // Route 5: 70 km away (> 25 km) -> EXCLUDED
        val tooFarRoute = nearShortRoute.copy(
            osmRelationId = 9005L,
            name = "Langt Væk Rute",
            polylines = listOf(listOf(GeoPointDto(55.5000, 10.2000), GeoPointDto(55.5100, 10.2100))),
            minLat = 55.5000,
            maxLat = 55.5100
        )

        val filtered = com.example.data.repository.MarkedRouteRepository.filterShortMarkedRoutesNearYou(
            routes = listOf(midShortRoute, tooLongRoute, catalogueRoute, nearShortRoute, tooFarRoute),
            userLat = userLat,
            userLng = userLng,
            catalogueRelationIds = setOf(9004L)
        )
        assertEquals(listOf(9001L, 9002L), filtered.map { it.osmRelationId })
    }

    @Test
    fun reviewItem10_trailPhotoRepository_usesNetworkConstantsUserAgentAndTitlesOnlyNoSearch() = runBlocking {
        val repo = TrailPhotoRepository(context)
        val urls = mutableListOf<String>()
        val agents = mutableListOf<String>()
        val emptyPagesJson = """{"query":{"pages":{}}}"""

        repo.fetchCommonsPhotosForTitles(
            fileTitles = listOf("File:Example.jpg"),
            connectionFactory = { url ->
                urls.add(url.toString())
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun setRequestProperty(key: String?, value: String?) {
                        super.setRequestProperty(key, value)
                        if (key.equals("User-Agent", ignoreCase = true) && value != null) {
                            agents.add(value)
                        }
                    }
                    override fun getResponseCode(): Int = 200
                    override fun getInputStream() = ByteArrayInputStream(emptyPagesJson.toByteArray(Charsets.UTF_8))
                }
            }
        )
        assertEquals(listOf(com.example.util.network.NetworkConstants.USER_AGENT), agents)
        assertEquals(1, urls.size)
        assertTrue(urls[0].contains("action=query&titles="))
        assertFalse(urls[0].contains("generator=search"))
    }

    @Test
    fun reviewItem11_curatedTrailDescriptionFormat_plainLanguageStrings_andRecordingsEmptyAction() {
        // 1. trail_descriptions.json uses { "trails": { "<trailId>": { "description": "", "bestSeason": "", "dogsNote": "", "officialUrl": "" } } }
        val rawDescJson = context.assets.open("catalogue/trail_descriptions.json").bufferedReader().use { it.readText() }
        val rootObj = org.json.JSONObject(rawDescJson)
        assertTrue("trail_descriptions.json must have top-level 'trails' object", rootObj.has("trails"))
        val trailsObj = rootObj.getJSONObject("trails")
        assertTrue("trail_descriptions.json must contain catalogue trail IDs", trailsObj.length() >= 30)
        val firstKey = trailsObj.keys().next()
        val firstEntry = trailsObj.getJSONObject(firstKey)
        assertTrue(firstEntry.has("description"))
        assertTrue(firstEntry.has("bestSeason"))
        assertTrue(firstEntry.has("dogsNote"))
        assertTrue(firstEntry.has("officialUrl"))

        // 2. Plain language strings & Optagelser empty-state action
        assertEquals("Planlæg en tur", context.getString(R.string.library_empty_recordings_action))
        assertEquals(
            "Afmærkede vandreruter fra OpenStreetMap, som ikke er i kataloget",
            context.getString(R.string.explore_more_marked_routes_subtitle)
        )
        assertEquals(
            "OpenStreetMap • 74,0 km (okt. 2025)",
            context.getString(R.string.ui_traildetailscreen_live_osm_196, "74,0 km", "(okt. 2025)")
        )
        assertFalse(context.getString(R.string.map_layer_base_topo_subtitle).contains("SDFI"))
        assertFalse(context.getString(R.string.map_layer_base_aerial_subtitle).contains("SDFI"))
        assertFalse(context.getString(R.string.map_layer_base_outdoor_subtitle).contains("EPSG"))
        assertFalse(context.getString(R.string.map_layer_terrain_subtitle).contains("DHM"))
    }

    @Test
    fun reviewItem12_noTestOnlyHooksInMainSources() {
        val mainDir = File("src/main/java").takeIf { it.exists() }
            ?: File("app/src/main/java")
        assertTrue("Expected app/src/main/java to exist", mainDir.exists())
        val forbidden = listOf(
            "cancelActiveJobs",
            "latestInstance",
            "registerViewModelScope",
            "cancelAllViewModelScopes",
            "activeViewModelScopes"
        )
        val violations = mutableListOf<String>()
        mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { symbol ->
                if (text.contains(symbol)) {
                    violations.add("${file.path} contains $symbol")
                }
            }
        }
        assertTrue("Found forbidden test-only hooks in main sources: $violations", violations.isEmpty())
    }

    @Test
    fun reviewItem13_fontScale130_trailCard_textDisplayedInsideParentBounds() {
        val sampleTrail = TrailEntity(
            id = "trail_font130",
            name = "Gendarmstien",
            description = "Kystvandrerute ved Flensborg Fjord",
            region = "Sønderjylland",
            parentRegion = "Jylland",
            osmRelationId = 1001L,
            computedWalkingKm = 84.0,
            isGeometryDownloaded = true
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                DanskStiTheme {
                    com.example.ui.screens.TrailCard(
                        trail = sampleTrail,
                        points = listOf(GeoPointDto(54.85, 9.45), GeoPointDto(54.90, 9.80)),
                        sheltersCount = 5,
                        friTeltningCount = 2,
                        ascentMeters = 420,
                        dailyTargetKm = 20.0,
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val cardNode = composeTestRule.onNodeWithTag("trail_card_trail_font130")
        cardNode.assertIsDisplayed()
        val cardBounds = cardNode.getUnclippedBoundsInRoot()
        val titleNode = composeTestRule.onNodeWithText("Gendarmstien", useUnmergedTree = true)
        titleNode.assertIsDisplayed()
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        assertTrue("Title left must be inside TrailCard bounds", titleBounds.left >= cardBounds.left)
        assertTrue("Title right must be inside TrailCard bounds", titleBounds.right <= cardBounds.right)
        assertTrue("Title top must be inside TrailCard bounds", titleBounds.top >= cardBounds.top)
        assertTrue("Title bottom must be inside TrailCard bounds", titleBounds.bottom <= cardBounds.bottom)
    }

    @Test
    fun reviewItem13_fontScale130_poiInfoSheet_textDisplayedInsideParentBounds() {
        val shelter = ShelterEntity(
            id = "shelter_font130",
            name = "Skovbryn Shelterplads",
            lat = 55.5,
            lng = 10.5,
            hasWaterNearby = true,
            hasToiletNearby = true,
            bookingRequired = true,
            source = "GeoFA"
        )
        val sheetData = PoiInfoSheetData.fromShelter(shelter, context)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                DanskStiTheme {
                    PoiInfoSheet(
                        poiData = sheetData,
                        liveUserLocation = null,
                        onDismiss = {},
                        onRouteHere = { _, _, _ -> },
                        onAddToRoute = { _, _, _ -> }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val sheetNode = composeTestRule.onNodeWithTag("poi_info_sheet")
        sheetNode.assertIsDisplayed()
        val sheetBounds = sheetNode.getUnclippedBoundsInRoot()
        val titleNode = composeTestRule.onNodeWithText("Skovbryn Shelterplads", useUnmergedTree = true)
        titleNode.assertIsDisplayed()
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        assertTrue("Title left must be inside PoiInfoSheet bounds", titleBounds.left >= sheetBounds.left)
        assertTrue("Title right must be inside PoiInfoSheet bounds", titleBounds.right <= sheetBounds.right)
        assertTrue("Title top must be inside PoiInfoSheet bounds", titleBounds.top >= sheetBounds.top)
        assertTrue("Title bottom must be inside PoiInfoSheet bounds", titleBounds.bottom <= sheetBounds.bottom)
    }

    @Test
    fun reviewItem13_fontScale130_turnCueCardAndLiveRegions_textInsideBoundsAndPoliteAssertive() {
        val cue = TurnCue(
            walkKm = 1.2,
            lat = 55.6,
            lng = 10.2,
            direction = "RIGHT",
            instructionText = "Drej til højre",
            angleDegrees = 90.0
        )
        val liveState = NavigationLiveState(
            isTracking = true,
            isOffTrailWarning = true,
            offTrailDistanceMeters = 48.0,
            isDetourMuted = false,
            bearingToPointAhead = 45.0f,
            deviceHeadingDegrees = 0.0f
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                DanskStiTheme {
                    com.example.ui.screens.NavigationTopCueAndAlertsStack(
                        activeCueWithin500m = cue to 30.0,
                        cueBannerPrimaryText = "Drej til højre om 30 m",
                        topCardHeadline = "3,4 km til Aftenlejr",
                        liveState = liveState,
                        deviceHeadingDegrees = 0.0f,
                        mapMode = com.example.ui.viewmodel.NavigationMapMode.FOLLOW_HEADING_UP,
                        routeBackErrorMessage = null,
                        isGpsWeakOrStale = false,
                        gpsStaleMinutes = 0,
                        isDaylightWarningVisible = false,
                        distanceRemainingToday = 3.4,
                        sunsetTime = "20:45",
                        isLocationPermissionMissing = false,
                        onBack = {},
                        onRerouteBack = {},
                        onTakeDetour = {},
                        onRequestLocationPermission = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val cardNode = composeTestRule.onNodeWithTag("nav_top_card")
        cardNode.assertIsDisplayed()
        val cardBounds = cardNode.getUnclippedBoundsInRoot()

        val cueTextNode = composeTestRule.onNodeWithTag("nav_cue_primary_text", useUnmergedTree = true)
        cueTextNode.assertIsDisplayed()
        val cueBounds = cueTextNode.getUnclippedBoundsInRoot()
        assertTrue("Cue text left must be inside nav_top_card", cueBounds.left >= cardBounds.left)
        assertTrue("Cue text right must be inside nav_top_card", cueBounds.right <= cardBounds.right)
        assertTrue("Cue text top must be inside nav_top_card", cueBounds.top >= cardBounds.top)
        assertTrue("Cue text bottom must be inside nav_top_card", cueBounds.bottom <= cardBounds.bottom)

        // Verify accessibility LiveRegion semantics on turn cue (Polite) and off-route banner (Assertive)
        val cueBannerSemantics = composeTestRule.onNodeWithTag("nav_cue_banner").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, cueBannerSemantics.config[SemanticsProperties.LiveRegion])

        val offRouteSemantics = composeTestRule.onNodeWithTag("nav_off_route_banner").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Assertive, offRouteSemantics.config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun reviewItem13_migration27To28_migrationTestHelper_readsDownloadedTrailWithDefaultTrailType() {
        val schema28 = File("schemas/com.example.data.local.DanskStiDatabase/28.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/com.example.data.local.DanskStiDatabase/28.json")
        assertTrue("Committed schemas/28.json must exist", schema28.exists())
        assertTrue(schema28.readText().contains("\"version\": 28"))

        val dbName = "r21_mig_27_28_${System.nanoTime()}.db"
        migrationHelper.createDatabase(dbName, 27).apply {
            execSQL(
                """
                INSERT INTO `downloaded_trails` (
                    `trailId`, `trailName`, `downloadedAt`, `sizeBytes`, `tileCount`, `corridorKm`, `minZoom`, `maxZoom`
                ) VALUES (
                    'trail_ohavsstien', 'Øhavsstien', 1700000000000, 1234567, 420, 2.0, 10, 15
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            dbName,
            28,
            true,
            DanskStiDatabase.MIGRATION_27_28
        )
        migratedDb.query("SELECT trailId, downloadType FROM downloaded_trails WHERE trailId = 'trail_ohavsstien'").use { c ->
            assertTrue("Existing downloaded_trails row must survive 27 -> 28 migration", c.moveToFirst())
            assertEquals("trail_ohavsstien", c.getString(0))
            assertEquals("TRAIL", c.getString(1))
        }
        migratedDb.close()
    }
}

