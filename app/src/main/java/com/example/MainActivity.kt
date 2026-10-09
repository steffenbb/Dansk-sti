package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.service.NavigationTrackingService
import com.example.ui.MainViewModel
import com.example.ui.navigation.DanskStiBottomBar
import com.example.ui.navigation.Screen
import com.example.ui.screens.FullscreenMapScreen
import com.example.ui.screens.LocationPermissionsDialog
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.OfflineTrailsScreen
import com.example.ui.screens.SavedPlansScreen
import com.example.ui.screens.TrailBrowserScreen
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.screens.TripPlannerScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.NavigationViewModel
import com.example.ui.viewmodel.OfflineViewModel
import com.example.ui.viewmodel.SavedViewModel
import com.example.ui.viewmodel.TrailViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DanskStiTheme {
                var showPermissionDialog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val fineLocationGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!fineLocationGranted) {
                        showPermissionDialog = true
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DanskStiAppNav(mainViewModel = mainViewModel)

                        if (showPermissionDialog) {
                            LocationPermissionsDialog(
                                onDismiss = { showPermissionDialog = false },
                                onPermissionsGranted = {
                                    showPermissionDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DanskStiAppNav(
    mainViewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val liveState by NavigationTrackingService.liveState.collectAsState()
    val isTripActive = liveState.isTracking

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val showSnackbar: (String, String?, () -> Unit) -> Unit = { message, actionLabel, onAction ->
        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                onAction()
            }
        }
    }

    // Hide the bottom navigation bar during full-screen navigation and fullscreen map
    val showBottomBar = currentRoute in listOf(
        Screen.Explore.route,
        Screen.Saved.route,
        Screen.Offline.route
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (showBottomBar) {
                DanskStiBottomBar(
                    currentRoute = currentRoute,
                    isTripActive = isTripActive,
                    onNavigate = { targetRoute ->
                        navController.navigate(targetRoute) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
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
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // 1. Udforsk (Home)
            composable(Screen.Explore.route) {
                val exploreViewModel: ExploreViewModel = viewModel()
                TrailBrowserScreen(
                    viewModel = exploreViewModel,
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    }
                )
            }

            // 2. Gemte (Saved trails, trips & gear checklist in Room)
            composable(Screen.Saved.route) {
                val savedViewModel: SavedViewModel = viewModel()
                SavedPlansScreen(
                    viewModel = savedViewModel,
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    },
                    onResumeTrip = { planId ->
                        navController.navigate(Screen.Navigation.route)
                    },
                    onShowSnackbar = showSnackbar
                )
            }

            // 3. Navigér (Navigation Screen)
            composable(Screen.Navigation.route) {
                val navViewModel: NavigationViewModel = viewModel()
                NavigationScreen(
                    viewModel = navViewModel,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            // 4. Offline (Offline downloaded trails & maps)
            composable(Screen.Offline.route) {
                val offlineViewModel: OfflineViewModel = viewModel()
                OfflineTrailsScreen(
                    viewModel = offlineViewModel,
                    onBack = {
                        navController.popBackStack()
                    },
                    onTrailClick = { trailId ->
                        navController.navigate(Screen.TrailDetail.createRoute(trailId))
                    }
                )
            }

            // 5. Trail Detail Page
            composable(
                route = Screen.TrailDetail.route,
                arguments = listOf(navArgument("trailId") { type = NavType.StringType })
            ) { backStackEntry ->
                val trailId = backStackEntry.arguments?.getString("trailId") ?: ""
                val trailViewModel: TrailViewModel = viewModel()
                TrailDetailScreen(
                    trailId = trailId,
                    viewModel = trailViewModel,
                    onBack = {
                        navController.popBackStack()
                    },
                    onPlanTrip = { tid ->
                        navController.navigate(Screen.TripPlanner.createRoute(tid))
                    },
                    onStartNavigation = {
                        navController.navigate(Screen.Navigation.route)
                    },
                    onOpenFullscreenMap = { tid ->
                        navController.navigate(Screen.FullscreenMap.createRoute(tid))
                    },
                    onDownloadOffline = { tid ->
                        navController.navigate(Screen.Offline.route)
                    }
                )
            }

            // 6. Trip Planner
            composable(
                route = Screen.TripPlanner.route,
                arguments = listOf(navArgument("trailId") { type = NavType.StringType })
            ) { backStackEntry ->
                val trailId = backStackEntry.arguments?.getString("trailId") ?: ""
                TripPlannerScreen(
                    trailId = trailId,
                    viewModel = mainViewModel,
                    onBack = {
                        navController.popBackStack()
                    },
                    onStartTrip = { planId ->
                        navController.navigate(Screen.Navigation.route) {
                            popUpTo(Screen.Explore.route)
                        }
                    }
                )
            }

            // 7. Fullscreen Interactive Map
            composable(
                route = Screen.FullscreenMap.route,
                arguments = listOf(navArgument("trailId") { type = NavType.StringType })
            ) { backStackEntry ->
                val trailId = backStackEntry.arguments?.getString("trailId") ?: ""
                val trailViewModel: TrailViewModel = viewModel()
                FullscreenMapScreen(
                    trailId = trailId,
                    viewModel = trailViewModel,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
