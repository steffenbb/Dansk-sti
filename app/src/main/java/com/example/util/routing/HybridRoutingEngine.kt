package com.example.util.routing

import android.content.Context
import android.util.Log

/**
 * Composite routing engine that chooses between offline and online engines.
 *
 * Engine choice rule (Requirement D):
 * Uses the offline engine when the segment files covering both leg ends are present on disk,
 * otherwise routes online via BRouterOnlineEngine.
 */
class HybridRoutingEngine(
    private val context: Context,
    val onlineEngine: BRouterOnlineEngine = BRouterOnlineEngine(context),
    val offlineEngine: BRouterOfflineEngine = BRouterOfflineEngine(context),
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(context)
) : RoutingEngine {

    companion object {
        private const val TAG = "HybridRoutingEngine"
    }

    /**
     * Checks if both leg endpoints are covered by downloaded segment files.
     */
    fun canRouteLegOffline(from: LatLng, to: LatLng): Boolean {
        return storageManager.areSegmentsAvailableForLeg(from, to)
    }

    override suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): RouteLegResult {
        val useOffline = canRouteLegOffline(from, to)

        if (useOffline) {
            Log.d(TAG, "Routing leg ($from -> $to) via BRouterOfflineEngine")
            val offlineResult = offlineEngine.routeLeg(from, to, options, alternative)

            if (offlineResult is RouteLegResult.Success) {
                return offlineResult
            }

            Log.w(TAG, "Offline routing failed or returned no route ($offlineResult). Attempting online fallback.")
            val onlineResult = onlineEngine.routeLeg(from, to, options, alternative)
            return if (onlineResult is RouteLegResult.Success) {
                onlineResult
            } else {
                offlineResult
            }
        } else {
            Log.d(TAG, "Routing leg ($from -> $to) via BRouterOnlineEngine (offline data not present)")
            return onlineEngine.routeLeg(from, to, options, alternative)
        }
    }
}
