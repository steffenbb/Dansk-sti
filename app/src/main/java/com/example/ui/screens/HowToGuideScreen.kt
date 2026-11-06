package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CardShape
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing

private data class HowToSectionData(
    val index: Int,
    val chipLabel: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val steps: List<String>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HowToGuideScreen(
    onDismiss: () -> Unit,
    initialSection: Int? = null,
    isFirstLaunchOnboarding: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onDismiss()
    }

    var selectedFilterIndex by rememberSaveable(initialSection) {
        mutableStateOf(initialSection)
    }
    var expandedCardIndices by rememberSaveable(initialSection) {
        mutableStateOf(
            if (initialSection != null) setOf(initialSection, 1, 2) else setOf(0, 1, 2, 3)
        )
    }

    val sections = listOf(
        HowToSectionData(
            index = 0,
            chipLabel = stringResource(R.string.how_to_guide_section_explore_chip),
            title = stringResource(R.string.how_to_guide_sec1_title),
            summary = stringResource(R.string.how_to_guide_sec1_summary),
            icon = Icons.Default.Explore,
            steps = listOf(
                stringResource(R.string.how_to_guide_sec1_step1),
                stringResource(R.string.how_to_guide_sec1_step2),
                stringResource(R.string.how_to_guide_sec1_step3)
            )
        ),
        HowToSectionData(
            index = 1,
            chipLabel = stringResource(R.string.how_to_guide_section_planner_chip),
            title = stringResource(R.string.how_to_guide_sec2_title),
            summary = stringResource(R.string.how_to_guide_sec2_summary),
            icon = Icons.Default.Route,
            steps = listOf(
                stringResource(R.string.how_to_guide_sec2_step1),
                stringResource(R.string.how_to_guide_sec2_step2),
                stringResource(R.string.how_to_guide_sec2_step3)
            )
        ),
        HowToSectionData(
            index = 2,
            chipLabel = stringResource(R.string.how_to_guide_section_smart_chip),
            title = stringResource(R.string.how_to_guide_sec3_title),
            summary = stringResource(R.string.how_to_guide_sec3_summary),
            icon = Icons.Default.AutoAwesome,
            steps = listOf(
                stringResource(R.string.how_to_guide_sec3_step1),
                stringResource(R.string.how_to_guide_sec3_step2),
                stringResource(R.string.how_to_guide_sec3_step3)
            )
        ),
        HowToSectionData(
            index = 3,
            chipLabel = stringResource(R.string.how_to_guide_section_offline_chip),
            title = stringResource(R.string.how_to_guide_sec4_title),
            summary = stringResource(R.string.how_to_guide_sec4_summary),
            icon = Icons.Default.Navigation,
            steps = listOf(
                stringResource(R.string.how_to_guide_sec4_step1),
                stringResource(R.string.how_to_guide_sec4_step2),
                stringResource(R.string.how_to_guide_sec4_step3)
            )
        )
    )

    val visibleSections = if (selectedFilterIndex == null) {
        sections
    } else {
        sections.filter { it.index == selectedFilterIndex }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = (if (isFirstLaunchOnboarding) modifier.fillMaxWidth().height(260.dp) else modifier.fillMaxSize())
            .testTag("how_to_guide_screen"),
        topBar = {
            if (isFirstLaunchOnboarding) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s3, vertical = Spacing.s1),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.how_to_guide_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("how_to_guide_header_title")
                            )
                            Text(
                                text = stringResource(R.string.how_to_guide_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s1)
                        ) {
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = MinTouchTargetSize)
                                    .testTag("how_to_guide_got_it_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.how_to_guide_got_it_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(MinTouchTargetSize)
                                    .testTag("how_to_guide_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.btn_close),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.how_to_guide_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("how_to_guide_header_title")
                            )
                            Text(
                                text = stringResource(R.string.how_to_guide_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(MinTouchTargetSize)
                                .testTag("how_to_guide_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_close)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (!isFirstLaunchOnboarding) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
                    ) {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MinTouchTargetSize)
                                .testTag("how_to_guide_got_it_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.s2))
                            Text(
                                text = stringResource(R.string.how_to_guide_got_it_btn),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .testTag("how_to_guide_list"),
            contentPadding = PaddingValues(if (isFirstLaunchOnboarding) Spacing.s2 else Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(if (isFirstLaunchOnboarding) Spacing.s2 else Spacing.s3)
        ) {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                ) {
                    FilterChip(
                        selected = selectedFilterIndex == null,
                        onClick = {
                            selectedFilterIndex = null
                            expandedCardIndices = setOf(0, 1, 2, 3)
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.how_to_guide_filter_all),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedFilterIndex == null) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .heightIn(min = MinTouchTargetSize)
                            .testTag("how_to_chip_all")
                    )

                    sections.forEach { sec ->
                        val isSelected = selectedFilterIndex == sec.index
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedFilterIndex = if (isSelected) null else sec.index
                                expandedCardIndices = expandedCardIndices + sec.index
                            },
                            label = {
                                Text(
                                    text = sec.chipLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .heightIn(min = MinTouchTargetSize)
                                .testTag("how_to_chip_${sec.index}")
                        )
                    }
                }
            }

            items(visibleSections.size, key = { visibleSections[it].index }) { itemIdx ->
                val sec = visibleSections[itemIdx]
                val isExpanded = sec.index in expandedCardIndices

                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = if (isExpanded) 1.5.dp else 1.dp,
                        color = if (isExpanded) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedCardIndices = if (isExpanded) {
                                expandedCardIndices - sec.index
                            } else {
                                expandedCardIndices + sec.index
                            }
                        }
                        .testTag("how_to_card_${sec.index}")
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.s4),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = sec.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(Spacing.s3))
                                Column {
                                    Text(
                                        text = sec.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = sec.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = stringResource(R.string.how_to_guide_expand_hint),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                                modifier = Modifier
                                    .padding(top = Spacing.s2)
                                    .testTag("how_to_card_steps_${sec.index}")
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                sec.steps.forEachIndexed { stepIdx, stepText ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 3.dp)
                                                .size(22.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${stepIdx + 1}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(Spacing.s2))
                                        Text(
                                            text = stepText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
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
}
