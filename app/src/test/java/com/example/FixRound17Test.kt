package com.example

import com.example.data.remote.NoInternetException
import com.example.data.remote.OverpassClient
import com.example.data.remote.OverpassQueryException
import com.example.data.remote.OverpassUnavailableException
import com.example.util.userMessageFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class FixRound17Test {

    @Before
    fun setUp() {
        OverpassClient.resetMetricsForTest()
    }

    @After
    fun tearDown() {
        OverpassClient.resetMetricsForTest()
    }

    @Test
    fun a8_1_healthyPrimary_tenRequestsAllGoToPrimary() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }

        val calledServers = CopyOnWriteArrayList<String>()
        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            OverpassClient.HttpResult(200, """{"elements":[{"type":"node","id":1}]}""")
        }

        repeat(10) { idx ->
            val res = OverpassClient.queryRawJson("[out:json][timeout:25];node($idx);out;", "test_$idx")
            assertTrue(res.startsWith("{"))
        }

        assertEquals(10, calledServers.size)
        assertTrue(calledServers.all { it == OverpassClient.PRIMARY })
    }

    @Test
    fun a8_2_primaryNoHeaders20s_switchesToFallbackAfter1to1_5sBackoff_nextTenRequestsSkipPrimary() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 1_250L }

        val attemptLog = CopyOnWriteArrayList<Pair<String, Long>>()
        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            attemptLog.add(serverUrl to testScheduler.currentTime)
            if (serverUrl == OverpassClient.PRIMARY) {
                CompletableDeferred<Unit>().await()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            } else {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, """{"elements":[{"type":"node","id":42}]}""")
            }
        }

        val firstRes = OverpassClient.queryRawJson(
            "[out:json][timeout:25];node(1);out;",
            "req_0",
            OverpassClient.Priority.USER_INITIATED
        )
        assertTrue(firstRes.contains("42"))
        assertEquals(2, attemptLog.size)
        assertEquals(OverpassClient.PRIMARY, attemptLog[0].first)
        assertEquals(0L, attemptLog[0].second)
        assertEquals(OverpassClient.FALLBACK, attemptLog[1].first)
        assertTrue(attemptLog[1].second in 21_000L..21_500L)

        attemptLog.clear()
        repeat(10) { idx ->
            testScheduler.advanceTimeBy(5_000L)
            val r = OverpassClient.queryRawJson(
                "[out:json][timeout:25];node($idx);out;",
                "subsequent_$idx",
                OverpassClient.Priority.USER_INITIATED
            )
            assertTrue(r.contains("42"))
        }

        assertEquals(10, attemptLog.size)
        assertTrue(
            "All 10 requests during the 2-minute cooldown must go straight to FALLBACK with zero attempts on PRIMARY",
            attemptLog.all { it.first == OverpassClient.FALLBACK }
        )
    }

    @Test
    fun a8_3_after2MinFakeClock_nextRequestProbesPrimary_andSuccessRestoresPrimary() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        var primaryHealthy = false
        val calledServers = CopyOnWriteArrayList<String>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            if (serverUrl == OverpassClient.PRIMARY && !primaryHealthy) {
                OverpassClient.HttpResult(503, "Service Unavailable")
            } else {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        OverpassClient.queryRawJson("[out:json];node(1);out;", "fail_primary")
        assertEquals(listOf(OverpassClient.PRIMARY, OverpassClient.FALLBACK), calledServers.toList())

        calledServers.clear()
        testScheduler.advanceTimeBy(120_000L)
        primaryHealthy = true

        OverpassClient.queryRawJson("[out:json];node(2);out;", "probe_primary")
        assertEquals(listOf(OverpassClient.PRIMARY), calledServers.toList())
        assertEquals(0, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).consecutiveFailures)

        calledServers.clear()
        repeat(3) {
            OverpassClient.queryRawJson("[out:json];node(3);out;", "after_probe_$it")
        }
        assertEquals(
            listOf(OverpassClient.PRIMARY, OverpassClient.PRIMARY, OverpassClient.PRIMARY),
            calledServers.toList()
        )
    }

    @Test
    fun a8_4_probeFails_cooldownsGrow_2_4_8_15_15_min() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            if (serverUrl == OverpassClient.PRIMARY) {
                OverpassClient.HttpResult(502, "Bad Gateway")
            } else {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        val expectedCooldownMinutes = listOf(2L, 4L, 8L, 15L, 15L)
        for ((idx, mins) in expectedCooldownMinutes.withIndex()) {
            val beforeMs = testScheduler.currentTime
            OverpassClient.queryRawJson("[out:json];node($idx);out;", "attempt_$idx")
            val afterFailureNowMs = testScheduler.currentTime - 1_000L
            val cooldownUntil = OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).cooldownUntilMs
            val actualDurationMs = cooldownUntil - beforeMs
            assertEquals(
                "Failure #${idx + 1} should set cooldown to $mins minutes",
                mins * 60_000L,
                actualDurationMs
            )
            val remainingToWait = (cooldownUntil - afterFailureNowMs).coerceAtLeast(0L) + 10L
            testScheduler.advanceTimeBy(remainingToWait)
        }
    }

    @Test
    fun a8_5_http429WithRetryAfter300_setsPrimaryCooldownTo300s() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            if (serverUrl == OverpassClient.PRIMARY) {
                OverpassClient.HttpResult(429, "Too Many Requests", retryAfterSeconds = 300L)
            } else {
                session.onHeadersReceived()
                session.onDataChunkReceived()
                OverpassClient.HttpResult(200, """{"elements":[]}""")
            }
        }

        val startMs = testScheduler.currentTime
        OverpassClient.queryRawJson("[out:json];node(1);out;", "test_429")
        val cooldownUntil = OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).cooldownUntilMs
        assertEquals(300_000L, cooldownUntil - startMs)
    }

    @Test
    fun a8_6_http200WithRuntimeErrorRemark_switchesToFallback_andDoesNotReturnPartialBody() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 1_000L }

        val partialBodyWithRemark = """{"version":0.6,"elements":[{"type":"node","id":99}],"remark":"runtime error: Query timed out in \"query\" at line 1 after 25 seconds."}"""
        val cleanFallbackBody = """{"version":0.6,"elements":[{"type":"node","id":777}]}"""

        val calledServers = CopyOnWriteArrayList<String>()
        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            if (serverUrl == OverpassClient.PRIMARY) {
                OverpassClient.HttpResult(200, partialBodyWithRemark)
            } else {
                OverpassClient.HttpResult(200, cleanFallbackBody)
            }
        }

        val returnedBody = OverpassClient.queryRawJson("[out:json];node(1);out;", "remark_test")
        assertEquals(listOf(OverpassClient.PRIMARY, OverpassClient.FALLBACK), calledServers.toList())
        assertEquals(cleanFallbackBody, returnedBody)
        assertFalse(returnedBody.contains("runtime error"))
        assertEquals(1, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).consecutiveFailures)
    }

    @Test
    fun a8_7_http400_throwsOverpassQueryExceptionImmediately_noFallbackAndNoCooldown() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }

        val calledServers = CopyOnWriteArrayList<String>()
        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            calledServers.add(serverUrl)
            session.onHeadersReceived()
            OverpassClient.HttpResult(400, "Bad Request: syntax error")
        }

        val result = runCatching {
            OverpassClient.queryRawJson("[out:json];bad_syntax;", "syntax_test")
        }
        assertTrue(result.exceptionOrNull() is OverpassQueryException)
        assertEquals(listOf(OverpassClient.PRIMARY), calledServers.toList())
        assertEquals(0, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).consecutiveFailures)
        assertEquals(0L, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).cooldownUntilMs)
    }

    @Test
    fun a8_8_offline_noTransportCallAndServerStateUnchanged() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { false }

        val transportCalls = AtomicInteger(0)
        OverpassClient.customSessionTransport = { _, _, _ ->
            transportCalls.incrementAndGet()
            OverpassClient.HttpResult(200, """{"elements":[]}""")
        }

        val result = runCatching {
            OverpassClient.queryRawJson("[out:json];node(1);out;", "offline_test")
        }
        assertTrue(result.exceptionOrNull() is NoInternetException)
        assertEquals(0, transportCalls.get())
        assertEquals(0, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).consecutiveFailures)
        assertEquals(0, OverpassClient.getServerStateSnapshot(OverpassClient.FALLBACK).consecutiveFailures)
    }

    @Test
    fun a8_9_twoConcurrentRequestsAfterCooldown_onlyOneProbesPrimary() = kotlinx.coroutines.runBlocking {
        var fakeClockMs = 1_000L
        OverpassClient.currentTimeMillisProvider = { fakeClockMs }
        OverpassClient.connectivityChecker = { true }
        OverpassClient.cooldownJitterFactorProvider = { 1.0 }
        OverpassClient.interServerBackoffMsProvider = { 10L }

        var phase = 0
        val primaryProbeCount = AtomicInteger(0)
        val fallbackCountDuringProbe = AtomicInteger(0)
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()

        OverpassClient.customSessionTransport = { serverUrl, _, session ->
            if (phase == 0) {
                if (serverUrl == OverpassClient.PRIMARY) {
                    OverpassClient.HttpResult(503, "Down")
                } else {
                    session.onHeadersReceived()
                    session.onDataChunkReceived()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
            } else {
                if (serverUrl == OverpassClient.PRIMARY) {
                    primaryProbeCount.incrementAndGet()
                    session.onHeadersReceived()
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                    session.onDataChunkReceived()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                } else {
                    fallbackCountDuringProbe.incrementAndGet()
                    session.onHeadersReceived()
                    session.onDataChunkReceived()
                    OverpassClient.HttpResult(200, """{"elements":[]}""")
                }
            }
        }

        OverpassClient.queryRawJson(
            "[out:json];node(0);out;",
            "put_in_cooldown",
            OverpassClient.Priority.USER_INITIATED
        )
        fakeClockMs += 125_000L
        phase = 1

        val req1 = async {
            OverpassClient.queryRawJson(
                "[out:json];node(1);out;",
                "concurrent_1",
                OverpassClient.Priority.USER_INITIATED
            )
        }
        probeStarted.await()

        val req2 = async {
            OverpassClient.queryRawJson(
                "[out:json];node(2);out;",
                "concurrent_2",
                OverpassClient.Priority.USER_INITIATED
            )
        }
        val res2 = req2.await()
        releaseProbe.complete(Unit)
        val res1 = req1.await()

        assertTrue(res1.startsWith("{"))
        assertTrue(res2.startsWith("{"))
        assertEquals("Exactly one request may probe PRIMARY", 1, primaryProbeCount.get())
        assertEquals("Concurrent second request must use FALLBACK while probe is in flight", 1, fallbackCountDuringProbe.get())
    }

    @Test
    fun a8_10_timeout90Query_headersAt0s_firstDataAt45s_succeeds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }

        OverpassClient.customSessionTransport = { _, _, session ->
            session.onHeadersReceived()
            delay(45_000L)
            session.onDataChunkReceived()
            OverpassClient.HttpResult(200, """{"elements":[{"type":"relation","id":100}]}""")
        }

        val query = "[out:json][timeout:90];relation(100);out geom;"
        assertEquals(105L, OverpassClient.computeClientReadTimeoutSeconds(query))
        val body = OverpassClient.queryRawJson(query, "long_query_test")
        assertTrue(body.contains("100"))
    }

    @Test
    fun a8_11_queryWithMaxsizeGetsOutOfMemoryRemark_retriesOnceWithoutMaxsizeOnSameServer_notCountedAsFailure() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OverpassClient.ioDispatcher = testDispatcher
        OverpassClient.currentTimeMillisProvider = { testScheduler.currentTime }
        OverpassClient.connectivityChecker = { true }

        val receivedQueries = CopyOnWriteArrayList<Pair<String, String>>()
        OverpassClient.customSessionTransport = { serverUrl, query, session ->
            receivedQueries.add(serverUrl to query)
            session.onHeadersReceived()
            session.onDataChunkReceived()
            if (query.contains("[maxsize:")) {
                OverpassClient.HttpResult(
                    200,
                    """{"version":0.6,"elements":[],"remark":"runtime error: Query ran out of memory in \"query\" at line 1."}"""
                )
            } else {
                OverpassClient.HttpResult(200, """{"version":0.6,"elements":[{"type":"node","id":555}]}""")
            }
        }

        val originalQuery = "[out:json][timeout:25][maxsize:67108864];node(55.0,10.0,55.1,10.1);out body;"
        val response = OverpassClient.queryRawJson(originalQuery, "oom_maxsize_retry")

        assertTrue(response.contains("555"))
        assertEquals(2, receivedQueries.size)
        assertEquals(OverpassClient.PRIMARY, receivedQueries[0].first)
        assertTrue(receivedQueries[0].second.contains("[maxsize:67108864]"))
        assertEquals(OverpassClient.PRIMARY, receivedQueries[1].first)
        assertFalse(receivedQueries[1].second.contains("[maxsize:"))
        assertEquals(0, OverpassClient.getServerStateSnapshot(OverpassClient.PRIMARY).consecutiveFailures)
    }

    @Test
    fun partB_userFacingErrorMessagesArePlainDanishWithoutUrlsOrCodes() {
        val errors = listOf(
            NoInternetException(),
            OverpassUnavailableException(),
            OverpassQueryException(400),
            SocketTimeoutException("Read timed out from https://overpass-api.de/api/interpreter"),
            IOException("HTTP 504 Gateway Timeout from https://overpass.private.coffee/api/interpreter")
        )
        for (err in errors) {
            val msg = userMessageFor(err)
            assertFalse("Message must not contain http/url: $msg", msg.contains("http", ignoreCase = true))
            assertFalse("Message must not contain overpass: $msg", msg.contains("overpass", ignoreCase = true))
            assertFalse("Message must not contain status code: $msg", Regex("\\b(400|429|500|502|503|504)\\b").containsMatchIn(msg))
        }
    }
}
