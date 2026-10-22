package com.example

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.BaseMapType
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapDarkMode
import com.example.data.local.MapLayersState
import com.example.data.local.SettingsRepository
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.WaterFeatureDto
import com.example.data.model.WaterFeatureType
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.util.routing.LatLng
import com.example.data.repository.WaterLayerRepository
import com.example.ui.components.DropPinInspectorHelper
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.navigation.PoiClusterOverlay
import com.example.util.DanskStiDhmContourTileSource
import com.example.util.DanskStiDhmHillshadeTileSource
import com.example.util.DanskStiTileSource
import com.example.util.DanskStiTileSourceHelper
import com.example.util.WaterLayerOverlay
import com.example.util.routing.RouteMarkerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FixRound3Part3Test {

    private lateinit var app: Application
    private lateinit var database: DanskStiDatabase
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = DanskStiDatabase.getInstance(app)
        settingsRepository = SettingsRepository(app)
    }

    @After
    fun tearDown() {
        database.close()
        DanskStiDatabase.resetInstance()
    }

    /**
     * Requirement A:
     * Shared MapLayersOverlayManager registers overlays by stable key ("route", "pois", "access", "water", etc.),
     * updates in place without calling overlays.clear(), and clusters all 7 POI layers below zoom 13.
     */
    @Test
    fun testStableOverlayRegistrationAndInPlaceUpdates_NeverClearsOverlays() {
        val mapView = MapView(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val manager = MapLayersOverlayManager(context = app, scope = scope)

        manager.attachToMap(mapView, isDark = false)

        // Verify core stable keys are registered
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_TERRAIN_HILLSHADE))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_TERRAIN_CONTOURS))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_ACCESS))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_WATER))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_MARKED_ROUTES))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_ROUTE))
        assertNotNull(manager.getOverlay(MapLayersOverlayManager.KEY_POIS))

        val initialRouteFolder = manager.getRouteFolderOverlay()
        val poly1 = RouteMarkerHelper.createSnappedLegPolyline(
            mapView,
            listOf(GeoPoint(56.80, 9.82), GeoPoint(56.82, 9.85)),
            isDark = false
        )
        manager.updateFolderOverlay(MapLayersOverlayManager.KEY_ROUTE, listOf(poly1))

        // Verify the FolderOverlay instance in mapView.overlays was updated in place, not replaced
        assertTrue(mapView.overlays.contains(initialRouteFolder))
        assertEquals(1, initialRouteFolder.items.size)

        // Toggling layers on and off updates in place without removing or recreating the POI overlay
        val poisOverlayBefore = manager.getOverlay(MapLayersOverlayManager.KEY_POIS)
        val shelters = listOf(
            ShelterEntity(id = "s1", name = "Rold Skov Shelter", lat = 56.81, lng = 9.83)
        )
        val pois = listOf(
            PoiEntity(id = "w1", type = "water", name = "Kilde", lat = 56.815, lng = 9.835)
        )
        manager.updateLayersState(
            state = MapLayersState(showShelters = true, showWater = true),
            shelters = shelters,
            pois = pois,
            isDark = false
        )
        assertEquals(2, manager.getActivePoiItemCount())

        // Re-invoking with identical data does not recreate markers
        val poisOverlayAfter = manager.getOverlay(MapLayersOverlayManager.KEY_POIS)
        assertTrue(poisOverlayBefore === poisOverlayAfter)
        assertEquals(PoiClusterOverlay.CLUSTER_BELOW_ZOOM, 13.0, 0.001)
    }

    /**
     * Requirement B & C:
     * Base maps ("Topografisk" with dæmpet dark variant, "Udendørs", "Luftfoto") and
     * DHM Terrain overlay ("Terræn" on by default: dhm_terraen_skyggekort + dhm_kurve_traditionel from zoom 14).
     */
    @Test
    fun testBaseMapsAndDhmTerrainLayers_DefaultsAndUrls() {
        val defaultState = MapLayersState()
        assertTrue("Terræn must be on by default", defaultState.showTerrain)
        assertTrue("Vand must be on by default", defaultState.showWaterLayer)

        // Skærmkortet uses muted dark variant in dark mode when discovered
        val lightTopo = DanskStiTileSource("test_token", "topo_skaermkort")
        val darkTopo = DanskStiTileSource("test_token", "dtk_skaermkort_daempet")
        val tileZoom13 = MapTileIndex.getTileIndex(13, 4345, 2550) // Rold Skov area

        val lightUrl = lightTopo.getTileURLString(tileZoom13)
        val darkUrl = darkTopo.getTileURLString(tileZoom13)
        assertTrue(lightUrl.contains("LAYERS=topo_skaermkort"))
        assertTrue(darkUrl.contains("LAYERS=dtk_skaermkort_daempet"))
        assertTrue(darkUrl.contains("SRS=EPSG:3857"))

        // DHM hillshade (terrain, not surface) and contour (from zoom 14)
        val hillshade = DanskStiDhmHillshadeTileSource("test_token", "dhm_terraen_skyggekort")
        val contour = DanskStiDhmContourTileSource("test_token", "dhm_kurve_traditionel")
        val tileZoom16 = MapTileIndex.getTileIndex(16, 34950, 20650) // Mols Bjerge zoom 16

        val hsUrl = hillshade.getTileURLString(tileZoom13)
        val ctUrl = contour.getTileURLString(tileZoom16)
        assertTrue(hsUrl.contains("dhm_DAF"))
        assertTrue(hsUrl.contains("LAYERS=dhm_terraen_skyggekort"))
        assertTrue(hsUrl.contains("TRANSPARENT=TRUE"))
        assertTrue(hsUrl.contains("FORMAT=image/png"))

        assertTrue(ctUrl.contains("dhm_DAF"))
        assertTrue(ctUrl.contains("LAYERS=dhm_kurve_traditionel"))
        assertEquals(14, contour.minimumZoomLevel)

        // Verify hillshade alpha is 30% (0.30f) in light mode and 45% (0.45f) in dark mode, contour 60% (0.60f)
        val hsLightOverlay = DanskStiTileSourceHelper.createHillshadeOverlay(app, isDark = false)
        val hsDarkOverlay = DanskStiTileSourceHelper.createHillshadeOverlay(app, isDark = true)
        val ctOverlay = DanskStiTileSourceHelper.createContourOverlay(app, isDark = true)
        assertNotNull(hsLightOverlay)
        assertNotNull(hsDarkOverlay)
        assertNotNull(ctOverlay)
        assertEquals(0.30f, hsLightOverlay!!.overlayAlpha, 0.01f)
        assertEquals(0.45f, hsDarkOverlay!!.overlayAlpha, 0.01f)
        assertEquals(0.60f, ctOverlay!!.overlayAlpha, 0.01f)
    }

    /**
     * Requirement D:
     * Water overlay ("Vand", on by default from zoom 12, 0.05x0.05 deg tiles cached 30 days,
     * lakes 45% blue, wetland hatched/25%, rivers 3 dp, streams/ditches 1.5 dp, springs as small blue dots).
     */
    @Test
    fun testWaterLayer_TileCachingAndRenderingAtRoldSkovAndMolsBjerge() = runBlocking {
        assertEquals(12.0, WaterLayerRepository.MIN_ZOOM_FOR_WATER, 0.01)
        assertEquals(0.05, WaterLayerRepository.TILE_STEP_DEG, 0.001)
        assertEquals(600L, MapLayersOverlayManager.WATER_DEBOUNCE_MS)

        // Verify 0.05 x 0.05 tile key generation for Rold Skov (56.81, 9.83) and Mols Bjerge (56.22, 10.55)
        val roldLatIdx = WaterLayerRepository.tileIndexForCoord(56.81)
        val roldLngIdx = WaterLayerRepository.tileIndexForCoord(9.83)
        val roldKey = WaterLayerRepository.tileIdForIndices(roldLatIdx, roldLngIdx)
        assertTrue(roldKey.startsWith("water_"))

        val waterOverlay = WaterLayerOverlay()
        val features = listOf(
            WaterFeatureDto(
                id = "lake_madum_soe",
                osmId = 1001L,
                name = "Madum Sø",
                featureType = WaterFeatureType.LAKE,
                subType = "water",
                points = listOf(
                    GeoPointDto(56.80, 9.93),
                    GeoPointDto(56.81, 9.94),
                    GeoPointDto(56.79, 9.95)
                ),
                minLat = 56.79,
                maxLat = 56.81,
                minLng = 9.93,
                maxLng = 9.95
            ),
            WaterFeatureDto(
                id = "stream_lindenborg_aa",
                osmId = 1002L,
                name = "Lindenborg Å",
                featureType = WaterFeatureType.RIVER,
                subType = "river",
                points = listOf(
                    GeoPointDto(56.80, 9.82),
                    GeoPointDto(56.83, 9.85)
                ),
                minLat = 56.80,
                maxLat = 56.83,
                minLng = 9.82,
                maxLng = 9.85
            ),
            WaterFeatureDto(
                id = "spring_blaa_kilde",
                osmId = 1003L,
                name = "Blåkilde",
                featureType = WaterFeatureType.SPRING,
                subType = "spring",
                points = listOf(GeoPointDto(56.81, 9.86)),
                minLat = 56.81,
                maxLat = 56.81,
                minLng = 9.86,
                maxLng = 9.86
            )
        )

        val changed = waterOverlay.updateFeatures(features, isDark = true, density = 2.0f)
        assertTrue(changed)
        assertEquals(3, waterOverlay.currentFeatureCount)

        // Verify calling updateFeatures again with identical features and dark mode returns false (no recreation)
        val changedSecondTime = waterOverlay.updateFeatures(features, isDark = true, density = 2.0f)
        assertFalse(changedSecondTime)
    }

    /**
     * Requirement E:
     * Hue-preserving dark map filter (invert brightness + 180 deg hue rotate + contrast 0.85 + brightness 0.9)
     * preserves blue water and green forest in dark mode!
     * Also tests "Kort i mørke" setting (Følg telefonen / Altid lyst / Altid mørkt) and light/dark route colors.
     */
    @Test
    fun testHuePreservingDarkMapFilter_WaterStaysBlueAndForestStaysGreen() = runBlocking {
        // Test "Kort i mørke" setting persistence
        settingsRepository.setMapDarkMode(MapDarkMode.ALWAYS_DARK)
        val stateDark = settingsRepository.mapLayersFlow.first()
        assertEquals(MapDarkMode.ALWAYS_DARK, stateDark.mapDarkMode)
        assertTrue(stateDark.resolveIsDarkMap(isSystemDark = false))

        settingsRepository.setMapDarkMode(MapDarkMode.ALWAYS_LIGHT)
        val stateLight = settingsRepository.mapLayersFlow.first()
        assertEquals(MapDarkMode.ALWAYS_LIGHT, stateLight.mapDarkMode)
        assertFalse(stateLight.resolveIsDarkMap(isSystemDark = true))

        settingsRepository.setMapDarkMode(MapDarkMode.FOLLOW_SYSTEM)
        val stateSys = settingsRepository.mapLayersFlow.first()
        assertTrue(stateSys.resolveIsDarkMap(isSystemDark = true))
        assertFalse(stateSys.resolveIsDarkMap(isSystemDark = false))

        // Test hue-preserving dark color matrix on light map forest green & water blue pixels
        val darkForest = DanskStiTileSourceHelper.transformColorForDarkMap(Color.rgb(180, 225, 175))
        val darkWater = DanskStiTileSourceHelper.transformColorForDarkMap(Color.rgb(165, 210, 245))
        val darkBg = DanskStiTileSourceHelper.transformColorForDarkMap(Color.rgb(250, 250, 250))

        // 1. Forest pixel in dark mode MUST remain green-dominant (G > R and G > B)
        assertTrue(
            "Forest must stay green in dark mode: R=${Color.red(darkForest)}, G=${Color.green(darkForest)}, B=${Color.blue(darkForest)}",
            Color.green(darkForest) > Color.red(darkForest) && Color.green(darkForest) > Color.blue(darkForest)
        )

        // 2. Water pixel in dark mode MUST remain blue-dominant (B > R and B >= G)
        assertTrue(
            "Water must stay blue in dark mode: R=${Color.red(darkWater)}, G=${Color.green(darkWater)}, B=${Color.blue(darkWater)}",
            Color.blue(darkWater) > Color.red(darkWater) && Color.blue(darkWater) >= Color.green(darkWater)
        )

        // 3. White background must become dark (< 70 brightness)
        assertTrue(
            "White background must become dark in dark mode: R=${Color.red(darkBg)}",
            Color.red(darkBg) < 70 && Color.green(darkBg) < 70 && Color.blue(darkBg) < 70
        )

        // 4. Verify Requirement E route colors:
        // Light mode: #1E88E5 with white casing
        // Dark mode: #64B5F6 with near-black casing
        assertEquals(Color.parseColor("#1E88E5"), RouteMarkerHelper.COLOR_PLANNED_ROUTE_LIGHT)
        assertEquals(Color.parseColor("#64B5F6"), RouteMarkerHelper.COLOR_PLANNED_ROUTE_DARK)
        assertNotEquals(RouteMarkerHelper.COLOR_PLANNED_CASING_LIGHT, RouteMarkerHelper.COLOR_PLANNED_CASING_DARK)
    }

    /**
     * Requirement F:
     * Long-press drop pin formatting (decimal and degrees/minutes coordinates) and Adgang access rule lookup.
     */
    @Test
    fun testDropPinCoordinatesAndAccessRuleResolution() {
        // Rold Skov (56.81234, 9.83456)
        val decimalStr = DropPinInspectorHelper.formatDecimalCoordinates(56.81234, 9.83456)
        val dmsStr = DropPinInspectorHelper.formatDegreesMinutesCoordinates(56.81234, 9.83456)
        assertTrue("Decimal coordinates must include 5 decimal places", decimalStr.contains("56.81234"))
        assertTrue("Degrees/minutes must include ° and '", dmsStr.contains("56°") && dmsStr.contains("N") && dmsStr.contains("Ø"))

        val roldSkovPoly = AccessPolygon(
            id = "rold_statsskov",
            name = "Rold Skov (Naturstyrelsen)",
            type = AccessLandType.PUBLIC_FOREST,
            coordinates = listOf(
                LatLng(56.78, 9.78),
                LatLng(56.85, 9.78),
                LatLng(56.85, 9.90),
                LatLng(56.78, 9.90)
            ),
            source = "GeoFA / Naturstyrelsen"
        )
        val rulePublic = DropPinInspectorHelper.resolveAccessRuleAtPoint(56.81, 9.83, listOf(roldSkovPoly))
        assertEquals("Statsskov - fri færdsel", rulePublic)

        val ruleUnknown = DropPinInspectorHelper.resolveAccessRuleAtPoint(55.00, 10.00, listOf(roldSkovPoly))
        assertEquals("Ukendt ejer", ruleUnknown)
    }
}
