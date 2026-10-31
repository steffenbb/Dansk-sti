package com.example.ui.components
import com.example.R

import androidx.compose.ui.res.stringResource

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
import com.example.data.local.MapThemeOverride
import com.example.ui.theme.DanskStiTheme

/**
 * Comprehensive Layers Sheet for Dansk Sti:
 * - Base map selector: Topografisk (Skærmkortet), Udendørs (Thunderforest / OpenTopoMap), Luftfoto
 * - Appearance setting ("Udseende"): Følg telefonen / Lyst / Mørkt + "Kortet følger appen"
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
        onMapFollowsAppChanged = { scope.launch { settingsRepository.setMapFollowsApp(it) } },
        onMapThemeOverrideChanged = { scope.launch { settingsRepository.setMapThemeOverride(it) } },
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
    onMapDarkModeChanged: (MapDarkMode) -> Unit = {},
    onMapFollowsAppChanged: (Boolean) -> Unit = {},
    onMapThemeOverrideChanged: (MapThemeOverride) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val primaryColor = MaterialTheme.colorScheme.primary
    val stiColors = DanskStiTheme.colors

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
                        tint = primaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.ui_maplayersbottomsheet_kortlag_visning_15),
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
                        contentDescription = stringResource(R.string.btn_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. BASE MAP SECTION
            Text(
                text = stringResource(R.string.ui_maplayersbottomsheet_grundkort_16),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = primaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = stringResource(R.string.map_layer_base_topo_title),
                subtitle = stringResource(R.string.map_layer_base_topo_subtitle),
                icon = Icons.Default.Map,
                isSelected = layersState.baseMap == BaseMapType.SKAERMKORT,
                onClick = { onBaseMapChanged(BaseMapType.SKAERMKORT) },
                testTag = "base_map_skaermkort"
            )

            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = stringResource(R.string.map_layer_base_outdoor_title),
                subtitle = stringResource(R.string.map_layer_base_outdoor_subtitle),
                icon = Icons.Default.Terrain,
                isSelected = layersState.baseMap == BaseMapType.FRILUFTSKORT,
                onClick = { onBaseMapChanged(BaseMapType.FRILUFTSKORT) },
                testTag = "base_map_friluftskort"
            )

            Spacer(modifier = Modifier.height(8.dp))

            BaseMapOptionCard(
                title = stringResource(R.string.map_layer_base_aerial_title),
                subtitle = stringResource(R.string.map_layer_base_aerial_subtitle),
                icon = Icons.Default.PhotoCamera,
                isSelected = layersState.baseMap == BaseMapType.LUFTFOTO,
                onClick = { onBaseMapChanged(BaseMapType.LUFTFOTO) },
                testTag = "base_map_luftfoto"
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            // 1B. APPEARANCE & MAP THEME SETTING ("Udseende" + "Kortet følger appen")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.setting_appearance),
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
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = if (selected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.testTag("map_dark_mode_${mode.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.setting_map_follows_app),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = layersState.mapFollowsApp,
                    onCheckedChange = onMapFollowsAppChanged,
                    modifier = Modifier.testTag("map_follows_app_switch")
                )
            }
            if (!layersState.mapFollowsApp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("map_theme_override_selector"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MapThemeOverride.entries.forEach { overrideMode ->
                        val selected = layersState.mapThemeOverride == overrideMode
                        FilterChip(
                            selected = selected,
                            onClick = { onMapThemeOverrideChanged(overrideMode) },
                            label = {
                                Text(
                                    text = overrideMode.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = if (selected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.testTag("map_override_${overrideMode.name.lowercase()}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // 2. OVERLAYS SECTION
            Text(
                text = stringResource(R.string.ui_maplayersbottomsheet_overlejringer_kortlag_17),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = primaryColor
            )
            Spacer(modifier = Modifier.height(8.dp))

            LayerToggleRow(
                title = stringResource(R.string.map_layer_terrain_title),
                subtitle = stringResource(R.string.map_layer_terrain_subtitle),
                icon = Icons.Default.Landscape,
                iconTint = primaryColor,
                checked = layersState.showTerrain,
                onCheckedChange = onToggleTerrain,
                testTag = "toggle_layer_terrain"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_water_bodies_title),
                subtitle = stringResource(R.string.map_layer_water_bodies_subtitle),
                icon = Icons.Default.Water,
                iconTint = stiColors.plannedRoute,
                checked = layersState.showWaterLayer,
                onCheckedChange = onToggleWaterLayer,
                testTag = "toggle_layer_water_bodies"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_marked_routes_title),
                subtitle = stringResource(R.string.map_layer_marked_routes_subtitle),
                icon = Icons.Default.Explore,
                iconTint = primaryColor,
                checked = layersState.showMarkedRoutes,
                onCheckedChange = onToggleMarkedRoutes,
                testTag = "toggle_layer_marked_routes"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_access_title),
                subtitle = stringResource(R.string.map_layer_access_subtitle),
                icon = Icons.Default.Security,
                iconTint = stiColors.success,
                checked = layersState.showAccess,
                onCheckedChange = onToggleAccess,
                testTag = "toggle_layer_access"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_shelters_title),
                subtitle = stringResource(R.string.map_layer_shelters_subtitle),
                icon = Icons.Default.HolidayVillage,
                iconTint = stiColors.warning,
                checked = layersState.showShelters,
                onCheckedChange = onToggleShelters,
                testTag = "toggle_layer_shelters"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_fri_teltning_title),
                subtitle = stringResource(R.string.map_layer_fri_teltning_subtitle),
                icon = Icons.Default.Forest,
                iconTint = primaryColor,
                checked = layersState.showFriTeltning,
                onCheckedChange = onToggleFriTeltning,
                testTag = "toggle_layer_fri_teltning"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_drinking_water_title),
                subtitle = stringResource(R.string.map_layer_drinking_water_subtitle),
                icon = Icons.Default.WaterDrop,
                iconTint = stiColors.plannedRoute,
                checked = layersState.showWater,
                onCheckedChange = onToggleWater,
                testTag = "toggle_layer_water"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_toilets_title),
                subtitle = stringResource(R.string.map_layer_toilets_subtitle),
                icon = Icons.Default.Wc,
                iconTint = stiColors.plannedRoute,
                checked = layersState.showToilets,
                onCheckedChange = onToggleToilets,
                testTag = "toggle_layer_toilets"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_shops_title),
                subtitle = stringResource(R.string.map_layer_shops_subtitle),
                icon = Icons.Default.ShoppingBag,
                iconTint = stiColors.warning,
                checked = layersState.showShops,
                onCheckedChange = onToggleShops,
                testTag = "toggle_layer_shops"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_transport_title),
                subtitle = stringResource(R.string.map_layer_transport_subtitle),
                icon = Icons.Default.DirectionsBus,
                iconTint = stiColors.plannedRoute,
                checked = layersState.showTransport,
                onCheckedChange = onToggleTransport,
                testTag = "toggle_layer_transport"
            )

            LayerToggleRow(
                title = stringResource(R.string.map_layer_my_points_title),
                subtitle = stringResource(R.string.map_layer_my_points_subtitle),
                icon = Icons.Default.Bookmark,
                iconTint = stiColors.warning,
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
    val primaryColor = MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
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
                color = if (isSelected) primaryColor else MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                colors = RadioButtonDefaults.colors(selectedColor = primaryColor)
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
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
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

    val containerColor = if (isDarkMode) {
        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    }
    val contentColor = if (isDarkMode) {
        MaterialTheme.colorScheme.inverseOnSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Text(
            text = activeCredits,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

