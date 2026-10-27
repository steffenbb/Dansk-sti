package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.util.network.NetworkConstants
import com.example.util.network.applyDebugMainThreadCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RouteOrTrailSearchItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val isCustomRoute: Boolean,
    val trailId: String? = null,
    val customRouteId: Long? = null,
    val centerLat: Double? = null,
    val centerLng: Double? = null
)

data class ShelterOrTentSearchItem(
    val id: String,
    val name: String,
    val type: String, // "SHELTER" or "FRI_TELTNING"
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val bookingRequired: Boolean = false
)

data class GroupedSearchResults(
    val query: String = "",
    val routesAndTrails: List<RouteOrTrailSearchItem> = emptyList(),
    val sheltersAndFriTeltning: List<ShelterOrTentSearchItem> = emptyList(),
    val places: List<PlaceSearchResult> = emptyList()
) {
    val isEmpty: Boolean
        get() = routesAndTrails.isEmpty() && sheltersAndFriTeltning.isEmpty() && places.isEmpty()
}

data class DanishAreaBounds(
    val name: String,
    val aliases: List<String>,
    val centerLat: Double,
    val centerLng: Double,
    val radiusMeters: Double
)

/**
 * OpenStreetMap-based place search limited to Denmark using Photon (komoot.io).
 * Note: Dataforsyningen GSearch is intentionally NOT used because Klimadatastyrelsen is closing it.
 *
 * Caches the last [MAX_CACHED_PLACES] (50) place results locally for offline use.
 */
class PhotonPlaceSearchService(private val context: Context) {

    companion object {
        private const val TAG = "PhotonPlaceSearch"
        const val MAX_CACHED_PLACES = 50
        private const val CACHE_FILE_NAME = "photon_place_cache.json"

        // Denmark bounding box: West=7.8, South=54.5, East=15.3, North=57.8
        const val DENMARK_BBOX = "7.8,54.5,15.3,57.8"
        const val PHOTON_BASE_URL = "https://photon.komoot.io/api/"

        const val PHOTON_USAGE_TERMS =
            "Photon (https://photon.komoot.io) OpenStreetMap place search usage terms: " +
                "(1) Public API is for interactive user search only — no bulk/automated geocoding; " +
                "(2) Requests must include a descriptive User-Agent header; " +
                "(3) Data © OpenStreetMap contributors (ODbL); " +
                "(4) Limited to Denmark (bbox=7.8,54.5,15.3,57.8). Dataforsyningen GSearch is not used."

        val KNOWN_DANISH_AREAS: List<DanishAreaBounds> = listOf(
            DanishAreaBounds(
                name = "Rold Skov",
                aliases = listOf("rold skov", "rold", "skørping", "rebild", "store økssø", "madum sø"),
                centerLat = 56.805,
                centerLng = 9.850,
                radiusMeters = 12_000.0
            ),
            DanishAreaBounds(
                name = "Gribskov",
                aliases = listOf("gribskov", "esrum", "hillerød", "nødebo"),
                centerLat = 55.995,
                centerLng = 12.305,
                radiusMeters = 12_000.0
            ),
            DanishAreaBounds(
                name = "Silkeborg Skovene",
                aliases = listOf("silkeborg", "himmelbjerget", "slåensø", "ry", "mossø"),
                centerLat = 56.135,
                centerLng = 9.585,
                radiusMeters = 14_000.0
            ),
            DanishAreaBounds(
                name = "Mols Bjerge",
                aliases = listOf("mols bjerge", "mols", "ebeltoft", "kalø"),
                centerLat = 56.220,
                centerLng = 10.550,
                radiusMeters = 12_000.0
            ),
            DanishAreaBounds(
                name = "Almindingen",
                aliases = listOf("almindingen", "bornholm", "rønne", "paradisebakkerne", "hammerknuden"),
                centerLat = 55.115,
                centerLng = 14.960,
                radiusMeters = 18_000.0
            ),
            DanishAreaBounds(
                name = "Det Sydfynske Øhav",
                aliases = listOf("sydfyn", "øhavsstien", "svendborg", "faaborg", "langeland", "ærø", "tåsinge"),
                centerLat = 55.030,
                centerLng = 10.560,
                radiusMeters = 25_000.0
            )
        )

        private val SEED_DANISH_PLACES: List<PlaceSearchResult> = listOf(
            PlaceSearchResult("Rold Skov", 56.8050, 9.8500, "Skov", "Skov · Himmerland", "landuse", "forest"),
            PlaceSearchResult("Skørping Station", 56.8358, 9.8825, "Station", "Station · Rebild", "railway", "station"),
            PlaceSearchResult("Store Økssø (Rold Skov)", 56.8012, 9.8745, "Sø", "Sø · Rold Skov", "natural", "water"),
            PlaceSearchResult("Rebild Bakker", 56.8225, 9.8400, "Natur", "Hede & bakker · Himmerland", "natural", "heath"),
            PlaceSearchResult("Gribskov", 55.9950, 12.3050, "Skov", "Skov · Nordsjælland", "landuse", "forest"),
            PlaceSearchResult("Esrum Sø", 56.0000, 12.3750, "Sø", "Sø · Nordsjælland", "natural", "water"),
            PlaceSearchResult("Silkeborg", 56.1697, 9.5451, "By", "By · Midtjylland", "place", "town"),
            PlaceSearchResult("Silkeborg Station", 56.1645, 9.5542, "Station", "Station · Midtjylland", "railway", "station"),
            PlaceSearchResult("Ry Station", 56.0903, 9.7614, "Station", "Station · Midtjylland", "railway", "station"),
            PlaceSearchResult("Mossø", 56.0400, 9.7900, "Sø", "Sø · Østjylland", "natural", "water"),
            PlaceSearchResult("Himmelbjerget", 56.1050, 9.6850, "Sted", "Udsigtspunkt · Søhøjlandet", "natural", "peak"),
            PlaceSearchResult("Mols Bjerge", 56.2200, 10.5500, "Skov", "Nationalpark · Østjylland", "boundary", "national_park"),
            PlaceSearchResult("Svendborg", 55.0598, 10.6068, "By", "By · Fyn", "place", "town"),
            PlaceSearchResult("Svendborg Station", 55.0593, 10.6105, "Station", "Station · Fyn", "railway", "station"),
            PlaceSearchResult("Faaborg", 55.0950, 10.2410, "By", "By · Fyn", "place", "town"),
            PlaceSearchResult("Rudkøbing", 54.9360, 10.7100, "By", "By · Langeland", "place", "town"),
            PlaceSearchResult("Ærøskøbing", 54.8890, 10.4110, "By", "By · Ærø", "place", "town"),
            PlaceSearchResult("Viborg", 56.4532, 9.4020, "By", "By · Midtjylland", "place", "city"),
            PlaceSearchResult("Viborg Station", 56.4482, 9.3941, "Station", "Station · Midtjylland", "railway", "station"),
            PlaceSearchResult("Almindingen", 55.1150, 14.9600, "Skov", "Skov · Bornholm", "landuse", "forest"),
            PlaceSearchResult("Rønne", 55.1037, 14.7065, "By", "By · Bornholm", "place", "town"),
            PlaceSearchResult("Hillerød Station", 55.9278, 12.3106, "Station", "Station · Nordsjælland", "railway", "station"),
            PlaceSearchResult("Arresø", 55.9750, 12.1150, "Sø", "Sø · Nordsjælland", "natural", "water"),
            PlaceSearchResult("Furesø", 55.7950, 12.4150, "Sø", "Sø · Nordsjælland", "natural", "water"),
            PlaceSearchResult("Tisvilde Hegn", 56.0400, 12.0500, "Skov", "Skov · Nordsjælland", "landuse", "forest"),
            PlaceSearchResult("Skagen", 57.7209, 10.5839, "By", "By · Nordjylland", "place", "town"),
            PlaceSearchResult("Ribe", 55.3269, 8.7611, "By", "By · Sønderjylland", "place", "town"),
            PlaceSearchResult("Sønderborg", 54.9093, 9.7922, "By", "By · Sønderjylland", "place", "town"),
            PlaceSearchResult("Roskilde", 55.6415, 12.0803, "By", "By · Sjælland", "place", "city"),
            PlaceSearchResult("Stege", 54.9870, 12.2850, "By", "By · Møn", "place", "town")
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .applyDebugMainThreadCheck()
        .build()

    private val cacheLock = Any()
    private var memoryCache: MutableList<PlaceSearchResult>? = null

    init {
        Log.i(TAG, PHOTON_USAGE_TERMS)
    }

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    fun getCachedPlaces(): List<PlaceSearchResult> {
        synchronized(cacheLock) {
            memoryCache?.let { return it.toList() }
            val loaded = loadCacheFromDisk()
            val initial = if (loaded.isNotEmpty()) {
                loaded.toMutableList()
            } else {
                val seeded = SEED_DANISH_PLACES.take(MAX_CACHED_PLACES).toMutableList()
                saveCacheToDisk(seeded)
                seeded
            }
            memoryCache = initial
            return initial.toList()
        }
    }

    fun cachePlaces(newPlaces: List<PlaceSearchResult>) {
        if (newPlaces.isEmpty()) return
        synchronized(cacheLock) {
            val current = getCachedPlaces().toMutableList()
            for (place in newPlaces.asReversed()) {
                current.removeAll {
                    it.displayName.equals(place.displayName, ignoreCase = true) &&
                        kotlin.math.abs(it.lat - place.lat) < 0.005 &&
                        kotlin.math.abs(it.lng - place.lng) < 0.005
                }
                current.add(0, place)
            }
            val trimmed = current.take(MAX_CACHED_PLACES).toMutableList()
            memoryCache = trimmed
            saveCacheToDisk(trimmed)
        }
    }

    fun searchCachedPlaces(query: String): List<PlaceSearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return getCachedPlaces().filter { place ->
            val haystack = "${place.displayName} ${place.subtitle.orEmpty()} ${place.type}".lowercase()
            tokens.all { token -> haystack.contains(token) }
        }
    }

    /**
     * Searches Photon OpenStreetMap API limited to Denmark when [isOnline] is true,
     * caching the last 50 results and falling back to cached places when offline.
     */
    suspend fun searchPlaces(query: String, isOnline: Boolean): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()

            Log.i(TAG, PHOTON_USAGE_TERMS)

            val cachedMatches = searchCachedPlaces(q)
            if (!isOnline) {
                return@withContext cachedMatches.take(10)
            }

            val remoteResults = try {
                val encodedQuery = URLEncoder.encode(q, "UTF-8")
                val url = "${PHOTON_BASE_URL}?q=$encodedQuery&bbox=$DENMARK_BBOX&limit=12"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkConstants.USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        parsePhotonGeoJson(body)
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photon search failed (using offline cache): ${e.message}")
                emptyList()
            }

            if (remoteResults.isNotEmpty()) {
                cachePlaces(remoteResults)
            }

            val combined = (remoteResults + cachedMatches).distinctBy {
                "${it.displayName.lowercase()}_${(it.lat * 100).toInt()}_${(it.lng * 100).toInt()}"
            }
            combined.take(10)
        }

    internal fun parsePhotonGeoJson(jsonStr: String): List<PlaceSearchResult> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonStr)
            val features = root.optJSONArray("features") ?: return emptyList()
            val results = mutableListOf<PlaceSearchResult>()

            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geom = feature.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue

                val lng = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lng.isFinite()) continue

                // Enforce Denmark geographic bounds
                if (lat !in 54.5..57.8 || lng !in 7.8..15.3) continue

                val props = feature.optJSONObject("properties") ?: JSONObject()
                val countryCode = props.optString("countrycode", "DK")
                if (countryCode.isNotBlank() && !countryCode.equals("DK", ignoreCase = true)) continue

                val name = props.optString("name", "").trim()
                if (name.isBlank()) continue

                val city = props.optString("city", "").trim()
                val county = props.optString("county", "").trim()
                val state = props.optString("state", "").trim()
                val osmKey = props.optString("osm_key", "").trim()
                val osmValue = props.optString("osm_value", "").trim()

                val typeLabel = classifyOsmPlaceType(osmKey, osmValue)
                val locationContext = listOf(city, county, state)
                    .firstOrNull { it.isNotBlank() && !it.equals(name, ignoreCase = true) }

                val subtitle = if (locationContext != null) {
                    "$typeLabel · $locationContext"
                } else {
                    typeLabel
                }

                results.add(
                    PlaceSearchResult(
                        displayName = name,
                        lat = lat,
                        lng = lng,
                        type = typeLabel,
                        subtitle = subtitle,
                        osmKey = osmKey,
                        osmValue = osmValue
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Photon JSON: ${e.message}")
            emptyList()
        }
    }

    private fun classifyOsmPlaceType(osmKey: String, osmValue: String): String {
        val k = osmKey.lowercase()
        val v = osmValue.lowercase()
        return when {
            k == "railway" && (v == "station" || v == "halt" || v == "tram_stop") -> "Station"
            k == "highway" && v == "bus_stop" -> "Stoppested"
            v == "forest" || v == "wood" || v == "national_park" || v == "nature_reserve" -> "Skov"
            v == "water" || v == "lake" || v == "reservoir" -> "Sø"
            k == "place" && (v == "city" || v == "town" || v == "village" || v == "hamlet" || v == "suburb" || v == "municipality") -> "By"
            v == "peak" || v == "hill" || v == "cliff" || v == "cape" || v == "heath" -> "Natur"
            else -> "Sted"
        }
    }

    private fun loadCacheFromDisk(): List<PlaceSearchResult> {
        val f = cacheFile
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<PlaceSearchResult>()
            for (i in 0 until minOf(arr.length(), MAX_CACHED_PLACES)) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("displayName", "").trim()
                val lat = obj.optDouble("lat", Double.NaN)
                val lng = obj.optDouble("lng", Double.NaN)
                if (name.isNotBlank() && lat.isFinite() && lng.isFinite()) {
                    list.add(
                        PlaceSearchResult(
                            displayName = name,
                            lat = lat,
                            lng = lng,
                            type = obj.optString("type", "Sted"),
                            subtitle = obj.optString("subtitle", "").takeIf { it.isNotBlank() },
                            osmKey = obj.optString("osmKey", "").takeIf { it.isNotBlank() },
                            osmValue = obj.optString("osmValue", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Photon place cache: ${e.message}")
            emptyList()
        }
    }

    private fun saveCacheToDisk(places: List<PlaceSearchResult>) {
        try {
            val arr = JSONArray()
            for (p in places.take(MAX_CACHED_PLACES)) {
                val obj = JSONObject()
                obj.put("displayName", p.displayName)
                obj.put("lat", p.lat)
                obj.put("lng", p.lng)
                obj.put("type", p.type)
                if (p.subtitle != null) obj.put("subtitle", p.subtitle)
                if (p.osmKey != null) obj.put("osmKey", p.osmKey)
                if (p.osmValue != null) obj.put("osmValue", p.osmValue)
                arr.put(obj)
            }
            cacheFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save Photon place cache: ${e.message}")
        }
    }
}
