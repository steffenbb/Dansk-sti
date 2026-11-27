package com.example.ui.navigation

import android.content.Context
import android.os.SystemClock
import com.example.data.local.DanskStiDatabase
import com.example.data.local.MapLayersState
import com.example.data.model.FriTeltningEntity
import com.example.data.model.MarkedRouteDto
import com.example.data.model.MarkedRouteRenderItem
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
import org.osmdroid.views.overlay.TilesOverlay
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared overlay manager for DanskStiMap across all 5 map screens.
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
    private val onAccessPolygonTapped: ((AccessPolygon) -> Unit)? = null,
    private val onRestrictedAreaTapped: ((com.example.data.repository.RestrictedArea) -> Unit)? = null
) {
    // Phase D3: military areas & wildlife reserves (Sikkerhed layer, zoom >= 9); shared repository so one cache/refresh
    private val restrictedAreaRepository: com.example.data.repository.RestrictedAreaRepository =
        runCatching { com.example.DanskStiApp.instance.repository.restrictedAreaRepository }
            .getOrElse { com.example.data.repository.RestrictedAreaRepository(context) }
    private val restrictedAreasFolderOverlay = FolderOverlay().apply { name = KEY_RESTRICTED_AREAS; isEnabled = false }
    private var restrictedAreasLoaded = false
    private var restrictedAreasJob: Job? = null

    private var mapView: MapView? = null
    private var currentState: MapLayersState =
        com.example.DanskStiApp.getSettingsRepository(context).currentSettingsSnapshot().mapLayers
    private var currentIsDark: Boolean = false

    // Tracks relation IDs currently being loaded from Room after a tap so duplicate taps are ignored
    private val loadingTappedRouteIds = ConcurrentHashMap.newKeySet<Long>()

    // Stable-keyed overlays
    private val registeredOverlays = LinkedHashMap<String, Overlay>()
    private var hillshadeOverlay: TilesOverlay? = null
    private var contourOverlay: TilesOverlay? = null
    private val accessFolderOverlay = FolderOverlay().apply { name = KEY_ACCESS }
    private val waterLayerOverlay = WaterLayerOverlay().apply { name = KEY_WATER }
    private val markedRoutesBatchOverlay = MarkedRoutesBatchOverlay(
        onRouteHit = { hitItem -> handleMarkedRouteTapped(hitItem.osmRelationId) }
    )
    private val routeFolderOverlay = FolderOverlay().apply { name = KEY_ROUTE }
    private var poiClusterOverlay: PoiClusterOverlay? = null

    // Data caches & signatures to avoid recreating markers/polygons unless data changed
    private var cachedShelters: List<ShelterEntity> = emptyList()
    private var cachedFriTeltning: List<FriTeltningEntity> = emptyList()
    private var cachedPois: List<PoiEntity> = emptyList()
    private var cachedCustomWaypoints: List<UserCustomWaypointEntity> = emptyList()
    private var cachedMarkedRouteSnapshots: List<MarkedRouteRenderItem> = emptyList()
    private var cachedAccessPolygons: List<AccessPolygon> = emptyList()
    private var cachedWaterFeatures: List<WaterFeatureDto> = emptyList()

    private var lastPoiSignature: Int = 0
    private var lastAccessSignature: Int = 0

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
    private var lastFetchedWasHighDetailZoom: Boolean? = null
    private var lastAccessBoundsKey: String? = null
    private var lastWaterBoundsKey: String? = null
    private var lastInvalidationElapsedMs: Long = 0L

    private val _layerErrorMessages = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val layerErrorMessages: kotlinx.coroutines.flow.StateFlow<Set<String>> = _layerErrorMessages

    private val _transientLayerMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val transientLayerMessage: kotlinx.coroutines.flow.StateFlow<String?> = _transientLayerMessage

    private val _transientLayerMessageVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val transientLayerMessageVersion: kotlinx.coroutines.flow.StateFlow<Long> = _transientLayerMessageVersion

    fun clearTransientLayerMessage() {
        _transientLayerMessage.value = null
    }

    fun setMarkedRouteTapsEnabled(enabled: Boolean) {
        markedRoutesBatchOverlay.tapsEnabled = enabled
    }

    fun isMarkedRouteTapsEnabled(): Boolean = markedRoutesBatchOverlay.tapsEnabled

    fun setLayerError(layerLabel: String, hasError: Boolean) {
        val msg = com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_err_fetch_fmt, layerLabel)
        val current = _layerErrorMessages.value.toMutableSet()
        if (hasError) {
            current.add(msg)
        } else {
            current.remove(msg)
        }
        _layerErrorMessages.value = current
    }

    companion object {
        const val KEY_TERRAIN_HILLSHADE = "terrain_hillshade"
        const val KEY_TERRAIN_CONTOURS = "terrain_contours"
        const val KEY_ACCESS = "access"
        const val KEY_RESTRICTED_AREAS = "restricted_areas"
        const val KEY_WATER = "water"
        const val KEY_MARKED_ROUTES = "marked_routes"
        const val KEY_ROUTE = "route"
        const val KEY_POIS = "pois"
        const val KEY_DROP_PIN = "drop_pin"
        const val KEY_LOCATION = "location"
        const val KEY_SCALE_BAR = "scale_bar"
        const val KEY_PLANNER_PIN_DRAG = "planner_pin_drag"
        const val KEY_EVENTS = "events"

        const val WATER_DEBOUNCE_MS = 600L
        const val CAMERA_DEBOUNCE_MS = 320L

        val EXPIRED_MARKED_ROUTE_MESSAGE: String
            get() = com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.map_layer_expired_marked_route_msg)
                ?: "Ruten er ikke længere gemt. Zoom ind for at hente den igen."
    }

    private fun handleMarkedRouteTapped(osmRelationId: Long) {
        // Ignore further taps on the same route while its load is in progress
        if (!loadingTappedRouteIds.add(osmRelationId)) {
            return
        }
        scope.launch {
            try {
                val fullDto = withContext(Dispatchers.IO) {
                    markedRouteRepository.getFullMarkedRouteById(osmRelationId)
                }
                if (fullDto != null) {
                    _transientLayerMessage.value = null
                    onMarkedRouteTapped?.invoke(fullDto)
                } else {
                    _transientLayerMessage.value = EXPIRED_MARKED_ROUTE_MESSAGE
                    _transientLayerMessageVersion.value = _transientLayerMessageVersion.value + 1L
                }
            } finally {
                loadingTappedRouteIds.remove(osmRelationId)
            }
        }
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
        registerOverlay(KEY_RESTRICTED_AREAS, restrictedAreasFolderOverlay)
        registerOverlay(KEY_WATER, waterLayerOverlay)
        registerOverlay(KEY_MARKED_ROUTES, markedRoutesBatchOverlay)
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
                val newZoom = event?.zoomLevel ?: map.zoomLevelDouble
                updateRestrictedAreasLayer(newZoom)
                val isHighDetailNow = newZoom >= 12.0
                val prevHighDetail = lastFetchedWasHighDetailZoom
                if (currentState.showMarkedRoutes && prevHighDetail != null && prevHighDetail != isHighDetailNow) {
                    // When zoom crosses 12.0 (either direction), force a refresh even if bounds key is unchanged
                    lastFetchedBoundsKey = null
                    fetchMarkedRoutesForCurrentViewport()
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
        val folderItems = folder.items ?: return
        folderItems.clear()
        folderItems.addAll(children)
        requestMapInvalidate()
    }

    fun getOverlay(key: String): Overlay? = registeredOverlays[key]

    fun getRouteFolderOverlay(): FolderOverlay = routeFolderOverlay

    fun getMarkedRoutesBatchOverlay(): MarkedRoutesBatchOverlay = markedRoutesBatchOverlay

    private fun canonicalOrderIndex(key: String): Int = when (key) {
        KEY_EVENTS -> 0
        KEY_TERRAIN_HILLSHADE -> 10
        KEY_TERRAIN_CONTOURS -> 11
        KEY_ACCESS -> 20
        KEY_RESTRICTED_AREAS -> 22
        KEY_WATER -> 25
        KEY_MARKED_ROUTES -> 30
        KEY_ROUTE -> 40
        KEY_POIS -> 50
        KEY_DROP_PIN -> 60
        KEY_LOCATION -> 70
        KEY_SCALE_BAR -> 80
        KEY_PLANNER_PIN_DRAG -> 90
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

        val hasKey = DanskStiTileSource.hasValidKey
        val effectiveBaseMap = if (!hasKey &&
            (state.baseMap == com.example.data.local.BaseMapType.SKAERMKORT || state.baseMap == com.example.data.local.BaseMapType.LUFTFOTO)
        ) {
            com.example.data.local.BaseMapType.FRILUFTSKORT
        } else {
            state.baseMap
        }
        val effectiveShowTerrain = hasKey && state.showTerrain

        // 1. Base map & dark mode
        if (lastAppliedBaseMap != effectiveBaseMap || lastAppliedDark != isDark) {
            lastAppliedBaseMap = effectiveBaseMap
            lastAppliedDark = isDark
            currentIsDark = isDark
            DanskStiTileSource.applyBaseMapTo(map, effectiveBaseMap, isDark)
        }

        // 2. Terrain overlay ("Terræn": hillshade + contours from zoom 14)
        hillshadeOverlay?.let {
            it.isEnabled = effectiveShowTerrain
            DanskStiTileSource.updateHillshadeDarkMode(it, isDark)
        }
        contourOverlay?.let {
            it.isEnabled = effectiveShowTerrain
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
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_water), false)
        }

        // 4. Rebuild POI cluster items only if toggles or POI lists changed
        rebuildPoiClusterItemsIfChanged()

        // 5. Marked routes ("Afmærkede ruter")
        markedRoutesBatchOverlay.isEnabled = state.showMarkedRoutes
        if (!state.showMarkedRoutes) {
            markedRouteFetchJob?.cancel()
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_marked_routes), false)
        } else if (!prevState.showMarkedRoutes || cachedMarkedRouteSnapshots.isEmpty()) {
            lastFetchedBoundsKey = null
            fetchMarkedRoutesForCurrentViewport()
        }

        // 6b. Military areas & wildlife reserves ("Sikkerhed", from zoom 9)
        updateRestrictedAreasLayer(map.zoomLevelDouble)

        // 6. Access polygons ("Adgang og ejerskab")
        accessFolderOverlay.isEnabled = state.showAccessLand
        if (!state.showAccessLand) {
            accessFetchJob?.cancel()
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_access), false)
        } else if (!prevState.showAccessLand || cachedAccessPolygons.isEmpty()) {
            lastAccessBoundsKey = null
            fetchAccessLandForCurrentViewport()
        }

        requestMapInvalidate()
    }

    /**
     * Debounced 600 ms after the map stops moving; cancels the previous request.
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
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_water), false)
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
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_water), isLiveZoom && features.isEmpty() && waterLayerRepository.lastFetchFailed)
            val density = context.resources.displayMetrics.density
            val changed = waterLayerOverlay.updateFeatures(features, currentIsDark, density)
            if (changed) {
                requestMapInvalidate()
            }
        }
    }

    fun fetchMarkedRoutesForCurrentViewport() {
        val map = mapView ?: return
        val zoom = map.zoomLevelDouble

        val box = map.boundingBox
        val minLat = (box?.latSouth ?: 54.5).coerceIn(54.4, 58.0)
        val maxLat = (box?.latNorth ?: 58.0).coerceIn(54.4, 58.0).let { if (it <= minLat) 58.0 else it }
        val minLng = (box?.lonWest ?: 8.0).coerceIn(7.8, 15.5)
        val maxLng = (box?.lonEast ?: 15.5).coerceIn(7.8, 15.5).let { if (it <= minLng) 15.5 else it }

        val isLiveZoom = zoom >= 10.0
        val isHighDetailZoom = zoom >= 12.0
        val prevHighDetail = lastFetchedWasHighDetailZoom
        val crossedZoom12 = prevHighDetail != null && prevHighDetail != isHighDetailZoom

        val boundsKey = "${isLiveZoom}_${isHighDetailZoom}_${(minLat * 5).toInt()}_${(maxLat * 5).toInt()}_${(minLng * 5).toInt()}_${(maxLng * 5).toInt()}"
        if (!crossedZoom12 && boundsKey == lastFetchedBoundsKey && cachedMarkedRouteSnapshots.isNotEmpty()) {
            return
        }
        lastFetchedBoundsKey = boundsKey
        lastFetchedWasHighDetailZoom = isHighDetailZoom

        markedRouteFetchJob?.cancel()
        markedRouteFetchJob = scope.launch {
            val snapshots = withContext(Dispatchers.IO) {
                markedRouteRepository.getMarkedRouteRenderSnapshots(
                    minLat = minLat,
                    maxLat = maxLat,
                    minLng = minLng,
                    maxLng = maxLng,
                    zoomLevel = zoom
                )
            }
            ensureActive()
            cachedMarkedRouteSnapshots = snapshots
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_marked_routes), isLiveZoom && snapshots.isEmpty() && markedRouteRepository.lastFetchFailed)
            // Deliver immutable render snapshot on the main thread
            withContext(Dispatchers.Main.immediate) {
                markedRoutesBatchOverlay.setRoutesSnapshot(snapshots)
                requestMapInvalidate()
            }
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
            setLayerError(com.example.util.AppLocaleHelper.str(context, com.example.R.string.map_layer_name_access), isLiveZoom && polygons.isEmpty() && AccessLandRepository.isAccessDataUnavailable)
            rebuildAccessOverlaysIfChanged()
        }
    }

    private fun rebuildAccessOverlaysIfChanged() {
        if (!currentState.showAccessLand) return
        val map = mapView ?: return
        val accessItems = accessFolderOverlay.items ?: return
        var sig = cachedAccessPolygons.size
        for (p in cachedAccessPolygons) {
            sig = 31 * sig + p.id.hashCode()
        }
        if (sig == lastAccessSignature && (cachedAccessPolygons.isEmpty() == accessItems.isEmpty())) {
            return
        }
        lastAccessSignature = sig
        accessItems.clear()
        val osmdroidPolygons = cachedAccessPolygons.map { poly ->
            AccessMapOverlayHelper.createAccessPolygonOverlay(
                mapView = map,
                polygon = poly,
                onPolygonClicked = { clickedPoly -> onAccessPolygonTapped?.invoke(clickedPoly) }
            )
        }
        accessItems.addAll(osmdroidPolygons)
        requestMapInvalidate()
    }

    /** Phase D3: enables the layer for Sikkerhed at zoom >= 9 and loads the national areas once. */
    private fun updateRestrictedAreasLayer(zoom: Double) {
        val shouldShow = currentState.showSafety && zoom >= RestrictedAreaOverlayHelper.MIN_ZOOM
        if (restrictedAreasFolderOverlay.isEnabled != shouldShow) {
            restrictedAreasFolderOverlay.isEnabled = shouldShow
            requestMapInvalidate()
        }
        if (!shouldShow || restrictedAreasLoaded || restrictedAreasJob?.isActive == true) return
        restrictedAreasJob = scope.launch {
            val areas = withContext(Dispatchers.IO) { restrictedAreaRepository.getAreas() }
            ensureActive()
            val map = mapView ?: return@launch
            val items = restrictedAreasFolderOverlay.items ?: return@launch
            items.clear()
            areas.forEach { area ->
                items.addAll(RestrictedAreaOverlayHelper.createPolygons(map, area) { tapped -> onRestrictedAreaTapped?.invoke(tapped) })
            }
            restrictedAreasLoaded = areas.isNotEmpty()
            requestMapInvalidate()
        }
    }

    private var cachedPlannerPois: List<com.example.ui.viewmodel.PlannerMapPoi> = emptyList()

    /**
     * Updates planner-fetched POIs on the shared POI cluster layer,
     * deduplicated against the map's shelter/POI layers so there is only one marker per place.
     */
    fun setPlannerPois(plannerPois: List<com.example.ui.viewmodel.PlannerMapPoi>) {
        if (cachedPlannerPois == plannerPois) return
        cachedPlannerPois = plannerPois
        rebuildPoiClusterItemsIfChanged()
    }

    private fun rebuildPoiClusterItemsIfChanged() {
        val overlay = poiClusterOverlay ?: return
        val sig = MapLayersPoiBuilder.computePoiSignature(
            state = currentState,
            shelters = cachedShelters,
            friTeltning = cachedFriTeltning,
            pois = cachedPois,
            customWaypoints = cachedCustomWaypoints,
            plannerPois = cachedPlannerPois
        )
        if (sig == lastPoiSignature) return
        lastPoiSignature = sig

        val items = MapLayersPoiBuilder.buildClusterItems(
            state = currentState,
            shelters = cachedShelters,
            friTeltning = cachedFriTeltning,
            pois = cachedPois,
            customWaypoints = cachedCustomWaypoints,
            plannerPois = cachedPlannerPois
        )

        if (overlay.setItems(items)) {
            requestMapInvalidate()
        }
    }

    private fun requestMapInvalidate() {
        val map = mapView ?: return
        lastInvalidationElapsedMs = SystemClock.uptimeMillis()
        map.postInvalidate()
    }

    /**
     * Telemetry getter: number of marked routes drawn in the last frame.
     */
    fun getLastFrameMarkedRoutesDrawn(): Int = markedRoutesBatchOverlay.lastFrameRoutesDrawn

    /**
     * Telemetry getter: number of marked route vertices drawn in the last frame.
     */
    fun getLastFrameMarkedVerticesDrawn(): Int = markedRoutesBatchOverlay.lastFrameVerticesDrawn

    fun getActivePoiItemCount(): Int = poiClusterOverlay?.getItemCount() ?: 0

    fun getActivePoiItems(): List<PoiItem> = poiClusterOverlay?.getItems().orEmpty()

    fun getActiveWaterFeatureCount(): Int = waterLayerOverlay.currentFeatureCount

    /**
     * If a shelter, fri teltning area, water, toilet, or shop is within [maxDistanceMeters] (30 m)
     * or the finger is on its marker, return its [PoiItem] so the caller can open [PoiInfoSheet].
     */
    fun findLongPressPoi(point: GeoPoint, maxDistanceMeters: Double = 30.0): PoiItem? {
        return MapLayersPoiBuilder.findLongPressPoi(
            point = point,
            maxDistanceMeters = maxDistanceMeters,
            poiClusterOverlay = poiClusterOverlay,
            mapView = mapView,
            cachedShelters = cachedShelters,
            cachedFriTeltning = cachedFriTeltning,
            cachedPois = cachedPois
        )
    }
}
