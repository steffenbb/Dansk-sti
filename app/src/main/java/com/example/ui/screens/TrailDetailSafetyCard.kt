package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import com.example.data.repository.RestrictedAreaKind
import com.example.ui.theme.RestrictedMilitaryColor
import com.example.ui.theme.RestrictedReserveColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.util.SafetyAccessItem

/** Phase D5: "Sikkerhed og adgang" on the Oversigt tab, above "Stien undervejs". */
@Composable
fun TrailSafetyAccessCard(
    items: List<SafetyAccessItem>,
    areaDataMissing: Boolean,
    onItemClick: ((SafetyAccessItem) -> Unit)? = null
) {
    val warningColor = DanskStiTheme.colors.warning
    Card(
        shape = com.example.ui.theme.TileShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().testTag("trail_safety_access_card")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.trail_safety_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            items.forEach { item ->
                // UI audit batch 3: a coloured edge per kind, matching the map (military red, reserve orange, other warnings amber)
                val edgeColor = when (item.areaCrossing?.area?.kind) {
                    RestrictedAreaKind.MILITARY -> RestrictedMilitaryColor
                    RestrictedAreaKind.WILDLIFE_RESERVE -> RestrictedReserveColor
                    null -> warningColor
                }
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = (if (onItemClick != null) Modifier.fillMaxWidth().clickable { onItemClick(item) } else Modifier)
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(edgeColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (item.isRestrictedArea) Icons.Default.Shield else Icons.Default.Warning,
                        contentDescription = null,
                        tint = edgeColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            when {
                areaDataMissing -> Text(
                    text = stringResource(R.string.trail_safety_area_data_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_safety_area_data_missing")
                )
                items.isEmpty() -> Text(
                    text = stringResource(R.string.trail_safety_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_safety_empty")
                )
            }
            Text(
                text = stringResource(R.string.path_details_source_osm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}
