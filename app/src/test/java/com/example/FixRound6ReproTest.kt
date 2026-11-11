package com.example

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.WayTagRangeDto
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.screens.computeMeasuredDistanceMeters
import com.example.util.DanskStiTileSourceHelper
import com.example.util.routing.TrailSlicer
import java.io.File
import kotlin.math.abs
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound6ReproTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.repository.isOnlineOverrideForTests = false
        DanskStiTileSourceHelper.userConfirmedOsmFallback = true
        runBlocking {
            DanskStiApp.getSettingsRepository(app).setHasSeenHowToGuide(true)
            app.repository.initializePreseededDataIfEmpty()
            val pts = (0..150).map { idx ->
                GeoPointDto(
                    lat = 55.0200 + idx * 0.0008,
                    lng = 10.6000 + idx * 0.0010,
                    ele = 5.0 + (idx % 12)
                )
            }
            val geom = TrailGeometryDto(
                trailId = "trail_ohavsstien",
                osmRelationId = 1202410L,
                segments = listOf(
                    TrailSegmentDto(
                        type = TrailSegmentType.WALK,
                        points = pts,
                        lengthKm = 220.0,
                        highway = "path",
                        surface = "dirt",
                        wayTagRanges = listOf(
                            WayTagRangeDto(fromKm = 0.0, toKm = 150.0, highway = "path", surface = "dirt"),
                            WayTagRangeDto(fromKm = 150.0, toKm = 220.0, highway = "tertiary", surface = "asphalt")
                        )
                    )
                ),
                sideBranches = emptyList(),
                gaps = emptyList(),
                totalWalkingKm = 220.0,
                totalAscentM = 450,
                totalDescentM = 440,
                gapsOver200mCount = 0,
                bboxNorth = pts.maxOf { it.lat },
                bboxSouth = pts.minOf { it.lat },
                bboxEast = pts.maxOf { it.lng },
                bboxWest = pts.minOf { it.lng }
            )
            TrailGeometryRepository(app).saveGeometry(geom)
            val trail = app.database.dao().getTrailById("trail_ohavsstien")
            if (trail != null) {
                app.database.dao().insertTrails(
                    listOf(
                        trail.copy(
                            isGeometryDownloaded = true,
                            computedWalkingKm = 220.0,
                            bboxNorth = geom.bboxNorth,
                            bboxSouth = geom.bboxSouth,
                            bboxEast = geom.bboxEast,
                            bboxWest = geom.bboxWest
                        )
                    )
                )
            }
        }
        runCatching {
            composeTestRule.waitForIdle()
            if (composeTestRule.onAllNodes(androidx.compose.ui.test.hasTestTag("how_to_guide_got_it_button")).fetchSemanticsNodes().isNotEmpty()) {
                composeTestRule.onNode(androidx.compose.ui.test.hasTestTag("how_to_guide_got_it_button")).performClick()
                composeTestRule.waitForIdle()
            }
        }
    }

    @org.junit.After
    fun tearDown() {
        app.repository.isOnlineOverrideForTests = null
    }

    private fun tapMapAt(lat: Double, lng: Double) {
        composeTestRule.runOnUiThread {
            val mapView = findMapViewByTag(composeTestRule.activity.window.decorView, "route_builder_map")
            val eventsOverlay = mapView?.overlays?.filterIsInstance<MapEventsOverlay>()?.firstOrNull()
            if (eventsOverlay != null) {
                val field = MapEventsOverlay::class.java.getDeclaredField("mReceiver")
                field.isAccessible = true
                val receiver = field.get(eventsOverlay) as? MapEventsReceiver
                receiver?.singleTapConfirmedHelper(GeoPoint(lat, lng))
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun findMapViewByTag(root: View?, tag: String): MapView? {
        if (root == null) return null
        if (root is MapView && (root.tag == tag || tag.isEmpty())) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findMapViewByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    @Test
    fun item1And3_plannerBottomSheetDoesNotCrashInFreeMode0_1_3PointsAndOhavsstienTrailMode() {
        composeTestRule.onNodeWithTag("nav_map_tab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kort_tool_plan_route").performClick()
        composeTestRule.waitForIdle()

        // 0 points: switch sheet to "Dage" and "Detaljer"
        composeTestRule.onNodeWithTag("btn_sheet_half").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_sheet_full").performClick()
        composeTestRule.waitForIdle()
        // With 0 points, elevation chart shows "Ingen højdedata tilgængelig"
        assertTrue(
            composeTestRule.onAllNodesWithTag("elevation_no_data_text").fetchSemanticsNodes().isNotEmpty()
        )
        composeTestRule.onNodeWithTag("btn_sheet_peek").performClick()
        composeTestRule.waitForIdle()

        // Switch to straight leg mode before adding points so points have 0 elevation and route immediately without network calls
        composeTestRule.onNodeWithTag("btn_toggle_leg_mode").performClick()
        composeTestRule.waitForIdle()

        // 1 point: switch sheet to "Dage" and "Detaljer"
        tapMapAt(56.010, 10.210)
        composeTestRule.onNodeWithTag("btn_sheet_half").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_sheet_full").performClick()
        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithTag("elevation_no_data_text").fetchSemanticsNodes().isNotEmpty()
        )
        composeTestRule.onNodeWithTag("btn_sheet_peek").performClick()
        composeTestRule.waitForIdle()

        // 3 points in FREE mode: tap map twice more and switch sheet to "Dage" and "Detaljer"
        tapMapAt(56.020, 10.220)
        tapMapAt(56.030, 10.230)
        composeTestRule.onNodeWithTag("btn_sheet_half").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_sheet_full").performClick()
        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithTag("route_bottom_sheet").fetchSemanticsNodes().isNotEmpty()
        )

        // Øhavsstien in TRAIL mode (Round 10A Item 7: back first collapses expanded sheet, then prompts Gem ruten?)
        composeTestRule.onNodeWithTag("btn_back_route_builder").performClick()
        composeTestRule.waitForIdle()
        if (composeTestRule.onAllNodesWithTag("btn_back_route_builder").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithTag("btn_back_route_builder").performClick()
            composeTestRule.waitForIdle()
        }
        if (composeTestRule.onAllNodesWithTag("btn_unsaved_discard").fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithTag("btn_unsaved_discard").performClick()
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithTag("nav_explore_tab").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("trail_card_trail_ohavsstien"))
        composeTestRule.onNodeWithTag("trail_card_trail_ohavsstien").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("plan_trip_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_sheet_half").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_sheet_full").performClick()
        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithTag("route_bottom_sheet").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun item2_withoutLocationExploreShowsNoDistancesFromYou() {
        // Without a GPS fix, Udforsk must not show any "fra dig" distance labels
        composeTestRule.waitForIdle()
        val fromYouNodes = composeTestRule.onAllNodesWithText("fra dig", substring = true).fetchSemanticsNodes()
        assertTrue("Expected no 'fra dig' distances when location is off, but found ${fromYouNodes.size}", fromYouNodes.isEmpty())
    }

    @Test
    fun item4_twoPointsOneKmApartMeasureOneKmPlusMinusOnePercent() {
        // 1 km north along longitude 10.0 at latitude 56.0 is ~0.0089932 degrees latitude
        val p1 = GeoPoint(56.000000, 10.000000)
        val p2 = GeoPoint(56.0089932, 10.000000)
        val measuredMeters = computeMeasuredDistanceMeters(listOf(p1, p2))
        val measuredKm = measuredMeters / 1000.0
        assertTrue(
            "Expected two points 1 km apart to measure 1.0 km +/- 1 %, but got $measuredKm km",
            abs(measuredKm - 1.0) <= 0.01
        )
    }

    @Test
    fun item5_roomMigration23To24DeletesFakeOperatorAndNonOverpassMarkedRoutes() {
        val db = app.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM marked_routes")
        db.execSQL("DELETE FROM marked_route_cache_boxes")

        db.execSQL(
            """
            INSERT INTO marked_routes (osmRelationId, name, lengthKm, operator, routeType, network, symbol, colorHex, segmentsJson, minLat, maxLat, minLng, maxLng, cachedAt)
            VALUES (111, 'Fake Op Route', 10.0, 'Naturstyrelsen / Dansk Vandrelaug', 'hiking', 'lwn', NULL, '#2E7D32', '[[[55.0,10.0],[55.1,10.1]]]', 55.0, 55.1, 10.0, 10.1, 1000)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO marked_routes (osmRelationId, name, lengthKm, operator, routeType, network, symbol, colorHex, segmentsJson, minLat, maxLat, minLng, maxLng, cachedAt)
            VALUES (222, 'No Overpass Geom', 8.0, 'Kommune', 'hiking', 'lwn', NULL, '#2E7D32', '[]', 55.0, 55.1, 10.0, 10.1, 1000)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO marked_routes (osmRelationId, name, lengthKm, operator, routeType, network, symbol, colorHex, segmentsJson, minLat, maxLat, minLng, maxLng, cachedAt)
            VALUES (333, 'Valid OSM Route', 15.0, 'Naturstyrelsen', 'hiking', 'rwn', NULL, '#2E7D32', '[[[55.0,10.0],[55.1,10.1]]]', 55.0, 55.1, 10.0, 10.1, 1000)
            """.trimIndent()
        )

        DanskStiDatabase.MIGRATION_23_24.migrate(db)

        val remainingIds = mutableListOf<Long>()
        db.query("SELECT osmRelationId FROM marked_routes").use { cursor ->
            while (cursor.moveToNext()) {
                remainingIds.add(cursor.getLong(0))
            }
        }
        assertEquals(listOf(333L), remainingIds)
    }

    @Test
    fun item6_backupRulesIncludeOnlyDatabaseAndSettingsAndExcludeActualFolders() {
        val backupXml = File("src/main/res/xml/backup_rules.xml").readText()
        val extractionXml = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (xml in listOf(backupXml, extractionXml)) {
            assertTrue(xml.contains("domain=\"database\""))
            assertTrue(xml.contains("domain=\"sharedpref\""))
            for (folder in listOf("trails", "routes", "plans", "geofa", "osm_pois", "offline", "brouter", "osmdroid", "photos")) {
                assertTrue("Expected $folder in backup rules", xml.contains("path=\"$folder\""))
            }
            for (nonExistent in listOf("trail_geometries", "geofa_cache", "osm_pois_cache")) {
                assertFalse("Unexpected nonexistent folder $nonExistent in backup rules", xml.contains(nonExistent))
            }
        }
    }

    @Test
    fun item7_trailWayTagsStoredAsKmRangesAndUsedByTrailSlicer() {
        val p0 = GeoPointDto(56.000000, 10.000000, 10.0)
        val p1 = GeoPointDto(56.008993, 10.000000, 15.0) // ~1.0 km
        val p2 = GeoPointDto(56.017986, 10.000000, 20.0) // ~2.0 km
        val segment = TrailSegmentDto(
            type = TrailSegmentType.WALK,
            points = listOf(p0, p1, p2),
            lengthKm = 2.0,
            highway = "path",
            surface = "dirt",
            wayTagRanges = listOf(
                WayTagRangeDto(fromKm = 0.0, toKm = 1.0, highway = "path", surface = "dirt"),
                WayTagRangeDto(fromKm = 1.0, toKm = 2.0, highway = "secondary", surface = "asphalt")
            )
        )

        val leg = TrailSlicer.sliceTrailLeg(
            fromWaypointId = "wp_start",
            toWaypointId = "wp_end",
            segments = listOf(segment),
            fromKm = 0.0,
            toKm = 2.0
        )
        assertEquals(2, leg.trailSegments.first().wayTagRanges.size)
        // First point lies on path/dirt, last point lies on secondary/asphalt
        assertEquals("path", leg.wayInfo.first().highway)
        assertEquals("dirt", leg.wayInfo.first().surface)
        assertEquals("secondary", leg.wayInfo.last().highway)
        assertEquals("asphalt", leg.wayInfo.last().surface)
        // 1 km path + 1 km road -> ~50% path share and ~1.0 km road
        assertNotNull(leg.pathSharePct)
        assertEquals(50.0, leg.pathSharePct!!, 2.0)
        assertEquals(1.0, leg.roadKm, 0.05)
    }
}
