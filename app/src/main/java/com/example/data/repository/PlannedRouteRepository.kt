package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
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
     * Builds a PlannedRoute from trail segments.
     * Keeps segments separate end to end: points carry their real segmentType (WALK/FERRY) and segmentIndex.
     * Gaps between segments are never counted as walked km.
     * Stage ends use the walkKm of the camp the stage planner chose, not re-spaced stage lengths.
     */
    fun buildPlannedRoute(
        planId: Long,
        trail: TrailEntity,
        segments: List<TrailSegmentDto>,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        stages: List<TripStage>
    ): PlannedRoute {
        if (segments.isEmpty()) {
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

        data class TaggedPoint(
            val pt: GeoPointDto,
            val segIdx: Int,
            val segType: TrailSegmentType,
            val trailDistKm: Double
        )

        val taggedPoints = mutableListOf<TaggedPoint>()
        var cumulativeTrailM = 0.0

        for ((sIdx, seg) in segments.withIndex()) {
            val pts = seg.points
            if (pts.isEmpty()) continue

            for (i in pts.indices) {
                if (i > 0) {
                    val d = GeoUtils.distanceMeters(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
                    cumulativeTrailM += d
                }
                taggedPoints.add(
                    TaggedPoint(
                        pt = pts[i],
                        segIdx = sIdx,
                        segType = seg.type,
                        trailDistKm = cumulativeTrailM / 1000.0
                    )
                )
            }
        }

        val minTargetTrailKm = min(startKm, endKm)
        val maxTargetTrailKm = max(startKm, endKm)

        var startIdx = 0
        var endIdx = taggedPoints.size - 1

        for (i in taggedPoints.indices) {
            if (taggedPoints[i].trailDistKm <= minTargetTrailKm) {
                startIdx = i
            }
            if (taggedPoints[i].trailDistKm >= maxTargetTrailKm) {
                endIdx = i
                break
            }
        }

        if (endIdx <= startIdx) {
            endIdx = min(taggedPoints.size - 1, startIdx + 1)
        }

        val sliced = taggedPoints.subList(startIdx, endIdx + 1)
        val ordered = if (isReverse) sliced.reversed() else sliced

        // Calculate walkKm starting at 0.0
        // Gaps across segments are never counted as walked km
        // Ferry segments are not counted as walked km
        val routePoints = ArrayList<RoutePoint>(ordered.size)
        var cumulativeWalkM = 0.0

        for (i in ordered.indices) {
            val cur = ordered[i]
            if (i > 0) {
                val prev = ordered[i - 1]
                if (cur.segIdx == prev.segIdx && cur.segType == TrailSegmentType.WALK) {
                    val d = GeoUtils.distanceMeters(prev.pt.lat, prev.pt.lng, cur.pt.lat, cur.pt.lng)
                    cumulativeWalkM += d
                }
            }
            val currentWalkKm = cumulativeWalkM / 1000.0
            routePoints.add(
                RoutePoint(
                    lat = cur.pt.lat,
                    lng = cur.pt.lng,
                    ele = cur.pt.ele,
                    walkKm = currentWalkKm,
                    trailKm = cur.trailDistKm,
                    segmentType = cur.segType,
                    segmentIndex = cur.segIdx
                )
            )
        }

        val totalWalkKm = routePoints.lastOrNull()?.walkKm ?: 0.0

        // Stage ends use the walkKm of the camp the stage planner chose, not re-spaced stage lengths
        return PlannedRoute(
            planId = planId,
            trailId = trail.id,
            trailName = trail.name,
            isReverse = isReverse,
            startTrailKm = startKm,
            endTrailKm = endKm,
            totalWalkKm = totalWalkKm,
            points = routePoints,
            stages = stages
        )
    }

    companion object {
        private const val KEY_LAST_ACTIVE_PLAN_ID = "last_active_plan_id"
    }
}
