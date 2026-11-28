package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class RestrictedAreaKind { MILITARY, WILDLIFE_RESERVE }

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class AreaBbox(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double) {
    fun contains(lat: Double, lng: Double, marginDeg: Double = 0.0): Boolean =
        lat >= minLat - marginDeg && lat <= maxLat + marginDeg && lng >= minLng - marginDeg && lng <= maxLng + marginDeg
}

/** Phase D1: a military area or wildlife reserve from OpenStreetMap (outer rings only). */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RestrictedArea(
    val id: String,
    val kind: RestrictedAreaKind,
    val name: String?,
    val website: String?,
    val rings: List<List<GeoPointDto>>,
    val bbox: AreaBbox,
    val fetchedAt: Long,
    val source: String = "osm"
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RestrictedAreaCache(val fetchedAt: Long, val areas: List<RestrictedArea>)

/**
 * Phase D1: one national Overpass query for military areas and wildlife reserves in Denmark,
 * cached in `filesDir/restricted_areas/dk.json` for 30 days. A failed refresh keeps the old cache;
 * without any cache, everything returns an empty list.
 */
class RestrictedAreaRepository(private val context: Context) {

    private val moshi = com.example.data.remote.AppMoshi.instance
    // Lazy: a JSON adapter problem must never stop the repository (and the app) from starting
    private val cacheAdapter by lazy { moshi.adapter(RestrictedAreaCache::class.java) }
    private val refreshMutex = Mutex()

    @Volatile
    private var memoryCache: RestrictedAreaCache? = null

    private val cacheFile: File
        get() = File(File(context.filesDir, "restricted_areas").apply { mkdirs() }, "dk.json")

    companion object {
        const val MAX_AGE_MS = 30L * 24L * 3600L * 1000L
        const val SIMPLIFY_EPSILON_M = 15.0

        val QUERY = """
            [out:json][timeout:120][maxsize:134217728];
            area["ISO3166-1"="DK"][admin_level=2]->.dk;
            (
              way["landuse"="military"](area.dk);
              relation["landuse"="military"](area.dk);
              way["military"~"^(danger_area|range|training_area)$"](area.dk);
              relation["military"~"^(danger_area|range|training_area)$"](area.dk);
              way["boundary"="protected_area"]["protection_title"~"vildtreservat",i](area.dk);
              relation["boundary"="protected_area"]["protection_title"~"vildtreservat",i](area.dk);
              way["boundary"="protected_area"]["name"~"vildtreservat",i](area.dk);
              relation["boundary"="protected_area"]["name"~"vildtreservat",i](area.dk);
            );
            out tags geom;
        """.trimIndent()

        private const val ENDPOINT_MATCH_DEG = 1e-7

        /** MILITARY for landuse=military or military=danger_area|range|training_area; else a reserve when it matches; else null. */
        fun classify(tags: Map<String, String>): RestrictedAreaKind? {
            if (tags.keys.any { it.startsWith("seamark:") }) return null
            if (tags["landuse"] == "military" || tags["military"] in setOf("danger_area", "range", "training_area")) {
                return RestrictedAreaKind.MILITARY
            }
            val isReserve = tags["boundary"] == "protected_area" &&
                (tags["protection_title"]?.contains("vildtreservat", ignoreCase = true) == true ||
                    tags["name"]?.contains("vildtreservat", ignoreCase = true) == true)
            return if (isReserve) RestrictedAreaKind.WILDLIFE_RESERVE else null
        }

        private fun same(a: GeoPointDto, b: GeoPointDto) =
            kotlin.math.abs(a.lat - b.lat) < ENDPOINT_MATCH_DEG && kotlin.math.abs(a.lng - b.lng) < ENDPOINT_MATCH_DEG

        private fun isClosed(pts: List<GeoPointDto>) = pts.size >= 4 && same(pts.first(), pts.last())

        /**
         * Stitches outer member ways into closed rings by matching end points, reversing ways when
         * needed. Returns null when any ring cannot be closed (the relation is then skipped).
         */
        fun stitchRings(ways: List<List<GeoPointDto>>): List<List<GeoPointDto>>? {
            val remaining = ways.filter { it.size >= 2 }.toMutableList()
            if (remaining.isEmpty()) return null
            val rings = mutableListOf<List<GeoPointDto>>()
            while (remaining.isNotEmpty()) {
                val ring = remaining.removeAt(0).toMutableList()
                while (!isClosed(ring)) {
                    val end = ring.last()
                    val idx = remaining.indexOfFirst { same(it.first(), end) || same(it.last(), end) }
                    if (idx < 0) return null
                    val next = remaining.removeAt(idx)
                    val oriented = if (same(next.first(), end)) next else next.reversed()
                    ring.addAll(oriented.drop(1))
                }
                rings.add(ring)
            }
            return rings
        }

        fun bboxOf(rings: List<List<GeoPointDto>>): AreaBbox {
            val pts = rings.flatten()
            return AreaBbox(pts.minOf { it.lat }, pts.minOf { it.lng }, pts.maxOf { it.lat }, pts.maxOf { it.lng })
        }

        @Suppress("UNCHECKED_CAST")
        private fun geometryOf(el: Map<String, Any>): List<GeoPointDto> =
            (el["geometry"] as? List<Map<String, Any>>).orEmpty().mapNotNull { g ->
                val lat = (g["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val lng = (g["lon"] as? Number)?.toDouble() ?: return@mapNotNull null
                GeoPointDto(lat, lng)
            }

        /** Parses an `out tags geom` Overpass response. Pure apart from Moshi. */
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String, fetchedAt: Long): List<RestrictedArea> {
            val moshi = Moshi.Builder().build()
            val root = runCatching { moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any> }.getOrNull()
                ?: return emptyList()
            val elements = root["elements"] as? List<Map<String, Any>> ?: return emptyList()
            val areas = mutableListOf<RestrictedArea>()
            for (el in elements) {
                val type = el["type"] as? String ?: continue
                val id = (el["id"] as? Number)?.toLong() ?: continue
                val tags = (el["tags"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: continue
                val kind = classify(tags) ?: continue
                val rawRings: List<List<GeoPointDto>> = when (type) {
                    "way" -> geometryOf(el).takeIf { isClosed(it) }?.let { listOf(it) } ?: continue
                    "relation" -> {
                        val outers = (el["members"] as? List<Map<String, Any>>).orEmpty()
                            .filter { it["type"] == "way" && (it["role"] as? String).let { r -> r == "outer" || r.isNullOrEmpty() } }
                            .map { geometryOf(it) }
                        stitchRings(outers) ?: continue
                    }
                    else -> continue
                }
                val rings = rawRings.map { com.example.util.DouglasPeucker.simplify(it, SIMPLIFY_EPSILON_M) }.filter { it.size >= 4 }
                if (rings.isEmpty()) continue
                areas.add(
                    RestrictedArea(
                        id = "osm_${type}_$id",
                        kind = kind,
                        name = tags["name"]?.trim()?.takeIf { it.isNotBlank() },
                        website = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.trim()?.takeIf { it.isNotBlank() },
                        rings = rings,
                        bbox = bboxOf(rings),
                        fetchedAt = fetchedAt
                    )
                )
            }
            return areas
        }
    }

    /** True when an area cache (fresh or old) exists. */
    fun hasCache(): Boolean = memoryCache != null || cacheFile.exists()

    /** Cached areas without touching the network; empty when nothing has been fetched yet. */
    fun cachedAreas(): List<RestrictedArea> {
        memoryCache?.let { return it.areas }
        val loaded = runCatching { cacheFile.takeIf { it.exists() }?.readText()?.let { cacheAdapter.fromJson(it) } }.getOrNull()
        memoryCache = loaded
        return loaded?.areas.orEmpty()
    }

    /**
     * Returns the areas, refreshing them at BACKGROUND priority first when the cache is missing or
     * older than 30 days. Any failure keeps the old cache.
     */
    suspend fun getAreas(nowMs: Long = System.currentTimeMillis()): List<RestrictedArea> = withContext(Dispatchers.IO) {
        val cached = cachedAreas()
        val age = memoryCache?.let { nowMs - it.fetchedAt } ?: Long.MAX_VALUE
        if (age < MAX_AGE_MS) return@withContext cached
        refreshMutex.withLock {
            val again = memoryCache
            if (again != null && nowMs - again.fetchedAt < MAX_AGE_MS) return@withLock again.areas
            try {
                val body = com.example.data.remote.OverpassClient.queryRawJson(
                    query = QUERY,
                    label = "RestrictedAreasDK",
                    priority = com.example.data.remote.OverpassClient.Priority.BACKGROUND
                )
                val areas = parse(body, nowMs)
                if (areas.isEmpty()) {
                    Log.w("RestrictedAreaRepo", "Overpass returned no areas; keeping the old cache")
                    return@withLock cached
                }
                val fresh = RestrictedAreaCache(nowMs, areas)
                val tmp = File(cacheFile.parentFile, "dk.json.tmp")
                tmp.writeText(cacheAdapter.toJson(fresh))
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.delete()
                    tmp.renameTo(cacheFile)
                }
                memoryCache = fresh
                areas
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RestrictedAreaRepo", "Restricted area refresh failed, keeping cache: ${e.message}")
                cached
            }
        }
    }
}
