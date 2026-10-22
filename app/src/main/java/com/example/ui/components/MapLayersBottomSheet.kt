package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.HolidayVillage
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BaseMapType
import com.example.data.local.MapDarkMode
import com.example.data.local.MapLayersState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.NordicBlue

/**
 * Comprehensive Layers Sheet for Dansk Sti:
 * - Base map selector: Topografisk (Skærmkortet), Udendørs (Thunderforest / OpenTopoMap), Luftfoto
 * - Dark map setting ("Kort i mørke"): Følg telefonen / Altid lyst / Altid mørkt
 * - Overlays: Terræn (DHM skyggekort + kurver), Vand (søer, vandløb, vådområder, kilder),
 *   Afmærkede ruter, Adgangsforhold, Shelters, Fri teltning, Drikkevand, Toiletter, Butikker, Transport, Mine punkter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLayersBottomSheet(
    state: MapLayersState,
    settingsRepository: com.example.data.local.SettingsRepository,
    onDismiss: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    MapLayersBottomSheet(
        layersState = state,
        onBaseMapChanged = { scope.launch { settingsRepository.setBaseMap(it) } },
        onToggleMarkedRoutes = { scope.launch { settingsRepository.setOverlayMarkedRoutes(it) } },
        onToggleAccess = { scope.launch { settingsRepository.setOverlayAccess(it) } },
        onToggleShelters = { scope.launch { settingsRepository.setOverlayShelters(it) } },
        onToggleFriTeltning = { scope.launch { settingsRepository.setOverlayFriTeltning(it) } },
        onToggleWater = { scope.launch { settingsRepository.setOverlayWater(it) } },
        onToggleToilets = { scope.launch { settingsRepository.setOverlayToilets(it) } },
        onToggleShops = { scope.launch { settingsRepository.setOverlayShops(it) } },
        onToggleTransport = { scope.launch { settingsRepository.setOverlayTransport(it) } },
        onToggleMyPoints = { scope.launch { settingsRepository.setOverlayMyPoints(it) } },
        onToggleTerrain = { scope.launch { settingsRepository.setOverlayTerrain(it) } },
        onToggleWaterLayer = { scope.launch { settingsRepository.setOverlayWaterLayer(it) } },
        onMapDarkModeChanged = { scope.launch { settingsRepository.setMapDarkMode(it) } },
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLayersBottomSheet(
    layersState: MapLayersState,
    onBaseMapChanged: (BaseMapType) -> Unit,
    onToggleMarkedRoutes: (Boolean) -> Unit,
    onToggleAccess: (Boolean) -> Unit,
    onToggleShelters: (Boolean) -> Unit,
    onToggleFriTeltning: (Boolean) -> Unit,
    onToggleWater: (Boolean) -> Unit,
    onToggleToilets: (Boolean) -> Unit,
    onToggleShops: (Boolean) -> Unit,
    onToggleTransport: (Boolean) -> Unit,
    onToggleMyPoints: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onToggleTerrain: (Boolean) -> Unit = {},
    onToggleWaterLayer: (Boolean) -> Unit = {},
    onMapDarkModeChanged: (MapDarkMode) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("map_layers_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Kortlag & Visning",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_layers_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Luk",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. BASE MAP SECTION
            Text(
                text = "GRUNDKORT",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = ForestGreenPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = "Topografisk",
                subtitle = "Skærmkortet — Danmarks officielle topografiske kort (SDFI)",
                icon = Icons.Default.Map,
                isSelected = layersState.baseMap == BaseMapType.SKAERMKORT,
                onClick = { onBaseMapChanged(BaseMapType.SKAERMKORT) },
                testTag = "base_map_skaermkort"
            )

            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = "Udendørs",
                subtitle = "Thunderforest Outdoors / OpenTopoMap med stier & kurver",
                icon = Icons.Default.Terrain,
                isSelected = layersState.baseMap == BaseMapType.FRILUFTSKORT,
                onClick = { onBaseMapChanged(BaseMapType.FRILUFTSKORT) },
                testTag = "base_map_friluftskort"
            )

            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = "Luftfoto",
                subtitle = "GeoDanmark forårsortofoto i høj opløsning (EPSG:3857)",
                icon = Icons.Default.PhotoCamera,
                isSelected = layersState.baseMap == BaseMapType.LUFTFOTO,
                onClick = { onBaseMapChanged(BaseMapType.LUFTFOTO) },
                testTag = "base_map_luftfoto"
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            // 1B. DARK MAP SETTING ("Kort i mørke")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = ForestGreenPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Kort i mørke",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("map_dark_mode_selector"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapDarkMode.entries.forEach { mode ->
                    val selected = layersState.mapDarkMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onMapDarkModeChanged(mode) },
                        label = {
                            Text(
                                text = mode.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ForestGreenPrimary.copy(alpha = 0.16f),
                            selectedLabelColor = ForestGreenPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (selected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.testTag("map_dark_mode_${mode.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // 2. OVERLAYS SECTION
            Text(
                text = "OVERLEJRINGER (KORTLAG)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = ForestGreenPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            LayerToggleRow(
                title = "Terræn",
                subtitle = "DHM terræn-skyggekort og højdekurver (fra zoom 14)",
                icon = Icons.Default.Landscape,
                iconTint = ForestGreenPrimary,
                checked = layersState.showTerrain,
                onCheckedChange = onToggleTerrain,
                testTag = "toggle_layer_terrain"
            )

            LayerToggleRow(
                title = "Vand",
                subtitle = "Søer, vådområder, åer, bække og kilder (fra zoom 12)",
                icon = Icons.Default.Water,
                iconTint = NordicBlue,
                checked = layersState.showWaterLayer,
                onCheckedChange = onToggleWaterLayer,
                testTag = "toggle_layer_water_bodies"
            )

            LayerToggleRow(
                title = "Afmærkede ruter",
                subtitle = "OSM vandreruter og lokale stier ved zoom 10+",
                icon = Icons.Default.Explore,
                iconTint = ForestGreenPrimary,
                checked = layersState.showMarkedRoutes,
                onCheckedChange = onToggleMarkedRoutes,
                testTag = "toggle_layer_marked_routes"
            )

            LayerToggleRow(
                title = "Adgangsforhold",
                subtitle = "Statsskove, private skove & naturbeskyttelse",
                icon = Icons.Default.Security,
                iconTint = ForestGreenLight,
                checked = layersState.showAccess,
                onCheckedChange = onToggleAccess,
                testTag = "toggle_layer_access"
            )

            LayerToggleRow(
                title = "Shelters",
                subtitle = "Over 1.000 GeoFA og OSM shelterpladser",
                icon = Icons.Default.HolidayVillage,
                iconTint = AmberAccent,
                checked = layersState.showShelters,
                onCheckedChange = onToggleShelters,
                testTag = "toggle_layer_shelters"
            )

            LayerToggleRow(
                title = "Fri teltning",
                subtitle = "Naturstyrelsens 275+ skove med 1-2-3 reglen",
                icon = Icons.Default.Forest,
                iconTint = ForestGreenPrimary,
                checked = layersState.showFriTeltning,
                onCheckedChange = onToggleFriTeltning,
                testTag = "toggle_layer_fri_teltning"
            )

            LayerToggleRow(
                title = "Drikkevand",
                subtitle = "Vandposter og kilder langs ruten",
                icon = Icons.Default.WaterDrop,
                iconTint = NordicBlue,
                checked = layersState.showWater,
                onCheckedChange = onToggleWater,
                testTag = "toggle_layer_water"
            )

            LayerToggleRow(
                title = "Toiletter",
                subtitle = "Offentlige toiletter og faciliteter",
                icon = Icons.Default.Wc,
                iconTint = NordicBlue,
                checked = layersState.showToilets,
                onCheckedChange = onToggleToilets,
                testTag = "toggle_layer_toilets"
            )

            LayerToggleRow(
                title = "Butikker & proviant",
                subtitle = "Supermarkeder, bagerier og gårdbutikker",
                icon = Icons.Default.ShoppingBag,
                iconTint = AmberAccent,
                checked = layersState.showShops,
                onCheckedChange = onToggleShops,
                testTag = "toggle_layer_shops"
            )

            LayerToggleRow(
                title = "Offentlig transport",
                subtitle = "Togstationer, busstoppesteder og færger",
                icon = Icons.Default.DirectionsBus,
                iconTint = NordicBlue,
                checked = layersState.showTransport,
                onCheckedChange = onToggleTransport,
                testTag = "toggle_layer_transport"
            )

            LayerToggleRow(
                title = "Mine punkter",
                subtitle = "Egne tilføjede waypoints og noter",
                icon = Icons.Default.Bookmark,
                iconTint = AmberAccent,
                checked = layersState.showMyPoints,
                onCheckedChange = onToggleMyPoints,
                testTag = "toggle_layer_my_points"
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BaseMapOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                ForestGreenPrimary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) ForestGreenPrimary else MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(9.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = ForestGreenPrimary)
            )
        }
    }
}

@Composable
private fun LayerToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = iconTint.copy(alpha = 0.15f),
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ForestGreenPrimary
            )
        )
    }
}

@Composable
fun MapAttributionPill(
    layersState: MapLayersState,
    isDarkMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeCredits = buildList {
        when (layersState.baseMap) {
            BaseMapType.SKAERMKORT -> add("© Klimadatastyrelsen (Skærmkortet)")
            BaseMapType.FRILUFTSKORT -> add("© OpenTopoMap / Thunderforest · © OpenStreetMap")
            BaseMapType.LUFTFOTO -> add("© GeoDanmark Ortofoto / Esri")
        }
        if (layersState.showTerrain) {
            add("DHM Terræn")
        }
        if (layersState.showWaterLayer) {
            add("© OSM Vand")
        }
        if (layersState.showAccess) {
            add("MiljøGIS / Fredninger")
        }
        if (layersState.showShelters || layersState.showFriTeltning) {
            add("GeoFA / Naturstyrelsen")
        }
    }.distinct().joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isDarkMode) Color(0xCC141A16) else Color(0xD9FFFFFF),
        border = BorderStroke(0.5.dp, if (isDarkMode) Color(0x44FFFFFF) else Color(0x22000000)),
        modifier = modifier
    ) {
        Text(
            text = activeCredits,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = if (isDarkMode) Color(0xFFD0DDD4) else Color(0xFF2B352E),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

