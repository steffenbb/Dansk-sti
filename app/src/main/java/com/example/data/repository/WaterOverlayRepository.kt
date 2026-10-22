package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import com.example.util.network.applyDebugMainThreadCheck
import com.example.util.routing.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

enum class WaterFeatureType {
    LAKE,
    WETLAND,
    RIVER,
    STREAM,
    SPRING
}

data class WaterFeature(
    val id: String,
    val type: WaterFeatureType,
    val name: String?,
    val coordinates: List<LatLng>,
    val tileId: String = ""
)

/**
 * Requirement D: Water overlay "Vand" (on by default from zoom 12)
 * - Overpass, per 0.05 x 0.05 degree tile, cached 30 days:
 *   natural=water, water=*, waterway=river|stream|canal|ditch|drain, natural=wetland, natural=spring.
 * - Draw lakes and wetland as fills (lakes 45% blue, wetland 25% blue),
 *   rivers 3 dp, streams and ditches 1.5 dp, all in a clear blue (#00ACC1) that differs from the route colour (#1E88E5 / #64B5F6).
 * - Springs as small blue dots.
 */
class WaterOverlayRepository(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val cacheDir: File = File(context.filesDir, "water_tiles_cache")
) {
    companion object {
        private const val TAG = "WaterOverlayRepo"
        const val TILE_SIZE_DEG = 0.05
        const val CACHE_TTL_MS = 30L * 24 * 3600 * 1000L // 30 days
        const val MIN_WATER_ZOOM = 12.0

        // Clear cyan-azure blue (#00ACC1) that stands out on both light and dark maps
        // and clearly differs from the planned route colors (#1E88E5 light, #64B5F6 dark)
        const val COLOR_WATER_BLUE_RGB = 0x00ACC1
        val COLOR_WATER_STROKE = Color.rgb(0, 172, 193) // #00ACC1
        val COLOR_LAKE_FILL = Color.argb((0.45f * 255).roundToInt(), 0, 172, 193) // 45% blue
        val COLOR_LAKE_OUTLINE = Color.argb(190, 0, 151, 167)
        val COLOR_WETLAND_FILL = Color.argb((0.25f * 255).roundToInt(), 0, 151, 167) // 25% blue
        val COLOR_WETLAND_OUTLINE = Color.argb(140, 0, 131, 143)

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .applyDebugMainThreadCheck()
                .build()
        }

        data class WaterTileBounds(
            val tileId: String,
            val minLat: Double,
            val maxLat: Double,
            val minLng: Double,
            val maxLng: Double
        )

        /**
         * Splits a bounding box into 0.05 x 0.05 degree tiles.
         */
        fun getTileIdsForBounds(
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double
        ): List<WaterTileBounds> {
            val scale = 20.0 // 1 / 0.05 = 20
            val sLat = floor(minLat * scale).toInt()
            val eLat = floor((maxLat - 1e-9).coerceAtLeast(minLat) * scale).toInt()
            val sLng = floor(minLng * scale).toInt()
            val eLng = floor((maxLng - 1e-9).coerceAtLeast(minLng) * scale).toInt()

            val tiles = mutableListOf<WaterTileBounds>()
            val maxLatIdx = (sLat + 12).coerceAtMost(eLat)
            val maxLngIdx = (sLng + 12).coerceAtMost(eLng)

            for (latIdx in sLat..maxLatIdx) {
                for (lngIdx in sLng..maxLngIdx) {
                    val tMinLat = latIdx / scale
                    val tMaxLat = (latIdx + 1) / scale
                    val tMinLng = lngIdx / scale
                    val tMaxLng = (lngIdx + 1) / scale
                    val tileId = "water_tile_${latIdx}_${lngIdx}"
                    tiles.add(WaterTileBounds(tileId, tMinLat, tMaxLat, tMinLng, tMaxLng))
                }
            }
            return tiles
        }

        fun buildOverpassQuery(
            minLat: Double,
            minLng: Double,
            maxLat: Double,
            maxLng: Double
        ): String {
            return """
                [out:json][timeout:20];
                (
                  way["natural"="water"]($minLat,$minLng,$maxLat,$maxLng);
                  relation["natural"="water"]($minLat,$minLng,$maxLat,$maxLng);
                  way["water"]($minLat,$minLng,$maxLat,$maxLng);
                  relation["water"]($minLat,$minLng,$maxLat,$maxLng);
                  way["waterway"~"^(river|stream|canal|ditch|drain)$"]($minLat,$minLng,$maxLat,$maxLng);
                  way["natural"="wetland"]($minLat,$minLng,$maxLat,$maxLng);
                  relation["natural"="wetland"]($minLat,$minLng,$maxLat,$maxLng);
                  node["natural"="spring"]($minLat,$minLng,$maxLat,$maxLng);
                );
                out body;
                >;
                out skel qt;
            """.trimIndent()
        }
    }

    private data class CachedWaterTile(
        val fetchedAt: Long,
        val features: List<WaterFeature>
    )

    private val memoryCache = ConcurrentHashMap<String, CachedWaterTile>()
    private var springDotDrawable: BitmapDrawable? = null

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    suspend fun getWaterFeatures(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double
    ): List<WaterFeature> = withContext(Dispatchers.IO) {
        val tiles = getTileIdsForBounds(minLat, maxLat, minLng, maxLng)
        val combined = LinkedHashMap<String, WaterFeature>()
        val now = System.currentTimeMillis()

        for (tile in tiles) {
            // 1. Check memory cache
            val mem = memoryCache[tile.tileId]
            if (mem != null && (now - mem.fetchedAt) < CACHE_TTL_MS) {
                for (f in mem.features) combined[f.id] = f
                continue
            }

            // 2. Check disk cache (30 days TTL)
            val diskTile = readDiskTileCache(tile.tileId)
            if (diskTile != null && (now - diskTile.fetchedAt) < CACHE_TTL_MS) {
                memoryCache[tile.tileId] = diskTile
                for (f in diskTile.features) combined[f.id] = f
                continue
            }

            // 3. Fetch live from Overpass for this 0.05 x 0.05 tile
            try {
                val fetched = fetchTileFromOverpass(tile)
                val entry = CachedWaterTile(now, fetched)
                memoryCache[tile.tileId] = entry
                writeDiskTileCache(tile.tileId, entry)
                for (f in fetched) combined[f.id] = f
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message == "Network on main thread") throw e
                Log.w(TAG, "Overpass water fetch failed for ${tile.tileId}: ${e.message}")
                // Fall back to stale disk cache if available
                if (diskTile != null) {
                    for (f in diskTile.features) combined[f.id] = f
                }
            }
        }

        combined.values.toList()
    }

    private suspend fun fetchTileFromOverpass(tile: WaterTileBounds): List<WaterFeature> = withContext(Dispatchers.IO) {
        val query = buildOverpassQuery(tile.minLat, tile.minLng, tile.maxLat, tile.maxLng)
        val body = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "DanskSti/1.8 (Android Water Overlay)")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Overpass HTTP ${response.code}")
            }
            val jsonStr = response.body?.string() ?: return@withContext emptyList()
            parseOverpassWaterJson(jsonStr, tile.tileId)
        }
    }

    fun parseOverpassWaterJson(jsonStr: String, tileId: String = ""): List<WaterFeature> {
        val root = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return emptyList()
        }
        val elements = root.optJSONArray("elements") ?: return emptyList()

        val nodeMap = HashMap<Long, LatLng>()
        val wayMap = HashMap<Long, List<LatLng>>()
        val taggedElements = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val id = el.optLong("id")
            when (type) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        nodeMap[id] = LatLng(lat, lon)
                        if (el.has("tags")) {
                            taggedElements.add(el)
                        }
                    }
                }
                "way" -> {
                    val nodesArr = el.optJSONArray("nodes")
                    val pts = mutableListOf<LatLng>()
                    if (nodesArr != null) {
                        for (j in 0 until nodesArr.length()) {
                            nodeMap[nodesArr.getLong(j)]?.let { pts.add(it) }
                        }
                    }
                    val geomArr = el.optJSONArray("geometry")
                    if (pts.isEmpty() && geomArr != null) {
                        for (j in 0 until geomArr.length()) {
                            val g = geomArr.optJSONObject(j) ?: continue
                            pts.add(LatLng(g.getDouble("lat"), g.getDouble("lon")))
                        }
                    }
                    wayMap[id] = pts
                    if (el.has("tags") && pts.size >= 2) {
                        taggedElements.add(el)
                    }
                }
                "relation" -> {
                    if (el.has("tags")) {
                        taggedElements.add(el)
                    }
                }
            }
        }

        val features = mutableListOf<WaterFeature>()
        for (el in taggedElements) {
            val tagsObj = el.optJSONObject("tags") ?: continue
            val natural = tagsObj.optString("natural")
            val water = tagsObj.optString("water")
            val waterway = tagsObj.optString("waterway")
            val name = tagsObj.optString("name").takeIf { it.isNotBlank() }
            val elType = el.optString("type")
            val elId = el.optLong("id")

            if (elType == "node" && natural == "spring") {
                val pt = nodeMap[elId] ?: continue
                features.add(
                    WaterFeature(
                        id = "spring_$elId",
                        type = WaterFeatureType.SPRING,
                        name = name ?: "Kilde",
                        coordinates = listOf(pt),
                        tileId = tileId
                    )
                )
                continue
            }

            val coords: List<LatLng> = when (elType) {
                "way" -> wayMap[elId] ?: emptyList()
                "relation" -> {
                    val members = el.optJSONArray("members")
                    val outerPts = mutableListOf<LatLng>()
                    if (members != null) {
                        for (m in 0 until members.length()) {
                            val mem = members.optJSONObject(m) ?: continue
                            if (mem.optString("role") != "inner" && mem.optString("type") == "way") {
                                wayMap[mem.optLong("ref")]?.let { outerPts.addAll(it) }
                            }
                        }
                    }
                    outerPts
                }
                else -> emptyList()
            }

            if (coords.size < 2) continue

            val featureType = when {
                natural == "wetland" -> WaterFeatureType.WETLAND
                natural == "water" || water.isNotBlank() -> WaterFeatureType.LAKE
                waterway == "river" || waterway == "canal" -> WaterFeatureType.RIVER
                waterway == "stream" || waterway == "ditch" || waterway == "drain" -> WaterFeatureType.STREAM
                else -> null
            } ?: continue

            if ((featureType == WaterFeatureType.LAKE || featureType == WaterFeatureType.WETLAND) && coords.size < 3) {
                continue
            }

            features.add(
                WaterFeature(
                    id = "${elType}_${elId}",
                    type = featureType,
                    name = name,
                    coordinates = coords,
                    tileId = tileId
                )
            )
        }

        return features
    }

    private fun readDiskTileCache(tileId: String): CachedWaterTile? {
        val file = File(cacheDir, "$tileId.json")
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val fetchedAt = root.optLong("fetchedAt", 0L)
            val arr = root.optJSONArray("features") ?: JSONArray()
            val list = mutableListOf<WaterFeature>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val type = WaterFeatureType.valueOf(obj.getString("type"))
                val name = obj.optString("name").takeIf { it.isNotBlank() }
                val coordsArr = obj.getJSONArray("coords")
                val pts = mutableListOf<LatLng>()
                for (j in 0 until coordsArr.length()) {
                    val p = coordsArr.getJSONArray(j)
                    pts.add(LatLng(p.getDouble(0), p.getDouble(1)))
                }
                list.add(WaterFeature(id, type, name, pts, tileId))
            }
            CachedWaterTile(fetchedAt, list)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeDiskTileCache(tileId: String, tile: CachedWaterTile) {
        try {
            val file = File(cacheDir, "$tileId.json")
            val root = JSONObject()
            root.put("fetchedAt", tile.fetchedAt)
            val arr = JSONArray()
            for (f in tile.features) {
                val obj = JSONObject()
                obj.put("id", f.id)
                obj.put("type", f.type.name)
                if (f.name != null) obj.put("name", f.name)
                val coordsArr = JSONArray()
                for (c in f.coordinates) {
                    val p = JSONArray()
                    p.put(c.lat)
                    p.put(c.lng)
                    coordsArr.put(p)
                }
                obj.put("coords", coordsArr)
                arr.put(obj)
            }
            root.put("features", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {}
    }

    /**
     * Populates a FolderOverlay with the water features (lakes, wetlands, rivers, streams, springs).
     */
    fun populateWaterFolderOverlay(
        context: Context,
        mapView: MapView,
        folder: FolderOverlay,
        features: List<WaterFeature>
    ) {
        folder.items.clear()
        val density = context.resources.displayMetrics.density

        for (feature in features) {
            when (feature.type) {
                WaterFeatureType.LAKE -> {
                    if (feature.coordinates.size >= 3) {
                        val poly = Polygon(mapView).apply {
                            points = feature.coordinates.map { GeoPoint(it.lat, it.lng) }
                            fillPaint.color = COLOR_LAKE_FILL // 45% blue
                            outlinePaint.color = COLOR_LAKE_OUTLINE
                            outlinePaint.strokeWidth = 1f * density
                            title = feature.name ?: "Sø"
                            infoWindow = null
                        }
                        folder.add(poly)
                    }
                }
                WaterFeatureType.WETLAND -> {
                    if (feature.coordinates.size >= 3) {
                        val poly = Polygon(mapView).apply {
                            points = feature.coordinates.map { GeoPoint(it.lat, it.lng) }
                            fillPaint.color = COLOR_WETLAND_FILL // 25% blue
                            outlinePaint.color = COLOR_WETLAND_OUTLINE
                            outlinePaint.strokeWidth = 1f * density
                            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
                            title = feature.name ?: "Vådområde"
                            infoWindow = null
                        }
                        folder.add(poly)
                    }
                }
                WaterFeatureType.RIVER -> {
                    if (feature.coordinates.size >= 2) {
                        val line = Polyline(mapView).apply {
                            setPoints(feature.coordinates.map { GeoPoint(it.lat, it.lng) })
                            outlinePaint.color = COLOR_WATER_STROKE
                            outlinePaint.strokeWidth = 3f * density // 3 dp
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            title = feature.name ?: "Å / Vandløb"
                            infoWindow = null
                        }
                        folder.add(line)
                    }
                }
                WaterFeatureType.STREAM -> {
                    if (feature.coordinates.size >= 2) {
                        val line = Polyline(mapView).apply {
                            setPoints(feature.coordinates.map { GeoPoint(it.lat, it.lng) })
                            outlinePaint.color = COLOR_WATER_STROKE
                            outlinePaint.strokeWidth = 1.5f * density // 1.5 dp
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                            title = feature.name ?: "Bæk / Grøft"
                            infoWindow = null
                        }
                        folder.add(line)
                    }
                }
                WaterFeatureType.SPRING -> {
                    val pt = feature.coordinates.firstOrNull() ?: continue
                    val dotMarker = Marker(mapView).apply {
                        position = GeoPoint(pt.lat, pt.lng)
                        icon = getSpringDotIcon(context)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = feature.name ?: "Kilde"
                        infoWindow = null
                    }
                    folder.add(dotMarker)
                }
            }
        }
    }

    private fun getSpringDotIcon(context: Context): BitmapDrawable {
        springDotDrawable?.let { return it }
        val density = context.resources.displayMetrics.density
        val sizePx = (10f * density).roundToInt().coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f - 1f * density

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_WATER_STROKE
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        return BitmapDrawable(context.resources, bmp).also { springDotDrawable = it }
    }
}
