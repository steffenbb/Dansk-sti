package com.example.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.DanskStiDatabase
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.data.remote.WeatherApiService
import com.example.data.repository.AccessLandRepository
import com.example.ui.theme.ForestGreenPrimary
import com.example.util.GeoUtils
import com.example.util.access.AccessGeometryUtils
import com.example.util.routing.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Requirement F: Helper for onX-style long-press "drop pin" inspection:
 * - Coordinates in decimal and degrees/minutes (DDM)
 * - Elevation from Open-Meteo (or "ukendt")
 * - Access rules at that point from Adgang data:
 *   ("Statsskov - fri færdsel", "Privat skov - kun veje og stier, kl. 6 til solnedgang", "Ukendt ejer")
 * - Straight-line distance from the user
 */
object DropPinInspectorHelper {

    const val ACCESS_PUBLIC_FOREST = "Statsskov - fri færdsel"
    const val ACCESS_PRIVATE_FOREST = "Privat skov - kun veje og stier, kl. 6 til solnedgang"
    const val ACCESS_UNKNOWN_OWNER = "Ukendt ejer"

    fun formatDecimalCoordinates(lat: Double, lng: Double): String {
        val latHemi = if (lat >= 0) "N" else "S"
        val lngHemi = if (lng >= 0) "Ø" else "V"
        return String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), latHemi, abs(lng), lngHemi)
    }

    fun formatDegreesMinutesCoordinates(lat: Double, lng: Double): String {
        val absLat = abs(lat)
        val latDeg = floor(absLat).toInt()
        val latMin = (absLat - latDeg) * 60.0
        val latHemi = if (lat >= 0) "N" else "S"

        val absLng = abs(lng)
        val lngDeg = floor(absLng).toInt()
        val lngMin = (absLng - lngDeg) * 60.0
        val lngHemi = if (lng >= 0) "Ø" else "V"

        return String.format(
            Locale.US,
            "%d° %06.3f' %s, %d° %06.3f' %s",
            latDeg,
            latMin,
            latHemi,
            lngDeg,
            lngMin,
            lngHemi
        )
    }

    fun resolveAccessRuleAtPoint(
        lat: Double,
        lng: Double,
        polygons: List<AccessPolygon>
    ): String {
        val pt = LatLng(lat, lng)
        val matching = polygons.filter { poly ->
            poly.coordinates.size >= 3 && AccessGeometryUtils.isPointInPolygon(pt, poly.coordinates)
        }
        if (matching.isEmpty()) {
            return ACCESS_UNKNOWN_OWNER
        }
        // Prioritize PUBLIC_FOREST if overlapping, then PRIVATE_FOREST, then other specific land types
        val publicForest = matching.firstOrNull { it.type == AccessLandType.PUBLIC_FOREST }
        if (publicForest != null) {
            return ACCESS_PUBLIC_FOREST
        }
        val privateForest = matching.firstOrNull { it.type == AccessLandType.PRIVATE_FOREST }
        if (privateForest != null) {
            return ACCESS_PRIVATE_FOREST
        }
        val first = matching.first()
        return when (first.type) {
            AccessLandType.PUBLIC_FOREST -> ACCESS_PUBLIC_FOREST
            AccessLandType.PRIVATE_FOREST -> ACCESS_PRIVATE_FOREST
            AccessLandType.UNCULTIVATED_LAND -> "Udyrket areal - fri færdsel til fods kl. 6 til solnedgang"
            AccessLandType.BEACH -> "Strand og kyst - fri færdsel til fods"
            AccessLandType.CULTIVATED_FIELD -> "Dyrket mark - kun veje og stier"
        }
    }

    fun formatStraightLineDistance(userLocation: GeoPoint?, targetLat: Double, targetLng: Double): String {
        if (userLocation == null) return "Ukendt (ingen GPS-position)"
        val meters = GeoUtils.distanceMeters(
            userLocation.latitude,
            userLocation.longitude,
            targetLat,
            targetLng
        )
        return if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            String.format(Locale.GERMANY, "%.2f km", meters / 1000.0)
        }
    }

    suspend fun fetchElevationText(
        lat: Double,
        lng: Double,
        weatherService: WeatherApiService = WeatherApiService.create()
    ): String = withContext(Dispatchers.IO) {
        try {
            val response = weatherService.getElevations(
                latitudes = String.format(Locale.US, "%.5f", lat),
                longitudes = String.format(Locale.US, "%.5f", lng)
            )
            val ele = response.elevation?.firstOrNull()
            if (ele != null && !ele.isNaN()) {
                "${ele.roundToInt()} m"
            } else {
                "ukendt"
            }
        } catch (_: Exception) {
            "ukendt"
        }
    }

    fun buildShareText(
        lat: Double,
        lng: Double,
        elevationText: String,
        accessRuleText: String,
        distanceText: String
    ): String {
        val dec = formatDecimalCoordinates(lat, lng)
        val ddm = formatDegreesMinutesCoordinates(lat, lng)
        val osmUrl = String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=15/%.5f/%.5f", lat, lng, lat, lng)
        return buildString {
            appendLine("Punkt på kort (DanskSti)")
            appendLine("Koordinater (dec): $dec")
            appendLine("Koordinater (grad/min): $ddm")
            appendLine("Højde: $elevationText")
            appendLine("Adgang: $accessRuleText")
            appendLine("Afstand i fugleflugt: $distanceText")
            append("Kort: $osmUrl")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropPinBottomSheet(
    point: GeoPoint,
    userLocation: GeoPoint?,
    routeIdForSave: Long? = null,
    onDismiss: () -> Unit,
    onRouteHere: (lat: Double, lng: Double, label: String) -> Unit,
    onPointSaved: ((UserCustomWaypointEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val lat = point.latitude
    val lng = point.longitude

    val decimalCoords = remember(lat, lng) { DropPinInspectorHelper.formatDecimalCoordinates(lat, lng) }
    val ddmCoords = remember(lat, lng) { DropPinInspectorHelper.formatDegreesMinutesCoordinates(lat, lng) }
    val distanceText = remember(userLocation, lat, lng) {
        DropPinInspectorHelper.formatStraightLineDistance(userLocation, lat, lng)
    }

    var elevationText by remember(lat, lng) { mutableStateOf("Henter...") }
    var accessRuleText by remember(lat, lng) { mutableStateOf("Undersøger adgangsforhold...") }
    var ownerDetailText by remember(lat, lng) { mutableStateOf<String?>(null) }

    var showSaveForm by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("Gemt punkt") }
    var saveNote by remember { mutableStateOf("") }
    var savedConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(lat, lng) {
        elevationText = DropPinInspectorHelper.fetchElevationText(lat, lng)
    }

    LaunchedEffect(lat, lng) {
        withContext(Dispatchers.IO) {
            try {
                val db = DanskStiDatabase.getInstance(context)
                val accessRepo = AccessLandRepository(db.dao())
                val delta = 0.015
                val polygons = accessRepo.getAccessPolygons(
                    minLat = lat - delta,
                    maxLat = lat + delta,
                    minLng = lng - delta,
                    maxLng = lng + delta
                )
                val rule = DropPinInspectorHelper.resolveAccessRuleAtPoint(lat, lng, polygons)
                val pt = LatLng(lat, lng)
                val match = polygons.firstOrNull { poly ->
                    poly.coordinates.size >= 3 && AccessGeometryUtils.isPointInPolygon(pt, poly.coordinates)
                }
                accessRuleText = rule
                ownerDetailText = match?.name
            } catch (_: Exception) {
                accessRuleText = DropPinInspectorHelper.ACCESS_UNKNOWN_OWNER
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("drop_pin_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = ForestGreenPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Valgt punkt på kort",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!ownerDetailText.isNullOrBlank()) {
                            Text(
                                text = ownerDetailText!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Luk")
                }
            }

            // Coordinates Card (Decimal & Degrees/Minutes)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = ForestGreenPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = decimalCoords,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("drop_pin_decimal_coords")
                            )
                            Text(
                                text = ddmCoords,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("drop_pin_ddm_coords")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Elevation & Straight-line distance row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Landscape,
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Højde: $elevationText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("drop_pin_elevation")
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Straighten,
                                contentDescription = null,
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Afstand: $distanceText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("drop_pin_distance")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Access rules at this point
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val accessColor = when {
                            accessRuleText.startsWith("Statsskov") -> Color(0xFF2E7D32)
                            accessRuleText.startsWith("Privat skov") -> Color(0xFFEF6C00)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            imageVector = Icons.Default.Gavel,
                            contentDescription = null,
                            tint = accessColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = accessRuleText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accessColor,
                            modifier = Modifier.testTag("drop_pin_access_rule")
                        )
                    }
                }
            }

            if (showSaveForm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Gem i Mine punkter",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            label = { Text("Navn") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drop_pin_save_name_input")
                        )
                        OutlinedTextField(
                            value = saveNote,
                            onValueChange = { saveNote = it },
                            label = { Text("Note (valgfri)") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("drop_pin_save_note_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showSaveForm = false }) {
                                Text("Annuller")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val wp = UserCustomWaypointEntity(
                                        id = "custom_wp_${UUID.randomUUID()}",
                                        routeId = routeIdForSave,
                                        lat = lat,
                                        lng = lng,
                                        name = saveName.ifBlank { "Gemt punkt" },
                                        note = saveNote.trim()
                                    )
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            DanskStiDatabase.getInstance(context).dao().insertCustomWaypoint(wp)
                                        }
                                        onPointSaved?.invoke(wp)
                                        savedConfirmation = true
                                        showSaveForm = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                                modifier = Modifier.testTag("drop_pin_confirm_save_button")
                            ) {
                                Text("Gem")
                            }
                        }
                    }
                }
            } else if (savedConfirmation) {
                Text(
                    text = "✓ Punktet er gemt under Mine punkter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ForestGreenPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Three standard actions: Rute hertil, Gem punkt, Del position
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val label = ownerDetailText?.takeIf { it.isNotBlank() } ?: "Valgt punkt på kort"
                        onRouteHere(lat, lng, label)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("confirm_route_here_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rute hertil", maxLines = 1)
                }

                OutlinedButton(
                    onClick = { showSaveForm = !showSaveForm },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("drop_pin_save_point_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gem punkt", maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        shareDropPinPosition(
                            context = context,
                            lat = lat,
                            lng = lng,
                            elevationText = elevationText,
                            accessRuleText = accessRuleText,
                            distanceText = distanceText
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("drop_pin_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Del position", maxLines = 1)
                }
            }
        }
    }
}

private fun shareDropPinPosition(
    context: Context,
    lat: Double,
    lng: Double,
    elevationText: String,
    accessRuleText: String,
    distanceText: String
) {
    val shareBody = DropPinInspectorHelper.buildShareText(
        lat = lat,
        lng = lng,
        elevationText = elevationText,
        accessRuleText = accessRuleText,
        distanceText = distanceText
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Position fra DanskSti")
        putExtra(Intent.EXTRA_TEXT, shareBody)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Del position").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}
}
