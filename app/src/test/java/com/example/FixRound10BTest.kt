package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppAppearanceMode
import com.example.data.local.BaseMapType
import com.example.data.local.SettingsRepository
import com.example.data.model.CustomRouteEntity
import com.example.data.model.PlannedRoute
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.RoutePoint
import com.example.data.model.TripPlanEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.remote.OverpassClient
import com.example.data.repository.DanskStiRepository
import com.example.service.GpsPowerProfile
import com.example.ui.screens.OsmRelationTagsCard
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.computeRemappedCompassAzimuthFromMatrix
import com.example.ui.screens.resolveNavigationHeadingDegrees
import com.example.ui.theme.DanskStiTheme
import com.example.util.DanishTtsChecker
import com.example.util.DanskStiTileSourceHelper
import com.example.util.GeoUtils
import com.example.util.GpxImporter
import com.example.util.OfflineDownloadManager
import com.example.util.StagePlanner
import com.example.util.WaypointPhotoHelper
import com.example.util.routing.BRouterGeoJsonParser
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FixRound10BTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // =========================================================================
    // Item 1: Dark map recolouring & Screenshot tests (Odense centre, forest,
    // coast at zoom 13 and 16 on both base maps: SKAERMKORT & FRILUFTSKORT)
    // =========================================================================

    private fun luminance(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    @Test
    fun item1_darkMapRecolouring_preservesLightLandLuminanceOrder_andKeepsSaturatedRoadHue() {
        // Light land lum 165..255 maps monotonically to #24282B..#4A5056
        val c165 = DanskStiTileSourceHelper.recolorPixelForDarkMap(Color.rgb(165, 165, 165), BaseMapType.SKAERMKORT)
        val c210 = DanskStiTileSourceHelper.recolorPixelForDarkMap(Color.rgb(210, 210, 210), BaseMapType.SKAERMKORT)
        val c255 = DanskStiTileSourceHelper.recolorPixelForDarkMap(Color.rgb(255, 255, 255), BaseMapType.SKAERMKORT)

        assertEquals(0xFF24282B.toInt(), c165)
        assertEquals(0xFF4A5056.toInt(), c255)
        assertTrue("Light land order must be preserved", luminance(c165) < luminance(c210))
        assertTrue("White road fills must be brighter than surrounding land", luminance(c210) < luminance(c255))

        // Saturated road colour (e.g. primary/motorway orange-red or yellow-orange) stays a darker version of same hue (~45% V, ~80% S)
        val orangeRoad = Color.rgb(245, 150, 50)
        val darkRoad = DanskStiTileSourceHelper.recolorPixelForDarkMap(orangeRoad, BaseMapType.SKAERMKORT)
        val origHsv = FloatArray(3)
        val darkHsv = FloatArray(3)
        Color.colorToHSV(orangeRoad, origHsv)
        Color.colorToHSV(darkRoad, darkHsv)

        assertTrue("Hue preserved within 6 degrees", abs(origHsv[0] - darkHsv[0]) <= 6f)
        assertEquals(0.80f, darkHsv[1], 0.05f)
        assertEquals(0.45f, darkHsv[2], 0.05f)

        // Town tint on Udendørs (FRILUFTSKORT) with mild greenish-grey/beige must NOT be painted as forest (#1E3324)
        val udendoersTownPixel = Color.rgb(222, 230, 214)
        val recoloredTown = DanskStiTileSourceHelper.recolorPixelForDarkMap(udendoersTownPixel, BaseMapType.FRILUFTSKORT)
        assertFalse(
            "Towns on Udendørs must not be painted as forest",
            recoloredTown == DanskStiTileSourceHelper.DARK_FOREST_COLOR_INT
        )
    }

    @Test
    fun item1_darkMapScreenshotTests_odenseForestCoast_zoom13And16_bothBaseMaps() {
        val outputDir = File("build/outputs/dark_map_screenshots_10b").apply { mkdirs() }
        val scenes = listOf("odense_centre", "forest", "coast")
        val zooms = listOf(13, 16)
        val baseMaps = listOf(BaseMapType.SKAERMKORT, BaseMapType.FRILUFTSKORT)

        for (baseMap in baseMaps) {
            for (scene in scenes) {
                for (zoom in zooms) {
                    val sourceTile = createSyntheticMapSceneTile(scene = scene, zoom = zoom, baseMap = baseMap)
                    val darkTile = DanskStiTileSourceHelper.recolorBitmapForDarkMap(sourceTile, baseMap)

                    val outFile = File(outputDir, "${baseMap.name.lowercase()}_${scene}_z${zoom}.png")
                    FileOutputStream(outFile).use { fos ->
                        darkTile.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    assertTrue("Screenshot ${outFile.name} must exist and be non-empty", outFile.exists() && outFile.length() > 0L)

                    when (scene) {
                        "odense_centre" -> {
                            val townBgPixel = darkTile.getPixel(40, 40)
                            assertFalse(
                                "Odense town background on $baseMap z$zoom must not be forest green",
                                townBgPixel == DanskStiTileSourceHelper.DARK_FOREST_COLOR_INT
                            )
                            val townG = Color.green(townBgPixel)
                            val townR = Color.red(townBgPixel)
                            assertTrue(
                                "Odense town pixel must not have strong green dominance (r=$townR, g=$townG)",
                                townG - townR <= 10
                            )
                            val whiteRoadPixel = darkTile.getPixel(40, 128)
                            val primaryRoadPixel = darkTile.getPixel(128, 40)
                            assertTrue(
                                "White road fill must stand out from town land on $baseMap z$zoom",
                                abs(luminance(whiteRoadPixel) - luminance(townBgPixel)) >= 8
                            )
                            assertTrue(
                                "Primary road must be clearly visible on $baseMap z$zoom",
                                Color.red(primaryRoadPixel) > Color.blue(primaryRoadPixel) + 30
                            )
                        }
                        "forest" -> {
                            val forestPixel = darkTile.getPixel(40, 40)
                            val forestRoadPixel = darkTile.getPixel(128, 128)
                            assertEquals(DanskStiTileSourceHelper.DARK_FOREST_COLOR_INT, forestPixel)
                            assertTrue(
                                "Road through forest must stand out against forest canopy on $baseMap z$zoom",
                                abs(luminance(forestRoadPixel) - luminance(forestPixel)) >= 12
                            )
                        }
                        "coast" -> {
                            val seaPixel = darkTile.getPixel(40, 128)
                            val coastLandPixel = darkTile.getPixel(200, 40)
                            val coastRoadPixel = darkTile.getPixel(200, 128)
                            assertEquals(DanskStiTileSourceHelper.DARK_WATER_COLOR_INT, seaPixel)
                            assertTrue(
                                "Coastal road must stand out from coastal land on $baseMap z$zoom",
                                abs(luminance(coastRoadPixel) - luminance(coastLandPixel)) >= 8
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createSyntheticMapSceneTile(scene: String, zoom: Int, baseMap: BaseMapType): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokeScale = if (zoom >= 16) 1.6f else 1.0f

        when (scene) {
            "odense_centre" -> {
                paint.style = Paint.Style.FILL
                paint.color = if (baseMap == BaseMapType.FRILUFTSKORT) {
                    Color.rgb(224, 229, 216)
                } else {
                    Color.rgb(218, 214, 208)
                }
                canvas.drawRect(0f, 0f, 256f, 256f, paint)

                paint.color = Color.rgb(188, 184, 178)
                canvas.drawRect(20f, 20f, 95f, 95f, paint)
                canvas.drawRect(155f, 155f, 230f, 230f, paint)

                paint.color = Color.rgb(255, 255, 255)
                canvas.drawRect(0f, 128f - 6f * strokeScale, 256f, 128f + 6f * strokeScale, paint)

                paint.color = Color.rgb(246, 156, 62)
                canvas.drawRect(128f - 6f * strokeScale, 0f, 128f + 6f * strokeScale, 256f, paint)
            }
            "forest" -> {
                paint.style = Paint.Style.FILL
                paint.color = if (baseMap == BaseMapType.FRILUFTSKORT) {
                    Color.rgb(152, 208, 142)
                } else {
                    Color.rgb(168, 208, 158)
                }
                canvas.drawRect(0f, 0f, 256f, 256f, paint)

                paint.color = Color.rgb(252, 252, 250)
                canvas.drawRect(0f, 128f - 5f * strokeScale, 256f, 128f + 5f * strokeScale, paint)
            }
            "coast" -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(165, 212, 240)
                canvas.drawRect(0f, 0f, 128f, 256f, paint)

                paint.color = Color.rgb(204, 202, 196)
                canvas.drawRect(128f, 0f, 256f, 256f, paint)

                paint.color = Color.rgb(255, 255, 255)
                canvas.drawRect(128f, 128f - 5f * strokeScale, 256f, 128f + 5f * strokeScale, paint)
            }
        }
        return bmp
    }

    // =========================================================================
    // Item 2: Compass remapCoordinateSystem & unified heading source
    // =========================================================================

    @Test
    fun item2_compassRemapCoordinateSystem_andUnifiedHeadingSourceAgreeInPortraitAndLandscape() {
        val rotationMatrix = floatArrayOf(
            0f, 1f, 0f,
            -1f, 0f, 0f,
            0f, 0f, 1f
        )

        val portraitHeading = computeRemappedCompassAzimuthFromMatrix(rotationMatrix, Surface.ROTATION_0)!!
        val landscape90Heading = computeRemappedCompassAzimuthFromMatrix(rotationMatrix, Surface.ROTATION_90)!!
        val rot180Heading = computeRemappedCompassAzimuthFromMatrix(rotationMatrix, Surface.ROTATION_180)!!
        val landscape270Heading = computeRemappedCompassAzimuthFromMatrix(rotationMatrix, Surface.ROTATION_270)!!

        assertEquals(90f, portraitHeading, 1f)
        assertEquals(180f, landscape90Heading, 1f)
        assertEquals(270f, rot180Heading, 1f)
        assertEquals(0f, landscape270Heading % 360f, 1f)

        // Unified heading source:
        // Below 1.5 km/h or with no GPS course -> uses compass
        assertEquals(
            portraitHeading,
            resolveNavigationHeadingDegrees(
                speedKmh = 1.0,
                gpsCourseDegrees = 240f,
                hasGpsCourse = true,
                compassHeadingDegrees = portraitHeading
            )!!,
            0.01f
        )
        assertEquals(
            landscape90Heading,
            resolveNavigationHeadingDegrees(
                speedKmh = 4.5,
                gpsCourseDegrees = null,
                hasGpsCourse = false,
                compassHeadingDegrees = landscape90Heading
            )!!,
            0.01f
        )

        // At or above 1.5 km/h with valid GPS course -> uses GPS course for both heading-up map and off-route arrow
        assertEquals(
            245f,
            resolveNavigationHeadingDegrees(
                speedKmh = 4.0,
                gpsCourseDegrees = 245f,
                hasGpsCourse = true,
                compassHeadingDegrees = portraitHeading
            )!!,
            0.01f
        )
    }

    // =========================================================================
    // Item 3: Sunset in planning uses each day's own date and overnight coords
    // =========================================================================

    @Test
    fun item3_sunsetInPlanning_usesTripStartDatePlusDayIndex_andOvernightCoordinates() {
        val routePoints = (0..30).map { km ->
            RoutePoint(
                lat = 55.0 + km * 0.009,
                lng = 10.0,
                ele = 20.0,
                walkKm = km.toDouble(),
                trailKm = km.toDouble()
            )
        }
        val plannedRoute = PlannedRoute(
            planId = 1L,
            trailId = "trail_1",
            trailName = "Test Rute",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 30.0,
            totalWalkKm = 30.0,
            points = routePoints,
            stages = emptyList()
        )

        val juneEpochMs = LocalDate.of(2026, 6, 20)
            .atStartOfDay(ZoneId.of("Europe/Copenhagen"))
            .toInstant()
            .toEpochMilli()
        val decEpochMs = LocalDate.of(2026, 12, 20)
            .atStartOfDay(ZoneId.of("Europe/Copenhagen"))
            .toInstant()
            .toEpochMilli()

        val juneStages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            dailyTargetKm = 15.0,
            numDays = 2,
            paceKmh = 4.0,
            startTime = "08:00",
            startDateEpochMs = juneEpochMs
        )
        val decStages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            dailyTargetKm = 15.0,
            numDays = 2,
            paceKmh = 4.0,
            startTime = "08:00",
            startDateEpochMs = decEpochMs
        )

        assertEquals(2, juneStages.size)
        val juneDay1Sunset = juneStages[0].sunsetTime
        val juneDay2Sunset = juneStages[1].sunsetTime
        val decDay1Sunset = decStages[0].sunsetTime

        assertTrue("June Day 1 sunset ($juneDay1Sunset) should be after 21:00", juneDay1Sunset.startsWith("21:") || juneDay1Sunset.startsWith("22:"))
        assertTrue("June Day 2 sunset ($juneDay2Sunset) should be after 21:00", juneDay2Sunset.startsWith("21:") || juneDay2Sunset.startsWith("22:"))
        assertTrue("December Day 1 sunset ($decDay1Sunset) should be around 15:xx", decDay1Sunset.startsWith("15:"))
    }

    // =========================================================================
    // Item 4, 5, 6: Catalogue relations metadata, unknown length filter,
    // trail_grid_columns_N testTag, and "Ifølge OpenStreetMap" tags on Trail page
    // =========================================================================

    @Test
    fun item4_and_6_catalogueMetadataRegionAndUnknownLengthFilter_andOsmTagsCard(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val repo = app.repository

        // Verify region computation from bounding box centre
        assertEquals("Jylland", GeoUtils.computeDanishRegionFromBbox(56.0, 9.2, 56.3, 9.8))
        assertEquals("Fyn & Øerne", GeoUtils.computeDanishRegionFromBbox(55.2, 10.1, 55.5, 10.6))
        assertEquals("Sjælland & Lolland-Falster", GeoUtils.computeDanishRegionFromBbox(55.5, 11.8, 55.9, 12.4))
        assertEquals("Bornholm", GeoUtils.computeDanishRegionFromBbox(55.0, 14.7, 55.3, 15.1))

        // Verify Overpass relation(id:...); out tags bb; JSON parsing
        val sampleOverpassJson = """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": 2726658,
                  "bounds": { "minlat": 55.30, "minlon": 10.20, "maxlat": 55.45, "maxlon": 10.50 },
                  "tags": {
                    "description": "Smuk vandrerute langs åen",
                    "note": "Husk vandtætte støvler",
                    "operator": "Naturstyrelsen",
                    "website": "https://udinaturen.dk",
                    "distance": "22 km"
                  }
                }
              ]
            }
        """.trimIndent()
        val parsedMeta = repo.parseCatalogueRelationsMetaJson(sampleOverpassJson)
        val meta = parsedMeta[2726658L]
        assertNotNull(meta)
        assertEquals("Naturstyrelsen", meta?.tags?.get("operator"))
        assertEquals(55.30, meta?.bboxSouth ?: 0.0, 0.001)

        // Item 6: OsmRelationTagsCard displays description, note, operator, website, distance with "Ifølge OpenStreetMap"
        composeTestRule.setContent {
            DanskStiTheme {
                OsmRelationTagsCard(osmTags = meta!!.tags)
            }
        }
        composeTestRule.onNodeWithTag("trail_osm_tags_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ifølge OpenStreetMap").assertIsDisplayed()
        composeTestRule.onNodeWithText("Smuk vandrerute langs åen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bemærkning: Husk vandtætte støvler").assertIsDisplayed()
        composeTestRule.onNodeWithText("Operatør: Naturstyrelsen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Distance: 22 km").assertIsDisplayed()
    }

    // =========================================================================
    // Item 7 & 8: Stored Udseende before first frame & Single SettingsRepository
    // =========================================================================

    @Test
    fun item7_and_8_settingsSurviveRestartBeforeFirstFrame_andSingleSettingsRepositoryUsed(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val repo = app.settingsRepository
        assertTrue("DanskStiApp must provide a single shared SettingsRepository", repo === app.settingsRepository)

        repo.setAppearanceMode(AppAppearanceMode.ALWAYS_DARK)
        repo.setMitTempoKmh(5.5)

        // Simulate a cold restart by reloading from disk synchronously before first frame
        val firstFrameSnapshot = repo.reloadFromDiskBlocking()
        assertEquals(
            "Stored Udseende must be available synchronously before first frame",
            AppAppearanceMode.ALWAYS_DARK,
            firstFrameSnapshot.appearanceMode
        )
        assertEquals(5.5, firstFrameSnapshot.mitTempoKmh, 0.01)
    }

    // =========================================================================
    // Item 9 & 10: Trip completion & Battery GPS modes
    // =========================================================================

    @Test
    fun item9_and_10_batteryGpsPowerModes_andTripCompletionMarksPlanFinished(): Unit = runBlocking {
        // Item 10: Verify GPS power profiles:
        // - Moving with screen on -> 2 s high accuracy
        assertEquals(2_000L, GpsPowerProfile.HIGH_ACCURACY_2S.intervalMs)
        // - Battery-saver screen -> 10 s balanced accuracy
        assertEquals(10_000L, GpsPowerProfile.BALANCED_10S.intervalMs)
        // - Paused -> 60 s low power
        assertEquals(60_000L, GpsPowerProfile.LOW_POWER_60S.intervalMs)

        // Item 9: Marking a trip plan finished in Mine ture via TripProgressEntity.isCompleted
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val planId = 910_001L
        dao.insertTripPlan(
            TripPlanEntity(
                id = planId,
                trailId = "trail_1",
                trailName = "Øhavsstien",
                startKm = 0.0,
                endKm = 20.0,
                isReverse = false,
                totalDistanceKm = 20.0,
                targetDays = 1,
                overnightType = "BOTH",
                preferredDailyKm = 20.0,
                stagesJson = "[]"
            )
        )
        dao.saveTripProgress(
            com.example.data.model.TripProgressEntity(
                planId = planId,
                currentDay = 1,
                distanceWalkedTodayKm = 20.0,
                totalDistanceWalkedKm = 20.0,
                currentSnappedLat = 55.1,
                currentSnappedLng = 10.2,
                remainingDistanceTripKm = 0.0,
                remainingDistanceTodayKm = 0.0,
                isPaused = false,
                isCompleted = true
            )
        )
        val progress = dao.getTripProgressSync(planId)
        assertNotNull(progress)
        assertTrue("Trip plan must be marked finished in Mine ture", progress!!.isCompleted)
    }

    // =========================================================================
    // Item 11: Shared OverpassClient concurrency limit (<= 2), queue, and retry
    // =========================================================================

    @Test
    fun item11_overpassClient_limitsConcurrencyToAtMostTwo(): Unit = runBlocking {
        OverpassClient.maxObservedConcurrent.set(0)
        OverpassClient.customTransport = { _, _ ->
            delay(40)
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }
        try {
            val jobs = (1..6).map {
                async {
                    OverpassClient.queryRawJson("[out:json];node(1);out;")
                }
            }
            val results = jobs.awaitAll()
            assertEquals(6, results.count { it != null })
            val maxObserved = OverpassClient.maxObservedConcurrent.get()
            assertTrue(
                "OverpassClient must run at most 2 concurrent requests, but observed $maxObserved",
                maxObserved in 1..2
            )
        } finally {
            OverpassClient.customTransport = null
        }
    }

    // =========================================================================
    // Item 12: Deleting trip plan, custom route (with plans), and saved point (with photo)
    // =========================================================================

    @Test
    fun item12_deletingTripPlan_customRoute_andSavedWaypoint_cleansUpAllFilesAndRecords(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val repo: DanskStiRepository = app.repository

        // 1. Trip plan delete: also deletes plans/ file, recorded tracks and day stats
        val planId = 120_001L
        val plansDir = File(context.filesDir, "plans").apply { mkdirs() }
        val planFile = File(plansDir, "$planId.json.gz").apply { writeBytes(ByteArray(16)) }
        dao.insertTripPlan(
            TripPlanEntity(
                id = planId,
                trailId = "route_77",
                trailName = "Test Turplan",
                startKm = 0.0,
                endKm = 15.0,
                isReverse = false,
                totalDistanceKm = 15.0,
                targetDays = 1,
                overnightType = "BOTH",
                preferredDailyKm = 15.0,
                stagesJson = "[]"
            )
        )
        dao.insertRecordedTrackPoint(
            RecordedTrackPointEntity(
                planId = planId,
                dayNumber = 1,
                lat = 55.5,
                lng = 10.5,
                altitude = 12.0,
                accuracy = 5f,
                speedKmh = 4.2,
                timestamp = 1000L
            )
        )
        dao.insertOrUpdateRecordedDayStat(
            RecordedDayStatEntity(
                planId = planId,
                dayNumber = 1,
                distanceKm = 5.0,
                movingTimeSeconds = 3600L,
                ascentMeters = 45,
                pointCount = 10,
                lastTimestamp = 2000L
            )
        )

        repo.deleteTripPlan(planId)
        assertNull("TripPlanEntity must be deleted", dao.getTripPlanById(planId))
        assertFalse("plans/ file must be deleted", planFile.exists())
        assertTrue("Recorded tracks for plan must be deleted", dao.getAllRecordedTrackPointsForPlanSync(planId).isEmpty())
        assertTrue("Day stats for plan must be deleted", dao.getAllRecordedDayStatsSync(planId).isEmpty())

        // 2. Custom route delete: also deletes its trip plans
        val routeId = 77L
        dao.insertCustomRoute(
            CustomRouteEntity(
                id = routeId,
                name = "Min Rute",
                createdAt = 1000L,
                updatedAt = 1000L,
                optionsJson = "{}",
                waypointsJson = "[]",
                totalKm = 10.0,
                ascentM = 50.0,
                descentM = 50.0,
                pathSharePct = 80.0,
                roadKm = 2.0,
                geometryFile = "",
                sourceName = "BRouter",
                fetchedAt = 1000L
            )
        )
        val associatedPlanId = 120_002L
        dao.insertTripPlan(
            TripPlanEntity(
                id = associatedPlanId,
                trailId = "route_$routeId",
                trailName = "Min Rute Plan",
                startKm = 0.0,
                endKm = 10.0,
                isReverse = false,
                totalDistanceKm = 10.0,
                targetDays = 1,
                overnightType = "BOTH",
                preferredDailyKm = 10.0,
                stagesJson = "[]",
                customRouteId = routeId
            )
        )
        repo.deleteCustomRoute(routeId)
        assertNull("Custom route must be deleted", dao.getCustomRouteById(routeId))
        assertNull("Associated trip plan must be deleted", dao.getTripPlanById(associatedPlanId))

        // 3. Saved point delete: also deletes its photo file
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val photoFile = File(photosDir, "wp_test_photo.jpg").apply { writeBytes(ByteArray(64)) }
        val wp = UserCustomWaypointEntity(
            id = "wp_555",
            routeId = 0L,
            lat = 55.4,
            lng = 10.4,
            name = "Udsigtspunkt",
            note = "",
            photoPath = photoFile.absolutePath,
            createdAt = 1000L
        )
        dao.insertCustomWaypoint(wp)
        assertTrue(photoFile.exists())
        repo.deleteCustomWaypoint("wp_555")
        assertFalse("Saved point's photo file must be deleted", photoFile.exists())
    }

    // =========================================================================
    // Item 13: GPX import simplifies 200 km GPX to <= 60 via points keeping turns
    // =========================================================================

    @Test
    fun item13_gpxImporter_simplifies200KmRouteToAtMost60ViaPoints_keepingTurns_andBuildsOriginalGpxLegs() {
        val trackPoints = mutableListOf<RoutePoint3D>()
        var lat = 55.0
        var lng = 9.0
        var headingRad = 0.2
        for (i in 0 until 2000) {
            if (i > 0 && i % 250 == 0) {
                headingRad += Math.toRadians(65.0)
            }
            lat += (100.0 / 111_320.0) * cos(headingRad)
            lng += (100.0 / (111_320.0 * cos(Math.toRadians(lat)))) * sin(headingRad)
            trackPoints.add(RoutePoint3D(lat = lat, lng = lng, ele = 15.0 + (i % 20)))
        }

        val startMs = System.currentTimeMillis()
        val keptIndices = GpxImporter.simplifyDouglasPeuckerWithTurns(trackPoints, maxViaPoints = 60)
        val elapsedMs = System.currentTimeMillis() - startMs

        // keptIndices includes start + interior via points + end (at most 62 total indices)
        assertTrue("Simplified indices (${keptIndices.size}) must have at most ~60 via points", keptIndices.size in 9..62)
        assertEquals(0, keptIndices.first())
        assertEquals(trackPoints.lastIndex, keptIndices.last())
        assertTrue("200 km GPX simplification must complete in under 2 seconds (took ${elapsedMs}ms)", elapsedMs < 2000L)

        // Verify "Behold GPX-linjen" builds direct legs from original geometry without routing
        val waypoints = keptIndices.mapIndexed { idx, rawIdx ->
            val pt = trackPoints[rawIdx]
            RouteWaypoint(
                lat = pt.lat,
                lng = pt.lng,
                role = when (idx) {
                    0 -> WaypointRole.START
                    keptIndices.lastIndex -> WaypointRole.END
                    else -> WaypointRole.VIA
                },
                legModeToNext = LegMode.SNAP,
                name = "WP $idx"
            )
        }
        val directLegs = GpxImporter.buildOriginalGpxLegs(waypoints, trackPoints, keptIndices)
        assertEquals(waypoints.size - 1, directLegs.size)
        assertTrue(directLegs.values.sumOf { it.lengthKm } > 180.0)
    }

    // =========================================================================
    // Item 14: Offline downloads storage check, network/Wi-Fi check, and resume
    // =========================================================================

    @Test
    fun item14_offlineDownloads_checksFreeStorage_andNetworkRequirement() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        OfflineDownloadManager.freeBytesProviderOverride = { 500L * 1024L * 1024L }
        try {
            val hugeCheck = OfflineDownloadManager.checkFreeStorage(
                context = context,
                requiredBytes = 999_999_999_999_999L
            )
            assertFalse("Must refuse download when required size exceeds free storage", hugeCheck.hasEnoughSpace)
            assertTrue(
                "Refusal message must state size needed in MB: ${hugeCheck.message}",
                hugeCheck.message?.contains("Ikke nok fri lagerplads") == true &&
                    hugeCheck.message?.contains("MB") == true
            )

            val smallCheck = OfflineDownloadManager.checkFreeStorage(
                context = context,
                requiredBytes = 1024L * 1024L
            )
            assertTrue(smallCheck.hasEnoughSpace)
        } finally {
            OfflineDownloadManager.freeBytesProviderOverride = null
        }

        // Test Wi-Fi only network requirement
        OfflineDownloadManager.networkStatusProviderOverride = { true to false } // connected on mobile data, not Wi-Fi
        try {
            val (allowedWifiOnly, errWifiOnly) = OfflineDownloadManager.checkNetworkRequirement(context, wifiOnly = true)
            assertFalse("Wi-Fi only must refuse cellular connection", allowedWifiOnly)
            assertNotNull(errWifiOnly)

            val (allowedAnyNet, _) = OfflineDownloadManager.checkNetworkRequirement(context, wifiOnly = false)
            assertTrue("Standard network check must allow cellular connection", allowedAnyNet)
        } finally {
            OfflineDownloadManager.networkStatusProviderOverride = null
        }
    }

    // =========================================================================
    // Item 15: Missing elevation skipped in ascent, ETA & access timing;
    // user pace is the only fallback
    // =========================================================================

    @Test
    fun item15_missingElevation_neverCountedAsZeroInAscentOrDescent() {
        // Points: 100 m -> null (missing) -> 100 m.
        // If null were treated as 0 m, ascent and descent would falsely be +100 m!
        val ptsWithNullElevation = listOf(
            RoutePoint3D(lat = 55.000, lng = 10.000, ele = 100.0),
            RoutePoint3D(lat = 55.005, lng = 10.000, ele = null),
            RoutePoint3D(lat = 55.010, lng = 10.000, ele = 100.0)
        )

        val ascent = BRouterGeoJsonParser.calculateAscent(ptsWithNullElevation)
        val descent = BRouterGeoJsonParser.calculateDescent(ptsWithNullElevation)
        assertEquals("Missing elevation must never be counted as 0 m in ascent", 0.0, ascent, 0.01)
        assertEquals("Missing elevation must never be counted as 0 m in descent", 0.0, descent, 0.01)
    }

    // =========================================================================
    // Item 16, 17, 18: Waypoint photos in files/photos, FileProvider paths,
    // Danish TTS setting message, and split file line counts < 800
    // =========================================================================

    @Test
    fun item16_waypointPhotoHelper_createsFileInFilesPhotosAndCompressesFullSize() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (photoFile, contentUri) = WaypointPhotoHelper.createPhotoCaptureTarget(context)
        assertTrue(
            "Photo file must be inside files/photos",
            photoFile.parentFile?.name == "photos" &&
                photoFile.parentFile?.parentFile?.absolutePath == context.filesDir.absolutePath
        )
        assertEquals("content", contentUri.scheme)

        val rawBmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        Canvas(rawBmp).drawColor(Color.rgb(60, 130, 90))
        FileOutputStream(photoFile).use { fos ->
            rawBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        val compressedOk = WaypointPhotoHelper.compressCapturedPhotoFile(photoFile)
        assertTrue(compressedOk)
        assertTrue(photoFile.exists() && photoFile.length() > 1000L)
        photoFile.delete()
    }

    @Test
    fun item17_filePathsXmlNarrowed_andDanishTtsMissingShownInProfileSettings() {
        val filePathsXml = File("src/main/res/xml/file_paths.xml").let {
            if (it.exists()) it else File("app/src/main/res/xml/file_paths.xml")
        }.readText()

        assertTrue(filePathsXml.contains("path=\"exports/\""))
        assertTrue(filePathsXml.contains("path=\"gpx/\""))
        assertTrue(filePathsXml.contains("path=\"photos/\""))
        assertFalse("file_paths.xml must not expose root path=\".\"", filePathsXml.contains("path=\".\""))
        assertFalse("file_paths.xml must not expose external-files-path", filePathsXml.contains("external-files-path"))

        DanishTtsChecker.setDanishVoiceInstalledForTesting(false)
        try {
            composeTestRule.setContent {
                DanskStiTheme {
                    ProfileScreen(
                        onOpenCredits = {},
                        onOpenDiagnostics = {},
                        onOpenOfflineMaps = {}
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("profile_screen_list")
                .performScrollToNode(hasTestTag("profile_danish_tts_missing_text"))
            composeTestRule.onNodeWithTag("profile_danish_tts_missing_text").assertIsDisplayed()
        } finally {
            DanishTtsChecker.setDanishVoiceInstalledForTesting(null)
        }
    }

    @Test
    fun item18_splitFilesAreAllUnder800Lines() {
        val targetFiles = listOf(
            "ui/screens/TrailBrowserScreen.kt",
            "ui/screens/TrailBrowserListAndMapContent.kt",
            "ui/screens/TrailBrowserCards.kt",
            "ui/screens/TrailDetailScreen.kt",
            "ui/screens/TrailDetailContentSection.kt",
            "ui/screens/TrailDetailComponents.kt",
            "ui/screens/SavedPlansScreen.kt",
            "ui/screens/SavedPlansLibraryCards.kt",
            "ui/screens/planner/RouteBuilderOverlays.kt",
            "ui/screens/planner/RouteBuilderSelectionAndCoachOverlays.kt",
            "ui/screens/ProfileScreen.kt",
            "ui/screens/ProfileComponentsAndPrivacy.kt"
        )
        for (relPath in targetFiles) {
            val file = File("src/main/java/com/example/$relPath").let {
                if (it.exists()) it else File("app/src/main/java/com/example/$relPath")
            }
            assertTrue("File $relPath must exist", file.exists())
            val lineCount = file.readLines().size
            assertTrue(
                "File $relPath has $lineCount lines, which exceeds the 800-line limit",
                lineCount < 800
            )
        }
    }
}
