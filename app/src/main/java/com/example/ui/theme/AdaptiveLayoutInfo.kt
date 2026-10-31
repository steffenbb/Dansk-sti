package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthSizeClass {
    COMPACT,  // < 600 dp
    MEDIUM,   // 600..840 dp
    EXPANDED  // > 840 dp
}

enum class WindowHeightSizeClass {
    COMPACT,  // < 480 dp (phone landscape)
    MEDIUM,   // 480..900 dp
    EXPANDED  // > 900 dp
}

@Immutable
data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp = 360.dp,
    val heightDp: Dp = 640.dp
) {
    val isWidthCompact: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.COMPACT

    val isWidthMedium: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.MEDIUM

    val isWidthExpanded: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.EXPANDED

    val isHeightCompact: Boolean
        get() = heightSizeClass == WindowHeightSizeClass.COMPACT

    val useNavigationRail: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.COMPACT

    val usePlannerSidePanel: Boolean
        get() = isHeightCompact || isWidthExpanded

    val udforskColumns: Int
        get() = when (widthSizeClass) {
            WindowWidthSizeClass.COMPACT -> 1
            WindowWidthSizeClass.MEDIUM -> 2
            WindowWidthSizeClass.EXPANDED -> 3
        }

    val useSideBySideTrailDetail: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.COMPACT

    val isLandscape: Boolean
        get() = isHeightCompact || widthDp > heightDp

    companion object {
        fun compute(widthDp: Dp, heightDp: Dp): WindowSizeClass {
            val wClass = when {
                widthDp < 600.dp -> WindowWidthSizeClass.COMPACT
                widthDp <= 840.dp -> WindowWidthSizeClass.MEDIUM
                else -> WindowWidthSizeClass.EXPANDED
            }
            val hClass = when {
                heightDp < 480.dp -> WindowHeightSizeClass.COMPACT
                heightDp <= 900.dp -> WindowHeightSizeClass.MEDIUM
                else -> WindowHeightSizeClass.EXPANDED
            }
            return WindowSizeClass(
                widthSizeClass = wClass,
                heightSizeClass = hClass,
                widthDp = widthDp,
                heightDp = heightDp
            )
        }
    }
}

@Immutable
data class WindowAdaptiveInfo(
    val windowSizeClass: WindowSizeClass
)

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass?> { null }

@Composable
@ReadOnlyComposable
fun currentWindowAdaptiveInfo(): WindowAdaptiveInfo {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp.takeIf { it > 0 }?.dp ?: 360.dp
    val heightDp = configuration.screenHeightDp.takeIf { it > 0 }?.dp ?: 640.dp
    return WindowAdaptiveInfo(
        windowSizeClass = WindowSizeClass.compute(widthDp = widthDp, heightDp = heightDp)
    )
}

@Composable
@ReadOnlyComposable
fun currentWindowSizeClass(explicit: WindowSizeClass? = null): WindowSizeClass {
    return explicit ?: LocalWindowSizeClass.current ?: currentWindowAdaptiveInfo().windowSizeClass
}
