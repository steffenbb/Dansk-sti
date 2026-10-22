package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for caching access polygons per 0.1 x 0.1 degree tile (`tileId`)
 * with spatial bounding box coordinates.
 */
@Entity(
    tableName = "access_polygons",
    indices = [
        Index(value = ["minLat", "maxLat", "minLng", "maxLng"]),
        Index(value = ["type"]),
        Index(value = ["tileId"])
    ]
)
data class AccessPolygonEntity(
    @PrimaryKey
    val id: String,
    val name: String?,
    val type: String, // AccessLandType enum name
    val coordinatesJson: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val source: String,
    val operatorOrOwner: String?,
    val fetchedAt: Long,
    @ColumnInfo(defaultValue = "")
    val tileId: String = ""
)
