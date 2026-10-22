package com.example.util.network

import android.os.Looper
import com.example.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

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
    if (BuildConfig.DEBUG) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}
