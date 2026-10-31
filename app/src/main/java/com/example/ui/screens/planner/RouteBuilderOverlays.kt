package com.example.ui.screens.planner

import android.graphics.Point
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * UI Round 1 Item 1:
 * Add a Kig / Tegn toggle at the top of the planner (segmented button, 48 dp).
 */
@Composable
fun KigTegnSegmentedToggle(
    interactionMode: PlannerInteractionMode,
    onModeChange: (PlannerInteractionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("kig_tegn_toggle"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isKig = interactionMode == PlannerInteractionMode.KIG
            val kigCd = stringResource(R.string.planner_mode_kig_cd)
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isKig) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onModeChange(PlannerInteractionMode.KIG) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { contentDescription = kigCd }
                    .testTag("mode_kig_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isKig) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.planner_mode_kig),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isKig) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val isTegn = interactionMode == PlannerInteractionMode.TEGN
            val tegnCd = stringResource(R.string.planner_mode_tegn_cd)
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isTegn) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onModeChange(PlannerInteractionMode.TEGN) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { contentDescription = tegnCd }
                    .testTag("mode_tegn_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isTegn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.planner_mode_tegn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isTegn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Round 9A Item 7 & 8:
 * Encapsulates pin drag and ghost handle drag state transitions:
 * - Item 7: Ghost handles insert the via point ONLY when the drag passes touch slop;
 *   if the gesture is cancelled, it removes the via point again.
 * - Item 8: If a second finger goes down during a pin drag, it cancels the drag
 *   (the pin returns to its old place) and lets the map zoom.
 */
class PlannerDragGestureSession(
    private val viewModel: RouteBuilderViewModel,
    val touchSlopPx: Float,
    private val hitWaypoint: RouteWaypoint? = null,
    hitWaypointIdx: Int = -1,
    private val hitGhostHandle: LegMidpointHandle? = null
) {
    val originalLat: Double? = hitWaypoint?.lat
    val originalLng: Double? = hitWaypoint?.lng
    val originalTrailKm: Double? = hitWaypoint?.trailKm

    var isDragging: Boolean = false
        private set
    var isCancelled: Boolean = false
        private set
    var draggedWpId: String? = hitWaypoint?.id
        private set
    var insertedGhostWpId: String? = null
        private set
    var draggedRole: WaypointRole = hitWaypoint?.role ?: WaypointRole.VIA
        private set
    var draggedIdx: Int = if (hitWaypointIdx >= 0) hitWaypointIdx else 1
        private set

    fun onMoveDistance(moveDistancePx: Float): Boolean {
        if (isCancelled || isDragging) return false
        if (moveDistancePx <= touchSlopPx) return false
        isDragging = true
        if (hitGhostHandle != null && draggedWpId == null) {
            val insertedId = viewModel.startGhostHandleDrag(
                hitGhostHandle.lat,
                hitGhostHandle.lng
            )
            insertedGhostWpId = insertedId
            draggedWpId = insertedId
            draggedRole = WaypointRole.VIA
            val updatedVisible = viewModel.uiState.value.visibleWaypoints
            draggedIdx = updatedVisible.indexOfFirst { it.id == insertedId }.coerceAtLeast(1)
        }
        return true
    }

    fun onSecondFingerDownOrCancel() {
        if (isCancelled) return
        isCancelled = true
        isDragging = false
        val ghostId = insertedGhostWpId
        if (ghostId != null) {
            insertedGhostWpId = null
            draggedWpId = null
            viewModel.cancelGhostHandleDrag(ghostId)
        } else if (hitWaypoint != null && originalLat != null && originalLng != null) {
            viewModel.cancelPinDrag(
                waypointId = hitWaypoint.id,
                originalLat = originalLat,
                originalLng = originalLng,
                originalTrailKm = originalTrailKm
            )
        }
    }

    fun onRelease(
        finalLat: Double,
        finalLng: Double,
        isOverTrashZone: Boolean = false,
        onTrashHaptic: () -> Unit = {}
    ) {
        if (isCancelled || !isDragging) return
        isDragging = false
        val wpId = draggedWpId ?: return
        if (isOverTrashZone) {
            onTrashHaptic()
            viewModel.deleteWaypointWithUndoSnackbar(wpId)
        } else {
            viewModel.moveWaypoint(wpId, finalLat, finalLng)
        }
    }
}

private data class ActivePinDragState(
    val waypointId: String,
    val role: WaypointRole,
    val waypointIndex: Int,
    val fingerOffset: Offset,
    val pinOffset: Offset,
    val currentLat: Double,
    val currentLng: Double,
    val isSnapped: Boolean,
    val snapLabel: String?,
    val deltaKm: Double,
    val prevScreenOffset: Offset?,
    val nextScreenOffset: Offset?,
    val isOverTrashZone: Boolean
)

/**
 * UI Round 1 Item 4 & 5:
 * Custom touch overlay for immediate press-and-move dragging of pins and ghost handles (no long-press).
 * - Draws the pin 48 dp above the finger
 * - Shows a 64 dp magnifier circle
 * - Draws dashed straight lines to the neighbouring pins
 * - Shows the length change ("+0,8 km")
 * - Snaps within 25 m to the trail line, a shelter or a fri teltning area with a haptic tick
 * - Shows a trash zone at the bottom while dragging; dropping there deletes the pin with a "Punkt slettet · Fortryd" snackbar
 * - Calls moveWaypoint once on release
 */
@Composable
fun RouteBuilderPinDragOverlay(
    mapView: MapView?,
    uiState: RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val currentMapView by rememberUpdatedState(mapView)
    val currentUiState by rememberUpdatedState(uiState)

    val touchRadiusPx = with(density) { 26.dp.toPx() }
    val pinAboveFingerPx = with(density) { 48.dp.toPx() }
    val magnifierSizeDp = 64.dp
    val magnifierRadiusPx = with(density) { 32.dp.toPx() }
    val trashZoneHeightPx = with(density) { 96.dp.toPx() }

    var activeDrag by remember { mutableStateOf<ActivePinDragState?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("pin_drag_touch_overlay")
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val mv = currentMapView ?: return@awaitEachGesture
                    val stateSnapshot = currentUiState
                    if (stateSnapshot.isCrosshairMode) return@awaitEachGesture

                    val proj = mv.projection ?: return@awaitEachGesture
                    val downPos = down.position
                    val screenPt = Point()

                    // 1. Check if press is within 24dp (48dp diameter) of any visible waypoint pin
                    val visibleWps = stateSnapshot.visibleWaypoints
                    var hitWaypoint: RouteWaypoint? = null
                    var hitWaypointIdx = -1
                    var bestPinDist = touchRadiusPx

                    for ((idx, wp) in visibleWps.withIndex()) {
                        proj.toPixels(GeoPoint(wp.lat, wp.lng), screenPt)
                        val d = hypot(downPos.x - screenPt.x.toFloat(), downPos.y - screenPt.y.toFloat())
                        if (d <= bestPinDist) {
                            bestPinDist = d
                            hitWaypoint = wp
                            hitWaypointIdx = idx
                        }
                    }

                    // 2. If no pin hit and zoom >= 13, check ghost handles at leg midpoints
                    var hitGhostHandle: LegMidpointHandle? = null
                    if (hitWaypoint == null && mv.zoomLevelDouble >= 13.0) {
                        val handles = viewModel.getLegMidpointHandles()
                        var bestGhostDist = touchRadiusPx
                        for (h in handles) {
                            proj.toPixels(GeoPoint(h.lat, h.lng), screenPt)
                            val d = hypot(downPos.x - screenPt.x.toFloat(), downPos.y - screenPt.y.toFloat())
                            if (d <= bestGhostDist) {
                                bestGhostDist = d
                                hitGhostHandle = h
                            }
                        }
                    }

                    if (hitWaypoint == null && hitGhostHandle == null) {
                        return@awaitEachGesture
                    }

                    val gestureSession = PlannerDragGestureSession(
                        viewModel = viewModel,
                        touchSlopPx = touchSlop,
                        hitWaypoint = hitWaypoint,
                        hitWaypointIdx = hitWaypointIdx,
                        hitGhostHandle = hitGhostHandle
                    )
                    var lastSnappedState = false
                    var completedOrCancelled = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)

                            // Round 9A Item 8: If a second finger goes down during a pin drag,
                            // cancel the drag (pin returns to its old place) and let the map zoom (do not consume).
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount > 1 || event.changes.size > 1) {
                                gestureSession.onSecondFingerDownOrCancel()
                                activeDrag = null
                                completedOrCancelled = true
                                break
                            }

                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed) {
                                // Round 9A Item 7 & 8: Gesture cancelled externally
                                gestureSession.onSecondFingerDownOrCancel()
                                activeDrag = null
                                completedOrCancelled = true
                                break
                            }

                            if (!change.pressed) {
                                if (gestureSession.isDragging) {
                                    change.consume()
                                    val finalDrag = activeDrag
                                    activeDrag = null
                                    if (finalDrag != null) {
                                        gestureSession.onRelease(
                                            finalLat = finalDrag.currentLat,
                                            finalLng = finalDrag.currentLng,
                                            isOverTrashZone = finalDrag.isOverTrashZone,
                                            onTrashHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                        )
                                    }
                                }
                                completedOrCancelled = true
                                break
                            }

                            val moveDist = (change.position - downPos).getDistance()
                            if (!gestureSession.isDragging) {
                                val started = gestureSession.onMoveDistance(moveDist)
                                if (started) {
                                    change.consume()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }

                            if (gestureSession.isDragging) {
                                change.consume()
                                val wpId = gestureSession.draggedWpId ?: continue
                                val fingerPos = change.position
                                val pinPos = Offset(fingerPos.x, (fingerPos.y - pinAboveFingerPx).coerceAtLeast(16f))
                                val activeProj = mv.projection ?: continue
                                val geo = activeProj.fromPixels(pinPos.x.toInt(), pinPos.y.toInt())
                                val rawLat = geo.latitude
                                val rawLng = geo.longitude

                                val snapRes = viewModel.snapCoordinateWithin25m(rawLat, rawLng)
                                if (snapRes.snapped && !lastSnappedState) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                lastSnappedState = snapRes.snapped

                                val effectivePinPos = if (snapRes.snapped) {
                                    val snappedPt = Point()
                                    activeProj.toPixels(GeoPoint(snapRes.lat, snapRes.lng), snappedPt)
                                    Offset(snappedPt.x.toFloat(), snappedPt.y.toFloat())
                                } else {
                                    pinPos
                                }

                                val allWps = viewModel.uiState.value.visibleWaypoints
                                val idxInAll = allWps.indexOfFirst { it.id == wpId }
                                val prevScreen = if (idxInAll > 0) {
                                    val pt = Point()
                                    activeProj.toPixels(GeoPoint(allWps[idxInAll - 1].lat, allWps[idxInAll - 1].lng), pt)
                                    Offset(pt.x.toFloat(), pt.y.toFloat())
                                } else null
                                val nextScreen = if (idxInAll >= 0 && idxInAll < allWps.lastIndex) {
                                    val pt = Point()
                                    activeProj.toPixels(GeoPoint(allWps[idxInAll + 1].lat, allWps[idxInAll + 1].lng), pt)
                                    Offset(pt.x.toFloat(), pt.y.toFloat())
                                } else null

                                val deltaKm = viewModel.computeDragLengthDeltaKm(wpId, snapRes.lat, snapRes.lng)
                                val overTrash = fingerPos.y >= (size.height - trashZoneHeightPx)

                                activeDrag = ActivePinDragState(
                                    waypointId = wpId,
                                    role = gestureSession.draggedRole,
                                    waypointIndex = if (idxInAll >= 0) idxInAll else gestureSession.draggedIdx,
                                    fingerOffset = fingerPos,
                                    pinOffset = effectivePinPos,
                                    currentLat = snapRes.lat,
                                    currentLng = snapRes.lng,
                                    isSnapped = snapRes.snapped,
                                    snapLabel = snapRes.snapLabel,
                                    deltaKm = deltaKm,
                                    prevScreenOffset = prevScreen,
                                    nextScreenOffset = nextScreen,
                                    isOverTrashZone = overTrash
                                )
                            }
                        }
                    } finally {
                        if (!completedOrCancelled && gestureSession.isDragging) {
                            gestureSession.onSecondFingerDownOrCancel()
                            activeDrag = null
                        }
                    }
                }
            }
    ) {
        val drag = activeDrag
        if (drag != null) {
            val danskStiColors = DanskStiTheme.colors
            val scheme = MaterialTheme.colorScheme
            // Dashed straight lines to neighbouring pins + 64 dp magnifier circle + elevated pin
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
                drag.prevScreenOffset?.let { prev ->
                    drawLine(
                        color = danskStiColors.plannedRoute,
                        start = prev,
                        end = drag.pinOffset,
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect
                    )
                }
                drag.nextScreenOffset?.let { next ->
                    drawLine(
                        color = danskStiColors.plannedRoute,
                        start = drag.pinOffset,
                        end = next,
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect
                    )
                }

                // 64 dp magnifier circle around the elevated pin
                drawCircle(
                    color = scheme.surface.copy(alpha = 0.35f),
                    radius = magnifierRadiusPx,
                    center = drag.pinOffset
                )
                drawCircle(
                    color = if (drag.isSnapped) danskStiColors.success else danskStiColors.plannedRoute,
                    radius = magnifierRadiusPx,
                    center = drag.pinOffset,
                    style = Stroke(width = 3.dp.toPx())
                )
                // Crosshair ticks inside magnifier
                val chLen = 10.dp.toPx()
                drawLine(
                    color = scheme.onSurface,
                    start = Offset(drag.pinOffset.x - chLen, drag.pinOffset.y),
                    end = Offset(drag.pinOffset.x + chLen, drag.pinOffset.y),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = scheme.onSurface,
                    start = Offset(drag.pinOffset.x, drag.pinOffset.y - chLen),
                    end = Offset(drag.pinOffset.x, drag.pinOffset.y + chLen),
                    strokeWidth = 2.dp.toPx()
                )
                // Pin dot at center of magnifier
                val pinFill = when (drag.role) {
                    WaypointRole.START -> danskStiColors.success
                    WaypointRole.END -> scheme.inverseSurface
                    WaypointRole.OVERNIGHT -> danskStiColors.overnight
                    WaypointRole.VIA -> scheme.surface
                }
                drawCircle(
                    color = pinFill,
                    radius = 10.dp.toPx(),
                    center = drag.pinOffset
                )
                drawCircle(
                    color = danskStiColors.plannedRoute,
                    radius = 10.dp.toPx(),
                    center = drag.pinOffset,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Length change badge ("+0,8 km") above the 64 dp magnifier circle
            val deltaText = String.format(Locale.GERMAN, "%+.1f km", drag.deltaKm)
            val badgeLabel = if (drag.isSnapped && !drag.snapLabel.isNullOrBlank()) {
                "$deltaText · ${drag.snapLabel}"
            } else {
                deltaText
            }
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (drag.pinOffset.x - with(density) { 48.dp.toPx() }).roundToInt(),
                            y = (drag.pinOffset.y - magnifierRadiusPx - with(density) { 36.dp.toPx() })
                                .coerceAtLeast(8f)
                                .roundToInt()
                        )
                    }
                    .size(width = 96.dp, height = 28.dp)
                    .testTag("drag_length_delta_badge"),
                shape = RoundedCornerShape(14.dp),
                color = scheme.inverseSurface.copy(alpha = 0.93f),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = badgeLabel,
                        color = scheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // Bottom trash zone while dragging
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(
                        if (drag.isOverTrashZone) danskStiColors.danger.copy(alpha = 0.92f)
                        else scheme.inverseSurface.copy(alpha = 0.85f)
                    )
                    .testTag("drag_trash_zone"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.planner_trash_drop_cd),
                        tint = if (drag.isOverTrashZone) scheme.onError else scheme.inverseOnSurface,
                        modifier = Modifier.size(if (drag.isOverTrashZone) 28.dp else 24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (drag.isOverTrashZone) stringResource(R.string.planner_trash_release_to_delete) else stringResource(R.string.planner_trash_drag_here_to_delete),
                        color = if (drag.isOverTrashZone) scheme.onError else scheme.inverseOnSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * UI Round 1 Item 6:
 * Crosshair placement: a "+" button shows a fixed crosshair in the map centre and a "Tilføj her" button;
 * it adds a point exactly at the centre (snapping as in 4). "Annuller" closes it.
 */
@Composable
fun CrosshairPlacementOverlay(
    isCrosshairMode: Boolean,
    onOpenCrosshair: () -> Unit,
    onConfirmHere: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val crosshairCd = stringResource(R.string.planner_crosshair_open_cd)
    Box(modifier = modifier.fillMaxSize()) {
        if (!isCrosshairMode) {
            // "+" button to enter crosshair placement mode (placed on left side below search bar so it never overlaps right-side map controls)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 136.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onOpenCrosshair() }
                    .semantics { contentDescription = crosshairCd }
                    .testTag("open_crosshair_button"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = crosshairCd,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            val danskStiColors = DanskStiTheme.colors
            // Fixed crosshair in the exact map centre
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .testTag("map_center_crosshair")
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val ringRadius = 18.dp.toPx()
                val lineHalf = 28.dp.toPx()

                // Halo for contrast
                drawCircle(
                    color = danskStiColors.routeCasing,
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 4.dp.toPx())
                )
                drawLine(
                    color = danskStiColors.routeCasing,
                    start = Offset(cx - lineHalf, cy),
                    end = Offset(cx + lineHalf, cy),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = danskStiColors.routeCasing,
                    start = Offset(cx, cy - lineHalf),
                    end = Offset(cx, cy + lineHalf),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Inner precision crosshair
                drawCircle(
                    color = danskStiColors.plannedRoute,
                    radius = ringRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawLine(
                    color = danskStiColors.plannedRoute,
                    start = Offset(cx - lineHalf, cy),
                    end = Offset(cx + lineHalf, cy),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = danskStiColors.plannedRoute,
                    start = Offset(cx, cy - lineHalf),
                    end = Offset(cx, cy + lineHalf),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = danskStiColors.danger,
                    radius = 3.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }

            // "Tilføj her" and "Annuller" pill bar below the crosshair
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 128.dp)
                    .testTag("crosshair_actions_bar"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onConfirmHere,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("crosshair_add_here_button"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.planner_crosshair_add_here), fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("crosshair_cancel_button"),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        }
    }
}

/**
 * UI Round 1 Item 7:
 * Pin selection: tapping a pin selects it (larger icon with ring, rest of the route at 60 % opacity)
 * and shows a slim action bar above the planner sheet:
 * Slet, Overnat her / Almindeligt punkt, Følg stier / Lige linje, Flyt til nærmeste sti, Navngiv.
 * Replace the modal waypoint sheet with this bar. Tapping empty map closes it.
 */
@Composable
fun PinSelectionActionBar(
    waypoint: RouteWaypoint,
    waypointIndex: Int,
    onDelete: () -> Unit,
    onToggleOvernight: () -> Unit,
    onToggleLegMode: () -> Unit,
    onSnapToNearestTrail: () -> Unit,
    onRenameClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOvernight = waypoint.role == WaypointRole.OVERNIGHT
    val isStraight = waypoint.legModeToNext == LegMode.STRAIGHT

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("pin_selection_action_bar"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val title = waypoint.name?.takeIf { it.isNotBlank() }
                    ?: "Punkt ${waypointIndex + 1}"
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("pin_action_bar_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.planner_pin_bar_close_cd),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Slet
                AssistChip(
                    onClick = onDelete,
                    label = { Text(stringResource(R.string.btn_delete)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("pin_bar_delete")
                )

                // 2. Overnat her / Almindeligt punkt
                val overnightText = if (isOvernight) stringResource(R.string.planner_pin_bar_normal_point) else stringResource(R.string.planner_pin_bar_overnight_here)
                AssistChip(
                    onClick = onToggleOvernight,
                    label = { Text(overnightText) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.NightShelter,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("pin_bar_toggle_overnight")
                )

                // 3. Følg stier / Lige linje
                val legModeText = if (isStraight) stringResource(R.string.planner_leg_mode_follow_trails) else stringResource(R.string.planner_leg_mode_straight_line)
                AssistChip(
                    onClick = onToggleLegMode,
                    label = { Text(legModeText) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isStraight) Icons.Default.Route else Icons.Default.LinearScale,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("pin_bar_toggle_leg_mode")
                )

                // 4. Flyt til nærmeste sti
                AssistChip(
                    onClick = onSnapToNearestTrail,
                    label = { Text(stringResource(R.string.planner_pin_bar_snap_nearest_trail)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("pin_bar_snap_nearest_trail")
                )

                // 5. Navngiv
                AssistChip(
                    onClick = onRenameClick,
                    label = { Text(stringResource(R.string.planner_pin_bar_rename)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag("pin_bar_rename")
                )
            }
        }
    }
}

/**
 * UI Round 1 Item 9:
 * Tapping the route line selects that leg and shows a leg card:
 * length, ascent, mode (Følg stier / Lige linje), "Vis alternativer" (alternatives 1-3 for that leg only).
 */
@Composable
fun SelectedLegCard(
    legKey: String,
    legIndex: Int,
    leg: LegGeometryData?,
    fromWaypoint: RouteWaypoint,
    alternatives: List<RouteAlternativeInfo>,
    isFetchingAlternatives: Boolean,
    onToggleMode: () -> Unit,
    onFetchAlternatives: () -> Unit,
    onSelectAlternative: (RouteAlternativeInfo) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lengthKm = leg?.lengthKm ?: 0.0
    val ascentM = leg?.ascentM ?: 0.0
    val currentMode = leg?.legMode ?: fromWaypoint.legModeToNext
    val modeLabel = if (currentMode == LegMode.STRAIGHT) stringResource(R.string.planner_leg_mode_straight_line) else stringResource(R.string.planner_leg_mode_follow_trails)
    val toggleTargetLabel = if (currentMode == LegMode.STRAIGHT) stringResource(R.string.planner_leg_mode_follow_trails) else stringResource(R.string.planner_leg_mode_straight_line)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("selected_leg_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.planner_leg_card_title_format, legIndex + 1, modeLabel),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.GERMAN, "%.1f km · +%d m stigning", lengthKm, ascentM.roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("selected_leg_card_close")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.planner_leg_card_close_cd),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onToggleMode,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("leg_card_toggle_mode_button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (currentMode == LegMode.STRAIGHT) Icons.Default.Route else Icons.Default.LinearScale,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(toggleTargetLabel, fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = onFetchAlternatives,
                    enabled = !isFetchingAlternatives,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("leg_card_show_alternatives_button"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (isFetchingAlternatives) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.AltRoute,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.planner_leg_card_show_alternatives), fontSize = 12.sp)
                }
            }

            if (alternatives.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    alternatives.forEach { alt ->
                        FilterChip(
                            selected = false,
                            onClick = { onSelectAlternative(alt) },
                            label = {
                                Text(
                                    String.format(
                                        Locale.GERMAN,
                                        "Alt %d: %.1f km (+%d m)",
                                        alt.index,
                                        alt.lengthKm,
                                        alt.ascentM.roundToInt()
                                    ),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.testTag("leg_alt_chip_${alt.index}")
                        )
                    }
                }
            }
        }
    }
}

/**
 * UI Round 1 Item 11:
 * TalkBack: every pin and leg has a contentDescription like
 * "Punkt 3, overnatning, Shelter Skovbo, km 32,1".
 * Exposes accessible semantic nodes in the Compose tree for screen readers and UI tests.
 */
@Composable
fun TalkBackRouteSemanticsOverlay(
    uiState: RouteBuilderState,
    onSelectWaypoint: (String) -> Unit,
    onSelectLeg: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleWps = uiState.visibleWaypoints
    if (visibleWps.isEmpty()) return

    var runningKm = 0.0
    Box(modifier = modifier) {
        visibleWps.forEachIndexed { idx, wp ->
            if (idx > 0) {
                val prevWp = visibleWps[idx - 1]
                val legKey = "${prevWp.id}_to_${wp.id}"
                val leg = uiState.legs[legKey]
                val legKm = leg?.lengthKm ?: 0.0
                val legAsc = leg?.ascentM ?: 0.0
                val legMode = leg?.legMode ?: prevWp.legModeToNext
                val legDesc = RouteMarkerHelper.formatLegTalkBackDescription(
                    legIndex = idx - 1,
                    lengthKm = legKm,
                    ascentM = legAsc,
                    legMode = legMode
                )
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .semantics { contentDescription = legDesc }
                        .clickable { onSelectLeg(legKey) }
                        .testTag("talkback_leg_${idx - 1}")
                )
                runningKm += legKm
            }

            val pinKm = wp.trailKm ?: runningKm
            val pinDesc = RouteMarkerHelper.formatPinTalkBackDescription(
                index = idx,
                waypoint = wp,
                cumulativeKm = pinKm
            )
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics { contentDescription = pinDesc }
                    .clickable { onSelectWaypoint(wp.id) }
                    .testTag("talkback_pin_$idx")
            )
        }
    }
}

/**
 * UI Round 4 Item 1:
 * First-run coach marks in the planner (3 tips: tap to add, drag a point, drag the middle of a leg),
 * stored as seen in DataStore.
 */
@Composable
fun PlannerCoachMarksOverlay(
    uiState: RouteBuilderState,
    onNextStep: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!uiState.showCoachMarks || uiState.waypoints.isNotEmpty() || uiState.mode != com.example.ui.viewmodel.PlannerMode.FREE || uiState.sheetHeightState != com.example.ui.viewmodel.SheetHeightState.PEEK) return

    val tips = listOf(
        stringResource(R.string.planner_coach_tip1_title) to stringResource(R.string.planner_coach_tip1_subtitle),
        stringResource(R.string.planner_coach_tip2_title) to stringResource(R.string.planner_coach_tip2_subtitle),
        stringResource(R.string.planner_coach_tip3_title) to stringResource(R.string.planner_coach_tip3_subtitle)
    )
    val stepIndex = uiState.coachMarkStep.coerceIn(0, tips.lastIndex)
    val coachIntroCd = stringResource(R.string.planner_coach_intro_cd)

    Box(
        modifier = modifier
            .zIndex(20f)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .testTag("planner_coach_marks"),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .semantics { contentDescription = coachIntroCd }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.planner_coach_header_format, stepIndex + 1),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("coach_marks_step_header")
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("coach_marks_close_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.planner_coach_close_cd),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                tips.forEachIndexed { idx, (title, subtitle) ->
                    val isCurrent = idx == stepIndex
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("coach_mark_tip_$idx")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = "${idx + 1}. $title",
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isCurrent) {
                                Text(
                                    text = subtitle,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .testTag("coach_marks_dismiss_button")
                    ) {
                        Text(stringResource(R.string.planner_coach_understood), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onNextStep,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .testTag("coach_marks_next_button")
                    ) {
                        Text(
                            text = if (stepIndex < tips.lastIndex) stringResource(R.string.planner_coach_next_tip) else stringResource(R.string.planner_coach_start_planning),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

