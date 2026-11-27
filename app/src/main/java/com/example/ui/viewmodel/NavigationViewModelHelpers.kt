package com.example.ui.viewmodel

import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripStage
import com.example.service.NavigationLiveState
import com.example.util.StagePlanner
import com.example.util.SunCalculator
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import kotlin.math.max

enum class NavigationMapMode {
    FOLLOW_NORTH_UP,
    FOLLOW_HEADING_UP,
    OVERVIEW;

    val label: String
        get() = when (this) {
            FOLLOW_NORTH_UP -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.nav_map_mode_follow_north) ?: "Følg (nord op)"
            FOLLOW_HEADING_UP -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.nav_map_mode_follow_heading) ?: "Følg (retning op)"
            OVERVIEW -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.nav_map_mode_overview) ?: "Oversigt"
        }
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
    previousNextOvernight: NextStopInfo?,
    pathWarnings: List<com.example.util.PathFeatureWarning> = emptyList()
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

    for (pw in pathWarnings) {
        val (projKm, detourM) = route.projectPoint(pw.lat, pw.lng)
        if (detourM <= 50.0) {
            val remKm = projKm - currentWalkKm
            if (remKm in 0.0..5.0) {
                upcomingItems.add(
                    AheadOnRouteItem(
                        title = pw.title,
                        type = "WARNING",
                        kmRemaining = remKm,
                        detourMeters = detourM
                    )
                )
            }
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
        fileName = com.example.util.GpxExporter.buildSafeGpxFileName(trailName, "dag_$dayNumber"),
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
        fileName = com.example.util.GpxExporter.buildSafeGpxFileName(trailName, "hel_tur"),
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

internal fun postNavigationRainAlertNotification(
    appCtx: android.content.Context,
    channelId: String,
    notificationId: Int,
    precipMm: Double,
    timeLabel: String
) {
    try {
        val localizedCtx = com.example.util.AppLocaleHelper.createLocalizedContext(appCtx)
        val nm = appCtx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                localizedCtx.getString(com.example.R.string.notif_rain_alert_channel_name),
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = localizedCtx.getString(com.example.R.string.notif_rain_alert_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
        val locale = com.example.util.AppLocaleHelper.getCurrentLocale(appCtx)
        val mmStr = String.format(locale, "%.1f", precipMm)
        val title = localizedCtx.getString(com.example.R.string.notif_rain_alert_title_fmt, timeLabel)
        val body = localizedCtx.getString(com.example.R.string.notif_rain_alert_body_fmt, mmStr)
        val notif = androidx.core.app.NotificationCompat.Builder(localizedCtx, channelId)
            .setSmallIcon(com.example.R.drawable.ic_notif_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, notif)
    } catch (e: Exception) {
        android.util.Log.w("NavigationViewModel", "Could not post rain alert notification: ${e.message}")
    }
}

internal suspend fun computeBackToRouteLegPoints(
    app: android.app.Application,
    from: com.example.util.routing.LatLng,
    to: com.example.util.routing.LatLng,
    customRouter: (suspend (com.example.util.routing.LatLng, com.example.util.routing.LatLng) -> com.example.util.routing.RouteLegResult?)?
): List<com.example.util.routing.RoutePoint3D>? {
    if (customRouter != null) {
        val customRes = customRouter(from, to)
        return if (customRes is com.example.util.routing.RouteLegResult.Success && customRes.points.size >= 2) {
            customRes.points
        } else null
    }
    val offlineEngine = com.example.util.routing.BRouterOfflineEngine(app)
    val offlineRes = offlineEngine.routeLeg(from, to, com.example.util.routing.RouteOptions())
    if (offlineRes is com.example.util.routing.RouteLegResult.Success && offlineRes.points.size >= 2) {
        return offlineRes.points
    }
    val onlineEngine = com.example.util.routing.BRouterOnlineEngine(app)
    val onlineRes = onlineEngine.routeLeg(from, to, com.example.util.routing.RouteOptions())
    return if (onlineRes is com.example.util.routing.RouteLegResult.Success && onlineRes.points.size >= 2) {
        onlineRes.points
    } else null
}

internal suspend fun shareCompletedTripWithDiaryHelper(
    context: android.content.Context,
    repository: com.example.data.repository.DanskStiRepository,
    planId: Long,
    fallbackEntries: List<com.example.data.model.TripDiaryEntryEntity>,
    trailName: String,
    totalDistanceKm: Double,
    totalMovingSeconds: Long,
    totalAscentMeters: Int,
    totalDays: Int
) {
    val entries = if (planId > 0L) repository.getDiaryEntriesForPlanSync(planId) else fallbackEntries
    val locale = com.example.util.AppLocaleHelper.getCurrentLocale(context)
    val hours = totalMovingSeconds / 3600
    val mins = (totalMovingSeconds % 3600) / 60
    val movingStr = if (hours > 0) "${hours}t ${mins}m" else "${mins} min"
    val summaryText = com.example.ui.components.TripDiaryPhotoHelper.buildTripShareSummaryText(
        trailName = trailName,
        totalDistanceKm = totalDistanceKm,
        totalMovingTimeFormatted = movingStr,
        totalAscentMeters = totalAscentMeters,
        totalDays = totalDays,
        diaryEntries = entries,
        locale = locale
    )
    if (planId > 0L) {
        val points = repository.getAllRecordedTrackPointsForPlanSync(planId)
        val stats = repository.getAllRecordedDayStatsSync(planId)
        val gpx = com.example.util.GpxExporter.generateRecordedTrackGpx(
            trailName = trailName.ifBlank { context.getString(com.example.R.string.default_trail_name) },
            planId = planId,
            dayNumber = null,
            points = points,
            dayStats = stats
        )
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            com.example.util.GpxExporter.exportAndShareGpxWithSummary(
                context = context,
                fileName = com.example.util.GpxExporter.buildSafeGpxFileName(trailName, "tur_dagbog"),
                gpxContent = gpx,
                shareTitle = trailName.ifBlank { context.getString(com.example.R.string.default_trail_name) },
                textSummary = summaryText
            )
        }
    } else {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, summaryText)
            }
            runCatching {
                context.startActivity(
                    android.content.Intent.createChooser(sendIntent, trailName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

internal data class CorridorDataFetchResult(
    val initialShelters: List<ShelterEntity>,
    val initialPois: List<PoiEntity>,
    val friTeltningWithPolygons: List<Pair<FriTeltningEntity, List<GeoPointDto>>>,
    val hazards: List<com.example.data.model.UserHazardNoteEntity>,
    val needsTrailCorridorSync: Boolean
)

internal suspend fun loadInitialCorridorDataForRoute(
    repository: com.example.data.repository.DanskStiRepository,
    route: PlannedRoute
): CorridorDataFetchResult {
    val trailId = route.trailId
    val geoPoints = route.points.map { GeoPointDto(it.lat, it.lng, it.ele) }
    val initialShelters = repository.getSheltersForTrail(trailId)
    val initialPois = repository.getPoisForTrail(trailId)
    var needsTrailCorridorSync = false
    if (initialShelters.isEmpty() && initialPois.isEmpty() && geoPoints.isNotEmpty()) {
        if (trailId.startsWith("route_")) {
            val routeId = trailId.removePrefix("route_")
            if (repository.getActiveRoutePoiSyncJob(routeId)?.isActive != true) {
                repository.enqueueRouteCorridorPoiSync(routeId, geoPoints)
            }
        } else {
            needsTrailCorridorSync = true
        }
    }
    val allFri = repository.getAllFriTeltningList()
    val corridorFri = if (geoPoints.isNotEmpty()) {
        allFri.filter { ft ->
            val (_, detourM) = com.example.util.GeoUtils.projectPointOnPolyline(
                GeoPointDto(ft.centerLat, ft.centerLng),
                geoPoints
            )
            detourM <= 3000.0
        }
    } else emptyList()
    val friWithPolygons = corridorFri.map { ft ->
        ft to parseFriTeltningPolygonCoords(ft.polygonJson)
    }
    val hazards = repository.getHazardNotesForTrail(trailId).firstOrNull() ?: emptyList()
    return CorridorDataFetchResult(
        initialShelters = initialShelters,
        initialPois = initialPois,
        friTeltningWithPolygons = friWithPolygons,
        hazards = hazards,
        needsTrailCorridorSync = needsTrailCorridorSync
    )
}

internal suspend fun NavigationViewModel.performTonightCampWeatherPrefetch(
    stopLat: Double,
    stopLng: Double,
    dayNum: Int,
    nowMs: Long
) {
    try {
        val cached = repository.getCachedTrailWeatherOnly(stopLat, stopLng, nowMs = nowMs)
        val cacheIsFresh = cached != null && cached.hourly48h.isNotEmpty() &&
            cached.fetchedAtMillis > 0L &&
            (nowMs - cached.fetchedAtMillis) <= com.example.data.remote.WeatherInterpreter.CAMP_PREFETCH_MAX_AGE_MS
        if (cacheIsFresh) {
            applyTonightCampWeatherResult(cached, isFreshSuccess = true, dayNum = dayNum, nowMs = nowMs)
        } else {
            val weather = repository.getTrailWeather(stopLat, stopLng, forceNetworkRefresh = true, nowMs = nowMs)
            val networkSucceeded = weather.hourly48h.isNotEmpty() && weather.fetchedAtMillis == nowMs
            applyTonightCampWeatherResult(weather, isFreshSuccess = networkSucceeded, dayNum = dayNum, nowMs = nowMs)
        }
    } catch (e: Exception) {
        android.util.Log.w("NavigationViewModel", "Failed to prefetch tonight camp weather: ${e.message}")
        applyTonightCampWeatherResult(null, isFreshSuccess = false, dayNum = dayNum, nowMs = nowMs)
    }
}

internal suspend fun NavigationViewModel.performNavigationWeatherUpdate(
    lat: Double,
    lng: Double,
    shouldRefresh: Boolean,
    state: NavigationLiveState,
    nowMs: Long
) {
    try {
        val weather = if (shouldRefresh) {
            repository.getTrailWeather(lat, lng, forceNetworkRefresh = true, nowMs = nowMs)
        } else {
            repository.getCachedTrailWeatherOnly(lat, lng, nowMs = nowMs)
        }
        applyNavigationWeatherResult(weather, shouldRefresh, lat, lng, state, nowMs)
    } catch (e: Exception) {
        android.util.Log.w("NavigationViewModel", "Failed updating navigation weather: ${e.message}")
        applyNavigationWeatherResult(null, shouldRefresh, lat, lng, state, nowMs)
    }
}

internal fun NavigationViewModel.applyNavigationWeatherResult(
    weather: com.example.data.remote.TrailWeatherInfo?,
    shouldRefresh: Boolean,
    lat: Double,
    lng: Double,
    state: NavigationLiveState,
    nowMs: Long
) {
    val hasHourly = weather != null && weather.hourly48h.isNotEmpty()
    val networkSucceeded = hasHourly && weather!!.fetchedAtMillis == nowMs
    if (shouldRefresh) {
        if (networkSucceeded) {
            lastWeatherFetchMs = nowMs
            lastWeatherFetchLat = lat
            lastWeatherFetchLng = lng
            lastWeatherFailureMs = 0L
        } else {
            lastWeatherFailureMs = nowMs
        }
    }
    if (hasHourly && (networkSucceeded || _navigationWeather.value == null)) {
        _navigationWeather.value = weather
        if (_rainAlertEnabled.value && state.isTracking && !state.isPaused) {
            com.example.data.remote.WeatherInterpreter.shouldPostRainAlert(
                rainAlertEnabled = true,
                lastAlertAtMs = lastRainAlertPostedMs,
                nowMs = nowMs,
                hourlyPoints = weather!!.hourly48h
            )?.let { alertBlock ->
                lastRainAlertPostedMs = nowMs
                postRainAlertNotification(alertBlock.precipitationMm, alertBlock.hourLabel)
            }
        }
    }
}

internal fun NavigationViewModel.applyTonightCampWeatherResult(
    weather: com.example.data.remote.TrailWeatherInfo?,
    isFreshSuccess: Boolean,
    dayNum: Int,
    nowMs: Long
) {
    val hasHourly = weather != null && weather.hourly48h.isNotEmpty()
    if (isFreshSuccess && hasHourly) {
        _tonightCampWeather.value = weather
        lastPrefetchedCampDay = dayNum
        lastCampWeatherFailureMs = 0L
    } else {
        lastCampWeatherFailureMs = nowMs
        if (_tonightCampWeather.value == null && hasHourly) {
            _tonightCampWeather.value = weather
        }
    }
}



