package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.util.routing.LegGeometryData
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteWaypoint
import java.util.Locale

/**
 * Phase 12 Feature 1: Vand og mad gap warnings along the route.
 */
enum class SupplyGapType {
    WATER,
    SHOP
}

data class SupplyGapWarning(
    val type: SupplyGapType,
    val startKm: Double,
    val endKm: Double,
    val gapKm: Double,
    val nearestDetourName: String? = null,
    val detourExtraMeters: Double? = null,
    val detourLat: Double? = null,
    val detourLng: Double? = null
) {
    val displayTitle: String get() {
        val label = if (type == SupplyGapType.WATER) "vand" else "mad/butik"
        return "Intet $label i ${String.format(Locale.US, "%.0f", gapKm)} km (km ${String.format(Locale.US, "%.0f", startKm)}-${String.format(Locale.US, "%.0f", endKm)})"
    }

    val detourOptionText: String? get() {
        if (!nearestDetourName.isNullOrBlank() && detourExtraMeters != null) {
            return "Nærmeste omvej: $nearestDetourName (+${detourExtraMeters.toInt()} m)"
        }
        return null
    }
}

/**
 * Phase 12 Feature 2: Rundtur generated candidate loop.
 */
data class RundturCandidate(
    val id: Int,
    val title: String,
    val directionLabel: String,
    val targetKm: Double,
    val actualKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val pathSharePct: Double,
    val overnightCount: Int,
    val turnPoint: RoutePoint3D,
    val waypoints: List<RouteWaypoint>,
    val legs: Map<String, LegGeometryData>
) {
    val deviationPct: Double
        get() = if (targetKm > 0.0) Math.abs(actualKm - targetKm) / targetKm * 100.0 else 0.0
}

/**
 * Phase 12 Feature 3: Weather and sunset ETA information for waypoints.
 */
data class WaypointWeatherEta(
    val waypointId: String,
    val waypointName: String,
    val isOvernight: Boolean,
    val isEnd: Boolean,
    val kmFromStart: Double,
    val etaFormatted: String,
    val etaMinutesFromStart: Int,
    val tempC: Double? = null,
    val precipMm: Double? = null,
    val windMs: Double? = null,
    val weatherIcon: String? = null,
    val weatherDescription: String? = null,
    val sunsetTime: String? = null,
    val isAfterSunset: Boolean = false,
    val warningMessage: String? = null
)

/**
 * Phase 12 Feature 4: Rute til nærmeste categories.
 */
enum class NearestPoiCategory(val label: String, val icon: String) {
    SHELTER("Shelter", "⛺"),
    WATER("Vandpost", "💧"),
    TOILET("Toilet", "🚻"),
    SHOP("Butik", "🛒")
}

/**
 * Phase 12 Feature 5: Rutekvalitet breakdown and road section warnings.
 */
data class RouteQualityBreakdown(
    val totalKm: Double = 0.0,
    val trailKm: Double = 0.0,
    val trailPct: Double = 0.0,
    val trackKm: Double = 0.0,
    val trackPct: Double = 0.0,
    val roadNoSidewalkKm: Double = 0.0,
    val roadNoSidewalkPct: Double = 0.0,
    val roadWithSidewalkKm: Double = 0.0,
    val roadWithSidewalkPct: Double = 0.0,
    val asphaltKm: Double = 0.0,
    val asphaltPct: Double = 0.0,
    val ferryKm: Double = 0.0,
    val ferryPct: Double = 0.0,
    val roadWithoutFootwayWarnings: List<RoadSectionWarning> = emptyList()
)

data class RoadSectionWarning(
    val startKm: Double,
    val endKm: Double,
    val lengthKm: Double,
    val roadName: String,
    val startLat: Double,
    val startLng: Double
) {
    val displayText: String get() =
        "Vej uden fortov: ${String.format(Locale.US, "%.1f", lengthKm)} km (km ${String.format(Locale.US, "%.1f", startKm)}-${String.format(Locale.US, "%.1f", endKm)}) · $roadName"
}

/**
 * Phase 12 Feature 6: Mine punkter custom user waypoint with photo, name, note.
 */
@Entity(
    tableName = "user_custom_waypoints",
    indices = [Index(value = ["routeId"])]
)
data class UserCustomWaypointEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val routeId: Long? = null,
    val lat: Double,
    val lng: Double,
    val name: String,
    val note: String = "",
    val photoPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
