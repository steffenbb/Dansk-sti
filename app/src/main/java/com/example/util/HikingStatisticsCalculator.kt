package com.example.util

import com.example.data.model.RecordedDayStatEntity
import com.example.data.model.RecordedTrackPointEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity

data class CompletedTrailStat(
    val planId: Long,
    val trailId: String,
    val trailName: String,
    val walkedKm: Double,
    val targetLengthKm: Double,
    val completedAtMillis: Long
)

data class HikingStatisticsSummary(
    val totalKmWalked: Double = 0.0,
    val hikingDaysCount: Int = 0,
    val totalAscentMeters: Int = 0,
    val completedTrails: List<CompletedTrailStat> = emptyList()
)

object HikingStatisticsCalculator {

    const val COMPLETION_THRESHOLD_RATIO = 0.90

    fun calculate(
        dayStats: List<RecordedDayStatEntity>,
        trackPoints: List<RecordedTrackPointEntity> = emptyList(),
        plans: List<TripPlanEntity> = emptyList(),
        progressList: List<TripProgressEntity> = emptyList(),
        trails: List<TrailEntity> = emptyList()
    ): HikingStatisticsSummary {
        val statKeys = dayStats.map { it.planId to it.dayNumber }.toSet()
        val orphanTrackGroups = trackPoints
            .groupBy { it.planId to it.dayNumber }
            .filterKeys { it !in statKeys }

        var extraTrackKm = 0.0
        var extraTrackAscentM = 0
        for ((_, pts) in orphanTrackGroups) {
            val sorted = pts.sortedBy { it.timestamp }
            for (i in 1 until sorted.size) {
                val p1 = sorted[i - 1]
                val p2 = sorted[i]
                extraTrackKm += GeoUtils.distanceMeters(p1.lat, p1.lng, p2.lat, p2.lng) / 1000.0
                val a1 = p1.altitude
                val a2 = p2.altitude
                if (a1 != null && a2 != null && (a2 - a1) > 1.5) {
                    extraTrackAscentM += (a2 - a1).toInt()
                }
            }
        }

        val totalKm = dayStats.sumOf { it.distanceKm } + extraTrackKm
        val totalAscent = dayStats.sumOf { it.ascentMeters } + extraTrackAscentM

        val activeDayKeys = buildSet {
            dayStats.filter { it.distanceKm > 0.0 || it.pointCount > 0 }.forEach {
                add(it.planId to it.dayNumber)
            }
            orphanTrackGroups.filter { it.value.isNotEmpty() }.keys.forEach {
                add(it)
            }
        }

        val plansById = plans.associateBy { it.id }
        val trailsById = trails.associateBy { it.id }
        val dayStatsByPlan = dayStats.groupBy { it.planId }
        val orphanKmByPlan = orphanTrackGroups.entries.groupBy(
            keySelector = { it.key.first },
            valueTransform = { (_, pts) ->
                val sorted = pts.sortedBy { it.timestamp }
                var d = 0.0
                for (i in 1 until sorted.size) {
                    d += GeoUtils.distanceMeters(
                        sorted[i - 1].lat,
                        sorted[i - 1].lng,
                        sorted[i].lat,
                        sorted[i].lng
                    ) / 1000.0
                }
                d
            }
        ).mapValues { (_, list) -> list.sum() }

        val completed = mutableListOf<CompletedTrailStat>()
        for (prog in progressList) {
            if (!prog.isCompleted) continue
            val plan = plansById[prog.planId]
            val trail = plan?.trailId?.let { trailsById[it] }
            val targetLengthKm = when {
                trail != null && trail.lengthKm > 0.0 -> trail.lengthKm
                plan != null && plan.totalDistanceKm > 0.0 -> plan.totalDistanceKm
                else -> 0.0
            }
            if (targetLengthKm <= 0.0) continue

            val recordedKmForPlan = (dayStatsByPlan[prog.planId]?.sumOf { it.distanceKm } ?: 0.0) +
                (orphanKmByPlan[prog.planId] ?: 0.0)
            val walkedKm = maxOf(prog.totalDistanceWalkedKm, recordedKmForPlan)

            if (walkedKm >= COMPLETION_THRESHOLD_RATIO * targetLengthKm) {
                val latestDayMillis = dayStatsByPlan[prog.planId]?.maxOfOrNull { it.lastUpdatedMillis } ?: 0L
                val completedAt = if (prog.lastActiveTimestamp > 0L) prog.lastActiveTimestamp else latestDayMillis
                completed.add(
                    CompletedTrailStat(
                        planId = prog.planId,
                        trailId = trail?.id ?: plan?.trailId.orEmpty(),
                        trailName = trail?.name ?: plan?.trailName.orEmpty(),
                        walkedKm = walkedKm,
                        targetLengthKm = targetLengthKm,
                        completedAtMillis = completedAt
                    )
                )
            }
        }

        return HikingStatisticsSummary(
            totalKmWalked = totalKm,
            hikingDaysCount = activeDayKeys.size,
            totalAscentMeters = totalAscent,
            completedTrails = completed.sortedByDescending { it.completedAtMillis }
        )
    }
}
