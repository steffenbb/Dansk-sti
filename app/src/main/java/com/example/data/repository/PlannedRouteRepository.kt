package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.util.GeoUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlannedRouteRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val plannedRouteAdapter = moshi.adapter(PlannedRoute::class.java)

    private val plansDir: File
        get() = File(context.filesDir, "plans").apply { if (!exists()) mkdirs() }

    private val prefs by lazy {
        context.getSharedPreferences("dansk_sti_nav_prefs", Context.MODE_PRIVATE)
    }

    fun getPlanFile(planId: Long): File {
        return File(plansDir, "$planId.json.gz")
    }

    suspend fun savePlannedRoute(route: PlannedRoute): Boolean = withContext(Dispatchers.IO) {
        val file = getPlanFile(route.planId)
        try {
            file.outputStream().use { fos ->
                GZIPOutputStream(fos).use { gos ->
                    gos.sink().buffer().use { sink ->
                        plannedRouteAdapter.toJson(sink, route)
                    }
                }
            }
            // Save as last active plan
            saveLastActivePlanId(route.planId)
            true
        } catch (e: Exception) {
            Log.e("PlannedRouteRepo", "Failed to save planned route ${route.planId}", e)
            false
        }
    }

    suspend fun loadPlannedRoute(planId: Long): PlannedRoute? = withContext(Dispatchers.IO) {
        val file = getPlanFile(planId)
        if (!file.exists()) return@withContext null
        try {
            file.inputStream().use { fis ->
                GZIPInputStream(fis).use { gis ->
                    gis.source().buffer().use { source ->
                        plannedRouteAdapter.fromJson(source)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PlannedRouteRepo", "Failed to load planned route $planId", e)
            null
        }
    }

    fun saveLastActivePlanId(planId: Long) {
        prefs.edit().putLong(KEY_LAST_ACTIVE_PLAN_ID, planId).apply()
    }

    fun getLastActivePlanId(): Long {
        return prefs.getLong(KEY_LAST_ACTIVE_PLAN_ID, 0L)
    }

    fun clearLastActivePlanId() {
        prefs.edit().remove(KEY_LAST_ACTIVE_PLAN_ID).apply()
    }

    /**
     * Builds a PlannedRoute from the full trail geometry slice.
     * Slices the route from startKm to endKm in the direction of walking.
     * walkKm starts strictly at 0.0.
     * Maps walkKm <-> trailKm for each point.
     * Updates stage startKm and endKm to match walkKm.
     */
    fun buildPlannedRoute(
        planId: Long,
        trail: TrailEntity,
        rawTrailPoints: List<GeoPointDto>,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        stages: List<TripStage>
    ): PlannedRoute {
        if (rawTrailPoints.isEmpty()) {
            return PlannedRoute(
                planId = planId,
                trailId = trail.id,
                trailName = trail.name,
                isReverse = isReverse,
                startTrailKm = startKm,
                endTrailKm = endKm,
                totalWalkKm = 0.0,
                points = emptyList(),
                stages = stages
            )
        }

        // 1. Calculate cumulative trail distance for every raw point along full trail
        val trailDistances = DoubleArray(rawTrailPoints.size)
        var cumulativeTrailM = 0.0
        trailDistances[0] = 0.0
        for (i in 0 until rawTrailPoints.size - 1) {
            val d = GeoUtils.distanceMeters(
                rawTrailPoints[i].lat, rawTrailPoints[i].lng,
                rawTrailPoints[i + 1].lat, rawTrailPoints[i + 1].lng
            )
            if (d <= 8000.0) {
                cumulativeTrailM += d
            }
            trailDistances[i + 1] = cumulativeTrailM / 1000.0
        }

        val minTargetTrailKm = min(startKm, endKm)
        val maxTargetTrailKm = max(startKm, endKm)

        // Find slice of raw points intersecting [minTargetTrailKm, maxTargetTrailKm]
        var startIdx = 0
        var endIdx = rawTrailPoints.size - 1

        for (i in trailDistances.indices) {
            if (trailDistances[i] <= minTargetTrailKm) {
                startIdx = i
            }
            if (trailDistances[i] >= maxTargetTrailKm) {
                endIdx = i
                break
            }
        }

        if (endIdx <= startIdx) {
            endIdx = min(rawTrailPoints.size - 1, startIdx + 1)
        }

        val slicedRaw = rawTrailPoints.subList(startIdx, endIdx + 1)
        val slicedTrailDistances = trailDistances.slice(startIdx..endIdx)

        // If reverse, reverse the sliced points so direction of walking is forward
        val pointsInWalkOrder = if (isReverse) slicedRaw.reversed() else slicedRaw
        val trailKmInWalkOrder = if (isReverse) slicedTrailDistances.reversed() else slicedTrailDistances

        // Compute walkKm starting at 0.0
        val routePoints = ArrayList<RoutePoint>(pointsInWalkOrder.size)
        var cumulativeWalkM = 0.0

        for (i in pointsInWalkOrder.indices) {
            val pt = pointsInWalkOrder[i]
            if (i > 0) {
                val prev = pointsInWalkOrder[i - 1]
                val d = GeoUtils.distanceMeters(prev.lat, prev.lng, pt.lat, pt.lng)
                if (d <= 8000.0) {
                    cumulativeWalkM += d
                }
            }
            val currentWalkKm = cumulativeWalkM / 1000.0
            routePoints.add(
                RoutePoint(
                    lat = pt.lat,
                    lng = pt.lng,
                    ele = pt.ele,
                    walkKm = currentWalkKm,
                    trailKm = trailKmInWalkOrder[i],
                    segmentType = TrailSegmentType.WALK
                )
            )
        }

        val totalWalkKm = routePoints.lastOrNull()?.walkKm ?: 0.0

        // Adapt stages to walkKm (Day 1 starts at 0.0)
        val updatedStages = mutableListOf<TripStage>()
        var currentWalkStart = 0.0

        for (stage in stages) {
            val stageLength = max(0.1, stage.stageKm)
            val stageWalkEnd = min(totalWalkKm, currentWalkStart + stageLength)

            // Approximate corresponding trail km
            val startRatio = if (totalWalkKm > 0.0) (currentWalkStart / totalWalkKm).coerceIn(0.0, 1.0) else 0.0
            val endRatio = if (totalWalkKm > 0.0) (stageWalkEnd / totalWalkKm).coerceIn(0.0, 1.0) else 1.0

            val stageStartTrailKm = if (isReverse) {
                maxTargetTrailKm - startRatio * (maxTargetTrailKm - minTargetTrailKm)
            } else {
                minTargetTrailKm + startRatio * (maxTargetTrailKm - minTargetTrailKm)
            }

            val stageEndTrailKm = if (isReverse) {
                maxTargetTrailKm - endRatio * (maxTargetTrailKm - minTargetTrailKm)
            } else {
                minTargetTrailKm + endRatio * (maxTargetTrailKm - minTargetTrailKm)
            }

            updatedStages.add(
                stage.copy(
                    startKm = currentWalkStart,
                    endKm = stageWalkEnd,
                    stageKm = stageWalkEnd - currentWalkStart,
                    startTrailKm = stageStartTrailKm,
                    endTrailKm = stageEndTrailKm
                )
            )
            currentWalkStart = stageWalkEnd
        }

        return PlannedRoute(
            planId = planId,
            trailId = trail.id,
            trailName = trail.name,
            isReverse = isReverse,
            startTrailKm = startKm,
            endTrailKm = endKm,
            totalWalkKm = totalWalkKm,
            points = routePoints,
            stages = updatedStages
        )
    }

    companion object {
        private const val KEY_LAST_ACTIVE_PLAN_ID = "last_active_plan_id"
    }
}
