package com.example.ui.components

import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.navigation.PoiItem
import com.example.ui.viewmodel.PlannerMapPoi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            if (payload is PlannerMapPoi) return fromPlannerPoi(payload, shelters, friTeltning, pois)

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
