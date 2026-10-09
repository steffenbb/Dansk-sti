package com.example.ui.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNoteEntity
import com.example.service.NavigationLiveState
import com.example.util.GeoUtils
import com.example.util.MapIconUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/**
 * High-performance, lifecycle-safe overlay manager for OpenStreetMap (osmdroid).
 * Requirement D & B:
 * - Persistent overlays, NEVER clears overlays on GPS tick.
 * - Route line: 10 dp white casing underneath, 6 dp #1E88E5 on top. Walked: 6 dp grey.
 * - Small direction chevron every 250 m.
 * - Ferry segments: dashed blue line with ferry marker and text "Færge - tjek sejlplan".
 * - Tonight camp: thin dashed line when detour > 50 m.
 * - Clustered POIs (Shelters, fri teltning, water, toilets, shops, transit).
 * - Fri teltning polygons draw only at zoom 11 and above.
 */
class NavigationMapOverlayManager(
    private val mapView: MapView,
    private val context: Context,
    private val onSelectPoi: (Any) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    // Permanent Route Overlays
    private val casingPolyline = Polyline(mapView).apply {
        outlinePaint.color = Color.WHITE
        outlinePaint.strokeWidth = dp(10f)
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    }

    private val routePolyline = Polyline(mapView).apply {
        outlinePaint.color = Color.parseColor("#1E88E5") // Strong Nordic Blue
        outlinePaint.strokeWidth = dp(6f)
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    }

    private val walkedPolyline = Polyline(mapView).apply {
        outlinePaint.color = Color.parseColor("#888888") // 6 dp grey
        outlinePaint.strokeWidth = dp(6f)
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    }

    // Ferry & Detour Overlays
    private val ferryOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
    private val chevronMarkers = mutableListOf<Marker>()

    private val campDetourPolyline = Polyline(mapView).apply {
        outlinePaint.color = Color.parseColor("#E65100")
        outlinePaint.strokeWidth = dp(3f)
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }

    private val campDetourMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }

    private val tonightCampMarker = Marker(mapView).apply {
        icon = MapIconUtils.getNightCampIcon(context)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "I nat"
    }

    // Dynamic POI & Polygon overlays
    private val friTeltningPolygons = mutableListOf<Polygon>()
    private val hazardMarkers = mutableListOf<Marker>()

    // Unified Clustered POI Layer (Requirement B)
    private val poiClusterOverlay = PoiClusterOverlay(context) { mapPoi ->
        if (mapPoi.payload != null) {
            onSelectPoi(mapPoi.payload)
        }
    }

    // GPS accuracy circle (> 15m)
    private val accuracyPolygon = Polygon(mapView).apply {
        outlinePaint.color = Color.parseColor("#4442A5F5")
        outlinePaint.strokeWidth = dp(1.5f)
        fillPaint.color = Color.parseColor("#2242A5F5")
    }

    // User position marker (ic_user_heading)
    private val userMarker = Marker(mapView).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_user_heading)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "Din position"
    }

    private var currentRouteId: Long = -1L
    private var allRouteGeoPoints: List<GeoPoint> = emptyList()
    private var routePointsData: List<RoutePoint> = emptyList()

    init {
        // Add permanent base overlays to mapView in bottom-to-top layer order
        mapView.overlays.add(casingPolyline)
        mapView.overlays.add(routePolyline)
        mapView.overlays.add(walkedPolyline)
        mapView.overlays.add(campDetourPolyline)
        mapView.overlays.add(campDetourMarker)
        mapView.overlays.add(tonightCampMarker)
        mapView.overlays.add(poiClusterOverlay)
        mapView.overlays.add(accuracyPolygon)
        mapView.overlays.add(userMarker)
    }

    /**
     * Configures the planned route polylines and 250m chevrons once when the route is loaded.
     */
    fun setPlannedRoute(route: PlannedRoute) {
        if (currentRouteId == route.planId && allRouteGeoPoints.isNotEmpty()) {
            return
        }
        currentRouteId = route.planId
        routePointsData = route.points

        val geoPoints = route.points.map { GeoPoint(it.lat, it.lng) }
        allRouteGeoPoints = geoPoints

        casingPolyline.setPoints(geoPoints)
        routePolyline.setPoints(geoPoints)
        walkedPolyline.setPoints(emptyList())

        // 1. Build small direction chevron every 250 m
        chevronMarkers.forEach { mapView.overlays.remove(it) }
        chevronMarkers.clear()

        val chevronIcon = ContextCompat.getDrawable(context, R.drawable.ic_chevron_dir)
        var lastChevronKm = 0.0

        for (i in 0 until route.points.size - 1) {
            val pt = route.points[i]
            val nextPt = route.points[i + 1]
            if (pt.walkKm - lastChevronKm >= 0.25) { // every 250m
                lastChevronKm = pt.walkKm
                val bearing = GeoUtils.bearing(pt.lat, pt.lng, nextPt.lat, nextPt.lng)
                val chevron = Marker(mapView).apply {
                    position = GeoPoint(pt.lat, pt.lng)
                    icon = chevronIcon
                    rotation = bearing
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                }
                chevronMarkers.add(chevron)
                val insertIdx = mapView.overlays.indexOf(routePolyline) + 1
                mapView.overlays.add(insertIdx.coerceAtMost(mapView.overlays.size), chevron)
            }
        }

        // 2. Build Ferry Segments (dashed blue line + text)
        ferryOverlays.forEach { mapView.overlays.remove(it) }
        ferryOverlays.clear()

        val ferrySegments = mutableListOf<MutableList<RoutePoint>>()
        var curFerrySeg = mutableListOf<RoutePoint>()

        for (pt in route.points) {
            if (pt.segmentType == TrailSegmentType.FERRY) {
                curFerrySeg.add(pt)
            } else {
                if (curFerrySeg.size >= 2) {
                    ferrySegments.add(curFerrySeg)
                }
                curFerrySeg = mutableListOf()
            }
        }
        if (curFerrySeg.size >= 2) {
            ferrySegments.add(curFerrySeg)
        }

        for (fSeg in ferrySegments) {
            val ferryLine = Polyline(mapView).apply {
                outlinePaint.color = Color.parseColor("#0288D1")
                outlinePaint.strokeWidth = dp(4f)
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dp(12f), dp(8f)), 0f)
                setPoints(fSeg.map { GeoPoint(it.lat, it.lng) })
            }
            ferryOverlays.add(ferryLine)
            mapView.overlays.add(mapView.overlays.indexOf(routePolyline) + 1, ferryLine)

            val midPt = fSeg[fSeg.size / 2]
            val ferryMarker = Marker(mapView).apply {
                position = GeoPoint(midPt.lat, midPt.lng)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_poi_transit)
                title = "Færge - tjek sejlplan"
                subDescription = "Færgeforbindelse på ruten"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            ferryOverlays.add(ferryMarker)
            mapView.overlays.add(ferryMarker)
        }

        mapView.invalidate()
    }

    /**
     * Updates tonight's camp marker and draws thin dashed detour line if detour > 50 m.
     */
    fun updateTonightCamp(stage: TripStage?, allPoints: List<GeoPointDto>) {
        if (stage == null || stage.stopLat == 0.0) {
            tonightCampMarker.isEnabled = false
            campDetourPolyline.setPoints(emptyList())
            campDetourMarker.isEnabled = false
            return
        }

        val campPt = GeoPointDto(stage.stopLat, stage.stopLng)
        val routePts = if (allPoints.isNotEmpty()) allPoints else routePointsData.map { GeoPointDto(it.lat, it.lng) }

        tonightCampMarker.isEnabled = true
        tonightCampMarker.position = GeoPoint(campPt.lat, campPt.lng)
        tonightCampMarker.title = "⛺ ${stage.stopName}"
        tonightCampMarker.subDescription = "Dagens mål (Dag ${stage.dayNumber}) • ${String.format(Locale.getDefault(), "%.1f", stage.stageKm)} km i dag"
        tonightCampMarker.setOnMarkerClickListener { _, _ ->
            onSelectPoi(stage)
            true
        }

        // Check detour from route
        if (routePts.isNotEmpty()) {
            val snapped = GeoUtils.snapPointToPolyline(campPt, routePts)
            val detourM = GeoUtils.distanceMeters(campPt, snapped)

            if (detourM > 50.0) {
                campDetourPolyline.setPoints(listOf(GeoPoint(snapped.lat, snapped.lng), GeoPoint(campPt.lat, campPt.lng)))
                campDetourMarker.isEnabled = true
                campDetourMarker.position = GeoPoint((campPt.lat + snapped.lat) / 2.0, (campPt.lng + snapped.lng) / 2.0)
                campDetourMarker.title = "Afstikker til lejr: ${detourM.toInt()} m"
                campDetourMarker.icon = MapIconUtils.getCrosshairIcon(context)
            } else {
                campDetourPolyline.setPoints(emptyList())
                campDetourMarker.isEnabled = false
            }
        }
    }

    /**
     * Updates POIs using the unified Clustered POI Layer (Requirement B).
     * Fri teltning polygons draw only at zoom 11 and above.
     */
    fun updatePois(
        shelters: List<ShelterEntity>,
        showShelters: Boolean,
        toilets: List<PoiEntity>,
        showToilets: Boolean,
        waters: List<PoiEntity>,
        showWater: Boolean,
        hazards: List<UserHazardNoteEntity>,
        showHazards: Boolean,
        parsedFriTeltning: List<Pair<FriTeltningEntity, List<GeoPointDto>>>,
        showFriTeltning: Boolean
    ) {
        val zoom = mapView.zoomLevelDouble

        // 1. Fri Teltning Polygons (only at zoom 11 and above)
        friTeltningPolygons.forEach { mapView.overlays.remove(it) }
        friTeltningPolygons.clear()

        if (showFriTeltning && zoom >= 11.0) {
            parsedFriTeltning.forEach { (ft, boundaryPts) ->
                if (boundaryPts.size >= 3) {
                    val polygon = Polygon(mapView).apply {
                        points = boundaryPts.map { GeoPoint(it.lat, it.lng) }
                        fillPaint.color = Color.parseColor("#332E7D32")
                        outlinePaint.color = Color.parseColor("#802E7D32")
                        outlinePaint.strokeWidth = dp(1.5f)
                        title = ft.name
                        subDescription = "Fri teltning (${ft.forestType})"
                        setOnClickListener { _, _, _ ->
                            onSelectPoi(ft)
                            true
                        }
                    }
                    friTeltningPolygons.add(polygon)
                    mapView.overlays.add(0, polygon) // Add behind routes
                }
            }
        }

        // 2. Build items for Unified Clustered Layer
        val clusterItems = mutableListOf<MapPoiItem>()

        if (showShelters) {
            shelters.forEach { sh ->
                clusterItems.add(
                    MapPoiItem(
                        id = sh.id,
                        type = "shelter",
                        name = sh.name,
                        subText = "${sh.ownership} • Plads: ${sh.capacity} • ${if (sh.bookingRequired) "Kræver booking" else "Fri afbenyttelse"}",
                        lat = sh.lat,
                        lng = sh.lng,
                        payload = sh
                    )
                )
            }
        }

        if (showFriTeltning) {
            parsedFriTeltning.forEach { (ft, _) ->
                clusterItems.add(
                    MapPoiItem(
                        id = ft.id,
                        type = "tent",
                        name = ft.name,
                        subText = "${ft.zoneType} • Fri teltning tilladt",
                        lat = ft.centerLat,
                        lng = ft.centerLng,
                        payload = ft
                    )
                )
            }
        }

        if (showToilets) {
            toilets.forEach { t ->
                clusterItems.add(
                    MapPoiItem(
                        id = t.id,
                        type = "toilet",
                        name = t.name,
                        subText = "Offentligt toilet (${t.access})",
                        lat = t.lat,
                        lng = t.lng,
                        payload = t
                    )
                )
            }
        }

        if (showWater) {
            waters.forEach { w ->
                clusterItems.add(
                    MapPoiItem(
                        id = w.id,
                        type = "water",
                        name = w.name,
                        subText = "Drikkevandspost",
                        lat = w.lat,
                        lng = w.lng,
                        payload = w
                    )
                )
            }
        }

        poiClusterOverlay.setItems(clusterItems)

        // 3. Hazards (kept as individual markers for immediate safety visibility)
        hazardMarkers.forEach { mapView.overlays.remove(it) }
        hazardMarkers.clear()
        if (showHazards) {
            hazards.forEach { h ->
                val m = Marker(mapView).apply {
                    position = GeoPoint(h.lat, h.lng)
                    icon = MapIconUtils.getHazardIcon(context)
                    title = h.title
                    subDescription = h.description
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onSelectPoi(h)
                        true
                    }
                }
                hazardMarkers.add(m)
                mapView.overlays.add(m)
            }
        }

        mapView.invalidate()
    }

    /**
     * FAST Location Tick: updates user marker, heading, walked polyline, and accuracy circle.
     * NEVER calls overlays.clear()!
     */
    fun onLocationTick(state: NavigationLiveState, followUser: Boolean) {
        val lat = state.currentLat
        val lng = state.currentLng

        if (lat == null || lng == null) {
            userMarker.isEnabled = false
            accuracyPolygon.points = emptyList()
            return
        }

        userMarker.isEnabled = true
        userMarker.position = GeoPoint(lat, lng)
        userMarker.rotation = state.bearing

        // Accuracy circle when accuracy > 15m
        if (state.accuracyM > 15f) {
            accuracyPolygon.points = Polygon.pointsAsCircle(userMarker.position, state.accuracyM.toDouble())
        } else {
            accuracyPolygon.points = emptyList()
        }

        // Update walked polyline (6 dp grey) up to current position
        if (routePointsData.isNotEmpty() && state.totalDistanceWalkedKm > 0.0) {
            val walkedPts = mutableListOf<GeoPoint>()
            for (pt in routePointsData) {
                if (pt.walkKm <= state.totalDistanceWalkedKm) {
                    walkedPts.add(GeoPoint(pt.lat, pt.lng))
                } else {
                    break
                }
            }
            if (state.currentSnappedPoint != null) {
                walkedPts.add(GeoPoint(state.currentSnappedPoint.lat, state.currentSnappedPoint.lng))
            }
            walkedPolyline.setPoints(walkedPts)
        }

        if (followUser) {
            mapView.controller.animateTo(userMarker.position)
        }

        mapView.invalidate()
    }
}
