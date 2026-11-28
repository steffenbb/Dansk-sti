package com.example.util.network

import com.example.BuildConfig
import okhttp3.OkHttpClient

object NetworkConstants {
    const val USER_AGENT = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"

    val defaultSslPair: Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager>? by lazy {
        runCatching {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            val tm = tmf.trustManagers
                ?.filterIsInstance<javax.net.ssl.X509TrustManager>()
                ?.firstOrNull()
                ?: return@runCatching null
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), null)
            sslContext.socketFactory to tm
        }.getOrNull()
    }
}

fun OkHttpClient.Builder.applyDanskStiDefaults(): OkHttpClient.Builder {
    NetworkConstants.defaultSslPair?.let { (sf, tm) ->
        runCatching { sslSocketFactory(sf, tm) }
    }
    // Idempotent, so builders derived from the shared client don't stack the interceptors twice
    if (interceptors().none { it is UserAgentInterceptor }) addInterceptor(UserAgentInterceptor())
    if (BuildConfig.DEBUG && interceptors().none { it is MainThreadNetworkInterceptor }) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}

/**
 * One base client for the whole app (audit fix batch 4): every service used to build its own
 * OkHttpClient, each with its own connection pool and threads. Services derive from it with
 * [sharedClientBuilder] and set their own timeouts; connections and threads are shared.
 */
private val sharedBaseClient: OkHttpClient by lazy { OkHttpClient.Builder().applyDanskStiDefaults().build() }

/** A builder sharing the app's connection pool and dispatcher, with the app's User-Agent. */
fun sharedClientBuilder(): OkHttpClient.Builder = sharedBaseClient.newBuilder()

