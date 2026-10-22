package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.CustomRouteEntity
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.formatDanishElevationMeters
import com.example.ui.components.formatDanishKm
import com.example.ui.theme.AlertRed
import com.example.ui.theme.CardShape
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.SavedViewModel
import com.example.util.routing.BRouterSegments
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LibrarySegment(val titleRes: Int, val tag: String) {
    PLANER(R.string.library_tab_plans, "library_tab_planer"),
    RUTER(R.string.library_tab_routes, "library_tab_ruter"),
    PUNKTER(R.string.library_tab_points, "library_tab_punkter"),
    OPTAGELSER(R.string.library_tab_recordings, "library_tab_optagelser"),
    OFFLINE(R.string.library_tab_offline, "library_tab_offline")
}

/**
 * Requirement H: Mine ture (library)
 * - Segmented: Planer, Ruter, Punkter, Optagelser, Offline.
 * - Each item opens in the planner or on the map.
 * - Swipe to delete with "Fortryd" snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlansScreen(
    onTrailClick: (String) -> Unit,
    onStartNavigation: (Long) -> Unit,
    onCreateNewCustomRoute: () -> Unit = {},
    onEditCustomRoute: (Long) -> Unit = {},
    onEditPlan: (TripPlanEntity) -> Unit = {},
    onOpenPointOnMap: (lat: Double, lng: Double, label: String) -> Unit = { _, _, _ -> },
    onExploreTrails: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    initialSegmentIndex: Int = 0,
    modifier: Modifier = Modifier,
    viewModel: SavedViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedPlans by viewModel.savedPlans.collectAsState()
    val savedTrails by viewModel.savedTrails.collectAsState()
    val customRoutes by viewModel.customRoutes.collectAsState()
    val customWaypoints by viewModel.customWaypoints.collectAsState()
    val recordedDays by viewModel.recordedDays.collectAsState()
    val downloadedTrails by viewModel.downloadedTrails.collectAsState()
    val brouterState by viewModel.brouterState.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgressText by viewModel.downloadProgressText.collectAsState()

    var selectedSegmentIndex by rememberSaveable { mutableIntStateOf(initialSegmentIndex.coerceIn(0, 4)) }
    val segments = LibrarySegment.values()
    var isSegmentsExpanded by remember { mutableStateOf(false) }

    val deletedMsg = stringResource(R.string.library_deleted_snackbar)
    val undoLabel = stringResource(R.string.library_undo_action)

    fun showUndoSnackbar(itemName: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "$deletedMsg: $itemName",
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoLastDelete()
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag("library_snackbar_host")
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNewCustomRoute,
                containerColor = ForestGreenPrimary,
                contentColor = Color.White,
                modifier = Modifier.testTag("fab_new_custom_route")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Opret ny rute")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Planlæg rute",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Segmented bar: Planer, Ruter, Punkter, Optagelser, Offline (Requirement H)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                segments.forEachIndexed { index, seg ->
                    val isSelected = selectedSegmentIndex == index
                    val countBadge = when (seg) {
                        LibrarySegment.PLANER -> savedPlans.size
                        LibrarySegment.RUTER -> customRoutes.size + savedTrails.size
                        LibrarySegment.PUNKTER -> customWaypoints.size
                        LibrarySegment.OPTAGELSER -> recordedDays.size
                        LibrarySegment.OFFLINE -> downloadedTrails.size
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .sizeIn(minHeight = 48.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { selectedSegmentIndex = index }
                            .testTag(seg.tag)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(seg.titleRes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            if (countBadge > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = countBadge.toString(),
                                        style = TabularLabelStyle,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s2))

            // Segment Content
            when (segments[selectedSegmentIndex]) {
                LibrarySegment.PLANER -> {
                    if (savedPlans.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.CalendarMonth,
                            sentence = stringResource(R.string.library_empty_plans),
                            actionLabel = stringResource(R.string.library_empty_plans_action),
                            onAction = onCreateNewCustomRoute,
                            actionTestTag = "empty_plans_action_button",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_plans_list"),
                            contentPadding = PaddingValues(
                                start = Spacing.s4,
                                end = Spacing.s4,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            items(savedPlans, key = { "plan_${it.id}" }) { plan ->
                                SwipeToDeleteWrapper(
                                    itemKey = "plan_${plan.id}",
                                    onDismissed = {
                                        viewModel.deletePlanWithUndo(plan)
                                        showUndoSnackbar(plan.trailName)
                                    }
                                ) {
                                    TripPlanLibraryCard(
                                        plan = plan,
                                        onOpenInPlanner = { onEditPlan(plan) },
                                        onStartNavigation = { onStartNavigation(plan.id) },
                                        onExportGpx = { viewModel.exportTripPlanGpx(context, plan) },
                                        onShare = { viewModel.shareTripPlan(context, plan) },
                                        onDelete = {
                                            viewModel.deletePlanWithUndo(plan)
                                            showUndoSnackbar(plan.trailName)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                LibrarySegment.RUTER -> {
                    if (customRoutes.isEmpty() && savedTrails.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.Route,
                            sentence = stringResource(R.string.library_empty_routes),
                            actionLabel = stringResource(R.string.library_empty_routes_action),
                            onAction = onExploreTrails,
                            actionTestTag = "empty_routes_action_button",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_routes_list"),
                            contentPadding = PaddingValues(
                                start = Spacing.s4,
                                end = Spacing.s4,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            items(customRoutes, key = { "custom_${it.id}" }) { route ->
                                SwipeToDeleteWrapper(
                                    itemKey = "custom_${route.id}",
                                    onDismissed = {
                                        viewModel.deleteCustomRouteWithUndo(route)
                                        showUndoSnackbar(route.name)
                                    }
                                ) {
                                    CustomRouteLibraryCard(
                                        route = route,
                                        onEdit = { onEditCustomRoute(route.id) },
                                        onDelete = {
                                            viewModel.deleteCustomRouteWithUndo(route)
                                            showUndoSnackbar(route.name)
                                        }
                                    )
                                }
                            }

                            items(savedTrails, key = { "saved_trail_${it.id}" }) { trail ->
                                SwipeToDeleteWrapper(
                                    itemKey = "saved_trail_${trail.id}",
                                    onDismissed = {
                                        viewModel.unsaveTrailWithUndo(trail.id)
                                        showUndoSnackbar(trail.name)
                                    }
                                ) {
                                    SavedTrailLibraryCard(
                                        trail = trail,
                                        onClick = { onTrailClick(trail.id) },
                                        onRemove = {
                                            viewModel.unsaveTrailWithUndo(trail.id)
                                            showUndoSnackbar(trail.name)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                LibrarySegment.PUNKTER -> {
                    if (customWaypoints.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.Place,
                            sentence = stringResource(R.string.library_empty_points),
                            actionLabel = stringResource(R.string.library_empty_points_action),
                            onAction = onOpenMap,
                            actionTestTag = "empty_points_action_button",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_points_list"),
                            contentPadding = PaddingValues(
                                start = Spacing.s4,
                                end = Spacing.s4,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            items(customWaypoints, key = { "wp_${it.id}" }) { wp ->
                                SwipeToDeleteWrapper(
                                    itemKey = "wp_${wp.id}",
                                    onDismissed = {
                                        viewModel.deleteWaypointWithUndo(wp)
                                        showUndoSnackbar(wp.name)
                                    }
                                ) {
                                    WaypointLibraryCard(
                                        waypoint = wp,
                                        onClick = { onOpenPointOnMap(wp.lat, wp.lng, wp.name) },
                                        onDelete = {
                                            viewModel.deleteWaypointWithUndo(wp)
                                            showUndoSnackbar(wp.name)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                LibrarySegment.OPTAGELSER -> {
                    if (recordedDays.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.FiberManualRecord,
                            sentence = stringResource(R.string.library_empty_recordings),
                            actionLabel = stringResource(R.string.library_empty_recordings_action),
                            onAction = onCreateNewCustomRoute,
                            actionTestTag = "empty_recordings_action_button",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("library_recordings_list"),
                            contentPadding = PaddingValues(
                                start = Spacing.s4,
                                end = Spacing.s4,
                                bottom = 96.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            items(recordedDays, key = { "rec_${it.planId}_${it.dayNumber}" }) { rec ->
                                SwipeToDeleteWrapper(
                                    itemKey = "rec_${rec.planId}_${rec.dayNumber}",
                                    onDismissed = {
                                        viewModel.deleteRecordingWithUndo(rec)
                                        showUndoSnackbar("Dag ${rec.dayNumber}")
                                    }
                                ) {
                                    RecordingLibraryCard(
                                        recording = rec,
                                        onClick = { onStartNavigation(rec.planId) },
                                        onDelete = {
                                            viewModel.deleteRecordingWithUndo(rec)
                                            showUndoSnackbar("Dag ${rec.dayNumber}")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                LibrarySegment.OFFLINE -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("library_offline_list"),
                        contentPadding = PaddingValues(
                            start = Spacing.s4,
                            end = Spacing.s4,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                    ) {
                        if (isDownloading) {
                            item(key = "trail_downloading_banner") {
                                Card(
                                    shape = CardShape,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Spacing.s4),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = ForestGreenPrimary
                                        )
                                        Spacer(modifier = Modifier.width(Spacing.s3))
                                        Text(
                                            text = downloadProgressText.ifBlank { "Henter offline data..." },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        // BRouter Denmark offline routing data card
                        item(key = "brouter_denmark_card") {
                            Card(
                                shape = CardShape,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(
                                    1.dp,
                                    if (brouterState.isAllDownloaded) ForestGreenPrimary.copy(alpha = 0.5f)
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
                                            color = ForestGreenPrimary.copy(alpha = 0.14f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (brouterState.isAllDownloaded) Icons.Default.CheckCircle else Icons.Default.Route,
                                                    contentDescription = null,
                                                    tint = ForestGreenPrimary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(Spacing.s3))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Rutedata for Danmark",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (brouterState.isAllDownloaded) {
                                                    val date = brouterState.lastUpdatedDate ?: "Opdateret"
                                                    "Klar til offline ruteplanlægning • $date"
                                                } else {
                                                    "BRouter routing data (5 segmenter, ${BRouterSegments.TOTAL_SIZE_MB} MB)"
                                                },
                                                style = TabularLabelStyle,
                                                color = if (brouterState.isAllDownloaded) ForestGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
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
                                                    text = "${brouterState.progressPercent} %",
                                                    style = TabularLabelStyle,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ForestGreenPrimary
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { brouterState.progressPercent / 100f },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp),
                                                color = ForestGreenPrimary,
                                                trackColor = ForestGreenLight.copy(alpha = 0.3f)
                                            )
                                            OutlinedButton(
                                                onClick = { viewModel.cancelBRouterDownload() },
                                                modifier = Modifier
                                                    .align(Alignment.End)
                                                    .testTag("btn_brouter_cancel")
                                            ) {
                                                Text("Annuller")
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { isSegmentsExpanded = !isSegmentsExpanded }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (isSegmentsExpanded) "Skjul dækning og filstørrelser" else "Vis dækning og filstørrelser (5 filer)",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ForestGreenPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Icon(
                                                imageVector = if (isSegmentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = ForestGreenPrimary
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
                                                            text = "${seg.filename} (${seg.name})",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "${seg.sizeMb} MB",
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
                                                Icon(
                                                    imageVector = Icons.Default.Wifi,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Hent kun via Wi-Fi",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                            Switch(
                                                checked = brouterState.wifiOnly,
                                                onCheckedChange = { viewModel.setBRouterWifiOnly(it) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = ForestGreenPrimary),
                                                modifier = Modifier.testTag("switch_brouter_wifi_only")
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(Spacing.s2))

                                        if (brouterState.isAllDownloaded) {
                                            Button(
                                                onClick = { viewModel.updateBRouterData() },
                                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .sizeIn(minHeight = 48.dp)
                                                    .testTag("btn_brouter_update")
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Opdater rutedata")
                                            }
                                        } else {
                                            Button(
                                                onClick = { viewModel.downloadBRouterData() },
                                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .sizeIn(minHeight = 48.dp)
                                                    .testTag("btn_brouter_download")
                                            ) {
                                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Hent rutedata (${BRouterSegments.TOTAL_SIZE_MB} MB)")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (downloadedTrails.isEmpty()) {
                            item(key = "offline_empty_state") {
                                EmptyStateView(
                                    icon = Icons.Default.CloudDone,
                                    sentence = stringResource(R.string.library_empty_offline),
                                    actionLabel = stringResource(R.string.library_empty_offline_action),
                                    onAction = onExploreTrails,
                                    actionTestTag = "empty_offline_action_button"
                                )
                            }
                        } else {
                            items(downloadedTrails, key = { "offline_${it.trailId}" }) { item ->
                                SwipeToDeleteWrapper(
                                    itemKey = "offline_${item.trailId}",
                                    onDismissed = {
                                        viewModel.deleteDownloadedTrailWithUndo(item)
                                        showUndoSnackbar(item.trailName)
                                    }
                                ) {
                                    OfflineTrailLibraryCard(
                                        item = item,
                                        onClick = { onTrailClick(item.trailId) },
                                        onDelete = {
                                            viewModel.deleteDownloadedTrailWithUndo(item)
                                            showUndoSnackbar(item.trailName)
                                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteWrapper(
    itemKey: String,
    onDismissed: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                onDismissed()
                true
            } else {
                false
            }
        }
    )

    LaunchedEffect(itemKey) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardShape)
                    .background(AlertRed.copy(alpha = 0.85f))
                    .padding(horizontal = Spacing.s5),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_item),
                    tint = Color.White
                )
            }
        },
        content = { content() }
    )
}

@Composable
private fun TripPlanLibraryCard(
    plan: TripPlanEntity,
    onOpenInPlanner: () -> Unit,
    onStartNavigation: () -> Unit,
    onExportGpx: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("d. MMM yyyy", Locale("da", "DK")).format(Date(plan.createdAt))

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenInPlanner() }
            .testTag("plan_card_${plan.id}")
    ) {
        Column(modifier = Modifier.padding(Spacing.s4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.trailName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Oprettet $dateStr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("share_plan_${plan.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Del turplan",
                            tint = ForestGreenPrimary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_plan_${plan.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.cd_delete_item),
                            tint = AlertRed
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s2))

            Text(
                text = "${formatDanishKm(plan.totalDistanceKm, 1)} km · ${plan.targetDays} ${if (plan.targetDays == 1) "dag" else "dage"} · ${formatDanishKm(plan.paceKmh, 1)} km/t",
                style = TabularBodyStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = ForestGreenPrimary
            )

            Spacer(modifier = Modifier.height(Spacing.s3))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
            ) {
                OutlinedButton(
                    onClick = onOpenInPlanner,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .testTag("edit_plan_${plan.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Åbn i planlægger", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onStartNavigation,
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .testTag("start_plan_${plan.id}")
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CustomRouteLibraryCard(
    route: CustomRouteEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .testTag("custom_route_card_${route.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ForestGreenPrimary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatDanishKm(route.lengthKm, 1)} km · +${formatDanishElevationMeters(route.ascentM.toInt())} m",
                    style = TabularLabelStyle,
                    color = ForestGreenPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_custom_route_${route.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rediger rute",
                    tint = ForestGreenPrimary
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_custom_route_${route.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_item),
                    tint = AlertRed
                )
            }
        }
    }
}

@Composable
private fun SavedTrailLibraryCard(
    trail: TrailEntity,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("saved_trail_card_${trail.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ForestGreenPrimary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trail.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${trail.region} · ${formatDanishKm(trail.lengthKm, 1)} km",
                    style = TabularLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("remove_saved_${trail.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_item),
                    tint = AlertRed
                )
            }
        }
    }
}

@Composable
private fun WaypointLibraryCard(
    waypoint: UserCustomWaypointEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("waypoint_card_${waypoint.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ForestGreenPrimary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", waypoint.lat, waypoint.lng),
                    style = TabularLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_waypoint_${waypoint.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_item),
                    tint = AlertRed
                )
            }
        }
    }
}

@Composable
private fun RecordingLibraryCard(
    recording: RecordedDayStatEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("d. MMM yyyy", Locale("da", "DK")).format(Date(recording.lastUpdatedMillis))
    val hours = recording.movingTimeSeconds / 3600
    val mins = (recording.movingTimeSeconds % 3600) / 60

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recording_card_${recording.planId}_${recording.dayNumber}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ForestGreenPrimary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Optagelse · Dag ${recording.dayNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatDanishKm(recording.distanceKm, 1)} km · +${recording.ascentMeters} m · ${hours}t ${mins}m · $dateStr",
                    style = TabularLabelStyle,
                    color = ForestGreenPrimary,
                    fontWeight = FontWeight.SemiBold
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
                    tint = AlertRed
                )
            }
        }
    }
}

@Composable
private fun OfflineTrailLibraryCard(
    item: DownloadedTrailEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val itemMb = String.format(Locale("da", "DK"), "%.1f", item.sizeBytes / (1024.0 * 1024.0))
    val dateStr = SimpleDateFormat("d. MMM yyyy", Locale("da", "DK")).format(Date(item.downloadedAt))

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("offline_trail_item_${item.trailId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ForestGreenPrimary.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = ForestGreenPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.trailName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$itemMb MB · ${item.tileCount} fliser · Hentet $dateStr",
                    style = TabularLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    tint = AlertRed
                )
            }
        }
    }
}
