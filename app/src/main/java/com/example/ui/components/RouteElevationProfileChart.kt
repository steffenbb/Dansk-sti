package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.util.routing.RoutePoint3D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elevation profile chart coloured by gradient:
 * - Green < 3 % (DanskStiColors.success)
 * - Yellow 3-8 % (DanskStiColors.warning)
 * - Red > 8 % (DanskStiColors.danger)
 *
 * Draws only points that have real elevation (`pt.ele != null`).
 * If fewer than 2 points have elevation, shows "Ingen højdedata tilgængelig".
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
    val stiColors = DanskStiTheme.colors
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val lowSlopeColor = stiColors.success
    val mediumSlopeColor = stiColors.warning
    val highSlopeColor = stiColors.danger
    val overnightColor = stiColors.overnight
    val casingColor = stiColors.routeCasing

    val validPoints = remember(points) {
        points.filter { pt ->
            val ele = pt.ele
            ele != null && ele.isFinite() && pt.lat.isFinite() && pt.lng.isFinite()
        }
    }

    if (validPoints.size < 2) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(130.dp)
                .testTag("elevation_no_data_box"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.elevation_no_data_available),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("elevation_no_data_text")
            )
        }
        return
    }

    val distancesKm = remember(validPoints) {
        val dists = FloatArray(validPoints.size)
        var sumM = 0.0
        for (i in 1 until validPoints.size) {
            val p1 = validPoints[i - 1]
            val p2 = validPoints[i]
            val dM = haversineM(p1.lat, p1.lng, p2.lat, p2.lng)
            if (dM.isFinite() && dM > 0.0) {
                sumM += dM
            }
            dists[i] = (sumM / 1000.0).toFloat()
        }
        dists
    }

    val rawTotalKm = distancesKm.lastOrNull() ?: 0f
    val totalKm = if (rawTotalKm.isFinite() && rawTotalKm > 0f) rawTotalKm else 0f

    var minEle = Float.MAX_VALUE
    var maxEle = -Float.MAX_VALUE
    for (pt in validPoints) {
        val ele = (pt.ele ?: continue).toFloat()
        if (!ele.isFinite()) continue
        if (ele < minEle) minEle = ele
        if (ele > maxEle) maxEle = ele
    }

    if (!minEle.isFinite() || !maxEle.isFinite() || minEle > maxEle) {
        minEle = 0f
        maxEle = 10f
    }
    if (minEle >= maxEle) {
        maxEle = minEle + 10f
    }
    val elePadding = ((maxEle - minEle) * 0.15f).coerceAtLeast(1f)
    val displayMinEle = (minEle - elePadding).coerceAtLeast(0f)
    val displayMaxEle = maxEle + elePadding
    val eleRange = (displayMaxEle - displayMinEle).let { if (it.isFinite() && it > 0f) it else 10f }

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
            val currentScrubbed = scrubbedPoint
            if (scrubFraction in 0f..1f && currentScrubbed != null) {
                Text(
                    text = String.format(
                        Locale.GERMANY,
                        stringResource(R.string.elevation_marker_format),
                        scrubbedKm,
                        currentScrubbed.ele ?: 0.0
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            } else {
                Text(
                    text = String.format(
                        Locale.GERMANY,
                        stringResource(R.string.elevation_min_max_format),
                        minEle,
                        maxEle
                    ),
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
                        .background(lowSlopeColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.elevation_slope_low),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(mediumSlopeColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.elevation_slope_medium),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(highSlopeColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.elevation_slope_high),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("route_elevation_chart")
                .pointerInput(validPoints, totalKm, dayBoundaryWalkKms, overnightWaypointIds) {
                    fun updateScrub(xPos: Float) {
                        if (size.width <= 0) return
                        val fraction = (xPos / size.width.toFloat()).coerceIn(0f, 1f)
                        scrubFraction = fraction
                        val targetKm = fraction * totalKm
                        scrubbedKm = targetKm

                        val closestIdx = if (totalKm > 0f) {
                            findClosestIndex(distancesKm, targetKm)
                        } else {
                            ((fraction * (validPoints.size - 1)).toInt()).coerceIn(0, validPoints.size - 1)
                        }
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
                            if (size.width <= 0) return@detectDragGestures
                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            scrubFraction = fraction
                            val targetKm = fraction * totalKm
                            scrubbedKm = targetKm

                            var matchedOvId: String? = null
                            if (totalKm > 0f && dayBoundaryWalkKms.isNotEmpty() && overnightWaypointIds.isNotEmpty()) {
                                val hitRadiusPx = 24.dp.toPx()
                                for (i in dayBoundaryWalkKms.indices) {
                                    val ovId = overnightWaypointIds.getOrNull(i) ?: continue
                                    val bx = ((dayBoundaryWalkKms[i].toFloat() / totalKm).coerceIn(0f, 1f)) * size.width
                                    if (abs(offset.x - bx) <= hitRadiusPx) {
                                        matchedOvId = ovId
                                        break
                                    }
                                }
                            }
                            draggingOvernightId = matchedOvId

                            val idx = if (totalKm > 0f) {
                                findClosestIndex(distancesKm, targetKm)
                            } else {
                                ((fraction * (validPoints.size - 1)).toInt()).coerceIn(0, validPoints.size - 1)
                            }
                            val pt = validPoints.getOrNull(idx)
                            scrubbedPoint = pt
                            onPointScrubbed?.invoke(pt, targetKm.toDouble())
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (size.width <= 0) return@detectDragGestures
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            scrubFraction = fraction
                            val targetKm = fraction * totalKm
                            scrubbedKm = targetKm
                            val idx = if (totalKm > 0f) {
                                findClosestIndex(distancesKm, targetKm)
                            } else {
                                ((fraction * (validPoints.size - 1)).toInt()).coerceIn(0, validPoints.size - 1)
                            }
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
            if (validPoints.size < 2 || width <= 0f || height <= 0f) return@Canvas

            val xCoords = FloatArray(validPoints.size)
            val yCoords = FloatArray(validPoints.size)

            val lastIdx = (validPoints.size - 1).coerceAtLeast(1)
            for (i in validPoints.indices) {
                val xFraction = if (totalKm > 0f) {
                    (distancesKm[i] / totalKm).coerceIn(0f, 1f)
                } else {
                    (i.toFloat() / lastIdx.toFloat()).coerceIn(0f, 1f)
                }
                xCoords[i] = xFraction * width
                val ele = (validPoints[i].ele ?: displayMinEle.toDouble()).toFloat()
                val normalizedY = 1f - ((ele - displayMinEle) / eleRange).coerceIn(0f, 1f)
                yCoords[i] = normalizedY * (height - 10f).coerceAtLeast(1f) + 5f
            }

            val firstX = xCoords.firstOrNull() ?: 0f
            val lastX = xCoords.lastOrNull() ?: width
            val fillPath = Path().apply {
                moveTo(firstX, height)
                for (i in validPoints.indices) {
                    lineTo(xCoords[i], yCoords[i])
                }
                lineTo(lastX, height)
                close()
            }
            drawPath(
                path = fillPath,
                color = lowSlopeColor.copy(alpha = 0.20f)
            )

            for (i in 0 until validPoints.size - 1) {
                val x1 = xCoords[i]
                val y1 = yCoords[i]
                val x2 = xCoords[i + 1]
                val y2 = yCoords[i + 1]

                val dDistM = (distancesKm[i + 1] - distancesKm[i]) * 1000.0
                val dEleM = (validPoints[i + 1].ele ?: 0.0) - (validPoints[i].ele ?: 0.0)
                val gradientPct = if (dDistM > 0.1) (abs(dEleM) / dDistM) * 100.0 else 0.0

                val segmentColor = when {
                    gradientPct < 3.0 -> lowSlopeColor
                    gradientPct <= 8.0 -> mediumSlopeColor
                    else -> highSlopeColor
                }

                drawLine(
                    color = segmentColor,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Draw day boundaries and overnight markers on the elevation profile when totalKm > 0
            if (totalKm > 0f) {
                for (boundaryKm in dayBoundaryWalkKms) {
                    if (!boundaryKm.isFinite()) continue
                    val bFraction = (boundaryKm.toFloat() / totalKm).coerceIn(0f, 1f)
                    val bx = bFraction * width
                    drawLine(
                        color = overnightColor,
                        start = Offset(bx, 0f),
                        end = Offset(bx, height),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                    val bIdx = findClosestIndex(distancesKm, boundaryKm.toFloat())
                    val by = yCoords.getOrElse(bIdx) { height / 2f }
                    drawCircle(
                        color = casingColor,
                        radius = 7.dp.toPx(),
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = overnightColor,
                        radius = 5.dp.toPx(),
                        center = Offset(bx, by)
                    )
                }
            }

            if (scrubFraction in 0f..1f) {
                val scrubX = scrubFraction * width
                drawLine(
                    color = onSurfaceColor,
                    start = Offset(scrubX, 0f),
                    end = Offset(scrubX, height),
                    strokeWidth = 1.5.dp.toPx()
                )

                val scrubIdx = if (totalKm > 0f) {
                    findClosestIndex(distancesKm, scrubFraction * totalKm)
                } else {
                    ((scrubFraction * lastIdx).toInt()).coerceIn(0, lastIdx)
                }
                val dotX = xCoords.getOrElse(scrubIdx) { scrubX }
                val dotY = yCoords.getOrElse(scrubIdx) { height / 2f }

                drawCircle(
                    color = casingColor,
                    radius = 5.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
                drawCircle(
                    color = primaryColor,
                    radius = 3.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }
    }
}

private fun findClosestIndex(distances: FloatArray, targetKm: Float): Int {
    if (distances.isEmpty()) return 0
    var closestIdx = 0
    var minDiff = Float.MAX_VALUE
    for (i in distances.indices) {
        val diff = abs(distances[i] - targetKm)
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
    val c = 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceIn(0.0, 1.0)))
    return r * c
}
