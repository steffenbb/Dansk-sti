package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.util.network.NetworkConstants
import com.example.util.network.applyDanskStiDefaults
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlin.random.Random

class NoInternetException(
    message: String = "Ingen internetforbindelse."
) : IOException(message)

class OverpassQueryException(
    val httpCode: Int = 400,
    message: String = "Kortdata kan ikke hentes lige nu. Prøv igen om lidt."
) : IOException(message)

class OverpassUnavailableException(
    message: String = "Kortdata kan ikke hentes lige nu. Prøv igen om lidt.",
    cause: Throwable? = null
) : IOException(message, cause)

object OverpassClient {
    private const val TAG = "OverpassClient"
    const val MAX_CONCURRENT_REQUESTS = 2
    const val MAX_CONCURRENT_BACKGROUND_REQUESTS = 1

    const val PRIMARY = "https://overpass-api.de/api/interpreter"
    const val FALLBACK = "https://overpass.private.coffee/api/interpreter"
    val SERVERS: List<String> = listOf(PRIMARY, FALLBACK)

    private const val BASE_COOLDOWN_MS = 2L * 60_000L
    private const val MAX_COOLDOWN_MS = 15L * 60_000L
    private const val PRIMARY_HEADER_TIMEOUT_MS = 20_000L
    private const val USER_INITIATED_BUDGET_MS = 60_000L
    private const val BACKGROUND_BUDGET_MS = 90_000L

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

    data class HttpResult(
        val code: Int,
        val body: String?,
        val retryAfterSeconds: Long? = null
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    data class ServerState(
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L,
        var probeInFlight: Boolean = false,
        var lastCooldownDurationMs: Long = 0L
    )

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

    private val circuitLock = Any()
    private val serverStates = mapOf(
        PRIMARY to ServerState(),
        FALLBACK to ServerState()
    )

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @VisibleForTesting
    internal val activeRequests = AtomicInteger(0)

    @VisibleForTesting
    internal val maxObservedConcurrent = AtomicInteger(0)

    @VisibleForTesting
    internal val queuedRequests = AtomicInteger(0)

    @VisibleForTesting
    internal val totalRequestsCompleted = AtomicInteger(0)

    @VisibleForTesting
    internal val backoffEventsCount = AtomicInteger(0)

    @VisibleForTesting
    internal val lastUsedReadTimeoutSeconds = AtomicLong(45L)

    @VisibleForTesting
    internal val observedReadTimeoutsByLabel = ConcurrentHashMap<String, Long>()

    @VisibleForTesting
    @Volatile
    internal var noAnswerMirrorTimeoutMs: Long = PRIMARY_HEADER_TIMEOUT_MS

    @VisibleForTesting
    @Volatile
    internal var noAnswerTotalTimeoutMs: Long = USER_INITIATED_BUDGET_MS

    @VisibleForTesting
    @Volatile
    internal var customTransport: (suspend (mirrorUrl: String, query: String) -> HttpResult)? = null

    @VisibleForTesting
    @Volatile
    internal var customSessionTransport: (suspend (mirrorUrl: String, query: String, session: TransferSession) -> HttpResult)? = null

    @VisibleForTesting
    @Volatile
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    @Volatile
    internal var currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    @Volatile
    internal var connectivityChecker: () -> Boolean = { checkSystemConnectivity() }

    @VisibleForTesting
    @Volatile
    internal var cooldownJitterFactorProvider: () -> Double = { Random.Default.nextDouble(0.9, 1.1) }

    @VisibleForTesting
    @Volatile
    internal var interServerBackoffMsProvider: () -> Long = { 1_000L + Random.Default.nextLong(0L, 501L) }

    private fun nowMs(): Long = currentTimeMillisProvider.invoke()

    private fun checkSystemConnectivity(): Boolean {
        if (customTransport != null || customSessionTransport != null) {
            return true
        }
        val ctx = appContext ?: return true
        val repoOverride = runCatching {
            (ctx.applicationContext as? com.example.DanskStiApp)?.repository?.isOnlineOverrideForTests
        }.getOrNull()
        if (repoOverride != null) {
            return repoOverride
        }
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private val SERVER_TIMEOUT_REGEX = Regex("""\[timeout:(\d+)\]""")
    private val MAXSIZE_REGEX = Regex("""\[maxsize:\d+\]""")
    private val REMARK_REGEX = Regex(""""remark"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    fun parseServerTimeoutSeconds(query: String): Long? {
        return SERVER_TIMEOUT_REGEX.find(query)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    fun computeClientReadTimeoutSeconds(query: String, defaultServerTimeoutSec: Long = 30L): Long {
        val serverTimeout = parseServerTimeoutSeconds(query) ?: defaultServerTimeoutSec
        return serverTimeout + 15L
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PRIMARY_HEADER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .applyDanskStiDefaults()
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

    @VisibleForTesting
    internal fun getServerStateSnapshot(serverUrl: String): ServerState {
        synchronized(circuitLock) {
            return serverStates.getValue(serverUrl).copy()
        }
    }

    @VisibleForTesting
    internal fun resetMetricsForTest() {
        synchronized(queueLock) {
            waitQueue.forEach { it.gate.cancel() }
            waitQueue.clear()
            activePermits = 0
            activeBackgroundPermits = 0
        }
        synchronized(circuitLock) {
            serverStates.values.forEach { state ->
                state.consecutiveFailures = 0
                state.cooldownUntilMs = 0L
                state.probeInFlight = false
                state.lastCooldownDurationMs = 0L
            }
        }
        activeRequests.set(0)
        maxObservedConcurrent.set(0)
        queuedRequests.set(0)
        totalRequestsCompleted.set(0)
        backoffEventsCount.set(0)
        lastUsedReadTimeoutSeconds.set(45L)
        observedReadTimeoutsByLabel.clear()
        noAnswerMirrorTimeoutMs = PRIMARY_HEADER_TIMEOUT_MS
        noAnswerTotalTimeoutMs = USER_INITIATED_BUDGET_MS
        customTransport = null
        customSessionTransport = null
        ioDispatcher = Dispatchers.IO
        currentTimeMillisProvider = { System.currentTimeMillis() }
        connectivityChecker = { checkSystemConnectivity() }
        cooldownJitterFactorProvider = { Random.Default.nextDouble(0.9, 1.1) }
        interServerBackoffMsProvider = { 1_000L + Random.Default.nextLong(0L, 501L) }
    }

    /**
     * Picks the server order for a request and reserves a probe slot if the first chosen
     * server has just finished its cooldown.
     * - Tries PRIMARY first unless PRIMARY is in cooldown (or already being probed), then FALLBACK.
     * - Only one probe at a time per server: while a probe is in flight, other requests skip it.
     * - If both PRIMARY and FALLBACK are in cooldown, still tries PRIMARY then FALLBACK once.
     */
    internal fun selectServerOrder(now: Long = nowMs()): Pair<List<String>, String?> {
        synchronized(circuitLock) {
            val available = mutableListOf<String>()
            var reservedProbeServer: String? = null
            for (server in SERVERS) {
                val state = serverStates.getValue(server)
                val inCooldown = now < state.cooldownUntilMs
                if (!inCooldown && !state.probeInFlight) {
                    if (available.isEmpty() && state.consecutiveFailures > 0) {
                        state.probeInFlight = true
                        reservedProbeServer = server
                    }
                    available.add(server)
                }
            }
            if (available.isNotEmpty()) {
                return available to reservedProbeServer
            }
            val anyProbeInFlight = SERVERS.any { serverStates.getValue(it).probeInFlight }
            if (anyProbeInFlight) {
                val nonProbing = SERVERS.filter { !serverStates.getValue(it).probeInFlight }
                if (nonProbing.isNotEmpty()) {
                    return nonProbing to null
                }
            }
            return SERVERS to null
        }
    }

    private fun tryAcquireServerForFollowUpAttempt(serverUrl: String, now: Long): Pair<Boolean, Boolean> {
        synchronized(circuitLock) {
            val state = serverStates.getValue(serverUrl)
            if (state.probeInFlight) {
                return false to false
            }
            if (state.consecutiveFailures > 0 && now >= state.cooldownUntilMs) {
                state.probeInFlight = true
                return true to true
            }
            return true to false
        }
    }

    private fun releaseProbeIfUnresolved(serverUrl: String?) {
        if (serverUrl == null) return
        synchronized(circuitLock) {
            serverStates[serverUrl]?.probeInFlight = false
        }
    }

    private fun onServerSuccess(serverUrl: String) {
        synchronized(circuitLock) {
            val state = serverStates.getValue(serverUrl)
            state.consecutiveFailures = 0
            state.cooldownUntilMs = 0L
            state.probeInFlight = false
            state.lastCooldownDurationMs = 0L
        }
    }

    internal fun computeBaseCooldownMs(consecutiveFailures: Int): Long {
        val exp = (consecutiveFailures - 1).coerceIn(0, 10)
        return minOf(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS * (1L shl exp))
    }

    private fun onServerFailure(
        serverUrl: String,
        httpCode: Int? = null,
        retryAfterSeconds: Long? = null
    ) {
        val now = nowMs()
        synchronized(circuitLock) {
            val state = serverStates.getValue(serverUrl)
            state.consecutiveFailures += 1
            state.probeInFlight = false
            val baseCooldownMs = computeBaseCooldownMs(state.consecutiveFailures)
            val jitter = cooldownJitterFactorProvider.invoke().coerceIn(0.9, 1.1)
            val jitteredCooldownMs = (baseCooldownMs * jitter).toLong().coerceAtMost(MAX_COOLDOWN_MS)
            val finalCooldownMs = if ((httpCode == 429 || httpCode == 406) && retryAfterSeconds != null && retryAfterSeconds > 0L) {
                val cappedRetryMs = minOf(retryAfterSeconds * 1_000L, MAX_COOLDOWN_MS)
                maxOf(jitteredCooldownMs, cappedRetryMs)
            } else {
                jitteredCooldownMs
            }
            state.lastCooldownDurationMs = finalCooldownMs
            state.cooldownUntilMs = now + finalCooldownMs
        }
    }

    private fun hostOf(url: String): String {
        return url.substringAfter("://").substringBefore("/")
    }

    internal fun extractErrorRemark(body: String): String? {
        val window = if (body.length <= 4096) {
            body
        } else {
            body.take(2048) + "\n" + body.takeLast(2048)
        }
        for (match in REMARK_REGEX.findAll(window)) {
            val remark = match.groupValues.getOrNull(1) ?: continue
            val lower = remark.lowercase()
            if (lower.contains("runtime error") ||
                lower.contains("timed out") ||
                lower.contains("out of memory") ||
                lower.contains("dispatcher")
            ) {
                return remark
            }
        }
        return null
    }

    private fun insertWaiterByPriorityLocked(waiter: QueueWaiter): Int {
        return if (waiter.priority == Priority.USER_INITIATED) {
            val firstBackgroundIdx = waitQueue.indexOfFirst { it.priority == Priority.BACKGROUND }
            if (firstBackgroundIdx >= 0) {
                waitQueue.add(firstBackgroundIdx, waiter)
                firstBackgroundIdx
            } else {
                waitQueue.add(waiter)
                waitQueue.lastIndex
            }
        } else {
            waitQueue.add(waiter)
            waitQueue.lastIndex
        }
    }

    private fun canAcquireImmediatelyLocked(priority: Priority): Boolean {
        if (activePermits >= MAX_CONCURRENT_REQUESTS) return false
        return when (priority) {
            Priority.USER_INITIATED -> waitQueue.none { it.priority == Priority.USER_INITIATED }
            Priority.BACKGROUND -> activeBackgroundPermits < MAX_CONCURRENT_BACKGROUND_REQUESTS && waitQueue.isEmpty()
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
        return waitQueue.mapIndexed { idx, w -> w to computeAheadCountLocked(idx) }
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
        if (!connectivityChecker.invoke()) {
            throw NoInternetException("Ingen internetforbindelse.")
        }

        acquirePermit(priority = priority, label = label, onStatusChange = onStatusChange)
        val currentActive = activeRequests.incrementAndGet()
        maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, currentActive) }
        try {
            executeWithCircuitBreaker(query = query, label = label, priority = priority)
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
            ?: throw OverpassUnavailableException("Kortdata kan ikke hentes lige nu. Prøv igen om lidt.")
    }

    private suspend fun executeWithCircuitBreaker(
        query: String,
        label: String,
        priority: Priority
    ): String {
        if (!connectivityChecker.invoke()) {
            throw NoInternetException("Ingen internetforbindelse.")
        }

        var activeQuery = query
        var retriedWithoutMaxsize = false

        val readTimeoutSec = computeClientReadTimeoutSeconds(activeQuery)
        lastUsedReadTimeoutSeconds.set(readTimeoutSec)
        observedReadTimeoutsByLabel[label] = readTimeoutSec

        val totalNoAnswerBudgetMs = if (priority == Priority.BACKGROUND && noAnswerTotalTimeoutMs == USER_INITIATED_BUDGET_MS) {
            BACKGROUND_BUDGET_MS
        } else {
            noAnswerTotalTimeoutMs
        }

        val requestStartMs = nowMs()
        val anyServerAnswered = AtomicBoolean(false)

        val (serverOrder, initialReservedProbe) = selectServerOrder(requestStartMs)
        var currentProbeServer: String? = initialReservedProbe
        var lastCause: Throwable? = null

        try {
            for ((index, serverUrl) in serverOrder.withIndex()) {
                currentCoroutineContext().ensureActive()

                if (index > 0) {
                    val backoffMs = interServerBackoffMsProvider.invoke().coerceIn(0L, 1_500L)
                    backoffEventsCount.incrementAndGet()
                    if (backoffMs > 0L) {
                        delay(backoffMs)
                    }
                    val (canTry, isProbe) = tryAcquireServerForFollowUpAttempt(serverUrl, nowMs())
                    if (!canTry) {
                        continue
                    }
                    if (isProbe) {
                        currentProbeServer = serverUrl
                    }
                }

                var trySameServerAgain = true
                while (trySameServerAgain) {
                    trySameServerAgain = false
                    currentCoroutineContext().ensureActive()

                    if (!anyServerAnswered.get()) {
                        val elapsedNoAnswerMs = (nowMs() - requestStartMs).coerceAtLeast(0L)
                        if (elapsedNoAnswerMs >= totalNoAnswerBudgetMs) {
                            releaseProbeIfUnresolved(currentProbeServer)
                            currentProbeServer = null
                            throw OverpassUnavailableException(
                                "Kortdata kan ikke hentes lige nu. Prøv igen om lidt.",
                                lastCause
                            )
                        }
                    }

                    val idleLimitMs = computeClientReadTimeoutSeconds(activeQuery) * 1_000L

                    try {
                        val result = performHttpCallWithTimeouts(
                            serverUrl = serverUrl,
                            query = activeQuery,
                            requestStartMs = requestStartMs,
                            totalNoAnswerBudgetMs = totalNoAnswerBudgetMs,
                            idleTimeoutMs = idleLimitMs,
                            anyServerAnswered = anyServerAnswered
                        )

                        if (result.code == 400) {
                            releaseProbeIfUnresolved(currentProbeServer)
                            currentProbeServer = null
                            throw OverpassQueryException(
                                httpCode = 400,
                                message = "Kortdata kan ikke hentes lige nu. Prøv igen om lidt."
                            )
                        }

                        if (result.isSuccessful) {
                            val trimmedBody = result.body?.trim()
                            if (trimmedBody.isNullOrEmpty() || !trimmedBody.startsWith("{")) {
                                runCatching { Log.w(TAG, "Server ${hostOf(serverUrl)} returned non-JSON body on HTTP 200") }
                                onServerFailure(serverUrl)
                                currentProbeServer = null
                                lastCause = IOException("Invalid body from ${hostOf(serverUrl)}")
                                break
                            }

                            val remark = extractErrorRemark(trimmedBody)
                            if (remark != null) {
                                val isOom = remark.lowercase().contains("out of memory")
                                if (isOom && !retriedWithoutMaxsize && MAXSIZE_REGEX.containsMatchIn(activeQuery)) {
                                    retriedWithoutMaxsize = true
                                    activeQuery = activeQuery.replace(MAXSIZE_REGEX, "")
                                    runCatching { Log.w(TAG, "Server ${hostOf(serverUrl)} reported out of memory with maxsize; retrying once without maxsize") }
                                    trySameServerAgain = true
                                    continue
                                }
                                runCatching { Log.w(TAG, "Server ${hostOf(serverUrl)} returned error remark on HTTP 200: $remark") }
                                onServerFailure(serverUrl)
                                currentProbeServer = null
                                lastCause = IOException("Overpass error remark from ${hostOf(serverUrl)}")
                                break
                            }

                            onServerSuccess(serverUrl)
                            currentProbeServer = null
                            return result.body!!
                        }

                        val code = result.code
                        runCatching { Log.w(TAG, "Server ${hostOf(serverUrl)} returned HTTP $code") }
                        onServerFailure(
                            serverUrl = serverUrl,
                            httpCode = code,
                            retryAfterSeconds = result.retryAfterSeconds
                        )
                        currentProbeServer = null
                        lastCause = IOException("HTTP $code from ${hostOf(serverUrl)}")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (qe: OverpassQueryException) {
                        throw qe
                    } catch (nie: NoInternetException) {
                        throw nie
                    } catch (e: Exception) {
                        if (e is IllegalStateException && e.message == "Network on main thread") {
                            throw e
                        }
                        if (!connectivityChecker.invoke()) {
                            releaseProbeIfUnresolved(currentProbeServer)
                            currentProbeServer = null
                            throw NoInternetException("Ingen internetforbindelse.")
                        }
                        runCatching { Log.w(TAG, "Server ${hostOf(serverUrl)} failed: ${e.javaClass.simpleName}") }
                        onServerFailure(serverUrl)
                        currentProbeServer = null
                        lastCause = e
                    }
                }
            }
        } finally {
            releaseProbeIfUnresolved(currentProbeServer)
        }

        throw OverpassUnavailableException(
            "Kortdata kan ikke hentes lige nu. Prøv igen om lidt.",
            lastCause
        )
    }

    private suspend fun performHttpCallWithTimeouts(
        serverUrl: String,
        query: String,
        requestStartMs: Long,
        totalNoAnswerBudgetMs: Long,
        idleTimeoutMs: Long,
        anyServerAnswered: AtomicBoolean
    ): HttpResult = coroutineScope {
        val elapsedBeforeServerMs = (nowMs() - requestStartMs).coerceAtLeast(0L)
        val remainingNoAnswerMs = (totalNoAnswerBudgetMs - elapsedBeforeServerMs).coerceAtLeast(1L)
        val headerLimitMs = if (serverUrl == PRIMARY) {
            minOf(noAnswerMirrorTimeoutMs, remainingNoAnswerMs).coerceAtLeast(1L)
        } else {
            remainingNoAnswerMs
        }

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
            executeTransportCall(
                serverUrl = serverUrl,
                query = query,
                session = session,
                connectAndHeaderTimeoutMs = headerLimitMs,
                readTimeoutMs = idleTimeoutMs
            )
        }

        val watchdogJob = launch {
            val headersArrived = withTimeoutOrNull(headerLimitMs) {
                headersGate.await()
                true
            } ?: session.headersReceived.get()

            if (!headersArrived && !callDeferred.isCompleted) {
                timedOutNoAnswer.set(true)
                callDeferred.cancel(CancellationException("NO_ANSWER_HEADER_TIMEOUT"))
                return@launch
            }

            val pollIntervalMs = minOf(200L, (idleTimeoutMs / 4L).coerceAtLeast(10L))
            while (isActive && callDeferred.isActive) {
                delay(pollIntervalMs)
                val idleMs = (nowMs() - lastDataTimestampMs.get()).coerceAtLeast(0L)
                if (idleMs >= idleTimeoutMs && !callDeferred.isCompleted) {
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
            currentCoroutineContext().ensureActive()
            if (timedOutNoAnswer.get()) {
                throw NoAnswerTimeoutException("Intet svar fra ${hostOf(serverUrl)}")
            }
            if (timedOutIdleData.get()) {
                throw IdleDataTimeoutException("Ingen data fra ${hostOf(serverUrl)}")
            }
            throw ce
        } finally {
            watchdogJob.cancel()
        }
    }

    private suspend fun executeTransportCall(
        serverUrl: String,
        query: String,
        session: TransferSession,
        connectAndHeaderTimeoutMs: Long,
        readTimeoutMs: Long
    ): HttpResult {
        val sessionTransport = customSessionTransport
        if (sessionTransport != null) {
            return sessionTransport.invoke(serverUrl, query, session)
        }
        val custom = customTransport
        if (custom != null) {
            return custom.invoke(serverUrl, query)
        }

        val cleanQuery = if (query.startsWith("data=")) {
            query
        } else {
            "data=" + URLEncoder.encode(query, "UTF-8")
        }
        val body = cleanQuery.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .header("User-Agent", NetworkConstants.USER_AGENT)
            .post(body)
            .build()

        val callClient = okHttpClient.newBuilder()
            .connectTimeout(connectAndHeaderTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val call = callClient.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            try {
                call.execute().use { response ->
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
