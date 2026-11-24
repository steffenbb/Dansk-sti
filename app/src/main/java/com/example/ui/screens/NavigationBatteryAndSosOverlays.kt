package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.TripDiaryEntryEntity
import com.example.service.NavigationLiveState
import com.example.ui.components.TripDiaryEditorSection
import com.example.ui.components.TripDiarySummaryList
import com.example.ui.components.formatDanishKm
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.NextStopInfo

@Composable
fun NavigationBatterySaverScreen(
    modifier: Modifier = Modifier,
    todayStageText: String,
    cueBannerPrimaryText: String,
    distanceRemainingToday: Double,
    speedKmh: Double,
    nextOvernight: NextStopInfo?,
    currentDayStat: RecordedDayStatEntity?,
    hasGpsFix: Boolean,
    emergencyInfo: DanishEmergencyInfo?,
    liveState: NavigationLiveState = NavigationLiveState(),
    onRerouteBack: () -> Unit = {},
    onTakeDetour: () -> Unit = {},
    onExitBatterySaver: () -> Unit,
    onOpenSos: () -> Unit,
    onShareLocation: () -> Unit
) {
    val danskStiColors = DanskStiTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .testTag("battery_mode_screen")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BatterySaver,
                        contentDescription = null,
                        tint = danskStiColors.success,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.nav_battery_saver_label),
                        color = danskStiColors.success,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { onExitBatterySaver() }
                        .testTag("exit_battery_mode_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.nav_battery_saver_exit),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = todayStageText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = cueBannerPrimaryText,
                    color = danskStiColors.warning,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("battery_mode_cue_text")
                )

                if (liveState.isOffTrailWarning && !liveState.isDetourMuted) {
                    val offMeters = kotlin.math.round(liveState.offTrailDistanceMeters).toInt().coerceAtLeast(1)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = danskStiColors.offRoute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("battery_mode_off_route_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.nav_off_route_banner_format, offMeters),
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.testTag("battery_mode_off_route_text")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onRerouteBack,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.onError,
                                        contentColor = danskStiColors.offRoute
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("battery_mode_reroute_back_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_reroute_back),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                OutlinedButton(
                                    onClick = onTakeDetour,
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onError),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onError),
                                    modifier = Modifier
                                        .weight(1f)
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("battery_mode_take_detour_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.nav_taking_detour),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(distanceRemainingToday, 1)),
                    style = MaterialTheme.typography.headlineLarge.merge(
                        TabularBodyStyle.copy(
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Black
                        )
                    )
                )
                Text(
                    text = stringResource(R.string.nav_remaining_today_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_km_t_57, formatDanishKm(speedKmh, 1)),
                            style = MaterialTheme.typography.headlineMedium.merge(
                                TabularBodyStyle.copy(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        )
                        Text(
                            text = stringResource(R.string.nav_walking_pace_label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    nextOvernight?.let { next ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.ui_markedroutedetailsheet_km_19, formatDanishKm(next.distanceRemainingKm, 1)),
                                style = MaterialTheme.typography.headlineMedium.merge(
                                    TabularBodyStyle.copy(
                                        color = danskStiColors.warning,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            )
                            Text(
                                text = next.name,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                        Text(
                            text = formatMovingTime(movSecs),
                            style = MaterialTheme.typography.titleLarge.merge(
                                TabularBodyStyle.copy(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        )
                        Text(
                            text = stringResource(R.string.nav_active_time_label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val ascent = currentDayStat?.ascentMeters ?: 0
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_m_58, ascent),
                            style = MaterialTheme.typography.titleLarge.merge(
                                TabularBodyStyle.copy(
                                    color = danskStiColors.success,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        )
                        Text(
                            text = stringResource(R.string.stat_elevation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = danskStiColors.danger,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { onOpenSos() }
                            .testTag("battery_mode_sos_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = stringResource(R.string.ui_navigationscreen_sos_n_dkald_59),
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.nav_sos_112),
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(enabled = hasGpsFix) { onShareLocation() }
                            .testTag("battery_mode_share_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = if (hasGpsFix) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasGpsFix) stringResource(R.string.nav_sos_share_location) else stringResource(R.string.nav_sos_waiting_gps),
                                color = if (hasGpsFix) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                emergencyInfo?.let { em ->
                    Text(
                        text = em.decimalCoords,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSosBottomSheet(
    liveState: NavigationLiveState,
    emergencyInfo: DanishEmergencyInfo?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val danskStiColors = DanskStiTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = danskStiColors.danger.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = danskStiColors.danger,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.ui_navigationscreen_dansk_n_dkald_112_74),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = danskStiColors.danger
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.ui_navigationscreen_din_n_jagtige_gps_position_75),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val coordsText = emergencyInfo?.decimalCoords
                        ?: if (liveState.currentLat != null && liveState.currentLng != null) {
                            "${liveState.currentLat}, ${liveState.currentLng}"
                        } else {
                            stringResource(R.string.nav_sos_waiting_gps)
                        }
                    Text(
                        text = coordsText,
                        style = MaterialTheme.typography.titleMedium.merge(
                            TabularBodyStyle.copy(
                                fontWeight = FontWeight.Bold
                            )
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    emergencyInfo?.spokenWgs84?.let { spoken ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_l_ses_h_jt_til_alarmcent_76, spoken),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:112")
                    }
                    try {
                        context.startActivity(dialIntent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.nav_sos_no_phone_app),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.nav_sos_phone_app_error),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("call_112_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ui_navigationscreen_ring_112_nu_77),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError
                )
            }

            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.ui_navigationscreen_luk_n_dsystem_78))
            }
        }
    }
}

@Composable
fun NavigationCampAndContinuePrompts(
    viewModel: NavigationViewModel,
    liveState: NavigationLiveState,
    currentDay: Int,
    distanceRemainingToday: Double,
    sunsetTime: String,
    isNearTonightCamp: Boolean,
    campNameForArrival: String?,
    currentDayStat: RecordedDayStatEntity?,
    nextWater: NextStopInfo?,
    onDismissCampArrival: () -> Unit
) {
    val context = LocalContext.current
    val diaryEntries by viewModel.diaryEntries.collectAsState()
    val currentDayDiaryEntry = diaryEntries.firstOrNull { it.dayNumber == currentDay }

    liveState.autoAdvancedDayMessage?.let { msg ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("nav_auto_advanced_day_banner")) {
            Text(text = msg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(12.dp))
        }
    }
    if (isNearTonightCamp && campNameForArrival != null) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("nav_camp_arrival_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_camp_arrival_prompt_format, campNameForArrival),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val walkedToday = formatDanishKm(liveState.distanceWalkedTodayKm, 1)
                val movSecs = currentDayStat?.movingTimeSeconds ?: 0L
                val ascent = currentDayStat?.ascentMeters ?: 0
                Text(
                    text = stringResource(
                        R.string.nav_day_ended_summary_fmt,
                        currentDay,
                        walkedToday,
                        formatMovingTime(movSecs),
                        ascent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TripDiaryEditorSection(
                    dayNumber = currentDay,
                    existingEntry = currentDayDiaryEntry,
                    onSaveEntry = { day, note, photos ->
                        viewModel.saveDiaryEntry(liveState.planId, day, note, photos)
                    },
                    onCopyPhotoToInternalStorage = { day, _, uri ->
                        viewModel.copyAndResizeDiaryPhoto(liveState.planId, day, uri) {}
                    },
                    onDeletePhotoFile = { path ->
                        viewModel.deleteDiaryPhotoFile(path)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.stopForToday() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("nav_camp_arrival_confirm_button")
                    ) {
                        Text(
                            text = stringResource(R.string.nav_stop_for_today),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onDismissCampArrival,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("nav_camp_arrival_dismiss_button")
                    ) {
                        Text(stringResource(R.string.nav_continue_action))
                    }
                }
            }
        }
    }

    if (liveState.showContinueDayPrompt) {
        val stageKm = liveState.currentStage?.stageKm ?: distanceRemainingToday
        val firstWaterText = nextWater?.let { "${it.name} (${formatDanishKm(it.distanceRemainingKm, 1)} km)" } ?: stringResource(R.string.nav_first_water_none)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("nav_continue_day_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.nav_continue_day_title_format, currentDay),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.nav_continue_day_summary_format,
                        formatDanishKm(stageKm, 1),
                        firstWaterText,
                        sunsetTime
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(
                    onClick = {
                        viewModel.setPaused(false)
                        viewModel.dismissContinueDayPrompt()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("nav_continue_day_button")
                ) {
                    Text(
                        text = stringResource(R.string.nav_continue_day_title_format, currentDay),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationTripCompletedSummaryDialog(
    trailName: String, totalDistanceKm: Double, totalMovingSeconds: Long,
    totalAscentMeters: Int, totalDays: Int, diaryEntries: List<TripDiaryEntryEntity> = emptyList(),
    onShareTrip: (() -> Unit)? = null, onDismiss: () -> Unit, onFinishAndBack: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("nav_trip_completed_summary_dialog"),
        title = { Text(text = stringResource(R.string.nav_trip_completed_title), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (trailName.isNotBlank()) {
                    Text(text = trailName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.nav_trip_completed_distance_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(text = "${formatDanishKm(totalDistanceKm, 1)} km", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nav_completed_distance_text"))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.nav_trip_completed_time_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(text = formatMovingTime(totalMovingSeconds), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nav_completed_time_text"))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.nav_trip_completed_ascent_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(text = "+$totalAscentMeters m", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nav_completed_ascent_text"))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.nav_trip_completed_days_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(text = "$totalDays ${if (totalDays == 1) stringResource(R.string.day_singular) else stringResource(R.string.day_plural)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nav_completed_days_text"))
                        }
                    }
                }
                TripDiarySummaryList(entries = diaryEntries)
                Text(text = stringResource(R.string.nav_trip_completed_footer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = if (onShareTrip != null) {
            {
                OutlinedButton(onClick = onShareTrip, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("nav_trip_completed_share_button")) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Del", fontWeight = FontWeight.Bold)
                }
            }
        } else null,
        confirmButton = {
            Button(onClick = onFinishAndBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("nav_trip_completed_close_button")) {
                Text(stringResource(R.string.nav_trip_completed_finish_button), fontWeight = FontWeight.Bold)
            }
        }
    )
}


