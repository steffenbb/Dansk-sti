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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Round 10B Item 11 & Round 13 Item 2:
 * One shared client for every Overpass request (trails, POIs, access, water, marked routes)
 * with at most 2 concurrent requests, a priority queue (USER_INITIATED ahead of BACKGROUND),
 * per-query read timeout = server [timeout:N] + 15 s, no retry on the same server on read timeout
 * (move to next mirror once, then fail), and queue status callbacks.
 */
object OverpassClient {
    private const val TAG = "OverpassClient"
    const val MAX_CONCURRENT_REQUESTS = 2

    enum class Priority {
        USER_INITIATED,
        BACKGROUND
    }

    sealed class RequestState {
        data class Queued(val aheadCount: Int) : RequestState()
        object Running : RequestState()
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

    @Volatile
    var customTransport: (suspend (mirrorUrl: String, query: String) -> HttpResult)? = null

    @Volatile
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

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
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
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
        customTransport = null
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

    private fun computeAheadCountLocked(queueIndex: Int): Int {
        // Count requests ahead in waitQueue, or active permits if at the front of the wait queue
        return if (queueIndex > 0) queueIndex else activePermits.coerceAtLeast(1)
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
            if (activePermits < MAX_CONCURRENT_REQUESTS && waitQueue.isEmpty()) {
                activePermits++
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
                releasePermit()
            } else {
                postCancelNotifications.forEach { (w, ahead) ->
                    w.onStatusChange?.invoke(RequestState.Queued(ahead))
                }
            }
            throw ce
        }
    }

    private fun releasePermit() {
        var nextWaiter: QueueWaiter? = null
        var notifications: List<Pair<QueueWaiter, Int>> = emptyList()
        synchronized(queueLock) {
            while (waitQueue.isNotEmpty()) {
                val candidate = waitQueue.removeAt(0)
                queuedRequests.set(waitQueue.size)
                if (candidate.gate.complete(Unit)) {
                    nextWaiter = candidate
                    break
                }
            }
            if (nextWaiter == null) {
                activePermits = (activePermits - 1).coerceAtLeast(0)
            } else {
                notifications = notifyWaitQueuePositionsLocked()
            }
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
        acquirePermit(priority = priority, label = label, onStatusChange = onStatusChange)
        val currentActive = activeRequests.incrementAndGet()
        maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, currentActive) }
        try {
            executeWithMirrorsAndBackoff(query = query, label = label)
        } finally {
            activeRequests.decrementAndGet()
            totalRequestsCompleted.incrementAndGet()
            releasePermit()
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

        val startMirrorIdx = mirrorCursor.getAndUpdate { (it + 1) % MIRRORS.size }
        var lastException: Exception? = null
        var hadReadTimeoutOnPreviousMirror = false

        outer@ for (mirrorOffset in MIRRORS.indices) {
            val mirrorIdx = (startMirrorIdx + mirrorOffset) % MIRRORS.size
            val mirrorUrl = MIRRORS[mirrorIdx]

            for (attempt in 0 until maxAttemptsPerMirror) {
                currentCoroutineContext().ensureActive()
                try {
                    val result = performHttpCall(mirrorUrl, query, readTimeoutSec)
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
                        // On 429/504, if we already retried once on this mirror, rotate to next mirror
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

    private suspend fun performHttpCall(
        mirrorUrl: String,
        query: String,
        readTimeoutSec: Long
    ): HttpResult {
        val custom = customTransport
        if (custom != null) {
            return custom.invoke(mirrorUrl, query)
        }
        if (android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                throw IllegalStateException("Network on main thread")
            }
            throw IOException("External Overpass network disabled in Robolectric unit test")
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
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()

        callClient.newCall(request).execute().use { response ->
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val respBody = response.body?.string()
            return HttpResult(
                code = response.code,
                body = respBody,
                retryAfterSeconds = retryAfter
            )
        }
    }
}
