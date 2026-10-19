package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.DanskStiDao
import com.example.data.local.PreseededData
import com.example.data.model.DanishEmergencyInfo
import com.example.data.model.FriTeltningEntity
import com.example.data.model.GearChecklistItem
import com.example.data.model.GeoPointDto
import com.example.data.model.HazardType
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNoteEntity
import com.example.data.remote.OverpassApiService
import com.example.data.remote.TrailWeatherInfo
import com.example.data.remote.WeatherApiService
import com.example.data.remote.WeatherInterpreter
import com.example.util.GeoUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.data.model.PlannedRoute
import com.example.util.StagePlanner
import kotlin.math.abs
import kotlin.math.max

class DanskStiRepository(
    private val dao: DanskStiDao,
    private val context: Context,
    private val apiService: OverpassApiService = OverpassApiService.create(),
    private val weatherService: WeatherApiService = WeatherApiService.create()
) {
    private val moshi = Moshi.Builder().build()
    private val stageListType = Types.newParameterizedType(List::class.java, TripStage::class.java)
    private val stageAdapter = moshi.adapter<List<TripStage>>(stageListType)

    val geoFaRepository = GeoFaRepository(context)
    val osmPoiRepository = OsmPoiRepository(context)
    val geometryRepository = TrailGeometryRepository(context)
    val markedRouteRepository = MarkedRouteRepository(context, dao, geometryRepository)

    private val initMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun initializePreseededDataIfEmpty() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            try {
                val trailCount = dao.getTrailCount()
                if (trailCount == 0) {
                    dao.insertTrails(PreseededData.getInitialTrails())
                    Log.d("DanskStiRepo", "Preseeded ${PreseededData.getInitialTrails().size} initial trails inserted into Room")
                }

                // Ensure GeoFA data is loaded into Room
                val shelterCount = dao.getShelterCount()
                if (shelterCount == 0) {
                    try {
                        // First load from pre-bundled assets for instant, reliable startup
                        var shelters = geoFaRepository.loadBundledShelters()
                        var friTeltnings = geoFaRepository.loadBundledFriTeltning()
                        var pois = geoFaRepository.loadBundledPois()

                        // If bundled files were empty or unavailable, fall back to online fetch
                        if (shelters.isEmpty()) {
                            shelters = geoFaRepository.fetchGeoFaShelters()
                        }
                        if (friTeltnings.isEmpty()) {
                            friTeltnings = geoFaRepository.fetchGeoFaFriTeltning()
                        }
                        if (pois.isEmpty()) {
                            pois = geoFaRepository.fetchGeoFaPois()
                        }

                        val waterPois = pois.filter { it.type == "water" }
                        val toiletPois = pois.filter { it.type == "toilet" }
                        
                        // Optimized distance checking with bounding-box pre-filtering (~0.005 lat, ~0.008 lng is ~500m in Denmark)
                        val computedShelters = shelters.map { sh ->
                            val hasWater = waterPois.any { w ->
                                kotlin.math.abs(w.lat - sh.lat) < 0.005 &&
                                kotlin.math.abs(w.lng - sh.lng) < 0.008 &&
                                GeoUtils.distanceMeters(sh.lat, sh.lng, w.lat, w.lng) <= 500.0
                            }
                            val hasToilet = toiletPois.any { t ->
                                kotlin.math.abs(t.lat - sh.lat) < 0.005 &&
                                kotlin.math.abs(t.lng - sh.lng) < 0.008 &&
                                GeoUtils.distanceMeters(sh.lat, sh.lng, t.lat, t.lng) <= 500.0
                            }
                            sh.copy(hasWaterNearby = hasWater, hasToiletNearby = hasToilet)
                        }

                        // Chunked database inserts to prevent SQLite binder transaction overflows
                        if (computedShelters.isNotEmpty()) {
                            computedShelters.chunked(250).forEach { dao.insertShelters(it) }
                        }
                        if (friTeltnings.isNotEmpty()) {
                            friTeltnings.chunked(100).forEach { dao.insertFriTeltning(it) }
                        }
                        if (pois.isNotEmpty()) {
                            pois.chunked(250).forEach { dao.insertPois(it) }
                        }
                        Log.d("DanskStiRepo", "GeoFA initial data loaded: ${computedShelters.size} shelters, ${friTeltnings.size} fri teltning, ${pois.size} pois")
                    } catch (t: Throwable) {
                        Log.e("DanskStiRepo", "Failed to load initial GeoFA data: ${t.message}", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e("DanskStiRepo", "Error in initializePreseededDataIfEmpty: ${t.message}", t)
            }
        }
    }

    fun getAllTrails(): Flow<List<TrailEntity>> = dao.getAllTrails()

    suspend fun getTrailById(id: String): TrailEntity? = withContext(Dispatchers.IO) {
        val trail = dao.getTrailById(id)
        if (trail != null) return@withContext trail

        // Custom route support: custom routes work everywhere a trail works (Phase 11 Requirement E)
        val cleanRouteId = id.removePrefix("custom_route_").removePrefix("route_").toLongOrNull()
        if (cleanRouteId != null) {
            val cr = dao.getCustomRouteById(cleanRouteId)
            if (cr != null) {
                return@withContext TrailEntity(
                    id = "route_${cr.id}",
                    name = cr.name,
                    description = "Brugerskabt rute (${String.format(java.util.Locale.US, "%.1f", cr.totalKm)} km)",
                    region = "Danmark",
                    parentRegion = "Danmark",
                    computedWalkingKm = cr.totalKm,
                    isGeometryDownloaded = true
                )
            }
        }
        null
    }

    suspend fun getTrailGeometry(trailId: String): com.example.data.model.TrailGeometryDto? = withContext(Dispatchers.IO) {
        geometryRepository.loadGeometry(trailId)
    }

    suspend fun getTrailPoints(trail: TrailEntity): List<TrailSegmentDto> = withContext(Dispatchers.IO) {
        val geom = try {
            geometryRepository.loadGeometry(trail.id)
                ?: if (trail.id.startsWith("route_")) {
                    val crId = trail.id.removePrefix("route_").toLongOrNull()
                    val cr = if (crId != null) dao.getCustomRouteById(crId) else null
                    if (cr != null) {
                        val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, cr.geometryFile)
                        val wps = com.example.util.routing.RouteWaypoint.listFromJson(cr.waypointsJson)
                        val orderedLegs = mutableListOf<com.example.util.routing.LegGeometryData>()
                        for (i in 0 until wps.size - 1) {
                            val key = "${wps[i].id}_to_${wps[i + 1].id}"
                            legs[key]?.let { orderedLegs.add(it) }
                        }
                        val customGeom = com.example.util.routing.RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(trail.id, orderedLegs, cr.name)
                        geometryRepository.saveRouteGeometry(trail.id, customGeom)
                        customGeom
                    } else null
                } else {
                    geometryRepository.fetchAndProcessTrail(trail.id, trail.name, trail.osmRelationId)?.also {
                        geometryRepository.saveGeometry(it)
                        dao.insertTrail(trail.copy(
                            computedWalkingKm = it.totalWalkingKm,
                            gapsOver200m = it.gapsOver200mCount,
                            osmTimestamp = it.osmTimestamp,
                            bboxNorth = it.bboxNorth,
                            bboxSouth = it.bboxSouth,
                            bboxEast = it.bboxEast,
                            bboxWest = it.bboxWest,
                            isGeometryDownloaded = true
                        ))
                    }
                }
        } catch (t: Throwable) {
            Log.e("DanskStiRepo", "Error getting trail points for ${trail.name}: ${t.message}", t)
            null
        }
        geom?.segments ?: emptyList()
    }

    fun parseStages(json: String): List<TripStage> {
        return try {
            stageAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeStages(stages: List<TripStage>): String {
        return stageAdapter.toJson(stages)
    }

    suspend fun getTrailWeather(lat: Double, lng: Double): TrailWeatherInfo = withContext(Dispatchers.IO) {
        try {
            val response = weatherService.getTrailWeather(lat, lng)
            WeatherInterpreter.parseWeather(response)
        } catch (e: Exception) {
            Log.w("DanskStiRepo", "Could not fetch live weather: ${e.message}")
            TrailWeatherInfo()
        }
    }

    // --- Corridor POI sync & attachment ---
    suspend fun syncCorridorPois(trail: TrailEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val points = getTrailPoints(trail).flatMap { it.points }
            if (points.isEmpty()) return@withContext Result.success(Unit)

            // If this is a custom route, delegate to syncCorridorPoisForRoute (Phase 11 Requirement B)
            if (trail.id.startsWith("route_")) {
                val cleanRouteId = trail.id.removePrefix("route_")
                return@withContext syncCorridorPoisForRoute(cleanRouteId, points)
            }

            // 1. Ensure GeoFA datasets are in Room
            if (dao.getShelterCount() == 0) {
                val gfShelters = geoFaRepository.fetchGeoFaShelters()
                val gfFri = geoFaRepository.fetchGeoFaFriTeltning()
                val gfPois = geoFaRepository.fetchGeoFaPois()
                if (gfShelters.isNotEmpty()) dao.insertShelters(gfShelters)
                if (gfFri.isNotEmpty()) dao.insertFriTeltning(gfFri)
                if (gfPois.isNotEmpty()) dao.insertPois(gfPois)
            }

            // 2. Fetch OSM POIs for this trail
            val relationId = trail.osmRelationId ?: 0L
            val childIds = if (trail.id == "trail_ohavsstien") {
                listOf(4591923L, 4591924L, 4591925L, 4591926L, 4591927L, 4591928L, 4589243L)
            } else {
                emptyList()
            }
            val osmResult = osmPoiRepository.fetchPoisForTrail(trail.id, relationId, childIds)

            // 3. De-duplicate GeoFA vs OSM items within 50 m
            val existingShelters = dao.getAllSheltersList().toMutableList()
            val existingPois = dao.getAllPoisList().toMutableList()

            val updatedShelters = mutableListOf<ShelterEntity>()
            val newShelters = mutableListOf<ShelterEntity>()

            for (osmSh in osmResult.shelters) {
                val matchIdx = existingShelters.indexOfFirst { gf ->
                    GeoUtils.distanceMeters(gf.lat, gf.lng, osmSh.lat, osmSh.lng) <= 50.0
                }
                if (matchIdx >= 0) {
                    val gf = existingShelters[matchIdx]
                    val merged = gf.copy(
                        description = if (gf.description.isNullOrBlank() || gf.description == "Ukendt") osmSh.description ?: gf.description else gf.description,
                        bookingUrl = gf.bookingUrl ?: osmSh.bookingUrl,
                        ownership = gf.ownership ?: osmSh.ownership,
                        capacity = gf.capacity ?: osmSh.capacity,
                        shelterType = if (gf.shelterType == "Shelter" && !osmSh.shelterType.isNullOrBlank()) osmSh.shelterType else gf.shelterType
                    )
                    existingShelters[matchIdx] = merged
                    updatedShelters.add(merged)
                } else {
                    newShelters.add(osmSh)
                    existingShelters.add(osmSh)
                }
            }

            val updatedPois = mutableListOf<PoiEntity>()
            val newPois = mutableListOf<PoiEntity>()

            for (osmPoi in osmResult.pois) {
                val matchIdx = existingPois.indexOfFirst { gf ->
                    gf.type == osmPoi.type && GeoUtils.distanceMeters(gf.lat, gf.lng, osmPoi.lat, osmPoi.lng) <= 50.0
                }
                if (matchIdx >= 0) {
                    val gf = existingPois[matchIdx]
                    val merged = gf.copy(
                        openingHours = gf.openingHours ?: osmPoi.openingHours,
                        operator = gf.operator ?: osmPoi.operator,
                        website = gf.website ?: osmPoi.website
                    )
                    existingPois[matchIdx] = merged
                    updatedPois.add(merged)
                } else {
                    newPois.add(osmPoi)
                    existingPois.add(osmPoi)
                }
            }

            if (updatedShelters.isNotEmpty()) dao.insertShelters(updatedShelters)
            if (newShelters.isNotEmpty()) dao.insertShelters(newShelters)
            if (updatedPois.isNotEmpty()) dao.insertPois(updatedPois)
            if (newPois.isNotEmpty()) dao.insertPois(newPois)

            // 4. Compute hasWaterNearby / hasToiletNearby on shelters within 500m
            val allWaterPois = existingPois.filter { it.type == "water" }
            val allToiletPois = existingPois.filter { it.type == "toilet" }

            val reevaluatedShelters = mutableListOf<ShelterEntity>()
            for (sh in existingShelters) {
                val hasWater = allWaterPois.any { GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0 }
                val hasToilet = allToiletPois.any { GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0 }
                if (sh.hasWaterNearby != hasWater || sh.hasToiletNearby != hasToilet) {
                    reevaluatedShelters.add(sh.copy(hasWaterNearby = hasWater, hasToiletNearby = hasToilet))
                }
            }
            if (reevaluatedShelters.isNotEmpty()) {
                dao.insertShelters(reevaluatedShelters)
            }

            // 5. Store per trail in join table TrailPoi(trailId, poiId, kmOnRoute, detourMeters)
            val trailPoiEntities = mutableListOf<com.example.data.model.TrailPoiEntity>()

            for (sh in existingShelters) {
                val (kmOnRoute, detourMeters) = GeoUtils.projectPointOnPolyline(GeoPointDto(sh.lat, sh.lng), points)
                if (detourMeters <= 3000.0) {
                    trailPoiEntities.add(
                        com.example.data.model.TrailPoiEntity(
                            trailId = trail.id,
                            poiId = sh.id,
                            kmOnRoute = kmOnRoute,
                            detourMeters = detourMeters
                        )
                    )
                }
            }

            for (poi in existingPois) {
                val (kmOnRoute, detourMeters) = GeoUtils.projectPointOnPolyline(GeoPointDto(poi.lat, poi.lng), points)
                if (detourMeters <= 3000.0) {
                    trailPoiEntities.add(
                        com.example.data.model.TrailPoiEntity(
                            trailId = trail.id,
                            poiId = poi.id,
                            kmOnRoute = kmOnRoute,
                            detourMeters = detourMeters
                        )
                    )
                }
            }

            if (trailPoiEntities.isNotEmpty()) {
                dao.insertTrailPois(trailPoiEntities)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error syncing corridor POIs: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Requirement B: Sync POIs along a custom route polyline using GeoFA and Overpass around corridor.
     * Projects onto route and stores in TrailPoi with routeId.
     */
    suspend fun syncCorridorPoisForRoute(routeId: String, points: List<GeoPointDto>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (points.isEmpty()) return@withContext Result.success(Unit)

            // 1. Ensure GeoFA datasets are in Room
            if (dao.getShelterCount() == 0) {
                val gfShelters = geoFaRepository.fetchGeoFaShelters()
                val gfFri = geoFaRepository.fetchGeoFaFriTeltning()
                val gfPois = geoFaRepository.fetchGeoFaPois()
                if (gfShelters.isNotEmpty()) dao.insertShelters(gfShelters)
                if (gfFri.isNotEmpty()) dao.insertFriTeltning(gfFri)
                if (gfPois.isNotEmpty()) dao.insertPois(gfPois)
            }

            // 2. Fetch OSM POIs along route polyline (Phase 11 Requirement B)
            val osmResult = osmPoiRepository.fetchPoisForPolyline(points)

            // 3. De-duplicate GeoFA vs OSM items within 50 m
            val existingShelters = dao.getAllSheltersList().toMutableList()
            val existingPois = dao.getAllPoisList().toMutableList()

            val updatedShelters = mutableListOf<ShelterEntity>()
            val newShelters = mutableListOf<ShelterEntity>()

            for (osmSh in osmResult.shelters) {
                val matchIdx = existingShelters.indexOfFirst { gf ->
                    GeoUtils.distanceMeters(gf.lat, gf.lng, osmSh.lat, osmSh.lng) <= 50.0
                }
                if (matchIdx >= 0) {
                    val gf = existingShelters[matchIdx]
                    val merged = gf.copy(
                        description = if (gf.description.isNullOrBlank() || gf.description == "Ukendt") osmSh.description ?: gf.description else gf.description,
                        bookingUrl = gf.bookingUrl ?: osmSh.bookingUrl,
                        ownership = gf.ownership ?: osmSh.ownership,
                        capacity = gf.capacity ?: osmSh.capacity,
                        shelterType = if (gf.shelterType == "Shelter" && !osmSh.shelterType.isNullOrBlank()) osmSh.shelterType else gf.shelterType
                    )
                    existingShelters[matchIdx] = merged
                    updatedShelters.add(merged)
                } else {
                    newShelters.add(osmSh)
                    existingShelters.add(osmSh)
                }
            }

            val updatedPois = mutableListOf<PoiEntity>()
            val newPois = mutableListOf<PoiEntity>()

            for (osmPoi in osmResult.pois) {
                val matchIdx = existingPois.indexOfFirst { gf ->
                    gf.type == osmPoi.type && GeoUtils.distanceMeters(gf.lat, gf.lng, osmPoi.lat, osmPoi.lng) <= 50.0
                }
                if (matchIdx >= 0) {
                    val gf = existingPois[matchIdx]
                    val merged = gf.copy(
                        openingHours = gf.openingHours ?: osmPoi.openingHours,
                        operator = gf.operator ?: osmPoi.operator,
                        website = gf.website ?: osmPoi.website
                    )
                    existingPois[matchIdx] = merged
                    updatedPois.add(merged)
                } else {
                    newPois.add(osmPoi)
                    existingPois.add(osmPoi)
                }
            }

            if (updatedShelters.isNotEmpty()) dao.insertShelters(updatedShelters)
            if (newShelters.isNotEmpty()) dao.insertShelters(newShelters)
            if (updatedPois.isNotEmpty()) dao.insertPois(updatedPois)
            if (newPois.isNotEmpty()) dao.insertPois(newPois)

            // 4. Compute hasWaterNearby / hasToiletNearby on shelters within 500m
            val allWaterPois = existingPois.filter { it.type == "water" }
            val allToiletPois = existingPois.filter { it.type == "toilet" }

            val reevaluatedShelters = mutableListOf<ShelterEntity>()
            for (sh in existingShelters) {
                val hasWater = allWaterPois.any { GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0 }
                val hasToilet = allToiletPois.any { GeoUtils.distanceMeters(sh.lat, sh.lng, it.lat, it.lng) <= 500.0 }
                if (sh.hasWaterNearby != hasWater || sh.hasToiletNearby != hasToilet) {
                    reevaluatedShelters.add(sh.copy(hasWaterNearby = hasWater, hasToiletNearby = hasToilet))
                }
            }
            if (reevaluatedShelters.isNotEmpty()) {
                dao.insertShelters(reevaluatedShelters)
            }

            // 5. Store in TrailPoiEntity with routeId (Phase 11 Requirement B)
            val trailPoiEntities = mutableListOf<com.example.data.model.TrailPoiEntity>()

            for (sh in existingShelters) {
                val (kmOnRoute, detourMeters) = GeoUtils.projectPointOnPolyline(GeoPointDto(sh.lat, sh.lng), points)
                if (detourMeters <= 3000.0) {
                    trailPoiEntities.add(
                        com.example.data.model.TrailPoiEntity(
                            trailId = "route_$routeId",
                            poiId = sh.id,
                            kmOnRoute = kmOnRoute,
                            detourMeters = detourMeters,
                            routeId = routeId
                        )
                    )
                }
            }

            for (poi in existingPois) {
                val (kmOnRoute, detourMeters) = GeoUtils.projectPointOnPolyline(GeoPointDto(poi.lat, poi.lng), points)
                if (detourMeters <= 3000.0) {
                    trailPoiEntities.add(
                        com.example.data.model.TrailPoiEntity(
                            trailId = "route_$routeId",
                            poiId = poi.id,
                            kmOnRoute = kmOnRoute,
                            detourMeters = detourMeters,
                            routeId = routeId
                        )
                    )
                }
            }

            if (trailPoiEntities.isNotEmpty()) {
                dao.deletePoisForRoute(routeId)
                dao.insertTrailPois(trailPoiEntities)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error syncing corridor POIs for route $routeId: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- Shelters, Fri Teltning, and POIs for Trail Corridor ---
    suspend fun getSheltersForTrail(trailId: String): List<ShelterEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getSheltersForTrail(trailId)
        if (corridor.isNotEmpty()) corridor else dao.getAllSheltersList()
    }

    suspend fun getSheltersForRoute(routeId: String, maxDetour: Double = 3000.0): List<ShelterEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getSheltersForRoute(routeId, maxDetour)
        if (corridor.isNotEmpty()) corridor else dao.getAllSheltersList()
    }

    suspend fun getFriTeltningForTrail(trailId: String): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        dao.getAllFriTeltningList()
    }

    suspend fun getPoisForTrail(trailId: String): List<PoiEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getPoisForTrail(trailId)
        if (corridor.isNotEmpty()) corridor else dao.getAllPoisList()
    }

    suspend fun getPoisForRoute(routeId: String, maxDetour: Double = 3000.0): List<PoiEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getPoisForRoute(routeId, maxDetour)
        if (corridor.isNotEmpty()) corridor else dao.getAllPoisList()
    }

    // --- Save and Manage Plans in Room ---
    fun getDefaultGearItems(overnightTypeStr: String): List<GearChecklistItem> {
        val type = try {
            OvernightType.valueOf(overnightTypeStr)
        } catch (_: Exception) {
            OvernightType.BOTH
        }
        return getDefaultGearChecklist(type, 12.0)
    }

    suspend fun saveTripPlan(
        trail: TrailEntity,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        days: Int,
        overnightType: OvernightType,
        preferredDailyKm: Double,
        stages: List<TripStage>,
        paceKmh: Double = 4.0,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00"
    ): Long = withContext(Dispatchers.IO) {
        val totalDist = if (stages.isNotEmpty()) stages.sumOf { it.stageKm } else abs(endKm - startKm)
        val plan = TripPlanEntity(
            trailId = trail.id,
            trailName = trail.name,
            startKm = startKm,
            endKm = endKm,
            isReverse = isReverse,
            totalDistanceKm = totalDist,
            targetDays = days,
            overnightType = overnightType.name,
            preferredDailyKm = preferredDailyKm,
            stagesJson = serializeStages(stages),
            paceKmh = paceKmh,
            maxDetourKm = maxDetourKm,
            startTime = startTime
        )
        val planId = dao.insertTripPlan(plan)
        if (stages.isNotEmpty()) {
            val stageEntities = stages.map { it.toEntity(planId) }
            dao.insertTripStages(stageEntities)
        }
        val defaultGear = getDefaultGearItems(plan.overnightType).map {
            com.example.data.model.TripGearItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                planId = planId,
                category = it.category,
                title = it.title,
                isPacked = false
            )
        }
        dao.insertGearItems(defaultGear)
        planId
    }

    suspend fun getAllSheltersList(): List<ShelterEntity> = withContext(Dispatchers.IO) {
        dao.getAllSheltersList()
    }

    suspend fun getAllFriTeltningList(): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        dao.getAllFriTeltningList()
    }

    suspend fun getPoisByType(type: String): List<PoiEntity> = withContext(Dispatchers.IO) {
        dao.getPoisByType(type)
    }

    suspend fun getAllPoisList(): List<PoiEntity> = withContext(Dispatchers.IO) {
        dao.getAllPoisList()
    }

    suspend fun getAllFriTeltningDirect(): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        dao.getAllFriTeltningDirect()
    }

    /**
     * Requirement D: Saves a trip plan for a custom route.
     */
    suspend fun saveTripPlanForRoute(
        routeId: String,
        routeName: String,
        totalKm: Double,
        days: Int,
        overnightType: OvernightType,
        stages: List<TripStage>,
        paceKmh: Double = 4.0,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00"
    ): Long = withContext(Dispatchers.IO) {
        val totalDist = if (stages.isNotEmpty()) stages.sumOf { it.stageKm } else totalKm
        val plan = TripPlanEntity(
            trailId = "route_$routeId",
            trailName = routeName,
            startKm = 0.0,
            endKm = totalDist,
            isReverse = false,
            totalDistanceKm = totalDist,
            targetDays = days,
            overnightType = overnightType.name,
            preferredDailyKm = totalDist / max(1, days),
            stagesJson = serializeStages(stages),
            paceKmh = paceKmh,
            maxDetourKm = maxDetourKm,
            startTime = startTime
        )
        val planId = dao.insertTripPlan(plan)
        if (stages.isNotEmpty()) {
            val stageEntities = stages.map { it.toEntity(planId) }
            dao.insertTripStages(stageEntities)
        }
        val defaultGear = getDefaultGearItems(plan.overnightType).map {
            com.example.data.model.TripGearItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                planId = planId,
                category = it.category,
                title = it.title,
                isPacked = false
            )
        }
        dao.insertGearItems(defaultGear)
        planId
    }

    /**
     * Ensures a custom route is saved as a TripPlan and PlannedRoute so it can be navigated.
     */
    suspend fun ensureTripPlanForCustomRoute(
        route: com.example.data.model.CustomRouteEntity,
        forceRecompute: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val existingPlans = dao.getAllTripPlansList().filter { it.trailId == "route_${route.id}" || it.trailId == route.id.toString() }
        if (!forceRecompute && existingPlans.isNotEmpty()) {
            return@withContext existingPlans.first().id
        }
        if (forceRecompute && existingPlans.isNotEmpty()) {
            existingPlans.forEach { dao.deleteTripPlan(it.id) }
        }

        val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, route.geometryFile)
        val orderedLegs = mutableListOf<com.example.util.routing.LegGeometryData>()
        val wps = com.example.util.routing.RouteWaypoint.listFromJson(route.waypointsJson)
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            legs[key]?.let { orderedLegs.add(it) }
        }

        // Save geometry to routes/<id>.json.gz (Requirement A)
        val geom = com.example.util.routing.RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(route.id.toString(), orderedLegs, route.name)
        geometryRepository.saveRouteGeometry(route.id.toString(), geom)

        val points = orderedLegs.flatMap { it.points }.map { GeoPointDto(it.lat, it.lng, it.ele) }
        val plannedPoints = points.mapIndexed { idx, pt ->
            com.example.data.model.RoutePoint(
                lat = pt.lat,
                lng = pt.lng,
                ele = pt.ele,
                walkKm = (idx.toDouble() / max(1, points.size - 1)) * route.totalKm,
                trailKm = (idx.toDouble() / max(1, points.size - 1)) * route.totalKm
            )
        }
        val overnightWps = wps.filter { it.role == com.example.util.routing.WaypointRole.OVERNIGHT }
        val days = if (overnightWps.isNotEmpty()) overnightWps.size + 1 else max(1, kotlin.math.ceil(route.totalKm / 15.0).toInt())

        val plannedRoute = PlannedRoute(
            planId = 0L,
            trailId = "route_${route.id}",
            trailName = route.name,
            isReverse = false,
            startTrailKm = 0.0,
            endTrailKm = route.totalKm,
            totalWalkKm = route.totalKm,
            points = plannedPoints,
            stages = emptyList<com.example.data.model.TripStage>()
        )

        // Sync corridor POIs
        syncCorridorPoisForRoute(route.id.toString(), points)

        val stages = StagePlanner.calculateStagesForCustomRoute(
            plannedRoute = plannedRoute,
            routeName = route.name,
            waypoints = wps,
            overnightWaypoints = overnightWps,
            dailyTargetKm = 15.0,
            numDays = days,
            shelters = dao.getAllSheltersList(),
            friTeltnings = dao.getAllFriTeltningList(),
            pois = dao.getAllPoisList()
        )

        val planId = saveTripPlanForRoute(
            routeId = route.id.toString(),
            routeName = route.name,
            totalKm = route.totalKm,
            days = days,
            overnightType = OvernightType.BOTH,
            stages = stages
        )

        val fullPlannedRoute = plannedRoute.copy(planId = planId, stages = stages)
        PlannedRouteRepository(context).savePlannedRoute(fullPlannedRoute)

        planId
    }

    fun getAllTripPlans(): Flow<List<TripPlanEntity>> = dao.getAllTripPlans()

    suspend fun getTripPlanById(id: Long): TripPlanEntity? = withContext(Dispatchers.IO) {
        dao.getTripPlanById(id)
    }

    suspend fun getLatestTripPlanForTrail(trailId: String): TripPlanEntity? = withContext(Dispatchers.IO) {
        dao.getLatestTripPlanForTrail(trailId)
    }

    suspend fun getStagesForPlan(planId: Long): List<TripStage> = withContext(Dispatchers.IO) {
        val stageEntities = dao.getTripStagesForPlanSync(planId)
        if (stageEntities.isNotEmpty()) {
            stageEntities.map { it.toTripStage() }
        } else {
            val plan = dao.getTripPlanById(planId)
            if (plan != null && plan.stagesJson.isNotBlank()) {
                parseStages(plan.stagesJson)
            } else {
                emptyList()
            }
        }
    }

    suspend fun deleteTripPlan(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteTripPlan(id)
        dao.deleteTripStages(id)
        dao.deleteTripProgress(id)
    }

    // --- Offline Downloaded Trails ---
    fun getAllDownloadedTrails(): Flow<List<com.example.data.model.DownloadedTrailEntity>> = dao.getAllDownloadedTrails()

    suspend fun getDownloadedTrail(trailId: String): com.example.data.model.DownloadedTrailEntity? = withContext(Dispatchers.IO) {
        dao.getDownloadedTrail(trailId)
    }

    suspend fun deleteDownloadedTrail(trailId: String) = withContext(Dispatchers.IO) {
        dao.deleteDownloadedTrail(trailId)
    }

    // --- Saved Trails (Phase 6) ---
    fun getAllSavedTrails(): Flow<List<com.example.data.model.SavedTrailEntity>> = dao.getAllSavedTrails()

    fun isTrailSaved(trailId: String): Flow<Boolean> = dao.isTrailSaved(trailId)

    suspend fun toggleSaveTrail(trailId: String) = withContext(Dispatchers.IO) {
        val list = dao.getAllSavedTrailsList()
        if (list.any { it.trailId == trailId }) {
            dao.deleteSavedTrail(trailId)
        } else {
            dao.insertSavedTrail(com.example.data.model.SavedTrailEntity(trailId = trailId))
        }
    }

    // --- Trip Gear Checklist in Room (Phase 6) ---
    fun getGearItemsForPlan(planId: Long): Flow<List<com.example.data.model.TripGearItemEntity>> =
        dao.getGearItemsForPlan(planId)

    suspend fun updateGearItem(item: com.example.data.model.TripGearItemEntity) = withContext(Dispatchers.IO) {
        dao.updateGearItem(item)
    }

    // --- Trip Progress & Navigation State ---
    fun getTripProgress(planId: Long): Flow<TripProgressEntity?> = dao.getTripProgress(planId)

    suspend fun saveTripProgress(progress: TripProgressEntity) = withContext(Dispatchers.IO) {
        dao.saveTripProgress(progress)
    }

    suspend fun resumeTrip(planId: Long, currentGpsLat: Double, currentGpsLng: Double): TripProgressEntity? =
        withContext(Dispatchers.IO) {
            val plan = dao.getTripPlanById(planId) ?: return@withContext null
            val trail = dao.getTrailById(plan.trailId) ?: return@withContext null
            val points = getTrailPoints(trail).flatMap { it.points }
            val currentProgress = dao.getTripProgressSync(planId)

            val snapped = GeoUtils.snapPointToPolyline(GeoPointDto(currentGpsLat, currentGpsLng), points)
            val snappedDistKm = GeoUtils.distanceAlongPolylineKm(points, snapped)

            val updatedProgress = TripProgressEntity(
                planId = planId,
                currentDay = currentProgress?.currentDay ?: 1,
                distanceWalkedTodayKm = currentProgress?.distanceWalkedTodayKm ?: 0.0,
                totalDistanceWalkedKm = snappedDistKm,
                currentSnappedLat = snapped.lat,
                currentSnappedLng = snapped.lng,
                remainingDistanceTripKm = maxOf(0.0, plan.totalDistanceKm - snappedDistKm),
                remainingDistanceTodayKm = maxOf(0.0, 15.0 - (currentProgress?.distanceWalkedTodayKm ?: 0.0)),
                isPaused = false,
                isCompleted = false,
                lastActiveTimestamp = System.currentTimeMillis()
            )
            dao.saveTripProgress(updatedProgress)
            updatedProgress
        }

    // --- Danish 112 SOS (Enhancement 14) ---
    fun getEmergencyInfo(lat: Double, lng: Double): DanishEmergencyInfo {
        val spoken = GeoUtils.formatDanishSpokenWgs84(lat, lng)
        val decimal = String.format(java.util.Locale.US, "%.5f° N, %.5f° Ø", lat, lng)
        return DanishEmergencyInfo(
            decimalCoords = decimal,
            spokenWgs84 = spoken
        )
    }

    // --- Trekking Gear Checklist (Enhancement 15) ---
    fun getDefaultGearChecklist(overnightType: OvernightType, minForecastTempC: Double): List<GearChecklistItem> {
        val items = mutableListOf<GearChecklistItem>()
        val isTent = overnightType == OvernightType.TENT || overnightType == OvernightType.MIXED

        // Ly & Søvn
        if (isTent) {
            items.add(GearChecklistItem("g1", "Ly & Søvn", "Letvægtstelt / Bivuak", "Skal opfylde Fri Teltning 3-personers regel", true))
            items.add(GearChecklistItem("g2", "Ly & Søvn", "Ekstra teltpløkker", "Til sandet eller hård skovbund", false))
        } else {
            items.add(GearChecklistItem("g1", "Ly & Søvn", "Tarp / Presenning", "Beskyttelse mod slagregn i åbne shelters", false))
        }

        val bagTempRating = if (minForecastTempC < 6.0) "Komfort 0°C (kold nat i vente!)" else "Komfort +7°C"
        items.add(GearChecklistItem("g3", "Ly & Søvn", "Sovepose ($bagTempRating)", "Vejrudsigt varsler min. ${minForecastTempC.toInt()}°C om natten", true))
        items.add(GearChecklistItem("g4", "Ly & Søvn", "Isolerende liggeunderlag", "R-værdi > 2.5 til træbunde i shelters", true))
        items.add(GearChecklistItem("g5", "Ly & Søvn", "Myggenet / Hovednet", "Uundværligt ved danske søer og fjorde sommer/efterår", false))

        // Sikkerhed & Sundhed
        items.add(GearChecklistItem("g6", "Sikkerhed & Førstehjælp", "Flåtfjerner (Tick remover)", "Kritisk i danske skove mod skovflåt / Borrelia", true))
        items.add(GearChecklistItem("g7", "Sikkerhed & Førstehjælp", "Vabelplaster (Compeed) & sportstape", "Til forebyggelse på hæle og trædepuder", true))
        items.add(GearChecklistItem("g8", "Sikkerhed & Førstehjælp", "Enkeltmandsforbinding & desinfektion", "Småsår og rifter fra grene", true))

        // Vand & Mad
        items.add(GearChecklistItem("g9", "Vand & Mad", "Min. 2L drikkedunke / Blære", "Vandhaner kan være lukket om vinteren", true))
        items.add(GearChecklistItem("g10", "Vand & Mad", "Letvægts vandfilter (Sawyer/BeFree)", "Backup hvis primitiv vandhane er uklar", false))
        items.add(GearChecklistItem("g11", "Vand & Mad", "Stormkøkken (Trangia/Gasbrænder)", "Husk tændstikker i vandtæt pose", true))

        // Navigation & Energi
        items.add(GearChecklistItem("g12", "Navigation & Energi", "Powerbank (min. 10.000 mAh)", "Offline GPS og nødkald kræver strøm", true))
        items.add(GearChecklistItem("g13", "Navigation & Energi", "Pandelampe med rødt lys", "Rødt lys forstyrrer ikke andre i shelteret", true))
        items.add(GearChecklistItem("g14", "Navigation & Energi", "Vandtæt pose til mobil & dokumenter", "Sikrer elektronik ved dansk regnvejr", true))

        return items
    }

    // --- User Hazard Field Notes in Room (Mine noter) ---
    fun getHazardNotesForTrail(trailId: String): Flow<List<UserHazardNoteEntity>> =
        dao.getHazardNotesForTrail(trailId)

    suspend fun addHazardNote(note: UserHazardNoteEntity) = withContext(Dispatchers.IO) {
        dao.insertHazardNote(note)
    }

    suspend fun removeHazardNote(id: String) = withContext(Dispatchers.IO) {
        dao.deleteHazardNote(id)
    }

    // --- Phase 7a: Recorded GPS Track & Per-day Stats ---
    suspend fun recordTrackPoint(
        planId: Long,
        dayNumber: Int,
        lat: Double,
        lng: Double,
        altitude: Double?,
        accuracy: Float,
        speedKmh: Double,
        timestamp: Long
    ) = withContext(Dispatchers.IO) {
        val point = com.example.data.model.RecordedTrackPointEntity(
            planId = planId,
            dayNumber = dayNumber,
            lat = lat,
            lng = lng,
            altitude = altitude,
            accuracy = accuracy,
            speedKmh = speedKmh,
            timestamp = timestamp
        )
        dao.insertRecordedTrackPoint(point)

        // Update day stats
        val existingStat = dao.getRecordedDayStatSync(planId, dayNumber)
        val prevTimestamp = existingStat?.lastTimestamp ?: timestamp
        val dtSeconds = if (existingStat != null && timestamp > prevTimestamp) {
            ((timestamp - prevTimestamp) / 1000L).coerceAtMost(60L)
        } else 0L

        val isMoving = speedKmh >= 0.5
        val addedMovingTime = if (isMoving) dtSeconds else 0L

        // Distance from last point
        val lastPoints = dao.getRecordedTrackPointsForDaySync(planId, dayNumber)
        val prevPoint = if (lastPoints.size >= 2) lastPoints[lastPoints.size - 2] else null
        val addedDistanceKm = if (prevPoint != null) {
            GeoUtils.distanceMeters(prevPoint.lat, prevPoint.lng, lat, lng) / 1000.0
        } else 0.0

        val addedAscent = if (prevPoint != null && altitude != null && prevPoint.altitude != null) {
            val dAlt = altitude - prevPoint.altitude
            if (dAlt > 1.5) dAlt.toInt() else 0
        } else 0

        val updatedStat = com.example.data.model.RecordedDayStatEntity(
            planId = planId,
            dayNumber = dayNumber,
            distanceKm = (existingStat?.distanceKm ?: 0.0) + addedDistanceKm,
            movingTimeSeconds = (existingStat?.movingTimeSeconds ?: 0L) + addedMovingTime,
            ascentMeters = (existingStat?.ascentMeters ?: 0) + addedAscent,
            pointCount = (existingStat?.pointCount ?: 0) + 1,
            lastTimestamp = timestamp
        )
        dao.saveRecordedDayStat(updatedStat)
    }

    fun getRecordedTrackPointsForDay(planId: Long, dayNumber: Int) =
        dao.getRecordedTrackPointsForDay(planId, dayNumber)

    suspend fun getRecordedTrackPointsForDaySync(planId: Long, dayNumber: Int) = withContext(Dispatchers.IO) {
        dao.getRecordedTrackPointsForDaySync(planId, dayNumber)
    }

    suspend fun getAllRecordedTrackPointsForPlanSync(planId: Long) = withContext(Dispatchers.IO) {
        dao.getAllRecordedTrackPointsForPlanSync(planId)
    }

    fun getRecordedDayStat(planId: Long, dayNumber: Int) =
        dao.getRecordedDayStat(planId, dayNumber)

    fun getAllRecordedDayStats(planId: Long) =
        dao.getAllRecordedDayStats(planId)

    suspend fun getAllRecordedDayStatsSync(planId: Long) = withContext(Dispatchers.IO) {
        dao.getAllRecordedDayStatsSync(planId)
    }

    // --- Phase 7b: Overnight spots near me (< 10 km) from Room cache ---
    suspend fun getNearbyOvernightSpots(
        userLat: Double,
        userLng: Double,
        maxRadiusKm: Double = 10.0
    ): List<com.example.data.model.NearbyOvernightSpot> = withContext(Dispatchers.IO) {
        val maxMeters = maxRadiusKm * 1000.0
        val results = mutableListOf<com.example.data.model.NearbyOvernightSpot>()

        // 1. Shelters
        val shelters = dao.getAllSheltersList()
        for (s in shelters) {
            // Rough bounding check first: ~0.1 deg lat is ~11 km
            if (kotlin.math.abs(s.lat - userLat) <= 0.12 && kotlin.math.abs(s.lng - userLng) <= 0.20) {
                val d = GeoUtils.distanceMeters(userLat, userLng, s.lat, s.lng)
                if (d <= maxMeters) {
                    results.add(
                        com.example.data.model.NearbyOvernightSpot(
                            id = "shelter_${s.id}",
                            name = s.name,
                            type = "SHELTER",
                            lat = s.lat,
                            lng = s.lng,
                            distanceMeters = d,
                            bookingRequired = s.bookingRequired,
                            bookingUrl = s.bookingUrl,
                            hasWaterNearby = s.hasWaterNearby,
                            hasToiletNearby = s.hasToiletNearby,
                            description = s.description ?: s.ownership
                        )
                    )
                }
            }
        }

        // 2. Fri Teltning
        val friTeltnings = dao.getAllFriTeltningList()
        for (ft in friTeltnings) {
            if (kotlin.math.abs(ft.centerLat - userLat) <= 0.12 && kotlin.math.abs(ft.centerLng - userLng) <= 0.20) {
                val d = GeoUtils.distanceMeters(userLat, userLng, ft.centerLat, ft.centerLng)
                if (d <= maxMeters) {
                    results.add(
                        com.example.data.model.NearbyOvernightSpot(
                            id = "friteltning_${ft.id}",
                            name = ft.name,
                            type = "FRI_TELTNING",
                            lat = ft.centerLat,
                            lng = ft.centerLng,
                            distanceMeters = d,
                            bookingRequired = false,
                            bookingUrl = ft.rulesUrl,
                            hasWaterNearby = true,
                            hasToiletNearby = false,
                            description = ft.rulesSummary ?: "Fri teltning efter 1-2-3 reglen"
                        )
                    )
                }
            }
        }

        results.sortBy { it.distanceMeters }
        results
    }

    // Custom Routes (Phase 9 - Rutebygger)
    fun getAllCustomRoutes(): Flow<List<com.example.data.model.CustomRouteEntity>> =
        dao.getAllCustomRoutes()

    suspend fun getCustomRoute(id: Long): com.example.data.model.CustomRouteEntity? = withContext(Dispatchers.IO) {
        dao.getCustomRouteById(id)
    }

    suspend fun saveCustomRoute(route: com.example.data.model.CustomRouteEntity): Long = withContext(Dispatchers.IO) {
        val savedId = dao.insertCustomRoute(route)
        val entity = if (route.id == 0L) route.copy(id = savedId) else route

        // Convert legs to TrailGeometryDto and save to filesDir/routes/<id>.json.gz (Requirement A)
        try {
            val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, entity.geometryFile)
            val wps = com.example.util.routing.RouteWaypoint.listFromJson(entity.waypointsJson)
            val orderedLegs = mutableListOf<com.example.util.routing.LegGeometryData>()
            for (i in 0 until wps.size - 1) {
                val key = "${wps[i].id}_to_${wps[i + 1].id}"
                legs[key]?.let { orderedLegs.add(it) }
            }
            val geom = com.example.util.routing.RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(savedId.toString(), orderedLegs, entity.name)
            geometryRepository.saveRouteGeometry(savedId.toString(), geom)

            val points = geom.segments.flatMap { it.points }
            if (points.isNotEmpty()) {
                // Sync corridor POIs with routeId (Requirement B)
                syncCorridorPoisForRoute(savedId.toString(), points)
            }
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error generating route geometry or POIs for route $savedId: ${e.message}", e)
        }

        savedId
    }

    suspend fun updateCustomRoute(route: com.example.data.model.CustomRouteEntity) = withContext(Dispatchers.IO) {
        dao.updateCustomRoute(route)
        try {
            val legs = com.example.util.routing.RouteGeometryStorageManager.loadLegs(context, route.geometryFile)
            val wps = com.example.util.routing.RouteWaypoint.listFromJson(route.waypointsJson)
            val orderedLegs = mutableListOf<com.example.util.routing.LegGeometryData>()
            for (i in 0 until wps.size - 1) {
                val key = "${wps[i].id}_to_${wps[i + 1].id}"
                legs[key]?.let { orderedLegs.add(it) }
            }
            val geom = com.example.util.routing.RouteGeometryStorageManager.convertCustomRouteToTrailGeometry(route.id.toString(), orderedLegs, route.name)
            geometryRepository.saveRouteGeometry(route.id.toString(), geom)

            val points = geom.segments.flatMap { it.points }
            if (points.isNotEmpty()) {
                syncCorridorPoisForRoute(route.id.toString(), points)
            }
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error updating route geometry or POIs for route ${route.id}: ${e.message}", e)
        }
    }

    suspend fun deleteCustomRoute(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteCustomRouteById(id)
        dao.deletePoisForRoute(id.toString())
        val routeFile = geometryRepository.getRouteGeometryFile(id.toString())
        if (routeFile.exists()) routeFile.delete()
    }

    // Access Land (Phase 10 - Naturbeskyttelsesloven)
    val accessLandRepo: AccessLandRepository = AccessLandRepository(dao)

    suspend fun getAccessPolygons(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<com.example.data.model.access.AccessPolygon> =
        accessLandRepo.getAccessPolygons(minLat, maxLat, minLng, maxLng)

    // User Custom Waypoints (Phase 12 Feature 6: Mine punkter)
    suspend fun saveCustomWaypoint(waypoint: com.example.data.model.UserCustomWaypointEntity) = withContext(Dispatchers.IO) {
        dao.insertCustomWaypoint(waypoint)
    }

    suspend fun getCustomWaypointsForRoute(routeId: Long): List<com.example.data.model.UserCustomWaypointEntity> = withContext(Dispatchers.IO) {
        dao.getCustomWaypointsForRoute(routeId)
    }

    fun observeCustomWaypointsForRoute(routeId: Long): Flow<List<com.example.data.model.UserCustomWaypointEntity>> =
        dao.observeCustomWaypointsForRoute(routeId)

    suspend fun getAllCustomWaypoints(): List<com.example.data.model.UserCustomWaypointEntity> = withContext(Dispatchers.IO) {
        dao.getAllCustomWaypointsList()
    }

    suspend fun deleteCustomWaypoint(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCustomWaypointById(id)
    }

    // Phase 13 Requirement B: Afmærkede ruter
    suspend fun getMarkedRoutes(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        zoomLevel: Double
    ): List<com.example.data.model.MarkedRouteDto> = withContext(Dispatchers.IO) {
        markedRouteRepository.getMarkedRoutes(minLat, maxLat, minLng, maxLng, zoomLevel)
    }

    suspend fun saveMarkedRouteAsTrail(
        markedRoute: com.example.data.model.MarkedRouteDto
    ): Result<com.example.data.model.TrailEntity> = withContext(Dispatchers.IO) {
        markedRouteRepository.saveMarkedRouteAsTrail(markedRoute)
    }
}


