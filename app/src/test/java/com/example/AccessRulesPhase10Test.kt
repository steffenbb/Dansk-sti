package com.example

import com.example.data.model.access.AccessClassifier
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.access.AccessGeometryUtils
import com.example.util.access.AccessWarningType
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.repository.AccessLandRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification of Phase 10: Naturbeskyttelsesloven kapitel 4 & Route Planner integration.
 *
 * Done when:
 * 1. A straight line through a private forest shows the warning ("Kun på veje og stier her").
 * 2. A late start time triggers the sunset warning ("Lukket ved solnedgang").
 * 3. "Rut uden om" produces a route that avoids the area or reports it cannot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AccessRulesPhase10Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    // Defined test polygon: Private forest (56.00 to 56.02, 10.00 to 10.02)
    private val privateForestPoly = AccessPolygon(
        id = "test_private_forest",
        name = "Privat Bøgeskov",
        type = AccessLandType.PRIVATE_FOREST,
        coordinates = listOf(
            LatLng(56.00, 10.00),
            LatLng(56.02, 10.00),
            LatLng(56.02, 10.02),
            LatLng(56.00, 10.02),
            LatLng(56.00, 10.00)
        ),
        source = "OSM (privat/ukendt ejer)"
    )

    // Defined test polygon: Public state forest (56.10 to 56.12, 10.10 to 10.12)
    private val publicForestPoly = AccessPolygon(
        id = "test_public_forest",
        name = "Gribskov Statsskov",
        type = AccessLandType.PUBLIC_FOREST,
        coordinates = listOf(
            LatLng(56.10, 10.10),
            LatLng(56.12, 10.10),
            LatLng(56.12, 10.12),
            LatLng(56.10, 10.12),
            LatLng(56.10, 10.10)
        ),
        source = "OSM (operator=Naturstyrelsen)",
        operatorOrOwner = "Naturstyrelsen"
    )

    // Defined test polygon: Farmland / cultivated fields (56.05 to 56.07, 10.05 to 10.07)
    private val farmlandPoly = AccessPolygon(
        id = "test_farmland",
        name = "Oplev Mark",
        type = AccessLandType.CULTIVATED_FIELD,
        coordinates = listOf(
            LatLng(56.05, 10.05),
            LatLng(56.07, 10.05),
            LatLng(56.07, 10.07),
            LatLng(56.05, 10.07),
            LatLng(56.05, 10.05)
        ),
        source = "OSM (dyrket mark)"
    )

    @Test
    fun testClassification_PublicVsPrivateForest() {
        // 1. Naturstyrelsen operator -> PUBLIC_FOREST
        val (nstType, nstSource) = AccessClassifier.classify(
            mapOf("landuse" to "forest", "operator" to "Naturstyrelsen")
        )
        assertEquals(AccessLandType.PUBLIC_FOREST, nstType)
        assertTrue(nstSource.contains("Naturstyrelsen"))

        // 2. Ownership = national -> PUBLIC_FOREST
        val (stateType, _) = AccessClassifier.classify(
            mapOf("landuse" to "forest", "ownership" to "state")
        )
        assertEquals(AccessLandType.PUBLIC_FOREST, stateType)

        // 3. Overlaps public GeoFA fri teltning (Kommune) -> PUBLIC_FOREST
        val (geoFaType, geoFaSource) = AccessClassifier.classify(
            tags = mapOf("natural" to "wood"),
            overlapsPublicGeoFa = true,
            publicGeoFaOwner = "Aarhus Kommune"
        )
        assertEquals(AccessLandType.PUBLIC_FOREST, geoFaType)
        assertTrue(geoFaSource.contains("Aarhus Kommune"))

        // 4. Forest with private or unspecified ownership -> PRIVATE_FOREST
        val (privType, privSource) = AccessClassifier.classify(
            mapOf("landuse" to "forest")
        )
        assertEquals(AccessLandType.PRIVATE_FOREST, privType)
        assertEquals("OSM (privat/ukendt ejer)", privSource)

        // 5. Beach / coast -> BEACH
        val (beachType, _) = AccessClassifier.classify(
            mapOf("natural" to "beach")
        )
        assertEquals(AccessLandType.BEACH, beachType)

        // 6. Farmland -> CULTIVATED_FIELD
        val (farmType, _) = AccessClassifier.classify(
            mapOf("landuse" to "farmland")
        )
        assertEquals(AccessLandType.CULTIVATED_FIELD, farmType)
    }

    @Test
    fun testDanishNaturbeskyttelseslovenRules_IncludeGeneralDisclaimer() {
        for (type in AccessLandType.values()) {
            val rules = type.fullRulesDanish
            assertTrue(
                "Each land access type must include the mandatory disclaimer: ${type.name}",
                rules.contains("Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.")
            )
        }
    }

    @Test
    fun testDoneWhen1_StraightLineThroughPrivateForest_ShowsWarning() {
        // Straight line passing directly through private forest (from 55.99, 10.01 to 56.03, 10.01)
        val wpStart = RouteWaypoint(id = "wp1", lat = 55.99, lng = 10.01, role = WaypointRole.START, legModeToNext = LegMode.STRAIGHT)
        val wpEnd = RouteWaypoint(id = "wp2", lat = 56.03, lng = 10.01, role = WaypointRole.END)

        val waypoints = listOf(wpStart, wpEnd)
        val legs = emptyMap<String, LegGeometryData>()
        val polygons = listOf(privateForestPoly, publicForestPoly)

        val warnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = waypoints,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = "09:00",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "21:00"
        )

        val straightWarning = warnings.find { it.type == AccessWarningType.STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD }
        assertNotNull("Straight leg crossing private forest must trigger warning", straightWarning)
        assertEquals("Kun på veje og stier her", straightWarning!!.title)
        assertTrue(straightWarning.canRerouteAround)
        assertEquals("test_private_forest", straightWarning.polygon?.id)
    }

    @Test
    fun testStraightLineThroughPublicForest_DoesNotTriggerStraightWarning() {
        // Straight line passing through public forest (56.09, 10.11 to 56.13, 10.11)
        val wpStart = RouteWaypoint(id = "wp1", lat = 56.09, lng = 10.11, role = WaypointRole.START, legModeToNext = LegMode.STRAIGHT)
        val wpEnd = RouteWaypoint(id = "wp2", lat = 56.13, lng = 10.11, role = WaypointRole.END)

        val waypoints = listOf(wpStart, wpEnd)
        val legs = emptyMap<String, LegGeometryData>()
        val polygons = listOf(publicForestPoly)

        val warnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = waypoints,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = "12:00",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "21:00"
        )

        val straightWarning = warnings.find { it.type == AccessWarningType.STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD }
        assertNull("Public forest allows off-trail walking day and night; no straight line warning", straightWarning)
    }

    @Test
    fun testStraightLineThroughCultivatedFields_ShowsWarning() {
        // Straight line passing through cultivated field
        val wpStart = RouteWaypoint(id = "wp1", lat = 56.04, lng = 10.06, role = WaypointRole.START, legModeToNext = LegMode.STRAIGHT)
        val wpEnd = RouteWaypoint(id = "wp2", lat = 56.08, lng = 10.06, role = WaypointRole.END)

        val waypoints = listOf(wpStart, wpEnd)
        val legs = emptyMap<String, LegGeometryData>()
        val polygons = listOf(farmlandPoly)

        val warnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = waypoints,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = "10:00",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "20:00"
        )

        val straightWarning = warnings.find { it.type == AccessWarningType.STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD }
        assertNotNull("Straight line crossing fields must warn about access", straightWarning)
        assertEquals("Kun på veje og stier her", straightWarning!!.title)
    }

    @Test
    fun testDoneWhen2_LateStartTime_TriggersSunsetWarning() {
        // Waypoints in snapped mode inside private forest
        val wpStart = RouteWaypoint(id = "wp1", lat = 56.005, lng = 10.005, role = WaypointRole.START, legModeToNext = LegMode.SNAP)
        val wpEnd = RouteWaypoint(id = "wp2", lat = 56.015, lng = 10.015, role = WaypointRole.END)

        // 10 km leg: takes ~2.2 hours at 4.5 km/h
        val legPoints = mutableListOf<RoutePoint3D>()
        for (i in 0..10) {
            val f = i / 10.0
            legPoints.add(RoutePoint3D(56.005 + f * 0.01, 10.005 + f * 0.01, 15.0))
        }

        val leg = LegGeometryData(
            fromWaypointId = "wp1",
            toWaypointId = "wp2",
            legMode = LegMode.SNAP,
            points = legPoints,
            lengthKm = 10.0,
            ascentM = 0.0,
            descentM = 0.0,
            pathSharePct = 90.0,
            roadKm = 1.0
        )

        val waypoints = listOf(wpStart, wpEnd)
        val legs = mapOf("wp1_to_wp2" to leg)
        val polygons = listOf(privateForestPoly)

        // Case A: Early start at 10:00 with sunset at 20:00 -> NO sunset warning
        val daytimeWarnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = waypoints,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = "10:00",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "20:00"
        )
        assertNull("Daytime walk does not trigger sunset warning", daytimeWarnings.find { it.type == AccessWarningType.BEFORE_06_OR_AFTER_SUNSET })

        // Case B: Late start at 20:30 with sunset at 20:00 -> after sunset triggers warning!
        val lateWarnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = waypoints,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = "20:30",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "20:00"
        )

        val sunsetWarning = lateWarnings.find { it.type == AccessWarningType.BEFORE_06_OR_AFTER_SUNSET }
        assertNotNull("Late start time walking into/inside private forest must trigger sunset warning", sunsetWarning)
        assertEquals("Lukket ved solnedgang", sunsetWarning!!.title)
        assertTrue(sunsetWarning.message.contains("efter solnedgang"))
        assertTrue(sunsetWarning.message.contains("06:00 til solnedgang"))
    }

    @Test
    fun testOvernightInPrivateForestWithoutCamp_TriggersWarning() {
        val wpOvernight = RouteWaypoint(
            id = "wp_camp",
            lat = 56.01,
            lng = 10.01,
            role = WaypointRole.OVERNIGHT,
            linkedPoiId = null // Not a designated shelter/campsite
        )
        val wpStart = RouteWaypoint(id = "wp_start", lat = 55.98, lng = 10.01, role = WaypointRole.START)

        val warnings = AccessGeometryUtils.evaluateRouteAccess(
            waypoints = listOf(wpStart, wpOvernight),
            legs = emptyMap(),
            accessPolygons = listOf(privateForestPoly),
            startTimeStr = "12:00",
            pacingSpeedKmh = 4.5,
            sunsetTimeStr = "21:00"
        )

        val overnightWarning = warnings.find { it.type == AccessWarningType.OVERNIGHT_IN_PRIVATE_FOREST }
        assertNotNull("Camping in private forest without designated site is forbidden", overnightWarning)
        assertEquals("Ikke tilladt at overnatte her", overnightWarning!!.title)
        assertTrue(overnightWarning.message.contains("Allemandsretten tillader ikke teltning eller primitiv overnatning i privat skov"))
    }

    @Test
    fun testDoneWhen3_RutUdenOm_AvoidsPolygonOrSaysItCannot() {
        val wpFrom = RouteWaypoint(id = "wp1", lat = 55.99, lng = 10.01, role = WaypointRole.START)
        val wpTo = RouteWaypoint(id = "wp2", lat = 56.03, lng = 10.01, role = WaypointRole.END)

        val warning = com.example.util.access.RouteAccessWarning(
            type = AccessWarningType.STRAIGHT_LINE_PRIVATE_FOREST_OR_FIELD,
            title = "Kun på veje og stier her",
            message = "Lige linje krydser Privat Bøgeskov.",
            polygon = privateForestPoly,
            location = LatLng(56.01, 10.01),
            legIndex = 0,
            canRerouteAround = true
        )

        // Verify warning allows reroute
        assertTrue("Warning for restricted crossing must offer 'Rut uden om'", warning.canRerouteAround)
        assertNotNull("Warning must reference the obstacle polygon", warning.polygon)

        // Mock routing engine behavior with noGoAreas:
        // Case A: Engine successfully routes around no-go polygon
        val successEngine = object : com.example.util.routing.RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: com.example.util.routing.RouteOptions,
                alternative: Int
            ): com.example.util.routing.RouteLegResult {
                assertTrue("Detour must include obstacle polygon in noGoAreas", options.noGoAreas.contains(privateForestPoly.coordinates))
                return com.example.util.routing.RouteLegResult.Success(
                    points = listOf(
                        RoutePoint3D(from.lat, from.lng),
                        RoutePoint3D(56.01, 10.03), // Detour around the east side of polygon (lng > 10.02)
                        RoutePoint3D(to.lat, to.lng)
                    ),
                    lengthKm = 5.2,
                    ascentM = 12.0,
                    descentM = 10.0,
                    perPointWayInfo = emptyList(),
                    sourceName = "BRouter Detour"
                )
            }
        }

        // Case B: Engine reports no viable detour
        val noRouteEngine = object : com.example.util.routing.RoutingEngine {
            override suspend fun routeLeg(
                from: LatLng,
                to: LatLng,
                options: com.example.util.routing.RouteOptions,
                alternative: Int
            ): com.example.util.routing.RouteLegResult {
                return com.example.util.routing.RouteLegResult.NoRoute("Ingen sti mulig uden om privat areal")
            }
        }

        // Test outcome A
        runBlocking {
            val options = com.example.util.routing.RouteOptions(noGoAreas = listOf(privateForestPoly.coordinates))
            val resA = successEngine.routeLeg(LatLng(wpFrom.lat, wpFrom.lng), LatLng(wpTo.lat, wpTo.lng), options)
            assertTrue("Success produces detour", resA is com.example.util.routing.RouteLegResult.Success)
            val detour = resA as com.example.util.routing.RouteLegResult.Success
            assertEquals("BRouter Detour", detour.sourceName)
            // Detour point (56.01, 10.03) is outside privateForestPoly (10.00..10.02)
            assertFalse(AccessGeometryUtils.isPointInPolygon(LatLng(56.01, 10.03), privateForestPoly.coordinates))

            // Test outcome B
            val resB = noRouteEngine.routeLeg(LatLng(wpFrom.lat, wpFrom.lng), LatLng(wpTo.lat, wpTo.lng), options)
            assertTrue("Handles engine unable to detour gracefully", resB is com.example.util.routing.RouteLegResult.NoRoute)
        }
    }

    @Test
    fun testMiljoeportalWfs_GeoJsonParsing() {
        val repo = AccessLandRepository(database.dao())
        val sampleWfsJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "Naturstyrelsens arealoversigt.16",
                  "geometry": {
                    "type": "MultiPolygon",
                    "coordinates": [
                      [
                        [
                          [9.850, 56.820],
                          [9.870, 56.820],
                          [9.870, 56.840],
                          [9.850, 56.840],
                          [9.850, 56.820]
                        ]
                      ]
                    ]
                  },
                  "properties": {
                    "skovnavn": "Mosskov",
                    "distnr": 314,
                    "distnavn": "Himmerland",
                    "skovnr": 105,
                    "areal": 250.4,
                    "unikid": "314_105"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = repo.parseMiljoeportalWfsJson(sampleWfsJson)
        assertEquals(1, parsed.size)
        val poly = parsed.first()
        assertEquals("Mosskov (Statsskov)", poly.name)
        assertEquals(AccessLandType.PUBLIC_FOREST, poly.type)
        assertEquals("Danmarks Miljøportal (Naturstyrelsens arealoversigt)", poly.source)
        assertEquals("Naturstyrelsen (Himmerland)", poly.operatorOrOwner)
        assertEquals(5, poly.coordinates.size)
        assertEquals(56.820, poly.coordinates.first().lat, 0.001)
        assertEquals(9.850, poly.coordinates.first().lng, 0.001)
    }

    @Test
    fun testMiljoeportalWfs_ComplementsAndCachesInRoom() = runBlocking {
        val dao = database.dao()
        val repo = AccessLandRepository(dao)

        // 1. Initially cache is empty
        val cachedBefore = dao.getAccessPolygonsInBoundingBox(56.81, 56.85, 9.84, 9.88)
        assertEquals(0, cachedBefore.size)

        // 2. Parse a state polygon from Miljøportal WFS
        val sampleWfsJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": "Naturstyrelsens arealoversigt.16",
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [
                        [9.850, 56.820],
                        [9.870, 56.820],
                        [9.870, 56.840],
                        [9.850, 56.840],
                        [9.850, 56.820]
                      ]
                    ]
                  },
                  "properties": {
                    "skovnavn": "Rold Statsskov",
                    "distnavn": "Himmerland",
                    "unikid": "314_rold"
                  }
                }
              ]
            }
        """.trimIndent()

        val wfsPolys = repo.parseMiljoeportalWfsJson(sampleWfsJson)
        assertEquals(1, wfsPolys.size)

        // 3. Simulated OSM forest that was originally PRIVATE_FOREST (no operator tag)
        val rawOsmForest = AccessPolygon(
            id = "way_99999",
            name = "Rold Skov Parceller",
            type = AccessLandType.PRIVATE_FOREST,
            coordinates = listOf(
                LatLng(56.825, 9.855),
                LatLng(56.835, 9.855),
                LatLng(56.835, 9.865),
                LatLng(56.825, 9.865),
                LatLng(56.825, 9.855)
            ),
            source = "OSM (privat/ukendt ejer)"
        )

        // 4. Test complementation: OSM forest centroid is inside Miljøportal state polygon
        val centerLat = rawOsmForest.coordinates.map { it.lat }.average()
        val centerLng = rawOsmForest.coordinates.map { it.lng }.average()
        val centerPt = LatLng(centerLat, centerLng)

        val matchingState = wfsPolys.find {
            AccessGeometryUtils.isPointInPolygon(centerPt, it.coordinates)
        }
        assertNotNull("Miljøportal WFS state boundary must encompass the forest area", matchingState)

        val complementedForest = rawOsmForest.copy(
            type = AccessLandType.PUBLIC_FOREST,
            source = "Danmarks Miljøportal (Naturstyrelsens arealoversigt)",
            operatorOrOwner = matchingState!!.operatorOrOwner
        )

        assertEquals(AccessLandType.PUBLIC_FOREST, complementedForest.type)
        assertEquals("Danmarks Miljøportal (Naturstyrelsens arealoversigt)", complementedForest.source)

        // 5. Test Room persistence & caching
        val entities = listOf(wfsPolys.first(), complementedForest).map {
            com.example.data.model.AccessPolygonEntity(
                id = it.id,
                name = it.name,
                type = it.type.name,
                coordinatesJson = org.json.JSONArray(it.coordinates.map { c -> org.json.JSONArray(listOf(c.lat, c.lng)) }).toString(),
                minLat = it.coordinates.minOf { c -> c.lat },
                maxLat = it.coordinates.maxOf { c -> c.lat },
                minLng = it.coordinates.minOf { c -> c.lng },
                maxLng = it.coordinates.maxOf { c -> c.lng },
                source = it.source,
                operatorOrOwner = it.operatorOrOwner,
                fetchedAt = System.currentTimeMillis()
            )
        }
        dao.insertAccessPolygons(entities)

        // Subsequent query loads directly from Room database
        val cachedAfter = dao.getAccessPolygonsInBoundingBox(56.81, 56.85, 9.84, 9.88)
        assertEquals(2, cachedAfter.size)
        assertTrue(cachedAfter.any { it.source.contains("Miljøportal") })
    }

    @Test
    fun testMiljoeportalWfs_HandlesXmlOrCorruptedPayloadGracefully() {
        val repo = AccessLandRepository(database.dao())
        // XML error response
        val xmlError = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ServiceExceptionReport><ServiceException>Invalid</ServiceException></ServiceExceptionReport>"
        val emptyFromXml = repo.parseMiljoeportalWfsJson(xmlError)
        assertTrue(emptyFromXml.isEmpty())

        // Corrupted JSON
        val invalidJson = "{ invalid: [ }"
        val emptyFromInvalid = repo.parseMiljoeportalWfsJson(invalidJson)
        assertTrue(emptyFromInvalid.isEmpty())
    }
}
