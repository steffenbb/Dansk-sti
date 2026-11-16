package com.example.util.network

import com.example.BuildConfig
import okhttp3.OkHttpClient

object NetworkConstants {
    const val USER_AGENT = "DanskSti/${BuildConfig.VERSION_NAME} (easysidedk.brydskov@gmail.com)"
}

fun OkHttpClient.Builder.applyDanskStiDefaults(): OkHttpClient.Builder {
    addInterceptor(UserAgentInterceptor())
    if (BuildConfig.DEBUG) {
        addInterceptor(MainThreadNetworkInterceptor())
    }
    return this
}
