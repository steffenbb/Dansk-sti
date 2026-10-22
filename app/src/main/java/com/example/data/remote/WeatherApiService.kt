package com.example.data.remote

import com.example.util.network.applyDebugMainThreadCheck
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class OpenMeteoResponse(
    @Json(name = "current") val current: CurrentWeatherDto? = null,
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
    val severeAlert: String? = null
)

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
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset",
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
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .applyDebugMainThreadCheck()
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
    fun parseWeather(response: OpenMeteoResponse): TrailWeatherInfo {
        val current = response.current
        val daily = response.daily

        val temp = current?.temperature
        val wind = current?.windSpeed
        val gusts = current?.windGusts
        val code = current?.weatherCode ?: 0

        val recentRain = (daily?.precipitationSum?.take(2)?.sum() ?: 0.0) + (current?.precipitation ?: 0.0)
        val isMuddy = recentRain > 5.0
        val isWindAlert = (wind ?: 0.0) >= 12.0 || (gusts ?: 0.0) >= 18.0

        val (desc, icon) = when (code) {
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

        return TrailWeatherInfo(
            tempC = temp,
            windSpeedMs = wind,
            windGustsMs = gusts,
            precipitationMm = current?.precipitation,
            conditionDescription = desc,
            weatherIcon = icon,
            isMuddyCondition = isMuddy,
            isHighWindAlert = isWindAlert,
            recentRainMm = recentRain,
            sunrise = sunriseStr,
            sunset = sunsetStr,
            twilightEnd = twilightStr,
            daylightDurationText = durationText,
            severeAlert = severeAlert
        )
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
            val durationStr = "${hours}t ${mins}m"
            durationStr
        } catch (e: Exception) {
            null
        }
    }
}
