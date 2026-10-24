package com.example.util.routing

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import com.example.util.network.NetworkConstants
import com.example.util.network.applyDebugMainThreadCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Online BRouter routing engine implementation.
 *
 * Calls https://brouter.de/brouter with custom profile upload (`dansk-vandring.brf` -> `custom_xxx`)
 * and falls back to `hiking-mountain` if custom profile upload is not supported by the server.
 * Caches every routed leg in Room for 30 days.
 *
 * All network and cache operations run strictly on Dispatchers.IO.
 */
class BRouterOnlineEngine(
    private val dao: DanskStiDao? = null,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val context: Context? = null,
    private val assetManager: android.content.res.AssetManager? = null
) : RoutingEngine {

    constructor(context: Context) : this(
        dao = try { DanskStiDatabase.getInstance(context).dao() } catch (_: Exception) { null },
        client = defaultClient(),
        baseUrl = DEFAULT_BASE_URL,
        context = context,
        assetManager = context.assets
    )

    companion object {
        private const val TAG = "BRouterOnlineEngine"
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter"
        const val CUSTOM_PROFILE_NAME = "dansk-vandring"
        const val FALLBACK_PROFILE_NAME = "hiking-mountain"
        const val CACHE_TTL_MS = BRouterGeoJsonParser.CACHE_TTL_MS
        private const val NETWORK_RETRY_COOLDOWN_MS = 60_000L
        private const val MIN_REQUEST_INTERVAL_MS = 1000L

        @Volatile
        private var uploadedServerProfileId: String? = null

        @Volatile
        private var profileUploadAttempted: Boolean = false

        @Volatile
        private var lastProfileUploadFailureMs: Long = 0L

        @Volatile
        private var usingBuiltinFallback: Boolean = false

        @Volatile
        private var lastProfileSyntaxError: String? = null

        private val profileUploadMutex = Mutex()
        private val rateLimitLock = Any()
        @Volatile
        private var lastServerRequestMs: Long = 0L

        fun isBuiltinFallbackInUse(): Boolean = usingBuiltinFallback

        val activeProfileName: String
            get() = getActiveProfileDisplayName()

        fun getActiveProfileDisplayName(): String {
            val id = uploadedServerProfileId
            return if (id != null && !usingBuiltinFallback) {
                "dansk-vandring.brf ($id)"
            } else {
                "$FALLBACK_PROFILE_NAME (standard)"
            }
        }

        fun getProfileSyntaxError(): String? = lastProfileSyntaxError

        fun resetStateForTesting() {
            uploadedServerProfileId = null
            profileUploadAttempted = false
            lastProfileUploadFailureMs = 0L
            usingBuiltinFallback = false
            lastProfileSyntaxError = null
            lastServerRequestMs = 0L
        }

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .applyDebugMainThreadCheck()
                .build()
        }

        /**
         * Checks whether the server's HTTP 400 error text specifically indicates
         * that the custom profile is unknown or missing on the server.
         */
        fun isUnknownProfileError(rawError: String?): Boolean {
            if (rawError.isNullOrBlank()) return false
            val lower = rawError.lowercase(Locale.ROOT)
            if (lower.contains("position not mapped") ||
                lower.contains("not mapped in existing datafile") ||
                lower.contains("target island") ||
                lower.contains("operation killed") ||
                lower.contains("time out") ||
                lower.contains("timeout")
            ) {
                return false
            }
            val mentionsProfile = lower.contains("profile") || lower.contains("custom_") || lower.contains(".brf")
            val mentionsMissing = lower.contains("not found") ||
                lower.contains("unknown") ||
                lower.contains("does not exist") ||
                lower.contains("no such file") ||
                lower.contains("missing") ||
                lower.contains("cannot read") ||
                lower.contains("filenotfound")
            return mentionsProfile && mentionsMissing
        }

        /**
         * Translates BRouter server error messages or exceptions into clear Danish user-facing explanations.
         * Never returns "null" or raw English text.
         */
        fun translateBRouterError(rawError: String?, isTimeoutException: Boolean = false): String {
            if (isTimeoutException) {
                return "Strækningen er for lang til én beregning. Tilføj et punkt imellem."
            }
            val lower = rawError?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lower.contains("position not mapped") || lower.contains("not mapped in existing datafile") ->
                    "Punktet ligger for langt fra en sti eller vej. Flyt det tættere på en sti."
                lower.contains("target island") ->
                    "Punktet ligger på en ø eller et område uden forbindelse. Prøv et andet punkt."
                lower.contains("operation killed") || lower.contains("time out") || lower.contains("timeout") ->
                    "Strækningen er for lang til én beregning. Tilføj et punkt imellem."
                else ->
                    "Ruten kunne ikke beregnes. Prøv igen."
            }
        }
    }

    /**
     * Uploads `dansk-vandring.brf` to `https://brouter.de/brouter/profile` and returns
     * the server-assigned `profileid` (e.g. `custom_1234567890`), or null if upload fails.
     *
     * Runs strictly on Dispatchers.IO.
     * Sets `profileUploadAttempted = true` ONLY after the server has answered (success or syntax error).
     * On network failure, allows a new attempt after 60 seconds.
     */
    suspend fun uploadProfileToServer(): String? = withContext(Dispatchers.IO) {
        uploadedServerProfileId?.let { return@withContext it }

        profileUploadMutex.withLock {
            uploadedServerProfileId?.let { return@withContext it }

            val now = System.currentTimeMillis()
            if (profileUploadAttempted) return@withContext uploadedServerProfileId
            if (lastProfileUploadFailureMs > 0L && (now - lastProfileUploadFailureMs) < NETWORK_RETRY_COOLDOWN_MS) {
                return@withContext null
            }

            val assets = assetManager ?: context?.assets ?: return@withContext null
            val brfContent = try {
                assets.open("brouter/dansk-vandring.brf").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read dansk-vandring.brf from assets: ${e.message}")
                return@withContext null
            }

            if (brfContent.isBlank()) return@withContext null

            try {
                val uploadUrl = if (baseUrl.endsWith("/")) "${baseUrl}profile" else "$baseUrl/profile"
                val reqBody = brfContent.toRequestBody("text/plain; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url(uploadUrl)
                    .header("User-Agent", NetworkConstants.USER_AGENT)
                    .post(reqBody)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty().trim()
                    val code = resp.code

                    if (resp.isSuccessful && body.isNotEmpty()) {
                        val profileId = if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val serverErr = json.optString("error", "").takeIf { it.isNotBlank() }
                            if (serverErr != null) {
                                profileUploadAttempted = true
                                lastProfileSyntaxError = serverErr
                                usingBuiltinFallback = true
                                Log.e(TAG, "BRouter server rejected profile syntax: $serverErr")
                                return@withContext null
                            }
                            json.optString("profileid", "").takeIf { it.isNotBlank() }
                        } else if (body.startsWith("custom_") && !body.contains(" ") && body.length < 64) {
                            body
                        } else {
                            profileUploadAttempted = true
                            lastProfileSyntaxError = body
                            usingBuiltinFallback = true
                            Log.e(TAG, "BRouter server rejected profile syntax: $body")
                            return@withContext null
                        }

                        if (!profileId.isNullOrBlank()) {
                            profileUploadAttempted = true
                            lastProfileSyntaxError = null
                            uploadedServerProfileId = profileId
                            usingBuiltinFallback = false
                            Log.i(TAG, "Uploaded dansk-vandring.brf to BRouter server: profileid=$profileId")
                            return@withContext profileId
                        }
                    } else if (code in 400..499) {
                        val exactError = body.ifBlank { "HTTP $code ved upload af dansk-vandring.brf" }
                        profileUploadAttempted = true
                        lastProfileSyntaxError = exactError
                        usingBuiltinFallback = true
                        Log.e(TAG, "BRouter server rejected profile upload (HTTP $code): $exactError")
                        return@withContext null
                    } else {
                        lastProfileUploadFailureMs = System.currentTimeMillis()
                        Log.w(TAG, "Profile upload transient server error HTTP $code: $body")
                    }
                }
            } catch (e: Exception) {
                if (e is IllegalStateException && e.message == "Network on main thread") {
                    throw e
                }
                lastProfileUploadFailureMs = System.currentTimeMillis()
                Log.w(TAG, "Profile upload to BRouter failed (will retry after 60s): ${e.message}")
            }
            usingBuiltinFallback = true
            return@withContext null
        }
    }

    private suspend fun enforceRateLimit() {
        val waitMs = synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastServerRequestMs
            val wait = if (lastServerRequestMs > 0L && elapsed < MIN_REQUEST_INTERVAL_MS) {
                MIN_REQUEST_INTERVAL_MS - elapsed
            } else {
                0L
            }
            lastServerRequestMs = now + wait
            wait
        }
        if (waitMs > 0L) {
            kotlinx.coroutines.delay(waitMs)
        }
    }

    override suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): RouteLegResult = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(from, to, options, alternative)

        // 1. Check local Room database cache first
        if (dao != null) {
            try {
                val cached = dao.getCachedRouteLeg(cacheKey)
                if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                    val points = deserializePoints(cached.pointsJson)
                    val wayInfo = deserializeWayInfo(cached.wayInfoJson)
                    return@withContext RouteLegResult.Success(
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
                Log.w(TAG, "Failed reading route leg from Room cache", e)
            }
        }

        // 2. Upload custom profile or determine fallback profile
        val customProfileId = uploadProfileToServer()
        val initialProfile = customProfileId ?: run {
            usingBuiltinFallback = true
            FALLBACK_PROFILE_NAME
        }

        val primaryResult = executeRouteRequest(
            from = from,
            to = to,
            options = options,
            alternative = alternative,
            profileName = initialProfile,
            cacheKey = cacheKey
        )

        return@withContext primaryResult
    }

    private suspend fun executeRouteRequest(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        profileName: String,
        cacheKey: String
    ): RouteLegResult = withContext(Dispatchers.IO) {
        val url = buildUrl(from, to, options, alternative, profileName)
            ?: return@withContext RouteLegResult.Failure(translateBRouterError(null))

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkConstants.USER_AGENT)
            .get()
            .build()

        try {
            enforceRateLimit()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string().orEmpty()

                if (code == 429 || code == 503) {
                    Log.w(TAG, "BRouter server busy (HTTP $code): $bodyStr")
                    return@withContext RouteLegResult.ServerBusy("Ruteserveren er optaget - prøv igen")
                }

                if (code == 400) {
                    Log.w(TAG, "BRouter HTTP 400 with profile '$profileName': $bodyStr")

                    // Requirement 4: Re-upload the profile and retry ONLY when the server's error text
                    // says the profile is unknown/missing. Any other HTTP 400 returns NoRoute without a second request.
                    if (profileName != FALLBACK_PROFILE_NAME && isUnknownProfileError(bodyStr)) {
                        Log.i(TAG, "Server reported unknown/missing profile '$profileName'. Re-uploading and retrying once.")
                        uploadedServerProfileId = null
                        profileUploadAttempted = false
                        lastProfileUploadFailureMs = 0L
                        val reuploadedProfileId = uploadProfileToServer()
                        val retryProfile = reuploadedProfileId ?: run {
                            usingBuiltinFallback = true
                            FALLBACK_PROFILE_NAME
                        }
                        val retryUrl = buildUrl(from, to, options, alternative, retryProfile)
                        if (retryUrl != null) {
                            val retryReq = Request.Builder()
                                .url(retryUrl)
                                .header("User-Agent", NetworkConstants.USER_AGENT)
                                .get()
                                .build()
                            enforceRateLimit()
                            client.newCall(retryReq).execute().use { retryResp ->
                                val retryCode = retryResp.code
                                val retryBody = retryResp.body?.string().orEmpty()
                                if (retryResp.isSuccessful && retryBody.isNotBlank()) {
                                    return@withContext parseGeoJsonResponse(retryBody, from, to, options, alternative, cacheKey)
                                } else {
                                    Log.w(TAG, "BRouter retry failed (HTTP $retryCode): $retryBody")
                                    return@withContext RouteLegResult.NoRoute(translateBRouterError(retryBody))
                                }
                            }
                        }
                    }

                    return@withContext RouteLegResult.NoRoute(translateBRouterError(bodyStr))
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "BRouter HTTP $code: $bodyStr")
                    val isTimeout = code == 408 || code == 504
                    return@withContext RouteLegResult.Failure(translateBRouterError(bodyStr, isTimeoutException = isTimeout))
                }

                if (bodyStr.isBlank()) {
                    Log.e(TAG, "BRouter returned empty response body")
                    return@withContext RouteLegResult.Failure(translateBRouterError(null))
                }

                return@withContext parseGeoJsonResponse(bodyStr, from, to, options, alternative, cacheKey)
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "BRouter timeout failure: ${e.message}", e)
            return@withContext RouteLegResult.Failure(translateBRouterError(e.message, isTimeoutException = true), e)
        } catch (e: IOException) {
            Log.w(TAG, "Network/IO error calling BRouter: ${e.message}", e)
            val isTimeout = e.message?.lowercase(Locale.ROOT)?.contains("timeout") == true
            val msg = if (isTimeout) translateBRouterError(e.message, isTimeoutException = true) else "Ingen internetforbindelse"
            return@withContext RouteLegResult.Offline(msg)
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message == "Network on main thread") {
                throw e
            }
            Log.e(TAG, "Failed parsing BRouter response: ${e.message}", e)
            return@withContext RouteLegResult.Failure(translateBRouterError(e.message), e)
        }
    }

    internal fun buildUrl(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        profileName: String
    ): String? {
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        val lonlats = String.format(
            Locale.US,
            "%.6f,%.6f|%.6f,%.6f",
            from.lng,
            from.lat,
            to.lng,
            to.lat
        )

        val builder = httpUrl.newBuilder()
            .addQueryParameter("lonlats", lonlats)
            .addQueryParameter("profile", profileName)
            .addQueryParameter("alternativeidx", alternative.coerceIn(0, 3).toString())
            .addQueryParameter("format", "geojson")

        // Pass weighted no-go points for loop routing (format: lon,lat,radius,weight|...)
        if (options.noGoPoints.isNotEmpty()) {
            val nogosParam = options.noGoPoints.joinToString("|") { nogo ->
                String.format(
                    Locale.US,
                    "%.6f,%.6f,%.0f,%.0f",
                    nogo.lng,
                    nogo.lat,
                    nogo.radiusM,
                    nogo.weight
                )
            }
            builder.addQueryParameter("nogos", nogosParam)
        }

        // Pass polygonal no-go areas ("Rut uden om") as polygons=lon,lat,lon,lat,...|...
        if (options.noGoAreas.isNotEmpty()) {
            val polygonsParam = options.noGoAreas
                .filter { it.size >= 3 }
                .joinToString("|") { ring ->
                    ring.joinToString(",") { pt ->
                        String.format(Locale.US, "%.6f,%.6f", pt.lng, pt.lat)
                    }
                }
            if (polygonsParam.isNotEmpty()) {
                builder.addEncodedQueryParameter("polygons", polygonsParam)
            }
        }

        // Only send custom profile parameters when using custom uploaded profile
        if (profileName != FALLBACK_PROFILE_NAME) {
            builder
                .addQueryParameter("profile:avoid_roads", String.format(Locale.US, "%.2f", options.avoidRoads.coerceIn(0.0, 1.0)))
                .addQueryParameter("profile:prefer_nature", if (options.preferNature) "1" else "0")
                .addQueryParameter("profile:allow_ferries", if (options.allowFerries) "1" else "0")
        }

        return builder.build().toString()
    }

    internal suspend fun parseGeoJsonResponse(
        jsonStr: String,
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int,
        cacheKey: String
    ): RouteLegResult {
        return BRouterGeoJsonParser.parseGeoJsonResponse(
            jsonStr = jsonStr,
            from = from,
            to = to,
            options = options,
            alternative = alternative,
            cacheKey = cacheKey,
            sourceName = "brouter.de",
            dao = dao
        )
    }

    internal fun parseMessagesTable(messages: org.json.JSONArray?): List<Pair<Double, WayInfo>> =
        BRouterGeoJsonParser.parseMessagesTable(messages)

    internal fun correlateWayInfoToPoints(
        points: List<RoutePoint3D>,
        sections: List<Pair<Double, WayInfo>>
    ): List<WayInfo> = BRouterGeoJsonParser.correlateWayInfoToPoints(points, sections)

    internal fun generateCacheKey(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): String = BRouterGeoJsonParser.generateCacheKey(from, to, options, alternative)

    internal fun serializePoints(points: List<RoutePoint3D>): String =
        BRouterGeoJsonParser.serializePoints(points)

    internal fun deserializePoints(jsonStr: String): List<RoutePoint3D> =
        BRouterGeoJsonParser.deserializePoints(jsonStr)

    internal fun serializeWayInfo(wayInfo: List<WayInfo>): String =
        BRouterGeoJsonParser.serializeWayInfo(wayInfo)

    internal fun deserializeWayInfo(jsonStr: String): List<WayInfo> =
        BRouterGeoJsonParser.deserializeWayInfo(jsonStr)
}
