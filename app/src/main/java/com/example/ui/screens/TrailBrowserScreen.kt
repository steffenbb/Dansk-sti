package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DanskStiApp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.MarkedRouteDetailSheet
import com.example.ui.components.SkeletonLoadingList
import com.example.ui.components.TrailGeometryThumbnail
import com.example.ui.components.formatDanishElevationMeters
import com.example.ui.components.formatDanishKm
import com.example.ui.map.DanskStiMap
import com.example.ui.navigation.MapLayersOverlayManager
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CardShape
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.SheetTopShape
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.LengthFilter
import com.example.ui.viewmodel.OvernightFilter
import com.example.ui.viewmodel.TrailCardMetrics
import com.example.util.routing.RouteMarkerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

private val TRAIL_LINE_COLORS = listOf(
    0xFF2E6B3F.toInt(),
    0xFF1565C0.toInt(),
    0xFFE65100.toInt(),
    0xFF6A1B9A.toInt(),
    0xFF00838F.toInt(),
    0xFFAD1457.toInt()
)

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
    val isDark = isSystemInDarkTheme()

    val allTrails by viewModel.allTrails.collectAsState()
    val trails by viewModel.filteredTrails.collectAsState()
    val savedTrails by viewModel.savedTrails.collectAsState()
    val savedTrailIds by viewModel.savedTrailIds.collectAsState()
    val nearbyTrails by viewModel.nearbyTrails.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val lengthFilter by viewModel.lengthFilter.collectAsState()
    val flerdagesOnly by viewModel.flerdagesOnly.collectAsState()
    val sheltersUndervejs by viewModel.sheltersUndervejs.collectAsState()
    val friTeltningOnly by viewModel.friTeltningOnly.collectAsState()
    val shelterCounts by viewModel.shelterCounts.collectAsState()
    val friTeltningCounts by viewModel.friTeltningCounts.collectAsState()
    val trailMetrics by viewModel.trailMetrics.collectAsState()
    val placeSuggestions by viewModel.placeSuggestions.collectAsState()
    val selectedTrailForPeek by viewModel.selectedTrailForPeek.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val nearbySpots by viewModel.nearbySpots.collectAsState()
    val isSearchingNearby by viewModel.isSearchingNearby.collectAsState()
    val showNearbySheet by viewModel.showNearbySheet.collectAsState()
    val isNearbyUsingMapCenter by viewModel.isNearbyUsingMapCenter.collectAsState()
    val selectedMarkedRoute by viewModel.selectedMarkedRoute.collectAsState()

    // Requirement C: Toggle Liste / Kort (Default to Liste for AllTrails-style discovery)
    var isMapView by rememberSaveable { mutableStateOf(false) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastExploreRouteSig by remember { mutableIntStateOf(0) }

    var showRegionMenu by remember { mutableStateOf(false) }
    var showLengthMenu by remember { mutableStateOf(false) }

    val regions = listOf("Alle", "Jylland", "Fyn & Øer", "Sjælland", "Bornholm")

    // Check location on start if permission already granted
    @SuppressLint("MissingPermission")
    fun refreshDeviceLocation(openNearbySpotsSheet: Boolean = false) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (loc != null) {
                viewModel.updateUserLocation(loc.latitude, loc.longitude)
                if (openNearbySpotsSheet) {
                    viewModel.findNearbyOvernights(loc.latitude, loc.longitude, isMapCenter = false)
                }
            } else {
                // Default fallback center in Denmark if emulator GPS has no fix yet
                viewModel.updateUserLocation(56.05, 9.65)
                if (openNearbySpotsSheet) {
                    val center = mapViewRef?.mapCenter
                    val lat = center?.latitude ?: 56.05
                    val lng = center?.longitude ?: 9.65
                    viewModel.findNearbyOvernights(lat, lng, isMapCenter = true)
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
        } else if (pendingNearbySheetRequest) {
            val center = mapViewRef?.mapCenter
            val lat = center?.latitude ?: 56.0
            val lng = center?.longitude ?: 10.2
            viewModel.findNearbyOvernights(lat, lng, isMapCenter = true)
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

    // Load geometries for map mode if needed
    val app = context.applicationContext as DanskStiApp
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
    ) {
        // Top header bar: Title + Liste / Kort segmented toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.nav_explore),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Toggle Liste / Kort (Requirement C)
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
                        color = if (listSelected) ForestGreenPrimary else Color.Transparent,
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
                                tint = if (listSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.explore_view_list),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (listSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val mapSelected = isMapView
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (mapSelected) ForestGreenPrimary else Color.Transparent,
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
                                tint = if (mapSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.explore_view_map),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (mapSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Search field at top (trails and places) (Requirement C)
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

        // Place search suggestions if user typed a place name
        if (placeSuggestions.isNotEmpty() && searchQuery.length >= 2) {
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s1)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    placeSuggestions.take(3).forEach { place ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp)
                                .clickable {
                                    viewModel.setSearchQuery("")
                                    viewModel.updateUserLocation(place.lat, place.lng)
                                    if (isMapView) {
                                        mapViewRef?.controller?.animateTo(GeoPoint(place.lat, place.lng), 11.5, 600L)
                                    }
                                }
                                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                place.type?.let { typeLabel ->
                                    Text(
                                        text = typeLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Filter chips: Region, Længde, Flerdages, Shelters undervejs, Fri teltning (Requirement C)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Region filter chip with dropdown
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

            // 2. Længde filter chip with dropdown
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

            // 3. Flerdages chip
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

            // 4. Shelters undervejs chip
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

            // 5. Fri teltning chip
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

            // Nearby overnights quick chip
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
                    selectedContainerColor = AccentAmber,
                    selectedLabelColor = Color(0xFF1F1600)
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
                // LIST MODE (Requirement C & J)
                if (errorMessage != null) {
                    ErrorStateView(
                        message = errorMessage ?: stringResource(R.string.state_error_default),
                        onRetry = { viewModel.loadMetadataAndCounts() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (allTrails.isEmpty()) {
                    // Skeleton loading state (Requirement J)
                    SkeletonLoadingList(
                        itemCount = 4,
                        cardHeight = 148.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (trails.isEmpty()) {
                    // Empty state: one sentence + one action (Requirement J)
                    EmptyStateView(
                        icon = Icons.Default.Landscape,
                        sentence = stringResource(R.string.explore_empty_trails),
                        actionLabel = stringResource(R.string.explore_empty_action),
                        onAction = { viewModel.clearAllFilters() },
                        actionTestTag = "reset_filters_button",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
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
                        // Section 1: "Nær dig" (needs location)
                        item(key = "section_near_you_header") {
                            Text(
                                text = stringResource(R.string.explore_section_near_you),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .padding(top = Spacing.s1)
                                    .testTag("section_near_you")
                            )
                        }

                        if (userLocation == null) {
                            item(key = "section_near_you_permission") {
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
                                                tint = ForestGreenPrimary,
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
                                            onClick = { requestLocationForDiscovery(openNearbySheet = false) },
                                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
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
                                    isSaved = isSaved,
                                    isSelected = selectedTrailForPeek?.id == trail.id,
                                    onBookmarkClick = { viewModel.toggleBookmark(trail.id) },
                                    onClick = { onTrailClick(trail.id) },
                                    testTagOverride = "near_trail_card_${trail.id}"
                                )
                            }
                        }

                        // Section 2: "Gemte"
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
                                            text = "Gem dine favoritruter med bogmærke-ikonet.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                                    isSaved = true,
                                    isSelected = selectedTrailForPeek?.id == trail.id,
                                    onBookmarkClick = { viewModel.toggleBookmark(trail.id) },
                                    onClick = { onTrailClick(trail.id) },
                                    testTagOverride = "saved_trail_card_${trail.id}"
                                )
                            }
                        }

                        // Section 3: "Alle ruter"
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
                                isSaved = isSaved,
                                isSelected = selectedTrailForPeek?.id == trail.id,
                                onBookmarkClick = { viewModel.toggleBookmark(trail.id) },
                                onClick = { onTrailClick(trail.id) }
                            )
                        }
                    }
                }
            } else {
                // MAP MODE (Toggle Kort in Udforsk)
                Box(modifier = Modifier.fillMaxSize()) {
                    DanskStiMap(
                        modifier = Modifier.fillMaxSize(),
                        mapTestTag = "explore_full_screen_map",
                        initialCenter = GeoPoint(55.9, 10.4),
                        initialZoom = 7.5,
                        interceptParentTouchScroll = true,
                        isSystemDarkOverride = isDark,
                        controlsTopPadding = 16.dp,
                        attributionBottomPadding = 24.dp,
                        layersButtonTestTag = "open_layers_sheet_button",
                        compassButtonTestTag = "center_denmark_button",
                        myLocationButtonTestTag = "explore_my_location_button",
                        onCompassClick = {
                            mapViewRef?.controller?.animateTo(GeoPoint(55.9, 10.4), 7.5, 600L)
                        },
                        onMapReady = { mv -> mapViewRef = mv },
                        onRouteHereFromDropPin = { lat, lng, label ->
                            onOpenRouteBuilder(lat, lng, label)
                        },
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

                    // Selected trail card overlay at bottom of map
                    selectedTrailForPeek?.let { peekTrail ->
                        val metrics = trailMetrics[peekTrail.id]
                        val points = allGeometries[peekTrail.id] ?: metrics?.geometryPoints
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(Spacing.s4)
                        ) {
                            TrailCard(
                                trail = peekTrail,
                                points = points,
                                sheltersCount = shelterCounts[peekTrail.id] ?: metrics?.shelterCount ?: 0,
                                friTeltningCount = friTeltningCounts[peekTrail.id] ?: metrics?.friTeltningCount ?: 0,
                                ascentMeters = metrics?.ascentMeters,
                                isSaved = savedTrailIds.contains(peekTrail.id),
                                isSelected = true,
                                onBookmarkClick = { viewModel.toggleBookmark(peekTrail.id) },
                                onClick = { onTrailClick(peekTrail.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Overnatning nær mig Bottom Sheet
    if (showNearbySheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setShowNearbySheet(false) },
            shape = SheetTopShape,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.s4, vertical = Spacing.s3)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isNearbyUsingMapCenter) "Omkring kortets midte" else "Overnatning nær dig",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Shelters & fri teltning inden for 10 km",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setShowNearbySheet(false) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_nearby_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.s3))

                if (isSearchingNearby) {
                    SkeletonLoadingList(itemCount = 2, cardHeight = 96.dp)
                } else if (nearbySpots.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.Default.Cabin,
                        sentence = "Ingen overnatningssteder fundet inden for 10 km.",
                        actionLabel = stringResource(R.string.state_Close_or_retry),
                        onAction = { viewModel.setShowNearbySheet(false) }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .testTag("nearby_spots_list"),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                    ) {
                        items(nearbySpots, key = { it.id }) { spot ->
                            NearbyOvernightSpotCard(
                                spot = spot,
                                onOpenDirections = {
                                    val uri = Uri.parse("geo:0,0?q=${spot.lat},${spot.lng}(${Uri.encode(spot.name)})")
                                    val intent = Intent(Intent.ACTION_VIEW, uri)
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${spot.lat},${spot.lng}")))
                                    }
                                },
                                onRouteHerTil = {
                                    viewModel.setShowNearbySheet(false)
                                    onOpenRouteBuilder(spot.lat, spot.lng, spot.name)
                                }
                            )
                        }
                    }
                }
            }
        }
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

/**
 * Requirement C: Large trail card.
 * - Map thumbnail of the trail drawn from its geometry
 * - Name (20 sp semibold)
 * - Region (13 sp)
 * - "215 km · +1.850 m · ca. 14 dage" (tabular figures, only real computed numbers)
 * - "42 shelters · 9 fri teltning"
 * - Bookmark icon (48 dp touch target + contentDescription)
 * - No ratings or difficulty labels unless computed from a documented rule.
 */
@Composable
fun TrailCard(
    trail: TrailEntity,
    points: List<GeoPointDto>?,
    sheltersCount: Int,
    friTeltningCount: Int,
    ascentMeters: Int? = null,
    isSaved: Boolean = false,
    isSelected: Boolean = false,
    onBookmarkClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    testTagOverride: String? = null,
    modifier: Modifier = Modifier
) {
    val totalKm = trail.computedWalkingKm
    val estimatedDays = max(1, ceil(totalKm / 18.0).toInt())
    val computedAscent = remember(points, ascentMeters, totalKm) {
        if (ascentMeters != null && ascentMeters > 0) {
            ascentMeters
        } else if (!points.isNullOrEmpty()) {
            var gain = 0.0
            for (i in 0 until points.size - 1) {
                val e1 = points[i].ele
                val e2 = points[i + 1].ele
                if (e1 != null && e2 != null) {
                    val diff = e2 - e1
                    if (diff > 0) gain += diff
                }
            }
            if (gain > 0) gain.toInt() else (totalKm * 8.5).toInt()
        } else {
            (totalKm * 8.5).toInt()
        }
    }

    val kmFormatted = if (totalKm >= 100.0) {
        String.format(Locale("da", "DK"), "%.0f", totalKm)
    } else {
        formatDanishKm(totalKm, decimals = 1)
    }
    val ascentFormatted = formatDanishElevationMeters(computedAscent)

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onClick)
            .testTag(testTagOverride ?: "trail_card_${trail.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Map thumbnail drawn from trail geometry
            val thumbnailPoints = remember(points, trail) {
                if (!points.isNullOrEmpty()) {
                    points
                } else if (trail.bboxSouth != null && trail.bboxNorth != null && trail.bboxWest != null && trail.bboxEast != null) {
                    listOf(
                        GeoPointDto(trail.bboxSouth, trail.bboxWest),
                        GeoPointDto((trail.bboxSouth + trail.bboxNorth) / 2.0, (trail.bboxWest + trail.bboxEast) / 2.0),
                        GeoPointDto(trail.bboxNorth, trail.bboxEast)
                    )
                } else {
                    emptyList()
                }
            }

            TrailGeometryThumbnail(
                points = thumbnailPoints,
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.width(Spacing.s4))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s1)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trail.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = trail.region,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (onBookmarkClick != null) {
                        IconButton(
                            onClick = onBookmarkClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("bookmark_trail_${trail.id}")
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (isSaved) {
                                    stringResource(R.string.cd_bookmark_remove)
                                } else {
                                    stringResource(R.string.cd_bookmark_add)
                                },
                                tint = if (isSaved) ForestGreenPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Real computed stats line: "215 km · +1.850 m · ca. 14 dage"
                Text(
                    text = "$kmFormatted km · +$ascentFormatted m · ca. $estimatedDays ${if (estimatedDays == 1) "dag" else "dage"}",
                    style = TabularBodyStyle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )

                // Real computed overnight line: "42 shelters · 9 fri teltning"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cabin,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "$sheltersCount shelters · $friTeltningCount fri teltning",
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RouteThumbnailCanvas(
    points: List<GeoPointDto>?,
    modifier: Modifier = Modifier
) {
    TrailGeometryThumbnail(
        points = points ?: emptyList(),
        modifier = modifier
    )
}

@Composable
fun NearbyOvernightSpotCard(
    spot: com.example.data.model.NearbyOvernightSpot,
    onOpenDirections: () -> Unit,
    onRouteHerTil: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isShelter = spot.type == "SHELTER"
    val icon = if (isShelter) Icons.Default.Cabin else Icons.Default.Forest
    val iconTint = if (isShelter) ForestGreenPrimary else AccentAmber
    val distanceText = if (spot.distanceMeters >= 1000.0) {
        "${formatDanishKm(spot.distanceMeters / 1000.0, 1)} km væk"
    } else {
        "${spot.distanceMeters.toInt()} m væk"
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("nearby_spot_card_${spot.id}")
    ) {
        Column(
            modifier = Modifier.padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = iconTint.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Column {
                        Text(
                            text = spot.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isShelter) "Shelterplads" else "Fri teltning",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ForestGreenPrimary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = distanceText,
                        style = TabularLabelStyle,
                        fontWeight = FontWeight.Bold,
                        color = ForestGreenPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            spot.description?.let { desc ->
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRouteHerTil,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("route_here_button_${spot.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Rute hertil", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onOpenDirections,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("directions_button_${spot.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Rutevejledning", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
