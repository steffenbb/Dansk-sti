package com.example.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.NearestPoiCategory
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RouteBuilderFeatureDialogsHost(
    uiState: RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    context: Context,
    mapViewInstance: MapView?,
    customWpName: String,
    onCustomWpNameChange: (String) -> Unit,
    customWpNote: String,
    onCustomWpNoteChange: (String) -> Unit,
    customWpPhotoPath: String?,
    onCustomWpPhotoPathChange: (String?) -> Unit,
    onLaunchCamera: () -> Unit,
    onLaunchGallery: () -> Unit,
    availableHeightDp: Float? = null
) {
    val danskStiColors = DanskStiTheme.colors
    val configuration = LocalConfiguration.current
    val realAvailableHeightDp = (availableHeightDp ?: configuration.screenHeightDp.takeIf { it > 0 }?.toFloat() ?: 640f)
        .coerceAtLeast(240f)
    val overnightPickerMaxHeight = (realAvailableHeightDp * 0.50f).dp

    if (uiState.showTripSettingsSheet) {
        TripSettingsDialog(
            uiState = uiState,
            onUpdateSettings = { pace, dailyKm, maxDetourKm, startTimeStr, startEpochMs, pref ->
                val parsedPref = try {
                    com.example.data.model.OvernightType.valueOf(pref)
                } catch (_: Exception) {
                    com.example.data.model.OvernightType.BOTH
                }
                viewModel.updateTripSettings(
                    paceKmh = pace,
                    dailyTargetKm = dailyKm,
                    startDateMillis = startEpochMs,
                    startTimeStr = startTimeStr,
                    maxDetourKm = maxDetourKm,
                    overnightPreference = parsedPref
                )
            },
            onDismiss = { viewModel.dismissTripSettingsSheet() }
        )
    }

    if (uiState.showOvernightDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOvernightDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ui_routebuilderscreen_v_lg_overnatningssted_134), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                if (uiState.overnightCandidatesByDay.isEmpty()) {
                    Text(stringResource(R.string.ui_routebuilderscreen_ingen_overnatningssteder_fun_135), fontSize = 14.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = overnightPickerMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        uiState.overnightCandidatesByDay.forEach { (day, candidates) ->
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ui_routebuilderscreen_dag_ca_km_m_l_136, day, day * 15),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            items(candidates) { cand ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.insertOvernightCandidate(cand) }
                                        .testTag("candidate_${cand.id}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 44.dp)
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(cand.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            val typeLabel = if (cand.type == "FRI_TELTNING") "Fri teltning" else "Shelter"
                                            Text(
                                                text = stringResource(R.string.ui_routebuilderscreen_km_m_omvej_137, typeLabel, String.format(Locale.US, "%.1f", cand.walkKm), cand.detourMeters.toInt()),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                if (cand.hasWaterNearby) {
                                                    Text(stringResource(R.string.ui_routebuilderscreen_vand_138), fontSize = 10.sp, color = danskStiColors.water)
                                                }
                                                if (cand.hasToiletNearby) {
                                                    Text(stringResource(R.string.ui_routebuilderscreen_toilet_139), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                                if (cand.bookingRequired) {
                                                    Text(stringResource(R.string.ui_routebuilderscreen_booking_140), fontSize = 10.sp, color = danskStiColors.warning)
                                                } else {
                                                    Text(stringResource(R.string.ui_routebuilderscreen_fri_afbenyttelse_141), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                        Button(
                                            onClick = { viewModel.insertOvernightCandidate(cand) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.heightIn(min = 30.dp)
                                        ) {
                                            Text(stringResource(R.string.ui_routebuilderscreen_v_lg_132), fontSize = 11.sp)
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
                TextButton(onClick = { viewModel.dismissOvernightDialog() }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (uiState.showRundturDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRundturDialog() },
            icon = {
                Icon(
                    Icons.Default.Loop,
                    contentDescription = null,
                    tint = danskStiColors.plannedRoute,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(stringResource(R.string.ui_routebuilderscreen_gener_r_rundtur_142), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_v_lg_nsket_rutedistance_5_60_143),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ui_routebuilderscreen_m_ll_ngde_km_144, uiState.rundturTargetKm.toInt()),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(10.0, 15.0, 20.0, 30.0).forEach { kmVal ->
                                OutlinedButton(
                                    onClick = { viewModel.setRundturTargetKm(kmVal) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 28.dp)
                                ) {
                                    Text(stringResource(R.string.ui_routebuilderscreen_k_145, kmVal.toInt()), fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Slider(
                        value = uiState.rundturTargetKm.toFloat(),
                        onValueChange = { viewModel.setRundturTargetKm(it.toDouble()) },
                        valueRange = 5f..60f,
                        steps = 54,
                        colors = SliderDefaults.colors(
                            thumbColor = danskStiColors.plannedRoute,
                            activeTrackColor = danskStiColors.plannedRoute
                        ),
                        modifier = Modifier.testTag("slider_rundtur_target_km")
                    )

                    Button(
                        onClick = {
                            val loc = getLastKnownLocation(context)
                            viewModel.generateRundtur(loc?.latitude, loc?.longitude)
                        },
                        enabled = !uiState.isGeneratingRundtur,
                        colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.plannedRoute),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 38.dp)
                            .testTag("btn_generate_rundtur_action")
                    ) {
                        if (uiState.isGeneratingRundtur) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_routebuilderscreen_beregner_3_rundture_146), fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_routebuilderscreen_beregn_3_rundture_147), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (uiState.rundturCandidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ui_routebuilderscreen_fundne_rundture_148),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        uiState.rundturCandidates.forEach { candidate ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, danskStiColors.plannedRoute.copy(alpha = 0.4f)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth().testTag("candidate_card_${candidate.id}")
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = candidate.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = stringResource(R.string.ui_markedroutedetailsheet_km_19, String.format(Locale.GERMANY, "%.1f", candidate.actualKm)),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = stringResource(R.string.ui_routebuilderscreen_sti_m_m_149, candidate.pathSharePct.toInt(), candidate.ascentM.toInt(), candidate.descentM.toInt()),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(R.string.ui_routebuilderscreen_overnatningsmuligheder_s_150, candidate.overnightCount),
                                        fontSize = 11.sp,
                                        color = if (candidate.overnightCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Button(
                                        onClick = { viewModel.applyRundtur(candidate) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 32.dp)
                                            .testTag("btn_select_rundtur_${candidate.id}")
                                    ) {
                                        Text(stringResource(R.string.ui_routebuilderscreen_v_lg_denne_rundtur_151), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRundturDialog() }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    if (uiState.showNearestPoiDialog) {
        val currentMapCenter = mapViewInstance?.mapCenter
        val origin = viewModel.resolveNearestSearchOrigin(
            mapCenterLat = currentMapCenter?.latitude,
            mapCenterLng = currentMapCenter?.longitude
        )
        AlertDialog(
            onDismissRequest = { viewModel.dismissNearestPoiDialog() },
            icon = {
                Icon(
                    Icons.Default.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(stringResource(R.string.ui_routebuilderscreen_rute_til_n_rmeste_152), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(origin.labelResId),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("nearest_poi_origin_label")
                        )
                    }

                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_v_lg_kategori_appen_beregner_153),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (uiState.isRoutingToNearest) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.ui_routebuilderscreen_beregner_rute_til_n_rmeste_154), fontSize = 12.sp)
                            }
                        }
                    } else if (uiState.showNearestShelterRoleChoice) {
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("nearest_shelter_role_choice_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.nearest_shelter_choice_title),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Button(
                                    onClick = {
                                        viewModel.confirmNearestShelterRole(
                                            com.example.util.routing.WaypointRole.OVERNIGHT
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .testTag("btn_nearest_shelter_overnight")
                                ) {
                                    Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.nearest_shelter_choice_overnight),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.confirmNearestShelterRole(
                                            com.example.util.routing.WaypointRole.VIA
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .testTag("btn_nearest_shelter_stop")
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.nearest_shelter_choice_stop),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else {
                        NearestPoiCategory.values().forEach { cat ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onNearestCategorySelected(
                                            category = cat,
                                            mapCenterLat = currentMapCenter?.latitude,
                                            mapCenterLng = currentMapCenter?.longitude
                                        )
                                    }
                                    .testTag("btn_nearest_${cat.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(cat.icon, fontSize = 20.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when (cat) {
                                                NearestPoiCategory.SHELTER -> "Nærmeste shelter / teltplads"
                                                NearestPoiCategory.WATER -> "Nærmeste vandpost"
                                                NearestPoiCategory.TOILET -> "Nærmeste toilet"
                                                NearestPoiCategory.SHOP -> "Nærmeste butik / indkøb"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = stringResource(R.string.ui_routebuilderscreen_beregner_hurtigste_vandrerut_155),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNearestPoiDialog() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (uiState.showAddCustomWaypointDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissAddCustomWaypointDialog()
                onCustomWpNameChange("")
                onCustomWpNoteChange("")
                onCustomWpPhotoPathChange(null)
            },
            icon = {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(stringResource(R.string.ui_routebuilderscreen_nyt_punkt_156), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val loc = uiState.pendingCustomWaypointLocation
                    if (loc != null) {
                        Text(
                            text = stringResource(R.string.ui_routebuilderscreen_position_157, String.format(Locale.US, "%.5f, %.5f", loc.lat, loc.lng)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = customWpName,
                        onValueChange = onCustomWpNameChange,
                        label = { Text(stringResource(R.string.save_point_name_label)) },
                        placeholder = { Text(stringResource(R.string.ui_routebuilderscreen_f_eks_flot_udsigtspunkt_158)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_custom_wp_name")
                    )

                    OutlinedTextField(
                        value = customWpNote,
                        onValueChange = onCustomWpNoteChange,
                        label = { Text(stringResource(R.string.ui_routebuilderscreen_note_beskrivelse_159)) },
                        placeholder = { Text(stringResource(R.string.ui_routebuilderscreen_f_eks_b_nk_under_egetr_et_160)) },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_custom_wp_note")
                    )

                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_foto_valgfrit_161),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )

                    if (!customWpPhotoPath.isNullOrBlank() && File(customWpPhotoPath).exists()) {
                        val bitmap = remember(customWpPhotoPath) {
                            try {
                                BitmapFactory.decodeFile(customWpPhotoPath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.ui_routebuilderscreen_valgt_foto_162),
                                    modifier = Modifier
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                        TextButton(
                            onClick = { onCustomWpPhotoPathChange(null) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.ui_routebuilderscreen_fjern_foto_163), fontSize = 11.sp)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onLaunchCamera,
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("btn_camera_capture")
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_routebuilderscreen_kamera_164), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onLaunchGallery,
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("btn_gallery_pick")
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_routebuilderscreen_galleri_165), fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveCustomWaypoint(customWpName, customWpNote, customWpPhotoPath)
                        onCustomWpNameChange("")
                        onCustomWpNoteChange("")
                        onCustomWpPhotoPathChange(null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.testTag("btn_save_custom_wp")
                ) { Text(stringResource(R.string.save_point_dialog_title)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissAddCustomWaypointDialog()
                        onCustomWpNameChange("")
                        onCustomWpNoteChange("")
                        onCustomWpPhotoPathChange(null)
                    }
                ) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    uiState.selectedCustomWaypointForDetails?.let { wp ->
        AlertDialog(
            onDismissRequest = { viewModel.selectCustomWaypointForDetails(null) },
            icon = {
                Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp))
            },
            title = { Text(wp.name, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wp.photoPath.isNullOrBlank()) {
                        val bitmap = remember(wp.photoPath) {
                            runCatching { if (File(wp.photoPath).exists()) BitmapFactory.decodeFile(wp.photoPath) else null }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = wp.name,
                                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp))
                            )
                        }
                        TextButton(
                            onClick = { viewModel.deleteCustomWaypointPhoto(wp.id) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("btn_delete_waypoint_photo")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.ui_routebuilderscreen_fjern_foto_163), fontSize = 12.sp)
                        }
                    }

                    if (wp.note.isNotBlank()) {
                        Text(text = wp.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_position_157, String.format(Locale.US, "%.5f, %.5f", wp.lat, wp.lng)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                mapViewInstance?.controller?.animateTo(GeoPoint(wp.lat, wp.lng), 16.0, 500L)
                                viewModel.selectCustomWaypointForDetails(null)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_view_on_map), fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.deleteCustomWaypoint(wp.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).testTag("btn_delete_custom_waypoint_dialog")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.btn_delete), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.selectCustomWaypointForDetails(null) }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
}

