package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGlass
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.NordicBlue
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailBrowserScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val trails by viewModel.filteredTrails.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedRegion by viewModel.selectedRegion.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val activePlan by viewModel.activeTripPlan.collectAsState()
    val navState by viewModel.navLiveState.collectAsState()

    val regions = listOf("Alle", "Fyn & Øerne", "Jylland", "Sjælland", "Bornholm")

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForestDarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ForestGreenPrimary,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Dansk Sti",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = ForestGreenLight.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "NORDIC",
                                        color = ForestGreenLight,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Vandreruter & Friluftsliv",
                                fontSize = 11.sp,
                                color = ForestGreenLight,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncLiveTrails() },
                        enabled = !isSyncing,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("sync_trails_button")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AmberAccent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Opdater ruter",
                                tint = Color.LightGray
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.SAVED_PLANS) },
                        modifier = Modifier
                            .background(ForestSurfaceVariant, CircleShape)
                            .testTag("saved_plans_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "Gemte planer",
                            tint = AmberAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ForestSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search Input with Glassmorphism
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Søg efter stier, f.eks. Hærvejen, Mols Bjerge...", color = Color.Gray, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Søg",
                        tint = ForestGreenLight
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Ryd søgning",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("trail_search_input"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ForestSurface,
                    unfocusedContainerColor = ForestSurface,
                    focusedBorderColor = ForestGreenLight,
                    unfocusedBorderColor = GlassBorderSubtle,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )

            // Region Filter Chips Carousel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                regions.forEach { region ->
                    val isSelected = selectedRegion == region
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.onRegionSelected(region) }
                            .testTag("region_chip_${region.lowercase().replace(" ", "_")}"),
                        color = if (isSelected) ForestGreenPrimary else ForestSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) ForestGreenLight else GlassBorderSubtle
                        )
                    ) {
                        Text(
                            text = region,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else Color.LightGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Active Navigation Banner if running
            if (activePlan != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { viewModel.navigateTo(AppScreen.NAVIGATION_MODE) }
                        .testTag("resume_active_banner"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberAccent.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AmberAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Aktiv vandretur i gang",
                                    color = AmberAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = activePlan!!.trailName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AmberAccent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Trail List
            if (trails.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Landscape,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Ingen ruter matcher søgningen",
                            color = Color.LightGray,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header counter
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "UDVALGTE VANDRERUTER (${trails.size})",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Naturstyrelsen verificeret",
                                color = ForestGreenLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    items(trails, key = { it.id }) { trail ->
                        ModernTrailCard(
                            trail = trail,
                            onClick = { viewModel.selectTrail(trail) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModernTrailCard(
    trail: TrailEntity,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("trail_card_${trail.id}"),
        shape = RoundedCornerShape(18.dp),
        color = ForestSurface,
        border = BorderStroke(1.dp, GlassBorderSubtle),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini-map polyline preview
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ForestSurfaceVariant)
                        .border(1.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
                ) {
                    TrailMiniMapPreview(
                        pointsJson = trail.pointsJson,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = trail.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ForestGreenPrimary.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, ForestGreenLight.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${trail.lengthKm.toInt()} km",
                                color = ForestGreenLight,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = trail.description,
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        maxLines = 2,
                        lineHeight = 16.sp,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = ForestDarkBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "📍 ${trail.region}",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = ForestDarkBg,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "⛰️ ${trail.elevationGainM} m",
                                color = AmberAccent,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Vælg rute",
                    tint = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TrailMiniMapPreview(
    pointsJson: String,
    modifier: Modifier = Modifier
) {
    val moshi = Moshi.Builder().build()
    val pointListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
    val points = try {
        moshi.adapter<List<GeoPointDto>>(pointListType).fromJson(pointsJson) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    Canvas(modifier = modifier.padding(8.dp)) {
        if (points.size < 2) return@Canvas

        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }

        val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
        val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)

        val path = Path()
        points.forEachIndexed { index, pt ->
            val x = ((pt.lng - minLng) / lngSpan * size.width).toFloat()
            val y = ((maxLat - pt.lat) / latSpan * size.height).toFloat()
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = AmberAccent,
            style = Stroke(width = 3.dp.toPx())
        )

        // Draw start dot
        val startX = ((points.first().lng - minLng) / lngSpan * size.width).toFloat()
        val startY = ((maxLat - points.first().lat) / latSpan * size.height).toFloat()
        drawCircle(color = ForestGreenLight, radius = 4.dp.toPx(), center = Offset(startX, startY))

        // Draw end dot
        val endX = ((points.last().lng - minLng) / lngSpan * size.width).toFloat()
        val endY = ((maxLat - points.last().lat) / latSpan * size.height).toFloat()
        drawCircle(color = Color.Red, radius = 4.dp.toPx(), center = Offset(endX, endY))
    }
}
