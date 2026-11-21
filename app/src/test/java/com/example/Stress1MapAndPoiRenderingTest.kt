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
@Config(sdk = [34], application = DanskStiApp::class)
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
}
