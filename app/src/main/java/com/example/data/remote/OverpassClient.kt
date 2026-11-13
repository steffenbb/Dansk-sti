package com.example.data.remote

import android.util.Log
import com.example.util.network.NetworkConstants
import com.example.util.network.applyDebugMainThreadCheck
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines. async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Round 10B Item 11, Round 13 Item 2 & Round 14 Item 2:
 * One shared client for every Overpass request (trails, POIs, access, water, marked routes)
 * with at most 2 concurrent requests, 1 slot reserved for USER_INITIATED requests so background
 * requests never block a trail download, queue time excluded from all timeouts, 30 s per-mirror
 * response-header timeout, 60 s total no-answer timeout, and 30 s idle-data timeout once a
 * server is answering.
 */
object OverpassClient {
    private const val TAG = "OverpassClient"
    const val MAX_CONCURRENT_REQUESTS = 2
    const val MAX_CONCURRENT_BACKGROUND_REQUESTS = 1

    enum class Priority {
        USER_INITIATED,
        BACKGROUND
    }

    sealed class RequestState {
        data class Queued(val aheadCount: Int) : RequestState()
        object Running : RequestState()
    }

    class NoAnswerTimeoutException(message: String) : SocketTimeoutException(message)
    class IdleDataTimeoutException(message: String) : SocketTimeoutException(message)

    class TransferSession(
        private val onHeadersAction: () -> Unit,
        private val onDataAction: () -> Unit
    ) {
        val headersReceived = AtomicBoolean(false)

        fun onHeadersReceived() {
            if (headersReceived.compareAndSet(false, true)) {
                onHeadersAction()
            }
        }

        fun onDataChunkReceived() {
            if (!headersReceived.get()) {
                onHeadersReceived()
            }
            onDataAction()
        }
    }

    val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    data class HttpResult(
        val code: Int,
        val body: String?,
        val retryAfterSeconds: Long? = null
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    private class QueueWaiter(
        val priority: Priority,
        val sequenceNumber: Long,
        val label: String,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
        val onStatusChange: ((RequestState) -> Unit)? = null
    )

    private val queueLock = Any()
    private var activePermits = 0
    private var activeBackgroundPermits = 0
    private val waitQueue = mutableListOf<QueueWaiter>()
    private val waiterSequence = AtomicLong(0L)

    private val mirrorCursor = AtomicInteger(0)

    val activeRequests = AtomicInteger(0)
    val maxObservedConcurrent = AtomicInteger(0)
    val queuedRequests = AtomicInteger(0)
    val totalRequestsCompleted = AtomicInteger(0)
    val backoffEventsCount = AtomicInteger(0)
    val lastUsedReadTimeoutSeconds = AtomicLong(45L)
    val observedReadTimeoutsByLabel = ConcurrentHashMap<String, Long>()

    @Volatile
    var backoffBaseDelayMs: Long = 600L

    @Volatile
    var maxAttemptsPerMirror: Int = 2

    /**
     * Round 14 Item 2:
     * - If a server sends no response headers within 30 s after the request actually starts, move to next mirror.
     * - If no mirror has answered within 60 s of the request starting, show the error.
     * - Once a server is answering, let the download finish; only fail if no data arrives for 30 s.
     */
    @Volatile
    var noAnswerMirrorTimeoutMs: Long = 30_000L

    @Volatile
    var noAnswerTotalTimeoutMs: Long = 60_000L

    @Volatile
    var idleDataTimeoutMs: Long = 30_000L

    @Volatile
    var customTransport: (suspend (mirrorUrl: String, query: String) -> HttpResult)? = null

    @Volatile
    var customSessionTransport: (suspend (mirrorUrl: String, query: String, session: TransferSession) -> HttpResult)? = null

    @Volatile
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @Volatile
    var currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() }

    private fun nowMs(): Long = currentTimeMillisProvider.invoke()

    private val SERVER_TIMEOUT_REGEX = Regex("""\[timeout:(\d+)\]""")

    fun parseServerTimeoutSeconds(query: String): Long? {
        return SERVER_TIMEOUT_REGEX.find(query)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /**
     * Round 13 Item 2c:
     * Set the client read timeout per query to the query's server timeout + 15 s
     * (relation geometry: [timeout:90] -> 105 s; corridor POIs: [timeout:120] -> 135 s).
     */
    fun computeClientReadTimeoutSeconds(query: String, defaultServerTimeoutSec: Long = 30L): Long {
        val serverTimeout = parseServerTimeoutSeconds(query) ?: defaultServerTimeoutSec
        return serverTimeout + 15L
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .applyDebugMainThreadCheck()
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val responseAdapter by lazy {
        moshi.adapter(OverpassResponse::class.java)
    }

    fun resetMetricsForTest() {
        synchronized(queueLock) {
            waitQueue.forEach { it.gate.cancel() }
            waitQueue.clear()
            activePermits = 0
            activeBackgroundPermits = 0
        }
        activeRequests.set(0)
        maxObservedConcurrent.set(0)
        queuedRequests.set(0)
        totalRequestsCompleted.set(0)
        backoffEventsCount.set(0)
        lastUsedReadTimeoutSeconds.set(45L)
        observedReadTimeoutsByLabel.clear()
        mirrorCursor.set(0)
        backoffBaseDelayMs = 600L
        maxAttemptsPerMirror = 2
        noAnswerMirrorTimeoutMs = 30_000L
        noAnswerTotalTimeoutMs = 60_000L
        idleDataTimeoutMs = 30_000L
        customTransport = null
        customSessionTransport = null
        ioDispatcher = Dispatchers.IO
        currentTimeMillisProvider = { System.currentTimeMillis() }
    }

    private fun insertWaiterByPriorityLocked(waiter: QueueWaiter): Int {
        if (waiter.priority == Priority.USER_INITIATED) {
            val firstBackgroundIdx = waitQueue.indexOfFirst { it.priority == Priority.BACKGROUND }
            return if (firstBackgroundIdx >= 0) {
                waitQueue.add(firstBackgroundIdx, waiter)
                firstBackgroundIdx
            } else {
                waitQueue.add(waiter)
                waitQueue.lastIndex
            }
        } else {
            waitQueue.add(waiter)
            return waitQueue.lastIndex
        }
    }

    private fun canAcquireImmediatelyLocked(priority: Priority): Boolean {
        if (activePermits >= MAX_CONCURRENT_REQUESTS) return false
        return when (priority) {
            Priority.USER_INITIATED -> {
                // Round 14 Item 2: 1 of the 2 slots is reserved for USER_INITIATED requests,
                // so aUSER_INITIATED request can start immediately even if background requests are queued.
                waitQueue.none { it.priority == Priority.USER_INITIATED }
            }
            Priority.BACKGROUND -> {
                activeBackgroundPermits < MAX_CONCURRENT_BACKGROUND_REQUESTS && waitQueue.isEmpty()
            }
        }
    }

    private fun computeAheadCountLocked(queueIndex: Int): Int {
        val waiter = waitQueue.getOrNull(queueIndex) ?: return 1
        return if (waiter.priority == Priority.USER_INITIATED) {
            val userAheadInQueue = waitQueue.subList(0, queueIndex).count { it.priority == Priority.USER_INITIATED }
            if (userAheadInQueue > 0) userAheadInQueue else activePermits.coerceAtLeast(1)
        } else {
            if (queueIndex > 0) queueIndex else activePermits.coerceAtLeast(1)
        }
    }

    private fun notifyWaitQueuePositionsLocked(): List<Pair<QueueWaiter, Int>> {
        return waitQueue.mapIndexed { idx, w ->
            w to computeAheadCountLocked(idx)
        }
    }

    private suspend fun acquirePermit(
        priority: Priority,
        label: String,
        onStatusChange: ((RequestState) -> Unit)?
    ) {
        var waiter: QueueWaiter? = null
        var statusNotifications: List<Pair<QueueWaiter, Int>> = emptyList()

        synchronized(queueLock) {
            if (canAcquireImmediatelyLocked(priority)) {
                activePermits++
                if (priority == Priority.BACKGROUND) {
                    activeBackgroundPermits++
                }
            } else {
                val w = QueueWaiter(
                    priority = priority,
                    sequenceNumber = waiterSequence.incrementAndGet(),
                    label = label,
                    onStatusChange = onStatusChange
                )
                insertWaiterByPriorityLocked(w)
                queuedRequests.set(waitQueue.size)
                waiter = w
                statusNotifications = notifyWaitQueuePositionsLocked()
            }
        }

        if (waiter == null) {
            onStatusChange?.invoke(RequestState.Running)
            return
        }

        statusNotifications.forEach { (w, ahead) ->
            w.onStatusChange?.invoke(RequestState.Queued(ahead))
        }

        try {
            waiter!!.gate.await()
            onStatusChange?.invoke(RequestState.Running)
        } catch (ce: CancellationException) {
            var postCancelNotifications: List<Pair<QueueWaiter, Int>> = emptyList()
            var wasGrantedBeforeCancel = false
            synchronized(queueLock) {
                val removed = waitQueue.remove(waiter)
                queuedRequests.set(waitQueue.size)
                if (!removed && waiter!!.gate.isCompleted) {
                    wasGrantedBeforeCancel = true
                } else {
                    postCancelNotifications = notifyWaitQueuePositionsLocked()
                }
            }
            if (wasGrantedBeforeCancel) {
                releasePermit(priority)
            } else {
                postCancelNotifications.forEach { (w, ahead) ->
                    w.onStatusChange?.invoke(RequestState.Queued(ahead))
                }
            }
            throw ce
        }
    }

    private fun releasePermit(releasedPriority: Priority) {
        var notifications: List<Pair<QueueWaiter, Int>> = emptyList()
        synchronized(queueLock) {
            activePermits = (activePermits - 1).coerceAtLeast(0)
            if (releasedPriority == Priority.BACKGROUND) {
                activeBackgroundPermits = (activeBackgroundPermits - 1).coerceAtLeast(0)
            }

            // 1. Grant available slots to USER_INITIATED waiters first
            val iterator = waitQueue.iterator()
            while (iterator.hasNext() && activePermits < MAX_CONCURRENT_REQUESTS) {
                val candidate = iterator.next()
                if (candidate.priority == Priority.USER_INITIATED) {
                    iterator.remove()
                    if (candidate.gate.complete(Unit)) {
                        activePermits++
                    }
                }
            }

            // 2. Grant up to MAX_CONCURRENT_BACKGROUND_REQUESTS (1) to BACKGROUND waiters
            // only if there are no USER_INITIATED waiters left waiting
            val hasWaitingUserRequest = waitQueue.any { it.priority == Priority.USER_INITIATED }
            if (!hasWaitingUserRequest &&
                activePermits < MAX_CONCURRENT_REQUESTS &&
                activeBackgroundPermits < MAX_CONCURRENT_BACKGROUND_REQUESTS
            ) {
                val bgIterator = waitQueue.iterator()
                while (bgIterator.hasNext() &&
                    activePermits < MAX_CONCURRENT_REQUESTS &&
                    activeBackgroundPermits < MAX_CONCURRENT_BACKGROUND_REQUESTS
                ) {
                    val candidate = bgIterator.next()
                    if (candidate.priority == Priority.BACKGROUND) {
                        bgIterator.remove()
                        if (candidate.gate.complete(Unit)) {
                            activePermits++
                            activeBackgroundPermits++
                        }
                    }
                }
            }

            queuedRequests.set(waitQueue.size)
            notifications = notifyWaitQueuePositionsLocked()
        }
        notifications.forEach { (w, ahead) ->
            w.onStatusChange?.invoke(RequestState.Queued(ahead))
        }
    }

    suspend fun queryRawJson(
        query: String,
        label: String = "overpass",
        priority: Priority = Priority.BACKGROUND,
        onStatusChange: ((RequestState) -> Unit)? = null
    ): String = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        // Round 14 Item 2: Time spent waiting in the OverpassClient queue never counts towards any timeout.
        acquirePermit(priority = priority, label = label, onStatusChange = onStatusChange)
        val currentActive = activeRequests.incrementAndGet()
        maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, currentActive) }
        try {
            executeWithMirrorsAndBackoff(query = query, label = label)
        } finally {
            activeRequests.decrementAndGet()
            totalRequestsCompleted.incrementAndGet()
            releasePermit(priority)
        }
    }

    suspend fun queryParsed(
        query: String,
        label: String = "overpass",
        priority: Priority = Priority.BACKGROUND,
        onStatusChange: ((RequestState) -> Unit)? = null
    ): OverpassResponse {
        val raw = queryRawJson(
            query = query,
            label = label,
            priority = priority,
            onStatusChange = onStatusChange
        )
        return responseAdapter.fromJson(raw)
            ?: throw IOException("Failed to parse Overpass response for $label")
    }

    private fun isReadTimeoutException(e: Throwable): Boolean {
        if (e is SocketTimeoutException) return true
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("read timed out") || msg.contains("socket timeout") || msg.contains("timeout")
    }

    private suspend fun executeWithMirrorsAndBackoff(
        query: String,
        label: String
    ): String {
        val readTimeoutSec = computeClientReadTimeoutSeconds(query)
        lastUsedReadTimeoutSeconds.set(readTimeoutSec)
        observedReadTimeoutsByLabel[label] = readTimeoutSec

        // Request actually starts NOW (after leaving the queue)
        val requestStartMs = nowMs()
        val anyServerAnswered = AtomicBoolean(false)

        val startMirrorIdx = mirrorCursor.getAndUpdate { (it + 1) % MIRRORS.size }
        var lastException: Exception? = null
        var hadReadTimeoutOnPreviousMirror = false

        outer@ for (mirrorOffset in MIRRORS.indices) {
            val mirrorIdx = (startMirrorIdx + mirrorOffset) % MIRRORS.size
            val mirrorUrl = MIRRORS[mirrorIdx]

            for (attempt in 0 until maxAttemptsPerMirror) {
                currentCoroutineContext().ensureActive()
                if (!anyServerAnswered.get()) {
                    val elapsedNoAnswerMs = (nowMs() - requestStartMs).coerceAtLeast(0L)
                    if (elapsedNoAnswerMs >= noAnswerTotalTimeoutMs) {
                        throw NoAnswerTimeoutException(
                            "Ingen Overpass-server svarede inden for ${noAnswerTotalTimeoutMs / 1000} sekunder ($label)"
                        )
                    }
                }

                try {
                    val result = performHttpCallWithTimeouts(
                        mirrorUrl = mirrorUrl,
                        query = query,
                        label = label,
                        requestStartMs = requestStartMs,
                        anyServerAnswered = anyServerAnswered
                    )
                    if (result.isSuccessful) {
                        val body = result.body
                        if (!body.isNullOrBlank()) {
                            return body
                        }
                        throw IOException("Empty response body from $mirrorUrl ($label)")
                    }

                    val code = result.code
                    if (code == 429 || code == 504 || code == 502 || code == 503) {
                        if (hadReadTimeoutOnPreviousMirror) {
                            lastException = IOException("Overpass HTTP $code from $mirrorUrl ($label)")
                            break@outer
                        }
                        backoffEventsCount.incrementAndGet()
                        val retryMs = result.retryAfterSeconds?.let { it * 1000L }
                            ?: (backoffBaseDelayMs * (1L shl attempt))
                        Log.w(TAG, "Overpass $mirrorUrl returned HTTP $code for $label (attempt ${attempt + 1}); backing off ${retryMs}ms")
                        if (retryMs > 0L) {
                            delay(retryMs)
                        }
                        lastException = IOException("Overpass HTTP $code from $mirrorUrl ($label)")
                        if (attempt + 1 >= maxAttemptsPerMirror) {
                            break
                        }
                    } else {
                        lastException = IOException("Overpass HTTP $code from $mirrorUrl ($label)")
                        if (hadReadTimeoutOnPreviousMirror) {
                            break@outer
                        }
                        break
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    if (e is IllegalStateException && e.message == "Network on main thread") {
                        throw e
                    }
                    lastException = e
                    Log.w(TAG, "Overpass mirror $mirrorUrl failed for $label (attempt ${attempt + 1}): ${e.message}")

                    if (e is NoAnswerTimeoutException) {
                        // Round 14 Item 2: If a server sends no response headers within 30 s, move to the next mirror;
                        // if no mirror has answered within 60 s of the request starting, show the error.
                        val elapsedNoAnswerMs = (nowMs() - requestStartMs).coerceAtLeast(0L)
                        if (elapsedNoAnswerMs >= noAnswerTotalTimeoutMs) {
                            break@outer
                        }
                        break // Move to next mirror immediately without retrying same server
                    }

                    if (isReadTimeoutException(e)) {
                        // Round 13 Item 2c: Do NOT retry a request that failed with a read timeout on the same server;
                        // move to the next mirror once, then fail.
                        if (hadReadTimeoutOnPreviousMirror) {
                            break@outer
                        }
                        hadReadTimeoutOnPreviousMirror = true
                        break
                    }
                    if (hadReadTimeoutOnPreviousMirror) {
                        break@outer
                    }
                    if (attempt + 1 < maxAttemptsPerMirror && backoffBaseDelayMs > 0L) {
                        delay(backoffBaseDelayMs)
                    }
                }
            }
        }

        throw (lastException ?: IOException("All Overpass mirrors failed for $label"))
    }

    private suspend fun performHttpCallWithTimeouts(
        mirrorUrl: String,
        query: String,
        label: String,
        requestStartMs: Long,
        anyServerAnswered: AtomicBoolean
    ): HttpResult = coroutineScope {
        val elapsedBeforeMirrorMs = (nowMs() - requestStartMs).coerceAtLeast(0L)
        val remainingNoAnswerMs = (noAnswerTotalTimeoutMs - elapsedBeforeMirrorMs).coerceAtLeast(1L)
        val mirrorHeaderLimitMs = minOf(noAnswerMirrorTimeoutMs, remainingNoAnswerMs).coerceAtLeast(1L)

        val headersGate = CompletableDeferred<Unit>()
        val lastDataTimestampMs = AtomicLong(nowMs())
        val timedOutNoAnswer = AtomicBoolean(false)
        val timedOutIdleData = AtomicBoolean(false)

        val session = TransferSession(
            onHeadersAction = {
                anyServerAnswered.set(true)
                lastDataTimestampMs.set(nowMs())
                headersGate.complete(Unit)
            },
            onDataAction = {
                lastDataTimestampMs.set(nowMs())
            }
        )

        val callDeferred = async {
            executeTransportCall(mirrorUrl, query, session, mirrorHeaderLimitMs)
        }

        val watchdogJob = launch {
            val headersArrived = withTimeoutOrNull(mirrorHeaderLimitMs) {
                headersGate.await()
                true
            } ?: session.headersReceived.get()

            if (!headersArrived && !callDeferred.isCompleted) {
                timedOutNoAnswer.set(true)
                callDeferred.cancel(CancellationException("NO_ANSWER_HEADER_TIMEOUT"))
                return@launch
            }

            // Round 14 Item 2: Once a server is answering, let the download finish;
            // only fail if no data arrives for 30 s (idleDataTimeoutMs).
            val pollIntervalMs = minOf(200L, (idleDataTimeoutMs / 4L).coerceAtLeast(10L))
            while (isActive && callDeferred.isActive) {
                delay(pollIntervalMs)
                val idleMs = (nowMs() - lastDataTimestampMs.get()).coerceAtLeast(0L)
                if (idleMs >= idleDataTimeoutMs && !callDeferred.isCompleted) {
                    timedOutIdleData.set(true)
                    callDeferred.cancel(CancellationException("IDLE_DATA_TIMEOUT"))
                    return@launch
                }
            }
        }

        try {
            val result = callDeferred.await()
            session.onHeadersReceived()
            result
        } catch (ce: CancellationException) {
            // If outer scope was cancelled (e.g. user pressed "Annuller"), propagate CancellationException
            currentCoroutineContext().ensureActive()
            if (timedOutNoAnswer.get()) {
                throw NoAnswerTimeoutException(
                    "Intet svar fra $mirrorUrl inden for ${mirrorHeaderLimitMs}ms ($label)"
                )
            }
            if (timedOutIdleData.get()) {
                throw IdleDataTimeoutException(
                    "Ingen data modtaget fra $mirrorUrl i ${idleDataTimeoutMs}ms ($label)"
                )
            }
            throw ce
        } finally {
            watchdogJob.cancel()
        }
    }

    private suspend fun executeTransportCall(
        mirrorUrl: String,
        query: String,
        session: TransferSession,
        connectAndHeaderTimeoutMs: Long
    ): HttpResult {
        val sessionTransport = customSessionTransport
        if (sessionTransport != null) {
            return sessionTransport.invoke(mirrorUrl, query, session)
        }
        val custom = customTransport
        if (custom != null) {
            return custom.invoke(mirrorUrl, query)
        }

        val cleanQuery = if (query.startsWith("data=")) {
            query
        } else {
            "data=" + URLEncoder.encode(query, "UTF-8")
        }
        val body = cleanQuery.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(mirrorUrl)
            .header("User-Agent", NetworkConstants.USER_AGENT)
            .post(body)
            .build()

        val callClient = okHttpClient.newBuilder()
            .connectTimeout(connectAndHeaderTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(idleDataTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val call = callClient.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    // Response headers have arrived!
                    session.onHeadersReceived()
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val responseBody = response.body
                    val respText = if (responseBody != null) {
                        val buffer = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        responseBody.byteStream().use { input ->
                            while (true) {
                                if (!cont.isActive) {
                                    throw CancellationException("Transfer cancelled")
                                }
                                val readCount = input.read(buffer)
                                if (readCount == -1) break
                                if (readCount > 0) {
                                    session.onDataChunkReceived()
                                    out.write(buffer, 0, readCount)
                                }
                            }
                        }
                        out.toString(Charsets.UTF_8.name())
                    } else {
                        null
                    }
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.success(
                                HttpResult(
                                    code = response.code,
                                    body = respText,
                                    retryAfterSeconds = retryAfter
                                )
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(t))
                }
            }
        }
    }
}
