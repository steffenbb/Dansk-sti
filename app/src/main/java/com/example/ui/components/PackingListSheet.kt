package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TripGearItemEntity
import com.example.data.model.TripPlanEntity
import com.example.util.AppLocaleHelper
import java.util.Locale

object PackingListFormatter {
    fun formatSummary(
        context: Context,
        items: List<TripGearItemEntity>,
        locale: Locale = AppLocaleHelper.getCurrentLocale(context)
    ): String {
        val packedCount = items.count { it.isPacked }
        val totalCount = items.size
        val hasAnyWeight = items.any { (it.weightGrams ?: 0) > 0 }
        val packedGrams = items.filter { it.isPacked }.sumOf { it.weightGrams ?: 0 }
        return if (hasAnyWeight) {
            val kgStr = String.format(locale, "%.1f", packedGrams / 1000.0)
            context.getString(
                R.string.packing_list_summary_with_weight_fmt,
                packedCount.toString(),
                totalCount.toString(),
                kgStr
            )
        } else {
            context.getString(
                R.string.packing_list_summary_no_weight_fmt,
                packedCount.toString(),
                totalCount.toString()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackingListModalSheet(
    planName: String,
    items: List<TripGearItemEntity>,
    otherPlans: List<TripPlanEntity>,
    onToggleItem: (TripGearItemEntity) -> Unit,
    onAddItem: (category: String, title: String, weightGrams: Int?) -> Unit,
    onEditItem: (TripGearItemEntity) -> Unit,
    onDeleteItem: (TripGearItemEntity) -> Unit,
    onCopyFromPlan: (sourcePlanId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("packing_list_modal_sheet")
    ) {
        PackingListContent(
            planName = planName,
            items = items,
            otherPlans = otherPlans,
            onToggleItem = onToggleItem,
            onAddItem = onAddItem,
            onEditItem = onEditItem,
            onDeleteItem = onDeleteItem,
            onCopyFromPlan = onCopyFromPlan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun PackingListContent(
    planName: String,
    items: List<TripGearItemEntity>,
    otherPlans: List<TripPlanEntity>,
    onToggleItem: (TripGearItemEntity) -> Unit,
    onAddItem: (category: String, title: String, weightGrams: Int?) -> Unit,
    onEditItem: (TripGearItemEntity) -> Unit,
    onDeleteItem: (TripGearItemEntity) -> Unit,
    onCopyFromPlan: (sourcePlanId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locale = rememberAppLocale()
    val summaryText = remember(items, locale) {
        PackingListFormatter.formatSummary(context, items, locale)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<TripGearItemEntity?>(null) }
    var showCopyFromPlanDialog by remember { mutableStateOf(false) }

    val grouped = remember(items) {
        items.groupBy { it.category.ifBlank { context.getString(R.string.packing_category_other) } }
    }

    Column(
        modifier = modifier.testTag("packing_list_content"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.packing_list_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (planName.isNotBlank()) {
                    Text(
                        text = planName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("packing_list_summary_text")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp)
                    .testTag("btn_packing_add_item")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.packing_list_add_item),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (otherPlans.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showCopyFromPlanDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .testTag("btn_packing_copy_from_plan")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.packing_list_copy_from_previous),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .testTag("packing_list_lazy_column"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            grouped.forEach { (category, categoryItems) ->
                item(key = "cat_$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                items(categoryItems, key = { it.id }) { gearItem ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (gearItem.isPacked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("packing_item_row_${gearItem.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleItem(gearItem.copy(isPacked = !gearItem.isPacked)) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = gearItem.isPacked,
                                onCheckedChange = { checked ->
                                    onToggleItem(gearItem.copy(isPacked = checked))
                                },
                                modifier = Modifier.testTag("packing_item_checkbox_${gearItem.id}")
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = gearItem.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                gearItem.weightGrams?.takeIf { it > 0 }?.let { grams ->
                                    Text(
                                        text = stringResource(R.string.packing_item_weight_grams_fmt, grams),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editingItem = gearItem },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("btn_packing_edit_${gearItem.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.packing_list_edit_item),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteItem(gearItem) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("btn_packing_delete_${gearItem.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_item),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showAddDialog) {
        GearItemEditorDialog(
            dialogTitle = stringResource(R.string.packing_list_add_item),
            initialTitle = "",
            initialCategory = stringResource(R.string.packing_category_other),
            initialWeightGrams = null,
            onConfirm = { cat, title, weight ->
                onAddItem(cat, title, weight)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    editingItem?.let { itemToEdit ->
        GearItemEditorDialog(
            dialogTitle = stringResource(R.string.packing_list_edit_item),
            initialTitle = itemToEdit.title,
            initialCategory = itemToEdit.category,
            initialWeightGrams = itemToEdit.weightGrams,
            onConfirm = { cat, title, weight ->
                onEditItem(
                    itemToEdit.copy(
                        category = cat.ifBlank { itemToEdit.category },
                        title = title.ifBlank { itemToEdit.title },
                        weightGrams = weight
                    )
                )
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }

    if (showCopyFromPlanDialog) {
        AlertDialog(
            onDismissRequest = { showCopyFromPlanDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.packing_list_copy_from_previous),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    otherPlans.forEach { otherPlan ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCopyFromPlan(otherPlan.id)
                                    showCopyFromPlanDialog = false
                                }
                                .testTag("copy_gear_from_plan_${otherPlan.id}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = otherPlan.trailName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.packing_copy_plan_subtitle_fmt,
                                        formatDanishKm(otherPlan.totalDistanceKm, 1, locale),
                                        otherPlan.targetDays
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCopyFromPlanDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun GearItemEditorDialog(
    dialogTitle: String,
    initialTitle: String,
    initialCategory: String,
    initialWeightGrams: Int?,
    onConfirm: (category: String, title: String, weightGrams: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var category by remember { mutableStateOf(initialCategory) }
    var weightText by remember { mutableStateOf(initialWeightGrams?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = dialogTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.packing_field_item_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_packing_item_title")
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.packing_field_category)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_packing_item_category")
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.packing_field_weight_grams)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_packing_item_weight")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedTitle = title.trim()
                    if (trimmedTitle.isNotEmpty()) {
                        val weight = weightText.trim().toIntOrNull()?.takeIf { it > 0 }
                        onConfirm(category.trim(), trimmedTitle, weight)
                    }
                },
                enabled = title.trim().isNotEmpty(),
                modifier = Modifier.testTag("btn_confirm_packing_item")
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
