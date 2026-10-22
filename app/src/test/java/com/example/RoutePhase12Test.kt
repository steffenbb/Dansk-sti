package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.model.NearestPoiCategory
import com.example.data.model.PoiEntity
import com.example.data.model.RoadSectionWarning
import com.example.data.model.RouteQualityBreakdown
import com.example.data.model.ShelterEntity
import com.example.data.model.SupplyGapType
import com.example.data.model.TrailEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.remote.CurrentWeatherDto
import com.example.data.remote.DailyWeatherDto
import com.example.data.remote.OpenMeteoResponse
import com.example.data.remote.WeatherApiService
import com.example.data.repository.DanskStiRepository
import com.example.util.GpxExporter
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteSmartTools
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.WayInfo
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoutePhase12Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase
    private lateinit var repository: DanskStiRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
        repository = DanskStiRepository(database.dao(), app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    // ---------------------------------------------------------
    // 1. Vand og mad
    // ---------------------------------------------------------
    @Test
    fun testFeature1_VandOgMad_SupplyGapDetectionAndDetour() {
        // Create a 40 km straight polyline from lat 56.00 to ~56.36
        val routePoints = (0..40).map { i ->
            val fraction = i / 40.0
            RoutePoint3D(lat = 56.00 + fraction * 0.36, lng = 10.00, ele = 20.0)
        }

        // Water POI at km 5 and at km 32 (gap of 27 km between km 5 and km 32 > 12 km default)
        val waterPoi1 = PoiEntity(
            id = "w1",
            type = "water",
            name = "Vandpost Kirkeby",
            lat = 56.00 + (5.0 / 40.0) * 0.36,
            lng = 10.00,
            source = "geofa"
        )
        val waterPoi2 = PoiEntity(
            id = "w2",
            type = "water",
            name = "Vandpost Skovsø",
            lat = 56.00 + (32.0 / 40.0) * 0.36,
            lng = 10.00,
            source = "geofa"
        )

        // Detour candidate near km 18 (200m off the route)
        val detourWater = PoiEntity(
            id = "w_detour",
            type = "water",
            name = "Detour Shelter Vand",
            lat = 56.00 + (18.0 / 40.0) * 0.36,
            lng = 10.003, // ~200m east
            source = "geofa"
        )

        val corridorPois = listOf(waterPoi1, waterPoi2, detourWater)

        val warnings = RouteSmartTools.analyzeSupplyGaps(
            routePoints = routePoints,
            corridorPois = corridorPois,
            maxWaterGapKm = 12.0,
            maxShopGapKm = 25.0
        )

        // There should be a water gap between km 5 and km 32
        val waterWarnings = warnings.filter { it.type == SupplyGapType.WATER }
        assertTrue("Expected at least one water gap warning", waterWarnings.isNotEmpty())

        val gap = waterWarnings.first { it.startKm in 4.0..6.0 && it.endKm in 31.0..33.0 }
        assertTrue("Gap km should be ~27 km", gap.gapKm in 26.0..28.0)
        assertTrue(gap.displayTitle.contains("Intet vand i 27 km"))
        assertNotNull("Nearest detour should be found", gap.nearestDetourName)
        assertEquals("Detour Shelter Vand", gap.nearestDetourName)
        assertTrue(gap.detourExtraMeters != null && gap.detourExtraMeters!! > 0)
    }

    // ---------------------------------------------------------
    // 2. Rundtur
    // ---------------------------------------------------------
    @Test
    fun testFeature2_Rundtur_CandidateGenerationAndDeviationFiltering() = runBlocking {
        val start = LatLng(56.15, 10.20)
        val targetKm = 15.0

        // Mock routing engine that returns realistic leg results
        val mockEngine = object : RoutingEngine {
            override suspend fun routeLeg(from: LatLng, to: LatLng, options: RouteOptions, alternative: Int): RouteLegResult {
                val dLat = to.lat - from.lat
                val dLng = to.lng - from.lng
                val straightDistKm = kotlin.math.sqrt(dLat * dLat + dLng * dLng) * 111.0
                val routedKm = straightDistKm * 1.25 // simulate trail curvature

                val pts = listOf(
                    RoutePoint3D(from.lat, from.lng, 20.0),
                    RoutePoint3D((from.lat + to.lat) / 2.0, (from.lng + to.lng) / 2.0, 25.0),
                    RoutePoint3D(to.lat, to.lng, 22.0)
                )

                val wayInfos = listOf(
                    WayInfo(highway = "path", surface = "ground"),
                    WayInfo(highway = "track", surface = "gravel"),
                    WayInfo(highway = "path", surface = "ground")
                )

                return RouteLegResult.Success(
                    points = pts,
                    lengthKm = routedKm,
                    ascentM = 35.0,
                    descentM = 33.0,
                    perPointWayInfo = wayInfos,
                    sourceName = "BRouter Test"
                )
            }
        }

        val candidates = RouteSmartTools.generateRundturCandidates(
            start = start,
            targetKm = targetKm,
            routingEngine = mockEngine,
            shelters = emptyList(),
            friTeltning = emptyList()
        )

        // Loop candidates that deviate by <= 20% from target should be kept
        assertTrue("Should produce candidates within 20% of target", candidates.isNotEmpty())
        for (cand in candidates) {
            val devPct = cand.deviationPct
            assertTrue("Candidate ${cand.title} deviation $devPct% must be <= 20%", devPct <= 20.0)
            assertTrue("Path share should be calculated", cand.pathSharePct >= 0.0)
            assertEquals("Start and End waypoint coordinates must match", cand.waypoints.first().lat, cand.waypoints.last().lat, 0.0001)
            assertEquals(cand.waypoints.first().lng, cand.waypoints.last().lng, 0.0001)
        }
    }

    // ---------------------------------------------------------
    // 3. Vejr og dagslys
    // ---------------------------------------------------------
    @Test
    fun testFeature3_VejrOgDagslys_EtaForecastAndSunsetWarning() = runBlocking {
        val wpStart = RouteWaypoint(id = "wp_0", lat = 56.10, lng = 10.10, role = WaypointRole.START, name = "Start")
        val wpOvernight = RouteWaypoint(id = "wp_1", lat = 56.18, lng = 10.18, role = WaypointRole.OVERNIGHT, name = "Shelter Rold")
        val wpEnd = RouteWaypoint(id = "wp_2", lat = 56.25, lng = 10.25, role = WaypointRole.END, name = "Mål")

        val leg1 = LegGeometryData(
            fromWaypointId = "wp_0",
            toWaypointId = "wp_1",
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(56.10, 10.10, 20.0), RoutePoint3D(56.18, 10.18, 40.0)),
            lengthKm = 15.0,
            ascentM = 50.0,
            descentM = 30.0,
            pathSharePct = 85.0,
            roadKm = 2.0
        )
        val leg2 = LegGeometryData(
            fromWaypointId = "wp_1",
            toWaypointId = "wp_2",
            legMode = LegMode.SNAP,
            points = listOf(RoutePoint3D(56.18, 10.18, 40.0), RoutePoint3D(56.25, 10.25, 30.0)),
            lengthKm = 12.0,
            ascentM = 40.0,
            descentM = 50.0,
            pathSharePct = 90.0,
            roadKm = 1.0
        )

        // Mock WeatherApiService returning a forecast with sunset at 17:00
        val mockWeatherService = object : WeatherApiService {
            override suspend fun getTrailWeather(
                lat: Double,
                lon: Double,
                current: String,
                daily: String,
                windSpeedUnit: String,
                timezone: String
            ): OpenMeteoResponse {
                return OpenMeteoResponse(
                    current = CurrentWeatherDto(
                        temperature = 14.5,
                        precipitation = 0.2,
                        windSpeed = 4.5,
                        weatherCode = 1
                    ),
                    daily = DailyWeatherDto(
                        time = listOf("2026-10-18"),
                        sunset = listOf("2026-10-18T17:00") // Sunset at 17:00
                    )
                )
            }

            override suspend fun getElevations(
                latitudes: String,
                longitudes: String
            ): com.example.data.remote.OpenMeteoElevationResponse {
                return com.example.data.remote.OpenMeteoElevationResponse(elevation = listOf(20.0, 30.0))
            }
        }

        // Start at 12:00, speed 3 km/h.
        // Leg 1: 15 km / 3 km/h = 5 hours -> arrival at 17:00.
        // Leg 2: 27 km cumulative / 3 km/h = 9 hours -> arrival at 21:00 (after sunset 17:00!).
        val etas = RouteSmartTools.calculateWaypointWeatherEtas(
            waypoints = listOf(wpStart, wpOvernight, wpEnd),
            orderedLegs = listOf(leg1, leg2),
            startTimeStr = "12:00",
            pacingSpeedKmh = 3.0,
            weatherService = mockWeatherService
        )

        assertEquals(2, etas.size) // OVERNIGHT and END
        val overnightEta = etas[0]
        assertEquals("wp_1", overnightEta.waypointId)
        assertEquals("17:00", overnightEta.etaFormatted)
        assertEquals("17:00", overnightEta.sunsetTime)
        assertEquals(14.5, overnightEta.tempC ?: 0.0, 0.5)

        val endEta = etas[1]
        assertEquals("wp_2", endEta.waypointId)
        assertEquals("21:00", endEta.etaFormatted)
        assertTrue("Arrival at 21:00 should be marked after sunset (17:00)", endEta.isAfterSunset)
        assertNotNull(endEta.warningMessage)
        assertTrue(endEta.warningMessage!!.contains("Ankomst efter solnedgang"))
    }

    // ---------------------------------------------------------
    // 4. Rute til nærmeste (routed distance, not straight line)
    // ---------------------------------------------------------
    @Test
    fun testFeature4_RuteTilNaermeste_UsesRoutedDistance() = runBlocking {
        val userLocation = LatLng(56.10, 10.10)

        // Candidate A: 500m straight line, but routed through river/detour -> 8.0 km
        val shelterA = ShelterEntity(id = "sh_A", name = "Shelter Flodside", lat = 56.104, lng = 10.10, source = "geofa")
        // Candidate B: 2 km straight line, direct open trail -> 2.5 km routed
        val shelterB = ShelterEntity(id = "sh_B", name = "Shelter Skovstien", lat = 56.118, lng = 10.10, source = "geofa")

        val mockRoutingEngine = object : RoutingEngine {
            override suspend fun routeLeg(from: LatLng, to: LatLng, options: RouteOptions, alternative: Int): RouteLegResult {
                val dist = if (to.lat < 56.11) 8.0 else 2.5
                return RouteLegResult.Success(
                    points = listOf(RoutePoint3D(from.lat, from.lng), RoutePoint3D(to.lat, to.lng)),
                    lengthKm = dist,
                    ascentM = 10.0,
                    descentM = 10.0,
                    perPointWayInfo = emptyList(),
                    sourceName = "BRouter Test"
                )
            }
        }

        val result = RouteSmartTools.routeToNearestPoi(
            start = userLocation,
            category = NearestPoiCategory.SHELTER,
            routingEngine = mockRoutingEngine,
            shelters = listOf(shelterA, shelterB),
            friTeltning = emptyList(),
            pois = emptyList()
        )

        assertNotNull(result)
        // Must select Shelter B because its routed distance (2.5 km) is shorter than A (8.0 km)
        assertEquals("sh_B", result!!.first.id)
        assertEquals("Shelter Skovstien", result.first.name)
        assertEquals(2.5, result.second.lengthKm, 0.1)
    }

    // ---------------------------------------------------------
    // 5. Rutekvalitet
    // ---------------------------------------------------------
    @Test
    fun testFeature5_Rutekvalitet_BreakdownAndSectionOver1kmWarning() {
        val pointsLeg1 = (0..20).map { i -> RoutePoint3D(56.00 + i * 0.001, 10.00) } // ~2.2 km
        val wayInfoTrail = (0..20).map { WayInfo(highway = "footway", surface = "ground") }

        val leg1 = LegGeometryData(
            fromWaypointId = "w1",
            toWaypointId = "w2",
            legMode = LegMode.SNAP,
            points = pointsLeg1,
            lengthKm = 2.2,
            ascentM = 20.0,
            descentM = 20.0,
            pathSharePct = 100.0,
            roadKm = 0.0,
            wayInfo = wayInfoTrail
        )

        // Leg 2: 1.5 km on road without sidewalk (unclassified road)
        val pointsLeg2 = (0..15).map { i -> RoutePoint3D(56.02 + i * 0.001, 10.00) } // ~1.6 km
        val wayInfoRoadNoSidewalk = (0..15).map {
            WayInfo(
                highway = "unclassified",
                surface = "asphalt",
                rawTags = mapOf("sidewalk" to "no", "name" to "Landevej 42")
            )
        }

        val leg2 = LegGeometryData(
            fromWaypointId = "w2",
            toWaypointId = "w3",
            legMode = LegMode.SNAP,
            points = pointsLeg2,
            lengthKm = 1.6,
            ascentM = 15.0,
            descentM = 15.0,
            pathSharePct = 0.0,
            roadKm = 1.6,
            wayInfo = wayInfoRoadNoSidewalk
        )

        val breakdown = RouteSmartTools.analyzeRouteQuality(listOf(leg1, leg2))

        assertTrue("Trail km should be ~2.2 km", breakdown.trailKm in 2.0..2.4)
        assertTrue("Road without sidewalk km should be ~1.6 km", breakdown.roadNoSidewalkKm in 1.4..1.8)
        assertTrue("Asphalt km should be ~1.6 km", breakdown.asphaltKm in 1.4..1.8)

        // Must flag the road section because length > 1 km without footway
        assertEquals(1, breakdown.roadWithoutFootwayWarnings.size)
        val warning = breakdown.roadWithoutFootwayWarnings[0]
        assertTrue(warning.lengthKm >= 1.0)
        assertTrue(warning.displayText.contains("Vej uden fortov"))
        assertTrue(warning.displayText.contains("Landevej 42"))
    }

    // ---------------------------------------------------------
    // 6. Mine punkter (Custom User Waypoint)
    // ---------------------------------------------------------
    @Test
    fun testFeature6_MinePunkter_PersistenceAndGpxExport() = runBlocking {
        val dao = database.dao()

        val customWp = UserCustomWaypointEntity(
            id = "wp_custom_1",
            routeId = 55L,
            lat = 56.123,
            lng = 10.456,
            name = "Mit Udsigtstårn",
            note = "Dejlig bænk med panoramaudsigt over søen",
            photoPath = "/data/user/0/com.example/files/wp_photo_test.jpg"
        )

        repository.saveCustomWaypoint(customWp)

        val savedList = repository.getCustomWaypointsForRoute(55L)
        assertEquals(1, savedList.size)
        assertEquals("Mit Udsigtstårn", savedList[0].name)
        assertEquals("Dejlig bænk med panoramaudsigt over søen", savedList[0].note)

        // Verify GPX Export includes the custom waypoint
        val trail = TrailEntity(
            id = "route_55",
            name = "Søhøjlandet Rute",
            description = "Smuk rute",
            region = "Midtjylland",
            parentRegion = "Danmark",
            computedWalkingKm = 15.0
        )

        val gpx = GpxExporter.generateMultiSegmentGpx(
            trail = trail,
            plan = null,
            stages = emptyList(),
            trailPoints = listOf(com.example.data.model.GeoPointDto(56.12, 10.45)),
            customWaypoints = savedList
        )

        assertTrue(gpx.contains("<wpt lat=\"56.123\" lon=\"10.456\">"))
        assertTrue(gpx.contains("<name>Mit Udsigtstårn</name>"))
        assertTrue(gpx.contains("<desc>Dejlig bænk med panoramaudsigt over søen</desc>"))
        assertTrue(gpx.contains("<sym>Custom</sym>"))
        assertTrue(gpx.contains("<type>UserWaypoint</type>"))
    }

    // ---------------------------------------------------------
    // 7. Del rute (Plain text summary and GPX)
    // ---------------------------------------------------------
    @Test
    fun testFeature7_DelRute_TextSummaryAndOfflineGpx() {
        val dummyTrail = TrailEntity(
            id = "route_test",
            name = "Himmerland Trail",
            description = "Flot rute i naturen",
            region = "Nordjylland",
            parentRegion = "Danmark",
            computedWalkingKm = 24.5
        )

        val customWaypoints = listOf(
            UserCustomWaypointEntity(
                id = "c1",
                lat = 56.7,
                lng = 9.8,
                name = "Vandkilde",
                note = "Rent kildevand"
            )
        )

        val gpxStr = GpxExporter.generateMultiSegmentGpx(
            trail = dummyTrail,
            plan = null,
            stages = emptyList(),
            trailPoints = listOf(
                com.example.data.model.GeoPointDto(56.70, 9.80),
                com.example.data.model.GeoPointDto(56.75, 9.85)
            ),
            customWaypoints = customWaypoints
        )

        // Generates valid GPX 1.1 with metadata, waypoint, track, track segment
        assertTrue(gpxStr.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpxStr.contains("<gpx version=\"1.1\""))
        assertTrue(gpxStr.contains("<name>Himmerland Trail - DanskSti Ruteplan</name>"))
        assertTrue(gpxStr.contains("<wpt lat=\"56.7\" lon=\"9.8\">"))
        assertTrue(gpxStr.contains("<trk>"))
        assertTrue(gpxStr.contains("</gpx>"))
    }
}
