package com.example.util.routing

import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.model.CachedRouteLegEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Shared utility for parsing BRouter GeoJSON responses, correlating OSM tags to points,
 * calculating elevation ascent/descent, and interacting with the Room cache.
 */
object BRouterGeoJsonParser {

    private const val TAG = "BRouterGeoJsonParser"
    const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

    suspend fun parseGeoJsonResponse(
        jsonStr: String,
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        cacheKey: String,
        sourceName: String,
        dao: DanskStiDao? = null
    ): RouteLegResult {
        val json = JSONObject(jsonStr)
        val features = json.optJSONArray("features")
        if (features == null || features.length() == 0) {
            return RouteLegResult.NoRoute("Ingen rute fundet i GeoJSON svar")
        }

        val feature = features.getJSONObject(0)
        val geometry = feature.optJSONObject("geometry")
        val properties = feature.optJSONObject("properties") ?: JSONObject()

        val coordinates = geometry?.optJSONArray("coordinates")
        if (coordinates == null || coordinates.length() == 0) {
            return RouteLegResult.NoRoute("Ingen koordinater fundet på ruten")
        }

        // Extract 3D points (coordinates format: [lon, lat, ele])
        val points = mutableListOf<RoutePoint3D>()
        for (i in 0 until coordinates.length()) {
            val coordArr = coordinates.getJSONArray(i)
            val lng = coordArr.getDouble(0)
            val lat = coordArr.getDouble(1)
            val ele = if (coordArr.length() > 2) coordArr.getDouble(2) else null
            points.add(RoutePoint3D(lat = lat, lng = lng, ele = ele))
        }

        // Track length
        val trackLengthMeters = properties.optDouble("track-length", 0.0)
        val lengthKm = if (trackLengthMeters > 0.0) trackLengthMeters / 1000.0 else calculateDistanceKm(points)

        // Calculate elevation ascent and descent
        var ascentM = properties.optDouble("filtered ascend", -1.0)
        if (ascentM < 0.0) {
            ascentM = calculateAscent(points)
        }
        var descentM = properties.optDouble("filtered descend", -1.0)
        if (descentM < 0.0) {
            descentM = calculateDescent(points)
        }

        // Parse messages table
        val messages = properties.optJSONArray("messages")
        val sectionWayInfos = parseMessagesTable(messages)

        // Correlate way info per point
        val perPointWayInfo = correlateWayInfoToPoints(points, sectionWayInfos)

        // Save into local Room database cache
        if (dao != null) {
            try {
                val now = System.currentTimeMillis()
                val cachedEntity = CachedRouteLegEntity(
                    cacheKey = cacheKey,
                    fromLat = from.lat,
                    fromLng = from.lng,
                    toLat = to.lat,
                    toLng = to.lng,
                    avoidRoads = options.avoidRoads,
                    preferNature = options.preferNature,
                    allowFerries = options.allowFerries,
                    alternative = alternative,
                    lengthKm = lengthKm,
                    ascentM = ascentM,
                    descentM = descentM,
                    pointsJson = serializePoints(points),
                    wayInfoJson = serializeWayInfo(perPointWayInfo),
                    sourceName = sourceName,
                    cachedAt = now,
                    expiresAt = now + CACHE_TTL_MS
                )
                dao.insertCachedRouteLeg(cachedEntity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing route leg to Room cache", e)
            }
        }

        return RouteLegResult.Success(
            points = points,
            lengthKm = lengthKm,
            ascentM = ascentM,
            descentM = descentM,
            perPointWayInfo = perPointWayInfo,
            sourceName = sourceName,
            fetchedAt = System.currentTimeMillis()
        )
    }

    fun parseMessagesTable(messages: JSONArray?): List<Pair<Double, WayInfo>> {
        if (messages == null || messages.length() <= 1) return emptyList()

        val headers = messages.getJSONArray(0)
        var wayTagsIdx = -1
        var distIdx = -1

        for (i in 0 until headers.length()) {
            val h = headers.getString(i)
            if (h.equals("WayTags", ignoreCase = true)) wayTagsIdx = i
            if (h.equals("Distance", ignoreCase = true)) distIdx = i
        }

        if (wayTagsIdx == -1) return emptyList()

        val sections = mutableListOf<Pair<Double, WayInfo>>()
        for (r in 1 until messages.length()) {
            val row = messages.getJSONArray(r)
            val distM = if (distIdx != -1) row.optDouble(distIdx, 0.0) else 0.0
            val wayTagsStr = row.optString(wayTagsIdx, "")

            val tagMap = mutableMapOf<String, String>()
            wayTagsStr.split(" ").forEach { token ->
                val eqIdx = token.indexOf('=')
                if (eqIdx > 0) {
                    val k = token.substring(0, eqIdx)
                    val v = token.substring(eqIdx + 1)
                    tagMap[k] = v
                }
            }

            val wayInfo = WayInfo(
                highway = tagMap["highway"],
                surface = tagMap["surface"],
                tracktype = tagMap["tracktype"],
                access = tagMap["access"],
                forestClass = tagMap["estimated_forest_class"] ?: tagMap["forestClass"],
                rawTags = tagMap
            )
            sections.add(Pair(distM, wayInfo))
        }

        return sections
    }

    fun correlateWayInfoToPoints(
        points: List<RoutePoint3D>,
        sections: List<Pair<Double, WayInfo>>
    ): List<WayInfo> {
        if (sections.isEmpty() || points.isEmpty()) {
            return List(points.size) { WayInfo() }
        }

        val result = mutableListOf<WayInfo>()
        var currentSectionIdx = 0
        var currentSectionRemainingM = sections[0].first

        result.add(sections[0].second)

        for (i in 1 until points.size) {
            val pPrev = points[i - 1]
            val pCurr = points[i]
            val stepDistM = distanceMeters(pPrev.lat, pPrev.lng, pCurr.lat, pCurr.lng)

            currentSectionRemainingM -= stepDistM
            while (currentSectionRemainingM <= 0.0 && currentSectionIdx < sections.size - 1) {
                currentSectionIdx++
                currentSectionRemainingM += sections[currentSectionIdx].first
            }

            result.add(sections[currentSectionIdx].second)
        }

        return result
    }

    fun calculateAscent(points: List<RoutePoint3D>): Double {
        var ascent = 0.0
        for (i in 0 until points.size - 1) {
            val e1 = points[i].ele
            val e2 = points[i + 1].ele
            if (e1 != null && e2 != null) {
                val d = e2 - e1
                if (d > 0.0) ascent += d
            }
        }
        return ascent
    }

    fun calculateDescent(points: List<RoutePoint3D>): Double {
        var descent = 0.0
        for (i in 0 until points.size - 1) {
            val e1 = points[i].ele
            val e2 = points[i + 1].ele
            if (e1 != null && e2 != null) {
                val d = e1 - e2
                if (d > 0.0) descent += d
            }
        }
        return descent
    }

    fun calculateDistanceKm(points: List<RoutePoint3D>): Double {
        var totalM = 0.0
        for (i in 0 until points.size - 1) {
            totalM += distanceMeters(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng)
        }
        return totalM / 1000.0
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDist = Math.toRadians(lat2 - lat1)
        val lonDist = Math.toRadians(lon2 - lon1)
        val a = Math.sin(latDist / 2) * Math.sin(latDist / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(lonDist / 2) * Math.sin(lonDist / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371000.0 * c
    }

    fun generateCacheKey(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%.5f,%.5f->%.5f,%.5f", from.lat, from.lng, to.lat, to.lng))
        sb.append("|ar:").append(String.format(Locale.US, "%.2f", options.avoidRoads))
        sb.append("|pn:").append(options.preferNature)
        sb.append("|af:").append(options.allowFerries)
        sb.append("|alt:").append(alternative)
        if (options.noGoPoints.isNotEmpty()) {
            sb.append("|ngp:").append(options.noGoPoints.joinToString(";") {
                String.format(Locale.US, "%.5f,%.5f,%.0f", it.lat, it.lng, it.radiusM)
            })
        }
        if (options.noGoAreas.isNotEmpty()) {
            sb.append("|nga:").append(options.noGoAreas.joinToString(";") { poly ->
                poly.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lng) }
            })
        }
        return sb.toString()
    }

    fun serializePoints(points: List<RoutePoint3D>): String {
        val arr = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            if (p.ele != null) obj.put("ele", p.ele)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializePoints(jsonStr: String): List<RoutePoint3D> {
        val arr = JSONArray(jsonStr)
        val list = ArrayList<RoutePoint3D>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                RoutePoint3D(
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    ele = if (obj.has("ele")) obj.getDouble("ele") else null
                )
            )
        }
        return list
    }

    fun serializeWayInfo(wayInfo: List<WayInfo>): String {
        val arr = JSONArray()
        for (w in wayInfo) {
            val obj = JSONObject()
            if (w.highway != null) obj.put("highway", w.highway)
            if (w.surface != null) obj.put("surface", w.surface)
            if (w.tracktype != null) obj.put("tracktype", w.tracktype)
            if (w.access != null) obj.put("access", w.access)
            if (w.forestClass != null) obj.put("forestClass", w.forestClass)
            if (w.rawTags.isNotEmpty()) obj.put("rawTags", JSONObject(w.rawTags))
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeWayInfo(jsonStr: String): List<WayInfo> {
        val arr = JSONArray(jsonStr)
        val list = ArrayList<WayInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rawMap = mutableMapOf<String, String>()
            if (obj.has("rawTags")) {
                val rawObj = obj.getJSONObject("rawTags")
                rawObj.keys().forEach { k -> rawMap[k] = rawObj.getString(k) }
            }
            list.add(
                WayInfo(
                    highway = if (obj.has("highway")) obj.getString("highway") else null,
                    surface = if (obj.has("surface")) obj.getString("surface") else null,
                    tracktype = if (obj.has("tracktype")) obj.getString("tracktype") else null,
                    access = if (obj.has("access")) obj.getString("access") else null,
                    forestClass = if (obj.has("forestClass")) obj.getString("forestClass") else null,
                    rawTags = rawMap
                )
            )
        }
        return list
    }
}
