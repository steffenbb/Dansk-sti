package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CustomRouteEntity
import com.example.data.model.PoiEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.routing.LatLng
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class Cuj4MapLayersAndLibraryTest {

    @Test
    fun cuj4_mapLayerTogglesWaterAccessAndMineTureCascadeCleanup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val repo = app.repository

        // 1. Water POI & Access polygon layer cache read
        val water = PoiEntity(
            id = "cuj4_water_1",
            type = "water",
            name = "Skovkildevand",
            lat = 56.10,
            lng = 9.90
        )
        dao.insertPois(listOf(water))
        val waterPois = dao.getPoisByType("water")
        assertTrue(waterPois.any { it.id == "cuj4_water_1" })

        val poly = AccessPolygon(
            id = "cuj4_poly_private",
            name = "Privat Skovpart",
            type = AccessLandType.PRIVATE_FOREST,
            coordinates = listOf(
                LatLng(56.10, 9.90),
                LatLng(56.11, 9.90),
                LatLng(56.11, 9.91),
                LatLng(56.10, 9.91),
                LatLng(56.10, 9.90)
            ),
            source = "OpenStreetMap",
            operatorOrOwner = "Privat"
        )
        dao.insertAccessPolygons(listOf(repo.accessLandRepo.modelToEntity(poly, "tile_561_99")))
        val cachedPolys = repo.getCachedAccessPolygons(56.09, 56.12, 9.89, 9.92)
        assertTrue(cachedPolys.any { it.id == "cuj4_poly_private" && it.type == AccessLandType.PRIVATE_FOREST })

        // 2. Mine ture: create custom route, trip plan, and custom waypoint with photo, then verify clean deletion
        val routeId = 404_001L
        dao.insertCustomRoute(
            CustomRouteEntity(
                id = routeId,
                name = "CUJ4 Custom Route",
                createdAt = 1000L,
                updatedAt = 1000L,
                optionsJson = "{}",
                waypointsJson = "[]",
                totalKm = 1.4,
                ascentM = 12.0,
                descentM = 10.0,
                pathSharePct = 85.0,
                roadKm = 0.2,
                geometryFile = "",
                sourceName = "BRouter",
                fetchedAt = 1000L
            )
        )
        val planId = 404_002L
        dao.insertTripPlan(
            TripPlanEntity(
                id = planId,
                trailId = "route_$routeId",
                trailName = "CUJ4 Custom Route",
                startKm = 0.0,
                endKm = 1.4,
                isReverse = false,
                totalDistanceKm = 1.4,
                targetDays = 1,
                overnightType = "BOTH",
                preferredDailyKm = 15.0,
                stagesJson = "[]",
                customRouteId = routeId
            )
        )
        val photoFile = File(context.filesDir, "cuj4_waypoint_photo.jpg").apply {
            writeBytes(ByteArray(64) { 0x2A })
        }
        val wpId = "cuj4_wp_1"
        dao.insertCustomWaypoint(
            UserCustomWaypointEntity(
                id = wpId,
                routeId = routeId,
                name = "CUJ4 Waypoint",
                note = "God lejr",
                lat = 56.105,
                lng = 9.905,
                photoPath = photoFile.absolutePath,
                createdAt = 1000L
            )
        )
        assertTrue(photoFile.exists())

        repo.deleteCustomWaypoint(wpId)
        assertFalse("Waypoint photo file must be deleted on waypoint deletion", photoFile.exists())

        repo.deleteTripPlan(planId)
        assertEquals(null, dao.getTripPlanById(planId))

        repo.deleteCustomRoute(routeId)
        assertEquals(null, dao.getCustomRouteById(routeId))
    }

    @Test
    fun cuj4_overlayZOrderShelterTapOnMarkedRouteAndRydCacheClearsRoomAndInMemoryCaches(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val repo = app.repository
        val geomRepo = repo.geometryRepository

        com.example.data.repository.MarkedRouteRepository.clearProcessWideCache()

        val routeEntity = com.example.data.model.MarkedRouteEntity(
            osmRelationId = 904_001L,
            name = "CUJ4 Afmærket Rute",
            lengthKm = 14.0,
            operator = "Naturstyrelsen",
            routeType = "hiking",
            network = "rwn",
            symbol = "Blå",
            colorHex = "#1E88E5",
            segmentsJson = """[[[56.0000,10.0000],[56.0000,10.0200],[56.0000,10.0400]]]""",
            minLat = 56.0000,
            maxLat = 56.0000,
            minLng = 10.0000,
            maxLng = 10.0400,
            cachedAt = System.currentTimeMillis()
        )
        dao.insertMarkedRoutes(listOf(routeEntity))
        dao.insertMarkedRouteCacheBox(
            com.example.data.model.MarkedRouteCacheBoxEntity(
                boxKey = "cuj4_box_56_10",
                minLat = 55.9,
                maxLat = 56.1,
                minLng = 9.9,
                maxLng = 10.1,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val shelterOnRoute = com.example.data.model.ShelterEntity(
            id = "cuj4_shelter_on_route",
            name = "CUJ4 Shelter Midt På Ruten",
            lat = 56.0000,
            lng = 10.0200,
            shelterType = "Shelter",
            capacity = 6,
            bookingRequired = false
        )

        val mapView = org.osmdroid.views.MapView(context)
        mapView.layout(0, 0, 1000, 1000)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(org.osmdroid.util.GeoPoint(56.0000, 10.0200))

        var tappedRoute: com.example.data.model.MarkedRouteDto? = null
        var tappedPoi: com.example.ui.navigation.PoiItem? = null

        val markedRepo = com.example.data.repository.MarkedRouteRepository(context, dao, geomRepo)
        val manager = com.example.ui.navigation.MapLayersOverlayManager(
            context = context,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate),
            markedRouteRepository = markedRepo,
            onMarkedRouteTapped = { dto -> tappedRoute = dto },
            onPoiTapped = { poi -> tappedPoi = poi }
        )
        manager.attachToMap(mapView, isDark = false)
        manager.updateLayersState(
            state = com.example.data.local.MapLayersState(showMarkedRoutes = true, showShelters = true),
            shelters = listOf(shelterOnRoute),
            isDark = false
        )

        val snapshots = markedRepo.getMarkedRouteRenderSnapshots(55.9, 56.1, 9.9, 10.1, zoomLevel = 14.0)
        assertTrue("In-memory marked-route cache must contain loaded snapshot", com.example.data.repository.MarkedRouteRepository.getProcessWideCachedRouteCount() > 0)
        manager.getMarkedRoutesBatchOverlay().setRoutesSnapshot(snapshots)

        val bmp = android.graphics.Bitmap.createBitmap(1000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        for (ov in mapView.overlays) {
            ov.draw(canvas, mapView, false)
        }

        // 1. Verify overlay order: POI above trail line (KEY_ROUTE) above marked routes (KEY_MARKED_ROUTES)
        val markedOverlay = manager.getOverlay(com.example.ui.navigation.MapLayersOverlayManager.KEY_MARKED_ROUTES)!!
        val routeOverlay = manager.getOverlay(com.example.ui.navigation.MapLayersOverlayManager.KEY_ROUTE)!!
        val poiOverlay = manager.getOverlay(com.example.ui.navigation.MapLayersOverlayManager.KEY_POIS)!!
        val idxMarked = mapView.overlays.indexOf(markedOverlay)
        val idxRoute = mapView.overlays.indexOf(routeOverlay)
        val idxPoi = mapView.overlays.indexOf(poiOverlay)
        assertTrue("KEY_MARKED_ROUTES ($idxMarked) must be below KEY_ROUTE ($idxRoute)", idxMarked < idxRoute)
        assertTrue("KEY_ROUTE ($idxRoute) must be below KEY_POIS ($idxPoi)", idxRoute < idxPoi)

        // 2. Tap on the shelter positioned directly on top of the marked route -> opens the shelter, not the marked route
        val ptOnShelter = android.graphics.Point()
        mapView.projection.toPixels(org.osmdroid.util.GeoPoint(56.0000, 10.0200), ptOnShelter)
        val motionTapShelter = android.view.MotionEvent.obtain(
            0L,
            0L,
            android.view.MotionEvent.ACTION_UP,
            ptOnShelter.x.toFloat(),
            ptOnShelter.y.toFloat(),
            0
        )
        var consumed = false
        for (i in mapView.overlays.indices.reversed()) {
            if (mapView.overlays[i].onSingleTapConfirmed(motionTapShelter, mapView)) {
                consumed = true
                break
            }
        }
        motionTapShelter.recycle()

        assertTrue("Tap on shelter on top of marked route must be consumed", consumed)
        assertEquals("cuj4_shelter_on_route", tappedPoi?.id)
        assertEquals("Marked route must not open when shelter on top is tapped", null, tappedRoute)

        // 3. "Ryd cache" empties Room cache tables and the in-memory marked-route cache
        repo.clearSelectedCaches(com.example.data.repository.CacheCategory.entries.toSet())

        assertEquals(0, com.example.data.repository.MarkedRouteRepository.getProcessWideCachedRouteCount())
        assertEquals(0L, dao.getMarkedRoutesApproxBytes())
        assertEquals(0L, dao.getMarkedRouteBoxesApproxBytes())
        assertEquals(0L, dao.getAccessPolygonsApproxBytes())
        assertEquals(0L, dao.getWaterFeaturesApproxBytes())
        assertEquals(0L, dao.getWaterTileCacheApproxBytes())
        assertEquals(0L, dao.getCachedRouteLegsApproxBytes())
    }
}
