package com.example.util.routing

import android.content.Context
import android.util.Log
import btools.router.FormatJson
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingEngine as CoreRoutingEngine
import btools.router.RoutingParamCollector
import com.example.data.local.DanskStiDao
import com.example.data.local.DanskStiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Offline BRouter routing engine implementation.
 *
 * Runs the BRouter Java routing core natively on-device using local .rd5 segment files
 * downloaded into app storage and the dansk-vandring.brf custom profile.
 * Operates without signal or internet connectivity.
 */
class BRouterOfflineEngine(
    private val context: Context,
    private val dao: DanskStiDao? = null,
    private val storageManager: BRouterStorageManager = BRouterStorageManager.getInstance(context)
) : RoutingEngine {

    constructor(context: Context) : this(
        context = context,
        dao = DanskStiDatabase.getInstance(context).dao(),
        storageManager = BRouterStorageManager.getInstance(context)
    )

    private val routingMutex = Mutex()

    companion object {
        private const val TAG = "BRouterOfflineEngine"
        private const val MAX_RUNNING_TIME_MS = 15000L
    }

    override suspend fun routeLeg(
        from: LatLng,
        to: LatLng,
        options: RouteOptions,
        alternative: Int
    ): RouteLegResult = withContext(Dispatchers.IO) {
        val cacheKey = BRouterGeoJsonParser.generateCacheKey(from, to, options, alternative)

        // 1. Check local Room database cache first
        if (dao != null) {
            try {
                val cached = dao.getCachedRouteLeg(cacheKey)
                if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                    val points = BRouterGeoJsonParser.deserializePoints(cached.pointsJson)
                    val wayInfo = BRouterGeoJsonParser.deserializeWayInfo(cached.wayInfoJson)
                    Log.d(TAG, "Serving offline route leg from Room cache: $cacheKey")
                    return@withContext RouteLegResult.Success(
                        points = points,
                        lengthKm = cached.lengthKm,
                        ascentM = cached.ascentM,
                        descentM = cached.descentM,
                        perPointWayInfo = wayInfo,
                        sourceName = "brouter-offline",
                        fetchedAt = cached.cachedAt
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading route from cache", e)
            }
        }

        // 2. Concurrency guard: CoreRoutingEngine runs search sequentially
        routingMutex.withLock {
            // Re-check cache inside lock
            if (dao != null) {
                try {
                    val cached = dao.getCachedRouteLeg(cacheKey)
                    if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                        val points = BRouterGeoJsonParser.deserializePoints(cached.pointsJson)
                        val wayInfo = BRouterGeoJsonParser.deserializeWayInfo(cached.wayInfoJson)
                        return@withLock RouteLegResult.Success(
                            points = points,
                            lengthKm = cached.lengthKm,
                            ascentM = cached.ascentM,
                            descentM = cached.descentM,
                            perPointWayInfo = wayInfo,
                            sourceName = "brouter-offline",
                            fetchedAt = cached.cachedAt
                        )
                    }
                } catch (_: Exception) {}
            }

            // 3. Verify that the required segment files covering both leg ends are present on disk
            val segFrom = BRouterStorageManager.getSegmentFilenameForCoordinate(from.lat, from.lng)
            val segTo = BRouterStorageManager.getSegmentFilenameForCoordinate(to.lat, to.lng)

            if (!storageManager.isSegmentDownloaded(segFrom) || !storageManager.isSegmentDownloaded(segTo)) {
                val missing = mutableListOf<String>()
                if (!storageManager.isSegmentDownloaded(segFrom)) missing.add(segFrom)
                if (!storageManager.isSegmentDownloaded(segTo)) missing.add(segTo)
                Log.w(TAG, "Missing offline segment files: ${missing.joinToString(", ")}")
                return@withLock RouteLegResult.Offline(
                    "Mangler offline rutedata for denne strækning (${missing.joinToString(", ")}). Hent rutedata for Danmark under Offline."
                )
            }

            try {
                // 4. Ensure profiles and lookups.dat are present in profiles directory
                storageManager.ensureProfilesCopied(context)
                System.setProperty("profileBaseDir", storageManager.profilesDir.absolutePath)

                // 5. Configure RoutingContext
                val rc = RoutingContext()
                rc.localFunction = "dansk-vandring"

                // Pass profile expression parameters
                val profileParams = mutableMapOf<String, String>()
                profileParams["avoid_roads"] = String.format(Locale.US, "%.2f", options.avoidRoads.coerceIn(0.0, 1.0))
                profileParams["prefer_nature"] = if (options.preferNature) "1" else "0"
                profileParams["allow_ferries"] = if (options.allowFerries) "1" else "0"
                rc.keyValues = profileParams
                rc.alternativeIdx = alternative.coerceIn(0, 3)
                rc.outputFormat = "geojson"

                // 6. Parse profile
                ProfileCache.parseProfile(rc)
                rc.initModel()

                // 7. Prepare waypoints using RoutingParamCollector
                val rpc = RoutingParamCollector()
                val waypoints = rpc.readPositions(
                    doubleArrayOf(from.lng, to.lng),
                    doubleArrayOf(from.lat, to.lat)
                )

                if (waypoints.size < 2) {
                    return@withLock RouteLegResult.Failure("Ugyldige rutepunkter for offline beregning")
                }

                // 8. Create and run BRouter core routing engine
                val engine = CoreRoutingEngine(
                    null,
                    null,
                    storageManager.segmentsDir,
                    waypoints,
                    rc
                )

                val startTime = System.currentTimeMillis()
                engine.doRun(MAX_RUNNING_TIME_MS)
                val durationMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "Offline routing completed in ${durationMs}ms")

                val track = engine.foundTrack
                if (track == null || track.nodes.isNullOrEmpty()) {
                    val errMsg = engine.errorMessage ?: "Ingen offline rute fundet mellem punkterne"
                    Log.w(TAG, "No offline route found: $errMsg")
                    return@withLock RouteLegResult.NoRoute("Ingen offline rute fundet: $errMsg")
                }

                // 9. Format track to standard GeoJSON
                val formatJson = FormatJson(rc)
                val geoJsonStr = formatJson.format(track)

                // 10. Parse and correlate way tags to points, then cache
                BRouterGeoJsonParser.parseGeoJsonResponse(
                    jsonStr = geoJsonStr,
                    from = from,
                    to = to,
                    options = options,
                    alternative = alternative,
                    cacheKey = cacheKey,
                    sourceName = "brouter-offline",
                    dao = dao
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception during offline routing", e)
                RouteLegResult.Failure("Fejl ved offline ruteberegning: ${e.message}", e)
            }
        }
    }
}
