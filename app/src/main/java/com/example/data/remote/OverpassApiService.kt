package com.example.data.remote

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

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

        /**
         * Round 10B Item 11:
         * All OverpassApiService instances delegate to the shared OverpassClient
         * (max 2 concurrent requests, FIFO queue, backoff on 429/504, and mirror fallback).
         */
        fun create(baseUrl: String = ENDPOINTS.first()): OverpassApiService {
            return object : OverpassApiService {
                override suspend fun queryOverpass(query: String): OverpassResponse {
                    return OverpassClient.queryParsed(query = query, label = "OverpassApiService($baseUrl)")
                }
            }
        }
    }
}
