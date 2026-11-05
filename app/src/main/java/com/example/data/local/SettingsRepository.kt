package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.StrictMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val plannerCoachMarksSeen: Boolean = false,
    val offlineDownloadsWifiOnly: Boolean = false,
    val mapLayers: MapLayersState = MapLayersState(
        mapDarkMode = mapDarkMode,
        mapFollowsApp = mapFollowsApp,
        mapThemeOverride = mapThemeOverride
    )
) {
    val appearanceMode: MapDarkMode
        get() = mapDarkMode

    val voiceGuidanceEnabled: Boolean
        get() = voiceGuidance

    fun toMapLayersState(): MapLayersState = mapLayers.copy(
        mapDarkMode = mapDarkMode,
        mapFollowsApp = mapFollowsApp,
        mapThemeOverride = mapThemeOverride
    )

    fun resolveIsDarkApp(isSystemDark: Boolean): Boolean =
        mapDarkMode.resolveIsDark(isSystemDark)

    fun resolveIsDarkMap(isSystemDark: Boolean): Boolean =
        if (mapFollowsApp) resolveIsDarkApp(isSystemDark) else (mapThemeOverride == MapThemeOverride.DARK)
}

class SettingsRepository(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    companion object {
        private const val SYNC_PREFS_NAME = "dansk_sti_sync_settings"

        @Volatile
        var cachedMitTempoKmh: Double = 4.0

        @Volatile
        var cachedPlannerCoachMarksSeen: Boolean = false

        @Volatile
        var cachedOfflineDownloadsWifiOnly: Boolean = false

        @Volatile
        private var lastInitializedContextId: Int = 0

        internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val memoryPrefsFlow = MutableStateFlow(emptyPreferences())

        val KEY_MIT_TEMPO = doublePreferencesKey("mit_tempo_kmh")
        val KEY_UNITS = stringPreferencesKey("units")
        val KEY_START_TIME = stringPreferencesKey("start_time")
        val KEY_MAX_DETOUR = doublePreferencesKey("max_detour_km")
        val KEY_DAILY_TARGET = doublePreferencesKey("daily_target_km")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val KEY_PLANNER_COACH_MARKS_SEEN = booleanPreferencesKey("planner_coach_marks_seen")
        val KEY_OFFLINE_WIFI_ONLY = booleanPreferencesKey("offline_wifi_only")

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

        private val DOUBLE_KEYS = listOf(
            KEY_MIT_TEMPO,
            KEY_MAX_DETOUR,
            KEY_DAILY_TARGET
        )

        private val STRING_KEYS = listOf(
            KEY_UNITS,
            KEY_START_TIME,
            KEY_BASE_MAP,
            KEY_MAP_DARK_MODE,
            KEY_MAP_THEME_OVERRIDE
        )

        private val BOOLEAN_KEYS = listOf(
            KEY_KEEP_SCREEN_ON,
            KEY_VOICE_GUIDANCE,
            KEY_PLANNER_COACH_MARKS_SEEN,
            KEY_OFFLINE_WIFI_ONLY,
            KEY_MAP_FOLLOWS_APP,
            KEY_OVERLAY_TERRAIN,
            KEY_OVERLAY_WATER_BODIES,
            KEY_OVERLAY_MARKED_ROUTES,
            KEY_OVERLAY_ACCESS,
            KEY_OVERLAY_SHELTERS,
            KEY_OVERLAY_FRI_TELTNING,
            KEY_OVERLAY_WATER,
            KEY_OVERLAY_TOILETS,
            KEY_OVERLAY_SHOPS,
            KEY_OVERLAY_TRANSPORT,
            KEY_OVERLAY_MY_POINTS
        )
    }

    init {
        ensureLoadedBeforeFirstFrame()
    }

    private fun getSyncPrefs(): SharedPreferences {
        val oldPolicy = StrictMode.allowThreadDiskReads()
        return try {
            appContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    /**
     * Round 10B Item 7 & 8:
     * Loads stored Udseende and settings synchronously before the first frame using SharedPreferences
     * and a short-timeout DataStore read, without starting any per-instance never-ending collector.
     */
    fun ensureLoadedBeforeFirstFrame() {
        val ctxId = System.identityHashCode(appContext)
        val isNewContext = ctxId != lastInitializedContextId
        if (!isNewContext && memoryPrefsFlow.value.asMap().isNotEmpty()) {
            return
        }
        val oldPolicy = StrictMode.allowThreadDiskReads()
        try {
            val sp = getSyncPrefs()
            val mutable = if (isNewContext) {
                emptyPreferences().toMutablePreferences()
            } else {
                memoryPrefsFlow.value.toMutablePreferences()
            }

            // 1. Load synchronous SharedPreferences mirror first
            readSharedPreferencesInto(sp, mutable)

            // 2. If SharedPreferences had no entries yet, wait briefly for DataStore (short timeout)
            if (mutable.asMap().isEmpty()) {
                val diskPrefs = runCatching {
                    runBlocking {
                        withTimeoutOrNull(150L) {
                            appContext.dataStore.data.firstOrNull()
                        }
                    }
                }.getOrNull()
                if (diskPrefs != null && diskPrefs.asMap().isNotEmpty()) {
                    for ((k, v) in diskPrefs.asMap()) {
                        @Suppress("UNCHECKED_CAST")
                        mutable[k as Preferences.Key<Any>] = v
                    }
                    mirrorPreferencesToSharedPrefs(sp, mutable)
                }
            }

            val built = mutable.toPreferences()
            if (isNewContext || built.asMap().isNotEmpty()) {
                memoryPrefsFlow.value = built
            }
            lastInitializedContextId = ctxId
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private fun readSharedPreferencesInto(sp: SharedPreferences, target: MutablePreferences) {
        for (key in STRING_KEYS) {
            if (sp.contains(key.name)) {
                sp.getString(key.name, null)?.let { target[key] = it }
            }
        }
        for (key in BOOLEAN_KEYS) {
            if (sp.contains(key.name)) {
                target[key] = sp.getBoolean(key.name, false)
            }
        }
        for (key in DOUBLE_KEYS) {
            if (sp.contains(key.name)) {
                val rawBits = sp.getLong(key.name, java.lang.Double.doubleToRawLongBits(Double.NaN))
                val d = java.lang.Double.longBitsToDouble(rawBits)
                if (!d.isNaN()) {
                    target[key] = d
                }
            }
        }
    }

    private fun mirrorPreferencesToSharedPrefs(sp: SharedPreferences, prefs: MutablePreferences) {
        val oldPolicy = StrictMode.allowThreadDiskWrites()
        try {
            val editor = sp.edit()
            for ((k, v) in prefs.asMap()) {
                when (v) {
                    is String -> editor.putString(k.name, v)
                    is Boolean -> editor.putBoolean(k.name, v)
                    is Double -> editor.putLong(k.name, java.lang.Double.doubleToRawLongBits(v))
                }
            }
            editor.commit()
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private fun <T> writeKeyToSharedPrefs(key: Preferences.Key<T>, value: T) {
        val oldRead = StrictMode.allowThreadDiskReads()
        val oldWrite = StrictMode.allowThreadDiskWrites()
        try {
            val editor = getSyncPrefs().edit()
            when (value) {
                is String -> editor.putString(key.name, value)
                is Boolean -> editor.putBoolean(key.name, value)
                is Double -> editor.putLong(key.name, java.lang.Double.doubleToRawLongBits(value))
            }
            editor.commit()
        } finally {
            StrictMode.setThreadPolicy(oldWrite)
            StrictMode.setThreadPolicy(oldRead)
        }
    }

    private val safeDataFlow: Flow<Preferences> = memoryPrefsFlow

    /**
     * Round 10B Item 7 & 8:
     * Updates in-memory state + SharedPreferences mirror immediately and writes to DataStore
     * in an application-scope coroutine without the 300 ms timeout, so writes are never cancelled.
     */
    private suspend fun <T> updatePref(key: Preferences.Key<T>, value: T) {
        val updated = memoryPrefsFlow.value.toMutablePreferences().apply {
            this[key] = value
        }.toPreferences()
        memoryPrefsFlow.value = updated
        writeKeyToSharedPrefs(key, value)

        val writeJob = applicationScope.launch {
            runCatching {
                appContext.dataStore.edit { prefs ->
                    prefs[key] = value
                }
            }
        }
        runCatching { writeJob.join() }
    }

    private fun parseMapLayers(prefs: Preferences): MapLayersState = MapLayersState(
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

    private fun parseSettings(prefs: Preferences): AppSettings {
        val tempo = prefs[KEY_MIT_TEMPO] ?: cachedMitTempoKmh
        cachedMitTempoKmh = tempo
        val coachSeen = prefs[KEY_PLANNER_COACH_MARKS_SEEN] ?: cachedPlannerCoachMarksSeen
        cachedPlannerCoachMarksSeen = coachSeen
        val wifiOnly = prefs[KEY_OFFLINE_WIFI_ONLY] ?: cachedOfflineDownloadsWifiOnly
        cachedOfflineDownloadsWifiOnly = wifiOnly
        val layers = parseMapLayers(prefs)
        return AppSettings(
            mitTempoKmh = tempo,
            units = prefs[KEY_UNITS] ?: "metric",
            startTime = prefs[KEY_START_TIME] ?: "09:00",
            maxDetourKm = prefs[KEY_MAX_DETOUR] ?: 1.5,
            dailyTargetKm = prefs[KEY_DAILY_TARGET] ?: 15.0,
            mapDarkMode = layers.mapDarkMode,
            mapFollowsApp = layers.mapFollowsApp,
            mapThemeOverride = layers.mapThemeOverride,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: true,
            voiceGuidance = prefs[KEY_VOICE_GUIDANCE] ?: false,
            plannerCoachMarksSeen = coachSeen,
            offlineDownloadsWifiOnly = wifiOnly,
            mapLayers = layers
        )
    }

    fun currentSettingsSnapshot(): AppSettings {
        ensureLoadedBeforeFirstFrame()
        return parseSettings(memoryPrefsFlow.value)
    }

    fun currentMapLayersSnapshot(): MapLayersState = currentSettingsSnapshot().mapLayers

    /**
     * Simulates a cold process restart in tests by clearing the in-memory cache and reloading
     * synchronously from disk (SharedPreferences + DataStore).
     */
    fun reloadFromDiskBlocking(): AppSettings {
        memoryPrefsFlow.value = emptyPreferences()
        lastInitializedContextId = 0
        ensureLoadedBeforeFirstFrame()
        return currentSettingsSnapshot()
    }

    val settingsFlow: Flow<AppSettings> = safeDataFlow.map { prefs ->
        parseSettings(prefs)
    }

    val plannerCoachMarksSeenFlow: Flow<Boolean> = safeDataFlow.map { prefs ->
        prefs[KEY_PLANNER_COACH_MARKS_SEEN] ?: cachedPlannerCoachMarksSeen
    }

    val mapLayersFlow: Flow<MapLayersState> = safeDataFlow.map { prefs ->
        parseMapLayers(prefs)
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

    val offlineDownloadsWifiOnlyFlow: Flow<Boolean> = safeDataFlow.map { prefs ->
        prefs[KEY_OFFLINE_WIFI_ONLY] ?: cachedOfflineDownloadsWifiOnly
    }

    suspend fun setVoiceGuidance(enabled: Boolean) {
        updatePref(KEY_VOICE_GUIDANCE, enabled)
    }

    suspend fun setVoiceGuidanceEnabled(enabled: Boolean) {
        setVoiceGuidance(enabled)
    }

    suspend fun setPlannerCoachMarksSeen(seen: Boolean) {
        cachedPlannerCoachMarksSeen = seen
        updatePref(KEY_PLANNER_COACH_MARKS_SEEN, seen)
    }

    suspend fun setOfflineDownloadsWifiOnly(wifiOnly: Boolean) {
        cachedOfflineDownloadsWifiOnly = wifiOnly
        updatePref(KEY_OFFLINE_WIFI_ONLY, wifiOnly)
    }
}
