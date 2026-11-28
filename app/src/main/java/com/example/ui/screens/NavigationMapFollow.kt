package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.PlannedRoute
import com.example.data.model.TripStage
import com.example.ui.theme.Motion
import com.example.ui.viewmodel.NavigationMapMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/** After the user moves the navigation map, follow mode comes back after this long without touch. */
const val NAV_FOLLOW_RESUME_AFTER_MS = 30_000L

/**
 * Camera behaviour for the navigation map (audit fix batch 2).
 *
 * - Entering a mode sets it up once: follow modes re-enable following; overview fits the day's
 *   stretch once, so the user can then zoom and pan in it.
 * - While following, the camera tracks position (and heading in heading-up mode).
 * - Once the user drags the map ([isFollowingUser] false) the camera leaves it alone, until
 *   "Følg mig"/my-location is pressed or [NAV_FOLLOW_RESUME_AFTER_MS] pass without touch.
 */
@Composable
fun NavigationCameraEffects(
    mapView: MapView?,
    mapMode: NavigationMapMode,
    lat: Double?,
    lng: Double?,
    headingDegrees: Float,
    targetAutoZoom: Double,
    plannedRoute: PlannedRoute?,
    currentStage: TripStage?,
    currentDay: Int,
    isFollowingUser: Boolean,
    /** Read inside the effect only, so touch events don't recompose the navigation screen. */
    lastTouchTimeMs: () -> Long,
    onFollowingChange: (Boolean) -> Unit
) {
    // 1. Mode entry (and a new route/day in overview)
    LaunchedEffect(mapView, mapMode, plannedRoute, if (mapMode == NavigationMapMode.OVERVIEW) currentDay else 0) {
        val mv = mapView ?: return@LaunchedEffect
        when (mapMode) {
            NavigationMapMode.FOLLOW_NORTH_UP, NavigationMapMode.FOLLOW_HEADING_UP -> {
                onFollowingChange(true)
                if (mapMode == NavigationMapMode.FOLLOW_NORTH_UP) mv.mapOrientation = 0f
            }
            NavigationMapMode.OVERVIEW -> {
                onFollowingChange(false)
                mv.mapOrientation = 0f
                overviewBounds(plannedRoute, currentStage, currentDay)?.let { box ->
                    runCatching { mv.zoomToBoundingBox(box, true, 64) }
                }
            }
        }
        mv.invalidate()
    }

    // 2. Following: position, heading and auto-zoom, only while the user hasn't moved the map
    val followHeading = mapMode == NavigationMapMode.FOLLOW_HEADING_UP
    LaunchedEffect(mapView, mapMode, isFollowingUser, lat, lng, if (followHeading) headingDegrees else 0f, targetAutoZoom) {
        val mv = mapView ?: return@LaunchedEffect
        if (mapMode == NavigationMapMode.OVERVIEW || !isFollowingUser) return@LaunchedEffect
        mv.mapOrientation = if (followHeading) -headingDegrees else 0f
        if (kotlin.math.abs(mv.zoomLevelDouble - targetAutoZoom) >= 0.4) mv.controller.setZoom(targetAutoZoom)
        if (lat != null && lng != null) mv.controller.animateTo(GeoPoint(lat, lng))
        mv.invalidate()
    }

    // 3. Resume following after a while without touch
    LaunchedEffect(isFollowingUser, mapMode) {
        if (isFollowingUser || mapMode == NavigationMapMode.OVERVIEW) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            if (System.currentTimeMillis() - lastTouchTimeMs() >= NAV_FOLLOW_RESUME_AFTER_MS) {
                onFollowingChange(true)
                break
            }
        }
    }
}

/** The day's stretch (or the whole route) with a small margin, or null when there is nothing to fit. */
internal fun overviewBounds(route: PlannedRoute?, stage: TripStage?, currentDay: Int): BoundingBox? {
    route ?: return null
    val st = stage ?: route.stages.getOrNull(currentDay - 1) ?: route.stages.firstOrNull()
    val pts = if (st != null) {
        route.points.filter { it.walkKm in st.startWalkKm..st.endWalkKm }.ifEmpty { route.points }
    } else route.points
    if (pts.size < 2) return null
    return BoundingBox(
        pts.maxOf { it.lat } + 0.005, pts.maxOf { it.lng } + 0.005,
        pts.minOf { it.lat } - 0.005, pts.minOf { it.lng } - 0.005
    )
}

/** "Følg mig": shown while the user has moved the navigation map away from their position. */
@Composable
fun NavigationFollowMeChip(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = Motion.ExpandEnter, exit = Motion.ExpandExit, modifier = modifier) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.testTag("nav_follow_me_chip")
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.nav_follow_me), fontWeight = FontWeight.Bold)
        }
    }
}

/** True when both lists hold the very same objects in the same order (cheap change check per GPS fix). */
internal fun sameRefs(a: List<Any?>, b: List<Any?>?): Boolean =
    b != null && a.size == b.size && a.indices.all { a[it] === b[it] }

/** Asks before replacing the trip that is being tracked with another one (audit fix batch 2). */
@Composable
fun NavigationReplaceTripDialog(
    prompt: com.example.ui.viewmodel.ReplaceTripPrompt,
    onReplace: () -> Unit,
    onKeepActive: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onKeepActive,
        modifier = Modifier.testTag("nav_replace_trip_dialog"),
        title = { Text(stringResource(R.string.nav_replace_trip_title), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                stringResource(
                    R.string.nav_replace_trip_text_fmt,
                    prompt.activeTrailName.ifBlank { stringResource(R.string.nav_replace_trip_unnamed) }
                )
            )
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = onReplace, modifier = Modifier.testTag("nav_replace_trip_confirm")) {
                Text(stringResource(R.string.nav_replace_trip_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onKeepActive, modifier = Modifier.testTag("nav_replace_trip_keep")) {
                Text(stringResource(R.string.nav_replace_trip_keep))
            }
        }
    )
}

/** Idle dimming (only when the user turns it on): after this long without touch… */
const val NAV_IDLE_DIM_AFTER_MS = 30_000L
/** …to this window brightness (was a fixed 0.15, unreadable outdoors). */
const val NAV_IDLE_DIM_BRIGHTNESS = 0.25f

/** Changes whenever something the walker should look at appears; null when nothing does. */
internal fun navWakeKey(state: com.example.service.NavigationLiveState): Any? {
    val cue = state.nextTurnCue
    val cueBucket = state.distanceToNextCueMeters?.let { d ->
        when {
            d <= com.example.util.routing.TurnCueGenerator.CUE_SECOND_ALERT_METERS -> 2
            d <= com.example.util.routing.TurnCueGenerator.CUE_FIRST_ALERT_METERS -> 1
            else -> null
        }
    }
    val parts = listOfNotNull(
        if (cue != null && cueBucket != null) "cue_${cue.walkKm}_$cueBucket" else null,
        if (state.isOffTrailWarning) "offroute" else null,
        if (state.hasArrivedAtTonightCamp) "camp_${state.currentDay}" else null
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
}

internal fun restoreWindowBrightness(window: android.view.Window?) {
    val params = window?.attributes ?: return
    if (params.screenBrightness != android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
        params.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }
}

/** "Dæmp skærmen efter 30 sek." in the navigation menu; only meaningful while the screen is kept on. */
@Composable
fun NavigationDimScreenMenuItem(enabledByKeepScreenOn: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = androidx.compose.runtime.remember(context) { com.example.DanskStiApp.getSettingsRepository(context) }
    val settings = repo.settingsFlow.collectAsStateWithInitial(repo)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val checked = settings.value.dimScreenWhenIdle
    val set: (Boolean) -> Unit = { v -> scope.launch { repo.setDimScreenWhenIdle(v) } }
    androidx.compose.material3.DropdownMenuItem(
        enabled = enabledByKeepScreenOn,
        text = {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                Text(text = stringResource(R.string.nav_dim_screen_when_idle), modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = checked,
                    enabled = enabledByKeepScreenOn,
                    onCheckedChange = com.example.ui.components.hapticToggle(set),
                    modifier = Modifier.testTag("nav_dim_screen_switch")
                )
            }
        },
        onClick = { set(!checked) },
        modifier = Modifier.testTag("nav_dim_screen_menu_item")
    )
}

@Composable
private fun kotlinx.coroutines.flow.Flow<com.example.data.local.AppSettings>.collectAsStateWithInitial(
    repo: com.example.data.local.SettingsRepository
): androidx.compose.runtime.State<com.example.data.local.AppSettings> =
    collectAsState(initial = androidx.compose.runtime.remember(repo) { repo.currentSettingsSnapshot() })
