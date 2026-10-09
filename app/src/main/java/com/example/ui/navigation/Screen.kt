package com.example.ui.navigation

sealed class Screen(val route: String) {
    object Explore : Screen("explore")
    object Saved : Screen("saved")
    object Navigation : Screen("navigation")
    object Offline : Screen("offline")

    object TrailDetail : Screen("trail/{trailId}") {
        fun createRoute(trailId: String) = "trail/$trailId"
    }

    object TripPlanner : Screen("planner/{trailId}") {
        fun createRoute(trailId: String) = "planner/$trailId"
    }

    object FullscreenMap : Screen("map/{trailId}") {
        fun createRoute(trailId: String) = "map/$trailId"
    }
}
