package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.MarkedRouteDto
import com.example.data.model.TrailEntity
import com.example.ui.components.formatDanishKm
import com.example.ui.components.rememberAppLocale
import org.json.JSONObject

data class CuratedTrailDescription(
    val description: String = "",
    val bestSeason: String = "",
    val dogsNote: String = "",
    val officialUrl: String = ""
) {
    val hasAnyContent: Boolean
        get() = description.isNotBlank() || bestSeason.isNotBlank() || dogsNote.isNotBlank() || officialUrl.isNotBlank()

    companion object {
        private const val ASSET_PATH = "catalogue/trail_descriptions.json"

        fun loadForTrail(context: android.content.Context, trailId: String): CuratedTrailDescription? {
            return try {
                val jsonText = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseFromJson(jsonText, trailId)
            } catch (_: Exception) {
                null
            }
        }

        fun parseFromJson(jsonText: String, trailId: String): CuratedTrailDescription? {
            return try {
                val root = JSONObject(jsonText)
                val trailsObj = root.optJSONObject("trails") ?: return null
                val item = trailsObj.optJSONObject(trailId) ?: return null
                CuratedTrailDescription(
                    description = item.optString("description").trim(),
                    bestSeason = item.optString("bestSeason").trim(),
                    dogsNote = item.optString("dogsNote").trim(),
                    officialUrl = item.optString("officialUrl").trim()
                ).takeIf { it.hasAnyContent }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun CuratedTrailDescriptionCard(
    info: CuratedTrailDescription,
    modifier: Modifier = Modifier
) {
    if (!info.hasAnyContent) return
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("trail_curated_description_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (info.description.isNotBlank()) {
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("trail_curated_description_text")
                )
            }
            if (info.bestSeason.isNotBlank()) {
                Text(
                    text = stringResource(R.string.trail_curated_best_season_fmt, info.bestSeason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_curated_best_season_text")
                )
            }
            if (info.dogsNote.isNotBlank()) {
                Text(
                    text = stringResource(R.string.trail_curated_dogs_note_fmt, info.dogsNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_curated_dogs_note_text")
                )
            }
            if (info.officialUrl.isNotBlank()) {
                Text(
                    text = info.officialUrl,
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            try {
                                val normalizedUrl = if (info.officialUrl.startsWith("http://") || info.officialUrl.startsWith("https://")) {
                                    info.officialUrl
                                } else {
                                    "https://${info.officialUrl}"
                                }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)))
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("trail_curated_official_url_text")
                )
            }
        }
    }
}

/**
 * Round 21 F2: Displays real OSM relation tags (description, note, operator, website, distance, from, to)
 * labelled "Ifølge OpenStreetMap".
 */
@Composable
fun OsmRelationTagsCard(
    osmTags: Map<String, String>,
    trail: TrailEntity? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val description = (osmTags["description:da"] ?: osmTags["description"] ?: trail?.description)?.trim()?.takeIf { it.isNotEmpty() }
    val note = osmTags["note"]?.trim()?.takeIf { it.isNotEmpty() }
    val operator = (osmTags["operator"] ?: trail?.osmOperator)?.trim()?.takeIf { it.isNotEmpty() }
    val website = (osmTags["website"] ?: osmTags["contact:website"] ?: osmTags["url"] ?: trail?.osmWebsite)?.trim()?.takeIf { it.isNotEmpty() }
    val distance = (osmTags["distance"] ?: trail?.osmDistanceTag)?.trim()?.takeIf { it.isNotEmpty() }
    val from = (osmTags["from"] ?: trail?.osmFrom)?.trim()?.takeIf { it.isNotEmpty() }
    val to = (osmTags["to"] ?: trail?.osmTo)?.trim()?.takeIf { it.isNotEmpty() }
    val osmcSymbol = (osmTags["osmc:symbol"] ?: osmTags["symbol"])?.trim()?.takeIf { it.isNotEmpty() }
    val colour = (osmTags["colour"] ?: osmTags["color"])?.trim()?.takeIf { it.isNotEmpty() }
    val networkLabel = com.example.util.OsmcSymbolParser.networkLabel(osmTags["network"], context)
    val parsedWaymark = com.example.util.OsmcSymbolParser.parse(osmcSymbol, colour)

    if (description == null && note == null && operator == null && website == null &&
        distance == null && from == null && to == null && parsedWaymark == null && networkLabel == null
    ) {
        return
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("trail_osm_tags_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.trail_detail_osm_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("trail_osm_tags_header")
            )

            if (parsedWaymark != null) {
                com.example.ui.components.WaymarkBadge(
                    symbol = osmcSymbol,
                    colour = colour,
                    modifier = Modifier.testTag("trail_osm_waymark_badge")
                )
            }

            if (networkLabel != null) {
                Text(
                    text = networkLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_osm_tag_network")
                )
            }

            if (description != null && description != trail?.description) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("trail_osm_tag_description")
                )
            }

            if (from != null || to != null) {
                val fromToLine = when {
                    from != null && to != null -> "$from → $to"
                    from != null -> from
                    else -> to!!
                }
                Text(
                    text = fromToLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_osm_tag_from_to")
                )
            }

            if (note != null) {
                Text(
                    text = stringResource(R.string.trail_detail_osm_note_fmt, note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_osm_tag_note")
                )
            }

            if (operator != null) {
                Text(
                    text = stringResource(R.string.trail_detail_osm_operator_fmt, operator),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_osm_tag_operator")
                )
            }

            if (distance != null) {
                Text(
                    text = stringResource(R.string.trail_detail_osm_distance_fmt, distance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_osm_tag_distance")
                )
            }

            if (website != null) {
                Text(
                    text = website,
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            try {
                                val normalizedUrl = if (website.startsWith("http://") || website.startsWith("https://")) {
                                    website
                                } else {
                                    "https://$website"
                                }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("trail_osm_tag_website")
                )
            }
        }
    }
}

@Composable
fun TrailCardPhotoHeader(
    photo: com.example.data.repository.TrailPhotoInfo,
    photoRepository: com.example.data.repository.TrailPhotoRepository,
    trailId: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by androidx.compose.runtime.remember(photo.imageUrl) {
        androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(photo.imageUrl) {
        bitmap = photoRepository.loadOrFetchPhotoBitmap(photo.imageUrl)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trail_card_photo_header_$trailId")
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = photo.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
        }
        Text(
            text = stringResource(R.string.trail_photo_attribution_fmt, photo.author, photo.license),
            style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.commonsPageUrl)))
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("trail_card_photo_attribution_$trailId")
        )
    }
}

@Composable
fun ShortMarkedRouteNearYouCard(
    route: MarkedRouteDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appLocale = rememberAppLocale()
    val kmText = formatDanishKm(route.lengthKm, decimals = 1, locale = appLocale)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("short_trip_near_you_${route.osmRelationId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subParts = buildList<String> {
                    route.symbol?.takeIf { it.isNotBlank() }?.let { add(it) }
                    route.operator?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (subParts.isNotEmpty()) {
                    Text(
                        text = subParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$kmText km",
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun TrailCommonsPhotosSection(
    photos: List<com.example.data.repository.TrailPhotoInfo>,
    photoRepository: com.example.data.repository.TrailPhotoRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trail_commons_photos_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        photos.take(2).forEachIndexed { idx, photo ->
            var bitmap by androidx.compose.runtime.remember(photo.imageUrl) {
                androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(photo.imageUrl) {
                bitmap = photoRepository.loadOrFetchPhotoBitmap(photo.imageUrl)
            }
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trail_commons_photo_card_$idx")
            ) {
                Column {
                    val bmp = bitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = photo.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.trail_photo_attribution_fmt, photo.author, photo.license),
                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.commonsPageUrl)))
                                } catch (_: Exception) {
                                    Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("trail_commons_photo_attribution_$idx")
                    )
                }
            }
        }
    }
}

@Composable
fun TrailStartEndTransportCard(
    startPoint: GeoPointDto?,
    trailName: String,
    nearestStartTransport: Pair<com.example.data.model.PoiEntity, Double>?,
    nearestEndTransport: Pair<com.example.data.model.PoiEntity, Double>?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appLocale = com.example.ui.components.rememberAppLocale()
    if (startPoint == null && nearestStartTransport == null && nearestEndTransport == null) return

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("trail_start_end_transport_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.trail_detail_tab_transport),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            nearestStartTransport?.let { (poi, distM) ->
                val distKm = distM / 1000.0
                Text(
                    text = stringResource(R.string.trail_transport_at_start_fmt, poi.name, String.format(appLocale, "%.1f", distKm)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_nearest_start_transport_text")
                )
            }

            nearestEndTransport?.let { (poi, distM) ->
                val distKm = distM / 1000.0
                Text(
                    text = stringResource(R.string.trail_transport_at_end_fmt, poi.name, String.format(appLocale, "%.1f", distKm)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_nearest_end_transport_text")
                )
            }

            if (startPoint != null) {
                OutlinedButton(
                    onClick = {
                        openTransitDirectionsToStart(context, startPoint.lat, startPoint.lng, trailName)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("trail_get_to_start_button")
                ) {
                    Text(
                        text = stringResource(R.string.trail_transport_get_to_start),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

fun buildTransitDirectionsIntent(lat: Double, lng: Double, label: String): Intent {
    val encodedLabel = Uri.encode(label.ifBlank { "Start" })
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($encodedLabel)")
    return Intent(Intent.ACTION_VIEW, uri)
}

fun openTransitDirectionsToStart(
    context: android.content.Context,
    lat: Double,
    lng: Double,
    label: String
) {
    val geoIntent = buildTransitDirectionsIntent(lat, lng, label)
    try {
        context.startActivity(geoIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            val webUri = Uri.parse("https://www.openstreetmap.org/directions?to=$lat%2C$lng")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.toast_no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }
}
