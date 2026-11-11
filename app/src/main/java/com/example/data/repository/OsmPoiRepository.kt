package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.util.network.applyDebugMainThreadCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class OsmPoiResult(
    val shelters: List<ShelterEntity> = emptyList(),
    val pois: List<PoiEntity> = emptyList()
)

class OsmPoiRepository(private val context: Context) {
    companion object {
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .applyDebugMainThreadCheck()
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "osm_pois").apply { if (!exists()) mkdirs() }

    suspend fun fetchPoisForTrail(
        trailId: String,
        relationId: Long,
        childRelationIds: List<Long> = emptyList()
    ): OsmPoiResult = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "$trailId.json")

        // 1. Try Overpass API if relationId > 0 via shared OverpassClient
        if (relationId > 0L) {
            try {
                val query = buildOverpassQuery(relationId, childRelationIds)
                val jsonStr = com.example.data.remote.OverpassClient.queryRawJson(
                    query = query,
                    label = "OsmPoiTrail($trailId)"
                )
                if (jsonStr.isNotBlank()) {
                    cacheFile.writeText(jsonStr)
                    val parsed = parseOverpassJson(jsonStr)
                    if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                        return@withContext parsed
                    }
                }
            } catch (e: Exception) {
                Log.w("OsmPoiRepo", "Overpass query failed for $trailId: ${e.message}")
            }
        }

        // 2. Fallback to cached file
        if (cacheFile.exists()) {
            try {
                val parsed = parseOverpassJson(cacheFile.readText())
                if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                    return@withContext parsed
                }
            } catch (e: Exception) {
                Log.e("OsmPoiRepo", "Failed to parse cached OSM POIs: ${e.message}", e)
            }
        }

        // 3. Fallback to bundled asset
        loadBundledPois(trailId)
    }

    fun loadBundledPois(trailId: String = "trail_ohavsstien"): OsmPoiResult {
        val assetJson = readAssetOsmPois(trailId)
        if (assetJson != null) {
            try {
                val parsed = parseOverpassJson(assetJson)
                if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                    return parsed
                }
            } catch (e: Exception) {
                Log.e("OsmPoiRepo", "Failed to parse asset OSM POIs: ${e.message}", e)
            }
        }
        return OsmPoiResult()
    }

    private fun readAssetOsmPois(trailId: String): String? {
        return try {
            context.assets.open("osm_pois/$trailId.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    var customPolylineChunkFetcher: (suspend (simplifiedChunk: List<GeoPointDto>) -> OsmPoiResult)? = null

    /**
     * Requirement B & Round 12A Item 8:
     * Fetch POIs along a custom route polyline using Overpass around:<m>,lat1,lon1,lat2,lon2,...
     * Splits the corridor POI query into chunks of about 50 km (with 3 km overlap),
     * simplifies each chunk to about 1 point per 200 m, and merges the results without duplicates.
     */
    suspend fun fetchPoisForPolyline(points: List<GeoPointDto>): OsmPoiResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext OsmPoiResult()

        val chunks = splitPolylineIntoChunks(points, chunkLengthKm = 50.0, overlapKm = 3.0)
        val mergedShelters = LinkedHashMap<String, ShelterEntity>()
        val mergedPois = LinkedHashMap<String, PoiEntity>()

        for ((chunkIdx, chunkPoints) in chunks.withIndex()) {
            val simplified = simplifyPointsForCorridor(chunkPoints, minDistanceMeters = 200.0)
            if (simplified.isEmpty()) continue

            val chunkResult = try {
                val customFetcher = customPolylineChunkFetcher
                if (customFetcher != null) {
                    customFetcher(simplified)
                } else {
                    val query = buildOverpassPolylineQuery(simplified)
                    val body = com.example.data.remote.OverpassClient.queryRawJson(
                        query = query,
                        label = "OsmPoiPolyline(chunk=${chunkIdx + 1}/${chunks.size},pts=${simplified.size})"
                    )
                    parseOverpassJson(body)
                }
            } catch (e: Exception) {
                Log.w("OsmPoiRepo", "Overpass polyline corridor chunk ${chunkIdx + 1}/${chunks.size} failed: ${e.message}")
                OsmPoiResult()
            }

            for (shelter in chunkResult.shelters) {
                val isDuplicate = mergedShelters.containsKey(shelter.id) || mergedShelters.values.any { existing ->
                    com.example.util.GeoUtils.distanceMeters(existing.lat, existing.lng, shelter.lat, shelter.lng) <= 20.0
                }
                if (!isDuplicate) {
                    mergedShelters[shelter.id] = shelter
                }
            }

            for (poi in chunkResult.pois) {
                val isDuplicate = mergedPois.containsKey(poi.id) || mergedPois.values.any { existing ->
                    existing.type == poi.type &&
                        com.example.util.GeoUtils.distanceMeters(existing.lat, existing.lng, poi.lat, poi.lng) <= 20.0
                }
                if (!isDuplicate) {
                    mergedPois[poi.id] = poi
                }
            }
        }

        OsmPoiResult(
            shelters = mergedShelters.values.toList(),
            pois = mergedPois.values.toList()
        )
    }

    /**
     * Round 12A Item 8:
     * Splits a polyline into chunks of [chunkLengthKm] (default 50 km) with [overlapKm] (default 3 km) overlap.
     */
    fun splitPolylineIntoChunks(
        points: List<GeoPointDto>,
        chunkLengthKm: Double = 50.0,
        overlapKm: Double = 3.0
    ): List<List<GeoPointDto>> {
        if (points.size <= 2) return if (points.isEmpty()) emptyList() else listOf(points)

        val cumKm = DoubleArray(points.size)
        var accKm = 0.0
        cumKm[0] = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            accKm += com.example.util.GeoUtils.distanceMeters(prev.lat, prev.lng, curr.lat, curr.lng) / 1000.0
            cumKm[i] = accKm
        }

        val totalKm = cumKm.last()
        if (totalKm <= chunkLengthKm + 1e-6) {
            return listOf(points)
        }

        val stepKm = (chunkLengthKm - overlapKm).coerceAtLeast(1.0)
        val chunks = mutableListOf<List<GeoPointDto>>()

        fun interpolateAtKm(targetKm: Double): GeoPointDto {
            val clamped = targetKm.coerceIn(0.0, totalKm)
            if (clamped <= 0.0) return points.first()
            if (clamped >= totalKm) return points.last()
            var segIdx = 0
            while (segIdx < cumKm.size - 2 && cumKm[segIdx + 1] < clamped) {
                segIdx++
            }
            val startD = cumKm[segIdx]
            val endD = cumKm[segIdx + 1]
            val span = endD - startD
            if (span <= 1e-9) return points[segIdx]
            val t = ((clamped - startD) / span).coerceIn(0.0, 1.0)
            val p0 = points[segIdx]
            val p1 = points[segIdx + 1]
            return GeoPointDto(
                lat = p0.lat + (p1.lat - p0.lat) * t,
                lng = p0.lng + (p1.lng - p0.lng) * t,
                ele = if (p0.ele != null && p1.ele != null) p0.ele + (p1.ele - p0.ele) * t else p0.ele ?: p1.ele
            )
        }

        var startKm = 0.0
        while (startKm < totalKm - 1e-6) {
            val endKm = kotlin.math.min(totalKm, startKm + chunkLengthKm)
            val chunk = mutableListOf<GeoPointDto>()
            chunk.add(interpolateAtKm(startKm))
            for (i in points.indices) {
                val d = cumKm[i]
                if (d > startKm + 1e-5 && d < endKm - 1e-5) {
                    chunk.add(points[i])
                }
            }
            chunk.add(interpolateAtKm(endKm))
            if (chunk.size >= 2) {
                chunks.add(chunk)
            }
            if (endKm >= totalKm - 1e-6) break
            startKm += stepKm
        }

        return if (chunks.isEmpty()) listOf(points) else chunks
    }

    fun simplifyPointsForCorridor(points: List<GeoPointDto>, minDistanceMeters: Double = 200.0): List<GeoPointDto> {
        if (points.size <= 2) return points
        val simplified = mutableListOf<GeoPointDto>()
        simplified.add(points.first())
        var lastAdded = points.first()

        for (i in 1 until points.size - 1) {
            val pt = points[i]
            val d = com.example.util.GeoUtils.distanceMeters(lastAdded.lat, lastAdded.lng, pt.lat, pt.lng)
            if (d >= minDistanceMeters) {
                simplified.add(pt)
                lastAdded = pt
            }
        }
        simplified.add(points.last())
        return simplified
    }

    fun buildOverpassPolylineQuery(simplifiedPoints: List<GeoPointDto>): String {
        val coordStr = simplifiedPoints.joinToString(",") { "${it.lat},${it.lng}" }
        return """
            [out:json][timeout:120];
            (
              nwr["shop"~"^(supermarket|convenience|bakery|farm|kiosk|greengrocer)$"](around:2000,$coordStr);
              nwr["amenity"="drinking_water"](around:3000,$coordStr);
              nwr["man_made"="water_tap"]["drinking_water"="yes"](around:3000,$coordStr);
              nwr["amenity"="toilets"]["access"!~"private|customers"](around:3000,$coordStr);
              nwr["amenity"="shelter"]["shelter_type"~"^(basic_hut|lean_to|weather_shelter)$"](around:3000,$coordStr);
              nwr["tourism"~"^(camp_site|wilderness_hut)$"](around:3000,$coordStr);
              nwr["railway"="station"](around:3000,$coordStr);
              nwr["amenity"="ferry_terminal"](around:3000,$coordStr);
            );
            out center tags;
        """.trimIndent()
    }

    private fun buildOverpassQuery(relationId: Long, childRelationIds: List<Long>): String {
        val relClauses = (listOf(relationId) + childRelationIds).joinToString("\n") { "relation($it);" }
        return """
            [out:json][timeout:120];
            (
              $relClauses
            )->.super;
            (
              rel(r.super);
              .super;
            );
            way(r)->.route;
            (
              nwr["shop"~"^(supermarket|convenience|bakery|farm|kiosk|greengrocer)$"](around.route:2000);
              nwr["amenity"="drinking_water"](around.route:3000);
              nwr["man_made"="water_tap"]["drinking_water"="yes"](around.route:3000);
              nwr["amenity"="toilets"]["access"!~"private|customers"](around.route:3000);
              nwr["amenity"="shelter"]["shelter_type"~"^(basic_hut|lean_to|weather_shelter)$"](around.route:3000);
              nwr["tourism"~"^(camp_site|wilderness_hut)$"](around.route:3000);
              nwr["railway"="station"](around.route:3000);
              nwr["amenity"="ferry_terminal"](around.route:3000);
            );
            out center tags;
        """.trimIndent()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseOverpassJson(jsonStr: String): OsmPoiResult {
        val root = try {
            moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return OsmPoiResult()
        } catch (e: Exception) {
            return OsmPoiResult()
        }

        val elements = root["elements"] as? List<Map<String, Any>> ?: return OsmPoiResult()
        val shelters = mutableListOf<ShelterEntity>()
        val pois = mutableListOf<PoiEntity>()

        for (el in elements) {
            val type = el["type"] as? String ?: continue
            val id = (el["id"] as? Number)?.toLong() ?: continue
            val tags = el["tags"] as? Map<String, String> ?: emptyMap()

            // Coordinates: lat/lon for nodes, center.lat/center.lon for ways/relations
            val lat: Double
            val lng: Double
            if (type == "node") {
                lat = (el["lat"] as? Number)?.toDouble() ?: continue
                lng = (el["lon"] as? Number)?.toDouble() ?: continue
            } else {
                val center = el["center"] as? Map<String, Any> ?: continue
                lat = (center["lat"] as? Number)?.toDouble() ?: continue
                lng = (center["lon"] as? Number)?.toDouble() ?: continue
            }

            val name = tags["name"]?.trim()
            val openingHours = tags["opening_hours"]?.trim()
            val operator = tags["operator"]?.trim()
            val website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.trim()
            val fee = tags["fee"]?.equals("yes", ignoreCase = true) == true
            val access = tags["access"]?.trim()
            val now = System.currentTimeMillis()

            val amenity = tags["amenity"]
            val shop = tags["shop"]
            val tourism = tags["tourism"]
            val railway = tags["railway"]
            val manMade = tags["man_made"]

            // 1. Shelter or Camp site
            if (amenity == "shelter" || tourism == "camp_site" || tourism == "wilderness_hut") {
                val shelterType = tags["shelter_type"] ?: if (tourism == "camp_site") "Teltplads" else "Shelter"
                val cap = tags["capacity"]?.toIntOrNull()
                shelters.add(
                    ShelterEntity(
                        id = "osm_${type}_$id",
                        name = if (!name.isNullOrBlank()) name else if (tourism == "camp_site") "Teltplads (OSM)" else "Shelter (OSM)",
                        lat = lat,
                        lng = lng,
                        description = tags["description"]?.trim() ?: tags["note"]?.trim(),
                        bookingRequired = tags["reservation"]?.equals("required", ignoreCase = true) == true,
                        ownership = operator,
                        capacity = cap,
                        hasWaterNearby = false,
                        hasToiletNearby = false,
                        source = "osm",
                        bookingUrl = website,
                        shelterType = shelterType,
                        fee = fee,
                        fetchedAt = now
                    )
                )
            }
            // 2. Shop
            else if (!shop.isNullOrBlank()) {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "shop",
                        name = if (!name.isNullOrBlank()) name else when (shop) {
                            "supermarket" -> "Supermarked"
                            "bakery" -> "Bageri"
                            "convenience" -> "Kiosk / Nærbutik"
                            "farm" -> "Gårdbutik"
                            else -> "Butik ($shop)"
                        },
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = false,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
            // 3. Drinking water
            else if (amenity == "drinking_water" || (manMade == "water_tap" && tags["drinking_water"] == "yes")) {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "water",
                        name = if (!name.isNullOrBlank()) name else "Drikkevandspost",
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = fee,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
            // 4. Toilet
            else if (amenity == "toilets") {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "toilet",
                        name = if (!name.isNullOrBlank()) name else "Offentligt toilet",
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = fee,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
            // 5. Station
            else if (railway == "station" || railway == "halt") {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "station",
                        name = if (!name.isNullOrBlank()) name else "Togstation",
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = false,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
            // 6. Ferry Terminal
            else if (amenity == "ferry_terminal") {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "ferry",
                        name = if (!name.isNullOrBlank()) name else "Færgeleje",
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = fee,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
        }

        return OsmPoiResult(shelters = shelters, pois = pois)
    }
}
