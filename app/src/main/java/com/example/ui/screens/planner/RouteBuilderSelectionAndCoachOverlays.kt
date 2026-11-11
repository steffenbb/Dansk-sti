package com.example.ui.screens.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteAlternativeInfo
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.util.Locale
import kotlin.math.roundToInt

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
    if (!uiState.showCoachMarks || uiState.waypoints.isNotEmpty() || uiState.mode != PlannerMode.FREE || uiState.sheetHeightState != SheetHeightState.PEEK) return

    val tips = listOf(
        stringResource(R.string.planner_coach_tip1_title) to stringResource(R.string.planner_coach_tip1_subtitle),
        stringResource(R.string.planner_coach_tip2_title) to stringResource(R.string.planner_coach_tip2_subtitle),
        stringResource(R.string.planner_coach_tip3_title) to stringResource(R.string.planner_coach_tip3_subtitle)
    )
    val stepIndex = uiState.coachMarkStep.coerceIn(0, tips.lastIndex)
    val coachIntroCd = stringResource(R.string.planner_coach_intro_cd)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp)
            .testTag("planner_coach_marks"),
        contentAlignment = Alignment.TopCenter
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .semantics { contentDescription = coachIntroCd }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.planner_coach_header_format, stepIndex + 1),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("coach_marks_step_header")
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("coach_marks_close_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.planner_coach_close_cd),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                tips.forEachIndexed { idx, (title, subtitle) ->
                    val isCurrent = idx == stepIndex
                    Surface(
                        shape = RoundedCornerShape(4.dp),
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
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${idx + 1}. $title",
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                maxLines = 1,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isCurrent) {
                                Text(
                                    text = subtitle,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .testTag("coach_marks_dismiss_button")
                    ) {
                        Text(stringResource(R.string.planner_coach_understood), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onNextStep,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .testTag("coach_marks_next_button")
                    ) {
                        Text(
                            text = if (stepIndex < tips.lastIndex) stringResource(R.string.planner_coach_next_tip) else stringResource(R.string.planner_coach_start_planning),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
