package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    ALWAYS_LIGHT("Lyst"),
    ALWAYS_DARK("Mørkt");

    val title: String
        get() = displayName

    fun resolveIsDark(isSystemDark: Boolean): Boolean = when (this) {
        FOLLOW_SYSTEM -> isSystemDark
        ALWAYS_LIGHT -> false
        ALWAYS_DARK -> true
    }

    companion object {
        val LIGHT: MapDarkMode = ALWAYS_LIGHT
        val DARK: MapDarkMode = ALWAYS_DARK

        fun fromString(value: String?): MapDarkMode {
            if (value.isNullOrBlank()) return FOLLOW_SYSTEM
            return when (value.trim().uppercase()) {
                "FOLLOW_SYSTEM", "SYSTEM", "FØLG TELEFONEN" -> FOLLOW_SYSTEM
                "ALWAYS_LIGHT", "LIGHT", "LYST", "ALTID LYST" -> ALWAYS_LIGHT
                "ALWAYS_DARK", "DARK", "MØRKT", "ALTID MØRKT" -> ALWAYS_DARK
                else -> entries.find {
                    it.name.equals(value, ignoreCase = true) ||
                        it.displayName.equals(value, ignoreCase = true)
                } ?: FOLLOW_SYSTEM
            }
        }
    }
}

typealias DarkMapMode = MapDarkMode
typealias AppAppearanceMode = MapDarkMode

enum class MapThemeOverride(val displayName: String) {
    LIGHT("Lyst"),
    DARK("Mørkt");

    val title: String
        get() = displayName

    companion object {
        fun fromString(value: String?): MapThemeOverride {
            if (value.isNullOrBlank()) return LIGHT
            return when (value.trim().uppercase()) {
                "DARK", "MØRKT", "ALWAYS_DARK", "ALTID MØRKT" -> DARK
                else -> LIGHT
            }
        }
    }
}

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
    val mapDarkMode: MapDarkMode = MapDarkMode.FOLLOW_SYSTEM,
    val mapFollowsApp: Boolean = true,
    val mapThemeOverride: MapThemeOverride = MapThemeOverride.LIGHT
) {
    val showAccessLand: Boolean
        get() = showAccess
    val showWaterPois: Boolean
        get() = showWater
    val showTransit: Boolean
        get() = showTransport
    val darkMapMode: MapDarkMode
        get() = mapDarkMode
    val appearanceMode: MapDarkMode
        get() = mapDarkMode

    fun resolveIsDarkApp(isSystemDark: Boolean): Boolean =
        mapDarkMode.resolveIsDark(isSystemDark)

    fun resolveIsDark(isSystemDark: Boolean): Boolean =
        resolveIsDarkMap(isSystemDark = isSystemDark)

    fun resolveIsDarkMap(
        isSystemDark: Boolean,
        isAppDark: Boolean? = null
    ): Boolean {
        return if (mapFollowsApp) {
            isAppDark ?: mapDarkMode.resolveIsDark(isSystemDark)
        } else {
            mapThemeOverride == MapThemeOverride.DARK
        }
    }
}

data class AppSettings(
    val mitTempoKmh: Double = 4.0,
    val units: String = "metric",
    val startTime: String = "09:00",
    val maxDetourKm: Double = 1.5,
    val dailyTargetKm: Double = 15.0,
    val mapDarkMode: MapDarkMode = MapDarkMode.FOLLOW_SYSTEM,
    val mapFollowsApp: Boolean = true,
    val mapThemeOverride: MapThemeOverride = MapThemeOverride.LIGHT,
    val keepScreenOn: Boolean = true,
    val voiceGuidance: Boolean = false,
    val plannerCoachMarksSeen: Boolean = false
) {
    val appearanceMode: MapDarkMode
        get() = mapDarkMode

    fun resolveIsDarkApp(isSystemDark: Boolean): Boolean =
        mapDarkMode.resolveIsDark(isSystemDark)

    fun resolveIsDarkMap(isSystemDark: Boolean): Boolean =
        if (mapFollowsApp) resolveIsDarkApp(isSystemDark) else (mapThemeOverride == MapThemeOverride.DARK)
}

class SettingsRepository(private val context: Context) {
    companion object {
        @Volatile
        var cachedMitTempoKmh: Double = 4.0

        @Volatile
        var cachedPlannerCoachMarksSeen: Boolean = false

        private val memoryPrefsFlow = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())

        val KEY_MIT_TEMPO = doublePreferencesKey("mit_tempo_kmh")
        val KEY_UNITS = stringPreferencesKey("units")
        val KEY_START_TIME = stringPreferencesKey("start_time")
        val KEY_MAX_DETOUR = doublePreferencesKey("max_detour_km")
        val KEY_DAILY_TARGET = doublePreferencesKey("daily_target_km")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val KEY_PLANNER_COACH_MARKS_SEEN = booleanPreferencesKey("planner_coach_marks_seen")

        // Appearance & Map Layers Keys
        val KEY_BASE_MAP = stringPreferencesKey("base_map_type")
        val KEY_MAP_DARK_MODE = stringPreferencesKey("map_dark_mode")
        val KEY_APP_APPEARANCE = KEY_MAP_DARK_MODE
        val KEY_MAP_FOLLOWS_APP = booleanPreferencesKey("map_follows_app")
        val KEY_MAP_THEME_OVERRIDE = stringPreferencesKey("map_theme_override")
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

    init {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                context.dataStore.data.collect { diskPrefs ->
                    if (memoryPrefsFlow.value.asMap().isEmpty() && diskPrefs.asMap().isNotEmpty()) {
                        memoryPrefsFlow.value = diskPrefs
                    }
                }
            }
        }
    }

    private val safeDataFlow: Flow<Preferences> = memoryPrefsFlow

    private suspend fun <T> updatePref(key: Preferences.Key<T>, value: T) {
        val updated = memoryPrefsFlow.value.toMutablePreferences().apply {
            this[key] = value
        }.toPreferences()
        memoryPrefsFlow.value = updated
        runCatching {
            withTimeoutOrNull(300L) {
                context.dataStore.edit { prefs ->
                    prefs[key] = value
                }
            }
        }
    }

    private fun parseSettings(prefs: Preferences): AppSettings {
        val tempo = prefs[KEY_MIT_TEMPO] ?: cachedMitTempoKmh
        cachedMitTempoKmh = tempo
        val coachSeen = prefs[KEY_PLANNER_COACH_MARKS_SEEN] ?: cachedPlannerCoachMarksSeen
        cachedPlannerCoachMarksSeen = coachSeen
        return AppSettings(
            mitTempoKmh = tempo,
            units = prefs[KEY_UNITS] ?: "metric",
            startTime = prefs[KEY_START_TIME] ?: "09:00",
            maxDetourKm = prefs[KEY_MAX_DETOUR] ?: 1.5,
            dailyTargetKm = prefs[KEY_DAILY_TARGET] ?: 15.0,
            mapDarkMode = MapDarkMode.fromString(prefs[KEY_MAP_DARK_MODE]),
            mapFollowsApp = prefs[KEY_MAP_FOLLOWS_APP] ?: true,
            mapThemeOverride = MapThemeOverride.fromString(prefs[KEY_MAP_THEME_OVERRIDE]),
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: true,
            voiceGuidance = prefs[KEY_VOICE_GUIDANCE] ?: false,
            plannerCoachMarksSeen = coachSeen
        )
    }

    fun currentSettingsSnapshot(): AppSettings = parseSettings(memoryPrefsFlow.value)

    val settingsFlow: Flow<AppSettings> = safeDataFlow.map { prefs ->
        parseSettings(prefs)
    }

    val plannerCoachMarksSeenFlow: Flow<Boolean> = safeDataFlow.map { prefs ->
        prefs[KEY_PLANNER_COACH_MARKS_SEEN] ?: cachedPlannerCoachMarksSeen
    }

    val mapLayersFlow: Flow<MapLayersState> = safeDataFlow.map { prefs ->
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
            mapDarkMode = MapDarkMode.fromString(prefs[KEY_MAP_DARK_MODE]),
            mapFollowsApp = prefs[KEY_MAP_FOLLOWS_APP] ?: true,
            mapThemeOverride = MapThemeOverride.fromString(prefs[KEY_MAP_THEME_OVERRIDE])
        )
    }

    suspend fun setBaseMap(type: BaseMapType) {
        updatePref(KEY_BASE_MAP, type.name)
    }

    suspend fun setAppearanceMode(mode: MapDarkMode) {
        updatePref(KEY_MAP_DARK_MODE, mode.name)
    }

    suspend fun setMapDarkMode(mode: MapDarkMode) {
        updatePref(KEY_MAP_DARK_MODE, mode.name)
    }

    suspend fun setMapFollowsApp(followsApp: Boolean) {
        updatePref(KEY_MAP_FOLLOWS_APP, followsApp)
    }

    suspend fun setMapThemeOverride(overrideMode: MapThemeOverride) {
        updatePref(KEY_MAP_THEME_OVERRIDE, overrideMode.name)
    }

    suspend fun setOverlayTerrain(enabled: Boolean) {
        updatePref(KEY_OVERLAY_TERRAIN, enabled)
    }

    suspend fun setOverlayWaterLayer(enabled: Boolean) {
        updatePref(KEY_OVERLAY_WATER_BODIES, enabled)
    }

    suspend fun setOverlayMarkedRoutes(enabled: Boolean) {
        updatePref(KEY_OVERLAY_MARKED_ROUTES, enabled)
    }

    suspend fun setOverlayAccess(enabled: Boolean) {
        updatePref(KEY_OVERLAY_ACCESS, enabled)
    }

    suspend fun setOverlayShelters(enabled: Boolean) {
        updatePref(KEY_OVERLAY_SHELTERS, enabled)
    }

    suspend fun setOverlayFriTeltning(enabled: Boolean) {
        updatePref(KEY_OVERLAY_FRI_TELTNING, enabled)
    }

    suspend fun setOverlayWater(enabled: Boolean) {
        updatePref(KEY_OVERLAY_WATER, enabled)
    }

    suspend fun setOverlayToilets(enabled: Boolean) {
        updatePref(KEY_OVERLAY_TOILETS, enabled)
    }

    suspend fun setOverlayShops(enabled: Boolean) {
        updatePref(KEY_OVERLAY_SHOPS, enabled)
    }

    suspend fun setOverlayTransport(enabled: Boolean) {
        updatePref(KEY_OVERLAY_TRANSPORT, enabled)
    }

    suspend fun setOverlayMyPoints(enabled: Boolean) {
        updatePref(KEY_OVERLAY_MY_POINTS, enabled)
    }

    suspend fun setMitTempoKmh(tempo: Double) {
        cachedMitTempoKmh = tempo
        updatePref(KEY_MIT_TEMPO, tempo)
    }

    suspend fun setUnits(units: String) {
        updatePref(KEY_UNITS, units)
    }

    suspend fun setStartTime(time: String) {
        updatePref(KEY_START_TIME, time)
    }

    suspend fun setMaxDetourKm(detour: Double) {
        updatePref(KEY_MAX_DETOUR, detour)
    }

    suspend fun setDailyTargetKm(dailyKm: Double) {
        updatePref(KEY_DAILY_TARGET, dailyKm)
    }

    suspend fun setDarkMapMode(mode: MapDarkMode) = setMapDarkMode(mode)

    suspend fun setKeepScreenOn(enabled: Boolean) {
        updatePref(KEY_KEEP_SCREEN_ON, enabled)
    }

    suspend fun setVoiceGuidance(enabled: Boolean) {
        updatePref(KEY_VOICE_GUIDANCE, enabled)
    }

    suspend fun setPlannerCoachMarksSeen(seen: Boolean) {
        cachedPlannerCoachMarksSeen = seen
        updatePref(KEY_PLANNER_COACH_MARKS_SEEN, seen)
    }
}
