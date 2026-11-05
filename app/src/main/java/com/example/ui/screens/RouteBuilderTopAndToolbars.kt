package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.TrailDownloadProgress
import com.example.ui.components.GroupedPlaceSearchDropdown
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LegMode
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.WaypointRole
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteBuilderTopAppBar(
    uiState: RouteBuilderState,
    visibleWaypoints: List<RouteWaypoint>,
    showPlannerMenu: Boolean,
    onSetShowPlannerMenu: (Boolean) -> Unit,
    onOpenRenameDialog: () -> Unit,
    onBack: () -> Unit,
    onOpenTripSettings: () -> Unit,
    onOpenLayersSheet: () -> Unit,
    onLaunchGpxImport: () -> Unit,
    onCloseRouteLoop: () -> Unit,
    onAppendOutAndBack: () -> Unit
) {
    TopAppBar(
        title = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenRenameDialog)
                        .testTag("planner_top_rename_chip")
                ) {
                    Text(
                        text = uiState.routeName.ifBlank {
                            if (uiState.mode == PlannerMode.TRAIL) uiState.baseTrail?.name ?: "Ruteplanlægger" else "Ny rute"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("planner_top_full_route_name")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.ui_routebuilderscreen_omd_b_rute_101),
                        modifier = Modifier
                            .size(15.dp)
                            .testTag("route_title_header")
                    )
                }

                val topTotalDays = maxOf(1, uiState.daySummaries.size)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clickable(onClick = onOpenRenameDialog)
                            .testTag("planner_top_stats_chip")
                    ) {
                        Text(
                            text = String.format(
                                Locale("da", "DK"),
                                "%.1f km · +%.0f m · %d d",
                                uiState.stats.totalKm,
                                uiState.stats.ascentM,
                                topTotalDays
                            ),
                            style = com.example.ui.theme.TabularLabelStyle.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag("route_top_stats_chip")
                        )
                    }
                    Text(
                        text = buildString {
                            append(if (uiState.mode == PlannerMode.TRAIL) "Vandrerute" else "Fri rute")
                            if (uiState.isReverse) append(" · Omvendt")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("btn_back_route_builder")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
            }
        },
        actions = {
            IconButton(
                onClick = onOpenLayersSheet,
                modifier = Modifier.testTag("btn_toggle_access_layer")
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = stringResource(R.string.ui_danskstimap_kortlag_29),
                    tint = if (uiState.isAccessLayerVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            Box {
                IconButton(
                    onClick = { onSetShowPlannerMenu(true) },
                    modifier = Modifier.testTag("btn_planner_menu")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.planner_menu_cd)
                    )
                }
                DropdownMenu(
                    expanded = showPlannerMenu,
                    onDismissRequest = { onSetShowPlannerMenu(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_routebuilderscreen_indstillinger_for_turen_102)) },
                        onClick = {
                            onSetShowPlannerMenu(false)
                            onOpenTripSettings()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Tune, contentDescription = null)
                        },
                        modifier = Modifier.testTag("btn_open_trip_settings")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_routebuilderscreen_importer_gpx_103)) },
                        onClick = {
                            onSetShowPlannerMenu(false)
                            onLaunchGpxImport()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                        },
                        modifier = Modifier.testTag("btn_import_gpx")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.planner_menu_close_loop)) },
                        onClick = {
                            onSetShowPlannerMenu(false)
                            onCloseRouteLoop()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Loop, contentDescription = null)
                        },
                        enabled = visibleWaypoints.size >= 2,
                        modifier = Modifier.testTag("menu_item_luk_ruten")
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.planner_menu_out_and_back)) },
                        onClick = {
                            onSetShowPlannerMenu(false)
                            onAppendOutAndBack()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.SwapVert, contentDescription = null)
                        },
                        enabled = visibleWaypoints.size >= 2,
                        modifier = Modifier.testTag("menu_item_ud_og_hjem")
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.testTag("route_builder_top_bar")
    )
}

@Composable
internal fun RouteBuilderTopSearchAndBannersOverlay(
    uiState: RouteBuilderState,
    activeDownloadProgress: TrailDownloadProgress?,
    context: Context,
    viewModel: RouteBuilderViewModel,
    modifier: Modifier = Modifier
) {
    val danskStiColors = DanskStiTheme.colors

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = uiState.plannerSearchQuery,
                onValueChange = { viewModel.setPlannerSearchQuery(it) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.planner_search_placeholder),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (uiState.plannerSearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearPlannerSearch() },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("planner_clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.btn_close),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("planner_search_input")
            )
        }

        if (uiState.plannerSearchQuery.trim().length >= 2 && !uiState.plannerSearchResults.isEmpty) {
            GroupedPlaceSearchDropdown(
                groupedResults = uiState.plannerSearchResults,
                onSelectRouteOrTrail = { item ->
                    viewModel.clearPlannerSearch()
                    val loc = getLastKnownLocation(context)
                    if (item.isCustomRoute && item.customRouteId != null) {
                        viewModel.initialize(
                            routeId = item.customRouteId,
                            destLat = null,
                            destLng = null,
                            destName = null,
                            gpsLat = loc?.latitude,
                            gpsLng = loc?.longitude
                        )
                    } else {
                        viewModel.initialize(
                            routeId = null,
                            destLat = null,
                            destLng = null,
                            destName = null,
                            gpsLat = loc?.latitude,
                            gpsLng = loc?.longitude,
                            baseTrailId = item.id
                        )
                    }
                },
                onRouteHere = { lat, lng, name ->
                    val loc = getLastKnownLocation(context)
                    viewModel.routeToPlace(
                        destLat = lat,
                        destLng = lng,
                        destName = name,
                        currentLat = loc?.latitude,
                        currentLng = loc?.longitude
                    )
                },
                onAddAsPoint = { lat, lng, name ->
                    viewModel.clearPlannerSearch()
                    if (uiState.mode == PlannerMode.TRAIL) {
                        viewModel.addDetourPoiToRoute(
                            lat = lat,
                            lng = lng,
                            name = name,
                            role = WaypointRole.VIA
                        )
                    } else {
                        viewModel.addPointAtEnd(lat, lng, name)
                    }
                    viewModel.panMapAboveSheetTo(lat, lng, 14.0)
                },
                onShowOnMap = { lat, lng, _ ->
                    viewModel.clearPlannerSearch()
                    viewModel.panMapAboveSheetTo(lat, lng, 14.5)
                }
            )
        }

        if (uiState.isBuiltinFallbackInUse || BRouterOnlineEngine.isBuiltinFallbackInUse()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, danskStiColors.warning),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_builtin_fallback")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = danskStiColors.warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_undg_veje_og_foretr_k_natur__104),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (uiState.waitingForStartPointTap) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_select_start_point")
            ) {
                Text(
                    text = stringResource(R.string.ui_routebuilderscreen_v_lg_startpunkt_tryk_p_korte_105),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (uiState.isRoutingGpxLegs || (uiState.gpxOriginalPoints.size >= 2 && !uiState.isUsingOriginalGpxGeometry)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_gpx_routing_progress")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isRoutingGpxLegs) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = if (uiState.isRoutingGpxLegs) {
                                    "Beregner GPX-etaper (${uiState.gpxLegsCompletedCount}/${uiState.gpxLegsTotalCount})"
                                } else {
                                    "GPX-rute indlæst (${uiState.waypoints.count { it.role == WaypointRole.VIA }} via-punkter)"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.keepOriginalGpxGeometry() },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("btn_keep_gpx_line")
                        ) {
                            Text(
                                text = "Behold GPX-linjen",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (uiState.isRoutingGpxLegs && uiState.gpxLegsTotalCount > 0) {
                        val frac = (uiState.gpxLegsCompletedCount.toFloat() / uiState.gpxLegsTotalCount.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .testTag("gpx_routing_progress_bar"),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }

        if (uiState.isDownloadingTrailGeometry || activeDownloadProgress != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_downloading_trail_geometry")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (activeDownloadProgress != null) {
                                stringResource(
                                    R.string.trail_download_progress_format,
                                    activeDownloadProgress.message,
                                    (activeDownloadProgress.fraction * 100).toInt().coerceIn(0, 100)
                                )
                            } else {
                                stringResource(R.string.ui_routebuilderscreen_henter_rutens_spor_106)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (activeDownloadProgress?.fraction ?: 0.15f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .testTag("planner_trail_download_linear_progress"),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        if (uiState.trailGeometryDownloadFailed) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, danskStiColors.danger),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_trail_download_failed")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_ruten_kunne_ikke_hentes_107),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = danskStiColors.danger,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.retryDownloadTrailGeometry() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .testTag("btn_retry_trail_download")
                    ) {
                        Text(stringResource(R.string.state_Close_or_retry), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        uiState.corridorPoisOfflineNotice?.let { notice ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("banner_corridor_pois_offline")
            ) {
                Text(
                    text = notice,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        uiState.failedLegs.entries.forEach { (legKey, errorMsg) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, danskStiColors.danger),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("failed_leg_chip_$legKey")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = errorMsg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = danskStiColors.danger,
                        modifier = Modifier.testTag("failed_leg_message_$legKey")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.retryFailedLeg(legKey) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .testTag("btn_retry_leg_$legKey")
                        ) {
                            Text(stringResource(R.string.state_Close_or_retry), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.setFailedLegStraight(legKey) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .testTag("btn_straight_leg_$legKey")
                        ) {
                            Text(stringResource(R.string.ui_routebuilderscreen_tegn_lige_linje_108), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.activateMovePointForFailedLeg(legKey) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .testTag("btn_move_point_$legKey")
                        ) {
                            Text(stringResource(R.string.ui_routebuilderscreen_flyt_punkt_109), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

