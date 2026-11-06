package com.example.ui.viewmodel

import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.service.NavigationLiveState
import com.example.util.StagePlanner
import com.example.util.SunCalculator
import org.json.JSONArray
import kotlin.math.max

enum class NavigationMapMode(val label: String) {
    FOLLOW_NORTH_UP("Følg (nord op)"),
    FOLLOW_HEADING_UP("Følg (retning op)"),
    OVERVIEW("Oversigt")
}

data class NextStopInfo(
    val name: String,
    val distanceRemainingKm: Double,
    val type: String
)

data class AheadOnRouteItem(
    val title: String,
    val type: String,
    val kmRemaining: Double,
    val detourMeters: Double
)

data class DayProgressStopIcon(
    val type: String, // "WATER", "SHOP", "CAMP"
    val name: String,
    val fraction: Float, // 0f..1f along today's stage
    val walkKm: Double
)

internal data class RouteStopsCalculationResult(
    val nextWater: NextStopInfo?,
    val nextShop: NextStopInfo?,
    val nextOvernight: NextStopInfo?,
    val aheadItems: List<AheadOnRouteItem>,
    val dayProgressStops: List<DayProgressStopIcon>
)

internal fun computeRouteStopsAndAhead(
    route: PlannedRoute,
    currentWalkKm: Double,
    currentDay: Int,
    currentStage: TripStage?,
    remainingTodayKm: Double,
    waters: List<PoiEntity>,
    shops: List<PoiEntity>,
    shelters: List<ShelterEntity>,
    previousNextOvernight: NextStopInfo?
): RouteStopsCalculationResult {
    val stage = currentStage ?: route.stages.getOrNull(currentDay - 1) ?: route.stages.lastOrNull()
    val stageStartKm = stage?.startWalkKm ?: 0.0
    val stageEndKm = stage?.endWalkKm ?: route.totalWalkKm
    val stageSpanKm = (stageEndKm - stageStartKm).coerceAtLeast(0.1)

    val upcomingItems = mutableListOf<AheadOnRouteItem>()
    val progressIcons = mutableListOf<DayProgressStopIcon>()

    var bestWater: NextStopInfo? = null
    for (w in waters) {
        val (wKm, detourM) = route.projectPoint(w.lat, w.lng)
        if (detourM <= 1500.0) {
            if (wKm in (stageStartKm - 0.1)..(stageEndKm + 0.1)) {
                val frac = ((wKm - stageStartKm) / stageSpanKm).toFloat().coerceIn(0.02f, 0.96f)
                progressIcons.add(DayProgressStopIcon("WATER", w.name, frac, wKm))
            }
            val remKm = wKm - currentWalkKm
            if (remKm > 0.02) {
                upcomingItems.add(AheadOnRouteItem(w.name, "WATER", remKm, detourM))
                if (bestWater == null || remKm < bestWater.distanceRemainingKm) {
                    bestWater = NextStopInfo(w.name, remKm, "WATER")
                }
            }
        }
    }
    if (bestWater == null && stage?.hasWaterNearby == true && remainingTodayKm > 0.0) {
        bestWater = NextStopInfo(stage.stopName, remainingTodayKm, "WATER")
    }

    var bestShop: NextStopInfo? = null
    for (s in shops) {
        val (sKm, detourM) = route.projectPoint(s.lat, s.lng)
        if (detourM <= 2000.0) {
            if (sKm in (stageStartKm - 0.1)..(stageEndKm + 0.1)) {
                val frac = ((sKm - stageStartKm) / stageSpanKm).toFloat().coerceIn(0.02f, 0.96f)
                progressIcons.add(DayProgressStopIcon("SHOP", s.name, frac, sKm))
            }
            val remKm = sKm - currentWalkKm
            if (remKm > 0.02) {
                upcomingItems.add(AheadOnRouteItem(s.name, "SHOP", remKm, detourM))
                if (bestShop == null || remKm < bestShop.distanceRemainingKm) {
                    bestShop = NextStopInfo(s.name, remKm, "SHOP")
                }
            }
        }
    }

    for (sh in shelters) {
        val (shKm, detourM) = route.projectPoint(sh.lat, sh.lng)
        val remKm = shKm - currentWalkKm
        if (detourM <= 1500.0 && remKm > 0.05 && shKm <= stageEndKm + 0.5) {
            upcomingItems.add(AheadOnRouteItem(sh.name, "SHELTER", remKm, detourM))
        }
    }

    var updatedOvernight = previousNextOvernight
    if (stage != null) {
        progressIcons.add(DayProgressStopIcon("CAMP", stage.stopName, 1.0f, stageEndKm))
        updatedOvernight = NextStopInfo(
            name = stage.stopName,
            distanceRemainingKm = max(0.0, stageEndKm - currentWalkKm),
            type = stage.stopType
        )
        if (upcomingItems.none { it.title == stage.stopName }) {
            upcomingItems.add(
                AheadOnRouteItem(
                    title = stage.stopName,
                    type = "SHELTER",
                    kmRemaining = max(0.0, stageEndKm - currentWalkKm),
                    detourMeters = stage.detourMeters
                )
            )
        }
    }

    return RouteStopsCalculationResult(
        nextWater = bestWater,
        nextShop = bestShop,
        nextOvernight = updatedOvernight,
        aheadItems = upcomingItems.sortedBy { it.kmRemaining },
        dayProgressStops = progressIcons.sortedBy { it.fraction }
    )
}

/**
 * Round 11 Item 4:
 * Computes navigation sunset using the current GPS position (or tonight's overnight point when known),
 * never the route's midpoint, and updates when the day changes.
 */
fun computeNavigationSunsetForState(
    liveState: NavigationLiveState,
    currentDay: Int,
    activePlan: TripPlanEntity?,
    activeStages: List<TripStage>,
    plannedRoute: PlannedRoute?,
    fallbackStartDateEpochMs: Long = System.currentTimeMillis()
): String {
    val effectiveDay = (if (liveState.currentDay > 0) liveState.currentDay else currentDay).coerceAtLeast(1)
    val stageForToday = liveState.currentStage?.takeIf { it.dayNumber == effectiveDay }
        ?: activeStages.find { it.dayNumber == effectiveDay }
        ?: plannedRoute?.stages?.find { it.dayNumber == effectiveDay }
        ?: activeStages.getOrNull(effectiveDay - 1)
        ?: plannedRoute?.stages?.getOrNull(effectiveDay - 1)
        ?: liveState.currentStage

    val curLat = liveState.currentLat
    val curLng = liveState.currentLng
    val hasValidCurrentPosition = curLat != null && curLng != null &&
        curLat.isFinite() && curLng.isFinite() &&
        (curLat != 0.0 || curLng != 0.0)

    val (targetLat, targetLng) = when {
        hasValidCurrentPosition -> curLat!! to curLng!!
        stageForToday != null && (stageForToday.stopLat != 0.0 || stageForToday.stopLng != 0.0) ->
            stageForToday.stopLat to stageForToday.stopLng
        else -> {
            val firstPt = plannedRoute?.points?.firstOrNull() ?: liveState.plannedRoute?.points?.firstOrNull()
            if (firstPt != null && (firstPt.lat != 0.0 || firstPt.lng != 0.0)) {
                firstPt.lat to firstPt.lng
            } else {
                SunCalculator.DEFAULT_COPENHAGEN_LAT to SunCalculator.DEFAULT_COPENHAGEN_LNG
            }
        }
    }

    val startEpochMs = activePlan?.createdAt?.takeIf { it > 0L } ?: fallbackStartDateEpochMs
    return StagePlanner.computeSunsetForStageDay(
        dayNumber = effectiveDay,
        stopLat = targetLat,
        stopLng = targetLng,
        startDateEpochMs = startEpochMs
    )
}

internal fun parseFriTeltningPolygonCoords(polygonJson: String?): List<GeoPointDto> {
    if (polygonJson.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(polygonJson)
        val pts = mutableListOf<GeoPointDto>()
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            if (item is JSONArray && item.length() >= 2) {
                val v0 = item.getDouble(0)
                val v1 = item.getDouble(1)
                if (v0 in 53.0..59.0) {
                    pts.add(GeoPointDto(v0, v1))
                } else {
                    pts.add(GeoPointDto(v1, v0))
                }
            }
        }
        pts
    } catch (_: Exception) {
        emptyList()
    }
}

internal suspend fun exportDayGpxForPlan(
    context: android.content.Context,
    repository: com.example.data.repository.DanskStiRepository,
    liveState: NavigationLiveState,
    dayNumber: Int
) {
    val pId = liveState.planId
    val trailName = liveState.trailName.ifBlank { context.getString(com.example.R.string.default_trail_name) }
    if (pId <= 0L) return
    val points = repository.getRecordedTrackPointsForDaySync(pId, dayNumber)
    val stats = repository.getAllRecordedDayStatsSync(pId)
    val gpx = com.example.util.GpxExporter.generateRecordedTrackGpx(
        trailName = trailName,
        planId = pId,
        dayNumber = dayNumber,
        points = points,
        dayStats = stats
    )
    com.example.util.GpxExporter.exportAndShareGpxFile(
        context = context,
        fileName = "${trailName.replace(" ", "_")}_dag_$dayNumber.gpx",
        gpxContent = gpx,
        shareTitle = context.getString(com.example.R.string.share_gps_track_day_title, dayNumber)
    )
}

internal suspend fun exportWholeTripGpxForPlan(
    context: android.content.Context,
    repository: com.example.data.repository.DanskStiRepository,
    liveState: NavigationLiveState
) {
    val pId = liveState.planId
    val trailName = liveState.trailName.ifBlank { context.getString(com.example.R.string.default_trail_name) }
    if (pId <= 0L) return
    val points = repository.getAllRecordedTrackPointsForPlanSync(pId)
    val stats = repository.getAllRecordedDayStatsSync(pId)
    val gpx = com.example.util.GpxExporter.generateRecordedTrackGpx(
        trailName = trailName,
        planId = pId,
        dayNumber = null,
        points = points,
        dayStats = stats
    )
    com.example.util.GpxExporter.exportAndShareGpxFile(
        context = context,
        fileName = "${trailName.replace(" ", "_")}_hel_tur.gpx",
        gpxContent = gpx,
        shareTitle = context.getString(com.example.R.string.share_gps_track_trip_title)
    )
}

internal fun shareCurrentNavigationLocation(
    context: android.content.Context,
    state: NavigationLiveState
) {
    val lat = state.currentLat
    val lng = state.currentLng
    if (lat == null || lng == null) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.example.R.string.toast_waiting_for_gps),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }
    val trailName = state.trailName.ifBlank { context.getString(com.example.R.string.app_name) }
    val walkedKm = String.format(java.util.Locale.US, "%.1f", state.distanceWalkedTodayKm)
    val latStr = String.format(java.util.Locale.US, "%.5f", lat)
    val lngStr = String.format(java.util.Locale.US, "%.5f", lng)

    val message = context.getString(
        com.example.R.string.share_current_location_message_fmt,
        trailName,
        latStr,
        lngStr,
        walkedKm,
        state.currentDay
    )

    val sendIntent = android.content.Intent().apply {
        action = android.content.Intent.ACTION_SEND
        putExtra(android.content.Intent.EXTRA_TEXT, message)
        type = "text/plain"
    }
    try {
        context.startActivity(
            android.content.Intent.createChooser(
                sendIntent,
                context.getString(com.example.R.string.share_current_location_title)
            )
        )
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.example.R.string.toast_no_app_to_share_location),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.example.R.string.toast_cannot_open_share),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

