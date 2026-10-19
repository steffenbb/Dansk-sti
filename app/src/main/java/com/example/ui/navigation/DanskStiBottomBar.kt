package com.example.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary

data class NavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val hasActiveDot: Boolean = false
)

@Composable
fun DanskStiBottomBar(
    currentRoute: String?,
    isTripActive: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = mutableListOf<NavItem>()

    items.add(
        NavItem(
            route = Screen.Explore.route,
            label = "Udforsk",
            selectedIcon = Icons.Filled.Explore,
            unselectedIcon = Icons.Outlined.Explore,
            testTag = "nav_item_explore"
        )
    )

    items.add(
        NavItem(
            route = Screen.Saved.route,
            label = "Gemte",
            selectedIcon = Icons.Filled.Bookmark,
            unselectedIcon = Icons.Outlined.BookmarkBorder,
            testTag = "nav_item_saved"
        )
    )

    // Navigér tab: only shown while a trip is active, with a green dot
    if (isTripActive) {
        items.add(
            NavItem(
                route = Screen.Navigation.createRoute(null),
                label = "Navigér",
                selectedIcon = Icons.Filled.Navigation,
                unselectedIcon = Icons.Outlined.Navigation,
                testTag = "nav_item_navigation",
                hasActiveDot = true
            )
        )
    }

    items.add(
        NavItem(
            route = Screen.Offline.route,
            label = "Offline",
            selectedIcon = Icons.Filled.CloudDownload,
            unselectedIcon = Icons.Outlined.CloudDownload,
            testTag = "nav_item_offline"
        )
    )

    NavigationBar(
        modifier = modifier.testTag("main_bottom_nav_bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp
    ) {
        items.forEach { item ->
            val isSelected = currentRoute == item.route || (item.route.startsWith("navigation") && currentRoute?.startsWith("navigation") == true)
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp)
                        )
                        if (item.hasActiveDot) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .offset(x = 10.dp, y = (-10).dp)
                                    .clip(CircleShape)
                                    .background(ForestGreenLight)
                            )
                        }
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ForestGreenPrimary,
                    selectedTextColor = ForestGreenPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.testTag(item.testTag)
            )
        }
    }
}
