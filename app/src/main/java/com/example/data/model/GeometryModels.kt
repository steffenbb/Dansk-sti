package com.example.data.model

import com.squareup.moshi.JsonClass

enum class TrailSegmentType {
    WALK,
    FERRY
}

@JsonClass(generateAdapter = true)
data class TrailSegmentDto(
    val type: TrailSegmentType,
    val points: List<GeoPointDto> = emptyList(),
    val lengthKm: Double = 0.0,
    val highway: String? = null,
    val surface: String? = null
)

@JsonClass(generateAdapter = true)
data class TrailGapDto(
    val fromPoint: GeoPointDto,
    val toPoint: GeoPointDto,
    val gapMeters: Double
)

@JsonClass(generateAdapter = true)
data class TrailGeometryDto(
    val trailId: String,
    val osmRelationId: Long,
    val segments: List<TrailSegmentDto>,
    val sideBranches: List<TrailSegmentDto> = emptyList(),
    val gaps: List<TrailGapDto> = emptyList(),
    val totalWalkingKm: Double,
    val totalAscentM: Int? = null,
    val totalDescentM: Int? = null,
    val gapsOver200mCount: Int,
    val bboxNorth: Double,
    val bboxSouth: Double,
    val bboxEast: Double,
    val bboxWest: Double,
    val osmTimestamp: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)
