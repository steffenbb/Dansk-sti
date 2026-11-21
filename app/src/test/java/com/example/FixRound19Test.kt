package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.TrailDownloadProgress
import com.example.data.remote.OverpassClient
import com.example.data.repository.MarkedRouteRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.map.DanskStiMap
import com.example.ui.map.DanskStiMapBottomAttributionAndBanners
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.screens.PrivacyScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerInteractionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound19Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: DanskStiDatabase
    private lateinit var dao: DanskStiDao
    private lateinit var geometryRepo: TrailGeometryRepository
    private lateinit var markedRepo: MarkedRouteRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        MarkedRouteRepository.clearProcessWideCache()
        MarkedRouteRepository.resetOversizedCleanupFlagForTests(context)
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = { coords -> coords.map { 15.0 } }

        db = Room.inMemoryDatabaseBuilder(context, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        geometryRepo = TrailGeometryRepository(context)
        markedRepo = MarkedRouteRepository(context, dao, geometryRepo)
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
        MarkedRouteRepository.clearProcessWideCache()
        db.close()
    }

    private fun pumpMainLooperUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    // =========================================================================
    // Item 1: Privacy screen includes ovp.dresel.systems and keeps private.coffee link button
    // =========================================================================

    @Test
    fun item1_privacyScreen_renderedOverpassEntryContainsOvpDreselSystemsAndKeepsPrivateCoffeeLink() {
        val overpassTitle = context.getString(R.string.privacy_service_overpass_title)
        val overpassDesc = context.getString(R.string.privacy_service_overpass_desc)

        assertEquals(
            "overpass-api.de (med skift til overpass.private.coffee eller ovp.dresel.systems, hvis hovedserveren ikke svarer)",
            overpassTitle
        )
        assertTrue(
            "Description must explain fallback servers",
            overpassDesc.contains("Reserveserverne bruges kun, når hovedserveren ikke svarer. De modtager de samme kortudsnit som hovedserveren.")
        )

        composeTestRule.setContent {
            DanskStiTheme {
                PrivacyScreen(onBack = {})
            }
        }

        composeTestRule.onNodeWithTag("privacy_screen_list")
            .performScrollToNode(hasText("ovp.dresel.systems", substring = true))

        composeTestRule.onNodeWithText("ovp.dresel.systems", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Reserveserverne bruges kun, når hovedserveren ikke svarer.", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("privacy_private_coffee_legal_link", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    // =========================================================================
    // Item 2: OverpassClient attemptLogLines bounded buffer of max 50 lines
    // =========================================================================

    @Test
    fun item2_overpassClientAttemptLog_200Requests_bufferSizeIs50AndContainsLast50Lines() = runBlocking {
        val reqCounter = AtomicInteger(0)
        OverpassClient.customSessionTransport = { _, _, session ->
            val n = reqCounter.incrementAndGet()
            session.onHeadersReceived()
            session.onDataChunkReceived()
            OverpassClient.HttpResult(
                code = 200,
                body = """{"elements":[{"id":$n}]}"""
            )
        }

        for (i in 1..200) {
            OverpassClient.queryRawJson(
                query = "[out:json];node($i);out;",
                label = "AttemptLogBoundedTest_$i"
            )
        }

        val snapshot: List<String> = synchronized(OverpassClient.attemptLogLock) {
            OverpassClient.attemptLogLines.toList()
        }
        assertEquals("Buffer size must be capped at 50 lines", 50, snapshot.size)

        // Verify the buffer is an ArrayDeque and contains the exact last 50 lines
        assertTrue(
            "attemptLogLines must be an ArrayDeque",
            OverpassClient.attemptLogLines is ArrayDeque<*>
        )
        for (line in snapshot) {
            assertTrue("Line should be a recorded ok attempt: $line", line.contains("outcome=ok"))
        }

        // Now run 200 requests where each request has a unique elapsed timestamp so we can verify exact last 50 ordering
        OverpassClient.resetMetricsForTest()
        OverpassClient.interServerBackoffMsProvider = { 0L }
        for (i in 1..200) {
            val code = 200
            OverpassClient.customSessionTransport = { _, _, session ->
                session.onHeadersReceived()
                session.onDataChunkReceived()
                // Advance fake clock by i ms so elapsedMs in logAttempt uniquely identifies request #i ("elapsed=${i}ms")
                OverpassClient.currentTimeMillisProvider = { 1_000_000L + i.toLong() }
                OverpassClient.HttpResult(code = code, body = """{"elements":[]}""")
            }
            OverpassClient.currentTimeMillisProvider = { 1_000_000L }
            OverpassClient.queryRawJson(
                query = "[out:json];node($i);out;",
                label = "OrderedAttempt_$i"
            )
        }

        val orderedSnapshot = synchronized(OverpassClient.attemptLogLock) {
            OverpassClient.attemptLogLines.toList()
        }
        assertEquals(50, orderedSnapshot.size)
        for (idx in 0 until 50) {
            val expectedReqNum = 151 + idx
            assertTrue(
                "Expected line $idx to correspond to request #$expectedReqNum (elapsed=${expectedReqNum}ms), but got: ${orderedSnapshot[idx]}",
                orderedSnapshot[idx].contains("elapsed=${expectedReqNum}ms")
            )
        }
    }

    // =========================================================================
    // Item 4: Map taps that should not be stolen by marked routes
    // =========================================================================

    @Test
    fun item4_markedRouteTapsEnabled_plannerTegnVsKig_kortMeasuring_and_trailDetailPreview() = runBlocking {
        val routeEntity = MarkedRouteEntity(
            osmRelationId = 990011L,
            name = "Test Afmærket Rute",
            lengthKm = 8.5,
            operator = "Naturstyrelsen",
            routeType = "hiking",
            network = "lwn",
            symbol = "Rød",
            colorHex = "#D32F2F",
            segmentsJson = """[[[56.0000,10.0000],[56.0000,10.0200],[56.0000,10.0400]]]""",
            minLat = 56.0000,
            maxLat = 56.0000,
            minLng = 10.0000,
            maxLng = 10.0400,
            cachedAt = System.currentTimeMillis()
        )
        dao.insertMarkedRoutes(listOf(routeEntity))

        val mapView = MapView(context)
        mapView.layout(0, 0, 1000, 1000)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(56.0000, 10.0200))

        var openedRouteSheetDto: MarkedRouteDto? = null
        var mapTapGeoPoint: GeoPoint? = null
        val measurePoints = mutableListOf<GeoPoint>()
        var fullscreenOpenedForTrailId: String? = null

        var activeOnMapTap: ((GeoPoint) -> Boolean)? = null

        val manager = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            markedRouteRepository = markedRepo,
            onMarkedRouteTapped = { dto ->
                openedRouteSheetDto = dto
            }
        )
        manager.attachToMap(mapView, isDark = false)
        manager.updateLayersState(
            state = MapLayersState(showMarkedRoutes = true),
            isDark = false
        )

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p != null) {
                    return activeOnMapTap?.invoke(p) ?: false
                }
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
        manager.registerOverlay(MapLayersOverlayManager.KEY_EVENTS, eventsOverlay)

        val snapshots = markedRepo.getMarkedRouteRenderSnapshots(55.9, 56.1, 9.9, 10.1, zoomLevel = 14.0)
        manager.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshots)

        // Draw once to verify routes are drawn regardless of tapsEnabled
        val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val density = context.resources.displayMetrics.density
        val ptOnLine = Point()
        mapView.projection.toPixels(GeoPoint(56.0000, 10.0200), ptOnLine)
        val tap5dpY = ptOnLine.y + 5f * density

        fun dispatchSingleTap5dpFromRoute(): Boolean {
            val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, ptOnLine.x.toFloat(), tap5dpY, 0)
            var consumed = false
            for (i in mapView.overlays.indices.reversed()) {
                if (mapView.overlays[i].onSingleTapConfirmed(ev, mapView)) {
                    consumed = true
                    break
                }
            }
            ev.recycle()
            return consumed
        }

        // (a) Planner in TEGN mode: markedRouteTapsEnabled = (interactionMode != PlannerInteractionMode.TEGN) -> false
        var plannerMode = PlannerInteractionMode.TEGN
        manager.setMarkedRouteTapsEnabled(plannerMode != PlannerInteractionMode.TEGN)
        manager.getMarkedRoutesBatchOverlay().draw(canvas, mapView, false)
        assertEquals("Route must still be drawn when tapsEnabled is false", 1, manager.getMarkedRoutesBatchOverlay().lastFrameRoutesDrawn)

        activeOnMapTap = { p ->
            mapTapGeoPoint = p
            true
        }
        assertTrue(dispatchSingleTap5dpFromRoute())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("In TEGN mode, onMapTap must be called to add a point", mapTapGeoPoint)
        assertNull("In TEGN mode, marked route detail sheet must NOT open", openedRouteSheetDto)

        // (b) Planner live switches to KIG mode: markedRouteTapsEnabled -> true
        mapTapGeoPoint = null
        openedRouteSheetDto = null
        plannerMode = PlannerInteractionMode.KIG
        manager.setMarkedRouteTapsEnabled(plannerMode != PlannerInteractionMode.TEGN)

        assertTrue(dispatchSingleTap5dpFromRoute())
        pumpMainLooperUntil { openedRouteSheetDto != null }
        assertNotNull("In KIG mode, tapping 5 dp from route must open the route sheet", openedRouteSheetDto)
        assertEquals(990011L, openedRouteSheetDto?.osmRelationId)
        assertNull("In KIG mode, tap consumed by route must not fall through to onMapTap", mapTapGeoPoint)

        // (c) Kort while measuring distance: markedRouteTapsEnabled = !isMeasuringDistance -> false
        openedRouteSheetDto = null
        val isMeasuringDistance = true
        manager.setMarkedRouteTapsEnabled(!isMeasuringDistance)
        activeOnMapTap = { geoPoint ->
            if (isMeasuringDistance) {
                measurePoints.add(geoPoint)
                true
            } else {
                false
            }
        }
        assertTrue(dispatchSingleTap5dpFromRoute())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("While measuring on Kort, tap 5 dp from route must add a measure point", 1, measurePoints.size)
        assertNull("While measuring on Kort, route sheet must NOT open", openedRouteSheetDto)

        // (d) TrailDetailScreen preview map: markedRouteTapsEnabled = false -> opens fullscreen map
        openedRouteSheetDto = null
        manager.setMarkedRouteTapsEnabled(false)
        activeOnMapTap = {
            fullscreenOpenedForTrailId = "trail_1"
            true
        }
        assertTrue(dispatchSingleTap5dpFromRoute())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("TrailDetailScreen preview tap must open fullscreen map", "trail_1", fullscreenOpenedForTrailId)
        assertNull("TrailDetailScreen preview tap must NOT open marked route sheet", openedRouteSheetDto)
    }

    // =========================================================================
    // Item 5: Expired-route message is temporary (4 s timer), not an error banner
    // =========================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun item5_expiredRouteTap_setsTransientMessageOnly_neverLayerError_andClearsAfter4Seconds() = runTest {
        val routeEntity = MarkedRouteEntity(
            osmRelationId = 555001L,
            name = "Udløbet Rute",
            lengthKm = 5.0,
            operator = null,
            routeType = "hiking",
            network = "lwn",
            symbol = null,
            colorHex = "#1E88E5",
            segmentsJson = """[[[56.0000,10.0000],[56.0000,10.0200]]]""",
            minLat = 56.0000,
            maxLat = 56.0000,
            minLng = 10.0000,
            maxLng = 10.0200,
            cachedAt = System.currentTimeMillis()
        )
        dao.insertMarkedRoutes(listOf(routeEntity))
        val snapshots = markedRepo.getMarkedRouteRenderSnapshots(55.9, 56.1, 9.9, 10.1, zoomLevel = 14.0)

        // Delete route from Room so tapping it triggers the expired-route path
        dao.clearAllMarkedRoutes()

        val mapView = MapView(context)
        mapView.layout(0, 0, 1000, 1000)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(56.0000, 10.0100))

        var sheetOpened = false
        val manager = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            markedRouteRepository = markedRepo,
            onMarkedRouteTapped = { sheetOpened = true }
        )
        manager.attachToMap(mapView, isDark = false)
        manager.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshots)

        // Wire the same 4-second auto-clear timer logic used by DanskStiMap onto the test scope
        val timerJob = backgroundScope.launch {
            var activeTimer: kotlinx.coroutines.Job? = null
            manager.transientLayerMessageVersion.collect {
                activeTimer?.cancel()
                if (manager.transientLayerMessage.value != null) {
                    activeTimer = launch {
                        delay(4_000L)
                        manager.clearTransientLayerMessage()
                    }
                }
            }
        }

        val pt = Point()
        mapView.projection.toPixels(GeoPoint(56.0000, 10.0100), pt)
        val ev1 = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, pt.x.toFloat(), pt.y.toFloat(), 0)
        assertTrue(manager.getMarkedRoutesBatchOverlay().onSingleTapConfirmed(ev1, mapView))
        ev1.recycle()

        pumpMainLooperUntil { manager.transientLayerMessage.value != null }
        runCurrent()

        assertFalse("Route detail sheet must not open for expired route", sheetOpened)
        assertTrue("layerErrorMessages must be empty after expired route tap", manager.layerErrorMessages.value.isEmpty())
        assertEquals(
            MapLayersOverlayManager.EXPIRED_MARKED_ROUTE_MESSAGE,
            manager.transientLayerMessage.value
        )

        // Advance 2.5 seconds: message is still visible
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(
            MapLayersOverlayManager.EXPIRED_MARKED_ROUTE_MESSAGE,
            manager.transientLayerMessage.value
        )

        // Tap expired route again at t = 2.5 s -> restarts the 4 s timer
        val ev2 = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, pt.x.toFloat(), pt.y.toFloat(), 0)
        assertTrue(manager.getMarkedRoutesBatchOverlay().onSingleTapConfirmed(ev2, mapView))
        ev2.recycle()
        pumpMainLooperUntil { manager.transientLayerMessageVersion.value >= 2L }
        runCurrent()

        // Advance another 2.5 s (5.0 s total, 2.5 s since second tap): must STILL be visible because timer restarted
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(
            "Second expired tap must restart the 4 s timer",
            MapLayersOverlayManager.EXPIRED_MARKED_ROUTE_MESSAGE,
            manager.transientLayerMessage.value
        )

        // Advance remaining 1.6 s (4.1 s since second tap): now cleared to null
        advanceTimeBy(1_600L)
        runCurrent()
        assertNull("After 4 s, transientLayerMessage must be null", manager.transientLayerMessage.value)
        assertTrue("layerErrorMessages must remain empty", manager.layerErrorMessages.value.isEmpty())

        timerJob.cancel()
    }

    // =========================================================================
    // Item 6: Download progress must not jump back to 0 during Slow / Queued / Running
    // =========================================================================

    @Test
    fun item6_downloadProgress_3BatchesWithSlowDuringBatch2_fractionsNeverDecreaseAndSlowMessageKeepsSection2Of3() = runBlocking {
        // Build a root superroute with 35 child relations inside Denmark so it splits into 3 batches (15 + 15 + 6 = 36 total relations)
        val childIds = (1..35).map { 2000L + it }
        val structureElements = StringBuilder()
        structureElements.append(
            """{"type":"relation","id":2000,"tags":{"name":"Test Superrute","type":"superroute","route":"hiking"},"bounds":{"minlat":55.0,"minlon":9.0,"maxlat":56.0,"maxlon":10.0},"members":["""
        )
        childIds.forEachIndexed { idx, cid ->
            if (idx > 0) structureElements.append(",")
            structureElements.append("""{"type":"relation","ref":$cid,"role":""}""")
        }
        structureElements.append("]}")
        for (cid in childIds) {
            structureElements.append(
                """,{"type":"relation","id":$cid,"tags":{"name":"Etape $cid","type":"route","route":"hiking"},"bounds":{"minlat":55.1,"minlon":9.1,"maxlat":55.2,"maxlon":9.2},"members":[{"type":"way","ref":${cid * 10},"role":""}]}"""
            )
        }
        val structureJson = """{"elements":[$structureElements]}"""

        val seenBatchQueries = LinkedHashSet<String>()
        OverpassClient.interServerBackoffMsProvider = { 0L }
        OverpassClient.customSessionTransport = { serverUrl, query, session ->
            if (query.contains("out body bb;")) {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(code = 200, body = structureJson)
            } else {
                val isNewBatch = seenBatchQueries.add(query)
                if (isNewBatch && seenBatchQueries.size == 2 && serverUrl == OverpassClient.PRIMARY) {
                    // Fail PRIMARY on batch 2 so OverpassClient switches to FALLBACK and emits RequestState.Slow!
                    throw IOException("Primary busy during batch 2")
                }
                session.onHeadersReceived()
                session.onDataChunkReceived()
                val batchBody = """
                    {
                      "elements": [
                        {
                          "type": "relation",
                          "id": 2000,
                          "tags": {"name": "Test Superrute", "type": "superroute", "route": "hiking"},
                          "members": [
                            {
                              "type": "way",
                              "ref": 9001,
                              "role": "",
                              "geometry": [
                                {"lat": 55.10, "lon": 9.10},
                                {"lat": 55.11, "lon": 9.11}
                              ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
                OverpassClient.HttpResult(code = 200, body = batchBody)
            }
        }

        val emitted = CopyOnWriteArrayList<TrailDownloadProgress>()
        val dto = geometryRepo.fetchAndProcessTrail(
            trailId = "trail_test_3batches",
            trailName = "Test Superrute",
            osmRelationId = 2000L,
            onProgress = { prog: TrailDownloadProgress ->
                emitted.add(prog)
            }
        )

        assertNotNull("Trail download should succeed", dto)
        assertEquals("Should have executed 3 distinct batch requests", 3, seenBatchQueries.size)

        // Verify emitted fractions NEVER decrease across the entire download
        var maxSeenFraction = 0f
        for (p in emitted) {
            assertTrue(
                "Emitted fraction (${p.fraction} at '${p.stageMessage}') must never decrease below previously seen $maxSeenFraction",
                p.fraction >= maxSeenFraction - 1e-6f
            )
            if (p.fraction > maxSeenFraction) {
                maxSeenFraction = p.fraction
            }
        }

        // Verify the Slow emission during batch 2 kept "Henter sektion 2 af 3" and fraction > 0 and isIndeterminate = false
        val slowDuringBatch2 = emitted.firstOrNull {
            it.stageMessage.contains("det tager lidt længere end normalt")
        }
        assertNotNull("Expected a Slow progress emission during batch 2", slowDuringBatch2)
        assertEquals(
            "Henter sektion 2 af 3 – det tager lidt længere end normalt…",
            slowDuringBatch2!!.stageMessage
        )
        assertFalse("Slow emission during batch 2 must not be indeterminate", slowDuringBatch2.isIndeterminate)
        assertEquals(2, slowDuringBatch2.downloadedRelations)
        assertEquals(3, slowDuringBatch2.totalRelations)
        assertTrue("Slow emission during batch 2 must preserve fraction > 0", slowDuringBatch2.fraction > 0.3f)
    }

    // =========================================================================
    // Item 8: Trail lookup by name (Camønoen, Kyststien Bornholm, Amarminoen, regex escape)
    // =========================================================================

    @Test
    fun item8_findRelationByName_camoenoenSuperroute_kyststienBornholm_amarminoen_and_regexEscape() = runBlocking {
        // 1. Camønoen ("Camønoen (Kongerigets venligste vandrerute)") -> cleans to "Camønoen",
        //    query contains "Camønoen" and NOT "Kongerigets", and picks superroute among 1 superroute + 5 stage relations
        val capturedQueries = mutableListOf<String>()
        OverpassClient.customSessionTransport = { _, query, session ->
            capturedQueries.add(query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            val body = """
                {
                  "elements": [
                    {"type":"relation","id":6200001,"tags":{"type":"route","route":"hiking","name":"Camønoen - Etape 1"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.0,"maxlon":12.3}},
                    {"type":"relation","id":6200002,"tags":{"type":"route","route":"hiking","name":"Camønoen - Etape 2"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.0,"maxlon":12.3}},
                    {"type":"relation","id":6200003,"tags":{"type":"route","route":"hiking","name":"Camønoen - Etape 3"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.0,"maxlon":12.3}},
                    {"type":"relation","id":6200004,"tags":{"type":"route","route":"hiking","name":"Camønoen - Etape 4"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.0,"maxlon":12.3}},
                    {"type":"relation","id":6200005,"tags":{"type":"route","route":"hiking","name":"Camønoen - Etape 5"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.0,"maxlon":12.3}},
                    {"type":"relation","id":6200000,"tags":{"type":"superroute","route":"hiking","name":"Camønoen"},"bounds":{"minlat":54.9,"minlon":12.1,"maxlat":55.1,"maxlon":12.5}}
                  ]
                }
            """.trimIndent()
            OverpassClient.HttpResult(code = 200, body = body)
        }

        val camoenoenId = geometryRepo.findRelationByName("Camønoen (Kongerigets venligste vandrerute)")
        assertEquals(6200000L, camoenoenId)
        assertEquals(1, capturedQueries.size)
        assertTrue("Camønoen query must contain 'Camønoen'", capturedQueries[0].contains("Camønoen"))
        assertFalse("Camønoen query must not contain 'Kongerigets'", capturedQueries[0].contains("Kongerigets"))

        // 2. Kyststien Bornholm with a Bornholm match (minlon >= 14.5) and a Jutland match -> Bornholm chosen
        capturedQueries.clear()
        OverpassClient.customSessionTransport = { _, query, session ->
            capturedQueries.add(query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            val body = """
                {
                  "elements": [
                    {"type":"relation","id":3100001,"tags":{"type":"route","route":"hiking","name":"Kyststien Snaptun - Juelsminde"},"bounds":{"minlat":55.7,"minlon":9.9,"maxlat":55.8,"maxlon":10.1}},
                    {"type":"relation","id":3100002,"tags":{"type":"route","route":"hiking","name":"Kyststien"},"bounds":{"minlat":55.0,"minlon":14.7,"maxlat":55.3,"maxlon":15.15}}
                  ]
                }
            """.trimIndent()
            OverpassClient.HttpResult(code = 200, body = body)
        }

        val bornholmId = geometryRepo.findRelationByName("Kyststien Bornholm")
        assertEquals(3100002L, bornholmId)
        assertTrue("Kyststien Bornholm must search for 'Kyststien'", capturedQueries[0].contains("\"^Kyststien$\""))

        // 3. Amarminoen exact single match -> chosen
        capturedQueries.clear()
        OverpassClient.customSessionTransport = { _, query, session ->
            capturedQueries.add(query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            val body = """
                {
                  "elements": [
                    {"type":"relation","id":11450999,"tags":{"type":"route","route":"hiking","name":"Amarminoen"},"bounds":{"minlat":55.55,"minlon":12.55,"maxlat":55.66,"maxlon":12.68}}
                  ]
                }
            """.trimIndent()
            OverpassClient.HttpResult(code = 200, body = body)
        }

        val amarminoenId = geometryRepo.findRelationByName("Amarminoen")
        assertEquals(11450999L, amarminoenId)

        // 4. Name containing a quote and parentheses produces a valid escaped query string
        val rawSpecialName = """Rute "Nord" (Etape 1+2) [Test.v1]"""
        val escaped = geometryRepo.overpassRegexEscape(rawSpecialName)
        assertEquals("""Rute \"Nord\" \\(Etape 1\\+2\\) \\[Test\\.v1\\]""", escaped)
        val builtQuery = geometryRepo.buildFindRelationQuery(escaped, exact = true)
        assertTrue(
            "Query must contain valid Overpass regex with escaped quotes and parens",
            builtQuery.contains("""["name"~"^Rute \"Nord\" \\(Etape 1\\+2\\) \\[Test\\.v1\\]$",i](54.4,7.8,57.9,15.3);""")
        )
    }

    // =========================================================================
    // Item 9: Marked routes fixed grid cells (0.2° x 0.3°)
    // =========================================================================

    @Test
    fun item9_markedRoutesGridCells_seaOnlyZeroRoutesCached_partialOverlapQueriesOnlyMissingCells_failureRecordsNoCells() = runBlocking {
        val overpassQueries = mutableListOf<String>()

        // (a) Sea-only viewport: Overpass returns zero routes -> second identical viewport makes 0 Overpass calls
        OverpassClient.customSessionTransport = { _, query, session ->
            overpassQueries.add(query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }

        val seaSnap1 = markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 56.05,
            maxLat = 56.15,
            minLng = 11.45,
            maxLng = 11.65,
            zoomLevel = 11.0
        )
        assertTrue("Sea viewport should return 0 routes", seaSnap1.isEmpty())
        assertEquals("First sea viewport call must make 1 Overpass query", 1, overpassQueries.size)

        // Second identical viewport call -> 0 Overpass calls because grid cell box was recorded
        val seaSnap2 = markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 56.05,
            maxLat = 56.15,
            minLng = 11.45,
            maxLng = 11.65,
            zoomLevel = 11.0
        )
        assertTrue(seaSnap2.isEmpty())
        assertEquals("Second identical sea viewport must make 0 additional Overpass calls", 1, overpassQueries.size)

        // (b) Viewport A fetched, then viewport B overlapping half of A -> one Overpass call covering only B's missing cells
        dao.clearAllMarkedRouteCacheBoxes()
        dao.clearAllMarkedRoutes()
        MarkedRouteRepository.clearProcessWideCache()
        overpassQueries.clear()

        // Viewport A covers lat [55.05..55.15] (latIdx = 275 -> 55.0..55.2) and lng [9.05..9.55] (lngIdx = 30, 31 -> 9.0..9.6)
        markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 55.05,
            maxLat = 55.15,
            minLng = 9.05,
            maxLng = 9.55,
            zoomLevel = 11.0
        )
        assertEquals("Viewport A must make 1 Overpass call", 1, overpassQueries.size)

        // Viewport B covers lat [55.05..55.15] (latIdx = 275) and lng [9.35..9.85] (lngIdx = 31, 32 -> 9.3..9.9)
        // Cell (275, 31) [9.3..9.6] is already fresh from A; only cell (275, 32) [9.6..9.9] is missing!
        markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 55.05,
            maxLat = 55.15,
            minLng = 9.35,
            maxLng = 9.85,
            zoomLevel = 11.0
        )
        assertEquals("Viewport B must make 1 Overpass call for its missing cells", 2, overpassQueries.size)
        val queryB = overpassQueries[1]
        // Cell (275, 32) has minLng = 32 * 0.3 = 9.6, maxLng = 33 * 0.3 = 9.9
        // Extract the bbox "(minLat,minLng,maxLat,maxLng)" from queryB
        val bboxMatch = Regex("""\((\d+\.\d+),(\d+\.\d+),(\d+\.\d+),(\d+\.\d+)\)""").find(queryB)
        assertNotNull("Query B must contain a bounding box", bboxMatch)
        val qMinLat = bboxMatch!!.groupValues[1].toDouble()
        val qMinLng = bboxMatch.groupValues[2].toDouble()
        val qMaxLat = bboxMatch.groupValues[3].toDouble()
        val qMaxLng = bboxMatch.groupValues[4].toDouble()
        assertEquals(55.0, qMinLat, 1e-4)
        assertEquals(9.6, qMinLng, 1e-4)
        assertEquals(55.2, qMaxLat, 1e-4)
        assertEquals(9.9, qMaxLng, 1e-4)

        // (c) Overpass failure -> no cell boxes recorded and the next refresh retries
        dao.clearAllMarkedRouteCacheBoxes()
        overpassQueries.clear()

        OverpassClient.interServerBackoffMsProvider = { 0L }
        OverpassClient.customSessionTransport = { _, query, _ ->
            overpassQueries.add(query)
            throw IOException("Simulated network outage")
        }

        val failedSnap = markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 55.45,
            maxLat = 55.55,
            minLng = 10.25,
            maxLng = 10.45,
            zoomLevel = 11.0
        )
        assertTrue(failedSnap.isEmpty())
        assertTrue("lastFetchFailed must be true when no cached routes exist and fetch failed", markedRepo.lastFetchFailed)

        val expectedCellKeys = MarkedRouteRepository.computeGridCellsForViewport(55.45, 55.55, 10.25, 10.45).map { it.cellKey }
        val recordedBoxesAfterFailure = dao.getMarkedRouteCacheBoxesByKeys(expectedCellKeys)
        assertTrue("No cell boxes may be recorded when Overpass fails", recordedBoxesAfterFailure.isEmpty())

        // Next refresh retries Overpass and succeeds
        val callsDuringFailedAttempt = overpassQueries.size
        OverpassClient.customSessionTransport = { _, query, session ->
            overpassQueries.add(query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            OverpassClient.HttpResult(code = 200, body = """{"elements":[]}""")
        }

        markedRepo.getMarkedRouteRenderSnapshots(
            minLat = 55.45,
            maxLat = 55.55,
            minLng = 10.25,
            maxLng = 10.45,
            zoomLevel = 11.0
        )
        assertTrue("Next refresh must retry Overpass", overpassQueries.size > callsDuringFailedAttempt)
        assertFalse("lastFetchFailed must be cleared after retry succeeds", markedRepo.lastFetchFailed)
        val recordedBoxesAfterRetry = dao.getMarkedRouteCacheBoxesByKeys(expectedCellKeys)
        assertEquals("All missing cells must be recorded after retry succeeds", expectedCellKeys.size, recordedBoxesAfterRetry.size)
    }
}
