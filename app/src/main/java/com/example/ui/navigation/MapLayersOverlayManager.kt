package com.example.ui.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.model.FriTeltningEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaterFeatureDto
import com.example.data.model.access.AccessPolygon
import com.example.data.repository.AccessLandRepository
import com.example.data.repository.MarkedRouteRepository
import com.example.data.repository.WaterLayerRepository
import com.example.util.DanskStiTileSourceHelper as DanskStiTileSource
import com.example.util.WaterLayerOverlay
import com.example.util.access.AccessMapOverlayHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Shared overlay manager for DanskStiMap across all 5 map screens.
 *
 * Enforces Fix Round 3 Part 3:
 * - Overlays are registered by a stable key ("terrain_hillshade", "terrain_contours", "access",
 *   "water", "marked_routes", "route", "pois", "drop_pin", "location", "scale_bar") and updated in place.
 * - Never calls `overlays.clear()`.
 * - Recreates markers and polygons only when their data actually changes.
 * - Clusters all 7 POI layers (shelters, fri teltning, water, toilets, shops, transit, my points) below zoom 13.
 * - Fetches and renders the "Vand" water overlay from zoom 12+, debounced 600 ms after map movement stops,
 *   cancelling the previous request.
 */
class MapLayersOverlayManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val markedRouteRepository: MarkedRouteRepository = MarkedRouteRepository(
        context,
        DanskStiDatabase.getInstance(context).dao(),
        com.example.data.repository.TrailGeometryRepository(context)
    ),
    private val accessLandRepository: AccessLandRepository = AccessLandRepository(
        DanskStiDatabase.getInstance(context).dao()
    ),
    private val waterLayerRepository: WaterLayerRepository = WaterLayerRepository(
        DanskStiDatabase.getInstance(context).dao()
    ),
    private val onMarkedRouteTapped: ((MarkedRouteDto) -> Unit)? = null,
    private val onPoiTapped: ((PoiItem) -> Unit)? = null,
    private val onAccessPolygonTapped: ((AccessPolygon) -> Unit)? = null
) {
    private var mapView: MapView? = null
    private var currentState: MapLayersState = MapLayersState()
    private var currentIsDark: Boolean = false

    // Stable-keyed overlays
    private val registeredOverlays = LinkedHashMap<String, Overlay>()
    private var hillshadeOverlay: TilesOverlay? = null
    private var contourOverlay: TilesOverlay? = null
    private val accessFolderOverlay = FolderOverlay().apply { name = KEY_ACCESS }
    private val waterLayerOverlay = WaterLayerOverlay().apply { name = KEY_WATER }
    private val markedRoutesFolderOverlay = FolderOverlay().apply { name = KEY_MARKED_ROUTES }
    private val routeFolderOverlay = FolderOverlay().apply { name = KEY_ROUTE }
    private var poiClusterOverlay: PoiClusterOverlay? = null

    // Data caches & signatures to avoid recreating markers/polygons unless data changed
    private var cachedShelters: List<ShelterEntity> = emptyList()
    private var cachedFriTeltning: List<FriTeltningEntity> = emptyList()
    private var cachedPois: List<PoiEntity> = emptyList()
    private var cachedCustomWaypoints: List<UserCustomWaypointEntity> = emptyList()
    private var cachedMarkedRoutes: List<MarkedRouteDto> = emptyList()
    private var cachedAccessPolygons: List<AccessPolygon> = emptyList()
    private var cachedWaterFeatures: List<WaterFeatureDto> = emptyList()

    private var lastPoiSignature: Int = 0
    private var lastAccessSignature: Int = 0
    private var lastMarkedRoutesSignature: Int = 0

    // Coroutine jobs for viewport fetches
    private var cameraIdleJob: Job? = null
    private var markedRouteFetchJob: Job? = null
    private var accessFetchJob: Job? = null
    private var waterDebounceJob: Job? = null
    private var waterFetchJob: Job? = null
    private var myPointsObserverJob: Job? = null

    private var lastAppliedBaseMap = currentState.baseMap
    private var lastAppliedDark: Boolean? = null
    private var lastFetchedBoundsKey: String? = null
    private var lastAccessBoundsKey: String? = null
    private var lastWaterBoundsKey: String? = null
    private var lastInvalidationElapsedMs: Long = 0L

    companion object {
        const val KEY_TERRAIN_HILLSHADE = "terrain_hillshade"
        const val KEY_TERRAIN_CONTOURS = "terrain_contours"
        const val KEY_ACCESS = "access"
        const val KEY_WATER = "water"
        const val KEY_MARKED_ROUTES = "marked_routes"
        const val KEY_ROUTE = "route"
        const val KEY_POIS = "pois"
        const val KEY_DROP_PIN = "drop_pin"
        const val KEY_LOCATION = "location"
        const val KEY_SCALE_BAR = "scale_bar"
        const val KEY_EVENTS = "events"

        const val WATER_DEBOUNCE_MS = 600L
        const val CAMERA_DEBOUNCE_MS = 320L
    }

    fun attachToMap(map: MapView, isDark: Boolean = currentIsDark) {
        if (this.mapView === map) {
            if (currentIsDark != isDark) {
                setDarkMode(isDark)
            }
            return
        }
        this.mapView = map
        this.currentIsDark = isDark

        // Ensure POI cluster overlay exists
        val clusterOverlay = poiClusterOverlay ?: PoiClusterOverlay(
            context = context,
            onSinglePoiTap = { item -> onPoiTapped?.invoke(item) }
        ).also { poiClusterOverlay = it }

        // Ensure terrain tile overlays exist
        val hs = hillshadeOverlay ?: DanskStiTileSource.createHillshadeOverlay(context, isDark).also {
            hillshadeOverlay = it
        }
        val ct = contourOverlay ?: DanskStiTileSource.createContourOverlay(context, isDark).also {
            contourOverlay = it
        }
        hs?.isEnabled = currentState.showTerrain
        ct?.isEnabled = currentState.showTerrain

        // Register core stable overlays in deterministic bottom-to-top Z-order
        if (hs != null) registerOverlay(KEY_TERRAIN_HILLSHADE, hs)
        if (ct != null) registerOverlay(KEY_TERRAIN_CONTOURS, ct)
        registerOverlay(KEY_ACCESS, accessFolderOverlay)
        registerOverlay(KEY_WATER, waterLayerOverlay)
        registerOverlay(KEY_MARKED_ROUTES, markedRoutesFolderOverlay)
        registerOverlay(KEY_ROUTE, routeFolderOverlay)
        registerOverlay(KEY_POIS, clusterOverlay)

        // Observe custom waypoints from DB so "Mine punkter" stays live across all screens
        if (myPointsObserverJob == null) {
            myPointsObserverJob = scope.launch {
                try {
                    DanskStiDatabase.getInstance(context).dao().getAllCustomWaypoints().collect { list ->
                        cachedCustomWaypoints = list
                        if (currentState.showMyPoints) {
                            rebuildPoiClusterItemsIfChanged()
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Listen for pan/zoom events to debounce water (600 ms) and marked routes / access
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleCameraIdleFetch()
                scheduleWaterDebouncedFetch()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                // Immediately toggle zoom-gated overlays without recreating objects
                val shouldShowWater = currentState.showWaterLayer
                if (waterLayerOverlay.isEnabled != shouldShowWater) {
                    waterLayerOverlay.isEnabled = shouldShowWater
                    requestMapInvalidate()
                }
                scheduleCameraIdleFetch()
                scheduleWaterDebouncedFetch()
                return false
            }
        })
    }

    /**
     * Registers or replaces an overlay under a stable key in `mapView.overlays` without ever calling `overlays.clear()`.
     */
    fun registerOverlay(key: String, overlay: Overlay) {
        val prev = registeredOverlays.put(key, overlay)
        val map = mapView ?: return
        val overlays = map.overlays
        if (prev != null && prev !== overlay) {
            val idx = overlays.indexOf(prev)
            if (idx >= 0) {
                overlays[idx] = overlay
                return
            }
        }
        if (!overlays.contains(overlay)) {
            // Insert according to canonical Z-order
            val targetOrder = canonicalOrderIndex(key)
            var insertIdx = overlays.size
            for (i in overlays.indices) {
                val existing = overlays[i]
                val existingKey = registeredOverlays.entries.firstOrNull { it.value === existing }?.key
                if (existingKey != null && canonicalOrderIndex(existingKey) > targetOrder) {
                    insertIdx = i
                    break
                }
            }
            overlays.add(insertIdx, overlay)
        }
    }

    /**
     * Updates the children of a stable FolderOverlay key (e.g. "route", "drop_pin") in place.
     */
    fun updateFolderOverlay(key: String, children: List<Overlay>) {
        val folder = (registeredOverlays[key] as? FolderOverlay) ?: FolderOverlay().apply {
            name = key
            registerOverlay(key, this)
        }
        folder.items.clear()
        folder.items.addAll(children)
        requestMapInvalidate()
    }

    fun getOverlay(key: String): Overlay? = registeredOverlays[key]

    fun getRouteFolderOverlay(): FolderOverlay = routeFolderOverlay

    private fun canonicalOrderIndex(key: String): Int = when (key) {
        KEY_EVENTS -> 0
        KEY_TERRAIN_HILLSHADE -> 10
        KEY_TERRAIN_CONTOURS -> 11
        KEY_ACCESS -> 20
        KEY_WATER -> 25
        KEY_MARKED_ROUTES -> 30
        KEY_ROUTE -> 40
        KEY_POIS -> 50
        KEY_DROP_PIN -> 60
        KEY_LOCATION -> 70
        KEY_SCALE_BAR -> 80
        else -> 45
    }

    fun setDarkMode(isDark: Boolean) {
        if (currentIsDark == isDark && lastAppliedDark == isDark) return
        currentIsDark = isDark
        val map = mapView ?: return
        lastAppliedDark = isDark
        lastAppliedBaseMap = currentState.baseMap
        DanskStiTileSource.applyBaseMapTo(map, currentState.baseMap, isDark)
        hillshadeOverlay?.let { DanskStiTileSource.updateHillshadeDarkMode(it, isDark) }
        contourOverlay?.let { DanskStiTileSource.updateContourDarkMode(it, isDark) }
        if (cachedWaterFeatures.isNotEmpty()) {
            val density = context.resources.displayMetrics.density
            waterLayerOverlay.updateFeatures(cachedWaterFeatures, isDark, density)
        }
        requestMapInvalidate()
    }

    /**
     * Updates active layer toggles and data sources without recreating unchanged overlays.
     */
    fun updateLayersState(
        state: MapLayersState,
        shelters: List<ShelterEntity> = cachedShelters,
        friTeltning: List<FriTeltningEntity> = cachedFriTeltning,
        pois: List<PoiEntity> = cachedPois,
        customWaypoints: List<UserCustomWaypointEntity> = cachedCustomWaypoints,
        isDark: Boolean = currentIsDark
    ) {
        val prevState = currentState
        currentState = state
        cachedShelters = shelters
        cachedFriTeltning = friTeltning
        cachedPois = pois
        if (customWaypoints.isNotEmpty() || cachedCustomWaypoints.isEmpty()) {
            cachedCustomWaypoints = customWaypoints
        }

        val map = mapView ?: return

        // 1. Base map & dark mode
        if (lastAppliedBaseMap != state.baseMap || lastAppliedDark != isDark) {
            lastAppliedBaseMap = state.baseMap
            lastAppliedDark = isDark
            currentIsDark = isDark
            DanskStiTileSource.applyBaseMapTo(map, state.baseMap, isDark)
        }

        // 2. Terrain overlay ("Terræn": hillshade + contours from zoom 14)
        hillshadeOverlay?.let {
            it.isEnabled = state.showTerrain
            DanskStiTileSource.updateHillshadeDarkMode(it, isDark)
        }
        contourOverlay?.let {
            it.isEnabled = state.showTerrain
            DanskStiTileSource.updateContourDarkMode(it, isDark)
        }

        // 3. Water overlay ("Vand")
        val shouldShowWater = state.showWaterLayer
        waterLayerOverlay.isEnabled = shouldShowWater
        if (state.showWaterLayer && (!prevState.showWaterLayer || cachedWaterFeatures.isEmpty())) {
            lastWaterBoundsKey = null
            fetchWaterForCurrentViewport()
        } else if (!state.showWaterLayer) {
            waterDebounceJob?.cancel()
            waterFetchJob?.cancel()
        }

        // 4. Rebuild POI cluster items only if toggles or POI lists changed
        rebuildPoiClusterItemsIfChanged()

        // 5. Marked routes ("Afmærkede ruter")
        markedRoutesFolderOverlay.isEnabled = state.showMarkedRoutes
        if (!state.showMarkedRoutes) {
            markedRouteFetchJob?.cancel()
        } else if (!prevState.showMarkedRoutes || cachedMarkedRoutes.isEmpty()) {
            lastFetchedBoundsKey = null
            fetchMarkedRoutesForCurrentViewport()
        }

        // 6. Access polygons ("Adgang og ejerskab")
        accessFolderOverlay.isEnabled = state.showAccessLand
        if (!state.showAccessLand) {
            accessFetchJob?.cancel()
        } else if (!prevState.showAccessLand || cachedAccessPolygons.isEmpty()) {
            lastAccessBoundsKey = null
            fetchAccessLandForCurrentViewport()
        }

        requestMapInvalidate()
    }

    /**
     * Requirement D: Debounced 600 ms after the map stops moving; cancels the previous request.
     */
    private fun scheduleWaterDebouncedFetch() {
        waterDebounceJob?.cancel()
        waterFetchJob?.cancel()
        if (!currentState.showWaterLayer) return
        waterDebounceJob = scope.launch {
            delay(WATER_DEBOUNCE_MS)
            fetchWaterForCurrentViewport()
        }
    }

    private fun scheduleCameraIdleFetch() {
        cameraIdleJob?.cancel()
        cameraIdleJob = scope.launch {
            delay(CAMERA_DEBOUNCE_MS)
            onCameraIdle()
        }
    }

    fun onCameraIdle() {
        if (currentState.showMarkedRoutes) {
            fetchMarkedRoutesForCurrentViewport()
        }
        if (currentState.showAccessLand) {
            fetchAccessLandForCurrentViewport()
        }
        if (currentState.showWaterLayer) {
            fetchWaterForCurrentViewport()
        }
    }

    fun fetchWaterForCurrentViewport() {
        val map = mapView ?: return
        if (!currentState.showWaterLayer) {
            waterLayerOverlay.isEnabled = false
            requestMapInvalidate()
            return
        }
        val zoom = map.zoomLevelDouble
        waterLayerOverlay.isEnabled = true

        val box = map.boundingBox
        val minLat = (box?.latSouth ?: 54.5).coerceIn(54.4, 58.0)
        val maxLat = (box?.latNorth ?: 58.0).coerceIn(54.4, 58.0).let { if (it <= minLat) 58.0 else it }
        val minLng = (box?.lonWest ?: 8.0).coerceIn(7.8, 15.5)
        val maxLng = (box?.lonEast ?: 15.5).coerceIn(7.8, 15.5).let { if (it <= minLng) 15.5 else it }

        val minTileLat = WaterLayerRepository.tileIndexForCoord(minLat)
        val maxTileLat = WaterLayerRepository.tileIndexForCoord(maxLat)
        val minTileLng = WaterLayerRepository.tileIndexForCoord(minLng)
        val maxTileLng = WaterLayerRepository.tileIndexForCoord(maxLng)
        val isLiveZoom = zoom >= WaterLayerRepository.MIN_ZOOM_FOR_WATER
        val boundsKey = "water_${isLiveZoom}_${minTileLat}_${maxTileLat}_${minTileLng}_${maxTileLng}"
        if (boundsKey == lastWaterBoundsKey && cachedWaterFeatures.isNotEmpty()) return
        lastWaterBoundsKey = boundsKey

        waterFetchJob?.cancel()
        waterFetchJob = scope.launch {
            val features = if (isLiveZoom) {
                waterLayerRepository.getWaterFeaturesForViewport(
                    south = minLat,
                    west = minLng,
                    north = maxLat,
                    east = maxLng
                )
            } else {
                waterLayerRepository.getCachedWaterFeaturesInBbox(
                    south = minLat,
                    west = minLng,
                    north = maxLat,
                    east = maxLng
                )
            }
            ensureActive()
            cachedWaterFeatures = features
            val density = context.resources.displayMetrics.density
            val changed = waterLayerOverlay.updateFeatures(features, currentIsDark, density)
            if (changed) {
                requestMapInvalidate()
            }
        }
    }

    private fun fetchMarkedRoutesForCurrentViewport() {
        val map = mapView ?: return
        val zoom = map.zoomLevelDouble

        val box = map.boundingBox
        val minLat = (box?.latSouth ?: 54.5).coerceIn(54.4, 58.0)
        val maxLat = (box?.latNorth ?: 58.0).coerceIn(54.4, 58.0).let { if (it <= minLat) 58.0 else it }
        val minLng = (box?.lonWest ?: 8.0).coerceIn(7.8, 15.5)
        val maxLng = (box?.lonEast ?: 15.5).coerceIn(7.8, 15.5).let { if (it <= minLng) 15.5 else it }

        val isLiveZoom = zoom >= 10.0
        val boundsKey = "${isLiveZoom}_${(minLat * 5).toInt()}_${(maxLat * 5).toInt()}_${(minLng * 5).toInt()}_${(maxLng * 5).toInt()}"
        if (boundsKey == lastFetchedBoundsKey && cachedMarkedRoutes.isNotEmpty()) {
            return
        }
        lastFetchedBoundsKey = boundsKey

        markedRouteFetchJob?.cancel()
        markedRouteFetchJob = scope.launch {
            val routes = withContext(Dispatchers.IO) {
                markedRouteRepository.getMarkedRoutes(
                    minLat = minLat,
                    maxLat = maxLat,
                    minLng = minLng,
                    maxLng = maxLng,
                    zoomLevel = zoom
                )
            }
            ensureActive()
            cachedMarkedRoutes = routes
            rebuildMarkedRouteOverlaysIfChanged()
        }
    }

    private fun fetchAccessLandForCurrentViewport() {
        val map = mapView ?: return
        val zoom = map.zoomLevelDouble

        val box = map.boundingBox
        val minLat = (box?.latSouth ?: 54.5).coerceIn(54.4, 58.0)
        val maxLat = (box?.latNorth ?: 58.0).coerceIn(54.4, 58.0).let { if (it <= minLat) 58.0 else it }
        val minLng = (box?.lonWest ?: 8.0).coerceIn(7.8, 15.5)
        val maxLng = (box?.lonEast ?: 15.5).coerceIn(7.8, 15.5).let { if (it <= minLng) 15.5 else it }

        val isLiveZoom = zoom >= 11.0
        val boundsKey = "${isLiveZoom}_${(minLat * 10).toInt()}_${(maxLat * 10).toInt()}_${(minLng * 10).toInt()}_${(maxLng * 10).toInt()}"
        if (boundsKey == lastAccessBoundsKey && cachedAccessPolygons.isNotEmpty()) {
            return
        }
        lastAccessBoundsKey = boundsKey

        accessFetchJob?.cancel()
        accessFetchJob = scope.launch {
            val polygons = withContext(Dispatchers.IO) {
                if (isLiveZoom) {
                    accessLandRepository.getAccessPolygons(
                        minLat = minLat,
                        maxLat = maxLat,
                        minLng = minLng,
                        maxLng = maxLng
                    )
                } else {
                    accessLandRepository.getCachedAccessPolygonsInBbox(
                        minLat = minLat,
                        maxLat = maxLat,
                        minLng = minLng,
                        maxLng = maxLng
                    )
                }
            }
            ensureActive()
            cachedAccessPolygons = polygons
            rebuildAccessOverlaysIfChanged()
        }
    }

    private fun rebuildAccessOverlaysIfChanged() {
        if (!currentState.showAccessLand) return
        val map = mapView ?: return
        var sig = cachedAccessPolygons.size
        for (p in cachedAccessPolygons) {
            sig = 31 * sig + p.id.hashCode()
        }
        if (sig == lastAccessSignature && (cachedAccessPolygons.isEmpty() == accessFolderOverlay.items.isEmpty())) {
            return
        }
        lastAccessSignature = sig
        accessFolderOverlay.items.clear()
        val osmdroidPolygons = cachedAccessPolygons.map { poly ->
            AccessMapOverlayHelper.createAccessPolygonOverlay(
                mapView = map,
                polygon = poly,
                onPolygonClicked = { clickedPoly -> onAccessPolygonTapped?.invoke(clickedPoly) }
            )
        }
        accessFolderOverlay.items.addAll(osmdroidPolygons)
        requestMapInvalidate()
    }

    private fun rebuildMarkedRouteOverlaysIfChanged() {
        if (!currentState.showMarkedRoutes) return
        val density = context.resources.displayMetrics.density
        var sig = cachedMarkedRoutes.size
        for (r in cachedMarkedRoutes) {
            sig = 31 * sig + r.osmRelationId.hashCode()
        }
        if (sig == lastMarkedRoutesSignature && (cachedMarkedRoutes.isEmpty() == markedRoutesFolderOverlay.items.isEmpty())) {
            return
        }
        lastMarkedRoutesSignature = sig
        markedRoutesFolderOverlay.items.clear()

        for (route in cachedMarkedRoutes) {
            val routeColor = try {
                Color.parseColor(route.colorHex)
            } catch (_: Exception) {
                0xFFD84315.toInt()
            }

            for (segment in route.polylines) {
                if (segment.size < 2) continue
                val geoPoints = segment.map { GeoPoint(it.lat, it.lng) }

                val casing = Polyline().apply {
                    setPoints(geoPoints)
                    outlinePaint.color = Color.argb(170, 255, 255, 255)
                    outlinePaint.strokeWidth = 5.5f * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                    setOnClickListener { _, _, _ ->
                        onMarkedRouteTapped?.invoke(route)
                        true
                    }
                }

                val line = Polyline().apply {
                    title = route.name
                    setPoints(geoPoints)
                    outlinePaint.color = routeColor
                    outlinePaint.strokeWidth = 3.2f * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                    setOnClickListener { _, _, _ ->
                        onMarkedRouteTapped?.invoke(route)
                        true
                    }
                }

                markedRoutesFolderOverlay.add(casing)
                markedRoutesFolderOverlay.add(line)
            }
        }
        requestMapInvalidate()
    }

    private fun rebuildPoiClusterItemsIfChanged() {
        val overlay = poiClusterOverlay ?: return
        var sig = 17
        sig = 31 * sig + currentState.showShelters.hashCode()
        sig = 31 * sig + currentState.showFriTeltning.hashCode()
        sig = 31 * sig + currentState.showWaterPois.hashCode()
        sig = 31 * sig + currentState.showToilets.hashCode()
        sig = 31 * sig + currentState.showShops.hashCode()
        sig = 31 * sig + currentState.showTransit.hashCode()
        sig = 31 * sig + currentState.showMyPoints.hashCode()
        sig = 31 * sig + cachedShelters.size
        sig = 31 * sig + cachedFriTeltning.size
        sig = 31 * sig + cachedPois.size
        sig = 31 * sig + cachedCustomWaypoints.size

        if (sig == lastPoiSignature) return
        lastPoiSignature = sig

        val items = ArrayList<PoiItem>()

        if (currentState.showShelters) {
            for (s in cachedShelters) {
                items.add(
                    PoiItem(
                        id = s.id,
                        type = "shelter",
                        name = s.name,
                        subText = buildString {
                            s.capacity?.let { append("Kapacitet: $it pladser") }
                            if (s.bookingRequired) {
                                if (isNotEmpty()) append(" · ")
                                append("Booking")
                            }
                        }.ifEmpty { "Shelter / Lejrplads" },
                        lat = s.lat,
                        lng = s.lng,
                        payload = s
                    )
                )
            }
        }

        if (currentState.showFriTeltning) {
            for (f in cachedFriTeltning) {
                items.add(
                    PoiItem(
                        id = f.id,
                        type = "tent",
                        name = f.name,
                        subText = "Fri teltning skov",
                        lat = f.centerLat,
                        lng = f.centerLng,
                        payload = f
                    )
                )
            }
        }

        for (p in cachedPois) {
            val normalized = p.type.lowercase()
            val shouldInclude = when (normalized) {
                "water", "drinking_water", "vand" -> currentState.showWaterPois
                "toilet", "toilets" -> currentState.showToilets
                "shop", "supermarket", "convenience", "bakery", "indkoeb" -> currentState.showShops
                "station", "ferry", "train_station", "bus_stop", "ferry_terminal", "transit", "offentlig_transport" -> currentState.showTransit
                else -> false
            }
            if (shouldInclude) {
                val typeKey = when (normalized) {
                    "water", "drinking_water", "vand" -> "water"
                    "toilet", "toilets" -> "toilet"
                    "shop", "supermarket", "convenience", "bakery", "indkoeb" -> "shop"
                    else -> "transit"
                }
                items.add(
                    PoiItem(
                        id = p.id,
                        type = typeKey,
                        name = p.name.ifBlank {
                            when (typeKey) {
                                "water" -> "Drikkevand"
                                "toilet" -> "Toilet"
                                "shop" -> "Indkøb"
                                else -> "Stoppested / Station"
                            }
                        },
                        subText = p.openingHours ?: p.operator ?: "",
                        lat = p.lat,
                        lng = p.lng,
                        payload = p
                    )
                )
            }
        }

        if (currentState.showMyPoints) {
            for (wp in cachedCustomWaypoints) {
                items.add(
                    PoiItem(
                        id = "wp_${wp.id}",
                        type = "my_point",
                        name = wp.name,
                        subText = wp.note.ifBlank { "Gemt punkt" },
                        lat = wp.lat,
                        lng = wp.lng,
                        payload = wp
                    )
                )
            }
        }

        if (overlay.setItems(items)) {
            requestMapInvalidate()
        }
    }

    private fun requestMapInvalidate() {
        val map = mapView ?: return
        lastInvalidationElapsedMs = SystemClock.uptimeMillis()
        map.postInvalidate()
    }

    fun getActiveMarkedRoutePolylinesCount(): Int = markedRoutesFolderOverlay.items.size

    fun getActivePoiItemCount(): Int = poiClusterOverlay?.getItemCount() ?: 0

    fun getActiveWaterFeatureCount(): Int = waterLayerOverlay.currentFeatureCount
}
