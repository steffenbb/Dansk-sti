package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.util.network.applyDanskStiDefaults
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Phase E1: a DMI tide-water station. [lowAstronomicalTide] is DMI's reference level for the station. */
data class TideStation(
    val stationId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val lowAstronomicalTide: Double? = null
)

/** One predicted high or low water. [levelCm] is always in centimetres. */
data class TideEvent(val timeMs: Long, val isHigh: Boolean, val levelCm: Double)

data class TideStationCache(val fetchedAt: Long, val stations: List<TideStation>)
data class TideDayCache(val fetchedAt: Long, val events: List<TideEvent>)

/**
 * Phase E1: DMI open data (oceanObs) tide stations and high/low-water predictions.
 * No API key; fair use is 500 requests per 5 s. Every failure returns empty – tide times are never invented.
 */
class TideRepository(private val context: Context) {

    companion object {
        const val BASE_URL = "https://opendataapi.dmi.dk/v2/oceanObs/collections"
        const val STATIONS_MAX_AGE_MS = 30L * 24L * 3600L * 1000L
        const val DAY_MAX_AGE_MS = 24L * 3600L * 1000L
        private val COPENHAGEN: TimeZone = TimeZone.getTimeZone("Europe/Copenhagen")
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        @Suppress("UNCHECKED_CAST")
        private fun features(json: String): List<Map<String, Any>>? = runCatching {
            (moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any>)?.get("features") as? List<Map<String, Any>>
        }.getOrNull()

        /** Parses a `tidewaterstation` FeatureCollection (properties `stationId`, `name`; Point geometry lon,lat). */
        @Suppress("UNCHECKED_CAST")
        fun parseStations(json: String): List<TideStation> = features(json).orEmpty().mapNotNull { f ->
            val props = f["properties"] as? Map<String, Any> ?: return@mapNotNull null
            val id = props["stationId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (props["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coords = (f["geometry"] as? Map<String, Any>)?.get("coordinates") as? List<Number> ?: return@mapNotNull null
            if (coords.size < 2) return@mapNotNull null
            TideStation(id, name, lat = coords[1].toDouble(), lng = coords[0].toDouble(),
                lowAstronomicalTide = (props["lowAstronomicalTide"] as? Number)?.toDouble())
        }

        /**
         * Unit choice: DMI's samples state no unit. Following the Phase E spec, values that are mostly
         * above 10 in absolute size are centimetres. Deviation: inner-water stations (e.g. Aabenraa,
         * +6/-13) have tiny tides whose values are mostly below 10; reading them as metres would invent
         * a 20 m tide. So a station whose `lowAstronomicalTide` exceeds 3 in size (impossible in metres
         * in Danish waters, normal in cm) is also treated as cm. Only when both tests fail are the values
         * read as metres. The UI always shows centimetres.
         */
        fun valuesAreCentimetres(values: List<Double>, stationLowAstronomicalTide: Double?): Boolean {
            if (values.isEmpty()) return true
            val mostlyAbove10 = values.count { kotlin.math.abs(it) > 10.0 } * 2 > values.size
            val latLooksLikeCm = stationLowAstronomicalTide != null && kotlin.math.abs(stationLowAstronomicalTide) > 3.0
            return mostlyAbove10 || latLooksLikeCm
        }

        private fun parseUtc(iso: String): Long? = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm'Z'")
            .firstNotNullOfOrNull { p -> runCatching { SimpleDateFormat(p, Locale.US).apply { timeZone = UTC; isLenient = false }.parse(iso)?.time }.getOrNull() }

        /** Parses a `tidewater` min/max FeatureCollection (`predictionType`, `predictionTime`, `value`), sorted by time. */
        @Suppress("UNCHECKED_CAST")
        fun parsePredictions(json: String, stationLowAstronomicalTide: Double?): List<TideEvent> {
            val raw = features(json).orEmpty().mapNotNull { f ->
                val p = f["properties"] as? Map<String, Any> ?: return@mapNotNull null
                val isHigh = when (p["predictionType"] as? String) { "maximum" -> true; "minimum" -> false; else -> return@mapNotNull null }
                val t = (p["predictionTime"] as? String)?.let { parseUtc(it) } ?: return@mapNotNull null
                val v = (p["value"] as? Number)?.toDouble() ?: return@mapNotNull null
                Triple(t, isHigh, v)
            }
            val factor = if (valuesAreCentimetres(raw.map { it.third }, stationLowAstronomicalTide)) 1.0 else 100.0
            return raw.map { (t, h, v) -> TideEvent(t, h, v * factor) }.sortedBy { it.timeMs }
        }

        /** [start, end) of the Copenhagen calendar day containing [epochMs], as epoch ms. */
        fun copenhagenDayBounds(epochMs: Long): Pair<Long, Long> {
            val cal = Calendar.getInstance(COPENHAGEN).apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return start to cal.timeInMillis
        }

        fun dayKey(epochMs: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = COPENHAGEN }.format(Date(epochMs))
        private fun isoZ(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = UTC }.format(Date(ms))
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().applyDanskStiDefaults().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    /** Test hook: replaces the HTTP GET (returns the body, or throws). */
    @androidx.annotation.VisibleForTesting
    internal var customFetcher: ((url: String) -> String)? = null

    private val dir: File get() = File(context.filesDir, "tides").apply { mkdirs() }
    // Lazy: a JSON adapter problem must never stop the repository (and the app) from starting
    private val stationsAdapter by lazy { moshi.adapter(TideStationCache::class.java) }
    private val dayAdapter by lazy { moshi.adapter(TideDayCache::class.java) }

    private fun get(url: String): String {
        customFetcher?.let { return it(url) }
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("DMI HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("DMI empty body")
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** All stations; cached 30 days in `filesDir/tides/stations.json`. Empty on failure without cache. */
    suspend fun getStations(nowMs: Long = System.currentTimeMillis(), allowNetwork: Boolean = true): List<TideStation> = withContext(Dispatchers.IO) {
        val file = File(dir, "stations.json")
        val cached = runCatching { file.takeIf { it.exists() }?.readText()?.let { stationsAdapter.fromJson(it) } }.getOrNull()
        if (cached != null && (nowMs - cached.fetchedAt < STATIONS_MAX_AGE_MS || !allowNetwork)) return@withContext cached.stations
        if (!allowNetwork) return@withContext emptyList()
        try {
            val stations = parseStations(get("$BASE_URL/tidewaterstation/items?limit=1000"))
            if (stations.isEmpty()) return@withContext cached?.stations.orEmpty()
            writeAtomically(file, stationsAdapter.toJson(TideStationCache(nowMs, stations)))
            stations
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TideRepo", "DMI station list failed: ${e.message}")
            cached?.stations.orEmpty()
        }
    }

    /**
     * High/low waters on the Copenhagen day containing [dayEpochMs]; cached 24 h per station and day in
     * `filesDir/tides/<stationId>_<yyyyMMdd>.json`. Empty on failure without cache.
     */
    suspend fun getPredictions(
        station: TideStation,
        dayEpochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        allowNetwork: Boolean = true
    ): List<TideEvent> = withContext(Dispatchers.IO) {
        val file = File(dir, "${station.stationId}_${dayKey(dayEpochMs)}.json")
        val cached = runCatching { file.takeIf { it.exists() }?.readText()?.let { dayAdapter.fromJson(it) } }.getOrNull()
        if (cached != null && (nowMs - cached.fetchedAt < DAY_MAX_AGE_MS || !allowNetwork)) return@withContext cached.events
        if (!allowNetwork) return@withContext emptyList()
        val (start, end) = copenhagenDayBounds(dayEpochMs)
        try {
            val url = "$BASE_URL/tidewater/items?stationId=${station.stationId}&predictionType=minimum_maximum" +
                "&datetime=${isoZ(start)}/${isoZ(end)}&limit=200"
            val events = parsePredictions(get(url), station.lowAstronomicalTide).filter { it.timeMs in start until end }
            writeAtomically(file, dayAdapter.toJson(TideDayCache(nowMs, events)))
            events
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TideRepo", "DMI predictions failed for ${station.stationId}: ${e.message}")
            cached?.events.orEmpty()
        }
    }
}
