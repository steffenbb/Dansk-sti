package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PreseededData
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailPoiEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class)
class Cuj1ExploreAndOfflineTest {

    @Test
    fun cuj1_groupedShelterCountsMatchPerTrailCountsForAllCatalogueTrailsAndEdgeFixture(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val repository = app.repository

        // Seed initial catalogue trails and shelters
        val catalogueTrails = PreseededData.getInitialTrails()
        dao.insertTrails(catalogueTrails)

        // Seed edge-case fixture:
        // 1) A row with both trailId and routeId set (detourMeters <= 3000)
        // 2) A shelter attached twice across trailId and routeId (counted per row, not DISTINCT)
        // 3) A trail with only non-shelter POIs (water source)
        val s1 = ShelterEntity(
            id = "cuj1_shelter_1",
            name = "Fixture Shelter 1",
            lat = 55.50,
            lng = 9.50,
            capacity = 6,
            hasWaterNearby = true,
            hasToiletNearby = true
        )
        val s2 = ShelterEntity(
            id = "cuj1_shelter_2",
            name = "Fixture Shelter 2",
            lat = 55.52,
            lng = 9.52,
            capacity = 4,
            hasWaterNearby = false,
            hasToiletNearby = false
        )
        val nonShelterWater = PoiEntity(
            id = "cuj1_water_only",
            type = "water",
            name = "Fixture Vandpost",
            lat = 57.50,
            lng = 10.50
        )
        dao.insertShelters(listOf(s1, s2))
        dao.insertPois(listOf(nonShelterWater))

        val fixtureTrailDual = TrailEntity(
            id = "cuj1_trail_dual",
            name = "Fixture Dual Trail",
            region = "Jylland",
            computedWalkingKm = 25.0,
            description = "Fixture",
            bboxSouth = 55.49,
            bboxNorth = 55.61,
            bboxWest = 9.49,
            bboxEast = 9.61
        )
        val fixtureTrailRouteTarget = TrailEntity(
            id = "cuj1_route_target",
            name = "Fixture Route Target",
            region = "Jylland",
            computedWalkingKm = 18.0,
            description = "Fixture",
            bboxSouth = 55.49,
            bboxNorth = 55.56,
            bboxWest = 9.49,
            bboxEast = 9.56
        )
        val fixtureTrailNonShelterOnly = TrailEntity(
            id = "cuj1_trail_non_shelter_only",
            name = "Fixture Non-Shelter Trail",
            region = "Nordjylland",
            computedWalkingKm = 12.0,
            description = "Only water POI attached, zero shelters in remote bbox",
            bboxSouth = 57.90,
            bboxNorth = 57.95,
            bboxWest = 11.90,
            bboxEast = 11.95
        )
        dao.insertTrails(listOf(fixtureTrailDual, fixtureTrailRouteTarget, fixtureTrailNonShelterOnly))

        dao.deletePoisForRoute(fixtureTrailDual.id)
        dao.deletePoisForRoute("cuj1_secondary_trail")
        dao.deletePoisForRoute(fixtureTrailRouteTarget.id)
        dao.deletePoisForRoute(fixtureTrailNonShelterOnly.id)

        dao.insertTrailPois(
            listOf(
                // 1) Row with BOTH trailId and routeId set (detourMeters <= 3000)
                TrailPoiEntity(
                    trailId = fixtureTrailDual.id,
                    poiId = s1.id,
                    kmOnRoute = 3.0,
                    detourMeters = 450.0,
                    routeId = fixtureTrailRouteTarget.id
                ),
                // 2) Same shelter s1 attached a SECOND time to fixtureTrailDual via routeId (counted per row, not DISTINCT)
                TrailPoiEntity(
                    trailId = "cuj1_secondary_trail",
                    poiId = s1.id,
                    kmOnRoute = 9.5,
                    detourMeters = 800.0,
                    routeId = fixtureTrailDual.id
                ),
                // 3) Row with both trailId and routeId equal to the same id (must not double-count on UNION ALL)
                TrailPoiEntity(
                    trailId = fixtureTrailDual.id,
                    poiId = s2.id,
                    kmOnRoute = 14.0,
                    detourMeters = 1200.0,
                    routeId = fixtureTrailDual.id
                ),
                // 4) Row with detourMeters > 3000 (must be excluded by both old and new queries)
                TrailPoiEntity(
                    trailId = "cuj1_secondary_trail",
                    poiId = s2.id,
                    kmOnRoute = 18.0,
                    detourMeters = 3500.0,
                    routeId = fixtureTrailRouteTarget.id
                ),
                // 5) Non-shelter POI attached to fixtureTrailNonShelterOnly
                TrailPoiEntity(
                    trailId = fixtureTrailNonShelterOnly.id,
                    poiId = nonShelterWater.id,
                    kmOnRoute = 2.0,
                    detourMeters = 50.0,
                    routeId = null
                )
            )
        )

        val allTrails = dao.getAllTrailsList()
        assertTrue("Expected at least 32 catalogue trails + 3 fixture trails", allTrails.size >= 35)

        val groupedCounts = repository.getShelterCountsForTrails(allTrails)

        // Verify fixture counts explicitly
        assertEquals(3, groupedCounts[fixtureTrailDual.id])
        assertEquals(1, groupedCounts[fixtureTrailRouteTarget.id])
        assertEquals(0, groupedCounts[fixtureTrailNonShelterOnly.id])

        // Verify 100% equivalence between old per-trail getShelterCountForTrail and new grouped getShelterCountsForTrails
        for (trail in allTrails) {
            val expectedPerTrail = repository.getShelterCountForTrail(trail.id)
            val actualGrouped = groupedCounts[trail.id] ?: 0
            assertEquals(
                "Grouped shelter count must equal getShelterCountForTrail for ${trail.id} (${trail.name})",
                expectedPerTrail,
                actualGrouped
            )
        }
    }

    @Test
    fun cuj1_exploreSearchFilterAndOfflineGeometryCacheRoundTrip(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val geomRepo = app.repository.geometryRepository

        val catalogueTrails = PreseededData.getInitialTrails()
        dao.insertTrails(catalogueTrails)

        val hærvejen = catalogueTrails.first { it.name.contains("Hærvejen", ignoreCase = true) }
        val syntheticGeom = com.example.data.model.TrailGeometryDto(
            trailId = hærvejen.id,
            osmRelationId = hærvejen.osmRelationId ?: 1001L,
            segments = listOf(
                com.example.data.model.TrailSegmentDto(
                    type = com.example.data.model.TrailSegmentType.WALK,
                    points = listOf(
                        com.example.data.model.GeoPointDto(56.45, 9.40, 42.0),
                        com.example.data.model.GeoPointDto(56.46, 9.41, 48.0),
                        com.example.data.model.GeoPointDto(56.47, 9.42, 44.0)
                    ),
                    lengthKm = 2.6,
                    surface = "gravel",
                    highway = "track"
                )
            ),
            totalWalkingKm = 2.6,
            totalAscentM = 6,
            totalDescentM = 4,
            gapsOver200mCount = 0,
            bboxSouth = 56.45,
            bboxNorth = 56.47,
            bboxWest = 9.40,
            bboxEast = 9.42
        )
        geomRepo.saveGeometry(syntheticGeom)
        assertTrue("Saved geometry should be cached on disk", geomRepo.isGeometryCached(hærvejen.id))
        val loaded = geomRepo.loadGeometry(hærvejen.id)
        assertEquals(hærvejen.id, loaded?.trailId)
        assertEquals(3, loaded?.segments?.firstOrNull()?.points?.size)
    }
}
