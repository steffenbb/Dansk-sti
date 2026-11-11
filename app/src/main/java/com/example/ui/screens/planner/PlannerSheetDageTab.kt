package com.example.ui.screens.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.SupplyGapType
import com.example.data.model.SupplyGapWarning
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerDaySummary
import com.example.ui.viewmodel.RouteBuilderState
import com.example.util.access.RouteAccessWarning
import java.util.Locale

@Composable
internal fun PlannerDageTabContent(
    uiState: RouteBuilderState,
    onDayCardClicked: (PlannerDaySummary) -> Unit,
    onReplaceOvernight: (String) -> Unit,
    onDeleteWaypoint: (String) -> Unit,
    onZoomToCoordinate: (Double, Double) -> Unit,
    onRerouteAroundWarning: (RouteAccessWarning) -> Unit
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
                    isSelected = uiState.selectedDayNumber == day.dayNumber,
                    showNightWeather = true,
                    onCardClicked = { onDayCardClicked(day) },
                    onReplaceOvernight = onReplaceOvernight,
                    onDeleteOvernight = onDeleteWaypoint,
                    onZoomToEnd = { wpId ->
                        val wp = uiState.waypoints.find { it.id == wpId }
                        if (wp != null) {
                            onZoomToCoordinate(wp.lat, wp.lng)
                        } else {
                            onDayCardClicked(day)
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
}

@Composable
fun PlannerDaySummaryCard(
    day: PlannerDaySummary,
    showNightWeather: Boolean,
    onReplaceOvernight: (String) -> Unit,
    onDeleteOvernight: (String) -> Unit,
    onZoomToEnd: (String) -> Unit,
    isSelected: Boolean = false,
    onCardClicked: () -> Unit = {}
) {
    val dsColors = DanskStiTheme.colors
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                day.warnings.isNotEmpty() || day.arrivesAfterSunset -> dsColors.warning.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClicked)
            .testTag("planner_day_card_${day.dayNumber}")
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
                    style = com.example.ui.theme.TabularStatsTextStyle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("day_card_header_${day.dayNumber}")
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
                    style = com.example.ui.theme.TabularStatsTextStyle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.ui_plannerbottomsheet_fmt_220, day.fromName, day.toName),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (day.campExplanation.isNotBlank()) {
                Text(
                    text = day.campExplanation,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("day_card_explanation_${day.dayNumber}")
                )
            }

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
                        color = dsColors.overnight,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = bookingLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (day.bookingRequired) dsColors.warning else MaterialTheme.colorScheme.primary
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
                        color = dsColors.overnight,
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
                        color = if (day.isAfterSunset) dsColors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = dsColors.warning.copy(alpha = 0.16f),
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
                                    tint = dsColors.overnight,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = w,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
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
                            .heightIn(min = 36.dp)
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
                        modifier = Modifier.heightIn(min = 36.dp)
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
                            .heightIn(min = 36.dp)
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
fun AccessWarningCard(
    warning: RouteAccessWarning,
    onZoomTo: () -> Unit,
    onRerouteAround: () -> Unit
) {
    val warningColor = DanskStiTheme.colors.warning
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = warningColor.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.8f)),
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
                    tint = warningColor,
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
                    modifier = Modifier
                        .heightIn(min = 28.dp)
                        .testTag("btn_warning_zoom_${warning.id}")
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.planner_btn_show), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (warning.canRerouteAround) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onRerouteAround,
                        colors = ButtonDefaults.buttonColors(containerColor = warningColor),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .testTag("btn_warning_reroute_${warning.id}")
                    ) {
                        Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_btn_reroute_around),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
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
    val dsColors = DanskStiTheme.colors
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = dsColors.warning.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, dsColors.overnight.copy(alpha = 0.6f)),
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
                    tint = dsColors.overnight,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            warning.detourOptionText?.let { detour ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detour,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .testTag("btn_zoom_detour_${warning.type.name}")
                    ) {
                        Icon(
                            Icons.Default.ZoomIn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = dsColors.overnight
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.planner_show_detour_on_map),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = dsColors.overnight
                        )
                    }
                }
            }
        }
    }
}
