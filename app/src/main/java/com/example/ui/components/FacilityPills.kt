package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One facility: icon plus the same text that was shown before (UI audit batch 3). */
data class FacilityPill(val icon: ImageVector, val text: String)

/**
 * Small icon pills for facilities (fire, tap, toilet, shower). Same data as the former
 * "Bålplads · Vandhane" line; TalkBack still reads that line as one sentence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FacilityPillRow(pills: List<FacilityPill>, modifier: Modifier = Modifier) {
    val spoken = pills.joinToString(" · ") { it.text }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken }
    ) {
        pills.forEach { pill ->
            Surface(
                shape = com.example.ui.theme.ChipShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = pill.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp)
                    )
                    Text(
                        text = pill.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/** A small icon in front of a line of facts (weather, day card); the line's text is unchanged. */
@Composable
fun IconFactLine(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(6.dp))
        content()
    }
}
