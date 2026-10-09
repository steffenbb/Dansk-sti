package com.example.ui.screens

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun FullscreenMapScreen(
    trailId: String,
    viewModel: TrailViewModel = viewModel(),
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val points by viewModel.trailPoints.collectAsState()
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(trailId) {
        viewModel.loadTrail(trailId)
    }

    BackHandler {
        onBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    DanskStiTileSource.applyTo(this, isDark)
                }
            },
            update = { mapView ->
                DanskStiTileSource.applyTo(mapView, isDark)
                mapView.overlays.removeAll { it is Polyline }

                if (points.isNotEmpty()) {
                    val polyline = Polyline(mapView).apply {
                        setPoints(points.map { GeoPoint(it.lat, it.lng) })
                        outlinePaint.color = AndroidColor.rgb(35, 83, 63)
                        outlinePaint.strokeWidth = 9f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    mapView.overlays.add(polyline)

                    val minLat = points.minOf { it.lat }
                    val maxLat = points.maxOf { it.lat }
                    val minLng = points.minOf { it.lng }
                    val maxLng = points.maxOf { it.lng }
                    mapView.zoomToBoundingBox(
                        BoundingBox(maxLat + 0.03, maxLng + 0.03, minLat - 0.03, minLng - 0.03),
                        false
                    )
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Floating Back Button
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .size(48.dp)
                .align(Alignment.TopStart)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Tilbage",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
