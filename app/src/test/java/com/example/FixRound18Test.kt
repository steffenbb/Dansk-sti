package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailDownloadProgress
import com.example.data.model.TrailSegmentType
import com.example.data.remote.OverpassClient
import com.example.data.repository.OsmPoiRepository
import com.example.data.repository.TrailGeometryRepository
import com.example.util.routing.BRouterGeoJsonParser
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class FixRound18Test {

    @Before
    fun setUp() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = { coords -> coords.map { 15.0 } }
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
        TrailGeometryRepository.resetDownloadDeduplicationForTest()
        TrailGeometryRepository.customRelationFetcher = null
        TrailGeometryRepository.customElevationFetcher = null
    }

    // =========================================================================
    // ITEM 1: Backup server must get its own full chance
    // a) PRIMARY sends headers at 0 s, returns HTTP 200 with remark "runtime error: Query timed out"
    //    at 90 s → FALLBACK is called with a 30 s header limit and its body is returned.
    // b) PRIMARY sends headers, then stalls until the idle limit → FALLBACK is called and succeeds.
    // c) Another request is probing FALLBACK while this request's PRIMARY fails → this request still tries FALLBACK.
    // =========================================================================

    @Test
    fun item1a_primarySendsHeadersAt0s_returnsRemarkAt90s_fallbackCalledWith30sHeaderLimitAndReturnsBody() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        val fallbackBody = """{"version":0.6,"elements":[{"type":"node","id":1801}]}"""
        val calledServers = CopyOnWriteArrayList<String>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            if (serverUrl == OverpassClient.PRIMARY) {
                // Sends headers at 0 s, streams chunks until 90 s, then returns HTTP 200 with runtime error remark
                session.onHeadersReceived()
                repeat(3) {
                    delay(30_000L)
                    session.onDataChunkReceived()
                }
                OverpassClient.HttpResult(
                    200,
                    """{"version":0.6,"elements":[],"remark":"runtime error: Query timed out"}"""
                )
            } else {
                // FALLBACK takes 25 s to send headers (requires the full 30 s header limit!)
                delay(25_000L)
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, fallbackBody)
            }
        }

        val returned = OverpassClient.queryRawJson(
            query = "[out:json][timeout:90];relation(1);out geom;",
            label = "item1a",
            priority = OverpassClient.Priority.USER_INITIATED
        )

        assertEquals(listOf(OverpassClient.PRIMARY, OverpassClient.FALLBACK), calledServers.toList())
        assertEquals(
            "FALLBACK must be called with a fixed 30 s (30000 ms) header limit",
            30_000L,
            OverpassClient.observedHeaderLimitsByServer[OverpassClient.FALLBACK]
        )
        assertEquals(
            "PRIMARY must be called with a fixed 20 s (20000 ms) header limit",
            20_000L,
            OverpassClient.observedHeaderLimitsByServer[OverpassClient.PRIMARY]
        )
        assertEquals(fallbackBody, returned)
    }

    @Test
    fun item1b_primarySendsHeadersThenStallsUntilIdleLimit_fallbackIsCalledAndSucceeds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        val fallbackBody = """{"version":0.6,"elements":[{"type":"node","id":1802}]}"""
        val calledServers = CopyOnWriteArrayList<String>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            if (serverUrl == OverpassClient.PRIMARY) {
                session.onHeadersReceived()
                // Stalls forever after sending headers
                CompletableDeferred<Unit>().await()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            } else {
                delay(25_000L) // Within FALLBACK's 30 s header limit
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, fallbackBody)
            }
        }

        val returned = OverpassClient.queryRawJson(
            query = "[out:json][timeout:25];node(1);out;",
            label = "item1b",
            priority = OverpassClient.Priority.USER_INITIATED
        )

        assertEquals(listOf(OverpassClient.PRIMARY, OverpassClient.FALLBACK), calledServers.toList())
        assertEquals(30_000L, OverpassClient.observedHeaderLimitsByServer[OverpassClient.FALLBACK])
        assertEquals(fallbackBody, returned)
    }

    @Test
    fun item1c_anotherRequestIsProbingFallbackWhilePrimaryFails_thisRequestStillTriesFallback() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        // Simulate another request currently probing FALLBACK
        OverpassClient.setServerProbeInFlightForTest(OverpassClient.FALLBACK, true)
        assertTrue(OverpassClient.getServerStateSnapshot(OverpassClient.FALLBACK).probeInFlight)

        val fallbackBody = """{"version":0.6,"elements":[{"type":"node","id":1803}]}"""
        val calledServers = CopyOnWriteArrayList<String>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            if (serverUrl == OverpassClient.PRIMARY) {
                OverpassClient.HttpResult(503, "Service Unavailable")
            } else {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, fallbackBody)
            }
        }

        val returned = OverpassClient.queryRawJson(
            query = "[out:json][timeout:25];node(1);out;",
            label = "item1c",
            priority = OverpassClient.Priority.USER_INITIATED
        )

        assertEquals(
            "When PRIMARY fails, request must still try FALLBACK even if another request is probing FALLBACK",
            listOf(OverpassClient.PRIMARY, OverpassClient.FALLBACK),
            calledServers.toList()
        )
        assertEquals(fallbackBody, returned)
    }

    // =========================================================================
    // ITEM 2: Do not let PRIMARY keep a user waiting
    // Test: USER_INITIATED [timeout:90] query, PRIMARY silent for 46 s after headers → FALLBACK used.
    // =========================================================================

    @Test
    fun item2_userInitiatedTimeout90Query_primarySilentFor46sAfterHeaders_fallbackUsed() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        val fallbackBody = """{"version":0.6,"elements":[{"type":"relation","id":2002}]}"""
        val attemptTimes = CopyOnWriteArrayList<Pair<String, Long>>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            attemptTimes.add(serverUrl to testScheduler.currentTime)
            if (serverUrl == OverpassClient.PRIMARY) {
                session.onHeadersReceived()
                // Silent for 46 s after headers (exceeds 45 s USER_INITIATED limit on PRIMARY)
                delay(46_000L)
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            } else {
                // FALLBACK gets full idle limit (90 + 15 = 105 s), so 60 s silence after headers succeeds
                session.onHeadersReceived()
                delay(60_000L)
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, fallbackBody)
            }
        }

        val returned = OverpassClient.queryRawJson(
            query = "[out:json][timeout:90];relation(2002);out geom;",
            label = "item2_user_initiated_45s",
            priority = OverpassClient.Priority.USER_INITIATED
        )

        assertEquals(2, attemptTimes.size)
        assertEquals(OverpassClient.PRIMARY, attemptTimes[0].first)
        assertEquals(OverpassClient.FALLBACK, attemptTimes[1].first)
        assertEquals(45_000L, OverpassClient.observedIdleLimitsByServer[OverpassClient.PRIMARY])
        assertEquals(105_000L, OverpassClient.observedIdleLimitsByServer[OverpassClient.FALLBACK])
        // FALLBACK started after PRIMARY failed at 45 s + 1 s backoff = ~46 s
        assertTrue(
            "PRIMARY must fail at 45 s idle and switch to FALLBACK around 46 s (actual=${attemptTimes[1].second}ms)",
            attemptTimes[1].second in 45_000L..47_000L
        )
        assertEquals(fallbackBody, returned)
    }

    // =========================================================================
    // ITEM 3: Connect timeout 5 s
    // =========================================================================

    @Test
    fun item3_connectTimeoutIs5s_andHeaderWatchdogStays20sPrimary30sFallback() {
        assertEquals(5_000L, OverpassClient.CONNECT_TIMEOUT_MS)
        assertEquals(5_000, OverpassClient.okHttpConnectTimeoutMillis())
        assertEquals(20_000L, OverpassClient.headerLimitMsFor(OverpassClient.PRIMARY))
        assertEquals(30_000L, OverpassClient.headerLimitMsFor(OverpassClient.FALLBACK))
    }

    // =========================================================================
    // ITEM 4: Trail download: fewer requests, never a silent partial trail
    // Tests:
    // - superroute with 3 Danish children + 1 German child → exactly 2 Overpass requests,
    //   German child excluded, stage order kept.
    // - 20 Danish children → 1 structure + 2 batch requests.
    // - A batch failing twice → download fails and nothing is saved.
    // =========================================================================

    @Test
    fun item4_superrouteWith3DanishChildrenAnd1GermanChild_exactly2OverpassRequests_germanExcluded_stageOrderKept(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "r18_superroute_3dk_1de_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val executedQueries = CopyOnWriteArrayList<String>()

        // Stage order in parent relation 5000: 5030 (stage 1, south), 5010 (stage 2, middle), 5020 (stage 3, north), 5099 (Germany)
        // Note: numerical IDs (5030, 5010, 5020) are intentionally NOT sorted, to prove member order (= stage order) is kept!
        OverpassClient.customTransport = { _, query ->
            executedQueries.add(query)
            when {
                query.contains("out body bb;") -> {
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 5000,
                              "tags": { "name": "Grænsestien Superrute", "type": "superroute", "route": "hiking" },
                              "members": [
                                { "type": "relation", "ref": 5030, "role": "" },
                                { "type": "relation", "ref": 5010, "role": "" },
                                { "type": "relation", "ref": 5099, "role": "" },
                                { "type": "relation", "ref": 5020, "role": "" }
                              ]
                            },
                            {
                              "type": "relation",
                              "id": 5030,
                              "bounds": { "minlat": 55.00, "minlon": 9.40, "maxlat": 55.10, "maxlon": 9.45 },
                              "tags": { "name": "Etape 1 Kruså" },
                              "members": [ { "type": "way", "ref": 301, "role": "" } ]
                            },
                            {
                              "type": "relation",
                              "id": 5010,
                              "bounds": { "minlat": 55.10, "minlon": 9.45, "maxlat": 55.20, "maxlon": 9.50 },
                              "tags": { "name": "Etape 2 Aabenraa" },
                              "members": [ { "type": "way", "ref": 101, "role": "" } ]
                            },
                            {
                              "type": "relation",
                              "id": 5099,
                              "bounds": { "minlat": 53.50, "minlon": 9.50, "maxlat": 54.00, "maxlon": 10.00 },
                              "tags": { "name": "Etappe Schleswig-Holstein", "country": "DE" },
                              "members": [
                                { "type": "relation", "ref": 5999, "role": "" },
                                { "type": "way", "ref": 999, "role": "" }
                              ]
                            },
                            {
                              "type": "relation",
                              "id": 5999,
                              "bounds": { "minlat": 55.00, "minlon": 9.40, "maxlat": 55.10, "maxlon": 9.45 },
                              "tags": { "name": "Subtree under German child" },
                              "members": [ { "type": "way", "ref": 998, "role": "" } ]
                            },
                            {
                              "type": "relation",
                              "id": 5020,
                              "bounds": { "minlat": 55.20, "minlon": 9.50, "maxlat": 55.30, "maxlon": 9.55 },
                              "tags": { "name": "Etape 3 Haderslev" },
                              "members": [ { "type": "way", "ref": 201, "role": "" } ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains(".r out geom;") && query.contains("way(r.r);") && query.contains("out tags;") -> {
                    // Overpass returns elements sorted by relation ID (5010, 5020, 5030), testing that member order (5030 -> 5010 -> 5020) is preserved
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 5010,
                              "tags": { "name": "Etape 2 Aabenraa" },
                              "members": [
                                {
                                  "type": "way",
                                  "ref": 101,
                                  "role": "",
                                  "geometry": [
                                    { "lat": 55.10, "lon": 9.45 },
                                    { "lat": 55.20, "lon": 9.50 }
                                  ]
                                }
                              ]
                            },
                            {
                              "type": "relation",
                              "id": 5020,
                              "tags": { "name": "Etape 3 Haderslev" },
                              "members": [
                                {
                                  "type": "way",
                                  "ref": 201,
                                  "role": "",
                                  "geometry": [
                                    { "lat": 55.20, "lon": 9.50 },
                                    { "lat": 55.30, "lon": 9.55 }
                                  ]
                                }
                              ]
                            },
                            {
                              "type": "relation",
                              "id": 5030,
                              "tags": { "name": "Etape 1 Kruså" },
                              "members": [
                                {
                                  "type": "way",
                                  "ref": 301,
                                  "role": "",
                                  "geometry": [
                                    { "lat": 55.00, "lon": 9.40 },
                                    { "lat": 55.10, "lon": 9.45 }
                                  ]
                                }
                              ]
                            },
                            { "type": "way", "id": 301, "tags": { "highway": "path", "surface": "dirt" } },
                            { "type": "way", "id": 101, "tags": { "highway": "track", "surface": "gravel" } },
                            { "type": "way", "id": 201, "tags": { "route": "ferry" } }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                else -> OverpassClient.HttpResult(400, "Unexpected query: $query")
            }
        }

        val dto = geomRepo.fetchAndProcessTrail(
            trailId = trailId,
            trailName = "Grænsestien Superrute",
            osmRelationId = 5000L
        )
        assertNotNull("Superroute download must succeed", dto)
        assertEquals(
            "Superroute with 3 Danish children + 1 German child must make exactly 2 Overpass requests (1 structure + 1 batch)",
            2,
            executedQueries.size
        )
        assertTrue(executedQueries[0].contains("out body bb;"))
        assertTrue(executedQueries[1].contains("relation(id:5000,5030,5010,5020)->.r;"))
        assertFalse("German child 5099 must be excluded from batch request", executedQueries[1].contains("5099"))
        assertFalse("Subtree 5999 of German child must be excluded from batch request", executedQueries[1].contains("5999"))

        // Verify stage order kept: 5030 (starts at 55.00) -> 5010 (55.10..55.20) -> 5020 (ferry 55.20..55.30)
        val allPts = dto!!.segments.flatMap { it.points }
        assertEquals(55.00, allPts.first().lat, 1e-4)
        assertEquals(55.30, allPts.last().lat, 1e-4)
        assertTrue("Ferry detection via way tags must still work", dto.segments.any { it.type == TrailSegmentType.FERRY })

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item4_superrouteWith20DanishChildren_makes1StructurePlus2BatchRequests(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "r18_superroute_20dk_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val childIds = (1..20).map { 6000L + it }
        val executedQueries = CopyOnWriteArrayList<String>()
        val observedProgressMessages = CopyOnWriteArrayList<String>()

        OverpassClient.customTransport = { _, query ->
            executedQueries.add(query)
            when {
                query.contains("out body bb;") -> {
                    val memberRefsJson = childIds.joinToString(",") { cid ->
                        """{ "type": "relation", "ref": $cid, "role": "" }"""
                    }
                    val childrenJson = childIds.mapIndexed { idx, cid ->
                        val lat0 = 55.0 + idx * 0.02
                        val lat1 = lat0 + 0.02
                        """
                        {
                          "type": "relation",
                          "id": $cid,
                          "bounds": { "minlat": $lat0, "minlon": 9.5, "maxlat": $lat1, "maxlon": 9.6 },
                          "tags": { "name": "Etape ${idx + 1}" },
                          "members": [ { "type": "way", "ref": ${7000L + idx}, "role": "" } ]
                        }
                        """.trimIndent()
                    }.joinToString(",")

                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 6000,
                              "tags": { "name": "Tyve Etaper Rute", "type": "superroute" },
                              "members": [ $memberRefsJson ]
                            },
                            $childrenJson
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains(".r out geom;") -> {
                    val idsStr = Regex("""relation\(id:([0-9,]+)\)->\.r;""").find(query)?.groupValues?.get(1).orEmpty()
                    val idsInBatch = idsStr.split(",").mapNotNull { it.toLongOrNull() }.filter { it != 6000L }
                    val relElementsJson = idsInBatch.joinToString(",") { cid ->
                        val idx = (cid - 6001L).toInt()
                        val lat0 = 55.0 + idx * 0.02
                        val lat1 = lat0 + 0.02
                        val wayId = 7000L + idx
                        """
                        {
                          "type": "relation",
                          "id": $cid,
                          "tags": { "name": "Etape ${idx + 1}" },
                          "members": [
                            {
                              "type": "way",
                              "ref": $wayId,
                              "role": "",
                              "geometry": [
                                { "lat": $lat0, "lon": 9.5 },
                                { "lat": $lat1, "lon": 9.5 }
                              ]
                            }
                          ]
                        },
                        { "type": "way", "id": $wayId, "tags": { "highway": "footway", "surface": "compacted" } }
                        """.trimIndent()
                    }
                    OverpassClient.HttpResult(200, """{ "elements": [ $relElementsJson ] }""")
                }
                else -> OverpassClient.HttpResult(400, "Unexpected query: $query")
            }
        }

        val dto = geomRepo.fetchAndProcessTrail(
            trailId = trailId,
            trailName = "Tyve Etaper Rute",
            osmRelationId = 6000L,
            onProgress = { p -> observedProgressMessages.add(p.stageMessage) }
        )

        assertNotNull("20-child superroute must download and process cleanly", dto)
        val structureReqs = executedQueries.filter { it.contains("out body bb;") }
        val batchReqs = executedQueries.filter { it.contains(".r out geom;") }
        assertEquals("Must execute 1 structure request", 1, structureReqs.size)
        assertEquals("Must execute 2 batch requests for 20 Danish children (max 15 ids per batch)", 2, batchReqs.size)
        assertTrue(
            "Progress must report 'Henter sektion 1 af 2' and 'Henter sektion 2 af 2': $observedProgressMessages",
            observedProgressMessages.contains("Henter sektion 1 af 2") &&
                observedProgressMessages.contains("Henter sektion 2 af 2")
        )

        geomRepo.getGeometryFile(trailId).delete()
    }

    @Test
    fun item4_batchFailingTwice_downloadFailsAndNothingIsSaved(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val geomRepo = TrailGeometryRepository(app)
        val trailId = "r18_fail_batch2_${System.currentTimeMillis()}"
        geomRepo.getGeometryFile(trailId).delete()

        val childIds = (1..20).map { 8000L + it }
        val batch2Attempts = AtomicInteger(0)
        val observedProgress = CopyOnWriteArrayList<TrailDownloadProgress>()
        OverpassClient.interServerBackoffMsProvider = { 1L }

        OverpassClient.customTransport = { _, query ->
            when {
                query.contains("out body bb;") -> {
                    val memberRefsJson = childIds.joinToString(",") { cid ->
                        """{ "type": "relation", "ref": $cid, "role": "" }"""
                    }
                    val childrenJson = childIds.mapIndexed { idx, cid ->
                        val lat0 = 55.0 + idx * 0.02
                        val lat1 = lat0 + 0.02
                        """
                        {
                          "type": "relation",
                          "id": $cid,
                          "bounds": { "minlat": $lat0, "minlon": 9.5, "maxlat": $lat1, "maxlon": 9.6 },
                          "tags": { "name": "Etape ${idx + 1}" },
                          "members": [ { "type": "way", "ref": ${9000L + idx}, "role": "" } ]
                        }
                        """.trimIndent()
                    }.joinToString(",")
                    OverpassClient.HttpResult(
                        200,
                        """
                        {
                          "elements": [
                            {
                              "type": "relation",
                              "id": 8000,
                              "tags": { "name": "Fejlende Batch Rute", "type": "superroute" },
                              "members": [ $memberRefsJson ]
                            },
                            $childrenJson
                          ]
                        }
                        """.trimIndent()
                    )
                }
                query.contains("8001") && query.contains(".r out geom;") -> {
                    // Batch 1 succeeds
                    val idsStr = Regex("""relation\(id:([0-9,]+)\)->\.r;""").find(query)?.groupValues?.get(1).orEmpty()
                    val idsInBatch = idsStr.split(",").mapNotNull { it.toLongOrNull() }.filter { it != 8000L }
                    val relElementsJson = idsInBatch.joinToString(",") { cid ->
                        val idx = (cid - 8001L).toInt()
                        val lat0 = 55.0 + idx * 0.02
                        val lat1 = lat0 + 0.02
                        """
                        {
                          "type": "relation",
                          "id": $cid,
                          "tags": { "name": "Etape ${idx + 1}" },
                          "members": [
                            {
                              "type": "way",
                              "ref": ${9000L + idx},
                              "role": "",
                              "geometry": [
                                { "lat": $lat0, "lon": 9.5 },
                                { "lat": $lat1, "lon": 9.5 }
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    OverpassClient.HttpResult(200, """{ "elements": [ $relElementsJson ] }""")
                }
                else -> {
                    // Batch 2 fails on both servers on initial attempt and on the retry
                    batch2Attempts.incrementAndGet()
                    OverpassClient.HttpResult(503, "Service Unavailable")
                }
            }
        }

        val dto = geomRepo.fetchAndProcessTrail(
            trailId = trailId,
            trailName = "Fejlende Batch Rute",
            osmRelationId = 8000L,
            onProgress = { p -> observedProgress.add(p) }
        )

        assertNull("When a batch fails twice, fetchAndProcessTrail must return null", dto)
        assertFalse(
            "Geometry file must NOT be saved when a batch fails",
            geomRepo.getGeometryFile(trailId).exists()
        )
        assertFalse(
            "Must NEVER emit 'Færdig' when a batch fails",
            observedProgress.any { it.stageMessage.startsWith("Færdig") }
        )
        // Batch 2 was tried once + retried once (each trying 2 Overpass servers = 4 transport calls)
        assertEquals(4, batch2Attempts.get())
    }

    // =========================================================================
    // ITEM 5, 6, 8 Verification Tests
    // =========================================================================

    @Test
    fun item5_6_8_lighterPoiQuery_dlogAttemptFormat_andGeoJsonNoRouteMessage(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DanskStiApp>()
        val poiRepo = OsmPoiRepository(app)
        val q = poiRepo.buildOverpassPolylineQuery(listOf(GeoPointDto(55.0, 10.0), GeoPointDto(55.1, 10.1)))
        assertTrue(q.contains("[out:json][timeout:60][maxsize:67108864];"))

        // Verify OverpassClient logs one DLog.d line per attempt with host, phase, elapsed ms, outcome and no full URLs or queries
        OverpassClient.attemptLogLines.clear()
        OverpassClient.customSessionTransport = { _, _, session ->
            session.onHeadersReceived()
            session.onDataChunkReceived()
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }
        OverpassClient.queryRawJson("[out:json][timeout:25];node(123456);out;", "dlog_test")
        assertEquals(1, OverpassClient.attemptLogLines.size)
        val logLine = OverpassClient.attemptLogLines.first()
        assertTrue(logLine.contains("host=overpass-api.de"))
        assertTrue(logLine.contains("phase=data"))
        assertTrue(logLine.contains("elapsed="))
        assertTrue(logLine.contains("outcome=ok"))
        assertFalse(logLine.contains("https://"))
        assertFalse(logLine.contains("123456"))

        // Verify BRouterGeoJsonParser empty features message
        val noRoute = BRouterGeoJsonParser.parseGeoJsonResponse(
            jsonStr = """{"type":"FeatureCollection","features":[]}""",
            from = LatLng(55.0, 10.0),
            to = LatLng(55.1, 10.1),
            options = RouteOptions(),
            alternative = 0,
            cacheKey = "test_empty",
            sourceName = "test"
        )
        assertTrue(noRoute is RouteLegResult.NoRoute)
        assertEquals(
            "Der blev ikke fundet en rute mellem punkterne.",
            (noRoute as RouteLegResult.NoRoute).message
        )
    }
}
