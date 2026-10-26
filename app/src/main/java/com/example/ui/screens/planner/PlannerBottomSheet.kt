package com.example.ui.screens.planner

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.RoadSectionWarning
import com.example.data.model.SupplyGapType
import com.example.data.model.SupplyGapWarning
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaypointWeatherEta
import com.example.ui.components.RouteElevationProfileChart
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteAlternativeInfo
import com.example.ui.viewmodel.RouteBuilderState
import com.example.ui.viewmodel.SheetHeightState
import com.example.util.access.RouteAccessWarning
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteMarkerHelper
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.io.File
import java.util.Locale

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
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("route_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            val totalDays = maxOf(1, uiState.daySummaries.size)
            val dayWord = if (totalDays == 1) {
                stringResource(R.string.day_singular)
            } else {
                stringResource(R.string.day_plural)
            }
            val safeTotalKm = if (uiState.stats.totalKm.isFinite()) uiState.stats.totalKm else 0.0
            val safeAscentM = if (uiState.stats.ascentM.isFinite()) uiState.stats.ascentM else 0.0
            val peekSummaryText = String.format(
                Locale.GERMANY,
                stringResource(R.string.planner_sheet_peek_summary_format),
                totalDays,
                dayWord,
                safeTotalKm,
                safeAscentM,
                uiState.timeEstimateFormatted
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val nextState = when (uiState.sheetHeightState) {
                            SheetHeightState.PEEK -> SheetHeightState.HALF
                            SheetHeightState.HALF -> SheetHeightState.FULL
                            SheetHeightState.FULL -> SheetHeightState.PEEK
                        }
                        onSetSheetHeightState(nextState)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peekSummaryText,
                        fontSize = 15.sp,
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
                            color = if (uiState.isOfflineRoute) ForestGreenPrimary.copy(alpha = 0.15f)
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
                                    tint = if (uiState.isOfflineRoute) ForestGreenPrimary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.routeEngineModeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (uiState.isOfflineRoute) ForestGreenPrimary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("route_engine_mode_label")
                                )
                            }
                        }

                        if (uiState.loadingLegKeys.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp),
                                color = ForestGreenPrimary
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

                // 3-Height selector buttons (Peek / Half / Full)
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

            // Quick Action Buttons Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("btn_find_overnight")
                    ) {
                        if (uiState.isFindingOvernight) {
                            CircularProgressIndicator(
                                color = Color.White,
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(0.8f)
                            .testTag("btn_start_planned_navigation")
                    ) {
                        if (uiState.isStarting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Starter...",
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
                        modifier = Modifier.testTag("btn_share_route_sheet")
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_share),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // HALF or FULL section: Day list & details
            AnimatedVisibility(
                visible = uiState.sheetHeightState == SheetHeightState.HALF || uiState.sheetHeightState == SheetHeightState.FULL
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(
                            max = if (uiState.sheetHeightState == SheetHeightState.HALF) 290.dp else 460.dp
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dayCount = uiState.daySummaries.size
                        val dayCountLabel = if (dayCount == 1) {
                            stringResource(R.string.day_singular)
                        } else {
                            stringResource(R.string.day_plural)
                        }
                        Text(
                            text = stringResource(R.string.planner_day_plan_header_format, dayCount, dayCountLabel),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format(
                                Locale.GERMANY,
                                stringResource(R.string.planner_day_target_pace_format),
                                uiState.dailyTargetKm.toInt(),
                                uiState.paceKmh
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (uiState.daySummaries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.planner_add_two_points_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.daySummaries.forEach { day ->
                                PlannerDaySummaryCard(
                                    day = day,
                                    showNightWeather = uiState.sheetHeightState == SheetHeightState.FULL,
                                    onReplaceOvernight = onReplaceOvernight,
                                    onDeleteOvernight = onDeleteWaypoint,
                                    onZoomToEnd = { wpId ->
                                        val wp = uiState.waypoints.find { it.id == wpId }
                                        if (wp != null) {
                                            onZoomToCoordinate(wp.lat, wp.lng)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.supplyGapWarnings.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.supplyGapWarnings.forEach { warning ->
                                SupplyGapWarningCard(
                                    warning = warning,
                                    onZoomToDetour = {
                                        val dLat = warning.detourLat
                                        val dLng = warning.detourLng
                                        if (dLat != null && dLng != null) {
                                            onZoomToCoordinate(dLat, dLng)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.accessWarnings.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.accessWarnings.forEach { warning ->
                                AccessWarningCard(
                                    warning = warning,
                                    onZoomTo = {
                                        onZoomToCoordinate(warning.location.lat, warning.location.lng)
                                    },
                                    onRerouteAround = {
                                        onRerouteAroundWarning(warning)
                                    }
                                )
                            }
                        }
                    }

                    if (uiState.sheetHeightState == SheetHeightState.FULL) {
                        HorizontalDivider()

                        val allPoints = remember(uiState.legs, uiState.waypoints) {
                            val ordered = mutableListOf<LegGeometryData>()
                            for (i in 0 until uiState.waypoints.size - 1) {
                                val key = "${uiState.waypoints[i].id}_to_${uiState.waypoints[i + 1].id}"
                                uiState.legs[key]?.let { ordered.add(it) }
                            }
                            RouteGeometryStorageManager.getCombinedPoints(ordered)
                        }
                        val overnightWps = remember(uiState.waypoints) {
                            uiState.waypoints.filter { !it.isHelper && it.role == WaypointRole.OVERNIGHT }
                        }
                        val dayBoundaryKms = remember(uiState.daySummaries) {
                            uiState.daySummaries.dropLast(1).map { it.endKm }
                        }

                        Text(
                            text = stringResource(R.string.planner_elevation_chart_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        RouteElevationProfileChart(
                            points = allPoints,
                            dayBoundaryWalkKms = dayBoundaryKms,
                            overnightWaypointIds = overnightWps.map { it.id },
                            onPointScrubbed = { pt, _ ->
                                onScrubbedPointChanged(pt)
                            },
                            onOvernightMarkerDragged = { wpId, targetWalkKm, _ ->
                                onOvernightMarkerDragged(wpId, targetWalkKm)
                            },
                            modifier = Modifier.testTag("planner_elevation_chart")
                        )

                        // 2. Rutekvalitet & Underlag
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.planner_route_quality_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!uiState.stats.hasWayInfo || uiState.stats.pathSharePct == null) {
                                Text(
                                    text = "${stringResource(R.string.planner_way_info_unknown)} · Stiandel: ukendt · Vej: ukendt",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("way_info_unknown_text")
                                )
                            } else {
                                Text(
                                    text = "Stiandel: ${uiState.stats.pathShareFormatted} · Vej: ${uiState.stats.roadKmFormatted}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("way_info_summary_text")
                                )
                                val q = uiState.routeQuality
                                val effTotal = if (q.totalKm.isFinite() && q.totalKm > 0.0) q.totalKm else 1.0
                                val segments = listOf(
                                    (q.trailKm / effTotal).toFloat() to Color(0xFF2E7D32),
                                    (q.trackKm / effTotal).toFloat() to Color(0xFF689F38),
                                    (q.roadWithSidewalkKm / effTotal).toFloat() to Color(0xFF546E7A),
                                    (q.roadNoSidewalkKm / effTotal).toFloat() to Color(0xFFE65100),
                                    (q.asphaltKm / effTotal).toFloat() to Color(0xFF757575),
                                    (q.ferryKm / effTotal).toFloat() to Color(0xFF0288D1)
                                ).filter { (w, _) -> w.isFinite() && w > 0f }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                ) {
                                    if (segments.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxSize()
                                                .background(Color.LightGray)
                                        )
                                    } else {
                                        segments.forEach { (w, col) ->
                                            if (w.isFinite() && w > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(w)
                                                        .fillMaxSize()
                                                        .background(col)
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        QualityRowItem(
                                            color = Color(0xFF2E7D32),
                                            label = stringResource(R.string.planner_quality_trail),
                                            km = q.trailKm,
                                            pct = q.trailPct
                                        )
                                        QualityRowItem(
                                            color = Color(0xFF689F38),
                                            label = stringResource(R.string.planner_quality_track),
                                            km = q.trackKm,
                                            pct = q.trackPct
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        QualityRowItem(
                                            color = Color(0xFF546E7A),
                                            label = stringResource(R.string.planner_quality_road_sidewalk),
                                            km = q.roadWithSidewalkKm,
                                            pct = q.roadWithSidewalkPct
                                        )
                                        QualityRowItem(
                                            color = Color(0xFFE65100),
                                            label = stringResource(R.string.planner_quality_road_no_sidewalk),
                                            km = q.roadNoSidewalkKm,
                                            pct = q.roadNoSidewalkPct
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        QualityRowItem(
                                            color = Color(0xFF757575),
                                            label = stringResource(R.string.planner_quality_asphalt),
                                            km = q.asphaltKm,
                                            pct = q.asphaltPct
                                        )
                                        QualityRowItem(
                                            color = Color(0xFF0288D1),
                                            label = stringResource(R.string.planner_quality_ferry),
                                            km = q.ferryKm,
                                            pct = q.ferryPct
                                        )
                                    }
                                }

                                if (q.roadWithoutFootwayWarnings.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.planner_road_warning_header),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE65100)
                                    )
                                    q.roadWithoutFootwayWarnings.forEach { rw ->
                                        RoadSectionWarningCard(
                                            warning = rw,
                                            onZoomTo = {
                                                onZoomToCoordinate(rw.startLat, rw.startLng)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Routing Options
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.planner_routing_options_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        stringResource(R.string.planner_avoid_roads),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        stringResource(R.string.ui_offlinetrailsscreen_fmt_86, (uiState.options.avoidRoads * 100).toInt()),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ForestGreenPrimary
                                    )
                                }
                                Slider(
                                    value = uiState.options.avoidRoads.toFloat().coerceIn(0f, 1f),
                                    onValueChange = { newVal ->
                                        onUpdateOptions(uiState.options.copy(avoidRoads = newVal.toDouble()))
                                    },
                                    colors = SliderDefaults.colors(
                                        thumbColor = ForestGreenPrimary,
                                        activeTrackColor = ForestGreenPrimary
                                    ),
                                    modifier = Modifier.testTag("slider_avoid_roads")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.planner_prefer_nature),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Switch(
                                    checked = uiState.options.preferNature,
                                    onCheckedChange = { checked ->
                                        onUpdateOptions(uiState.options.copy(preferNature = checked))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = ForestGreenPrimary),
                                    modifier = Modifier.testTag("switch_prefer_nature")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.planner_allow_ferries),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Switch(
                                    checked = uiState.options.allowFerries,
                                    onCheckedChange = { checked ->
                                        onUpdateOptions(uiState.options.copy(allowFerries = checked))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = ForestGreenPrimary),
                                    modifier = Modifier.testTag("switch_allow_ferries")
                                )
                            }
                        }

                        // 4. Waypoints list (visible waypoints only)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.planner_waypoints_header_format, visibleWaypoints.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            visibleWaypoints.forEachIndexed { idx, wp ->
                                WaypointRow(
                                    index = idx,
                                    waypoint = wp,
                                    onClick = { onWaypointClicked(wp) }
                                )
                            }
                        }

                        // 5. Gem & Start buttons in Full sheet
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenSaveDialog,
                                enabled = visibleWaypoints.size >= 2 && !uiState.isSaving && !uiState.isStarting,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_full_sheet_save")
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(
                                        color = ForestGreenPrimary,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(stringResource(R.string.btn_save), fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = onStartNavigation,
                                enabled = visibleWaypoints.size >= 2 && !uiState.isSaving && !uiState.isStarting,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_full_sheet_start")
                            ) {
                                if (uiState.isStarting) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Starter...", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.planner_btn_start), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SheetHeightChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ForestGreenPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PlannerDaySummaryCard(
    day: PlannerDaySummary,
    showNightWeather: Boolean,
    onReplaceOvernight: (String) -> Unit,
    onDeleteOvernight: (String) -> Unit,
    onZoomToEnd: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(
            1.dp,
            if (day.warnings.isNotEmpty() || day.arrivesAfterSunset) {
                AmberAccent.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("planner_day_card_${day.dayNumber}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val safeDistKm = if (day.distanceKm.isFinite()) day.distanceKm else 0.0
                Text(
                    text = String.format(
                        Locale.GERMANY,
                        stringResource(R.string.planner_day_card_header_format),
                        day.dayNumber,
                        safeDistKm,
                        day.ascentM
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ForestGreenPrimary,
                    modifier = Modifier.testTag("day_card_header_${day.dayNumber}")
                )

                val totalMins = if (day.estimatedHours.isFinite() && day.estimatedHours >= 0.0) {
                    (day.estimatedHours * 60).toInt()
                } else {
                    0
                }
                val h = totalMins / 60
                val m = totalMins % 60
                Text(
                    text = if (h > 0) {
                        stringResource(R.string.planner_time_hours_mins_format, h, m)
                    } else {
                        stringResource(R.string.planner_time_mins_format, m)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.ui_plannerbottomsheet_fmt_220, day.fromName, day.toName),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (day.overnightWaypointId != null) {
                val typeLabel = when (day.overnightType) {
                    "FRI_TELTNING" -> stringResource(R.string.overnight_type_fri_teltning)
                    "SHELTER" -> stringResource(R.string.overnight_type_shelter)
                    else -> stringResource(R.string.overnight_type_default)
                }
                val bookingLabel = if (day.bookingRequired) {
                    stringResource(R.string.overnight_booking_required)
                } else {
                    stringResource(R.string.overnight_free_use)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ui_plannerbottomsheet_fmt_221, day.overnightName.ifBlank { day.toName }, typeLabel),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE65100),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = bookingLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (day.bookingRequired) AmberAccent else ForestGreenPrimary
                    )
                }

                val detourKm = day.detourKm
                if (detourKm.isFinite() && detourKm > 0.01) {
                    Text(
                        text = String.format(
                            Locale.GERMANY,
                            stringResource(R.string.planner_detour_format),
                            day.overnightName.ifBlank { day.toName },
                            detourKm,
                            day.detourAscentM
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE65100),
                        modifier = Modifier.testTag("day_card_detour_${day.dayNumber}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (day.arrivalTime.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.planner_arrival_time_format, day.arrivalTime),
                        fontSize = 11.sp,
                        color = if (day.isAfterSunset) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (day.isAfterSunset) FontWeight.Bold else FontWeight.Normal
                    )
                }
                if (day.sunsetTime.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.planner_sunset_time_format, day.sunsetTime),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showNightWeather) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.planner_night_weather_format, day.nightWeatherSummary),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("day_card_weather_${day.dayNumber}")
                    )
                }
            }

            if (day.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    day.warnings.forEach { w ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = w,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFBF360C)
                                )
                            }
                        }
                    }
                }
            }

            if (day.overnightWaypointId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onReplaceOvernight(day.overnightWaypointId) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("btn_day_replace_overnight_${day.dayNumber}")
                    ) {
                        Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_replace_overnight),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { onZoomToEnd(day.overnightWaypointId) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(stringResource(R.string.planner_btn_show), fontSize = 11.sp)
                    }

                    TextButton(
                        onClick = { onDeleteOvernight(day.overnightWaypointId) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("btn_day_delete_overnight_${day.dayNumber}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.planner_btn_remove), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun WaypointRow(
    index: Int,
    waypoint: RouteWaypoint,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when (waypoint.role) {
                    WaypointRole.START -> Color(0xFF2E7D32)
                    WaypointRole.END -> Color(0xFFC62828)
                    WaypointRole.OVERNIGHT -> Color(0xFFE65100)
                    WaypointRole.VIA -> Color(0xFF1E88E5)
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
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name ?: when (waypoint.role) {
                        WaypointRole.START -> stringResource(R.string.waypoint_role_start)
                        WaypointRole.END -> stringResource(R.string.waypoint_role_end)
                        WaypointRole.OVERNIGHT -> stringResource(R.string.waypoint_role_overnight)
                        WaypointRole.VIA -> stringResource(R.string.waypoint_role_via_format, index + 1)
                    },
                    fontSize = 13.sp,
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
                        Color(0xFFE65100)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
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
            modifier = Modifier.padding(10.dp),
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
                modifier = Modifier.height(30.dp)
            ) {
                Text(stringResource(R.string.planner_btn_choose), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AccessWarningCard(
    warning: RouteAccessWarning,
    onZoomTo: () -> Unit,
    onRerouteAround: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AmberAccent.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.8f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("access_warning_card_${warning.type.name}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = warning.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onZoomTo,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("btn_warning_zoom_${warning.id}")
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.planner_btn_show), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (warning.canRerouteAround) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onRerouteAround,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("btn_warning_reroute_${warning.id}")
                    ) {
                        Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_btn_reroute_around),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SupplyGapWarningCard(
    warning: SupplyGapWarning,
    onZoomToDetour: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        border = BorderStroke(1.dp, Color(0xFFE65100).copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supply_gap_warning_${warning.type.name}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (warning.type == SupplyGapType.WATER) Icons.Default.WaterDrop else Icons.Default.Place,
                    contentDescription = null,
                    tint = Color(0xFFE65100),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBF360C)
                )
            }

            warning.detourOptionText?.let { detour ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detour,
                    fontSize = 12.sp,
                    color = Color(0xFF5D4037)
                )
            }

            if (warning.detourLat != null && warning.detourLng != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onZoomToDetour,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("btn_zoom_detour_${warning.type.name}")
                    ) {
                        Icon(
                            Icons.Default.ZoomIn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_show_detour_on_map),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QualityRowItem(
    color: Color,
    label: String,
    km: Double,
    pct: Double
) {
    val safeKm = if (km.isFinite()) km else 0.0
    val safePct = if (pct.isFinite()) pct.toInt() else 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(
            text = stringResource(R.string.ui_plannerbottomsheet_km_222, label, String.format(Locale.GERMANY, "%.1f", safeKm), safePct),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RoadSectionWarningCard(
    warning: RoadSectionWarning,
    onZoomTo: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE0B2).copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, Color(0xFFE65100).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = warning.displayText,
                    fontSize = 11.sp,
                    color = Color(0xFFBF360C),
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(
                onClick = onZoomTo,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFE65100)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    stringResource(R.string.planner_btn_show),
                    fontSize = 11.sp,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun WaypointWeatherEtaCard(
    weather: WaypointWeatherEta
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (weather.isAfterSunset) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            1.dp,
            if (weather.isAfterSunset) Color(0xFFE65100).copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("weather_eta_${weather.waypointId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (weather.isOvernight) "⛺ " else "🏁 ",
                        fontSize = 14.sp
                    )
                    Text(
                        text = weather.waypointName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (weather.isAfterSunset) Color(0xFFE65100) else ForestGreenPrimary,
                    contentColor = Color.White
                ) {
                    Text(
                        text = stringResource(R.string.ui_plannerbottomsheet_eta_223, weather.etaFormatted),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = String.format(
                    Locale.GERMANY,
                    stringResource(R.string.planner_weather_eta_distance_format),
                    weather.kmFromStart,
                    weather.etaMinutesFromStart / 60,
                    weather.etaMinutesFromStart % 60
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (weather.tempC != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ui_plannerbottomsheet_c_224, weather.weatherIcon ?: "🌤️", weather.tempC.toInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (weather.precipMm != null) {
                        Text(
                            text = stringResource(R.string.ui_plannerbottomsheet_mm_225, String.format(Locale.US, "%.1f", weather.precipMm)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (weather.windMs != null) {
                        Text(
                            text = stringResource(R.string.ui_plannerbottomsheet_m_s_226, String.format(Locale.US, "%.1f", weather.windMs)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.planner_weather_arrival_unavailable),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!weather.sunsetTime.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.planner_sunset_time_format, weather.sunsetTime),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (weather.warningMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFE0B2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = weather.warningMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFBF360C),
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CustomWaypointRow(
    waypoint: UserCustomWaypointEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!waypoint.photoPath.isNullOrBlank() && File(waypoint.photoPath).exists()) {
                val bitmap = remember(waypoint.photoPath) {
                    try {
                        BitmapFactory.decodeFile(waypoint.photoPath)
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
                        contentDescription = null,
                        tint = Color(0xFF7B1FA2),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF7B1FA2),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.ui_plannerbottomsheet_label_227), color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (waypoint.note.isNotBlank()) {
                    Text(
                        text = waypoint.note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
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
