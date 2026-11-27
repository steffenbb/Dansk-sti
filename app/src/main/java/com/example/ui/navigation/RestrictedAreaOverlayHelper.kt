package com.example.ui.navigation

import android.graphics.DashPathEffect
import com.example.data.repository.RestrictedArea
import com.example.data.repository.RestrictedAreaKind
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

/** Phase D3: osmdroid polygons for military areas (red, dashed) and wildlife reserves (orange). */
internal object RestrictedAreaOverlayHelper {
    const val MIN_ZOOM = 9.0

    private val MILITARY_STROKE = android.graphics.Color.parseColor("#D32F2F")
    private val MILITARY_FILL = android.graphics.Color.parseColor("#22D32F2F")
    private val RESERVE_STROKE = android.graphics.Color.parseColor("#F57C00")
    private val RESERVE_FILL = android.graphics.Color.parseColor("#14F57C00")

    fun createPolygons(mapView: MapView, area: RestrictedArea, onTap: (RestrictedArea) -> Unit): List<Polygon> {
        val density = mapView.context.resources.displayMetrics.density
        return area.rings.map { ring ->
            Polygon(mapView).apply {
                points = ring.map { GeoPoint(it.lat, it.lng) }
                title = area.name
                when (area.kind) {
                    RestrictedAreaKind.MILITARY -> {
                        fillPaint.color = MILITARY_FILL
                        outlinePaint.color = MILITARY_STROKE
                        outlinePaint.strokeWidth = 3f * density
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
                    }
                    RestrictedAreaKind.WILDLIFE_RESERVE -> {
                        fillPaint.color = RESERVE_FILL
                        outlinePaint.color = RESERVE_STROKE
                        outlinePaint.strokeWidth = 2f * density
                    }
                }
                setOnClickListener { _, _, _ ->
                    onTap(area)
                    true
                }
            }
        }
    }
}
