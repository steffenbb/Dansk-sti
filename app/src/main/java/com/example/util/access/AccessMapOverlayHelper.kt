package com.example.util.access

import android.content.Context
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

/**
 * Creates and styles osmdroid Polygon overlays representing access zones according to Danish Naturbeskyttelsesloven.
 */
object AccessMapOverlayHelper {

    fun createAccessPolygonOverlay(
        mapView: MapView,
        polygon: AccessPolygon,
        onPolygonClicked: (AccessPolygon) -> Unit
    ): Polygon {
        val overlay = Polygon(mapView).apply {
            title = polygon.name ?: polygon.type.title
            snippet = polygon.type.summary

            fillPaint.color = polygon.type.fillColorArgb
            outlinePaint.color = polygon.type.strokeColorArgb
            outlinePaint.strokeWidth = if (polygon.type == AccessLandType.CULTIVATED_FIELD) 1.5f else 3f

            val geoPoints = polygon.coordinates.map { GeoPoint(it.lat, it.lng) }
            points = geoPoints

            setOnClickListener { _, _, _ ->
                onPolygonClicked(polygon)
                true
            }
        }
        return overlay
    }
}
