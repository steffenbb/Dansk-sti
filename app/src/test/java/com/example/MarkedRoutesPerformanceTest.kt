package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.view.MotionEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteCacheBoxEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteEntity
import com.example.data.model.ShelterEntity
import com.example.data.repository.CacheCategory
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.MarkedRouteRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.navigation.MarkedRoutesBatchOverlay
import com.example.ui.navigation.PoiItem
import com.example.ui.screens.computeUdforskZoomTier
import com.example.ui.screens.decimateCatalogueTrailForZoomTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkedRoutesPerformanceTest {

    private lateinit var context: Context
    private lateinit var db: DanskStiDatabase
    private lateinit var dao: DanskStiDao
    private lateinit var geometryRepo: TrailGeometryRepository
    private lateinit var markedRepo: MarkedRouteRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        MarkedRouteRepository.clearProcessWideCache()
        MarkedRouteRepository.resetOversizedCleanupFlagForTests(context)
        MarkedRouteRepository.roomSegmentsLoadCount.set(0)
        MarkedRouteRepository.batchSegmentsQueryInterceptor = null

        db = Room.inMemoryDatabaseBuilder(context, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        geometryRepo = TrailGeometryRepository(context)
        markedRepo = MarkedRouteRepository(context, dao, geometryRepo)
    }

    @After
    fun tearDown() {
        MarkedRouteRepository.batchSegmentsQueryInterceptor = null
        MarkedRouteRepository.clearProcessWideCache()
        db.close()
    }

    /**
     * Generates a synthetic route of [wayCount] contiguous ways, each with [pointsPerWay] points,
     * following a zigzag/sine path with a point every ~20 m and ±30–80 m sideways deviation,
     * so lod1 (35 m) and lod2 (150 m) Douglas-Peucker simplification genuinely reduce points.
     */
    private fun buildSyntheticRouteEntity(
        relationId: Long,
        baseLat: Double,
        baseLng: Double,
        wayCount: Int = 30,
        pointsPerWay: Int = 12,
        cachedAt: Long = 1_730_000_000_000L
    ): MarkedRouteEntity {
        val rootArr = JSONArray()
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY

        // Forward step ~20 m per point (12 m N + 16 m E = 20 m)
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians(baseLat)).coerceAtLeast(0.2)
        val fwdNorthMeters = 12.0
        val fwdEastMeters = 16.0
        // Perpendicular unit vector (-0.8 N, +0.6 E) for sideways deviation
        val perpNorthUnit = -0.8
        val perpEastUnit = 0.6

        fun computePoint(globalIdx: Int): Pair<Double, Double> {
            val alongNorthM = globalIdx * fwdNorthMeters
            val alongEastM = globalIdx * fwdEastMeters
            // Combine medium-wavelength sine wave (±75 m every ~18 points = 360 m)
            // with a local ~20 m zigzag (±32 m every 2 points) so:
            // - lod0 keeps all points (every 20 m)
            // - lod1 (35 m tolerance) strips the ±32 m local zigzag (< 50% points)
            // - lod2 (150 m tolerance) strips both the local zigzag and the ±75 m wave (< 25% points)
            val macroWaveM = 75.0 * sin(globalIdx * 0.35)
            val localZigzagM = 32.0 * sin(globalIdx * 1.65)
            val sidewaysM = if (globalIdx == 0) 0.0 else (macroWaveM + localZigzagM)

            val totalNorthM = alongNorthM + sidewaysM * perpNorthUnit
            val totalEastM = alongEastM + sidewaysM * perpEastUnit
            val lat = baseLat + totalNorthM / metersPerDegLat
            val lng = baseLng + totalEastM / metersPerDegLng
            return lat to lng
        }

        for (w in 0 until wayCount) {
            val wayArr = JSONArray()
            for (p in 0 until pointsPerWay) {
                val globalIdx = w * (pointsPerWay - 1) + p
                val (lat, lng) = computePoint(globalIdx)
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lng < minLng) minLng = lng
                if (lng > maxLng) maxLng = lng

                val ptArr = JSONArray().apply {
                    put(lat)
                    put(lng)
                }
                wayArr.put(ptArr)
            }
            rootArr.put(wayArr)
        }

        val colorHex = MarkedRouteRepository.PALETTE[(relationId % MarkedRouteRepository.PALETTE.size).toInt()]
        return MarkedRouteEntity(
            osmRelationId = relationId,
            name = "Syntetisk Rute #$relationId",
            lengthKm = 12.4,
            operator = "Naturstyrelsen",
            routeType = "hiking",
            network = "rwn",
            symbol = "Blå",
            colorHex = colorHex,
            segmentsJson = rootArr.toString(),
            minLat = minLat,
            maxLat = maxLat,
            minLng = minLng,
            maxLng = maxLng,
            cachedAt = cachedAt
        )
    }

    // =========================================================================
    // Test 1: 300 synthetic routes x 30 ways benchmark at zoom 7, 10, and 14
    // =========================================================================

    @Test
    fun benchmark_300SyntheticRoutesBy30Ways_reportsRoutesVerticesAndDrawTimeAtZoom7_10_14() = runBlocking {
        val now = System.currentTimeMillis()
        val routes = ArrayList<MarkedRouteEntity>(300)
        for (i in 0 until 300) {
            val row = i / 20
            val col = i % 20
            val baseLat = 54.8 + row * 0.16
            val baseLng = 8.2 + col * 0.22
            routes.add(
                buildSyntheticRouteEntity(
                    relationId = 10_000L + i,
                    baseLat = baseLat,
                    baseLng = baseLng,
                    wayCount = 30,
                    pointsPerWay = 12,
                    cachedAt = now
                )
            )
        }
        dao.insertMarkedRoutes(routes)

        // Pre-populate fresh grid cell boxes across Denmark so no network fetch is triggered at zoom >= 10
        for (cell in MarkedRouteRepository.computeGridCellsForViewport(54.4, 58.0, 7.8, 15.5)) {
            dao.insertMarkedRouteCacheBox(
                MarkedRouteCacheBoxEntity(
                    boxKey = cell.cellKey,
                    minLat = cell.minLat,
                    maxLat = cell.maxLat,
                    minLng = cell.minLng,
                    maxLng = cell.maxLng,
                    fetchedAt = now
                )
            )
        }

        // Pre-compute each route's stitched lod0 point count directly from its stored segments
        val lod0PointCountByRouteId = HashMap<Long, Int>(300)
        for (entity in routes) {
            val rawPacked = markedRepo.parseSegmentsJsonToPackedArrays(entity.segmentsJson)
            val stitched = MarkedRouteRepository.stitchWaySegmentsToPackedPolylines(rawPacked)
            lod0PointCountByRouteId[entity.osmRelationId] = stitched.sumOf { it.size / 2 }
        }

        val mapView = MapView(context)
        mapView.layout(0, 0, 1080, 1920)
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val overlay = MarkedRoutesBatchOverlay()
        assertFalse(
            "MarkedRoutesBatchOverlay must be a plain Overlay, not a FolderOverlay",
            org.osmdroid.views.overlay.FolderOverlay::class.java.isAssignableFrom(MarkedRoutesBatchOverlay::class.java)
        )

        data class ZoomBenchmarkResult(
            val zoom: Int,
            val routesDrawn: Int,
            val verticesDrawn: Int,
            val totalLod0PointsOfDrawnRoutes: Int,
            val medianDrawMs: Double
        )

        suspend fun runBenchmarkAtZoom(zoomLevel: Double, queryMinLat: Double, queryMaxLat: Double, queryMinLng: Double, queryMaxLng: Double): ZoomBenchmarkResult {
            mapView.controller.setZoom(zoomLevel)
            mapView.controller.setCenter(GeoPoint(56.0, 10.4))
            val snapshots = markedRepo.getMarkedRouteRenderSnapshots(
                queryMinLat,
                queryMaxLat,
                queryMinLng,
                queryMaxLng,
                zoomLevel = zoomLevel
            )
            overlay.setRoutesSnapshot(snapshots)

            if (zoomLevel >= 12.0) {
                for (item in snapshots) {
                    assertNotNull(
                        "At zoom $zoomLevel, every route in snapshot must have lod0 loaded (no lod1 fallback) for route ${item.osmRelationId}",
                        item.lod0
                    )
                }
            }

            // 3 warm-up draws
            repeat(3) {
                overlay.draw(canvas, mapView, false)
            }
            // 5 timed draws
            val samplesMs = DoubleArray(5)
            for (s in 0 until 5) {
                val t0 = System.nanoTime()
                overlay.draw(canvas, mapView, false)
                samplesMs[s] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samplesMs.sort()
            val medianMs = samplesMs[2]

            val drawnIds = overlay.lastFrameDrawnRouteIds
            val perRouteLod0 = lod0PointCountByRouteId.values.firstOrNull() ?: 331
            val totalLod0Points = if (drawnIds.isNotEmpty()) {
                drawnIds.sumOf { id -> lod0PointCountByRouteId[id] ?: perRouteLod0 }
            } else {
                overlay.lastFrameRoutesDrawn * perRouteLod0
            }

            if (zoomLevel >= 12.0) {
                val snapById = snapshots.associateBy { it.osmRelationId }
                for (drawnId in drawnIds) {
                    val item = snapById[drawnId]
                    assertNotNull("Drawn route $drawnId must be in snapshot", item)
                    assertNotNull("Drawn route $drawnId at zoom $zoomLevel must use lod0 (no lod1 fallback)", item!!.lod0)
                }
            }

            return ZoomBenchmarkResult(
                zoom = zoomLevel.toInt(),
                routesDrawn = overlay.lastFrameRoutesDrawn,
                verticesDrawn = overlay.lastFrameVerticesDrawn,
                totalLod0PointsOfDrawnRoutes = totalLod0Points,
                medianDrawMs = medianMs
            )
        }

        // 1. Zoom 7 (Overview across Denmark)
        val resZ7 = runBenchmarkAtZoom(7.0, 54.4, 58.0, 7.8, 15.5)

        // 2. Zoom 10 (Regional sector)
        mapView.controller.setZoom(10.0)
        mapView.controller.setCenter(GeoPoint(56.0, 10.4))
        val box10 = mapView.boundingBox
        val resZ10 = runBenchmarkAtZoom(10.0, box10.latSouth, box10.latNorth, box10.lonWest, box10.lonEast)

        // 3. Zoom 14 (High detail trail view)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(56.0, 10.4))
        val box14 = mapView.boundingBox
        val resZ14 = runBenchmarkAtZoom(14.0, box14.latSouth, box14.latNorth, box14.lonWest, box14.lonEast)

        for (r in listOf(resZ7, resZ10, resZ14)) {
            println(
                "BENCHMARK_300x30 Zoom ${r.zoom}: " +
                    "routesDrawn=${r.routesDrawn}, " +
                    "verticesDrawn=${r.verticesDrawn}, " +
                    "totalLod0Points=${r.totalLod0PointsOfDrawnRoutes}, " +
                    "medianDrawMs=${String.format(java.util.Locale.US, "%.2f", r.medianDrawMs)}ms"
            )
        }

        assertEquals("All 300 routes should be drawn at zoom 7", 300, resZ7.routesDrawn)
        assertTrue(
            "Zoom 7 vertices (${resZ7.verticesDrawn}) must be < 25% of lod0 points of drawn routes (${resZ7.totalLod0PointsOfDrawnRoutes})",
            resZ7.verticesDrawn < (resZ7.totalLod0PointsOfDrawnRoutes * 0.25)
        )
        assertTrue("Expected zoom 10 routes drawn (${resZ10.routesDrawn}) > 0", resZ10.routesDrawn > 0)
        assertTrue(
            "Zoom 10 vertices (${resZ10.verticesDrawn}) must be < 50% of lod0 points of drawn routes (${resZ10.totalLod0PointsOfDrawnRoutes})",
            resZ10.verticesDrawn < (resZ10.totalLod0PointsOfDrawnRoutes * 0.50)
        )
        assertTrue("Expected zoom 14 routes drawn (${resZ14.routesDrawn}) > 0", resZ14.routesDrawn > 0)
        assertEquals(
            "Zoom 14 vertices (${resZ14.verticesDrawn}) must equal lod0 points of drawn routes (${resZ14.totalLod0PointsOfDrawnRoutes})",
            resZ14.totalLod0PointsOfDrawnRoutes,
            resZ14.verticesDrawn
        )
    }

    // =========================================================================
    // Test 2: Tiered eviction with 600,000 points visible at zoom 7 ->
    //         0 repeated Room loads across 10 consecutive viewport refreshes
    // =========================================================================

    @Test
    fun tieredEviction_600kPointsVisibleAtZoom7_noRepeatedRoomLoadsAcross10Refreshes() = runBlocking {
        // 300 routes * 2,000 points each = 600,000 raw lod0 points (> 300,000 cap)
        val routes = ArrayList<MarkedRouteEntity>(300)
        for (i in 0 until 300) {
            val row = i / 20
            val col = i % 20
            routes.add(
                buildSyntheticRouteEntity(
                    relationId = 50_000L + i,
                    baseLat = 54.7 + row * 0.16,
                    baseLng = 8.1 + col * 0.22,
                    wayCount = 40,
                    pointsPerWay = 51 // 40 * 50 + 1 = 2,001 points per route -> 600,300 raw points total
                )
            )
        }
        dao.insertMarkedRoutes(routes)

        MarkedRouteRepository.roomSegmentsLoadCount.set(0)

        // Initial load at zoom 7 populates the cache (and drops lod0 on older entries to stay <= 300,000 points)
        val initialSnapshots = markedRepo.getMarkedRouteRenderSnapshots(54.4, 58.0, 7.8, 15.5, zoomLevel = 7.0)
        assertEquals(300, initialSnapshots.size)
        val firstLoadCount = MarkedRouteRepository.roomSegmentsLoadCount.get()
        assertEquals("Initial load should fetch all 300 routes from Room once", 300, firstLoadCount)

        // Verify cache cap is enforced (<= 300,000 total points) while all 300 routes remain resident with lod1+lod2
        val totalCachedPoints = MarkedRouteRepository.getProcessWideCachedTotalPoints()
        assertTrue(
            "Total cached points ($totalCachedPoints) must not exceed ${MarkedRouteRepository.MAX_CACHE_TOTAL_POINTS}",
            totalCachedPoints <= MarkedRouteRepository.MAX_CACHE_TOTAL_POINTS
        )
        assertEquals(
            "All 300 routes must remain in cache via tiered lod0 eviction",
            300,
            MarkedRouteRepository.getProcessWideCachedRouteCount()
        )

        // Perform 10 consecutive viewport refreshes at zoom 7
        MarkedRouteRepository.roomSegmentsLoadCount.set(0)
        repeat(10) {
            val snap = markedRepo.getMarkedRouteRenderSnapshots(54.4, 58.0, 7.8, 15.5, zoomLevel = 7.0)
            assertEquals(300, snap.size)
        }
        assertEquals(
            "Must perform 0 repeated Room segmentsJson loads across 10 consecutive refreshes at zoom 7",
            0,
            MarkedRouteRepository.roomSegmentsLoadCount.get()
        )
    }

    // =========================================================================
    // Test 3: Tap 10 dp from route opens it; tap on shelter marker on top of route
    //         opens shelter; tap on expired/deleted route shows message
    // =========================================================================

    @Test
    fun tapHitTestAndZOrder_tap10dpOpensRoute_tapOnShelterOnRouteOpensShelter_expiredRouteShowsMessage() = runBlocking {
        val routeEntity = MarkedRouteEntity(
            osmRelationId = 777001L,
            name = "Gendarmstien Etape 1",
            lengthKm = 15.0,
            operator = "Naturstyrelsen",
            routeType = "hiking",
            network = "rwn",
            symbol = "Blå gendarm",
            colorHex = "#1E88E5",
            segmentsJson = """[[[56.0000,10.0000],[56.0000,10.0200],[56.0000,10.0400]]]""",
            minLat = 56.0000,
            maxLat = 56.0000,
            minLng = 10.0000,
            maxLng = 10.0400,
            cachedAt = System.currentTimeMillis()
        )
        dao.insertMarkedRoutes(listOf(routeEntity))

        val shelterOnRoute = ShelterEntity(
            id = "shelter_on_route_1",
            name = "Shelter Midt På Ruten",
            lat = 56.0000,
            lng = 10.0200,
            shelterType = "Shelter",
            capacity = 6,
            bookingRequired = false
        )

        val mapView = MapView(context)
        mapView.layout(0, 0, 1000, 1000)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(56.0000, 10.0200))

        var tappedRoute: MarkedRouteDto? = null
        var tappedPoi: PoiItem? = null
        var expiredMsg: String? = null
        val routeLatch = CountDownLatch(1)

        val secondRepo = MarkedRouteRepository(context, dao, geometryRepo)
        val manager = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            markedRouteRepository = secondRepo,
            onMarkedRouteTapped = { dto ->
                tappedRoute = dto
                routeLatch.countDown()
            },
            onPoiTapped = { poi ->
                tappedPoi = poi
            }
        )
        manager.attachToMap(mapView, isDark = false)
        manager.updateLayersState(
            state = MapLayersState(showMarkedRoutes = true, showShelters = true),
            shelters = listOf(shelterOnRoute),
            isDark = false
        )

        // Populate snapshot and draw once so POI cluster overlay and batch route overlay have screen positions
        val snapshots = secondRepo.getMarkedRouteRenderSnapshots(55.9, 56.1, 9.9, 10.1, zoomLevel = 14.0)
        manager.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshots)

        val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (ov in mapView.overlays) {
            ov.draw(canvas, mapView, false)
        }

        // Verify Z-order: KEY_MARKED_ROUTES sits strictly below KEY_ROUTE and KEY_POIS
        val markedOverlay = manager.getOverlay(MapLayersOverlayManager.KEY_MARKED_ROUTES)!!
        val routeOverlay = manager.getOverlay(MapLayersOverlayManager.KEY_ROUTE)!!
        val poiOverlay = manager.getOverlay(MapLayersOverlayManager.KEY_POIS)!!
        val idxMarked = mapView.overlays.indexOf(markedOverlay)
        val idxRoute = mapView.overlays.indexOf(routeOverlay)
        val idxPoi = mapView.overlays.indexOf(poiOverlay)
        assertTrue("KEY_MARKED_ROUTES ($idxMarked) must be below KEY_ROUTE ($idxRoute)", idxMarked < idxRoute)
        assertTrue("KEY_ROUTE ($idxRoute) must be below KEY_POIS ($idxPoi)", idxRoute < idxPoi)

        // 1. Tap 10 dp away from the route line (at lng 10.0100, away from the shelter at 10.0200)
        val density = context.resources.displayMetrics.density
        val ptOnLine = Point()
        mapView.projection.toPixels(GeoPoint(56.0000, 10.0100), ptOnLine)
        val tap10dpY = ptOnLine.y + 10f * density
        val motionTapRoute = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, ptOnLine.x.toFloat(), tap10dpY, 0)

        // Dispatch top-down like osmdroid OverlayManager
        var consumedByOverlay = false
        for (i in mapView.overlays.indices.reversed()) {
            if (mapView.overlays[i].onSingleTapConfirmed(motionTapRoute, mapView)) {
                consumedByOverlay = true
                break
            }
        }
        motionTapRoute.recycle()

        assertTrue("Tap 10 dp from route must be consumed by MarkedRoutesBatchOverlay", consumedByOverlay)
        waitForLatchWithMainLooper(routeLatch)
        assertNull("Shelter must not be opened when tapping route away from shelter", tappedPoi)
        assertNotNull("Route must be loaded from Room and opened", tappedRoute)
        assertEquals(777001L, tappedRoute?.osmRelationId)

        // 2. Tap directly on the shelter marker positioned on top of the route (at 56.0000, 10.0200)
        tappedRoute = null
        tappedPoi = null
        val ptOnShelter = Point()
        mapView.projection.toPixels(GeoPoint(56.0000, 10.0200), ptOnShelter)
        val motionTapShelter = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, ptOnShelter.x.toFloat(), ptOnShelter.y.toFloat(), 0)

        var shelterConsumed = false
        for (i in mapView.overlays.indices.reversed()) {
            if (mapView.overlays[i].onSingleTapConfirmed(motionTapShelter, mapView)) {
                shelterConsumed = true
                break
            }
        }
        motionTapShelter.recycle()

        assertTrue("Tap on shelter marker must be consumed", shelterConsumed)
        assertNotNull("Shelter marker on top of route must open shelter", tappedPoi)
        assertEquals("shelter_on_route_1", tappedPoi?.id)
        assertNull("Marked route must not open when shelter on top is tapped", tappedRoute)

        // 3. Delete route from Room (simulating expired/cleared cache) while snapshot is still on screen, then tap route
        dao.clearAllMarkedRoutes()
        val managerExpired = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            markedRouteRepository = secondRepo,
            onMarkedRouteTapped = { dto -> tappedRoute = dto }
        )
        managerExpired.attachToMap(mapView, isDark = false)
        managerExpired.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshots)

        val motionTapExpired = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, ptOnLine.x.toFloat(), tap10dpY, 0)
        managerExpired.getMarkedRoutesBatchOverlay().onSingleTapConfirmed(motionTapExpired, mapView)
        motionTapExpired.recycle()

        val deadline = System.currentTimeMillis() + 3_000L
        while (managerExpired.transientLayerMessage.value == null && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertNull("Detail sheet must not open when route is no longer in Room", tappedRoute)
        assertTrue("layerErrorMessages must be empty on expired route tap", managerExpired.layerErrorMessages.value.isEmpty())
        assertEquals(
            "Ruten er ikke længere gemt. Zoom ind for at hente den igen.",
            managerExpired.transientLayerMessage.value
        )
    }

    // =========================================================================
    // Test 4: Drawing-only simplification:
    //         - At zoom 14 no drawn line deviates more than 1.5 px from original geometry
    //         - A route tapped at zoom 7 and saved has the exact same point count as stored geometry
    // =========================================================================

    @Test
    fun zoom14FidelityWithin1_5Px_and_routeTappedAtZoom7AndSavedPreservesExactPointCount() = runBlocking {
        // Create a detailed multi-way route in Denmark with 15 ways x 15 points = 225 raw points
        val syntheticEntity = buildSyntheticRouteEntity(
            relationId = 888123L,
            baseLat = 56.10,
            baseLng = 9.50,
            wayCount = 15,
            pointsPerWay = 15
        )
        dao.insertMarkedRoutes(listOf(syntheticEntity))

        val originalDto = markedRepo.getFullMarkedRouteById(888123L)!!
        val originalTotalPoints = originalDto.polylines.sumOf { it.size }
        assertEquals(15 * 15, originalTotalPoints)

        // 1. Check zoom 14 screen-space deviation against original geometry
        val mapView = MapView(context)
        mapView.layout(0, 0, 1080, 1920)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(syntheticEntity.minLat, syntheticEntity.minLng))

        val snapshotsZ14 = markedRepo.getMarkedRouteRenderSnapshots(
            syntheticEntity.minLat - 0.1,
            syntheticEntity.maxLat + 0.1,
            syntheticEntity.minLng - 0.1,
            syntheticEntity.maxLng + 0.1,
            zoomLevel = 14.0
        )
        assertEquals(1, snapshotsZ14.size)
        val renderItem = snapshotsZ14.first()
        assertNotNull("At zoom 14, lod0 must be loaded and present", renderItem.lod0)

        // Compare every original vertex projected to screen pixels against the drawn lod0 segments at zoom 14
        val proj = mapView.projection
        val origScreenPt = Point()
        val segPtA = Point()
        var maxDeviationPx = 0.0

        val drawnPolylines = renderItem.lod0!!
        for (origWay in originalDto.polylines) {
            for (origGeo in origWay) {
                proj.toPixels(GeoPoint(origGeo.lat, origGeo.lng), origScreenPt)
                val ox = origScreenPt.x.toFloat()
                val oy = origScreenPt.y.toFloat()

                var minVertexToDrawnDist = Double.MAX_VALUE
                for (coords in drawnPolylines) {
                    val n = coords.size / 2
                    for (i in 0 until n) {
                        proj.toPixels(GeoPoint(coords[i * 2], coords[i * 2 + 1]), segPtA)
                        val dx = (ox - segPtA.x).toDouble()
                        val dy = (oy - segPtA.y).toDouble()
                        val d = sqrt(dx * dx + dy * dy)
                        if (d < minVertexToDrawnDist) {
                            minVertexToDrawnDist = d
                        }
                    }
                }
                if (minVertexToDrawnDist > maxDeviationPx) {
                    maxDeviationPx = minVertexToDrawnDist
                }
            }
        }
        assertTrue(
            "At zoom 14 no drawn line may deviate > 1.5 px from original geometry (maxDeviationPx=$maxDeviationPx)",
            maxDeviationPx <= 1.5
        )

        // 2. View at zoom 7 (where LOD 2 is drawn with far fewer points), tap the route, and save it as a trail
        mapView.controller.setZoom(7.0)
        mapView.controller.setCenter(GeoPoint(56.10, 9.50))
        val snapshotsZ7 = markedRepo.getMarkedRouteRenderSnapshots(54.4, 58.0, 7.8, 15.5, zoomLevel = 7.0)
        val overlay = MarkedRoutesBatchOverlay()
        overlay.setRoutesSnapshot(snapshotsZ7)
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bmp), mapView, false)

        // Verify zoom 7 drew simplified LOD 2 (< original points)
        assertTrue(
            "Zoom 7 should draw simplified LOD2 vertices (${overlay.lastFrameVerticesDrawn}) < $originalTotalPoints",
            overlay.lastFrameVerticesDrawn < originalTotalPoints
        )

        // Simulate tapping the route at zoom 7 and saving it (with live network disabled so fallback saves the route's geometry)
        var tappedAtZoom7: MarkedRouteDto? = null
        val latch = CountDownLatch(1)
        val manager = MapLayersOverlayManager(
            context = context,
            scope = CoroutineScope(Dispatchers.Main.immediate),
            markedRouteRepository = markedRepo,
            onMarkedRouteTapped = { dto ->
                tappedAtZoom7 = dto
                latch.countDown()
            }
        )
        manager.attachToMap(mapView, isDark = false)
        manager.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshotsZ7)

        val tapPt = Point()
        mapView.projection.toPixels(GeoPoint(originalDto.polylines[0][0].lat, originalDto.polylines[0][0].lng), tapPt)
        val tapEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, tapPt.x.toFloat(), tapPt.y.toFloat(), 0)
        assertTrue(manager.getMarkedRoutesBatchOverlay().onSingleTapConfirmed(tapEvent, mapView))
        tapEvent.recycle()

        waitForLatchWithMainLooper(latch)
        assertNotNull("Route tapped at zoom 7 must load full DTO from Room", tappedAtZoom7)
        val tappedPointsCount = tappedAtZoom7!!.polylines.sumOf { it.size }
        assertEquals(
            "Route tapped at zoom 7 must have exact same point count as stored original geometry",
            originalTotalPoints,
            tappedPointsCount
        )

        // Force offline fallback in TrailGeometryRepository so saveMarkedRouteAsTrail saves the tapped route's geometry directly
        TrailGeometryRepository.customRelationFetcher = { _, _, _ -> false }
        try {
            val saveRes = markedRepo.saveMarkedRouteAsTrail(tappedAtZoom7!!)
            assertTrue(saveRes.isSuccess)
            val savedGeom = geometryRepo.loadGeometry("trail_osm_888123")
            assertNotNull(savedGeom)
            val savedPointsCount = savedGeom!!.segments.sumOf { it.points.size }
            assertEquals(
                "Saved trail geometry from zoom-7 tap must preserve all $originalTotalPoints original points",
                originalTotalPoints,
                savedPointsCount
            )
        } finally {
            TrailGeometryRepository.customRelationFetcher = null
        }
    }

    // =========================================================================
    // Test 5: 3 MB segmentsJson row + 49 normal rows -> 49 still draw, no crash;
    //         and Denmark way bbox filter + one-time cleanup
    // =========================================================================

    @Test
    fun oversized3MbRowPlus49NormalRows_49StillDrawWithoutCrash_and_denmarkWayFilterRecomputesBbox() = runBlocking {
        // Pre-mark the one-time cleanup as already run so we can directly test the 3 MB row batch fault isolation during normal operation
        context.getSharedPreferences("dansk_sti_marked_routes_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("cleaned_oversized_marked_routes_v1", true)
            .commit()

        val rows = ArrayList<MarkedRouteEntity>(50)
        for (i in 0 until 49) {
            rows.add(
                buildSyntheticRouteEntity(
                    relationId = 90_000L + i,
                    baseLat = 55.5 + (i / 10) * 0.1,
                    baseLng = 9.5 + (i % 10) * 0.1,
                    wayCount = 5,
                    pointsPerWay = 6
                )
            )
        }

        // Build a > 3 MB segmentsJson string for the 50th row
        val hugeSb = StringBuilder(3_300_000)
        hugeSb.append("[[")
        val targetPoints = 135_000
        for (p in 0 until targetPoints) {
            if (p > 0) hugeSb.append(',')
            hugeSb.append("[55.6000123,10.1000456]")
        }
        hugeSb.append("]]")
        val hugeJson = hugeSb.toString()
        assertTrue("Expected hugeJson > 3 MB, was ${hugeJson.length}", hugeJson.length > 3_000_000)

        rows.add(
            MarkedRouteEntity(
                osmRelationId = 90_999L,
                name = "Oversized 3MB Route",
                lengthKm = 999.0,
                operator = "E-Path",
                routeType = "hiking",
                network = "iwn",
                symbol = "E1",
                colorHex = "#D32F2F",
                segmentsJson = hugeJson,
                minLat = 55.5,
                maxLat = 55.7,
                minLng = 10.0,
                maxLng = 10.2,
                cachedAt = System.currentTimeMillis()
            )
        )
        dao.insertMarkedRoutes(rows)

        val snapshots = markedRepo.getMarkedRouteRenderSnapshots(54.4, 58.0, 7.8, 15.5, zoomLevel = 8.0)
        assertEquals("All 49 normal rows must load and the 3 MB row must be skipped without crashing", 49, snapshots.size)

        val mapView = MapView(context)
        mapView.layout(0, 0, 1080, 1920)
        mapView.controller.setZoom(8.0)
        mapView.controller.setCenter(GeoPoint(55.7, 10.0))

        val overlay = MarkedRoutesBatchOverlay()
        overlay.setRoutesSnapshot(snapshots)
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bmp), mapView, false)
        assertEquals("All 49 normal routes must draw on screen", 49, overlay.lastFrameRoutesDrawn)

        // Verify one-time cleanup removes the 3 MB row and its overlapping cache box when triggered on first run
        dao.insertMarkedRouteCacheBox(
            MarkedRouteCacheBoxEntity(
                boxKey = "box_oversized",
                minLat = 55.4,
                maxLat = 55.8,
                minLng = 9.9,
                maxLng = 10.3,
                fetchedAt = System.currentTimeMillis()
            )
        )
        MarkedRouteRepository.resetOversizedCleanupFlagForTests(context)
        markedRepo.runOneTimeOversizedRoutesCleanupIfNeeded()
        assertNull("One-time cleanup must delete the >1.5MB marked_route row", dao.getMarkedRouteById(90_999L))
        assertTrue(
            "One-time cleanup must delete cache boxes overlapping the >1.5MB row",
            dao.getMarkedRouteCacheBoxes(55.5, 55.7, 10.0, 10.2).isEmpty()
        )

        // Verify Denmark way filter in parseOverpassRoutesJson excludes German/Spanish ways and recomputes bbox from kept Danish ways
        val overpassWithGermanWayJson = """
            {
              "elements": [
                {"type": "node", "id": 1, "lat": 55.00, "lon": 9.40},
                {"type": "node", "id": 2, "lat": 55.10, "lon": 9.50},
                {"type": "node", "id": 3, "lat": 53.50, "lon": 10.00},
                {"type": "node", "id": 4, "lat": 53.60, "lon": 10.10},
                {"type": "way", "id": 101, "nodes": [1, 2]},
                {"type": "way", "id": 102, "nodes": [3, 4]},
                {
                  "type": "relation",
                  "id": 555001,
                  "tags": {"route": "hiking", "name": "E1 Danmark-Tyskland", "network": "iwn"},
                  "members": [
                    {"type": "way", "ref": 101, "role": ""},
                    {"type": "way", "ref": 102, "role": ""}
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = markedRepo.parseOverpassRoutesJson(overpassWithGermanWayJson)
        assertEquals(1, parsed.size)
        val e1 = parsed.first()
        assertEquals("Only the Danish way (101) must be kept", 1, e1.polylines.size)
        assertEquals(55.00, e1.minLat, 1e-6)
        assertEquals(55.10, e1.maxLat, 1e-6)
        assertEquals(9.40, e1.minLng, 1e-6)
        assertEquals(9.50, e1.maxLng, 1e-6)
    }

    // =========================================================================
    // Test 6: Process-wide cache shared across instances & cleared by "Ryd cache",
    //         cachedAt change triggers reload, and Udforsk zoom tiers
    // =========================================================================

    @Test
    fun processWideCacheSharedAndCleared_cachedAtMismatchReloads_and_udforskZoomTiers() = runBlocking {
        val entityV1 = buildSyntheticRouteEntity(
            relationId = 333001L,
            baseLat = 56.0,
            baseLng = 10.0,
            wayCount = 4,
            pointsPerWay = 5,
            cachedAt = 1000L
        )
        dao.insertMarkedRoutes(listOf(entityV1))

        val repoA = MarkedRouteRepository(context, dao, geometryRepo)
        val repoB = MarkedRouteRepository(context, dao, geometryRepo)

        MarkedRouteRepository.roomSegmentsLoadCount.set(0)
        repoA.getMarkedRouteRenderSnapshots(55.0, 57.0, 9.0, 11.0, zoomLevel = 8.0)
        assertEquals(1, MarkedRouteRepository.roomSegmentsLoadCount.get())

        // Second instance repoB must hit the shared process-wide cache without loading from Room
        repoB.getMarkedRouteRenderSnapshots(55.0, 57.0, 9.0, 11.0, zoomLevel = 8.0)
        assertEquals(1, MarkedRouteRepository.roomSegmentsLoadCount.get())

        // Updating cachedAt in Room must trigger a reload of that route's geometry
        val entityV2 = entityV1.copy(cachedAt = 2000L)
        dao.insertMarkedRoutes(listOf(entityV2))
        repoB.getMarkedRouteRenderSnapshots(55.0, 57.0, 9.0, 11.0, zoomLevel = 8.0)
        assertEquals("Changed cachedAt must reload geometry from Room", 2, MarkedRouteRepository.roomSegmentsLoadCount.get())

        // "Ryd cache" via DanskStiRepository clears the process-wide cache
        val danskStiRepo = DanskStiRepository(dao, context)
        danskStiRepo.clearSelectedCaches(setOf(CacheCategory.MARKED_ROUTES))
        assertEquals(0, MarkedRouteRepository.getProcessWideCachedRouteCount())
        assertEquals(0, MarkedRouteRepository.getProcessWideCachedTotalPoints())

        // Verify Udforsk catalogue map zoom tiers (< 9.5 -> 0, 9.5..12 -> 1, >= 12 -> 2)
        assertEquals(0, computeUdforskZoomTier(7.5))
        assertEquals(0, computeUdforskZoomTier(9.4))
        assertEquals(1, computeUdforskZoomTier(9.5))
        assertEquals(1, computeUdforskZoomTier(11.9))
        assertEquals(2, computeUdforskZoomTier(12.0))
        assertEquals(2, computeUdforskZoomTier(14.5))

        val trailPts = (0..100).map { idx ->
            GeoPointDto(56.0 + idx * 0.0002, 10.0 + idx * 0.0002)
        }
        val tier0Pts = decimateCatalogueTrailForZoomTier(trailPts, 0)
        val tier1Pts = decimateCatalogueTrailForZoomTier(trailPts, 1)
        val tier2Pts = decimateCatalogueTrailForZoomTier(trailPts, 2)
        assertTrue(tier0Pts.size < tier1Pts.size)
        assertTrue(tier1Pts.size < tier2Pts.size)
        assertEquals("At zoom >= 12 (tier 2), Udforsk map must use full unsimplified geometry", trailPts.size, tier2Pts.size)
    }

    private fun waitForLatchWithMainLooper(latch: CountDownLatch, timeoutMs: Long = 3_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            latch.await(15, TimeUnit.MILLISECONDS)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}

