package com.example.ui.navigation

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import com.example.data.model.MarkedRouteRenderItem
import com.example.ui.theme.LightDanskStiColors
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.sqrt

/**
 * Plain Overlay that renders discovered OpenStreetMap marked routes ("Afmærkede ruter")
 * from an immutable main-thread snapshot list.
 *
 * - Never reads the shared repository cache during draw().
 * - Performs fast viewport bounding-box culling per route.
 * - Selects zoom-adaptive LOD tier:
 *     * Zoom >= 12.0: draws lod0 (full lossless geometry, or lod1 fallback while lod0 is being reloaded)
 *       with zero vertex skipping.
 *     * Zoom 9.5..12.0: draws lod1 (35 m metric Douglas-Peucker).
 *     * Zoom < 9.5: draws lod2 (150 m metric Douglas-Peucker).
 * - Batches casing paths into one drawPath call and foreground strokes by color.
 * - Handles onSingleTapConfirmed with a 24 dp hit radius, returning true ONLY when a route is hit,
 *   and false otherwise so other overlays receive the tap.
 */
class MarkedRoutesBatchOverlay(
    private val onRouteHit: ((MarkedRouteRenderItem) -> Unit)? = null
) : Overlay() {

    @Volatile
    private var snapshotRoutes: List<MarkedRouteRenderItem> = emptyList()

    var lastFrameRoutesDrawn: Int = 0
        private set

    var lastFrameVerticesDrawn: Int = 0
        private set

    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = LightDanskStiColors.routeCasing.copy(alpha = 0.67f).toArgb()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val foregroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val casingPath = Path()
    private val colorPaths = LinkedHashMap<Int, Path>(16)
    private val reusableGeoPoint = GeoPoint(0.0, 0.0)
    private val reusableScreenPoint = Point()
    private val hitTestPointA = Point()
    private val hitTestPointB = Point()

    companion object {
        const val HIT_TOLERANCE_DP = 24f
    }

    /**
     * Sets the immutable snapshot of routes to render.
     * Must be called on the main thread (or in tests before draw()).
     */
    fun setRoutesSnapshot(routes: List<MarkedRouteRenderItem>) {
        snapshotRoutes = routes.toList()
    }

    fun getRoutesSnapshot(): List<MarkedRouteRenderItem> = snapshotRoutes

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        if (!isEnabled) {
            lastFrameRoutesDrawn = 0
            lastFrameVerticesDrawn = 0
            return
        }

        val currentSnapshot = snapshotRoutes
        if (currentSnapshot.isEmpty()) {
            lastFrameRoutesDrawn = 0
            lastFrameVerticesDrawn = 0
            return
        }

        val projection = mapView.projection ?: return
        val zoom = mapView.zoomLevelDouble
        val density = mapView.context.resources.displayMetrics.density.coerceAtLeast(1f)

        // Zoom-adaptive stroke weights
        val (fgWidthDp, casingWidthDp) = when {
            zoom < 9.5 -> 2.2f to 3.8f
            zoom < 12.0 -> 2.8f to 4.8f
            else -> 3.2f to 5.5f
        }
        casingPaint.strokeWidth = casingWidthDp * density
        foregroundPaint.strokeWidth = fgWidthDp * density

        val box = mapView.boundingBox
        val padDeg = when {
            zoom < 9.5 -> 0.15
            zoom < 12.0 -> 0.05
            else -> 0.02
        }
        val viewMinLat = if (box != null && box.latSouth < box.latNorth) box.latSouth - padDeg else -90.0
        val viewMaxLat = if (box != null && box.latSouth < box.latNorth) box.latNorth + padDeg else 90.0
        val viewMinLng = if (box != null && box.lonWest < box.lonEast) box.lonWest - padDeg else -180.0
        val viewMaxLng = if (box != null && box.lonWest < box.lonEast) box.lonEast + padDeg else 180.0

        casingPath.rewind()
        for (path in colorPaths.values) {
            path.rewind()
        }

        var routesDrawn = 0
        var verticesDrawn = 0

        for (route in currentSnapshot) {
            // Fast O(1) viewport bounding-box culling
            if (route.maxLat < viewMinLat || route.minLat > viewMaxLat ||
                route.maxLng < viewMinLng || route.minLng > viewMaxLng
            ) {
                continue
            }

            // Select LOD tier for current zoom:
            // At zoom >= 12.0, always draw full-resolution lod0 (falling back to lod1 while lod0 is being reloaded).
            val polylines: List<DoubleArray> = when {
                zoom >= 12.0 -> route.lod0 ?: route.lod1
                zoom >= 9.5 -> route.lod1
                else -> route.lod2
            }

            if (polylines.isEmpty()) continue

            val colorPath = colorPaths.getOrPut(route.colorInt) { Path() }
            var routeContributedVertices = 0

            for (coords in polylines) {
                val n = coords.size / 2
                if (n < 2) continue

                reusableGeoPoint.setCoords(coords[0], coords[1])
                projection.toPixels(reusableGeoPoint, reusableScreenPoint)
                val startX = reusableScreenPoint.x.toFloat()
                val startY = reusableScreenPoint.y.toFloat()

                casingPath.moveTo(startX, startY)
                colorPath.moveTo(startX, startY)
                routeContributedVertices++

                for (i in 1 until n) {
                    reusableGeoPoint.setCoords(coords[i * 2], coords[i * 2 + 1])
                    projection.toPixels(reusableGeoPoint, reusableScreenPoint)
                    val px = reusableScreenPoint.x.toFloat()
                    val py = reusableScreenPoint.y.toFloat()

                    casingPath.lineTo(px, py)
                    colorPath.lineTo(px, py)
                    routeContributedVertices++
                }
            }

            if (routeContributedVertices >= 2) {
                routesDrawn++
                verticesDrawn += routeContributedVertices
            }
        }

        if (routesDrawn > 0) {
            canvas.drawPath(casingPath, casingPaint)
            for ((colorInt, path) in colorPaths) {
                if (!path.isEmpty) {
                    foregroundPaint.color = colorInt
                    canvas.drawPath(path, foregroundPaint)
                }
            }
        }

        lastFrameRoutesDrawn = routesDrawn
        lastFrameVerticesDrawn = verticesDrawn
    }

    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
        if (!isEnabled || e == null || mapView == null) return false
        val currentSnapshot = snapshotRoutes
        if (currentSnapshot.isEmpty()) return false

        val projection = mapView.projection ?: return false
        val density = mapView.context.resources.displayMetrics.density.coerceAtLeast(1f)
        val hitRadiusPx = HIT_TOLERANCE_DP * density
        val hitRadiusSq = (hitRadiusPx * hitRadiusPx).toDouble()

        val tapX = e.x
        val tapY = e.y

        // Stage 1: Geographic bounding box around tap point (+ hitRadiusPx margin)
        val padPx = (hitRadiusPx + 8f).toInt()
        val nw = projection.fromPixels((tapX - padPx).toInt(), (tapY - padPx).toInt())
        val se = projection.fromPixels((tapX + padPx).toInt(), (tapY + padPx).toInt())
        val tapMinLat = minOf(nw.latitude, se.latitude)
        val tapMaxLat = maxOf(nw.latitude, se.latitude)
        val tapMinLng = minOf(nw.longitude, se.longitude)
        val tapMaxLng = maxOf(nw.longitude, se.longitude)

        val zoom = mapView.zoomLevelDouble
        var closestRoute: MarkedRouteRenderItem? = null
        var closestDistSq = Double.MAX_VALUE

        // Stage 2: Exact screen-space point-to-segment distance for candidate routes
        for (route in currentSnapshot) {
            if (route.maxLat < tapMinLat || route.minLat > tapMaxLat ||
                route.maxLng < tapMinLng || route.minLng > tapMaxLng
            ) {
                continue
            }

            val polylines: List<DoubleArray> = when {
                zoom >= 12.0 -> route.lod0 ?: route.lod1
                zoom >= 9.5 -> route.lod1
                else -> route.lod2
            }

            for (coords in polylines) {
                val n = coords.size / 2
                if (n < 2) continue

                reusableGeoPoint.setCoords(coords[0], coords[1])
                projection.toPixels(reusableGeoPoint, hitTestPointA)
                var prevX = hitTestPointA.x.toFloat()
                var prevY = hitTestPointA.y.toFloat()

                for (i in 1 until n) {
                    reusableGeoPoint.setCoords(coords[i * 2], coords[i * 2 + 1])
                    projection.toPixels(reusableGeoPoint, hitTestPointB)
                    val curX = hitTestPointB.x.toFloat()
                    val curY = hitTestPointB.y.toFloat()

                    val distSq = pointToSegmentDistanceSq(tapX, tapY, prevX, prevY, curX, curY)
                    if (distSq <= hitRadiusSq && distSq < closestDistSq) {
                        closestDistSq = distSq
                        closestRoute = route
                    }

                    prevX = curX
                    prevY = curY
                }
            }
        }

        val hit = closestRoute ?: return false
        onRouteHit?.invoke(hit)
        return true
    }

    private fun pointToSegmentDistanceSq(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-9) {
            val ex = (px - ax).toDouble()
            val ey = (py - ay).toDouble()
            return ex * ex + ey * ey
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        val ex = px - projX
        val ey = py - projY
        return ex * ex + ey * ey
    }
}
