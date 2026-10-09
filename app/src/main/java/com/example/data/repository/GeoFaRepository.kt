package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class GeoFaRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "geofa").apply { if (!exists()) mkdirs() }

    suspend fun fetchGeoFaShelters(): List<ShelterEntity> = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "shelters.json")
        val sql = "SELECT * FROM fkg.t_5800_fac_pkt WHERE facil_ty_k IN (3012, 3022, 3031)"
        val jsonStr = queryWithRetry(sql)
        if (jsonStr != null) {
            try {
                cacheFile.writeText(jsonStr)
                val parsed = parseSheltersGeoJson(jsonStr)
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse live GeoFA shelters: ${e.message}", e)
            }
        }
        // Fallback to cache file
        if (cacheFile.exists()) {
            try {
                val parsed = parseSheltersGeoJson(cacheFile.readText())
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse cached GeoFA shelters: ${e.message}", e)
            }
        }
        // Fallback to bundled asset
        val assetJson = readAssetGeoJsonGz("shelters.geojson.gz")
        if (assetJson != null) {
            try {
                return@withContext parseSheltersGeoJson(assetJson)
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse asset shelters: ${e.message}", e)
            }
        }
        emptyList()
    }

    suspend fun fetchGeoFaFriTeltning(): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "fri_teltning.json")
        val sql = "SELECT * FROM fkg.t_5801_fac_fl WHERE facil_ty_k = 3071"
        val jsonStr = queryWithRetry(sql)
        if (jsonStr != null) {
            try {
                cacheFile.writeText(jsonStr)
                val parsed = parseFriTeltningGeoJson(jsonStr)
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse live GeoFA fri teltning: ${e.message}", e)
            }
        }
        if (cacheFile.exists()) {
            try {
                val parsed = parseFriTeltningGeoJson(cacheFile.readText())
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse cached GeoFA fri teltning: ${e.message}", e)
            }
        }
        val assetJson = readAssetGeoJsonGz("fri_teltning.geojson.gz")
        if (assetJson != null) {
            try {
                return@withContext parseFriTeltningGeoJson(assetJson)
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse asset fri teltning: ${e.message}", e)
            }
        }
        emptyList()
    }

    suspend fun fetchGeoFaPois(): List<PoiEntity> = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "toilets_water.json")
        val sql = "SELECT * FROM fkg.t_5800_fac_pkt WHERE facil_ty_k IN (1012, 1222)"
        val jsonStr = queryWithRetry(sql)
        if (jsonStr != null) {
            try {
                cacheFile.writeText(jsonStr)
                val parsed = parsePoisGeoJson(jsonStr)
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse live GeoFA POIs: ${e.message}", e)
            }
        }
        if (cacheFile.exists()) {
            try {
                val parsed = parsePoisGeoJson(cacheFile.readText())
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse cached GeoFA POIs: ${e.message}", e)
            }
        }
        val assetJson = readAssetGeoJsonGz("toilets_water.geojson.gz")
        if (assetJson != null) {
            try {
                return@withContext parsePoisGeoJson(assetJson)
            } catch (e: Exception) {
                Log.e("GeoFaRepo", "Failed to parse asset toilets_water: ${e.message}", e)
            }
        }
        emptyList()
    }

    private fun readAssetGeoJsonGz(assetName: String): String? {
        return try {
            context.assets.open("geofa/$assetName").use { input ->
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e("GeoFaRepo", "Failed to read asset geofa/$assetName: ${e.message}")
            null
        }
    }

    private fun queryWithRetry(sql: String): String? {
        val encodedSql = URLEncoder.encode(sql, "UTF-8")
        val url = "https://geofa.geodanmark.dk/api/v2/sql/fkg?q=$encodedSql&format=geojson&geoformat=geojson&srs=4326"

        for (attempt in 1..3) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "DanskSti/1.0 (Android Trail App)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) return body
                    } else {
                        Log.w("GeoFaRepo", "GeoFA HTTP ${response.code} on attempt $attempt")
                    }
                }
            } catch (e: Exception) {
                Log.w("GeoFaRepo", "GeoFA error on attempt $attempt: ${e.message}")
            }
            if (attempt < 3) {
                try {
                    Thread.sleep((attempt * 1500).toLong())
                } catch (_: InterruptedException) {}
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSheltersGeoJson(jsonStr: String): List<ShelterEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: return emptyList()
        val result = mutableListOf<ShelterEntity>()

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            val geom = f["geometry"] as? Map<String, Any> ?: continue
            val coordsRaw = geom["coordinates"] as? List<*> ?: continue
            val coords: List<Number>? = when {
                coordsRaw.isNotEmpty() && coordsRaw[0] is Number -> @Suppress("UNCHECKED_CAST") (coordsRaw as? List<Number>)
                coordsRaw.isNotEmpty() && coordsRaw[0] is List<*> -> {
                    val first = coordsRaw[0] as? List<*>
                    if (first != null && first.isNotEmpty() && first[0] is Number) {
                        @Suppress("UNCHECKED_CAST") (first as? List<Number>)
                    } else null
                }
                else -> null
            }
            if (coords == null || coords.size < 2) continue

            val lng = coords[0].toDouble()
            val lat = coords[1].toDouble()
            val objektId = props["objekt_id"] as? String ?: "geofa_$idx"
            val navn = (props["navn"] as? String)?.trim()
            val beskrivels = ((props["beskrivels"] as? String) ?: (props["lang_beskr"] as? String))?.trim()
            val bookingUrl = ((props["bookinglink"] as? String) ?: (props["link"] as? String) ?: (props["link1"] as? String))?.trim()
            val ownership = ((props["ansva_v"] as? String) ?: (props["ansvar_org"] as? String) ?: (props["cvr_navn"] as? String))?.trim()
            val antalPl = (props["antal_pl"] as? Number)?.toInt()

            result.add(
                ShelterEntity(
                    id = objektId,
                    name = if (!navn.isNullOrBlank()) navn else "Shelter / Teltplads",
                    lat = lat,
                    lng = lng,
                    description = if (!beskrivels.isNullOrBlank()) beskrivels else null,
                    bookingRequired = !bookingUrl.isNullOrBlank() || props["book"] == "Ja",
                    ownership = if (!ownership.isNullOrBlank()) ownership else null,
                    capacity = antalPl, // null if unknown, never default to 4
                    hasWaterNearby = false, // computed by 500m proximity
                    hasToiletNearby = false, // computed by 500m proximity
                    source = "geofa",
                    bookingUrl = if (!bookingUrl.isNullOrBlank()) bookingUrl else null,
                    shelterType = props["facil_ty"] as? String ?: "Shelter",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFriTeltningGeoJson(jsonStr: String): List<FriTeltningEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: emptyList()
        val result = mutableListOf<FriTeltningEntity>()

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            val geom = f["geometry"] as? Map<String, Any> ?: continue
            val coords = geom["coordinates"] as? List<Any> ?: continue
            val objektId = props["objekt_id"] as? String ?: "geofa_area_$idx"
            val navn = (props["navn"] as? String)?.trim()

            var sumLat = 0.0
            var sumLng = 0.0
            var count = 0
            val pointsList = mutableListOf<com.example.data.model.GeoPointDto>()

            fun extract(list: List<Any>) {
                for (item in list) {
                    if (item is List<*>) {
                        if (item.size >= 2 && item[0] is Number && item[1] is Number) {
                            val lng = (item[0] as Number).toDouble()
                            val lat = (item[1] as Number).toDouble()
                            sumLng += lng
                            sumLat += lat
                            count++
                            pointsList.add(com.example.data.model.GeoPointDto(lat, lng))
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            extract(item as List<Any>)
                        }
                    }
                }
            }
            extract(coords)

            val centerLat = if (count > 0) sumLat / count else 55.0
            val centerLng = if (count > 0) sumLng / count else 10.0

            val polygonJson = try {
                val ptType = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.example.data.model.GeoPointDto::class.java)
                moshi.adapter<List<com.example.data.model.GeoPointDto>>(ptType).toJson(pointsList)
            } catch (e: Exception) {
                "[]"
            }

            val beskrivels = ((props["beskrivels"] as? String) ?: (props["lang_beskr"] as? String))?.trim()

            result.add(
                FriTeltningEntity(
                    id = objektId,
                    name = if (!navn.isNullOrBlank()) navn else "Fri Teltningsområde",
                    centerLat = centerLat,
                    centerLng = centerLng,
                    polygonJson = polygonJson,
                    rulesSummary = if (!beskrivels.isNullOrBlank()) beskrivels else "Fri teltning (1-2-3 reglen)",
                    source = "geofa",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePoisGeoJson(jsonStr: String): List<PoiEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: return emptyList()
        val result = mutableListOf<PoiEntity>()

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            val geom = f["geometry"] as? Map<String, Any> ?: continue
            val coordsRaw = geom["coordinates"] as? List<*> ?: continue
            val coords: List<Number>? = when {
                coordsRaw.isNotEmpty() && coordsRaw[0] is Number -> @Suppress("UNCHECKED_CAST") (coordsRaw as? List<Number>)
                coordsRaw.isNotEmpty() && coordsRaw[0] is List<*> -> {
                    val first = coordsRaw[0] as? List<*>
                    if (first != null && first.isNotEmpty() && first[0] is Number) {
                        @Suppress("UNCHECKED_CAST") (first as? List<Number>)
                    } else null
                }
                else -> null
            }
            if (coords == null || coords.size < 2) continue

            val lng = coords[0].toDouble()
            val lat = coords[1].toDouble()
            val objektId = props["objekt_id"] as? String ?: "geofa_poi_$idx"
            val typeK = (props["facil_ty_k"] as? Number)?.toInt() ?: 0
            val type = if (typeK == 1012) "toilet" else if (typeK == 1222) "water" else "poi"
            val navn = (props["navn"] as? String)?.trim()
            val doegnaab = props["doegnaab"] as? String
            val betaling = props["betaling"] as? String

            result.add(
                PoiEntity(
                    id = objektId,
                    type = type,
                    name = if (!navn.isNullOrBlank()) navn else if (type == "toilet") "Offentligt toilet" else "Drikkevandspost",
                    lat = lat,
                    lng = lng,
                    access = if (doegnaab == "Ja") "Døgnåbent" else null,
                    fee = betaling == "Ja",
                    source = "geofa",
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }
}
