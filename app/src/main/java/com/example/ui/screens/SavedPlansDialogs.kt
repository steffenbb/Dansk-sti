package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CustomRouteEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.TripDiaryEntryEntity
import com.example.data.model.TripGearItemEntity
import com.example.data.model.TripPlanEntity
import com.example.ui.components.PackingListModalSheet
import com.example.ui.components.TripDiaryEditDialog
import com.example.ui.components.TripDiaryPhotoParser
import com.example.ui.theme.CardShape
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.BRouterDataState
import com.example.ui.viewmodel.SavedViewModel

@Composable
internal fun BRouterOfflineDataCard(
    brouterState: BRouterDataState,
    isSegmentsExpanded: Boolean,
    onToggleSegmentsExpanded: () -> Unit,
    viewModel: SavedViewModel
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (brouterState.isAllDownloaded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("brouter_data_card")
    ) {
        Column(modifier = Modifier.padding(Spacing.s4)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (brouterState.isAllDownloaded) Icons.Default.CheckCircle else Icons.Default.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.s3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui_offlinetrailsscreen_rutedata_for_danmark_85),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (brouterState.isAllDownloaded) {
                            val date = brouterState.lastUpdatedDate ?: "Opdateret"
                            stringResource(R.string.brouter_ready_offline_planning_fmt, date)
                        } else {
                            stringResource(R.string.offline_routing_data_denmark_mb_format, brouterState.totalSizeMb)
                        },
                        style = TabularLabelStyle,
                        color = if (brouterState.isAllDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    brouterState.statusNotice?.let { notice ->
                        Text(
                            text = notice,
                            style = TabularLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = if (brouterState.hasVersionMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag(if (brouterState.hasVersionMismatch) "brouter_version_mismatch_badge" else "brouter_update_available_badge")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s3))

            if (brouterState.isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (brouterState.currentSegmentName.isNotBlank())
                                "Henter ${brouterState.currentSegmentName}..."
                            else "Henter rutedata...",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = stringResource(R.string.ui_offlinetrailsscreen_fmt_86, brouterState.progressPercent),
                            style = TabularLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { brouterState.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                    OutlinedButton(
                        onClick = { viewModel.cancelBRouterDownload() },
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("btn_brouter_cancel")
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSegmentsExpanded() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isSegmentsExpanded) stringResource(R.string.brouter_hide_coverage_sizes) else stringResource(R.string.brouter_show_coverage_sizes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (isSegmentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = isSegmentsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.s2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                    ) {
                        brouterState.segments.forEach { seg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("brouter_segment_${seg.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.ui_offlinetrailsscreen_fmt_87, seg.filename, seg.name),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.ui_offlinetrailsscreen_mb_88, seg.sizeMb),
                                    style = TabularLabelStyle,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.ui_offlinetrailsscreen_hent_kun_via_wi_fi_89), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = brouterState.wifiOnly,
                        onCheckedChange = { viewModel.setBRouterWifiOnly(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("switch_brouter_wifi_only")
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s2))
                if (brouterState.isAllDownloaded) {
                    Button(
                        onClick = { viewModel.updateBRouterData() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = com.example.ui.theme.TileShape,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("btn_brouter_update")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.storage_routing_update))
                    }
                } else {
                    Button(
                        onClick = { viewModel.downloadBRouterData() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = com.example.ui.theme.TileShape,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("btn_brouter_download")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_offlinetrailsscreen_hent_rutedata_mb_91, brouterState.totalSizeMb))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedPlansModalsAndDialogs(
    activePackingListPlan: TripPlanEntity?,
    onDismissPackingList: () -> Unit,
    savedPlans: List<TripPlanEntity>,
    gearItemsByPlanId: Map<Long, List<TripGearItemEntity>>,
    activeDiaryTarget: RecordedDayStatEntity?,
    onDismissDiary: () -> Unit,
    diaryEntriesByKey: Map<String, TripDiaryEntryEntity>,
    pendingOfflineDownloadRoute: CustomRouteEntity?,
    pendingOfflineDownloadPlan: TripPlanEntity?,
    onClearPendingOfflineDownload: () -> Unit,
    onSwitchToOfflineSegment: () -> Unit,
    pendingCascadeDeleteRoute: CustomRouteEntity?,
    pendingCascadeDeletePlan: TripPlanEntity?,
    onClearPendingCascadeDelete: () -> Unit,
    showUndoSnackbar: (String) -> Unit,
    viewModel: SavedViewModel
) {
    // Round 21 D1: Packing List modal sheet
    activePackingListPlan?.let { plan ->
        val items = gearItemsByPlanId[plan.id].orEmpty()
        val otherPlans = remember(savedPlans, plan.id) {
            savedPlans.filter { it.id != plan.id }
        }
        PackingListModalSheet(
            planName = plan.trailName,
            items = items,
            otherPlans = otherPlans,
            onToggleItem = { updated -> viewModel.updateGearItem(updated) },
            onAddItem = { cat, title, weight -> viewModel.addGearItem(plan.id, cat, title, weight) },
            onEditItem = { updated -> viewModel.updateGearItem(updated) },
            onDeleteItem = { target -> viewModel.deleteGearItem(target) },
            onCopyFromPlan = { sourcePlanId -> viewModel.copyGearItemsFromPlan(sourcePlanId, plan.id) },
            onDismiss = onDismissPackingList
        )
    }

    // Round 21 D2: Trip Diary edit dialog
    activeDiaryTarget?.let { rec ->
        val existingEntry = diaryEntriesByKey["${rec.planId}_${rec.dayNumber}"]
        val initialNote = existingEntry?.note.orEmpty()
        val initialPhotos = remember(existingEntry?.photoPathsJson) {
            existingEntry?.photoPathsJson?.let {
                TripDiaryPhotoParser.parsePhotoPaths(it)
            }.orEmpty()
        }
        TripDiaryEditDialog(
            planId = rec.planId,
            dayNumber = rec.dayNumber,
            initialNote = initialNote,
            initialPhotoPaths = initialPhotos,
            onSave = { note, photos ->
                viewModel.saveTripDiaryEntry(rec.planId, rec.dayNumber, note, photos)
            },
            onDismiss = onDismissDiary
        )
    }

    // Round 21 C1: Offline download dialog for saved route or trip plan
    val activeOfflineTargetName = pendingOfflineDownloadRoute?.name ?: pendingOfflineDownloadPlan?.trailName
    if (activeOfflineTargetName != null) {
        var includeAerial by remember { mutableStateOf(false) }
        var includeTerrain by remember { mutableStateOf(false) }
        var wifiOnly by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onClearPendingOfflineDownload,
            title = {
                Text(
                    text = stringResource(R.string.btn_download_offline) + ": $activeOfflineTargetName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.offline_dataforsyningen_only_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeAerial = !includeAerial }
                    ) {
                        Checkbox(
                            checked = includeAerial,
                            onCheckedChange = { includeAerial = it }
                        )
                        Text(
                            text = stringResource(R.string.offline_layer_aerial),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeTerrain = !includeTerrain }
                    ) {
                        Checkbox(
                            checked = includeTerrain,
                            onCheckedChange = { includeTerrain = it }
                        )
                        Text(
                            text = stringResource(R.string.offline_layer_terrain),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { wifiOnly = !wifiOnly }
                    ) {
                        Checkbox(
                            checked = wifiOnly,
                            onCheckedChange = { wifiOnly = it }
                        )
                        Text(
                            text = stringResource(R.string.offline_wifi_only_map_tiles),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingOfflineDownloadRoute?.let { route ->
                            viewModel.downloadCustomRouteOffline(
                                route = route,
                                wifiOnly = wifiOnly,
                                includeAerial = includeAerial,
                                includeTerrain = includeTerrain
                            )
                        }
                        pendingOfflineDownloadPlan?.let { plan ->
                            viewModel.downloadTripPlanOffline(
                                plan = plan,
                                wifiOnly = wifiOnly,
                                includeAerial = includeAerial,
                                includeTerrain = includeTerrain
                            )
                        }
                        onClearPendingOfflineDownload()
                        onSwitchToOfflineSegment()
                    },
                    modifier = Modifier.testTag("btn_confirm_offline_download_dialog")
                ) {
                    Text(stringResource(R.string.btn_download_offline))
                }
            },
            dismissButton = {
                TextButton(onClick = onClearPendingOfflineDownload) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Round 21 C5: Cascade delete prompt when a saved route or plan has an offline map download
    val cascadeRoute = pendingCascadeDeleteRoute
    val cascadePlan = pendingCascadeDeletePlan
    if (cascadeRoute != null || cascadePlan != null) {
        val targetName = cascadeRoute?.name ?: cascadePlan?.trailName.orEmpty()
        AlertDialog(
            onDismissRequest = onClearPendingCascadeDelete,
            title = {
                Text(
                    text = stringResource(R.string.offline_cascade_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.offline_cascade_delete_message_fmt, targetName),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        cascadeRoute?.let {
                            viewModel.deleteCustomRouteWithUndo(it, alsoDeleteOfflineMap = true)
                            showUndoSnackbar(it.name)
                        }
                        cascadePlan?.let {
                            viewModel.deletePlanWithUndo(it, alsoDeleteOfflineMap = true)
                            showUndoSnackbar(it.trailName)
                        }
                        onClearPendingCascadeDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("btn_cascade_delete_both")
                ) {
                    Text(stringResource(R.string.offline_cascade_delete_also_offline))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClearPendingCascadeDelete) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                    OutlinedButton(
                        onClick = {
                            cascadeRoute?.let {
                                viewModel.deleteCustomRouteWithUndo(it, alsoDeleteOfflineMap = false)
                                showUndoSnackbar(it.name)
                            }
                            cascadePlan?.let {
                                viewModel.deletePlanWithUndo(it, alsoDeleteOfflineMap = false)
                                showUndoSnackbar(it.trailName)
                            }
                            onClearPendingCascadeDelete()
                        },
                        modifier = Modifier.testTag("btn_cascade_keep_offline")
                    ) {
                        Text(stringResource(R.string.offline_cascade_keep_offline))
                    }
                }
            }
        )
    }
}
