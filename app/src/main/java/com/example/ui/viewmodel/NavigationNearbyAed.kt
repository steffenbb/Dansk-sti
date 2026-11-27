package com.example.ui.viewmodel

import com.example.data.model.PoiEntity
import com.example.util.GeoUtils

/** Phase C8: a defibrillator for the SOS sheet, with its distance from the current position. */
data class NearbyAed(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val locationText: String?,
    val lat: Double,
    val lng: Double
)

internal object NavigationNearbyAeds {
    const val ROOM_RADIUS_M = 5_000.0
    const val MAX_SHOWN = 3

    /**
     * AEDs from the route corridor (any distance) plus AEDs stored in Room within 5 km of the
     * position, nearest first. Empty when there is no position.
     */
    fun nearest(
        lat: Double?,
        lng: Double?,
        corridorAeds: List<PoiEntity>,
        roomAeds: List<PoiEntity>
    ): List<NearbyAed> {
        if (lat == null || lng == null) return emptyList()
        val corridorIds = corridorAeds.map { it.id }.toSet()
        return (corridorAeds + roomAeds)
            .filter { it.type == "aed" }
            .distinctBy { it.id }
            .map { it to GeoUtils.distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { (poi, d) -> poi.id in corridorIds || d <= ROOM_RADIUS_M }
            .sortedBy { it.second }
            .take(MAX_SHOWN)
            .map { (poi, d) ->
                NearbyAed(
                    id = poi.id,
                    name = poi.name,
                    distanceMeters = d,
                    locationText = com.example.data.repository.GeoFaFieldParser.parseDetailsJson(poi.detailsJson)["defibrillator:location"],
                    lat = poi.lat,
                    lng = poi.lng
                )
            }
    }
}
