package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "osm_pois").apply { if (!exists()) mkdirs() }

    suspend fun fetchPoisForTrail(
        trailId: String,
        relationId: Long,
        childRelationIds: List<Long> = emptyList()
    ): OsmPoiResult = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "$trailId.json")

        // 1. Try Overpass API if relationId > 0
        if (relationId > 0L) {
            try {
                val query = buildOverpassQuery(relationId, childRelationIds)
                val body = query.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://overpass-api.de/api/interpreter")
                    .post(body)
                    .header("User-Agent", "DanskSti/1.0 (Android Hiking App)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string()
                        if (!jsonStr.isNullOrBlank()) {
                            cacheFile.writeText(jsonStr)
                            val parsed = parseOverpassJson(jsonStr)
                            if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                                return@withContext parsed
                            }
                        }
                    } else {
                        Log.w("OsmPoiRepo", "Overpass returned code ${response.code} for trail $trailId")
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
        val assetJson = readAssetOsmPois(trailId)
        if (assetJson != null) {
            try {
                val parsed = parseOverpassJson(assetJson)
                if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                    return@withContext parsed
                }
            } catch (e: Exception) {
                Log.e("OsmPoiRepo", "Failed to parse asset OSM POIs: ${e.message}", e)
            }
        }

        OsmPoiResult()
    }

    private fun readAssetOsmPois(trailId: String): String? {
        return try {
            context.assets.open("osm_pois/$trailId.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Requirement B: Fetch POIs along a custom route polyline using Overpass around:<m>,lat1,lon1,lat2,lon2,...
     * Simplifies the route to about 1 point per 200 m, uses the exact same filters as Phase 2, and sends as POST.
     */
    suspend fun fetchPoisForPolyline(points: List<GeoPointDto>): OsmPoiResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext OsmPoiResult()

        val simplified = simplifyPointsForCorridor(points, minDistanceMeters = 200.0)
        val query = buildOverpassPolylineQuery(simplified)

        for (endpoint in OVERPASS_ENDPOINTS) {
            try {
                val requestBody = query.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "DanskSti/1.8 (https://dansksti.app; contact@dansksti.app)")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val parsed = parseOverpassJson(body)
                        if (parsed.shelters.isNotEmpty() || parsed.pois.isNotEmpty()) {
                            return@withContext parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("OsmPoiRepo", "Overpass polyline corridor query failed on $endpoint: ${e.message}")
            }
        }

        OsmPoiResult()
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
            [out:json][timeout:180];
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
            [out:json][timeout:180];
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
