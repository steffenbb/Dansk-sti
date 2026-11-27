package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.GeoPointDto
import com.example.data.model.TripStage
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteWaypoint
import java.util.Locale

/**
 * Per-day planner warnings that depend on where along the route something lies
 * (moved out of RouteBuilderViewModelDays to keep that file under the 800-line limit).
 */
internal object PlannerDayWarnings {

    /** Combined route points used to project warning locations onto km. */
    fun combinedRoutePoints(state: RouteBuilderUiState, wps: List<RouteWaypoint>): List<GeoPointDto> {
        val orderedLegs = buildList {
            for (i in 0 until wps.size - 1) {
                val k = "${wps[i].id}_to_${wps[i + 1].id}"
                state.legs[k]?.let { add(it) }
            }
        }
        return if (orderedLegs.isNotEmpty()) {
            RouteGeometryStorageManager.getCombinedPoints(orderedLegs).map { GeoPointDto(it.lat, it.lng, it.ele) }
        } else {
            wps.filter { !it.isHelper }.map { GeoPointDto(it.lat, it.lng) }
        }
    }

    /**
     * True when [km] belongs to the day [stage]. A km exactly on a day boundary belongs to the
     * following day, so a warning is never listed on two days.
     */
    fun kmBelongsToDay(km: Double, stage: TripStage, isLastDay: Boolean): Boolean {
        val inStart = km >= stage.startKm - 1e-6
        val inEnd = if (isLastDay) km <= stage.endKm + 1e-3 else km < stage.endKm - 1e-6
        return inStart && inEnd
    }

    /** B6: each access warning is added only to the day whose km range contains it. */
    fun appendAccessWarnings(
        state: RouteBuilderUiState,
        combinedPts: List<GeoPointDto>,
        stage: TripStage,
        idx: Int,
        isLastDay: Boolean,
        dayWarnings: MutableList<String>
    ) {
        state.accessWarnings.forEach { acc ->
            val belongsToThisDay = if (combinedPts.size >= 2) {
                val (projKm, _) = com.example.util.GeoUtils.projectPointOnPolyline(
                    GeoPointDto(acc.location.lat, acc.location.lng),
                    combinedPts
                )
                kmBelongsToDay(projKm, stage, isLastDay)
            } else {
                idx == 0
            }
            if (belongsToThisDay && dayWarnings.none { it.contains(acc.message) }) {
                dayWarnings.add(acc.message)
            }
        }
    }

    /** B6: path warnings overlapping [stage.startKm, stage.endKm]; SEASONAL_CLOSURE only when closed that day. */
    fun appendPathWarnings(
        state: RouteBuilderUiState,
        stage: TripStage,
        idx: Int,
        isLastDay: Boolean,
        dayEpochMs: Long,
        appLocale: Locale,
        localizedCtx: Context,
        dayWarnings: MutableList<String>
    ) {
        state.pathFeatureWarnings.forEach { pw ->
            val overlapsDay = pw.endKm >= stage.startKm - 1e-6 && pw.startKm <= stage.endKm + 1e-6 &&
                (idx == 0 || pw.endKm > stage.startKm + 1e-6 || kotlin.math.abs(pw.startKm - stage.startKm) < 1e-6) &&
                (isLastDay || pw.startKm < stage.endKm - 1e-6)
            if (!overlapsDay) return@forEach
            val kmLabel = com.example.ui.components.formatDanishKm(
                pw.startKm.coerceAtLeast(stage.startKm),
                decimals = 1,
                locale = appLocale
            )
            val rule = pw.closureRule
            val msg = if (pw.type == com.example.util.PathFeatureType.SEASONAL_CLOSURE && rule != null && !rule.isUnparseable) {
                if (!rule.isClosedOn(dayEpochMs)) return@forEach
                localizedCtx.getString(
                    com.example.R.string.day_seasonal_closure_on_date_fmt,
                    kmLabel,
                    rule.displayPeriod(appLocale.language)
                )
            } else {
                localizedCtx.getString(com.example.R.string.day_path_warning_km_fmt, kmLabel, pw.title)
            }
            if (dayWarnings.none { it == msg }) dayWarnings.add(msg)
        }
    }
}
