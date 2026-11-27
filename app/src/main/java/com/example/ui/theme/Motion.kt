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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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

/**
 * Gentle press feedback: the element sinks to [pressedScale] while pressed and springs back.
 * Pass the same [interactionSource] to the element's `clickable`/`onClick` (UI audit batch 2).
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.98f
): Modifier = this.composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.pressSpring(),
        label = "press_scale"
    )
    Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A progress fraction that moves smoothly to [target] instead of jumping between updates. */
@Composable
fun animatedProgress(target: Float, label: String = "progress"): Float {
    val value by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = Motion.data(),
        label = label
    )
    return value
}

/** A small pop when [active] turns true (e.g. a bookmark being saved); nothing on first display. */
fun Modifier.bookmarkPop(active: Boolean): Modifier = this.composed {
    val scale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val previous = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(active) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (active && !previous.value) {
            scale.animateTo(1.25f, tween(Motion.MICRO_MS, easing = Motion.EmphasizedDecelerate))
            scale.animateTo(1f, Motion.pressSpring())
        }
        previous.value = active
    }
    Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * Shows [value]'s banner with the shared banner motion; keeps the last value while it slides out,
 * so the exit animation still has its text (UI audit batch 2).
 */
@Composable
fun <T : Any> androidx.compose.foundation.layout.ColumnScope.AnimatedBanner(
    value: T?,
    content: @Composable (T) -> Unit
) {
    // Remember the last shown value after composition (no state writes during composition)
    val last = remember { androidx.compose.runtime.mutableStateOf(value) }
    androidx.compose.runtime.SideEffect { if (value != null) last.value = value }
    androidx.compose.animation.AnimatedVisibility(
        visible = value != null,
        enter = Motion.BannerEnter,
        exit = Motion.BannerExit
    ) {
        (value ?: last.value)?.let { content(it) }
    }
}

/**
 * Slides content up a little and fades it in whenever [key] changes (not on first display).
 * Animates the one element in place, so there is never a second copy of it on screen.
 */
fun Modifier.slideInOnChange(key: Any?, distance: androidx.compose.ui.unit.Dp = 10.dp): Modifier = this.composed {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val firstKey = remember { androidx.compose.runtime.mutableStateOf(key) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key != firstKey.value) {
            progress.snapTo(1f)
            progress.animateTo(0f, tween(Motion.ENTER_MS, easing = Motion.EmphasizedDecelerate))
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Modifier.graphicsLayer {
        translationY = with(density) { distance.toPx() } * progress.value
        alpha = 1f - 0.7f * progress.value
    }
}

/** Like [animatedProgress], as the `progress = { … }` lambda Material progress indicators take. */
@Composable
fun smoothProgress(target: Float): () -> Float {
    val value = animatedProgress(target, "smooth_progress")
    return { value }
}

/** `clickable` with the standard ripple plus [pressScale] feedback (buttons, round actions, tool buttons). */
fun Modifier.springClickable(pressedScale: Float = 0.94f, onClick: () -> Unit): Modifier = this.composed {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Modifier
        .pressScale(source, pressedScale)
        .clickable(
            interactionSource = source,
            indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick
        )
}

/** Draws the content left to right once, the first time it appears (bars, charts; UI audit batch 3). */
fun Modifier.revealOnce(): Modifier = this.composed {
    val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { reveal.animateTo(1f, Motion.data()) }
    Modifier.drawWithContent {
        clipRect(right = size.width * reveal.value) { this@drawWithContent.drawContent() }
    }
}

/**
 * A number that counts up from zero the first time it is shown, then follows [target] smoothly.
 * Callers format the returned value exactly as before, so the final text is unchanged.
 */
@Composable
fun countUp(target: Double, durationMs: Int = Motion.DATA_MS): Double {
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    val settled = remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(target) {
        settled.value = false
        anim.animateTo(target.toFloat(), tween(if (anim.value == 0f) durationMs else Motion.SCREEN_MS, easing = Motion.EmphasizedDecelerate))
        settled.value = true
    }
    // The exact value once settled, so float rounding never changes the final text
    return if (settled.value) target else anim.value.toDouble()
}

/** [countUp] for whole numbers. */
@Composable
fun countUp(target: Int, durationMs: Int = Motion.DATA_MS): Int =
    countUp(target.toDouble(), durationMs).let { if (it == target.toDouble()) target else kotlin.math.round(it).toInt() }

