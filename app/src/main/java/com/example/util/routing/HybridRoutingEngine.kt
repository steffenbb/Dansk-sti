package com.example.util.routing

import android.content.Context
import android.util.Log

/**
 * Composite routing engine that chooses between offline and online engines.
 *
 * Engine choice rule (Requirement D & Fix Round 5 Part B Item 1):
 * Uses the offline engine when the segment files covering both leg ends are present on disk
 * AND no polygonal no-go areas ("Rut uden om") are active. If no-go areas are present,
 * routes that leg online because the offline engine cannot apply no-go polygons, and labels
 * the result accordingly.
 */
class HybridRoutingEngine(
    private val context: Context,
    val onlineEngine: BRouterOnlineEngine = BRouterOnlineEngine(context),
    val offlineEngine: BRouterOfflineEngine = BRouterOfflineEngine(context),
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(context)
) : RoutingEngine {

    companion object {
        private const val TAG = "HybridRoutingEngine"
        const val ONLINE_NOGO_SOURCE_NOTICE = "brouter.de (online — Rut uden om understøttes ikke offline)"
        const val OFFLINE_NOGO_UNSUPPORTED_MESSAGE =
            "Rut uden om kræver internetforbindelse, da offline-motoren ikke kan anvende no-go områder."
    }

    /**
     * Checks if both leg endpoints are covered by downloaded segment files
     * and no polygonal no-go areas require online routing.
     */
    fun canRouteLegOffline(from: LatLng, to: LatLng, options: RouteOptions = RouteOptions()): Boolean {
        if (options.noGoAreas.isNotEmpty()) return false
        return storageManager.areSegmentsAvailableForLeg(from, to)
    }

    override suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): RouteLegResult {
        if (options.noGoAreas.isNotEmpty()) {
            Log.i(TAG, "Leg ($from -> $to) has ${options.noGoAreas.size} no-go polygon(s); routing online via BRouterOnlineEngine")
            return when (val onlineResult = onlineEngine.routeLeg(from, to, options, alternative)) {
                is RouteLegResult.Success -> onlineResult.copy(sourceName = ONLINE_NOGO_SOURCE_NOTICE)
                is RouteLegResult.Offline -> RouteLegResult.Offline(OFFLINE_NOGO_UNSUPPORTED_MESSAGE)
                else -> onlineResult
            }
        }

        val useOffline = canRouteLegOffline(from, to, options)

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
