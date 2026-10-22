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

    /**
     * Builds a PlannedRoute directly from the composed legs of the unified Ruteplanlægger
     * (Requirement G: both TRAIL mode and FREE mode).
     * Preserves FERRY segments and gaps between WALK segments.
     */
    fun buildFromComposedLegs(
        planId: Long,
        trailId: String,
        trailName: String,
        isReverse: Boolean,
        waypoints: List<com.example.util.routing.RouteWaypoint>,
        orderedLegs: List<com.example.util.routing.LegGeometryData>,
        stages: List<TripStage> = emptyList()
    ): PlannedRoute = Companion.buildFromComposedLegs(
        planId = planId,
        trailId = trailId,
        trailName = trailName,
        isReverse = isReverse,
        waypoints = waypoints,
        orderedLegs = orderedLegs,
        stages = stages
    )

    fun buildStagesAndPlannedRoute(
        planId: Long,
        trailId: String,
        trailName: String,
        isReverse: Boolean,
        waypoints: List<com.example.util.routing.RouteWaypoint>,
        orderedLegs: List<com.example.util.routing.LegGeometryData>,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        numDays: Int? = null,
        overnightType: com.example.data.model.OvernightType = com.example.data.model.OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        shelters: List<com.example.data.model.ShelterEntity> = emptyList(),
        friTeltnings: List<com.example.data.model.FriTeltningEntity> = emptyList(),
        pois: List<com.example.data.model.PoiEntity> = emptyList(),
        sunsetTime: String? = null
    ): Pair<List<TripStage>, PlannedRoute> = Companion.buildStagesAndPlannedRoute(
        planId = planId,
        trailId = trailId,
        trailName = trailName,
        isReverse = isReverse,
        waypoints = waypoints,
        orderedLegs = orderedLegs,
        paceKmh = paceKmh,
        dailyTargetKm = dailyTargetKm,
        numDays = numDays,
        overnightType = overnightType,
        maxDetourKm = maxDetourKm,
        startTime = startTime,
        shelters = shelters,
        friTeltnings = friTeltnings,
        pois = pois,
        sunsetTime = sunsetTime
    )

    /**
     * Requirement G: Gem builds the TripPlan, the stages (from OVERNIGHT pins) and the PlannedRoute
     * in ONE place (PlannedRouteRepository) from the composed legs.
     */
    suspend fun saveUnifiedPlanAndRoute(
        existingRouteId: Long? = null,
        existingPlanId: Long? = null,
        name: String,
        baseTrailId: String? = null,
        isReverse: Boolean = false,
        optionsJson: String = "{}",
        waypoints: List<com.example.util.routing.RouteWaypoint>,
        legs: Map<String, com.example.util.routing.LegGeometryData>,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        overnightType: com.example.data.model.OvernightType = com.example.data.model.OvernightType.BOTH,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        startDateEpochMs: Long? = null,
        shelters: List<com.example.data.model.ShelterEntity> = emptyList(),
        friTeltnings: List<com.example.data.model.FriTeltningEntity> = emptyList(),
        pois: List<com.example.data.model.PoiEntity> = emptyList(),
        sunsetTime: String? = null
    ): Triple<Long, List<TripStage>, PlannedRoute> = withContext(Dispatchers.IO) {
        val db = com.example.data.local.DanskStiDatabase.getInstance(context)
        val dao = db.dao()

        val orderedLegs = mutableListOf<com.example.util.routing.LegGeometryData>()
        for (i in 0 until waypoints.size - 1) {
            val key = "${waypoints[i].id}_to_${waypoints[i + 1].id}"
            legs[key]?.let { orderedLegs.add(it) }
        }

        val stats = com.example.util.routing.RouteGeometryStorageManager.calculateStats(orderedLegs)
        val now = System.currentTimeMillis()
        val geomFile = if (existingRouteId != null && existingRouteId > 0L) {
            dao.getCustomRouteById(existingRouteId)?.geometryFile?.takeIf { it.isNotBlank() }
                ?: "route_${existingRouteId}.json"
        } else {
            "route_${java.util.UUID.randomUUID()}.json"
        }

        for (leg in orderedLegs) {
            com.example.util.routing.RouteGeometryStorageManager.saveLeg(context, geomFile, leg)
        }

        val customRoute = com.example.data.model.CustomRouteEntity(
            id = existingRouteId ?: 0L,
            name = name,
            createdAt = now,
            updatedAt = now,
            optionsJson = optionsJson,
            waypointsJson = com.example.util.routing.RouteWaypoint.listToJson(waypoints),
            totalKm = stats.totalKm,
            ascentM = stats.ascentM,
            descentM = stats.descentM,
            pathSharePct = stats.pathSharePct ?: 0.0,
            roadKm = stats.roadKm ?: 0.0,
            geometryFile = geomFile,
            sourceName = if (baseTrailId != null) "Ruteplanlægger (Sti)" else "Ruteplanlægger",
            fetchedAt = now,
            baseTrailId = baseTrailId,
            isReverse = isReverse
        )

        val savedRouteId = dao.insertCustomRoute(customRoute)
        val finalRouteId = if (savedRouteId > 0L) savedRouteId else (existingRouteId ?: 1L)
        val effectiveTrailId = baseTrailId ?: "route_$finalRouteId"

        val (stages, _) = buildStagesAndPlannedRoute(
            planId = existingPlanId ?: 0L,
            trailId = effectiveTrailId,
            trailName = name,
            isReverse = isReverse,
            waypoints = waypoints,
            orderedLegs = orderedLegs,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = sunsetTime
        )

        val stagesListType = com.squareup.moshi.Types.newParameterizedType(List::class.java, TripStage::class.java)
        val stagesJson = moshi.adapter<List<TripStage>>(stagesListType).toJson(stages)

        val startTrailKm = waypoints.firstOrNull { !it.isHelper }?.trailKm ?: 0.0
        val endTrailKm = waypoints.lastOrNull { !it.isHelper }?.trailKm ?: stats.totalKm

        val planEntity = com.example.data.model.TripPlanEntity(
            id = existingPlanId ?: 0L,
            trailId = effectiveTrailId,
            trailName = name,
            startKm = startTrailKm,
            endKm = endTrailKm,
            isReverse = isReverse,
            totalDistanceKm = stats.totalKm,
            targetDays = max(1, stages.size),
            overnightType = overnightType.name,
            preferredDailyKm = dailyTargetKm,
            stagesJson = stagesJson,
            createdAt = startDateEpochMs ?: now,
            paceKmh = paceKmh,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            customRouteId = finalRouteId
        )

        val insertedPlanId = dao.insertTripPlan(planEntity)
        val finalPlanId = if (insertedPlanId > 0L) insertedPlanId else (existingPlanId ?: 1L)

        dao.deleteTripStages(finalPlanId)
        dao.insertTripStages(stages.map { it.toEntity(finalPlanId) })

        val (_, finalPlannedRoute) = buildStagesAndPlannedRoute(
            planId = finalPlanId,
            trailId = effectiveTrailId,
            trailName = name,
            isReverse = isReverse,
            waypoints = waypoints,
            orderedLegs = orderedLegs,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = sunsetTime
        )
        savePlannedRoute(finalPlannedRoute)

        Triple(finalPlanId, stages, finalPlannedRoute)
    }

    companion object {
        private const val KEY_LAST_ACTIVE_PLAN_ID = "last_active_plan_id"

        fun buildFromComposedLegs(
            planId: Long,
            trailId: String,
            trailName: String,
            isReverse: Boolean,
            waypoints: List<com.example.util.routing.RouteWaypoint>,
            orderedLegs: List<com.example.util.routing.LegGeometryData>,
            stages: List<TripStage> = emptyList()
        ): PlannedRoute {
            val startWp = waypoints.firstOrNull { !it.isHelper }
            val endWp = waypoints.lastOrNull { !it.isHelper }
            val startTrailKm = startWp?.trailKm ?: 0.0

            val composedSegments = mutableListOf<TrailSegmentDto>()
            for (leg in orderedLegs) {
                if (leg.trailSegments.isNotEmpty()) {
                    composedSegments.addAll(leg.trailSegments)
                } else if (leg.points.isNotEmpty()) {
                    val isFerry = leg.legMode == com.example.util.routing.LegMode.STRAIGHT &&
                        leg.sourceName.contains("ferry", ignoreCase = true)
                    val segType = if (isFerry) TrailSegmentType.FERRY else TrailSegmentType.WALK
                    composedSegments.add(
                        TrailSegmentDto(
                            type = segType,
                            points = leg.points.map { GeoPointDto(it.lat, it.lng, it.ele) },
                            lengthKm = leg.lengthKm
                        )
                    )
                }
            }

            val routePoints = ArrayList<RoutePoint>()
            var cumulativeWalkM = 0.0

            for ((segIdx, seg) in composedSegments.withIndex()) {
                val pts = seg.points
                for (i in pts.indices) {
                    if (i > 0 && seg.type == TrailSegmentType.WALK) {
                        val prev = pts[i - 1]
                        val cur = pts[i]
                        cumulativeWalkM += GeoUtils.distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng)
                    }
                    val walkKm = cumulativeWalkM / 1000.0
                    routePoints.add(
                        RoutePoint(
                            lat = pts[i].lat,
                            lng = pts[i].lng,
                            ele = pts[i].ele,
                            walkKm = walkKm,
                            trailKm = walkKm,
                            segmentType = seg.type,
                            segmentIndex = segIdx
                        )
                    )
                }
            }

            val totalWalkKm = routePoints.lastOrNull()?.walkKm
                ?: orderedLegs.sumOf { it.lengthKm }
            val endTrailKm = endWp?.trailKm ?: totalWalkKm

            return PlannedRoute(
                planId = planId,
                trailId = trailId,
                trailName = trailName,
                isReverse = isReverse,
                startTrailKm = startTrailKm,
                endTrailKm = endTrailKm,
                totalWalkKm = totalWalkKm,
                points = routePoints,
                stages = stages
            )
        }

        fun buildStagesAndPlannedRoute(
            planId: Long,
            trailId: String,
            trailName: String,
            isReverse: Boolean,
            waypoints: List<com.example.util.routing.RouteWaypoint>,
            orderedLegs: List<com.example.util.routing.LegGeometryData>,
            paceKmh: Double = 4.0,
            dailyTargetKm: Double = 15.0,
            numDays: Int? = null,
            overnightType: com.example.data.model.OvernightType = com.example.data.model.OvernightType.BOTH,
            maxDetourKm: Double = 1.5,
            startTime: String = "09:00",
            shelters: List<com.example.data.model.ShelterEntity> = emptyList(),
            friTeltnings: List<com.example.data.model.FriTeltningEntity> = emptyList(),
            pois: List<com.example.data.model.PoiEntity> = emptyList(),
            sunsetTime: String? = null
        ): Pair<List<TripStage>, PlannedRoute> {
            val baseRoute = buildFromComposedLegs(
                planId = planId,
                trailId = trailId,
                trailName = trailName,
                isReverse = isReverse,
                waypoints = waypoints,
                orderedLegs = orderedLegs,
                stages = emptyList()
            )

            // Compute exact cumulative walkKm at each waypoint from orderedLegs
            val overnightWalkKms = mutableMapOf<String, Double>()
            var cumKm = 0.0
            if (waypoints.isNotEmpty()) {
                overnightWalkKms[waypoints[0].id] = 0.0
                for (i in 0 until waypoints.size - 1) {
                    val fromWp = waypoints[i]
                    val toWp = waypoints[i + 1]
                    val leg = orderedLegs.find { it.fromWaypointId == fromWp.id && it.toWaypointId == toWp.id }
                        ?: orderedLegs.getOrNull(i)
                    cumKm += (leg?.lengthKm ?: 0.0)
                    overnightWalkKms[toWp.id] = cumKm
                }
            }

            val overnightWaypoints = waypoints.filter {
                it.role == com.example.util.routing.WaypointRole.OVERNIGHT && !it.isHelper
            }
            val effectiveDays = numDays ?: max(1, overnightWaypoints.size + 1)

            val stages = com.example.util.StagePlanner.calculateStages(
                plannedRoute = baseRoute,
                trail = null,
                routeName = trailName,
                waypoints = waypoints,
                overnightWaypoints = overnightWaypoints,
                overnightWalkKms = overnightWalkKms,
                paceKmh = paceKmh,
                dailyTargetKm = dailyTargetKm,
                numDays = effectiveDays,
                overnightType = overnightType,
                maxDetourKm = maxDetourKm,
                startTime = startTime,
                shelters = shelters,
                friTeltnings = friTeltnings,
                pois = pois,
                sunsetTime = sunsetTime
            )

            val finalRoute = baseRoute.copy(stages = stages)
            return Pair(stages, finalRoute)
        }
    }
}
