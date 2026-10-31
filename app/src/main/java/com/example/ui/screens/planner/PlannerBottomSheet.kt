package com.example.ui.screens.planner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.PlannerSheetTab
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.access.RouteAccessWarning
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import java.util.Locale
import kotlin.math.abs

@Composable
fun PlannerBottomSheet(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    onSetSheetHeightState: (SheetHeightState) -> Unit,
    onSuggestOvernightStops: () -> Unit,
    onStartNavigation: () -> Unit,
    onOpenTripSettings: () -> Unit,
    onOpenRundturDialog: () -> Unit,
    onOpenNearestPoiDialog: () -> Unit,
    onShareRoute: () -> Unit,
    onReplaceOvernight: (String) -> Unit,
    onDeleteWaypoint: (String) -> Unit,
    onZoomToCoordinate: (Double, Double) -> Unit,
    onRerouteAroundWarning: (RouteAccessWarning) -> Unit,
    onScrubbedPointChanged: (RoutePoint3D?) -> Unit,
    onOvernightMarkerDragged: (String, Double) -> Unit,
    onUpdateOptions: (RouteOptions) -> Unit,
    onWaypointClicked: (RouteWaypoint) -> Unit,
    onOpenSaveDialog: () -> Unit,
    onSelectSheetTab: (PlannerSheetTab) -> Unit = {},
    onSheetHeightFractionChanged: (Float) -> Unit = {},
    onReorderWaypoints: (Int, Int) -> Unit = { _, _ -> },
    onDayCardClicked: (PlannerDaySummary) -> Unit = {},
    onCustomWaypointClicked: (UserCustomWaypointEntity) -> Unit = {},
    onDeleteCustomWaypoint: (String) -> Unit = {},
    onDeleteCustomWaypointPhoto: (String) -> Unit = {},
    availableHeightDp: Float? = null,
    isSidePanel: Boolean = false,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Compute heights from the real available height (no 700 dp floor); the map always keeps at least 40 %
    val realAvailableHeightDp = (availableHeightDp ?: configuration.screenHeightDp.takeIf { it > 0 }?.toFloat() ?: 640f)
        .coerceAtLeast(180f)
    val maxFullSheetDp = realAvailableHeightDp * 0.60f
    val halfSheetDp = (realAvailableHeightDp * 0.42f).coerceAtMost(maxFullSheetDp)
    val peekSheetDp = (realAvailableHeightDp * 0.22f).coerceIn(minOf(110f, maxFullSheetDp), maxFullSheetDp)

    var dragDeltaDp by remember(uiState.sheetHeightState) { mutableFloatStateOf(0f) }

    val baseTargetDp = when (uiState.sheetHeightState) {
        SheetHeightState.PEEK -> peekSheetDp
        SheetHeightState.HALF -> halfSheetDp
        SheetHeightState.FULL -> maxFullSheetDp
    }
    val currentMaxSheetDp = (baseTargetDp - dragDeltaDp).coerceIn(peekSheetDp, maxFullSheetDp)
    val animatedMaxHeightDp by animateDpAsState(
        targetValue = currentMaxSheetDp.dp,
        label = "sheetMaxHeightDp"
    )

    val sheetDraggableState = rememberDraggableState { deltaPx ->
        val deltaDp = with(density) { deltaPx.toDp().value }
        dragDeltaDp = (dragDeltaDp + deltaDp).coerceIn(
            baseTargetDp - maxFullSheetDp,
            baseTargetDp - peekSheetDp
        )
        val newHeightDp = (baseTargetDp - dragDeltaDp).coerceIn(peekSheetDp, maxFullSheetDp)
        onSheetHeightFractionChanged((newHeightDp / realAvailableHeightDp).coerceIn(0.18f, 0.60f))
    }

    fun settleSheetDrag(velocityPxPerSec: Float) {
        val currentHeightDp = (baseTargetDp - dragDeltaDp).coerceIn(peekSheetDp, maxFullSheetDp)
        val targetState = when {
            velocityPxPerSec < -600f -> {
                when (uiState.sheetHeightState) {
                    SheetHeightState.PEEK -> SheetHeightState.HALF
                    SheetHeightState.HALF -> SheetHeightState.FULL
                    SheetHeightState.FULL -> SheetHeightState.FULL
                }
            }
            velocityPxPerSec > 600f -> {
                when (uiState.sheetHeightState) {
                    SheetHeightState.FULL -> SheetHeightState.HALF
                    SheetHeightState.HALF -> SheetHeightState.PEEK
                    SheetHeightState.PEEK -> SheetHeightState.PEEK
                }
            }
            else -> {
                val distPeek = abs(currentHeightDp - peekSheetDp)
                val distHalf = abs(currentHeightDp - halfSheetDp)
                val distFull = abs(currentHeightDp - maxFullSheetDp)
                when {
                    distPeek <= distHalf && distPeek <= distFull -> SheetHeightState.PEEK
                    distHalf <= distFull -> SheetHeightState.HALF
                    else -> SheetHeightState.FULL
                }
            }
        }
        dragDeltaDp = 0f
        onSetSheetHeightState(targetState)
    }

    val cardShape = if (isSidePanel) {
        RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
    } else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }

    val cardModifier = if (isSidePanel) {
        modifier
            .fillMaxHeight()
            .testTag("route_bottom_sheet")
    } else {
        modifier
            .fillMaxWidth()
            .heightIn(max = animatedMaxHeightDp)
            .draggable(
                state = sheetDraggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> settleSheetDrag(velocity) }
            )
            .testTag("route_bottom_sheet")
    }

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = cardModifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(if (isSidePanel) "planner_side_panel" else "planner_bottom_sheet")
                .then(
                    if (isSidePanel) {
                        Modifier
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding()
                    } else {
                        Modifier.navigationBarsPadding()
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!isSidePanel) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .testTag("planner_sheet_drag_handle"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    )
                }
            }

            val totalDays = maxOf(1, uiState.daySummaries.size)
            val dayWord = if (totalDays == 1) {
                stringResource(R.string.day_singular)
            } else {
                stringResource(R.string.day_plural)
            }
            val safeTotalKm = if (uiState.stats.totalKm.isFinite()) uiState.stats.totalKm else 0.0
            val safeAscentM = if (uiState.stats.ascentM.isFinite()) uiState.stats.ascentM else 0.0
            val pathShareLabel = if (!uiState.stats.hasWayInfo || uiState.stats.pathSharePct == null) {
                stringResource(R.string.planner_path_share_unknown)
            } else {
                uiState.stats.pathShareFormatted
            }
            val peekSummaryText = String.format(
                Locale.GERMANY,
                stringResource(R.string.planner_sheet_peek_with_path_format),
                totalDays,
                dayWord,
                safeTotalKm,
                safeAscentM,
                uiState.timeEstimateFormatted,
                pathShareLabel
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peekSummaryText,
                        style = com.example.ui.theme.TabularStatsTextStyle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("route_summary_text")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (uiState.isOfflineRoute) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.testTag("route_engine_mode_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isOfflineRoute) Icons.Default.CloudOff else Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.routeEngineModeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("route_engine_mode_label")
                                )
                            }
                        }

                        if (uiState.loadingLegKeys.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.planner_calculating_route),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isSidePanel) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SheetHeightChip(
                            label = stringResource(R.string.planner_sheet_chip_map),
                            selected = uiState.sheetHeightState == SheetHeightState.PEEK,
                            onClick = { onSetSheetHeightState(SheetHeightState.PEEK) },
                            testTag = "btn_sheet_peek"
                        )
                        SheetHeightChip(
                            label = stringResource(R.string.planner_sheet_chip_days),
                            selected = uiState.sheetHeightState == SheetHeightState.HALF,
                            onClick = { onSetSheetHeightState(SheetHeightState.HALF) },
                            testTag = "btn_sheet_half"
                        )
                        SheetHeightChip(
                            label = stringResource(R.string.planner_sheet_chip_details),
                            selected = uiState.sheetHeightState == SheetHeightState.FULL,
                            onClick = { onSetSheetHeightState(SheetHeightState.FULL) },
                            testTag = "btn_sheet_full"
                        )
                        IconButton(
                            onClick = {
                                val nextState = when (uiState.sheetHeightState) {
                                    SheetHeightState.PEEK -> SheetHeightState.HALF
                                    SheetHeightState.HALF -> SheetHeightState.FULL
                                    SheetHeightState.FULL -> SheetHeightState.PEEK
                                }
                                onSetSheetHeightState(nextState)
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("btn_toggle_sheet")
                        ) {
                            Icon(
                                imageVector = if (uiState.sheetHeightState == SheetHeightState.FULL) {
                                    Icons.Default.KeyboardArrowDown
                                } else {
                                    Icons.Default.KeyboardArrowUp
                                },
                                contentDescription = stringResource(R.string.planner_sheet_toggle_cd),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Quick Action Buttons Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSuggestOvernightStops()
                            if (uiState.sheetHeightState == SheetHeightState.PEEK) {
                                onSetSheetHeightState(SheetHeightState.HALF)
                            }
                        },
                        enabled = visibleWaypoints.size >= 2 && !uiState.isFindingOvernight,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .heightIn(min = 36.dp)
                            .testTag("btn_find_overnight")
                    ) {
                        if (uiState.isFindingOvernight) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.planner_suggesting_overnights), fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.planner_suggest_overnights),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = onStartNavigation,
                        enabled = visibleWaypoints.size >= 2 && !uiState.isSaving && !uiState.isStarting,
                        colors = ButtonDefaults.buttonColors(containerColor = DanskStiTheme.colors.plannedRoute),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(0.8f)
                            .heightIn(min = 36.dp)
                            .testTag("btn_start_planned_navigation")
                    ) {
                        if (uiState.isStarting) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.planner_btn_starting),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.planner_btn_start),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenTripSettings,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1.1f)
                            .heightIn(min = 34.dp)
                            .testTag("btn_sheet_trip_settings")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_btn_settings),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (uiState.mode == PlannerMode.FREE) {
                        OutlinedButton(
                            onClick = onOpenRundturDialog,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .heightIn(min = 34.dp)
                                .testTag("btn_open_rundtur")
                        ) {
                            Icon(Icons.Default.Loop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.planner_btn_rundtur),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenNearestPoiDialog,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .heightIn(min = 34.dp)
                                .testTag("btn_open_nearest_poi")
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.planner_btn_nearest),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onShareRoute,
                        enabled = visibleWaypoints.size >= 2,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .testTag("btn_share_route_sheet")
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_share),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // HALF, FULL, or Side Panel section: Tabs (Rute, Dage, Detaljer) + Tab Content
            AnimatedVisibility(
                visible = isSidePanel || uiState.sheetHeightState == SheetHeightState.HALF || uiState.sheetHeightState == SheetHeightState.FULL
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlannerTabButton(
                            label = stringResource(R.string.planner_tab_rute),
                            selected = uiState.selectedSheetTab == PlannerSheetTab.RUTE,
                            onClick = { onSelectSheetTab(PlannerSheetTab.RUTE) },
                            testTag = "planner_tab_rute",
                            modifier = Modifier.weight(1f)
                        )
                        PlannerTabButton(
                            label = stringResource(R.string.planner_tab_dage),
                            selected = uiState.selectedSheetTab == PlannerSheetTab.DAGE,
                            onClick = { onSelectSheetTab(PlannerSheetTab.DAGE) },
                            testTag = "planner_tab_dage",
                            modifier = Modifier.weight(1f)
                        )
                        PlannerTabButton(
                            label = stringResource(R.string.planner_tab_detaljer),
                            selected = uiState.selectedSheetTab == PlannerSheetTab.DETALJER,
                            onClick = { onSelectSheetTab(PlannerSheetTab.DETALJER) },
                            testTag = "planner_tab_detaljer",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (uiState.selectedSheetTab) {
                            PlannerSheetTab.RUTE -> {
                                PlannerRuteTabContent(
                                    uiState = uiState,
                                    visibleWaypoints = visibleWaypoints,
                                    onWaypointClicked = onWaypointClicked,
                                    onReorderWaypoints = onReorderWaypoints,
                                    onDeleteWaypoint = onDeleteWaypoint,
                                    onCustomWaypointClicked = onCustomWaypointClicked,
                                    onDeleteCustomWaypoint = onDeleteCustomWaypoint,
                                    onDeleteCustomWaypointPhoto = onDeleteCustomWaypointPhoto,
                                    onUpdateOptions = onUpdateOptions,
                                    onOpenSaveDialog = onOpenSaveDialog,
                                    onStartNavigation = onStartNavigation
                                )
                            }

                            PlannerSheetTab.DAGE -> {
                                PlannerDageTabContent(
                                    uiState = uiState,
                                    onDayCardClicked = onDayCardClicked,
                                    onReplaceOvernight = onReplaceOvernight,
                                    onDeleteWaypoint = onDeleteWaypoint,
                                    onZoomToCoordinate = onZoomToCoordinate,
                                    onRerouteAroundWarning = onRerouteAroundWarning
                                )
                            }

                            PlannerSheetTab.DETALJER -> {
                                PlannerDetaljerTabContent(
                                    uiState = uiState,
                                    visibleWaypoints = visibleWaypoints,
                                    onScrubbedPointChanged = onScrubbedPointChanged,
                                    onOvernightMarkerDragged = onOvernightMarkerDragged,
                                    onZoomToCoordinate = onZoomToCoordinate,
                                    onUpdateOptions = onUpdateOptions,
                                    onOpenSaveDialog = onOpenSaveDialog,
                                    onStartNavigation = onStartNavigation
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlannerTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = modifier
            .heightIn(min = 32.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 8.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

