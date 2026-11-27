package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class OsmPoiResult(
    val shelters: List<ShelterEntity> = emptyList(),
    val pois: List<PoiEntity> = emptyList()
)

class OsmPoiRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val cacheDir: File
        get() = File(context.filesDir, "osm_pois").apply { if (!exists()) mkdirs() }

    private val resultAdapter = moshi.adapter(OsmPoiResult::class.java)

    suspend fun fetchPoisForTrail(
        trailId: String,
        points: List<GeoPointDto> = emptyList()
    ): OsmPoiResult = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "$trailId.json")

        val effectivePoints = if (points.isNotEmpty()) {
            points
        } else {
            TrailGeometryRepository(context).loadGeometry(trailId)
                ?.segments
                ?.flatMap { it.points }
                .orEmpty()
        }

        // 1. Query POIs along the trail's downloaded geometry polyline
        if (effectivePoints.isNotEmpty()) {
            try {
                val liveResult = fetchPoisForPolyline(effectivePoints)
                if (liveResult.shelters.isNotEmpty() || liveResult.pois.isNotEmpty()) {
                    runCatching { cacheFile.writeText(resultAdapter.toJson(liveResult)) }
                    return@withContext liveResult
                }
            } catch (e: Exception) {
                Log.w("OsmPoiRepo", "Overpass polyline POI query failed for $trailId: ${e.message}")
            }
        }

        // 2. Fallback to cached file
        if (cacheFile.exists()) {
            try {
                val cachedText = cacheFile.readText()
                val parsed = if (cachedText.contains("\"elements\"")) {
                    parseOverpassJson(cachedText)
                } else {
                    resultAdapter.fromJson(cachedText) ?: OsmPoiResult()
                }
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

    @VisibleForTesting
    internal var customPolylineChunkFetcher: (suspend (simplifiedChunk: List<GeoPointDto>) -> OsmPoiResult)? = null

    /**
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
            [out:json][timeout:60][maxsize:67108864];
            (
              nwr["shop"~"^(supermarket|convenience|bakery|farm|kiosk|greengrocer)$"](around:2000,$coordStr);
              nwr["amenity"="drinking_water"](around:3000,$coordStr);
              nwr["man_made"="water_tap"]["drinking_water"="yes"](around:3000,$coordStr);
              nwr["amenity"="toilets"]["access"!~"private|customers"](around:3000,$coordStr);
              nwr["amenity"="shelter"]["shelter_type"~"^(basic_hut|lean_to|weather_shelter)$"](around:3000,$coordStr);
              nwr["tourism"~"^(camp_site|wilderness_hut)$"](around:3000,$coordStr);
              nwr["highway"="bus_stop"](around:1500,$coordStr);
              nwr["public_transport"="platform"]["bus"="yes"](around:1500,$coordStr);
              nwr["railway"~"^(station|halt)$"](around:3000,$coordStr);
              nwr["amenity"="ferry_terminal"](around:3000,$coordStr);
            );
            out center tags;
        """.trimIndent()
    }

    private fun yesNoTag(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "yes" -> true
        "no" -> false
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun parseOverpassJson(jsonStr: String): OsmPoiResult {
        val root = try {
            moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return OsmPoiResult()
        } catch (e: Exception) {
            return OsmPoiResult()
        }

        val elements = root["elements"] as? List<Map<String, Any>> ?: return OsmPoiResult()
        val shelters = mutableListOf<ShelterEntity>()
        val pois = mutableListOf<PoiEntity>()
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val defaultBusStopName = runCatching {
            localizedCtx.getString(com.example.R.string.osm_default_bus_stop_name)
        }.getOrDefault("Busstoppested")
        val primitiveCampName = runCatching {
            localizedCtx.getString(com.example.R.string.osm_shelter_type_primitive_camp)
        }.getOrDefault("Primitiv teltplads")

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
            val seasonal = tags["seasonal"]?.trim()
            val operator = tags["operator"]?.trim()
            val website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.trim()
            val feeTag = tags["fee"]?.trim()?.lowercase()
            val feeNullable: Boolean? = when (feeTag) {
                "yes" -> true
                "no" -> false
                else -> null
            }
            val feeBool = feeNullable == true
            val hasFireRing: Boolean? = when (tags["fireplace"]?.trim()?.lowercase()) {
                "yes" -> true
                "no" -> false
                else -> null
            }
            val dogAllowed: Boolean? = when (tags["dog"]?.trim()?.lowercase()) {
                "yes", "leashed" -> true
                "no" -> false
                else -> null
            }
            val access = tags["access"]?.trim()
            val now = System.currentTimeMillis()

            val amenity = tags["amenity"]
            val shop = tags["shop"]
            val tourism = tags["tourism"]
            val railway = tags["railway"]
            val manMade = tags["man_made"]
            val highway = tags["highway"]
            val publicTransport = tags["public_transport"]
            val bus = tags["bus"]

            // 1. Shelter or Camp site
            if (amenity == "shelter" || tourism == "camp_site" || tourism == "wilderness_hut") {
                val shelterType = if (tags["backcountry"]?.trim()?.lowercase() == "yes") {
                    primitiveCampName
                } else {
                    tags["shelter_type"] ?: if (tourism == "camp_site") "Teltplads" else "Shelter"
                }
                val cap = tags["capacity"]?.toIntOrNull()
                val isBookingReq = tags["reservation"]?.equals("required", ignoreCase = true) == true
                shelters.add(
                    ShelterEntity(
                        id = "osm_${type}_$id",
                        name = if (!name.isNullOrBlank()) name else if (tourism == "camp_site") "Teltplads (OSM)" else "Shelter (OSM)",
                        lat = lat,
                        lng = lng,
                        description = tags["description"]?.trim() ?: tags["note"]?.trim(),
                        bookingRequired = isBookingReq,
                        ownership = operator,
                        capacity = cap,
                        hasWaterNearby = false,
                        hasToiletNearby = false,
                        source = "osm",
                        bookingUrl = if (isBookingReq) website else null,
                        infoUrl = if (!isBookingReq) website else null,
                        hasFireRing = hasFireRing,
                        dogAllowed = dogAllowed,
                        shelterType = shelterType,
                        fee = feeNullable,
                        fetchedAt = now,
                        // Phase C2: OSM facility tags
                        hasOwnTap = yesNoTag(tags["drinking_water"]),
                        hasOwnToilet = yesNoTag(tags["toilets"]),
                        hasShower = when (tags["shower"]?.trim()?.lowercase()) {
                            "yes", "hot" -> true
                            "no" -> false
                            else -> null
                        },
                        hasElectricity = if (tags["power_supply"]?.trim()?.lowercase() == "yes") true else null
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
                        fee = feeBool,
                        openingHours = openingHours,
                        seasonal = seasonal,
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
                        fee = feeBool,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
            // 5. Bus stop (before station branch)
            else if (highway == "bus_stop" || (publicTransport == "platform" && bus == "yes")) {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "bus_stop",
                        name = if (!name.isNullOrBlank()) name else defaultBusStopName,
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
            // 6. Station or Halt
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
            // 7. Ferry Terminal
            else if (amenity == "ferry_terminal") {
                pois.add(
                    PoiEntity(
                        id = "osm_${type}_$id",
                        type = "ferry",
                        name = if (!name.isNullOrBlank()) name else "Færgeleje",
                        lat = lat,
                        lng = lng,
                        access = access,
                        fee = feeBool,
                        openingHours = openingHours,
                        operator = operator,
                        website = website,
                        source = "osm",
                        fetchedAt = now
                    )
                )
            }
        }

        // Merge bus stops with the same name within 60 m (the two sides of a road) into one
        val mergedPois = mutableListOf<PoiEntity>()
        for (poi in pois) {
            if (poi.type == "bus_stop") {
                val normName = poi.name.trim().lowercase()
                val existingDuplicate = mergedPois.any { existing ->
                    existing.type == "bus_stop" &&
                        existing.name.trim().lowercase() == normName &&
                        com.example.util.GeoUtils.distanceMeters(existing.lat, existing.lng, poi.lat, poi.lng) <= 60.0
                }
                if (!existingDuplicate) {
                    mergedPois.add(poi)
                }
            } else {
                mergedPois.add(poi)
            }
        }

        return OsmPoiResult(shelters = shelters, pois = mergedPois)
    }

    // ---------------------------------------------------------------------------------------
    // Phase C1: "extras" – cafés, likely water, highlights, beaches, AEDs, pharmacies, hostels.
    // Separate query, parser and cache, so these never pass through parseOverpassJson (whose
    // amenity=shelter branch would turn picnic shelters into overnight shelters).
    // ---------------------------------------------------------------------------------------

    @VisibleForTesting
    internal var customExtrasChunkFetcher: (suspend (simplifiedChunk: List<GeoPointDto>) -> List<PoiEntity>)? = null

    private val poiListAdapter = moshi.adapter<List<PoiEntity>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, PoiEntity::class.java)
    )

    fun buildOverpassExtrasQuery(simplifiedPoints: List<GeoPointDto>): String {
        val c = simplifiedPoints.joinToString(",") { "${it.lat},${it.lng}" }
        return """
            [out:json][timeout:60][maxsize:67108864];
            (
              nwr["amenity"~"^(cafe|restaurant|fast_food|ice_cream)$"](around:1000,$c);
              nwr["amenity"="fuel"](around:1500,$c);
              nwr["amenity"="water_point"](around:2000,$c);
              nwr["natural"="spring"]["drinking_water"="yes"](around:2000,$c);
              nwr["amenity"="grave_yard"](around:1000,$c);
              nwr["landuse"="cemetery"](around:1000,$c);
              nwr["leisure"="firepit"](around:500,$c);
              nwr["amenity"="bbq"](around:500,$c);
              nwr["tourism"="picnic_site"](around:500,$c);
              nwr["amenity"="shelter"]["shelter_type"="picnic_shelter"](around:500,$c);
              nwr["tourism"="viewpoint"](around:500,$c);
              nwr["man_made"="tower"]["tower:type"="observation"](around:1000,$c);
              nwr["leisure"="bird_hide"](around:500,$c);
              nwr["historic"~"^(archaeological_site|tumulus|ruins|castle|manor|rune_stone|monument)$"](around:300,$c);
              nwr["historic"="memorial"]["name"](around:300,$c);
              nwr["natural"="beach"](around:500,$c);
              nwr["leisure"~"^(bathing_place|swimming_area)$"](around:500,$c);
              nwr["emergency"="defibrillator"](around:1500,$c);
              nwr["amenity"="pharmacy"](around:3000,$c);
              nwr["tourism"~"^(hostel|guest_house|hotel|chalet)$"](around:2000,$c);
            );
            out center tags;
        """.trimIndent()
    }

    /**
     * Fetches extras along [points] (same 50 km chunking and 200 m simplification as
     * [fetchPoisForPolyline]) at BACKGROUND priority. Live first, then the per-id cache
     * `filesDir/osm_pois/<cacheId>_extras.json`, then empty. Pass an empty [points] to skip
     * the network (offline).
     */
    suspend fun fetchExtrasForPolyline(cacheId: String, points: List<GeoPointDto>): List<PoiEntity> = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "${cacheId}_extras.json")
        if (points.isNotEmpty()) {
            val chunks = splitPolylineIntoChunks(points, chunkLengthKm = 50.0, overlapKm = 3.0)
            val merged = mutableListOf<PoiEntity>()
            var anyChunkSucceeded = false
            for ((chunkIdx, chunkPoints) in chunks.withIndex()) {
                val simplified = simplifyPointsForCorridor(chunkPoints, minDistanceMeters = 200.0)
                if (simplified.isEmpty()) continue
                try {
                    val customFetcher = customExtrasChunkFetcher
                    val chunkPois = if (customFetcher != null) {
                        customFetcher(simplified)
                    } else {
                        val body = com.example.data.remote.OverpassClient.queryRawJson(
                            query = buildOverpassExtrasQuery(simplified),
                            label = "OsmPoiExtras(chunk=${chunkIdx + 1}/${chunks.size},pts=${simplified.size})",
                            priority = com.example.data.remote.OverpassClient.Priority.BACKGROUND
                        )
                        parseExtrasOverpassJson(body)
                    }
                    anyChunkSucceeded = true
                    merged.addAll(chunkPois)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("OsmPoiRepo", "Overpass extras chunk ${chunkIdx + 1}/${chunks.size} failed: ${e.message}")
                }
            }
            if (anyChunkSucceeded) {
                val result = dedupeExtras(merged, unnamedIds = null)
                runCatching { cacheFile.writeText(poiListAdapter.toJson(result)) }
                return@withContext result
            }
        }
        if (cacheFile.exists()) {
            try {
                return@withContext poiListAdapter.fromJson(cacheFile.readText()).orEmpty()
            } catch (e: Exception) {
                Log.w("OsmPoiRepo", "Failed to parse cached extras for $cacheId: ${e.message}")
            }
        }
        emptyList()
    }

    /** Keys copied into detailsJson when present (Phase C1 whitelist). */
    private val extrasDetailKeys = listOf(
        "wikipedia", "wikidata", "wheelchair", "defibrillator:location", "indoor", "access",
        "fee", "cuisine", "outdoor_seating", "inscription", "heritage"
    )

    private data class ExtraKind(val type: String, val subtype: String, val fallbackRes: Int, val fallbackDa: String)

    private fun classifyExtra(tags: Map<String, String>): ExtraKind? {
        val amenity = tags["amenity"]
        val leisure = tags["leisure"]
        val tourism = tags["tourism"]
        val historic = tags["historic"]
        return when {
            amenity == "cafe" -> ExtraKind("cafe", "cafe", com.example.R.string.osm_extra_name_cafe, "Café")
            amenity == "restaurant" -> ExtraKind("cafe", "restaurant", com.example.R.string.osm_extra_name_restaurant, "Restaurant")
            amenity == "fast_food" -> ExtraKind("cafe", "fast_food", com.example.R.string.osm_extra_name_fast_food, "Grillbar")
            amenity == "ice_cream" -> ExtraKind("cafe", "ice_cream", com.example.R.string.osm_extra_name_ice_cream, "Is")
            amenity == "fuel" -> ExtraKind("shop", "fuel", com.example.R.string.osm_extra_name_fuel, "Tankstation")
            amenity == "water_point" -> ExtraKind("water", "water_point", com.example.R.string.osm_extra_name_water_point, "Vandpost")
            tags["natural"] == "spring" && tags["drinking_water"] == "yes" ->
                ExtraKind("water", "spring", com.example.R.string.osm_extra_name_spring, "Kilde (drikkevand)")
            amenity == "grave_yard" || tags["landuse"] == "cemetery" -> {
                val access = tags["access"]?.lowercase()
                if (access == "private" || access == "no") null
                else ExtraKind("water_likely", "churchyard", com.example.R.string.osm_extra_name_churchyard, "Kirkegård")
            }
            leisure == "firepit" -> ExtraKind("firepit", "firepit", com.example.R.string.osm_extra_name_firepit, "Bålplads")
            amenity == "bbq" -> ExtraKind("firepit", "bbq", com.example.R.string.osm_extra_name_bbq, "Grillplads")
            tourism == "picnic_site" -> ExtraKind("picnic", "picnic_site", com.example.R.string.osm_extra_name_picnic_site, "Rasteplads")
            amenity == "shelter" && tags["shelter_type"] == "picnic_shelter" ->
                ExtraKind("picnic", "madpakkehus", com.example.R.string.osm_extra_name_madpakkehus, "Madpakkehus")
            tourism == "viewpoint" -> ExtraKind("highlight", "viewpoint", com.example.R.string.osm_extra_name_viewpoint, "Udsigtspunkt")
            tags["man_made"] == "tower" && tags["tower:type"] == "observation" ->
                ExtraKind("highlight", "observation_tower", com.example.R.string.osm_extra_name_observation_tower, "Udsigtstårn")
            leisure == "bird_hide" -> ExtraKind("highlight", "bird_hide", com.example.R.string.osm_extra_name_bird_hide, "Fugleskjul")
            historic != null -> when (historic) {
                "archaeological_site" -> ExtraKind("highlight", "historic_archaeological_site", com.example.R.string.osm_extra_name_historic_archaeological_site, "Fortidsminde")
                "tumulus" -> ExtraKind("highlight", "historic_tumulus", com.example.R.string.osm_extra_name_historic_tumulus, "Gravhøj")
                "ruins" -> ExtraKind("highlight", "historic_ruins", com.example.R.string.osm_extra_name_historic_ruins, "Ruin")
                "castle" -> ExtraKind("highlight", "historic_castle", com.example.R.string.osm_extra_name_historic_castle, "Slot")
                "manor" -> ExtraKind("highlight", "historic_manor", com.example.R.string.osm_extra_name_historic_manor, "Herregård")
                "rune_stone" -> ExtraKind("highlight", "historic_rune_stone", com.example.R.string.osm_extra_name_historic_rune_stone, "Runesten")
                "monument" -> ExtraKind("highlight", "historic_monument", com.example.R.string.osm_extra_name_historic_monument, "Monument")
                "memorial" -> if (tags["name"].isNullOrBlank()) null
                    else ExtraKind("highlight", "historic_memorial", com.example.R.string.osm_extra_name_historic_memorial, "Mindesmærke")
                else -> null
            }
            tags["natural"] == "beach" -> ExtraKind("beach", "beach", com.example.R.string.osm_extra_name_beach, "Strand")
            leisure == "bathing_place" || leisure == "swimming_area" ->
                ExtraKind("beach", leisure, com.example.R.string.osm_extra_name_bathing_place, "Badested")
            tags["emergency"] == "defibrillator" -> ExtraKind("aed", "defibrillator", com.example.R.string.osm_extra_name_aed, "Hjertestarter")
            amenity == "pharmacy" -> ExtraKind("pharmacy", "pharmacy", com.example.R.string.osm_extra_name_pharmacy, "Apotek")
            tourism == "hostel" -> ExtraKind("accommodation", "hostel", com.example.R.string.osm_extra_name_hostel, "Vandrehjem")
            tourism == "guest_house" -> ExtraKind("accommodation", "guest_house", com.example.R.string.osm_extra_name_guest_house, "B&B / gæstehus")
            tourism == "hotel" -> ExtraKind("accommodation", "hotel", com.example.R.string.osm_extra_name_hotel, "Hotel")
            tourism == "chalet" -> ExtraKind("accommodation", "chalet", com.example.R.string.osm_extra_name_chalet, "Hytte")
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseExtrasOverpassJson(jsonStr: String): List<PoiEntity> {
        val root = try {
            moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val elements = root["elements"] as? List<Map<String, Any>> ?: return emptyList()
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val mapAdapter = moshi.adapter(Map::class.java)
        val now = System.currentTimeMillis()
        val pois = mutableListOf<PoiEntity>()
        val unnamedIds = HashSet<String>()

        for (el in elements) {
            val elType = el["type"] as? String ?: continue
            val id = (el["id"] as? Number)?.toLong() ?: continue
            val tags = (el["tags"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: continue
            val kind = classifyExtra(tags) ?: continue

            val lat: Double
            val lng: Double
            if (elType == "node") {
                lat = (el["lat"] as? Number)?.toDouble() ?: continue
                lng = (el["lon"] as? Number)?.toDouble() ?: continue
            } else {
                val center = el["center"] as? Map<String, Any> ?: continue
                lat = (center["lat"] as? Number)?.toDouble() ?: continue
                lng = (center["lon"] as? Number)?.toDouble() ?: continue
            }

            val details = LinkedHashMap<String, String>()
            val description = (tags["description:da"] ?: tags["description"])?.trim()
            if (!description.isNullOrBlank()) details["description"] = description
            for (key in extrasDetailKeys) {
                tags[key]?.trim()?.takeIf { it.isNotBlank() }?.let { details[key] = it }
            }

            val osmName = tags["name"]?.trim()?.takeIf { it.isNotBlank() }
            if (osmName == null) unnamedIds.add("osm_${elType}_$id")
            val name = osmName ?: runCatching { localizedCtx.getString(kind.fallbackRes) }.getOrDefault(kind.fallbackDa)

            pois.add(
                PoiEntity(
                    id = "osm_${elType}_$id",
                    type = kind.type,
                    name = name,
                    lat = lat,
                    lng = lng,
                    access = tags["access"]?.trim(),
                    fee = tags["fee"]?.trim()?.lowercase() == "yes",
                    openingHours = tags["opening_hours"]?.trim()?.takeIf { it.isNotBlank() },
                    operator = tags["operator"]?.trim()?.takeIf { it.isNotBlank() },
                    website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.trim()?.takeIf { it.isNotBlank() },
                    source = "osm",
                    fetchedAt = now,
                    seasonal = tags["seasonal"]?.trim()?.takeIf { it.isNotBlank() },
                    subtype = kind.subtype,
                    detailsJson = if (details.isEmpty()) null else runCatching { mapAdapter.toJson(details) }.getOrNull()
                )
            )
        }
        return dedupeExtras(pois, unnamedIds)
    }

    /**
     * Same type within 20 m: keep one. Same type and same name within 150 m: keep one (this merges
     * a grave_yard and the cemetery around it). The name rule is skipped when [unnamedIds] is null
     * (merging chunks that were each de-duplicated already) and for places without a real OSM
     * name (their fallback names are all equal), except churchyards.
     */
    fun dedupeExtras(pois: List<PoiEntity>, unnamedIds: Set<String>? = emptySet()): List<PoiEntity> {
        val kept = mutableListOf<PoiEntity>()
        val seenIds = HashSet<String>()
        for (poi in pois) {
            if (!seenIds.add(poi.id)) continue
            val normName = poi.name.trim().lowercase()
            val duplicate = kept.any { existing ->
                if (existing.type != poi.type) return@any false
                val d = com.example.util.GeoUtils.distanceMeters(existing.lat, existing.lng, poi.lat, poi.lng)
                val nameRuleApplies = unnamedIds != null &&
                    (poi.type == "water_likely" || (poi.id !in unnamedIds && existing.id !in unnamedIds))
                d <= 20.0 || (nameRuleApplies && d <= 150.0 && existing.name.trim().lowercase() == normName)
            }
            if (!duplicate) kept.add(poi)
        }
        return kept
    }
}
