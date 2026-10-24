package com.example.util.network

import android.os.Looper
import com.example.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

object NetworkConstants {
    const val USER_AGENT = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"
}

/**
 * Interceptor that enforces a single canonical User-Agent header on every HTTP request.
 */
class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val requestWithUserAgent = chain.request().newBuilder()
            .header("User-Agent", NetworkConstants.USER_AGENT)
            .build()
        return chain.proceed(requestWithUserAgent)
    }
}

/**
 * Interceptor enforced on every OkHttpClient in debug builds.
 * Throws IllegalStateException("Network on main thread") if any network call
 * is executed on the Android main thread.
 */
class MainThreadNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Network on main thread")
        }
        return chain.proceed(chain.request())
    }
}

fun OkHttpClient.Builder.applyDebugMainThreadCheck(): OkHttpClient.Builder {
    addInterceptor(UserAgentInterceptor())
    if (BuildConfig.DEBUG) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}
