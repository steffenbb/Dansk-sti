package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class DanskStiColors(
    val warning: Color,
    val danger: Color,
    val success: Color,
    val plannedRoute: Color,
    val walkedRoute: Color,
    val routeCasing: Color,
    val water: Color,
    val offRoute: Color,
    val overnight: Color
)

val LightDanskStiColors = DanskStiColors(
    warning = Color(0xFFF2A33A),
    danger = Color(0xFFC62828),
    success = Color(0xFF2E6B3F),
    plannedRoute = Color(0xFF1E88E5),
    walkedRoute = Color(0xFF888888),
    routeCasing = Color(0xFFFFFFFF),
    water = Color(0xFFA3CDE3),
    offRoute = Color(0xFFC62828),
    overnight = Color(0xFFD84315)
)

val DarkDanskStiColors = DanskStiColors(
    warning = Color(0xFFFFB74D),
    danger = Color(0xFFEF5350),
    success = Color(0xFF66BB7E),
    plannedRoute = Color(0xFF64B5F6),
    walkedRoute = Color(0xFF9E9E9E),
    routeCasing = Color(0xFF14181C),
    water = Color(0xFF1F4E6E),
    offRoute = Color(0xFFEF5350),
    overnight = Color(0xFFFF8A65)
)

val LocalDanskStiColors = staticCompositionLocalOf { LightDanskStiColors }
val LocalIsDarkTheme = staticCompositionLocalOf { false }

object DanskStiTheme {
    val colors: DanskStiColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDanskStiColors.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDarkTheme.current

    val windowSizeClass: WindowSizeClass
        @Composable
        @ReadOnlyComposable
        get() = currentWindowSizeClass()
}

// Requirement A:
// Light theme by default, real dark theme.
// Primary forest green #2E6B3F, primary container #DCEFE0,
// accent amber #F2A33A, error #C62828,
// surfaces white (#FFFFFF) / #F6F7F5 in light and #121614 / #1B201D in dark.
val LightColorScheme = lightColorScheme(
    primary = ForestGreenPrimary,
    onPrimary = SurfaceLight,
    primaryContainer = ForestGreenContainerLight,
    onPrimaryContainer = ForestGreenOnContainerLight,
    secondary = AccentAmber,
    onSecondary = TextPrimaryLight,
    secondaryContainer = SurfaceVariantLight,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = WaterBlue,
    onTertiary = SurfaceLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderSubtleLight,
    error = ErrorRed,
    onError = SurfaceLight,
    errorContainer = AlertRedContainer,
    onErrorContainer = ErrorRed
)

val DarkColorScheme = darkColorScheme(
    primary = ForestGreenDarkPrimary,
    onPrimary = TextPrimaryLight,
    primaryContainer = ForestGreenDarkContainer,
    onPrimaryContainer = ForestGreenOnDarkContainer,
    secondary = AccentAmber,
    onSecondary = TextPrimaryLight,
    secondaryContainer = SurfaceVariantDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = NordicSky,
    onTertiary = TextPrimaryLight,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderSubtleDark,
    error = ErrorRed,
    onError = SurfaceLight,
    errorContainer = Color(0xFF491212),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun DanskStiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val stiColors = if (darkTheme) DarkDanskStiColors else LightDanskStiColors

    CompositionLocalProvider(
        LocalDanskStiColors provides stiColors,
        LocalIsDarkTheme provides darkTheme,
        LocalWindowSizeClass provides windowSizeClass
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass,
    content: @Composable () -> Unit
) {
    DanskStiTheme(darkTheme = darkTheme, windowSizeClass = windowSizeClass, content = content)
}
