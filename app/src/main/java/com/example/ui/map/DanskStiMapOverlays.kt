package com.example.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import com.example.ui.theme.pressScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.MapLayersState
import com.example.ui.components.MapAttributionPill
import com.example.ui.components.OfflineStatusPill
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

@Composable
internal fun BoxScope.DanskStiMapBottomAttributionAndBanners(
    isDeviceOnline: Boolean,
    layerErrorMessages: Set<String>,
    transientMessage: String? = null,
    onClearTransientMessage: () -> Unit = {},
    staleText: String?,
    locationErrorMessage: String?,
    onClearLocationError: () -> Unit,
    mapLayersState: MapLayersState,
    effectiveIsDark: Boolean,
    attributionStartPadding: Dp,
    attributionBottomPadding: Dp
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = attributionStartPadding, bottom = attributionBottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!isDeviceOnline) {
            OfflineStatusPill()
        }
        for (errMsg in layerErrorMessages) {
            Surface(
                shape = com.example.ui.theme.TileShape,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
                modifier = Modifier.testTag("map_layer_error_banner")
            ) {
                Text(
                    text = errMsg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        if (transientMessage != null) {
            Surface(
                shape = com.example.ui.theme.TileShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                modifier = Modifier
                    .clickable { onClearTransientMessage() }
                    .testTag("map_transient_message")
            ) {
                Text(
                    text = transientMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (staleText != null) {
            Surface(
                shape = com.example.ui.theme.TileShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                modifier = Modifier.testTag("map_stale_location_badge")
            ) {
                Text(
                    text = staleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (locationErrorMessage != null) {
            Surface(
                shape = com.example.ui.theme.TileShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .clickable { onClearLocationError() }
                    .testTag("map_location_error_banner")
            ) {
                Text(
                    text = locationErrorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        MapAttributionPill(
            layersState = mapLayersState,
            isDarkMode = effectiveIsDark,
            modifier = Modifier.testTag("map_attribution_pill")
        )
    }
}

@Composable
internal fun BoxScope.DanskStiMapStandardButtonsColumn(
    controlsTopPadding: Dp,
    controlsEndPadding: Dp,
    layersButtonTestTag: String,
    compassButtonTestTag: String,
    myLocationButtonTestTag: String,
    currentBearing: Float,
    isRequestingCurrentLocation: Boolean,
    onOpenLayersSheet: () -> Unit,
    onResetCompass: () -> Unit,
    onMyLocationButtonClick: () -> Unit
) {
    val mapFabSource1 = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val mapFabSource2 = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val mapFabSource3 = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = controlsTopPadding, end = controlsEndPadding)
            .testTag("map_controls_column"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        SmallFloatingActionButton(
            interactionSource = mapFabSource1,
            onClick = onOpenLayersSheet,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = primaryColor,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
            modifier = Modifier.pressScale(mapFabSource1, 0.92f)
                .size(48.dp)
                .testTag(layersButtonTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = stringResource(R.string.ui_danskstimap_kortlag_29)
            )
        }

        SmallFloatingActionButton(
            interactionSource = mapFabSource2,
            onClick = onResetCompass,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = if (currentBearing != 0f) primaryColor else MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
            modifier = Modifier.pressScale(mapFabSource2, 0.92f)
                .size(48.dp)
                .testTag(compassButtonTestTag)
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = stringResource(R.string.ui_danskstimap_kompas_nord_op_30),
                modifier = Modifier.rotate(-currentBearing)
            )
        }

        SmallFloatingActionButton(
            interactionSource = mapFabSource3,
            onClick = onMyLocationButtonClick,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = primaryColor,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
            modifier = Modifier.pressScale(mapFabSource3, 0.92f)
                .size(48.dp)
                .testTag(myLocationButtonTestTag)
        ) {
            if (isRequestingCurrentLocation) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .testTag("my_location_spinner"),
                    strokeWidth = 2.5.dp,
                    color = primaryColor
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_btn_my_location)
                )
            }
        }
    }
}

/**
 * Renders the user's GPS position dot with a translucent accuracy circle and directional heading cone.
 * Greys out the dot when the fix is stale (> 2 minutes old).
 */
class UserLocationHeadingOverlay : Overlay() {

    private var location: GeoPoint? = null
    private var headingDegrees: Float? = null
    private var accuracyMeters: Float = 15f
    var isStale: Boolean = false
        private set

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(42, 30, 136, 229)
    }
    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(115, 30, 136, 229)
        strokeWidth = 2f
    }
    private val headingConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 30, 136, 229)
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(25, 118, 210)
    }

    private val screenPt = Point()
    private val conePath = Path()

    // UI audit batch 4: glide between GPS fixes and a short halo pulse after each fresh fix.
    // Both are finite (no endless loop) and off when the system's animations are off.
    private var glideFrom: GeoPoint? = null
    private var glideStartMs = 0L
    private var fixAtMs = 0L
    private val drawnPoint = GeoPoint(0.0, 0.0)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun animationsOn(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Where the dot is drawn at [nowMs]: on the way from the previous fix to the new one, eased out. */
    internal fun displayedPosition(nowMs: Long = android.os.SystemClock.uptimeMillis()): GeoPoint? {
        val to = location ?: return null
        val from = glideFrom
        val elapsed = nowMs - glideStartMs
        if (from == null || !animationsOn() || elapsed >= GLIDE_MS) return to
        val t = (elapsed.toFloat() / GLIDE_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        drawnPoint.setCoords(
            from.latitude + (to.latitude - from.latitude) * eased,
            from.longitude + (to.longitude - from.longitude) * eased
        )
        return drawnPoint
    }

    fun updateLocation(
        location: GeoPoint?,
        headingDegrees: Float?,
        accuracyMeters: Float,
        isStale: Boolean = false
    ) {
        val previous = this.location
        if (location != null && (previous == null || previous.latitude != location.latitude || previous.longitude != location.longitude)) {
            val now = android.os.SystemClock.uptimeMillis()
            // Glide only for normal walking steps; a big jump (first fix, teleport after a gap) moves at once
            val startFrom = if (previous != null) GeoPoint(displayedPosition(now) ?: previous) else null
            glideFrom = startFrom?.takeIf { it.distanceToAsDouble(location) < GLIDE_MAX_METERS }
            glideStartMs = now
            if (!isStale) fixAtMs = now
        }
        this.location = location
        this.headingDegrees = headingDegrees
        this.accuracyMeters = accuracyMeters.coerceAtLeast(3f)
        this.isStale = isStale
        if (isStale) {
            accuracyFillPaint.color = Color.argb(30, 140, 140, 140)
            accuracyStrokePaint.color = Color.argb(90, 130, 130, 130)
            headingConePaint.color = Color.argb(65, 140, 140, 140)
            dotPaint.color = Color.rgb(142, 142, 147)
        } else {
            accuracyFillPaint.color = Color.argb(42, 30, 136, 229)
            accuracyStrokePaint.color = Color.argb(115, 30, 136, 229)
            headingConePaint.color = Color.argb(95, 30, 136, 229)
            dotPaint.color = Color.rgb(25, 118, 210)
        }
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null || !isEnabled) return
        val now = android.os.SystemClock.uptimeMillis()
        val loc = displayedPosition(now) ?: return
        val proj = mapView.projection ?: return
        if (loc !== location) mapView.postInvalidateOnAnimation()

        proj.toPixels(loc, screenPt)
        val cx = screenPt.x.toFloat()
        val cy = screenPt.y.toFloat()
        val density = mapView.context.resources.displayMetrics.density

        val metersPerPixel = proj.metersToPixels(accuracyMeters)
        if (metersPerPixel > 6f * density && metersPerPixel < canvas.width * 1.5f) {
            canvas.drawCircle(cx, cy, metersPerPixel, accuracyFillPaint)
            canvas.drawCircle(cx, cy, metersPerPixel, accuracyStrokePaint)
        }

        val heading = headingDegrees
        if (heading != null && !heading.isNaN() && !isStale) {
            val coneLen = 26f * density
            val halfSpread = 13f * density
            canvas.save()
            canvas.rotate(heading, cx, cy)
            conePath.reset()
            conePath.moveTo(cx, cy)
            conePath.lineTo(cx - halfSpread, cy - coneLen)
            conePath.lineTo(cx + halfSpread, cy - coneLen)
            conePath.close()
            canvas.drawPath(conePath, headingConePaint)
            canvas.restore()
        }

        val sinceFix = now - fixAtMs
        if (!isStale && fixAtMs != 0L && animationsOn() && sinceFix < PULSE_MS * PULSE_COUNT) {
            val phase = (sinceFix % PULSE_MS).toFloat() / PULSE_MS
            pulsePaint.color = dotPaint.color
            pulsePaint.alpha = ((1f - phase) * 90).toInt()
            canvas.drawCircle(cx, cy, (8.5f + 16f * phase) * density, pulsePaint)
            mapView.postInvalidateOnAnimation()
        }

        canvas.drawCircle(cx, cy, 8.5f * density, haloPaint)
        canvas.drawCircle(cx, cy, 6.2f * density, dotPaint)
    }

    companion object {
        const val GLIDE_MS = 800L
        const val GLIDE_MAX_METERS = 250.0
        const val PULSE_MS = 1600L
        const val PULSE_COUNT = 2
    }
}
