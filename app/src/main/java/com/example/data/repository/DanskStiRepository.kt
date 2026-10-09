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
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.math.abs

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

    suspend fun initializePreseededDataIfEmpty() = withContext(Dispatchers.IO) {
        dao.insertTrails(PreseededData.getInitialTrails())

        // Ensure GeoFA data is loaded into Room
        val shelterCount = dao.getShelterCount()
        if (shelterCount == 0) {
            try {
                val shelters = geoFaRepository.fetchGeoFaShelters()
                val friTeltnings = geoFaRepository.fetchGeoFaFriTeltning()
                val pois = geoFaRepository.fetchGeoFaPois()

                val waterPois = pois.filter { it.type == "water" }
                val toiletPois = pois.filter { it.type == "toilet" }
                val computedShelters = shelters.map { sh ->
                    val hasWater = waterPois.any { w -> GeoUtils.distanceMeters(sh.lat, sh.lng, w.lat, w.lng) <= 500.0 }
                    val hasToilet = toiletPois.any { t -> GeoUtils.distanceMeters(sh.lat, sh.lng, t.lat, t.lng) <= 500.0 }
                    sh.copy(hasWaterNearby = hasWater, hasToiletNearby = hasToilet)
                }

                if (computedShelters.isNotEmpty()) dao.insertShelters(computedShelters)
                if (friTeltnings.isNotEmpty()) dao.insertFriTeltning(friTeltnings)
                if (pois.isNotEmpty()) dao.insertPois(pois)
                Log.d("DanskStiRepo", "GeoFA initial data loaded: ${computedShelters.size} shelters, ${friTeltnings.size} fri teltning, ${pois.size} pois")
            } catch (e: Exception) {
                Log.e("DanskStiRepo", "Failed to load initial GeoFA data: ${e.message}", e)
            }
        }
    }

    fun getAllTrails(): Flow<List<TrailEntity>> = dao.getAllTrails()

    suspend fun getTrailById(id: String): TrailEntity? = withContext(Dispatchers.IO) {
        dao.getTrailById(id)
    }

    suspend fun getTrailGeometry(trailId: String): com.example.data.model.TrailGeometryDto? = withContext(Dispatchers.IO) {
        geometryRepository.loadGeometry(trailId)
    }

    suspend fun getTrailPoints(trail: TrailEntity): List<GeoPointDto> = withContext(Dispatchers.IO) {
        val geom = geometryRepository.loadGeometry(trail.id)
            ?: geometryRepository.fetchAndProcessTrail(trail.id, trail.name, trail.osmRelationId)?.also {
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
        geom?.segments?.flatMap { it.points } ?: emptyList()
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
            val points = getTrailPoints(trail)
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

    // --- Shelters, Fri Teltning, and POIs for Trail Corridor ---
    suspend fun getSheltersForTrail(trailId: String): List<ShelterEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getSheltersForTrail(trailId)
        if (corridor.isNotEmpty()) corridor else dao.getAllSheltersList()
    }

    suspend fun getFriTeltningForTrail(trailId: String): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        dao.getAllFriTeltningList()
    }

    suspend fun getPoisForTrail(trailId: String): List<PoiEntity> = withContext(Dispatchers.IO) {
        val corridor = dao.getPoisForTrail(trailId)
        if (corridor.isNotEmpty()) corridor else dao.getAllPoisList()
    }

    // --- Automatic Stage Planning Algorithm (Phase 5) ---
    suspend fun calculateStages(
        trail: TrailEntity,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        days: Int,
        overnightType: OvernightType,
        dailyTargetKm: Double,
        paceKmh: Double = 4.0,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        sunsetTime: String? = null
    ): List<TripStage> = withContext(Dispatchers.Default) {
        val rawPoints = getTrailPoints(trail)
        val plannedRouteRepo = PlannedRouteRepository(context)
        val plannedRoute = plannedRouteRepo.buildPlannedRoute(
            planId = 0L,
            trail = trail,
            rawTrailPoints = rawPoints,
            startKm = startKm,
            endKm = endKm,
            isReverse = isReverse,
            stages = emptyList()
        )

        val corridorShelters = dao.getSheltersForTrail(trail.id)
        val shelters = if (corridorShelters.isNotEmpty()) corridorShelters else dao.getAllSheltersList()
        val friTeltnings = dao.getAllFriTeltningList()
        val corridorPois = dao.getPoisForTrail(trail.id)
        val pois = if (corridorPois.isNotEmpty()) corridorPois else dao.getAllPoisList()

        com.example.util.StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            numDays = days,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = sunsetTime
        )
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

    fun getAllTripPlans(): Flow<List<TripPlanEntity>> = dao.getAllTripPlans()

    suspend fun getTripPlanById(id: Long): TripPlanEntity? = withContext(Dispatchers.IO) {
        dao.getTripPlanById(id)
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
            val points = getTrailPoints(trail)
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
}

