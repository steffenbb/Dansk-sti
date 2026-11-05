package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.data.remote.TrailWeatherInfo
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.OvernightSpotItem
import com.example.ui.viewmodel.PoiSpotItem
import java.util.Locale

@Composable
fun StatCardItem(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = com.example.ui.theme.TabularBodyStyle.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun RoundActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
    progressFraction: Float? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (progressFraction != null) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("${testTag}_progress"),
                        strokeWidth = 3.dp,
                        color = tint
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${testTag}_label")
        )
    }
}

@Composable
fun TrailOfflineDownloadDialog(
    trailName: String,
    estimate: com.example.util.OfflineEstimateResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val sizeStr = com.example.util.OfflineDownloadManager.formatSizeMbDanish(estimate.estimatedSizeBytes)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("trail_offline_confirm_dialog"),
        title = {
            Text(
                text = stringResource(R.string.trail_detail_action_download_offline),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Hent offline-kort for $trailName ($sizeStr · ${estimate.tileCount} kortfliser)?",
                modifier = Modifier.testTag("trail_offline_confirm_text")
            )
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_trail_offline_download_button")
            ) {
                Text(stringResource(R.string.trail_detail_action_download_offline))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_trail_offline_download_button")
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
fun ElevationProfileCanvas(
    points: List<GeoPointDto>,
    gainM: Int,
    lossM: Int,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val warningColor = DanskStiTheme.colors.warning
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.ui_traildetailscreen_m_stigning_208, gainM), fontSize = 12.sp, color = lineColor, fontWeight = FontWeight.SemiBold)
                Text(text = stringResource(R.string.ui_traildetailscreen_m_fald_209, lossM), fontSize = 12.sp, color = warningColor, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(modifier = Modifier.fillMaxSize()) {
                val elePoints = points.mapNotNull { it.ele }
                if (elePoints.size < 2) return@Canvas

                val minEle = elePoints.minOrNull() ?: 0.0
                val maxEle = elePoints.maxOrNull() ?: 0.0
                val dEle = (maxEle - minEle).coerceAtLeast(10.0)

                val step = size.width / (points.size - 1)
                val path = Path()

                points.forEachIndexed { i, pt ->
                    val ele = pt.ele ?: minEle
                    val x = i * step
                    val y = ((1.0 - (ele - minEle) / dEle) * size.height).toFloat().coerceIn(0f, size.height)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun SurfaceMixBar(surfaceMix: Map<String, Int>) {
    if (surfaceMix.isEmpty()) {
        Text(
            text = stringResource(R.string.way_info_unknown_summary),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("trail_surface_unknown_text")
        )
        return
    }
    val stiPct = (surfaceMix["Sti"] ?: 0).coerceAtLeast(0)
    val grusPct = (surfaceMix["Grus"] ?: 0).coerceAtLeast(0)
    val asfaltPct = (surfaceMix["Asfalt"] ?: 0).coerceAtLeast(0)
    val ukendtPct = (surfaceMix["Ukendt"] ?: 0).coerceAtLeast(0)

    val validSti = if (stiPct > 0) stiPct.toFloat() else 0.001f
    val validGrus = if (grusPct > 0) grusPct.toFloat() else 0.001f
    val validAsfalt = if (asfaltPct > 0) asfaltPct.toFloat() else 0.001f
    val stiColor = MaterialTheme.colorScheme.primary
    val grusColor = DanskStiTheme.colors.warning
    val asfaltColor = MaterialTheme.colorScheme.outline
    val ukendtColor = MaterialTheme.colorScheme.surfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
        ) {
            Box(
                modifier = Modifier
                    .weight(validSti)
                    .fillMaxHeight()
                    .background(stiColor)
            )
            Box(
                modifier = Modifier
                    .weight(validGrus)
                    .fillMaxHeight()
                    .background(grusColor)
            )
            Box(
                modifier = Modifier
                    .weight(validAsfalt)
                    .fillMaxHeight()
                    .background(asfaltColor)
            )
            if (ukendtPct > 0) {
                Box(
                    modifier = Modifier
                        .weight(ukendtPct.toFloat())
                        .fillMaxHeight()
                        .background(ukendtColor)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(stiColor, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ui_traildetailscreen_sti_210, stiPct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(grusColor, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ui_traildetailscreen_grus_211, grusPct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(asfaltColor, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.ui_traildetailscreen_asfalt_212, asfaltPct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ukendtPct > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(ukendtColor, CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.trail_surface_ukendt_pct, ukendtPct),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("trail_surface_ukendt_pct")
                    )
                }
            }
        }
    }
}

@Composable
fun OvernightSpotCard(spot: OvernightSpotItem) {
    val context = LocalContext.current
    val warningColor = DanskStiTheme.colors.warning
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (spot.type == "SHELTER") MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else warningColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (spot.type == "SHELTER") Icons.Default.Cabin else Icons.Default.Forest,
                            contentDescription = null,
                            tint = if (spot.type == "SHELTER") MaterialTheme.colorScheme.primary else warningColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = spot.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (spot.detourMeters > 50) "${spot.detourMeters.toInt()} m afstikker" else "Direkte på ruten",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (spot.bookingRequired) {
                        Text(
                            text = stringResource(R.string.ui_traildetailscreen_kr_ver_booking_213),
                            fontSize = 12.sp,
                            color = warningColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = stringResource(R.string.ui_traildetailscreen_km_214, String.format(Locale("da", "DK"), "%.1f", spot.kmOnRoute)),
                        style = com.example.ui.theme.TabularStatsTextStyle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (spot.bookingRequired) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ui_traildetailscreen_tjek_ledighed_hos_udbyder_215),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    val targetUrl = if (!spot.bookingUrl.isNullOrBlank()) {
                        spot.bookingUrl
                    } else {
                        "https://udinaturen.dk/soeg?q=${Uri.encode(spot.name)}"
                    }
                    val buttonLabel = if (!spot.bookingUrl.isNullOrBlank()) "Åbn booking" else "Book via udinaturen.dk"

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "Ingen app fundet til at åbne linket", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ingen app fundet til at åbne linket", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = warningColor),
                        border = BorderStroke(1.dp, warningColor),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .testTag("shelter_booking_button_${spot.id}")
                    ) {
                        Text(text = buttonLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PoiItemCard(
    poi: PoiSpotItem,
    onRouteHerTil: (() -> Unit)? = null
) {
    val icon = when (poi.type) {
        "water" -> Icons.Default.LocalDrink
        "toilet" -> Icons.Default.Wc
        "shop" -> Icons.Default.Storefront
        "station" -> Icons.Default.DirectionsTransit
        "ferry" -> Icons.Default.DirectionsTransit
        else -> Icons.Default.Place
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (poi.detourMeters > 50) {
                    Text(
                        text = stringResource(R.string.ui_traildetailscreen_m_afstikker_216, poi.detourMeters.toInt()),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = stringResource(R.string.ui_traildetailscreen_km_214, String.format(Locale("da", "DK"), "%.1f", poi.kmOnRoute)),
                style = com.example.ui.theme.TabularStatsTextStyle,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (onRouteHerTil != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onRouteHerTil,
                    modifier = Modifier.size(32.dp).testTag("poi_route_here_${poi.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = stringResource(R.string.ui_droppinbottomsheet_rute_hertil_14),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherForecastCard(
    title: String,
    weather: TrailWeatherInfo?
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (weather == null) {
                Text(stringResource(R.string.ui_traildetailscreen_venter_p_vejrdata_217), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Thermostat,
                            contentDescription = null,
                            tint = DanskStiTheme.colors.warning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ui_traildetailscreen_c_218, weather.tempC?.toInt() ?: "--"),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.ui_traildetailscreen_mm_219, weather.precipitationMm ?: 0.0),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = weather.conditionDescription ?: "Skyet",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
