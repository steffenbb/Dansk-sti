package com.example.ui.components

import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TripDiaryEntryEntity
import com.example.util.WaypointPhotoHelper
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

object TripDiaryPhotoParser {
    const val MAX_PHOTOS_PER_DAY = 5

    fun parsePhotoPaths(photoPathsJson: String): List<String> {
        if (photoPathsJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(photoPathsJson)
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i)?.takeIf { it.isNotBlank() }
                if (p != null) result.add(p)
            }
            result.distinct().take(MAX_PHOTOS_PER_DAY)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object TripDiaryPhotoHelper {
    const val MAX_PHOTOS_PER_DAY = TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY
    const val MAX_PHOTO_DIMENSION_PX = 1600

    fun parsePhotoPaths(photoPathsJson: String): List<String> =
        TripDiaryPhotoParser.parsePhotoPaths(photoPathsJson)

    fun copyAndResizePhotoToInternalStorage(
        context: android.content.Context,
        sourceUri: Uri,
        planId: Long,
        dayNumber: Int
    ): String? {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val photoFile = WaypointPhotoHelper.createPhotoFile(context, "diary_${planId}_day${dayNumber}")
                FileOutputStream(photoFile).use { out ->
                    input.copyTo(out)
                }
                WaypointPhotoHelper.compressCapturedPhotoFile(photoFile)
                photoFile.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteDiaryPhotoFile(path: String): Boolean {
        if (path.isBlank()) return false
        return runCatching {
            val f = File(path)
            if (f.exists()) f.delete() else false
        }.getOrDefault(false)
    }

    fun buildTripShareSummaryText(
        trailName: String,
        totalDistanceKm: Double,
        totalMovingTimeFormatted: String,
        totalAscentMeters: Int,
        totalDays: Int,
        diaryEntries: List<TripDiaryEntryEntity>,
        locale: java.util.Locale = java.util.Locale.getDefault()
    ): String {
        val sb = StringBuilder()
        val kmStr = String.format(locale, "%.1f", totalDistanceKm)
        sb.append("# $trailName\n")
        val statsLine = com.example.util.AppLocaleHelper.strOrNull(
            null,
            R.string.diary_share_stats_line_fmt,
            kmStr,
            totalMovingTimeFormatted,
            totalAscentMeters,
            totalDays
        ) ?: "Distance: $kmStr km · Tid i bevægelse: $totalMovingTimeFormatted · Opstigning: +$totalAscentMeters m · Dage: $totalDays"
        sb.append(statsLine).append("\n")
        val nonEmpty = diaryEntries
            .filter { it.note.isNotBlank() || TripDiaryPhotoParser.parsePhotoPaths(it.photoPathsJson).isNotEmpty() }
            .sortedBy { it.dayNumber }
        if (nonEmpty.isNotEmpty()) {
            val heading = com.example.util.AppLocaleHelper.strOrNull(null, R.string.diary_share_heading) ?: "Dagbog"
            sb.append("\n## $heading\n")
            nonEmpty.forEach { entry ->
                val dayHeading = com.example.util.AppLocaleHelper.strOrNull(null, R.string.diary_share_day_heading_fmt, entry.dayNumber)
                    ?: "Dag ${entry.dayNumber}"
                sb.append("### $dayHeading\n")
                if (entry.note.isNotBlank()) {
                    sb.append("${entry.note.trim()}\n")
                }
                val photos = TripDiaryPhotoParser.parsePhotoPaths(entry.photoPathsJson)
                if (photos.isNotEmpty()) {
                    val photoText = if (photos.size == 1) {
                        com.example.util.AppLocaleHelper.strOrNull(null, R.string.diary_share_photos_singular) ?: "(1 foto)"
                    } else {
                        com.example.util.AppLocaleHelper.strOrNull(null, R.string.diary_share_photos_plural_fmt, photos.size)
                            ?: "(${photos.size} fotos)"
                    }
                    sb.append("$photoText\n")
                }
            }
        }
        return sb.toString().trim()
    }
}

@Composable
fun TripDiaryEditorSection(
    dayNumber: Int,
    existingEntry: TripDiaryEntryEntity?,
    onSaveEntry: (dayNumber: Int, note: String, photoPaths: List<String>) -> Unit,
    onCopyPhotoToInternalStorage: ((dayNumber: Int, slot: Int, uri: Uri) -> Unit)? = null,
    onDeletePhotoFile: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val initialNote = existingEntry?.note.orEmpty()
    val initialPhotos = remember(existingEntry?.photoPathsJson) {
        existingEntry?.photoPathsJson?.let { TripDiaryPhotoParser.parsePhotoPaths(it) }.orEmpty()
    }
    var note by remember(dayNumber, initialNote) { mutableStateOf(initialNote) }
    var photoPaths by remember(dayNumber, initialPhotos) { mutableStateOf(initialPhotos) }

    TripDiaryEditorSection(
        dayNumber = dayNumber,
        note = note,
        onNoteChange = { updated ->
            note = updated
            onSaveEntry(dayNumber, updated, photoPaths)
        },
        photoPaths = photoPaths,
        onPhotoPathsChange = { updated ->
            val removed = photoPaths - updated.toSet()
            removed.forEach { oldPath -> onDeletePhotoFile?.invoke(oldPath) }
            photoPaths = updated
            onSaveEntry(dayNumber, note, updated)
        },
        modifier = modifier
    )
}

@Composable
fun TripDiaryEditorSection(
    dayNumber: Int,
    note: String,
    onNoteChange: (String) -> Unit,
    photoPaths: List<String>,
    onPhotoPathsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }
    // Photo decoding/compression runs off the main thread; read the latest paths when it finishes
    val photoScope = androidx.compose.runtime.rememberCoroutineScope()
    val latestPhotoPaths by androidx.compose.runtime.rememberUpdatedState(photoPaths)
    val latestOnPhotoPathsChange by androidx.compose.runtime.rememberUpdatedState(onPhotoPathsChange)

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val path = pendingCameraPath
        pendingCameraPath = null
        if (saved && !path.isNullOrBlank()) {
            photoScope.launch {
                try {
                    val file = File(path)
                    if (WaypointPhotoHelper.compressInBackground(file)) {
                        if (latestPhotoPaths.size < TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY) {
                            latestOnPhotoPathsChange((latestPhotoPaths + file.absolutePath).distinct().take(TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } else if (!path.isNullOrBlank()) {
            runCatching { File(path).delete() }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            if (photoPaths.size >= TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY) return@let
            photoScope.launch {
                try {
                    val photoFile = WaypointPhotoHelper.importPickedPhoto(context, it, "diary_photo")
                    latestOnPhotoPathsChange((latestPhotoPaths + photoFile.absolutePath).distinct().take(TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY))
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.diary_photo_load_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier.testTag("trip_diary_editor_day_$dayNumber"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.diary_section_title_day_fmt, dayNumber),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text(stringResource(R.string.diary_note_label)) },
            placeholder = { Text(stringResource(R.string.diary_note_placeholder)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("diary_note_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.diary_photos_count_fmt,
                    photoPaths.size,
                    TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("diary_photos_count_text")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (photoPaths.size < TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY) {
                            try {
                                val (file, uri) = WaypointPhotoHelper.createPhotoCaptureTarget(context)
                                pendingCameraPath = file.absolutePath
                                cameraLauncher.launch(uri)
                            } catch (_: Exception) {
                            }
                        }
                    },
                    enabled = photoPaths.size < TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY,
                    shape = com.example.ui.theme.TileShape,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("btn_diary_take_photo")
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.diary_btn_take_photo), style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = {
                        if (photoPaths.size < TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY) {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                    enabled = photoPaths.size < TripDiaryPhotoParser.MAX_PHOTOS_PER_DAY,
                    shape = com.example.ui.theme.TileShape,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("btn_diary_pick_photo")
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.diary_btn_pick_photo), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (photoPaths.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                photoPaths.forEachIndexed { idx, path ->
                    DiaryPhotoThumbnail(
                        photoPath = path,
                        onRemove = {
                            onPhotoPathsChange(photoPaths.filterIndexed { i, _ -> i != idx })
                        },
                        testTag = "diary_photo_thumb_$idx"
                    )
                }
            }
        }
    }
}

@Composable
fun DiaryPhotoThumbnail(
    photoPath: String,
    onRemove: (() -> Unit)? = null,
    testTag: String = "diary_photo_thumb"
) {
    val bitmap = rememberPhotoThumbnail(photoPath, maxSizePx = 320)

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(com.example.ui.theme.TileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(testTag)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.diary_photo_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(R.string.diary_photo_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                    .testTag("${testTag}_remove")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_delete_item),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun TripDiaryEditDialog(
    planId: Long,
    dayNumber: Int,
    initialNote: String,
    initialPhotoPaths: List<String>,
    onSave: (note: String, photoPaths: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember(planId, dayNumber, initialNote) { mutableStateOf(initialNote) }
    var photoPaths by remember(planId, dayNumber, initialPhotoPaths) { mutableStateOf(initialPhotoPaths) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.diary_dialog_title_day_fmt, dayNumber),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            TripDiaryEditorSection(
                dayNumber = dayNumber,
                note = note,
                onNoteChange = { note = it },
                photoPaths = photoPaths,
                onPhotoPathsChange = { photoPaths = it }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(note, photoPaths)
                    onDismiss()
                },
                modifier = Modifier.testTag("btn_save_trip_diary")
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun TripDiarySummaryList(
    entries: List<TripDiaryEntryEntity>,
    modifier: Modifier = Modifier
) {
    val nonEmpty = remember(entries) {
        entries.filter { it.note.isNotBlank() || TripDiaryPhotoParser.parsePhotoPaths(it.photoPathsJson).isNotEmpty() }
            .sortedBy { it.dayNumber }
    }
    if (nonEmpty.isEmpty()) return

    Column(
        modifier = modifier.testTag("trip_diary_summary_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.diary_summary_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        nonEmpty.forEach { entry ->
            val photos = remember(entry.photoPathsJson) {
                TripDiaryPhotoParser.parsePhotoPaths(entry.photoPathsJson)
            }
            Surface(
                shape = com.example.ui.theme.TileShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trip_diary_summary_day_${entry.dayNumber}")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.diary_section_title_day_fmt, entry.dayNumber),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (entry.note.isNotBlank()) {
                        Text(
                            text = entry.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (photos.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            photos.forEachIndexed { idx, path ->
                                DiaryPhotoThumbnail(
                                    photoPath = path,
                                    onRemove = null,
                                    testTag = "trip_diary_summary_photo_${entry.dayNumber}_$idx"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
