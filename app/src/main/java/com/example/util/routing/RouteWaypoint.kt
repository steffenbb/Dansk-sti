package com.example.util.routing

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Functional role of a waypoint within a custom planned route.
 */
enum class WaypointRole {
    START,
    VIA,
    OVERNIGHT,
    END
}

/**
 * Routing mode for the route leg connecting this waypoint to the NEXT waypoint.
 * - SNAP: Routes along trails/paths using the RoutingEngine (BRouter).
 * - STRAIGHT: Direct geodesic line without trail snapping (e.g. crossing unmapped terrain or water).
 */
enum class LegMode {
    SNAP,
    STRAIGHT
}

/**
 * Represents a single waypoint along a custom route.
 */
data class RouteWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lng: Double,
    val role: WaypointRole = WaypointRole.VIA,
    val legModeToNext: LegMode = LegMode.SNAP,
    val linkedPoiId: String? = null,
    val name: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("lat", lat)
        obj.put("lng", lng)
        obj.put("role", role.name)
        obj.put("legModeToNext", legModeToNext.name)
        if (linkedPoiId != null) obj.put("linkedPoiId", linkedPoiId)
        if (name != null) obj.put("name", name)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): RouteWaypoint {
            return RouteWaypoint(
                id = obj.optString("id", UUID.randomUUID().toString()),
                lat = obj.getDouble("lat"),
                lng = obj.getDouble("lng"),
                role = runCatching { WaypointRole.valueOf(obj.optString("role", WaypointRole.VIA.name)) }
                    .getOrDefault(WaypointRole.VIA),
                legModeToNext = runCatching { LegMode.valueOf(obj.optString("legModeToNext", LegMode.SNAP.name)) }
                    .getOrDefault(LegMode.SNAP),
                linkedPoiId = if (obj.has("linkedPoiId")) obj.getString("linkedPoiId") else null,
                name = if (obj.has("name")) obj.getString("name") else null
            )
        }

        fun listToJson(list: List<RouteWaypoint>): String {
            val arr = JSONArray()
            for (wp in list) {
                arr.put(wp.toJson())
            }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String): List<RouteWaypoint> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val list = ArrayList<RouteWaypoint>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(fromJson(arr.getJSONObject(i)))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
