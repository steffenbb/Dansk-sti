package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.DanskStiDatabase
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.access.AccessPolygon
import com.example.ui.theme.DanskStiTheme
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Round 10A Item 1 & Round 11 Item 9/10:
 * Shared PoiInfoSheet component used on every map (Kort, Udforsk map, trail page, full-screen map, navigation, planner).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PoiInfoSheet(
    poiData: PoiInfoSheetData,
    liveUserLocation: GeoPoint?,
    isLocationFresh: Boolean = true,
    onDismiss: () -> Unit,
    onRouteHere: (lat: Double, lng: Double, name: String) -> Unit,
    onAddToRoute: (lat: Double, lng: Double, name: String) -> Unit,
    onOvernightHereInPlanner: (() -> Unit)? = null,
    onSavedPoint: ((UserCustomWaypointEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stiColors = DanskStiTheme.colors
    var isSaved by remember(poiData.id) { mutableStateOf(false) }
    val unknownDistLabel = stringResource(R.string.poi_sheet_unknown)

    val distanceText = remember(liveUserLocation, isLocationFresh, poiData.lat, poiData.lng, unknownDistLabel) {
        if (liveUserLocation == null || !isLocationFresh) {
            unknownDistLabel
        } else {
            val meters = GeoUtils.distanceMeters(
                liveUserLocation.latitude,
                liveUserLocation.longitude,
                poiData.lat,
                poiData.lng
            )
            if (meters < 1000.0) {
                "${meters.roundToInt()} m"
            } else {
                String.format(Locale.GERMANY, "%.1f km", meters / 1000.0)
            }
        }
    }

    val iconVector = when (poiData.rawType.lowercase()) {
        "shelter" -> Icons.Default.NightShelter
        "tent", "fri_teltning" -> Icons.Default.Forest
        "water", "drinking_water", "vand" -> Icons.Default.WaterDrop
        "toilet", "toilets" -> Icons.Default.Wc
        "shop", "supermarket", "indkoeb" -> Icons.Default.ShoppingCart
        "transit", "station", "ferry" -> Icons.Default.DirectionsBus
        else -> Icons.Default.Place
    }

    val badgeColor = when (poiData.rawType.lowercase()) {
        "shelter", "tent", "fri_teltning" -> stiColors.overnight
        "water", "drinking_water", "vand" -> stiColors.water
        "toilet", "toilets" -> MaterialTheme.colorScheme.secondary
        "shop", "supermarket", "indkoeb" -> stiColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("poi_info_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("kort_poi_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColor.copy(alpha = 0.16f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = poiData.typeLabel,
                                tint = badgeColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = poiData.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .testTag("poi_info_name")
                                .testTag("kort_poi_sheet_title")
                        )
                        Text(
                            text = poiData.typeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = badgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("poi_info_type")
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("poi_info_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.poi_sheet_close_cd)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.poi_sheet_distance_from_me),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("poi_info_distance")
                        )
                    }

                    if (!poiData.capacityText.isNullOrBlank() || !poiData.bookingText.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        val capBookingLine = listOfNotNull(poiData.capacityText, poiData.bookingText).joinToString(" · ")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = capBookingLine,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (poiData.bookingRequired == true) stiColors.warning else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("poi_info_capacity_booking")
                            )
                            if (!poiData.bookingUrl.isNullOrBlank()) {
                                val noBrowserMsg = stringResource(R.string.poi_sheet_no_browser_found)
                                val cannotOpenMsg = stringResource(R.string.poi_sheet_cannot_open_link)
                                TextButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(poiData.bookingUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            Toast.makeText(context, noBrowserMsg, Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) {
                                            Toast.makeText(context, cannotOpenMsg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("poi_info_booking_link")
                                ) {
                                    Text(stringResource(R.string.poi_sheet_open_link), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (poiData.facilities.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("poi_info_facilities")
                ) {
                    Text(
                        text = stringResource(R.string.poi_sheet_facilities_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        poiData.facilities.forEach { fac ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = fac,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!poiData.description.isNullOrBlank()) {
                Text(
                    text = poiData.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.poi_sheet_source_fmt, poiData.sourceAndDateLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("poi_info_source_date")
            )

            if (onOvernightHereInPlanner != null &&
                (poiData.rawType == "shelter" || poiData.rawType == "tent" || poiData.rawType == "fri_teltning")
            ) {
                Button(
                    onClick = {
                        onOvernightHereInPlanner()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = stiColors.overnight),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("btn_poi_overnat_her")
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.poi_sheet_overnight_here), fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onRouteHere(poiData.lat, poiData.lng, poiData.name)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_route_here_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.poi_sheet_route_here),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("kort_poi_route_here_button")
                    )
                }

                OutlinedButton(
                    onClick = {
                        onAddToRoute(poiData.lat, poiData.lng, poiData.name)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_add_to_route_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.poi_sheet_add_to_route),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("btn_poi_tilfoej_som_stop")
                    )
                }
            }

            val savedToastMsg = stringResource(R.string.poi_sheet_saved_toast)
            val shareChooserTitle = stringResource(R.string.poi_sheet_share_chooser_title)
            val noShareAppMsg = stringResource(R.string.poi_sheet_no_share_app)
            val cannotShareMsg = stringResource(R.string.toast_cannot_open_share)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val entity = UserCustomWaypointEntity(
                                lat = poiData.lat,
                                lng = poiData.lng,
                                name = poiData.name,
                                note = "${poiData.typeLabel} (${poiData.sourceAndDateLabel})"
                            )
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    DanskStiDatabase.getInstance(context).dao().insertUserCustomWaypoint(entity)
                                }
                            }
                            isSaved = true
                            onSavedPoint?.invoke(entity)
                            Toast.makeText(context, savedToastMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_save_button")
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSaved) stringResource(R.string.poi_sheet_saved_label) else stringResource(R.string.poi_sheet_save_label),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val osmUrl = String.format(
                            Locale.US,
                            "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=16/%.5f/%.5f",
                            poiData.lat,
                            poiData.lng,
                            poiData.lat,
                            poiData.lng
                        )
                        val shareBody = buildString {
                            appendLine("${poiData.name} (${poiData.typeLabel})")
                            poiData.capacityText?.let { appendLine("Kapacitet: $it") }
                            poiData.bookingText?.let { appendLine("Booking: $it") }
                            if (poiData.facilities.isNotEmpty()) {
                                appendLine("Faciliteter: ${poiData.facilities.joinToString(", ")}")
                            }
                            appendLine("Koordinater: ${String.format(Locale.US, "%.5f, %.5f", poiData.lat, poiData.lng)}")
                            append("Kort: $osmUrl")
                        }
                        try {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, poiData.name)
                                putExtra(Intent.EXTRA_TEXT, shareBody)
                            }
                            val chooser = Intent.createChooser(sendIntent, "$shareChooserTitle: ${poiData.name}")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, noShareAppMsg, Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, cannotShareMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.poi_sheet_share_label),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Round 10A Item 1:
 * Tapping an Adgang area shows its access rules on every map, not only in the planner.
 */
@Composable
fun AccessPolygonRulesDialog(
    polygon: AccessPolygon,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("access_polygon_rules_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(polygon.type.fillColorArgb), CircleShape)
                    .border(2.dp, Color(polygon.type.strokeColorArgb), CircleShape)
            )
        },
        title = {
            Text(
                text = polygon.name ?: polygon.type.title,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.testTag("access_polygon_rules_title")
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = stringResource(R.string.poi_sheet_type_fmt, polygon.type.title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!polygon.operatorOrOwner.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.poi_sheet_owner_fmt, polygon.operatorOrOwner),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.poi_sheet_source_fmt, polygon.source),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = polygon.type.fullRulesDanish,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("access_polygon_rules_body")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("access_polygon_rules_close")
            ) {
                Text(stringResource(R.string.poi_sheet_close_cd))
            }
        }
    )
}
