package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeoPointDto(
    val lat: Double,
    val lng: Double
)

enum class OvernightType {
    TENT,
    SHELTER,
    MIXED
}

enum class PoiType {
    SHELTER,
    FRI_TELTNING,
    TOILET,
    WATER
}

@JsonClass(generateAdapter = true)
data class TripStage(
    val dayNumber: Int,
    val startKm: Double,
    val endKm: Double,
    val stageKm: Double,
    var stopName: String,
    var stopType: String, // "SHELTER", "FRI_TELTNING", "CUSTOM"
    var stopLat: Double,
    var stopLng: Double,
    var bookingRequired: Boolean = false,
    var hasWaterNearby: Boolean = false,
    var hasToiletNearby: Boolean = false
)

@Entity(tableName = "trails")
data class TrailEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val lengthKm: Double,
    val region: String,
    val elevationGainM: Int,
    val pointsJson: String, // JSON list of GeoPointDto
    val bboxNorth: Double,
    val bboxSouth: Double,
    val bboxEast: Double,
    val bboxWest: Double,
    val osmRelationId: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "shelters")
data class ShelterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val description: String,
    val bookingRequired: Boolean,
    val ownership: String, // "Naturstyrelsen", "Kommune", "Offentlig stiftelse", "Den Danske Naturfond"
    val capacity: Int,
    val hasWaterNearby: Boolean,
    val hasToiletNearby: Boolean,
    val source: String, // "udinaturen", "bookenshelter", "osm"
    val trailCorridorId: String? = null,
    val bookingUrl: String? = null,
    val hasFireRing: Boolean = true,
    val hasFirewood: Boolean = false,
    val fee: Boolean = false,
    val priceDkk: Int = 0,
    val maxNights: Int = 2,
    val accessibility: String = "Til fods / cykel",
    val shelterType: String = "Klassisk træshelter",
    val hasElectricity: Boolean = false,
    val dogAllowed: Boolean = true
)

@Entity(tableName = "fri_teltning")
data class FriTeltningEntity(
    @PrimaryKey val id: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val polygonJson: String, // JSON list of GeoPointDto representing boundary
    val rulesSummary: String,
    val rulesUrl: String = "https://naturstyrelsen.dk/aktiviteter-i-naturen/overnatning-i-naturen/fri-teltning/",
    val areaHa: Double = 0.0,
    val trailCorridorId: String? = null,
    val forestType: String = "Løvskov",
    val maxTents: Int = 2,
    val maxPersonsPerTent: Int = 3,
    val openFireAllowed: Boolean = false,
    val waterDistanceMeters: Int = 500,
    val toiletDistanceMeters: Int = 800,
    val zoneType: String = "Fri teltningsskov (1-2-3 reglen)",
    val hammockFriendly: Boolean = true
)

@Entity(tableName = "pois")
data class PoiEntity(
    @PrimaryKey val id: String,
    val type: String, // "toilet", "water"
    val name: String,
    val lat: Double,
    val lng: Double,
    val access: String,
    val fee: Boolean = false,
    val trailCorridorId: String? = null
)

@Entity(tableName = "trip_plans")
data class TripPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trailId: String,
    val trailName: String,
    val startKm: Double,
    val endKm: Double,
    val isReverse: Boolean,
    val totalDistanceKm: Double,
    val targetDays: Int,
    val overnightType: String, // TENT, SHELTER, MIXED
    val preferredDailyKm: Double,
    val stagesJson: String, // JSON list of TripStage
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trip_progress")
data class TripProgressEntity(
    @PrimaryKey val planId: Long,
    val currentDay: Int,
    val distanceWalkedTodayKm: Double,
    val totalDistanceWalkedKm: Double,
    val currentSnappedLat: Double,
    val currentSnappedLng: Double,
    val remainingDistanceTripKm: Double,
    val remainingDistanceTodayKm: Double,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)

// --- Trail Surface & Footwear Advisor (New Feature 1) ---
data class TrailSurfaceStats(
    val forestSoilPct: Int,    // Skovsti (jord/rødder)
    val gravelPct: Int,        // Grusvej
    val asphaltPct: Int,       // Asfalt / cykelsti
    val sandBeachPct: Int,     // Strand / sand / klit
    val recommendedFootwear: String, // e.g. "Trail runners anbefales" / "Vandrestøvler med stiv sål"
    val blisterRisk: String,   // "Lav", "Mellem", "Høj (pga. 28% asfalt)"
    val footwearTip: String
)

// --- Resupply & Provisioning Radar (New Feature 4) ---
enum class ResupplyType {
    GROCERY,      // Supermarked (Netto, Brugsen, Spar, Coop365)
    FARM_STALL,   // Vejbod med frugt, grønt, æg, MobilePay
    BAKERY_CAFE   // Bageri / Café / Kiosk
}

data class ResupplyPoint(
    val id: String,
    val trailId: String,
    val name: String,
    val type: ResupplyType,
    val lat: Double,
    val lng: Double,
    val distanceKmFromStart: Double,
    val openingHours: String,
    val hasMobilePay: Boolean = true,
    val description: String
)

// --- Public Transit Hub Connectors (Enhancement 13) ---
data class TransitStation(
    val id: String,
    val trailId: String,
    val name: String,
    val type: String, // "DSB Tog", "Regionalbus"
    val lat: Double,
    val lng: Double,
    val kmAlongTrail: Double,
    val lines: String
)

// --- Custom Waypoint / Trail Hazards (Enhancement 7) ---
enum class HazardType {
    FLOODED_BOARDWALK, // 🌊 Oversvømmet sti/spang
    FALLEN_TREE,       // 🌲 Væltet træ over stien
    CATTLE_ENCLOSURE,  // 🐂 Nærgående kvæg i indhegning
    TRAIL_DETOUR       // 🚧 Midlertidig omlægning
}

data class UserHazardNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val trailId: String,
    val lat: Double,
    val lng: Double,
    val type: HazardType,
    val title: String,
    val description: String,
    val reportedAt: Long = System.currentTimeMillis()
)

// --- Trekking Gear Checklist (Enhancement 15) ---
data class GearChecklistItem(
    val id: String,
    val category: String, // "Ly & Søvn", "Vand & Mad", "Sikkerhed & Førstehjælp", "Navigation & Energi"
    val title: String,
    val subtitle: String,
    val isMandatory: Boolean,
    var isChecked: Boolean = false
)

// --- Danish 112 SOS & Red Rescue Post (Enhancement 14) ---
data class DanishEmergencyInfo(
    val decimalCoords: String,
    val spokenWgs84: String,       // e.g. "55 grader 03 minutter 42 sekunder Nord, 10 grader 36 minutter 18 sekunder Øst"
    val nearestRedPostCode: String,// e.g. "K104"
    val nearestRedPostLocation: String, // e.g. "Svanninge Bjerge Vest, stiudmunding"
    val distanceToPostMeters: Int
)

