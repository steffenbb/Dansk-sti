package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.example.util.network.applyDanskStiDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        .applyDanskStiDefaults()
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "geofa").apply { if (!exists()) mkdirs() }

    fun loadBundledShelters(): List<ShelterEntity> {
        val assetJson = readAssetGeoJsonGz("shelters.geojson.gz") ?: return emptyList()
        return try {
            parseSheltersGeoJson(assetJson)
        } catch (e: Exception) {
            Log.e("GeoFaRepo", "Failed to parse bundled shelters: ${e.message}", e)
            emptyList()
        }
    }

    fun loadBundledFriTeltning(): List<FriTeltningEntity> {
        val assetJson = readAssetGeoJsonGz("fri_teltning.geojson.gz") ?: return emptyList()
        return try {
            parseFriTeltningGeoJson(assetJson)
        } catch (e: Exception) {
            Log.e("GeoFaRepo", "Failed to parse bundled fri teltning: ${e.message}", e)
            emptyList()
        }
    }

    fun loadBundledPois(): List<PoiEntity> {
        val assetJson = readAssetGeoJsonGz("toilets_water.geojson.gz") ?: return emptyList()
        return try {
            parsePoisGeoJson(assetJson)
        } catch (e: Exception) {
            Log.e("GeoFaRepo", "Failed to parse bundled pois: ${e.message}", e)
            emptyList()
        }
    }

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

    internal fun readAssetGeoJsonGz(assetName: String): String? {
        val candidateAssetPaths = buildList {
            add("geofa/$assetName")
            if (assetName.endsWith(".gz")) {
                add("geofa/${assetName.removeSuffix(".gz")}")
            }
        }
        for (assetPath in candidateAssetPaths) {
            try {
                context.assets.open(assetPath).use { rawInput ->
                    val buffered = java.io.BufferedInputStream(rawInput)
                    buffered.mark(2)
                    val b0 = buffered.read()
                    val b1 = buffered.read()
                    buffered.reset()
                    val isGzip = (b0 == 0x1f && b1 == 0x8b)
                    val stream = if (isGzip) GZIPInputStream(buffered) else buffered
                    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            } catch (_: Exception) {
                // Try next candidate path
            }
        }
        Log.w("GeoFaRepo", "Could not read asset geofa/$assetName")
        return null
    }

    private suspend fun queryWithRetry(sql: String): String? = withContext(Dispatchers.IO) {
        val encodedSql = URLEncoder.encode(sql, "UTF-8")
        val url = "https://geofa.geodanmark.dk/api/v2/sql/fkg?q=$encodedSql&format=geojson&geoformat=geojson&srs=4326"

        for (attempt in 1..3) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", com.example.util.network.NetworkConstants.USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) return@withContext body
                    } else {
                        Log.w("GeoFaRepo", "GeoFA HTTP ${response.code} on attempt $attempt")
                    }
                }
            } catch (e: Exception) {
                Log.w("GeoFaRepo", "GeoFA error on attempt $attempt: ${e.message}")
            }
            if (attempt < 3) {
                delay((attempt * 1500).toLong())
            }
        }
        null
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseSheltersGeoJson(jsonStr: String): List<ShelterEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: return emptyList()
        val result = mutableListOf<ShelterEntity>()

        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val defaultShelterName = runCatching { localizedCtx.getString(com.example.R.string.geofa_default_shelter_name) }.getOrDefault("Shelter")
        val defaultType = runCatching { localizedCtx.getString(com.example.R.string.poi_type_shelter) }.getOrDefault("Shelter")

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            if (!GeoFaFieldParser.isActiveStatus(props["status"] as? String)) continue

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
            val cleanedDescription = GeoFaFieldParser.toPlainText(props["beskrivels"] as? String)
                ?: GeoFaFieldParser.toPlainText(props["lang_beskr"] as? String)
            val bookingLink = ((props["bookinglink"] as? String) ?: (props["booking_url"] as? String))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val plainInfoLink = ((props["link"] as? String) ?: (props["link1"] as? String))
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != bookingLink }
            val isBookJa = (props["book"] as? String)?.trim()?.equals("Ja", ignoreCase = true) == true
            val ownership = ((props["ansva_v"] as? String) ?: (props["ansvar_org"] as? String) ?: (props["cvr_navn"] as? String))?.trim()
            val antalPl = (props["antal_pl"] as? Number)?.toInt()

            val saeson = props["saeson"] as? String
            val saesonSt = props["saeson_st"] as? String
            val saesonSl = props["saeson_sl"] as? String
            val saesonBem = props["saeson_bem"] as? String

            result.add(
                ShelterEntity(
                    id = objektId,
                    name = if (!navn.isNullOrBlank()) navn else defaultShelterName,
                    lat = lat,
                    lng = lng,
                    description = cleanedDescription,
                    bookingRequired = isBookJa || bookingLink != null,
                    ownership = if (!ownership.isNullOrBlank()) ownership else null,
                    capacity = antalPl, // null if unknown, never default to 4
                    hasWaterNearby = false, // computed by 500m proximity or hasOwnTap
                    hasToiletNearby = false, // computed by 500m proximity or hasOwnToilet
                    source = "geofa",
                    bookingUrl = bookingLink,
                    infoUrl = plainInfoLink,
                    shelterType = props["facil_ty"] as? String ?: defaultType,
                    fee = GeoFaFieldParser.yesNo(props["betaling"] as? String),
                    accessibility = GeoFaFieldParser.accessibilityText(props["handicap"] as? String),
                    hasOwnTap = GeoFaFieldParser.yesNo(props["vandhane"] as? String),
                    seasonal = GeoFaFieldParser.seasonalFromGeoFa(saeson, saesonSt, saesonSl),
                    seasonInfo = GeoFaFieldParser.seasonDisplayText(saeson, saesonSt, saesonSl, saesonBem),
                    open24h = GeoFaFieldParser.yesNo(props["doegnaab"] as? String),
                    accessNote = GeoFaFieldParser.accessNote(props["tilgaeng_opl"] as? String),
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseFriTeltningGeoJson(jsonStr: String): List<FriTeltningEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: emptyList()
        val result = mutableListOf<FriTeltningEntity>()
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val defaultFriTeltningName = runCatching { localizedCtx.getString(com.example.R.string.geofa_default_fri_teltning_name) }.getOrDefault("Fri teltning")
        val defaultFriTeltningRules = runCatching { localizedCtx.getString(com.example.R.string.geofa_default_fri_teltning_rules) }.getOrDefault("Fri teltning (1-2-3)")

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            if (!GeoFaFieldParser.isActiveStatus(props["status"] as? String)) continue

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

            val cleanedRules = GeoFaFieldParser.toPlainText(props["beskrivels"] as? String)
                ?: GeoFaFieldParser.toPlainText(props["lang_beskr"] as? String)
            val cvrNavn = ((props["cvr_navn"] as? String) ?: (props["ansva_v"] as? String) ?: (props["ansvar_org"] as? String))?.trim()

            result.add(
                FriTeltningEntity(
                    id = objektId,
                    name = if (!navn.isNullOrBlank()) navn else defaultFriTeltningName,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    polygonJson = polygonJson,
                    rulesSummary = cleanedRules ?: defaultFriTeltningRules,
                    source = "geofa",
                    cvrNavn = if (!cvrNavn.isNullOrBlank()) cvrNavn else null,
                    fetchedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parsePoisGeoJson(jsonStr: String): List<PoiEntity> {
        val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
        val features = root["features"] as? List<Map<String, Any>> ?: return emptyList()
        val result = mutableListOf<PoiEntity>()
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val defaultToiletName = runCatching { localizedCtx.getString(com.example.R.string.geofa_default_toilet_name) }.getOrDefault("Toilet")
        val defaultWaterName = runCatching { localizedCtx.getString(com.example.R.string.geofa_default_water_name) }.getOrDefault("Water")
        val open24hLabel = runCatching { localizedCtx.getString(com.example.R.string.geofa_access_open_24h) }.getOrDefault("24/7")
        val toiletTapName = runCatching { localizedCtx.getString(com.example.R.string.geofa_toilet_tap_name) }.getOrDefault("Vandhane ved toilet")

        for ((idx, f) in features.withIndex()) {
            val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: emptyMap()
            if (!GeoFaFieldParser.isActiveStatus(props["status"] as? String)) continue

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
            val subtype = when (type) {
                "toilet" -> "geofa_toilet"
                "water" -> "geofa_water"
                else -> null
            }
            val navn = (props["navn"] as? String)?.trim()
            val doegnaab = props["doegnaab"] as? String
            val betaling = props["betaling"] as? String
            val saeson = props["saeson"] as? String
            val saesonSt = props["saeson_st"] as? String
            val saesonSl = props["saeson_sl"] as? String
            val seasonal = GeoFaFieldParser.seasonalFromGeoFa(saeson, saesonSt, saesonSl)
            val accessibility = GeoFaFieldParser.accessibilityText(props["handicap"] as? String)
            val accNote = GeoFaFieldParser.accessNote(props["tilgaeng_opl"] as? String)
            val detailsJson = GeoFaFieldParser.buildDetailsJson(accessibility = accessibility, accessNote = accNote)
            val now = System.currentTimeMillis()

            result.add(
                PoiEntity(
                    id = objektId,
                    type = type,
                    name = if (!navn.isNullOrBlank()) navn else if (type == "toilet") defaultToiletName else defaultWaterName,
                    lat = lat,
                    lng = lng,
                    access = if (doegnaab == "Ja") open24hLabel else null,
                    fee = betaling == "Ja",
                    seasonal = seasonal,
                    subtype = subtype,
                    detailsJson = detailsJson,
                    source = "geofa",
                    fetchedAt = now
                )
            )

            if (type == "toilet" && GeoFaFieldParser.yesNo(props["vandhane"] as? String) == true) {
                result.add(
                    PoiEntity(
                        id = "${objektId}_vandhane",
                        type = "water",
                        name = toiletTapName,
                        lat = lat,
                        lng = lng,
                        seasonal = seasonal,
                        subtype = "geofa_toilet_tap",
                        source = "geofa",
                        fetchedAt = now
                    )
                )
            }
        }
        return result
    }

    fun tapPoisFor(shelters: List<ShelterEntity>): List<PoiEntity> {
        val localizedCtx = com.example.util.AppLocaleHelper.getLocalizedContext(context)
        val result = mutableListOf<PoiEntity>()
        val now = System.currentTimeMillis()
        for (sh in shelters) {
            if (sh.hasOwnTap == true) {
                val tapName = runCatching {
                    localizedCtx.getString(com.example.R.string.geofa_shelter_tap_name_fmt, sh.name)
                }.getOrDefault("Vandhane ved ${sh.name}")
                result.add(
                    PoiEntity(
                        id = "${sh.id}_vandhane",
                        type = "water",
                        name = tapName,
                        lat = sh.lat,
                        lng = sh.lng,
                        seasonal = sh.seasonal,
                        subtype = "geofa_shelter_tap",
                        source = "geofa",
                        fetchedAt = sh.fetchedAt.takeIf { it > 0L } ?: now
                    )
                )
            }
        }
        return result
    }

    internal fun loadGeoFaDatasetJsonForBackfill(cacheFileName: String, assetFileName: String): String? {
        val cacheFile = File(cacheDir, cacheFileName)
        if (cacheFile.exists()) {
            runCatching {
                val text = cacheFile.readText()
                if (text.contains("\"features\"")) return text
            }
        }
        return readAssetGeoJsonGz(assetFileName)
    }
}

/**
 * Pure Kotlin parser for GeoFA fields (no Android types).
 */
object GeoFaFieldParser {
    private val INACTIVE_STATUS_KEYWORDS = listOf("kladde", "forslag", "aflyst", "ikke gældende")
    private val ENGLISH_MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DANISH_MONTHS = arrayOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
    private val DATE_MD_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun isActiveStatus(status: String?): Boolean {
        if (status.isNullOrBlank()) return true
        val lower = status.lowercase()
        return INACTIVE_STATUS_KEYWORDS.none { lower.contains(it) }
    }

    fun yesNo(v: String?): Boolean? {
        return when (v?.trim()?.lowercase()) {
            "ja" -> true
            "nej" -> false
            else -> null
        }
    }

    fun toPlainText(html: String?): String? {
        if (html.isNullOrBlank()) return null

        var s = html
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        // Turn <br>, </p>, </div>, </li>, </h1-6> into line breaks
        s = s.replace(Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<\s*/\s*(?:p|div|li|h[1-6])\s*>""", RegexOption.IGNORE_CASE), "\n")
        // Turn <li> into "• "
        s = s.replace(Regex("""<\s*li(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE), "• ")
        // Remove all other tags, including <img ...>, keeping link text
        s = s.replace(Regex("""<[^>]+>"""), "")

        // Decode named and numeric HTML entities
        s = decodeHtmlEntities(s)

        // Normalize non-breaking spaces to regular spaces
        s = s.replace('\u00A0', ' ')

        // Trim each line and collapse repeated horizontal spaces to one
        val lines = s.split('\n').map { line ->
            line.replace(Regex("""[ \t]+"""), " ").trim()
        }
        s = lines.joinToString("\n").trim()

        // Remove empty bullet-only lines if any and collapse 3+ line breaks to 2
        s = s.replace(Regex("""\n{3,}"""), "\n\n").trim()

        return s.takeIf { it.isNotEmpty() }
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&aelig;", "æ")
            .replace("&oslash;", "ø")
            .replace("&aring;", "å")
            .replace("&AElig;", "Æ")
            .replace("&Oslash;", "Ø")
            .replace("&Aring;", "Å")

        // Decode hex numeric entities &#x...;
        s = Regex("""&#x([0-9a-fA-F]+);""").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }

        // Decode decimal numeric entities &#...;
        s = Regex("""&#(\d+);""").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }

        // Decode &amp; last
        s = s.replace("&amp;", "&", ignoreCase = true)
        return s
    }

    private data class MonthDay(val month: Int, val day: Int)

    private fun parseMonthDay(dateStr: String?): MonthDay? {
        if (dateStr.isNullOrBlank()) return null
        val match = DATE_MD_REGEX.find(dateStr.trim()) ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return MonthDay(month, day)
    }

    fun seasonalFromGeoFa(saeson: String?, saesonStart: String?, saesonSlut: String?): String? {
        val s = saeson?.trim() ?: return null
        return when {
            s.equals("Helårsåbent", ignoreCase = true) -> "no"
            s.equals("Sæsonåbent", ignoreCase = true) -> {
                val startMd = parseMonthDay(saesonStart)
                val endMd = parseMonthDay(saesonSlut)
                if (startMd != null && endMd != null) {
                    val isFullYear = (startMd.month == 1 && startMd.day == 1 && endMd.month == 12 && endMd.day == 31)
                    if (!isFullYear) {
                        "${ENGLISH_MONTHS[startMd.month - 1]}-${ENGLISH_MONTHS[endMd.month - 1]}"
                    } else {
                        "yes"
                    }
                } else {
                    "yes"
                }
            }
            else -> null
        }
    }

    fun seasonDisplayText(saeson: String?, start: String?, slut: String?, bemaerkning: String?): String? {
        val s = saeson?.trim()
        val base: String? = when {
            s == null -> null
            s.equals("Helårsåbent", ignoreCase = true) -> "Åbent hele året"
            s.equals("Sæsonåbent", ignoreCase = true) -> {
                val startMd = parseMonthDay(start)
                val endMd = parseMonthDay(slut)
                if (startMd != null && endMd != null) {
                    val isFullYear = (startMd.month == 1 && startMd.day == 1 && endMd.month == 12 && endMd.day == 31)
                    if (!isFullYear) {
                        "Sæsonåbent ${startMd.day}. ${DANISH_MONTHS[startMd.month - 1]} – ${endMd.day}. ${DANISH_MONTHS[endMd.month - 1]}"
                    } else {
                        "Sæsonåbent"
                    }
                } else {
                    "Sæsonåbent"
                }
            }
            else -> null
        } ?: return null

        val cleanBem = toPlainText(bemaerkning)
        return if (!cleanBem.isNullOrBlank()) {
            "$base · $cleanBem"
        } else {
            base
        }
    }

    fun accessibilityText(handicap: String?): String? {
        return when (handicap?.trim()?.lowercase()) {
            "handicapegnet", "handicapvenligt" -> "Handicapegnet"
            "delvist handicapegnet" -> "Delvist handicapegnet"
            "ikke handicapegnet" -> "Ikke handicapegnet"
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun accessNote(tilgaengOplJson: String?): String? {
        if (tilgaengOplJson.isNullOrBlank()) return null
        val trimmed = tilgaengOplJson.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val map = moshi.adapter(Map::class.java).fromJson(trimmed) as? Map<String, Any> ?: return null
            val raw = (map["shelter_tilgaengelighed_beskrivelse"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map["toiletforhold_beskrivelse"] as? String)?.takeIf { it.isNotBlank() }
            toPlainText(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun buildDetailsJson(accessibility: String?, accessNote: String?): String? {
        val map = LinkedHashMap<String, String>()
        if (!accessibility.isNullOrBlank()) map["accessibility"] = accessibility
        if (!accessNote.isNullOrBlank()) map["accessNote"] = accessNote
        if (map.isEmpty()) return null
        return try {
            moshi.adapter(Map::class.java).toJson(map)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseDetailsJson(detailsJson: String?): Map<String, String> {
        if (detailsJson.isNullOrBlank()) return emptyMap()
        return try {
            val raw = moshi.adapter(Map::class.java).fromJson(detailsJson) as? Map<String, Any> ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((k, v) in raw) {
                val str = v as? String
                if (!str.isNullOrBlank()) out[k] = str
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun extractInactiveObjektIds(jsonStr: String): List<String> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val root = moshi.adapter(Map::class.java).fromJson(jsonStr) as? Map<String, Any> ?: return emptyList()
            val features = root["features"] as? List<Map<String, Any>> ?: return emptyList()
            val inactiveIds = mutableListOf<String>()
            for (f in features) {
                val props = f["props"] as? Map<String, Any> ?: f["properties"] as? Map<String, Any> ?: continue
                val status = props["status"] as? String
                if (!isActiveStatus(status)) {
                    val objektId = (props["objekt_id"] as? String)?.trim()
                    if (!objektId.isNullOrEmpty()) {
                        inactiveIds.add(objektId)
                    }
                }
            }
            inactiveIds
        } catch (_: Exception) {
            emptyList()
        }
    }
}

