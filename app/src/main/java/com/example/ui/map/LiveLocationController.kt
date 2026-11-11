package com.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Round 10A Item 8:
 * - While a map screen is visible, request location updates (FusedLocationProviderClient, balanced accuracy, every 5 s)
 *   and stop them in onPause.
 * - Show the dot greyed out with "sidst set X min siden" when the fix is older than 2 minutes.
 * - "Min position" requests a fresh fix (getCurrentLocation) with a short spinner,
 *   and says "Kan ikke finde din position" if none comes within 15 s.
 * - The long-press card's distance uses a fix less than 2 minutes old, otherwise "ukendt".
 */
data class FreshLocationResolution(
    val location: Location?,
    val isOldFix: Boolean,
    val ageMinutes: Long = 0L,
    val staleMessage: String? = null
)

@Stable
class LiveMapLocationState(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val UPDATE_INTERVAL_MS = 5_000L
        const val MIN_UPDATE_INTERVAL_MS = 2_500L
        const val STALE_THRESHOLD_MS = 120_000L // 2 minutes
        const val CURRENT_LOCATION_TIMEOUT_MS = 15_000L
        const val CANNOT_FIND_LOCATION_MESSAGE = "Kan ikke finde din position"

        @Volatile
        var testClockNowMs: Long? = null

        @Volatile
        var testInitialLocation: Location? = null

        @Volatile
        var testCurrentLocationProvider: (suspend () -> Location?)? = null

        @Volatile
        var testLastKnownFallbackProvider: (() -> Location?)? = null

        fun currentTimeMillis(): Long = testClockNowMs ?: System.currentTimeMillis()

        fun isFixFresh(fixTimeMs: Long, nowMs: Long = currentTimeMillis()): Boolean {
            if (fixTimeMs <= 0L) return false
            return (nowMs - fixTimeMs) <= STALE_THRESHOLD_MS
        }

        fun formatStaleBadge(fixTimeMs: Long, nowMs: Long = currentTimeMillis()): String? {
            if (fixTimeMs <= 0L) return null
            val ageMs = nowMs - fixTimeMs
            if (ageMs <= STALE_THRESHOLD_MS) return null
            val mins = max(2L, ageMs / 60_000L)
            return "sidst set $mins min siden"
        }

        fun formatOldFixMessage(fixTimeMs: Long, nowMs: Long = currentTimeMillis()): String? {
            if (fixTimeMs <= 0L) return null
            val ageMs = nowMs - fixTimeMs
            if (ageMs <= STALE_THRESHOLD_MS) return null
            val mins = max(1L, ageMs / 60_000L)
            return "Position fra for $mins min siden"
        }

        @SuppressLint("MissingPermission")
        fun readAnyLastKnownLocation(context: Context): Location? {
            testLastKnownFallbackProvider?.let { provider ->
                provider.invoke()?.let { return it }
            }
            testInitialLocation?.let { return it }
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return null
            return try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
                val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                val passive = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
                listOfNotNull(gps, net, passive).maxByOrNull { it.time }
            } catch (_: SecurityException) {
                null
            }
        }

        /**
         * Round 12A Item 6:
         * - Requests a fresh fix (getCurrentLocation, up to 15 s).
         * - If only an old fix exists, returns it with isOldFix = true and staleMessage = "Position fra for X min siden".
         */
        suspend fun resolveFreshOrFallbackLocation(
            context: Context,
            timeoutMs: Long = CURRENT_LOCATION_TIMEOUT_MS,
            fallbackCandidate: Location? = null
        ): FreshLocationResolution {
            val fresh = awaitFreshLocationWithTimeout(context, timeoutMs)
            val now = currentTimeMillis()
            if (fresh != null) {
                val fixTime = fresh.time
                if (fixTime <= 0L || isFixFresh(fixTime, now)) {
                    return FreshLocationResolution(
                        location = fresh,
                        isOldFix = false,
                        ageMinutes = 0L,
                        staleMessage = null
                    )
                } else {
                    val mins = max(1L, (now - fixTime) / 60_000L)
                    return FreshLocationResolution(
                        location = fresh,
                        isOldFix = true,
                        ageMinutes = mins,
                        staleMessage = "Position fra for $mins min siden"
                    )
                }
            }

            val oldLoc = testLastKnownFallbackProvider?.invoke()
                ?: fallbackCandidate
                ?: readAnyLastKnownLocation(context)
            if (oldLoc != null) {
                val fixTime = oldLoc.time.takeIf { it > 0L } ?: (now - STALE_THRESHOLD_MS - 60_000L)
                val mins = max(1L, (now - fixTime) / 60_000L)
                return FreshLocationResolution(
                    location = oldLoc,
                    isOldFix = true,
                    ageMinutes = mins,
                    staleMessage = "Position fra for $mins min siden"
                )
            }

            return FreshLocationResolution(
                location = null,
                isOldFix = false,
                ageMinutes = 0L,
                staleMessage = null
            )
        }

        /**
         * Round 10C Item 8: Request a fresh GPS fix (as in Round 10A Item 8) with a 15 s timeout.
         * Returns null if no fix arrives within timeoutMs.
         */
        @SuppressLint("MissingPermission")
        suspend fun awaitFreshLocationWithTimeout(
            context: Context,
            timeoutMs: Long = CURRENT_LOCATION_TIMEOUT_MS
        ): Location? {
            val customProvider = testCurrentLocationProvider
            if (customProvider != null) {
                return withTimeoutOrNull(timeoutMs) {
                    customProvider.invoke()
                }
            }

            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) return null

            val tokenSource = CancellationTokenSource()
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        tokenSource.cancel()
                    }
                    try {
                        val fused = LocationServices.getFusedLocationProviderClient(context)
                        fused.getCurrentLocation(
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                            tokenSource.token
                        ).addOnSuccessListener { loc ->
                            if (!cont.isActive) return@addOnSuccessListener
                            if (loc != null && (loc.time <= 0L || isFixFresh(loc.time))) {
                                cont.resume(loc)
                            } else {
                                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                val gps = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                                val net = runCatching { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                                val best = when {
                                    gps != null && net != null -> if (gps.time >= net.time) gps else net
                                    else -> gps ?: net
                                }
                                if (best != null && (best.time <= 0L || isFixFresh(best.time))) {
                                    cont.resume(best)
                                } else {
                                    cont.resume(null)
                                }
                            }
                        }.addOnFailureListener {
                            if (!cont.isActive) return@addOnFailureListener
                            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                            val gps = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                            val net = runCatching { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                            val best = when {
                                gps != null && net != null -> if (gps.time >= net.time) gps else net
                                else -> gps ?: net
                            }
                            if (best != null && (best.time <= 0L || isFixFresh(best.time))) {
                                cont.resume(best)
                            } else {
                                cont.resume(null)
                            }
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                }
            }
        }
    }

    val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    var location by mutableStateOf<Location?>(testInitialLocation ?: readInitialLastKnownLocation(context))
        private set

    var fixTimestampMs by mutableLongStateOf(location?.time?.takeIf { it > 0L } ?: 0L)
        private set

    var nowMs by mutableLongStateOf(currentTimeMillis())
        private set

    var isReceivingUpdates by mutableStateOf(false)
        private set

    var isRequestingCurrentLocation by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    val hasLocation: Boolean
        get() = location != null

    val isStale: Boolean
        get() {
            val loc = location ?: return false
            val ts = if (fixTimestampMs > 0L) fixTimestampMs else loc.time
            if (ts <= 0L) return false
            return !isFixFresh(ts, nowMs)
        }

    val isFresh: Boolean
        get() {
            val loc = location ?: return false
            val ts = if (fixTimestampMs > 0L) fixTimestampMs else loc.time
            return isFixFresh(ts, nowMs)
        }

    val freshLocationOrNull: Location?
        get() = if (isFresh) location else null

    val staleBadgeText: String?
        get() {
            val loc = location ?: return null
            val ts = if (fixTimestampMs > 0L) fixTimestampMs else loc.time
            return formatStaleBadge(ts, nowMs)
        }

    val geoPoint: GeoPoint?
        get() = location?.let { GeoPoint(it.latitude, it.longitude) }

    val accuracyMeters: Float?
        get() = location?.takeIf { it.hasAccuracy() }?.accuracy

    val headingDegrees: Float?
        get() = location?.takeIf { it.hasBearing() }?.bearing

    val locationErrorMessage: String?
        get() = statusMessage

    fun clearErrorMessage() {
        statusMessage = null
    }

    private var activeCancellationToken: CancellationTokenSource? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val latest = result.lastLocation ?: return
            onNewLocationFix(latest)
        }
    }

    fun onNewLocationFix(newLocation: Location, overrideTimestampMs: Long? = null) {
        val now = currentTimeMillis()
        val ts = overrideTimestampMs ?: newLocation.time.takeIf { it > 0L } ?: now
        location = newLocation
        fixTimestampMs = ts
        nowMs = now
        if (statusMessage == CANNOT_FIND_LOCATION_MESSAGE) {
            statusMessage = null
        }
    }

    fun tickClock(overrideNowMs: Long = currentTimeMillis()) {
        nowMs = overrideNowMs
    }

    fun clearStatusMessage() {
        statusMessage = null
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (isReceivingUpdates) return

        readInitialLastKnownLocation(context)?.let { last ->
            if (location == null || last.time > fixTimestampMs) {
                onNewLocationFix(last, last.time.takeIf { it > 0L })
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        runCatching {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            isReceivingUpdates = true
        }
        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && (location == null || loc.time >= fixTimestampMs)) {
                    onNewLocationFix(loc, loc.time.takeIf { it > 0L })
                }
            }
        }
    }

    fun stopLocationUpdates() {
        if (!isReceivingUpdates) return
        runCatching {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        isReceivingUpdates = false
    }

    @SuppressLint("MissingPermission")
    fun requestFreshCurrentLocation(
        timeoutMs: Long = CURRENT_LOCATION_TIMEOUT_MS,
        onResult: (Location?) -> Unit = {}
    ) {
        if (!hasLocationPermission()) {
            statusMessage = CANNOT_FIND_LOCATION_MESSAGE
            onResult(null)
            return
        }

        activeCancellationToken?.cancel()
        val tokenSource = CancellationTokenSource()
        activeCancellationToken = tokenSource
        isRequestingCurrentLocation = true
        statusMessage = null

        scope.launch {
            val customProvider = testCurrentLocationProvider
            val fetched: Location? = withTimeoutOrNull(timeoutMs) {
                if (customProvider != null) {
                    customProvider.invoke()
                } else {
                    awaitFusedCurrentLocation(tokenSource)
                }
            }
            activeCancellationToken = null
            isRequestingCurrentLocation = false
            if (fetched != null) {
                val now = currentTimeMillis()
                val ts = fetched.time.takeIf { it > 0L && (now - it) <= STALE_THRESHOLD_MS } ?: now
                onNewLocationFix(fetched, ts)
                onResult(fetched)
            } else {
                statusMessage = CANNOT_FIND_LOCATION_MESSAGE
                onResult(null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFusedCurrentLocation(tokenSource: CancellationTokenSource): Location? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                tokenSource.cancel()
            }
            try {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    tokenSource.token
                ).addOnSuccessListener { loc ->
                    if (!cont.isActive) return@addOnSuccessListener
                    if (loc != null) {
                        cont.resume(loc)
                    } else {
                        val fallback = readFreshManagerLocationOrNull(context)
                        if (fallback != null) {
                            cont.resume(fallback)
                        }
                        // Otherwise wait for timeout so the 15s spinner + timeout message works as specified
                    }
                }.addOnFailureListener {
                    if (!cont.isActive) return@addOnFailureListener
                    val fallback = readFreshManagerLocationOrNull(context)
                    if (fallback != null) {
                        cont.resume(fallback)
                    }
                }
            } catch (_: Exception) {
                if (cont.isActive) {
                    val fallback = readFreshManagerLocationOrNull(context)
                    if (fallback != null) {
                        cont.resume(fallback)
                    }
                }
            }
        }

    private fun readInitialLastKnownLocation(ctx: Context): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            when {
                gps != null && net != null -> if (gps.time >= net.time) gps else net
                else -> gps ?: net
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readFreshManagerLocationOrNull(ctx: Context): Location? {
        val loc = readInitialLastKnownLocation(ctx) ?: return null
        val ts = loc.time.takeIf { it > 0L } ?: return loc
        return if (isFixFresh(ts)) loc else null
    }
}

@Composable
fun rememberLiveMapLocationState(context: Context): LiveMapLocationState {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context) { LiveMapLocationState(context, scope) }

    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.startLocationUpdates()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> state.stopLocationUpdates()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            state.startLocationUpdates()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.stopLocationUpdates()
        }
    }

    LaunchedEffect(state) {
        while (true) {
            state.tickClock()
            delay(10_000L)
        }
    }

    return state
}
