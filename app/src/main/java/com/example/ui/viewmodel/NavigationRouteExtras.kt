package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.RestrictedArea
import com.example.data.repository.RestrictedAreaKind
import com.example.data.remote.TrailWeatherInfo
import com.example.service.NavigationLiveState
import com.example.util.AreaCrossing
import com.example.util.WindAssessment
import com.example.util.WindRelation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Phases C8/D5/D7 navigation extras, kept out of NavigationViewModel (800-line limit):
 * highlights/cafés for "Foran dig", nearest AEDs for the SOS sheet, military areas ahead
 * (banner once per area per trip) and headwind/tailwind from the last 200 m of GPS track.
 */
internal class NavigationRouteExtras(private val app: Context) {
    val highlights = MutableStateFlow<List<PoiEntity>>(emptyList())
    val cafes = MutableStateFlow<List<PoiEntity>>(emptyList())
    private val aeds = MutableStateFlow<List<PoiEntity>>(emptyList())
    val areaCrossings = MutableStateFlow<List<AreaCrossing>>(emptyList())

    private val _militaryAreaBanner = MutableStateFlow<String?>(null)
    val militaryAreaBanner: StateFlow<String?> = _militaryAreaBanner.asStateFlow()
    private val _nearestAeds = MutableStateFlow<List<NearbyAed>>(emptyList())
    val nearestAeds: StateFlow<List<NearbyAed>> = _nearestAeds.asStateFlow()
    private val _navWindRelation = MutableStateFlow(WindRelation.NONE)
    val navWindRelation: StateFlow<WindRelation> = _navWindRelation.asStateFlow()
    private val recentTrack = ArrayDeque<GeoPointDto>()

    fun dismissMilitaryAreaBanner() { _militaryAreaBanner.value = null }

    fun applyCorridorPois(pois: List<PoiEntity>) {
        highlights.value = pois.filter { it.type == "highlight" || it.type == "beach" }
        cafes.value = pois.filter { it.type == "cafe" }
        aeds.value = pois.filter { it.type == "aed" }
    }

    /** Military crossings along the planned route (cached areas, then a 30-day refresh when online). */
    suspend fun loadAreaCrossings(route: PlannedRoute, repository: DanskStiRepository) {
        val pts = route.points.map { GeoPointDto(it.lat, it.lng) }
        val areaRepo = repository.restrictedAreaRepository
        fun military(areas: List<RestrictedArea>) =
            com.example.util.RestrictedAreaChecker.findCrossings(pts, areas).filter { it.area.kind == RestrictedAreaKind.MILITARY }
        areaCrossings.value = military(areaRepo.cachedAreas())
        if (repository.isDeviceOnline()) areaCrossings.value = military(areaRepo.getAreas())
    }

    /** Called on each position update. */
    fun onProgress(route: PlannedRoute, currentWalkKm: Double, live: NavigationLiveState, weather: TrailWeatherInfo?) {
        maybeShowMilitaryAreaBanner(route, currentWalkKm)
        updateWindRelation(live, weather)
    }

    private fun updateWindRelation(st: NavigationLiveState, w: TrailWeatherInfo?) {
        val lat = st.currentLat ?: return
        val lng = st.currentLng ?: return
        val last = recentTrack.lastOrNull()
        if (last == null || com.example.util.GeoUtils.distanceMeters(last.lat, last.lng, lat, lng) >= 10.0) {
            recentTrack.addLast(GeoPointDto(lat, lng))
            while (recentTrack.size > 60) recentTrack.removeFirst()
        }
        val bearing = WindAssessment.recentBearing(recentTrack.toList())
        val dir = w?.windDirectionDeg
        val speed = w?.windSpeedMs
        _navWindRelation.value = if (bearing != null && dir != null && speed != null) WindAssessment.assess(dir, bearing, speed) else WindRelation.NONE
    }

    private fun maybeShowMilitaryAreaBanner(route: PlannedRoute, currentWalkKm: Double) {
        val prefs = app.getSharedPreferences("dansk_sti_nav_alerts", Context.MODE_PRIVATE)
        val key = "military_alert_shown_${route.planId}"
        val shown = prefs.getStringSet(key, emptySet()).orEmpty()
        val next = areaCrossings.value.firstOrNull { c ->
            val (km, _) = route.projectPoint(c.lat, c.lng)
            c.area.id !in shown && km - currentWalkKm in 0.0..AREA_WARNING_AHEAD_KM
        } ?: return
        val ctx = com.example.util.AppLocaleHelper.getLocalizedContext(app)
        _militaryAreaBanner.value = com.example.util.SafetyAccess.areaWarningText(next, ctx, com.example.util.AppLocaleHelper.getCurrentLocale(ctx))
        prefs.edit().putStringSet(key, shown + next.area.id).apply()
    }

    /** Nearest AEDs from the route corridor and from Room (within 5 km) for the SOS sheet. */
    suspend fun refreshNearestAeds(live: NavigationLiveState, repository: DanskStiRepository) {
        val roomAeds = runCatching { withContext(Dispatchers.IO) { repository.getPoisByType("aed") } }.getOrDefault(emptyList())
        _nearestAeds.value = NavigationNearbyAeds.nearest(live.currentLat, live.currentLng, aeds.value, roomAeds)
    }
}
