package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity

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

    if (description == null && note == null && operator == null && website == null && distance == null && from == null && to == null) {
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
                        text = "Foto: ${photo.author} · ${photo.license}",
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
                    text = "Ved start: ${poi.name} (${String.format(appLocale, "%.1f", distKm)} km)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_nearest_start_transport_text")
                )
            }

            nearestEndTransport?.let { (poi, distM) ->
                val distKm = distM / 1000.0
                Text(
                    text = "Ved mål: ${poi.name} (${String.format(appLocale, "%.1f", distKm)} km)",
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
                        text = "Kom til start",
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
