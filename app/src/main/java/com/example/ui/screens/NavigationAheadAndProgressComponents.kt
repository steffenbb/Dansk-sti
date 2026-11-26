package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RoutePoint
import com.example.data.remote.TrailWeatherInfo
import com.example.ui.components.RouteElevationProfileChart
import com.example.ui.components.formatDanishKm
import com.example.ui.components.rememberAppLocale
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.AheadOnRouteItem
import com.example.ui.viewmodel.DayProgressStopIcon
import com.example.ui.viewmodel.NavigationViewModel
import com.example.util.routing.RoutePoint3D
import kotlin.math.roundToInt

data class Next5KmElevationSlice(
    val points: List<GeoPointDto>,
    val spanKm: Double,
    val ascentMeters: Int,
    val descentMeters: Int
)

/**
 * Computes the next 5 km slice of the route starting from [currentWalkKm],
 * with the hiker's position at index 0 (left edge) and ascent/descent over those 5 km (+X m / -Y m).
 */
fun computeNext5KmElevationSlice(
    route: PlannedRoute?,
    currentWalkKm: Double,
    windowKm: Double = 5.0
): Next5KmElevationSlice {
    val allPts = route?.points ?: emptyList()
    if (allPts.size < 2) {
        return Next5KmElevationSlice(emptyList(), 0.0, 0, 0)
    }
    val startKm = currentWalkKm.coerceIn(0.0, route!!.totalWalkKm)
    val endKm = (startKm + windowKm).coerceAtMost(route.totalWalkKm)

    val sliced = allPts.filter { it.walkKm in (startKm - 0.05)..(endKm + 0.05) }
        .ifEmpty {
            val idx = allPts.indexOfFirst { it.walkKm >= startKm }.coerceAtLeast(0)
            allPts.drop(idx).take(20)
        }

    if (sliced.size < 2) {
        val fallback = allPts.take(2).map { GeoPointDto(it.lat, it.lng, it.ele) }
        return Next5KmElevationSlice(fallback, (endKm - startKm).coerceAtLeast(0.0), 0, 0)
    }

    var gain = 0.0
    var loss = 0.0
    var prevEle = sliced.first().ele
    for (i in 1 until sliced.size) {
        val curEle = sliced[i].ele
        if (prevEle != null && curEle != null) {
            val diff = curEle - prevEle
            if (diff > 0.5) gain += diff
            else if (diff < -0.5) loss += (-diff)
        }
        if (curEle != null) prevEle = curEle
    }
    val dtoPts = sliced.map { GeoPointDto(it.lat, it.lng, it.ele) }
    val actualSpanKm = (sliced.last().walkKm - sliced.first().walkKm).coerceAtLeast(0.1)
    return Next5KmElevationSlice(
        points = dtoPts,
        spanKm = actualSpanKm,
        ascentMeters = gain.roundToInt(),
        descentMeters = loss.roundToInt()
    )
}

/**
 * Navigation screen in landscape:
 * cue banner top-left, stats in a column on the right, map in the middle.
 */
@Composable
fun NavigationLandscapeScaffoldBox(
    mapContent: @Composable () -> Unit,
    topLeftCueContent: @Composable (Modifier) -> Unit,
    rightStatsColumnContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .testTag("nav_landscape_row")
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("nav_landscape_map_pane")
        ) {
            mapContent()

            topLeftCueContent(
                Modifier
                    .widthIn(max = 350.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 64.dp, top = 8.dp, bottom = 8.dp)
                    .align(Alignment.TopStart)
                    .testTag("nav_landscape_top_left_cue")
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .width(300.dp)
                .widthIn(min = 260.dp, max = 340.dp)
                .fillMaxHeight()
                .testTag("nav_landscape_right_stats_column")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rightStatsColumnContent()
            }
        }
    }
}

@Composable
internal fun NavigationExpandedAheadDrawer(
    isDrawerExpanded: Boolean,
    plannedRoute: PlannedRoute?,
    dayProgressFraction: Float,
    aheadItems: List<AheadOnRouteItem>,
    currentDay: Int,
    currentDayStat: RecordedDayStatEntity?,
    distanceWalkedTodayKm: Double,
    allRecordedDayStats: List<RecordedDayStatEntity>,
    hasGpsFix: Boolean,
    viewModel: NavigationViewModel
) {
    val context = LocalContext.current
    val liveState by viewModel.liveState.collectAsState()
    val navigationWeather by viewModel.navigationWeather.collectAsState()
    val tonightCampWeather by viewModel.tonightCampWeather.collectAsState()
    var showFullDayElevationDialog by remember { mutableStateOf(false) }

    val activeRoute = plannedRoute ?: liveState.plannedRoute
    val next5KmSlice = remember(activeRoute, liveState.totalDistanceWalkedKm) {
        computeNext5KmElevationSlice(activeRoute, liveState.totalDistanceWalkedKm)
    }

    if (showFullDayElevationDialog && activeRoute != null) {
        val currentStage = liveState.currentStage
            ?: activeRoute.stages.getOrNull(currentDay - 1)
            ?: activeRoute.stages.firstOrNull()
        val dayPoints3D = remember(activeRoute, currentStage) {
            val pts: List<RoutePoint> = if (currentStage != null) {
                activeRoute.points.filter {
                    it.walkKm in (currentStage.startWalkKm - 0.05)..(currentStage.endWalkKm + 0.05)
                }.ifEmpty { activeRoute.points }
            } else {
                activeRoute.points
            }
            pts.map { RoutePoint3D(it.lat, it.lng, it.ele) }
        }
        var scrubbedInfo by remember { mutableStateOf<Pair<RoutePoint3D, Double>?>(null) }

        AlertDialog(
            onDismissRequest = { showFullDayElevationDialog = false },
            modifier = Modifier.testTag("nav_full_day_elevation_dialog"),
            title = {
                Text(
                    text = stringResource(R.string.nav_full_day_elevation_title_fmt, currentDay),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RouteElevationProfileChart(
                        points = dayPoints3D,
                        onPointScrubbed = { pt, km ->
                            scrubbedInfo = if (pt != null) pt to km else null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav_full_day_elevation_chart")
                    )
                    val info = scrubbedInfo
                    if (info != null) {
                        Text(
                            text = "${formatDanishKm(info.second, 1)} km · ${(info.first.ele ?: 0.0).roundToInt()} m",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.nav_elevation_scrub_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showFullDayElevationDialog = false },
                    modifier = Modifier.testTag("nav_full_day_elevation_close_button")
                ) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    AnimatedVisibility(
        visible = isDrawerExpanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.nav_ahead_on_route),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.nav_next_5km_ascent_descent_fmt, next5KmSlice.ascentMeters, next5KmSlice.descentMeters),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("nav_next_5km_ascent_descent_label")
                )
            }

            val profilePoints = if (next5KmSlice.points.size >= 2) {
                next5KmSlice.points
            } else {
                activeRoute?.points?.map { GeoPointDto(it.lat, it.lng, it.ele) } ?: emptyList()
            }
            // Hiker's position is on the left (0f) of the next-5-km mini profile
            ElevationPositionCanvas(
                points = profilePoints,
                progressFraction = if (next5KmSlice.points.size >= 2) 0f else dayProgressFraction,
                onClick = { showFullDayElevationDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .testTag("nav_elevation_profile")
            )

            // Part E1: Compact 6-hour weather card while navigating
            val displayWeather = navigationWeather ?: tonightCampWeather
            if (displayWeather != null) {
                Navigation6HourWeatherCard(weather = displayWeather)
            }

            if (aheadItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_navigationscreen_ingen_yderligere_interessepu_65),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    aheadItems.take(5).forEach { item ->
                        AheadItemRow(item = item)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recorded_day_stats_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_optaget_gps_spor_dag_66, currentDay),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_punkter_67, currentDayStat?.pointCount ?: 0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ui_markedroutedetailsheet_distance_18),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val dist = currentDayStat?.distanceKm ?: distanceWalkedTodayKm
                            Text(
                                text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(dist, 1)),
                                style = TabularBodyStyle.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_bev_gelsestid_68),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                            Text(
                                text = formatMovingTime(movSecs),
                                style = TabularBodyStyle.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = stringResource(R.string.stat_elevation),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val ascent = currentDayStat?.ascentMeters ?: 0
                            Text(
                                text = stringResource(R.string.ui_navigationscreen_m_58, ascent),
                                style = TabularBodyStyle.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.exportDayGpx(context, currentDay) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("export_day_gpx_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_navigationscreen_dags_spor_gpx_69), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }

                        Button(
                            onClick = { viewModel.exportWholeTripGpx(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("export_whole_trip_gpx_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_navigationscreen_hele_turen_gpx_70), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.shareCurrentLocation(context) },
                        enabled = hasGpsFix,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("share_current_location_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (hasGpsFix) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasGpsFix) stringResource(R.string.nav_share_current_location_link) else stringResource(R.string.toast_waiting_for_gps),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hasGpsFix) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (allRecordedDayStats.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_tidligere_dages_optagede_spo_71),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        allRecordedDayStats.filter { it.dayNumber != currentDay }.forEach { dayStat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.ui_navigationscreen_dag_km_m_72,
                                        dayStat.dayNumber,
                                        formatDanishKm(dayStat.distanceKm, 1),
                                        formatMovingTime(dayStat.movingTimeSeconds),
                                        dayStat.ascentMeters
                                    ),
                                    style = TabularBodyStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { viewModel.exportDayGpx(context, dayStat.dayNumber) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("export_day_${dayStat.dayNumber}_button")
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = stringResource(R.string.ui_navigationscreen_eksporter_dag_73, dayStat.dayNumber),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
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
fun Navigation6HourWeatherCard(
    weather: TrailWeatherInfo,
    modifier: Modifier = Modifier
) {
    val appLocale = rememberAppLocale()
    val isStale = weather.isStale()
    val updatedClock = weather.formatUpdatedClock()
    val statusText = if (isStale) {
        stringResource(R.string.weather_updated_at_stale_fmt, updatedClock)
    } else {
        stringResource(R.string.weather_updated_at_fmt, updatedClock)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("nav_6h_weather_card")
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = DanskStiTheme.colors.warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.nav_weather_next_6h_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isStale) DanskStiTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("nav_weather_updated_label")
                )
            }

            if (weather.next6Hours.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    weather.next6Hours.take(6).forEach { block ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = block.timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${block.tempC.toInt()}°",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${String.format(appLocale, "%.1f", block.precipitationMm)}mm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (block.precipitationMm >= 1.0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "${weather.tempC?.toInt() ?: "--"}°C", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "${String.format(appLocale, "%.1f", weather.precipitationMm ?: 0.0)} mm", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(text = weather.conditionDescription ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DayProgressBarWithIcons(progressFraction: Float, stops: List<DayProgressStopIcon>, modifier: Modifier = Modifier) {
    val danskStiColors = DanskStiTheme.colors
    BoxWithConstraints(modifier = modifier.height(26.dp), contentAlignment = Alignment.CenterStart) {
        val barWidth = maxWidth
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(modifier = Modifier.fillMaxWidth(progressFraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        val displayStops = if (stops.isNotEmpty()) stops else listOf(DayProgressStopIcon("CAMP", "Lejr", 1.0f, 0.0))
        displayStops.forEach { stop ->
            val xOffset = (barWidth - 20.dp) * stop.fraction.coerceIn(0f, 1f)
            val stopColor = when (stop.type) {
                "WATER" -> MaterialTheme.colorScheme.tertiary
                "SHOP" -> danskStiColors.warning
                else -> MaterialTheme.colorScheme.primary
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, stopColor), modifier = Modifier.offset(x = xOffset).size(20.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (stop.type) {
                            "WATER" -> Icons.Default.LocalDrink
                            "SHOP" -> Icons.Default.ShoppingCart
                            else -> Icons.Default.Cabin
                        },
                        contentDescription = stop.name, tint = stopColor, modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AheadItemRow(item: AheadOnRouteItem) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (item.type) {
                    "SHELTER" -> Icons.Default.Cabin
                    "WATER" -> Icons.Default.LocalDrink
                    "TOILET" -> Icons.Default.Wc
                    "SHOP" -> Icons.Default.ShoppingBag
                    else -> Icons.Default.Navigation
                },
                contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                if (item.detourMeters > 30) {
                    Text(text = stringResource(R.string.ui_navigationscreen_m_afstikker_79, item.detourMeters.toInt()), style = TabularLabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(item.kmRemaining, 1)),
            style = TabularBodyStyle.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ElevationPositionCanvas(points: List<GeoPointDto>, progressFraction: Float, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.tertiary
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            if (points.size < 2) return@Canvas
            val validElevations = points.mapNotNull { it.ele }
            if (validElevations.isEmpty()) return@Canvas
            val minEle = validElevations.minOrNull() ?: 0.0
            val maxEle = validElevations.maxOrNull() ?: 0.0
            val dEle = (maxEle - minEle).coerceAtLeast(10.0)
            val step = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { i, pt ->
                val x = i * step
                val eleVal = pt.ele ?: minEle
                val y = ((1.0 - (eleVal - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val curX = (size.width * progressFraction).coerceIn(0f, size.width)
            val pointIdx = (progressFraction * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
            val curEle = points[pointIdx].ele ?: minEle
            val curY = ((1.0 - (curEle - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)
            drawCircle(color = dotColor, radius = 5.dp.toPx(), center = Offset(curX, curY))
        }
    }
}

internal fun formatMovingTime(seconds: Long): String {
    if (seconds <= 0L) return "0 min"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}t ${minutes}m" else "${minutes} min"
}
