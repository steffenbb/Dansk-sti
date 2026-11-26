package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailSegmentType
import com.example.ui.navigation.MapPoiItem
import com.example.ui.navigation.PoiClusterOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class Stress1MapAndPoiRenderingTest {

    @Test
    fun stress1_poiClusterOverlay1000PoisAcrossZoomLevelsAnd50kPointProjection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapView = MapView(context)
        mapView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        mapView.layout(0, 0, 1080, 1920)
        mapView.controller.setCenter(GeoPoint(55.8, 10.0))

        val types = listOf("shelter", "tent", "water", "toilet", "shop", "transit")
        val pois = (0 until 1000).map { i ->
            val row = i / 40
            val col = i % 40
            MapPoiItem(
                id = "stress_poi_$i",
                type = types[i % types.size],
                name = "POI $i",
                subText = "Detail $i",
                lat = 55.60 + row * 0.015,
                lng = 9.70 + col * 0.015
            )
        }

        val overlay = PoiClusterOverlay(context = context, poiItems = pois)
        assertEquals(1000, overlay.getItemCount())
        assertFalse("Idempotent setItems with same list and selection must return false", overlay.setItems(pois, null))

        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centerGeo = GeoPoint(pois[500].lat, pois[500].lng)

        // Draw at low zoom (< 13.0) -> POIs are clustered into grid buckets, bounding drawn items
        mapView.controller.setZoom(9.5)
        mapView.setExpectedCenter(centerGeo)
        overlay.draw(canvas, mapView, false)
        val lowZoomClusters = overlay.lastFrameClustersDrawn
        val lowZoomSingles = overlay.lastFrameSinglePoisDrawn
        val lowZoomTotalDrawn = lowZoomClusters + lowZoomSingles
        println("Stress1 low-zoom (9.5): clusters=$lowZoomClusters, singles=$lowZoomSingles, totalDrawn=$lowZoomTotalDrawn")
        assertTrue("Expected clustered markers at zoom 9.5", lowZoomClusters > 0)
        assertTrue(
            "Expected 60dp grid clustering at zoom 9.5 to bound drawn items well below 1000 (got $lowZoomTotalDrawn)",
            lowZoomTotalDrawn in 1..250
        )

        // Draw at high zoom (>= 13.0) -> 0 clusters, only individual viewport-clipped POIs drawn
        mapView.controller.setZoom(14.5)
        mapView.setExpectedCenter(centerGeo)
        overlay.draw(canvas, mapView, false)
        val highZoomClusters = overlay.lastFrameClustersDrawn
        val highZoomSingles = overlay.lastFrameSinglePoisDrawn
        println("Stress1 high-zoom (14.5): clusters=$highZoomClusters, singles=$highZoomSingles")
        assertEquals("At zoom >= 13.0 there must be 0 clusters drawn", 0, highZoomClusters)
        assertTrue("At zoom 14.5 viewport culling must draw only visible subset of 1000 POIs (got $highZoomSingles)", highZoomSingles in 1..400)

        // Stress test 50,000-point route projection via CoarseRouteIndex
        val routePoints = (0 until 50_000).map { i ->
            val walkKm = i * 0.01 // 500 km total at 10 m resolution
            RoutePoint(
                lat = 54.80 + i * 0.00005,
                lng = 9.00 + i * 0.00005,
                ele = 25.0,
                walkKm = walkKm,
                trailKm = walkKm,
                segmentType = TrailSegmentType.WALK,
                segmentIndex = 0
            )
        }
        val megaRoute = PlannedRoute(
            planId = 900_001L,
            trailId = "stress1_mega_route",
            trailName = "50k Point Mega Route",
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = 500.0,
            totalWalkKm = 500.0,
            points = routePoints,
            stages = emptyList()
        )

        val midPoint = routePoints[25_000]
        val (projectedWalkKm, detourM) = megaRoute.projectPoint(midPoint.lat + 0.0001, midPoint.lng)
        println("Stress1 50k-point projection: walkKm=$projectedWalkKm, detourM=$detourM")
        assertEquals(midPoint.walkKm, projectedWalkKm, 0.25)
        assertTrue("Detour from +0.0001 deg lat (~11 m) must be < 25 m", detourM < 25.0)
    }

    private fun buildZigzagMarkedRouteEntity(
        relationId: Long,
        baseLat: Double,
        baseLng: Double,
        wayCount: Int = 30,
        pointsPerWay: Int = 12,
        cachedAt: Long = System.currentTimeMillis()
    ): com.example.data.model.MarkedRouteEntity {
        val rootArr = org.json.JSONArray()
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY

        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * kotlin.math.cos(Math.toRadians(baseLat)).coerceAtLeast(0.2)
        val fwdNorthMeters = 12.0
        val fwdEastMeters = 16.0
        val perpNorthUnit = -0.8
        val perpEastUnit = 0.6

        for (w in 0 until wayCount) {
            val wayArr = org.json.JSONArray()
            for (p in 0 until pointsPerWay) {
                val globalIdx = w * (pointsPerWay - 1) + p
                val alongNorthM = globalIdx * fwdNorthMeters
                val alongEastM = globalIdx * fwdEastMeters
                val macroWaveM = 75.0 * kotlin.math.sin(globalIdx * 0.35)
                val localZigzagM = 32.0 * kotlin.math.sin(globalIdx * 1.65)
                val sidewaysM = if (globalIdx == 0) 0.0 else (macroWaveM + localZigzagM)
                val lat = baseLat + (alongNorthM + sidewaysM * perpNorthUnit) / metersPerDegLat
                val lng = baseLng + (alongEastM + sidewaysM * perpEastUnit) / metersPerDegLng
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lng < minLng) minLng = lng
                if (lng > maxLng) maxLng = lng
                wayArr.put(org.json.JSONArray().apply { put(lat); put(lng) })
            }
            rootArr.put(wayArr)
        }

        val colorHex = com.example.data.repository.MarkedRouteRepository.PALETTE[(relationId % com.example.data.repository.MarkedRouteRepository.PALETTE.size).toInt()]
        return com.example.data.model.MarkedRouteEntity(
            osmRelationId = relationId,
            name = "Stress1 Zigzag Rute #$relationId",
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

    @Test
    fun stress1_300ZigzagMarkedRoutesAnd2000PoisDrawnTogetherAtZoom7_10_14(): Unit = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val geomRepo = app.repository.geometryRepository
        com.example.data.repository.MarkedRouteRepository.clearProcessWideCache()
        dao.clearAllMarkedRoutes()
        dao.clearAllMarkedRouteCacheBoxes()

        val now = System.currentTimeMillis()
        val routes = ArrayList<com.example.data.model.MarkedRouteEntity>(300)
        for (i in 0 until 300) {
            val row = i / 20
            val col = i % 20
            val baseLat = 54.8 + row * 0.16
            val baseLng = 8.2 + col * 0.22
            routes.add(
                buildZigzagMarkedRouteEntity(
                    relationId = 50_000L + i,
                    baseLat = baseLat,
                    baseLng = baseLng,
                    wayCount = 30,
                    pointsPerWay = 12,
                    cachedAt = now
                )
            )
        }
        dao.insertMarkedRoutes(routes)

        for (cell in com.example.data.repository.MarkedRouteRepository.computeGridCellsForViewport(54.4, 58.0, 7.8, 15.5)) {
            dao.insertMarkedRouteCacheBox(
                com.example.data.model.MarkedRouteCacheBoxEntity(
                    boxKey = cell.cellKey,
                    minLat = cell.minLat,
                    maxLat = cell.maxLat,
                    minLng = cell.minLng,
                    maxLng = cell.maxLng,
                    fetchedAt = now
                )
            )
        }

        val types = listOf("shelter", "tent", "water", "toilet", "shop", "transit")
        val pois = (0 until 2000).map { i ->
            val row = i / 50
            val col = i % 50
            MapPoiItem(
                id = "stress1_combined_poi_$i",
                type = types[i % types.size],
                name = "POI $i",
                subText = "Detail $i",
                lat = 54.8 + row * 0.055,
                lng = 8.2 + col * 0.085
            )
        }

        val markedRepo = com.example.data.repository.MarkedRouteRepository(context, dao, geomRepo)
        val markedOverlay = com.example.ui.navigation.MarkedRoutesBatchOverlay()
        val poiOverlay = PoiClusterOverlay(context = context, poiItems = pois)

        val mapView = MapView(context)
        mapView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        mapView.layout(0, 0, 1080, 1920)
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        suspend fun runCombinedBenchmark(
            zoomLevel: Double,
            qMinLat: Double,
            qMaxLat: Double,
            qMinLng: Double,
            qMaxLng: Double
        ) {
            mapView.controller.setZoom(zoomLevel)
            mapView.controller.setCenter(GeoPoint(56.0, 10.4))
            mapView.setExpectedCenter(GeoPoint(56.0, 10.4))

            val snapshots = markedRepo.getMarkedRouteRenderSnapshots(qMinLat, qMaxLat, qMinLng, qMaxLng, zoomLevel = zoomLevel)
            markedOverlay.setRoutesSnapshot(snapshots)

            // 3 warm-up frames drawing both overlays together
            repeat(3) {
                markedOverlay.draw(canvas, mapView, false)
                poiOverlay.draw(canvas, mapView, false)
            }

            // 5 timed frames drawing both overlays together
            val samplesMs = DoubleArray(5)
            for (s in 0 until 5) {
                val t0 = System.nanoTime()
                markedOverlay.draw(canvas, mapView, false)
                poiOverlay.draw(canvas, mapView, false)
                samplesMs[s] = (System.nanoTime() - t0) / 1_000_000.0
            }
            samplesMs.sort()
            val medianMs = samplesMs[2]
            val routesDrawn = markedOverlay.lastFrameRoutesDrawn
            val verticesDrawn = markedOverlay.lastFrameVerticesDrawn
            val clustersDrawn = poiOverlay.lastFrameClustersDrawn
            val singlesDrawn = poiOverlay.lastFrameSinglePoisDrawn

            println(
                String.format(
                    java.util.Locale.US,
                    "[Stress1 Combined 300 Routes + 2000 POIs] zoom=%d | routes=%d | vertices=%d | clusters=%d | singles=%d | medianDrawMs=%.2f ms",
                    zoomLevel.toInt(),
                    routesDrawn,
                    verticesDrawn,
                    clustersDrawn,
                    singlesDrawn,
                    medianMs
                )
            )
            assertTrue("Expected routes drawn > 0 at zoom ${zoomLevel.toInt()}", routesDrawn > 0)
            assertTrue("Expected vertices drawn > 0 at zoom ${zoomLevel.toInt()}", verticesDrawn > 0)
            assertTrue("Expected POI clusters or single POIs drawn > 0 at zoom ${zoomLevel.toInt()}", (clustersDrawn + singlesDrawn) > 0)
        }

        runCombinedBenchmark(7.0, 54.4, 58.0, 7.8, 15.5)
        runCombinedBenchmark(10.0, 55.2, 56.8, 9.0, 11.8)
        runCombinedBenchmark(14.0, 55.85, 56.15, 10.15, 10.65)
    }
}
