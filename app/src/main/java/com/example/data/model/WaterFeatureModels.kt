package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.squareup.moshi.JsonClass
import org.osmdroid.util.GeoPoint

/**
 * Classification of water features for Requirement D ("Vand" overlay):
 * - LAKE: natural=water, water=* (drawn as fill with 45% blue)
 * - WETLAND: natural=wetland (drawn as hatched/25% blue fill)
 * - RIVER: waterway=river|canal (drawn as 3 dp blue line)
 * - STREAM: waterway=stream|ditch|drain (drawn as 1.5 dp blue line)
 * - SPRING: natural=spring (drawn as small blue dot)
 */
enum class WaterFeatureType {
    LAKE,
    WETLAND,
    RIVER,
    STREAM,
    SPRING
}

@Entity(
    tableName = "water_features",
    indices = [
        Index(value = ["minLat", "maxLat", "minLng", "maxLng"], name = "index_water_features_bbox"),
        Index(value = ["tileId"], name = "index_water_features_tileId"),
        Index(value = ["featureType"], name = "index_water_features_type")
    ]
)
data class WaterFeatureEntity(
    @PrimaryKey val id: String,
    val osmId: Long,
    val name: String?,
    val featureType: String, // WaterFeatureType.name
    val subType: String?,
    val coordinatesJson: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val tileId: String,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "water_tile_cache")
data class WaterTileCacheEntity(
    @PrimaryKey val tileId: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val fetchedAt: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class WaterFeatureDto(
    val id: String,
    val osmId: Long,
    val name: String?,
    val featureType: WaterFeatureType,
    val subType: String?,
    val points: List<GeoPointDto>,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
) {
    fun toGeoPoints(): List<GeoPoint> = points.map { GeoPoint(it.lat, it.lng) }
}
