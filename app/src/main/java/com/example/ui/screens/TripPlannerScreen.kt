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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.OvernightType
import com.example.data.model.TripStage
import com.example.ui.AppScreen
import com.example.ui.MainViewModel
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
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.ShelterBrown
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripPlannerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val trail by viewModel.selectedTrail.collectAsState()
    val startKm by viewModel.plannerStartKm.collectAsState()
    val endKm by viewModel.plannerEndKm.collectAsState()
    val days by viewModel.plannerDays.collectAsState()
    val overnightType by viewModel.plannerOvernightType.collectAsState()
    val dailyKm by viewModel.plannerDailyKm.collectAsState()
    val stages by viewModel.plannerStages.collectAsState()
    val isCalculating by viewModel.isCalculatingStages.collectAsState()
    val downloadProgress by viewModel.tileDownloadProgress.collectAsState()
    val missingFriTeltningDays by viewModel.stagesMissingFriTeltning.collectAsState()
    val showDisclaimerDialog by viewModel.showFriTeltningDisclaimer.collectAsState()
    val weather by viewModel.trailWeather.collectAsState()
    val walkDistanceKm by viewModel.plannerWalkDistanceKm.collectAsState()
    val isWalkingReverse by viewModel.isWalkingReverse.collectAsState()
    val hikingSpeed by viewModel.hikingSpeedKmh.collectAsState()
    val packWeight by viewModel.packWeightKg.collectAsState()
    val offlineBufferKm by viewModel.offlineCorridorBufferKm.collectAsState()
    val estimatedDownloadMb by viewModel.estimatedDownloadMb.collectAsState()

    val effectiveSpeed = (hikingSpeed - (packWeight * 0.03)).coerceAtLeast(2.0)
    val hoursPerDay = dailyKm / effectiveSpeed
    val wholeHours = hoursPerDay.toInt()
    val minutes = ((hoursPerDay - wholeHours) * 60).roundToInt()

    var editingStageIndex by remember { mutableStateOf<Int?>(null) }
    var editStopName by remember { mutableStateOf("") }

    BackHandler {
        viewModel.navigateTo(AppScreen.TRAIL_DETAIL)
    }

    if (trail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ingen sti valgt", color = Color.White)
        }
        return
    }

    val currentTrail = trail!!
    val totalLength = currentTrail.lengthKm

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
                        onClick = { viewModel.navigateTo(AppScreen.TRAIL_DETAIL) },
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
                    IconButton(
                        onClick = { viewModel.saveCurrentPlanAndStart(startNavigation = false) },
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
                        onClick = { viewModel.saveCurrentPlanAndStart(startNavigation = false) },
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
                        onClick = { viewModel.requestStartNavigation() },
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
            // Severe Weather Alert Banner (If Any)
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

            // Fri Teltning Availability Disclaimer Banner (If any stop lacks Fri Teltning)
            if (missingFriTeltningDays.isNotEmpty() && (overnightType == OvernightType.TENT || overnightType == OvernightType.MIXED)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("planner_fri_teltning_disclaimer_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = ForestSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberAccent)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Advarsel",
                                    tint = AmberAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Overnatnings-disclaimer (Fri teltning)",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Fri teltning er IKKE tilgængelig på alle dine etaper (mangler på Dag ${missingFriTeltningDays.map { it.dayNumber }.joinToString(", ")}). I Danmark er teltning kun tilladt i udpegede Fri Teltningsskove eller på etablerede lejrpladser. På disse stop skal shelter benyttes eller lejrplads bookes forud.",
                                color = Color(0xFFDDDDDD),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // Unified Interactive Route Segment & Interval Card
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
                                    text = "Start (A): km ${String.format("%.1f", startKm)}  ➔  Slut (B): km ${String.format("%.1f", endKm)}",
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
                                    text = "${String.format("%.1f", walkDistanceKm)} km",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Interactive Dual-Thumb RangeSlider to easily adjust segment
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
                            valueRange = 0f..totalLength.toFloat(),
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

            // Days & Daily Distance
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            text = "Foretrukken daglig distance: ${dailyKm.roundToInt()} km/dag",
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

            // Overnight Type Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                                isSelected = overnightType == OvernightType.TENT,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.TENT) },
                                modifier = Modifier.weight(1f)
                            )
                            OvernightChip(
                                label = "Blandet",
                                icon = Icons.Default.Navigation,
                                isSelected = overnightType == OvernightType.MIXED,
                                onClick = { viewModel.setPlannerOvernightType(OvernightType.MIXED) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 1. Stage Pace & Cadence Adjuster (Enhancement 9)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("pace_adjuster_card"),
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
                                    text = "Vandretempo & Oppakning",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = AmberAccent.copy(alpha = 0.2f)) {
                                Text(
                                    text = "${String.format("%.1f", effectiveSpeed)} km/t effektiv",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Juster dit tempo og rygsækvægt for præcis estimering af dagsetaper:",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Grundtempo: ${String.format("%.1f", hikingSpeed)} km/t", color = Color.White, fontSize = 13.sp)
                            Text("Rygsæk: ${packWeight.toInt()} kg", color = Color.White, fontSize = 13.sp)
                        }

                        Slider(
                            value = hikingSpeed.toFloat(),
                            onValueChange = { viewModel.setHikingSpeedKmh(it.toDouble()) },
                            valueRange = 3.0f..6.0f,
                            steps = 30,
                            modifier = Modifier.fillMaxWidth().testTag("hiking_speed_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = AmberAccent,
                                activeTrackColor = AmberAccent,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )

                        Slider(
                            value = packWeight.toFloat(),
                            onValueChange = { viewModel.setPackWeightKg(it.toDouble()) },
                            valueRange = 0f..25f,
                            steps = 25,
                            modifier = Modifier.fillMaxWidth().testTag("pack_weight_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = NordicBlue,
                                activeTrackColor = NordicBlue,
                                inactiveTrackColor = ForestSurfaceVariant
                            )
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestDarkBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⏱️", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Estimeret vandretid: ~$wholeHours t $minutes min aktiv gang pr. dag",
                                    color = ForestGreenLight,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Daylight & Twilight Hiking Window Calculator (Enhancement 2)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("daylight_calculator_card"),
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
                                Icon(Icons.Default.WbSunny, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Dagslys & Skumring (DMI)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = ForestSurfaceVariant) {
                                Text(
                                    text = weather?.daylightDurationText ?: "11 t 40 min dagslys",
                                    color = ForestGreenLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        val sunriseTime = weather?.sunrise ?: "07:10"
                        val sunsetTime = weather?.sunset ?: "18:50"
                        val twilightTime = weather?.twilightEnd ?: "19:25"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DaylightStatItem(label = "Solopgang", time = sunriseTime, icon = "🌅")
                            DaylightStatItem(label = "Solnedgang", time = sunsetTime, icon = "🌇")
                            DaylightStatItem(label = "Tusmørke", time = twilightTime, icon = "🌌")
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        val sunsetHour = sunsetTime.substringBefore(":").toIntOrNull() ?: 18
                        val sunsetMin = sunsetTime.substringAfter(":").toIntOrNull() ?: 50
                        val totalSunsetMinutes = sunsetHour * 60 + sunsetMin
                        val hikeMinutesWithBuffer = (wholeHours * 60 + minutes + 60)
                        val latestStartMinutes = (totalSunsetMinutes - hikeMinutesWithBuffer).coerceIn(6 * 60, 16 * 60)
                        val latestStartHour = latestStartMinutes / 60
                        val latestStartMin = latestStartMinutes % 60
                        val latestStartFormatted = String.format("%02d:%02d", latestStartHour, latestStartMin)

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ForestDarkBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🛡️ Råd: Start senest kl. $latestStartFormatted for at nå sikkert frem til lejrpladsen i god tid før solnedgang ($sunsetTime).",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 3. Offline Download Button with Granular Buffer (Enhancement 10)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("offline_corridor_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Offline Kort & Data (Korridor)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Kortfelter for ruten • Estimeret: ~$estimatedDownloadMb MB",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = { viewModel.startOfflineCacheDownload() },
                                enabled = !downloadProgress.isDownloading,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                modifier = Modifier.testTag("download_offline_tiles_button")
                            ) {
                                if (downloadProgress.isDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Hent")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "Vælg korridorbredde omkring stien:", color = Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0.5 to "500 m (75 MB)", 1.0 to "1,0 km (145 MB)", 2.0 to "2,0 km (320 MB)").forEach { (bufKm, label) ->
                                val isSelected = kotlin.math.abs(offlineBufferKm - bufKm) < 0.1
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setOfflineBufferKm(bufKm) }
                                        .testTag("buffer_chip_${bufKm}"),
                                    color = if (isSelected) ForestGreenPrimary else ForestDarkBg
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        if (downloadProgress.isDownloading || downloadProgress.isComplete) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress.percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = ForestGreenLight,
                                trackColor = ForestDarkBg
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (downloadProgress.isComplete) "✓ Offline kort gemt i cache ($estimatedDownloadMb MB)" else "Henter kort: ${downloadProgress.percent}% (${downloadProgress.downloadedCount}/${downloadProgress.totalCount})",
                                color = if (downloadProgress.isComplete) ForestGreenLight else Color.LightGray,
                                fontSize = 11.sp
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
                        text = "Foreslået Etapeplan ($days dage)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    if (isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = ForestGreenLight,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // Day-by-Day Stages
            itemsIndexed(stages, key = { index, _ -> "stage_$index" }) { index, stage ->
                StageItemCard(
                    stage = stage,
                    onEditStop = {
                        editingStageIndex = index
                        editStopName = stage.stopName
                    }
                )
            }
        }
    }

    // Edit Stage Stop Dialog
    if (editingStageIndex != null) {
        val currentStage = stages.getOrNull(editingStageIndex!!)
        if (currentStage != null) {
            AlertDialog(
                onDismissRequest = { editingStageIndex = null },
                containerColor = ForestSurface,
                title = { Text("Rediger stop for Dag ${currentStage.dayNumber}", color = Color.White) },
                text = {
                    Column {
                        Text("Angiv overnatningsstedets navn eller placering:", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = editStopName,
                            onValueChange = { editStopName = it },
                            label = { Text("Stedets navn") },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_stop_name_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.updateStageStop(
                                index = editingStageIndex!!,
                                newName = editStopName,
                                newType = currentStage.stopType,
                                lat = currentStage.stopLat,
                                lng = currentStage.stopLng,
                                bookingReq = currentStage.bookingRequired
                            )
                            editingStageIndex = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        modifier = Modifier.testTag("confirm_edit_stop_button")
                    ) {
                        Text("Gem")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { editingStageIndex = null },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant)
                    ) {
                        Text("Annuller", color = Color.White)
                    }
                }
            )
        }
    }

    // Fri Teltning Disclaimer Dialog before starting route
    if (showDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFriTeltningDisclaimer() },
            containerColor = ForestSurface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Overnatnings-disclaimer",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Vigtigt inden du starter turen:",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Der er IKKE Fri Teltning tilgængelig på alle dine etaper (mangler på Dag ${missingFriTeltningDays.map { it.dayNumber }.joinToString(", ")}).\n\nIfølge dansk naturbeskyttelseslov må du kun slå telt op i statens udpegede Fri Teltningsskove eller på godkendte lejrpladser.\n\nPå disse stop skal du benytte shelter eller booke officiel lejrplads på forhånd.",
                        color = Color(0xFFDDDDDD),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmStartHikingWithDisclaimer() },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_disclaimer_start_button")
                ) {
                    Text("Jeg forstår - Start ruten", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissFriTeltningDisclaimer() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("cancel_disclaimer_button")
                ) {
                    Text("Tilpas etaper")
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
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Color.LightGray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isSelected) Color.White else Color.LightGray,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun StageItemCard(
    stage: TripStage,
    onEditStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_card_${stage.dayNumber}"),
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
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(ForestGreenPrimary, CircleShape),
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

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ForestSurfaceVariant
                ) {
                    Text(
                        text = String.format("%.1f km", stage.stageKm),
                        color = ForestGreenLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Overnight stop info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (stage.stopType) {
                    "SHELTER" -> Icons.Default.Cabin
                    "FRI_TELTNING" -> Icons.Default.Forest
                    else -> Icons.Default.Navigation
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (stage.stopType == "SHELTER") ShelterBrown else ForestGreenLight,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stage.stopName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onEditStop,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Flyt/rediger stop",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Amenities & Booking Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (stage.stopType == "SHELTER") {
                    StatusChip(
                        label = if (stage.bookingRequired) "Booking påkrævet" else "Fri afbenyttelse",
                        isHighlight = stage.bookingRequired,
                        color = if (stage.bookingRequired) AmberAccent else ForestGreenPrimary
                    )
                }
                if (stage.hasWaterNearby) {
                    StatusChip(
                        label = "Vand <1 km",
                        isHighlight = false,
                        color = NordicBlue
                    )
                }
                if (stage.hasToiletNearby) {
                    StatusChip(
                        label = "Toilet <1 km",
                        isHighlight = false,
                        color = ForestGreenPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun DaylightStatItem(label: String, time: String, icon: String) {
    Surface(
        color = ForestDarkBg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, color = Color.Gray, fontSize = 10.sp)
            Text(text = time, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
