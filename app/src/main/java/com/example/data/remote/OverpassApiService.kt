package com.example.data.remote

import com.example.util.network.applyDebugMainThreadCheck
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OverpassApiService {
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun queryOverpass(
        @Field("data") query: String
    ): OverpassResponse

    companion object {
        val ENDPOINTS = listOf(
            "https://overpass-api.de/",
            "https://lz4.overpass-api.de/",
            "https://overpass.kumi.systems/"
        )

        fun create(baseUrl: String = ENDPOINTS.first()): OverpassApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .applyDebugMainThreadCheck()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(OverpassApiService::class.java)
        }
    }
}
