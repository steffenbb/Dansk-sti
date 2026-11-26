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
 * Normalized data model for the shared [PoiInfoSheet].
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
    val infoUrl: String? = null,
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
        private fun resString(resId: Int, context: android.content.Context? = null, vararg args: Any): String {
            val baseCtx = context ?: com.example.DanskStiApp.instance
            val ctx = com.example.util.AppLocaleHelper.createLocalizedContext(baseCtx)
            return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
        }

        fun fromPoiItem(
            item: PoiItem,
            shelters: List<ShelterEntity> = emptyList(),
            friTeltning: List<FriTeltningEntity> = emptyList(),
            pois: List<PoiEntity> = emptyList(),
            context: android.content.Context? = null
        ): PoiInfoSheetData {
            val payload = item.payload
            if (payload is ShelterEntity) return fromShelter(payload, context)
            if (payload is FriTeltningEntity) return fromFriTeltning(payload, context)
            if (payload is PoiEntity) return fromPoiEntity(payload, context)
            if (payload is UserCustomWaypointEntity) return fromCustomWaypoint(payload, context)
            if (payload is PlannerMapPoi) return fromPlannerPoi(payload, shelters, friTeltning, pois, context)

            val matchedShelter = shelters.firstOrNull { it.id == item.id }
            if (matchedShelter != null) return fromShelter(matchedShelter, context)

            val matchedFri = friTeltning.firstOrNull { it.id == item.id }
            if (matchedFri != null) return fromFriTeltning(matchedFri, context)

            val matchedPoi = pois.firstOrNull { it.id == item.id }
            if (matchedPoi != null) return fromPoiEntity(matchedPoi, context)

            val norm = item.type.lowercase()
            val typeLabel = when (norm) {
                "shelter" -> resString(com.example.R.string.poi_type_shelter, context)
                "tent", "fri_teltning" -> resString(com.example.R.string.poi_type_fri_teltning_rule, context)
                "water", "drinking_water", "vand" -> resString(com.example.R.string.poi_type_water, context)
                "toilet", "toilets" -> resString(com.example.R.string.poi_type_toilet, context)
                "shop", "supermarket", "indkoeb" -> resString(com.example.R.string.poi_type_shop, context)
                "transit", "station", "ferry" -> resString(com.example.R.string.poi_type_transit, context)
                "my_point" -> resString(com.example.R.string.poi_type_saved_point, context)
                else -> item.type
            }
            val facList = buildList {
                if (item.subText.isNotBlank()) add(item.subText)
                if (norm == "water") add(resString(com.example.R.string.poi_fac_drinking_water, context))
                if (norm == "toilet") add(resString(com.example.R.string.poi_fac_toilet, context))
                if (norm == "shop") add(resString(com.example.R.string.poi_fac_shopping, context))
            }
            val isOvernight = norm == "shelter" || norm == "tent" || norm == "fri_teltning"
            val requiresBooking = isOvernight && item.subText.contains("Booking", ignoreCase = true)
            return PoiInfoSheetData(
                id = item.id,
                name = item.name,
                rawType = norm,
                typeLabel = typeLabel,
                lat = item.lat,
                lng = item.lng,
                capacityText = if (norm == "shelter") resString(com.example.R.string.poi_capacity_unknown, context) else null,
                bookingText = if (requiresBooking) {
                    resString(com.example.R.string.poi_booking_required, context)
                } else null,
                bookingRequired = if (isOvernight) requiresBooking else null,
                facilities = facList.ifEmpty {
                    if (norm == "shelter") listOf(resString(com.example.R.string.poi_fac_not_specified, context)) else emptyList()
                },
                dataSource = if (norm == "shelter" || norm == "tent" || norm == "fri_teltning") {
                    "GeoFA / Naturstyrelsen"
                } else {
                    "OpenStreetMap (ODbL)"
                }
            )
        }

        fun fromShelter(s: ShelterEntity, context: android.content.Context? = null): PoiInfoSheetData {
            val rawFacilities = buildList {
                if (s.hasWaterNearby) add(resString(com.example.R.string.poi_fac_water_nearby, context))
                if (s.hasToiletNearby) add(resString(com.example.R.string.poi_fac_toilet_nearby, context))
                if (s.hasFireRing == true) add(resString(com.example.R.string.poi_fac_fire_ring, context))
                if (s.hasFirewood == true) add(resString(com.example.R.string.poi_fac_firewood, context))
                if (s.dogAllowed == true) add(resString(com.example.R.string.poi_fac_dog_on_leash, context))
                if (s.hasElectricity == true) add(resString(com.example.R.string.poi_fac_electricity, context))
                if (s.fee == true && s.priceDkk > 0) {
                    add(resString(com.example.R.string.poi_fac_price_dkk_fmt, context, s.priceDkk))
                } else if (s.fee == true) {
                    add(resString(com.example.R.string.poi_fac_fee_required, context))
                } else if (s.fee == false) {
                    add(resString(com.example.R.string.poi_fac_free, context))
                }
                s.maxNights?.takeIf { it > 0 }?.let {
                    add(resString(com.example.R.string.poi_fac_max_nights_fmt, context, it))
                }
                s.accessibility?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val facilities = if (rawFacilities.isEmpty()) {
                listOf(resString(com.example.R.string.poi_fac_not_specified, context))
            } else {
                rawFacilities
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
                typeLabel = s.shelterType.ifBlank { resString(com.example.R.string.poi_type_shelter, context) },
                lat = s.lat,
                lng = s.lng,
                capacityText = s.capacity?.let { resString(com.example.R.string.poi_capacity_places_fmt, context, it) },
                bookingText = if (s.bookingRequired) resString(com.example.R.string.poi_booking_required, context) else null,
                bookingRequired = s.bookingRequired,
                bookingUrl = s.bookingUrl,
                infoUrl = s.infoUrl,
                facilities = facilities,
                description = s.description,
                dataSource = sourceLabel,
                fetchedAtMillis = s.fetchedAt
            )
        }

        fun fromFriTeltning(f: FriTeltningEntity, context: android.content.Context? = null): PoiInfoSheetData {
            val facilities = buildList {
                add(
                    resString(
                        com.example.R.string.poi_fac_123_rule_fmt,
                        context,
                        f.maxTents,
                        f.maxPersonsPerTent
                    )
                )
                if (f.hammockFriendly == true) add(resString(com.example.R.string.poi_fac_hammock_allowed, context))
                if (f.openFireAllowed == true) {
                    add(resString(com.example.R.string.poi_fac_open_fire_at_ring, context))
                } else {
                    add(resString(com.example.R.string.poi_fac_open_fire_designated_only, context))
                }
            }
            val sourceLabel = when (f.source.lowercase()) {
                "geofa" -> "GeoFA / Naturstyrelsen"
                else -> f.source
            }
            return PoiInfoSheetData(
                id = f.id,
                name = f.name,
                rawType = "tent",
                typeLabel = f.zoneType.ifBlank { resString(com.example.R.string.poi_type_fri_teltning, context) },
                lat = f.centerLat,
                lng = f.centerLng,
                capacityText = resString(
                    com.example.R.string.poi_capacity_tents_fmt,
                    context,
                    f.maxTents,
                    f.maxPersonsPerTent
                ),
                bookingText = resString(com.example.R.string.poi_booking_fri_teltning, context),
                bookingRequired = false,
                bookingUrl = f.rulesUrl,
                facilities = facilities,
                description = f.rulesSummary,
                dataSource = sourceLabel,
                fetchedAtMillis = f.fetchedAt
            )
        }

        fun fromPoiEntity(p: PoiEntity, context: android.content.Context? = null): PoiInfoSheetData {
            val norm = p.type.lowercase()
            val typeLabel = when (norm) {
                "water", "drinking_water", "vand" -> resString(com.example.R.string.poi_type_water, context)
                "toilet", "toilets" -> resString(com.example.R.string.poi_type_toilet, context)
                "shop", "supermarket", "convenience", "bakery", "indkoeb" -> resString(com.example.R.string.poi_type_shop, context)
                else -> resString(com.example.R.string.poi_type_transit, context)
            }
            val facilities = buildList {
                if (norm.contains("water") || norm == "vand") add(resString(com.example.R.string.poi_fac_drinking_water, context))
                if (norm.contains("toilet")) add(resString(com.example.R.string.poi_fac_toilet, context))
                if (norm.contains("shop") || norm == "supermarket" || norm == "convenience" || norm == "bakery") {
                    add(resString(com.example.R.string.poi_fac_groceries, context))
                }
                p.seasonal?.takeIf { it.isNotBlank() && !it.equals("no", ignoreCase = true) }?.let {
                    add(resString(com.example.R.string.poi_fac_seasonal, context))
                }
                p.openingHours?.takeIf { it.isNotBlank() }?.let {
                    add(resString(com.example.R.string.poi_fac_opening_hours_fmt, context, it))
                }
                p.operator?.takeIf { it.isNotBlank() }?.let {
                    add(resString(com.example.R.string.poi_fac_operator_fmt, context, it))
                }
                if (p.fee) add(resString(com.example.R.string.poi_fac_fee_required, context))
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
                bookingUrl = null,
                infoUrl = p.website,
                facilities = facilities,
                dataSource = sourceLabel,
                fetchedAtMillis = p.fetchedAt
            )
        }

        fun fromCustomWaypoint(wp: UserCustomWaypointEntity, context: android.content.Context? = null): PoiInfoSheetData {
            return PoiInfoSheetData(
                id = wp.id,
                name = wp.name,
                rawType = "my_point",
                typeLabel = resString(com.example.R.string.poi_type_saved_point_mine, context),
                lat = wp.lat,
                lng = wp.lng,
                facilities = if (wp.note.isNotBlank()) listOf(wp.note) else listOf(resString(com.example.R.string.poi_fac_user_created, context)),
                description = wp.note.takeIf { it.isNotBlank() },
                dataSource = resString(com.example.R.string.poi_source_local, context),
                fetchedAtMillis = wp.createdAt
            )
        }

        fun fromPlannerPoi(
            poi: PlannerMapPoi,
            shelters: List<ShelterEntity> = emptyList(),
            friTeltning: List<FriTeltningEntity> = emptyList(),
            pois: List<PoiEntity> = emptyList(),
            context: android.content.Context? = null
        ): PoiInfoSheetData {
            shelters.firstOrNull { it.id == poi.id }?.let { return fromShelter(it, context) }
            friTeltning.firstOrNull { it.id == poi.id }?.let { return fromFriTeltning(it, context) }
            pois.firstOrNull { it.id == poi.id }?.let { return fromPoiEntity(it, context) }

            val norm = poi.type.lowercase()
            val facilities = buildList {
                if (poi.hasWater || norm == "water") add(resString(com.example.R.string.poi_fac_drinking_water, context))
                if (poi.hasToilet || norm == "toilet") add(resString(com.example.R.string.poi_fac_toilet, context))
                if (norm == "shop") add(resString(com.example.R.string.poi_fac_shopping, context))
            }
            return PoiInfoSheetData(
                id = poi.id,
                name = poi.name,
                rawType = norm,
                typeLabel = when (poi.type.uppercase()) {
                    "SHELTER" -> resString(com.example.R.string.poi_type_shelter, context)
                    "FRI_TELTNING" -> resString(com.example.R.string.poi_type_fri_teltning, context)
                    "WATER" -> resString(com.example.R.string.poi_type_water, context)
                    "TOILET" -> resString(com.example.R.string.poi_type_toilet, context)
                    "SHOP" -> resString(com.example.R.string.poi_type_shop, context)
                    else -> poi.type
                },
                lat = poi.lat,
                lng = poi.lng,
                bookingText = if ((poi.type == "SHELTER" || poi.type == "FRI_TELTNING") && poi.bookingRequired) {
                    resString(com.example.R.string.poi_booking_required, context)
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
            pois: List<PoiEntity> = emptyList(),
            context: android.content.Context? = null
        ): PoiInfoSheetData? {
            return when (corridorPoi) {
                is PoiItem -> fromPoiItem(corridorPoi, shelters, friTeltning, pois, context)
                is ShelterEntity -> fromShelter(corridorPoi, context)
                is FriTeltningEntity -> fromFriTeltning(corridorPoi, context)
                is PoiEntity -> fromPoiEntity(corridorPoi, context)
                is UserCustomWaypointEntity -> fromCustomWaypoint(corridorPoi, context)
                is com.example.data.model.TripStage -> {
                    shelters.firstOrNull {
                        kotlin.math.abs(it.lat - corridorPoi.stopLat) < 0.0005 &&
                            kotlin.math.abs(it.lng - corridorPoi.stopLng) < 0.0005
                    }?.let { return fromShelter(it, context) }
                    PoiInfoSheetData(
                        id = "stage_${corridorPoi.dayNumber}",
                        name = corridorPoi.stopName.ifBlank {
                            resString(com.example.R.string.poi_stage_goal_fmt, context, corridorPoi.dayNumber)
                        },
                        rawType = "shelter",
                        typeLabel = corridorPoi.stopType.ifBlank {
                            resString(com.example.R.string.poi_stage_goal_type_fmt, context, corridorPoi.dayNumber)
                        },
                        lat = corridorPoi.stopLat,
                        lng = corridorPoi.stopLng,
                        facilities = listOf(
                            resString(com.example.R.string.poi_stage_overnight_fmt, context, corridorPoi.dayNumber)
                        ),
                        dataSource = resString(com.example.R.string.poi_source_planned_stage, context)
                    )
                }
                else -> null
            }
        }
    }
}
