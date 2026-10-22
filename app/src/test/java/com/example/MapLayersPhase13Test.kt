package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BaseMapType
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.local.SettingsRepository
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteDto
import com.example.data.repository.DanskStiRepository
import com.example.util.DanskStiFriluftskortTileSource
import com.example.util.DanskStiLuftfotoTileSource
import com.example.util.DanskStiTileSourceHelper
import kotlinx.coroutines.flow.first
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
class MapLayersPhase13Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase
    private lateinit var repository: DanskStiRepository
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
        repository = DanskStiRepository(database.dao(), app)
        settingsRepository = SettingsRepository(app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    @Test
    fun testBaseMapTypeEnum() {
        assertEquals(BaseMapType.SKAERMKORT, BaseMapType.fromString("SKAERMKORT"))
        assertEquals(BaseMapType.FRILUFTSKORT, BaseMapType.fromString("friluftskort"))
        assertEquals(BaseMapType.LUFTFOTO, BaseMapType.fromString("Luftfoto"))
        assertEquals(BaseMapType.SKAERMKORT, BaseMapType.fromString("UNKNOWN_VALUE"))

        assertEquals("Skærmkortet", BaseMapType.SKAERMKORT.displayName)
        assertEquals("Friluftskort", BaseMapType.FRILUFTSKORT.displayName)
        assertEquals("Luftfoto", BaseMapType.LUFTFOTO.displayName)

        assertEquals("Topografisk", BaseMapType.SKAERMKORT.sheetTitle)
        assertEquals("Udendørs", BaseMapType.FRILUFTSKORT.sheetTitle)
        assertEquals("Luftfoto", BaseMapType.LUFTFOTO.sheetTitle)
    }

    @Test
    fun testDataStoreMapLayersPersistence() = runBlocking {
        // Change base map to Friluftskort
        settingsRepository.setBaseMap(BaseMapType.FRILUFTSKORT)
        settingsRepository.setOverlayMarkedRoutes(true)
        settingsRepository.setOverlayShops(true)
        settingsRepository.setOverlayTransport(true)

        val state = settingsRepository.mapLayersFlow.first()
        assertEquals(BaseMapType.FRILUFTSKORT, state.baseMap)
        assertTrue(state.showMarkedRoutes)
        assertTrue(state.showShops)
        assertTrue(state.showTransport)

        // Switch to Luftfoto
        settingsRepository.setBaseMap(BaseMapType.LUFTFOTO)
        val state2 = settingsRepository.mapLayersFlow.first()
        assertEquals(BaseMapType.LUFTFOTO, state2.baseMap)
    }

    @Test
    fun testGeoDanmarkLuftfotoTileSourceUrlFormat() {
        val luftfotoSource = DanskStiLuftfotoTileSource("test_api_key_123")
        // MapTileIndex for zoom 12, x 2170, y 1290
        val tileIndex = org.osmdroid.util.MapTileIndex.getTileIndex(12, 2170, 1290)
        val url = luftfotoSource.getTileURLString(tileIndex)

        assertTrue("URL must point to Dataforsyningen ortofoto endpoint", url.startsWith("https://api.dataforsyningen.dk/orto_foraar_DAF"))
        assertTrue("Must use WMS 1.1.1 or 1.3.0", url.contains("SERVICE=WMS"))
        assertTrue("Must specify layer orto_foraar", url.contains("LAYERS=orto_foraar"))
        assertTrue("Must request Web Mercator EPSG:3857", url.contains("SRS=EPSG:3857"))
        assertTrue("Must include API token parameter", url.contains("token=test_api_key_123"))
        assertTrue("Must have BBOX parameter", url.contains("BBOX="))
    }

    @Test
    fun testFriluftskortTileSourceUrl() {
        val friluftskortSource = DanskStiFriluftskortTileSource("DEFAULT_KEY")
        val tileIndex = org.osmdroid.util.MapTileIndex.getTileIndex(14, 8700, 5200)
        val url = friluftskortSource.getTileURLString(tileIndex)

        assertTrue("URL must contain zoom and coords", url.contains("14/8700/5200.png"))
    }

    @Test
    fun testDynamicAttributionSummary() {
        val skaermkortState = MapLayersState(
            baseMap = BaseMapType.SKAERMKORT,
            showMarkedRoutes = true,
            showAccess = true,
            showShelters = true
        )
        val attr1 = DanskStiTileSourceHelper.getAttributionSummary(skaermkortState)
        assertTrue(attr1.contains("Skærmkortet"))
        assertTrue(attr1.contains("OSM Ruter"))
        assertTrue(attr1.contains("Miljøportalen"))
        assertTrue(attr1.contains("GeoFA/NST"))

        val luftfotoState = MapLayersState(
            baseMap = BaseMapType.LUFTFOTO,
            showTerrain = false,
            showWaterLayer = false,
            showMarkedRoutes = false,
            showAccess = false,
            showShelters = false,
            showFriTeltning = false,
            showWater = false,
            showToilets = false,
            showShops = false,
            showTransport = false,
            showMyPoints = false
        )
        val attr2 = DanskStiTileSourceHelper.getAttributionSummary(luftfotoState)
        assertEquals("GeoDanmark Ortofoto", attr2)
    }

    @Test
    fun testSaveMarkedRouteAsTrail() = runBlocking {
        val testRoute = MarkedRouteDto(
            osmRelationId = 99887766L,
            name = "Gribskov Stien Test",
            lengthKm = 14.5,
            operator = "Naturstyrelsen Nordsjælland",
            routeType = "hiking",
            network = "lwn",
            symbol = "Gul pæl",
            colorHex = "#F57C00",
            polylines = listOf(
                listOf(
                    GeoPointDto(55.98, 12.30),
                    GeoPointDto(55.99, 12.32),
                    GeoPointDto(56.00, 12.33)
                )
            ),
            minLat = 55.98,
            maxLat = 56.00,
            minLng = 12.30,
            maxLng = 12.33
        )

        val saveResult = repository.saveMarkedRouteAsTrail(testRoute)
        assertTrue("Saving marked route must succeed", saveResult.isSuccess)

        val savedTrail = saveResult.getOrNull()
        assertNotNull(savedTrail)
        assertEquals("trail_osm_99887766", savedTrail?.id)
        assertEquals("Gribskov Stien Test", savedTrail?.name)
        assertEquals(14.5, savedTrail?.computedWalkingKm ?: 0.0, 0.2)
        assertEquals(99887766L, savedTrail?.osmRelationId)

        // Verify stored in Room database
        val fromDb = database.dao().getTrailById("trail_osm_99887766")
        assertNotNull(fromDb)
        assertEquals("Gribskov Stien Test", fromDb?.name)

        // Verify geometry was saved via geometry pipeline
        val geom = repository.getTrailGeometry("trail_osm_99887766")
        assertNotNull(geom)
        assertTrue((geom?.totalWalkingKm ?: 0.0) > 0.0)
    }
}
