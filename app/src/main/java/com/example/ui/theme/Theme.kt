package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldHigh,
    onPrimary = Color(0xFF022013),
    primaryContainer = EmeraldDark,
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = AmberTrail,
    onSecondary = Color(0xFF451A03),
    secondaryContainer = Color(0xFF78350F),
    onSecondaryContainer = AmberLight,
    tertiary = NordicSky,
    onTertiary = Color(0xFF082F49),
    background = ForestDarkBg,
    onBackground = TextPrimary,
    surface = ForestSurface,
    onSurface = TextPrimary,
    surfaceVariant = ForestSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = OffTrailAlert,
    onError = Color.White
)

@Composable
fun DanskStiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    DanskStiTheme(content = content)
}
