package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.remote.GroupedSearchResults
import com.example.data.remote.RouteOrTrailSearchItem
import com.example.ui.theme.CardShape
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.Spacing

@Composable
fun GroupedPlaceSearchDropdown(
    groupedResults: GroupedSearchResults,
    onSelectRouteOrTrail: (RouteOrTrailSearchItem) -> Unit,
    onRouteHere: (lat: Double, lng: Double, name: String) -> Unit,
    onAddAsPoint: (lat: Double, lng: Double, name: String) -> Unit,
    onShowOnMap: (lat: Double, lng: Double, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groupedResults.isEmpty) return
    val primaryColor = MaterialTheme.colorScheme.primary
    val warningColor = DanskStiTheme.colors.warning

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val availableHeightDp = configuration.screenHeightDp.takeIf { it > 0 }?.dp ?: 640.dp
    val dropdownMaxHeight = availableHeightDp * 0.48f

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("grouped_search_dropdown")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = dropdownMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            // Group 1: Mine ruter og vandreruter
            if (groupedResults.routesAndTrails.isNotEmpty()) {
                SearchGroupHeader(
                    title = stringResource(R.string.search_group_routes_title),
                    testTag = "search_group_routes_header"
                )
                groupedResults.routesAndTrails.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .clickable { onSelectRouteOrTrail(item) }
                            .padding(horizontal = Spacing.s4, vertical = 8.dp)
                            .testTag("search_route_item_${item.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (groupedResults.sheltersAndFriTeltning.isNotEmpty() || groupedResults.places.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            // Group 2: Shelters og fri teltning
            if (groupedResults.sheltersAndFriTeltning.isNotEmpty()) {
                SearchGroupHeader(
                    title = stringResource(R.string.search_group_shelters_title),
                    testTag = "search_group_shelters_header"
                )
                groupedResults.sheltersAndFriTeltning.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowOnMap(item.lat, item.lng, item.name) }
                            .padding(horizontal = Spacing.s4, vertical = 6.dp)
                            .testTag("search_shelter_item_${item.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            Icon(
                                imageVector = if (item.type == "FRI_TELTNING") Icons.Default.Forest else Icons.Default.Cabin,
                                contentDescription = null,
                                tint = warningColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        PlaceActionButtonsRow(
                            routeHereTestTag = "search_shelter_route_here_${item.id}",
                            addPointTestTag = "search_shelter_add_point_${item.id}",
                            showMapTestTag = "search_shelter_show_map_${item.id}",
                            onRouteHere = { onRouteHere(item.lat, item.lng, item.name) },
                            onAddAsPoint = { onAddAsPoint(item.lat, item.lng, item.name) },
                            onShowOnMap = { onShowOnMap(item.lat, item.lng, item.name) }
                        )
                    }
                }
                if (groupedResults.places.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            // Group 3: Steder (towns, forests, lakes, stations)
            if (groupedResults.places.isNotEmpty()) {
                SearchGroupHeader(
                    title = stringResource(R.string.search_group_places_title),
                    testTag = "search_group_places_header"
                )
                groupedResults.places.forEachIndexed { idx, place ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowOnMap(place.lat, place.lng, place.displayName) }
                            .padding(horizontal = Spacing.s4, vertical = 6.dp)
                            .testTag("search_place_item_$idx")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = warningColor,
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
                                Text(
                                    text = place.subtitle ?: place.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        PlaceActionButtonsRow(
                            routeHereTestTag = "search_place_route_here_$idx",
                            addPointTestTag = "search_place_add_point_$idx",
                            showMapTestTag = "search_place_show_map_$idx",
                            onRouteHere = { onRouteHere(place.lat, place.lng, place.displayName) },
                            onAddAsPoint = { onAddAsPoint(place.lat, place.lng, place.displayName) },
                            onShowOnMap = { onShowOnMap(place.lat, place.lng, place.displayName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchGroupHeader(
    title: String,
    testTag: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PlaceActionButtonsRow(
    routeHereTestTag: String,
    addPointTestTag: String,
    showMapTestTag: String,
    onRouteHere: () -> Unit,
    onAddAsPoint: () -> Unit,
    onShowOnMap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onRouteHere,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
                .sizeIn(minHeight = 36.dp)
                .testTag(routeHereTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Directions,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.search_action_route_here),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onAddAsPoint,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
                .sizeIn(minHeight = 36.dp)
                .testTag(addPointTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.AddLocationAlt,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.search_action_add_as_point),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onShowOnMap,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
                .sizeIn(minHeight = 36.dp)
                .testTag(showMapTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.search_action_show_on_map),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
