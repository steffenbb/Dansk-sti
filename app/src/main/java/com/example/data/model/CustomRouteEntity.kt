package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a user-created route from Rutebygger (Phase 9).
 *
 * Each leg's geometry is stored separately in the file referenced by [geometryFile],
 * allowing one leg to be updated without rewriting the others.
 */
@Entity(
    tableName = "custom_routes",
    indices = [Index(value = ["updatedAt"])]
)
data class CustomRouteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val optionsJson: String,
    val waypointsJson: String,
    val totalKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val pathSharePct: Double,
    val roadKm: Double,
    val geometryFile: String,
    val sourceName: String,
    val fetchedAt: Long,
    val baseTrailId: String? = null,
    val isReverse: Boolean = false
) {
    val lengthKm: Double
        get() = totalKm
}
