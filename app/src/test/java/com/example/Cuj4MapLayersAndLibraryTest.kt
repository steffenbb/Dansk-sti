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
}
