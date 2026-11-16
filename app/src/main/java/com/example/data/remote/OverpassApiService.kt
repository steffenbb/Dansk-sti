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
        fun create(): OverpassApiService {
            return object : OverpassApiService {
                override suspend fun queryOverpass(query: String): OverpassResponse {
                    return OverpassClient.queryParsed(query = query, label = "OverpassApiService")
                }
            }
        }
    }
}
