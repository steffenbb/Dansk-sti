package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.service.NavigationTrackingService
import com.example.ui.navigation.ActiveTripBannerState
import com.example.ui.navigation.DanskStiBottomBar
import com.example.ui.navigation.Screen
import com.example.ui.screens.ApiDiagnosticsScreen
import com.example.ui.screens.DataCreditsScreen
import com.example.ui.screens.FullscreenMapScreen
import com.example.ui.screens.KortScreen
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.OfflineTrailsScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.RouteBuilderScreen
import com.example.ui.screens.SavedPlansScreen
import com.example.ui.screens.TrailBrowserScreen
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.RouteBuilderViewModel
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as DanskStiApp
        setContent {
            val dbError by app.databaseError.collectAsState()
            DanskStiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (dbError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .testTag("database_error_screen"),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Databasen kunne ikke åbnes",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = dbError ?: "Ukendt databasefejl",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("database_error_text")
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { app.retryDatabaseInit() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_database_retry")
                            ) {
                                Text(stringResource(R.string.btn_retry))
                            }
                        }
                    } else {
                        DanskStiAppRoot()
                    }
                }
            }
        }
    }
}

@Composable
fun DanskStiAppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as DanskStiApp
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val liveState by NavigationTrackingService.liveState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val activeTripBannerState = remember(liveState) {
        if (liveState.isTracking) {
            val stopName = liveState.currentStage?.stopName
            val dest = if (!stopName.isNullOrBlank()) {
                stopName
            } else {
                liveState.trailName.ifBlank { "dagens mål" }
            }
            ActiveTripBannerState(
                isActive = true,
                currentDay = liveState.currentDay.coerceAtLeast(1),
                remainingKm = liveState.distanceRemainingTodayKm.coerceAtLeast(0.0),
                destinationName = dest,
                planId = liveState.planId.takeIf { it > 0L }
            )
        } else {
            ActiveTripBannerState(isActive = false)
        }
    }

    // Show the 4-tab bottom bar on the 4 main tabs (Requirement B)
    val showBottomBar = currentRoute == Screen.Explore.route ||
        currentRoute == Screen.Map.route ||
        currentRoute == Screen.Saved.route ||
        currentRoute == Screen.Profile.route

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                DanskStiBottomBar(
                    currentRoute = currentRoute,
                    activeTripState = activeTripBannerState,
                    onContinueActiveTrip = {
                        navController.navigate(Screen.Navigation.createRoute(activeTripBannerState.planId))
                    },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Screen.Explore.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Explore.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab 1: Udforsk (Requirement C)
            composable(Screen.Explore.route) {
                TrailBrowserScreen(
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    },
                    onOpenDiagnostics = {
                        navController.navigate(Screen.Diagnostics.route)
                    },
                    onOpenCredits = {
                        navController.navigate(Screen.DataCredits.route)
                    },
                    onOpenRouteBuilder = { destLat, destLng, destName ->
                        navController.navigate(
                            Screen.RouteBuilder.createRoute(
                                destLat = destLat,
                                destLng = destLng,
                                destName = destName
                            )
                        )
                    }
                )
            }

            // Tab 2: Kort (Requirement E)
            composable(Screen.Map.route) {
                KortScreen(
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    },
                    onOpenPlanner = { destLat, destLng, destName ->
                        navController.navigate(
                            Screen.RouteBuilder.createRoute(
                                destLat = destLat,
                                destLng = destLng,
                                destName = destName
                            )
                        )
                    },
                    onOpenOfflineMaps = {
                        navController.navigate(Screen.Offline.route)
                    }
                )
            }

            // Tab 3: Mine ture (Requirement H)
            composable(Screen.Saved.route) {
                SavedPlansScreen(
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    },
                    onStartNavigation = { planId ->
                        navController.navigate(Screen.Navigation.createRoute(planId))
                    },
                    onCreateNewCustomRoute = {
                        navController.navigate(Screen.RouteBuilder.createRoute())
                    },
                    onEditCustomRoute = { routeId ->
                        navController.navigate(Screen.RouteBuilder.createRoute(routeId = routeId))
                    },
                    onEditPlan = { plan ->
                        navController.navigate(Screen.RouteBuilder.createRoute(planId = plan.id))
                    },
                    onOpenPointOnMap = { _, _, _ ->
                        navController.navigate(Screen.Map.route) {
                            popUpTo(Screen.Explore.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onExploreTrails = {
                        navController.navigate(Screen.Explore.route) {
                            popUpTo(Screen.Explore.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenMap = {
                        navController.navigate(Screen.Map.route) {
                            popUpTo(Screen.Explore.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // Tab 4: Profil (Requirement I)
            composable(Screen.Profile.route) {
                ProfileScreen(
                    onOpenCredits = {
                        navController.navigate(Screen.DataCredits.route)
                    },
                    onOpenDiagnostics = {
                        navController.navigate(Screen.Diagnostics.route)
                    },
                    onOpenOfflineMaps = {
                        navController.navigate(Screen.Offline.route)
                    }
                )
            }

            // Secondary Destination: Navigation (Requirement G)
            composable(
                route = Screen.Navigation.route,
                arguments = listOf(
                    navArgument("planId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) { backStackEntry ->
                val planIdArg = backStackEntry.arguments?.getLong("planId") ?: -1L
                val navVm: NavigationViewModel = viewModel()
                LaunchedEffect(planIdArg) {
                    navVm.initPlan(if (planIdArg > 0L) planIdArg else null)
                }
                NavigationScreen(
                    viewModel = navVm,
                    onBack = { navController.popBackStack() }
                )
            }

            // Secondary Destination: Offline-kort
            composable(Screen.Offline.route) {
                OfflineTrailsScreen(
                    onBack = { navController.popBackStack() },
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    },
                    onOpenCredits = {
                        navController.navigate(Screen.DataCredits.route)
                    }
                )
            }

            // Secondary Destination: Trail Page (Requirement D)
            composable(
                route = Screen.TrailDetail.route,
                arguments = listOf(navArgument("trailId") { type = NavType.StringType })
            ) { backStackEntry ->
                val trailId = backStackEntry.arguments?.getString("trailId").orEmpty()
                TrailDetailScreen(
                    trailId = trailId,
                    onBack = { navController.popBackStack() },
                    onPlanTrip = { tid ->
                        navController.navigate(Screen.RouteBuilder.createRoute(baseTrailId = tid))
                    },
                    onStartNavigation = { planId ->
                        navController.navigate(Screen.Navigation.createRoute(planId))
                    },
                    onOpenFullscreenMap = { tid ->
                        navController.navigate(Screen.FullscreenMap.createRoute(tid))
                    },
                    onDownloadOffline = {
                        navController.navigate(Screen.Offline.route)
                    },
                    onOpenDiagnostics = {
                        navController.navigate(Screen.Diagnostics.route)
                    },
                    onOpenRouteBuilder = { destLat, destLng, destName ->
                        navController.navigate(
                            Screen.RouteBuilder.createRoute(
                                destLat = destLat,
                                destLng = destLng,
                                destName = destName
                            )
                        )
                    }
                )
            }

            // Secondary Destination: Fullscreen Trail Map ("Vis på kort")
            composable(
                route = Screen.FullscreenMap.route,
                arguments = listOf(navArgument("trailId") { type = NavType.StringType })
            ) { backStackEntry ->
                val trailId = backStackEntry.arguments?.getString("trailId").orEmpty()
                FullscreenMapScreen(
                    trailId = trailId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Secondary Destination: Diagnostics
            composable(Screen.Diagnostics.route) {
                ApiDiagnosticsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Secondary Destination: Data Credits
            composable(Screen.DataCredits.route) {
                DataCreditsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            // Secondary Destination: Ruteplanlægger (Requirement F)
            composable(
                route = Screen.RouteBuilder.route,
                arguments = listOf(
                    navArgument("routeId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("baseTrailId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("planId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("destLat") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("destLng") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("destName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val routeIdStr = backStackEntry.arguments?.getString("routeId")
                val baseTrailIdRaw = backStackEntry.arguments?.getString("baseTrailId")
                val planIdStr = backStackEntry.arguments?.getString("planId")
                val destLatStr = backStackEntry.arguments?.getString("destLat")
                val destLngStr = backStackEntry.arguments?.getString("destLng")
                val destNameRaw = backStackEntry.arguments?.getString("destName")

                val routeId = routeIdStr?.toLongOrNull()
                val baseTrailId = baseTrailIdRaw?.let {
                    try {
                        URLDecoder.decode(it, "UTF-8")
                    } catch (_: Exception) {
                        it
                    }
                }?.takeIf { it.isNotBlank() }
                val planId = planIdStr?.toLongOrNull()
                val destLat = destLatStr?.toDoubleOrNull()
                val destLng = destLngStr?.toDoubleOrNull()
                val destName = destNameRaw?.let {
                    try {
                        URLDecoder.decode(it, "UTF-8")
                    } catch (_: Exception) {
                        it
                    }
                }

                val builderVm: RouteBuilderViewModel = viewModel()
                RouteBuilderScreen(
                    routeId = routeId,
                    baseTrailId = baseTrailId,
                    existingPlanId = planId,
                    destLat = destLat,
                    destLng = destLng,
                    destName = destName,
                    viewModel = builderVm,
                    onBack = { navController.popBackStack() },
                    onSavedSuccessfully = {
                        navController.navigate(Screen.Saved.route) {
                            popUpTo(Screen.Explore.route)
                        }
                    },
                    onStartNavigation = { startedPlanId ->
                        navController.navigate(Screen.Navigation.createRoute(startedPlanId.removePrefix("route_").toLongOrNull()))
                    },
                    onPlanStages = { customTrailId ->
                        navController.navigate(Screen.RouteBuilder.createRoute(baseTrailId = customTrailId))
                    }
                )
            }
        }
    }
}
