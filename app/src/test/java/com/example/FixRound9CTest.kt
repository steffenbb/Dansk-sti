package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.local.PreseededData
import com.example.data.model.TrailDownloadProgress
import com.example.data.repository.MarkedRouteRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.ui.screens.TrailBrowserScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.GeoUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixRound9CTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: DanskStiApp
    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = app.database
        TrailGeometryRepository.clearRecordedNameMismatches(app.filesDir)
        TrailGeometryRepository.customRelationFetcher = null
        MarkedRouteRepository.customMoreMarkedRoutesFetcher = null
        File(app.filesDir, "trails").deleteRecursively()
        File(app.filesDir, "trail_geometries").deleteRecursively()
        File(app.filesDir, "more_marked_routes_cache.json").delete()
        runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.clearAllTables()
                app.repository.initializePreseededDataIfEmpty()
            }
        }
    }

    @After
    fun tearDown() {
        TrailGeometryRepository.customRelationFetcher = null
        MarkedRouteRepository.customMoreMarkedRoutesFetcher = null
        TrailGeometryRepository.clearRecordedNameMismatches(app.filesDir)
    }

    /**
     * Item 1:
     * Verify all 39 new OSM relations are in PreseededData with source = "OSM wiki Denmark Walking Routes",
     * no typed lengths, descriptions, or regions; region is computed from the route's centre
     * (Jylland, Fyn & Øerne, Sjælland & Lolland-Falster, Bornholm); and on first download, any mismatch
     * between the relation's name tag and the catalogue name is logged in Diagnostics.
     */
    @Test
    fun item1_all39CatalogueRelationsPresentWithoutTypedValuesAndNameMismatchLoggedInDiagnostics(): Unit = runBlocking {
        val expected39Relations = mapOf(
            297398L to "E1",
            556666L to "E6",
            2203180L to "Nordsøstien",
            1970035L to "Sjællandsleden",
            1792586L to "Pilgrimsruten",
            1595942L to "Fjordstien",
            2813821L to "Sjællandsleden 2012",
            309907L to "Sydsjællandsleden",
            4004055L to "Hærvejen nordvest",
            2839163L to "Møn Rundt",
            4004054L to "Hærvejen nordøst",
            374893L to "Kyst til Kyst stien",
            8220396L to "Camino Haderslev Næs",
            1166751L to "Vestkyststien",
            2836954L to "Paradisruten",
            2838931L to "Sejerø Bugt Ruten",
            2820402L to "Lagunestien",
            2717313L to "Gudernes Stræde",
            2021716L to "Kongeåstien",
            3029875L to "Vandreruten Århus-Silkeborg",
            1727731L to "Alsstien",
            4003809L to "Hærvejen nord",
            3029770L to "Bryrupbanestien",
            2076253L to "Jyderupstien",
            2838766L to "Musholmleden",
            3030656L to "Naturstien Nibe-Hvalpsund",
            8397167L to "Marskstien",
            13577498L to "Halsninoen",
            3031593L to "Alhedestien",
            1710329L to "Gjernstien",
            2843842L to "Trelleborgleden",
            3038370L to "Mølleådalen",
            3030189L to "Den Skæve Bane",
            2932602L to "Gjerrildbanestien",
            2466789L to "Fjord til Fjord Naturstien",
            2830942L to "Susåleden",
            2835117L to "Falster Rundt",
            2836839L to "Østersøstien",
            14190349L to "Djurslandstien"
        )
        assertEquals(39, expected39Relations.size)

        val catalogue = PreseededData.getInitialTrails()
        for ((relId, expectedName) in expected39Relations) {
            val match = catalogue.find { it.osmRelationId == relId }
            assertNotNull("Catalogue must contain relation $relId ($expectedName)", match)
            assertEquals(expectedName, match!!.name)
            assertEquals("OSM wiki Denmark Walking Routes", match.source)
            assertEquals("No typed length allowed for $expectedName", 0.0, match.computedWalkingKm, 0.001)
            assertEquals("No typed description allowed for $expectedName", "", match.description)
            assertEquals("No typed region allowed for $expectedName", "", match.region)
            assertEquals("No typed parentRegion allowed for $expectedName", "", match.parentRegion)
        }

        // Verify region computation from centre for all 4 Danish regions
        assertEquals("Jylland", GeoUtils.computeDanishRegionFromCenter(56.15, 9.55))
        assertEquals("Fyn & Øerne", GeoUtils.computeDanishRegionFromCenter(55.15, 10.40))
        assertEquals("Sjælland & Lolland-Falster", GeoUtils.computeDanishRegionFromCenter(55.45, 11.80))
        assertEquals("Sjælland & Lolland-Falster", GeoUtils.computeDanishRegionFromCenter(54.77, 11.50))
        assertEquals("Bornholm", GeoUtils.computeDanishRegionFromCenter(55.12, 14.90))

        // Verify first download compares relation's name tag with catalogue name and logs mismatch in Diagnostics
        val bryrupTrail = database.danskStiDao().getTrailById("trail_bryrupbanestien")!!
        TrailGeometryRepository.customRelationFetcher = { relId, collected, onChildProgress ->
            onChildProgress?.invoke(1, 1)
            collected[relId] = mapOf(
                "type" to "relation",
                "id" to relId,
                "tags" to mapOf(
                    "type" to "route",
                    "route" to "hiking",
                    "name" to "Horsens-Silkeborg Naturstien (Bryrupbanestien)"
                ),
                "members" to listOf(
                    mapOf(
                        "type" to "way",
                        "ref" to 1001L,
                        "role" to "",
                        "geometry" to listOf(
                            mapOf("lat" to 56.0200, "lon" to 9.5200),
                            mapOf("lat" to 56.0500, "lon" to 9.6500),
                            mapOf("lat" to 55.9800, "lon" to 9.7800)
                        )
                    )
                )
            )
            true
        }

        val segments = app.repository.getTrailPoints(bryrupTrail)
        assertTrue("Should download real geometry segments", segments.isNotEmpty())

        val updatedBryrup = database.danskStiDao().getTrailById("trail_bryrupbanestien")!!
        assertTrue("Length must be computed from downloaded geometry", updatedBryrup.computedWalkingKm > 10.0)
        assertEquals("Region must be computed from route centre", "Jylland", updatedBryrup.region)

        val mismatches = app.repository.geometryRepository.getNameMismatches()
        assertTrue(
            "Mismatch between 'Bryrupbanestien' and 'Horsens-Silkeborg Naturstien (Bryrupbanestien)' must be logged",
            mismatches.any {
                it.trailId == "trail_bryrupbanestien" &&
                    it.catalogueName == "Bryrupbanestien" &&
                    it.osmRelationName == "Horsens-Silkeborg Naturstien (Bryrupbanestien)"
            }
        )
    }

    /**
     * Item 2:
     * Udforsk has section "Flere afmærkede ruter" filled from Overpass
     * relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area Denmark); out tags;
     * cached 30 days, excluding relations already in the catalogue.
     * Tapping one saves it as a trail and downloads it with the existing pipeline.
     */
    @Test
    fun item2_moreMarkedRoutesExcludesCatalogueCaches30DaysAndTappingSavesAndDownloads(): Unit = runBlocking {
        assertTrue(
            "Overpass query must match relation[\"route\"=\"hiking\"][\"network\"~\"^(iwn|nwn|rwn)$\"]",
            MarkedRouteRepository.MORE_MARKED_ROUTES_OVERPASS_QUERY.contains(
                """relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"]"""
            )
        )
        assertEquals(30L * 24L * 60L * 60L * 1000L, MarkedRouteRepository.MORE_MARKED_ROUTES_CACHE_TTL_MS)

        var overpassQueryCount = 0
        MarkedRouteRepository.customMoreMarkedRoutesFetcher = {
            overpassQueryCount++
            """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": 374893,
                  "tags": {
                    "name": "Kyst til Kyst stien",
                    "route": "hiking",
                    "network": "rwn"
                  }
                },
                {
                  "type": "relation",
                  "id": 99887766,
                  "tags": {
                    "name": " HimmelbjergRuten ",
                    "route": "hiking",
                    "network": "rwn",
                    "ref": "HBR"
                  }
                }
              ]
            }
            """.trimIndent()
        }

        val markedRepo = MarkedRouteRepository(
            context = app,
            dao = database.danskStiDao(),
            trailGeometryRepository = app.repository.geometryRepository
        )

        val firstFetch = markedRepo.getMoreMarkedHikingRoutes(forceRefresh = false)
        assertEquals(1, overpassQueryCount)
        assertEquals("Catalogue relation 374893 must be excluded", 1, firstFetch.size)
        assertEquals(99887766L, firstFetch.first().osmRelationId)
        assertEquals("HimmelbjergRuten", firstFetch.first().name)

        // Second fetch must hit the 30-day cache without querying Overpass again
        val cachedFetch = markedRepo.getMoreMarkedHikingRoutes(forceRefresh = false)
        assertEquals(1, overpassQueryCount)
        assertEquals(1, cachedFetch.size)
        assertEquals(99887766L, cachedFetch.first().osmRelationId)

        // Tapping one saves it as a trail and downloads its geometry with the existing pipeline
        TrailGeometryRepository.customRelationFetcher = { relId, collected, onChildProgress ->
            onChildProgress?.invoke(1, 1)
            collected[relId] = mapOf(
                "type" to "relation",
                "id" to relId,
                "tags" to mapOf("name" to "HimmelbjergRuten", "route" to "hiking"),
                "members" to listOf(
                    mapOf(
                        "type" to "way",
                        "ref" to 2001L,
                        "role" to "",
                        "geometry" to listOf(
                            mapOf("lat" to 56.1000, "lon" to 9.6800),
                            mapOf("lat" to 56.1200, "lon" to 9.7200),
                            mapOf("lat" to 56.1400, "lon" to 9.7600)
                        )
                    )
                )
            )
            true
        }

        val saveResult = markedRepo.saveAndDownloadDiscoveredRoute(firstFetch.first())
        assertTrue("Saving and downloading discovered route must succeed", saveResult.isSuccess)
        val savedTrail = saveResult.getOrNull()!!
        assertEquals("trail_osm_99887766", savedTrail.id)
        assertTrue("Downloaded trail must have positive computed length", savedTrail.computedWalkingKm > 4.0)
        assertEquals("Jylland", savedTrail.region)
        assertTrue(savedTrail.isGeometryDownloaded)

        // Once saved in the database, it is excluded from "Flere afmærkede ruter"
        val afterSaveFetch = markedRepo.getMoreMarkedHikingRoutes(forceRefresh = false)
        assertTrue("Saved route must now be excluded from Flere afmærkede ruter", afterSaveFetch.isEmpty())
    }

    /**
     * Item 3:
     * Long routes (over 300 km) download with a progress indicator and can be planned in sections like any trail.
     */
    @Test
    fun item3_longRouteOver300KmDownloadsWithProgressAndPlansSections(): Unit = runBlocking {
        val sjLeden = database.danskStiDao().getTrailById("trail_sjaellandsleden")!!
        val progressUpdates = mutableListOf<TrailDownloadProgress>()

        // Build a ~360 km multi-point relation across Sjælland
        val points = (0..40).map { idx ->
            val t = idx / 40.0
            mapOf(
                "lat" to (55.00 + (idx % 2) * 0.08 + t * 0.95),
                "lon" to (11.10 + t * 1.40)
            )
        }
        TrailGeometryRepository.customRelationFetcher = { relId, collected, onChildProgress ->
            onChildProgress?.invoke(1, 2)
            onChildProgress?.invoke(2, 2)
            collected[relId] = mapOf(
                "type" to "relation",
                "id" to relId,
                "tags" to mapOf("name" to "Sjællandsleden", "route" to "hiking"),
                "members" to listOf(
                    mapOf(
                        "type" to "way",
                        "ref" to 3001L,
                        "role" to "",
                        "geometry" to points
                    )
                )
            )
            true
        }

        val dto = app.repository.geometryRepository.fetchAndProcessTrail(
            trailId = sjLeden.id,
            trailName = sjLeden.name,
            osmRelationId = sjLeden.osmRelationId,
            onProgress = { prog -> progressUpdates.add(prog) }
        )
        assertNotNull(dto)
        app.repository.geometryRepository.saveGeometry(dto!!)
        assertTrue("Route must be over 300 km (was ${dto.totalWalkingKm} km)", dto.totalWalkingKm > 300.0)
        assertTrue("Progress indicator must receive multiple stage updates", progressUpdates.size >= 3)
        assertTrue("Progress fraction must increase to 1.0", progressUpdates.last().fraction >= 0.99f)

        // Persist the downloaded metadata in DB
        val computedReg = GeoUtils.computeDanishRegionFromBbox(
            dto.bboxSouth, dto.bboxWest, dto.bboxNorth, dto.bboxEast
        )
        database.danskStiDao().insertTrails(
            listOf(
                sjLeden.copy(
                    region = computedReg,
                    parentRegion = computedReg,
                    computedWalkingKm = dto.totalWalkingKm,
                    bboxSouth = dto.bboxSouth,
                    bboxWest = dto.bboxWest,
                    bboxNorth = dto.bboxNorth,
                    bboxEast = dto.bboxEast,
                    isGeometryDownloaded = true
                )
            )
        )

        // Plan a 45 km section (from km 50 to km 95) of the > 300 km route in RouteBuilderViewModel
        val routeBuilderVm = RouteBuilderViewModel(app)
        routeBuilderVm.initialize(baseTrailId = "trail_sjaellandsleden")

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && routeBuilderVm.uiState.value.baseTrailSegments.isEmpty()) {
            Thread.sleep(50)
        }
        assertEquals(PlannerMode.TRAIL, routeBuilderVm.uiState.value.mode)
        assertTrue(routeBuilderVm.uiState.value.baseTrailSegments.isNotEmpty())

        routeBuilderVm.setTrailSection(startKm = 50.0, endKm = 95.0)
        val stateAfterSection = routeBuilderVm.uiState.value
        val startWp = stateAfterSection.waypoints.first()
        val endWp = stateAfterSection.waypoints.last()
        assertEquals(50.0, startWp.trailKm ?: 0.0, 0.5)
        assertEquals(95.0, endWp.trailKm ?: 0.0, 0.5)
        assertEquals("Planned section length should be ~45 km", 45.0, stateAfterSection.stats.totalKm, 2.0)
    }

    private suspend fun seedTrailWithCachedGeometry(
        trailId: String,
        osmRelationId: Long,
        name: String,
        km: Double,
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ) {
        val geom = com.example.data.model.TrailGeometryDto(
            trailId = trailId,
            osmRelationId = osmRelationId,
            osmRelationName = name,
            segments = listOf(
                com.example.data.model.TrailSegmentDto(
                    type = com.example.data.model.TrailSegmentType.WALK,
                    points = listOf(
                        com.example.data.model.GeoPointDto(south, west, 5.0),
                        com.example.data.model.GeoPointDto((south + north) / 2.0, (west + east) / 2.0, 12.0),
                        com.example.data.model.GeoPointDto(north, east, 8.0)
                    ),
                    lengthKm = km
                )
            ),
            totalWalkingKm = km,
            totalAscentM = (km * 8).toInt(),
            totalDescentM = (km * 8).toInt(),
            gapsOver200mCount = 0,
            bboxSouth = south,
            bboxWest = west,
            bboxNorth = north,
            bboxEast = east
        )
        app.repository.geometryRepository.saveGeometry(geom)
        val computedReg = GeoUtils.computeDanishRegionFromBbox(south, west, north, east)
        val existing = database.danskStiDao().getTrailById(trailId)!!
        database.danskStiDao().insertTrails(
            listOf(
                existing.copy(
                    region = computedReg,
                    parentRegion = computedReg,
                    computedWalkingKm = km,
                    bboxSouth = south,
                    bboxWest = west,
                    bboxNorth = north,
                    bboxEast = east,
                    isGeometryDownloaded = true
                )
            )
        )
    }

    /**
     * Item 4:
     * Filters (region, length) work for all routes, using computed values only,
     * and Udforsk shows both the new catalogue routes and the "Flere afmærkede ruter" section.
     */
    @Test
    fun item4_filtersWorkUsingComputedValuesOnlyAndUdforskRendersNewRoutes(): Unit = runBlocking {
        // Seed computed geometry for 3 routes across different regions & lengths:
        // 1. Marskstien (Jylland, 54 km -> MEDIUM)
        seedTrailWithCachedGeometry(
            trailId = "trail_marskstien",
            osmRelationId = 8397167L,
            name = "Marskstien",
            km = 54.0,
            south = 54.92,
            west = 8.65,
            north = 55.02,
            east = 8.88
        )
        // 2. Mølleådalen (Sjælland & Lolland-Falster, 28 km -> SHORT)
        seedTrailWithCachedGeometry(
            trailId = "trail_moelleaadalen",
            osmRelationId = 3038370L,
            name = "Mølleådalen",
            km = 28.0,
            south = 55.77,
            west = 12.35,
            north = 55.83,
            east = 12.56
        )
        // 3. Kyst til Kyst stien (Jylland, 180 km -> LONG)
        seedTrailWithCachedGeometry(
            trailId = "trail_kyst_til_kyst_stien",
            osmRelationId = 374893L,
            name = "Kyst til Kyst stien",
            km = 180.0,
            south = 55.55,
            west = 8.15,
            north = 55.72,
            east = 9.55
        )

        MarkedRouteRepository.customMoreMarkedRoutesFetcher = {
            """
            {
              "elements": [
                {
                  "type": "relation",
                  "id": 99887766,
                  "tags": {
                    "name": "HimmelbjergRuten",
                    "route": "hiking",
                    "network": "rwn"
                  }
                }
              ]
            }
            """.trimIndent()
        }

        val exploreVm = ExploreViewModel(app)
        composeTestRule.setContent {
            DanskStiTheme {
                TrailBrowserScreen(
                    viewModel = exploreVm,
                    onTrailClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Unfiltered: all 51 catalogue trails are present
        composeTestRule.waitUntil(5_000) {
            exploreVm.filteredTrails.value.size >= 51
        }

        // Filter by region = "Jylland" -> only trails with computed center in Jylland match
        exploreVm.setSelectedRegion("Jylland")
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(5_000) {
            val ids = exploreVm.filteredTrails.value.map { it.id }.toSet()
            ids.contains("trail_marskstien") &&
                ids.contains("trail_kyst_til_kyst_stien") &&
                !ids.contains("trail_moelleaadalen") &&
                !ids.contains("trail_e1") // undownloaded trail has no computed region
        }

        // Add length filter = MEDIUM (50-150 km) -> only Marskstien (54 km) matches in Jylland
        exploreVm.setLengthFilter(LengthFilter.MEDIUM)
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(5_000) {
            val ids = exploreVm.filteredTrails.value.map { it.id }
            ids == listOf("trail_marskstien")
        }

        // Filter by region = "Sjælland & Lolland-Falster" and length = SHORT (< 50 km) -> Mølleådalen (28 km)
        exploreVm.setSelectedRegion("Sjælland & Lolland-Falster")
        exploreVm.setLengthFilter(LengthFilter.SHORT)
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(5_000) {
            val ids = exploreVm.filteredTrails.value.map { it.id }
            ids == listOf("trail_moelleaadalen")
        }

        // Reset filters and verify "Flere afmærkede ruter" section header exists in Udforsk list
        exploreVm.clearAllFilters()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("trail_list")
            .performScrollToNode(hasTestTag("section_more_marked_routes"))
        composeTestRule.onNodeWithTag("section_more_marked_routes").assertIsDisplayed()
    }
}
