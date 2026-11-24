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
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null
)

@JsonClass(generateAdapter = true)
data class DailyWeatherDto(
    @Json(name = "time") val time: List<String>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int>? = null,
    @Json(name = "temperature_2m_max") val tempMax: List<Double>? = null,
    @Json(name = "temperature_2m_min") val tempMin: List<Double>? = null,
    @Json(name = "precipitation_sum") val precipitationSum: List<Double>? = null,
    @Json(name = "wind_speed_10m_max") val windSpeedMax: List<Double>? = null,
    @Json(name = "wind_gusts_10m_max") val windGustsMax: List<Double>? = null,
    @Json(name = "sunrise") val sunrise: List<String>? = null,
    @Json(name = "sunset") val sunset: List<String>? = null,
    @Json(name = "civil_twilight_end") val civilTwilightEnd: List<String>? = null
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
    val conditionDescription: String
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
    val fetchedAtMillis: Long = System.currentTimeMillis()
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
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,weather_code",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset",
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
    const val NAV_REFRESH_DISTANCE_METERS: Double = 5_000.0
    const val RAIN_ALERT_THROTTLE_MS: Long = 3L * 3600_000L
    const val RAIN_ALERT_THRESHOLD_MM_PER_H: Double = 1.0

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
        if (lastFetchLat == null || lastFetchLng == null) return true
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

    fun describeWeatherCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> "Klart vejr" to "☀️"
            1, 2 -> "Let skyet" to "🌤️"
            3 -> "Overskyet" to "☁️"
            45, 48 -> "Tåge" to "🌫️"
            51, 53, 55 -> "Støvregn" to "🌦️"
            61, 63, 65 -> "Regn" to "🌧️"
            71, 73, 75 -> "Sne" to "❄️"
            80, 81, 82 -> "Regnbyger" to "🌧️"
            95, 96, 99 -> "Tordenvejr" to "⛈️"
            else -> "Skiftende skydække" to "⛅"
        }
    }

    fun parseWeather(
        response: OpenMeteoResponse,
        fetchedAtMillis: Long = System.currentTimeMillis()
    ): TrailWeatherInfo {
        val current = response.current
        val daily = response.daily
        val hourlyDto = response.hourly

        val hourlyPoints = parseHourlyPoints(hourlyDto)
        val firstHourly = hourlyPoints.firstOrNull()

        val temp = current?.temperature ?: firstHourly?.tempC
        val wind = current?.windSpeed ?: firstHourly?.windSpeedMs
        val gusts = current?.windGusts ?: firstHourly?.windGustsMs
        val code = current?.weatherCode ?: firstHourly?.weatherCode ?: 0
        val precip = current?.precipitation ?: firstHourly?.precipitationMm

        val recentRain = (daily?.precipitationSum?.take(2)?.sum() ?: 0.0) + (precip ?: 0.0)
        val isMuddy = recentRain > 5.0
        val isWindAlert = (wind ?: 0.0) >= 12.0 || (gusts ?: 0.0) >= 18.0

        val (desc, icon) = describeWeatherCode(code)

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

        val severeAlert = when {
            code in listOf(95, 96, 99) -> "Varsel om tordenvejr — undgå åbne højdedrag og enkeltstående træer."
            isWindAlert -> "Kraftig vind/vindstød varslet — pas på nedfaldende grene i skovområder."
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
            fetchedAtMillis = fetchedAtMillis
        )
    }

    private fun parseHourlyPoints(hourly: HourlyWeatherDto?): List<HourlyForecastPoint> {
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
            val (desc, icon) = describeWeatherCode(code)
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
                    conditionDescription = desc
                )
            )
        }
        return result
    }

    /**
     * Finds the first hourly forecast point within the next 2 hours with >= 1.0 mm/h precipitation.
     */
    fun findRainAlertInNext2Hours(hourlyPoints: List<HourlyForecastPoint>): HourlyForecastPoint? {
        return hourlyPoints.take(2).firstOrNull { it.precipitationMm >= RAIN_ALERT_THRESHOLD_MM_PER_H }
    }

    /**
     * Checks if a rain alert notification should be posted (enabled, >= 1 mm/h in next 2h, and at most once per 3 hours).
     */
    fun shouldPostRainAlert(
        rainAlertEnabled: Boolean,
        lastAlertAtMs: Long,
        nowMs: Long,
        hourlyPoints: List<HourlyForecastPoint>
    ): HourlyForecastPoint? {
        if (!rainAlertEnabled) return null
        if (lastAlertAtMs > 0L && (nowMs - lastAlertAtMs) < RAIN_ALERT_THROTTLE_MS) return null
        return findRainAlertInNext2Hours(hourlyPoints)
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
            hourlyPoints = weather.hourly48h
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

