package com.example.util.routing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale
import kotlin.math.max
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Styling and marker creation helpers for Rutebygger (Phase 9 & UI Round 1).
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
    const val DIMMED_ALPHA = 153 // 60% opacity (255 * 0.6)

    val ALTERNATIVE_COLORS = intArrayOf(
        0xFF0288D1.toInt(), // Light Blue
        0xFF7B1FA2.toInt(), // Purple
        0xFF00796B.toInt()  // Teal
    )

    /**
     * UI Round 1 Item 8:
     * start = green flag, end = chequered flag, overnight = tent with day number ("D1"),
     * via = white numbered circle. Every pin gets a 48 dp touch area regardless of icon size.
     * UI Round 1 Item 7:
     * tapping a pin selects it (larger icon with ring, rest of the route at 60 % opacity).
     */
    fun createWaypointIcon(
        context: Context,
        role: WaypointRole,
        index: Int,
        isSelected: Boolean = false,
        badgeLabel: String? = null,
        dayNumber: Int = 1,
        isDimmed: Boolean = false
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val minTouchPx = (48f * density).toInt().coerceAtLeast(48)

        val baseSizeDp = when (role) {
            WaypointRole.START, WaypointRole.END -> if (isSelected) 38 else 32
            WaypointRole.OVERNIGHT -> if (isSelected) 38 else 32
            WaypointRole.VIA -> if (isSelected) 30 else 24
        }
        val circlePx = (baseSizeDp * density).toInt()
        val ringExtraPx = if (isSelected) (10f * density).toInt() else 0

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10.5f * density
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val badgeTextWidth = if (!badgeLabel.isNullOrBlank()) badgePaint.measureText(badgeLabel) else 0f
        val badgeWidth = if (!badgeLabel.isNullOrBlank()) (badgeTextWidth + 12f * density).toInt() else 0
        val badgeHeight = if (!badgeLabel.isNullOrBlank()) (18f * density).toInt() else 0

        val totalWidth = max(minTouchPx, max(circlePx + ringExtraPx, badgeWidth))
        val contentHeight = if (badgeHeight > 0) {
            circlePx + ringExtraPx + badgeHeight + (4 * density).toInt()
        } else {
            circlePx + ringExtraPx
        }
        val totalHeight = max(minTouchPx, contentHeight)

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val alpha = if (isDimmed && !isSelected) DIMMED_ALPHA else 255

        val cx = totalWidth / 2f
        val cy = if (badgeHeight > 0) {
            badgeHeight + (4f * density) + (totalHeight - badgeHeight - 4f * density) / 2f
        } else {
            totalHeight / 2f
        }

        // Draw badge pill above pin if present
        if (!badgeLabel.isNullOrBlank() && badgeHeight > 0) {
            val pillLeft = (totalWidth - badgeWidth) / 2f
            val pillRect = android.graphics.RectF(pillLeft, 1f, pillLeft + badgeWidth, badgeHeight.toFloat())
            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#DD263238")
                this.alpha = alpha
            }
            canvas.drawRoundRect(pillRect, 8f * density, 8f * density, pillBgPaint)
            badgePaint.alpha = alpha
            val badgeTextY = badgeHeight / 2f - (badgePaint.descent() + badgePaint.ascent()) / 2f
            canvas.drawText(badgeLabel, totalWidth / 2f, badgeTextY, badgePaint)
        }

        val radius = (circlePx / 2f) - (2f * density)

        // Selection outer ring (Item 7: larger icon with ring)
        if (isSelected) {
            val ringHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(55, 30, 136, 229)
            }
            val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.rgb(30, 136, 229)
                strokeWidth = 2.5f * density
            }
            canvas.drawCircle(cx, cy, radius + 4.5f * density, ringHaloPaint)
            canvas.drawCircle(cx, cy, radius + 4.5f * density, ringStrokePaint)
        }

        // Circle background & border per role (Item 8)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when (role) {
                WaypointRole.START -> Color.rgb(46, 125, 50) // Green flag badge
                WaypointRole.END -> Color.rgb(38, 50, 56) // Dark slate for chequered flag
                WaypointRole.OVERNIGHT -> Color.rgb(230, 81, 0) // Warm camp orange for tent + D1
                WaypointRole.VIA -> Color.WHITE // White numbered circle
            }
            this.alpha = alpha
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = when (role) {
                WaypointRole.VIA -> if (isSelected) Color.rgb(21, 101, 192) else Color.rgb(55, 71, 79)
                else -> Color.WHITE
            }
            strokeWidth = (if (isSelected) 2.8f else 2f) * density
            this.alpha = alpha
        }

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        when (role) {
            WaypointRole.START -> drawGreenFlagGlyph(canvas, cx, cy, radius, density, alpha)
            WaypointRole.END -> drawChequeredFlagGlyph(canvas, cx, cy, radius, density, alpha)
            WaypointRole.OVERNIGHT -> drawTentWithDayGlyph(canvas, cx, cy, radius, density, dayNumber, alpha)
            WaypointRole.VIA -> {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(38, 50, 56)
                    textAlign = Paint.Align.CENTER
                    textSize = (if (isSelected) 12.5f else 11f) * density
                    isFakeBoldText = true
                    this.alpha = alpha
                }
                val label = "${index + 1}"
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(label, cx, textY, textPaint)
            }
        }

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun drawGreenFlagGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        alpha: Int
    ) {
        val poleX = cx - radius * 0.28f
        val topY = cy - radius * 0.48f
        val bottomY = cy + radius * 0.48f
        val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            this.alpha = alpha
        }
        canvas.drawLine(poleX, topY, poleX, bottomY, polePaint)

        val flagPath = Path().apply {
            moveTo(poleX, topY)
            lineTo(cx + radius * 0.45f, topY + radius * 0.26f)
            lineTo(poleX, topY + radius * 0.54f)
            close()
        }
        val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            this.alpha = alpha
        }
        canvas.drawPath(flagPath, flagPaint)
    }

    private fun drawChequeredFlagGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        alpha: Int
    ) {
        val poleX = cx - radius * 0.34f
        val topY = cy - radius * 0.46f
        val bottomY = cy + radius * 0.48f
        val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            this.alpha = alpha
        }
        canvas.drawLine(poleX, topY, poleX, bottomY, polePaint)

        val cols = 3
        val rows = 2
        val flagW = radius * 0.72f
        val flagH = radius * 0.48f
        val cellW = flagW / cols
        val cellH = flagH / rows

        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            this.alpha = alpha
        }
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 24, 28)
            style = Paint.Style.FILL
            this.alpha = alpha
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = poleX + c * cellW
                val top = topY + r * cellH
                val paint = if ((r + c) % 2 == 0) whitePaint else darkPaint
                canvas.drawRect(left, top, left + cellW, top + cellH, paint)
            }
        }
    }

    private fun drawTentWithDayGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        dayNumber: Int,
        alpha: Int
    ) {
        // Small tent roof at upper half
        val tentTopY = cy - radius * 0.52f
        val tentBaseY = cy - radius * 0.02f
        val tentHalfW = radius * 0.42f
        val tentPath = Path().apply {
            moveTo(cx, tentTopY)
            lineTo(cx + tentHalfW, tentBaseY)
            lineTo(cx - tentHalfW, tentBaseY)
            close()
        }
        val tentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            this.alpha = alpha
        }
        canvas.drawPath(tentPath, tentPaint)

        // Door triangle cutout
        val doorPath = Path().apply {
            moveTo(cx, tentTopY + (tentBaseY - tentTopY) * 0.45f)
            lineTo(cx + tentHalfW * 0.35f, tentBaseY)
            lineTo(cx - tentHalfW * 0.35f, tentBaseY)
            close()
        }
        val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(230, 81, 0)
            style = Paint.Style.FILL
            this.alpha = alpha
        }
        canvas.drawPath(doorPath, doorPaint)

        // Day number label ("D1", "D2", ...) at lower half
        val dayLabel = "D${dayNumber.coerceAtLeast(1)}"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 9.5f * density
            isFakeBoldText = true
            this.alpha = alpha
        }
        val textY = cy + radius * 0.48f
        canvas.drawText(dayLabel, cx, textY, textPaint)
    }

    /**
     * UI Round 1 Item 5:
     * Ghost handles: at zoom >= 13 draw a small grey hollow circle at the middle of each leg.
     * Dragging it inserts a via point there (insertViaPointOnLine) and continues as a pin drag.
     * Uses a 48 dp touch target so it is easy to grab.
     */
    fun createGhostHandleIcon(
        context: Context,
        isDimmed: Boolean = false
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val touchSizePx = (48f * density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(touchSizePx, touchSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = touchSizePx / 2f
        val cy = touchSizePx / 2f
        val visualRadius = 6.5f * density
        val alpha = if (isDimmed) DIMMED_ALPHA else 255

        // Subtle semi-transparent white backing so the hollow circle stands out on top of the route line
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((190 * (alpha / 255f)).toInt(), 255, 255, 255)
        }
        val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3.5f * density
            this.alpha = alpha
        }
        val greyRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(117, 117, 117) // Grey hollow circle
            strokeWidth = 2.2f * density
            this.alpha = alpha
        }

        canvas.drawCircle(cx, cy, visualRadius, fillPaint)
        canvas.drawCircle(cx, cy, visualRadius, outerHaloPaint)
        canvas.drawCircle(cx, cy, visualRadius, greyRingPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * UI Round 1 Item 11:
     * TalkBack: every pin and leg has a contentDescription like
     * "Punkt 3, overnatning, Shelter Skovbo, km 32,1".
     */
    fun formatPinTalkBackDescription(
        index: Int,
        waypoint: RouteWaypoint,
        cumulativeKm: Double
    ): String {
        val roleText = when (waypoint.role) {
            WaypointRole.START -> "start"
            WaypointRole.END -> "slut"
            WaypointRole.OVERNIGHT -> "overnatning"
            WaypointRole.VIA -> "via"
        }
        val namePart = waypoint.name?.takeIf { it.isNotBlank() }
        val kmFormatted = String.format(Locale.GERMAN, "%.1f", cumulativeKm)
        return if (namePart != null) {
            "Punkt ${index + 1}, $roleText, $namePart, km $kmFormatted"
        } else {
            "Punkt ${index + 1}, $roleText, km $kmFormatted"
        }
    }

    fun formatLegTalkBackDescription(
        legIndex: Int,
        lengthKm: Double,
        ascentM: Double,
        legMode: LegMode
    ): String {
        val modeText = if (legMode == LegMode.SNAP) "Følg stier" else "Lige linje"
        val kmFormatted = String.format(Locale.GERMAN, "%.1f", lengthKm)
        return "Strækning ${legIndex + 1}, $modeText, $kmFormatted km, ${ascentM.toInt()} m stigning"
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
        isDark: Boolean = isDarkMode,
        isDimmed: Boolean = false,
        isSelected: Boolean = false
    ): Polyline {
        val routeColor = if (isDark) COLOR_PLANNED_ROUTE_DARK else COLOR_PLANNED_ROUTE_LIGHT
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (hasAccessWarning) COLOR_ACCESS_WARNING else routeColor
            outlinePaint.strokeWidth = if (isSelected) 18f else 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (isDimmed && !isSelected) {
                outlinePaint.alpha = DIMMED_ALPHA
            }
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
        isDark: Boolean = isDarkMode,
        isDimmed: Boolean = false,
        isSelected: Boolean = false
    ): Polyline {
        val casingColor = if (isDark) COLOR_PLANNED_CASING_DARK else COLOR_PLANNED_CASING_LIGHT
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = casingColor
            outlinePaint.strokeWidth = if (isSelected) 26f else 22f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (isDimmed && !isSelected) {
                outlinePaint.alpha = DIMMED_ALPHA
            }
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
        isDark: Boolean = isDarkMode,
        isDimmed: Boolean = false,
        isSelected: Boolean = false
    ): Polyline {
        val straightColor = if (isDark) COLOR_PLANNED_ROUTE_DARK else COLOR_STRAIGHT_LEG
        val polyline = Polyline(mapView).apply {
            outlinePaint.color = if (isLoading) COLOR_LOADING_LEG else straightColor
            outlinePaint.strokeWidth = when {
                isLoading -> 6f
                isSelected -> 14f
                else -> 10f
            }
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
            if (isDimmed && !isSelected) {
                outlinePaint.alpha = DIMMED_ALPHA
            }
            setPoints(points)
        }
        return polyline
    }

    /**
     * Requirement 6: A failed leg is drawn as a red dashed line between its two points.
     */
    fun createFailedLegPolyline(
        mapView: MapView,
        points: List<GeoPoint>,
        isDimmed: Boolean = false
    ): Polyline {
        return Polyline(mapView).apply {
            outlinePaint.color = COLOR_FAILED_LEG
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
            if (isDimmed) {
                outlinePaint.alpha = DIMMED_ALPHA
            }
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
