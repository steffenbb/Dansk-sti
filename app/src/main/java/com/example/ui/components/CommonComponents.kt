package com.example.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
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
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.MapButtonShadowElevation
import com.example.ui.theme.MapButtonShape
import com.example.ui.theme.MapButtonSize
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Danish formatting helpers for tabular distance and elevation figures.
 */
fun formatDanishKm(km: Double, decimals: Int = -1): String {
    return when {
        decimals == 0 -> String.format(Locale("da", "DK"), "%.0f", km)
        decimals > 0 -> String.format(Locale("da", "DK"), "%.${decimals}f", km)
        km >= 100.0 || km % 1.0 == 0.0 -> String.format(Locale("da", "DK"), "%.0f", km)
        else -> String.format(Locale("da", "DK"), "%.1f", km)
    }
}

fun formatDanishElevationMeters(meters: Int): String {
    val nf = NumberFormat.getIntegerInstance(Locale("da", "DK"))
    return nf.format(meters.coerceAtLeast(0))
}

fun formatDanishElevationMeters(meters: Double): String {
    return formatDanishElevationMeters(meters.roundToInt())
}

/**
 * Requirement A: Haptic tick when a pin is added, snapped or dropped.
 */
object HapticFeedbackHelper {
    @Volatile
    var tickCountForTesting: Int = 0
        private set

    @Volatile
    var lastEventReason: String = ""
        private set

    fun performPinHapticTick(view: View?, reason: String = "pin") {
        tickCountForTesting++
        lastEventReason = reason
        try {
            view?.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Exception) {}
    }

    fun resetForTesting() {
        tickCountForTesting = 0
        lastEventReason = ""
    }
}

/**
 * Requirement A: 48 dp circular map button with shadow and mandatory contentDescription.
 */
@Composable
fun MapCircleButton(
    icon: ImageVector,
    contentDescriptionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = ForestGreenPrimary,
    isActive: Boolean = false,
    activeColor: Color = com.example.ui.theme.AccentAmber,
    testTag: String? = null
) {
    val bgColor = if (isActive) activeColor else containerColor
    val fgColor = if (isActive) Color(0xFF121614) else contentColor
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
 * Requirement J: Loading state (skeleton cards).
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
                            .clip(RoundedCornerShape(12.dp))
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
 * Requirement J: Empty state (one sentence + one action).
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
            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
            modifier = Modifier
                .heightIn(min = MinTouchTargetSize)
                .testTag(actionTestTag)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

/**
 * Requirement J: Error state (Danish message + Prøv igen).
 */
@Composable
fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "error_state_view",
    retryTestTag: String = "error_state_retry_button"
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
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
                tint = ErrorRed,
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
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
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
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Requirement C: Map thumbnail of the trail drawn from its real geometry.
 */
@Composable
fun TrailGeometryThumbnail(
    points: List<GeoPointDto>,
    modifier: Modifier = Modifier,
    isDark: Boolean = isSystemInDarkTheme(),
    fallbackCenterLat: Double = 56.0,
    fallbackCenterLng: Double = 10.5
) {
    val bgColor = if (isDark) Color(0xFF17221B) else Color(0xFFE7F0E9)
    val gridColor = if (isDark) Color(0xFF25352B) else Color(0xFFD0E1D4)
    val routeColor = if (isDark) Color(0xFF66BB7E) else ForestGreenPrimary
    val casingColor = if (isDark) Color(0xFF0E1511) else Color.White

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
                drawCircle(color = ForestGreenPrimary, radius = 4.dp.toPx(), center = start)
            }
            lastOffset?.let { end ->
                drawCircle(color = casingColor, radius = 5.5.dp.toPx(), center = end)
                drawCircle(color = ErrorRed, radius = 4.dp.toPx(), center = end)
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
