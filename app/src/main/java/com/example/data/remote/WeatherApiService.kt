package com.example.data.remote

import com.example.util.GeoUtils
import com.example.util.network.applyDanskStiDefaults
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.round

@JsonClass(generateAdapter = true)
data class OpenMeteoResponse(
    @Json(name = "current") val current: CurrentWeatherDto? = null,
    @Json(name = "hourly") val hourly: HourlyWeatherDto? = null,
    @Json(name = "daily") val daily: DailyWeatherDto? = null
)

@JsonClass(generateAdapter = true)
data class CurrentWeatherDto(
    @Json(name = "temperature_2m") val temperature: Double? = null,
    @Json(name = "precipitation") val precipitation: Double? = null,
    @Json(name = "wind_speed_10m") val windSpeed: Double? = null,
    @Json(name = "wind_gusts_10m") val windGusts: Double? = null,
    @Json(name = "weather_code") val weatherCode: Int? = null
)

@JsonClass(generateAdapter = true)
data class HourlyWeatherDto(
    @Json(name = "time") val time: List<String>? = null,
    @Json(name = "temperature_2m") val temperature2m: List<Double?>? = null,
    @Json(name = "precipitation_probability") val precipitationProbability: List<Int?>? = null,
    @Json(name = "precipitation") val precipitation: List<Double?>? = null,
    @Json(name = "wind_speed_10m") val windSpeed10m: List<Double?>? = null,
    @Json(name = "wind_gusts_10m") val windGusts10m: List<Double?>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null,
    // Phase D6 (dmi_seamless may return null for any of these)
    @Json(name = "wind_direction_10m") val windDirection10m: List<Int?>? = null,
    @Json(name = "apparent_temperature") val apparentTemperature: List<Double?>? = null
)

@JsonClass(generateAdapter = true)
data class DailyWeatherDto(
    @Json(name = "time") val time: List<String>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null,
    @Json(name = "temperature_2m_max") val tempMax: List<Double?>? = null,
    @Json(name = "temperature_2m_min") val tempMin: List<Double?>? = null,
    @Json(name = "precipitation_sum") val precipitationSum: List<Double?>? = null,
    @Json(name = "wind_speed_10m_max") val windSpeedMax: List<Double?>? = null,
    @Json(name = "wind_gusts_10m_max") val windGustsMax: List<Double?>? = null,
    @Json(name = "sunrise") val sunrise: List<String>? = null,
    @Json(name = "sunset") val sunset: List<String>? = null,
    @Json(name = "civil_twilight_end") val civilTwilightEnd: List<String>? = null,
    // Phase D6 (nullable: dmi_seamless may not provide them)
    @Json(name = "wind_direction_10m_dominant") val windDirectionDominant: List<Int?>? = null,
    @Json(name = "apparent_temperature_min") val apparentTempMin: List<Double?>? = null,
    @Json(name = "uv_index_max") val uvIndexMax: List<Double?>? = null
)

/** Phase D6: one day of the daily forecast, so the planner can look up a date. Null = not provided. */
data class DailyPoint(
    val date: String,
    val dominantWindDeg: Int?,
    val windMaxMs: Double?,
    val apparentMinC: Double?,
    val uvMax: Double?,
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    val weatherCode: Int? = null,
    val precipitationSumMm: Double? = null
)

data class HourlyForecastPoint(
    val isoTime: String,
    val hourLabel: String,
    val tempC: Double,
    val precipitationProbabilityPct: Int?,
    val precipitationMm: Double,
    val windSpeedMs: Double,
    val windGustsMs: Double?,
    val weatherCode: Int,
    val weatherIcon: String,
    val conditionDescription: String,
    val windDirectionDeg: Int? = null,
    val apparentTempC: Double? = null
) {
    val timeLabel: String
        get() = hourLabel
}

data class TrailWeatherInfo(
    val tempC: Double? = null,
    val windSpeedMs: Double? = null,
    val windGustsMs: Double? = null,
    val precipitationMm: Double? = null,
    val conditionDescription: String = "Vejrdata indlæses...",
    val weatherIcon: String = "⛅",
    val isMuddyCondition: Boolean = false,
    val isHighWindAlert: Boolean = false,
    val recentRainMm: Double = 0.0,
    val sunrise: String? = null,
    val sunset: String? = null,
    val twilightEnd: String? = null,
    val daylightDurationText: String? = null,
    val severeAlert: String? = null,
    val hourly48h: List<HourlyForecastPoint> = emptyList(),
    val blocks24h: List<HourlyForecastPoint> = emptyList(),
    val next6Hours: List<HourlyForecastPoint> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    // Phase D6
    val windDirectionDeg: Int? = null,
    val apparentTempC: Double? = null,
    val uvIndexMaxToday: Double? = null,
    val thunderFromTime: String? = null,
    val daily: List<DailyPoint> = emptyList()
) {
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return (nowMillis - fetchedAtMillis) > WeatherInterpreter.STALE_THRESHOLD_MS
    }

    fun formatUpdatedClock(locale: Locale = Locale.getDefault()): String {
        val sdf = SimpleDateFormat("HH:mm", locale).apply {
            timeZone = TimeZone.getTimeZone("Europe/Copenhagen")
        }
        return sdf.format(Date(fetchedAtMillis))
    }
}

@JsonClass(generateAdapter = true)
data class OpenMeteoElevationResponse(
    @Json(name = "elevation") val elevation: List<Double?>? = null
)

interface WeatherApiService {
    @GET("v1/forecast")
    suspend fun getTrailWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,precipitation,wind_speed_10m,wind_gusts_10m,weather_code",
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,weather_code,wind_direction_10m,apparent_temperature",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset,wind_direction_10m_dominant,apparent_temperature_min,uv_index_max",
        @Query("forecast_hours") forecastHours: Int = 48,
        @Query("models") models: String? = "dmi_seamless",
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
        @Query("timezone") timezone: String = "Europe/Copenhagen"
    ): OpenMeteoResponse

    @GET("v1/elevation")
    suspend fun getElevations(
        @Query("latitude") latitudes: String,
        @Query("longitude") longitudes: String
    ): OpenMeteoElevationResponse

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/"

        fun create(): WeatherApiService {
            val client = OkHttpClient.Builder()
                .applyDanskStiDefaults()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(WeatherApiService::class.java)
        }
    }
}

object WeatherInterpreter {
    const val STALE_THRESHOLD_MS: Long = 6L * 3600_000L
    const val NAV_REFRESH_INTERVAL_MS: Long = 60L * 60_000L
    const val NAV_RETRY_INTERVAL_MS: Long = 10L * 60_000L
    const val NAV_REFRESH_DISTANCE_METERS: Double = 5_000.0
    const val RAIN_ALERT_THROTTLE_MS: Long = 3L * 3600_000L
    const val RAIN_ALERT_THRESHOLD_MM_PER_H: Double = 1.0
    const val CAMP_PREFETCH_MAX_AGE_MS: Long = 3L * 3600_000L

    private val COPENHAGEN_TZ: TimeZone = TimeZone.getTimeZone("Europe/Copenhagen")

    private fun truncateToCopenhagenHourStart(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance(COPENHAGEN_TZ).apply {
            timeInMillis = epochMs
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }

    fun roundCoord005(value: Double): Double {
        return round(value * 20.0) / 20.0
    }

    fun shouldRefreshNavigationWeather(
        lastFetchTimeMs: Long,
        lastFetchLat: Double?,
        lastFetchLng: Double?,
        nowMs: Long,
        currentLat: Double,
        currentLng: Double
    ): Boolean {
        if (lastFetchTimeMs <= 0L) return true
        if (nowMs - lastFetchTimeMs >= NAV_REFRESH_INTERVAL_MS) return true
        if (lastFetchLat == null || lastFetchLng == null || lastFetchLat.isNaN() || lastFetchLng.isNaN()) return true
        val movedMeters = GeoUtils.distanceMeters(lastFetchLat, lastFetchLng, currentLat, currentLng)
        return movedMeters >= NAV_REFRESH_DISTANCE_METERS
    }

    fun serializeResponseToJson(response: OpenMeteoResponse): String {
        return moshi.adapter(OpenMeteoResponse::class.java).toJson(response)
    }

    fun deserializeResponseFromJson(json: String): OpenMeteoResponse? {
        if (json.isBlank()) return null
        return runCatching {
            moshi.adapter(OpenMeteoResponse::class.java).fromJson(json)
        }.getOrNull()
    }

    fun describeWeatherCode(code: Int, context: android.content.Context? = null): Pair<String, String> {
        val ctx = context ?: runCatching { com.example.DanskStiApp.instance }.getOrNull()
        val localizedCtx = ctx?.let { com.example.util.AppLocaleHelper.createLocalizedContext(it) }
        val icon = when (code) {
            0 -> "☀️"
            1, 2 -> "🌤️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            51, 53, 55 -> "🌦️"
            61, 63, 65 -> "🌧️"
            71, 73, 75 -> "❄️"
            80, 81, 82 -> "🌧️"
            95, 96, 99 -> "⛈️"
            else -> "⛅"
        }
        if (localizedCtx != null) {
            val resId = when (code) {
                0 -> com.example.R.string.weather_code_clear
                1, 2 -> com.example.R.string.weather_code_partly_cloudy
                3 -> com.example.R.string.weather_code_overcast
                45, 48 -> com.example.R.string.weather_code_fog
                51, 53, 55 -> com.example.R.string.weather_code_drizzle
                61, 63, 65 -> com.example.R.string.weather_code_rain
                71, 73, 75 -> com.example.R.string.weather_code_snow
                80, 81, 82 -> com.example.R.string.weather_code_rain_showers
                95, 96, 99 -> com.example.R.string.weather_code_thunderstorm
                else -> com.example.R.string.weather_code_changeable
            }
            return localizedCtx.getString(resId) to icon
        }
        val fallbackDesc = when (code) {
            0 -> "Klart vejr"
            1, 2 -> "Let skyet"
            3 -> "Overskyet"
            45, 48 -> "Tåge"
            51, 53, 55 -> "Støvregn"
            61, 63, 65 -> "Regn"
            71, 73, 75 -> "Sne"
            80, 81, 82 -> "Regnbyger"
            95, 96, 99 -> "Tordenvejr"
            else -> "Skiftende skydække"
        }
        return fallbackDesc to icon
    }

    internal fun parseIsoToEpochMillis(isoTime: String): Long? {
        val trimmed = isoTime.trim()
        if (trimmed.isEmpty()) return null
        val hasExplicitOffset = trimmed.endsWith("Z", ignoreCase = true) ||
            Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(trimmed)
        if (hasExplicitOffset) {
            val normalized = if (trimmed.endsWith("Z", ignoreCase = true)) {
                trimmed.dropLast(1) + "+00:00"
            } else {
                trimmed
            }
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX")) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                    }.parse(normalized)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        if (trimmed.length >= 19) {
            val parsedSec = runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = COPENHAGEN_TZ
                    isLenient = false
                }.parse(trimmed.take(19))?.time
            }.getOrNull()
            if (parsedSec != null) return parsedSec
        }
        if (trimmed.length >= 16) {
            val parsedMin = runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
                    timeZone = COPENHAGEN_TZ
                    isLenient = false
                }.parse(trimmed.take(16))?.time
            }.getOrNull()
            if (parsedMin != null) return parsedMin
        }
        return null
    }

    fun parseWeather(
        response: OpenMeteoResponse,
        fetchedAtMillis: Long = System.currentTimeMillis(),
        nowMillis: Long = System.currentTimeMillis(),
        context: android.content.Context? = null
    ): TrailWeatherInfo {
        val current = response.current
        val daily = response.daily
        val hourlyDto = response.hourly

        val currentHourStartMs = truncateToCopenhagenHourStart(nowMillis)

        val rawHourlyPoints = parseHourlyPoints(hourlyDto, context)
        val hasParseableTimestamps = rawHourlyPoints.any { parseIsoToEpochMillis(it.isoTime) != null }
        val hourlyPoints = if (hasParseableTimestamps) {
            rawHourlyPoints.filter { pt ->
                val ptMs = parseIsoToEpochMillis(pt.isoTime)
                ptMs == null || ptMs >= currentHourStartMs
            }
        } else {
            rawHourlyPoints
        }
        val firstHourly = hourlyPoints.firstOrNull()

        val temp = current?.temperature ?: firstHourly?.tempC
        val wind = current?.windSpeed ?: firstHourly?.windSpeedMs
        val gusts = current?.windGusts ?: firstHourly?.windGustsMs
        val code = current?.weatherCode ?: firstHourly?.weatherCode ?: 0
        val precip = current?.precipitation ?: firstHourly?.precipitationMm

        val recentRain = (daily?.precipitationSum?.take(2)?.sumOf { it ?: 0.0 } ?: 0.0) + (precip ?: 0.0)
        val isMuddy = recentRain > 5.0
        val isWindAlert = (wind ?: 0.0) >= 12.0 || (gusts ?: 0.0) >= 18.0

        val (desc, icon) = describeWeatherCode(code, context)

        // Extract real astronomical sunrise and sunset from Open-Meteo
        val rawSunrise = daily?.sunrise?.firstOrNull()
        val rawSunset = daily?.sunset?.firstOrNull()
        val rawTwilight = daily?.civilTwilightEnd?.firstOrNull()

        val sunriseStr = rawSunrise?.substringAfter("T", "")?.take(5)?.takeIf { it.length == 5 }
        val sunsetStr = rawSunset?.substringAfter("T", "")?.take(5)?.takeIf { it.length == 5 }
        // Show civil twilight ONLY if Open-Meteo provides it; never invent sunset + 35 min
        val twilightStr = rawTwilight?.substringAfter("T", "")?.take(5)?.takeIf { it.length == 5 }

        // Calculate daylight duration; on parse failure return null
        val durationText = if (sunriseStr != null && sunsetStr != null) {
            calculateSunMetrics(sunriseStr, sunsetStr)
        } else {
            null
        }

        val ctx = context ?: runCatching { com.example.DanskStiApp.instance }.getOrNull()
        val localizedCtx = ctx?.let { com.example.util.AppLocaleHelper.createLocalizedContext(it) }
        // Phase D6: thunder in the next 12 hourly points (codes 95, 96, 99)
        val thunderFrom = findThunderFromTime(hourlyPoints)
        val severeAlert = when {
            code in listOf(95, 96, 99) -> localizedCtx?.getString(com.example.R.string.weather_alert_thunderstorm)
                ?: "Varsel om tordenvejr — undgå åbne højdedrag og enkeltstående træer."
            thunderFrom != null -> localizedCtx?.getString(com.example.R.string.weather_alert_thunder_from_fmt, thunderFrom)
                ?: "Risiko for torden fra kl. $thunderFrom – undgå åbne højdedrag og enkeltstående træer"
            isWindAlert -> localizedCtx?.getString(com.example.R.string.weather_alert_high_wind)
                ?: "Kraftig vind/vindstød varslet — pas på nedfaldende grene i skovområder."
            else -> null
        }

        val blocks24h = hourlyPoints.take(24).filterIndexed { index, _ -> index % 3 == 0 }.take(8)
        val next6h = hourlyPoints.take(6)

        return TrailWeatherInfo(
            tempC = temp,
            windSpeedMs = wind,
            windGustsMs = gusts,
            precipitationMm = precip,
            conditionDescription = desc,
            weatherIcon = icon,
            isMuddyCondition = isMuddy,
            isHighWindAlert = isWindAlert,
            recentRainMm = recentRain,
            sunrise = sunriseStr,
            sunset = sunsetStr,
            twilightEnd = twilightStr,
            daylightDurationText = durationText,
            severeAlert = severeAlert,
            hourly48h = hourlyPoints,
            blocks24h = blocks24h,
            next6Hours = next6h,
            fetchedAtMillis = fetchedAtMillis,
            windDirectionDeg = firstHourly?.windDirectionDeg,
            apparentTempC = firstHourly?.apparentTempC,
            uvIndexMaxToday = daily?.uvIndexMax?.firstOrNull(),
            thunderFromTime = thunderFrom,
            daily = parseDailyPoints(daily)
        )
    }

    /** Phase D7: the forecast for one moment – from the hourly point covering it, else that date's daily values. */
    data class ArrivalForecast(
        val tempC: Double?,
        val precipMm: Double?,
        val windMs: Double?,
        val windDirectionDeg: Int?,
        val apparentTempC: Double?,
        val weatherCode: Int?,
        val fromHourly: Boolean
    )

    /** Null when neither an hourly point nor a daily value exists for [atEpochMs]. */
    fun forecastAt(info: TrailWeatherInfo, atEpochMs: Long): ArrivalForecast? {
        val hour = info.hourly48h.firstOrNull { pt ->
            val start = parseIsoToEpochMillis(pt.isoTime) ?: return@firstOrNull false
            atEpochMs >= start && atEpochMs < start + 3_600_000L
        }
        if (hour != null) {
            return ArrivalForecast(hour.tempC, hour.precipitationMm, hour.windSpeedMs, hour.windDirectionDeg, hour.apparentTempC, hour.weatherCode, true)
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = COPENHAGEN_TZ }.format(Date(atEpochMs))
        val day = info.daily.firstOrNull { it.date == dateStr } ?: return null
        if (day.tempMaxC == null && day.weatherCode == null) return null
        return ArrivalForecast(day.tempMaxC, day.precipitationSumMm, day.windMaxMs, day.dominantWindDeg, null, day.weatherCode, false)
    }

    /** Phase D6: the first of the next 12 hourly points with a thunder code, as "HH:mm". */
    fun findThunderFromTime(hourlyPoints: List<HourlyForecastPoint>): String? =
        hourlyPoints.take(12).firstOrNull { it.weatherCode in setOf(95, 96, 99) }?.hourLabel

    private fun parseDailyPoints(daily: DailyWeatherDto?): List<DailyPoint> {
        val dates = daily?.time ?: return emptyList()
        return dates.mapIndexed { i, date ->
            DailyPoint(
                date = date,
                dominantWindDeg = daily.windDirectionDominant?.getOrNull(i),
                windMaxMs = daily.windSpeedMax?.getOrNull(i),
                apparentMinC = daily.apparentTempMin?.getOrNull(i),
                uvMax = daily.uvIndexMax?.getOrNull(i),
                tempMinC = daily.tempMin?.getOrNull(i),
                tempMaxC = daily.tempMax?.getOrNull(i),
                weatherCode = daily.weatherCode?.getOrNull(i),
                precipitationSumMm = daily.precipitationSum?.getOrNull(i)
            )
        }
    }

    private fun parseHourlyPoints(
        hourly: HourlyWeatherDto?,
        context: android.content.Context? = null
    ): List<HourlyForecastPoint> {
        val times = hourly?.time ?: return emptyList()
        val result = mutableListOf<HourlyForecastPoint>()
        val limit = minOf(times.size, 48)
        for (i in 0 until limit) {
            val iso = times[i]
            val hourLabel = iso.substringAfter("T", iso).take(5)
            val temp = hourly.temperature2m?.getOrNull(i) ?: continue
            val prob = hourly.precipitationProbability?.getOrNull(i)
            val precip = hourly.precipitation?.getOrNull(i) ?: 0.0
            val wind = hourly.windSpeed10m?.getOrNull(i) ?: 0.0
            val gusts = hourly.windGusts10m?.getOrNull(i)
            val code = hourly.weatherCode?.getOrNull(i) ?: 0
            val (desc, icon) = describeWeatherCode(code, context)
            result.add(
                HourlyForecastPoint(
                    isoTime = iso,
                    hourLabel = hourLabel,
                    tempC = temp,
                    precipitationProbabilityPct = prob,
                    precipitationMm = precip,
                    windSpeedMs = wind,
                    windGustsMs = gusts,
                    weatherCode = code,
                    weatherIcon = icon,
                    conditionDescription = desc,
                    windDirectionDeg = hourly.windDirection10m?.getOrNull(i),
                    apparentTempC = hourly.apparentTemperature?.getOrNull(i)
                )
            )
        }
        return result
    }

    /**
     * Finds the first hourly forecast point in the next 60 minutes (the hourly point covering [nowMs],
     * `start <= nowMs < start + 1h`, plus the next point ONLY if it starts within 60 minutes of [nowMs],
     * `nowMs < start <= nowMs + 60m`) with >= 1.0 mm/h precipitation.
     */
    fun findRainAlertInNext60Minutes(
        hourlyPoints: List<HourlyForecastPoint>,
        nowMs: Long = System.currentTimeMillis()
    ): HourlyForecastPoint? {
        if (hourlyPoints.isEmpty()) return null
        val currentHourStartMs = truncateToCopenhagenHourStart(nowMs)
        val windowEndMs = nowMs + 3_600_000L

        for ((idx, pt) in hourlyPoints.withIndex()) {
            val startMs = parseIsoToEpochMillis(pt.isoTime) ?: (currentHourStartMs + idx * 3_600_000L)
            val endMs = startMs + 3_600_000L
            val coversNow = startMs <= nowMs && nowMs < endMs
            val startsWithin60Min = startMs > nowMs && startMs <= windowEndMs
            if ((coversNow || startsWithin60Min) && pt.precipitationMm >= RAIN_ALERT_THRESHOLD_MM_PER_H) {
                return pt
            }
        }
        return null
    }

    fun findRainAlertInNext2Hours(
        hourlyPoints: List<HourlyForecastPoint>,
        nowMs: Long = System.currentTimeMillis()
    ): HourlyForecastPoint? {
        return findRainAlertInNext60Minutes(hourlyPoints, nowMs)
    }

    /**
     * Checks if a rain alert notification should be posted (enabled, >= 1 mm/h in next 60 min, and at most once per 3 hours).
     */
    fun shouldPostRainAlert(
        rainAlertEnabled: Boolean,
        lastAlertAtMs: Long,
        nowMs: Long,
        hourlyPoints: List<HourlyForecastPoint>
    ): HourlyForecastPoint? {
        if (!rainAlertEnabled) return null
        if (lastAlertAtMs > 0L && (nowMs - lastAlertAtMs) < RAIN_ALERT_THROTTLE_MS) return null
        return findRainAlertInNext60Minutes(hourlyPoints, nowMs)
    }

    fun shouldPostRainAlert(
        weather: TrailWeatherInfo,
        lastRainAlertMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): HourlyForecastPoint? {
        return shouldPostRainAlert(
            rainAlertEnabled = true,
            lastAlertAtMs = lastRainAlertMs,
            nowMs = nowMs,
            hourlyPoints = weather.hourly48h.ifEmpty { weather.next6Hours }
        )
    }

    fun shouldRefreshNavigationWeather(
        lastFetchMs: Long,
        lastLat: Double?,
        lastLng: Double?,
        currentLat: Double,
        currentLng: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return shouldRefreshNavigationWeather(
            lastFetchTimeMs = lastFetchMs,
            lastFetchLat = lastLat,
            lastFetchLng = lastLng,
            nowMs = nowMs,
            currentLat = currentLat,
            currentLng = currentLng
        )
    }

    /**
     * Finds the afternoon (around 14:00 or 15:00) forecast for a given day offset (0 = today, 1 = tomorrow)
     * if within the 48-hour forecast window.
     */
    fun findAfternoonForecastForDayOffset(
        weather: TrailWeatherInfo?,
        dayOffsetFromToday: Int
    ): HourlyForecastPoint? {
        if (weather == null || dayOffsetFromToday !in 0..1) return null
        val sliceStart = (dayOffsetFromToday * 24).coerceAtMost(weather.hourly48h.size)
        val sliceEnd = ((dayOffsetFromToday + 1) * 24).coerceAtMost(weather.hourly48h.size)
        if (sliceStart >= sliceEnd) return null
        val dayPoints = weather.hourly48h.subList(sliceStart, sliceEnd)
        return dayPoints.firstOrNull { it.hourLabel.startsWith("14:") || it.hourLabel.startsWith("15:") }
            ?: dayPoints.getOrNull(dayPoints.size / 2)
    }

    fun findAfternoonForecastForDate(
        weather: TrailWeatherInfo?,
        targetDateStr: String
    ): HourlyForecastPoint? {
        if (weather == null || targetDateStr.isBlank()) return null
        val dayPoints = weather.hourly48h.filter { it.isoTime.startsWith(targetDateStr) }
        if (dayPoints.isEmpty()) return null
        return dayPoints.firstOrNull { it.hourLabel.startsWith("14:") || it.hourLabel.startsWith("15:") }
            ?: dayPoints.getOrNull(dayPoints.size / 2)
    }

    /**
     * Computes daylight duration from sunrise and sunset strings ("HH:mm").
     * On parse failure returns null — never returns a hardcoded fallback like "19:25" or "12t 30m".
     */
    fun calculateSunMetrics(sunrise: String, sunset: String): String? {
        return try {
            val riseParts = sunrise.split(":")
            val setParts = sunset.split(":")
            if (riseParts.size < 2 || setParts.size < 2) return null

            val riseH = riseParts[0].toIntOrNull() ?: return null
            val riseM = riseParts[1].toIntOrNull() ?: return null
            val setH = setParts[0].toIntOrNull() ?: return null
            val setM = setParts[1].toIntOrNull() ?: return null

            val riseMinutes = riseH * 60 + riseM
            val setMinutes = setH * 60 + setM
            val diff = (setMinutes - riseMinutes).coerceAtLeast(0)
            if (diff == 0) return null

            val hours = diff / 60
            val mins = diff % 60
            "${hours}t ${mins}m"
        } catch (e: Exception) {
            null
        }
    }
}

