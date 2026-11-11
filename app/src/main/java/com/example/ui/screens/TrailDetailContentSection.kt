package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DownloadedTrailEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.data.remote.TrailWeatherInfo
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.OvernightSpotItem
import com.example.ui.viewmodel.PoiSpotItem
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.GeoUtils
import java.util.Locale

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun TrailDetailDetailsSection(
    trailId: String,
    t: TrailEntity,
    points: List<GeoPointDto>,
    osmRelationTags: Map<String, String>,
    totalKm: Double,
    elevationGain: Int,
    elevationLoss: Int,
    estimatedDays: Int,
    routeType: String,
    isSaved: Boolean,
    downloadedTrail: DownloadedTrailEntity?,
    surfaceMix: Map<String, Int>,
    isSyncingPois: Boolean,
    longestGapWithoutWaterKm: Double,
    longestGapWithoutOvernightKm: Double,
    overnightSpots: List<OvernightSpotItem>,
    waterAndToilets: List<PoiSpotItem>,
    shops: List<PoiSpotItem>,
    transports: List<PoiSpotItem>,
    weatherStart: TrailWeatherInfo?,
    weatherEnd: TrailWeatherInfo?,
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    useSideBySide: Boolean,
    viewModel: TrailViewModel,
    onPlanTrip: (String) -> Unit,
    onOpenFullscreenMap: (String) -> Unit,
    onDownloadOffline: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val showOfflineConfirmDialog by viewModel.showOfflineConfirmDialog.collectAsState()
    val offlineEstimate by viewModel.offlineEstimate.collectAsState()
    val offlineDownloadError by viewModel.offlineDownloadError.collectAsState()
    val isOfflineMapDownloading by viewModel.isOfflineMapDownloading.collectAsState()
    val offlineMapProgressPct by viewModel.offlineMapProgressPct.collectAsState()
    val startNowStatusMessage by viewModel.startNowStatusMessage.collectAsState()

    if (showOfflineConfirmDialog && offlineEstimate != null) {
        TrailOfflineDownloadDialog(
            trailName = t.name,
            estimate = offlineEstimate!!,
            onConfirm = { viewModel.confirmAndStartOfflineDownload() },
            onDismiss = { viewModel.dismissOfflineConfirmDialog() }
        )
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .then(if (useSideBySide) Modifier.statusBarsPadding() else Modifier)
            .testTag("trail_detail_details_column")
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("trail_detail_list"),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Title Block: title, region, data date + "Hentet <dato>" when downloaded
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = t.name,
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("trail_detail_name")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val computedReg = GeoUtils.computeDanishRegionForTrail(t)
                        val regionLabel = computedReg.ifEmpty { stringResource(R.string.explore_region_after_download) }
                        Text(
                            text = regionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("trail_detail_region")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ui_traildetailscreen_data_okt_2026_198),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.testTag("trail_detail_data_date")
                        )
                    }
                    downloadedTrail?.let { dl ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = com.example.ui.components.formatDownloadedDateDanish(dl.downloadedAt),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("trail_detail_downloaded_date")
                        )
                    }
                }
            }

            // Stats Row: Længde, Stigning, Estimeret tid, Type
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StatCardItem(label = stringResource(R.string.trail_detail_stat_length), value = "${String.format(Locale("da", "DK"), "%.1f", totalKm)} km")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        StatCardItem(label = stringResource(R.string.trail_detail_stat_ascent), value = "+${elevationGain} m")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        StatCardItem(label = stringResource(R.string.trail_detail_stat_estimated_time), value = "$estimatedDays ${if (estimatedDays == 1) stringResource(R.string.day_singular) else stringResource(R.string.day_plural)}")
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        StatCardItem(label = stringResource(R.string.trail_detail_stat_type), value = routeType)
                    }
                }
            }

            // Action Row of 4 Round Buttons: Gem, Hent offline, Del, Vis på kort
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RoundActionButton(
                        icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        label = if (isSaved) stringResource(R.string.trail_detail_action_saved) else stringResource(R.string.btn_save),
                        tint = if (isSaved) DanskStiTheme.colors.warning else MaterialTheme.colorScheme.primary,
                        onClick = { viewModel.toggleSave() },
                        testTag = "action_save_trail"
                    )

                    val offlineButtonLabel = when {
                        isOfflineMapDownloading -> stringResource(R.string.trail_detail_offline_downloading_fmt, offlineMapProgressPct)
                        downloadedTrail != null -> stringResource(R.string.trail_detail_offline_downloaded)
                        else -> stringResource(R.string.trail_detail_action_download_offline)
                    }
                    RoundActionButton(
                        icon = Icons.Default.CloudDownload,
                        label = offlineButtonLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { viewModel.requestOfflineDownloadEstimate() },
                        testTag = "action_download_offline",
                        progressFraction = if (isOfflineMapDownloading) offlineMapProgressPct / 100f else null
                    )

                    RoundActionButton(
                        icon = Icons.Default.Share,
                        label = stringResource(R.string.trail_detail_action_share),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            if (points.isNotEmpty()) {
                                val gpxContent = com.example.util.GpxExporter.generateMultiSegmentGpx(
                                    trail = t,
                                    plan = null,
                                    stages = emptyList(),
                                    trailPoints = points,
                                    shelters = emptyList()
                                )
                                com.example.util.GpxExporter.exportAndShareGpxFile(
                                    context = context,
                                    fileName = "${t.name.replace(" ", "_")}.gpx",
                                    gpxContent = gpxContent,
                                    shareTitle = context.getString(R.string.trail_detail_export_gpx_title_fmt, t.name)
                                )
                            } else {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.trail_detail_share_text_fmt, t.name, totalKm))
                                    type = "text/plain"
                                }
                                try {
                                    context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.trail_detail_share_chooser_title)))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, context.getString(R.string.trail_detail_share_no_app), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        testTag = "action_share_trail"
                    )

                    RoundActionButton(
                        icon = Icons.Default.Map,
                        label = stringResource(R.string.trail_detail_action_show_on_map),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { onOpenFullscreenMap(trailId) },
                        testTag = "action_show_on_map"
                    )
                }

                // Round 10C Item 3: Only open the Offline screen from a "Se offline-kort" link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.trail_detail_see_offline_maps),
                        style = MaterialTheme.typography.labelLarge.copy(textDecoration = TextDecoration.Underline),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onDownloadOffline(trailId) }
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .testTag("see_offline_maps_link")
                    )
                }

                offlineDownloadError?.let { errMsg ->
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("trail_offline_error_text")
                    )
                }
            }

            // Tabs: Oversigt, Overnatning, Vand & mad, Vejr, Transport
            item {
                val tabs = listOf(
                    stringResource(R.string.trail_detail_tab_overview),
                    stringResource(R.string.trail_detail_tab_overnight),
                    stringResource(R.string.trail_detail_tab_water_food),
                    stringResource(R.string.trail_detail_tab_weather),
                    stringResource(R.string.trail_detail_tab_transport)
                )
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 16.dp,
                    indicator = { tabPositions ->
                        if (selectedTabIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { onSelectTab(index) },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTabIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.testTag("tab_${title.lowercase().replace(" ", "_")}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (selectedTabIndex) {
                0 -> {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (t.description.isNotBlank()) {
                                Text(
                                    text = t.description,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Round 10B Item 6: Show relation's description, note, operator, website and distance tags when present, labelled "Ifølge OpenStreetMap"
                            OsmRelationTagsCard(osmTags = osmRelationTags)

                            if (points.any { it.ele != null }) {
                                Text(
                                    text = stringResource(R.string.ui_traildetailscreen_h_jdeprofil_199),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                ElevationProfileCanvas(
                                    points = points,
                                    gainM = elevationGain,
                                    lossM = elevationLoss,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                )
                            }

                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_underlag_200),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            SurfaceMixBar(surfaceMix = surfaceMix)

                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_forsyning_p_ruten_201),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isSyncingPois) {
                                Text(
                                    text = stringResource(R.string.ui_traildetailscreen_henter_data_202),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("supply_gaps_loading_text")
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.ui_traildetailscreen_l_ngste_str_k_uden_vand__203, String.format(Locale.GERMANY, "%.1f", longestGapWithoutWaterKm), String.format(Locale.GERMANY, "%.1f", longestGapWithoutOvernightKm)),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("supply_gaps_summary_text")
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (isSyncingPois && overnightSpots.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_henter_data_202),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else if (overnightSpots.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_ingen_overnatningssteder_reg_204),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(overnightSpots, key = { it.id }) { spot ->
                            OvernightSpotCard(spot = spot)
                        }
                    }
                }

                2 -> {
                    val combinedPois = (waterAndToilets + shops).sortedBy { it.kmOnRoute }
                    if (isSyncingPois && combinedPois.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_henter_data_202),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else if (combinedPois.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_ingen_vandposter_eller_butik_205),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(combinedPois, key = { it.id }) { poi ->
                            PoiItemCard(poi = poi)
                        }
                    }
                }

                3 -> {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_vejrudsigt_open_meteo_206),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            WeatherForecastCard(
                                title = stringResource(R.string.trail_detail_weather_start_fmt, points.firstOrNull()?.let { "km 0" } ?: ""),
                                weather = weatherStart
                            )

                            WeatherForecastCard(
                                title = stringResource(R.string.trail_detail_weather_end_fmt, points.lastOrNull()?.let { "km ${String.format(Locale.US, "%.1f", totalKm)}" } ?: ""),
                                weather = weatherEnd
                            )
                        }
                    }
                }

                4 -> {
                    if (transports.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_ingen_togstationer_eller_f_r_207),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(transports, key = { it.id }) { trans ->
                            PoiItemCard(poi = trans)
                        }
                    }
                }
            }
        }

        // Fixed Bottom Bar with "Start nu" next to "Planlæg tur"
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onPlanTrip(trailId) },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("plan_trip_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_plan_trip),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        viewModel.startTrailNow(onPlanCreated = {})
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("start_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.trail_detail_start_now),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        startNowStatusMessage?.let { statusMsg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("start_now_status_message")
                )
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = { onPlanTrip(trailId) },
                    modifier = Modifier.testTag("start_now_pick_another_start_button")
                ) {
                    Text(
                        text = "Vælg andet startpunkt",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Round 10B Item 6: Show the relation's description, note, operator, website and distance tags when present,
 * labelled "Ifølge OpenStreetMap".
 */
@Composable
fun OsmRelationTagsCard(
    osmTags: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val description = (osmTags["description:da"] ?: osmTags["description"])?.trim()?.takeIf { it.isNotEmpty() }
    val note = osmTags["note"]?.trim()?.takeIf { it.isNotEmpty() }
    val operator = osmTags["operator"]?.trim()?.takeIf { it.isNotEmpty() }
    val website = (osmTags["website"] ?: osmTags["contact:website"] ?: osmTags["url"])?.trim()?.takeIf { it.isNotEmpty() }
    val distance = osmTags["distance"]?.trim()?.takeIf { it.isNotEmpty() }

    if (description == null && note == null && operator == null && website == null && distance == null) {
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

            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("trail_osm_tag_description")
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
