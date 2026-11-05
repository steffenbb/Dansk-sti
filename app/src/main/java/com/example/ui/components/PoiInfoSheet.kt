package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.NightShelter
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DanskStiApp
import com.example.data.local.DanskStiDatabase
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.access.AccessPolygon
import com.example.ui.navigation.PoiItem
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.PlannerMapPoi
import com.example.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Normalized data model for the shared [PoiInfoSheet] (Round 10A Item 1).
 */
data class PoiInfoSheetData(
    val id: String,
    val name: String,
    val rawType: String,
    val typeLabel: String,
    val lat: Double,
    val lng: Double,
    val capacityText: String? = null,
    val bookingText: String? = null,
    val bookingRequired: Boolean? = null,
    val bookingUrl: String? = null,
    val facilities: List<String> = emptyList(),
    val description: String? = null,
    val dataSource: String = "OpenStreetMap / GeoFA",
    val fetchedAtMillis: Long = System.currentTimeMillis()
) {
    val formattedDate: String
        get() = try {
            SimpleDateFormat("dd.MM.yyyy", Locale.forLanguageTag("da-DK")).format(Date(fetchedAtMillis))
        } catch (_: Exception) {
            "2025"
        }

    val sourceAndDateLabel: String
        get() = "$dataSource · $formattedDate"

    companion object {
        fun fromPoiItem(
            item: PoiItem,
            shelters: List<ShelterEntity> = emptyList(),
            friTeltning: List<FriTeltningEntity> = emptyList(),
            pois: List<PoiEntity> = emptyList()
        ): PoiInfoSheetData {
            val payload = item.payload
            if (payload is ShelterEntity) return fromShelter(payload)
            if (payload is FriTeltningEntity) return fromFriTeltning(payload)
            if (payload is PoiEntity) return fromPoiEntity(payload)
            if (payload is UserCustomWaypointEntity) return fromCustomWaypoint(payload)

            val matchedShelter = shelters.firstOrNull { it.id == item.id }
            if (matchedShelter != null) return fromShelter(matchedShelter)

            val matchedFri = friTeltning.firstOrNull { it.id == item.id }
            if (matchedFri != null) return fromFriTeltning(matchedFri)

            val matchedPoi = pois.firstOrNull { it.id == item.id }
            if (matchedPoi != null) return fromPoiEntity(matchedPoi)

            val norm = item.type.lowercase()
            val typeLabel = when (norm) {
                "shelter" -> "Shelter / lejrplads"
                "tent", "fri_teltning" -> "Fri teltning skov (1-2-3 reglen)"
                "water", "drinking_water", "vand" -> "Drikkevand / vandpost"
                "toilet", "toilets" -> "Offentligt toilet"
                "shop", "supermarket", "indkoeb" -> "Indkøb / butik"
                "transit", "station", "ferry" -> "Offentlig transport"
                "my_point" -> "Gemt punkt"
                else -> item.type
            }
            val facList = buildList {
                if (item.subText.isNotBlank()) add(item.subText)
                if (norm == "water") add("Drikkevand")
                if (norm == "toilet") add("Toilet")
                if (norm == "shop") add("Indkøb")
            }
            val isOvernight = norm == "shelter" || norm == "tent" || norm == "fri_teltning"
            return PoiInfoSheetData(
                id = item.id,
                name = item.name,
                rawType = norm,
                typeLabel = typeLabel,
                lat = item.lat,
                lng = item.lng,
                capacityText = if (norm == "shelter") "Ukendt kapacitet" else null,
                bookingText = if (isOvernight) {
                    if (item.subText.contains("Booking", ignoreCase = true)) "Kræver booking" else "Fri afbenyttelse"
                } else null,
                bookingRequired = if (isOvernight) item.subText.contains("Booking", ignoreCase = true) else null,
                facilities = facList,
                dataSource = if (norm == "shelter" || norm == "tent" || norm == "fri_teltning") {
                    "GeoFA / Naturstyrelsen"
                } else {
                    "OpenStreetMap (ODbL)"
                }
            )
        }

        fun fromShelter(s: ShelterEntity): PoiInfoSheetData {
            val facilities = buildList {
                if (s.hasWaterNearby) add("Drikkevand i nærheden")
                if (s.hasToiletNearby) add("Toilet i nærheden")
                if (s.hasFireRing) add("Bålplads")
                if (s.hasFirewood) add("Brænde")
                if (s.dogAllowed) add("Hund i snor tilladt")
                if (s.hasElectricity) add("Strøm")
                if (s.fee && s.priceDkk > 0) add("Pris: ${s.priceDkk} kr.") else if (!s.fee) add("Gratis")
            }
            val sourceLabel = when (s.source.lowercase()) {
                "geofa" -> "GeoFA / Naturstyrelsen"
                "osm" -> "OpenStreetMap (ODbL)"
                else -> s.source
            }
            return PoiInfoSheetData(
                id = s.id,
                name = s.name,
                rawType = "shelter",
                typeLabel = s.shelterType.ifBlank { "Shelter / lejrplads" },
                lat = s.lat,
                lng = s.lng,
                capacityText = s.capacity?.let { "$it pladser" },
                bookingText = if (s.bookingRequired) "Kræver booking" else "Fri afbenyttelse (først-til-mølle)",
                bookingRequired = s.bookingRequired,
                bookingUrl = s.bookingUrl,
                facilities = facilities,
                description = s.description,
                dataSource = sourceLabel,
                fetchedAtMillis = s.fetchedAt
            )
        }

        fun fromFriTeltning(f: FriTeltningEntity): PoiInfoSheetData {
            val facilities = buildList {
                add("1-2-3 reglen (maks. 1 nat, ${f.maxTents} telte, ${f.maxPersonsPerTent}-pers. telte)")
                if (f.hammockFriendly) add("Hængekøje tilladt")
                if (f.openFireAllowed) add("Åben ild ved bålplads") else add("Kun stormkøkken")
            }
            val sourceLabel = when (f.source.lowercase()) {
                "geofa" -> "GeoFA / Naturstyrelsen"
                else -> f.source
            }
            return PoiInfoSheetData(
                id = f.id,
                name = f.name,
                rawType = "tent",
                typeLabel = f.zoneType.ifBlank { "Fri teltning skov" },
                lat = f.centerLat,
                lng = f.centerLng,
                capacityText = "Maks. ${f.maxTents} telte (${f.maxPersonsPerTent} pers./telt)",
                bookingText = "Ingen booking – fri teltning",
                bookingRequired = false,
                bookingUrl = f.rulesUrl,
                facilities = facilities,
                description = f.rulesSummary,
                dataSource = sourceLabel,
                fetchedAtMillis = f.fetchedAt
            )
        }

        fun fromPoiEntity(p: PoiEntity): PoiInfoSheetData {
            val norm = p.type.lowercase()
            val typeLabel = when (norm) {
                "water", "drinking_water", "vand" -> "Drikkevand / vandpost"
                "toilet", "toilets" -> "Offentligt toilet"
                "shop", "supermarket", "convenience", "bakery", "indkoeb" -> "Indkøb / butik"
                else -> "Offentlig transport"
            }
            val facilities = buildList {
                if (norm.contains("water") || norm == "vand") add("Drikkevand")
                if (norm.contains("toilet")) add("Toilet")
                if (norm.contains("shop") || norm == "supermarket" || norm == "convenience" || norm == "bakery") add("Dagligvarer / indkøb")
                p.openingHours?.takeIf { it.isNotBlank() }?.let { add("Åbningstider: $it") }
                p.operator?.takeIf { it.isNotBlank() }?.let { add("Operatør: $it") }
                if (p.fee) add("Betaling påkrævet") else add("Gratis adgang")
            }
            val sourceLabel = when (p.source.lowercase()) {
                "geofa" -> "GeoFA / OpenStreetMap"
                "osm" -> "OpenStreetMap (ODbL)"
                else -> p.source
            }
            return PoiInfoSheetData(
                id = p.id,
                name = p.name.ifBlank { typeLabel },
                rawType = norm,
                typeLabel = typeLabel,
                lat = p.lat,
                lng = p.lng,
                capacityText = null,
                bookingText = null,
                bookingRequired = null,
                bookingUrl = p.website,
                facilities = facilities,
                dataSource = sourceLabel,
                fetchedAtMillis = p.fetchedAt
            )
        }

        fun fromCustomWaypoint(wp: UserCustomWaypointEntity): PoiInfoSheetData {
            return PoiInfoSheetData(
                id = wp.id,
                name = wp.name,
                rawType = "my_point",
                typeLabel = "Gemt punkt (Mine punkter)",
                lat = wp.lat,
                lng = wp.lng,
                facilities = if (wp.note.isNotBlank()) listOf(wp.note) else listOf("Brugeroprettet punkt"),
                description = wp.note.takeIf { it.isNotBlank() },
                dataSource = "Lokalt gemt i DanskSti",
                fetchedAtMillis = wp.createdAt
            )
        }

        fun fromPlannerPoi(
            poi: PlannerMapPoi,
            shelters: List<ShelterEntity> = emptyList(),
            friTeltning: List<FriTeltningEntity> = emptyList(),
            pois: List<PoiEntity> = emptyList()
        ): PoiInfoSheetData {
            shelters.firstOrNull { it.id == poi.id }?.let { return fromShelter(it) }
            friTeltning.firstOrNull { it.id == poi.id }?.let { return fromFriTeltning(it) }
            pois.firstOrNull { it.id == poi.id }?.let { return fromPoiEntity(it) }

            val norm = poi.type.lowercase()
            val facilities = buildList {
                if (poi.hasWater || norm == "water") add("Drikkevand")
                if (poi.hasToilet || norm == "toilet") add("Toilet")
                if (norm == "shop") add("Indkøb")
            }
            return PoiInfoSheetData(
                id = poi.id,
                name = poi.name,
                rawType = norm,
                typeLabel = when (poi.type.uppercase()) {
                    "SHELTER" -> "Shelter / lejrplads"
                    "FRI_TELTNING" -> "Fri teltning skov"
                    "WATER" -> "Drikkevand / vandpost"
                    "TOILET" -> "Offentligt toilet"
                    "SHOP" -> "Indkøb / butik"
                    else -> poi.type
                },
                lat = poi.lat,
                lng = poi.lng,
                bookingText = if (poi.type == "SHELTER" || poi.type == "FRI_TELTNING") {
                    if (poi.bookingRequired) "Kræver booking" else "Fri afbenyttelse"
                } else null,
                bookingRequired = poi.bookingRequired,
                facilities = facilities,
                dataSource = if (poi.type == "SHELTER" || poi.type == "FRI_TELTNING") "GeoFA / Naturstyrelsen" else "OpenStreetMap (ODbL)"
            )
        }

        fun fromNavigationCorridorPoi(
            corridorPoi: Any,
            shelters: List<ShelterEntity> = emptyList(),
            friTeltning: List<FriTeltningEntity> = emptyList(),
            pois: List<PoiEntity> = emptyList()
        ): PoiInfoSheetData? {
            return when (corridorPoi) {
                is PoiItem -> fromPoiItem(corridorPoi, shelters, friTeltning, pois)
                is ShelterEntity -> fromShelter(corridorPoi)
                is FriTeltningEntity -> fromFriTeltning(corridorPoi)
                is PoiEntity -> fromPoiEntity(corridorPoi)
                is UserCustomWaypointEntity -> fromCustomWaypoint(corridorPoi)
                is com.example.data.model.TripStage -> {
                    shelters.firstOrNull {
                        kotlin.math.abs(it.lat - corridorPoi.stopLat) < 0.0005 &&
                            kotlin.math.abs(it.lng - corridorPoi.stopLng) < 0.0005
                    }?.let { return fromShelter(it) }
                    PoiInfoSheetData(
                        id = "stage_${corridorPoi.dayNumber}",
                        name = corridorPoi.stopName.ifBlank { "Dag ${corridorPoi.dayNumber} mål" },
                        rawType = "shelter",
                        typeLabel = corridorPoi.stopType.ifBlank { "Etapemål (Dag ${corridorPoi.dayNumber})" },
                        lat = corridorPoi.stopLat,
                        lng = corridorPoi.stopLng,
                        facilities = listOf("Dag ${corridorPoi.dayNumber} overnatning"),
                        dataSource = "Planlagt etape"
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * Round 10A Item 1:
 * Shared PoiInfoSheet component used on every map (Kort, Udforsk map, trail page, full-screen map, navigation, planner).
 * Displays:
 * - Name
 * - Type
 * - Capacity and booking if known
 * - Facilities
 * - Data source and date
 * - Distance from live position (if fix < 2 min old, otherwise "ukendt")
 * Buttons:
 * - "Rute hertil"
 * - "Tilføj til rute"
 * - "Gem"
 * - "Del"
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PoiInfoSheet(
    poiData: PoiInfoSheetData,
    liveUserLocation: GeoPoint?,
    isLocationFresh: Boolean = true,
    onDismiss: () -> Unit,
    onRouteHere: (lat: Double, lng: Double, name: String) -> Unit,
    onAddToRoute: (lat: Double, lng: Double, name: String) -> Unit,
    onOvernightHereInPlanner: (() -> Unit)? = null,
    onSavedPoint: ((UserCustomWaypointEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stiColors = DanskStiTheme.colors
    var isSaved by remember(poiData.id) { mutableStateOf(false) }

    val distanceText = remember(liveUserLocation, isLocationFresh, poiData.lat, poiData.lng) {
        if (liveUserLocation == null || !isLocationFresh) {
            "ukendt"
        } else {
            val meters = GeoUtils.distanceMeters(
                liveUserLocation.latitude,
                liveUserLocation.longitude,
                poiData.lat,
                poiData.lng
            )
            if (meters < 1000.0) {
                "${meters.roundToInt()} m"
            } else {
                String.format(Locale.GERMANY, "%.1f km", meters / 1000.0)
            }
        }
    }

    val iconVector = when (poiData.rawType.lowercase()) {
        "shelter" -> Icons.Default.NightShelter
        "tent", "fri_teltning" -> Icons.Default.Forest
        "water", "drinking_water", "vand" -> Icons.Default.WaterDrop
        "toilet", "toilets" -> Icons.Default.Wc
        "shop", "supermarket", "indkoeb" -> Icons.Default.ShoppingCart
        "transit", "station", "ferry" -> Icons.Default.DirectionsBus
        else -> Icons.Default.Place
    }

    val badgeColor = when (poiData.rawType.lowercase()) {
        "shelter", "tent", "fri_teltning" -> stiColors.overnight
        "water", "drinking_water", "vand" -> stiColors.water
        "toilet", "toilets" -> MaterialTheme.colorScheme.secondary
        "shop", "supermarket", "indkoeb" -> stiColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("poi_info_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("kort_poi_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Icon, Name, Type, Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColor.copy(alpha = 0.16f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = poiData.typeLabel,
                                tint = badgeColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = poiData.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .testTag("poi_info_name")
                                .testTag("kort_poi_sheet_title")
                        )
                        Text(
                            text = poiData.typeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = badgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("poi_info_type")
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("poi_info_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Luk"
                    )
                }
            }

            // Distance from live position + Capacity & Booking
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Afstand fra min position",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("poi_info_distance")
                        )
                    }

                    if (!poiData.capacityText.isNullOrBlank() || !poiData.bookingText.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        val capBookingLine = listOfNotNull(poiData.capacityText, poiData.bookingText).joinToString(" · ")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = capBookingLine,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (poiData.bookingRequired == true) stiColors.warning else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("poi_info_capacity_booking")
                            )
                            if (!poiData.bookingUrl.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(poiData.bookingUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Ingen browser fundet til at åbne linket",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Kunne ikke åbne linket",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("poi_info_booking_link")
                                ) {
                                    Text("Åbn link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Facilities
            if (poiData.facilities.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("poi_info_facilities")
                ) {
                    Text(
                        text = "Faciliteter",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        poiData.facilities.forEach { fac ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = fac,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!poiData.description.isNullOrBlank()) {
                Text(
                    text = poiData.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Data source and date
            Text(
                text = "Kilde: ${poiData.sourceAndDateLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("poi_info_source_date")
            )

            // Optional Planner "Overnat her" button when inside planner for overnight POIs
            if (onOvernightHereInPlanner != null &&
                (poiData.rawType == "shelter" || poiData.rawType == "tent" || poiData.rawType == "fri_teltning")
            ) {
                Button(
                    onClick = {
                        onOvernightHereInPlanner()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = stiColors.overnight),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("btn_poi_overnat_her")
                ) {
                    Icon(Icons.Default.NightShelter, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Overnat her", fontWeight = FontWeight.Bold)
                }
            }

            // Primary action buttons: "Rute hertil" and "Tilføj til rute"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onRouteHere(poiData.lat, poiData.lng, poiData.name)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_route_here_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Rute hertil",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("kort_poi_route_here_button")
                    )
                }

                OutlinedButton(
                    onClick = {
                        onAddToRoute(poiData.lat, poiData.lng, poiData.name)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_add_to_route_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLocationAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tilføj til rute",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.testTag("btn_poi_tilfoej_som_stop")
                    )
                }
            }

            // Secondary action buttons: "Gem" and "Del"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val entity = UserCustomWaypointEntity(
                                lat = poiData.lat,
                                lng = poiData.lng,
                                name = poiData.name,
                                note = "${poiData.typeLabel} (${poiData.sourceAndDateLabel})"
                            )
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    DanskStiDatabase.getInstance(context).dao().insertUserCustomWaypoint(entity)
                                }
                            }
                            isSaved = true
                            onSavedPoint?.invoke(entity)
                            Toast.makeText(context, "Gemt under Mine punkter", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_save_button")
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSaved) "Gemt" else "Gem",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val osmUrl = String.format(
                            Locale.US,
                            "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=16/%.5f/%.5f",
                            poiData.lat,
                            poiData.lng,
                            poiData.lat,
                            poiData.lng
                        )
                        val shareBody = buildString {
                            appendLine("${poiData.name} (${poiData.typeLabel})")
                            poiData.capacityText?.let { appendLine("Kapacitet: $it") }
                            poiData.bookingText?.let { appendLine("Booking: $it") }
                            if (poiData.facilities.isNotEmpty()) {
                                appendLine("Faciliteter: ${poiData.facilities.joinToString(", ")}")
                            }
                            appendLine("Koordinater: ${String.format(Locale.US, "%.5f, %.5f", poiData.lat, poiData.lng)}")
                            append("Kort: $osmUrl")
                        }
                        try {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, poiData.name)
                                putExtra(Intent.EXTRA_TEXT, shareBody)
                            }
                            val chooser = Intent.createChooser(sendIntent, "Del ${poiData.name}")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                        } catch (_: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(
                                context,
                                "Ingen app fundet til at dele stedet",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Kunne ikke dele stedet",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("poi_info_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Del",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Round 10A Item 1:
 * Tapping an Adgang area shows its access rules on every map, not only in the planner.
 */
@Composable
fun AccessPolygonRulesDialog(
    polygon: AccessPolygon,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("access_polygon_rules_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(polygon.type.fillColorArgb), CircleShape)
                    .border(2.dp, Color(polygon.type.strokeColorArgb), CircleShape)
            )
        },
        title = {
            Text(
                text = polygon.name ?: polygon.type.title,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.testTag("access_polygon_rules_title")
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = "Type: ${polygon.type.title}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!polygon.operatorOrOwner.isNullOrBlank()) {
                            Text(
                                text = "Ejer: ${polygon.operatorOrOwner}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Kilde: ${polygon.source}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = polygon.type.fullRulesDanish,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("access_polygon_rules_body")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("access_polygon_rules_close")
            ) {
                Text("Luk")
            }
        }
    )
}
