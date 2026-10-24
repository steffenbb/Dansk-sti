package com.example.util

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import com.example.data.model.WaterFeatureDto
import com.example.data.model.WaterFeatureType
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/**
 * Stable-keyed FolderOverlay ("water") that caches constructed osmdroid Polygons, Polylines,
 * and Spring dots and only rebuilds when the underlying feature set or dark mode changes.
 */
class WaterLayerOverlay : FolderOverlay() {

    private var lastSignature: Int = 0
    var currentFeatureCount: Int = 0
        private set

    init {
        name = "water"
    }

    companion object {
        const val COLOR_WATER_CLEAR_BLUE_LIGHT = 0xFF0091EA.toInt()
        const val COLOR_WATER_CLEAR_BLUE_DARK = 0xFF00B0FF.toInt()

        val ALPHA_LAKE_FILL: Int = (255 * 0.45f).roundToInt() // 115
        val ALPHA_WETLAND_FILL: Int = (255 * 0.25f).roundToInt() // 64
        const val ALPHA_WATERWAY_LINE = 225

        const val RIVER_WIDTH_DP = 3.0f
        const val STREAM_WIDTH_DP = 1.5f
        const val SPRING_DOT_RADIUS_DP = 4.0f

        fun withAlpha(rgbColor: Int, alpha: Int): Int {
            return (rgbColor and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
        }

        fun getWaterBaseColor(isDark: Boolean): Int {
            return if (isDark) COLOR_WATER_CLEAR_BLUE_DARK else COLOR_WATER_CLEAR_BLUE_LIGHT
        }

        fun getLakeFillColor(isDark: Boolean): Int {
            return withAlpha(getWaterBaseColor(isDark), ALPHA_LAKE_FILL)
        }

        fun getWetlandFillColor(isDark: Boolean): Int {
            return withAlpha(getWaterBaseColor(isDark), ALPHA_WETLAND_FILL)
        }

        fun getWaterwayLineColor(isDark: Boolean): Int {
            return withAlpha(getWaterBaseColor(isDark), ALPHA_WATERWAY_LINE)
        }
    }

    fun updateFeatures(
        features: List<WaterFeatureDto>,
        isDark: Boolean,
        density: Float
    ): Boolean {
        val safeDensity = density.coerceAtLeast(1f)
        val signature = computeSignature(features, isDark, safeDensity)
        if (signature == lastSignature && (features.isEmpty() == items.isEmpty())) {
            return false
        }
        lastSignature = signature
        currentFeatureCount = features.size
        items.clear()

        if (features.isEmpty()) {
            return true
        }

        val baseBlue = getWaterBaseColor(isDark)
        val lakeFill = getLakeFillColor(isDark)
        val lakeStroke = withAlpha(baseBlue, 185)
        val wetlandFill = getWetlandFillColor(isDark)
        val wetlandStroke = withAlpha(baseBlue, 150)
        val waterwayColor = getWaterwayLineColor(isDark)

        val springPoints = mutableListOf<Pair<GeoPoint, String?>>()

        // 1. Draw fills first (wetlands & lakes) so rivers/streams/springs render cleanly on top
        for (feature in features) {
            when (feature.featureType) {
                WaterFeatureType.LAKE -> {
                    val geoPoints = feature.toGeoPoints()
                    if (geoPoints.size >= 3) {
                        val poly = Polygon().apply {
                            id = feature.id
                            title = feature.name ?: "Sø / Vand"
                            points = geoPoints
                            fillPaint.color = lakeFill
                            fillPaint.style = Paint.Style.FILL
                            outlinePaint.color = lakeStroke
                            outlinePaint.strokeWidth = 1.0f * safeDensity
                            outlinePaint.style = Paint.Style.STROKE
                            outlinePaint.isAntiAlias = true
                            setOnClickListener { _, _, _ -> false }
                        }
                        items.add(poly)
                    }
                }
                WaterFeatureType.WETLAND -> {
                    val geoPoints = feature.toGeoPoints()
                    if (geoPoints.size >= 3) {
                        val poly = Polygon().apply {
                            id = feature.id
                            title = feature.name ?: "Vådområde"
                            points = geoPoints
                            fillPaint.color = wetlandFill
                            fillPaint.style = Paint.Style.FILL
                            outlinePaint.color = wetlandStroke
                            outlinePaint.strokeWidth = 1.2f * safeDensity
                            outlinePaint.style = Paint.Style.STROKE
                            outlinePaint.isAntiAlias = true
                            outlinePaint.pathEffect = DashPathEffect(
                                floatArrayOf(5f * safeDensity, 4f * safeDensity),
                                0f
                            )
                            setOnClickListener { _, _, _ -> false }
                        }
                        items.add(poly)
                    }
                }
                else -> {}
            }
        }

        // 2. Linear waterways: rivers/canals (3 dp) and streams/ditches/drains (1.5 dp)
        for (feature in features) {
            when (feature.featureType) {
                WaterFeatureType.RIVER -> {
                    val geoPoints = feature.toGeoPoints()
                    if (geoPoints.size >= 2) {
                        val line = Polyline().apply {
                            id = feature.id
                            title = feature.name ?: "Å / Kanal"
                            setPoints(geoPoints)
                            outlinePaint.color = waterwayColor
                            outlinePaint.strokeWidth = RIVER_WIDTH_DP * safeDensity
                            outlinePaint.style = Paint.Style.STROKE
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                            setOnClickListener { _, _, _ -> false }
                        }
                        items.add(line)
                    }
                }
                WaterFeatureType.STREAM -> {
                    val geoPoints = feature.toGeoPoints()
                    if (geoPoints.size >= 2) {
                        val line = Polyline().apply {
                            id = feature.id
                            title = feature.name ?: "Bæk / Grøft"
                            setPoints(geoPoints)
                            outlinePaint.color = waterwayColor
                            outlinePaint.strokeWidth = STREAM_WIDTH_DP * safeDensity
                            outlinePaint.style = Paint.Style.STROKE
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                            setOnClickListener { _, _, _ -> false }
                        }
                        items.add(line)
                    }
                }
                WaterFeatureType.SPRING -> {
                    val first = feature.points.firstOrNull()
                    if (first != null) {
                        springPoints.add(GeoPoint(first.lat, first.lng) to feature.name)
                    }
                }
                else -> {}
            }
        }

        // 3. Springs as small blue dots
        if (springPoints.isNotEmpty()) {
            items.add(
                SpringDotsOverlay(
                    springs = springPoints,
                    dotColor = baseBlue,
                    haloColor = if (isDark) 0xDD101418.toInt() else 0xEEFFFFFF.toInt(),
                    radiusPx = SPRING_DOT_RADIUS_DP * safeDensity,
                    haloStrokePx = 1.5f * safeDensity
                )
            )
        }

        return true
    }

    private fun computeSignature(
        features: List<WaterFeatureDto>,
        isDark: Boolean,
        density: Float
    ): Int {
        var h = if (isDark) 1231 else 1237
        h = 31 * h + density.toBits()
        h = 31 * h + features.size
        for (f in features) {
            h = 31 * h + f.id.hashCode()
            h = 31 * h + f.featureType.ordinal
        }
        return h
    }
}

/**
 * Renders natural=spring nodes as small blue dots with a crisp contrast halo.
 */
class SpringDotsOverlay(
    val springs: List<Pair<GeoPoint, String?>>,
    dotColor: Int,
    haloColor: Int,
    private val radiusPx: Float,
    haloStrokePx: Float
) : Overlay() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = dotColor
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = haloColor
        strokeWidth = haloStrokePx
    }

    private val screenPt = Point()

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null || !isEnabled) return
        val proj = mapView.projection ?: return
        for ((geoPoint, _) in springs) {
            proj.toPixels(geoPoint, screenPt)
            val x = screenPt.x.toFloat()
            val y = screenPt.y.toFloat()
            if (x >= -radiusPx * 2 && x <= canvas.width + radiusPx * 2 &&
                y >= -radiusPx * 2 && y <= canvas.height + radiusPx * 2
            ) {
                canvas.drawCircle(x, y, radiusPx, fillPaint)
                canvas.drawCircle(x, y, radiusPx, haloPaint)
            }
        }
    }
}
