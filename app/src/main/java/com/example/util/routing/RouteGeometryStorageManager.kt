package com.example.util.routing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Detailed geometry and metadata for a single leg between two consecutive waypoints.
 * Stored individually to enable incremental replacement of single legs without touching others.
 */
data class LegGeometryData(
    val fromWaypointId: String,
    val toWaypointId: String,
    val legMode: LegMode,
    val points: List<RoutePoint3D>,
    val lengthKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val pathSharePct: Double,
    val roadKm: Double,
    val hasAccessWarning: Boolean = false,
    val sourceName: String = "BRouter",
    val fetchedAt: Long = System.currentTimeMillis(),
    val wayInfo: List<WayInfo> = emptyList()
) {
    val legKey: String get() = "${fromWaypointId}_to_${toWaypointId}"

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("fromWaypointId", fromWaypointId)
        obj.put("toWaypointId", toWaypointId)
        obj.put("legMode", legMode.name)
        obj.put("lengthKm", lengthKm)
        obj.put("ascentM", ascentM)
        obj.put("descentM", descentM)
        obj.put("pathSharePct", pathSharePct)
        obj.put("roadKm", roadKm)
        obj.put("hasAccessWarning", hasAccessWarning)
        obj.put("sourceName", sourceName)
        obj.put("fetchedAt", fetchedAt)

        val ptsArr = JSONArray()
        for (pt in points) {
            val ptArr = JSONArray()
            ptArr.put(pt.lat)
            ptArr.put(pt.lng)
            if (pt.ele != null) ptArr.put(pt.ele)
            ptsArr.put(ptArr)
        }
        val wiArr = JSONArray()
        for (w in wayInfo) {
            val wObj = JSONObject()
            if (w.highway != null) wObj.put("h", w.highway)
            if (w.surface != null) wObj.put("s", w.surface)
            if (w.tracktype != null) wObj.put("t", w.tracktype)
            if (w.rawTags.isNotEmpty()) {
                val tagsObj = JSONObject()
                w.rawTags.forEach { (k, v) -> tagsObj.put(k, v) }
                wObj.put("raw", tagsObj)
            }
            wiArr.put(wObj)
        }
        obj.put("wayInfo", wiArr)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): LegGeometryData {
            val ptsList = ArrayList<RoutePoint3D>()
            val ptsArr = obj.optJSONArray("points")
            if (ptsArr != null) {
                for (i in 0 until ptsArr.length()) {
                    val p = ptsArr.getJSONArray(i)
                    val lat = p.getDouble(0)
                    val lng = p.getDouble(1)
                    val ele = if (p.length() > 2) p.getDouble(2) else null
                    ptsList.add(RoutePoint3D(lat, lng, ele))
                }
            }

            val wiList = ArrayList<WayInfo>()
            val wiArr = obj.optJSONArray("wayInfo")
            if (wiArr != null) {
                for (i in 0 until wiArr.length()) {
                    val wObj = wiArr.getJSONObject(i)
                    val rawMap = mutableMapOf<String, String>()
                    val rawObj = wObj.optJSONObject("raw")
                    if (rawObj != null) {
                        for (k in rawObj.keys()) {
                            rawMap[k] = rawObj.optString(k, "")
                        }
                    }
                    wiList.add(
                        WayInfo(
                            highway = if (wObj.has("h")) wObj.optString("h") else null,
                            surface = if (wObj.has("s")) wObj.optString("s") else null,
                            tracktype = if (wObj.has("t")) wObj.optString("t") else null,
                            rawTags = rawMap
                        )
                    )
                }
            }

            return LegGeometryData(
                fromWaypointId = obj.getString("fromWaypointId"),
                toWaypointId = obj.getString("toWaypointId"),
                legMode = runCatching { LegMode.valueOf(obj.getString("legMode")) }.getOrDefault(LegMode.SNAP),
                points = ptsList,
                lengthKm = obj.optDouble("lengthKm", 0.0),
                ascentM = obj.optDouble("ascentM", 0.0),
                descentM = obj.optDouble("descentM", 0.0),
                pathSharePct = obj.optDouble("pathSharePct", 0.0),
                roadKm = obj.optDouble("roadKm", 0.0),
                hasAccessWarning = obj.optBoolean("hasAccessWarning", false),
                sourceName = obj.optString("sourceName", "BRouter"),
                fetchedAt = obj.optLong("fetchedAt", System.currentTimeMillis()),
                wayInfo = wiList
            )
        }
    }
}

data class RouteStats(
    val totalKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val pathSharePct: Double,
    val roadKm: Double
)

/**
 * Manages atomic and isolated persistence of route leg geometries in files.
 */
object RouteGeometryStorageManager {
    private const val TAG = "RouteGeomStorage"
    private const val DIR_NAME = "custom_routes"

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getGeometryFile(context: Context, filename: String): File {
        return File(getStorageDir(context), filename)
    }

    /**
     * Loads all legs from the geometry file.
     * Returns a map keyed by legKey ("${fromId}_to_${toId}").
     */
    fun loadLegs(context: Context, filename: String): Map<String, LegGeometryData> {
        val file = getGeometryFile(context, filename)
        if (!file.exists()) return emptyMap()

        return try {
            val content = file.readText()
            val obj = JSONObject(content)
            val legsObj = obj.optJSONObject("legs") ?: JSONObject()
            val result = mutableMapOf<String, LegGeometryData>()
            val keys = legsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val legObj = legsObj.getJSONObject(key)
                result[key] = LegGeometryData.fromJson(legObj)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load legs from $filename: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Updates or inserts a single leg into the geometry file without re-computing other legs.
     */
    @Synchronized
    fun saveLeg(context: Context, filename: String, leg: LegGeometryData) {
        try {
            val currentLegs = loadLegs(context, filename).toMutableMap()
            currentLegs[leg.legKey] = leg
            saveAllLegs(context, filename, currentLegs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save leg ${leg.legKey} to $filename: ${e.message}")
        }
    }

    /**
     * Saves all legs into the geometry file in one batch.
     */
    @Synchronized
    fun saveAllLegs(context: Context, filename: String, legs: Map<String, LegGeometryData>) {
        try {
            val root = JSONObject()
            val legsObj = JSONObject()
            for ((key, leg) in legs) {
                legsObj.put(key, leg.toJson())
            }
            root.put("version", 1)
            root.put("legs", legsObj)

            val file = getGeometryFile(context, filename)
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save all legs to $filename: ${e.message}")
        }
    }

    /**
     * Deletes the geometry file when a custom route is deleted.
     */
    fun deleteGeometryFile(context: Context, filename: String) {
        try {
            val file = getGeometryFile(context, filename)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete geometry file $filename: ${e.message}")
        }
    }

    /**
     * Calculates combined stats across all legs in ordered sequence.
     */
    fun calculateStats(orderedLegs: List<LegGeometryData>): RouteStats {
        if (orderedLegs.isEmpty()) {
            return RouteStats(0.0, 0.0, 0.0, 100.0, 0.0)
        }

        var totalKm = 0.0
        var totalAscent = 0.0
        var totalDescent = 0.0
        var totalRoadKm = 0.0
        var weightedPathKm = 0.0

        for (leg in orderedLegs) {
            totalKm += leg.lengthKm
            totalAscent += leg.ascentM
            totalDescent += leg.descentM
            totalRoadKm += leg.roadKm
            weightedPathKm += (leg.lengthKm * (leg.pathSharePct / 100.0))
        }

        val pathShare = if (totalKm > 0.0) {
            (weightedPathKm / totalKm * 100.0).coerceIn(0.0, 100.0)
        } else 100.0

        return RouteStats(
            totalKm = totalKm,
            ascentM = totalAscent,
            descentM = totalDescent,
            pathSharePct = pathShare,
            roadKm = totalRoadKm
        )
    }

    /**
     * Combines all points across legs sequentially.
     */
    fun getCombinedPoints(orderedLegs: List<LegGeometryData>): List<RoutePoint3D> {
        val result = mutableListOf<RoutePoint3D>()
        for (leg in orderedLegs) {
            if (result.isEmpty()) {
                result.addAll(leg.points)
            } else {
                // Avoid duplicating junction point if identical
                val startIdx = if (leg.points.isNotEmpty() &&
                    result.last().lat == leg.points.first().lat &&
                    result.last().lng == leg.points.first().lng) 1 else 0
                for (i in startIdx until leg.points.size) {
                    result.add(leg.points[i])
                }
            }
        }
        return result
    }

    /**
     * Converts a custom route and its ordered legs into the exact same TrailGeometryDto
     * used by trails (Requirement A).
     */
    fun convertCustomRouteToTrailGeometry(
        routeId: String,
        orderedLegs: List<LegGeometryData>,
        customRouteName: String = ""
    ): com.example.data.model.TrailGeometryDto {
        val segments = mutableListOf<com.example.data.model.TrailSegmentDto>()
        var totalWalkingKm = 0.0
        val allPoints = mutableListOf<com.example.data.model.GeoPointDto>()

        for (leg in orderedLegs) {
            val isFerry = leg.legMode == LegMode.STRAIGHT && leg.sourceName.contains("ferry", ignoreCase = true)
            val segType = if (isFerry) com.example.data.model.TrailSegmentType.FERRY else com.example.data.model.TrailSegmentType.WALK
            val pts = leg.points.map { com.example.data.model.GeoPointDto(it.lat, it.lng, it.ele) }
            allPoints.addAll(pts)
            if (segType == com.example.data.model.TrailSegmentType.WALK) {
                totalWalkingKm += leg.lengthKm
            }
            segments.add(
                com.example.data.model.TrailSegmentDto(
                    type = segType,
                    points = pts,
                    lengthKm = leg.lengthKm
                )
            )
        }

        val lats = allPoints.map { it.lat }
        val lngs = allPoints.map { it.lng }
        val bboxNorth = lats.maxOrNull() ?: 57.0
        val bboxSouth = lats.minOrNull() ?: 55.0
        val bboxEast = lngs.maxOrNull() ?: 12.0
        val bboxWest = lngs.minOrNull() ?: 8.0

        val stats = calculateStats(orderedLegs)

        return com.example.data.model.TrailGeometryDto(
            trailId = routeId,
            osmRelationId = 0L,
            segments = segments,
            sideBranches = emptyList(),
            gaps = emptyList(),
            totalWalkingKm = if (totalWalkingKm > 0.0) totalWalkingKm else stats.totalKm,
            totalAscentM = stats.ascentM.toInt(),
            totalDescentM = stats.descentM.toInt(),
            gapsOver200mCount = 0,
            bboxNorth = bboxNorth,
            bboxSouth = bboxSouth,
            bboxEast = bboxEast,
            bboxWest = bboxWest,
            osmTimestamp = null,
            fetchedAt = System.currentTimeMillis()
        )
    }
}
