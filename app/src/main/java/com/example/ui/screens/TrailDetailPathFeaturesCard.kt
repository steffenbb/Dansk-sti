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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
fun PathFeaturesAlongTrailCard(
    summary: com.example.util.PathFeatureSummary,
    warnings: List<com.example.util.PathFeatureWarning>,
    pathTagsVersion: Int,
    onWarningClick: ((com.example.util.PathFeatureWarning) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val appLocale = com.example.ui.components.rememberAppLocale()
    var showAllWarnings by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    fun fmtKm(km: Double): String = com.example.ui.components.formatDanishKm(km, decimals = 1, locale = appLocale)

    val summaryLines = buildList {
        if (summary.stepsCount > 0) {
            add("steps" to stringResource(R.string.path_summary_steps_fmt, summary.stepsCount))
        }
        if (summary.fordCount > 0) {
            add("fords" to stringResource(R.string.path_summary_fords_fmt, summary.fordCount))
        }
        if (summary.privateKm >= 0.05) {
            add("private" to stringResource(R.string.path_summary_private_km_fmt, fmtKm(summary.privateKm)))
        }
        if (summary.horseKm >= 0.05) {
            add("horse" to stringResource(R.string.path_summary_horse_km_fmt, fmtKm(summary.horseKm)))
        }
        if (summary.bikeKm >= 0.05) {
            add("bike" to stringResource(R.string.path_summary_bike_km_fmt, fmtKm(summary.bikeKm)))
        }
        if (summary.litKm >= 0.05) {
            add("lit" to stringResource(R.string.path_summary_lit_km_fmt, fmtKm(summary.litKm)))
        }
        if (summary.roughOrUnclearKm >= 0.05) {
            add("rough" to stringResource(R.string.path_summary_rough_km_fmt, fmtKm(summary.roughOrUnclearKm)))
        }
        if (summary.sandKm >= 0.05) {
            add("sand" to stringResource(R.string.path_summary_sand_km_fmt, fmtKm(summary.sandKm)))
        }
    }

    val sortedWarnings = androidx.compose.runtime.remember(warnings) {
        warnings.sortedBy { it.startKm }
    }
    val visibleWarnings = if (showAllWarnings || sortedWarnings.size <= 8) {
        sortedWarnings
    } else {
        sortedWarnings.take(8)
    }

    Card(
        shape = com.example.ui.theme.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("path_features_along_trail_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.path_details_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("path_features_along_trail_title")
            )

            if (summaryLines.isEmpty() && sortedWarnings.isEmpty()) {
                if (pathTagsVersion < 1) {
                    Text(
                        text = stringResource(R.string.path_details_empty_upgrade_next),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("path_features_empty_pending_upgrade")
                    )
                } else {
                    Text(
                        text = stringResource(R.string.path_details_empty_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("path_features_empty_no_warnings")
                    )
                }
            } else {
                if (summaryLines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((key, text) in summaryLines) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("path_feature_summary_row_$key")
                            )
                        }
                    }
                }

                if (visibleWarnings.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        visibleWarnings.forEachIndexed { idx, warning ->
                            val kmStr = fmtKm(warning.startKm)
                            val rowText = stringResource(R.string.path_warning_row_km_fmt, kmStr, warning.title)
                            val rowMod = if (onWarningClick != null) {
                                Modifier
                                    .fillMaxWidth()
                                    .clip(com.example.ui.theme.ChipShape)
                                    .clickable { onWarningClick(warning) }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            } else {
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            }
                            Row(
                                modifier = rowMod.testTag("path_feature_warning_row_$idx"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(DanskStiTheme.colors.warning, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = rowText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (!showAllWarnings && sortedWarnings.size > 8) {
                            androidx.compose.material3.TextButton(
                                onClick = { showAllWarnings = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("path_features_show_all_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.path_warnings_show_all_fmt, sortedWarnings.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.path_details_source_osm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.testTag("path_features_source_osm")
            )
        }
    }
}
