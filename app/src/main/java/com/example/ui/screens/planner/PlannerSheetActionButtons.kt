package com.example.ui.screens.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerMode
import com.example.ui.viewmodel.RouteBuilderState
import java.util.concurrent.ConcurrentHashMap

object PlannerSheetButtonLayoutTracker {
    val lastTextLayouts = ConcurrentHashMap<String, TextLayoutResult>()
    val lastDisplayedText = ConcurrentHashMap<String, String>()

    fun clear() {
        lastTextLayouts.clear()
        lastDisplayedText.clear()
    }

    fun hasMidWordWrap(layoutResult: TextLayoutResult): Boolean {
        val text = layoutResult.layoutInput.text.text
        for (lineIdx in 0 until layoutResult.lineCount - 1) {
            val lineEnd = layoutResult.getLineEnd(lineIdx, visibleEnd = true)
            if (lineEnd in 1 until text.length) {
                val charBefore = text[lineEnd - 1]
                val charAt = text[lineEnd]
                if (!charBefore.isWhitespace() && !charAt.isWhitespace()) {
                    return true
                }
            }
        }
        return false
    }
}

internal data class PlannerActionItemSpec(
    val testTag: String,
    val label: String,
    val icon: ImageVector,
    val isPrimary: Boolean = false,
    val primaryContainerColor: Color? = null,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Formats multi-word labels into at most 2 lines at word boundaries so that
 * no word is ever split mid-word across lines.
 */
internal fun formatLabelForTwoLinesNoMidWordBreak(
    rawLabel: String,
    wrapTwoWords: Boolean
): Pair<String, Int> {
    val trimmed = rawLabel.trim()
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size <= 1) {
        return trimmed to 1
    }
    if (!wrapTwoWords && trimmed.length <= 12) {
        return trimmed to 1
    }
    if (words.size == 2) {
        return "${words[0]}\n${words[1]}" to 2
    }
    val totalChars = words.sumOf { it.length }
    var acc = 0
    var splitIdx = 1
    for (i in 0 until words.size - 1) {
        acc += words[i].length
        if (acc >= totalChars / 2) {
            splitIdx = i + 1
            break
        }
    }
    val line1 = words.subList(0, splitIdx).joinToString(" ")
    val line2 = words.subList(splitIdx, words.size).joinToString(" ")
    return "$line1\n$line2" to 2
}

@Composable
internal fun PlannerSheetActionButtonGrid(
    uiState: RouteBuilderState,
    visibleWaypointsCount: Int,
    onSuggestOvernightStops: () -> Unit,
    onStartNavigation: () -> Unit,
    onOpenTripSettings: () -> Unit,
    onOpenRundturDialog: () -> Unit,
    onOpenNearestPoiDialog: () -> Unit,
    onShareRoute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val plannedRouteColor = DanskStiTheme.colors.plannedRoute

    val items = buildList {
        add(
            PlannerActionItemSpec(
                testTag = "btn_find_overnight",
                label = if (uiState.isFindingOvernight) {
                    stringResource(R.string.planner_suggesting_overnights)
                } else {
                    stringResource(R.string.planner_suggest_overnights)
                },
                icon = Icons.Default.NightShelter,
                isPrimary = true,
                primaryContainerColor = primaryColor,
                enabled = visibleWaypointsCount >= 2 && !uiState.isFindingOvernight,
                isLoading = uiState.isFindingOvernight,
                onClick = onSuggestOvernightStops
            )
        )
        add(
            PlannerActionItemSpec(
                testTag = "btn_start_planned_navigation",
                label = if (uiState.isStarting) {
                    stringResource(R.string.planner_btn_starting)
                } else {
                    stringResource(R.string.planner_btn_start)
                },
                icon = Icons.Default.Navigation,
                isPrimary = true,
                primaryContainerColor = plannedRouteColor,
                enabled = visibleWaypointsCount >= 2 && !uiState.isSaving && !uiState.isStarting,
                isLoading = uiState.isStarting,
                onClick = onStartNavigation
            )
        )
        add(
            PlannerActionItemSpec(
                testTag = "btn_sheet_trip_settings",
                label = stringResource(R.string.planner_btn_settings),
                icon = Icons.Default.Tune,
                isPrimary = false,
                enabled = true,
                onClick = onOpenTripSettings
            )
        )
        if (uiState.mode == PlannerMode.FREE) {
            add(
                PlannerActionItemSpec(
                    testTag = "btn_open_rundtur",
                    label = stringResource(R.string.planner_btn_rundtur),
                    icon = Icons.Default.Loop,
                    isPrimary = false,
                    enabled = true,
                    onClick = onOpenRundturDialog
                )
            )
        }
        add(
            PlannerActionItemSpec(
                testTag = "btn_open_nearest_poi",
                label = stringResource(R.string.planner_btn_nearest),
                icon = Icons.Default.Explore,
                isPrimary = false,
                enabled = true,
                onClick = onOpenNearestPoiDialog
            )
        )
        add(
            PlannerActionItemSpec(
                testTag = "btn_share_route_sheet",
                label = stringResource(R.string.action_share),
                icon = Icons.Default.Share,
                isPrimary = false,
                enabled = visibleWaypointsCount >= 2,
                onClick = onShareRoute
            )
        )
    }

    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(0.85f)

    BoxWithConstraints(modifier = modifier) {
        val availWidthDp = maxWidth.value
        val minColWidthDp = 94f * (if (fontScale > 1.3f) 1.35f else 1f)
        val maxColsByWidth = (availWidthDp / minColWidthDp).toInt().coerceIn(1, items.size)
        val columns = when {
            maxColsByWidth >= items.size && availWidthDp >= 520f -> items.size
            maxColsByWidth >= 3 -> 3
            maxColsByWidth >= 2 -> 2
            else -> 1
        }
        val colWidthDp = (availWidthDp - (columns - 1) * 6f) / columns.toFloat()
        val rows = items.chunked(columns)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEach { spec ->
                        PlannerIconOverLabelButton(
                            spec = spec,
                            colWidthDp = colWidthDp,
                            fontScale = fontScale,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlannerIconOverLabelButton(
    spec: PlannerActionItemSpec,
    colWidthDp: Float,
    fontScale: Float,
    modifier: Modifier = Modifier
) {
    val wrapTwoWords = colWidthDp / fontScale < 150f || spec.label.length > 12
    val (formattedLabel, allowedLines) = remember(spec.label, wrapTwoWords) {
        formatLabelForTwoLinesNoMidWordBreak(spec.label, wrapTwoWords)
    }

    val contentColor = if (spec.isPrimary) {
        MaterialTheme.colorScheme.onPrimary
    } else if (spec.enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    val containerColor = if (spec.isPrimary) {
        if (spec.enabled) {
            spec.primaryContainerColor ?: MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        }
    } else {
        MaterialTheme.colorScheme.surface
    }

    val borderStroke = if (spec.isPrimary) {
        null
    } else {
        BorderStroke(
            1.dp,
            if (spec.enabled) MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    }

    Surface(
        onClick = spec.onClick,
        enabled = spec.enabled,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        contentColor = contentColor,
        border = borderStroke,
        modifier = modifier
            .heightIn(min = 36.dp)
            .testTag(spec.testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (spec.isLoading) {
                CircularProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = spec.label,
                    tint = contentColor,
                    modifier = Modifier
                        .size(16.dp)
                        .testTag("${spec.testTag}_icon")
                )
            }
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = formattedLabel,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = allowedLines.coerceIn(1, 2),
                softWrap = allowedLines > 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    PlannerSheetButtonLayoutTracker.lastDisplayedText[spec.testTag] = formattedLabel
                    PlannerSheetButtonLayoutTracker.lastTextLayouts[spec.testTag] = result
                },
                modifier = Modifier.testTag("${spec.testTag}_label")
            )
        }
    }
}
