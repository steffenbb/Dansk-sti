package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OverpassResponse(
    val version: Double? = null,
    val generator: String? = null,
    val elements: List<OverpassElement> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OverpassElement(
    val type: String, // "relation", "way", "node"
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null,
    val members: List<OverpassMember>? = null,
    val geometry: List<OverpassPoint>? = null,
    val nodes: List<Long>? = null
)

@JsonClass(generateAdapter = true)
data class OverpassMember(
    val type: String,
    val ref: Long,
    val role: String? = null,
    val geometry: List<OverpassPoint>? = null
)

@JsonClass(generateAdapter = true)
data class OverpassPoint(
    val lat: Double,
    val lon: Double
)
