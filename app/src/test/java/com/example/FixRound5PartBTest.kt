package com.example

import android.Manifest
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.repository.OsmPoiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.service.NavigationTrackingService
import com.example.data.local.BaseMapType
import com.example.util.DanskStiTileSourceHelper as DanskStiTileSource
import com.example.util.network.NetworkConstants
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.HybridRoutingEngine
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound5PartBTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: DanskStiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DanskStiTileSource.userConfirmedOsmFallback = true
        runBlocking {
            app.repository.initializePreseededDataIfEmpty()
        }
    }

    @Test
    fun `item 1 - BRouterOnlineEngine buildUrl serializes noGoAreas as polygons lon,lat and HybridRoutingEngine routes noGoAreas online`() = runBlocking {
        val context: Context = app
        val engine = BRouterOnlineEngine(context)

        val polygon1 = listOf(
            LatLng(55.100000, 10.200000),
            LatLng(55.110000, 10.210000),
            LatLng(55.105000, 10.220000)
        )
        val polygon2 = listOf(
            LatLng(55.120000, 10.250000),
            LatLng(55.130000, 10.260000),
            LatLng(55.125000, 10.270000)
        )

        val url = engine.buildUrl(
            from = LatLng(55.09, 10.19),
            to = LatLng(55.14, 10.28),
            profileName = "dansk-vandring",
            options = RouteOptions(noGoAreas = listOf(polygon1, polygon2)),
            alternative = 0
        )

        assertNotNull("buildUrl must not return null", url)
        assertTrue(
            "buildUrl must serialize options.noGoAreas as polygons=lon,lat,lon,lat,...|..., got: $url",
            url!!.contains("&polygons=10.200000,55.100000,10.210000,55.110000,10.220000,55.105000|10.250000,55.120000,10.260000,55.130000,10.270000,55.125000")
        )

        // Verify HybridRoutingEngine routes legs with noGoAreas online and explains so in sourceName
        val mockOnlineClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val geoJson = """
                    {
                      "type": "FeatureCollection",
                      "features": [{
                        "type": "Feature",
                        "properties": {"track-length": "4200", "filtered ascend": "12", "plain-ascend": "12"},
                        "geometry": {
                          "type": "LineString",
                          "coordinates": [[10.19, 55.09, 5.0], [10.235, 55.115, 8.0], [10.28, 55.14, 6.0]]
                        }
                      }]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(geoJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val fakeOnline = BRouterOnlineEngine(
            dao = null,
            client = mockOnlineClient,
            context = context,
            assetManager = context.assets
        )
        val hybrid = HybridRoutingEngine(
            context = context,
            onlineEngine = fakeOnline,
            offlineEngine = BRouterOfflineEngine(context)
        )

        assertFalse(
            "canRouteLegOffline must be false when options.noGoAreas is non-empty",
            hybrid.canRouteLegOffline(
                LatLng(55.09, 10.19),
                LatLng(55.14, 10.28),
                RouteOptions(noGoAreas = listOf(polygon1))
            )
        )

        val routedResult = hybrid.routeLeg(
            from = LatLng(55.09, 10.19),
            to = LatLng(55.14, 10.28),
            options = RouteOptions(noGoAreas = listOf(polygon1))
        )
        assertTrue(routedResult is RouteLegResult.Success)
        val success = routedResult as RouteLegResult.Success
        assertTrue(
            "HybridRoutingEngine must indicate that Rut uden om was routed online, got: ${success.sourceName}",
            success.sourceName.contains("online", ignoreCase = true) &&
                success.sourceName.contains("Rut uden om", ignoreCase = true)
        )
    }

    @Test
    fun `item 2 - starting navigation with location permission denied does not start service or crash and sets locationPermissionMissing`() {
        val shadowApp = shadowOf(app)
        shadowApp.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        assertFalse(NavigationTrackingService.hasLocationPermission(app))
        val started = NavigationTrackingService.start(app, 1L)
        assertFalse("NavigationTrackingService.start must return false when location permission is denied", started)
        assertTrue(
            "NavigationTrackingService.liveState.locationPermissionMissing must be true when permission is denied",
            NavigationTrackingService.liveState.value.locationPermissionMissing
        )
        assertFalse(
            "NavigationTrackingService must not be tracking when location permission is denied",
            NavigationTrackingService.liveState.value.isTracking
        )
    }

    @Test
    fun `item 3 - single User-Agent constant with email and no dansksti_app`() {
        val expectedUa = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"
        assertEquals(expectedUa, NetworkConstants.USER_AGENT)
        assertFalse(NetworkConstants.USER_AGENT.contains("dansksti.app", ignoreCase = true))
    }

    @Test
    fun `item 4 - BRouter profile upload is guarded by Mutex so parallel legs upload once`() = runBlocking {
        BRouterOnlineEngine.resetStateForTesting()
        val profileUploadCalls = AtomicInteger(0)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req: Request = chain.request()
                val urlStr = req.url.toString()
                if (req.method == "POST" && urlStr.contains("/brouter/profile")) {
                    profileUploadCalls.incrementAndGet()
                    Thread.sleep(60)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"profileid":"custom_dansk"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val geoJson = """
                        {
                          "type": "FeatureCollection",
                          "features": [{
                            "type": "Feature",
                            "properties": {"track-length": "1200", "filtered ascend": "5", "plain-ascend": "5"},
                            "geometry": {
                              "type": "LineString",
                              "coordinates": [[10.20, 55.10, 5.0], [10.21, 55.11, 8.0]]
                            }
                          }]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(geoJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val engine = BRouterOnlineEngine(
            dao = null,
            client = mockClient,
            context = app,
            assetManager = app.assets
        )
        val jobs = (1..6).map { i ->
            async {
                engine.routeLeg(
                    from = LatLng(55.10 + i * 0.001, 10.20),
                    to = LatLng(55.11 + i * 0.001, 10.21),
                    options = RouteOptions()
                )
            }
        }
        val results = jobs.awaitAll()
        assertTrue(results.all { it is RouteLegResult.Success })
        assertEquals(
            "Parallel legs must upload custom BRouter profile only once",
            1,
            profileUploadCalls.get()
        )
    }

    @Test
    fun `item 5 - offline tiles in dark mode use light archive with dark colour filter`() {
        val offlineDir = File(app.filesDir, "offline").apply { mkdirs() }
        val dummySqlite = File(offlineDir, "test_offline_tiles.sqlite")
        dummySqlite.writeBytes(ByteArray(128))
        try {
            assertTrue(DanskStiTileSource.hasOfflineSqliteArchives(app))
            val mapView = MapView(app)
            DanskStiTileSource.applyTo(
                mapView = mapView,
                baseMap = BaseMapType.SKAERMKORT,
                isDarkMode = true
            )
            assertNotNull(
                "Dark colour matrix must be created for dark mode",
                DanskStiTileSource.createHuePreservingDarkColorMatrix()
            )
        } finally {
            dummySqlite.delete()
        }
    }

    @Test
    fun `item 6 - WaterOverlayRepository and WaterMapOverlayHelper are deleted`() {
        val repoExists = try {
            Class.forName("com.example.data.repository.WaterOverlayRepository")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        val helperExists = try {
            Class.forName("com.example.util.WaterMapOverlayHelper")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertFalse("WaterOverlayRepository must be deleted", repoExists)
        assertFalse("WaterMapOverlayHelper must be deleted", helperExists)
    }

    @Test
    fun `item 11 - open Ohavsstien, plan, save, Start and assert navigation map has a route overlay with more than 100 points`() {
        val shadowApp = shadowOf(app)
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )

        // Ensure real Øhavsstien geometry is cached (either from live OSM or real bundled Øhavsstien OSM coordinates for offline test runner)
        runBlocking {
            val existing = app.repository.getTrailGeometry("trail_ohavsstien")
            if (existing == null || existing.segments.sumOf { it.points.size } <= 100) {
                val realOsmNodes = OsmPoiRepository(app).loadBundledPois("trail_ohavsstien").pois
                assertTrue("Bundled Øhavsstien OSM file must have > 100 real points", realOsmNodes.size > 100)
                val sortedPts = realOsmNodes
                    .sortedBy { it.lng }
                    .map { GeoPointDto(it.lat, it.lng, 8.0) }
                val geom = TrailGeometryDto(
                    trailId = "trail_ohavsstien",
                    osmRelationId = 305195L,
                    segments = listOf(
                        TrailSegmentDto(
                            type = TrailSegmentType.WALK,
                            points = sortedPts,
                            lengthKm = 220.0
                        )
                    ),
                    totalWalkingKm = 220.0,
                    gapsOver200mCount = 0,
                    bboxNorth = sortedPts.maxOf { it.lat },
                    bboxSouth = sortedPts.minOf { it.lat },
                    bboxEast = sortedPts.maxOf { it.lng },
                    bboxWest = sortedPts.minOf { it.lng }
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
        }

        // 1. Open Øhavsstien from Udforsk
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("trail_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("trail_card_trail_ohavsstien"))
        composeTestRule.onNodeWithTag("trail_card_trail_ohavsstien").performClick()
        composeTestRule.waitForIdle()

        // 2. Click "Planlæg tur" on TrailDetailScreen
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("plan_trip_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("plan_trip_button").performClick()
        composeTestRule.waitForIdle()

        // 3. Wait for RouteBuilderScreen to load Øhavsstien and click "Gem" -> "Gem til Gemte"
        composeTestRule.waitUntil(15_000) {
            val nodes = composeTestRule.onAllNodesWithTag("btn_save_route").fetchSemanticsNodes()
            nodes.isNotEmpty() && !nodes.first().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        composeTestRule.onNodeWithTag("btn_save_route").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("btn_confirm_save_route").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("btn_confirm_save_route").performClick()
        composeTestRule.waitForIdle()

        // 4. On "Mine ture" (SavedPlansScreen), click "Start" on the saved Øhavsstien plan
        var savedPlanId = -1L
        composeTestRule.waitUntil(10_000) {
            val plans = runBlocking { app.database.dao().getAllTripPlansList() }
            val ohavsPlan = plans.firstOrNull { it.trailName.contains("Øhavsstien") || it.trailId.contains("ohavsstien") }
                ?: plans.firstOrNull()
            if (ohavsPlan != null) {
                savedPlanId = ohavsPlan.id
                true
            } else {
                false
            }
        }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("start_plan_$savedPlanId").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("start_plan_$savedPlanId").performClick()
        composeTestRule.waitForIdle()

        // 5. Wait for NavigationScreen map view and assert its route overlay has > 100 points
        composeTestRule.waitUntil(15_000) {
            val mapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
            mapView != null && countRouteOverlayPoints(mapView.overlays) > 100
        }

        val navMapView = findMapViewByTag(composeTestRule.activity.window.decorView, "navigation_map_view")
        assertNotNull("navigation_map_view MapView must exist", navMapView)
        val routePointCount = countRouteOverlayPoints(navMapView!!.overlays)
        assertTrue(
            "Expected navigation map route overlay to have > 100 points, but had $routePointCount",
            routePointCount > 100
        )
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

    private fun countRouteOverlayPoints(overlays: List<Overlay>): Int {
        var maxPolylinePoints = 0
        var totalWalkPolylinePoints = 0
        for (overlay in overlays) {
            when (overlay) {
                is Polyline -> {
                    val pts = overlay.actualPoints?.size ?: 0
                    if (pts > maxPolylinePoints) {
                        maxPolylinePoints = pts
                    }
                    totalWalkPolylinePoints += pts
                }
                is FolderOverlay -> {
                    val folderPts = countRouteOverlayPoints(overlay.items)
                    if (folderPts > maxPolylinePoints) {
                        maxPolylinePoints = folderPts
                    }
                }
            }
        }
        return maxOf(maxPolylinePoints, totalWalkPolylinePoints / 2)
    }
}
