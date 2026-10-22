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

    // Colors (Requirement E)
    const val COLOR_PLANNED_ROUTE_LIGHT = 0xFF1E88E5.toInt() // #1E88E5 in light mode
    const val COLOR_PLANNED_CASING_LIGHT = 0xFFFFFFFF.toInt() // White casing in light mode
    const val COLOR_PLANNED_ROUTE_DARK = 0xFF64B5F6.toInt() // #64B5F6 in dark mode
    const val COLOR_PLANNED_CASING_DARK = 0xFF14181C.toInt() // Near-black casing in dark mode
    const val COLOR_WALKED_ROUTE = 0xFF888888.toInt() // Walked part grey
    const val COLOR_TRAIL_BASE_LIGHT = 0xB3388E3C.toInt() // Muted green TRAIL base line (light)
    const val COLOR_TRAIL_BASE_DARK = 0xB366BB6A.toInt() // Muted green TRAIL base line (dark)

    const val COLOR_SOLID_ROUTE = COLOR_PLANNED_ROUTE_LIGHT
    const val COLOR_SOLID_CASING = COLOR_PLANNED_CASING_LIGHT
    const val COLOR_STRAIGHT_LEG = 0xFF1565C0.toInt() // Blue dashed
    const val COLOR_LOADING_LEG = 0xFF757575.toInt() // Grey dashed
    const val COLOR_FAILED_LEG = 0xFFC62828.toInt() // Red dashed for failed legs
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
        isSelected: Boolean = false,
        badgeLabel: String? = null
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val sizeDp = when (role) {
            WaypointRole.START, WaypointRole.END -> 32
            WaypointRole.OVERNIGHT -> 30
            WaypointRole.VIA -> 24
        }
        val circlePx = (sizeDp * density).toInt()

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10.5f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val badgeTextWidth = if (!badgeLabel.isNullOrBlank()) badgePaint.measureText(badgeLabel) else 0f
        val badgeWidth = if (!badgeLabel.isNullOrBlank()) (badgeTextWidth + 12f * density).toInt() else 0
        val badgeHeight = if (!badgeLabel.isNullOrBlank()) (18f * density).toInt() else 0

        val totalWidth = kotlin.math.max(circlePx, badgeWidth)
        val totalHeight = if (badgeHeight > 0) circlePx + badgeHeight + (4 * density).toInt() else circlePx

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
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

        val circleTop = if (badgeHeight > 0) badgeHeight + (4 * density) else 0f
        val radius = (circlePx / 2f) - (strokePaint.strokeWidth / 2f)
        val cx = totalWidth / 2f
        val cy = circleTop + (circlePx / 2f)

        // Draw badge pill above pin if present (Requirement C & D: "km 23,4" while dragging or detour cost)
        if (!badgeLabel.isNullOrBlank() && badgeHeight > 0) {
            val pillLeft = (totalWidth - badgeWidth) / 2f
            val pillRect = android.graphics.RectF(pillLeft, 1f, pillLeft + badgeWidth, badgeHeight.toFloat())
            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#DD263238")
            }
            canvas.drawRoundRect(pillRect, 8f * density, 8f * density, pillBgPaint)
            val badgeTextY = badgeHeight / 2f - (badgePaint.descent() + badgePaint.ascent()) / 2f
            canvas.drawText(badgeLabel, totalWidth / 2f, badgeTextY, badgePaint)
        }

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
     * Requirement C & E: Draw the full base trail in a muted green colour underneath the planned section.
     */
    fun createMutedTrailPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        isFerry: Boolean = false,
        isDarkMode: Boolean = false,
        isDark: Boolean = isDarkMode
    ): Polyline {
        val baseGreen = if (isDark) COLOR_TRAIL_BASE_DARK else COLOR_TRAIL_BASE_LIGHT
        return Polyline(mapView).apply {
            outlinePaint.color = if (isFerry) Color.parseColor("#995C6BC0") else baseGreen
            outlinePaint.strokeWidth = 9f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (isFerry) {
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
            }
            setPoints(points)
        }
    }

    /**
     * Requirement C: 5 km label badge along the base trail in TRAIL mode.
     */
    fun createKmMarkerBitmap(
        context: Context,
        label: String
    ): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#37474F")
            textSize = 9.5f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textWidth = textPaint.measureText(label)
        val padH = (6 * density)
        val w = (textWidth + padH * 2).toInt().coerceAtLeast((36 * density).toInt())
        val h = (18 * density).toInt()

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEFFFFFF")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#90A4AE")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }
        val rect = android.graphics.RectF(1f, 1f, w - 1f, h - 1f)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, bgPaint)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, borderPaint)

        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, w / 2f, textY, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Requirement E: Map marker for nearby shelters, fri teltning, water, toilets, and shops (supports cluster count).
     */
    fun createNearbyPoiMarkerBitmap(
        context: Context,
        typeKey: String,
        clusterCount: Int = 1
    ): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (if (clusterCount > 1) 26 else 22) * density
        val sizeInt = sizePx.toInt()
        val bitmap = Bitmap.createBitmap(sizeInt, sizeInt, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillColor = when (typeKey.lowercase()) {
            "shelter" -> Color.parseColor("#6D4C41")
            "fri_teltning" -> Color.parseColor("#2E7D32")
            "water" -> Color.parseColor("#0288D1")
            "toilet" -> Color.parseColor("#546E7A")
            "shop" -> Color.parseColor("#F57C00")
            else -> Color.parseColor("#455A64")
        }

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f - 2f * density

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, strokePaint)

        val label = if (clusterCount > 1) {
            clusterCount.toString()
        } else {
            when (typeKey.lowercase()) {
                "shelter" -> "S"
                "fri_teltning" -> "T"
                "water" -> "V"
                "toilet" -> "WC"
                "shop" -> "I"
                else -> "•"
            }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (if (label.length > 1) 8f else 9.5f) * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
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
     * Creates an osmdroid Polyline representing a snapped leg with solid route color (#1E88E5 light, #64B5F6 dark).
     */
    fun createSnappedLegPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        hasAccessWarning: Boolean = false,
        isDarkMode: Boolean = false,
        isDark: Boolean = isDarkMode
    ): Polyline {
        val routeColor = if (isDark) COLOR_PLANNED_ROUTE_DARK else COLOR_PLANNED_ROUTE_LIGHT
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (hasAccessWarning) COLOR_ACCESS_WARNING else routeColor
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setPoints(points)
        }
        return polyline
    }

    /**
     * Creates an osmdroid Polyline representing a casing underneath the snapped leg
     * (white in light mode, near-black in dark mode).
     */
    fun createCasingPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        isDarkMode: Boolean = false,
        isDark: Boolean = isDarkMode
    ): Polyline {
        val casingColor = if (isDark) COLOR_PLANNED_CASING_DARK else COLOR_PLANNED_CASING_LIGHT
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = casingColor
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
        isLoading: Boolean = false,
        isDarkMode: Boolean = false,
        isDark: Boolean = isDarkMode
    ): Polyline {
        val straightColor = if (isDark) COLOR_PLANNED_ROUTE_DARK else COLOR_STRAIGHT_LEG
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (isLoading) COLOR_LOADING_LEG else straightColor
            outlinePaint.strokeWidth = if (isLoading) 6f else 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
            setPoints(points)
        }
        return polyline
    }

    /**
     * Requirement 6: A failed leg is drawn as a red dashed line between its two points.
     */
    fun createFailedLegPolyline(
        mapView: MapView,
        points: List<GeoPoint>
    ): Polyline {
        return Polyline(mapView).apply {
            outlinePaint.color = COLOR_FAILED_LEG
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
            setPoints(points)
        }
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
