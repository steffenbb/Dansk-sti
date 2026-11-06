package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.TurnCue
import com.example.service.NavigationLiveState
import com.example.ui.components.formatDanishKm
import com.example.ui.theme.CardShape
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.viewmodel.NavigationMapMode
import com.example.util.routing.TurnCueGenerator
import kotlin.math.roundToInt

@Composable
fun NavigationTopCueAndAlertsStack(
    activeCueWithin500m: Pair<TurnCue, Double>?,
    cueBannerPrimaryText: String,
    topCardHeadline: String,
    liveState: NavigationLiveState,
    deviceHeadingDegrees: Float?,
    mapMode: NavigationMapMode,
    routeBackErrorMessage: String?,
    isGpsWeakOrStale: Boolean,
    gpsStaleMinutes: Int,
    isDaylightWarningVisible: Boolean,
    distanceRemainingToday: Double,
    sunsetTime: String,
    isLocationPermissionMissing: Boolean,
    onBack: () -> Unit,
    onRerouteBack: () -> Unit,
    onTakeDetour: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
    corridorPoisErrorMessage: String? = null,
    onRetryCorridorPois: () -> Unit = {}
) {
    val danskStiColors = DanskStiTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isImmediateTurn = activeCueWithin500m != null &&
            activeCueWithin500m.second <= TurnCueGenerator.CUE_FIRST_ALERT_METERS
        Surface(
            shape = CardShape,
            color = if (isImmediateTurn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nav_top_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("nav_cue_banner")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("nav_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                            tint = if (isImmediateTurn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isImmediateTurn) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            TurnCueDirectionIcon(
                                cue = activeCueWithin500m?.first,
                                tint = if (isImmediateTurn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cueBannerPrimaryText,
                            style = TabularBodyStyle.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            color = if (isImmediateTurn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("nav_cue_primary_text")
                        )
                        Text(
                            text = topCardHeadline,
                            style = TabularBodyStyle.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isImmediateTurn) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("nav_top_headline_text")
                        )
                    }
                }
            }
        }

        if (liveState.isOffTrailWarning && !liveState.isDetourMuted) {
            val offMeters = liveState.offTrailDistanceMeters.roundToInt().coerceAtLeast(1)
            val effectiveHeading = deviceHeadingDegrees ?: liveState.deviceHeadingDegrees
            val (arrowRotationDeg, compassDirectionText) = remember(
                liveState.bearingToPointAhead,
                effectiveHeading,
                mapMode
            ) {
                computeOffRouteDirectionOrArrow(
                    bearingToPointAhead = liveState.bearingToPointAhead,
                    deviceHeadingDegrees = effectiveHeading,
                    mapMode = mapMode
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = danskStiColors.offRoute,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_off_route_banner")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (arrowRotationDeg != null) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onError.copy(alpha = 0.2f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = stringResource(R.string.nav_direction_towards_route),
                                        tint = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .rotate(arrowRotationDeg)
                                            .testTag("nav_off_route_arrow")
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.nav_off_route_banner_format, offMeters),
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("nav_off_route_text")
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.onError.copy(alpha = 0.22f),
                                modifier = Modifier.testTag("nav_off_route_direction_text")
                            ) {
                                Text(
                                    text = compassDirectionText,
                                    color = MaterialTheme.colorScheme.onError,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.nav_off_route_banner_with_dir_format,
                                    offMeters,
                                    compassDirectionText
                                ),
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("nav_off_route_text")
                            )
                        }
                    }
                    if (!routeBackErrorMessage.isNullOrBlank()) {
                        Text(
                            text = routeBackErrorMessage,
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("nav_route_back_error_text")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRerouteBack,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onError,
                                contentColor = danskStiColors.offRoute
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("nav_reroute_back_button")
                        ) {
                            Text(
                                text = stringResource(R.string.nav_reroute_back),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        OutlinedButton(
                            onClick = onTakeDetour,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onError),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onError),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("nav_take_detour_button")
                        ) {
                            Text(
                                text = stringResource(R.string.nav_taking_detour),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        if (isGpsWeakOrStale) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("nav_gps_weak_chip")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.nav_gps_weak_minutes_format, gpsStaleMinutes),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }

        if (isDaylightWarningVisible) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_daylight_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.nav_daylight_warning_format,
                            formatDanishKm(distanceRemainingToday, 1),
                            sunsetTime
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                }
            }
        }

        liveState.privateForestSunsetWarning?.let { warningText ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_sunset_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = warningText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                        Text(
                            text = stringResource(R.string.nav_private_forest_hours_note),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        if (isLocationPermissionMissing) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, danskStiColors.warning),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("location_permission_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.location_permission_required_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.location_permission_required_explanation),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRequestLocationPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("grant_location_permission_button")
                    ) {
                        Text(
                            text = stringResource(R.string.grant_location_permission_action),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (!corridorPoisErrorMessage.isNullOrBlank()) {
            Surface(
                onClick = onRetryCorridorPois,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("nav_corridor_pois_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = corridorPoisErrorMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("nav_corridor_pois_error_text")
                    )
                    OutlinedButton(
                        onClick = onRetryCorridorPois,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 36.dp)
                            .testTag("nav_corridor_pois_retry_button")
                    ) {
                        Text(
                            text = stringResource(R.string.nav_corridor_pois_retry_button),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TurnCueDirectionIcon(
    cue: TurnCue?,
    tint: Color
) {
    val icon = when (cue?.direction) {
        "LEFT" -> Icons.Default.TurnLeft
        "SLIGHT_LEFT" -> Icons.Default.TurnSlightLeft
        "SHARP_LEFT" -> Icons.Default.TurnSharpLeft
        "RIGHT" -> Icons.Default.TurnRight
        "SLIGHT_RIGHT" -> Icons.Default.TurnSlightRight
        "SHARP_RIGHT" -> Icons.Default.TurnSharpRight
        "UTURN" -> Icons.Default.UTurnLeft
        else -> Icons.AutoMirrored.Filled.ArrowForward
    }
    Icon(
        imageVector = icon,
        contentDescription = cue?.instructionText ?: "Næste på ruten",
        tint = tint,
        modifier = Modifier
            .size(26.dp)
            .testTag("nav_cue_arrow_icon")
    )
}

/**
 * Round 10B Item 2:
 * Remaps the rotation matrix from the rotation vector sensor using [android.hardware.SensorManager.remapCoordinateSystem]
 * according to the current display rotation (`ROTATION_0`, `ROTATION_90`, `ROTATION_180`, `ROTATION_270`)
 * and returns the compass azimuth in `[0, 360)`.
 */
fun computeRemappedCompassAzimuthDegrees(
    rotationVectorValues: FloatArray,
    displayRotation: Int
): Float? {
    if (rotationVectorValues.size < 3) return null
    val rotationMatrix = FloatArray(9)
    try {
        android.hardware.SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValues)
    } catch (_: Exception) {
        return null
    }
    return computeRemappedCompassAzimuthFromMatrix(rotationMatrix, displayRotation)
}

/**
 * Computes the remapped compass azimuth in `[0, 360)` from a 3x3 rotation matrix and display rotation
 * using [android.hardware.SensorManager.remapCoordinateSystem].
 */
fun computeRemappedCompassAzimuthFromMatrix(
    rotationMatrix: FloatArray,
    displayRotation: Int
): Float? {
    if (rotationMatrix.size < 9) return null
    val (axisX, axisY) = when (displayRotation) {
        android.view.Surface.ROTATION_90 ->
            android.hardware.SensorManager.AXIS_Y to android.hardware.SensorManager.AXIS_MINUS_X
        android.view.Surface.ROTATION_180 ->
            android.hardware.SensorManager.AXIS_MINUS_X to android.hardware.SensorManager.AXIS_MINUS_Y
        android.view.Surface.ROTATION_270 ->
            android.hardware.SensorManager.AXIS_MINUS_Y to android.hardware.SensorManager.AXIS_X
        else ->
            android.hardware.SensorManager.AXIS_X to android.hardware.SensorManager.AXIS_Y
    }
    val remappedMatrix = FloatArray(9)
    val remappedOk = android.hardware.SensorManager.remapCoordinateSystem(
        rotationMatrix,
        axisX,
        axisY,
        remappedMatrix
    )
    val matrixToUse = if (remappedOk) remappedMatrix else rotationMatrix
    val orientation = FloatArray(3)
    android.hardware.SensorManager.getOrientation(matrixToUse, orientation)
    val azimuthRad = orientation[0].toDouble()
    if (azimuthRad.isNaN()) return null
    return ((Math.toDegrees(azimuthRad).toFloat() % 360f) + 360f) % 360f
}

/**
 * Round 10B Item 2:
 * Single heading source shared by the heading-up map and the off-route arrow:
 * - Uses the compass below 1.5 km/h or when there is no GPS course.
 * - Uses the GPS course otherwise (at >= 1.5 km/h with a valid GPS course).
 */
fun resolveNavigationHeadingDegrees(
    speedKmh: Double,
    gpsCourseDegrees: Float?,
    hasGpsCourse: Boolean,
    compassHeadingDegrees: Float?
): Float? {
    val validGpsCourse = hasGpsCourse && gpsCourseDegrees != null && !gpsCourseDegrees.isNaN()
    return if (speedKmh < 1.5 || !validGpsCourse) {
        compassHeadingDegrees?.takeIf { !it.isNaN() }?.let { ((it % 360f) + 360f) % 360f }
    } else {
        ((gpsCourseDegrees!! % 360f) + 360f) % 360f
    }
}

/**
 * Round 9A Item 4:
 * Computes the off-route arrow rotation (or null if device heading is unavailable)
 * and the Danish compass direction text (e.g., "mod NØ").
 */
fun computeOffRouteDirectionOrArrow(
    bearingToPointAhead: Float,
    deviceHeadingDegrees: Float?,
    mapMode: NavigationMapMode
): Pair<Float?, String> {
    val normalizedBearing = ((bearingToPointAhead % 360f) + 360f) % 360f
    val compassLabels = arrayOf("N", "NØ", "Ø", "SØ", "S", "SV", "V", "NV")
    val sectorIndex = (((normalizedBearing + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
    val textDirection = "mod ${compassLabels[sectorIndex]}"
    if (deviceHeadingDegrees == null || deviceHeadingDegrees.isNaN()) {
        return null to textDirection
    }
    val arrowRotation = when (mapMode) {
        NavigationMapMode.FOLLOW_HEADING_UP -> ((normalizedBearing - deviceHeadingDegrees) % 360f + 360f) % 360f
        NavigationMapMode.FOLLOW_NORTH_UP, NavigationMapMode.OVERVIEW -> normalizedBearing
    }
    return arrowRotation to textDirection
}

fun updateKeepScreenOnWindowFlag(
    window: Window?,
    isNavigating: Boolean,
    keepScreenOnEnabled: Boolean
) {
    if (window == null) return
    if (isNavigating && keepScreenOnEnabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val params = window.attributes
        if (params != null && params.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
    }
}

fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
internal fun ObserveCompassSensorHeading(
    context: Context,
    activity: Activity?,
    onHeadingDegrees: (Float) -> Unit
) {
    DisposableEffect(context, activity) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (event?.sensor?.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR && event.values != null) {
                    val displayRotation = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity?.display?.rotation
                                ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
                                ?: android.view.Surface.ROTATION_0
                        } else {
                            @Suppress("DEPRECATION")
                            (activity?.windowManager?.defaultDisplay?.rotation
                                ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
                                ?: android.view.Surface.ROTATION_0)
                        }
                    } catch (_: Exception) {
                        android.view.Surface.ROTATION_0
                    }
                    val azimuthDeg = computeRemappedCompassAzimuthDegrees(event.values, displayRotation)
                    if (azimuthDeg != null) {
                        onHeadingDegrees(azimuthDeg)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (rotationSensor != null) {
            try {
                sensorManager.registerListener(listener, rotationSensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
            } catch (_: Exception) {}
        }
        onDispose {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (_: Exception) {}
        }
    }
}


