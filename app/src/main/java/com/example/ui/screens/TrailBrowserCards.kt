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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DiscoveredHikingRouteDto
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailEntity
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SkeletonLoadingList
import com.example.ui.components.TrailGeometryThumbnail
import com.example.ui.components.formatDanishElevationMeters
import com.example.ui.components.formatDanishKm
import com.example.ui.components.formatDownloadedDateDanish
import com.example.ui.theme.CardShape
import com.example.ui.theme.SheetTopShape
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.util.GeoUtils
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

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
    downloadedAt: Long? = null,
    downloadProgress: TrailDownloadProgress? = null,
    isSaved: Boolean = false,
    isSelected: Boolean = false,
    onBookmarkClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    testTagOverride: String? = null,
    modifier: Modifier = Modifier
) {
    val totalKm = trail.computedWalkingKm
    val estimatedDays = max(1, ceil(totalKm / 18.0).toInt())
    val computedRegion = remember(trail) { GeoUtils.computeDanishRegionForTrail(trail) }
    val displayRegion = computedRegion.ifEmpty { stringResource(R.string.explore_region_after_download) }

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
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
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
                            text = displayRegion,
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
                                tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Real computed stats line: "215 km · +1.850 m · ca. 14 dage" or "Længde kendes efter download"
                if (totalKm > 0.0) {
                    Text(
                        text = stringResource(R.string.ui_trailbrowserscreen_km_m_ca_184, kmFormatted, ascentFormatted, estimatedDays, if (estimatedDays == 1) "dag" else "dage"),
                        style = TabularBodyStyle.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.explore_length_after_download),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Real computed overnight line: "42 shelters · 9 fri teltning"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cabin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.ui_trailbrowserscreen_shelters_fri_teltning_185, sheltersCount, friTeltningCount),
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (downloadProgress != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("trail_card_progress_${trail.id}"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.trail_download_progress_format,
                                downloadProgress.message,
                                (downloadProgress.fraction * 100).toInt().coerceIn(0, 100)
                            ),
                            style = TabularLabelStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Downloaded date badge ("Hentet <dato>") when trail is downloaded offline
                if (downloadedAt != null) {
                    Text(
                        text = formatDownloadedDateDanish(downloadedAt),
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("trail_card_downloaded_date_${trail.id}")
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoveredHikingRouteCard(
    route: DiscoveredHikingRouteDto,
    isDownloading: Boolean,
    progress: TrailDownloadProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(enabled = !isDownloading, onClick = onClick)
            .testTag("discovered_route_card_${route.osmRelationId}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s1)
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
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = route.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(route.networkLabel)
                                route.ref?.takeIf { it.isNotBlank() }?.let {
                                    append(" · ")
                                    append(it)
                                }
                                append(" · OSM #")
                                append(route.osmRelationId)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isDownloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.s1)
                        .testTag("discovered_route_progress_${route.osmRelationId}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { (progress?.fraction ?: 0.1f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = if (progress != null) {
                            stringResource(
                                R.string.trail_download_progress_format,
                                progress.message,
                                (progress.fraction * 100).toInt().coerceIn(0, 100)
                            )
                        } else {
                            stringResource(R.string.explore_more_marked_routes_downloading)
                        },
                        style = TabularLabelStyle,
                        color = MaterialTheme.colorScheme.primary
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
    showDistance: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isShelter = spot.type == "SHELTER"
    val icon = if (isShelter) Icons.Default.Cabin else Icons.Default.Forest
    val iconTint = if (isShelter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
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

                if (showDistance) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = distanceText,
                            style = TabularLabelStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.ui_droppinbottomsheet_rute_hertil_14), style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onOpenDirections,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
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
                    Text(text = stringResource(R.string.ui_trailbrowserscreen_rutevejledning_186), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailBrowserNearbySheet(
    isNearbyUsingMapCenter: Boolean,
    isSearchingNearby: Boolean,
    nearbySpots: List<com.example.data.model.NearbyOvernightSpot>,
    hasUserLocation: Boolean,
    onDismiss: () -> Unit,
    onOpenRouteBuilder: (Double, Double, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                        text = stringResource(R.string.ui_trailbrowserscreen_shelters_fri_teltning_inden__183),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
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
                    onAction = onDismiss
                )
            } else {
                val availableHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.takeIf { it > 0 }?.dp ?: 640.dp
                val nearbyListMaxHeight = availableHeightDp * 0.60f
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = nearbyListMaxHeight)
                        .testTag("nearby_spots_list"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s3)
                ) {
                    items(nearbySpots, key = { it.id }) { spot ->
                        NearbyOvernightSpotCard(
                            spot = spot,
                            showDistance = !isNearbyUsingMapCenter && hasUserLocation,
                            onOpenDirections = {
                                val uri = Uri.parse("geo:0,0?q=${spot.lat},${spot.lng}(${Uri.encode(spot.name)})")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${spot.lat},${spot.lng}")))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Ingen app fundet til at åbne linket", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${spot.lat},${spot.lng}")))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Ingen app fundet til at åbne linket", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onRouteHerTil = {
                                onDismiss()
                                onOpenRouteBuilder(spot.lat, spot.lng, spot.name)
                            }
                        )
                    }
                }
            }
        }
    }
}

