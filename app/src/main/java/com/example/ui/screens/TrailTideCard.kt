package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import com.example.ui.theme.revealOnce
import androidx.compose.foundation.layout.height
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
import com.example.ui.viewmodel.TrailTideInfo

/** Phase E3: "Tidevand" on the trail Vejr tab (only shown when tides are relevant). */
@Composable
fun TrailTideCard(info: TrailTideInfo) {
    val appLocale = com.example.ui.components.rememberAppLocale()
    Card(
        shape = com.example.ui.theme.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().testTag("trail_tide_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tide_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            info.days.forEach { (offset, line) ->
                Text(
                    text = stringResource(if (offset == 0) R.string.tide_today_fmt else R.string.tide_tomorrow_fmt, line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                info.dayEvents[offset]?.takeIf { it.isNotEmpty() }?.let { TideDayTimeline(it) }
            }
            Text(
                text = stringResource(R.string.tide_station_fmt, info.stationName, String.format(appLocale, "%.1f", info.distanceKm)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.tide_source_dmi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

/**
 * UI audit batch 3: the day's real DMI high and low times as markers on a 24-hour line
 * (filled = high water, ring = low water). No curve is drawn between them, since that would be
 * invented data (RULES.md 1–2). Decorative only: the text line above carries the same times.
 */
@Composable
internal fun TideDayTimeline(events: List<com.example.data.repository.TideEvent>) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val high = MaterialTheme.colorScheme.primary
    val low = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minutes = androidx.compose.runtime.remember(events) {
        events.map { e ->
            val cal = com.example.util.CopenhagenTime.calendar(e.timeMs)
            e.isHigh to (cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE))
        }
    }
    Column(modifier = Modifier.fillMaxWidth().testTag("trail_tide_timeline")) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(16.dp).revealOnce()) {
            val y = size.height / 2f
            drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2.dp.toPx())
            for (h in 0..4) {
                val x = size.width * h / 4f
                drawLine(track, androidx.compose.ui.geometry.Offset(x, y - 3.dp.toPx()), androidx.compose.ui.geometry.Offset(x, y + 3.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
            val r = 5.dp.toPx()
            minutes.forEach { (isHigh, min) ->
                val x = (size.width * min / (24f * 60f)).coerceIn(r, size.width - r)
                val c = androidx.compose.ui.geometry.Offset(x, y)
                if (isHigh) drawCircle(high, r, c)
                else {
                    drawCircle(low, r - 1.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
        }
        androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}
