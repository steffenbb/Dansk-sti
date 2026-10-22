package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Requirement A:
// Light theme by default, real dark theme.
// Primary forest green #2E6B3F, primary container #DCEFE0,
// accent amber #F2A33A, error #C62828,
// surfaces white (#FFFFFF) / #F6F7F5 in light and #121614 / #1B201D in dark.
val LightColorScheme = lightColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = ForestGreenContainerLight,
    onPrimaryContainer = ForestGreenOnContainerLight,
    secondary = AccentAmber,
    onSecondary = Color(0xFF121614),
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = WaterBlue,
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderSubtleLight,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = AlertRedContainer,
    onErrorContainer = ErrorRed
)

val DarkColorScheme = darkColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = ForestGreenDarkContainer,
    onPrimaryContainer = ForestGreenOnDarkContainer,
    secondary = AccentAmber,
    onSecondary = Color(0xFF121614),
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = WaterBlue,
    onTertiary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderSubtleDark,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF491212),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun DanskStiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    DanskStiTheme(darkTheme = darkTheme, content = content)
}
