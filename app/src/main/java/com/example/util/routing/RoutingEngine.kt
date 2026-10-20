package com.example.util.routing

/**
 * Coordinate model for routing endpoints and waypoints.
 */
data class LatLng(
    val lat: Double,
    val lng: Double
)

/**
 * Circular no-go area defining an obstacle or area to avoid.
 */
data class NoGoPoint(
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val weight: Double? = null
)

/**
 * Options influencing route generation and preference weights.
 *
 * @param avoidRoads 0.0 (no penalty for motor roads) to 1.0 (strongly penalizes roads and paved routes).
 * @param preferNature prefers forested, quiet paths and penalizes noise/traffic.
 * @param allowFerries whether water transit / ferry connections are permitted.
 * @param noGoAreas list of polygonal obstacle boundaries to strictly bypass.
 * @param noGoPoints list of circular obstacle centroids and radii to bypass.
 */
data class RouteOptions(
    val avoidRoads: Double = 0.5,
    val preferNature: Boolean = true,
    val allowFerries: Boolean = false,
    val noGoAreas: List<List<LatLng>> = emptyList(),
    val noGoPoints: List<NoGoPoint> = emptyList()
)

/**
 * 3D geographic point containing latitude, longitude, and optional elevation.
 */
data class RoutePoint3D(
    val lat: Double,
    val lng: Double,
    val ele: Double? = null
)

/**
 * OpenStreetMap tag metadata along a route section or waypoint.
 */
data class WayInfo(
    val highway: String? = null,
    val surface: String? = null,
    val tracktype: String? = null,
    val access: String? = null,
    val forestClass: String? = null,
    val rawTags: Map<String, String> = emptyMap()
)

/**
 * Typed routing result distinguishing success from specific operational errors.
 * Never returns a fake straight line on failure.
 */
sealed interface RouteLegResult {
    data class Success(
        val points: List<RoutePoint3D>,
        val lengthKm: Double,
        val ascentM: Double,
        val descentM: Double,
        val perPointWayInfo: List<WayInfo>,
        val sourceName: String,
        val fetchedAt: Long = System.currentTimeMillis()
    ) : RouteLegResult

    data class NoRoute(
        val message: String = "Ingen rute fundet"
    ) : RouteLegResult

    data class Offline(
        val message: String = "Ingen internetforbindelse"
    ) : RouteLegResult

    data class ServerBusy(
        val message: String = "Ruteserveren er optaget - prøv igen"
    ) : RouteLegResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : RouteLegResult
}

/**
 * Routing engine interface for computing point-to-point hiking route legs.
 */
interface RoutingEngine {
    suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int = 0
    ): RouteLegResult
}
