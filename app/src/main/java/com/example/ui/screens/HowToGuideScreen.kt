package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.R

data class HowToGuideSectionData(
    val id: String,
    val chipLabelRes: Int,
    val titleRes: Int,
    val summaryRes: Int,
    val icon: ImageVector,
    val bulletResList: List<Int>
)

object HowToGuideDefinitions {
    const val SECTION_EXPLORE = 0
    const val SECTION_PLANNER = 1
    const val SECTION_SMART_TOOLS = 2
    const val SECTION_OFFLINE_NAVIGATION = 3

    val REAL_EXPLORE_FILTER_RES_IDS: List<Int> = listOf(
        R.string.explore_filter_region,
        R.string.explore_filter_length,
        R.string.explore_filter_multiday,
        R.string.explore_filter_shelters,
        R.string.explore_filter_fri_teltning
    )

    val REAL_PLANNER_ACTION_RES_IDS: List<Int> = listOf(
        R.string.planner_mode_kig,
        R.string.planner_mode_tegn,
        R.string.planner_tool_undo,
        R.string.planner_tool_redo,
        R.string.planner_tool_follow_trails,
        R.string.planner_tool_straight_line,
        R.string.planner_tool_reverse,
        R.string.planner_tool_overnight,
        R.string.planner_tool_save,
        R.string.planner_suggest_overnights,
        R.string.planner_btn_start,
        R.string.planner_btn_settings,
        R.string.planner_btn_rundtur,
        R.string.planner_btn_nearest,
        R.string.trail_detail_action_share,
        R.string.planner_tab_rute,
        R.string.planner_tab_dage,
        R.string.planner_tab_detaljer,
        R.string.planner_replace_overnight,
        R.string.planner_leg_card_show_alternatives
    )

    fun getSections(): List<HowToGuideSectionData> = listOf(
        HowToGuideSectionData(
            id = "explore_map",
            chipLabelRes = R.string.how_to_guide_section_explore_chip,
            titleRes = R.string.how_to_guide_sec1_title,
            summaryRes = R.string.how_to_guide_sec1_summary,
            icon = Icons.Default.Explore,
            bulletResList = listOf(
                R.string.how_to_guide_sec1_step1,
                R.string.how_to_guide_sec1_step2,
                R.string.how_to_guide_sec1_step3
            )
        ),
        HowToGuideSectionData(
            id = "route_planner",
            chipLabelRes = R.string.how_to_guide_section_planner_chip,
            titleRes = R.string.how_to_guide_sec2_title,
            summaryRes = R.string.how_to_guide_sec2_summary,
            icon = Icons.Default.Route,
            bulletResList = listOf(
                R.string.how_to_guide_sec2_step1,
                R.string.how_to_guide_sec2_step2,
                R.string.how_to_guide_sec2_step3
            )
        ),
        HowToGuideSectionData(
            id = "smart_tools",
            chipLabelRes = R.string.how_to_guide_section_smart_chip,
            titleRes = R.string.how_to_guide_sec3_title,
            summaryRes = R.string.how_to_guide_sec3_summary,
            icon = Icons.Default.AutoAwesome,
            bulletResList = listOf(
                R.string.how_to_guide_sec3_step1,
                R.string.how_to_guide_sec3_step2,
                R.string.how_to_guide_sec3_step3
            )
        ),
        HowToGuideSectionData(
            id = "offline_navigation",
            chipLabelRes = R.string.how_to_guide_section_offline_chip,
            titleRes = R.string.how_to_guide_sec4_title,
            summaryRes = R.string.how_to_guide_sec4_summary,
            icon = Icons.Default.Navigation,
            bulletResList = listOf(
                R.string.how_to_guide_sec4_step1,
                R.string.how_to_guide_sec4_step2,
                R.string.how_to_guide_sec4_step3
            )
        )
    )
}

/**
 * Interactive How-To Guide screen & full-screen first-open onboarding overlay.
 * Round 12B Item 1:
 * - First-open guide is a full-screen overlay above everything (bottom bar included).
 * - Only "Forstået" or the close button marks it as seen.
 * - Opened from the planner or navigation, opens at the matching section.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HowToGuideScreen(
    onDismiss: () -> Unit,
    initialSection: Int? = null,
    isFirstLaunchOnboarding: Boolean = false,
    modifier: Modifier = Modifier
) {
    val sections = remember { HowToGuideDefinitions.getSections() }

    var selectedCategoryIndex by remember(initialSection) {
        mutableIntStateOf(if (initialSection != null) initialSection + 1 else 0)
    }
    var expandedSections by remember(initialSection) {
        mutableStateOf(
            if (initialSection != null) setOf(initialSection) else setOf(0, 1, 2, 3)
        )
    }
    val listState = rememberLazyListState()

    Surface(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
            .testTag("how_to_guide_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag(if (isFirstLaunchOnboarding) "how_to_guide_first_open_overlay" else "how_to_guide_full_root")
        ) {
            // Top Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.how_to_guide_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("how_to_guide_title")
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.how_to_guide_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("how_to_guide_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.btn_close),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Topic Filter Chips
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("how_to_guide_chips_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategoryIndex == 0,
                            onClick = {
                                selectedCategoryIndex = 0
                                expandedSections = setOf(0, 1, 2, 3)
                            },
                            label = { Text(stringResource(R.string.how_to_guide_filter_all)) },
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("how_to_chip_all")
                        )
                        sections.forEachIndexed { idx, section ->
                            FilterChip(
                                selected = selectedCategoryIndex == idx + 1,
                                onClick = {
                                    selectedCategoryIndex = idx + 1
                                    expandedSections = expandedSections + idx
                                },
                                label = { Text(stringResource(section.chipLabelRes)) },
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("how_to_chip_$idx")
                            )
                        }
                    }
                }
            }

            // Interactive Accordion Content
            val visibleSections = remember(selectedCategoryIndex, sections) {
                if (selectedCategoryIndex == 0) {
                    sections.mapIndexed { idx, sec -> idx to sec }
                } else {
                    val idx = selectedCategoryIndex - 1
                    listOfNotNull(sections.getOrNull(idx)?.let { idx to it })
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("how_to_guide_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(visibleSections, key = { _, pair -> pair.second.id }) { _, (originalIdx, section) ->
                    val isExpanded = expandedSections.contains(originalIdx)
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(
                            width = if (initialSection == originalIdx) 2.dp else 1.dp,
                            color = if (initialSection == originalIdx) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("how_to_card_$originalIdx")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedSections = if (isExpanded) {
                                        expandedSections - originalIdx
                                    } else {
                                        expandedSections + originalIdx
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = section.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(section.titleRes),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(section.summaryRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = stringResource(R.string.how_to_guide_expand_hint),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    section.bulletResList.forEachIndexed { stepIdx, stepRes ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Text(
                                                    text = "${stepIdx + 1}.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.width(22.dp)
                                                )
                                                Text(
                                                    text = stringResource(stepRes),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.testTag("how_to_step_${originalIdx}_$stepIdx")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("how_to_guide_got_it_button")
                    ) {
                        Text(
                            text = stringResource(R.string.how_to_guide_got_it_btn),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
