package com.example.ui.screens

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.screens.planner.PinSelectionActionBar
import com.example.ui.screens.planner.SelectedLegCard
import com.example.ui.theme.DanskStiColors
import com.example.ui.theme.DarkDanskStiColors
import com.example.ui.theme.LightDanskStiColors
import com.example.ui.viewmodel.*
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

internal fun refreshMapOverlays(
    mapView: MapView,
    overlayManager: MapLayersOverlayManager,
    isDark: Boolean,
    context: Context,
    state: RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    onWaypointClicked: (RouteWaypoint) -> Unit
) {
    val routeFolder = mutableListOf<Overlay>()
    val mapColors: DanskStiColors = if (isDark) DarkDanskStiColors else LightDanskStiColors

    val hasAnySelection = state.selectedWaypointId != null || state.selectedLegKey != null

    if (state.mode == PlannerMode.TRAIL && state.baseTrailPoints.size >= 2) {
        val trailGeoPoints = state.baseTrailPoints.map { GeoPoint(it.lat, it.lng) }
        val mutedLine = RouteMarkerHelper.createMutedTrailPolyline(mapView, trailGeoPoints, isDark = isDark).apply {
            setOnClickListener { _, _, eventPos ->
                if (eventPos != null) {
                    viewModel.onTrailLineTapped(eventPos.latitude, eventPos.longitude)
                }
                true
            }
        }
        routeFolder.add(mutedLine)

        state.baseTrailKmMarkers.forEach { kmMarker ->
            val m = Marker(mapView).apply {
                position = GeoPoint(kmMarker.lat, kmMarker.lng)
                icon = RouteMarkerHelper.createKmMarkerBitmap(context, kmMarker.label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
            routeFolder.add(m)
        }
    }

    if (state.gpxOriginalPoints.isNotEmpty()) {
        val gpxGeoPoints = state.gpxOriginalPoints.map { GeoPoint(it.lat, it.lng) }
        val gpxLine = RouteMarkerHelper.createGpxOriginalPolyline(mapView, gpxGeoPoints)
        routeFolder.add(gpxLine)
    }

    state.alternatives.forEach { alt ->
        val altGeoPoints = alt.points.map { GeoPoint(it.lat, it.lng) }
        val altLine = RouteMarkerHelper.createAlternativePolyline(mapView, altGeoPoints, alt.index)
        routeFolder.add(altLine)
    }
    state.selectedLegKey?.let { selLegKey ->
        state.legAlternatives[selLegKey]?.forEach { alt ->
            val altGeoPoints = alt.points.map { GeoPoint(it.lat, it.lng) }
            val altLine = RouteMarkerHelper.createAlternativePolyline(mapView, altGeoPoints, alt.index - 1).apply {
                setOnClickListener { _, _, _ ->
                    viewModel.selectAlternativeForLeg(selLegKey, alt)
                    true
                }
            }
            routeFolder.add(altLine)
        }
    }

    val wps = state.waypoints
    for (i in 0 until wps.size - 1) {
        val wpFrom = wps[i]
        val wpTo = wps[i + 1]
        val legKey = "${wpFrom.id}_to_${wpTo.id}"

        val isLoading = state.loadingLegKeys.contains(legKey)
        val isFailed = state.failedLegs.containsKey(legKey)
        val legData = state.legs[legKey]
        val isLegSelected = state.selectedLegKey == legKey
        val isLegDimmed = hasAnySelection && !isLegSelected

        if (isLoading) {
            val linePts = listOf(GeoPoint(wpFrom.lat, wpFrom.lng), GeoPoint(wpTo.lat, wpTo.lng))
            val loadingLine = RouteMarkerHelper.createDashedLegPolyline(
                mapView,
                linePts,
                isLoading = true,
                isDark = isDark,
                isDimmed = isLegDimmed,
                isSelected = isLegSelected,
                danskStiColors = mapColors
            )
            routeFolder.add(loadingLine)
        } else if (isFailed) {
            val linePts = listOf(GeoPoint(wpFrom.lat, wpFrom.lng), GeoPoint(wpTo.lat, wpTo.lng))
            val failedLine = RouteMarkerHelper.createFailedLegPolyline(
                mapView,
                linePts,
                isDimmed = isLegDimmed,
                danskStiColors = mapColors
            ).apply {
                setOnClickListener { _, _, _ ->
                    viewModel.selectLeg(legKey)
                    true
                }
            }
            routeFolder.add(failedLine)
        } else if (legData != null) {
            val geoPoints = legData.points.map { GeoPoint(it.lat, it.lng) }
            val legDesc = RouteMarkerHelper.formatLegTalkBackDescription(
                legIndex = i,
                lengthKm = legData.lengthKm,
                ascentM = legData.ascentM,
                legMode = legData.legMode
            )
            if (legData.legMode == LegMode.STRAIGHT) {
                val straightLine = RouteMarkerHelper.createDashedLegPolyline(
                    mapView,
                    geoPoints,
                    isLoading = false,
                    isDark = isDark,
                    isDimmed = isLegDimmed,
                    isSelected = isLegSelected,
                    danskStiColors = mapColors
                ).apply {
                    title = legDesc
                    setOnClickListener { _, _, _ ->
                        viewModel.selectLeg(legKey)
                        true
                    }
                }
                routeFolder.add(straightLine)
            } else {
                val casing = RouteMarkerHelper.createCasingPolyline(
                    mapView,
                    geoPoints,
                    isDark = isDark,
                    isDimmed = isLegDimmed,
                    isSelected = isLegSelected,
                    danskStiColors = mapColors
                )
                val core = RouteMarkerHelper.createSnappedLegPolyline(
                    mapView,
                    geoPoints,
                    legData.hasAccessWarning,
                    isDark = isDark,
                    isDimmed = isLegDimmed,
                    isSelected = isLegSelected,
                    danskStiColors = mapColors
                ).apply {
                    title = legDesc
                    setOnClickListener { _, _, _ ->
                        viewModel.selectLeg(legKey)
                        true
                    }
                }
                routeFolder.add(casing)
                routeFolder.add(core)
            }
        }
    }

    if (mapView.zoomLevelDouble >= 13.0) {
        val ghostHandles = viewModel.getLegMidpointHandles()
        ghostHandles.forEach { handle ->
            val ghostMarker = Marker(mapView).apply {
                position = GeoPoint(handle.lat, handle.lng)
                title = "Indsæt viapunkt på strækning ${handle.legIndex + 1}"
                icon = RouteMarkerHelper.createGhostHandleIcon(context, isDimmed = hasAnySelection)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                isDraggable = false
            }
            routeFolder.add(ghostMarker)
        }
    }

    // Round 11 Item 5: Do not draw the planner's own shelter/POI markers where the map's shelter/POI layer
    // already shows them. Delegate planner POIs to overlayManager so there is strictly one marker per place.
    overlayManager.setPlannerPois(state.nearbyPoisForPlan)

    val visibleWaypoints = wps.filter { !it.isHelper }
    var overnightDayCounter = 0
    var runningKmForPins = 0.0
    visibleWaypoints.forEachIndexed { idx, wp ->
        if (idx > 0) {
            val prevWp = visibleWaypoints[idx - 1]
            runningKmForPins += state.legs["${prevWp.id}_to_${wp.id}"]?.lengthKm ?: 0.0
        }
        val dayNumForPin = if (wp.role == WaypointRole.OVERNIGHT) {
            overnightDayCounter += 1
            overnightDayCounter
        } else {
            1
        }
        val wpDetourDist = wp.detourDistanceKm
        val badgeText: String? = when {
            state.draggingWaypointId == wp.id && state.draggingTrailKmLabel != null -> state.draggingTrailKmLabel
            wpDetourDist != null && wpDetourDist > 0.01 -> {
                String.format(Locale.GERMANY, "+%.1f km", wpDetourDist)
            }
            wp.trailKm != null && (wp.role == WaypointRole.START || wp.role == WaypointRole.END || wp.role == WaypointRole.OVERNIGHT) -> {
                String.format(Locale.GERMANY, "km %.1f", wp.trailKm)
            }
            else -> null
        }

        val isSelected = wp.id == state.selectedWaypointId
        val isDimmed = hasAnySelection && !isSelected
        val talkBackDesc = RouteMarkerHelper.formatPinTalkBackDescription(
            index = idx,
            waypoint = wp,
            cumulativeKm = wp.trailKm ?: runningKmForPins
        )

        val marker = Marker(mapView).apply {
            position = GeoPoint(wp.lat, wp.lng)
            title = talkBackDesc
            subDescription = talkBackDesc
            icon = RouteMarkerHelper.createWaypointIcon(
                context = context,
                role = wp.role,
                index = idx,
                isSelected = isSelected,
                badgeLabel = badgeText,
                dayNumber = dayNumForPin,
                isDimmed = isDimmed,
                danskStiColors = mapColors
            )
            setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_CENTER
            )
            setInfoWindow(null)
            isDraggable = false

            setOnMarkerClickListener { _, _ ->
                onWaypointClicked(wp)
                true
            }
        }
        routeFolder.add(marker)
    }

    state.scrubbedPointOnMap?.let { scrub ->
        val cursorMarker = Marker(mapView).apply {
            position = GeoPoint(scrub.lat, scrub.lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            val bmp = android.graphics.Bitmap.createBitmap(36, 36, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = mapColors.routeCasing.toArgb()
                style = android.graphics.Paint.Style.FILL
            }
            cv.drawCircle(18f, 18f, 16f, p)
            p.color = mapColors.success.toArgb()
            cv.drawCircle(18f, 18f, 11f, p)
            icon = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        }
        routeFolder.add(cursorMarker)
    }

    overlayManager.updateFolderOverlay(MapLayersOverlayManager.KEY_ROUTE, routeFolder)
}

internal fun zoomToRoute(mapView: MapView, waypoints: List<RouteWaypoint>) {
    if (waypoints.isEmpty()) return
    var minLat = 90.0
    var maxLat = -90.0
    var minLng = 180.0
    var maxLng = -180.0

    for (wp in waypoints) {
        if (wp.lat < minLat) minLat = wp.lat
        if (wp.lat > maxLat) maxLat = wp.lat
        if (wp.lng < minLng) minLng = wp.lng
        if (wp.lng > maxLng) maxLng = wp.lng
    }

    val latPad = (maxLat - minLat).coerceAtLeast(0.01) * 0.2
    val lngPad = (maxLng - minLng).coerceAtLeast(0.01) * 0.2

    val box = BoundingBox(maxLat + latPad, maxLng + lngPad, minLat - latPad, minLng - lngPad)
    mapView.zoomToBoundingBox(box, true, 100)
}

internal fun getLastKnownLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (_: SecurityException) {
        null
    }
}

@Composable
internal fun RouteBuilderBottomSelectionAndSnackbarStack(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    viewModel: RouteBuilderViewModel,
    onRenameWaypointRequest: (String, String) -> Unit
) {
    uiState.deletedPinSnackbarMessage?.let { snackMsg ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("deleted_pin_snackbar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snackMsg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.undoLastDelete() },
                    modifier = Modifier.testTag("deleted_pin_undo_button")
                ) {
                    Text(
                        text = stringResource(R.string.snackbar_undo),
                        color = MaterialTheme.colorScheme.inversePrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    val selectedWpIdx = visibleWaypoints.indexOfFirst { it.id == uiState.selectedWaypointId }
    if (selectedWpIdx != -1) {
        val selectedWp = visibleWaypoints[selectedWpIdx]
        val targetLegWpId = if (selectedWp.role == WaypointRole.END && visibleWaypoints.size >= 2) {
            visibleWaypoints[visibleWaypoints.size - 2].id
        } else {
            selectedWp.id
        }
        PinSelectionActionBar(
            waypoint = selectedWp,
            waypointIndex = selectedWpIdx,
            onDelete = { viewModel.deleteWaypointWithUndoSnackbar(selectedWp.id) },
            onToggleOvernight = { viewModel.toggleWaypointOvernight(selectedWp.id) },
            onToggleLegMode = { viewModel.toggleLegMode(targetLegWpId) },
            onSnapToNearestTrail = { viewModel.snapWaypointToNearestTrail(selectedWp.id) },
            onRenameClick = { onRenameWaypointRequest(selectedWp.id, selectedWp.name ?: "") },
            onClose = { viewModel.selectWaypoint(null) }
        )
    }

    uiState.selectedLegKey?.let { legKey ->
        val parts = legKey.split("_to_")
        val fromIdx = if (parts.size == 2) visibleWaypoints.indexOfFirst { it.id == parts[0] } else -1
        if (fromIdx != -1) {
            val fromWp = visibleWaypoints[fromIdx]
            val legData = uiState.legs[legKey]
            val legAlts = uiState.legAlternatives[legKey].orEmpty()
            SelectedLegCard(
                legKey = legKey,
                legIndex = fromIdx,
                leg = legData,
                fromWaypoint = fromWp,
                alternatives = legAlts,
                isFetchingAlternatives = uiState.isFetchingLegAlternatives,
                onToggleMode = { viewModel.toggleLegMode(fromWp.id) },
                onFetchAlternatives = { viewModel.fetchAlternativesForLeg(legKey) },
                onSelectAlternative = { alt -> viewModel.selectAlternativeForLeg(legKey, alt) },
                onClose = { viewModel.selectLeg(null) }
            )
        }
    }
}

@Composable
internal fun RouteBuilderFloatingToolbar(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    viewModel: RouteBuilderViewModel,
    onOpenSaveDialog: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("planner_floating_toolbar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (uiState.canUndo) 0.7f else 0.3f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = uiState.canUndo) { viewModel.undo() }
                    .testTag("btn_undo")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.planner_tool_undo),
                        tint = if (uiState.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.planner_tool_undo),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (uiState.canRedo) 0.7f else 0.3f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = uiState.canRedo) { viewModel.redo() }
                    .testTag("btn_redo")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = stringResource(R.string.planner_tool_redo),
                        tint = if (uiState.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.planner_tool_redo),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            val isStraightNext = uiState.nextLegMode == LegMode.STRAIGHT
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isStraightNext) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { viewModel.toggleNextLegMode() }
                    .testTag("btn_toggle_leg_mode")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isStraightNext) Icons.Default.Timeline else Icons.Default.Route,
                        contentDescription = if (isStraightNext) stringResource(R.string.planner_leg_mode_straight_line) else stringResource(R.string.planner_leg_mode_follow_trails),
                        tint = if (isStraightNext) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isStraightNext) stringResource(R.string.planner_leg_mode_straight_line) else stringResource(R.string.planner_leg_mode_follow_trails),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isStraightNext) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (uiState.isReverse) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = visibleWaypoints.size >= 2) { viewModel.reverseRoute() }
                    .testTag("btn_reverse_route")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = stringResource(R.string.planner_tool_reverse),
                        tint = if (uiState.isReverse) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.planner_tool_reverse),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (uiState.isReverse) FontWeight.Bold else FontWeight.Medium,
                        color = if (uiState.isReverse) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (visibleWaypoints.size >= 2) 0.7f else 0.3f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = visibleWaypoints.size >= 2) { viewModel.closeRouteLoop() }
                    .testTag("btn_close_route_loop")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Loop,
                        contentDescription = stringResource(R.string.planner_menu_close_loop),
                        tint = if (visibleWaypoints.size >= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.planner_menu_close_loop),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (visibleWaypoints.size >= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (visibleWaypoints.size >= 2) 0.7f else 0.3f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = visibleWaypoints.size >= 2) { viewModel.appendOutAndBack() }
                    .testTag("btn_out_and_back")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AltRoute,
                        contentDescription = stringResource(R.string.planner_menu_out_and_back),
                        tint = if (visibleWaypoints.size >= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.planner_menu_out_and_back),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (visibleWaypoints.size >= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (uiState.isOvernightToolActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { viewModel.toggleOvernightTool() }
                    .testTag("btn_overnight_tool")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NightShelter,
                        contentDescription = stringResource(R.string.nav_next_overnight),
                        tint = if (uiState.isOvernightToolActive) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.nav_next_overnight),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (uiState.isOvernightToolActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (uiState.isOvernightToolActive) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Button(
                onClick = onOpenSaveDialog,
                enabled = visibleWaypoints.size >= 2 && !uiState.isSaving && !uiState.isStarting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("btn_save_route")
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.bookmark_add_cd), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_save), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

