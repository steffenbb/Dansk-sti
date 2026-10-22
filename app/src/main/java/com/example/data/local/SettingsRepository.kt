package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dansk_sti_settings")

enum class BaseMapType(
    val displayName: String,
    val sheetTitle: String,
    val attribution: String
) {
    SKAERMKORT("Skærmkortet", "Topografisk", "Klimadatastyrelsen / Skærmkortet"),
    FRILUFTSKORT("Friluftskort", "Udendørs", "© OpenStreetMap • Thunderforest / OpenTopoMap"),
    LUFTFOTO("Luftfoto", "Luftfoto", "GeoDanmark / Klimadatastyrelsen Ortofoto");

    companion object {
        fun fromString(value: String?): BaseMapType {
            if (value.isNullOrBlank()) return SKAERMKORT
            return when (value.trim().uppercase()) {
                "SKAERMKORT", "TOPOGRAFISK", "SKÆRMKORTET" -> SKAERMKORT
                "FRILUFTSKORT", "UDENDOERS", "UDENDØRS", "OUTDOORS" -> FRILUFTSKORT
                "LUFTFOTO", "ORTOFOTO" -> LUFTFOTO
                else -> entries.find {
                    it.name.equals(value, ignoreCase = true) ||
                        it.displayName.equals(value, ignoreCase = true) ||
                        it.sheetTitle.equals(value, ignoreCase = true)
                } ?: SKAERMKORT
            }
        }
    }
}

enum class MapDarkMode(val displayName: String) {
    FOLLOW_SYSTEM("Følg telefonen"),
    ALWAYS_LIGHT("Altid lyst"),
    ALWAYS_DARK("Altid mørkt");

    val title: String
        get() = displayName

    fun resolveIsDark(isSystemDark: Boolean): Boolean = when (this) {
        FOLLOW_SYSTEM -> isSystemDark
        ALWAYS_LIGHT -> false
        ALWAYS_DARK -> true
    }

    companion object {
        fun fromString(value: String?): MapDarkMode {
            if (value.isNullOrBlank()) return FOLLOW_SYSTEM
            return entries.find {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true)
            } ?: FOLLOW_SYSTEM
        }
    }
}

typealias DarkMapMode = MapDarkMode

data class MapLayersState(
    val baseMap: BaseMapType = BaseMapType.SKAERMKORT,
    val showTerrain: Boolean = true,
    val showWaterLayer: Boolean = true,
    val showMarkedRoutes: Boolean = true,
    val showAccess: Boolean = true,
    val showShelters: Boolean = true,
    val showFriTeltning: Boolean = true,
    val showWater: Boolean = true,
    val showToilets: Boolean = true,
    val showShops: Boolean = false,
    val showTransport: Boolean = false,
    val showMyPoints: Boolean = true,
    val mapDarkMode: MapDarkMode = MapDarkMode.FOLLOW_SYSTEM
) {
    val showAccessLand: Boolean
        get() = showAccess
    val showWaterPois: Boolean
        get() = showWater
    val showTransit: Boolean
        get() = showTransport
    val darkMapMode: MapDarkMode
        get() = mapDarkMode

    fun resolveIsDark(isSystemDark: Boolean): Boolean = mapDarkMode.resolveIsDark(isSystemDark)
    fun resolveIsDarkMap(isSystemDark: Boolean): Boolean = mapDarkMode.resolveIsDark(isSystemDark)
}

data class AppSettings(
    val mitTempoKmh: Double = 4.0,
    val units: String = "metric",
    val startTime: String = "09:00",
    val maxDetourKm: Double = 1.5,
    val dailyTargetKm: Double = 15.0,
    val mapDarkMode: MapDarkMode = MapDarkMode.FOLLOW_SYSTEM
)

class SettingsRepository(private val context: Context) {
    companion object {
        val KEY_MIT_TEMPO = doublePreferencesKey("mit_tempo_kmh")
        val KEY_UNITS = stringPreferencesKey("units")
        val KEY_START_TIME = stringPreferencesKey("start_time")
        val KEY_MAX_DETOUR = doublePreferencesKey("max_detour_km")
        val KEY_DAILY_TARGET = doublePreferencesKey("daily_target_km")

        // Map Layers & Base Map Keys
        val KEY_BASE_MAP = stringPreferencesKey("base_map_type")
        val KEY_MAP_DARK_MODE = stringPreferencesKey("map_dark_mode")
        val KEY_OVERLAY_TERRAIN = booleanPreferencesKey("overlay_terrain")
        val KEY_OVERLAY_WATER_BODIES = booleanPreferencesKey("overlay_water_bodies")
        val KEY_OVERLAY_MARKED_ROUTES = booleanPreferencesKey("overlay_marked_routes")
        val KEY_OVERLAY_ACCESS = booleanPreferencesKey("overlay_access")
        val KEY_OVERLAY_SHELTERS = booleanPreferencesKey("overlay_shelters")
        val KEY_OVERLAY_FRI_TELTNING = booleanPreferencesKey("overlay_fri_teltning")
        val KEY_OVERLAY_WATER = booleanPreferencesKey("overlay_water")
        val KEY_OVERLAY_TOILETS = booleanPreferencesKey("overlay_toilets")
        val KEY_OVERLAY_SHOPS = booleanPreferencesKey("overlay_shops")
        val KEY_OVERLAY_TRANSPORT = booleanPreferencesKey("overlay_transport")
        val KEY_OVERLAY_MY_POINTS = booleanPreferencesKey("overlay_my_points")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            mitTempoKmh = prefs[KEY_MIT_TEMPO] ?: 4.0,
            units = prefs[KEY_UNITS] ?: "metric",
            startTime = prefs[KEY_START_TIME] ?: "09:00",
            maxDetourKm = prefs[KEY_MAX_DETOUR] ?: 1.5,
            dailyTargetKm = prefs[KEY_DAILY_TARGET] ?: 15.0,
            mapDarkMode = MapDarkMode.fromString(prefs[KEY_MAP_DARK_MODE])
        )
    }

    val mapLayersFlow: Flow<MapLayersState> = context.dataStore.data.map { prefs ->
        MapLayersState(
            baseMap = BaseMapType.fromString(prefs[KEY_BASE_MAP]),
            showTerrain = prefs[KEY_OVERLAY_TERRAIN] ?: true,
            showWaterLayer = prefs[KEY_OVERLAY_WATER_BODIES] ?: true,
            showMarkedRoutes = prefs[KEY_OVERLAY_MARKED_ROUTES] ?: true,
            showAccess = prefs[KEY_OVERLAY_ACCESS] ?: true,
            showShelters = prefs[KEY_OVERLAY_SHELTERS] ?: true,
            showFriTeltning = prefs[KEY_OVERLAY_FRI_TELTNING] ?: true,
            showWater = prefs[KEY_OVERLAY_WATER] ?: true,
            showToilets = prefs[KEY_OVERLAY_TOILETS] ?: true,
            showShops = prefs[KEY_OVERLAY_SHOPS] ?: false,
            showTransport = prefs[KEY_OVERLAY_TRANSPORT] ?: false,
            showMyPoints = prefs[KEY_OVERLAY_MY_POINTS] ?: true,
            mapDarkMode = MapDarkMode.fromString(prefs[KEY_MAP_DARK_MODE])
        )
    }

    suspend fun setBaseMap(type: BaseMapType) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_MAP] = type.name
        }
    }

    suspend fun setMapDarkMode(mode: MapDarkMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAP_DARK_MODE] = mode.name
        }
    }

    suspend fun setOverlayTerrain(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_TERRAIN] = enabled
        }
    }

    suspend fun setOverlayWaterLayer(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_WATER_BODIES] = enabled
        }
    }

    suspend fun setOverlayMarkedRoutes(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_MARKED_ROUTES] = enabled
        }
    }

    suspend fun setOverlayAccess(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_ACCESS] = enabled
        }
    }

    suspend fun setOverlayShelters(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_SHELTERS] = enabled
        }
    }

    suspend fun setOverlayFriTeltning(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_FRI_TELTNING] = enabled
        }
    }

    suspend fun setOverlayWater(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_WATER] = enabled
        }
    }

    suspend fun setOverlayToilets(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_TOILETS] = enabled
        }
    }

    suspend fun setOverlayShops(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_SHOPS] = enabled
        }
    }

    suspend fun setOverlayTransport(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_TRANSPORT] = enabled
        }
    }

    suspend fun setOverlayMyPoints(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_OVERLAY_MY_POINTS] = enabled
        }
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

    suspend fun setDarkMapMode(mode: MapDarkMode) = setMapDarkMode(mode)
}
