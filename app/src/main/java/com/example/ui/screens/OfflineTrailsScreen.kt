package com.example.ui.screens
import com.example.R

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DanskStiApp
import com.example.data.local.AppAppearanceMode
import com.example.data.local.MapThemeOverride
import com.example.data.model.DownloadedTrailEntity
import com.example.ui.components.OfflineStatusPill
import com.example.ui.components.formatDownloadedDateDanish
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.viewmodel.OfflineViewModel
import com.example.util.routing.BRouterSegments
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineTrailsScreen(
    viewModel: OfflineViewModel = viewModel(),
    onBack: () -> Unit,
    onTrailClick: (String) -> Unit,
    onOpenCredits: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val colorScheme = MaterialTheme.colorScheme
    val danskStiColors = DanskStiTheme.colors
    val isDeviceOnline = remember(context) {
        runCatching { (context.applicationContext as DanskStiApp).repository.isDeviceOnline() }.getOrDefault(true)
    }

    val downloadedTrails by viewModel.downloadedTrails.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgressText by viewModel.downloadProgressText.collectAsState()
    val brouterState by viewModel.brouterDataState.collectAsState()
    val mapTilesWifiOnly by viewModel.mapTilesWifiOnly.collectAsState()
    val downloadRefuseError by viewModel.downloadRefuseError.collectAsState()
    var isSegmentsExpanded by remember { mutableStateOf(false) }
    var trailToDelete by remember { mutableStateOf<DownloadedTrailEntity?>(null) }

    val totalBytes = downloadedTrails.sumOf { it.sizeBytes } + brouterState.totalDownloadedBytes
    val totalMbStr = String.format(Locale("da", "DK"), "%.1f", totalBytes / (1024.0 * 1024.0))
    val deletedMsg = stringResource(R.string.library_deleted_snackbar)
    val undoLabel = stringResource(R.string.library_undo_action)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var activeSnackbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                activeSnackbarJob?.cancel()
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.commitPendingDeletedTrail()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeSnackbarJob?.cancel()
            snackbarHostState.currentSnackbarData?.dismiss()
            viewModel.commitPendingDeletedTrail()
        }
    }

    BackHandler {
        activeSnackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        viewModel.commitPendingDeletedTrail()
        onBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxSize()
            .testTag("offline_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag("offline_snackbar_host")
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.ui_offlinetrailsscreen_offline_kort_ruter_80),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.ui_offlinetrailsscreen_ruter_mb_samlet_81, downloadedTrails.size, totalMbStr),
                            style = TabularBodyStyle.copy(fontSize = 13.sp),
                            color = colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("offline_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (!isDeviceOnline) {
                        OfflineStatusPill(modifier = Modifier.padding(end = 6.dp))
                    }
                    IconButton(
                        onClick = onOpenCredits,
                        modifier = Modifier.testTag("offline_credits_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.about_data_credits),
                            tint = colorScheme.primary
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
                .consumeWindowInsets(paddingValues)
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Storage Summary Card
            item(key = "storage_summary") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = stringResource(R.string.ui_offlinetrailsscreen_enhedslager_82),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.ui_offlinetrailsscreen_mb_optaget_83, totalMbStr),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.ui_offlinetrailsscreen_kortfliser_rutedata_gemt_lok_84),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Round 9 B Item 1: "Udseende" (Følg telefonen / Lyst / Mørkt) + "Kortet følger appen"
            item(key = "appearance_mode_card") {
                val settingsRepository = remember(context) { com.example.DanskStiApp.getSettingsRepository(context) }
                val layersState by settingsRepository.mapLayersFlow.collectAsState(
                    initial = remember(settingsRepository) { settingsRepository.currentSettingsSnapshot().mapLayers }
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline_appearance_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setting_appearance),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val cardScope = androidx.compose.runtime.rememberCoroutineScope()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppAppearanceMode.entries.forEach { mode ->
                                val isSelected = layersState.appearanceMode == mode
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        if (isSelected) 1.5.dp else 1.dp,
                                        if (isSelected) colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            cardScope.launch {
                                                settingsRepository.setAppearanceMode(mode)
                                            }
                                        }
                                        .testTag("offline_appearance_mode_${mode.name}")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = mode.title,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.setting_map_follows_app),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Switch(
                                checked = layersState.mapFollowsApp,
                                onCheckedChange = { enabled ->
                                    cardScope.launch {
                                        settingsRepository.setMapFollowsApp(enabled)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = colorScheme.primary),
                                modifier = Modifier.testTag("offline_map_follows_app_switch")
                            )
                        }

                        if (!layersState.mapFollowsApp) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MapThemeOverride.entries.forEach { overrideMode ->
                                    val isSelected = layersState.mapThemeOverride == overrideMode
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            if (isSelected) 1.5.dp else 1.dp,
                                            if (isSelected) colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                cardScope.launch {
                                                    settingsRepository.setMapThemeOverride(overrideMode)
                                                }
                                            }
                                            .testTag("offline_map_override_${overrideMode.name}")
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = overrideMode.title,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Ongoing trail download banner (if active)
            if (isDownloading) {
                item(key = "trail_downloading") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = downloadProgressText.ifBlank { "Henter offline data..." },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // BRouter Routing Data for Denmark Card (Requirement B)
            item(key = "brouter_denmark_card") {
                OfflineBRouterDenmarkCard(
                    brouterState = brouterState,
                    isSegmentsExpanded = isSegmentsExpanded,
                    onToggleSegmentsExpanded = { isSegmentsExpanded = !isSegmentsExpanded },
                    viewModel = viewModel
                )
            }

            // Refusal error banner (e.g. insufficient free storage or missing network/Wi-Fi)
            downloadRefuseError?.let { errorMsg ->
                item(key = "download_refuse_error") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_refuse_error_banner"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = danskStiColors.danger.copy(alpha = 0.14f)),
                        border = BorderStroke(1.dp, danskStiColors.danger.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = errorMsg,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("offline_refuse_error_text")
                            )
                            TextButton(onClick = { viewModel.clearDownloadRefuseError() }) {
                                Text("OK", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section Header: Saved trails and maps + "Kun på Wi-Fi" toggle
            item(key = "section_header_trails") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ui_offlinetrailsscreen_gemte_ruter_og_kortfliser_92),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Kun på Wi-Fi (kortfliser)",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Switch(
                                checked = mapTilesWifiOnly,
                                onCheckedChange = { viewModel.setMapTilesWifiOnly(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = colorScheme.primary),
                                modifier = Modifier.testTag("switch_map_tiles_wifi_only")
                            )
                        }
                    }
                }
            }

            // List of downloaded trails
            if (downloadedTrails.isEmpty()) {
                item(key = "empty_trails") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = stringResource(R.string.ui_offlinetrailsscreen_ingen_offline_ruter_hentet_e_93),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.ui_offlinetrailsscreen_bn_en_vandrerute_og_tryk_p_h_94),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(downloadedTrails, key = { it.trailId }) { item ->
                    val itemMb = String.format(Locale("da", "DK"), "%.1f", item.sizeBytes / (1024.0 * 1024.0))
                    val downloadedLabel = formatDownloadedDateDanish(item.downloadedAt)

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrailClick(item.trailId) }
                            .testTag("offline_trail_item_${item.trailId}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.trailName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$itemMb MB · ${item.tileCount} fliser · $downloadedLabel",
                                    style = TabularBodyStyle.copy(fontSize = 16.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("offline_trail_downloaded_date_${item.trailId}")
                                )
                            }

                            IconButton(
                                onClick = { trailToDelete = item },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("delete_offline_${item.trailId}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.ui_offlinetrailsscreen_slet_offline_kort_for_96, item.trailName),
                                    tint = danskStiColors.danger.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog with Fortryd snackbar
    trailToDelete?.let { trail ->
        AlertDialog(
            onDismissRequest = { trailToDelete = null },
            title = {
                Text(stringResource(R.string.ui_offlinetrailsscreen_slet_offline_kort_97))
            },
            text = {
                Text(stringResource(R.string.ui_offlinetrailsscreen_er_du_sikker_p_at_du_vil_98, trail.trailName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeSnackbarJob?.cancel()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        viewModel.deleteDownloadedTrailWithUndo(trail)
                        trailToDelete = null
                        activeSnackbarJob = coroutineScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "$deletedMsg: ${trail.trailName}",
                                actionLabel = undoLabel,
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undoLastDeletedTrail()
                            } else {
                                viewModel.commitPendingDeletedTrail()
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete_offline_button")
                ) {
                    Text(stringResource(R.string.btn_delete), color = danskStiColors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { trailToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
