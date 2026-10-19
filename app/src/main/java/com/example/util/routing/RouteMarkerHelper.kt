package com.example.util.routing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Styling and marker creation helpers for Rutebygger (Phase 9).
 */
object RouteMarkerHelper {

    // Colors
    const val COLOR_SOLID_ROUTE = 0xFF2E7D32.toInt() // Forest green
    const val COLOR_SOLID_CASING = 0xFF0D3E10.toInt() // Dark forest green casing
    const val COLOR_STRAIGHT_LEG = 0xFF1565C0.toInt() // Blue dashed
    const val COLOR_LOADING_LEG = 0xFF757575.toInt() // Grey dashed
    const val COLOR_ACCESS_WARNING = 0xFFF57C00.toInt() // Amber
    const val COLOR_GPX_ORIGINAL = 0xFF9E9E9E.toInt() // Thin grey for GPX comparison
    val ALTERNATIVE_COLORS = intArrayOf(
        0xFF0288D1.toInt(), // Light Blue
        0xFF7B1FA2.toInt(), // Purple
        0xFF00796B.toInt()  // Teal
    )

    fun createWaypointIcon(
        context: Context,
        role: WaypointRole,
        index: Int,
        isSelected: Boolean = false
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val sizeDp = when (role) {
            WaypointRole.START, WaypointRole.END -> 32
            WaypointRole.OVERNIGHT -> 30
            WaypointRole.VIA -> 24
        }
        val sizePx = (sizeDp * density).toInt()

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when (role) {
                WaypointRole.START -> Color.rgb(46, 125, 50) // Forest green
                WaypointRole.END -> Color.rgb(198, 40, 40) // Red
                WaypointRole.OVERNIGHT -> Color.rgb(230, 81, 0) // Amber / Orange
                WaypointRole.VIA -> Color.rgb(30, 136, 229) // Blue
            }
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = if (isSelected) Color.YELLOW else Color.WHITE
            strokeWidth = (if (isSelected) 3f else 2f) * density
        }

        val radius = (sizePx / 2f) - (strokePaint.strokeWidth / 2f)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        // Draw circle
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        // Draw label text / letter
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = (if (role == WaypointRole.VIA) 11f else 13f) * density
            isFakeBoldText = true
        }

        val label = when (role) {
            WaypointRole.START -> "A"
            WaypointRole.END -> "B"
            WaypointRole.OVERNIGHT -> "⛺"
            WaypointRole.VIA -> "${index + 1}"
        }

        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val textY = cy - textBounds.exactCenterY()
        canvas.drawText(label, cx, textY, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Creates an icon for a user-created custom waypoint (Mine punkter - Phase 12).
     */
    fun createCustomWaypointIcon(
        context: Context,
        isSelected: Boolean = false
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (28 * density).toInt()

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(123, 31, 162) // Deep Purple
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = if (isSelected) Color.YELLOW else Color.WHITE
            strokeWidth = (if (isSelected) 3f else 2f) * density
        }

        val radius = (sizePx / 2f) - (strokePaint.strokeWidth / 2f)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 12f * density
        }

        val label = "★"
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val textY = cy - textBounds.exactCenterY()
        canvas.drawText(label, cx, textY, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Creates an osmdroid Polyline representing a snapped leg with solid route color and casing.
     */
    fun createSnappedLegPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        hasAccessWarning: Boolean = false
    ): Polyline {
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (hasAccessWarning) COLOR_ACCESS_WARNING else COLOR_SOLID_ROUTE
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        return polyline
    }

    /**
     * Creates an osmdroid Polyline representing a casing underneath the snapped leg.
     */
    fun createCasingPolyline(
        mapView: MapView,
        points: List<GeoPoint>
    ): Polyline {
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = COLOR_SOLID_CASING
            outlinePaint.strokeWidth = 22f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        return polyline
    }

    /**
     * Creates a dashed polyline for straight-line connections or while a leg is loading.
     */
    fun createDashedLegPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        isLoading: Boolean = false
    ): Polyline {
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (isLoading) COLOR_LOADING_LEG else COLOR_STRAIGHT_LEG
            outlinePaint.strokeWidth = if (isLoading) 6f else 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
            setPoints(points)
        }
        return polyline
    }

    /**
     * Creates a polyline for alternative route comparison.
     */
    fun createAlternativePolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        altIndex: Int
    ): Polyline {
        val color = ALTERNATIVE_COLORS.getOrElse(altIndex) { COLOR_SOLID_ROUTE }
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = color
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        return polyline
    }

    /**
     * Creates a thin grey polyline showing the original imported GPX track.
     */
    fun createGpxOriginalPolyline(
        mapView: MapView,
        points: List<GeoPoint>
    ): Polyline {
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = COLOR_GPX_ORIGINAL
            outlinePaint.strokeWidth = 5f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        return polyline
    }
}
