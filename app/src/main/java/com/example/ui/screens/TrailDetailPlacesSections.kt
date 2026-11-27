package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.viewmodel.PoiSpotItem
import com.example.ui.viewmodel.TrailCorridorExtras
import com.example.util.OsmExtraLabels

/** Phase C6: places from OpenStreetMap on the trail page (Oversigt, Overnatning, Vand & mad). */

private const val HIGHLIGHT_ROWS_COLLAPSED = 15

/** The label for a place's subtype, e.g. "Udsigtspunkt", or null for the older POI types. */
@Composable
internal fun placeTypeLabel(poi: PoiSpotItem): String? {
    if (poi.type == "shop" && poi.subtype == "fuel") return stringResource(R.string.osm_extra_name_fuel)
    if (poi.type !in OsmExtraLabels.EXTRA_TYPES) return null
    return OsmExtraLabels.labelRes(poi.type, poi.subtype)?.let { stringResource(it) }
}

/** Oversigt tab: "Undervejs" – highlights, beaches, picnic sites and fire pits sorted by km. */
@Composable
fun TrailHighlightsAlongCard(highlights: List<PoiSpotItem>, isSyncingPois: Boolean) {
    val appLocale = com.example.ui.components.rememberAppLocale()
    var showAll by remember(highlights.size) { mutableStateOf(false) }
    Card(
        shape = com.example.ui.theme.TileShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().testTag("trail_highlights_card")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.trail_highlights_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (highlights.isEmpty()) {
                Text(
                    text = stringResource(if (isSyncingPois) R.string.ui_traildetailscreen_henter_data_202 else R.string.trail_highlights_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_highlights_empty")
                )
            } else {
                val counts = TrailCorridorExtras.highlightCounts(highlights)
                if (counts.isNotEmpty()) {
                    Text(
                        text = counts.map { (group, n) -> pluralStringResource(highlightGroupPlural(group), n, n) }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("trail_highlights_counts")
                    )
                }
                val visible = if (showAll) highlights else highlights.take(HIGHLIGHT_ROWS_COLLAPSED)
                visible.forEach { h ->
                    val label = placeTypeLabel(h) ?: h.name
                    val km = String.format(appLocale, "%.1f", h.kmOnRoute)
                    val distance = if (h.detourMeters > 50) {
                        stringResource(R.string.trail_place_detour_fmt, h.detourMeters.toInt())
                    } else {
                        stringResource(R.string.trail_place_at_trail)
                    }
                    val row = if (h.name.equals(label, ignoreCase = true)) {
                        stringResource(R.string.trail_highlight_row_unnamed_fmt, km, label, distance)
                    } else {
                        stringResource(R.string.trail_highlight_row_fmt, km, label, h.name, distance)
                    }
                    Text(
                        text = row,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!showAll && highlights.size > HIGHLIGHT_ROWS_COLLAPSED) {
                    Text(
                        text = stringResource(R.string.trail_highlights_show_all_fmt, highlights.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { showAll = true }
                            .padding(vertical = 6.dp)
                            .testTag("trail_highlights_show_all")
                    )
                }
            }
            Text(
                text = stringResource(R.string.path_details_source_osm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

private fun highlightGroupPlural(group: String): Int = when (group) {
    "viewpoint" -> R.plurals.trail_highlight_count_viewpoint
    "tower" -> R.plurals.trail_highlight_count_tower
    "historic" -> R.plurals.trail_highlight_count_historic
    "firepit" -> R.plurals.trail_highlight_count_firepit
    "picnic" -> R.plurals.trail_highlight_count_picnic
    else -> R.plurals.trail_highlight_count_beach
}

@Composable
private fun SectionHeading(text: String, testTag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag(testTag)
    )
}

@Composable
private fun SectionNote(text: String, testTag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(testTag)
    )
}

/** Overnatning tab: "Hostels & B&B nær ruten" (within 2 km). */
fun LazyListScope.trailAccommodationItems(accommodations: List<PoiSpotItem>) {
    item(key = "accommodation_heading") {
        SectionHeading(stringResource(R.string.trail_accommodation_title), "trail_accommodation_heading")
    }
    if (accommodations.isEmpty()) {
        item(key = "accommodation_empty") {
            SectionNote(stringResource(R.string.trail_accommodation_empty), "trail_accommodation_empty")
        }
    } else {
        items(accommodations, key = { it.id }) { PoiItemCard(poi = it) }
    }
}

/**
 * Vand & mad tab: cafés with the open-now line, and churchyards as possible (unconfirmed) water,
 * including the hint when one lies inside the longest confirmed water gap.
 */
fun LazyListScope.trailFoodAndLikelyWaterItems(
    foodPlaces: List<PoiSpotItem>,
    likelyWater: List<PoiSpotItem>,
    likelyWaterInLongestGap: PoiSpotItem?
) {
    item(key = "food_heading") {
        SectionHeading(stringResource(R.string.trail_food_title), "trail_food_heading")
    }
    if (foodPlaces.isEmpty()) {
        item(key = "food_empty") { SectionNote(stringResource(R.string.trail_food_empty), "trail_food_empty") }
    } else {
        items(foodPlaces, key = { it.id }) { PoiItemCard(poi = it) }
    }
    if (likelyWater.isNotEmpty() || likelyWaterInLongestGap != null) {
        item(key = "likely_water_heading") {
            SectionHeading(stringResource(R.string.trail_likely_water_title), "trail_likely_water_heading")
        }
        item(key = "likely_water_disclaimer") {
            SectionNote(stringResource(R.string.poi_water_likely_disclaimer), "trail_likely_water_disclaimer")
        }
        likelyWaterInLongestGap?.let { gapPlace ->
            item(key = "likely_water_gap_hint") {
                val appLocale = com.example.ui.components.rememberAppLocale()
                SectionNote(
                    stringResource(
                        R.string.trail_likely_water_in_gap_fmt,
                        gapPlace.name,
                        String.format(appLocale, "%.1f", gapPlace.kmOnRoute)
                    ),
                    "trail_likely_water_gap_hint"
                )
            }
        }
        items(likelyWater, key = { it.id }) { PoiItemCard(poi = it) }
    }
}

/** The open-now line for cafés, shops and pharmacies with opening hours (evaluated now). */
@Composable
internal fun openNowLine(poi: PoiSpotItem): String? {
    if (poi.type !in setOf("cafe", "shop", "pharmacy") || poi.openingHours.isNullOrBlank()) return null
    val context = LocalContext.current
    val state = com.example.util.OpeningHoursEvaluator.evaluate(poi.openingHours, System.currentTimeMillis())
    return com.example.util.OpeningHoursEvaluator.formatDanish(state, context)
}
