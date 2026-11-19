package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass

/**
 * Room entity for caching hiking and foot routes fetched from OpenStreetMap Overpass.
 * Cached locally for 7 days per geographic area to avoid unnecessary network queries.
 */
@Entity(
    tableName = "marked_routes",
    indices = [
        Index(value = ["minLat", "maxLat", "minLng", "maxLng"], name = "index_marked_routes_bbox"),
        Index(value = ["routeType"], name = "index_marked_routes_routeType")
    ]
)
data class MarkedRouteEntity(
    @PrimaryKey val osmRelationId: Long,
    val name: String,
    val lengthKm: Double,
    val operator: String?,
    val routeType: String, // "hiking", "foot"
    val network: String?, // "iwn", "nwn", "rwn", "lwn"
    val symbol: String?,
    val colorHex: String,
    val segmentsJson: String, // JSON serialized List<List<GeoPointDto>>
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity tracking geographic bounding boxes queried for marked routes.
 * Used to enforce the 7-day cache requirement (168 hours).
 */
@Entity(tableName = "marked_route_cache_boxes")
data class MarkedRouteCacheBoxEntity(
    @PrimaryKey val boxKey: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * Lightweight model representation of an OSM marked hiking/foot route for Compose and osmdroid.
 */
@JsonClass(generateAdapter = true)
data class MarkedRouteDto(
    val osmRelationId: Long,
    val name: String,
    val lengthKm: Double,
    val operator: String?,
    val routeType: String,
    val network: String?,
    val symbol: String?,
    val colorHex: String,
    val polylines: List<List<GeoPointDto>>,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
) {
    val displayOperator: String
        get() = operator ?: "Ikke angivet"

    val networkDescription: String
        get() = when (network?.lowercase()) {
            "iwn" -> "International vandrerute"
            "nwn" -> "National vandrerute"
            "rwn" -> "Regional vandrerute"
            "lwn" -> "Lokal vandrerute"
            else -> "Afmærket rute"
        }
}

/**
 * Route discovered from Overpass query:
 * relation["route"="hiking"]["network"~"^(iwn|nwn|rwn)$"](area Denmark); out tags;
 */
@JsonClass(generateAdapter = true)
data class DiscoveredHikingRouteDto(
    val osmRelationId: Long,
    val name: String,
    val ref: String? = null,
    val network: String = "rwn",
    val operator: String? = null,
    val distanceTagKm: Double? = null
) {
    val networkLabel: String
        get() = when (network.lowercase()) {
            "iwn" -> "International vandrerute (IWN)"
            "nwn" -> "National vandrerute (NWN)"
            "rwn" -> "Regional vandrerute (RWN)"
            else -> "Afmærket vandrerute"
        }
}

/**
 * Lightweight projection for marked_routes table queries without loading segmentsJson.
 */
data class MarkedRouteHeaderTuple(
    val osmRelationId: Long,
    val name: String,
    val lengthKm: Double,
    val operator: String?,
    val routeType: String,
    val network: String?,
    val symbol: String?,
    val colorHex: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val cachedAt: Long,
    val segmentsByteLength: Int
)

/**
 * Projection for loading segmentsJson in batches of max 50 IDs on cache miss.
 */
data class MarkedRouteSegmentsTuple(
    val osmRelationId: Long,
    val segmentsJson: String,
    val cachedAt: Long
)

/**
 * Immutable render snapshot item handed to MarkedRoutesBatchOverlay on the main thread.
 * Coordinates in lod0, lod1, and lod2 are packed DoubleArrays: [lat0, lng0, lat1, lng1, ...].
 */
data class MarkedRouteRenderItem(
    val osmRelationId: Long,
    val name: String,
    val lengthKm: Double,
    val operator: String?,
    val routeType: String,
    val network: String?,
    val symbol: String?,
    val colorHex: String,
    val colorInt: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val cachedAt: Long,
    val lod0: List<DoubleArray>?,
    val lod1: List<DoubleArray>,
    val lod2: List<DoubleArray>
)

