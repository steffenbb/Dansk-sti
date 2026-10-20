package com.example.util.routing

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.data.model.CachedRouteLegEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Online BRouter routing engine implementation.
 *
 * Connects to brouter.de using the free shared routing API, uploads/manages custom
 * hiking profiles, caches route legs in Room for 30 days, debounces user drag requests,
 * enforces a strict rate limit of max 1 call per second, and returns typed errors.
 */
class BRouterOnlineEngine(
    private val dao: DanskStiDao? = null,
    private val assetManager: AssetManager? = null,
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "DanskSti/${BuildConfig.VERSION_NAME} (contact: easysidedk.brydskov@gmail.com)",
    private val baseUrl: String = "https://brouter.de/brouter"
) : RoutingEngine {

    constructor(context: Context) : this(
        dao = DanskStiDatabase.getInstance(context).dao(),
        assetManager = context.assets
    )

    private val requestMutex = Mutex()
    private var lastServerCallTimestamp: Long = 0L

    @Volatile
    private var uploadedProfileId: String? = null
    @Volatile
    private var profileUploadAttempted: Boolean = false

    companion object {
        private const val TAG = "BRouterOnlineEngine"
        private const val MIN_SERVER_CALL_INTERVAL_MS = 1000L
        private const val DEBOUNCE_DELAY_MS = 400L
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days

        @Volatile
        var activeProfileName: String = "dansk-vandring"

        fun isBuiltinFallbackInUse(): Boolean =
            activeProfileName.contains("indbygget", ignoreCase = true) ||
            activeProfileName.startsWith("hiking-mountain")

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): RouteLegResult {
        val cacheKey = generateCacheKey(from, to, options, alternative)

        // 1. Check local Room cache first (valid for 30 days)
        if (dao != null) {
            try {
                val cached = dao.getCachedRouteLeg(cacheKey)
                if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                    val points = deserializePoints(cached.pointsJson)
                    val wayInfo = deserializeWayInfo(cached.wayInfoJson)
                    Log.d(TAG, "Serving route leg from Room cache: $cacheKey")
                    return RouteLegResult.Success(
                        points = points,
                        lengthKm = cached.lengthKm,
                        ascentM = cached.ascentM,
                        descentM = cached.descentM,
                        perPointWayInfo = wayInfo,
                        sourceName = "cache",
                        fetchedAt = cached.cachedAt
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading route from cache", e)
            }
        }

        // 2. Debounce 400 ms while user drags waypoints before executing network call
        delay(DEBOUNCE_DELAY_MS)

        // 3. Concurrency guard: max 1 request at a time
        return requestMutex.withLock {
            // Re-check cache in case another concurrent job already cached it
            if (dao != null) {
                try {
                    val cached = dao.getCachedRouteLeg(cacheKey)
                    if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                        val points = deserializePoints(cached.pointsJson)
                        val wayInfo = deserializeWayInfo(cached.wayInfoJson)
                        return@withLock RouteLegResult.Success(
                            points = points,
                            lengthKm = cached.lengthKm,
                            ascentM = cached.ascentM,
                            descentM = cached.descentM,
                            perPointWayInfo = wayInfo,
                            sourceName = "cache",
                            fetchedAt = cached.cachedAt
                        )
                    }
                } catch (_: Exception) {}
            }

            // 4. Rate-limiting: nothing calls the server more than once per second
            val now = System.currentTimeMillis()
            val elapsed = now - lastServerCallTimestamp
            if (elapsed < MIN_SERVER_CALL_INTERVAL_MS) {
                delay(MIN_SERVER_CALL_INTERVAL_MS - elapsed)
            }

            // 5. Determine profile to use (custom uploaded or built-in fallback)
            val profileToUse = getOrUploadProfile()

            // 6. Build BRouter request URL
            val requestUrl = buildRoutingUrl(from, to, options, alternative, profileToUse)
            val request = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", userAgent)
                .get()
                .build()

            // 7. Execute request and record server timestamp
            lastServerCallTimestamp = System.currentTimeMillis()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    val code = response.code

                    when {
                        code == 429 || code in 500..599 -> {
                            Log.w(TAG, "BRouter server busy: HTTP $code")
                            return@withLock RouteLegResult.ServerBusy("Ruteserveren er optaget - prøv igen")
                        }
                        code != 200 -> {
                            Log.w(TAG, "BRouter error HTTP $code: $responseBody")
                            val isUnknownProfile = responseBody.contains("profile", ignoreCase = true) ||
                                    responseBody.contains("not found", ignoreCase = true) ||
                                    responseBody.contains("unknown", ignoreCase = true) ||
                                    code == 400

                            if (isUnknownProfile && uploadedProfileId != null) {
                                Log.i(TAG, "Profile $uploadedProfileId unknown to BRouter. Re-uploading once and retrying.")
                                uploadedProfileId = null
                                profileUploadAttempted = false
                                val retryProfile = getOrUploadProfile()
                                val retryUrl = buildRoutingUrl(from, to, options, alternative, retryProfile)
                                val retryReq = Request.Builder()
                                    .url(retryUrl)
                                    .header("User-Agent", userAgent)
                                    .get()
                                    .build()
                                lastServerCallTimestamp = System.currentTimeMillis()
                                val retryResp = client.newCall(retryReq).execute()
                                return@withLock retryResp.use { r2 ->
                                    val r2Body = r2.body?.string().orEmpty()
                                    if (r2.isSuccessful) {
                                        parseGeoJsonResponse(
                                            jsonStr = r2Body,
                                            from = from,
                                            to = to,
                                            options = options,
                                            alternative = alternative,
                                            cacheKey = cacheKey,
                                            profileUsed = retryProfile
                                        )
                                    } else {
                                        RouteLegResult.NoRoute("Ingen rute fundet: ${r2Body.take(200).trim()}")
                                    }
                                }
                            }

                            val cleanMsg = responseBody.take(200).trim()
                            return@withLock RouteLegResult.NoRoute("Ingen rute fundet: $cleanMsg")
                        }
                    }

                    // 8. Parse GeoJSON response
                    parseGeoJsonResponse(
                        jsonStr = responseBody,
                        from = from,
                        to = to,
                        options = options,
                        alternative = alternative,
                        cacheKey = cacheKey,
                        profileUsed = profileToUse
                    )
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network failure calling BRouter", e)
                RouteLegResult.Offline("Ingen internetforbindelse")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error routing leg", e)
                RouteLegResult.Failure("Fejl ved ruteberegning: ${e.message}", e)
            }
        }
    }

    /**
     * Resolves the profile to use, attempting custom profile upload first,
     * or falling back to BRouter's built-in hiking-mountain profile.
     */
    private suspend fun getOrUploadProfile(): String {
        uploadedProfileId?.let {
            activeProfileName = "dansk-vandring ($it)"
            return it
        }

        if (!profileUploadAttempted) {
            profileUploadAttempted = true
            val profileText = loadCustomProfileContent()
            if (profileText.isNotBlank()) {
                val uploadedId = uploadProfileToServer(profileText)
                if (!uploadedId.isNullOrBlank()) {
                    uploadedProfileId = uploadedId
                    activeProfileName = "dansk-vandring ($uploadedId)"
                    Log.i(TAG, "Active BRouter profile: $uploadedId (custom profile uploaded successfully)")
                    return uploadedId
                }
            }
        }

        activeProfileName = "hiking-mountain (indbygget fallback)"
        Log.i(TAG, "Active BRouter profile: hiking-mountain (fallback built-in profile used)")
        return "hiking-mountain"
    }

    /**
     * Loads the dansk-vandring.brf custom profile from assets or fallback bundle.
     */
    private fun loadCustomProfileContent(): String {
        return try {
            assetManager?.open("brouter/dansk-vandring.brf")?.bufferedReader()?.use { it.readText() }
                ?: javaClass.classLoader?.getResourceAsStream("assets/brouter/dansk-vandring.brf")?.bufferedReader()?.use { it.readText() }
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Could not load dansk-vandring.brf from assets", e)
            ""
        }
    }

    /**
     * Uploads the custom profile to BRouter's profile-upload endpoint.
     */
    private fun uploadProfileToServer(profileContent: String): String? {
        return try {
            val uploadUrl = "https://brouter.de/brouter/profile"
            val body = profileContent.toRequestBody("text/plain".toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .header("User-Agent", userAgent)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respJson = JSONObject(response.body?.string().orEmpty())
                    if (!respJson.has("error") && respJson.has("profileid")) {
                        return respJson.getString("profileid")
                    } else if (respJson.has("error")) {
                        Log.w(TAG, "Profile upload syntax error: ${respJson.optString("error")}")
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Profile upload HTTP request failed", e)
            null
        }
    }

    /**
     * Constructs the BRouter HTTP query URL with all options, coordinates in LON,LAT format.
     */
    fun buildRoutingUrl(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        profileName: String
    ): String {
        val sb = StringBuilder(baseUrl)
        // Order is LON,LAT
        sb.append(String.format(Locale.US, "?lonlats=%.6f,%.6f|%.6f,%.6f", from.lng, from.lat, to.lng, to.lat))
        sb.append("&profile=").append(profileName)
        sb.append("&alternativeidx=").append(alternative.coerceIn(0, 3))
        sb.append("&format=geojson")

        // No-go circular points: nogos=<lon,lat,radius[,weight]|...>
        if (options.noGoPoints.isNotEmpty()) {
            val nogosStr = options.noGoPoints.joinToString("|") {
                if (it.weight != null) {
                    String.format(Locale.US, "%.6f,%.6f,%.0f,%.0f", it.lng, it.lat, it.radiusM, it.weight)
                } else {
                    String.format(Locale.US, "%.6f,%.6f,%.0f", it.lng, it.lat, it.radiusM)
                }
            }
            sb.append("&nogos=").append(nogosStr)
        }

        // No-go polygons: polygons=<lon,lat,lon,lat,...|...>
        if (options.noGoAreas.isNotEmpty()) {
            val polysStr = options.noGoAreas.joinToString("|") { poly ->
                poly.joinToString(",") { String.format(Locale.US, "%.6f,%.6f", it.lng, it.lat) }
            }
            sb.append("&polygons=").append(polysStr)
        }

        // Profile parameter overrides
        sb.append(String.format(Locale.US, "&profile:avoid_roads=%.2f", options.avoidRoads.coerceIn(0.0, 1.0)))
        sb.append("&profile:prefer_nature=").append(if (options.preferNature) "1" else "0")
        sb.append("&profile:allow_ferries=").append(if (options.allowFerries) "1" else "0")

        return sb.toString()
    }

    /**
     * Parses the BRouter GeoJSON LineString and messages table, mapping way info to route points.
     */
    private suspend fun parseGeoJsonResponse(
        jsonStr: String,
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        cacheKey: String,
        profileUsed: String
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
                    sourceName = "brouter.de",
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
            sourceName = "brouter.de",
            fetchedAt = System.currentTimeMillis()
        )
    }

    /**
     * Parses the BRouter messages 2D table into section-level WayInfo and lengths.
     */
    private fun parseMessagesTable(messages: JSONArray?): List<Pair<Double, WayInfo>> {
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
        var hadForestClass = false

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

            if (tagMap.containsKey("estimated_forest_class") || tagMap.containsKey("forestClass")) {
                hadForestClass = true
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

        if (!hadForestClass) {
            Log.d(TAG, "Danish route data lacks estimated_forest_class tags; skipping environmental penalty calculation.")
        }

        return sections
    }

    /**
     * Maps each route point to its corresponding WayInfo section.
     */
    private fun correlateWayInfoToPoints(
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

    private fun calculateAscent(points: List<RoutePoint3D>): Double {
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

    private fun calculateDescent(points: List<RoutePoint3D>): Double {
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

    private fun calculateDistanceKm(points: List<RoutePoint3D>): Double {
        var totalM = 0.0
        for (i in 0 until points.size - 1) {
            totalM += distanceMeters(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng)
        }
        return totalM / 1000.0
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDist = Math.toRadians(lat2 - lat1)
        val lonDist = Math.toRadians(lon2 - lon1)
        val a = Math.sin(latDist / 2) * Math.sin(latDist / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(lonDist / 2) * Math.sin(lonDist / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371000.0 * c
    }

    private fun generateCacheKey(
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

    private fun serializePoints(points: List<RoutePoint3D>): String {
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

    private fun deserializePoints(jsonStr: String): List<RoutePoint3D> {
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

    private fun serializeWayInfo(wayInfo: List<WayInfo>): String {
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

    private fun deserializeWayInfo(jsonStr: String): List<WayInfo> {
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
