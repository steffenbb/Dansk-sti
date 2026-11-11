package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DiscoveredHikingRouteDto
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailEntity
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.theme.CardShape
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.Spacing
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.TrailCardMetrics
import com.example.util.routing.RouteMarkerHelper
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

private val TRAIL_LINE_COLORS = listOf(
    0xFF2E6B3F.toInt(),
    0xFF1565C0.toInt(),
    0xFFE65100.toInt(),
    0xFF6A1B9A.toInt(),
    0xFF00838F.toInt(),
    0xFFAD1457.toInt()
)

@Composable
internal fun TrailBrowserListContent(
    trails: List<TrailEntity>,
    savedTrails: List<TrailEntity>,
    savedTrailIds: Set<String>,
    nearbyTrails: List<TrailEntity>,
    userLocation: Pair<Double, Double>?,
    userLocationStaleMessage: String? = null,
    onPickAnotherStart: () -> Unit = {},
    shelterCounts: Map<String, Int>,
    friTeltningCounts: Map<String, Int>,
    trailMetrics: Map<String, TrailCardMetrics>,
    allGeometries: Map<String, List<GeoPointDto>>,
    downloadedTrailMap: Map<String, DownloadedTrailEntity>,
    downloadProgress: Map<String, TrailDownloadProgress>,
    selectedTrailForPeek: TrailEntity?,
    moreMarkedRoutes: List<DiscoveredHikingRouteDto>,
    isLoadingMoreMarkedRoutes: Boolean,
    downloadingDiscoveredRelationId: Long?,
    onRequestLocation: () -> Unit,
    onToggleBookmark: (String) -> Unit,
    onTrailClick: (String) -> Unit,
    onDownloadDiscoveredRoute: (DiscoveredHikingRouteDto) -> Unit
) {
    val windowSizeClass = DanskStiTheme.windowSizeClass
    val gridColumns = windowSizeClass.udforskColumns
    if (gridColumns >= 2) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("trail_grid_columns_$gridColumns")
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trail_list"),
                contentPadding = PaddingValues(
                    start = Spacing.s4,
                    end = Spacing.s4,
                    top = Spacing.s2,
                    bottom = 96.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
                verticalArrangement = Arrangement.spacedBy(Spacing.s4)
            ) {
                item(key = "section_near_you_header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
                        Text(
                            text = stringResource(R.string.explore_section_near_you),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .padding(top = Spacing.s1)
                                .testTag("section_near_you")
                        )
                        userLocationStaleMessage?.let { staleMsg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = staleMsg,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("explore_stale_location_message")
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = onPickAnotherStart,
                                    modifier = Modifier.testTag("explore_pick_another_start_button")
                                ) {
                                    Text("Vælg andet startpunkt")
                                }
                            }
                        }
                    }
                }

                if (userLocation == null) {
                    item(key = "section_near_you_permission", span = { GridItemSpan(maxLineSpan) }) {
                        NearYouPermissionCard(onRequestLocation = onRequestLocation)
                    }
                } else {
                    gridItems(
                        items = nearbyTrails.take(gridColumns),
                        key = { "near_${it.id}" }
                    ) { trail ->
                        val metrics = trailMetrics[trail.id]
                        val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                        val isSaved = savedTrailIds.contains(trail.id)
                        TrailCard(
                            trail = trail,
                            points = points,
                            sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                            friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                            ascentMeters = metrics?.ascentMeters,
                            downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                            isSaved = isSaved,
                            isSelected = selectedTrailForPeek?.id == trail.id,
                            onBookmarkClick = { onToggleBookmark(trail.id) },
                            onClick = { onTrailClick(trail.id) },
                            testTagOverride = "near_trail_card_${trail.id}"
                        )
                    }
                }

                item(key = "section_saved_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.explore_section_saved),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(top = Spacing.s2)
                            .testTag("section_saved")
                    )
                }

                if (savedTrails.isEmpty()) {
                    item(key = "section_saved_empty", span = { GridItemSpan(maxLineSpan) }) {
                        SavedTrailsEmptyCard()
                    }
                } else {
                    gridItems(
                        items = savedTrails,
                        key = { "saved_${it.id}" }
                    ) { trail ->
                        val metrics = trailMetrics[trail.id]
                        val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                        TrailCard(
                            trail = trail,
                            points = points,
                            sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                            friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                            ascentMeters = metrics?.ascentMeters,
                            downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                            isSaved = true,
                            isSelected = selectedTrailForPeek?.id == trail.id,
                            onBookmarkClick = { onToggleBookmark(trail.id) },
                            onClick = { onTrailClick(trail.id) },
                            testTagOverride = "saved_trail_card_${trail.id}"
                        )
                    }
                }

                item(key = "section_all_trails_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.explore_section_all_trails),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(top = Spacing.s2)
                            .testTag("section_all_trails")
                    )
                }

                gridItems(
                    items = trails,
                    key = { it.id }
                ) { trail ->
                    val metrics = trailMetrics[trail.id]
                    val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                    val isSaved = savedTrailIds.contains(trail.id)
                    TrailCard(
                        trail = trail,
                        points = points,
                        sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                        friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                        ascentMeters = metrics?.ascentMeters,
                        downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                        downloadProgress = downloadProgress[trail.id],
                        isSaved = isSaved,
                        isSelected = selectedTrailForPeek?.id == trail.id,
                        onBookmarkClick = { onToggleBookmark(trail.id) },
                        onClick = { onTrailClick(trail.id) }
                    )
                }

                item(key = "section_more_marked_routes_header", span = { GridItemSpan(maxLineSpan) }) {
                    MoreMarkedRoutesHeader()
                }

                if (isLoadingMoreMarkedRoutes && moreMarkedRoutes.isEmpty()) {
                    item(key = "section_more_marked_routes_loading", span = { GridItemSpan(maxLineSpan) }) {
                        MoreMarkedRoutesLoadingCard()
                    }
                } else if (moreMarkedRoutes.isEmpty()) {
                    item(key = "section_more_marked_routes_empty", span = { GridItemSpan(maxLineSpan) }) {
                        MoreMarkedRoutesEmptyCard()
                    }
                } else {
                    gridItems(
                        items = moreMarkedRoutes,
                        key = { "discovered_${it.osmRelationId}" }
                    ) { route ->
                        val targetTrailId = "trail_osm_${route.osmRelationId}"
                        val activeProg = downloadProgress[targetTrailId]
                        val isDownloadingThis = downloadingDiscoveredRelationId == route.osmRelationId || activeProg != null
                        DiscoveredHikingRouteCard(
                            route = route,
                            isDownloading = isDownloadingThis,
                            progress = activeProg,
                            onClick = {
                                if (!isDownloadingThis) {
                                    onDownloadDiscoveredRoute(route)
                                }
                            }
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("trail_grid_columns_1")
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trail_list"),
                contentPadding = PaddingValues(
                    start = Spacing.s4,
                    end = Spacing.s4,
                    top = Spacing.s2,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.s4)
            ) {
                item(key = "section_near_you_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s1)) {
                        Text(
                            text = stringResource(R.string.explore_section_near_you),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .padding(top = Spacing.s1)
                                .testTag("section_near_you")
                        )
                        userLocationStaleMessage?.let { staleMsg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = staleMsg,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("explore_stale_location_message")
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = onPickAnotherStart,
                                    modifier = Modifier.testTag("explore_pick_another_start_button")
                                ) {
                                    Text("Vælg andet startpunkt")
                                }
                            }
                        }
                    }
                }

                if (userLocation == null) {
                    item(key = "section_near_you_permission") {
                        NearYouPermissionCard(onRequestLocation = onRequestLocation)
                    }
                } else {
                    items(
                        items = nearbyTrails.take(2),
                        key = { "near_${it.id}" }
                    ) { trail ->
                        val metrics = trailMetrics[trail.id]
                        val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                        val isSaved = savedTrailIds.contains(trail.id)
                        TrailCard(
                            trail = trail,
                            points = points,
                            sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                            friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                            ascentMeters = metrics?.ascentMeters,
                            downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                            isSaved = isSaved,
                            isSelected = selectedTrailForPeek?.id == trail.id,
                            onBookmarkClick = { onToggleBookmark(trail.id) },
                            onClick = { onTrailClick(trail.id) },
                            testTagOverride = "near_trail_card_${trail.id}"
                        )
                    }
                }

                item(key = "section_saved_header") {
                    Text(
                        text = stringResource(R.string.explore_section_saved),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(top = Spacing.s2)
                            .testTag("section_saved")
                    )
                }

                if (savedTrails.isEmpty()) {
                    item(key = "section_saved_empty") {
                        SavedTrailsEmptyCard()
                    }
                } else {
                    items(
                        items = savedTrails,
                        key = { "saved_${it.id}" }
                    ) { trail ->
                        val metrics = trailMetrics[trail.id]
                        val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                        TrailCard(
                            trail = trail,
                            points = points,
                            sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                            friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                            ascentMeters = metrics?.ascentMeters,
                            downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                            isSaved = true,
                            isSelected = selectedTrailForPeek?.id == trail.id,
                            onBookmarkClick = { onToggleBookmark(trail.id) },
                            onClick = { onTrailClick(trail.id) },
                            testTagOverride = "saved_trail_card_${trail.id}"
                        )
                    }
                }

                item(key = "section_all_trails_header") {
                    Text(
                        text = stringResource(R.string.explore_section_all_trails),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(top = Spacing.s2)
                            .testTag("section_all_trails")
                    )
                }

                items(
                    items = trails,
                    key = { it.id }
                ) { trail ->
                    val metrics = trailMetrics[trail.id]
                    val points = allGeometries[trail.id] ?: metrics?.geometryPoints
                    val isSaved = savedTrailIds.contains(trail.id)
                    TrailCard(
                        trail = trail,
                        points = points,
                        sheltersCount = shelterCounts[trail.id] ?: metrics?.shelterCount ?: 0,
                        friTeltningCount = friTeltningCounts[trail.id] ?: metrics?.friTeltningCount ?: 0,
                        ascentMeters = metrics?.ascentMeters,
                        downloadedAt = downloadedTrailMap[trail.id]?.downloadedAt,
                        downloadProgress = downloadProgress[trail.id],
                        isSaved = isSaved,
                        isSelected = selectedTrailForPeek?.id == trail.id,
                        onBookmarkClick = { onToggleBookmark(trail.id) },
                        onClick = { onTrailClick(trail.id) }
                    )
                }

                item(key = "section_more_marked_routes_header") {
                    MoreMarkedRoutesHeader()
                }

                if (isLoadingMoreMarkedRoutes && moreMarkedRoutes.isEmpty()) {
                    item(key = "section_more_marked_routes_loading") {
                        MoreMarkedRoutesLoadingCard()
                    }
                } else if (moreMarkedRoutes.isEmpty()) {
                    item(key = "section_more_marked_routes_empty") {
                        MoreMarkedRoutesEmptyCard()
                    }
                } else {
                    items(
                        items = moreMarkedRoutes,
                        key = { "discovered_${it.osmRelationId}" }
                    ) { route ->
                        val targetTrailId = "trail_osm_${route.osmRelationId}"
                        val activeProg = downloadProgress[targetTrailId]
                        val isDownloadingThis = downloadingDiscoveredRelationId == route.osmRelationId || activeProg != null
                        DiscoveredHikingRouteCard(
                            route = route,
                            isDownloading = isDownloadingThis,
                            progress = activeProg,
                            onClick = {
                                if (!isDownloadingThis) {
                                    onDownloadDiscoveredRoute(route)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NearYouPermissionCard(onRequestLocation: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.explore_near_you_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(Spacing.s3))
            Button(
                onClick = onRequestLocation,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("enable_location_button")
            ) {
                Text(stringResource(R.string.explore_near_you_action))
            }
        }
    }
}

@Composable
private fun SavedTrailsEmptyCard() {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            Icon(
                imageVector = Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.ui_trailbrowserscreen_gem_dine_favoritruter_med_bo_182),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoreMarkedRoutesHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.explore_section_more_marked_routes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag("section_more_marked_routes")
        )
        Text(
            text = stringResource(R.string.explore_more_marked_routes_subtitle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MoreMarkedRoutesLoadingCard() {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("more_marked_routes_loading")
    ) {
        Row(
            modifier = Modifier.padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.explore_more_marked_routes_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoreMarkedRoutesEmptyCard() {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("more_marked_routes_empty")
    ) {
        Text(
            text = stringResource(R.string.explore_more_marked_routes_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.s4)
        )
    }
}

@Composable
internal fun TrailBrowserMapContent(
    isDark: Boolean,
    trails: List<TrailEntity>,
    allGeometries: Map<String, List<GeoPointDto>>,
    trailMetrics: Map<String, TrailCardMetrics>,
    shelterCounts: Map<String, Int>,
    friTeltningCounts: Map<String, Int>,
    downloadedTrailMap: Map<String, DownloadedTrailEntity>,
    savedTrailIds: Set<String>,
    selectedTrailForPeek: TrailEntity?,
    viewModel: ExploreViewModel,
    onMapReady: (MapView) -> Unit,
    onCenterDenmark: () -> Unit,
    onOpenRouteBuilder: (Double, Double, String) -> Unit,
    onTrailClick: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var lastExploreRouteSig by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        DanskStiMap(
            modifier = Modifier.fillMaxSize(),
            mapTestTag = "explore_full_screen_map",
            initialCenter = GeoPoint(55.9, 10.4),
            initialZoom = 7.5,
            interceptParentTouchScroll = true,
            appIsDark = isDark,
            controlsTopPadding = 16.dp,
            controlsEndPadding = 16.dp,
            attributionBottomPadding = 24.dp,
            layersButtonTestTag = "open_layers_sheet_button",
            compassButtonTestTag = "center_denmark_button",
            myLocationButtonTestTag = "explore_my_location_button",
            onCompassClick = onCenterDenmark,
            onMapReady = onMapReady,
            onAddToRouteFromDropPin = onOpenRouteBuilder,
            onRouteHereFromDropPin = onOpenRouteBuilder,
            onUseMarkedRouteInPlanner = { markedRoute ->
                coroutineScope.launch {
                    viewModel.saveMarkedRouteAsTrail(markedRoute)
                }
            },
            updateOverlaysKey = Triple(trails, allGeometries, selectedTrailForPeek),
            onUpdateOverlays = { mapView, overlayManager, isDarkMap ->
                var sig = if (isDarkMap) 1231 else 1237
                sig = 31 * sig + trails.size
                sig = 31 * sig + allGeometries.size
                sig = 31 * sig + (selectedTrailForPeek?.id?.hashCode() ?: 0)
                if (sig != lastExploreRouteSig) {
                    lastExploreRouteSig = sig
                    val density = mapView.context.resources.displayMetrics.density
                    val children = ArrayList<Overlay>()
                    val defaultCasingColor = if (isDarkMap) {
                        RouteMarkerHelper.COLOR_PLANNED_CASING_DARK
                    } else {
                        RouteMarkerHelper.COLOR_PLANNED_CASING_LIGHT
                    }

                    trails.forEachIndexed { index, trail ->
                        val points = allGeometries[trail.id] ?: emptyList()
                        if (points.isNotEmpty()) {
                            val geoPoints = points.map { GeoPoint(it.lat, it.lng) }
                            val isSelected = selectedTrailForPeek?.id == trail.id
                            val casing = Polyline(mapView).apply {
                                setPoints(geoPoints)
                                outlinePaint.color = defaultCasingColor
                                outlinePaint.strokeWidth = (if (isSelected) 7.5f else 5.0f) * density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true
                            }
                            children.add(casing)

                            val polyline = Polyline(mapView).apply {
                                setPoints(geoPoints)
                                outlinePaint.color = if (isSelected) {
                                    if (isDarkMap) RouteMarkerHelper.COLOR_PLANNED_ROUTE_DARK
                                    else RouteMarkerHelper.COLOR_PLANNED_ROUTE_LIGHT
                                } else {
                                    TRAIL_LINE_COLORS[index % TRAIL_LINE_COLORS.size]
                                }
                                outlinePaint.strokeWidth = (if (isSelected) 5.0f else 3.2f) * density
                                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                outlinePaint.isAntiAlias = true

                                setOnClickListener { _, _, _ ->
                                    viewModel.selectTrailForPeek(trail)
                                    true
                                }
                            }
                            children.add(polyline)
                        }
                    }
                    overlayManager.updateFolderOverlay(
                        MapLayersOverlayManager.KEY_ROUTE,
                        children
                    )
                }
            }
        )

        selectedTrailForPeek?.let { peekTrail ->
            val metrics = trailMetrics[peekTrail.id]
            val points = allGeometries[peekTrail.id] ?: metrics?.geometryPoints
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(Spacing.s4)
            ) {
                TrailCard(
                    trail = peekTrail,
                    points = points,
                    sheltersCount = shelterCounts[peekTrail.id] ?: metrics?.shelterCount ?: 0,
                    friTeltningCount = friTeltningCounts[peekTrail.id] ?: metrics?.friTeltningCount ?: 0,
                    ascentMeters = metrics?.ascentMeters,
                    downloadedAt = downloadedTrailMap[peekTrail.id]?.downloadedAt,
                    isSaved = savedTrailIds.contains(peekTrail.id),
                    isSelected = true,
                    onBookmarkClick = { viewModel.toggleBookmark(peekTrail.id) },
                    onClick = { onTrailClick(peekTrail.id) }
                )
            }
        }
    }
}
