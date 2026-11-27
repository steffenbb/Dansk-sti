package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.TripDiaryEntryEntity
import com.example.ui.components.TripDiaryPhotoParser
import com.example.ui.components.formatDanishKm
import com.example.ui.components.formatDownloadedDateDanish
import com.example.ui.components.rememberAppLocale
import com.example.ui.theme.CardShape
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.util.OfflineDownloadManager
import java.text.SimpleDateFormat
import java.util.Date

@Composable
internal fun RecordingLibraryCard(
    recording: RecordedDayStatEntity,
    diaryEntry: TripDiaryEntryEntity? = null,
    onClick: () -> Unit,
    onOpenDiary: () -> Unit = {},
    onDelete: () -> Unit
) {
    val locale = rememberAppLocale()
    val dateStr = remember(recording.lastUpdatedMillis, locale) {
        SimpleDateFormat("d. MMM yyyy", locale).format(Date(recording.lastUpdatedMillis))
    }
    val hours = recording.movingTimeSeconds / 3600
    val mins = (recording.movingTimeSeconds % 3600) / 60
    val diaryPhotosCount = remember(diaryEntry?.photoPathsJson) {
        diaryEntry?.photoPathsJson?.let { TripDiaryPhotoParser.parsePhotoPaths(it).size } ?: 0
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recording_card_${recording.planId}_${recording.dayNumber}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.s3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui_savedplansscreen_optagelse_dag_179, recording.dayNumber),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.ui_savedplansscreen_km_m_t_m_180,
                            formatDanishKm(recording.distanceKm, 1, locale),
                            recording.ascentMeters,
                            hours,
                            mins,
                            dateStr
                        ),
                        style = TabularBodyStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onOpenDiary,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_recording_diary_${recording.planId}_${recording.dayNumber}")
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = stringResource(R.string.diary_btn_open),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_recording_${recording.planId}_${recording.dayNumber}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_delete_item),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (diaryEntry != null && (diaryEntry.note.isNotBlank() || diaryPhotosCount > 0)) {
                Surface(
                    shape = com.example.ui.theme.TileShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDiary() }
                        .testTag("recording_diary_preview_${recording.planId}_${recording.dayNumber}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (diaryEntry.note.isNotBlank()) {
                            Text(
                                text = diaryEntry.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (diaryPhotosCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.diary_photos_count_fmt,
                                    diaryPhotosCount,
                                    TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OfflineTrailLibraryCard(
    item: DownloadedTrailEntity,
    isOutdated: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val locale = rememberAppLocale()
    val itemMb = String.format(locale, "%.1f", item.sizeBytes / (1024.0 * 1024.0))
    val downloadedLabel = formatDownloadedDateDanish(item.downloadedAt, locale)
    val effectiveType = OfflineDownloadManager.inferDownloadType(item.trailId, item.downloadType)
    val typeIcon = when (effectiveType) {
        OfflineDownloadManager.TYPE_ROUTE -> Icons.Default.Route
        OfflineDownloadManager.TYPE_PLAN -> Icons.Default.CalendarMonth
        OfflineDownloadManager.TYPE_AREA -> Icons.Default.Map
        else -> Icons.Default.Hiking
    }
    val typeLabel = when (effectiveType) {
        OfflineDownloadManager.TYPE_ROUTE -> stringResource(R.string.offline_type_custom_route)
        OfflineDownloadManager.TYPE_PLAN -> stringResource(R.string.offline_type_plan)
        OfflineDownloadManager.TYPE_AREA -> stringResource(R.string.offline_type_area)
        else -> stringResource(R.string.offline_type_trail)
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("offline_trail_item_${item.trailId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = typeLabel,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.s3))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.trailName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("offline_item_type_badge_${item.trailId}")
                            )
                        }
                    }
                    Text(
                        text = "$itemMb MB · ${item.tileCount} fliser · $downloadedLabel",
                        style = TabularBodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("offline_trail_downloaded_date_${item.trailId}")
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_offline_${item.trailId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.cd_delete_item),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (isOutdated) {
                Text(
                    text = stringResource(R.string.offline_route_changed_update_prompt),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("offline_item_outdated_badge_${item.trailId}")
                )
            }
        }
    }
}
