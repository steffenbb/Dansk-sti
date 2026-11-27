package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.formatDanishKm
import com.example.ui.viewmodel.NearbyAed

/** Phase C8: "Nærmeste hjertestarter" in the SOS sheet (shown below the 112 button). */
@Composable
fun NavigationSosAedSection(aeds: List<NearbyAed>) {
    Card(
        shape = com.example.ui.theme.TileShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("sos_nearest_aed")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.nav_sos_nearest_aed_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (aeds.isEmpty()) {
                Text(
                    text = stringResource(R.string.nav_sos_no_aed_nearby),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sos_no_aed")
                )
            } else {
                aeds.forEach { aed ->
                    val distance = if (aed.distanceMeters < 1000.0) {
                        stringResource(R.string.nav_sos_aed_distance_m_fmt, aed.distanceMeters.toInt())
                    } else {
                        stringResource(R.string.nav_sos_aed_distance_km_fmt, formatDanishKm(aed.distanceMeters / 1000.0, 1))
                    }
                    Column {
                        Text(
                            text = "${aed.name} · $distance",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        aed.locationText?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = stringResource(R.string.poi_aed_location_fmt, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
