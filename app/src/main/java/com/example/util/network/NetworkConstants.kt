package com.example.util.network

import com.example.BuildConfig
import okhttp3.OkHttpClient

object NetworkConstants {
    const val USER_AGENT = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"

}

fun OkHttpClient.Builder.applyDanskStiDefaults(): OkHttpClient.Builder {
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

