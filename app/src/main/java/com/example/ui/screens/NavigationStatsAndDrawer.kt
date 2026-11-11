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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RecordedDayStatEntity
import com.example.service.NavigationLiveState
import com.example.ui.components.formatDanishKm
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.SheetTopShape
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.AheadOnRouteItem
import com.example.ui.viewmodel.DayProgressStopIcon
import com.example.ui.viewmodel.NavigationMapMode
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.NextStopInfo

@Composable
fun NavigationBottomOrSideControlsAndStats(
    viewModel: NavigationViewModel,
    liveState: NavigationLiveState,
    currentDay: Int,
    distanceRemainingToday: Double,
    arrivalClock: String,
    sunsetTime: String,
    isNearTonightCamp: Boolean,
    campNameForArrival: String?,
    currentDayStat: RecordedDayStatEntity?,
    allRecordedDayStats: List<RecordedDayStatEntity>,
    nextOvernight: NextStopInfo?,
    nextWater: NextStopInfo?,
    nextShop: NextStopInfo?,
    dayProgressStops: List<DayProgressStopIcon>,
    aheadItems: List<AheadOnRouteItem>,
    plannedRoute: PlannedRoute?,
    mapMode: NavigationMapMode,
    keepScreenOn: Boolean,
    voiceGuidance: Boolean,
    hasGpsFix: Boolean,
    isDrawerExpanded: Boolean,
    onToggleDrawerExpanded: () -> Unit,
    onSetDrawerExpanded: (Boolean) -> Unit,
    showNavMenu: Boolean,
    onSetShowNavMenu: (Boolean) -> Unit,
    onDismissCampArrival: () -> Unit,
    onBack: () -> Unit,
    onOpenHowToGuide: () -> Unit = {},
    isLandscapeRightColumn: Boolean = false
) {
    val context = LocalContext.current
    val danskStiColors = DanskStiTheme.colors

    NavigationCampAndContinuePrompts(
        viewModel = viewModel,
        liveState = liveState,
        currentDay = currentDay,
        distanceRemainingToday = distanceRemainingToday,
        sunsetTime = sunsetTime,
        isNearTonightCamp = isNearTonightCamp,
        campNameForArrival = campNameForArrival,
        currentDayStat = currentDayStat,
        nextWater = nextWater,
        onDismissCampArrival = onDismissCampArrival
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable { viewModel.cycleMapMode() }
                .testTag("nav_map_mode_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when (mapMode) {
                        NavigationMapMode.FOLLOW_NORTH_UP -> Icons.Default.Navigation
                        NavigationMapMode.FOLLOW_HEADING_UP -> Icons.Default.Explore
                        NavigationMapMode.OVERVIEW -> Icons.Default.MyLocation
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = mapMode.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (liveState.isPaused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { viewModel.togglePause() }
                    .testTag("nav_pause_resume_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (liveState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = if (liveState.isPaused) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (liveState.isPaused) {
                            stringResource(R.string.nav_resume)
                        } else {
                            stringResource(R.string.nav_pause)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (liveState.isPaused) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.toggleBatterySaver() }
                    .testTag("toggle_battery_mode_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.BatterySaver,
                        contentDescription = stringResource(R.string.ui_navigationscreen_str_msparer_tilstand_61),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onSetShowNavMenu(true) }
                        .testTag("nav_menu_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.nav_menu_content_desc),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showNavMenu,
                    onDismissRequest = { onSetShowNavMenu(false) }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_keep_screen_on),
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = keepScreenOn,
                                    onCheckedChange = { viewModel.setKeepScreenOn(it) },
                                    modifier = Modifier.testTag("nav_keep_screen_on_switch")
                                )
                            }
                        },
                        onClick = { viewModel.setKeepScreenOn(!keepScreenOn) },
                        modifier = Modifier.testTag("nav_keep_screen_on_menu_item")
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.nav_voice_guidance),
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = voiceGuidance,
                                    onCheckedChange = { viewModel.setVoiceGuidance(it) },
                                    modifier = Modifier.testTag("nav_voice_guidance_switch")
                                )
                            }
                        },
                        onClick = { viewModel.setVoiceGuidance(!voiceGuidance) },
                        modifier = Modifier.testTag("nav_voice_guidance_menu_item")
                    )
                    DropdownMenuItem(
                        text = { Text(if (hasGpsFix) "Del min position" else "Venter på GPS-position") },
                        enabled = hasGpsFix,
                        onClick = {
                            onSetShowNavMenu(false)
                            viewModel.shareCurrentLocation(context)
                        },
                        modifier = Modifier.testTag("nav_share_location_top_button")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.nav_help_guide_menu_item)) },
                        onClick = {
                            onSetShowNavMenu(false)
                            onOpenHowToGuide()
                        },
                        modifier = Modifier.testTag("nav_menu_how_to_guide")
                    )
                }
            }
        }
    }

    Surface(
        shape = if (isLandscapeRightColumn) RoundedCornerShape(16.dp) else SheetTopShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isLandscapeRightColumn) 0.dp else 8.dp,
        shadowElevation = if (isLandscapeRightColumn) 0.dp else 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nav_bottom_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f) {
                                onSetDrawerExpanded(true)
                            } else if (dragAmount > 12f) {
                                onSetDrawerExpanded(false)
                            }
                        }
                    }
                    .clickable { onToggleDrawerExpanded() }
                    .testTag("nav_expand_ahead_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.nav_ahead_on_route),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isDrawerExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (isDrawerExpanded) "Skjul foran dig på ruten" else "Vis foran dig på ruten",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val dayProgressFraction = remember(liveState.distanceWalkedTodayKm, distanceRemainingToday) {
                val totalToday = (liveState.distanceWalkedTodayKm + distanceRemainingToday).coerceAtLeast(0.1)
                (liveState.distanceWalkedTodayKm / totalToday).toFloat().coerceIn(0f, 1f)
            }
            DayProgressBarWithIcons(
                progressFraction = dayProgressFraction,
                stops = dayProgressStops,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("nav_day_progress_bar")
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (isLandscapeRightColumn) {
                // In landscape right column: vertical or compact 3-stat layout so 200% font size never clips
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_stats_strip"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatDanishKm(distanceRemainingToday, 1),
                                style = TabularBodyStyle.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.testTag("nav_stat_km_left_today")
                            )
                            Text(
                                text = stringResource(R.string.nav_stat_label_km_left),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = arrivalClock,
                                style = TabularBodyStyle.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.testTag("nav_stat_arrival_time")
                            )
                            Text(
                                text = stringResource(R.string.nav_stat_label_arrival),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            val nextWaterKmText = nextWater?.let { formatDanishKm(it.distanceRemainingKm, 1) } ?: "--"
                            Text(
                                text = nextWaterKmText,
                                style = TabularBodyStyle.copy(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                ),
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                modifier = Modifier.testTag("nav_stat_next_water_km")
                            )
                            Text(
                                text = stringResource(R.string.nav_stat_label_next_water),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nav_stats_strip"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = formatDanishKm(distanceRemainingToday, 1),
                            style = TabularBodyStyle.copy(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.testTag("nav_stat_km_left_today")
                        )
                        Text(
                            text = stringResource(R.string.nav_stat_label_km_left),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = arrivalClock,
                            style = TabularBodyStyle.copy(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.testTag("nav_stat_arrival_time")
                        )
                        Text(
                            text = stringResource(R.string.nav_stat_label_arrival),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        val nextWaterKmText = nextWater?.let { formatDanishKm(it.distanceRemainingKm, 1) } ?: "--"
                        Text(
                            text = nextWaterKmText,
                            style = TabularBodyStyle.copy(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            modifier = Modifier.testTag("nav_stat_next_water_km")
                        )
                        Text(
                            text = stringResource(R.string.nav_stat_label_next_water),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("nav_next_overnight_row")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cabin,
                            contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_overnatning_62),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = nextOvernight?.name ?: "Overnatning",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = nextOvernight?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                style = TabularLabelStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("nav_next_water_row")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalDrink,
                            contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_vandpost_63),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = nextWater?.name ?: "Vandpost",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = nextWater?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                style = TabularLabelStyle,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("nav_next_shop_row")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = stringResource(R.string.ui_navigationscreen_n_ste_indk_b_64),
                            tint = danskStiColors.warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = nextShop?.name ?: "Indkøb",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = nextShop?.let { "${formatDanishKm(it.distanceRemainingKm, 1)} km" } ?: "-- km",
                                style = TabularLabelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            NavigationExpandedAheadDrawer(
                isDrawerExpanded = isDrawerExpanded,
                plannedRoute = plannedRoute,
                dayProgressFraction = dayProgressFraction,
                aheadItems = aheadItems,
                currentDay = currentDay,
                currentDayStat = currentDayStat,
                distanceWalkedTodayKm = liveState.distanceWalkedTodayKm,
                allRecordedDayStats = allRecordedDayStats,
                hasGpsFix = hasGpsFix,
                viewModel = viewModel
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        viewModel.stopTrip()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minWidth = 48.dp, minHeight = 52.dp)
                        .testTag("stop_for_today_button")
                ) {
                    Text(
                        text = stringResource(R.string.nav_stop_for_today),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = danskStiColors.danger,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { viewModel.setSosSheet(true) }
                        .testTag("nav_sos_button")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.nav_sos),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
        }
    }
}

