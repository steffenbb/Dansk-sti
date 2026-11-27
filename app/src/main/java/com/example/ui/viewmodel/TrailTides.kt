package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.repository.TideRepository
import com.example.util.PathFeatureType
import com.example.util.PathFeatureWarning
import com.example.util.TideRelevance

/** Phase E3: the trail's "Tidevand" card – today and tomorrow, only when relevant. */
data class TrailTideInfo(
    val stationName: String,
    val distanceKm: Double,
    val days: List<Pair<Int, String>>, // day offset (0 = today, 1 = tomorrow) to the tide line
    /** The same DMI high/low events per day offset, for the 24-hour timeline (UI audit batch 3). */
    val dayEvents: Map<Int, List<com.example.data.repository.TideEvent>> = emptyMap()
)

internal object TrailTides {
    suspend fun load(
        tideRepo: TideRepository,
        segments: List<TrailSegmentDto>,
        pathWarnings: List<PathFeatureWarning>,
        online: Boolean,
        nowMs: Long,
        context: Context
    ): TrailTideInfo? {
        val points = segments.filter { it.type == TrailSegmentType.WALK }.flatMap { it.points }
        if (points.isEmpty()) return null
        val nearest = TideRelevance.nearestStation(points, tideRepo.getStations(nowMs, allowNetwork = online)) ?: return null
        val hasTidal = pathWarnings.any { it.type == PathFeatureType.TIDAL }
        val dayEvents = HashMap<Int, List<com.example.data.repository.TideEvent>>()
        val days = (0..1).mapNotNull { offset ->
            val dayMs = com.example.util.CopenhagenTime.localMidnightPlus(nowMs, offset, 12 * 60)
            val events = tideRepo.getPredictions(nearest.station, dayMs, nowMs, allowNetwork = online)
            if (TideRelevance.isRelevant(events, hasTidal)) {
                dayEvents[offset] = events
                offset to TideRelevance.dayLine(events, context)
            } else null
        }
        if (days.isEmpty()) return null
        return TrailTideInfo(nearest.station.name, nearest.distanceKm, days, dayEvents)
    }
}
