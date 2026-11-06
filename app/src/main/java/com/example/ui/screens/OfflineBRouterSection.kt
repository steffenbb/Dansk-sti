package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.BRouterDataState
import com.example.ui.viewmodel.OfflineViewModel

@Composable
internal fun OfflineBRouterDenmarkCard(
    brouterState: BRouterDataState,
    isSegmentsExpanded: Boolean,
    onToggleSegmentsExpanded: () -> Unit,
    viewModel: OfflineViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val danskStiColors = DanskStiTheme.colors

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (brouterState.isAllDownloaded) colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("brouter_data_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (brouterState.isAllDownloaded) colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (brouterState.isAllDownloaded) Icons.Default.CheckCircle else Icons.Default.Route,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui_offlinetrailsscreen_rutedata_for_danmark_85),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (brouterState.isAllDownloaded) {
                            val date = brouterState.lastUpdatedDate ?: "Opdateret"
                            "Klar til brug uden mobildækning • $date"
                        } else {
                            "BRouter routing data (5 segmenter, ${brouterState.totalSizeMb} MB)"
                        },
                        fontSize = 12.sp,
                        color = if (brouterState.isAllDownloaded) colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (brouterState.hasVersionMismatch) {
                        Text(
                            text = stringResource(R.string.brouter_version_mismatch_update_needed),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = danskStiColors.danger,
                            modifier = Modifier.testTag("brouter_version_mismatch_badge")
                        )
                    } else if (brouterState.isUpdateAvailable) {
                        Text(
                            text = stringResource(R.string.brouter_update_available),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary,
                            modifier = Modifier.testTag("brouter_update_available_badge")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (brouterState.isDownloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (brouterState.currentSegmentName.isNotBlank())
                                "Henter ${brouterState.currentSegmentName}..."
                            else "Henter rutedata...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.ui_offlinetrailsscreen_fmt_86, brouterState.progressPercent),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { brouterState.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = colorScheme.primary,
                        trackColor = colorScheme.primaryContainer
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.cancelBRouterDownload() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("btn_brouter_cancel")
                        ) {
                            Text(stringResource(R.string.btn_cancel), fontSize = 12.sp)
                        }
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
                        text = if (isSegmentsExpanded) "Skjul dækning og filstørrelser" else "Vis dækning og filstørrelser (5 filer)",
                        fontSize = 12.sp,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (isSegmentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isSegmentsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        brouterState.segments.forEach { seg ->
                            val isSegDownloaded = brouterState.downloadedSegments.contains(seg.filename)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("brouter_segment_${seg.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isSegDownloaded) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = if (isSegDownloaded) colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.ui_offlinetrailsscreen_fmt_87, seg.filename, seg.name),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.ui_offlinetrailsscreen_mb_88, seg.sizeMb),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            text = stringResource(R.string.ui_offlinetrailsscreen_hent_kun_via_wi_fi_89),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = brouterState.wifiOnly,
                        onCheckedChange = { viewModel.setBRouterWifiOnly(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = colorScheme.primary),
                        modifier = Modifier.testTag("switch_brouter_wifi_only")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (brouterState.isAllDownloaded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = brouterState.lastUpdatedDate?.let { "Opdateret: $it" } ?: "Hentet lokalt",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("brouter_last_updated_text")
                        )
                        Button(
                            onClick = { viewModel.updateBRouterData() },
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("btn_brouter_update")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_offlinetrailsscreen_opdater_90))
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.downloadBRouterData() },
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_brouter_download")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_offlinetrailsscreen_hent_rutedata_mb_91, brouterState.totalSizeMb))
                    }
                }
            }
        }
    }
}
