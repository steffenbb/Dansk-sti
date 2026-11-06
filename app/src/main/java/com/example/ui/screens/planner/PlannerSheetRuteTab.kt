package com.example.ui.screens.planner

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.RouteAlternativeInfo
import com.example.ui.viewmodel.RouteBuilderState
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteOptions
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PlannerRuteTabContent(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    onWaypointClicked: (RouteWaypoint) -> Unit,
    onReorderWaypoints: (Int, Int) -> Unit,
    onDeleteWaypoint: (String) -> Unit,
    onCustomWaypointClicked: (UserCustomWaypointEntity) -> Unit,
    onDeleteCustomWaypoint: (String) -> Unit,
    onDeleteCustomWaypointPhoto: (String) -> Unit,
    onUpdateOptions: (RouteOptions) -> Unit,
    onOpenSaveDialog: () -> Unit,
    onStartNavigation: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.planner_waypoints_header_format, visibleWaypoints.size),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (visibleWaypoints.size >= 2) {
                Text(
                    text = stringResource(R.string.planner_waypoints_reorder_delete_hint),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (visibleWaypoints.isEmpty()) {
            Text(
                text = stringResource(R.string.planner_add_two_points_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            visibleWaypoints.forEachIndexed { idx, wp ->
                WaypointRow(
                    index = idx,
                    totalCount = visibleWaypoints.size,
                    waypoint = wp,
                    isSelected = uiState.selectedWaypointId == wp.id,
                    onClick = { onWaypointClicked(wp) },
                    onMoveUp = {
                        if (idx > 0) onReorderWaypoints(idx, idx - 1)
                    },
                    onMoveDown = {
                        if (idx < visibleWaypoints.size - 1) onReorderWaypoints(idx, idx + 1)
                    },
                    onDragReorderByOffset = { stepDelta ->
                        val targetIdx = (idx + stepDelta).coerceIn(0, visibleWaypoints.size - 1)
                        if (targetIdx != idx) {
                            onReorderWaypoints(idx, targetIdx)
                        }
                    },
                    onSwipeDelete = { onDeleteWaypoint(wp.id) }
                )
            }
        }

        if (uiState.customUserWaypoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.planner_my_points_count_format, uiState.customUserWaypoints.size),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            uiState.customUserWaypoints.forEach { customWp ->
                CustomWaypointRow(
                    waypoint = customWp,
                    onClick = { onCustomWaypointClicked(customWp) },
                    onDelete = { onDeleteCustomWaypoint(customWp.id) },
                    onDeletePhoto = if (!customWp.photoPath.isNullOrBlank()) {
                        { onDeleteCustomWaypointPhoto(customWp.id) }
                    } else null
                )
            }
        }
    }

    HorizontalDivider()

    PlannerRoutingOptionsSection(
        options = uiState.options,
        onUpdateOptions = onUpdateOptions
    )

    PlannerSaveAndStartRow(
        visibleWaypointsCount = visibleWaypoints.size,
        isSaving = uiState.isSaving,
        isStarting = uiState.isStarting,
        onOpenSaveDialog = onOpenSaveDialog,
        onStartNavigation = onStartNavigation
    )
}

@Composable
fun WaypointRow(
    index: Int,
    waypoint: RouteWaypoint,
    onClick: () -> Unit,
    totalCount: Int = 1,
    isSelected: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onDragReorderByOffset: (Int) -> Unit = {},
    onSwipeDelete: () -> Unit = {}
) {
    var horizontalSwipeOffsetPx by remember(waypoint.id) { mutableFloatStateOf(0f) }
    var verticalDragAccumPx by remember(waypoint.id, index) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 96.dp.toPx() }
    val rowStepPx = with(density) { 48.dp.toPx() }
    val dsColors = DanskStiTheme.colors

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(horizontalSwipeOffsetPx.roundToInt(), 0) }
            .pointerInput(waypoint.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(horizontalSwipeOffsetPx) >= swipeThresholdPx) {
                            horizontalSwipeOffsetPx = 0f
                            onSwipeDelete()
                        } else {
                            horizontalSwipeOffsetPx = 0f
                        }
                    },
                    onDragCancel = {
                        horizontalSwipeOffsetPx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalSwipeOffsetPx = (horizontalSwipeOffsetPx + dragAmount).coerceIn(-swipeThresholdPx * 1.5f, swipeThresholdPx * 1.5f)
                    }
                )
            }
            .clickable(onClick = onClick)
            .testTag("waypoint_row_${waypoint.id}")
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.planner_drag_to_reorder_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .pointerInput(waypoint.id, index) {
                        detectVerticalDragGestures(
                            onDragEnd = { verticalDragAccumPx = 0f },
                            onDragCancel = { verticalDragAccumPx = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                verticalDragAccumPx += dragAmount
                                if (abs(verticalDragAccumPx) >= rowStepPx) {
                                    val steps = (verticalDragAccumPx / rowStepPx).toInt()
                                    verticalDragAccumPx -= steps * rowStepPx
                                    onDragReorderByOffset(steps)
                                }
                            }
                        )
                    }
                    .testTag("waypoint_drag_handle_${waypoint.id}")
            )

            Spacer(modifier = Modifier.width(6.dp))

            Surface(
                shape = CircleShape,
                color = when (waypoint.role) {
                    WaypointRole.START -> dsColors.success
                    WaypointRole.END -> dsColors.danger
                    WaypointRole.OVERNIGHT -> dsColors.overnight
                    WaypointRole.VIA -> dsColors.plannedRoute
                },
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = when (waypoint.role) {
                            WaypointRole.START -> "A"
                            WaypointRole.END -> "B"
                            WaypointRole.OVERNIGHT -> "⛺"
                            WaypointRole.VIA -> "${index + 1}"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name ?: when (waypoint.role) {
                        WaypointRole.START -> stringResource(R.string.waypoint_role_start)
                        WaypointRole.END -> stringResource(R.string.waypoint_role_end)
                        WaypointRole.OVERNIGHT -> stringResource(R.string.waypoint_role_overnight)
                        WaypointRole.VIA -> stringResource(R.string.waypoint_role_via_format, index + 1)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val detourDist = waypoint.detourDistanceKm
                val subtitleText = when {
                    detourDist != null && detourDist.isFinite() && detourDist > 0.01 -> {
                        String.format(
                            Locale.GERMANY,
                            stringResource(R.string.planner_detour_format),
                            waypoint.name ?: stringResource(R.string.waypoint_default_point),
                            detourDist,
                            (waypoint.detourAscentM ?: 0.0).toInt()
                        )
                    }
                    waypoint.trailKm != null -> {
                        String.format(
                            Locale.GERMANY,
                            stringResource(R.string.waypoint_on_trail_km_format),
                            waypoint.trailKm
                        )
                    }
                    waypoint.legModeToNext == LegMode.STRAIGHT -> stringResource(R.string.waypoint_straight_line_to_next)
                    else -> stringResource(R.string.waypoint_follows_trails)
                }
                Text(
                    text = subtitleText,
                    fontSize = 11.sp,
                    color = if (detourDist != null && detourDist.isFinite() && detourDist > 0.01) {
                        dsColors.overnight
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (totalCount > 1) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("waypoint_move_up_$index")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.planner_move_waypoint_up),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("waypoint_move_down_$index")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.planner_move_waypoint_down),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onSwipeDelete,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("waypoint_delete_${waypoint.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.btn_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun AlternativeRouteCard(
    alt: RouteAlternativeInfo,
    onSelect: () -> Unit
) {
    val altColor = Color(
        RouteMarkerHelper.ALTERNATIVE_COLORS.getOrElse(alt.index) { RouteMarkerHelper.COLOR_SOLID_ROUTE }
    )

    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, altColor.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(altColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.planner_alternative_title_format, alt.index),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = String.format(
                            Locale.GERMANY,
                            stringResource(R.string.planner_alternative_stats_format),
                            alt.lengthKm,
                            alt.ascentM,
                            alt.pathSharePct ?: 0.0
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = altColor),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 30.dp)
            ) {
                Text(stringResource(R.string.planner_btn_choose), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CustomWaypointRow(
    waypoint: UserCustomWaypointEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDeletePhoto: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("custom_waypoint_row_${waypoint.id}")
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!waypoint.photoPath.isNullOrBlank()) {
                val bitmap = remember(waypoint.photoPath) {
                    try {
                        if (File(waypoint.photoPath).exists()) BitmapFactory.decodeFile(waypoint.photoPath) else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = waypoint.name,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.planner_photo_attached_cd),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ui_plannerbottomsheet_label_227), color = MaterialTheme.colorScheme.onTertiary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (waypoint.note.isNotBlank()) {
                    Text(
                        text = waypoint.note,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (onDeletePhoto != null && !waypoint.photoPath.isNullOrBlank()) {
                TextButton(
                    onClick = onDeletePhoto,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_delete_waypoint_photo_${waypoint.id}")
                ) {
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_fjern_foto_163),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("btn_delete_custom_waypoint_${waypoint.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.btn_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
