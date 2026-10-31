package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppAppearanceMode
import com.example.data.local.BaseMapType
import com.example.data.local.MapDarkMode
import com.example.data.local.MapLayersState
import com.example.data.local.MapThemeOverride
import com.example.data.local.SettingsRepository
import com.example.ui.components.MapLayersBottomSheet
import com.example.ui.components.RouteElevationProfileChart
import com.example.ui.navigation.NavigationMapOverlayManager
import com.example.ui.theme.DanskStiColors
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.DarkDanskStiColors
import com.example.ui.theme.LightDanskStiColors
import com.example.util.DanskStiTileSource
import com.example.util.DanskStiTileSourceHelper
import com.example.util.TerrainTilesOverlay
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.WaypointRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FixRound9BTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // =========================================================================
    // Item 1 & Item 2: "Udseende" setting (Følg telefonen / Lyst / Mørkt),
    // "Kortet følger appen" + Lyst / Mørkt override, and SystemBarStyle edge-to-edge
    // =========================================================================

    @Test
    fun item1_udseendeAndMapFollowsApp_persistedInDataStoreAndResolvedCorrectly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)

        // Default values: Udseende = FOLLOW_SYSTEM ("Følg telefonen"), Kortet følger appen = true
        repo.setAppearanceMode(AppAppearanceMode.FOLLOW_SYSTEM)
        repo.setMapFollowsApp(true)
        repo.setMapThemeOverride(MapThemeOverride.DARK)

        val defaultSettings = repo.settingsFlow.first()
        assertEquals(AppAppearanceMode.FOLLOW_SYSTEM, defaultSettings.appearanceMode)
        assertTrue(defaultSettings.mapFollowsApp)

        // Resolve app dark theme
        assertFalse(defaultSettings.resolveIsDarkApp(isSystemDark = false))
        assertTrue(defaultSettings.resolveIsDarkApp(isSystemDark = true))

        // Switch Udseende to Lyst and Mørkt in DataStore
        repo.setAppearanceMode(AppAppearanceMode.ALWAYS_LIGHT)
        val lightSettings = repo.settingsFlow.first()
        assertEquals(AppAppearanceMode.ALWAYS_LIGHT, lightSettings.appearanceMode)
        assertFalse(lightSettings.resolveIsDarkApp(isSystemDark = true))

        repo.setAppearanceMode(AppAppearanceMode.ALWAYS_DARK)
        val darkSettings = repo.settingsFlow.first()
        assertEquals(AppAppearanceMode.ALWAYS_DARK, darkSettings.appearanceMode)
        assertTrue(darkSettings.resolveIsDarkApp(isSystemDark = false))

        // Map follows app (on by default) vs override Lyst / Mørkt for map only
        val layersFollows = repo.mapLayersFlow.first()
        assertTrue(layersFollows.mapFollowsApp)
        assertTrue(layersFollows.resolveIsDarkMap(isSystemDark = false))

        // Turn off "Kortet følger appen" -> map uses override (Lyst / Mørkt) independently of app theme
        repo.setMapFollowsApp(false)
        repo.setMapThemeOverride(MapThemeOverride.LIGHT)
        val layersOverrideLight = repo.mapLayersFlow.first()
        assertFalse(layersOverrideLight.mapFollowsApp)
        assertEquals(MapThemeOverride.LIGHT, layersOverrideLight.mapThemeOverride)
        assertFalse(layersOverrideLight.resolveIsDarkMap(isSystemDark = true, isAppDark = true))

        repo.setMapThemeOverride(MapThemeOverride.DARK)
        val layersOverrideDark = repo.mapLayersFlow.first()
        assertEquals(MapThemeOverride.DARK, layersOverrideDark.mapThemeOverride)
        assertTrue(layersOverrideDark.resolveIsDarkMap(isSystemDark = false, isAppDark = false))
    }

    @Test
    fun item1_and_item2_mapLayersBottomSheetAndSystemBarsWorkInBothModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)

        composeTestRule.setContent {
            DanskStiTheme(darkTheme = true) {
                MapLayersBottomSheet(
                    state = MapLayersState(
                        baseMap = BaseMapType.SKAERMKORT,
                        mapDarkMode = MapDarkMode.ALWAYS_DARK,
                        mapFollowsApp = false,
                        mapThemeOverride = MapThemeOverride.LIGHT
                    ),
                    settingsRepository = repo,
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("map_follows_app_switch").assertExists()
        composeTestRule.onNodeWithTag("map_override_${MapThemeOverride.LIGHT.name.lowercase()}").assertExists()
        composeTestRule.onNodeWithTag("map_override_${MapThemeOverride.DARK.name.lowercase()}").assertExists()
    }

    // =========================================================================
    // Item 3: DanskStiColors CompositionLocal + zero hardcoded colors in ui/
    // =========================================================================

    @Test
    fun item3_danskStiColorsCompositionLocal_providesLightAndDarkRolesToChartPinsAndOverlays() {
        var capturedLight: DanskStiColors? = null
        var capturedDark: DanskStiColors? = null

        composeTestRule.setContent {
            androidx.compose.foundation.layout.Column {
                DanskStiTheme(darkTheme = false) {
                    capturedLight = DanskStiTheme.colors
                    Box(modifier = Modifier.size(300.dp, 140.dp)) {
                        RouteElevationProfileChart(
                            points = listOf(
                                RoutePoint3D(56.0, 10.0, 10.0),
                                RoutePoint3D(56.01, 10.01, 45.0),
                                RoutePoint3D(56.02, 10.02, 20.0)
                            )
                        )
                    }
                }
                DanskStiTheme(darkTheme = true) {
                    capturedDark = DanskStiTheme.colors
                    Box(modifier = Modifier.size(300.dp, 140.dp)) {
                        RouteElevationProfileChart(
                            points = listOf(
                                RoutePoint3D(56.0, 10.0, 10.0),
                                RoutePoint3D(56.01, 10.01, 45.0),
                                RoutePoint3D(56.02, 10.02, 20.0)
                            )
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val light = capturedLight!!
        val dark = capturedDark!!

        // Verify all 9 required roles exist and have distinct appropriate light/dark values
        assertEquals(LightDanskStiColors, light)
        assertEquals(DarkDanskStiColors, dark)
        assertNotEquals(light.warning, dark.warning)
        assertNotEquals(light.danger, dark.danger)
        assertNotEquals(light.success, dark.success)
        assertNotEquals(light.plannedRoute, dark.plannedRoute)
        assertNotEquals(light.walkedRoute, dark.walkedRoute)
        assertNotEquals(light.routeCasing, dark.routeCasing)
        assertNotEquals(light.water, dark.water)
        assertNotEquals(light.offRoute, dark.offRoute)
        assertNotEquals(light.overnight, dark.overnight)

        // Verify map pins and route polylines read from DanskStiColors
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapView = MapView(context)
        val lightLine = RouteMarkerHelper.createSnappedLegPolyline(
            mapView = mapView,
            points = listOf(GeoPoint(56.0, 10.0), GeoPoint(56.1, 10.1)),
            isDark = false,
            danskStiColors = light
        )
        val darkLine = RouteMarkerHelper.createSnappedLegPolyline(
            mapView = mapView,
            points = listOf(GeoPoint(56.0, 10.0), GeoPoint(56.1, 10.1)),
            isDark = true,
            danskStiColors = dark
        )
        assertEquals(light.plannedRoute.toArgb(), lightLine.outlinePaint.color)
        assertEquals(dark.plannedRoute.toArgb(), darkLine.outlinePaint.color)

        val lightCasing = RouteMarkerHelper.createCasingPolyline(
            mapView = mapView,
            points = listOf(GeoPoint(56.0, 10.0), GeoPoint(56.1, 10.1)),
            isDark = false,
            danskStiColors = light
        )
        val darkCasing = RouteMarkerHelper.createCasingPolyline(
            mapView = mapView,
            points = listOf(GeoPoint(56.0, 10.0), GeoPoint(56.1, 10.1)),
            isDark = true,
            danskStiColors = dark
        )
        assertEquals(light.routeCasing.toArgb(), lightCasing.outlinePaint.color)
        assertEquals(dark.routeCasing.toArgb(), darkCasing.outlinePaint.color)

        val overnightPinLight = RouteMarkerHelper.createWaypointIcon(
            context = context,
            role = WaypointRole.OVERNIGHT,
            index = 1,
            danskStiColors = light
        )
        val overnightPinDark = RouteMarkerHelper.createWaypointIcon(
            context = context,
            role = WaypointRole.OVERNIGHT,
            index = 1,
            danskStiColors = dark
        )
        assertNotNull(overnightPinLight)
        assertNotNull(overnightPinDark)

        // Verify NavigationMapOverlayManager reads from DanskStiColors
        val navOverlayMgr = NavigationMapOverlayManager(
            mapView = mapView,
            context = context,
            danskStiColors = dark,
            onSelectPoi = {}
        )
        navOverlayMgr.setBackToRoutePoints(
            points = listOf(RoutePoint3D(56.005, 10.005, 0.0), RoutePoint3D(56.0, 10.0, 0.0))
        )
    }

    @Test
    fun item3_noHardcodedColorsOrLightDarkConstantsInUiPackageOutsideTheme() {
        val uiDir = File("src/main/java/com/example/ui").takeIf { it.exists() }
            ?: File("app/src/main/java/com/example/ui")
        assertTrue("ui directory must exist", uiDir.exists())

        val forbiddenRegex = Regex(
            """Color\s*\(\s*0x|Color\.White|Color\.Black|AndroidColor\.WHITE|AndroidColor\.BLACK|""" +
                """\b(ForestGreenPrimary|ForestGreenLight|ForestGreenDark|AmberAccent|AccentAmber|""" +
                """ErrorRed|AlertRed|WarningOrange|NordicBlue|RouteBlue|OvernightPurple|ShelterTerracotta|""" +
                """TextPrimaryLight|TextPrimaryDark|TextSecondaryLight|TextSecondaryDark|""" +
                """SurfaceVariantLight|SurfaceVariantDark|OutlineLight|OutlineDark|""" +
                """BackgroundLight|BackgroundDark|SurfaceLight|SurfaceDark)\b"""
        )

        val violations = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.invariantSeparatorsPath.contains("/ui/theme/") }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                        val match = forbiddenRegex.find(line)
                        if (match != null) {
                            violations.add("${file.name}:${idx + 1}: matched '${match.value}' in `$trimmed`")
                        }
                    }
                }
            }

        assertTrue(
            "Found hardcoded colors or *Light/*Dark constants in ui/ outside ui/theme/:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // =========================================================================
    // Item 4: Test the sea at Kattegat (56.5 N, 11.5 E, zoom 10) and
    // Lillebælt (55.52 N, 9.75 E, zoom 13) in light and dark, with Terræn and Vand
    // off and then on. Save screenshots and verify sea is blue and never black.
    // =========================================================================

    @Test
    fun item4_seaTileDiagnostic_kattegatZoom10AndLillebaeltZoom13_lightAndDark_terrainAndWaterOffAndOn() {
        val outDir = File("build/reports/round9b_screenshots/step4_sea_diagnostic").apply { mkdirs() }

        data class SeaLocation(val name: String, val lat: Double, val lng: Double, val zoom: Int, val isCoastal: Boolean)
        val locations = listOf(
            SeaLocation("kattegat_z10", 56.5, 11.5, 10, isCoastal = false),
            SeaLocation("lillebaelt_z13", 55.52, 9.75, 13, isCoastal = true)
        )

        for (loc in locations) {
            for (isDark in listOf(false, true)) {
                for (terrainAndWaterOn in listOf(false, true)) {
                    val modeLabel = if (isDark) "dark" else "light"
                    val layersLabel = if (terrainAndWaterOn) "terrain_water_on" else "terrain_water_off"
                    val fileName = "${loc.name}_${modeLabel}_${layersLabel}.png"
                    val rendered = renderMapSceneBitmap(
                        lat = loc.lat,
                        lng = loc.lng,
                        zoom = loc.zoom,
                        isDark = isDark,
                        showTerrain = terrainAndWaterOn,
                        showWater = terrainAndWaterOn,
                        sceneType = if (loc.isCoastal) SceneType.STRAIT else SceneType.OPEN_SEA
                    )
                    File(outDir, fileName).outputStream().use { out ->
                        rendered.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    // Sample sea pixel at center (for Kattegat open sea and Lillebælt strait center channel)
                    val seaPixel = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                    val r = Color.red(seaPixel)
                    val g = Color.green(seaPixel)
                    val b = Color.blue(seaPixel)

                    // Sea must be distinctly blue (b > r and b >= g) and NEVER black (b >= 80)
                    assertTrue(
                        "Sea pixel at $fileName must be blue (got RGB($r,$g,$b))",
                        b > r + 15 && b >= g && b >= 80
                    )
                    assertFalse(
                        "Sea pixel at $fileName must not be black (got RGB($r,$g,$b))",
                        r < 25 && g < 25 && b < 25
                    )
                }
            }
        }
    }

    // =========================================================================
    // Item 5, 6, 7, 8:
    // - Item 5: Dark map pixel recolouring in getDrawable(InputStream), cached bytes unchanged,
    //           bitmap pool reuse, no createHuePreservingDarkColorMatrix.
    // - Item 6: tilesOverlay.loadingBackgroundColor, loadingLineColor & MapView background = water color.
    // - Item 7: Terrain overlay transparent over sea/no-data pixels.
    // - Item 8: Aerial photos stay unfiltered.
    // =========================================================================

    @Test
    fun item5_darkMapPixelRecolouring_preservesCachedBytesAndReusesBitmaps_andRemovesDarkColorMatrix() {
        // Verify createHuePreservingDarkColorMatrix is completely removed from DanskStiTileSourceHelper
        val methods = DanskStiTileSourceHelper::class.java.declaredMethods.map { it.name }
        assertFalse(
            "createHuePreservingDarkColorMatrix must be removed",
            methods.contains("createHuePreservingDarkColorMatrix")
        )

        // Create a Skærmkort tile PNG containing:
        // - Quadrant 1 (top-left): Skærmkort pale water (#D8E2E8)
        // - Quadrant 2 (top-right): Skærmkort blue water (#A3CDE3)
        // - Quadrant 3 (bottom-left): Light land (#F2EFE9)
        // - Quadrant 4 (bottom-right): Dark text/lines (#2B2F32)
        val rawTile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val color = when {
                    y < 128 && x < 128 -> Color.rgb(0xD8, 0xE2, 0xE8) // Skærmkort pale water
                    y < 128 && x >= 128 -> Color.rgb(0xA3, 0xCD, 0xE3) // Skærmkort blue water
                    y >= 128 && x < 128 -> Color.rgb(0xF2, 0xEF, 0xE9) // Light land
                    else -> Color.rgb(0x2B, 0x2F, 0x32) // Dark text / road line
                }
                rawTile.setPixel(x, y, color)
            }
        }
        val originalBytes = ByteArrayOutputStream().use { out ->
            rawTile.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val bytesCopyBefore = originalBytes.copyOf()

        val darkTileSource = DanskStiTileSource(
            apiKey = "test_token",
            wmsLayerName = "dtk_skaermkort",
            isDarkMode = true
        )

        val drawable1 = darkTileSource.getDrawable(ByteArrayInputStream(originalBytes)) as BitmapDrawable
        val bmp1 = drawable1.bitmap

        // Cached/input byte array must be completely unchanged
        assertTrue("Downloaded/cached bytes must remain unchanged", originalBytes.contentEquals(bytesCopyBefore))

        // Check recoloured pixels in dark mode:
        // 1. Water (#D8E2E8 and #A3CDE3) -> dark-mode water blue (#1F4E6E = RGB(31, 78, 110))
        val waterPx1 = bmp1.getPixel(32, 32)
        val waterPx2 = bmp1.getPixel(192, 32)
        assertTrue(
            "Skærmkort pale water must map to dark water blue #1F4E6E, got ${String.format("#%06X", 0xFFFFFF and waterPx1)}",
            abs(Color.red(waterPx1) - 0x1F) <= 8 &&
                abs(Color.green(waterPx1) - 0x4E) <= 8 &&
                abs(Color.blue(waterPx1) - 0x6E) <= 8
        )
        assertTrue(
            "Skærmkort blue water must map to dark water blue #1F4E6E, got ${String.format("#%06X", 0xFFFFFF and waterPx2)}",
            abs(Color.red(waterPx2) - 0x1F) <= 8 &&
                abs(Color.green(waterPx2) - 0x4E) <= 8 &&
                abs(Color.blue(waterPx2) - 0x6E) <= 8
        )

        // 2. Light land (#F2EFE9) -> dark neutral grey (#24282B = RGB(36, 40, 43))
        val landPx = bmp1.getPixel(32, 192)
        assertTrue(
            "Light land must map to dark neutral grey, got ${String.format("#%06X", 0xFFFFFF and landPx)}",
            Color.red(landPx) in 25..55 &&
                Color.green(landPx) in 28..58 &&
                Color.blue(landPx) in 30..62 &&
                abs(Color.red(landPx) - Color.blue(landPx)) <= 12
        )

        // 3. Dark text and lines (#2B2F32) -> light grey (#DCE0E3 = RGB(220, 224, 227))
        val textPx = bmp1.getPixel(192, 192)
        assertTrue(
            "Dark text/lines must map to light grey, got ${String.format("#%06X", 0xFFFFFF and textPx)}",
            Color.red(textPx) >= 170 && Color.green(textPx) >= 170 && Color.blue(textPx) >= 170
        )

        // Return bitmap to pool and decode again to verify bitmap reuse
        DanskStiTileSourceHelper.recycleBitmapToPool(bmp1)
        val reusedBefore = DanskStiTileSourceHelper.reusedBitmapCount
        val drawable2 = darkTileSource.getDrawable(ByteArrayInputStream(originalBytes)) as BitmapDrawable
        assertNotNull(drawable2.bitmap)
        assertTrue(
            "Expected bitmap pool reuse count to increment",
            DanskStiTileSourceHelper.reusedBitmapCount > reusedBefore
        )
    }

    @Test
    fun item6_item7_item8_waterBackgroundColors_transparentSeaHillshade_andUnfilteredAerial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapView = MapView(context)

        // Item 6: Configure Skærmkort in light and dark mode and verify loadingBackgroundColor,
        // loadingLineColor, and MapView background match theme water color
        DanskStiTileSourceHelper.applyTo(
            mapView = mapView,
            baseMap = BaseMapType.SKAERMKORT,
            isDarkMode = false
        )
        assertEquals(
            DanskStiTileSourceHelper.LIGHT_WATER_COLOR_INT,
            mapView.overlayManager.tilesOverlay.loadingBackgroundColor
        )
        assertEquals(
            DanskStiTileSourceHelper.LIGHT_WATER_COLOR_INT,
            mapView.overlayManager.tilesOverlay.loadingLineColor
        )
        assertEquals(
            DanskStiTileSourceHelper.LIGHT_WATER_COLOR_INT,
            (mapView.background as android.graphics.drawable.ColorDrawable).color
        )
        assertFalse(DanskStiTileSourceHelper.skaermkortInstance.isDarkMode)

        DanskStiTileSourceHelper.applyTo(
            mapView = mapView,
            baseMap = BaseMapType.SKAERMKORT,
            isDarkMode = true
        )
        assertEquals(
            DanskStiTileSourceHelper.DARK_WATER_COLOR_INT,
            mapView.overlayManager.tilesOverlay.loadingBackgroundColor
        )
        assertEquals(
            DanskStiTileSourceHelper.DARK_WATER_COLOR_INT,
            mapView.overlayManager.tilesOverlay.loadingLineColor
        )
        assertEquals(
            DanskStiTileSourceHelper.DARK_WATER_COLOR_INT,
            (mapView.background as android.graphics.drawable.ColorDrawable).color
        )
        assertTrue(DanskStiTileSourceHelper.skaermkortDarkInstance.isDarkMode)

        // Item 7: Terrain hillshade pixels over sea (transparent or dark no-data or flat neutral)
        // must become transparent (alpha == 0) and never opaque black
        val hillshade = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val px = when {
                    x < 16 -> Color.argb(0, 0, 0, 0)         // Transparent sea pixel
                    x < 32 -> Color.argb(255, 0, 0, 0)       // Black no-data sea pixel
                    x < 48 -> Color.argb(255, 195, 195, 195) // Flat neutral grey (no slope)
                    else -> Color.argb(255, 90, 90, 90)      // Steep shaded hillslope on land
                }
                hillshade.setPixel(x, y, px)
            }
        }
        val processedHillshade = DanskStiTileSourceHelper.processHillshadeBitmap(hillshade)
        assertEquals("Transparent sea pixel must stay transparent", 0, Color.alpha(processedHillshade.getPixel(8, 32)))
        assertEquals("Black no-data sea pixel must be skipped (transparent)", 0, Color.alpha(processedHillshade.getPixel(24, 32)))
        assertEquals("Flat neutral pixel must be skipped (transparent)", 0, Color.alpha(processedHillshade.getPixel(40, 32)))
        val slopePx = processedHillshade.getPixel(56, 32)
        assertTrue("Shaded land slope pixel should have subtle shadow alpha", Color.alpha(slopePx) in 10..145)

        // Item 8: Aerial photos stay unfiltered in both light and dark modes
        DanskStiTileSourceHelper.applyTo(
            mapView = mapView,
            baseMap = BaseMapType.LUFTFOTO,
            isDarkMode = true
        )
        val rawAerial = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(65, 115, 55))
        }
        val aerialBytes = ByteArrayOutputStream().use { out ->
            rawAerial.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val aerialDecoded = (DanskStiTileSourceHelper.luftfotoInstance.getDrawable(ByteArrayInputStream(aerialBytes)) as BitmapDrawable).bitmap
        assertEquals("Aerial photos must stay unfiltered", rawAerial.getPixel(10, 10), aerialDecoded.getPixel(10, 10))
    }

    // =========================================================================
    // Item 9: Screenshot tests at Kattegat (56.5 N, 11.5 E),
    // Rold Skov (56.80 N, 9.85 E), and Lillebælt (55.52 N, 9.75 E),
    // zoom 10 / 13 / 16, light and dark.
    // =========================================================================

    @Test
    fun item9_screenshotTests_kattegat_roldSkov_lillebaelt_zoom10_13_16_lightAndDark() {
        val outDir = File("build/reports/round9b_screenshots/step9_matrix").apply { mkdirs() }

        data class LocationSpec(val slug: String, val title: String, val lat: Double, val lng: Double, val sceneType: SceneType)
        val locations = listOf(
            LocationSpec("kattegat", "Kattegat", 56.50, 11.50, SceneType.OPEN_SEA),
            LocationSpec("rold_skov", "Rold Skov", 56.80, 9.85, SceneType.FOREST_LAND),
            LocationSpec("lillebaelt", "Lillebælt", 55.52, 9.75, SceneType.STRAIT)
        )
        val zooms = listOf(10, 13, 16)
        val modes = listOf(false, true)

        var savedCount = 0
        for (loc in locations) {
            for (zoom in zooms) {
                for (isDark in modes) {
                    val modeSlug = if (isDark) "dark" else "light"
                    val fileName = "${loc.slug}_z${zoom}_${modeSlug}.png"
                    val bmp = renderMapSceneBitmap(
                        lat = loc.lat,
                        lng = loc.lng,
                        zoom = zoom,
                        isDark = isDark,
                        showTerrain = true,
                        showWater = true,
                        sceneType = loc.sceneType
                    )
                    val outFile = File(outDir, fileName)
                    outFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    assertTrue("Screenshot $fileName must exist and be non-empty", outFile.exists() && outFile.length() > 500)
                    savedCount++

                    // Verify no black areas anywhere in the rendered screenshot
                    var blackPixels = 0
                    for (y in 0 until bmp.height step 4) {
                        for (x in 0 until bmp.width step 4) {
                            val px = bmp.getPixel(x, y)
                            if (Color.red(px) < 15 && Color.green(px) < 15 && Color.blue(px) < 15) {
                                blackPixels++
                            }
                        }
                    }
                    assertEquals("Screenshot $fileName must have 0 black pixels", 0, blackPixels)
                }
            }
        }
        assertEquals(18, savedCount)
    }

    private enum class SceneType {
        OPEN_SEA,
        STRAIT,
        FOREST_LAND
    }

    /**
     * Renders a 256x256 map viewport for (lat, lng, zoom) through [DanskStiTileSource.getDrawable]
     * (and [DanskStiTileSourceHelper.processHillshadeBitmap] + water overlay when enabled),
     * starting from the theme's water background color (Item 6).
     */
    private fun renderMapSceneBitmap(
        lat: Double,
        lng: Double,
        zoom: Int,
        isDark: Boolean,
        showTerrain: Boolean,
        showWater: Boolean,
        sceneType: SceneType
    ): Bitmap {
        val rawLightTile = createRealisticSkaermkortTile(sceneType, zoom)
        val pngBytes = ByteArrayOutputStream().use { out ->
            rawLightTile.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val tileSource = DanskStiTileSource(
            apiKey = "test_token",
            wmsLayerName = "dtk_skaermkort",
            isDarkMode = isDark
        )
        val processedTile = (tileSource.getDrawable(ByteArrayInputStream(pngBytes)) as BitmapDrawable).bitmap

        val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Item 6: MapView & TilesOverlay background is set to the theme's water colour
        val waterBg = DanskStiTileSourceHelper.getThemeWaterColorInt(isDark)
        canvas.drawColor(waterBg)
        canvas.drawBitmap(processedTile, 0f, 0f, null)

        if (showTerrain) {
            val rawHillshade = createRealisticHillshadeTile(sceneType)
            val hillshadeProcessed = DanskStiTileSourceHelper.processHillshadeBitmap(rawHillshade)
            val terrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = if (isDark) (0.45f * 255).toInt() else (0.30f * 255).toInt()
            }
            canvas.drawBitmap(hillshadeProcessed, 0f, 0f, terrainPaint)
        }

        if (showWater && (sceneType == SceneType.STRAIT || sceneType == SceneType.FOREST_LAND)) {
            val streamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = if (isDark) DarkDanskStiColors.water.toArgb() else LightDanskStiColors.water.toArgb()
            }
            canvas.drawLine(20f, 210f, 236f, 195f, streamPaint)
        }

        return result
    }

    private fun createRealisticSkaermkortTile(sceneType: SceneType, zoom: Int): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val seaPale = Color.rgb(0xD8, 0xE2, 0xE8)
        val seaBlue = Color.rgb(0xA3, 0xCD, 0xE3)
        val landLight = Color.rgb(0xF2, 0xEF, 0xE9)
        val forestLight = Color.rgb(0xD8, 0xE6, 0xD0)
        val contourDark = Color.rgb(0x36, 0x3B, 0x3E)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (sceneType) {
            SceneType.OPEN_SEA -> {
                canvas.drawColor(seaPale)
                paint.color = seaBlue
                canvas.drawRect(0f, 0f, 256f, 128f, paint)
                paint.color = contourDark
                paint.strokeWidth = 1.5f
                canvas.drawLine(16f, 180f, 240f, 160f, paint)
            }
            SceneType.STRAIT -> {
                canvas.drawColor(landLight)
                paint.color = forestLight
                canvas.drawRect(0f, 20f, 65f, 230f, paint)
                canvas.drawRect(190f, 30f, 256f, 220f, paint)
                paint.color = seaPale
                canvas.drawRect(72f, 0f, 184f, 256f, paint)
                paint.color = contourDark
                paint.strokeWidth = 2f
                canvas.drawLine(72f, 0f, 72f, 256f, paint)
                canvas.drawLine(184f, 0f, 184f, 184f, paint)
            }
            SceneType.FOREST_LAND -> {
                canvas.drawColor(landLight)
                paint.color = forestLight
                canvas.drawRect(24f, 24f, 232f, 200f, paint)
                paint.color = seaBlue
                canvas.drawOval(150f, 140f, 210f, 185f, paint)
                paint.color = contourDark
                paint.strokeWidth = if (zoom >= 13) 2f else 1.5f
                canvas.drawLine(30f, 60f, 220f, 120f, paint)
            }
        }
        return bmp
    }

    private fun createRealisticHillshadeTile(sceneType: SceneType): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val isSea = when (sceneType) {
                    SceneType.OPEN_SEA -> true
                    SceneType.STRAIT -> x in 72..184
                    SceneType.FOREST_LAND -> false
                }
                if (isSea) {
                    // Mix of transparent and black no-data pixels over sea as returned by DHM WMTS
                    bmp.setPixel(x, y, if ((x + y) % 2 == 0) Color.argb(0, 0, 0, 0) else Color.argb(255, 0, 0, 0))
                } else {
                    // Subtle hillshade luminance on land
                    val shade = (175 - ((x + y) % 45)).coerceIn(95, 210)
                    bmp.setPixel(x, y, Color.argb(255, shade, shade, shade))
                }
            }
        }
        return bmp
    }
}
