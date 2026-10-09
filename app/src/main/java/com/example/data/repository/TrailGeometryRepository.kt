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

class TrailGeometryRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val geometryAdapter = moshi.adapter(TrailGeometryDto::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val trailsDir: File
        get() = File(context.filesDir, "trails").apply { if (!exists()) mkdirs() }

    fun getGeometryFile(trailId: String): File {
        return File(trailsDir, "$trailId.json.gz")
    }

    suspend fun isGeometryCached(trailId: String): Boolean = withContext(Dispatchers.IO) {
        getGeometryFile(trailId).exists()
    }

    suspend fun loadGeometry(trailId: String): TrailGeometryDto? = withContext(Dispatchers.IO) {
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

        // Fallback to bundled asset
        try {
            context.assets.open("trails/$trailId.json.gz").use { fis ->
                GZIPInputStream(fis).use { gis ->
                    gis.source().buffer().use { bufferedSource ->
                        val dto = geometryAdapter.fromJson(bufferedSource)
                        if (dto != null) {
                            saveGeometry(dto)
                            return@withContext dto
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        null
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

    suspend fun fetchAndProcessTrail(
        trailId: String,
        trailName: String,
        osmRelationId: Long?
    ): TrailGeometryDto? = withContext(Dispatchers.IO) {
        val relationId = osmRelationId ?: findRelationByName(trailName) ?: return@withContext null
        val rawElements = mutableMapOf<Long, Map<String, Any>>()
        downloadRelationRecursive(relationId, rawElements)

        if (rawElements.isEmpty()) {
            Log.e("TrailGeometry", "No elements downloaded for relation $relationId ($trailName)")
            return@withContext null
        }

        processRelationData(trailId, relationId, rawElements)
    }

    private fun findRelationByName(name: String): Long? {
        val query = """
            [out:json][timeout:60];
            area["ISO3166-1"="DK"][admin_level=2]->.dk;
            relation["type"="route"]["route"~"hiking|foot"]["name"~"$name",i](area.dk);
            out tags bb;
        """.trimIndent()

        try {
            val url = "https://overpass-api.de/api/interpreter"
            val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).header("User-Agent", "DanskSti/1.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val jsonStr = response.body?.string() ?: return null
                val root = moshi.adapter(Map::class.java).fromJson(jsonStr)
                @Suppress("UNCHECKED_CAST")
                val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()

                Log.i("TrailGeometry", "=== Overpass search matches for \"$name\" (${elements.size} found) ===")
                var bestId: Long? = null
                for (el in elements) {
                    val id = (el["id"] as? Number)?.toLong()
                    @Suppress("UNCHECKED_CAST")
                    val tags = el["tags"] as? Map<String, String> ?: emptyMap()
                    val matchName = tags["name"] ?: ""
                    val type = tags["type"] ?: ""
                    val route = tags["route"] ?: ""
                    val dist = tags["distance"] ?: ""
                    Log.i("TrailGeometry", "  ID: $id, name: \"$matchName\", type: \"$type\", route: \"$route\", dist: \"$dist\"")
                    if (bestId == null && id != null) {
                        bestId = id
                    }
                }
                return bestId
            }
        } catch (e: Exception) {
            Log.e("TrailGeometry", "Error searching relation for $name", e)
            return null
        }
    }

    private fun downloadRelationRecursive(relationId: Long, collected: MutableMap<Long, Map<String, Any>>) {
        if (collected.containsKey(relationId)) return
        val query = """
            [out:json][timeout:180];
            relation($relationId);
            out geom;
        """.trimIndent()

        for (attempt in 1..3) {
            try {
                val url = "https://overpass-api.de/api/interpreter"
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).header("User-Agent", "DanskSti/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string() ?: return
                        @Suppress("UNCHECKED_CAST")
                        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any>
                        @Suppress("UNCHECKED_CAST")
                        val elements = root?.get("elements") as? List<Map<String, Any>> ?: emptyList()
                        for (el in elements) {
                            val id = (el["id"] as? Number)?.toLong()
                            val type = el["type"] as? String
                            if (id != null && type == "relation") {
                                collected[id] = el
                                @Suppress("UNCHECKED_CAST")
                                val members = el["members"] as? List<Map<String, Any>> ?: emptyList()
                                for (m in members) {
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
            } catch (e: Exception) {
                Log.w("TrailGeometry", "Attempt $attempt failed for relation $relationId: ${e.message}")
                if (attempt == 3) return
                Thread.sleep((attempt * 2000).toLong())
            }
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

        // Resample every 100m and fetch elevation from Open-Meteo (Copernicus DEM)
        val finalSegments = resampleAndFetchElevation(simplifiedMain)
        val finalSideBranches = resampleAndFetchElevation(simplifiedSide)

        var totalWalkingKm = 0.0
        var totalAscent = 0.0
        var totalDescent = 0.0
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0

        for (seg in finalSegments) {
            if (seg.type == TrailSegmentType.WALK) {
                totalWalkingKm += seg.lengthKm
                val pts = seg.points
                for (i in 0 until pts.size - 1) {
                    val diff = pts[i + 1].ele - pts[i].ele
                    if (diff > 3.0) {
                        totalAscent += diff
                    } else if (diff < -3.0) {
                        totalDescent += -diff
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
            totalAscentM = totalAscent.toInt(),
            totalDescentM = totalDescent.toInt(),
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

    private fun resampleAndFetchElevation(segments: List<InternalSegment>): List<TrailSegmentDto> {
        val resampledSegments = mutableListOf<TrailSegmentDto>()

        for (seg in segments) {
            val pts = seg.points
            if (pts.isEmpty()) continue

            if (seg.type == TrailSegmentType.FERRY || pts.size < 2) {
                resampledSegments.add(
                    TrailSegmentDto(
                        type = seg.type,
                        points = pts.map { GeoPointDto(it.lat, it.lng, 0.0) },
                        lengthKm = calculateSegmentKm(pts)
                    )
                )
                continue
            }

            val resampled = mutableListOf<GeoPointDto>()
            resampled.add(pts.first())
            var accumulatedDist = 0.0
            val targetInterval = 100.0 // 100 meters
            var nextTarget = targetInterval

            for (i in 0 until pts.size - 1) {
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val d = distanceMeters(p1, p2)
                var currentSegmentCovered = 0.0

                while (d > 0.0 && currentSegmentCovered + d >= nextTarget - accumulatedDist) {
                    val rem = nextTarget - accumulatedDist
                    val ratio = (rem / d).coerceIn(0.0, 1.0)
                    val interpLat = p1.lat + ratio * (p2.lat - p1.lat)
                    val interpLng = p1.lng + ratio * (p2.lng - p1.lng)
                    resampled.add(GeoPointDto(interpLat, interpLng, 0.0))
                    currentSegmentCovered += rem
                    accumulatedDist = nextTarget
                    nextTarget += targetInterval
                }
                accumulatedDist += d - currentSegmentCovered
            }
            if (resampled.last() != pts.last()) {
                resampled.add(pts.last())
            }

            val elevations = mutableListOf<Double>()
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
                                if (eleList != null) {
                                    for (e in eleList) {
                                        elevations.add(e.toDouble())
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TrailGeometry", "Elevation fetch failed: ${e.message}")
                }
            }

            while (elevations.size < resampled.size) {
                elevations.add(elevations.lastOrNull() ?: 0.0)
            }

            // 5-point moving average smoothing
            val smoothedElevations = mutableListOf<Double>()
            val windowSize = 5
            for (i in elevations.indices) {
                val start = max(0, i - windowSize / 2)
                val end = min(elevations.size - 1, i + windowSize / 2)
                val avg = elevations.subList(start, end + 1).average()
                smoothedElevations.add(avg)
            }

            val finalPts = resampled.indices.map { i ->
                GeoPointDto(resampled[i].lat, resampled[i].lng, smoothedElevations.getOrElse(i) { 0.0 })
            }

            val km = calculateSegmentKm(finalPts)
            resampledSegments.add(TrailSegmentDto(type = seg.type, points = finalPts, lengthKm = km))
        }

        return resampledSegments
    }

    private fun douglasPeucker(points: List<GeoPointDto>, epsilon: Double): List<GeoPointDto> {
        if (points.size <= 2) return points
        var dMax = 0.0
        var index = 0
        val a = points.first()
        val b = points.last()

        val dx = (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)) * 111320.0
        val dy = (b.lat - a.lat) * 110540.0
        val denom = dx * dx + dy * dy

        for (i in 1 until points.size - 1) {
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
                index = i
                dMax = d
            }
        }

        return if (dMax > epsilon) {
            val rec1 = douglasPeucker(points.subList(0, index + 1), epsilon)
            val rec2 = douglasPeucker(points.subList(index, points.size), epsilon)
            rec1.dropLast(1) + rec2
        } else {
            listOf(a, b)
        }
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
