package com.example.ui.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.data.local.MapLayersState
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.repository.AccessLandRepository
import com.example.data.repository.DanskStiRepository
import com.example.util.DanskStiTileSourceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Phase 13 Overlay Manager:
 * Coordinates all map layer overlays (marked routes, access rules, shelters, fri teltning, POIs, user waypoints)
 * and base map switching for any MapView in Dansk Sti.
 */
class MapLayersOverlayManager(
    private val mapView: MapView,
    private val context: Context,
    private val repository: DanskStiRepository,
    private val coroutineScope: CoroutineScope,
    private val onRouteTapped: (MarkedRouteDto) -> Unit = {},
    private val onShelterTapped: (ShelterEntity) -> Unit = {},
    private val onFriTeltningTapped: (FriTeltningEntity) -> Unit = {},
    private val onPoiTapped: (PoiEntity) -> Unit = {},
    private val onWaypointTapped: (UserCustomWaypointEntity) -> Unit = {}
) {
    companion object {
        private const val TAG = "MapLayersOverlayMgr"
    }

    private val markedRoutesFolder = FolderOverlay()
    private val accessFolder = FolderOverlay()
    private val sheltersFolder = FolderOverlay()
    private val friTeltningFolder = FolderOverlay()
    private val poiFolder = FolderOverlay()
    private val myPointsFolder = FolderOverlay()

    private var currentLayersState: MapLayersState = MapLayersState()
    private var isDarkMode: Boolean = false
    private var fetchJob: Job? = null

    private var lastQueriedBbox: BoundingBox? = null
    private var lastQueriedZoom: Double = 0.0

    init {
        // Add folders to map overlays in layered order (polygons at bottom, lines in middle, markers on top)
        mapView.overlays.add(accessFolder)
        mapView.overlays.add(markedRoutesFolder)
        mapView.overlays.add(sheltersFolder)
        mapView.overlays.add(friTeltningFolder)
        mapView.overlays.add(poiFolder)
        mapView.overlays.add(myPointsFolder)
    }

    /**
     * Updates map layers according to user preferences and triggers data sync.
     */
    fun updateState(layersState: MapLayersState, darkMode: Boolean = false) {
        val baseMapChanged = currentLayersState.baseMap != layersState.baseMap || this.isDarkMode != darkMode
        currentLayersState = layersState
        this.isDarkMode = darkMode

        if (baseMapChanged) {
            DanskStiTileSourceHelper.applyTo(mapView, layersState.baseMap, isDarkMode)
        }

        // Toggle folder visibility
        accessFolder.isEnabled = layersState.showAccess
        markedRoutesFolder.isEnabled = layersState.showMarkedRoutes
        sheltersFolder.isEnabled = layersState.showShelters
        friTeltningFolder.isEnabled = layersState.showFriTeltning
        poiFolder.isEnabled = layersState.showWater || layersState.showToilets || layersState.showShops || layersState.showTransport
        myPointsFolder.isEnabled = layersState.showMyPoints

        refreshOverlaysForCurrentView()
    }

    /**
     * Called on map pan/zoom changes to fetch overlays inside visible area.
     */
    fun onMapMoved() {
        val zoom = mapView.zoomLevelDouble
        val bbox = mapView.boundingBox ?: return

        // Throttle updates if viewport has not meaningfully changed
        val prevBbox = lastQueriedBbox
        if (prevBbox != null && kotlin.math.abs(zoom - lastQueriedZoom) < 0.3) {
            val dLat = kotlin.math.abs(bbox.centerLatitude - prevBbox.centerLatitude)
            val dLng = kotlin.math.abs(bbox.centerLongitude - prevBbox.centerLongitude)
            if (dLat < 0.02 && dLng < 0.02) {
                return
            }
        }

        refreshOverlaysForCurrentView()
    }

    private fun refreshOverlaysForCurrentView() {
        val zoom = mapView.zoomLevelDouble
        val bbox = mapView.boundingBox ?: return

        lastQueriedBbox = bbox
        lastQueriedZoom = zoom

        fetchJob?.cancel()
        fetchJob = coroutineScope.launch {
            val minLat = bbox.latSouth
            val maxLat = bbox.latNorth
            val minLng = bbox.lonWest
            val maxLng = bbox.lonEast

            // 1. Marked routes (zoom 10+ only)
            if (currentLayersState.showMarkedRoutes && zoom >= 10.0) {
                try {
                    val routes = repository.getMarkedRoutes(minLat, maxLat, minLng, maxLng, zoom)
                    renderMarkedRoutes(routes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load marked routes: ${e.message}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    markedRoutesFolder.items.clear()
                    mapView.invalidate()
                }
            }

            // 2. Shelters & Fri teltning
            if (currentLayersState.showShelters || currentLayersState.showFriTeltning) {
                try {
                    val allShelters = if (currentLayersState.showShelters) {
                        repository.getAllSheltersList().filter {
                            it.lat in minLat..maxLat && it.lng in minLng..maxLng
                        }
                    } else emptyList()

                    val allFriTeltning = if (currentLayersState.showFriTeltning) {
                        repository.getAllFriTeltningList().filter {
                            it.centerLat in minLat..maxLat && it.centerLng in minLng..maxLng
                        }
                    } else emptyList()

                    renderSheltersAndFriTeltning(allShelters, allFriTeltning)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load overnight spots: ${e.message}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    sheltersFolder.items.clear()
                    friTeltningFolder.items.clear()
                    mapView.invalidate()
                }
            }

            // 3. POIs (Water, Toilets, Shops, Transport)
            if (currentLayersState.showWater || currentLayersState.showToilets ||
                currentLayersState.showShops || currentLayersState.showTransport
            ) {
                try {
                    val pois = repository.getAllPoisList().filter { poi ->
                        val matchesType = when (poi.type) {
                            "water" -> currentLayersState.showWater
                            "toilet" -> currentLayersState.showToilets
                            "shop", "supermarket", "convenience", "bakery" -> currentLayersState.showShops
                            "station", "ferry", "bus_stop" -> currentLayersState.showTransport
                            else -> false
                        }
                        matchesType && poi.lat in minLat..maxLat && poi.lng in minLng..maxLng
                    }
                    renderPois(pois)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load POIs: ${e.message}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    poiFolder.items.clear()
                    mapView.invalidate()
                }
            }

            // 4. Access polygons (zoom 12+ for performance)
            if (currentLayersState.showAccess && zoom >= 11.5) {
                try {
                    val polygons = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                    renderAccessPolygons(polygons)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load access polygons: ${e.message}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    accessFolder.items.clear()
                    mapView.invalidate()
                }
            }

            // 5. User Custom Waypoints
            if (currentLayersState.showMyPoints) {
                try {
                    val waypoints = repository.getAllCustomWaypoints().filter {
                        it.lat in minLat..maxLat && it.lng in minLng..maxLng
                    }
                    renderCustomWaypoints(waypoints)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load custom waypoints: ${e.message}")
                }
            } else {
                withContext(Dispatchers.Main) {
                    myPointsFolder.items.clear()
                    mapView.invalidate()
                }
            }
        }
    }

    private suspend fun renderMarkedRoutes(routes: List<MarkedRouteDto>) = withContext(Dispatchers.Main) {
        markedRoutesFolder.items.clear()

        for (route in routes) {
            val colorInt = try {
                Color.parseColor(route.colorHex)
            } catch (e: Exception) {
                Color.parseColor("#2E7D32")
            }

            for (pts in route.polylines) {
                if (pts.size < 2) continue
                val polyline = Polyline(mapView).apply {
                    setPoints(pts.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = colorInt
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    title = route.name
                    subDescription = "${route.lengthKm} km • ${route.displayOperator}"

                    setOnClickListener { _, _, _ ->
                        onRouteTapped(route)
                        true
                    }
                }
                markedRoutesFolder.add(polyline)
            }
        }
        mapView.invalidate()
    }

    private suspend fun renderAccessPolygons(polygons: List<AccessPolygon>) = withContext(Dispatchers.Main) {
        accessFolder.items.clear()

        for (poly in polygons) {
            if (poly.coordinates.size < 3) continue
            val osmpoly = Polygon(mapView).apply {
                points = poly.coordinates.map { GeoPoint(it.lat, it.lng) }
                when (poly.type) {
                    AccessLandType.PUBLIC_FOREST -> {
                        fillPaint.color = Color.argb(45, 46, 125, 50) // Soft green
                        outlinePaint.color = Color.argb(160, 46, 125, 50)
                        outlinePaint.strokeWidth = 2.5f
                    }
                    AccessLandType.PRIVATE_FOREST -> {
                        fillPaint.color = Color.argb(35, 230, 81, 0) // Soft amber/orange
                        outlinePaint.color = Color.argb(160, 230, 81, 0)
                        outlinePaint.strokeWidth = 2.5f
                    }
                    AccessLandType.BEACH -> {
                        fillPaint.color = Color.argb(45, 2, 136, 209) // Soft blue
                        outlinePaint.color = Color.argb(160, 2, 136, 209)
                        outlinePaint.strokeWidth = 2f
                    }
                    AccessLandType.UNCULTIVATED_LAND -> {
                        fillPaint.color = Color.argb(40, 121, 85, 72) // Earth/heath
                        outlinePaint.color = Color.argb(150, 121, 85, 72)
                        outlinePaint.strokeWidth = 2f
                    }
                    AccessLandType.CULTIVATED_FIELD -> {
                        fillPaint.color = Color.argb(20, 100, 100, 100)
                        outlinePaint.color = Color.argb(80, 100, 100, 100)
                        outlinePaint.strokeWidth = 1.5f
                    }
                }
                title = poly.name ?: poly.type.title
                subDescription = poly.type.summary
            }
            accessFolder.add(osmpoly)
        }
        mapView.invalidate()
    }

    private suspend fun renderSheltersAndFriTeltning(
        shelters: List<ShelterEntity>,
        friTeltning: List<FriTeltningEntity>
    ) = withContext(Dispatchers.Main) {
        sheltersFolder.items.clear()
        friTeltningFolder.items.clear()

        for (shelter in shelters) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(shelter.lat, shelter.lng)
                title = shelter.name
                snippet = "Shelter • ${shelter.ownership ?: "Offentlig"}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    onShelterTapped(shelter)
                    true
                }
            }
            sheltersFolder.add(marker)
        }

        for (ft in friTeltning) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(ft.centerLat, ft.centerLng)
                title = ft.name
                snippet = "Fri teltning • Naturstyrelsen (1-2-3 reglen)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    onFriTeltningTapped(ft)
                    true
                }
            }
            friTeltningFolder.add(marker)
        }
        mapView.invalidate()
    }

    private suspend fun renderPois(pois: List<PoiEntity>) = withContext(Dispatchers.Main) {
        poiFolder.items.clear()

        for (poi in pois) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(poi.lat, poi.lng)
                title = poi.name
                snippet = when (poi.type) {
                    "water" -> "Drikkevandspost"
                    "toilet" -> "Offentligt toilet"
                    "shop", "supermarket", "convenience" -> "Indkøb: ${poi.name}"
                    "station" -> "Togstation"
                    "bus_stop" -> "Busstoppested"
                    "ferry" -> "Færgeleje"
                    else -> poi.type
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    onPoiTapped(poi)
                    true
                }
            }
            poiFolder.add(marker)
        }
        mapView.invalidate()
    }

    private suspend fun renderCustomWaypoints(waypoints: List<UserCustomWaypointEntity>) = withContext(Dispatchers.Main) {
        myPointsFolder.items.clear()

        for (wp in waypoints) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(wp.lat, wp.lng)
                title = wp.name
                snippet = wp.note
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    onWaypointTapped(wp)
                    true
                }
            }
            myPointsFolder.add(marker)
        }
        mapView.invalidate()
    }
}
