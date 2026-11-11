package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.CacheBreakdown
import com.example.data.repository.CacheCategory
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.TabularLabelStyle
import java.util.Locale

fun formatCacheSizeDanish(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val daLocale = Locale.forLanguageTag("da-DK")
    return when {
        safe >= 1024L * 1024L -> String.format(daLocale, "%.1f MB", safe / (1024.0 * 1024.0))
        safe >= 1024L -> String.format(daLocale, "%.1f KB", safe / 1024.0)
        else -> "$safe B"
    }
}

@Composable
fun ClearCacheSheet(
    breakdown: CacheBreakdown,
    onConfirmClear: (Set<CacheCategory>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedCategoriesState = remember {
        mutableStateOf(CacheCategory.entries.toSet())
    }
    val selectedCategories = selectedCategoriesState.value

    val totalToFreeBytes = breakdown.totalBytesFor(selectedCategories)
    val totalToFreeFormatted = formatCacheSizeDanish(totalToFreeBytes)

    val items = listOf(
        Triple(
            CacheCategory.MAP_TILES,
            stringResource(R.string.clear_cache_item_tiles),
            null as String?
        ),
        Triple(
            CacheCategory.ROUTE_LINES,
            stringResource(R.string.clear_cache_item_route_lines),
            stringResource(R.string.clear_cache_item_route_lines_sub)
        ),
        Triple(
            CacheCategory.WATER_AND_ACCESS,
            stringResource(R.string.clear_cache_item_water_access),
            null
        ),
        Triple(
            CacheCategory.MARKED_ROUTES,
            stringResource(R.string.clear_cache_item_marked_routes),
            null
        ),
        Triple(
            CacheCategory.PLACE_SEARCH,
            stringResource(R.string.clear_cache_item_place_search),
            null
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("clear_cache_sheet"),
        title = {
            Column {
                Text(
                    text = stringResource(R.string.clear_cache_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.clear_cache_sheet_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { (category, title, subtitle) ->
                    val tagSuffix = category.name.lowercase()
                    val isChecked = category in selectedCategories
                    val itemSizeStr = formatCacheSizeDanish(breakdown.bytesFor(category))

                    val toggleItem = {
                        selectedCategoriesState.value = if (isChecked) {
                            selectedCategories - category
                        } else {
                            selectedCategories + category
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTargetSize)
                            .clickable(onClick = toggleItem)
                            .padding(vertical = 4.dp)
                            .testTag("clear_cache_row_$tagSuffix"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { toggleItem() },
                            modifier = Modifier.testTag("clear_cache_checkbox_$tagSuffix")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = itemSizeStr,
                            style = TabularLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("clear_cache_size_$tagSuffix")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = stringResource(R.string.clear_cache_total_to_free_fmt, totalToFreeFormatted),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("clear_cache_total_text")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmClear(selectedCategories) },
                enabled = selectedCategories.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = MinTouchTargetSize)
                    .testTag("clear_cache_confirm_button")
            ) {
                Text(stringResource(R.string.clear_cache_confirm_btn))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = MinTouchTargetSize)
                    .testTag("clear_cache_cancel_button")
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
