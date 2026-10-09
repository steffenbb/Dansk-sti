package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dansk_sti_settings")

data class AppSettings(
    val mitTempoKmh: Double = 4.0,
    val units: String = "metric",
    val startTime: String = "09:00",
    val maxDetourKm: Double = 1.5,
    val dailyTargetKm: Double = 15.0
)

class SettingsRepository(private val context: Context) {
    companion object {
        val KEY_MIT_TEMPO = doublePreferencesKey("mit_tempo_kmh")
        val KEY_UNITS = stringPreferencesKey("units")
        val KEY_START_TIME = stringPreferencesKey("start_time")
        val KEY_MAX_DETOUR = doublePreferencesKey("max_detour_km")
        val KEY_DAILY_TARGET = doublePreferencesKey("daily_target_km")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            mitTempoKmh = prefs[KEY_MIT_TEMPO] ?: 4.0,
            units = prefs[KEY_UNITS] ?: "metric",
            startTime = prefs[KEY_START_TIME] ?: "09:00",
            maxDetourKm = prefs[KEY_MAX_DETOUR] ?: 1.5,
            dailyTargetKm = prefs[KEY_DAILY_TARGET] ?: 15.0
        )
    }

    suspend fun setMitTempoKmh(tempo: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MIT_TEMPO] = tempo
        }
    }

    suspend fun setUnits(units: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_UNITS] = units
        }
    }

    suspend fun setStartTime(time: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_TIME] = time
        }
    }

    suspend fun setMaxDetourKm(detour: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_DETOUR] = detour
        }
    }

    suspend fun setDailyTargetKm(dailyKm: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAILY_TARGET] = dailyKm
        }
    }
}
