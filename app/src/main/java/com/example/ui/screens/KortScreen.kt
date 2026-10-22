package com.example.ui.screens

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.components.HapticFeedbackHelper
import com.example.ui.components.formatDanishKm
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.CardShape
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.ExploreViewModel
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.UUID

/**
 * Requirement E: Kort (onX-style map-first tab)
 * - Full-screen DanskStiMap with a search field on top and the map buttons on the right: Lag, Kompas, Min position.
 * - A floating tool row at the bottom: "Planlæg rute", "Gem punkt", "Mål afstand", "Offline-kort".
 * - Long-press sheet from Part 3-F (built into DanskStiMap).
 * - "Mål afstand": tap points to measure straight-line distance; shows total; clear button.
 */
@Composable
fun KortScreen(
    onOpenPlanner: (lat: Double?, lng: Double?, label: String?) -> Unit,
    onNavigateToPoint: (lat: Double, lng: Double, label: String) -> Unit = { lat, lng, label ->
        onOpenPlanner(lat, lng, label)
    },
    onOpenOfflineMaps: () -> Unit,
    onTrailClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val placeSuggestions by viewModel.placeSuggestions.collectAsState()
    val allTrails by viewModel.allTrails.collectAsState()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Distance measurement tool state ("Mål afstand")
    var isMeasuringDistance by remember { mutableStateOf(false) }
    val measurePoints = remember { mutableStateListOf<GeoPoint>() }
    var measureOverlaySignature by remember { mutableIntStateOf(0) }

    // "Gem punkt" quick dialog state
    var showSavePointDialog by remember { mutableStateOf(false) }
    var savePointName by remember { mutableStateOf("") }
    var savePointLat by remember { mutableStateOf(56.0) }
    var savePointLng by remember { mutableStateOf(10.2) }

    // Compute total straight-line distance across measured points
    val totalMeasuredMeters = remember(measurePoints.size, measurePoints.toList()) {
        if (measurePoints.size < 2) {
            0.0
        } else {
            var sum = 0.0
            for (i in 0 until measurePoints.size - 1) {
                val p1 = measurePoints[i]
                val p2 = measurePoints[i + 1]
                sum += GeoUtils.haversineMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }
            sum
        }
    }

    val matchingTrails = remember(searchQuery, allTrails) {
        if (searchQuery.trim().length < 2) {
            emptyList()
        } else {
            allTrails.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.region.contains(searchQuery, ignoreCase = true)
            }.take(3)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        DanskStiMap(
            modifier = Modifier.fillMaxSize(),
            mapTestTag = "kort_osmdroid_map",
            initialCenter = GeoPoint(56.0, 10.2),
            initialZoom = 8.0,
            isSystemDarkOverride = isDark,
            controlsTopPadding = 84.dp,
            attributionBottomPadding = 88.dp,
            layersButtonTestTag = "kort_layers_button",
            compassButtonTestTag = "kort_compass_button",
            myLocationButtonTestTag = "kort_my_location_button",
            onMapReady = { mv -> mapViewRef = mv },
            onMapTap = { geoPoint ->
                if (isMeasuringDistance) {
                    HapticFeedbackHelper.performPinHapticTick(view, "measure_tap")
                    measurePoints.add(geoPoint)
                    true
                } else {
                    false
                }
            },
            onAddToRouteFromDropPin = { lat, lng, label ->
                HapticFeedbackHelper.performPinHapticTick(view, "drop_pin_add_to_route")
                onOpenPlanner(lat, lng, label)
            },
            onRouteHereFromDropPin = { lat, lng, label ->
                HapticFeedbackHelper.performPinHapticTick(view, "drop_pin_route_here")
                onNavigateToPoint(lat, lng, label)
            },
            onUseMarkedRouteInPlanner = { markedRoute ->
                coroutineScope.launch {
                    val res = viewModel.saveMarkedRouteAsTrail(markedRoute)
                    res.onSuccess { trail ->
                        onTrailClick(trail.id)
                    }
                }
            },
            updateOverlaysKey = Pair(isMeasuringDistance, measurePoints.toList()),
            onUpdateOverlays = { mapView, overlayManager, _ ->
                val pts = measurePoints.toList()
                val sig = pts.hashCode() * 31 + (if (isMeasuringDistance) 1 else 0)
                if (sig != measureOverlaySignature) {
                    measureOverlaySignature = sig
                    val density = mapView.context.resources.displayMetrics.density
                    val children = ArrayList<Overlay>()
                    if (pts.size >= 2) {
                        val casing = Polyline(mapView).apply {
                            setPoints(pts)
                            outlinePaint.color = android.graphics.Color.BLACK
                            outlinePaint.strokeWidth = 6f * density
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.isAntiAlias = true
                        }
                        children.add(casing)

                        val measureLine = Polyline(mapView).apply {
                            setPoints(pts)
                            outlinePaint.color = 0xFFF2A33A.toInt() // AccentAmber
                            outlinePaint.strokeWidth = 3.5f * density
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.isAntiAlias = true
                        }
                        children.add(measureLine)
                    }
                    pts.forEach { pt ->
                        val dot = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(pt, 35.0)
                            fillPaint.color = 0xFFF2A33A.toInt()
                            outlinePaint.color = android.graphics.Color.WHITE
                            outlinePaint.strokeWidth = 2f * density
                        }
                        children.add(dot)
                    }
                    overlayManager.updateFolderOverlay(MapLayersOverlayManager.KEY_ROUTE, children)
                }
            }
        ) { _, _ ->
            // Top search bar overlay (Requirement E)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 52.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s4),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.map_search_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("kort_search_input")
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.setSearchQuery("") },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("kort_clear_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Search suggestions dropdown (places + trails)
                if (searchQuery.trim().length >= 2 && (placeSuggestions.isNotEmpty() || matchingTrails.isNotEmpty())) {
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Card(
                        shape = CardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            matchingTrails.forEach { trail ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp)
                                        .clickable {
                                            viewModel.setSearchQuery("")
                                            onTrailClick(trail.id)
                                        }
                                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Route,
                                        contentDescription = null,
                                        tint = ForestGreenPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = trail.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${trail.region} · ${formatDanishKm(trail.lengthKm, 1)} km",
                                            style = TabularLabelStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            placeSuggestions.take(4).forEach { place ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp)
                                        .clickable {
                                            viewModel.setSearchQuery("")
                                            mapViewRef?.controller?.animateTo(
                                                GeoPoint(place.lat, place.lng),
                                                13.5,
                                                600L
                                            )
                                        }
                                        .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = AccentAmber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = place.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom area: Distance measurement banner (when active) + Floating tool row (Requirement E)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2)
            ) {
                if (isMeasuringDistance) {
                    Card(
                        shape = CardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        ),
                        border = BorderStroke(1.5.dp, AccentAmber),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("kort_measure_panel")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.s3),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s2)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val distanceText = if (totalMeasuredMeters >= 1000.0) {
                                        "Samlet afstand: ${formatDanishKm(totalMeasuredMeters / 1000.0, 2)} km"
                                    } else {
                                        "Samlet afstand: ${totalMeasuredMeters.toInt()} m"
                                    }
                                    Text(
                                        text = distanceText,
                                        style = TabularBodyStyle.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("kort_measure_total_text")
                                    )
                                    Text(
                                        text = if (measurePoints.isEmpty()) {
                                            stringResource(R.string.map_measure_instruction)
                                        } else {
                                            "${measurePoints.size} ${if (measurePoints.size == 1) "punkt" else "punkter"} valgt"
                                        },
                                        style = TabularLabelStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                                    OutlinedButton(
                                        onClick = {
                                            HapticFeedbackHelper.performPinHapticTick(view, "measure_add_center")
                                            val center = mapViewRef?.mapCenter
                                            val baseLat = center?.latitude ?: 56.0
                                            val baseLng = center?.longitude ?: 10.2
                                            val offset = measurePoints.size * 0.01
                                            measurePoints.add(GeoPoint(baseLat + offset, baseLng + offset))
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .sizeIn(minHeight = 48.dp)
                                            .testTag("kort_measure_add_point_button")
                                    ) {
                                        Text("+ Punkt", style = MaterialTheme.typography.labelMedium)
                                    }

                                    Button(
                                        onClick = { measurePoints.clear() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .sizeIn(minHeight = 48.dp)
                                            .testTag("kort_measure_clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = stringResource(R.string.map_measure_clear),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.map_measure_clear),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Floating tool row at the bottom: "Planlæg rute", "Gem punkt", "Mål afstand", "Offline-kort" (Requirement E)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("kort_floating_tool_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.s2, vertical = Spacing.s2),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KortFloatingToolButton(
                            icon = Icons.Default.Route,
                            label = stringResource(R.string.map_tool_plan_route),
                            isSelected = false,
                            onClick = { onOpenPlanner(null, null, null) },
                            testTag = "kort_tool_plan_route"
                        )

                        KortFloatingToolButton(
                            icon = Icons.Default.AddLocationAlt,
                            label = stringResource(R.string.map_tool_save_point),
                            isSelected = showSavePointDialog,
                            onClick = {
                                HapticFeedbackHelper.performPinHapticTick(view, "save_point_tool")
                                val center = mapViewRef?.mapCenter
                                savePointLat = center?.latitude ?: 56.0
                                savePointLng = center?.longitude ?: 10.2
                                savePointName = ""
                                showSavePointDialog = true
                            },
                            testTag = "kort_tool_save_point"
                        )

                        KortFloatingToolButton(
                            icon = Icons.Default.Straighten,
                            label = stringResource(R.string.map_tool_measure_distance),
                            isSelected = isMeasuringDistance,
                            onClick = {
                                isMeasuringDistance = !isMeasuringDistance
                            },
                            testTag = "kort_tool_measure_distance"
                        )

                        KortFloatingToolButton(
                            icon = Icons.Default.CloudDownload,
                            label = stringResource(R.string.map_tool_offline_maps),
                            isSelected = false,
                            onClick = onOpenOfflineMaps,
                            testTag = "kort_tool_offline_maps"
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }

    // "Gem punkt" quick dialog
    if (showSavePointDialog) {
        AlertDialog(
            onDismissRequest = { showSavePointDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.map_tool_save_point),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Text(
                        text = String.format(
                            java.util.Locale.US,
                            "Koordinater: %.5f, %.5f",
                            savePointLat,
                            savePointLng
                        ),
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = savePointName,
                        onValueChange = { savePointName = it },
                        label = { Text("Navn på punkt") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("kort_save_point_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = savePointName.trim().ifBlank { "Mit punkt" }
                        showSavePointDialog = false
                        HapticFeedbackHelper.performPinHapticTick(view, "save_custom_waypoint")
                        coroutineScope.launch(Dispatchers.IO) {
                            val app = context.applicationContext as DanskStiApp
                            app.database.danskStiDao().insertUserCustomWaypoint(
                                UserCustomWaypointEntity(
                                    id = "wp_${UUID.randomUUID()}",
                                    name = finalName,
                                    note = "Gemt fra Kort",
                                    lat = savePointLat,
                                    lng = savePointLng,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Punkt '$finalName' gemt i Mine ture")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier.testTag("kort_save_point_confirm_button")
                ) {
                    Text("Gem")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePointDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun KortFloatingToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val containerColor = if (isSelected) AccentAmber else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val contentColor = if (isSelected) Color(0xFF1F1600) else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(
            1.dp,
            if (isSelected) AccentAmber else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .sizeIn(minHeight = 48.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color(0xFF1F1600) else ForestGreenPrimary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}
