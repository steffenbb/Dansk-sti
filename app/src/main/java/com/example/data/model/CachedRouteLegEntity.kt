package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached route leg calculated by a routing engine, stored for up to 30 days.
 */
@Entity(
    tableName = "cached_route_legs",
    indices = [
        Index(value = ["expiresAt"])
    ]
)
data class CachedRouteLegEntity(
    @PrimaryKey val cacheKey: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val avoidRoads: Double,
    val preferNature: Boolean,
    val allowFerries: Boolean,
    val alternative: Int,
    val lengthKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val pointsJson: String,
    val wayInfoJson: String,
    val sourceName: String,
    val cachedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L
)
