package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.screens.LocationPermissionsDialog
import com.example.ui.screens.NavigationScreen
import com.example.ui.screens.SavedPlansScreen
import com.example.ui.screens.TrailBrowserScreen
import com.example.ui.screens.TrailDetailScreen
import com.example.ui.screens.TripPlannerScreen
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.ForestDarkBg

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ForestDarkBg),
                    color = ForestDarkBg
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DanskStiAppNavHost(viewModel = viewModel)

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
fun DanskStiAppNavHost(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ScreenTransition",
        modifier = modifier.fillMaxSize()
    ) { screen ->
        when (screen) {
            AppScreen.TRAIL_BROWSER -> {
                TrailBrowserScreen(viewModel = viewModel)
            }
            AppScreen.TRAIL_DETAIL -> {
                TrailDetailScreen(viewModel = viewModel)
            }
            AppScreen.TRIP_PLANNER -> {
                TripPlannerScreen(viewModel = viewModel)
            }
            AppScreen.NAVIGATION_MODE -> {
                NavigationScreen(viewModel = viewModel)
            }
            AppScreen.SAVED_PLANS -> {
                SavedPlansScreen(viewModel = viewModel)
            }
        }
    }
}
