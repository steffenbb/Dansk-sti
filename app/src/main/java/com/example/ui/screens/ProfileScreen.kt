package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.DanskStiApp
import com.example.R
import com.example.data.local.AppSettings
import com.example.data.local.MapDarkMode
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.CardShape
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.OfflineViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Requirement I: Profil
 * - Settings: Mit tempo, daily target, start time, "Kort i mørke", units,
 *   data and storage (offline maps size, routing data, clear cache),
 *   API keys, Om appen / data credits.
 * - Diagnostics only in debug builds or behind "Om appen" tapped 7 times.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onOpenCredits: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenOfflineMaps: () -> Unit = {},
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    offlineViewModel: OfflineViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as DanskStiApp
    val settingsRepository = remember { app.settingsRepository }
    val coroutineScope = rememberCoroutineScope()

    val appSettings by settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
    val downloadedTrails by offlineViewModel.downloadedTrails.collectAsState()
    val brouterState by offlineViewModel.brouterDataState.collectAsState()

    var aboutTapCount by rememberSaveable { mutableIntStateOf(0) }
    val showDiagnostics = isDebugBuild || aboutTapCount >= 7
    var showPrivacyScreen by rememberSaveable { mutableStateOf(false) }

    if (showPrivacyScreen) {
        PrivacyScreen(
            onBack = { showPrivacyScreen = false },
            modifier = modifier
        )
        return
    }

    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }
    val cacheClearedText = stringResource(R.string.storage_cache_cleared)

    val totalOfflineMapsMb = remember(downloadedTrails) {
        downloadedTrails.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
    }
    val brouterDownloadedMb = remember(brouterState.totalDownloadedBytes) {
        brouterState.totalDownloadedBytes / (1024.0 * 1024.0)
    }
    val brouterTotalMb = remember(brouterState.totalBytes) {
        brouterState.totalBytes / (1024.0 * 1024.0)
    }

    val hasDataforsyningenKey = remember {
        BuildConfig.DATAFORSYNINGEN_API_KEY.isNotBlank() &&
            !BuildConfig.DATAFORSYNINGEN_API_KEY.startsWith("YOUR_", ignoreCase = true)
    }
    val hasThunderforestKey = remember {
        BuildConfig.THUNDERFOREST_API_KEY.isNotBlank() &&
            !BuildConfig.THUNDERFOREST_API_KEY.startsWith("YOUR_", ignoreCase = true)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("profile_screen_list"),
        contentPadding = PaddingValues(
            start = Spacing.s4,
            end = Spacing.s4,
            top = Spacing.s3,
            bottom = Spacing.s8
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s4)
    ) {
        // Screen Header
        item {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("profile_screen_title")
            )
        }

        // Section 1: Vandring & Tempo (Mit tempo, daily target, start time)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_hiking)) {
                // 1a. Mit tempo
                val daLocale = Locale.forLanguageTag("da-DK")
                val paceFormatted = String.format(daLocale, "%.1f km/t", appSettings.mitTempoKmh)
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
                            tint = ForestGreenPrimary,
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
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_pace_value")
                    )
                }
                Slider(
                    value = appSettings.mitTempoKmh.toFloat(),
                    onValueChange = { newVal ->
                        val rounded = (newVal * 2).roundToInt() / 2.0
                        coroutineScope.launch {
                            settingsRepository.setMitTempoKmh(rounded)
                        }
                    },
                    valueRange = 2.0f..7.0f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = ForestGreenPrimary,
                        activeTrackColor = ForestGreenPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_pace_slider")
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 1b. Dagligt mål (Daily target)
                val targetFormatted = String.format(daLocale, "%.0f km / dag", appSettings.dailyTargetKm)
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
                            tint = ForestGreenPrimary,
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
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_daily_target_value")
                    )
                }
                Slider(
                    value = appSettings.dailyTargetKm.toFloat(),
                    onValueChange = { newVal ->
                        val rounded = newVal.roundToInt().toDouble()
                        coroutineScope.launch {
                            settingsRepository.setDailyTargetKm(rounded)
                        }
                    },
                    valueRange = 5.0f..40.0f,
                    steps = 34,
                    colors = SliderDefaults.colors(
                        thumbColor = ForestGreenPrimary,
                        activeTrackColor = ForestGreenPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_daily_target_slider")
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 1c. Starttidspunkt (Start time)
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
                            tint = ForestGreenPrimary,
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
                        text = "kl. ${appSettings.startTime}",
                        style = TabularBodyStyle,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_start_time_value")
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s2))
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
            }
        }

        // Section 2: Kort & Visning ("Kort i mørke", Enheder)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_map_display)) {
                // 2a. Kort i mørke
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    Text(
                        text = stringResource(R.string.setting_dark_map),
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
                                    settingsRepository.setMapDarkMode(mode)
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

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s3),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 2b. Enheder (Units)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Straighten,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    Text(
                        text = stringResource(R.string.setting_units),
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
                    val unitOptions = listOf(
                        "metric" to stringResource(R.string.units_metric),
                        "imperial" to stringResource(R.string.units_imperial)
                    )
                    unitOptions.forEach { (key, label) ->
                        val selected = appSettings.units.equals(key, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                coroutineScope.launch {
                                    settingsRepository.setUnits(key)
                                }
                            },
                            label = {
                                Text(
                                    text = label,
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
                                .testTag("profile_units_chip_$key")
                        )
                    }
                }
            }
        }

        // Section 3: Data og lagerplads (offline maps size, routing data, clear cache)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_storage)) {
                // 3a. Offline-kort
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .clickable(onClick = onOpenOfflineMaps)
                        .testTag("profile_offline_maps_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Column {
                            Text(
                                text = stringResource(R.string.storage_offline_maps),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.storage_saved_routes_count_format, downloadedTrails.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = String.format(Locale.forLanguageTag("da-DK"), "%.1f MB", totalOfflineMapsMb),
                        style = TabularBodyStyle,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_offline_maps_size")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s3),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 3b. Rutedata (BRouter)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Column {
                            Text(
                                text = stringResource(R.string.storage_routing_data),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val statusSub = if (brouterState.isAllDownloaded) {
                                stringResource(R.string.storage_brouter_all_downloaded)
                            } else {
                                stringResource(
                                    R.string.storage_brouter_segments_format,
                                    brouterState.downloadedSegments.size,
                                    brouterState.segments.size
                                )
                            }
                            Text(
                                text = statusSub,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = String.format(
                            Locale.forLanguageTag("da-DK"),
                            "%.1f / %.0f MB",
                            brouterDownloadedMb,
                            brouterTotalMb
                        ),
                        style = TabularLabelStyle,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_routing_data_size")
                    )
                }

                if (brouterState.isDownloading) {
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    LinearProgressIndicator(
                        progress = { (brouterState.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = ForestGreenPrimary
                    )
                } else {
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    OutlinedButton(
                        onClick = { offlineViewModel.downloadBRouterData() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTargetSize)
                            .testTag("profile_download_brouter_button")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = if (brouterState.isAllDownloaded) {
                                stringResource(R.string.storage_brouter_update)
                            } else {
                                stringResource(R.string.storage_brouter_download)
                            }
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s3),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 3c. Ryd cache
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val trailsDir = File(context.filesDir, "trails")
                                    trailsDir.listFiles()?.forEach { it.delete() }
                                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                } catch (_: Exception) {
                                }
                            }
                            cacheClearedMessage = cacheClearedText
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .testTag("profile_clear_cache_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    Text(text = stringResource(R.string.storage_clear_cache))
                }

                cacheClearedMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelLarge,
                        color = ForestGreenPrimary,
                        modifier = Modifier.testTag("profile_cache_cleared_text")
                    )
                }
            }
        }

        // Section 4: API-nøgler
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_api_keys)) {
                ApiKeyStatusRow(
                    title = stringResource(R.string.api_key_dataforsyningen),
                    isConfigured = hasDataforsyningenKey,
                    testTag = "profile_api_key_dataforsyningen"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ApiKeyStatusRow(
                    title = stringResource(R.string.api_key_thunderforest),
                    isConfigured = hasThunderforestKey,
                    testTag = "profile_api_key_thunderforest"
                )
            }
        }

        // Section 5: Om appen / Datakreditter & Kilder / Diagnostik (7 taps or debug build)
        item {
            ProfileSectionCard(title = stringResource(R.string.profile_section_about)) {
                // Om appen row (Tap 7 times to unlock Diagnostics)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .clickable {
                            aboutTapCount++
                        }
                        .testTag("profile_about_app_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Column {
                            Text(
                                text = "Dansk Sti v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.about_version_tap_hint),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Datakreditter & Kilder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .clickable(onClick = onOpenCredits)
                        .testTag("profile_data_credits_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.about_data_credits),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.about_data_credits),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.s2),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Privatliv & Databrug (Item 9)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTargetSize)
                        .clickable { showPrivacyScreen = true }
                        .testTag("profile_privacy_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PrivacyTip,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.s2))
                        Text(
                            text = stringResource(R.string.about_privacy),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.about_privacy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Diagnostics: only in debug builds or behind "Om appen" tapped 7 times
                if (showDiagnostics) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = Spacing.s2),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTargetSize)
                            .clickable(onClick = onOpenDiagnostics)
                            .testTag("profile_diagnostics_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = AccentAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.s2))
                            Text(
                                text = stringResource(R.string.about_diagnostics),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.about_diagnostics),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.s3))
            content()
        }
    }
}

@Composable
private fun ApiKeyStatusRow(
    title: String,
    isConfigured: Boolean,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetSize)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = if (isConfigured) ForestGreenPrimary else AccentAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.s2))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isConfigured) {
                        stringResource(R.string.api_key_configured)
                    } else {
                        stringResource(R.string.api_key_missing)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isConfigured) ForestGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isConfigured) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.api_key_configured),
                tint = ForestGreenPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    val services = listOf(
        stringResource(R.string.privacy_service_brouter_title) to stringResource(R.string.privacy_service_brouter_desc),
        stringResource(R.string.privacy_service_overpass_title) to stringResource(R.string.privacy_service_overpass_desc),
        stringResource(R.string.privacy_service_openmeteo_title) to stringResource(R.string.privacy_service_openmeteo_desc),
        stringResource(R.string.privacy_service_dataforsyningen_title) to stringResource(R.string.privacy_service_dataforsyningen_desc),
        stringResource(R.string.privacy_service_tiles_title) to stringResource(R.string.privacy_service_tiles_desc)
    )

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("privacy_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.privacy_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.privacy_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("privacy_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("privacy_screen_list"),
            contentPadding = PaddingValues(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            item {
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, ForestGreenPrimary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("privacy_no_tracking_card")
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.s4),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PrivacyTip,
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.s2))
                            Text(
                                text = stringResource(R.string.privacy_no_tracking_headline),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.privacy_no_tracking_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.privacy_services_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = Spacing.s2)
                )
                Spacer(modifier = Modifier.height(Spacing.s1))
                Text(
                    text = stringResource(R.string.privacy_services_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(services.size) { index ->
                val (serviceName, serviceDesc) = services[index]
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("privacy_service_card_$index")
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.s4),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                    ) {
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = ForestGreenPrimary
                        )
                        Text(
                            text = serviceDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

