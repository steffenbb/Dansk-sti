package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.DanskStiApp
import com.example.R
import com.example.data.local.MapDarkMode
import com.example.data.local.MapThemeOverride
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.OfflineViewModel
import com.example.util.DanishTtsChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

import android.os.Build
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Watch
import com.example.ui.components.rememberAppLocale
import com.example.util.AppLanguageOption
import com.example.util.AppLocaleHelper
import com.example.util.HikingStatisticsCalculator

/**
 * Profil settings: Min statistik, Mit tempo, daily target, start time, voice guidance & Danish TTS check,
 * watch turn cues, rain alert, "Udseende" + "Kortet følger appen", Sprog (Android 13+), data and storage,
 * Om appen / data credits / privacy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onOpenCredits: () -> Unit,
    onOpenOfflineMaps: () -> Unit = {},
    offlineViewModel: OfflineViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as DanskStiApp
    val settingsRepository = remember { app.settingsRepository }
    val dao = remember { app.database.dao() }
    val coroutineScope = rememberCoroutineScope()
    val appLocale = rememberAppLocale()

    val appSettings by settingsRepository.settingsFlow.collectAsState(
        initial = remember { settingsRepository.currentSettingsSnapshot() }
    )
    val downloadedTrails by offlineViewModel.downloadedTrails.collectAsState()
    val brouterState by offlineViewModel.brouterDataState.collectAsState()
    val isDanishVoiceInstalled by DanishTtsChecker.isDanishVoiceInstalled.collectAsState()
    val isUsingDanishFallbackVoice by DanishTtsChecker.isUsingDanishFallbackVoice.collectAsState()

    val recordedDayStats by dao.getAllRecordedDayStats().collectAsState(initial = emptyList())
    val tripPlans by dao.getAllTripPlans().collectAsState(initial = emptyList())
    val completedTripProgress by dao.getCompletedTripProgressFlow().collectAsState(initial = emptyList())
    val allTrails by dao.getAllTrails().collectAsState(initial = emptyList())

    val hikingStats = remember(recordedDayStats, tripPlans, completedTripProgress, allTrails) {
        HikingStatisticsCalculator.calculate(
            dayStats = recordedDayStats,
            plans = tripPlans,
            progressList = completedTripProgress,
            trails = allTrails
        )
    }

    var selectedLanguageOption by remember(appLocale) {
        mutableStateOf(AppLocaleHelper.getCurrentLanguageOption(context))
    }

    LaunchedEffect(Unit) {
        DanishTtsChecker.checkDanishVoiceAvailability(context)
    }

    var showPrivacyScreen by rememberSaveable { mutableStateOf(false) }
    var showHowToGuideScreen by rememberSaveable { mutableStateOf(false) }

    if (showPrivacyScreen) {
        PrivacyScreen(
            onBack = { showPrivacyScreen = false },
            modifier = modifier
        )
        return
    }

    if (showHowToGuideScreen) {
        HowToGuideScreen(
            onDismiss = {
                showHowToGuideScreen = false
                coroutineScope.launch {
                    settingsRepository.setHasSeenHowToGuide(true)
                }
            },
            modifier = modifier
        )
        return
    }

    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }
    val cacheClearedText = stringResource(R.string.storage_cache_cleared)
    var showClearCacheSheet by remember { mutableStateOf(false) }
    var cacheBreakdown by remember { mutableStateOf(com.example.data.repository.CacheBreakdown()) }

    if (showClearCacheSheet) {
        ClearCacheSheet(
            breakdown = cacheBreakdown,
            onConfirmClear = { selected ->
                showClearCacheSheet = false
                coroutineScope.launch {
                    val freedBytes = app.repository.clearSelectedCaches(selected)
                    cacheClearedMessage = "$cacheClearedText (${formatCacheSizeDanish(freedBytes)})"
                }
            },
            onDismiss = { showClearCacheSheet = false }
        )
    }

    val totalOfflineMapsMb = remember(downloadedTrails) {
        downloadedTrails.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
    }
    val brouterDownloadedMb = remember(brouterState.totalDownloadedBytes) {
        brouterState.totalDownloadedBytes / (1024.0 * 1024.0)
    }
    val brouterTotalMb = remember(brouterState.totalBytes) {
        brouterState.totalBytes / (1024.0 * 1024.0)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("profile_screen_list"),
        contentPadding = PaddingValues(
            start = Spacing.s4,
            end = Spacing.s4,
            top = Spacing.s3,
            bottom = Spacing.s8
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4)
    ) {
        item {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("profile_screen_title")
            )
        }

        // Section 0: Min statistik (Part D3)
        item {
            ProfileStatisticsSectionCard(
                stats = hikingStats,
                locale = appLocale
            )
        }

        // Section 1: Vandring & Tempo (Mit tempo, daily target, start time, voice guidance, watch cues, rain alert)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_hiking)) {
                val paceFormatted = stringResource(
                    R.string.profile_pace_value_fmt,
                    String.format(appLocale, "%.1f", appSettings.mitTempoKmh)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.setting_my_pace),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = paceFormatted,
                        style = TabularBodyStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("profile_pace_value")
                    )
                }
                Slider(
                    value = appSettings.mitTempoKmh.toFloat().coerceIn(
                        com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT.start,
                        com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT.endInclusive
                    ),
                    onValueChange = { newVal ->
                        val rounded = com.example.data.local.HikingSettingsRanges.clampPaceKmh(newVal.toDouble())
                        coroutineScope.launch {
                            settingsRepository.setMitTempoKmh(rounded)
                        }
                    },
                    valueRange = com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT,
                    steps = com.example.data.local.HikingSettingsRanges.PACE_SLIDER_STEPS,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_pace_slider")
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                val targetFormatted = stringResource(
                    R.string.profile_daily_target_value_fmt,
                    String.format(appLocale, "%.0f", appSettings.dailyTargetKm)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.setting_daily_target),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = targetFormatted,
                        style = TabularBodyStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("profile_daily_target_value")
                    )
                }
                Slider(
                    value = appSettings.dailyTargetKm.toFloat().coerceIn(
                        com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT.start,
                        com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT.endInclusive
                    ),
                    onValueChange = { newVal ->
                        val rounded = com.example.data.local.HikingSettingsRanges.clampDailyTargetKm(newVal.toDouble())
                        coroutineScope.launch {
                            settingsRepository.setDailyTargetKm(rounded)
                        }
                    },
                    valueRange = com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT,
                    steps = com.example.data.local.HikingSettingsRanges.DAILY_TARGET_SLIDER_STEPS,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_daily_target_slider")
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.setting_start_time),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.ui_profilescreen_kl_99, appSettings.startTime),
                        style = TabularBodyStyle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("profile_start_time_value")
                    )
                }
                Slider(
                    value = com.example.data.local.HikingSettingsRanges.startTimeToSliderIndex(appSettings.startTime),
                    onValueChange = { rawIdx ->
                        val newTime = com.example.data.local.HikingSettingsRanges.sliderIndexToStartTime(rawIdx)
                        coroutineScope.launch {
                            settingsRepository.setStartTime(newTime)
                        }
                    },
                    valueRange = com.example.data.local.HikingSettingsRanges.START_TIME_SLIDER_RANGE,
                    steps = com.example.data.local.HikingSettingsRanges.START_TIME_SLIDER_STEPS,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_start_time_slider")
                )
                val startTimes = listOf("07:00", "08:00", "09:00", "10:00")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                ) {
                    startTimes.forEach { timeStr ->
                        val selected = appSettings.startTime == timeStr
                        FilterChip(
                            selected = selected,
                            onClick = {
                                coroutineScope.launch {
                                    settingsRepository.setStartTime(timeStr)
                                }
                            },
                            label = {
                                Text(
                                    text = timeStr,
                                    style = TabularLabelStyle,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .heightIn(min = MinTouchTargetSize)
                                .testTag("profile_start_time_chip_$timeStr")
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Stemmeguidning + Danish TTS voice check
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.profile_voice_guidance_danish),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = appSettings.voiceGuidanceEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                settingsRepository.setVoiceGuidanceEnabled(enabled)
                            }
                            if (enabled) {
                                DanishTtsChecker.checkDanishVoiceAvailability(context)
                            }
                        },
                        modifier = Modifier.testTag("profile_voice_guidance_switch")
                    )
                }

                if (isDanishVoiceInstalled == false) {
                    Spacer(modifier = Modifier.height(Spacing.s1))
                    Text(
                        text = stringResource(R.string.profile_danish_tts_missing_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = DanskStiTheme.colors.warning,
                        modifier = Modifier.testTag("profile_danish_tts_missing_text")
                    )
                } else if (isUsingDanishFallbackVoice) {
                    Spacer(modifier = Modifier.height(Spacing.s1))
                    Text(
                        text = stringResource(R.string.profile_tts_fallback_to_danish_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("profile_tts_fallback_note_text")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Part H2: Vis sving på ur
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Watch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.profile_watch_turn_cues),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = appSettings.watchTurnCuesEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                settingsRepository.setWatchTurnCuesEnabled(enabled)
                            }
                        },
                        modifier = Modifier.testTag("profile_watch_turn_cues_switch")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Part E1: Regnvarsel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = Spacing.s2)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.profile_rain_alert),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = appSettings.rainAlertEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                settingsRepository.setRainAlertEnabled(enabled)
                            }
                        },
                        modifier = Modifier.testTag("profile_rain_alert_switch")
                    )
                }
            }
        }

        // Section 2: Kort & Visning ("Udseende", "Kortet følger appen", "Sprog" on Android 13+)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_map_display)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    Text(
                        text = stringResource(R.string.setting_appearance),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s2))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                ) {
                    MapDarkMode.entries.forEach { mode ->
                        val selected = appSettings.mapDarkMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                coroutineScope.launch {
                                    settingsRepository.setAppearanceMode(mode)
                                }
                            },
                            label = {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .heightIn(min = MinTouchTargetSize)
                                .testTag("profile_dark_map_chip_${mode.name.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.s2))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.setting_map_follows_app),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = appSettings.mapFollowsApp,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                settingsRepository.setMapFollowsApp(checked)
                            }
                        },
                        modifier = Modifier.testTag("profile_map_follows_app_switch")
                    )
                }

                if (!appSettings.mapFollowsApp) {
                    Spacer(modifier = Modifier.height(Spacing.s1))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                    ) {
                        MapThemeOverride.entries.forEach { overrideMode ->
                            val selected = appSettings.mapThemeOverride == overrideMode
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    coroutineScope.launch {
                                        settingsRepository.setMapThemeOverride(overrideMode)
                                    }
                                },
                                label = {
                                    Text(
                                        text = overrideMode.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier
                                    .heightIn(min = MinTouchTargetSize)
                                    .testTag("profile_map_override_chip_${overrideMode.name.lowercase()}")
                            )
                        }
                    }
                }

                // Part G3: Per-app language selector via LocaleManager on Android 13+ (hidden on Android 12 and older)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.s2),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.setting_language),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_language_selector"),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                    ) {
                        AppLanguageOption.entries.forEach { option ->
                            val selected = selectedLanguageOption == option
                            val labelText = when (option) {
                                AppLanguageOption.SYSTEM -> stringResource(R.string.language_follow_phone)
                                AppLanguageOption.DANISH -> stringResource(R.string.language_danish)
                                AppLanguageOption.ENGLISH -> stringResource(R.string.language_english)
                                AppLanguageOption.GERMAN -> stringResource(R.string.language_german)
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedLanguageOption = option
                                    AppLocaleHelper.setApplicationLanguage(context, option)
                                },
                                label = {
                                    Text(
                                        text = labelText,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier
                                    .heightIn(min = MinTouchTargetSize)
                                    .testTag("profile_language_chip_${option.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Data og lagerplads
        item {
            ProfileStorageSectionCard(
                downloadedTrailsCount = downloadedTrails.size,
                totalOfflineMapsMb = totalOfflineMapsMb,
                brouterState = brouterState,
                brouterDownloadedMb = brouterDownloadedMb,
                brouterTotalMb = brouterTotalMb,
                downloadWifiOnly = appSettings.offlineDownloadsWifiOnly,
                onSetDownloadWifiOnly = { wifiOnly ->
                    coroutineScope.launch {
                        settingsRepository.setOfflineDownloadsWifiOnly(wifiOnly)
                    }
                },
                onOpenOfflineMaps = onOpenOfflineMaps,
                onDownloadBrouter = { offlineViewModel.downloadBRouterData() },
                onClearCache = {
                    coroutineScope.launch {
                        cacheBreakdown = app.repository.computeCacheBreakdown()
                        showClearCacheSheet = true
                    }
                },
                cacheClearedMessage = cacheClearedMessage
            )
        }

        // Section 4: Om appen / Datakreditter & Kilder
        item {
            ProfileAboutSectionCard(
                onOpenHowToGuide = { showHowToGuideScreen = true },
                onOpenCredits = onOpenCredits,
                onOpenPrivacy = { showPrivacyScreen = true }
            )
        }
    }
}

