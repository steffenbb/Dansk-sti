package com.example.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GeoPointDto
import com.example.ui.theme.CardShape
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.MapButtonShadowElevation
import com.example.ui.theme.MapButtonShape
import com.example.ui.theme.MapButtonSize
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun rememberAppLocale(): Locale {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val locales = config.locales
    return if (!locales.isEmpty) {
        locales[0] ?: com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE
    } else {
        com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE
    }
}

/**
 * Locale-aware formatting helpers for tabular distance and elevation figures.
 */
fun formatDanishKm(km: Double, decimals: Int = -1, locale: Locale = com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE): String {
    return when {
        decimals == 0 -> String.format(locale, "%.0f", km)
        decimals > 0 -> String.format(locale, "%.${decimals}f", km)
        km >= 100.0 || km % 1.0 == 0.0 -> String.format(locale, "%.0f", km)
        else -> String.format(locale, "%.1f", km)
    }
}

fun formatDanishElevationMeters(meters: Int, locale: Locale = com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE): String {
    val nf = NumberFormat.getIntegerInstance(locale)
    return nf.format(meters.coerceAtLeast(0))
}

fun formatDanishElevationMeters(meters: Double, locale: Locale = com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE): String {
    return formatDanishElevationMeters(meters.roundToInt(), locale)
}

/**
 * Formats a timestamp as "Hentet <dato>", e.g. "Hentet 27. okt. 2026".
 */
fun formatDownloadedDateDanish(epochMillis: Long, locale: Locale = com.example.util.AppLocaleHelper.DEFAULT_DANISH_LOCALE, prefix: String = "Hentet"): String {
    val datePart = com.example.util.AppLocaleHelper.formatShortDate(epochMillis, locale)
    return "$prefix $datePart"
}

/**
 * "Offline" pill shown on maps and lists without connection.
 */
@Composable
fun OfflineStatusPill(
    modifier: Modifier = Modifier,
    testTag: String = "offline_pill"
) {
    val offlineCd = androidx.compose.ui.res.stringResource(com.example.R.string.offline_pill_cd)
    val warningColor = DanskStiTheme.colors.warning
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.7f)),
        shadowElevation = 2.dp,
        modifier = modifier
            .testTag(testTag)
            .semantics(mergeDescendants = true) {
                contentDescription = offlineCd
            }
    ) {
         androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(warningColor)
            )
            Text(
                text = androidx.compose.ui.res.stringResource(com.example.R.string.offline_pill_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

/** The feels the app uses, one meaning each (UI audit "haptics map"). */
enum class HapticKind { TICK, CONFIRM, REJECT, THRESHOLD, HEAVY }

/**
 * Haptic feedback through [View.performHapticFeedback]: follows the phone's own touch-feedback
 * setting and needs no vibrate permission. Newer constants fall back on older Android versions.
 */
object HapticFeedbackHelper {
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var tickCountForTesting: Int = 0
        private set

    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var lastEventReason: String = ""
        private set

    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var lastKindForTesting: HapticKind? = null
        private set

    /** Haptic tick when a pin is added, snapped or dropped. */
    fun performPinHapticTick(view: View?, reason: String = "pin") {
        tickCountForTesting++
        lastEventReason = reason
        perform(view, HapticKind.TICK, reason, countAsPinTick = false)
    }

    fun perform(view: View?, kind: HapticKind, reason: String = kind.name.lowercase(), countAsPinTick: Boolean = false) {
        if (countAsPinTick) tickCountForTesting++
        lastEventReason = reason
        lastKindForTesting = kind
        val constant = constantFor(kind)
        try {
            // Callbacks such as "saved" can arrive off the main thread: hand the haptic to the view's thread
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                view?.performHapticFeedback(constant)
            } else {
                view?.post { view.performHapticFeedback(constant) }
            }
        } catch (_: Exception) {}
    }

    internal fun constantFor(kind: HapticKind, sdk: Int = android.os.Build.VERSION.SDK_INT): Int = when (kind) {
        HapticKind.TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        HapticKind.CONFIRM -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        HapticKind.REJECT -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        HapticKind.THRESHOLD -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
        HapticKind.HEAVY -> HapticFeedbackConstants.LONG_PRESS
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        tickCountForTesting = 0
        lastEventReason = ""
        lastKindForTesting = null
    }
}

/** Haptics bound to the current view: `val haptics = rememberHaptics(); haptics(HapticKind.CONFIRM)`. */
class Haptics internal constructor(private val view: View) {
    operator fun invoke(kind: HapticKind, reason: String = kind.name.lowercase()) = HapticFeedbackHelper.perform(view, kind, reason)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = androidx.compose.ui.platform.LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * Shows [content] only when loading lasts longer than [delayMs], then fades it in, so fast loads
 * never flash a spinner (UI audit batch 1).
 */
@Composable
fun DelayedLoadingVisibility(delayMs: Long = 300L, content: @Composable () -> Unit) {
    var show by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        show = true
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = show,
        enter = androidx.compose.animation.fadeIn(com.example.ui.theme.Motion.enter())
    ) { content() }
}

/**
 * 48 dp circular map button with shadow and mandatory contentDescription.
 */
@Composable
fun MapCircleButton(
    icon: ImageVector,
    contentDescriptionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    isActive: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.secondary,
    testTag: String? = null
) {
    val bgColor = if (isActive) activeColor else containerColor
    val fgColor = if (isActive) MaterialTheme.colorScheme.onSecondary else contentColor
    Surface(
        shape = MapButtonShape,
        color = bgColor,
        shadowElevation = MapButtonShadowElevation,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier
            .sizeIn(minWidth = MapButtonSize, minHeight = MapButtonSize)
            .size(MapButtonSize)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescriptionText,
                tint = fgColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Loading state (skeleton cards).
 */
@Composable
fun SkeletonLoadingList(
    itemCount: Int = 3,
    cardHeight: Dp = 112.dp,
    modifier: Modifier = Modifier,
    testTag: String = "skeleton_loading_list"
) {
    val transition = rememberInfiniteTransition(label = "skeleton_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    val loadingCd = stringResource(R.string.loading_skeleton_cd)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .testTag(testTag)
            .semantics { contentDescription = loadingCd },
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        repeat(itemCount) {
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cardHeight)
                            .clip(com.example.ui.theme.TileShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

/**
 * Empty state (one sentence + one action).
 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    sentence: String,
    actionLabel: String,
    onActionClick: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    testTag: String = "empty_state_view",
    actionTestTag: String = "empty_state_action_button"
) {
    val resolvedAction = onActionClick ?: onAction ?: {}
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = sentence,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        Button(
            onClick = resolvedAction,
            shape = CardShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .heightIn(min = MinTouchTargetSize)
                .testTag(actionTestTag)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * Error state (Danish message + Prøv igen).
 */
@Composable
fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "error_state_view",
    retryTestTag: String = "error_state_retry_button"
) {
    val dangerColor = DanskStiTheme.colors.danger
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, dangerColor.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = dangerColor,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Button(
                onClick = onRetry,
                shape = CardShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .heightIn(min = MinTouchTargetSize)
                    .testTag(retryTestTag)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.btn_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * Map thumbnail of the trail drawn from its real geometry.
 */
@Composable
fun TrailGeometryThumbnail(
    points: List<GeoPointDto>,
    modifier: Modifier = Modifier,
    isDark: Boolean = DanskStiTheme.isDark,
    fallbackCenterLat: Double = 56.0,
    fallbackCenterLng: Double = 10.5
) {
    val stiColors = DanskStiTheme.colors
    val bgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.35f else 0.65f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val routeColor = MaterialTheme.colorScheme.primary
    val casingColor = stiColors.routeCasing
    val startPinColor = stiColors.success
    val endPinColor = stiColors.danger

    Canvas(
        modifier = modifier
            .background(bgColor)
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Topographic-inspired subtle contour arcs
        val strokeThin = 1.dp.toPx()
        for (i in 1..3) {
            val y = h * (i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = strokeThin
            )
        }
        for (i in 1..4) {
            val x = w * (i / 5f)
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = strokeThin
            )
        }

        val validPoints = points.filter { it.lat in 53.0..59.0 && it.lng in 7.0..16.0 }
        if (validPoints.size >= 2) {
            val minLat = validPoints.minOf { it.lat }
            val maxLat = validPoints.maxOf { it.lat }
            val minLng = validPoints.minOf { it.lng }
            val maxLng = validPoints.maxOf { it.lng }

            val dLat = (maxLat - minLat).coerceAtLeast(0.005)
            val dLng = (maxLng - minLng).coerceAtLeast(0.005)
            val padX = w * 0.14f
            val padY = h * 0.18f
            val drawW = (w - 2 * padX).coerceAtLeast(10f)
            val drawH = (h - 2 * padY).coerceAtLeast(10f)

            val step = (validPoints.size / 120).coerceAtLeast(1)
            val path = Path()
            var firstOffset: Offset? = null
            var lastOffset: Offset? = null

            var idx = 0
            while (idx < validPoints.size) {
                val pt = validPoints[idx]
                val nx = ((pt.lng - minLng) / dLng).toFloat().coerceIn(0f, 1f)
                val ny = (1.0 - (pt.lat - minLat) / dLat).toFloat().coerceIn(0f, 1f)
                val pos = Offset(padX + nx * drawW, padY + ny * drawH)
                if (firstOffset == null) {
                    firstOffset = pos
                    path.moveTo(pos.x, pos.y)
                } else {
                    path.lineTo(pos.x, pos.y)
                }
                lastOffset = pos
                idx += step
            }
            // Ensure final point is included
            val lastPt = validPoints.last()
            val endPos = Offset(
                padX + ((lastPt.lng - minLng) / dLng).toFloat().coerceIn(0f, 1f) * drawW,
                padY + (1.0 - (lastPt.lat - minLat) / dLat).toFloat().coerceIn(0f, 1f) * drawH
            )
            path.lineTo(endPos.x, endPos.y)
            lastOffset = endPos

            // Draw casing + trail line
            drawPath(
                path = path,
                color = casingColor,
                style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = routeColor,
                style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Start & End markers
            firstOffset?.let { start ->
                drawCircle(color = casingColor, radius = 5.5.dp.toPx(), center = start)
                drawCircle(color = startPinColor, radius = 4.dp.toPx(), center = start)
            }
            lastOffset?.let { end ->
                drawCircle(color = casingColor, radius = 5.5.dp.toPx(), center = end)
                drawCircle(color = endPinColor, radius = 4.dp.toPx(), center = end)
            }
        } else {
            // Single center marker when geometry is not yet synced
            val center = Offset(w * 0.5f, h * 0.5f)
            drawCircle(color = routeColor.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = center)
            drawCircle(color = casingColor, radius = 6.dp.toPx(), center = center)
            drawCircle(color = routeColor, radius = 4.5.dp.toPx(), center = center)
        }
    }
}

/** Wraps a click action with a haptic [kind] (tick by default) – for chips, segmented buttons and similar. */
@Composable
fun hapticClick(kind: HapticKind = HapticKind.TICK, onClick: () -> Unit): () -> Unit {
    val haptics = rememberHaptics()
    return { haptics(kind); onClick() }
}

/** Wraps a switch/checkbox change with a tick (UI audit haptics map). */
@Composable
fun hapticToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = rememberHaptics()
    return { checked -> haptics(HapticKind.TICK); onChange(checked) }
}
