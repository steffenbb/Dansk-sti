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
    addInterceptor(UserAgentInterceptor())
    if (BuildConfig.DEBUG) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}

