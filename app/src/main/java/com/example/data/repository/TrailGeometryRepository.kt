package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailGapDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

import com.example.util.network.applyDebugMainThreadCheck

class TrailGeometryRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val geometryAdapter = moshi.adapter(TrailGeometryDto::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .applyDebugMainThreadCheck()
        .build()

    private val trailsDir: File
        get() = File(context.filesDir, "trails").apply { if (!exists()) mkdirs() }

    val routesDir: File
        get() = File(context.filesDir, "routes").apply { if (!exists()) mkdirs() }

    fun getGeometryFile(trailId: String): File {
        return File(trailsDir, "$trailId.json.gz")
    }

    fun getRouteGeometryFile(routeId: String): File {
        val cleanId = routeId.removePrefix("custom_route_").removePrefix("route_")
        return File(routesDir, "$cleanId.json.gz")
    }

    suspend fun isGeometryCached(trailId: String): Boolean = withContext(Dispatchers.IO) {
        getRouteGeometryFile(trailId).exists() ||
            getGeometryFile(trailId).exists() ||
            File(context.filesDir, "trail_geom_$trailId.json").exists() ||
            com.example.data.local.PreseededData.getFallbackTrailGeometry(trailId) != null
    }

    suspend fun loadGeometry(trailId: String): TrailGeometryDto? = withContext(Dispatchers.IO) {
        // 1. Check custom routes directory first
        val routeFile = getRouteGeometryFile(trailId)
        if (routeFile.exists()) {
            try {
                routeFile.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached route geometry for $trailId", e)
            }
        }

        // 2. Check trails directory (.json.gz)
        val file = getGeometryFile(trailId)
        if (file.exists()) {
            try {
                file.inputStream().use { fis ->
                    GZIPInputStream(fis).use { gis ->
                        gis.source().buffer().use { bufferedSource ->
                            val dto = geometryAdapter.fromJson(bufferedSource)
                            if (dto != null) return@withContext dto
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load cached geometry for $trailId", e)
            }
        }

        // 3. Check plain JSON file in filesDir (e.g. trail_geom_<trailId>.json)
        val plainFile = File(context.filesDir, "trail_geom_$trailId.json")
        if (plainFile.exists()) {
            try {
                val dto = geometryAdapter.fromJson(plainFile.readText())
                if (dto != null) return@withContext dto
            } catch (e: Exception) {
                Log.e("TrailGeometry", "Failed to load plain JSON geometry for $trailId", e)
            }
        }

        // 4. Fall back to pre-seeded geometry for the 12 core Danish trails
        com.example.data.local.PreseededData.getFallbackTrailGeometry(trailId)
    }

    suspend fun saveGeometry(geometry: TrailGeometryDto) = withContext(Dispatchers.IO) {
        val file = getGeometryFile(geometry.trailId)
        try {
            file.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TrailGeometry", "Failed to save geometry for ${geometry.trailId}", e)
        }
    }

    suspend fun saveRouteGeometry(routeId: String, geometry: TrailGeometryDto) = withContext(Dispatchers.IO) {
        val file = getRouteGeometryFile(routeId)
        try {
            file.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { bufferedSink ->
                        geometryAdapter.toJson(bufferedSink, geometry)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TrailGeometry", "Failed to save route geometry for $routeId", e)
        }
    }

    suspend fun fetchAndProcessTrail(
        trailId: String,
        trailName: String,
        osmRelationId: Long?
    ): TrailGeometryDto? = withContext(Dispatchers.IO) {
        val relationId = osmRelationId ?: findRelationByName(trailName) ?: return@withContext null
        val rawElements = mutableMapOf<Long, Map<String, Any>>()
        
        // 1. Download via direct OSM 0.6 API first (official, canonical, responsive in ~1-2s)
        downloadRelationDirectOsm(relationId, rawElements)

        // 2. If direct OSM returned no elements, fall back to Overpass mirrors
        if (rawElements.isEmpty()) {
            Log.i("TrailGeometry", "Direct OSM API returned no data for relation $relationId ($trailName). Trying Overpass mirrors.")
            downloadRelationRecursive(relationId, rawElements)
        }

        if (rawElements.isEmpty()) {
            Log.e("TrailGeometry", "No elements downloaded for relation $relationId ($trailName) from any source")
            return@withContext null
        }

        processRelationData(trailId, relationId, rawElements)
    }

    private fun findRelationByName(name: String): Long? {
        val cleanName = name.replace(Regex("\\s*\\(.*\\)"), "").trim()
        val query = """
            [out:json][timeout:10];
            relation["type"="route"]["route"~"hiking|foot"]["name"~"$cleanName",i](54.5,7.5,58.0,15.5);
            out tags bb;
        """.trimIndent()

        val mirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in mirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", "DanskSti/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val jsonStr = response.body?.string() ?: return@use
                    val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                    @Suppress("UNCHECKED_CAST")
                    val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()

                    Log.i("TrailGeometry", "=== Overpass search matches for \"$name\" (${elements.size} found on $url) ===")
                    for (el in elements) {
                        val id = (el["id"] as? Number)?.toLong()
                        @Suppress("UNCHECKED_CAST")
                        val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                        val matchName = tags["name"] ?: ""
                        val type = tags["type"] ?: ""
                        val route = tags["route"] ?: ""
                        val dist = tags["distance"] ?: ""
                        Log.i("TrailGeometry", "  ID: $id, name: \"$matchName\", type: \"$type\", route: \"$route\", dist: \"$dist\"")
                    }
                    if (elements.size == 1) {
                        return (elements[0]["id"] as? Number)?.toLong()
                    } else {
                        if (elements.size > 1) {
                            Log.w("TrailGeometry", "Multiple relation matches (${elements.size}) for \"$name\". Returning null.")
                        }
                        return null
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Error searching relation for $name on $url: ${e.message}")
            }
        }
        return null
    }

    private fun downloadRelationRecursive(relationId: Long, collected: MutableMap<Long, Map<String, Any>>) {
        if (collected.containsKey(relationId)) return
        val query = """
            [out:json][timeout:60];
            relation($relationId);
            out geom;
        """.trimIndent()

        val overpassMirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in overpassMirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", "DanskSti/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string() ?: return@use
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                        if (elements.isNotEmpty()) {
                            // Requirement 22: After downloading a relation, fetch its member way tags (way(r); out tags;)
                            // so ways with route=ferry are marked as FERRY segments.
                            val wayTagsMap = fetchMemberWayTags(relationId)
                            for (el in elements) {
                                val id = (el["id"] as? Number)?.toLong()
                                val type = el["type"] as? String
                                if (id != null && type == "relation") {
                                    val mutableEl = el.toMutableMap()
                                    @Suppress("UNCHECKED_CAST")
                                    val members = el["members"] as? List<Map<String, Any>> ?: emptyList()
                                    val enrichedMembers = members.map { m ->
                                        val mType = m["type"] as? String
                                        val mRef = (m["ref"] as? Number)?.toLong()
                                        if (mType == "way" && mRef != null) {
                                            val wTags = wayTagsMap[mRef]
                                            if (wTags != null) {
                                                val mCopy = m.toMutableMap()
                                                mCopy["tags"] = wTags
                                                mCopy
                                            } else m
                                        } else m
                                    }
                                    mutableEl["members"] = enrichedMembers
                                    collected[id] = mutableEl
                                    for (m in enrichedMembers) {
                                        val mType = m["type"] as? String
                                        val mRef = (m["ref"] as? Number)?.toLong()
                                        if (mType == "relation" && mRef != null) {
                                            downloadRelationRecursive(mRef, collected)
                                        }
                                    }
                                }
                            }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Overpass mirror $url failed for relation $relationId: ${e.message}")
            }
        }
    }

    /**
     * Requirement 22: Fetches member way tags for a relation using `relation($relationId); way(r); out tags;`
     * so ways with `route=ferry` are detected and marked as FERRY segments.
     */
    private fun fetchMemberWayTags(relationId: Long): Map<Long, Map<String, String>> {
        val query = """
            [out:json][timeout:30];
            relation($relationId);
            way(r);
            out tags;
        """.trimIndent()

        val overpassMirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        for (url in overpassMirrors) {
            try {
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", "DanskSti/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string() ?: return@use
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                        val result = mutableMapOf<Long, Map<String, String>>()
                        for (el in elements) {
                            val id = (el["id"] as? Number)?.toLong() ?: continue
                            val type = el["type"] as? String
                            if (type == "way") {
                                @Suppress("UNCHECKED_CAST")
                                val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                                result[id] = tags
                            }
                        }
                        return result
                    }
                }
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Way tags fetch failed on $url for relation $relationId: ${e.message}")
            }
        }
        return emptyMap()
    }

    /**
     * Requirement 13: Look up elevation for both ends of a STRAIGHT leg with Open-Meteo;
     * if that fails, return null. Never 15.0.
     */
    suspend fun fetchEndpointElevations(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/elevation?latitude=$fromLat,$toLat&longitude=$fromLng,$toLng"
        try {
            val request = Request.Builder().url(url).header("User-Agent", "DanskSti/1.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (!jsonStr.isNullOrEmpty()) {
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                        @Suppress("UNCHECKED_CAST")
                        val eleList = root?.get("elevation") as? List<Number?>
                        if (eleList != null && eleList.size >= 2) {
                            return@withContext Pair(eleList[0]?.toDouble(), eleList[1]?.toDouble())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TrailGeometry", "STRAIGHT leg endpoint elevation lookup failed: ${e.message}")
        }
        Pair(null, null)
    }

    private fun downloadRelationDirectOsm(relationId: Long, collected: MutableMap<Long, Map<String, Any>>) {
        if (collected.containsKey(relationId)) return
        val url = "https://api.openstreetmap.org/api/0.6/relation/$relationId/full.json"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DanskSti/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("TrailGeometry", "Direct OSM API returned ${response.code} for relation $relationId")
                    return
                }
                val jsonStr = response.body?.string() ?: return
                @Suppress("UNCHECKED_CAST")
                val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()

                val nodeMap = mutableMapOf<Long, Map<String, Double>>()
                val wayMap = mutableMapOf<Long, MutableMap<String, Any>>()
                val relMap = mutableMapOf<Long, MutableMap<String, Any>>()

                for (el in elements) {
                    val id = (el["id"] as? Number)?.toLong() ?: continue
                    val type = el["type"] as? String
                    when (type) {
                        "node" -> {
                            val lat = (el["lat"] as? Number)?.toDouble() ?: 0.0
                            val lon = (el["lon"] as? Number)?.toDouble() ?: 0.0
                            nodeMap[id] = mapOf("lat" to lat, "lon" to lon)
                        }
                        "way" -> {
                            wayMap[id] = el.toMutableMap()
                        }
                        "relation" -> {
                            relMap[id] = el.toMutableMap()
                        }
                    }
                }

                // Resolve way node coordinates into inline geometry
                for ((_, way) in wayMap) {
                    @Suppress("UNCHECKED_CAST")
                    val nodeRefs = (way["nodes"] as? List<Number>)?.map { it.toLong() } ?: emptyList()
                    val wayGeom = nodeRefs.mapNotNull { nodeMap[it] }
                    way["geometry"] = wayGeom
                }

                val currentRel = relMap[relationId] ?: return
                @Suppress("UNCHECKED_CAST")
                val rawMembers = currentRel["members"] as? List<Map<String, Any>> ?: emptyList()
                val resolvedMembers = mutableListOf<Map<String, Any>>()
                val childRelIds = mutableListOf<Long>()

                for (m in rawMembers) {
                    val mType = m["type"] as? String
                    val mRef = (m["ref"] as? Number)?.toLong()
                    if (mType == "way" && mRef != null) {
                        val fullWay = wayMap[mRef]
                        val memberCopy = m.toMutableMap()
                        if (fullWay != null) {
                            @Suppress("UNCHECKED_CAST")
                            val geom = fullWay["geometry"] as? List<Map<String, Double>> ?: emptyList()
                            memberCopy["geometry"] = geom
                            @Suppress("UNCHECKED_CAST")
                            val tags = fullWay["tags"] as? Map<String, String> ?: emptyMap()
                            memberCopy["tags"] = tags
                        }
                        resolvedMembers.add(memberCopy)
                    } else if (mType == "relation" && mRef != null) {
                        resolvedMembers.add(m)
                        childRelIds.add(mRef)
                    } else {
                        resolvedMembers.add(m)
                    }
                }

                currentRel["members"] = resolvedMembers
                collected[relationId] = currentRel

                // Recursively fetch child relations
                for (cid in childRelIds) {
                    downloadRelationDirectOsm(cid, collected)
                }
            }
        } catch (e: Exception) {
            Log.e("TrailGeometry", "Error fetching direct OSM relation $relationId: ${e.message}", e)
        }
    }

    private fun processRelationData(
        trailId: String,
        rootRelationId: Long,
        elements: Map<Long, Map<String, Any>>
    ): TrailGeometryDto {
        val rootEl = elements[rootRelationId]
        val childRelationIds = mutableListOf<Long>()
        val directWayMembers = mutableListOf<Map<String, Any>>()

        @Suppress("UNCHECKED_CAST")
        val rootMembers = rootEl?.get("members") as? List<Map<String, Any>> ?: emptyList()

        for (m in rootMembers) {
            val mType = m["type"] as? String
            val mRef = (m["ref"] as? Number)?.toLong()
            if (mType == "relation" && mRef != null) {
                childRelationIds.add(mRef)
            } else if (mType == "way") {
                directWayMembers.add(m)
            }
        }

        val orderedMembers = mutableListOf<Map<String, Any>>()
        if (childRelationIds.isNotEmpty()) {
            for (cid in childRelationIds) {
                val childEl = elements[cid]
                @Suppress("UNCHECKED_CAST")
                val cMembers = childEl?.get("members") as? List<Map<String, Any>> ?: emptyList()
                orderedMembers.addAll(cMembers)
            }
        } else {
            orderedMembers.addAll(rootMembers)
        }

        val walkWays = mutableListOf<WayData>()
        val sideBranchWays = mutableListOf<WayData>()

        for (m in orderedMembers) {
            val mType = m["type"] as? String
            if (mType != "way") continue
            @Suppress("UNCHECKED_CAST")
            val geom = m["geometry"] as? List<Map<String, Double>> ?: continue
            if (geom.size < 2) continue

            val pts = geom.map { GeoPointDto(lat = it["lat"] ?: 0.0, lng = it["lon"] ?: 0.0) }
            val role = m["role"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val tags = m["tags"] as? Map<String, String> ?: emptyMap()

            val isFerry = (role == "ferry") || (tags["route"] == "ferry") || (tags["highway"] == "ferry")
            val segType = if (isFerry) TrailSegmentType.FERRY else TrailSegmentType.WALK

            val wayData = WayData(type = segType, points = pts, role = role)
            if (role == "alternative" || role == "excursion") {
                sideBranchWays.add(wayData)
            } else {
                walkWays.add(wayData)
            }
        }

        val (mainSegments, gaps) = stitchWays(walkWays)
        val (sideSegments, _) = stitchWays(sideBranchWays)

        // Simplify with Douglas-Peucker at 4m
        val simplifiedMain = mainSegments.map { seg ->
            InternalSegment(seg.type, douglasPeucker(seg.points, 4.0).toMutableList())
        }
        val simplifiedSide = sideSegments.map { seg ->
            InternalSegment(seg.type, douglasPeucker(seg.points, 4.0).toMutableList())
        }

        // Keep Douglas-Peucker (4m) points as route geometry; resample 100m ONLY for elevation lookup
        val finalSegments = applyElevationToSegments(simplifiedMain)
        val finalSideBranches = applyElevationToSegments(simplifiedSide)

        var totalWalkingKm = 0.0
        var totalAscent = 0.0
        var totalDescent = 0.0
        var hasElevationData = false
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (seg in finalSegments) {
            if (seg.type == TrailSegmentType.WALK) {
                totalWalkingKm += seg.lengthKm
                val pts = seg.points
                for (i in 0 until pts.size - 1) {
                    val e1 = pts[i].ele
                    val e2 = pts[i + 1].ele
                    if (e1 != null && e2 != null) {
                        hasElevationData = true
                        val diff = e2 - e1
                        if (diff > 3.0) {
                            totalAscent += diff
                        } else if (diff < -3.0) {
                            totalDescent += -diff
                        }
                    }
                }
            }
            for (p in seg.points) {
                minLat = min(minLat, p.lat)
                maxLat = max(maxLat, p.lat)
                minLng = min(minLng, p.lng)
                maxLng = max(maxLng, p.lng)
            }
        }

        val gapsOver200mCount = gaps.count { it.gapMeters > 200.0 }
        val osmTimestamp = (rootEl?.get("timestamp") as? String)

        return TrailGeometryDto(
            trailId = trailId,
            osmRelationId = rootRelationId,
            segments = finalSegments,
            sideBranches = finalSideBranches,
            gaps = gaps,
            totalWalkingKm = roundTo1Decimal(totalWalkingKm),
            totalAscentM = if (hasElevationData) totalAscent.toInt() else null,
            totalDescentM = if (hasElevationData) totalDescent.toInt() else null,
            gapsOver200mCount = gapsOver200mCount,
            bboxNorth = if (maxLat > -90.0) maxLat else 56.0,
            bboxSouth = if (minLat < 90.0) minLat else 54.0,
            bboxEast = if (maxLng > -180.0) maxLng else 13.0,
            bboxWest = if (minLng < 180.0) minLng else 8.0,
            osmTimestamp = osmTimestamp
        )
    }

    private data class WayData(
        val type: TrailSegmentType,
        val points: List<GeoPointDto>,
        val role: String
    )

    private data class InternalSegment(
        val type: TrailSegmentType,
        val points: MutableList<GeoPointDto>
    )

    private fun stitchWays(ways: List<WayData>): Pair<List<InternalSegment>, List<TrailGapDto>> {
        val segments = mutableListOf<InternalSegment>()
        val gaps = mutableListOf<TrailGapDto>()
        var curPts = mutableListOf<GeoPointDto>()
        var curType = TrailSegmentType.WALK

        for (w in ways) {
            if (curPts.isEmpty()) {
                curPts.addAll(w.points)
                curType = w.type
                continue
            }

            if (w.type != curType) {
                segments.add(InternalSegment(curType, curPts))
                curPts = w.points.toMutableList()
                curType = w.type
                continue
            }

            val last = curPts.last()
            val firstP = w.points.first()
            val lastP = w.points.last()

            val dNorm = distanceMeters(last, firstP)
            val dFlip = distanceMeters(last, lastP)

            val oriented = if (dFlip < dNorm) w.points.reversed() else w.points
            val gap = min(dNorm, dFlip)

            if (gap > 30.0) {
                segments.add(InternalSegment(curType, curPts))
                gaps.add(TrailGapDto(fromPoint = last, toPoint = oriented.first(), gapMeters = gap))
                curPts = oriented.toMutableList()
            } else {
                if (gap < 1.0) {
                    curPts.addAll(oriented.drop(1))
                } else {
                    curPts.addAll(oriented)
                }
            }
        }

        if (curPts.isNotEmpty()) {
            segments.add(InternalSegment(curType, curPts))
        }

        return Pair(segments, gaps)
    }

    /**
     * Preserves Douglas-Peucker (4m) points as the real route geometry.
     * Uses 100m resampling ONLY to query elevation from Open-Meteo,
     * then interpolates elevation onto the real points.
     * If elevation lookup fails, stores null and never pads with a value.
     */
    private fun applyElevationToSegments(segments: List<InternalSegment>): List<TrailSegmentDto> {
        val resultSegments = mutableListOf<TrailSegmentDto>()

        for (seg in segments) {
            val realPts = seg.points
            if (realPts.isEmpty()) continue

            val km = calculateSegmentKm(realPts)

            if (seg.type == TrailSegmentType.FERRY || realPts.size < 2) {
                resultSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                        lengthKm = km
                    )
                )
                continue
            }

            // 1. Resample every 100m ONLY to look up elevation
            val resampled = mutableListOf<GeoPointDto>()
            val resampledDistances = mutableListOf<Double>()
            resampled.add(realPts.first())
            resampledDistances.add(0.0)

            var distFromLastSample = 0.0
            var totalDist = 0.0
            val targetInterval = 100.0

            for (i in 0 until realPts.size - 1) {
                val p1 = realPts[i]
                val p2 = realPts[i + 1]
                val segLen = distanceMeters(p1, p2)
                if (segLen <= 0.0) continue

                var segPos = 0.0
                while (distFromLastSample + (segLen - segPos) >= targetInterval) {
                    val needed = targetInterval - distFromLastSample
                    segPos += needed
                    val ratio = (segPos / segLen).coerceIn(0.0, 1.0)
                    val interpLat = p1.lat + ratio * (p2.lat - p1.lat)
                    val interpLng = p1.lng + ratio * (p2.lng - p1.lng)
                    totalDist += needed
                    resampled.add(GeoPointDto(interpLat, interpLng, null))
                    resampledDistances.add(totalDist)
                    distFromLastSample = 0.0
                }
                val remaining = segLen - segPos
                distFromLastSample += remaining
                totalDist += remaining
            }

            if (resampled.size < 2 || resampled.last() != realPts.last()) {
                resampled.add(realPts.last())
                resampledDistances.add(totalDist)
            }

            // 2. Query Open-Meteo for resampled coordinates
            val elevations = mutableListOf<Double>()
            var lookupFailed = false

            for (chunk in resampled.chunked(100)) {
                val lats = chunk.joinToString(",") { it.lat.toString() }
                val lons = chunk.joinToString(",") { it.lng.toString() }
                val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
                try {
                    val request = Request.Builder().url(url).header("User-Agent", "DanskSti/1.0").build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string()
                            if (!jsonStr.isNullOrEmpty()) {
                                val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                                @Suppress("UNCHECKED_CAST")
                                val eleList = root?.get("elevation") as? List<Number>
                                if (eleList != null && eleList.size == chunk.size) {
                                    for (e in eleList) {
                                        elevations.add(e.toDouble())
                                    }
                                } else {
                                    lookupFailed = true
                                }
                            } else {
                                lookupFailed = true
                            }
                        } else {
                            lookupFailed = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TrailGeometry", "Elevation fetch failed: ${e.message}")
                    lookupFailed = true
                }
                if (lookupFailed) break
            }

            if (lookupFailed || elevations.size != resampled.size) {
                // If elevation lookup fails, store null and never pad with a value
                resultSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = realPts.map { GeoPointDto(it.lat, it.lng, null) },
                        lengthKm = km
                    )
                )
                continue
            }

            // 5-point moving average smoothing on resampled elevations
            val smoothedElevations = mutableListOf<Double>()
            val windowSize = 5
            for (i in elevations.indices) {
                val start = max(0, i - windowSize / 2)
                val end = min(elevations.size - 1, i + windowSize / 2)
                val avg = elevations.subList(start, end + 1).average()
                smoothedElevations.add(avg)
            }

            // 3. Interpolate elevation onto the real Douglas-Peucker points
            val realDistances = DoubleArray(realPts.size)
            var curDist = 0.0
            realDistances[0] = 0.0
            for (i in 0 until realPts.size - 1) {
                curDist += distanceMeters(realPts[i], realPts[i + 1])
                realDistances[i + 1] = curDist
            }

            var rIdx = 0
            val finalRealPoints = realPts.mapIndexed { idx, pt ->
                val d = realDistances[idx]
                while (rIdx < resampledDistances.size - 2 && resampledDistances[rIdx + 1] < d) {
                    rIdx++
                }
                val d0 = resampledDistances[rIdx]
                val d1 = resampledDistances[rIdx + 1]
                val span = d1 - d0
                val ratio = if (span > 0.0) ((d - d0) / span).coerceIn(0.0, 1.0) else 0.0
                val ele = if (smoothedElevations.isNotEmpty()) {
                    val e0 = smoothedElevations.getOrElse(rIdx) { smoothedElevations.last() }
                    val e1 = smoothedElevations.getOrElse(rIdx + 1) { e0 }
                    e0 + ratio * (e1 - e0)
                } else null
                GeoPointDto(pt.lat, pt.lng, ele)
            }

            resultSegments.add(TrailSegmentDto(type = seg.type, points = finalRealPoints, lengthKm = km))
        }

        return resultSegments
    }

    private fun douglasPeucker(points: List<GeoPointDto>, epsilon: Double): List<GeoPointDto> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true

        val stack = java.util.ArrayDeque<Pair<Int, Int>>()
        stack.push(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (startIndex, endIndex) = stack.pop()
            if (endIndex <= startIndex + 1) continue

            val a = points[startIndex]
            val b = points[endIndex]
            val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
            val dy = (b.lat - a.lat) * 110540.0
            val denom = dx * dx + dy * dy

            var dMax = 0.0
            var maxIdx = startIndex

            for (i in startIndex + 1 until endIndex) {
                val p = points[i]
                val d = if (denom == 0.0) {
                    distanceMeters(p, a)
                } else {
                    val x = (p.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
                    val y = (p.lat - a.lat) * 110540.0
                    val t = max(0.0, min(1.0, (x * dx + y * dy) / denom))
                    val projLon = a.lng + t * (b.lng - a.lng)
                    val projLat = a.lat + t * (b.lat - a.lat)
                    distanceMeters(p.lat, p.lng, projLat, projLon)
                }
                if (d > dMax) {
                    maxIdx = i
                    dMax = d
                }
            }

            if (dMax > epsilon) {
                keep[maxIdx] = true
                stack.push(startIndex to maxIdx)
                stack.push(maxIdx to endIndex)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun distanceMeters(p1: GeoPointDto, p2: GeoPointDto): Double {
        return distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    private fun calculateSegmentKm(points: List<GeoPointDto>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total / 1000.0
    }

    private fun roundTo1Decimal(value: Double): Double {
        return Math.round(value * 10.0) / 10.0
    }
}
