package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.*
import com.example.util.routing.WaypointRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RouteBuilderBasicDialogs(
    uiState: RouteBuilderState,
    viewModel: RouteBuilderViewModel,
    showClearDialog: Boolean,
    onDismissClearDialog: () -> Unit,
    showSaveDialog: Boolean,
    onDismissSaveDialog: () -> Unit,
    renameRouteName: String,
    onRenameRouteNameChange: (String) -> Unit,
    onSavedSuccessfully: () -> Unit,
    showRenameDialog: Boolean,
    onDismissRenameDialog: () -> Unit,
    showRenameWaypointDialog: Boolean,
    renameWaypointTargetId: String?,
    renameWaypointText: String,
    onRenameWaypointTextChange: (String) -> Unit,
    onDismissRenameWaypointDialog: () -> Unit,
    availableHeightDp: Float? = null
) {
    val danskStiColors = DanskStiTheme.colors
    val configuration = LocalConfiguration.current
    val realAvailableHeightDp = (availableHeightDp ?: configuration.screenHeightDp.takeIf { it > 0 }?.toFloat() ?: 640f)
        .coerceAtLeast(240f)
    val replaceOvernightMaxHeight = (realAvailableHeightDp * 0.45f).dp

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = onDismissClearDialog,
            title = { Text(stringResource(R.string.ui_routebuilderscreen_ryd_rute_112)) },
            text = { Text(stringResource(R.string.ui_routebuilderscreen_er_du_sikker_p_at_du_vil_sle_113)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearRoute()
                        onDismissClearDialog()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.map_measure_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearDialog) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = onDismissSaveDialog,
            title = { Text(stringResource(R.string.bookmark_add_cd)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_routebuilderscreen_angiv_et_navn_til_din_rute_114), fontSize = 13.sp)
                    OutlinedTextField(
                        value = renameRouteName,
                        onValueChange = onRenameRouteNameChange,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ui_routebuilderscreen_f_eks_rold_skov_rundtur_115)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_save_route_name")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateRouteName(renameRouteName)
                        onDismissSaveDialog()
                        viewModel.saveRoute(onSuccess = {
                            onSavedSuccessfully()
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("btn_confirm_save_route")
                ) {
                    Text(stringResource(R.string.ui_routebuilderscreen_gem_til_gemte_116))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSaveDialog) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (uiState.showResetProgressDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissResetProgressDialog() },
            title = { Text(stringResource(R.string.ui_routebuilderscreen_ruten_er_ndret_nulstil_frems_117)) },
            text = {
                Text(stringResource(R.string.ui_routebuilderscreen_denne_rute_har_registreret_v_118))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmResetProgressAndSave() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("btn_confirm_reset_progress")
                ) {
                    Text(stringResource(R.string.ui_routebuilderscreen_ja_nulstil_119))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissResetProgressDialog() },
                    modifier = Modifier.testTag("btn_cancel_reset_progress")
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = onDismissRenameDialog,
            title = { Text(stringResource(R.string.ui_routebuilderscreen_rutenavn_120)) },
            text = {
                OutlinedTextField(
                    value = renameRouteName,
                    onValueChange = onRenameRouteNameChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateRouteName(renameRouteName)
                        onDismissRenameDialog()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRenameDialog) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    if (showRenameWaypointDialog && renameWaypointTargetId != null) {
        AlertDialog(
            onDismissRequest = onDismissRenameWaypointDialog,
            title = { Text(stringResource(R.string.planner_rename_waypoint_title)) },
            text = {
                OutlinedTextField(
                    value = renameWaypointText,
                    onValueChange = onRenameWaypointTextChange,
                    label = { Text(stringResource(R.string.save_point_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_waypoint_name")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameWaypoint(renameWaypointTargetId, renameWaypointText)
                        onDismissRenameWaypointDialog()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("btn_confirm_rename_waypoint")
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRenameWaypointDialog) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    uiState.trailLineTapTarget?.let { tap ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTrailLineTapSheet() },
            title = {
                Text(
                    text = stringResource(R.string.planner_on_trail_at_km_fmt, String.format(Locale.GERMANY, "%.1f", tap.trailKm)),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("trail_line_tap_sheet")
                ) {
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_v_lg_handling_for_dette_punk_123),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { viewModel.onTrailTapStartHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.success),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .testTag("btn_trail_tap_start_here")
                    ) {
                        Text(stringResource(R.string.ui_routebuilderscreen_start_her_124), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.onTrailTapSlutHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.danger),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .testTag("btn_trail_tap_end_here")
                    ) {
                        Text(stringResource(R.string.ui_routebuilderscreen_slut_her_125), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.onTrailTapOvernatHer() },
                        colors = ButtonDefaults.buttonColors(containerColor = danskStiColors.overnight),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .testTag("btn_trail_tap_overnight_here")
                    ) {
                        Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.ui_routebuilderscreen_overnat_her_126), fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { viewModel.onTrailTapTilfoejPunkt() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .testTag("btn_trail_tap_add_point")
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.ui_routebuilderscreen_tilf_j_punkt_127), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissTrailLineTapSheet() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    uiState.selectedNearbyPoiForSheet?.let { poi ->
        val sheetData = remember(poi) {
            com.example.ui.components.PoiInfoSheetData.fromPlannerPoi(poi)
        }
        val liveGeoPoint = remember(uiState.currentGpsPosition) {
            uiState.currentGpsPosition?.let { org.osmdroid.util.GeoPoint(it.lat, it.lng) }
        }
        com.example.ui.components.PoiInfoSheet(
            poiData = sheetData,
            liveUserLocation = liveGeoPoint,
            isLocationFresh = uiState.currentGpsPosition != null,
            onDismiss = { viewModel.selectNearbyPoiForSheet(null) },
            onRouteHere = { lat, lng, label ->
                viewModel.selectNearbyPoiForSheet(null)
                viewModel.addDetourPoiToRoute(lat = lat, lng = lng, name = label, role = WaypointRole.VIA, poiId = poi.id, poiType = poi.type)
            },
            onAddToRoute = { lat, lng, label ->
                viewModel.selectNearbyPoiForSheet(null)
                viewModel.addDetourPoiToRoute(lat = lat, lng = lng, name = label, role = WaypointRole.VIA, poiId = poi.id, poiType = poi.type)
            },
            onOvernightHereInPlanner = {
                viewModel.selectNearbyPoiForSheet(null)
                viewModel.addDetourPoiToRoute(lat = poi.lat, lng = poi.lng, name = poi.name, role = WaypointRole.OVERNIGHT, poiId = poi.id, poiType = poi.type)
            }
        )
    }

    if (uiState.replacingOvernightWaypointId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReplaceOvernightDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ui_routebuilderscreen_skift_overnatning_121), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                if (uiState.replaceOvernightCandidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_routebuilderscreen_ingen_andre_shelters_ell_130, String.format(Locale.GERMANY, "%.1f", uiState.maxDetourKm)),
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = replaceOvernightMaxHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.replaceOvernightCandidates) { cand ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.replaceOvernightWaypoint(cand) }
                                    .testTag("replace_candidate_${cand.id}")
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
                                        val typeLabel = if (cand.type == "FRI_TELTNING") stringResource(R.string.planner_pref_free_tent) else stringResource(R.string.poi_type_shelter)
                                        Text(
                                            text = stringResource(R.string.ui_routebuilderscreen_km_m_omvej_131, typeLabel, String.format(Locale.GERMANY, "%.1f", cand.walkKm), cand.detourMeters.toInt()),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { viewModel.replaceOvernightWaypoint(cand) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 30.dp)
                                    ) {
                                        Text(stringResource(R.string.ui_routebuilderscreen_v_lg_132), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissReplaceOvernightDialog() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    uiState.selectedAccessPolygonForDetails?.let { poly ->
        com.example.ui.components.AccessPolygonRulesDialog(
            polygon = poly,
            onDismiss = { viewModel.selectAccessPolygon(null) }
        )
    }

    uiState.pendingDraftToRestore?.let { draft ->
        AlertDialog(
            onDismissRequest = { viewModel.discardDraft() },
            title = { Text(stringResource(R.string.planner_draft_continue_title), fontWeight = FontWeight.Bold) },
            text = {
                val label = draft.routeName.ifBlank { stringResource(R.string.planner_unsaved_route_default) }
                Text(stringResource(R.string.planner_draft_restore_message_fmt, label))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.continueDraft() },
                    modifier = Modifier.testTag("btn_draft_continue")
                ) {
                    Text(stringResource(R.string.planner_draft_continue_title))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.discardDraft() },
                    modifier = Modifier.testTag("btn_draft_discard")
                ) {
                    Text(stringResource(R.string.planner_discard_button))
                }
            },
            modifier = Modifier.testTag("planner_draft_restore_dialog")
        )
    }
}

@Composable
internal fun PlannerUnsavedChangesDialog(
    show: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onContinueEditing,
        title = { Text(stringResource(R.string.planner_save_route_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.planner_unsaved_changes_message)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDiscard,
                    modifier = Modifier.testTag("btn_unsaved_discard")
                ) {
                    Text(stringResource(R.string.planner_discard_button), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.testTag("btn_unsaved_save")
                ) {
                    Text(stringResource(R.string.btn_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueEditing,
                modifier = Modifier.testTag("btn_unsaved_continue")
            ) {
                Text(stringResource(R.string.planner_continue_editing_button))
            }
        },
        modifier = Modifier.testTag("planner_unsaved_changes_dialog")
    )
}

@Composable
internal fun TripSettingsDialog(
    uiState: RouteBuilderState,
    onUpdateSettings: (Double, Double, Double, String, Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var pace by remember(uiState.paceKmh) { mutableStateOf(uiState.paceKmh) }
    var dailyKm by remember(uiState.dailyTargetKm) { mutableStateOf(uiState.dailyTargetKm) }
    var maxDetourKm by remember(uiState.maxDetourKm) { mutableStateOf(uiState.maxDetourKm) }
    var startTimeStr by remember(uiState.startTimeStr) { mutableStateOf(uiState.startTimeStr) }
    var selectedEpochMs by remember(uiState.startDateEpochMs) { mutableStateOf(uiState.startDateEpochMs) }
    var startDateOffsetDays by remember(uiState.startDateEpochMs) {
        val diff = ((uiState.startDateEpochMs - System.currentTimeMillis()) / (24L * 3600_000L)).toInt().coerceIn(0, 30)
        mutableStateOf(diff)
    }
    var overnightPref by remember(uiState.overnightPreference) { mutableStateOf(uiState.overnightPreference.name) }

    val formattedStartDate = remember(selectedEpochMs) {
        SimpleDateFormat("d. MMM yyyy", Locale("da", "DK")).format(Date(selectedEpochMs))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ui_routebuilderscreen_indstillinger_for_turen_102), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("trip_settings_sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.setting_my_pace), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "%.1f km/t", pace),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("trip_settings_pace_value")
                        )
                    }
                    Slider(
                        value = pace.toFloat().coerceIn(
                            com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT.start,
                            com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT.endInclusive
                        ),
                        onValueChange = { pace = com.example.data.local.HikingSettingsRanges.clampPaceKmh(it.toDouble()) },
                        valueRange = com.example.data.local.HikingSettingsRanges.PACE_RANGE_FLOAT,
                        steps = com.example.data.local.HikingSettingsRanges.PACE_SLIDER_STEPS,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("slider_trip_pace")
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_routebuilderscreen_dagsm_l_km_dag_166), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = stringResource(R.string.ui_markedroutedetailsheet_km_19, dailyKm.toInt()),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("trip_settings_daily_km_value")
                        )
                    }
                    Slider(
                        value = dailyKm.toFloat().coerceIn(
                            com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT.start,
                            com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT.endInclusive
                        ),
                        onValueChange = { dailyKm = com.example.data.local.HikingSettingsRanges.clampDailyTargetKm(it.toDouble()) },
                        valueRange = com.example.data.local.HikingSettingsRanges.DAILY_TARGET_RANGE_FLOAT,
                        steps = com.example.data.local.HikingSettingsRanges.DAILY_TARGET_SLIDER_STEPS,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("slider_trip_daily_km")
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_routebuilderscreen_maks_omvej_til_overnatning_167), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "%.1f km (%d m)", maxDetourKm, (maxDetourKm * 1000).toInt()),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = maxDetourKm.toFloat().coerceIn(0.2f, 2.5f),
                        onValueChange = { maxDetourKm = (Math.round(it * 10f) / 10.0) },
                        valueRange = 0.2f..2.5f,
                        steps = 22,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("slider_trip_max_detour")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.ui_routebuilderscreen_startdato_168), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (startDateOffsetDays == 0) {
                                stringResource(R.string.planner_start_date_today_fmt, formattedStartDate)
                            } else {
                                stringResource(R.string.planner_start_date_in_days_fmt, startDateOffsetDays, formattedStartDate)
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = startDateOffsetDays.toFloat().coerceIn(0f, 14f),
                        onValueChange = {
                            startDateOffsetDays = it.toInt()
                            selectedEpochMs = System.currentTimeMillis() + startDateOffsetDays * 24L * 3600_000L
                        },
                        valueRange = 0f..14f,
                        steps = 13,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("slider_trip_start_date")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.setting_start_time), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = String.format(Locale.GERMANY, "kl. %s", startTimeStr),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("trip_settings_start_time_value")
                        )
                    }
                    Slider(
                        value = com.example.data.local.HikingSettingsRanges.startTimeToSliderIndex(startTimeStr),
                        onValueChange = { startTimeStr = com.example.data.local.HikingSettingsRanges.sliderIndexToStartTime(it) },
                        valueRange = com.example.data.local.HikingSettingsRanges.START_TIME_SLIDER_RANGE,
                        steps = com.example.data.local.HikingSettingsRanges.START_TIME_SLIDER_STEPS,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("slider_trip_start_hour")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ui_routebuilderscreen_overnatningstype_169), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "BOTH" to stringResource(R.string.planner_pref_both),
                            "SHELTER" to stringResource(R.string.planner_pref_shelter_only),
                            "FRI_TELTNING" to stringResource(R.string.planner_pref_free_tent)
                        ).forEach { (key, label) ->
                            OutlinedButton(
                                onClick = { overnightPref = key },
                                shape = RoundedCornerShape(8.dp),
                                colors = if (overnightPref == key) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 34.dp)
                            ) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdateSettings(
                        pace,
                        dailyKm,
                        maxDetourKm,
                        startTimeStr,
                        selectedEpochMs,
                        overnightPref
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("btn_save_trip_settings")
            ) {
                Text(stringResource(R.string.ui_routebuilderscreen_gem_indstillinger_170), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

