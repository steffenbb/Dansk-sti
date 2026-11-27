package com.example.ui.theme

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Shared motion tokens (UI audit, batch 1). Four sizes of movement; nothing slower than a screen change.
 * Compose already follows the system animation scale, so "Remove animations" turns these off;
 * looping animations must additionally check [rememberAnimationsEnabled].
 */
object Motion {
    const val MICRO_MS = 150
    const val ENTER_MS = 250
    const val EXIT_MS = 150
    const val SCREEN_MS = 300
    const val TAB_MS = 150
    const val DATA_MS = 600

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = FastOutSlowInEasing

    fun <T> micro(): FiniteAnimationSpec<T> = tween(MICRO_MS, easing = Standard)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(ENTER_MS, easing = EmphasizedDecelerate)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(EXIT_MS, easing = EmphasizedAccelerate)
    fun <T> data(): FiniteAnimationSpec<T> = tween(DATA_MS, easing = EmphasizedDecelerate)

    /** Soft spring for press feedback and small pops. */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    /** Banners and alerts sliding in from the top and back out. */
    val BannerEnter: EnterTransition = slideInVertically(tween(ENTER_MS, easing = EmphasizedDecelerate)) { -it / 2 } +
        fadeIn(tween(ENTER_MS)) + expandVertically(tween(ENTER_MS, easing = EmphasizedDecelerate))
    val BannerExit: ExitTransition = slideOutVertically(tween(EXIT_MS, easing = EmphasizedAccelerate)) { -it / 2 } +
        fadeOut(tween(EXIT_MS)) + shrinkVertically(tween(EXIT_MS, easing = EmphasizedAccelerate))

    /** Content that expands in place (lists, sections). */
    val ExpandEnter: EnterTransition = fadeIn(tween(ENTER_MS)) + expandVertically(tween(ENTER_MS, easing = EmphasizedDecelerate))
    val ExpandExit: ExitTransition = fadeOut(tween(EXIT_MS)) + shrinkVertically(tween(EXIT_MS, easing = EmphasizedAccelerate))
}

/** False when the user has turned animations off (Settings › Accessibility › Remove animations). */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}
