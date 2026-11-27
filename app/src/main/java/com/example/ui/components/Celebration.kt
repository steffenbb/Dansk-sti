package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.Motion

/**
 * UI audit batch 4: a small celebration for camp arrival and trip completed. A green disc grows in,
 * a check mark is drawn, and the phone gives one confirm haptic. Plays once each time it appears;
 * decorative only (the dialog's text says the same).
 */
@Composable
fun CelebrationCheck(reason: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val color = DanskStiTheme.colors.success
    val onColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val haptics = rememberHaptics()
    val disc = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics(HapticKind.CONFIRM, reason)
        disc.animateTo(1f, Motion.pressSpring())
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.MICRO_MS.toLong())
        check.animateTo(1f, tween(Motion.ENTER_MS + 150, easing = Motion.EmphasizedDecelerate))
    }
    val path = remember { Path() }
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier = modifier.size(size).testTag("celebration_check")) {
        val r = this.size.minDimension / 2f
        drawCircle(color, radius = r * disc.value)
        path.reset()
        path.moveTo(this.size.width * 0.28f, this.size.height * 0.52f)
        path.lineTo(this.size.width * 0.44f, this.size.height * 0.68f)
        path.lineTo(this.size.width * 0.73f, this.size.height * 0.36f)
        measure.setPath(path, false)
        segment.reset()
        measure.getSegment(0f, measure.length * check.value, segment, true)
        drawPath(segment, onColor, style = Stroke(width = r * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
