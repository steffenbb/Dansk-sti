package com.example.ui.screens.planner

import android.graphics.Point
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
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

data class ActivePinDragState(
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
 * Round 10A Item 3:
 * osmdroid [org.osmdroid.views.overlay.Overlay] that handles pin and ghost-handle dragging.
 * Its [onTouchEvent] returns `true` ONLY when a visible waypoint pin or ghost handle is hit,
 * allowing all other touches (pan, fling, pinch-zoom, double-tap zoom in Kig and Tegn mode)
 * to pass directly to [MapView].
 */
class PlannerPinDragMapOverlay(
    var viewModel: RouteBuilderViewModel,
    var uiStateProvider: () -> RouteBuilderState = { viewModel.uiState.value },
    var onActiveDragChanged: (ActivePinDragState?) -> Unit = {},
    var onHapticMove: () -> Unit = {},
    var onHapticTrash: () -> Unit = {}
) : org.osmdroid.views.overlay.Overlay() {

    private var activeSession: PlannerDragGestureSession? = null
    private var currentActiveDrag: ActivePinDragState? = null
    private var hitWaypointOnDown: RouteWaypoint? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var activePointerId: Int = android.view.MotionEvent.INVALID_POINTER_ID
    private var lastSnappedState: Boolean = false

    val isDraggingPinOrHandle: Boolean
        get() = activeSession?.isDragging == true

    val hasActiveHitSession: Boolean
        get() = activeSession != null

    private fun updateDragState(newState: ActivePinDragState?) {
        currentActiveDrag = newState
        onActiveDragChanged(newState)
    }

    override fun draw(c: android.graphics.Canvas?, osmv: MapView?, shadow: Boolean) {
        // Visual feedback (magnifier, dashed lines, trash zone) is drawn by the non-touch Compose layer.
    }

    override fun onTouchEvent(event: android.view.MotionEvent?, mapView: MapView?): Boolean {
        if (event == null || mapView == null || !isEnabled) return false
        val stateSnapshot = uiStateProvider()
        if (stateSnapshot.isCrosshairMode) {
            if (activeSession != null) {
                activeSession?.onSecondFingerDownOrCancel()
                activeSession = null
                hitWaypointOnDown = null
                activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                updateDragState(null)
            }
            return false
        }

        val density = mapView.context.resources.displayMetrics.density
        val touchRadiusPx = 26f * density
        val pinAboveFingerPx = 48f * density
        val trashZoneHeightPx = 96f * density
        val touchSlopPx = android.view.ViewConfiguration.get(mapView.context).scaledTouchSlop.toFloat()

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount > 1) return false
                val proj = mapView.projection ?: return false
                val x = event.x
                val y = event.y
                val screenPt = Point()

                // 1. Check if press is within 26dp of any visible waypoint pin
                val visibleWps = stateSnapshot.visibleWaypoints
                var hitWaypoint: RouteWaypoint? = null
                var hitWaypointIdx = -1
                var bestPinDist = touchRadiusPx

                for ((idx, wp) in visibleWps.withIndex()) {
                    proj.toPixels(GeoPoint(wp.lat, wp.lng), screenPt)
                    val d = hypot(x - screenPt.x.toFloat(), y - screenPt.y.toFloat())
                    if (d <= bestPinDist) {
                        bestPinDist = d
                        hitWaypoint = wp
                        hitWaypointIdx = idx
                    }
                }

                // 2. If no pin hit and zoom >= 13, check ghost handles at leg midpoints
                var hitGhostHandle: LegMidpointHandle? = null
                if (hitWaypoint == null && mapView.zoomLevelDouble >= 13.0) {
                    val handles = viewModel.getLegMidpointHandles()
                    var bestGhostDist = touchRadiusPx
                    for (h in handles) {
                        proj.toPixels(GeoPoint(h.lat, h.lng), screenPt)
                        val d = hypot(x - screenPt.x.toFloat(), y - screenPt.y.toFloat())
                        if (d <= bestGhostDist) {
                            bestGhostDist = d
                            hitGhostHandle = h
                        }
                    }
                }

                // Return false when neither a pin nor a ghost handle is hit so MapView pans/zooms freely!
                if (hitWaypoint == null && hitGhostHandle == null) {
                    return false
                }

                hitWaypointOnDown = hitWaypoint
                activeSession = PlannerDragGestureSession(
                    viewModel = viewModel,
                    touchSlopPx = touchSlopPx,
                    hitWaypoint = hitWaypoint,
                    hitWaypointIdx = hitWaypointIdx,
                    hitGhostHandle = hitGhostHandle
                )
                downX = x
                downY = y
                activePointerId = event.getPointerId(0)
                lastSnappedState = false
                mapView.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // If a second finger goes down during a pin drag, cancel the drag and let MapView pinch-zoom
                val session = activeSession
                if (session != null) {
                    session.onSecondFingerDownOrCancel()
                    activeSession = null
                    hitWaypointOnDown = null
                    activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                    updateDragState(null)
                }
                return false
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val session = activeSession ?: return false
                if (event.pointerCount > 1) {
                    session.onSecondFingerDownOrCancel()
                    activeSession = null
                    hitWaypointOnDown = null
                    activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                    updateDragState(null)
                    return false
                }
                val ptrIdx = event.findPointerIndex(activePointerId).let { if (it >= 0) it else 0 }
                val x = event.getX(ptrIdx)
                val y = event.getY(ptrIdx)
                val moveDist = hypot(x - downX, y - downY)

                if (!session.isDragging) {
                    val started = session.onMoveDistance(moveDist)
                    if (started) {
                        onHapticMove()
                    }
                }

                if (session.isDragging) {
                    val wpId = session.draggedWpId ?: return true
                    val fingerPos = Offset(x, y)
                    val pinPos = Offset(fingerPos.x, (fingerPos.y - pinAboveFingerPx).coerceAtLeast(16f))
                    val activeProj = mapView.projection ?: return true
                    val geo = activeProj.fromPixels(pinPos.x.toInt(), pinPos.y.toInt())
                    val rawLat = geo.latitude
                    val rawLng = geo.longitude

                    val snapRes = viewModel.snapCoordinateWithin25m(rawLat, rawLng)
                    if (snapRes.snapped && !lastSnappedState) {
                        onHapticMove()
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
                    val viewHeight = mapView.height.takeIf { it > 0 } ?: 1000
                    val overTrash = fingerPos.y >= (viewHeight - trashZoneHeightPx)

                    updateDragState(
                        ActivePinDragState(
                            waypointId = wpId,
                            role = session.draggedRole,
                            waypointIndex = if (idxInAll >= 0) idxInAll else session.draggedIdx,
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
                    )
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                val session = activeSession ?: return false
                val finalDrag = currentActiveDrag
                val tappedWp = hitWaypointOnDown
                activeSession = null
                hitWaypointOnDown = null
                activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                updateDragState(null)

                if (session.isDragging && finalDrag != null) {
                    session.onRelease(
                        finalLat = finalDrag.currentLat,
                        finalLng = finalDrag.currentLng,
                        isOverTrashZone = finalDrag.isOverTrashZone,
                        onTrashHaptic = onHapticTrash
                    )
                } else if (tappedWp != null) {
                    viewModel.selectWaypoint(tappedWp.id)
                }
                return true
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                val session = activeSession ?: return false
                session.onSecondFingerDownOrCancel()
                activeSession = null
                hitWaypointOnDown = null
                activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                updateDragState(null)
                return true
            }

            else -> return activeSession != null
        }
    }
}

/**
 * Round 10A Item 3:
 * Non-touch drawing layer for pin/ghost-handle drag feedback:
 * - Draws the pin 48 dp above the finger
 * - Shows a 64 dp magnifier circle
 * - Draws dashed straight lines to the neighbouring pins
 * - Shows the length change ("+0,8 km")
 * - Shows a trash zone at the bottom while dragging
 * Touch handling lives in [PlannerPinDragMapOverlay] registered on the [MapView] so the map pans/zooms freely.
 */
@Composable
fun RouteBuilderPinDragOverlay(
    mapView: MapView?,
    uiState: RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    overlayManager: com.example.ui.navigation.MapLayersOverlayManager? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val currentUiState by rememberUpdatedState(uiState)

    val magnifierRadiusPx = with(density) { 32.dp.toPx() }
    var activeDrag by remember { mutableStateOf<ActivePinDragState?>(null) }

    val pinDragOverlay = remember(viewModel) {
        PlannerPinDragMapOverlay(
            viewModel = viewModel,
            uiStateProvider = { currentUiState },
            onActiveDragChanged = { dragState -> activeDrag = dragState },
            onHapticMove = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onHapticTrash = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        )
    }
    pinDragOverlay.viewModel = viewModel
    pinDragOverlay.uiStateProvider = { currentUiState }
    pinDragOverlay.onActiveDragChanged = { dragState -> activeDrag = dragState }

    androidx.compose.runtime.LaunchedEffect(mapView, overlayManager, pinDragOverlay, uiState.waypoints) {
        val mv = mapView ?: return@LaunchedEffect
        if (overlayManager != null) {
            overlayManager.registerOverlay(
                com.example.ui.navigation.MapLayersOverlayManager.KEY_PLANNER_PIN_DRAG,
                pinDragOverlay
            )
        } else if (!mv.overlays.contains(pinDragOverlay)) {
            mv.overlays.add(pinDragOverlay)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("pin_drag_touch_overlay")
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
