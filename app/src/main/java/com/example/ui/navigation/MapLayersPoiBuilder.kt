package com.example.ui.navigation

import com.example.data.local.MapLayersState
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.ui.viewmodel.PlannerMapPoi
import com.example.util.GeoUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

internal object MapLayersPoiBuilder {

    private const val DEDUP_DISTANCE_METERS = 25.0

    fun computePoiSignature(
        state: MapLayersState,
        shelters: List<ShelterEntity>,
        friTeltning: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        customWaypoints: List<UserCustomWaypointEntity>,
        plannerPois: List<PlannerMapPoi>
    ): Int {
        var sig = 17
        sig = 31 * sig + state.showShelters.hashCode()
        sig = 31 * sig + state.showFriTeltning.hashCode()
        sig = 31 * sig + state.showWaterPois.hashCode()
        sig = 31 * sig + state.showToilets.hashCode()
        sig = 31 * sig + state.showShops.hashCode()
        sig = 31 * sig + state.showTransit.hashCode()
        sig = 31 * sig + state.showMyPoints.hashCode()
        sig = 31 * sig + shelters.size
        sig = 31 * sig + friTeltning.size
        sig = 31 * sig + pois.size
        sig = 31 * sig + customWaypoints.size
        sig = 31 * sig + plannerPois.size
        return sig
    }

    /**
     * Builds the list of [PoiItem]s for the map's POI cluster layer, deduplicating by ID and
     * proximity (within [DEDUP_DISTANCE_METERS]) so there is strictly one marker per place.
     */
    fun buildClusterItems(
        state: MapLayersState,
        shelters: List<ShelterEntity>,
        friTeltning: List<FriTeltningEntity>,
        pois: List<PoiEntity>,
        customWaypoints: List<UserCustomWaypointEntity>,
        plannerPois: List<PlannerMapPoi> = emptyList()
    ): ArrayList<PoiItem> {
        val items = ArrayList<PoiItem>()
        val seenIds = HashSet<String>()

        fun addIfUnique(candidate: PoiItem) {
            if (!seenIds.add(candidate.id)) return
            val isDuplicatePlace = items.any { existing ->
                existing.type == candidate.type &&
                    GeoUtils.distanceMeters(existing.lat, existing.lng, candidate.lat, candidate.lng) <= DEDUP_DISTANCE_METERS
            }
            if (!isDuplicatePlace) {
                items.add(candidate)
            }
        }

        if (state.showShelters) {
            for (s in shelters) {
                addIfUnique(
                    PoiItem(
                        id = s.id,
                        type = "shelter",
                        name = s.name,
                        subText = buildString {
                            s.capacity?.let { append("Kapacitet: $it pladser") }
                            if (s.bookingRequired) {
                                if (isNotEmpty()) append(" · ")
                                append("Booking")
                            }
                        }.ifEmpty { "Shelter / Lejrplads" },
                        lat = s.lat,
                        lng = s.lng,
                        payload = s
                    )
                )
            }
        }

        if (state.showFriTeltning) {
            for (f in friTeltning) {
                addIfUnique(
                    PoiItem(
                        id = f.id,
                        type = "tent",
                        name = f.name,
                        subText = "Fri teltning skov",
                        lat = f.centerLat,
                        lng = f.centerLng,
                        payload = f
                    )
                )
            }
        }

        for (p in pois) {
            val normalized = p.type.lowercase()
            val shouldInclude = when (normalized) {
                "water", "drinking_water", "vand" -> state.showWaterPois
                "toilet", "toilets" -> state.showToilets
                "shop", "supermarket", "convenience", "bakery", "indkoeb" -> state.showShops
                "station", "ferry", "train_station", "bus_stop", "ferry_terminal", "transit", "offentlig_transport" -> state.showTransit
                else -> false
            }
            if (shouldInclude) {
                val typeKey = when (normalized) {
                    "water", "drinking_water", "vand" -> "water"
                    "toilet", "toilets" -> "toilet"
                    "shop", "supermarket", "convenience", "bakery", "indkoeb" -> "shop"
                    else -> "transit"
                }
                addIfUnique(
                    PoiItem(
                        id = p.id,
                        type = typeKey,
                        name = p.name.ifBlank {
                            when (typeKey) {
                                "water" -> "Drikkevand"
                                "toilet" -> "Toilet"
                                "shop" -> "Indkøb"
                                else -> "Stoppested / Station"
                            }
                        },
                        subText = p.openingHours ?: p.operator ?: "",
                        lat = p.lat,
                        lng = p.lng,
                        payload = p
                    )
                )
            }
        }

        // Round 11 Item 5: Include planner POIs only when not already shown by the map's shelter/POI layer
        for (pp in plannerPois) {
            val normalized = pp.type.lowercase()
            val (typeKey, enabled) = when {
                normalized.contains("shelter") || normalized == "overnight" -> "shelter" to state.showShelters
                normalized.contains("tent") || normalized.contains("telt") -> "tent" to state.showFriTeltning
                normalized.contains("water") || normalized.contains("vand") -> "water" to state.showWaterPois
                normalized.contains("toilet") -> "toilet" to state.showToilets
                normalized.contains("shop") || normalized.contains("supermarket") || normalized.contains("convenience") || normalized.contains("bakery") ->
                    "shop" to state.showShops
                normalized.contains("station") || normalized.contains("bus") || normalized.contains("transit") || normalized.contains("ferry") ->
                    "transit" to state.showTransit
                else -> "shelter" to state.showShelters
            }
            if (enabled) {
                addIfUnique(
                    PoiItem(
                        id = pp.id,
                        type = typeKey,
                        name = pp.name,
                        subText = if (pp.bookingRequired) "Booking" else pp.type,
                        lat = pp.lat,
                        lng = pp.lng,
                        payload = pp
                    )
                )
            }
        }

        if (state.showMyPoints) {
            for (wp in customWaypoints) {
                addIfUnique(
                    PoiItem(
                        id = "wp_${wp.id}",
                        type = "my_point",
                        name = wp.name,
                        subText = wp.note.ifBlank { "Gemt punkt" },
                        lat = wp.lat,
                        lng = wp.lng,
                        payload = wp
                    )
                )
            }
        }

        return items
    }

    fun findLongPressPoi(
        point: GeoPoint,
        maxDistanceMeters: Double,
        poiClusterOverlay: PoiClusterOverlay?,
        mapView: MapView?,
        cachedShelters: List<ShelterEntity>,
        cachedFriTeltning: List<FriTeltningEntity>,
        cachedPois: List<PoiEntity>
    ): PoiItem? {
        val allowedTypes = setOf("shelter", "tent", "water", "toilet", "shop")

        var bestPoi: PoiItem? = null
        var bestDist = maxDistanceMeters

        for (item in poiClusterOverlay?.getItems().orEmpty()) {
            if (item.type !in allowedTypes) continue
            val d = GeoUtils.distanceMeters(point.latitude, point.longitude, item.lat, item.lng)
            if (d <= bestDist) {
                bestDist = d
                bestPoi = item
            }
        }

        for (s in cachedShelters) {
            val d = GeoUtils.distanceMeters(point.latitude, point.longitude, s.lat, s.lng)
            if (d <= bestDist) {
                bestDist = d
                bestPoi = PoiItem(
                    id = s.id,
                    type = "shelter",
                    name = s.name,
                    subText = buildString {
                        s.capacity?.let { append("Kapacitet: $it pladser") }
                        if (s.bookingRequired) {
                            if (isNotEmpty()) append(" · ")
                            append("Booking")
                        }
                    }.ifEmpty { "Shelter / Lejrplads" },
                    lat = s.lat,
                    lng = s.lng,
                    payload = s
                )
            }
        }

        for (f in cachedFriTeltning) {
            val d = GeoUtils.distanceMeters(point.latitude, point.longitude, f.centerLat, f.centerLng)
            if (d <= bestDist) {
                bestDist = d
                bestPoi = PoiItem(
                    id = f.id,
                    type = "tent",
                    name = f.name,
                    subText = "Fri teltning skov",
                    lat = f.centerLat,
                    lng = f.centerLng,
                    payload = f
                )
            }
        }

        for (p in cachedPois) {
            val normalized = p.type.lowercase()
            val typeKey = when (normalized) {
                "water", "drinking_water", "vand" -> "water"
                "toilet", "toilets" -> "toilet"
                "shop", "supermarket", "convenience", "bakery", "indkoeb" -> "shop"
                else -> null
            } ?: continue
            val d = GeoUtils.distanceMeters(point.latitude, point.longitude, p.lat, p.lng)
            if (d <= bestDist) {
                bestDist = d
                bestPoi = PoiItem(
                    id = p.id,
                    type = typeKey,
                    name = p.name.ifBlank {
                        when (typeKey) {
                            "water" -> "Drikkevand"
                            "toilet" -> "Toilet"
                            else -> "Indkøb"
                        }
                    },
                    subText = p.openingHours ?: p.operator ?: "",
                    lat = p.lat,
                    lng = p.lng,
                    payload = p
                )
            }
        }

        if (bestPoi != null) {
            return bestPoi
        }

        return poiClusterOverlay?.findHitPoiAtGeoPoint(point, mapView, allowedTypes)
    }
}
