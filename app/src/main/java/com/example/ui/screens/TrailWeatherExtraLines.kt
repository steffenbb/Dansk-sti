package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.TrailWeatherInfo
import com.example.ui.theme.DanskStiTheme
import com.example.util.WindAssessment
import kotlin.math.abs
import kotlin.math.roundToInt

/** Phase D7: UV text for a UV index ≥ 3, or null. */
internal fun uvLevelRes(uv: Double): Int? = when {
    uv >= 8.0 -> R.string.weather_uv_very_high_fmt
    uv >= 6.0 -> R.string.weather_uv_high_fmt
    uv >= 3.0 -> R.string.weather_uv_moderate_fmt
    else -> null
}

/** Phase D7: wind with direction, feels-like, UV and thunder lines; each only when its data exists. */
@Composable
fun TrailWeatherExtraLines(weather: TrailWeatherInfo) {
    val appLocale = com.example.ui.components.rememberAppLocale()
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        weather.windSpeedMs?.let { speed ->
            val speedText = String.format(appLocale, "%.1f", speed)
            Text(
                text = weather.windDirectionDeg?.let {
                    stringResource(R.string.weather_wind_from_fmt, speedText, WindAssessment.compassDanish(it))
                } ?: stringResource(R.string.weather_wind_speed_fmt, speedText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("trail_weather_wind")
            )
        }
        val temp = weather.tempC
        val feels = weather.apparentTempC
        if (temp != null && feels != null && abs(feels - temp) >= 2.0) {
            Text(
                text = stringResource(R.string.weather_feels_like_fmt, feels.roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("trail_weather_feels_like")
            )
        }
        weather.uvIndexMaxToday?.let { uv ->
            uvLevelRes(uv)?.let { res ->
                Text(
                    text = stringResource(res, String.format(appLocale, "%.0f", uv)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uv >= 6.0) DanskStiTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("trail_weather_uv")
                )
            }
        }
        weather.severeAlert?.let { alert ->
            Text(
                text = alert,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = DanskStiTheme.colors.danger,
                modifier = Modifier.testTag("trail_weather_alert")
            )
        }
    }
}
