package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TabularBodyStyle
import com.example.util.GeoUtils

/** A fix older than this is flagged in red on the SOS sheet. */
const val SOS_STALE_FIX_MS = 5 * 60_000L

/**
 * "±12 m · 2 min gammel": how far to trust the position on the SOS sheet. Null without a fix.
 * Uses the app locale's strings.
 */
@Composable
internal fun sosFixQualityText(accuracyM: Float?, fixTimeMs: Long?, nowMs: Long): String? {
    if (fixTimeMs == null || fixTimeMs <= 0L) return null
    val ageMin = ((nowMs - fixTimeMs).coerceAtLeast(0L) / 60_000L).toInt()
    val age = if (ageMin < 1) stringResource(R.string.sos_fix_age_now) else stringResource(R.string.sos_fix_age_min_fmt, ageMin)
    return if (accuracyM != null && accuracyM > 0f) stringResource(R.string.sos_fix_quality_fmt, kotlin.math.round(accuracyM).toInt(), age) else age
}

/**
 * The 112 sheet: position to read to the emergency operator, its accuracy and age, a call button
 * and the nearest defibrillators. Shared by navigation and Kort (audit fix batch 3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencySosSheet(
    lat: Double?,
    lng: Double?,
    accuracyM: Float?,
    fixTimeMs: Long?,
    onDismiss: () -> Unit,
    nearestAeds: List<com.example.ui.viewmodel.NearbyAed> = emptyList(),
    nowMs: Long = System.currentTimeMillis()
) {
    val emergencyInfo = remember(lat, lng) {
        if (lat != null && lng != null) com.example.data.model.DanishEmergencyInfo(
            decimalCoords = GeoUtils.formatDecimalWgs84(lat, lng),
            spokenWgs84 = GeoUtils.formatDanishSpokenWgs84(lat, lng)
        ) else null
    }
    val sosHaptics = com.example.ui.components.rememberHaptics()
    val context = LocalContext.current
    val danskStiColors = DanskStiTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = danskStiColors.danger.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = danskStiColors.danger,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.ui_navigationscreen_dansk_n_dkald_112_74),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = danskStiColors.danger
            )

            Card(
                shape = com.example.ui.theme.TileShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.ui_navigationscreen_din_n_jagtige_gps_position_75),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val coordsText = emergencyInfo?.decimalCoords ?: stringResource(R.string.nav_sos_waiting_gps)
                    Text(
                        text = coordsText,
                        style = MaterialTheme.typography.titleMedium.merge(
                            TabularBodyStyle.copy(
                                fontWeight = FontWeight.Bold
                            )
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("sos_coordinates")
                    )
                    // How far to trust the position: accuracy and age of the fix (audit fix batch 3)
                    sosFixQualityText(accuracyM, fixTimeMs, nowMs)?.let { quality ->
                        Text(
                            text = quality,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (fixTimeMs != null && nowMs - fixTimeMs > SOS_STALE_FIX_MS) danskStiColors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("sos_fix_quality")
                        )
                    }
                    emergencyInfo?.spokenWgs84?.let { spoken ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.ui_navigationscreen_l_ses_h_jt_til_alarmcent_76, spoken),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    sosHaptics(com.example.ui.components.HapticKind.HEAVY, "sos_112")
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:112")
                    }
                    try {
                        context.startActivity(dialIntent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.nav_sos_no_phone_app),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.nav_sos_phone_app_error),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                shape = com.example.ui.theme.CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("call_112_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ui_navigationscreen_ring_112_nu_77),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError
                )
            }

            NavigationSosAedSection(aeds = nearestAeds)

            OutlinedButton(
                onClick = onDismiss,
                shape = com.example.ui.theme.CardShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.ui_navigationscreen_luk_n_dsystem_78))
            }
        }
    }
}


/**
 * The 112 sheet outside navigation (Kort tab). Takes its own position, with lifecycle-aware location
 * updates while the sheet is open, and the nearest stored defibrillators.
 */
@Composable
fun KortSosSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locationState = com.example.ui.map.rememberLiveMapLocationState(context)
    val loc = locationState.location
    var aeds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.example.ui.viewmodel.NearbyAed>>(emptyList()) }
    // Recompute when the position has moved about 100 m (rounded to 0.001°)
    val roundedKey = loc?.let { (it.latitude * 1000).toInt() to (it.longitude * 1000).toInt() }
    androidx.compose.runtime.LaunchedEffect(roundedKey) {
        val l = loc ?: return@LaunchedEffect
        aeds = runCatching {
            val stored = com.example.DanskStiApp.instance.repository.getPoisByType("aed")
            com.example.ui.viewmodel.NavigationNearbyAeds.nearest(l.latitude, l.longitude, emptyList(), stored)
        }.getOrDefault(emptyList())
    }
    EmergencySosSheet(
        lat = loc?.latitude,
        lng = loc?.longitude,
        accuracyM = locationState.accuracyMeters,
        fixTimeMs = (if (locationState.fixTimestampMs > 0L) locationState.fixTimestampMs else loc?.time)?.takeIf { it > 0L },
        onDismiss = onDismiss,
        nearestAeds = aeds,
        nowMs = locationState.nowMs
    )
}
