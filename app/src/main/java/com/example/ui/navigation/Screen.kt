package com.example.ui.navigation

sealed class Screen(val route: String) {
    // 4 Bottom Bar Tabs (Requirement B)
    object Explore : Screen("explore")
    object Map : Screen("kort")
    object Saved : Screen("saved")
    object Profile : Screen("profile")

    // Secondary Destinations
    object Navigation : Screen("navigation?planId={planId}") {
        fun createRoute(planId: Long? = null) = if (planId != null && planId > 0) "navigation?planId=$planId" else "navigation"
    }
    object Offline : Screen("offline")

    object TrailDetail : Screen("trail/{trailId}") {
        fun createRoute(trailId: String) = "trail/$trailId"
    }

    object FullscreenMap : Screen("map/{trailId}") {
        fun createRoute(trailId: String) = "map/$trailId"
    }

    object Diagnostics : Screen("diagnostics")
    object DataCredits : Screen("credits")

    object RouteBuilder : Screen("route_builder?routeId={routeId}&baseTrailId={baseTrailId}&planId={planId}&destLat={destLat}&destLng={destLng}&destName={destName}") {
        fun createRoute(
            routeId: Long? = null,
            baseTrailId: String? = null,
            planId: Long? = null,
            destLat: Double? = null,
            destLng: Double? = null,
            destName: String? = null
        ): String {
            val params = mutableListOf<String>()
            if (routeId != null && routeId > 0) params.add("routeId=$routeId")
            if (!baseTrailId.isNullOrBlank()) params.add("baseTrailId=${java.net.URLEncoder.encode(baseTrailId, "UTF-8")}")
            if (planId != null && planId > 0) params.add("planId=$planId")
            if (destLat != null) params.add("destLat=$destLat")
            if (destLng != null) params.add("destLng=$destLng")
            if (!destName.isNullOrBlank()) params.add("destName=${java.net.URLEncoder.encode(destName, "UTF-8")}")
            return if (params.isNotEmpty()) "route_builder?${params.joinToString("&")}" else "route_builder"
        }
    }
}
