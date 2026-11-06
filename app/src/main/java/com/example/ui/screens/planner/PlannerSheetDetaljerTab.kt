package com.example.ui.screens.planner

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.RoadSectionWarning
import com.example.data.model.WaypointWeatherEta
import com.example.ui.components.RouteElevationProfileChart
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.RouteBuilderState
import com.example.util.routing.LegGeometryData
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.util.Locale

@Composable
internal fun PlannerDetaljerTabContent(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    onScrubbedPointChanged: (RoutePoint3D?) -> Unit,
    onOvernightMarkerDragged: (String, Double) -> Unit,
    onZoomToCoordinate: (Double, Double) -> Unit,
    onUpdateOptions: (RouteOptions) -> Unit,
    onOpenSaveDialog: () -> Unit,
    onStartNavigation: () -> Unit
) {
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.planner_route_quality_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (!uiState.stats.hasWayInfo || uiState.stats.pathSharePct == null) {
            Text(
                text = stringResource(
                    R.string.planner_way_info_unknown_full,
                    stringResource(R.string.planner_way_info_unknown)
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("way_info_unknown_text")
            )
        } else {
            Text(
                text = stringResource(
                    R.string.way_info_summary_format,
                    uiState.stats.pathShareFormatted,
                    uiState.stats.roadKmFormatted
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("way_info_summary_text")
            )
            val q = uiState.routeQuality
            val effTotal = if (q.totalKm.isFinite() && q.totalKm > 0.0) q.totalKm else 1.0
            val dsColors = DanskStiTheme.colors
            val segments = listOf(
                (q.trailKm / effTotal).toFloat() to dsColors.success,
                (q.trackKm / effTotal).toFloat() to MaterialTheme.colorScheme.primary,
                (q.roadWithSidewalkKm / effTotal).toFloat() to MaterialTheme.colorScheme.secondary,
                (q.roadNoSidewalkKm / effTotal).toFloat() to dsColors.overnight,
                (q.asphaltKm / effTotal).toFloat() to dsColors.walkedRoute,
                (q.ferryKm / effTotal).toFloat() to dsColors.plannedRoute
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
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                        color = dsColors.success,
                        label = stringResource(R.string.planner_quality_trail),
                        km = q.trailKm,
                        pct = q.trailPct
                    )
                    QualityRowItem(
                        color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.secondary,
                        label = stringResource(R.string.planner_quality_road_sidewalk),
                        km = q.roadWithSidewalkKm,
                        pct = q.roadWithSidewalkPct
                    )
                    QualityRowItem(
                        color = dsColors.overnight,
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
                        color = dsColors.walkedRoute,
                        label = stringResource(R.string.planner_quality_asphalt),
                        km = q.asphaltKm,
                        pct = q.asphaltPct
                    )
                    QualityRowItem(
                        color = dsColors.plannedRoute,
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
                    color = dsColors.overnight
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

    if (uiState.waypointWeatherEtas.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.ui_traildetailscreen_vejrudsigt_open_meteo_206),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            uiState.waypointWeatherEtas.forEach { weather ->
                WaypointWeatherEtaCard(weather = weather)
            }
        }
    } else if (uiState.daySummaries.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.ui_traildetailscreen_vejrudsigt_open_meteo_206),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            uiState.daySummaries.forEach { day ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Dag ${day.dayNumber}: " + stringResource(
                            R.string.planner_night_weather_format,
                            day.nightWeatherSummary
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("day_card_weather_${day.dayNumber}")
                    )
                }
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
    val dsColors = DanskStiTheme.colors
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = dsColors.warning.copy(alpha = 0.16f)),
        border = BorderStroke(1.dp, dsColors.overnight.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = warning.displayText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(
                onClick = onZoomTo,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.heightIn(min = 28.dp)
            ) {
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = dsColors.overnight
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    stringResource(R.string.planner_btn_show),
                    fontSize = 11.sp,
                    color = dsColors.overnight,
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
    val dsColors = DanskStiTheme.colors
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (weather.isAfterSunset) dsColors.warning.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = BorderStroke(
            1.dp,
            if (weather.isAfterSunset) dsColors.overnight.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
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
                    color = if (weather.isAfterSunset) dsColors.overnight else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                    color = dsColors.warning.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = weather.warningMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlannerRoutingOptionsSection(
    options: RouteOptions,
    onUpdateOptions: (RouteOptions) -> Unit
) {
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
                    stringResource(R.string.ui_offlinetrailsscreen_fmt_86, (options.avoidRoads * 100).toInt()),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = options.avoidRoads.toFloat().coerceIn(0f, 1f),
                onValueChange = { newVal ->
                    onUpdateOptions(options.copy(avoidRoads = newVal.toDouble()))
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
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
                checked = options.preferNature,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(preferNature = checked))
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
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
                checked = options.allowFerries,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(allowFerries = checked))
                },
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("switch_allow_ferries")
            )
        }
    }
}

@Composable
internal fun PlannerSaveAndStartRow(
    visibleWaypointsCount: Int,
    isSaving: Boolean,
    isStarting: Boolean,
    onOpenSaveDialog: () -> Unit,
    onStartNavigation: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onOpenSaveDialog,
            enabled = visibleWaypointsCount >= 2 && !isSaving && !isStarting,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("btn_full_sheet_save")
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
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
            enabled = visibleWaypointsCount >= 2 && !isSaving && !isStarting,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("btn_full_sheet_start")
        ) {
            if (isStarting) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.planner_btn_starting), fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.planner_btn_start), fontWeight = FontWeight.Bold)
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
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

