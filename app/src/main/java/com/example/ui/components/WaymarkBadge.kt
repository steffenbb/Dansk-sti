package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.WaymarkSignInk
import com.example.ui.theme.WaymarkSignWhite
import com.example.util.OsmcBackgroundShape
import com.example.util.OsmcColor
import com.example.util.OsmcForegroundShape
import com.example.util.OsmcForegroundSpec
import com.example.util.OsmcSymbolParser

@Composable
fun WaymarkBadge(
    symbol: String?,
    colour: String?,
    modifier: Modifier = Modifier
) {
    val parsed = OsmcSymbolParser.parse(symbol, colour) ?: return
    val context = LocalContext.current
    val description = parsed.description(context)

    val bgColor = parsed.backgroundColor?.let { Color(it.argbHex) }
        ?: if (parsed.foreground == null && parsed.text.isNullOrBlank() && parsed.wayColor != null) {
            Color(parsed.wayColor.argbHex)
        } else {
            WaymarkSignWhite
        }

    val textColor = when {
        parsed.textColor != null -> Color(parsed.textColor.argbHex)
        parsed.foreground != null -> {
            if (parsed.foreground.color == OsmcColor.WHITE || parsed.foreground.color == OsmcColor.YELLOW) {
                WaymarkSignInk
            } else {
                WaymarkSignWhite
            }
        }
        parsed.backgroundColor == OsmcColor.BLACK ||
            parsed.backgroundColor == OsmcColor.BLUE ||
            parsed.backgroundColor == OsmcColor.RED ||
            parsed.backgroundColor == OsmcColor.GREEN ||
            parsed.backgroundColor == OsmcColor.PURPLE ||
            parsed.backgroundColor == OsmcColor.BROWN -> WaymarkSignWhite
        else -> WaymarkSignInk
    }

    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(28.dp)) {
                val w = size.width
                val h = size.height

                when (parsed.backgroundShape) {
                    OsmcBackgroundShape.SOLID -> {
                        drawRoundRect(
                            color = bgColor,
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )
                    }
                    OsmcBackgroundShape.ROUND -> {
                        drawRect(color = WaymarkSignWhite)
                        drawCircle(
                            color = bgColor,
                            radius = w * 0.44f,
                            center = Offset(w / 2f, h / 2f)
                        )
                    }
                    OsmcBackgroundShape.CIRCLE -> {
                        drawRect(color = WaymarkSignWhite)
                        drawCircle(
                            color = bgColor,
                            radius = w * 0.38f,
                            center = Offset(w / 2f, h / 2f),
                            style = Stroke(width = w * 0.12f)
                        )
                    }
                    OsmcBackgroundShape.FRAME -> {
                        drawRect(color = WaymarkSignWhite)
                        drawRect(
                            color = bgColor,
                            topLeft = Offset(w * 0.08f, h * 0.08f),
                            size = Size(w * 0.84f, h * 0.84f),
                            style = Stroke(width = w * 0.14f)
                        )
                    }
                }

                parsed.foreground?.let { drawForegroundShape(it) }
                parsed.foreground2?.let { drawForegroundShape(it) }
            }

            if (!parsed.text.isNullOrBlank()) {
                val displayTxt = parsed.text.take(4)
                Text(
                    text = displayTxt,
                    color = textColor,
                    fontSize = if (displayTxt.length <= 2) 11.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }

        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun DrawScope.drawForegroundShape(spec: OsmcForegroundSpec) {
    val fgColor = Color(spec.color.argbHex)
    val w = size.width
    val h = size.height
    when (spec.shape) {
        OsmcForegroundShape.BAR -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(w * 0.12f, h * 0.34f),
                size = Size(w * 0.76f, h * 0.32f)
            )
        }
        OsmcForegroundShape.STRIPE -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(w * 0.34f, h * 0.12f),
                size = Size(w * 0.32f, h * 0.76f)
            )
        }
        OsmcForegroundShape.DOT -> {
            drawCircle(
                color = fgColor,
                radius = w * 0.24f,
                center = Offset(w / 2f, h / 2f)
            )
        }
        OsmcForegroundShape.CIRCLE -> {
            drawCircle(
                color = fgColor,
                radius = w * 0.30f,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = w * 0.12f)
            )
        }
        OsmcForegroundShape.CROSS -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(w * 0.16f, h * 0.38f),
                size = Size(w * 0.68f, h * 0.24f)
            )
            drawRect(
                color = fgColor,
                topLeft = Offset(w * 0.38f, h * 0.16f),
                size = Size(w * 0.24f, h * 0.68f)
            )
        }
        OsmcForegroundShape.X -> {
            val stroke = w * 0.16f
            drawLine(
                color = fgColor,
                start = Offset(w * 0.22f, h * 0.22f),
                end = Offset(w * 0.78f, h * 0.78f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = fgColor,
                start = Offset(w * 0.78f, h * 0.22f),
                end = Offset(w * 0.22f, h * 0.78f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        OsmcForegroundShape.TRIANGLE -> {
            val path = Path().apply {
                moveTo(w * 0.50f, h * 0.18f)
                lineTo(w * 0.82f, h * 0.78f)
                lineTo(w * 0.18f, h * 0.78f)
                close()
            }
            drawPath(path = path, color = fgColor)
        }
        OsmcForegroundShape.DIAMOND -> {
            val path = Path().apply {
                moveTo(w * 0.50f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.50f)
                lineTo(w * 0.50f, h * 0.86f)
                lineTo(w * 0.14f, h * 0.50f)
                close()
            }
            drawPath(path = path, color = fgColor)
        }
        OsmcForegroundShape.RECTANGLE -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(w * 0.20f, h * 0.24f),
                size = Size(w * 0.60f, h * 0.52f)
            )
        }
        OsmcForegroundShape.LOWER -> {
            drawRect(
                color = fgColor,
                topLeft = Offset(0f, h * 0.50f),
                size = Size(w, h * 0.50f)
            )
        }
        OsmcForegroundShape.CORNER -> {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                close()
            }
            drawPath(path = path, color = fgColor)
        }
        OsmcForegroundShape.POINTER -> {
            val path = Path().apply {
                moveTo(w * 0.20f, h * 0.20f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.20f, h * 0.80f)
                close()
            }
            drawPath(path = path, color = fgColor)
        }
    }
}
