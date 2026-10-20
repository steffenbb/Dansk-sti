package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.util.GeoUtils
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutingEngineTest {

    private lateinit var database: DanskStiDatabase

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        database = Room.inMemoryDatabaseBuilder(app, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testProfileSyntaxAcceptedByBRouterServer() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val profileContent = app.assets.open("brouter/dansk-vandring.brf")
            .bufferedReader().use { it.readText() }

        assertTrue("Profile content should not be empty", profileContent.isNotBlank())
        assertTrue("Profile should contain global context", profileContent.contains("---context:global"))
        assertTrue("Profile should contain way context", profileContent.contains("---context:way"))
        assertTrue("Profile should contain node context", profileContent.contains("---context:node"))

        // Test that BRouter server accepts the profile
        val client = OkHttpClient()
        val body = okhttp3.RequestBody.Companion.run {
            profileContent.toRequestBody("text/plain".toMediaType())
        }
        val request = okhttp3.Request.Builder()
            .url("https://brouter.de/brouter/profile")
            .header("User-Agent", "DanskStiTest/1.0")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals("Profile upload should return HTTP 200", 200, response.code)
            val json = org.json.JSONObject(response.body?.string().orEmpty())
            assertTrue("Server should return profileid", json.has("profileid"))
            val hasError = json.has("error")
            if (hasError) {
                org.junit.Assert.fail("Server rejected profile with error: ${json.getString("error")}")
            }
        }
    }

    @Test
    fun testRouteLegInRoldSkov_PathTrackShareHigherWithAvoidRoads() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val engine = BRouterOnlineEngine(dao = database.dao(), assetManager = app.assets)

        // Skørping station to Rebild Bakker inside Rold Skov
        val from = LatLng(lat = 56.835, lng = 9.890)
        val to = LatLng(lat = 56.830, lng = 9.840)

        // 1. Route with avoidRoads = 0.0
        val resultLowAvoid = engine.routeLeg(
            from = from,
            to = to,
            options = RouteOptions(avoidRoads = 0.0, preferNature = true)
        )
        assertTrue("Expected Success with avoidRoads=0.0", resultLowAvoid is RouteLegResult.Success)
        val successLow = resultLowAvoid as RouteLegResult.Success
        assertTrue("Points should not be empty", successLow.points.isNotEmpty())
        assertTrue("Route should have real length in km", successLow.lengthKm > 2.0)
        assertTrue("Points should include elevation values", successLow.points.any { it.ele != null && it.ele > 0.0 })

        // 2. Route with avoidRoads = 1.0
        val resultHighAvoid = engine.routeLeg(
            from = from,
            to = to,
            options = RouteOptions(avoidRoads = 1.0, preferNature = true)
        )
        assertTrue("Expected Success with avoidRoads=1.0", resultHighAvoid is RouteLegResult.Success)
        val successHigh = resultHighAvoid as RouteLegResult.Success

        // Calculate path/footway/track share by distance for both routes
        fun calculatePathTrackShare(result: RouteLegResult.Success): Double {
            var pathTrackM = 0.0
            var totalM = 0.0
            for (i in 1 until result.points.size) {
                val p1 = result.points[i - 1]
                val p2 = result.points[i]
                val d = GeoUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng)
                totalM += d
                val way = result.perPointWayInfo.getOrNull(i) ?: result.perPointWayInfo.getOrNull(i - 1)
                val hw = way?.highway.orEmpty().lowercase()
                if (hw == "path" || hw == "footway" || hw == "track" || hw == "bridleway") {
                    pathTrackM += d
                }
            }
            return if (totalM > 0.0) pathTrackM / totalM else 0.0
        }

        val shareLow = calculatePathTrackShare(successLow)
        val shareHigh = calculatePathTrackShare(successHigh)

        assertTrue(
            "Share of path/track with avoidRoads=1.0 ($shareHigh) should be higher than with avoidRoads=0.0 ($shareLow)",
            shareHigh > shareLow
        )
    }

    @Test
    fun testSeaPointReturnsNoRouteNotStraightLine() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val engine = BRouterOnlineEngine(dao = database.dao(), assetManager = app.assets)

        // Point in Denmark to open North Sea (no land route)
        val from = LatLng(lat = 56.835, lng = 9.890)
        val toSea = LatLng(lat = 56.000, lng = 6.000)

        val result = engine.routeLeg(
            from = from,
            to = toSea,
            options = RouteOptions(avoidRoads = 0.5, allowFerries = false)
        )

        assertTrue("Routing to open sea should return NoRoute, got: $result", result is RouteLegResult.NoRoute)
        val noRoute = result as RouteLegResult.NoRoute
        assertTrue("NoRoute message should be descriptive", noRoute.message.isNotBlank())
    }

    @Test
    fun testSameRequestComesFromCacheSecondTime() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val engine = BRouterOnlineEngine(dao = database.dao(), assetManager = app.assets)

        val from = LatLng(lat = 56.835, lng = 9.890)
        val to = LatLng(lat = 56.830, lng = 9.840)
        val options = RouteOptions(avoidRoads = 0.7, preferNature = true)

        // First call: fetched from server
        val firstResult = engine.routeLeg(from = from, to = to, options = options)
        assertTrue("First call should succeed", firstResult is RouteLegResult.Success)
        val firstSuccess = firstResult as RouteLegResult.Success
        assertEquals("brouter.de", firstSuccess.sourceName)

        // Second call: must be served from cache
        val secondResult = engine.routeLeg(from = from, to = to, options = options)
        assertTrue("Second call should succeed", secondResult is RouteLegResult.Success)
        val secondSuccess = secondResult as RouteLegResult.Success
        assertEquals("cache", secondSuccess.sourceName)
        assertEquals("Length should match cached route", firstSuccess.lengthKm, secondSuccess.lengthKm, 0.001)
        assertEquals("Point count should match cached route", firstSuccess.points.size, secondSuccess.points.size)
    }

    @Test
    fun testRateLimiting_NeverCallsServerMoreThanOncePerSecond() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        var serverCallCount = 0
        val callTimestamps = mutableListOf<Long>()

        val loggingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (chain.request().url.encodedPath.contains("brouter") && !chain.request().url.encodedPath.contains("profile")) {
                    serverCallCount++
                    callTimestamps.add(System.currentTimeMillis())
                }
                chain.proceed(chain.request())
            })
            .build()

        val engine = BRouterOnlineEngine(
            dao = database.dao(),
            assetManager = app.assets,
            client = loggingClient
        )

        // Call 1
        val p1 = LatLng(56.835, 9.890)
        val p2 = LatLng(56.830, 9.840)
        engine.routeLeg(from = p1, to = p2, options = RouteOptions(avoidRoads = 0.2))

        // Call 2 with different avoidRoads (cache miss)
        val p3 = LatLng(56.832, 9.880)
        engine.routeLeg(from = p1, to = p3, options = RouteOptions(avoidRoads = 0.8))

        assertTrue("Server should have received 2 routing calls", callTimestamps.size >= 2)
        val intervalMs = callTimestamps[1] - callTimestamps[0]
        assertTrue(
            "Time between server calls should be at least 1000 ms, was $intervalMs ms",
            intervalMs >= 950L // Allow small clock jitter margin
        )
    }

    @Test
    fun testServerBusyErrorOn429() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("Rate limit exceeded".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()

        val engine = BRouterOnlineEngine(dao = database.dao(), client = mockClient)
        val result = engine.routeLeg(
            from = LatLng(56.0, 10.0),
            to = LatLng(56.1, 10.1),
            options = RouteOptions()
        )

        assertTrue("Expected ServerBusy on HTTP 429", result is RouteLegResult.ServerBusy)
        val busy = result as RouteLegResult.ServerBusy
        assertEquals("Ruteserveren er optaget - prøv igen", busy.message)
    }

    @Test
    fun testOfflineErrorOnNetworkException() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { _ ->
                throw IOException("Unable to resolve host brouter.de")
            })
            .build()

        val engine = BRouterOnlineEngine(dao = database.dao(), client = mockClient)
        val result = engine.routeLeg(
            from = LatLng(56.0, 10.0),
            to = LatLng(56.1, 10.1),
            options = RouteOptions()
        )

        assertTrue("Expected Offline on network failure", result is RouteLegResult.Offline)
        val offline = result as RouteLegResult.Offline
        assertEquals("Ingen internetforbindelse", offline.message)
    }
}
