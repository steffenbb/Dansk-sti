package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.components.OfflineStatusPill
import com.example.ui.components.SkeletonLoadingList
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.SheetTopShape
import com.example.ui.theme.Spacing
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.LengthFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailBrowserScreen(
    onTrailClick: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenCredits: () -> Unit = {},
    onOpenRouteBuilder: (lat: Double, lng: Double, label: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = DanskStiTheme.isDark

    val allTrails by viewModel.allTrails.collectAsState()
    val trails by viewModel.filteredTrails.collectAsState()
    val savedTrails by viewModel.savedTrails.collectAsState()
    val savedTrailIds by viewModel.savedTrailIds.collectAsState()
    val nearbyTrails by viewModel.nearbyTrails.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val userLocationStaleMessage by viewModel.userLocationStaleMessage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val lengthFilter by viewModel.lengthFilter.collectAsState()
    val flerdagesOnly by viewModel.flerdagesOnly.collectAsState()
    val sheltersUndervejs by viewModel.sheltersUndervejs.collectAsState()
    val friTeltningOnly by viewModel.friTeltningOnly.collectAsState()
    val shelterCounts by viewModel.shelterCounts.collectAsState()
    val friTeltningCounts by viewModel.friTeltningCounts.collectAsState()
    val trailMetrics by viewModel.trailMetrics.collectAsState()
    val groupedSearchResults by viewModel.groupedSearchResults.collectAsState()
    val selectedTrailForPeek by viewModel.selectedTrailForPeek.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val nearbySpots by viewModel.nearbySpots.collectAsState()
    val isSearchingNearby by viewModel.isSearchingNearby.collectAsState()
    val showNearbySheet by viewModel.showNearbySheet.collectAsState()
    val isNearbyUsingMapCenter by viewModel.isNearbyUsingMapCenter.collectAsState()
    val selectedMarkedRoute by viewModel.selectedMarkedRoute.collectAsState()
    val moreMarkedRoutes by viewModel.moreMarkedRoutes.collectAsState()
    val isLoadingMoreMarkedRoutes by viewModel.isLoadingMoreMarkedRoutes.collectAsState()
    val downloadingDiscoveredRelationId by viewModel.downloadingDiscoveredRelationId.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    var isMapView by rememberSaveable { mutableStateOf(false) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    var showRegionMenu by remember { mutableStateOf(false) }
    var showLengthMenu by remember { mutableStateOf(false) }

    val regions = listOf("Alle", "Jylland", "Fyn & Øerne", "Sjælland & Lolland-Falster", "Bornholm")

    @SuppressLint("MissingPermission")
    fun refreshDeviceLocation(openNearbySpotsSheet: Boolean = false) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val centerPair = mapViewRef?.mapCenter?.let { Pair(it.latitude, it.longitude) }
        if (hasFine || hasCoarse || viewModel.customLocationResolver != null) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = if (hasFine || hasCoarse) {
                lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            } else null
            viewModel.refreshUserLocationWithFreshness(
                openNearbySpotsSheet = openNearbySpotsSheet,
                fallbackLocation = loc,
                fallbackMapCenter = centerPair
            )
        } else {
            viewModel.clearUserLocation()
            if (openNearbySpotsSheet) {
                val center = mapViewRef?.mapCenter
                if (center != null && (center.latitude != 0.0 || center.longitude != 0.0)) {
                    viewModel.findNearbyOvernights(center.latitude, center.longitude, isMapCenter = true)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceLocation(openNearbySpotsSheet = false)
    }

    var pendingNearbySheetRequest by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            refreshDeviceLocation(openNearbySpotsSheet = pendingNearbySheetRequest)
        } else {
            viewModel.clearUserLocation()
            if (pendingNearbySheetRequest) {
                val center = mapViewRef?.mapCenter
                if (center != null && (center.latitude != 0.0 || center.longitude != 0.0)) {
                    viewModel.findNearbyOvernights(center.latitude, center.longitude, isMapCenter = true)
                }
            }
        }
        pendingNearbySheetRequest = false
    }

    fun requestLocationForDiscovery(openNearbySheet: Boolean = false) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            refreshDeviceLocation(openNearbySpotsSheet = openNearbySheet)
        } else {
            pendingNearbySheetRequest = openNearbySheet
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val app = context.applicationContext as DanskStiApp
    val downloadedTrails by remember(app) { app.repository.getAllDownloadedTrails() }.collectAsState(initial = emptyList())
    val downloadedTrailMap = remember(downloadedTrails) {
        downloadedTrails.associateBy { it.trailId }
    }
    val isDeviceOnline = remember(app) {
        runCatching { app.repository.isDeviceOnline() }.getOrDefault(true)
    }
    var allGeometries by remember { mutableStateOf<Map<String, List<GeoPointDto>>>(emptyMap()) }
    LaunchedEffect(allTrails, trailMetrics) {
        if (allTrails.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val map = mutableMapOf<String, List<GeoPointDto>>()
                for (t in allTrails) {
                    val fromMetrics = trailMetrics[t.id]?.geometryPoints
                    if (!fromMetrics.isNullOrEmpty()) {
                        map[t.id] = fromMetrics
                    } else {
                        val pts = try {
                            app.repository.getTrailGeometry(t.id)?.segments?.flatMap { it.points } ?: emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (pts.isNotEmpty()) {
                            map[t.id] = pts
                        } else if (t.bboxSouth != null && t.bboxNorth != null && t.bboxWest != null && t.bboxEast != null) {
                            map[t.id] = listOf(
                                GeoPointDto(t.bboxSouth, t.bboxWest),
                                GeoPointDto((t.bboxSouth + t.bboxNorth) / 2.0, (t.bboxWest + t.bboxEast) / 2.0),
                                GeoPointDto(t.bboxNorth, t.bboxEast)
                            )
                        }
                    }
                }
                allGeometries = map
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top header bar: Title + Offline pill (if offline) + Liste / Kort segmented toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
            ) {
                Text(
                    text = stringResource(R.string.nav_explore),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (!isDeviceOnline && !isMapView) {
                    OfflineStatusPill()
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val listSelected = !isMapView
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (listSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .sizeIn(minHeight = 44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { isMapView = false }
                            .testTag("view_toggle_list")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatListBulleted,
                                contentDescription = stringResource(R.string.explore_view_list),
                                tint = if (listSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.explore_view_list),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (listSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val mapSelected = isMapView
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (mapSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .sizeIn(minHeight = 44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable { isMapView = true }
                            .testTag("view_toggle_map")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = stringResource(R.string.explore_view_map),
                                tint = if (mapSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.explore_view_map),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (mapSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Search field at top (trails and places)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s4)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 52.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.s4),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.cd_search),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.explore_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("trail_search_input")
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.setSearchQuery("") },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.cd_clear_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (!groupedSearchResults.isEmpty && searchQuery.trim().length >= 2) {
            com.example.ui.components.GroupedPlaceSearchDropdown(
                groupedResults = groupedSearchResults,
                onSelectRouteOrTrail = { item ->
                    viewModel.setSearchQuery("")
                    if (item.isCustomRoute && item.customRouteId != null) {
                        onTrailClick("route_${item.customRouteId}")
                    } else if (item.trailId != null) {
                        onTrailClick(item.trailId)
                    }
                },
                onRouteHere = { lat, lng, name ->
                    viewModel.setSearchQuery("")
                    onOpenRouteBuilder(lat, lng, name)
                },
                onAddAsPoint = { lat, lng, name ->
                    viewModel.setSearchQuery("")
                    onOpenRouteBuilder(lat, lng, name)
                },
                onShowOnMap = { lat, lng, _ ->
                    viewModel.setSearchQuery("")
                    viewModel.updateUserLocation(lat, lng)
                    isMapView = true
                    mapViewRef?.controller?.animateTo(GeoPoint(lat, lng), 13.0, 600L)
                },
                modifier = Modifier.padding(horizontal = Spacing.s4, vertical = Spacing.s1)
            )
        }

        // Filter chips: Region, Længde, Flerdages, Shelters undervejs, Fri teltning
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                val hasRegion = selectedRegion != null && selectedRegion != "Alle"
                FilterChip(
                    selected = hasRegion,
                    onClick = { showRegionMenu = true },
                    label = {
                        Text(
                            text = if (hasRegion) selectedRegion!! else stringResource(R.string.explore_filter_region),
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("filter_chip_region")
                )
                DropdownMenu(
                    expanded = showRegionMenu,
                    onDismissRequest = { showRegionMenu = false }
                ) {
                    regions.forEach { reg ->
                        DropdownMenuItem(
                            text = { Text(reg) },
                            onClick = {
                                viewModel.setSelectedRegion(if (reg == "Alle") null else reg)
                                showRegionMenu = false
                            },
                            modifier = Modifier.testTag("filter_region_${reg.lowercase()}")
                        )
                    }
                }
            }

            Box {
                val hasLength = lengthFilter != LengthFilter.ALL
                FilterChip(
                    selected = hasLength,
                    onClick = { showLengthMenu = true },
                    label = {
                        Text(
                            text = if (hasLength) lengthFilter.label else stringResource(R.string.explore_filter_length),
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("filter_chip_length")
                )
                DropdownMenu(
                    expanded = showLengthMenu,
                    onDismissRequest = { showLengthMenu = false }
                ) {
                    LengthFilter.values().forEach { lf ->
                        DropdownMenuItem(
                            text = { Text(lf.label) },
                            onClick = {
                                viewModel.setLengthFilter(lf)
                                showLengthMenu = false
                            }
                        )
                    }
                }
            }

            FilterChip(
                selected = flerdagesOnly,
                onClick = { viewModel.toggleFlerdagesOnly() },
                label = {
                    Text(
                        text = stringResource(R.string.explore_filter_multiday),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("filter_chip_flerdages")
            )

            FilterChip(
                selected = sheltersUndervejs,
                onClick = { viewModel.toggleSheltersUndervejs() },
                label = {
                    Text(
                        text = stringResource(R.string.explore_filter_shelters),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("filter_chip_shelters")
            )

            FilterChip(
                selected = friTeltningOnly,
                onClick = { viewModel.toggleFriTeltningOnly() },
                label = {
                    Text(
                        text = stringResource(R.string.explore_filter_fri_teltning),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("filter_chip_fri_teltning")
            )

            FilterChip(
                selected = showNearbySheet,
                onClick = { requestLocationForDiscovery(openNearbySheet = true) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.explore_section_near_you),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                ),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("near_me_button")
            )
        }

        // Main Content: Either Liste or Kort
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (!isMapView) {
                if (errorMessage != null) {
                    ErrorStateView(
                        message = errorMessage ?: stringResource(R.string.state_error_default),
                        onRetry = { viewModel.loadMetadataAndCounts() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (allTrails.isEmpty()) {
                    SkeletonLoadingList(
                        itemCount = 4,
                        cardHeight = 148.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (trails.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Default.Landscape,
                        sentence = stringResource(R.string.explore_empty_trails),
                        actionLabel = stringResource(R.string.explore_empty_action),
                        onAction = { viewModel.clearAllFilters() },
                        actionTestTag = "reset_filters_button",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    TrailBrowserListContent(
                        trails = trails,
                        savedTrails = savedTrails,
                        savedTrailIds = savedTrailIds,
                        nearbyTrails = nearbyTrails,
                        userLocation = userLocation,
                        userLocationStaleMessage = userLocationStaleMessage,
                        onPickAnotherStart = {
                            isMapView = true
                        },
                        shelterCounts = shelterCounts,
                        friTeltningCounts = friTeltningCounts,
                        trailMetrics = trailMetrics,
                        allGeometries = allGeometries,
                        downloadedTrailMap = downloadedTrailMap,
                        downloadProgress = downloadProgress,
                        selectedTrailForPeek = selectedTrailForPeek,
                        moreMarkedRoutes = moreMarkedRoutes,
                        isLoadingMoreMarkedRoutes = isLoadingMoreMarkedRoutes,
                        downloadingDiscoveredRelationId = downloadingDiscoveredRelationId,
                        onRequestLocation = { requestLocationForDiscovery(openNearbySheet = false) },
                        onToggleBookmark = { viewModel.toggleBookmark(it) },
                        onTrailClick = onTrailClick,
                        onDownloadDiscoveredRoute = { route ->
                            viewModel.saveAndDownloadDiscoveredRoute(route) { savedTrail ->
                                if (savedTrail != null) {
                                    onTrailClick(savedTrail.id)
                                }
                            }
                        }
                    )
                }
            } else {
                TrailBrowserMapContent(
                    isDark = isDark,
                    trails = trails,
                    allGeometries = allGeometries,
                    trailMetrics = trailMetrics,
                    shelterCounts = shelterCounts,
                    friTeltningCounts = friTeltningCounts,
                    downloadedTrailMap = downloadedTrailMap,
                    savedTrailIds = savedTrailIds,
                    selectedTrailForPeek = selectedTrailForPeek,
                    viewModel = viewModel,
                    onMapReady = { mv -> mapViewRef = mv },
                    onCenterDenmark = {
                        mapViewRef?.controller?.animateTo(GeoPoint(55.9, 10.4), 7.5, 600L)
                    },
                    onOpenRouteBuilder = onOpenRouteBuilder,
                    onTrailClick = onTrailClick
                )
            }
        }
    }

    if (showNearbySheet) {
        TrailBrowserNearbySheet(
            isNearbyUsingMapCenter = isNearbyUsingMapCenter,
            isSearchingNearby = isSearchingNearby,
            nearbySpots = nearbySpots,
            hasUserLocation = userLocation != null,
            staleLocationMessage = userLocationStaleMessage,
            onPickAnotherStart = {
                viewModel.setShowNearbySheet(false)
                isMapView = true
            },
            onDismiss = { viewModel.setShowNearbySheet(false) },
            onOpenRouteBuilder = onOpenRouteBuilder
        )
    }

    selectedMarkedRoute?.let { markedRoute ->
        MarkedRouteDetailSheet(
            route = markedRoute,
            onDismiss = { viewModel.selectMarkedRoute(null) },
            onUseAsBaseInRouteBuilder = { r ->
                coroutineScope.launch {
                    viewModel.saveMarkedRouteAsTrail(r)
                }
                viewModel.selectMarkedRoute(null)
            }
        )
    }
}

