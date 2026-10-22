package com.example.data.repository

import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.model.GeoPointDto
import com.example.data.model.WaterFeatureDto
import com.example.data.model.WaterFeatureEntity
import com.example.data.model.WaterFeatureType
import com.example.data.model.WaterTileCacheEntity
import com.example.data.remote.OverpassApiService
import com.example.data.remote.OverpassElement
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.floor

/**
 * Requirement D: Water overlay "Vand" (on by default from zoom 12)
 * - Overpass, per 0.05 x 0.05 degree tile, cached 30 days:
 *   natural=water, water=*, waterway=river|stream|canal|ditch|drain, natural=wetland, natural=spring.
 * - Supports coroutine cancellation when the previous debounced request is superseded.
 */
class WaterLayerRepository(
    private val dao: DanskStiDao,
    private val overpassService: OverpassApiService = OverpassApiService.create()
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val pointsListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
    private val pointsAdapter = moshi.adapter<List<GeoPointDto>>(pointsListType)
    private val fetchMutex = Mutex()

    companion object {
        private const val TAG = "WaterLayerRepo"
        const val TILE_STEP_DEG = 0.05
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days
        const val MIN_ZOOM_FOR_WATER = 12.0
        private const val MAX_TILES_PER_VIEWPORT = 12

        fun tileIndexForCoord(coord: Double): Int = floor(coord / TILE_STEP_DEG).toInt()

        fun tileIdForIndices(latIdx: Int, lngIdx: Int): String = "water_tile_${latIdx}_${lngIdx}"

        fun tileBoundsForIndices(latIdx: Int, lngIdx: Int): DoubleArray {
            val south = latIdx * TILE_STEP_DEG
            val north = (latIdx + 1) * TILE_STEP_DEG
            val west = lngIdx * TILE_STEP_DEG
            val east = (lngIdx + 1) * TILE_STEP_DEG
            return doubleArrayOf(south, west, north, east)
        }

        fun buildOverpassQuery(south: Double, west: Double, north: Double, east: Double): String {
            val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", south, west, north, east)
            return """
                [out:json][timeout:25];
                (
                  way["natural"="water"]($bbox);
                  relation["natural"="water"]($bbox);
                  way["water"]($bbox);
                  relation["water"]($bbox);
                  way["waterway"~"^(river|stream|canal|ditch|drain)$"]($bbox);
                  way["natural"="wetland"]($bbox);
                  relation["natural"="wetland"]($bbox);
                  node["natural"="spring"]($bbox);
                );
                out body geom;
            """.trimIndent()
        }
    }

    /**
     * Loads water features for the given viewport bounding box (when zoom >= 12).
     * Each 0.05 x 0.05 degree tile is cached in Room for 30 days.
     */
    suspend fun getWaterFeaturesForViewport(
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): List<WaterFeatureDto> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        val minLatIdx = tileIndexForCoord(south)
        val maxLatIdx = tileIndexForCoord(north)
        val minLngIdx = tileIndexForCoord(west)
        val maxLngIdx = tileIndexForCoord(east)

        val tilePairs = mutableListOf<Pair<Int, Int>>()
        for (latIdx in minLatIdx..maxLatIdx) {
            for (lngIdx in minLngIdx..maxLngIdx) {
                tilePairs.add(latIdx to lngIdx)
            }
        }

        val now = System.currentTimeMillis()
        val expiredCutoff = now - CACHE_TTL_MS

        // Prune expired cache entries opportunistically
        try {
            dao.deleteExpiredWaterFeatures(expiredCutoff)
            dao.deleteExpiredWaterTileCache(expiredCutoff)
        } catch (_: Exception) {}

        val centerLatIdx = (minLatIdx + maxLatIdx) / 2.0
        val centerLngIdx = (minLngIdx + maxLngIdx) / 2.0
        val prioritizedTiles = tilePairs.sortedBy { (latIdx, lngIdx) ->
            val dLat = latIdx - centerLatIdx
            val dLng = lngIdx - centerLngIdx
            dLat * dLat + dLng * dLng
        }.take(MAX_TILES_PER_VIEWPORT)

        for ((latIdx, lngIdx) in prioritizedTiles) {
            currentCoroutineContext().ensureActive()
            val tileId = tileIdForIndices(latIdx, lngIdx)
            val cachedTile = dao.getWaterTileCache(tileId)
            val isFresh = cachedTile != null && (now - cachedTile.fetchedAt) < CACHE_TTL_MS
            if (!isFresh) {
                fetchAndCacheTile(latIdx, lngIdx, tileId, now)
            }
        }

        currentCoroutineContext().ensureActive()
        val entities = dao.getWaterFeaturesInBbox(
            minLat = south,
            maxLat = north,
            minLng = west,
            maxLng = east
        )

        // Deduplicate by osmId + featureType across adjacent 0.05x0.05 tiles
        val seenKeys = HashSet<String>(entities.size)
        val result = ArrayList<WaterFeatureDto>(entities.size)
        for (entity in entities) {
            val dedupKey = "${entity.featureType}_${entity.osmId}_${entity.minLat}_${entity.minLng}"
            if (seenKeys.add(dedupKey)) {
                entityToDto(entity)?.let { result.add(it) }
            }
        }
        result
    }

    private suspend fun fetchAndCacheTile(
        latIdx: Int,
        lngIdx: Int,
        tileId: String,
        now: Long
    ) {
        val bounds = tileBoundsForIndices(latIdx, lngIdx)
        val south = bounds[0]
        val west = bounds[1]
        val north = bounds[2]
        val east = bounds[3]

        val query = buildOverpassQuery(south, west, north, east)
        try {
            fetchMutex.withLock {
                currentCoroutineContext().ensureActive()
                // Double-check cache inside lock
                val existing = dao.getWaterTileCache(tileId)
                if (existing != null && (now - existing.fetchedAt) < CACHE_TTL_MS) {
                    return
                }

                val response = overpassService.queryOverpass(query)
                currentCoroutineContext().ensureActive()

                val parsedEntities = parseOverpassElements(response.elements, tileId, now)
                dao.deleteWaterFeaturesByTileId(tileId)
                if (parsedEntities.isNotEmpty()) {
                    dao.insertWaterFeatures(parsedEntities)
                }
                dao.insertWaterTileCache(
                    WaterTileCacheEntity(
                        tileId = tileId,
                        minLat = south,
                        maxLat = north,
                        minLng = west,
                        maxLng = east,
                        fetchedAt = now
                    )
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Overpass water tile fetch failed for $tileId: ${e.message}")
        }
    }

    internal fun parseOverpassElements(
        elements: List<OverpassElement>,
        tileId: String,
        fetchedAt: Long
    ): List<WaterFeatureEntity> {
        val results = mutableListOf<WaterFeatureEntity>()

        for (el in elements) {
            val tags = el.tags ?: continue
            val name = tags["name"]
            val natural = tags["natural"]?.lowercase()
            val waterway = tags["waterway"]?.lowercase()
            val water = tags["water"]?.lowercase()

            when {
                // 1. Springs (node["natural"="spring"])
                natural == "spring" -> {
                    val lat = el.lat ?: el.center?.lat ?: continue
                    val lon = el.lon ?: el.center?.lon ?: continue
                    val pts = listOf(GeoPointDto(lat, lon))
                    results.add(
                        WaterFeatureEntity(
                            id = "spring_${el.id}_$tileId",
                            osmId = el.id,
                            name = name,
                            featureType = WaterFeatureType.SPRING.name,
                            subType = "spring",
                            coordinatesJson = pointsAdapter.toJson(pts),
                            minLat = lat,
                            maxLat = lat,
                            minLng = lon,
                            maxLng = lon,
                            tileId = tileId,
                            fetchedAt = fetchedAt
                        )
                    )
                }

                // 2. Linear waterways: river, canal, stream, ditch, drain
                waterway in setOf("river", "canal", "stream", "ditch", "drain") && natural != "water" && water == null -> {
                    val geom = el.geometry ?: continue
                    val pts = geom.map { GeoPointDto(it.lat, it.lon) }
                    if (pts.size < 2) continue
                    val type = if (waterway == "river" || waterway == "canal") {
                        WaterFeatureType.RIVER
                    } else {
                        WaterFeatureType.STREAM
                    }
                    results.add(
                        buildEntity(
                            id = "${type.name.lowercase()}_${el.id}_$tileId",
                            osmId = el.id,
                            name = name,
                            featureType = type,
                            subType = waterway,
                            pts = pts,
                            tileId = tileId,
                            fetchedAt = fetchedAt
                        )
                    )
                }

                // 3. Wetlands: natural=wetland
                natural == "wetland" -> {
                    val rings = extractGeometryRings(el)
                    rings.forEachIndexed { idx, pts ->
                        if (pts.size >= 3) {
                            results.add(
                                buildEntity(
                                    id = "wetland_${el.id}_${idx}_$tileId",
                                    osmId = el.id,
                                    name = name,
                                    featureType = WaterFeatureType.WETLAND,
                                    subType = tags["wetland"] ?: "wetland",
                                    pts = pts,
                                    tileId = tileId,
                                    fetchedAt = fetchedAt
                                )
                            )
                        }
                    }
                }

                // 4. Water bodies (lakes, ponds, basins, riverbanks): natural=water or water=*
                natural == "water" || water != null -> {
                    val rings = extractGeometryRings(el)
                    rings.forEachIndexed { idx, pts ->
                        if (pts.size >= 3) {
                            results.add(
                                buildEntity(
                                    id = "lake_${el.id}_${idx}_$tileId",
                                    osmId = el.id,
                                    name = name,
                                    featureType = WaterFeatureType.LAKE,
                                    subType = water ?: "water",
                                    pts = pts,
                                    tileId = tileId,
                                    fetchedAt = fetchedAt
                                )
                            )
                        }
                    }
                }
            }
        }

        return results
    }

    private fun extractGeometryRings(el: OverpassElement): List<List<GeoPointDto>> {
        val directGeom = el.geometry
        if (!directGeom.isNullOrEmpty()) {
            return listOf(directGeom.map { GeoPointDto(it.lat, it.lon) })
        }
        val members = el.members ?: return emptyList()
        val outerWays = members.filter {
            (it.role.isNullOrEmpty() || it.role == "outer") && !it.geometry.isNullOrEmpty()
        }
        return outerWays.mapNotNull { member ->
            val pts = member.geometry?.map { GeoPointDto(it.lat, it.lon) }
            if (pts != null && pts.size >= 3) pts else null
        }
    }

    suspend fun getCachedWaterFeaturesInBbox(
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): List<WaterFeatureDto> = withContext(Dispatchers.IO) {
        val entities = dao.getWaterFeaturesInBbox(
            minLat = south,
            maxLat = north,
            minLng = west,
            maxLng = east
        )
        val seenKeys = HashSet<String>(entities.size)
        val result = ArrayList<WaterFeatureDto>(entities.size)
        for (entity in entities) {
            val dedupKey = "${entity.featureType}_${entity.osmId}_${entity.minLat}_${entity.minLng}"
            if (seenKeys.add(dedupKey)) {
                entityToDto(entity)?.let { result.add(it) }
            }
        }
        result
    }

    fun buildEntity(
        id: String,
        osmId: Long,
        name: String?,
        featureType: WaterFeatureType,
        subType: String?,
        pts: List<GeoPointDto>,
        tileId: String,
        fetchedAt: Long
    ): WaterFeatureEntity {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        return WaterFeatureEntity(
            id = id,
            osmId = osmId,
            name = name,
            featureType = featureType.name,
            subType = subType,
            coordinatesJson = pointsAdapter.toJson(pts),
            minLat = minLat,
            maxLat = maxLat,
            minLng = minLng,
            maxLng = maxLng,
            tileId = tileId,
            fetchedAt = fetchedAt
        )
    }

    private fun entityToDto(entity: WaterFeatureEntity): WaterFeatureDto? {
        return try {
            val pts = pointsAdapter.fromJson(entity.coordinatesJson) ?: return null
            val type = try {
                WaterFeatureType.valueOf(entity.featureType)
            } catch (_: Exception) {
                WaterFeatureType.LAKE
            }
            WaterFeatureDto(
                id = entity.id,
                osmId = entity.osmId,
                name = entity.name,
                featureType = type,
                subType = entity.subType,
                points = pts,
                minLat = entity.minLat,
                maxLat = entity.maxLat,
                minLng = entity.minLng,
                maxLng = entity.maxLng
            )
        } catch (_: Exception) {
            null
        }
    }
}
