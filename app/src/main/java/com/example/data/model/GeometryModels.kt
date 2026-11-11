package com.example.data.model

import com.squareup.moshi.JsonClass

enum class TrailSegmentType {
    WALK,
    FERRY
}

@JsonClass(generateAdapter = true)
data class WayTagRangeDto(
    val fromKm: Double,
    val toKm: Double,
    val highway: String? = null,
    val surface: String? = null
)

typealias WayTagRange = WayTagRangeDto
typealias TrailWayTagRange = WayTagRangeDto

@JsonClass(generateAdapter = true)
data class TrailSegmentDto(
    val type: TrailSegmentType,
    val points: List<GeoPointDto> = emptyList(),
    val lengthKm: Double = 0.0,
    val highway: String? = null,
    val surface: String? = null,
    val wayTagRanges: List<WayTagRangeDto> = emptyList()
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
    val fetchedAt: Long = System.currentTimeMillis(),
    val osmRelationName: String? = null,
    val osmTags: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class CatalogueRelationMetaDto(
    val relationId: Long,
    val bboxSouth: Double? = null,
    val bboxWest: Double? = null,
    val bboxNorth: Double? = null,
    val bboxEast: Double? = null,
    val tags: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class CatalogueRelationsMetaCacheDto(
    val fetchedAtEpochMs: Long,
    val relations: Map<String, CatalogueRelationMetaDto> = emptyMap()
)

data class TrailDownloadProgress(
    val trailId: String,
    val fraction: Float,
    val downloadedRelations: Int = 0,
    val totalRelations: Int = 1,
    val accumulatedKm: Double = 0.0,
    val stageMessage: String = "Henter rute fra OpenStreetMap…",
    val isIndeterminate: Boolean = false,
    val queuePosition: Int = 0
) {
    val message: String get() = stageMessage
}

@JsonClass(generateAdapter = true)
data class TrailNameMismatchEntry(
    val trailId: String,
    val osmRelationId: Long,
    val catalogueName: String,
    val osmRelationName: String,
    val detectedAtEpochMs: Long = System.currentTimeMillis()
)
