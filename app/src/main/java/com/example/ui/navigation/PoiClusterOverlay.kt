package com.example.ui.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.view.MotionEvent
import com.example.util.MapIconUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.sqrt

data class MapPoiItem(
    val id: String,
    val type: String, // "shelter", "tent", "water", "toilet", "shop", "transit", "my_point"
    val name: String,
    val subText: String = "",
    val lat: Double,
    val lng: Double,
    val payload: Any? = null
)

typealias PoiItem = MapPoiItem

/**
 * Unified Clustered Layer for Dansk Sti (Requirement A):
 * - Shelters, fri teltning, water, toilets, shops, transit, and my points in one clustered layer.
 * - Below zoom 13 (CLUSTER_BELOW_ZOOM = 13.0): groups markers into a 60 dp grid and draws circle with count; tap zooms in.
 * - At zoom 13+: shows individual icons (32 dp, white circle background, type-specific color).
 * - Selected marker grows to 40 dp.
 */
class PoiClusterOverlay(
    private val context: Context,
    private var poiItems: List<MapPoiItem> = emptyList(),
    var selectedPoiId: String? = null,
    var onPoiClicked: ((MapPoiItem) -> Unit)? = null,
    onSinglePoiTap: ((MapPoiItem) -> Unit)? = null
) : Overlay() {

    init {
        if (onSinglePoiTap != null && onPoiClicked == null) {
            onPoiClicked = onSinglePoiTap
        }
    }

    companion object {
        const val CLUSTER_BELOW_ZOOM = 13.0
    }

    private data class ClusterBucket(
        val items: MutableList<MapPoiItem> = mutableListOf(),
        var sumX: Float = 0f,
        var sumY: Float = 0f,
        var sumLat: Double = 0.0,
        var sumLng: Double = 0.0,
        var screenX: Float = 0f,
        var screenY: Float = 0f,
        var radiusPx: Float = 0f
    )

    private val activeClusters = mutableListOf<ClusterBucket>()
    private val activeSingleItems = mutableListOf<Pair<MapPoiItem, Point>>()
    private val tempPoint = Point()
    private var lastSignature: Int = 0

    fun getItems(): List<MapPoiItem> = poiItems

    fun getItemCount(): Int = poiItems.size

    fun setItems(newItems: List<MapPoiItem>, newSelectedId: String? = selectedPoiId): Boolean {
        val sig = 31 * newItems.hashCode() + (newSelectedId?.hashCode() ?: 0)
        if (sig == lastSignature && newItems.size == poiItems.size && newSelectedId == selectedPoiId) {
            return false
        }
        synchronized(this) {
            lastSignature = sig
            poiItems = newItems
            selectedPoiId = newSelectedId
        }
        return true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return

        val zoom = mapView.zoomLevelDouble
        val projection = mapView.projection ?: return
        val density = context.resources.displayMetrics.density
        val clip = canvas.clipBounds
        val hasClip = clip.width() > 0 && clip.height() > 0
        val minScreenX = if (hasClip) clip.left.toFloat() else 0f
        val maxScreenX = if (hasClip) clip.right.toFloat() else mapView.width.toFloat().coerceAtLeast(1080f)
        val minScreenY = if (hasClip) clip.top.toFloat() else 0f
        val maxScreenY = if (hasClip) clip.bottom.toFloat() else mapView.height.toFloat().coerceAtLeast(1920f)

        synchronized(this) {
            activeClusters.clear()
            activeSingleItems.clear()

            if (zoom < CLUSTER_BELOW_ZOOM) {
                // Below zoom 13: Group into a ~60 dp grid
                val gridSizePx = 60f * density
                val gridBuckets = mutableMapOf<Long, ClusterBucket>()

                for (poi in poiItems) {
                    val geoPoint = GeoPoint(poi.lat, poi.lng)
                    projection.toPixels(geoPoint, tempPoint)
                    val px = tempPoint.x.toFloat()
                    val py = tempPoint.y.toFloat()

                    // Quick viewport culling with padding
                    if (px < minScreenX - gridSizePx || px > maxScreenX + gridSizePx ||
                        py < minScreenY - gridSizePx || py > maxScreenY + gridSizePx
                    ) {
                        continue
                    }

                    val cellX = kotlin.math.floor((px - minScreenX) / gridSizePx).toInt()
                    val cellY = kotlin.math.floor((py - minScreenY) / gridSizePx).toInt()
                    val cellKey = ((cellX.toLong() and 0xFFFFFFFFL) shl 32) or (cellY.toLong() and 0xFFFFFFFFL)

                    val bucket = gridBuckets.getOrPut(cellKey) { ClusterBucket() }
                    bucket.items.add(poi)
                    bucket.sumX += px
                    bucket.sumY += py
                    bucket.sumLat += poi.lat
                    bucket.sumLng += poi.lng
                }

                // Draw clusters or single items
                for (bucket in gridBuckets.values) {
                    val count = bucket.items.size
                    bucket.screenX = bucket.sumX / count
                    bucket.screenY = bucket.sumY / count

                    if (count == 1) {
                        val singleItem = bucket.items.first()
                        val isSelected = singleItem.id == selectedPoiId
                        val icon = MapIconUtils.getClusterPoiIcon(context, singleItem.type, isSelected)
                        val halfW = icon.intrinsicWidth / 2
                        val halfH = icon.intrinsicHeight / 2
                        val left = (bucket.screenX - halfW).toInt()
                        val top = (bucket.screenY - halfH).toInt()
                        icon.setBounds(left, top, left + icon.intrinsicWidth, top + icon.intrinsicHeight)
                        icon.draw(canvas)

                        activeSingleItems.add(singleItem to Point(bucket.screenX.toInt(), bucket.screenY.toInt()))
                    } else {
                        val countDrawable = MapIconUtils.getClusterCountDrawable(context, count)
                        val halfW = countDrawable.intrinsicWidth / 2
                        val halfH = countDrawable.intrinsicHeight / 2
                        val left = (bucket.screenX - halfW).toInt()
                        val top = (bucket.screenY - halfH).toInt()
                        countDrawable.setBounds(left, top, left + countDrawable.intrinsicWidth, top + countDrawable.intrinsicHeight)
                        countDrawable.draw(canvas)

                        bucket.radiusPx = halfW.toFloat()
                        activeClusters.add(bucket)
                    }
                }
            } else {
                // At zoom 13+: Show individual icons (32 dp, selected 40 dp)
                for (poi in poiItems) {
                    val geoPoint = GeoPoint(poi.lat, poi.lng)
                    projection.toPixels(geoPoint, tempPoint)
                    val px = tempPoint.x.toFloat()
                    val py = tempPoint.y.toFloat()

                    val isSelected = poi.id == selectedPoiId
                    val icon = MapIconUtils.getClusterPoiIcon(context, poi.type, isSelected)
                    val halfW = icon.intrinsicWidth / 2
                    val halfH = icon.intrinsicHeight / 2

                    if (px < minScreenX - halfW || px > maxScreenX + halfW ||
                        py < minScreenY - halfH || py > maxScreenY + halfH
                    ) {
                        continue
                    }

                    val left = (px - halfW).toInt()
                    val top = (py - halfH).toInt()
                    icon.setBounds(left, top, left + icon.intrinsicWidth, top + icon.intrinsicHeight)
                    icon.draw(canvas)

                    activeSingleItems.add(poi to Point(tempPoint.x, tempPoint.y))
                }
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (!isEnabled) return false
        val tapX = e.x
        val tapY = e.y
        val zoom = mapView.zoomLevelDouble
        val density = context.resources.displayMetrics.density

        synchronized(this) {
            if (zoom < CLUSTER_BELOW_ZOOM) {
                // 1. Check if user tapped a cluster circle
                for (cluster in activeClusters) {
                    val dx = tapX - cluster.screenX
                    val dy = tapY - cluster.screenY
                    val dist = sqrt((dx * dx + dy * dy).toDouble())
                    if (dist <= cluster.radiusPx * 1.3f) {
                        // Tapping cluster zooms in centered on cluster
                        val centerLat = cluster.sumLat / cluster.items.size
                        val centerLng = cluster.sumLng / cluster.items.size
                        mapView.controller.animateTo(GeoPoint(centerLat, centerLng))
                        mapView.controller.zoomTo(mapView.zoomLevelDouble + 2.0)
                        return true
                    }
                }

                // 2. Check if user tapped a single isolated marker in low zoom
                val touchRadius = 22f * density
                for ((item, point) in activeSingleItems) {
                    val dx = tapX - point.x
                    val dy = tapY - point.y
                    val dist = sqrt((dx * dx + dy * dy).toDouble())
                    if (dist <= touchRadius) {
                        selectedPoiId = item.id
                        onPoiClicked?.invoke(item)
                        mapView.invalidate()
                        return true
                    }
                }
            } else {
                // Zoom 13+: Check if user tapped an individual icon
                val touchRadius = 24f * density
                var closestItem: MapPoiItem? = null
                var closestDist = Double.MAX_VALUE

                for ((item, point) in activeSingleItems) {
                    val dx = tapX - point.x
                    val dy = tapY - point.y
                    val dist = sqrt((dx * dx + dy * dy).toDouble())
                    if (dist <= touchRadius && dist < closestDist) {
                        closestDist = dist
                        closestItem = item
                    }
                }

                if (closestItem != null) {
                    selectedPoiId = closestItem.id
                    onPoiClicked?.invoke(closestItem)
                    mapView.invalidate()
                    return true
                }
            }
        }
        return false
    }
}
