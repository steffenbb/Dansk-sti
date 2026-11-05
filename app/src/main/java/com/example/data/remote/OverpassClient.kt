package com.example.data.remote

import android.util.Log
import com.example.util.network.NetworkConstants
import com.example.util.network.applyDebugMainThreadCheck
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 10B Item 11:
 * One shared client for every Overpass request (trails, POIs, access, water, marked routes)
 * with at most 2 concurrent requests, a FIFO queue, exponential backoff on 429/504,
 * and the existing Overpass mirrors.
 */
object OverpassClient {
    private const val TAG = "OverpassClient"
    const val MAX_CONCURRENT_REQUESTS = 2

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

    private val concurrencySemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val mirrorCursor = AtomicInteger(0)

    val activeRequests = AtomicInteger(0)
    val maxObservedConcurrent = AtomicInteger(0)
    val queuedRequests = AtomicInteger(0)
    val totalRequestsCompleted = AtomicInteger(0)
    val backoffEventsCount = AtomicInteger(0)

    @Volatile
    var backoffBaseDelayMs: Long = 600L

    @Volatile
    var maxAttemptsPerMirror: Int = 2

    @Volatile
    var customTransport: (suspend (mirrorUrl: String, query: String) -> HttpResult)? = null

    @Volatile
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

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
        activeRequests.set(0)
        maxObservedConcurrent.set(0)
        queuedRequests.set(0)
        totalRequestsCompleted.set(0)
        backoffEventsCount.set(0)
        mirrorCursor.set(0)
        backoffBaseDelayMs = 600L
        maxAttemptsPerMirror = 2
        customTransport = null
    }

    suspend fun queryRawJson(
        query: String,
        label: String = "overpass"
    ): String = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        queuedRequests.incrementAndGet()
        try {
            concurrencySemaphore.withPermit {
                queuedRequests.decrementAndGet()
                val currentActive = activeRequests.incrementAndGet()
                maxObservedConcurrent.updateAndGet { prev -> maxOf(prev, currentActive) }
                try {
                    executeWithMirrorsAndBackoff(query, label)
                } finally {
                    activeRequests.decrementAndGet()
                    totalRequestsCompleted.incrementAndGet()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        }
    }

    suspend fun queryParsed(
        query: String,
        label: String = "overpass"
    ): OverpassResponse {
        val raw = queryRawJson(query = query, label = label)
        return responseAdapter.fromJson(raw)
            ?: throw IOException("Failed to parse Overpass response for $label")
    }

    private suspend fun executeWithMirrorsAndBackoff(
        query: String,
        label: String
    ): String {
        val startMirrorIdx = mirrorCursor.getAndUpdate { (it + 1) % MIRRORS.size }
        var lastException: Exception? = null

        for (mirrorOffset in MIRRORS.indices) {
            val mirrorIdx = (startMirrorIdx + mirrorOffset) % MIRRORS.size
            val mirrorUrl = MIRRORS[mirrorIdx]

            for (attempt in 0 until maxAttemptsPerMirror) {
                currentCoroutineContext().ensureActive()
                try {
                    val result = performHttpCall(mirrorUrl, query)
                    if (result.isSuccessful) {
                        val body = result.body
                        if (!body.isNullOrBlank()) {
                            return body
                        }
                        throw IOException("Empty response body from $mirrorUrl ($label)")
                    }

                    val code = result.code
                    if (code == 429 || code == 504 || code == 502 || code == 503) {
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
                    if (attempt + 1 < maxAttemptsPerMirror && backoffBaseDelayMs > 0L) {
                        delay(backoffBaseDelayMs)
                    }
                }
            }
        }

        throw (lastException ?: IOException("All Overpass mirrors failed for $label"))
    }

    private suspend fun performHttpCall(mirrorUrl: String, query: String): HttpResult {
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

        okHttpClient.newCall(request).execute().use { response ->
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
