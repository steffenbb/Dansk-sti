package com.example.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface OverpassApiService {

    @FormUrlEncoded
    @POST("interpreter")
    suspend fun queryOverpassPost(
        @Field("data") query: String
    ): OverpassResponse

    @GET("interpreter")
    suspend fun queryOverpassGet(
        @Query("data") query: String
    ): OverpassResponse

    companion object {
        private const val BASE_URL = "https://overpass-api.de/api/"

        fun create(): OverpassApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "DanskStiApp/1.0 (Android; Outdoor Hiking Denmark)")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(45, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(OverpassApiService::class.java)
        }
    }
}
