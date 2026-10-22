package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeoPointDto(
    val lat: Double,
    val lng: Double,
    val ele: Double? = null
)

enum class OvernightType {
    SHELTER,
    FRI_TELTNING,
    BOTH,
    CAMPSITE;

    companion object {
        val TENT = FRI_TELTNING
        val MIXED = BOTH
    }
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
    var stopType: String, // "SHELTER", "FRI_TELTNING", "CAMPSITE", "DESTINATION", "NONE"
    var stopLat: Double,
    var stopLng: Double,
    var bookingRequired: Boolean = false,
    var hasWaterNearby: Boolean = false,
    var hasToiletNearby: Boolean = false,
    val startTrailKm: Double = 0.0,
    val endTrailKm: Double = 0.0,
    var fromName: String = "",
    var toName: String = "",
    var detourMeters: Double = 0.0,
    var ascentMeters: Double = 0.0,
    var descentMeters: Double = 0.0,
    var estimatedWalkMinutes: Int = 0,
    var waterCount: Int = 0,
    var shopCount: Int = 0,
    var sunsetTime: String = "",
    var isAfterSunset: Boolean = false,
    var spotId: String? = null,
    var isWarningNoOvernight: Boolean = false,
    var warningMessage: String? = null
) {
    val startWalkKm: Double get() = startKm
    val endWalkKm: Double get() = endKm

    fun toEntity(planId: Long): TripStageEntity {
        return TripStageEntity(
            planId = planId,
            dayNumber = dayNumber,
            startKm = startKm,
            endKm = endKm,
            stageKm = stageKm,
            stopName = stopName,
            stopType = stopType,
            stopLat = stopLat,
            stopLng = stopLng,
            bookingRequired = bookingRequired,
            hasWaterNearby = hasWaterNearby,
            hasToiletNearby = hasToiletNearby,
            startTrailKm = startTrailKm,
            endTrailKm = endTrailKm,
            fromName = fromName,
            toName = toName,
            detourMeters = detourMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            estimatedWalkMinutes = estimatedWalkMinutes,
            waterCount = waterCount,
            shopCount = shopCount,
            sunsetTime = sunsetTime,
            isAfterSunset = isAfterSunset,
            spotId = spotId,
            isWarningNoOvernight = isWarningNoOvernight,
            warningMessage = warningMessage
        )
    }
}

@Entity(
    tableName = "trip_stages",
    primaryKeys = ["planId", "dayNumber"]
)
data class TripStageEntity(
    val planId: Long,
    val dayNumber: Int,
    val startKm: Double,
    val endKm: Double,
    val stageKm: Double,
    val stopName: String,
    val stopType: String,
    val stopLat: Double,
    val stopLng: Double,
    val bookingRequired: Boolean = false,
    val hasWaterNearby: Boolean = false,
    val hasToiletNearby: Boolean = false,
    val startTrailKm: Double = 0.0,
    val endTrailKm: Double = 0.0,
    val fromName: String = "",
    val toName: String = "",
    val detourMeters: Double = 0.0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val estimatedWalkMinutes: Int = 0,
    val waterCount: Int = 0,
    val shopCount: Int = 0,
    val sunsetTime: String = "",
    val isAfterSunset: Boolean = false,
    val spotId: String? = null,
    val isWarningNoOvernight: Boolean = false,
    val warningMessage: String? = null
) {
    fun toTripStage(): TripStage {
        return TripStage(
            dayNumber = dayNumber,
            startKm = startKm,
            endKm = endKm,
            stageKm = stageKm,
            stopName = stopName,
            stopType = stopType,
            stopLat = stopLat,
            stopLng = stopLng,
            bookingRequired = bookingRequired,
            hasWaterNearby = hasWaterNearby,
            hasToiletNearby = hasToiletNearby,
            startTrailKm = startTrailKm,
            endTrailKm = endTrailKm,
            fromName = fromName,
            toName = toName,
            detourMeters = detourMeters,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
            estimatedWalkMinutes = estimatedWalkMinutes,
            waterCount = waterCount,
            shopCount = shopCount,
            sunsetTime = sunsetTime,
            isAfterSunset = isAfterSunset,
            spotId = spotId,
            isWarningNoOvernight = isWarningNoOvernight,
            warningMessage = warningMessage
        )
    }
}

@Entity(tableName = "trails")
data class TrailEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val region: String,
    val parentRegion: String,
    val osmRelationId: Long? = null,
    val computedWalkingKm: Double = 0.0,
    val gapsOver200m: Int = 0,
    val osmTimestamp: String? = null,
    val bboxNorth: Double? = null,
    val bboxSouth: Double? = null,
    val bboxEast: Double? = null,
    val bboxWest: Double? = null,
    val isGeometryDownloaded: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val lengthKm: Double
        get() = computedWalkingKm
}

@Entity(tableName = "shelters")
data class ShelterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val description: String? = null,
    val bookingRequired: Boolean = false,
    val ownership: String? = null, // "Naturstyrelsen", "Kommune", "Offentlig stiftelse", "Den Danske Naturfond"
    val capacity: Int? = null,
    val hasWaterNearby: Boolean = false,
    val hasToiletNearby: Boolean = false,
    val source: String = "geofa", // "geofa", "osm"
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
    val dogAllowed: Boolean = true,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "fri_teltning")
data class FriTeltningEntity(
    @PrimaryKey val id: String,
    val name: String,
    val centerLat: Double,
    val centerLng: Double,
    val polygonJson: String, // JSON list of GeoPointDto representing boundary
    val rulesSummary: String? = null,
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
    val hammockFriendly: Boolean = true,
    val source: String = "geofa",
    val cvrNavn: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pois")
data class PoiEntity(
    @PrimaryKey val id: String,
    val type: String, // "toilet", "water", "shop", "station", "ferry"
    val name: String,
    val lat: Double,
    val lng: Double,
    val access: String? = null,
    val fee: Boolean = false,
    val trailCorridorId: String? = null,
    val openingHours: String? = null,
    val operator: String? = null,
    val website: String? = null,
    val source: String = "geofa",
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "trail_pois",
    primaryKeys = ["trailId", "poiId"],
    indices = [Index(value = ["routeId"])]
)
data class TrailPoiEntity(
    val trailId: String,
    val poiId: String,
    val kmOnRoute: Double,
    val detourMeters: Double,
    val routeId: String? = null
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
    val createdAt: Long = System.currentTimeMillis(),
    val paceKmh: Double = 4.0,
    val maxDetourKm: Double = 1.5,
    val startTime: String = "09:00",
    val customRouteId: Long? = null
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

// --- Custom Waypoint / Trail Hazards (Enhancement 7) ---
enum class HazardType {
    FLOODED_BOARDWALK, // 🌊 Oversvømmet sti/spang
    FALLEN_TREE,       // 🌲 Væltet træ over stien
    CATTLE_ENCLOSURE,  // 🐂 Nærgående kvæg i indhegning
    TRAIL_DETOUR       // 🚧 Midlertidig omlægning
}

@Entity(tableName = "user_hazard_notes")
data class UserHazardNoteEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val trailId: String,
    val lat: Double,
    val lng: Double,
    val type: String,
    val title: String,
    val description: String,
    val createdAt: Long = System.currentTimeMillis()
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

// --- Danish 112 SOS (Enhancement 14) ---
data class DanishEmergencyInfo(
    val decimalCoords: String,
    val spokenWgs84: String        // e.g. "55 grader 03 minutter 42 sekunder Nord, 10 grader 36 minutter 18 sekunder Øst"
)

@Entity(tableName = "downloaded_trails")
data class DownloadedTrailEntity(
    @PrimaryKey val trailId: String,
    val trailName: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val tileCount: Int = 0,
    val corridorKm: Double = 1.0,
    val minZoom: Int = 8,
    val maxZoom: Int = 16
) {
    val estimatedSizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)
}

@Entity(tableName = "saved_trails")
data class SavedTrailEntity(
    @PrimaryKey val trailId: String,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trip_gear_items")
data class TripGearItemEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val planId: Long,
    val category: String, // "Ly & Søvn", "Vand & Mad", "Sikkerhed & Førstehjælp", "Navigation & Energi", "Beklædning"
    val title: String,
    val isPacked: Boolean = false
)

// --- Phase 7a: Recorded GPS Track Points (Accuracy-filtered) ---
@Entity(
    tableName = "recorded_track_points",
    indices = [Index(value = ["planId", "dayNumber", "timestamp"])]
)
data class RecordedTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val planId: Long,
    val dayNumber: Int,
    val lat: Double,
    val lng: Double,
    val altitude: Double? = null,
    val accuracy: Float,
    val speedKmh: Double,
    val timestamp: Long
)

// --- Phase 7a: Daily summary of recorded track ---
@Entity(
    tableName = "recorded_day_stats",
    primaryKeys = ["planId", "dayNumber"]
)
data class RecordedDayStatEntity(
    val planId: Long,
    val dayNumber: Int,
    val distanceKm: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val ascentMeters: Int = 0,
    val pointCount: Int = 0,
    val lastTimestamp: Long = 0L
) {
    val lastUpdatedMillis: Long
        get() = lastTimestamp
}

// --- Phase 7b: Overnight spots near me (< 10 km) ---
data class NearbyOvernightSpot(
    val id: String,
    val name: String,
    val type: String, // "SHELTER" or "FRI_TELTNING"
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val bookingRequired: Boolean = false,
    val bookingUrl: String? = null,
    val hasWaterNearby: Boolean = false,
    val hasToiletNearby: Boolean = false,
    val description: String? = null
) {
    val distanceKm: Double
        get() = distanceMeters / 1000.0
}


