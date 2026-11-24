package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Vibrator
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [34])
class Round21VerificationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

        val sheetData = PoiInfoSheetData.fromShelter(unknownShelter)
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

        val photos = repo.fetchCommonsPhotosForQuery(
            queryTitle = "Hærvejen",
            connectionFactory = { url: URL ->
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
            "Commons request must use TrailPhotoRepository.APP_USER_AGENT",
            capturedUserAgents.isNotEmpty() && capturedUserAgents.all { it == TrailPhotoRepository.APP_USER_AGENT }
        )
    }

    @Test
    fun partB4_turnCueAndOffRouteVibrationPatterns_areDistinctFromEachOther() {
        val left = NavigationTrackingService.VIBRATION_PATTERN_LEFT
        val right = NavigationTrackingService.VIBRATION_PATTERN_RIGHT
        val other = NavigationTrackingService.VIBRATION_PATTERN_OTHER
        val offRoute = NavigationTrackingService.VIBRATION_PATTERN_OFF_ROUTE

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
            nowMs = 1_000_000L,
            hourlyPoints = hourlyPoints
        )
        assertNotNull("1.4 mm/h in next 2 hours must trigger rain alert", rainPoint)
        assertEquals("13:00", rainPoint!!.hourLabel)

        // Throttled within 3 hours
        val throttled = WeatherInterpreter.shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = 1_000_000L,
            nowMs = 1_000_000L + 60 * 60_000L,
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
}
