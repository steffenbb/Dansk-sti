package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class OpenMeteoResponse(
    val current: CurrentWeatherDto? = null,
    val daily: DailyWeatherDto? = null
)

@JsonClass(generateAdapter = true)
data class CurrentWeatherDto(
    @Json(name = "temperature_2m") val temperature: Double? = null,
    @Json(name = "relative_humidity_2m") val humidity: Int? = null,
    val precipitation: Double? = null,
    @Json(name = "weather_code") val weatherCode: Int? = null,
    @Json(name = "wind_speed_10m") val windSpeedKmh: Double? = null,
    @Json(name = "wind_gusts_10m") val windGustsKmh: Double? = null
)

@JsonClass(generateAdapter = true)
data class DailyWeatherDto(
    val time: List<String>? = null,
    @Json(name = "weather_code") val weatherCodes: List<Int>? = null,
    @Json(name = "temperature_2m_max") val maxTemps: List<Double>? = null,
    @Json(name = "temperature_2m_min") val minTemps: List<Double>? = null,
    @Json(name = "precipitation_sum") val precipSums: List<Double>? = null,
    @Json(name = "wind_speed_10m_max") val maxWindSpeeds: List<Double>? = null,
    @Json(name = "wind_gusts_10m_max") val maxWindGusts: List<Double>? = null,
    val sunrise: List<String>? = null,
    val sunset: List<String>? = null
)

data class TrailWeatherInfo(
    val tempC: Double? = null,
    val windSpeedMs: Double? = null,
    val windGustsMs: Double? = null,
    val precipitationMm: Double? = null,
    val conditionDescription: String? = null,
    val weatherIcon: String? = null,
    val severeAlert: String? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
    val twilightEnd: String? = null,
    val daylightDurationText: String? = null
)

interface WeatherApiService {

    @GET("v1/forecast")
    suspend fun getTrailWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset",
        @Query("timezone") timezone: String = "Europe/Copenhagen"
    ): OpenMeteoResponse

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/"

        fun create(): WeatherApiService {
            val client = OkHttpClient.Builder()
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
        val curr = response.current
        val daily = response.daily

        val temp = curr?.temperature
        val windMs = curr?.windSpeedKmh?.let { it / 3.6 }
        val gustsMs = curr?.windGustsKmh?.let { it / 3.6 }
        val precip = curr?.precipitation
        val code = curr?.weatherCode

        val (description, icon) = if (code != null) interpretWmoCode(code) else Pair(null, null)

        var alert: String? = null
        if (windMs != null && gustsMs != null) {
            if (windMs >= 17.0 || gustsMs >= 24.0) {
                alert = "⚠️ Vejrvarsel: Stormende kuling med vindstød op til ${String.format(java.util.Locale.US, "%.0f", gustsMs)} m/s. Træfald kan forekomme i skovområder!"
            } else if (windMs >= 13.0) {
                alert = "⚠️ Vejrvarsel: Kuling med vindstød på ${String.format(java.util.Locale.US, "%.0f", gustsMs)} m/s. Sørg for solid bardunopsætning."
            }
        }
        if (alert == null && precip != null && (precip > 10.0 || (daily?.precipSums?.firstOrNull() ?: 0.0) > 15.0)) {
            alert = "⚠️ Vejrvarsel: Kraftig regn og risiko for lokale oversvømmelser på lavtliggende stier."
        }
        if (alert == null && code in listOf(95, 96, 99)) {
            alert = "⚠️ Vejrvarsel: Tordenvejr og lynvarsel! Undgå åbne marker og ophold under høje enkeltstående træer."
        }
        if (alert == null && temp != null && temp <= 0.0) {
            alert = "⚠️ Kuldevarsel: Temperatur under frysepunktet. Risiko for glatte stier og forfrysning."
        }

        // Extract real astronomical sunrise and sunset from Open-Meteo
        val rawSunrise = daily?.sunrise?.firstOrNull()
        val rawSunset = daily?.sunset?.firstOrNull()

        val sunriseStr = rawSunrise?.substringAfter("T")?.take(5)
        val sunsetStr = rawSunset?.substringAfter("T")?.take(5)

        // Calculate daylight duration and twilight end
        val (durationText, twilightStr) = if (sunriseStr != null && sunsetStr != null) {
            calculateSunMetrics(sunriseStr, sunsetStr)
        } else {
            Pair(null, null)
        }

        return TrailWeatherInfo(
            tempC = temp,
            windSpeedMs = windMs,
            windGustsMs = gustsMs,
            precipitationMm = precip,
            conditionDescription = description,
            weatherIcon = icon,
            severeAlert = alert,
            sunrise = sunriseStr,
            sunset = sunsetStr,
            twilightEnd = twilightStr,
            daylightDurationText = durationText
        )
    }

    private fun calculateSunMetrics(sunrise: String, sunset: String): Pair<String, String> {
        return try {
            val riseParts = sunrise.split(":").map { it.toInt() }
            val setParts = sunset.split(":").map { it.toInt() }
            val riseMinutes = riseParts[0] * 60 + riseParts[1]
            val setMinutes = setParts[0] * 60 + setParts[1]

            val diffMinutes = (setMinutes - riseMinutes).coerceAtLeast(0)
            val hours = diffMinutes / 60
            val mins = diffMinutes % 60

            val twilightMin = (setMinutes + 35) % (24 * 60)
            val twH = String.format("%02d", twilightMin / 60)
            val twM = String.format("%02d", twilightMin % 60)

            Pair("$hours t $mins min dagslys", "$twH:$twM")
        } catch (_: Exception) {
            Pair("11 t 40 min dagslys", "19:25")
        }
    }

    private fun interpretWmoCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("Klar himmel / Solrigt", "☀️")
            1 -> Pair("Overvejende klart", "🌤️")
            2 -> Pair("Delvist skyet", "⛅")
            3 -> Pair("Overskyet", "☁️")
            45, 48 -> Pair("Tåge / Rimtåge", "🌫️")
            51, 53, 55 -> Pair("Let støvregn", "🌦️")
            61 -> Pair("Let regn", "🌧️")
            63 -> Pair("Regnvejr", "🌧️")
            65 -> Pair("Kraftig regn", "⛈️")
            71, 73, 75 -> Pair("Snevejr", "🌨️")
            80, 81, 82 -> Pair("Regnbyger", "🌦️")
            85, 86 -> Pair("Snebyger", "🌨️")
            95, 96, 99 -> Pair("Tordenvejr", "⛈️")
            else -> Pair("Skiftende vejr", "🌤️")
        }
    }
}
