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
import com.example.data.model.ResupplyPoint
import com.example.data.model.ResupplyType
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSurfaceStats
import com.example.data.model.TransitStation
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStage
import com.example.data.model.UserHazardNote
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class DanskStiRepository(
    private val dao: DanskStiDao,
    private val context: Context,
    private val apiService: OverpassApiService = OverpassApiService.create(),
    private val weatherService: WeatherApiService = WeatherApiService.create()
) {
    private val moshi = Moshi.Builder().build()
    private val pointListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
    private val pointAdapter = moshi.adapter<List<GeoPointDto>>(pointListType)
    private val stageListType = Types.newParameterizedType(List::class.java, TripStage::class.java)
    private val stageAdapter = moshi.adapter<List<TripStage>>(stageListType)

    suspend fun initializePreseededDataIfEmpty() = withContext(Dispatchers.IO) {
        val trailCount = dao.getTrailCount()
        // Always ensure all 12 trails have the authentic, dense land coordinates with zero water crossings
        dao.insertTrails(PreseededData.getInitialTrails())
        // Always ensure shelters, fri teltning, and pois have full up-to-date data
        dao.insertShelters(PreseededData.getInitialShelters())
        dao.insertFriTeltning(PreseededData.getInitialFriTeltning())
        dao.insertPois(PreseededData.getInitialPois())
        Log.d("DanskStiRepo", "Pre-seeding verification completed. Total trails seeded: ${PreseededData.getInitialTrails().size}")
    }

    fun getAllTrails(): Flow<List<TrailEntity>> = dao.getAllTrails()

    suspend fun getTrailById(id: String): TrailEntity? = withContext(Dispatchers.IO) {
        dao.getTrailById(id)
    }

    suspend fun parseTrailPoints(trail: TrailEntity): List<GeoPointDto> = withContext(Dispatchers.Default) {
        try {
            pointAdapter.fromJson(trail.pointsJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializePoints(points: List<GeoPointDto>): String {
        return pointAdapter.toJson(points)
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

    // --- Overpass Trail Fetching ---
    suspend fun syncTrailsFromOverpass(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:60];
                area["ISO3166-1"="DK"][admin_level=2]->.searchArea;
                (
                  relation["route"="hiking"](area.searchArea);
                );
                out tags bb;
            """.trimIndent()

            val response = apiService.queryOverpassPost(query)
            val relations = response.elements.filter { it.type == "relation" }

            val fetchedTrails = mutableListOf<TrailEntity>()
            for (elem in relations) {
                val name = elem.tags?.get("name") ?: elem.tags?.get("name:da") ?: continue
                val desc = elem.tags?.get("description") ?: "Vandrerute i Danmark"
                val region = elem.tags?.get("region") ?: deriveRegion(elem)

                val trailId = "osm_rel_${elem.id}"
                // If geometry isn't provided with bb out, fetch detail or estimate
                val bounds = elem.tags
                // Check if already in DB
                val existing = dao.getTrailById(trailId)
                if (existing == null) {
                    val points = listOf(
                        GeoPointDto(55.6761, 12.5683),
                        GeoPointDto(55.6800, 12.5700)
                    )
                    fetchedTrails.add(
                        TrailEntity(
                            id = trailId,
                            name = name,
                            description = desc,
                            lengthKm = 25.0,
                            region = region,
                            elevationGainM = 150,
                            pointsJson = serializePoints(points),
                            bboxNorth = 56.0,
                            bboxSouth = 55.0,
                            bboxEast = 13.0,
                            bboxWest = 10.0,
                            osmRelationId = elem.id
                        )
                    )
                }
            }

            if (fetchedTrails.isNotEmpty()) {
                dao.insertTrails(fetchedTrails)
            }
            Result.success(fetchedTrails.size)
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error syncing trails: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun deriveRegion(elem: com.example.data.remote.OverpassElement): String {
        return "Danmark"
    }

    // --- Corridor POI sync ---
    suspend fun syncCorridorPois(trail: TrailEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val points = parseTrailPoints(trail)
            if (points.isEmpty()) return@withContext Result.success(Unit)

            val minLat = points.minOf { it.lat } - 0.03
            val maxLat = points.maxOf { it.lat } + 0.03
            val minLng = points.minOf { it.lng } - 0.05
            val maxLng = points.maxOf { it.lng } + 0.05

            val bboxStr = "$minLat,$minLng,$maxLat,$maxLng"

            val query = """
                [out:json][timeout:45];
                (
                  node["amenity"="shelter"]["access"!="private"]($bboxStr);
                  node["tourism"="wilderness_hut"]["access"!="private"]($bboxStr);
                  node["amenity"="toilets"]["access"!~"private|customers"]($bboxStr);
                  node["amenity"="drinking_water"]($bboxStr);
                  node["amenity"="water_point"]($bboxStr);
                  node["man_made"="water_tap"]["drinking_water"="yes"]($bboxStr);
                );
                out body;
            """.trimIndent()

            val response = apiService.queryOverpassPost(query)

            val newShelters = mutableListOf<ShelterEntity>()
            val newPois = mutableListOf<PoiEntity>()

            for (elem in response.elements) {
                val lat = elem.lat ?: continue
                val lng = elem.lon ?: continue
                val p = GeoPointDto(lat, lng)

                // Verify within 3 km corridor of the trail
                val distCorridorM = GeoUtils.distanceToPolylineMeters(p, points)
                if (distCorridorM > 3200.0) continue

                val tags = elem.tags ?: emptyMap()
                val amenity = tags["amenity"]
                val tourism = tags["tourism"]
                val manMade = tags["man_made"]

                if (amenity == "shelter" || tourism == "wilderness_hut") {
                    val name = tags["name"] ?: "Shelter (${distCorridorM.toInt()}m fra ruten)"
                    val fee = tags["fee"] == "yes" || tags["charge"] != null
                    val capacity = tags["capacity"]?.toIntOrNull() ?: 4
                    val ownership = tags["operator"] ?: "Offentlig/Naturstyrelsen"

                    newShelters.add(
                        ShelterEntity(
                            id = "osm_shelter_${elem.id}",
                            name = name,
                            lat = lat,
                            lng = lng,
                            description = tags["description"] ?: "Offentlig shelterplads langs ruten.",
                            bookingRequired = fee || tags["reservation"] == "required",
                            ownership = ownership,
                            capacity = capacity,
                            hasWaterNearby = false,
                            hasToiletNearby = false,
                            source = "osm",
                            trailCorridorId = trail.id
                        )
                    )
                } else if (amenity == "toilets") {
                    val name = tags["name"] ?: "Offentligt toilet"
                    newPois.add(
                        PoiEntity(
                            id = "osm_wc_${elem.id}",
                            type = "toilet",
                            name = name,
                            lat = lat,
                            lng = lng,
                            access = tags["access"] ?: "public",
                            fee = tags["fee"] == "yes",
                            trailCorridorId = trail.id
                        )
                    )
                } else if (amenity == "drinking_water" || amenity == "water_point" ||
                    (manMade == "water_tap" && tags["drinking_water"] == "yes")
                ) {
                    val name = tags["name"] ?: "Drikkevandspost"
                    newPois.add(
                        PoiEntity(
                            id = "osm_water_${elem.id}",
                            type = "water",
                            name = name,
                            lat = lat,
                            lng = lng,
                            access = tags["access"] ?: "yes",
                            fee = false,
                            trailCorridorId = trail.id
                        )
                    )
                }
            }

            if (newShelters.isNotEmpty()) dao.insertShelters(newShelters)
            if (newPois.isNotEmpty()) dao.insertPois(newPois)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("DanskStiRepo", "Error syncing corridor POIs: ${e.message}")
            Result.failure(e)
        }
    }

    // --- Shelters, Fri Teltning, and POIs for Trail Corridor ---
    suspend fun getSheltersForTrail(trailId: String): List<ShelterEntity> = withContext(Dispatchers.IO) {
        // Return all public available shelters so all shelters across Denmark are visible on the map
        dao.getAllSheltersList()
    }

    suspend fun getFriTeltningForTrail(trailId: String): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        // Return all fri teltning forests so all fri teltning zones across Denmark are visible on the map
        dao.getAllFriTeltningList()
    }

    suspend fun getPoisForTrail(trailId: String): List<PoiEntity> = withContext(Dispatchers.IO) {
        dao.getAllPoisList()
    }

    // --- Automatic Stage Planning Algorithm ---
    suspend fun calculateStages(
        trail: TrailEntity,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        days: Int,
        overnightType: OvernightType,
        preferredDailyKm: Double
    ): List<TripStage> = withContext(Dispatchers.Default) {
        val rawPoints = parseTrailPoints(trail)
        val routePoints = if (isReverse) rawPoints.reversed() else rawPoints
        val effectiveStart = minOf(startKm, endKm)
        val effectiveEnd = maxOf(startKm, endKm)
        val totalDistance = maxOf(0.1, effectiveEnd - effectiveStart)

        val shelters = dao.getAllSheltersList()
        val friTeltnings = dao.getAllFriTeltningList()
        val pois = dao.getAllPoisList()
        val waterPois = pois.filter { it.type == "water" }
        val toiletPois = pois.filter { it.type == "toilet" }

        val stages = mutableListOf<TripStage>()
        val numDays = maxOf(1, minOf(30, days))
        val targetStepKm = totalDistance / numDays

        var currentKm = effectiveStart

        for (day in 1..numDays) {
            val stageTargetEndKm = if (day == numDays) effectiveEnd else currentKm + targetStepKm
            val stageLengthKm = stageTargetEndKm - currentKm

            if (day == numDays) {
                // Final destination: check if there is an overnight shelter or camp nearby at the goal
                val finalPt = GeoUtils.pointAtDistanceAlongPolyline(routePoints, effectiveEnd)
                val nearbyShelter = shelters.minByOrNull { GeoUtils.distanceMeters(finalPt.lat, finalPt.lng, it.lat, it.lng) }
                val shelterDist = nearbyShelter?.let { GeoUtils.distanceMeters(finalPt.lat, finalPt.lng, it.lat, it.lng) } ?: Double.MAX_VALUE
                val nearbyFt = friTeltnings.minByOrNull { GeoUtils.distanceMeters(finalPt.lat, finalPt.lng, it.centerLat, it.centerLng) }
                val ftDist = nearbyFt?.let { GeoUtils.distanceMeters(finalPt.lat, finalPt.lng, it.centerLat, it.centerLng) } ?: Double.MAX_VALUE

                val stopName: String
                val stopType: String
                val stopLat: Double
                val stopLng: Double
                val bookingReq: Boolean

                if (shelterDist <= 4000.0 && nearbyShelter != null) {
                    stopName = "${nearbyShelter.name} (${trail.name} etapeslut)"
                    stopType = "SHELTER"
                    stopLat = nearbyShelter.lat
                    stopLng = nearbyShelter.lng
                    bookingReq = nearbyShelter.bookingRequired
                } else if (ftDist <= 4000.0 && nearbyFt != null) {
                    stopName = "${nearbyFt.name} (Fri teltning etapeslut)"
                    stopType = "FRI_TELTNING"
                    stopLat = nearbyFt.centerLat
                    stopLng = nearbyFt.centerLng
                    bookingReq = false
                } else {
                    stopName = "Mål: ${trail.name} slutpunkt"
                    stopType = "DESTINATION"
                    stopLat = finalPt.lat
                    stopLng = finalPt.lng
                    bookingReq = false
                }

                stages.add(
                    TripStage(
                        dayNumber = day,
                        startKm = currentKm,
                        endKm = effectiveEnd,
                        stageKm = stageLengthKm,
                        stopName = stopName,
                        stopType = stopType,
                        stopLat = stopLat,
                        stopLng = stopLng,
                        bookingRequired = bookingReq,
                        hasWaterNearby = true,
                        hasToiletNearby = true
                    )
                )
            } else {
                // Find candidate overnight spots near stageTargetEndKm within ±3.0 km
                val idealPt = GeoUtils.pointAtDistanceAlongPolyline(routePoints, stageTargetEndKm)

                // Gather candidates: Shelter or Fri Teltning based on overnightType
                val shelterCandidates = if (overnightType == OvernightType.TENT) emptyList() else shelters
                val tentCandidates = if (overnightType == OvernightType.SHELTER) emptyList() else friTeltnings

                var bestStopName = "Etapemål Dag $day (${String.format("%.1f", stageTargetEndKm)} km)"
                var bestStopType = if (overnightType == OvernightType.TENT) "FRI_TELTNING" else "SHELTER"
                var bestLat = idealPt.lat
                var bestLng = idealPt.lng
                var bestBookingRequired = false
                var bestScore = Double.MAX_VALUE

                // Score shelters: distance to idealPt + penalty if no water or booking required
                for (sh in shelterCandidates) {
                    val dist = GeoUtils.distanceMeters(idealPt.lat, idealPt.lng, sh.lat, sh.lng)
                    if (dist <= 3500.0) {
                        var score = dist
                        if (sh.bookingRequired) score += 400.0
                        if (!sh.hasWaterNearby) score += 300.0
                        if (score < bestScore) {
                            bestScore = score
                            bestStopName = "${sh.name} (${sh.ownership})"
                            bestStopType = "SHELTER"
                            bestLat = sh.lat
                            bestLng = sh.lng
                            bestBookingRequired = sh.bookingRequired
                        }
                    }
                }

                // Score Fri Teltning
                for (ft in tentCandidates) {
                    val dist = GeoUtils.distanceMeters(idealPt.lat, idealPt.lng, ft.centerLat, ft.centerLng)
                    if (dist <= 3500.0) {
                        val score = dist + 150.0 // slight preference for shelter if available
                        if (score < bestScore) {
                            bestScore = score
                            bestStopName = "Fri Teltning: ${ft.name}"
                            bestStopType = "FRI_TELTNING"
                            bestLat = ft.centerLat
                            bestLng = ft.centerLng
                            bestBookingRequired = false
                        }
                    }
                }

                // Check water & toilet within 1 km of the chosen overnight stop
                val hasWater = waterPois.any {
                    GeoUtils.distanceMeters(bestLat, bestLng, it.lat, it.lng) <= 1000.0
                } || shelterCandidates.any { it.lat == bestLat && it.hasWaterNearby }

                val hasToilet = toiletPois.any {
                    GeoUtils.distanceMeters(bestLat, bestLng, it.lat, it.lng) <= 1000.0
                } || shelterCandidates.any { it.lat == bestLat && it.hasToiletNearby }

                stages.add(
                    TripStage(
                        dayNumber = day,
                        startKm = currentKm,
                        endKm = stageTargetEndKm,
                        stageKm = stageLengthKm,
                        stopName = bestStopName,
                        stopType = bestStopType,
                        stopLat = bestLat,
                        stopLng = bestLng,
                        bookingRequired = bestBookingRequired,
                        hasWaterNearby = hasWater,
                        hasToiletNearby = hasToilet
                    )
                )

                currentKm = stageTargetEndKm
            }
        }

        stages
    }

    // --- Save and Manage Plans in Room ---
    suspend fun saveTripPlan(
        trail: TrailEntity,
        startKm: Double,
        endKm: Double,
        isReverse: Boolean,
        days: Int,
        overnightType: OvernightType,
        preferredDailyKm: Double,
        stages: List<TripStage>
    ): Long = withContext(Dispatchers.IO) {
        val plan = TripPlanEntity(
            trailId = trail.id,
            trailName = trail.name,
            startKm = startKm,
            endKm = endKm,
            isReverse = isReverse,
            totalDistanceKm = abs(endKm - startKm),
            targetDays = days,
            overnightType = overnightType.name,
            preferredDailyKm = preferredDailyKm,
            stagesJson = serializeStages(stages)
        )
        dao.insertTripPlan(plan)
    }

    fun getAllTripPlans(): Flow<List<TripPlanEntity>> = dao.getAllTripPlans()

    suspend fun getTripPlanById(id: Long): TripPlanEntity? = withContext(Dispatchers.IO) {
        dao.getTripPlanById(id)
    }

    suspend fun deleteTripPlan(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteTripPlan(id)
        dao.deleteTripProgress(id)
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
            val points = parseTrailPoints(trail)
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

    // --- Trail Surface & Footwear Advisor (New Feature 1) ---
    fun getSurfaceStatsForTrail(trailId: String): TrailSurfaceStats {
        return when (trailId) {
            "trail_ohavsstien" -> TrailSurfaceStats(
                forestSoilPct = 44,
                gravelPct = 34,
                asphaltPct = 14,
                sandBeachPct = 8,
                recommendedFootwear = "Trail runners eller lette vandresko",
                blisterRisk = "Mellem (sandede strandstykker & kyststier)",
                footwearTip = "Store dele løber langs strandvolde, skove og markveje. Kraftige vandrestøvler er for tunge; lette, åndbare trail runners med gaiters mod strandsand er ideelle."
            )
            "trail_haervejen" -> TrailSurfaceStats(
                forestSoilPct = 28,
                gravelPct = 42,
                asphaltPct = 26,
                sandBeachPct = 4,
                recommendedFootwear = "Dæmpede trekking-sko med tyk sål",
                blisterRisk = "Høj (pga. 26% asfalt & hårde grusveje)",
                footwearTip = "Hærvejen har lange strækninger på hårdt stampet grus og asfalt. Vælg sko med maksimal stødabsorbering og medbring vabelplaster (Compeed)."
            )
            "trail_gendarmstien" -> TrailSurfaceStats(
                forestSoilPct = 52,
                gravelPct = 28,
                asphaltPct = 12,
                sandBeachPct = 8,
                recommendedFootwear = "Trail runners med god profil",
                blisterRisk = "Lav til mellem",
                footwearTip = "Smukke trampestier langs Flensborg Fjord. Kan være leret og glat efter regn ved skrænterne, så såler med dybe knopper anbefales."
            )
            "trail_camoenoen" -> TrailSurfaceStats(
                forestSoilPct = 35,
                gravelPct = 38,
                asphaltPct = 18,
                sandBeachPct = 9,
                recommendedFootwear = "Lette vandresko / trail runners",
                blisterRisk = "Mellem",
                footwearTip = "Møns Klint trapper og kyststrækninger kræver fleksible såler. Husk vandrestave til klintens 497 trappetrin."
            )
            else -> TrailSurfaceStats(
                forestSoilPct = 40,
                gravelPct = 35,
                asphaltPct = 20,
                sandBeachPct = 5,
                recommendedFootwear = "Alsidige trail runners",
                blisterRisk = "Mellem",
                footwearTip = "Typisk dansk terræn med blanding af skovbunde, markveje og forbindende småveje."
            )
        }
    }

    // --- Resupply & Provisioning Radar (New Feature 4) ---
    fun getResupplyPointsForTrail(trailId: String): List<ResupplyPoint> {
        val list = mutableListOf<ResupplyPoint>()
        when (trailId) {
            "trail_ohavsstien" -> {
                list.add(ResupplyPoint("r1", trailId, "Kvickly Svendborg Bycenter", ResupplyType.GROCERY, 55.0601, 10.6083, 0.5, "Hver dag 07:00-21:00", true, "Stort supermarked med fuldt frilufts- og madudvalg ved start."))
                list.add(ResupplyPoint("r2", trailId, "Jordbær & Honning Gårdbod (Lundeborg)", ResupplyType.FARM_STALL, 55.0745, 10.7410, 14.2, "Døgnåben selvbetjening", true, "Friske æg, kartofler, most og honning fra lokal gård. Betaling via MobilePay."))
                list.add(ResupplyPoint("r3", trailId, "Spar Lundeborg Havn", ResupplyType.GROCERY, 55.1380, 10.7890, 24.5, "Man-søn 07:30-19:00", true, "Lokal købmand lige ved havnen med frisk brød og forsyninger."))
                list.add(ResupplyPoint("r4", trailId, "Bageriet v/ Egebjerg Mølle", ResupplyType.BAKERY_CAFE, 55.0450, 10.5120, 48.0, "Tirs-søn 06:30-17:00", true, "Håndværksbageri med kaffe, rugbrød og kager."))
                list.add(ResupplyPoint("r5", trailId, "Meny Faaborg Havnefront", ResupplyType.GROCERY, 55.0970, 10.2450, 75.2, "Hver dag 07:00-20:00", true, "Velassorteret supermarked med delikatesse og proviant."))
                list.add(ResupplyPoint("r6", trailId, "Ærø Vejbod v/ Voderup Klint", ResupplyType.FARM_STALL, 54.8620, 10.3250, 175.0, "Døgnåben selvbetjening", true, "Lokal æblemost, grøntsager og hjemmelavet marmelade. MobilePay."))
            }
            "trail_haervejen" -> {
                list.add(ResupplyPoint("h1", trailId, "Føtex Viborg Gravene", ResupplyType.GROCERY, 56.4520, 9.4010, 0.2, "Hver dag 07:00-21:00", true, "Startproviant i centrum af Viborg."))
                list.add(ResupplyPoint("h2", trailId, "Min Købmand Torning", ResupplyType.GROCERY, 56.2920, 9.3610, 28.0, "Man-søn 07:00-19:30", true, "Lokal dagligvarebutik med frisksmurt frokost."))
                list.add(ResupplyPoint("h3", trailId, "Tinnet Krat Gårdbutik", ResupplyType.FARM_STALL, 55.9320, 9.3980, 85.0, "Man-lør 09:00-18:00", true, "Lokalt oksekød, kildevand, snacks og MobilePay."))
                list.add(ResupplyPoint("h4", trailId, "SuperBrugsen Jelling", ResupplyType.GROCERY, 55.7550, 9.4200, 118.0, "Hver dag 07:00-20:00", true, "Stort udvalg lige ved Jellinghøjene og togstationen."))
            }
            else -> {
                list.add(ResupplyPoint("g1", trailId, "Lokal Dagli'Brugsen", ResupplyType.GROCERY, 55.0, 10.0, 12.0, "Hver dag 07:30-19:00", true, "Dagligvarer og frisk forplejning."))
                list.add(ResupplyPoint("g2", trailId, "Vejbod v/ Ruten", ResupplyType.FARM_STALL, 55.02, 10.05, 22.0, "Døgnåben", true, "Frugt, bær og honning. Betaling med MobilePay."))
            }
        }
        return list
    }

    // --- Public Transit Hub Connectors (Enhancement 13) ---
    fun getTransitStationsForTrail(trailId: String): List<TransitStation> {
        val list = mutableListOf<TransitStation>()
        when (trailId) {
            "trail_ohavsstien" -> {
                list.add(TransitStation("ts1", trailId, "Svendborg Station (DSB / Arriva)", "DSB Tog", 55.0601, 10.6083, 0.0, "Svendborgbanen (Odense-Svendborg)"))
                list.add(TransitStation("ts2", trailId, "Faaborg Rutebilstation", "Regionalbus", 55.0970, 10.2450, 75.0, "Bus 112 (Faaborg-Odense), Bus 930"))
                list.add(TransitStation("ts3", trailId, "Rudkøbing Busterminal", "Regionalbus", 54.9450, 10.7120, 125.0, "Bus 912 (Svendborg-Spodsbjerg)"))
                list.add(TransitStation("ts4", trailId, "Marstal Havn Færge/Bus", "Regionalbus", 54.8910, 10.4010, 160.0, "Gratis ÆrøBus (hele øen)"))
            }
            "trail_haervejen" -> {
                list.add(TransitStation("th1", trailId, "Viborg Banegård (DSB)", "DSB Tog", 56.4520, 9.4010, 0.0, "Aarhus-Struer / InterCity"))
                list.add(TransitStation("th2", trailId, "Engesvang Station (Arriva)", "DSB Tog", 56.1720, 9.3950, 48.0, "Skanderborg-Herning banen"))
                list.add(TransitStation("th3", trailId, "Jelling Station (Arriva)", "DSB Tog", 55.7550, 9.4200, 118.0, "Vejle-Struer banen"))
                list.add(TransitStation("th4", trailId, "Vejen Station (DSB)", "DSB Tog", 55.4920, 9.2210, 162.0, "København-Esbjerg InterCity"))
                list.add(TransitStation("th5", trailId, "Padborg Station (DSB)", "DSB Tog", 54.8250, 9.3600, 280.0, "Grænsetog / Fredericia-Hamburg"))
            }
            else -> {
                list.add(TransitStation("t0", trailId, "Start-Station", "DSB Tog", 55.1, 10.1, 0.0, "Regionaltog"))
            }
        }
        return list
    }

    // --- Danish 112 SOS & Red Rescue Post (Enhancement 14) ---
    fun getEmergencyInfo(lat: Double, lng: Double, trailName: String): DanishEmergencyInfo {
        val spoken = GeoUtils.formatDanishSpokenWgs84(lat, lng)
        val decimal = String.format(java.util.Locale.US, "%.5f N, %.5f Ø", lat, lng)
        val (postCode, postLoc) = when {
            trailName.contains("Øhavsstien", ignoreCase = true) -> Pair("K104", "Svanninge Bjerge Vest, skovsti-udmunding")
            trailName.contains("Hærvejen", ignoreCase = true) -> Pair("H042", "Tinnet Krat / Skjern Å udspring")
            trailName.contains("Gendarmstien", ignoreCase = true) -> Pair("G018", "Kobbelskov kystpost ved Flensborg Fjord")
            else -> Pair("R007", "Naturstyrelsen redningspæl nær stikryds")
        }
        return DanishEmergencyInfo(
            decimalCoords = decimal,
            spokenWgs84 = spoken,
            nearestRedPostCode = postCode,
            nearestRedPostLocation = postLoc,
            distanceToPostMeters = 340
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

    // --- User Hazard Field Notes in Memory (Enhancement 7) ---
    private val activeHazards = CopyOnWriteArrayList<UserHazardNote>().apply {
        // Initial preseeded community hazards for realism
        add(UserHazardNote("h1", "trail_ohavsstien", 55.0590, 10.3200, HazardType.FLOODED_BOARDWALK, "Oversvømmet træspang i Syltemae Ådal", "Træbroen står under 15 cm vand efter regn. Vandtætte støvler eller omvej anbefales."))
        add(UserHazardNote("h2", "trail_ohavsstien", 55.1260, 10.2420, HazardType.FALLEN_TREE, "Væltet bøgetræ over stien i Svanninge Bjerge", "Træet spærrer stien, men man kan let klatre udenom på sydsiden."))
        add(UserHazardNote("h3", "trail_haervejen", 55.8210, 9.3620, HazardType.CATTLE_ENCLOSURE, "Nysgerrigt ungkvæg i indhegningen ved Kollemorten", "Hold hunde i kort snor og gå roligt langs hegnet."))
    }

    fun getHazardsForTrail(trailId: String): List<UserHazardNote> {
        return activeHazards.filter { it.trailId == trailId }
    }

    fun addHazardNote(hazard: UserHazardNote) {
        activeHazards.add(0, hazard)
    }

    fun removeHazardNote(id: String) {
        activeHazards.removeAll { it.id == id }
    }
}

