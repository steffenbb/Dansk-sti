package com.example.util.network

import com.example.BuildConfig
import okhttp3.OkHttpClient

object NetworkConstants {
    const val USER_AGENT = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"
}

fun OkHttpClient.Builder.applyDanskStiDefaults(): OkHttpClient.Builder {
    try {
        okhttp3.internal.platform.Platform.get().platformTrustManager()
    } catch (_: Throwable) {
        try {
            okhttp3.internal.platform.Platform.resetForTests()
        } catch (_: Throwable) {
        }
    }
    addInterceptor(UserAgentInterceptor())
    if (BuildConfig.DEBUG) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}
