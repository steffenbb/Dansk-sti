package com.example.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.DanskStiApp

/**
 * Observes live connectivity changes via [ConnectivityManager.registerDefaultNetworkCallback]
 * and posts state updates on the main thread.
 */
private fun queryIsOnlineNow(context: Context, cm: ConnectivityManager?): Boolean {
    return runCatching {
        val currentApp = runCatching { DanskStiApp.instance }.getOrNull()
            ?: (context.applicationContext as? DanskStiApp)
        currentApp?.repository?.isOnlineOverrideForTests?.let { return@runCatching it }
        val manager = cm ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return@runCatching false
        val active = manager.activeNetwork ?: return@runCatching false
        val caps = manager.getNetworkCapabilities(active) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}

@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    var isOnline by remember(context) {
        mutableStateOf(queryIsOnlineNow(context, cm))
    }

    DisposableEffect(context) {
        val mainHandler = Handler(Looper.getMainLooper())

        fun postRefresh() {
            mainHandler.post {
                val current = queryIsOnlineNow(context, cm)
                if (isOnline != current) {
                    isOnline = current
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                postRefresh()
            }

            override fun onLost(network: Network) {
                postRefresh()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                postRefresh()
            }
        }

        val initialNow = queryIsOnlineNow(context, cm)
        if (isOnline != initialNow) {
            isOnline = initialNow
        }

        var registered = false
        try {
            cm?.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) {
            registered = false
        }

        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            if (registered) {
                try {
                    cm?.unregisterNetworkCallback(callback)
                } catch (_: Exception) {}
            }
        }
    }

    return isOnline
}
