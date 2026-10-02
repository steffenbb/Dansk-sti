package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TripPlanEntity
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorderSubtle
import androidx.compose.foundation.BorderStroke
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlansScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val plans by viewModel.savedPlans.collectAsState()

    BackHandler {
        viewModel.navigateTo(AppScreen.TRAIL_BROWSER)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForestDarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Gemte Vandreplaner",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(AppScreen.TRAIL_BROWSER) },
                        modifier = Modifier.testTag("saved_plans_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Tilbage",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestSurface)
            )
        }
    ) { paddingValues ->
        if (plans.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Ingen gemte vandreplaner endnu",
                        color = Color.LightGray,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Vælg en sti og tryk 'Planlæg tur' for at oprette en plan.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(plans, key = { it.id }) { plan ->
                    SavedPlanCard(
                        plan = plan,
                        onResume = { viewModel.resumeTripNavigation(plan) },
                        onDelete = { viewModel.deletePlan(plan.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SavedPlanCard(
    plan: TripPlanEntity,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("d. MMMM yyyy", Locale("da", "DK")).format(Date(plan.createdAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_plan_card_${plan.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurface),
        border = BorderStroke(1.dp, GlassBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plan.trailName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_plan_button_${plan.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Slet plan",
                        tint = AlertRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Oprettet: $dateStr",
                color = Color.Gray,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForestSurfaceVariant
                ) {
                    Text(
                        text = "${plan.totalDistanceKm.toInt()} km",
                        color = ForestGreenLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForestSurfaceVariant
                ) {
                    Text(
                        text = "${plan.targetDays} dage",
                        color = AmberAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForestSurfaceVariant
                ) {
                    Text(
                        text = plan.overnightType.lowercase().replaceFirstChar { it.uppercase() },
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onResume,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("resume_trip_button_${plan.id}"),
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Genoptag vandring (GPS)",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
