package com.example.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import java.util.Locale

data class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

/**
 * State for the slim "Aktiv tur" bar above the bottom navigation bar (Requirement B).
 * Example: "Dag 2 · 6,3 km til Shelter Skovbo · Fortsæt"
 */
data class ActiveTripBannerState(
    val isActive: Boolean,
    val currentDay: Int = 1,
    val remainingKm: Double = 0.0,
    val destinationName: String = "",
    val planId: Long? = null
)

@Composable
fun DanskStiBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    activeTripState: ActiveTripBannerState? = null,
    onContinueActiveTrip: (() -> Unit)? = null
) {
    // 4 Bottom Tabs: Udforsk, Kort, Mine ture, Profil (Requirement B)
    val items = listOf(
        BottomNavItem(
            route = Screen.Explore.route,
            labelRes = R.string.nav_explore,
            selectedIcon = Icons.Filled.Explore,
            unselectedIcon = Icons.Outlined.Explore,
            testTag = "nav_explore_tab"
        ),
        BottomNavItem(
            route = Screen.Map.route,
            labelRes = R.string.nav_map,
            selectedIcon = Icons.Filled.Map,
            unselectedIcon = Icons.Outlined.Map,
            testTag = "nav_map_tab"
        ),
        BottomNavItem(
            route = Screen.Saved.route,
            labelRes = R.string.nav_saved,
            selectedIcon = Icons.Filled.Bookmarks,
            unselectedIcon = Icons.Outlined.Bookmarks,
            testTag = "nav_saved_tab"
        ),
        BottomNavItem(
            route = Screen.Profile.route,
            labelRes = R.string.nav_profile,
            selectedIcon = Icons.Filled.Person,
            unselectedIcon = Icons.Outlined.Person,
            testTag = "nav_profile_tab"
        )
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Slim "Aktiv tur" bar sits above the bottom bar on every tab when a trip is active
        if (activeTripState != null && activeTripState.isActive && onContinueActiveTrip != null) {
            ActiveTripSlimBar(
                state = activeTripState,
                onClick = onContinueActiveTrip
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
        ) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                items.forEach { item ->
                    val label = stringResource(item.labelRes)
                    val isSelected = currentRoute == item.route ||
                        (item.route == Screen.Explore.route && currentRoute?.startsWith("trail/") == true)

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onNavigate(item.route) },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = label
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.testTag(item.testTag)
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveTripSlimBar(
    state: ActiveTripBannerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val kmComma = String.format(Locale("da", "DK"), "%.1f", state.remainingKm)
    val dest = state.destinationName.ifBlank { "dagens mål" }
    val bannerText = stringResource(
        R.string.active_trip_format,
        state.currentDay,
        kmComma,
        dest
    )
    val bannerCd = stringResource(
        R.string.active_trip_bar_cd,
        state.currentDay,
        kmComma,
        dest
    )

    Surface(
        color = ForestGreenPrimary,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetSize)
            .clickable(onClick = onClick)
            .testTag("active_trip_slim_bar")
            .semantics { contentDescription = bannerCd }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = bannerText,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
