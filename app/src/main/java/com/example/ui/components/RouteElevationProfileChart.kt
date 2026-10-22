package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.routing.RoutePoint3D
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elevation profile chart coloured by gradient:
 * - Green < 3 %
 * - Yellow 3-8 %
 * - Red > 8 %
 *
 * Supports dragging/scrubbing on the chart, which invokes [onPointScrubbed]
 * with the corresponding point and distance in km, allowing the map marker to move in sync.
 */
@Composable
fun RouteElevationProfileChart(
    points: List<RoutePoint3D>,
    modifier: Modifier = Modifier,
    dayBoundaryWalkKms: List<Double> = emptyList(),
    overnightWaypointIds: List<String> = emptyList(),
    onPointScrubbed: ((RoutePoint3D?, Double) -> Unit)? = null,
    onOvernightMarkerDragged: ((String, Double, Boolean) -> Unit)? = null
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(130.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Ingen højdedata tilgængelig",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val validPoints = remember(points) {
        points.map { pt ->
            RoutePoint3D(pt.lat, pt.lng, pt.ele ?: 15.0)
        }
    }

    val distancesKm = remember(validPoints) {
        val dists = FloatArray(validPoints.size)
        var sumM = 0.0
        dists[0] = 0f
        for (i in 1 until validPoints.size) {
            val p1 = validPoints[i - 1]
            val p2 = validPoints[i]
            sumM += haversineM(p1.lat, p1.lng, p2.lat, p2.lng)
            dists[i] = (sumM / 1000.0).toFloat()
        }
        dists
    }

    val totalKm = distancesKm.lastOrNull() ?: 0f

    var minEle = Float.MAX_VALUE
    var maxEle = Float.MIN_VALUE
    for (pt in validPoints) {
        val ele = (pt.ele ?: 0.0).toFloat()
        if (ele < minEle) minEle = ele
        if (ele > maxEle) maxEle = ele
    }

    if (minEle >= maxEle) {
        maxEle = minEle + 10f
    }
    val elePadding = (maxEle - minEle) * 0.15f
    val displayMinEle = (minEle - elePadding).coerceAtLeast(0f)
    val displayMaxEle = maxEle + elePadding
    val eleRange = displayMaxEle - displayMinEle

    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var scrubbedPoint by remember { mutableStateOf<RoutePoint3D?>(null) }
    var scrubbedKm by remember { mutableFloatStateOf(0f) }
    var draggingOvernightId by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scrubFraction in 0f..1f && scrubbedPoint != null) {
                Text(
                    text = String.format(Locale.GERMANY, "Marker: %.1f km · %.0f m", scrubbedKm, scrubbedPoint?.ele ?: 0.0),
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = String.format(Locale.GERMANY, "Min: %.0f m · Max: %.0f m", minEle, maxEle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("<3%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(Color(0xFFFFB300), CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("3-8%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(Color(0xFFE53935), CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(">8%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("route_elevation_chart")
                .pointerInput(validPoints, totalKm, dayBoundaryWalkKms, overnightWaypointIds) {
                    fun updateScrub(xPos: Float) {
                        val fraction = (xPos / size.width).coerceIn(0f, 1f)
                        scrubFraction = fraction
                        val targetKm = fraction * totalKm
                        scrubbedKm = targetKm

                        val closestIdx = findClosestIndex(distancesKm, targetKm)
                        val pt = validPoints.getOrNull(closestIdx)
                        scrubbedPoint = pt
                        onPointScrubbed?.invoke(pt, targetKm.toDouble())
                    }

                    detectTapGestures(
                        onPress = { offset ->
                            updateScrub(offset.x)
                        }
                    )
                }
                .pointerInput(validPoints, totalKm, dayBoundaryWalkKms, overnightWaypointIds) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            scrubFraction = fraction
                            val targetKm = fraction * totalKm
                            scrubbedKm = targetKm

                            // Check if user grabbed near an overnight day boundary marker (within 24dp horizontally)
                            var matchedOvId: String? = null
                            if (totalKm > 0f && dayBoundaryWalkKms.isNotEmpty() && overnightWaypointIds.isNotEmpty()) {
                                val hitRadiusPx = 24.dp.toPx()
                                for (i in dayBoundaryWalkKms.indices) {
                                    val ovId = overnightWaypointIds.getOrNull(i) ?: continue
                                    val bx = ((dayBoundaryWalkKms[i].toFloat() / totalKm).coerceIn(0f, 1f)) * size.width
                                    if (kotlin.math.abs(offset.x - bx) <= hitRadiusPx) {
                                        matchedOvId = ovId
                                        break
                                    }
                                }
                            }
                            draggingOvernightId = matchedOvId

                            val idx = findClosestIndex(distancesKm, targetKm)
                            val pt = validPoints.getOrNull(idx)
                            scrubbedPoint = pt
                            onPointScrubbed?.invoke(pt, targetKm.toDouble())
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            scrubFraction = fraction
                            val targetKm = fraction * totalKm
                            scrubbedKm = targetKm
                            val idx = findClosestIndex(distancesKm, targetKm)
                            val pt = validPoints.getOrNull(idx)
                            scrubbedPoint = pt
                            onPointScrubbed?.invoke(pt, targetKm.toDouble())
                            draggingOvernightId?.let { ovId ->
                                onOvernightMarkerDragged?.invoke(ovId, targetKm.toDouble(), false)
                            }
                        },
                        onDragEnd = {
                            draggingOvernightId?.let { ovId ->
                                onOvernightMarkerDragged?.invoke(ovId, scrubbedKm.toDouble(), true)
                            }
                            draggingOvernightId = null
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height
            if (validPoints.size < 2 || totalKm <= 0f) return@Canvas

            val xCoords = FloatArray(validPoints.size)
            val yCoords = FloatArray(validPoints.size)

            for (i in validPoints.indices) {
                val km = distancesKm[i]
                xCoords[i] = (km / totalKm) * width
                val ele = (validPoints[i].ele ?: displayMinEle.toDouble()).toFloat()
                val normalizedY = 1f - ((ele - displayMinEle) / eleRange).coerceIn(0f, 1f)
                yCoords[i] = normalizedY * (height - 10f) + 5f
            }

            val fillPath = Path().apply {
                moveTo(xCoords[0], height)
                for (i in validPoints.indices) {
                    lineTo(xCoords[i], yCoords[i])
                }
                lineTo(xCoords.last(), height)
                close()
            }
            drawPath(
                path = fillPath,
                color = Color(0x334CAF50)
            )

            for (i in 0 until validPoints.size - 1) {
                val x1 = xCoords[i]
                val y1 = yCoords[i]
                val x2 = xCoords[i + 1]
                val y2 = yCoords[i + 1]

                val dDistM = (distancesKm[i + 1] - distancesKm[i]) * 1000.0
                val dEleM = (validPoints[i + 1].ele ?: 0.0) - (validPoints[i].ele ?: 0.0)
                val gradientPct = if (dDistM > 0.1) (kotlin.math.abs(dEleM) / dDistM) * 100.0 else 0.0

                val segmentColor = when {
                    gradientPct < 3.0 -> Color(0xFF4CAF50)
                    gradientPct <= 8.0 -> Color(0xFFFFB300)
                    else -> Color(0xFFE53935)
                }

                drawLine(
                    color = segmentColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Requirement F: Draw day boundaries and overnight markers on the elevation profile
            for (boundaryKm in dayBoundaryWalkKms) {
                val bFraction = (boundaryKm.toFloat() / totalKm).coerceIn(0f, 1f)
                val bx = bFraction * width
                drawLine(
                    color = Color(0xFFE65100),
                    start = Offset(bx, 0f),
                    end = Offset(bx, height),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
                val bIdx = findClosestIndex(distancesKm, boundaryKm.toFloat())
                val by = yCoords.getOrElse(bIdx) { height / 2f }
                drawCircle(
                    color = Color.White,
                    radius = 7.dp.toPx(),
                    center = Offset(bx, by)
                )
                drawCircle(
                    color = Color(0xFFE65100),
                    radius = 5.dp.toPx(),
                    center = Offset(bx, by)
                )
            }

            if (scrubFraction in 0f..1f) {
                val scrubX = scrubFraction * width
                drawLine(
                    color = Color.White,
                    start = Offset(scrubX, 0f),
                    end = Offset(scrubX, height),
                    strokeWidth = 1.5.dp.toPx()
                )

                val scrubIdx = findClosestIndex(distancesKm, scrubFraction * totalKm)
                val dotX = xCoords[scrubIdx]
                val dotY = yCoords[scrubIdx]

                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                drawCircle(
                    color = Color(0xFF2E7D32),
                    radius = 3.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }
    }
}

private fun findClosestIndex(distances: FloatArray, targetKm: Float): Int {
    var closestIdx = 0
    var minDiff = Float.MAX_VALUE
    for (i in distances.indices) {
        val diff = kotlin.math.abs(distances[i] - targetKm)
        if (diff < minDiff) {
            minDiff = diff
            closestIdx = i
        }
    }
    return closestIdx
}

private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}
