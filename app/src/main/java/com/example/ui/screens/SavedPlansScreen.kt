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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.TripPlanEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.OfflineStatusPill
import com.example.ui.theme.CardShape
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.SavedViewModel
import com.example.util.routing.BRouterSegments
import kotlinx.coroutines.launch

enum class LibrarySegment(val titleRes: Int, val tag: String) {
    PLANER(R.string.library_tab_plans, "library_tab_planer"),
    RUTER(R.string.library_tab_routes, "library_tab_ruter"),
    PUNKTER(R.string.library_tab_points, "library_tab_punkter"),
    OPTAGELSER(R.string.library_tab_recordings, "library_tab_optagelser"),
    OFFLINE(R.string.library_tab_offline, "library_tab_offline")
}

/**
 * Mine ture (library):
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
    val completedPlanIds by viewModel.completedPlanIds.collectAsState()
    val savedTrails by viewModel.savedTrails.collectAsState()
    val customRoutes by viewModel.customRoutes.collectAsState()
    val customWaypoints by viewModel.customWaypoints.collectAsState()
    val recordedDays by viewModel.recordedDays.collectAsState()
    val downloadedTrails by viewModel.downloadedTrails.collectAsState()
    val gearItemsByPlanId by viewModel.gearItemsByPlanId.collectAsState()
    val diaryEntriesByKey by viewModel.diaryEntriesByKey.collectAsState()
    val routeGeometryHashes by viewModel.routeGeometryHashes.collectAsState()
    val brouterState by viewModel.brouterState.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgressText by viewModel.downloadProgressText.collectAsState()

    var activePackingListPlan by remember { mutableStateOf<TripPlanEntity?>(null) }
    var activeDiaryTarget by remember { mutableStateOf<com.example.data.model.RecordedDayStatEntity?>(null) }
    var pendingOfflineDownloadRoute by remember { mutableStateOf<com.example.data.model.CustomRouteEntity?>(null) }
    var pendingOfflineDownloadPlan by remember { mutableStateOf<TripPlanEntity?>(null) }
    var pendingCascadeDeleteRoute by remember { mutableStateOf<com.example.data.model.CustomRouteEntity?>(null) }
    var pendingCascadeDeletePlan by remember { mutableStateOf<TripPlanEntity?>(null) }

    var selectedSegmentIndex by rememberSaveable { mutableIntStateOf(initialSegmentIndex.coerceIn(0, 4)) }
    val segments = LibrarySegment.values()
    var isSegmentsExpanded by remember { mutableStateOf(false) }

    val deletedMsg = stringResource(R.string.library_deleted_snackbar)
    val undoLabel = stringResource(R.string.library_undo_action)

    var activeSnackbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                activeSnackbarJob?.cancel()
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.commitPendingDelete()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeSnackbarJob?.cancel()
            viewModel.commitPendingDelete()
        }
    }

    fun showUndoSnackbar(itemName: String) {
        activeSnackbarJob?.cancel()
        activeSnackbarJob = coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "$deletedMsg: $itemName",
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoLastDelete()
            } else if (result == SnackbarResult.Dismissed) {
                viewModel.commitPendingDelete()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag("library_snackbar_host")
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNewCustomRoute,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_new_custom_route")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ui_savedplansscreen_opret_ny_rute_171))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.map_tool_plan_route),
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
                .consumeWindowInsets(innerPadding)
        ) {
            // Header
            val isDeviceOnline = com.example.ui.components.rememberIsOnline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (!isDeviceOnline) {
                    OfflineStatusPill()
                }
            }

            // Segmented bar: Planer, Ruter, Punkter, Optagelser, Offline
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
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
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
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            if (countBadge > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = countBadge.toString(),
                                        style = TabularLabelStyle,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                val hasOfflinePlan = downloadedTrails.any { it.trailId == "plan:${plan.id}" }
                                SwipeToDeleteWrapper(
                                    itemKey = "plan_${plan.id}",
                                    onDismissed = {
                                        if (hasOfflinePlan) {
                                            pendingCascadeDeletePlan = plan
                                        } else {
                                            viewModel.deletePlanWithUndo(plan)
                                            showUndoSnackbar(plan.trailName)
                                        }
                                    }
                                ) {
                                    TripPlanLibraryCard(
                                        plan = plan,
                                        isCompleted = plan.id in completedPlanIds,
                                        gearItems = gearItemsByPlanId[plan.id].orEmpty(),
                                        isDownloadedOffline = hasOfflinePlan,
                                        onOpenInPlanner = { onEditPlan(plan) },
                                        onStartNavigation = { onStartNavigation(plan.id) },
                                        onOpenPackingList = {
                                            viewModel.ensureDefaultGearItemsForPlan(plan)
                                            activePackingListPlan = plan
                                        },
                                        onDownloadOffline = { pendingOfflineDownloadPlan = plan },
                                        onExportGpx = { viewModel.exportTripPlanGpx(context, plan) },
                                        onShare = { viewModel.shareTripPlan(context, plan) },
                                        onDelete = {
                                            if (hasOfflinePlan) {
                                                pendingCascadeDeletePlan = plan
                                            } else {
                                                viewModel.deletePlanWithUndo(plan)
                                                showUndoSnackbar(plan.trailName)
                                            }
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
                                val offlineItem = downloadedTrails.find { it.trailId == "route:${route.id}" }
                                val currentHash = routeGeometryHashes[route.id]
                                val isOfflineOutdated = offlineItem?.geometryHash != null &&
                                    currentHash != null &&
                                    offlineItem.geometryHash != currentHash
                                SwipeToDeleteWrapper(
                                    itemKey = "custom_${route.id}",
                                    onDismissed = {
                                        if (offlineItem != null) {
                                            pendingCascadeDeleteRoute = route
                                        } else {
                                            viewModel.deleteCustomRouteWithUndo(route)
                                            showUndoSnackbar(route.name)
                                        }
                                    }
                                ) {
                                    CustomRouteLibraryCard(
                                        route = route,
                                        isDownloadedOffline = offlineItem != null,
                                        isOfflineOutdated = isOfflineOutdated,
                                        onEdit = { onEditCustomRoute(route.id) },
                                        onDownloadOffline = { pendingOfflineDownloadRoute = route },
                                        onDelete = {
                                            if (offlineItem != null) {
                                                pendingCascadeDeleteRoute = route
                                            } else {
                                                viewModel.deleteCustomRouteWithUndo(route)
                                                showUndoSnackbar(route.name)
                                            }
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
                                        },
                                        onDeletePhoto = {
                                            viewModel.deleteWaypointPhotoWithUndo(wp)
                                            showUndoSnackbar("Foto (${wp.name})")
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
                                        diaryEntry = diaryEntriesByKey["${rec.planId}_${rec.dayNumber}"],
                                        onClick = { onStartNavigation(rec.planId) },
                                        onOpenDiary = { activeDiaryTarget = rec },
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
                                            tint = MaterialTheme.colorScheme.primary
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
                            BRouterOfflineDataCard(
                                brouterState = brouterState,
                                isSegmentsExpanded = isSegmentsExpanded,
                                onToggleSegmentsExpanded = { isSegmentsExpanded = !isSegmentsExpanded },
                                viewModel = viewModel
                            )
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
                                val customRouteId = item.trailId.removePrefix("route:").toLongOrNull()
                                val currentHash = if (customRouteId != null) routeGeometryHashes[customRouteId] else null
                                val isOutdated = item.geometryHash != null &&
                                    currentHash != null &&
                                    item.geometryHash != currentHash
                                SwipeToDeleteWrapper(
                                    itemKey = "offline_${item.trailId}",
                                    onDismissed = {
                                        viewModel.deleteDownloadedTrailWithUndo(item)
                                        showUndoSnackbar(item.trailName)
                                    }
                                ) {
                                    OfflineTrailLibraryCard(
                                        item = item,
                                        isOutdated = isOutdated,
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

    SavedPlansModalsAndDialogs(
        activePackingListPlan = activePackingListPlan,
        onDismissPackingList = { activePackingListPlan = null },
        savedPlans = savedPlans,
        gearItemsByPlanId = gearItemsByPlanId,
        activeDiaryTarget = activeDiaryTarget,
        onDismissDiary = { activeDiaryTarget = null },
        diaryEntriesByKey = diaryEntriesByKey,
        pendingOfflineDownloadRoute = pendingOfflineDownloadRoute,
        pendingOfflineDownloadPlan = pendingOfflineDownloadPlan,
        onClearPendingOfflineDownload = {
            pendingOfflineDownloadRoute = null
            pendingOfflineDownloadPlan = null
        },
        onSwitchToOfflineSegment = { selectedSegmentIndex = LibrarySegment.OFFLINE.ordinal },
        pendingCascadeDeleteRoute = pendingCascadeDeleteRoute,
        pendingCascadeDeletePlan = pendingCascadeDeletePlan,
        onClearPendingCascadeDelete = {
            pendingCascadeDeleteRoute = null
            pendingCascadeDeletePlan = null
        },
        showUndoSnackbar = { showUndoSnackbar(it) },
        viewModel = viewModel
    )
}
