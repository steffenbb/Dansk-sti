package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.OvernightType
import com.example.data.model.TripStage
import com.example.ui.components.StatusChip
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGlass
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorderSubtle
import android.content.Context
import android.content.Intent
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.ShelterBrown
import com.example.ui.viewmodel.PlannerViewModel
import com.example.util.OvernightCandidate
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPlannerScreen(
    trailId: String = "",
    viewModel: PlannerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit = {},
    onStartTrip: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var hasInitiatedLoad by remember(trailId) { mutableStateOf(false) }

    LaunchedEffect(trailId) {
        if (trailId.isNotBlank()) {
            hasInitiatedLoad = true
            viewModel.initTrail(trailId)
        }
    }

    val trail by viewModel.selectedTrail.collectAsState()
    val isTrailNotFound by viewModel.isTrailNotFound.collectAsState()
    val startKm by viewModel.plannerStartKm.collectAsState()
    val endKm by viewModel.plannerEndKm.collectAsState()
    val days by viewModel.plannerDays.collectAsState()
    val overnightType by viewModel.plannerOvernightType.collectAsState()
    val dailyKm by viewModel.plannerDailyKm.collectAsState()
    val mitTempo by viewModel.mitTempoKmh.collectAsState()
    val maxDetour by viewModel.plannerMaxDetourKm.collectAsState()
    val startTime by viewModel.plannerStartTime.collectAsState()
    val stages by viewModel.plannerStages.collectAsState()
    val isCalculating by viewModel.isCalculatingStages.collectAsState()
    val weather by viewModel.trailWeather.collectAsState()
    val walkDistanceKm by viewModel.plannerWalkDistanceKm.collectAsState()
    val isWalkingReverse by viewModel.isWalkingReverse.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var candidatePickerStageIndex by remember { mutableStateOf<Int?>(null) }
    var availableCandidates by remember { mutableStateOf<List<OvernightCandidate>>(emptyList()) }
    var isLoadingCandidates by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    val isCurrentTrailLoaded = trail != null && (trailId.isBlank() || trail?.id == trailId)

    if (!isCurrentTrailLoaded) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = ForestDarkBg,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Turplanlægger",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("planner_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Tilbage",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestDarkBg)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                if (trailId.isNotBlank() && !isTrailNotFound) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        CircularProgressIndicator(
                            color = ForestGreenPrimary,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("planner_loading_indicator")
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Indlæser rute",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Beregner etaper, overnatningssteder og vejr...",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (trailId.isNotBlank()) "Stien kunne ikke findes" else "Ingen sti valgt",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (trailId.isNotBlank()) {
                                "Den valgte sti kunne ikke indlæses fra databasen."
                            } else {
                                "Vælg en sti fra oversigten for at planlægge en tur."
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Gå tilbage til ruter", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        return
    }

    val currentTrail = trail!!
    val totalLength = currentTrail.lengthKm

    // Function to open candidate picker dialog
    val openCandidatePicker: (Int) -> Unit = { stageIndex ->
        candidatePickerStageIndex = stageIndex
        isLoadingCandidates = true
        scope.launch {
            availableCandidates = viewModel.getNearestCandidatesForStage(stageIndex)
            isLoadingCandidates = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ForestDarkBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Turplanlægger",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("planner_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Tilbage",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (stages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                shareTripPlanAsText(context, currentTrail.name, stages, mitTempo)
                            },
                            modifier = Modifier.testTag("share_trip_plan_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Del turplan",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                viewModel.savePlan()
                                onBack()
                            }
                        },
                        modifier = Modifier.testTag("save_plan_only_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Gem plan",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ForestSurface)
            )
        },
        bottomBar = {
            Surface(
                color = ForestGlass,
                border = BorderStroke(1.dp, GlassBorderSubtle),
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.savePlan()
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("save_plan_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF4B5563)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gem plan")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val planId = viewModel.savePlan()
                                onStartTrip(planId)
                            }
                        },
                        modifier = Modifier
                            .weight(1.4f)
                            .height(52.dp)
                            .testTag("start_hiking_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start vandring", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Severe Weather Alert Banner
            if (weather?.severeAlert != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("planner_weather_alert_banner"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AlertRed)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Vejrvarsel",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = weather!!.severeAlert!!,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Route Segment Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("planner_route_segment_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface),
                    border = BorderStroke(1.dp, GlassBorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentTrail.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Start (A): km ${String.format(Locale.US, "%.1f", startKm)}  ➔  Slut (B): km ${String.format(Locale.US, "%.1f", endKm)}",
                                    color = ForestGreenLight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AmberAccent.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", walkDistanceKm)} km",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Dual-Thumb RangeSlider
                        val curStart = min(startKm, endKm).toFloat()
                        val curEnd = max(startKm, endKm).toFloat()
                        RangeSlider(
                            value = curStart..curEnd,
                            onValueChange = { range ->
                                if (isWalkingReverse) {
                                    viewModel.setPlannerRange(range.endInclusive.toDouble(), range.start.toDouble())
                                } else {
                                    viewModel.setPlannerRange(range.start.toDouble(), range.endInclusive.toDouble())
                                }
                            },
                            valueRange = 0f..max(1.0f, totalLength.toFloat()),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("planner_segment_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = AmberAccent,
                                activeTrackColor = AmberAccent,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Direction Selector & Presets Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setWalkingDirection(false) }
                                        .testTag("planner_direction_forward"),
                                    color = if (!isWalkingReverse) ForestGreenPrimary else ForestSurfaceVariant
                                ) {
                                    Text(
                                        text = "➔ Fremad",
                                        color = if (!isWalkingReverse) Color.White else Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { viewModel.setWalkingDirection(true) }
                                        .testTag("planner_direction_backward"),
                                    color = if (isWalkingReverse) AmberAccent else ForestSurfaceVariant
                                ) {
                                    Text(
                                        text = "⬅ Baglæns",
                                        color = if (isWalkingReverse) Color.Black else Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewModel.setPlannerRange(0.0, totalLength) }
                                    .testTag("planner_full_trail_preset"),
                                color = ForestSurfaceVariant
                            ) {
                                Text(
                                    text = "Hele stien (${totalLength.toInt()} km)",
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Planner Inputs Card: Days & Daily Target Km (Requirements A, F)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("planner_days_target_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Antal dage: $days dage",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.removePlannerStage() },
                                    enabled = days > 1,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(ForestSurfaceVariant, CircleShape)
                                        .testTag("decrease_days_button")
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Færre dage", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                IconButton(
                                    onClick = { viewModel.addPlannerStage() },
                                    enabled = days < 30,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(ForestSurfaceVariant, CircleShape)
                                        .testTag("increase_days_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Flere dage", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Dagligt mål: ${dailyKm.roundToInt()} km/dag",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = dailyKm.toFloat(),
                            onValueChange = { viewModel.setPlannerDailyKm(it.toDouble()) },
                            valueRange = 5f..40f,
                            steps = 34,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("daily_km_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = NordicBlue,
                                activeTrackColor = NordicBlue,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Single "Mit tempo" Setting Card (Requirement A & F)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("mit_tempo_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Mit tempo",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = AmberAccent.copy(alpha = 0.2f)) {
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", mitTempo)} km/t",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Dit personlige vandretempo anvendes til tidsestimering på alle etaper:",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = mitTempo.toFloat(),
                            onValueChange = { viewModel.setMitTempoKmh(it.toDouble()) },
                            valueRange = 2.5f..6.0f,
                            steps = 34,
                            modifier = Modifier.fillMaxWidth().testTag("mit_tempo_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = AmberAccent,
                                activeTrackColor = AmberAccent,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )
                    }
                }
            }

            // Overnight Preference & Max Detour (Requirement A)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("planner_preferences_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Overnatningsform",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OvernightChip(
                                label = "Shelter",
                                icon = Icons.Default.Cabin,
                                isSelected = overnightType == OvernightType.SHELTER,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.SHELTER) },
                                modifier = Modifier.weight(1f)
                            )
                            OvernightChip(
                                label = "Fri Teltning",
                                icon = Icons.Default.Forest,
                                isSelected = overnightType == OvernightType.FRI_TELTNING,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.FRI_TELTNING) },
                                modifier = Modifier.weight(1f)
                            )
                            OvernightChip(
                                label = "Begge",
                                icon = Icons.Default.Navigation,
                                isSelected = overnightType == OvernightType.BOTH,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.BOTH) },
                                modifier = Modifier.weight(1f)
                            )
                            OvernightChip(
                                label = "Lejrplads",
                                icon = Icons.Default.Hotel,
                                isSelected = overnightType == OvernightType.CAMPSITE,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.CAMPSITE) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Max Detour options: 0.5 km, 1.5 km (default), 3.0 km (Requirement A)
                        Text(
                            text = "Maksimal omvej til overnatning",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0.5, 1.5, 3.0).forEach { option ->
                                val isSelected = abs(maxDetour - option) < 0.1
                                val label = when (option) {
                                    1.5 -> "1.5 km (Standard)"
                                    else -> "$option km"
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setPlannerMaxDetourKm(option) }
                                        .testTag("max_detour_${option.toString().replace(".", "_")}"),
                                    color = if (isSelected) ForestGreenPrimary else ForestSurfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Start Time Setting (default 09:00 - Requirement D)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Starttidspunkt: kl. $startTime",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("08:00", "09:00", "10:00").forEach { t ->
                                    val isSelected = startTime == t
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { viewModel.setPlannerStartTime(t) }
                                            .testTag("start_time_$t"),
                                        color = if (isSelected) ForestGreenPrimary else ForestSurfaceVariant,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = t,
                                            color = if (isSelected) Color.White else Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Daylight & Open-Meteo Sunset Banner (Requirement D)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("daylight_info_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.WbSunny, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Soltider & Dagslys (Open-Meteo)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                text = "🌇 Solnedgang: ${weather?.sunset ?: "Solnedgang ukendt"}",
                                color = AmberAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Stage List Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Etapeplan ($days dage)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    if (isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = ForestGreenLight,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // Day-by-Day Stages (Requirement D & E)
            itemsIndexed(stages, key = { index, _ -> "stage_$index" }) { index, stage ->
                StageItemCard(
                    stage = stage,
                    totalRouteKm = totalLength,
                    onChangeOvernight = { openCandidatePicker(index) },
                    onWalkShorter = { viewModel.adjustStageEndKm(index, max(stage.startKm + 0.5, stage.endKm - 3.0)) },
                    onWalkLonger = { viewModel.adjustStageEndKm(index, min(totalLength, stage.endKm + 3.0)) },
                    onAdjustEndKm = { newKm -> viewModel.adjustStageEndKm(index, newKm) }
                )
            }
        }
    }

    // Candidate Picker Dialog ("Skift overnatning" & "Vælg nærmeste overnatningssted" - Requirement C & E)
    if (candidatePickerStageIndex != null) {
        val stageIdx = candidatePickerStageIndex!!
        val currentStage = stages.getOrNull(stageIdx)

        AlertDialog(
            onDismissRequest = { candidatePickerStageIndex = null },
            containerColor = ForestSurface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text(
                    text = "Vælg overnatning for Dag ${currentStage?.dayNumber ?: (stageIdx + 1)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                if (isLoadingCandidates) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ForestGreenLight)
                    }
                } else if (availableCandidates.isEmpty()) {
                    Text("Ingen overnatningssteder fundet i dette område.", color = Color.LightGray, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableCandidates) { cand ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ForestSurfaceVariant,
                                border = BorderStroke(1.dp, GlassBorderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.changeStageCandidate(stageIdx, cand)
                                        candidatePickerStageIndex = null
                                    }
                                    .testTag("candidate_item_${cand.id}")
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = cand.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (cand.type == "SHELTER") ShelterBrown else ForestGreenPrimary
                                        ) {
                                            Text(
                                                text = if (cand.type == "SHELTER") "Shelter" else if (cand.type == "FRI_TELTNING") "Fri teltning" else "Lejrplads",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "km ${String.format(Locale.US, "%.1f", cand.walkKm)}  (omvej ${cand.detourMeters.roundToInt()} m)",
                                            color = ForestGreenLight,
                                            fontSize = 11.sp
                                        )
                                        if (cand.bookingRequired) {
                                            Text("Booking påkrævet", color = AmberAccent, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { candidatePickerStageIndex = null },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Luk")
                }
            }
        )
    }
}

@Composable
fun OvernightChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("overnight_chip_${label.lowercase().replace(" ", "_")}"),
        color = if (isSelected) ForestGreenPrimary else ForestSurfaceVariant,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Color.LightGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (isSelected) Color.White else Color.LightGray,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

/**
 * Stage Item Card showing all details specified in Requirement D:
 * day, from-to place names, km, ascent/descent, estimated walking time (+1 min per 10m ascent),
 * tonight's spot with type, booking flag and detour, water and shops passed that day,
 * sunset time at camp, and sunset warning.
 */
@Composable
fun StageItemCard(
    stage: TripStage,
    totalRouteKm: Double,
    onChangeOvernight: () -> Unit,
    onWalkShorter: () -> Unit,
    onWalkLonger: () -> Unit,
    onAdjustEndKm: (Double) -> Unit
) {
    val walkHours = stage.estimatedWalkMinutes / 60
    val walkMins = stage.estimatedWalkMinutes % 60

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_card_${stage.dayNumber}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (stage.isWarningNoOvernight) ForestSurface.copy(alpha = 0.95f) else ForestSurface
        ),
        border = BorderStroke(
            1.dp,
            if (stage.isWarningNoOvernight) AlertRed else GlassBorderSubtle
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Day, Distance, Elevation, Walking Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(if (stage.isWarningNoOvernight) AlertRed else ForestGreenPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${stage.dayNumber}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Dag ${stage.dayNumber}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ForestSurfaceVariant
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f km", stage.stageKm),
                            color = ForestGreenLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ForestSurfaceVariant
                    ) {
                        Text(
                            text = "~$walkHours t $walkMins m",
                            color = AmberAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // From - To Place Names (Requirement D)
            Text(
                text = "${stage.fromName}  ➔  ${stage.toName}",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Elevation (Phase 2 ascent/descent - Requirement D)
            Text(
                text = "Højde: ↗ ${stage.ascentMeters.roundToInt()} m stigning   ↘ ${stage.descentMeters.roundToInt()} m fald",
                color = Color.LightGray,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Tonight's Spot Section
            if (stage.isWarningNoOvernight) {
                // Requirement C: Mark "Ingen overnatning fundet nær km X" in red and offer:
                // walk shorter, walk longer, or pick from a list of the nearest spots.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AlertRed.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, AlertRed),
                    modifier = Modifier.fillMaxWidth().testTag("no_overnight_warning_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stage.warningMessage ?: "Ingen overnatning fundet",
                                color = AlertRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Der er ingen lejrpladser i standardvinduet. Vælg en løsning:",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = onWalkShorter,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant),
                                modifier = Modifier.weight(1f).testTag("walk_shorter_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Gå kortere", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = onWalkLonger,
                                colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant),
                                modifier = Modifier.weight(1f).testTag("walk_longer_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Gå længere", fontSize = 11.sp, color = Color.White)
                            }
                            Button(
                                onClick = onChangeOvernight,
                                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                                modifier = Modifier.weight(1.2f).testTag("pick_nearest_spot_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Vælg fra liste", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                // Normal Overnight Spot Display
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ForestSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            val icon = when (stage.stopType) {
                                "SHELTER" -> Icons.Default.Cabin
                                "FRI_TELTNING" -> Icons.Default.Forest
                                "CAMPSITE" -> Icons.Default.Hotel
                                else -> Icons.Default.Navigation
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (stage.stopType == "SHELTER") ShelterBrown else ForestGreenLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stage.stopName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val typeLabel = when (stage.stopType) {
                                        "SHELTER" -> "Shelter"
                                        "FRI_TELTNING" -> "Fri teltning"
                                        "CAMPSITE" -> "Lejrplads"
                                        else -> "Mål"
                                    }
                                    Text(typeLabel, color = ForestGreenLight, fontSize = 11.sp)
                                    Text("•", color = Color.Gray, fontSize = 11.sp)
                                    Text("Omvej: ${stage.detourMeters.roundToInt()} m", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                        }

                        // "Skift overnatning" button (Requirement E)
                        OutlinedButton(
                            onClick = onChangeOvernight,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, ForestGreenLight.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.testTag("change_overnight_button_${stage.dayNumber}")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Skift", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Amenities passed that day (Water, Shops) & Camp Sunset (Requirement D)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    label = if (stage.waterCount > 0) "💧 ${stage.waterCount} vand" else "Ingen vand",
                    isHighlight = false,
                    color = if (stage.waterCount > 0) NordicBlue else Color.Gray
                )
                StatusChip(
                    label = if (stage.shopCount > 0) "🛒 ${stage.shopCount} indkøb" else "Ingen butik",
                    isHighlight = false,
                    color = if (stage.shopCount > 0) ForestGreenPrimary else Color.Gray
                )
                if (stage.bookingRequired) {
                    StatusChip(
                        label = "Booking påkrævet",
                        isHighlight = true,
                        color = AmberAccent
                    )
                }
                StatusChip(
                    label = "🌇 Solnedgang ${stage.sunsetTime}",
                    isHighlight = false,
                    color = ForestSurfaceVariant
                )
            }

            // Warning: Arrival after sunset (Requirement D)
            if (stage.isAfterSunset) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AlertRed.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, AlertRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚠️ Ankomst efter solnedgang (lejr solnedgang kl. ${stage.sunsetTime})",
                            color = AlertRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Phase 7d: Share trip plan as text
private fun shareTripPlanAsText(
    context: Context,
    trailName: String,
    stages: List<TripStage>,
    mitTempo: Double
) {
    val totalKm = stages.sumOf { it.stageKm }
    val days = stages.size
    val sb = StringBuilder()
    sb.append("🥾 Min turplan: $trailName\n")
    sb.append("Samlet distance: ${String.format(Locale.US, "%.1f", totalKm)} km over $days ${if (days == 1) "dag" else "dage"}\n")
    sb.append("Planlagt tempo: ${String.format(Locale.US, "%.1f", mitTempo)} km/t\n\n")
    sb.append("📍 Dags-etaper & overnatning:\n")
    stages.forEach { stage ->
        val bookStr = if (stage.bookingRequired) " (Kræver booking)" else ""
        sb.append("• Dag ${stage.dayNumber}: ${String.format(Locale.US, "%.1f", stage.stageKm)} km")
        if (stage.estimatedWalkMinutes > 0) {
            val h = stage.estimatedWalkMinutes / 60
            val m = stage.estimatedWalkMinutes % 60
            sb.append(" (~${if (h > 0) "${h}t " else ""}${m}m)")
        }
        sb.append("\n")
        sb.append("   Overnatning: ${stage.stopName} [${stage.stopType}]$bookStr\n")
        if (stage.sunsetTime.isNotBlank()) {
            sb.append("   Solnedgang: ${stage.sunsetTime}\n")
        }
    }
    sb.append("\nPlanlagt med Dansk Sti")

    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, sb.toString())
        putExtra(Intent.EXTRA_SUBJECT, "Turplan: $trailName")
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, "Del turplan"))
}

