package com.example

import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.util.GeoUtils
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

class StagePlannerTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun testCalculateScoreFormula() {
        val targetKm = 15.0

        // Candidate 1: exact target, 500m detour (0.5km), booking=false, water=true
        // Score = |15.0 - 15.0| + 0.5 * 2 + 0 + 0 = 1.0
        val cand1 = OvernightCandidate(
            id = "c1", name = "Spot 1", type = "SHELTER",
            lat = 55.0, lng = 10.0, walkKm = 15.0, detourMeters = 500.0,
            bookingRequired = false, hasWaterNearby = true, hasToiletNearby = true
        )
        assertEquals(1.0, StagePlanner.calculateScore(cand1, targetKm), 0.001)

        // Candidate 2: 1 km over target, 200m detour (0.2km), booking=true (+1km), water=false (+2km)
        // Score = |16.0 - 15.0| + 0.2 * 2 + 1.0 + 2.0 = 1.0 + 0.4 + 1.0 + 2.0 = 4.4
        val cand2 = OvernightCandidate(
            id = "c2", name = "Spot 2", type = "SHELTER",
            lat = 55.0, lng = 10.0, walkKm = 16.0, detourMeters = 200.0,
            bookingRequired = true, hasWaterNearby = false, hasToiletNearby = false
        )
        assertEquals(4.4, StagePlanner.calculateScore(cand2, targetKm), 0.001)

        // Candidate 3: 2 km under target, 0m detour, booking=false, water=true
        // Score = |13.0 - 15.0| + 0 + 0 + 0 = 2.0
        val cand3 = OvernightCandidate(
            id = "c3", name = "Spot 3", type = "FRI_TELTNING",
            lat = 55.0, lng = 10.0, walkKm = 13.0, detourMeters = 0.0,
            bookingRequired = false, hasWaterNearby = true, hasToiletNearby = false
        )
        assertEquals(2.0, StagePlanner.calculateScore(cand3, targetKm), 0.001)
    }

    @Test
    fun testWindowCandidateSelectionAndNoEvenSplit() {
        // Create a 50 km test route
        val routePoints = mutableListOf<RoutePoint>()
        for (i in 0..500) {
            val km = i * 0.1
            routePoints.add(
                RoutePoint(
                    lat = 55.0 + (i * 0.001),
                    lng = 10.0,
                    ele = 20.0,
                    walkKm = km,
                    trailKm = km,
                    segmentType = TrailSegmentType.WALK
                )
            )
        }
        val plannedRoute = PlannedRoute(
            planId = 1L, trailId = "trail_test", trailName = "Test Trail",
            isReverse = false, startTrailKm = 0.0, endTrailKm = 50.0,
            totalWalkKm = 50.0, points = routePoints, stages = emptyList()
        )
        val trail = TrailEntity(id = "trail_test", name = "Test Trail", description = "", region = "", parentRegion = "")

        // Target for Day 1 is 15.0 km. Window is [11.0, 18.0].
        // Candidate at 10.5 km (outside window: target - 4.5)
        // Candidate at 14.8 km (in window: score = 0.2)
        // Candidate at 16.2 km (in window: score = 1.2)
        // Candidate at 19.0 km (outside window: target + 4.0)
        val shelters = listOf(
            ShelterEntity(id = "s_outside_low", name = "Outside Low", shelterType = "shelter", lat = 55.105, lng = 10.0),
            ShelterEntity(id = "s_best", name = "Best In Window", shelterType = "shelter", lat = 55.148, lng = 10.0, hasWaterNearby = true),
            ShelterEntity(id = "s_high", name = "High In Window", shelterType = "shelter", lat = 55.162, lng = 10.0, hasWaterNearby = true),
            ShelterEntity(id = "s_outside_high", name = "Outside High", shelterType = "shelter", lat = 55.190, lng = 10.0)
        )

        val stages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 2,
            overnightType = OvernightType.SHELTER,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = emptyList(),
            pois = emptyList()
        )

        assertEquals(2, stages.size)
        val stage1 = stages[0]
        assertEquals("Best In Window", stage1.stopName)
        assertEquals("SHELTER", stage1.stopType)
        // Verify stage ends at candidate walkKm (~14.8 km), NOT an even split of 25 km!
        assertEquals(14.8, stage1.endKm, 0.2)

        val stage2 = stages[1]
        // Next stage starts where stage 1 ended
        assertEquals(stage1.endKm, stage2.startKm, 0.001)
    }

    @Test
    fun testNoCandidateFoundWarning() {
        // Route of 30 km with no candidates near 15 km
        val routePoints = mutableListOf<RoutePoint>()
        for (i in 0..300) {
            val km = i * 0.1
            routePoints.add(RoutePoint(55.0 + (i * 0.001), 10.0, 10.0, km, km))
        }
        val plannedRoute = PlannedRoute(
            planId = 1L, trailId = "trail_test", trailName = "Test Trail",
            isReverse = false, startTrailKm = 0.0, endTrailKm = 30.0,
            totalWalkKm = 30.0, points = routePoints, stages = emptyList()
        )
        val trail = TrailEntity(id = "trail_test", name = "Test Trail", description = "", region = "", parentRegion = "")

        // Shelter only at 5 km (outside window 11..18)
        val shelters = listOf(
            ShelterEntity(id = "s1", name = "Early Shelter", shelterType = "shelter", lat = 55.05, lng = 10.0)
        )

        val stages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 2,
            overnightType = OvernightType.SHELTER,
            shelters = shelters,
            friTeltnings = emptyList(),
            pois = emptyList()
        )

        assertTrue(stages.isNotEmpty())
        val stage1 = stages[0]
        assertTrue("Stage 1 should have warning flag", stage1.isWarningNoOvernight)
        assertTrue(
            "Stage 1 warning should match format 'Ingen overnatning fundet nær km 15.0'",
            stage1.warningMessage?.startsWith("Ingen overnatning fundet nær km 15.0") == true
        )
    }

    @Test
    fun testSevenDayPlanOnOhavsstienGivesSevenStagesEndingAtRealSheltersOrFriTeltning() {
        // Load real Øhavsstien geometry and GeoFA shelters/fri teltning from bundled assets
        val (plannedRoute, trail, shelters, friTeltnings, pois) = loadRealOhavsstienData(isReverse = false)

        val stages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 7,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois,
            sunsetTime = "18:50"
        )

        assertEquals("Must produce exactly 7 stages for a 7-day plan", 7, stages.size)

        for (i in 0 until 7) {
            val stage = stages[i]
            assertEquals(i + 1, stage.dayNumber)
            assertTrue("Stage distance must be positive", stage.stageKm > 5.0)

            // Requirement: each stage ends at a real shelter or fri teltning area from Phase 2
            assertTrue(
                "Stage ${stage.dayNumber} must end at a real SHELTER or FRI_TELTNING, was: ${stage.stopType} (${stage.stopName})",
                stage.stopType == "SHELTER" || stage.stopType == "FRI_TELTNING" || stage.stopType == "CAMPSITE"
            )

            assertFalse(
                "Stage ${stage.dayNumber} should not have no-overnight warning",
                stage.isWarningNoOvernight
            )

            // Verify stages are contiguous (stage N+1 starts at stage N end)
            if (i > 0) {
                val prevStage = stages[i - 1]
                assertEquals(
                    "Stage ${stage.dayNumber} start must match previous stage end",
                    prevStage.endKm, stage.startKm, 0.001
                )
            }
        }
    }

    @Test
    fun testReversingDirectionGivesSensibleStagesFromOppositeEnd() {
        val (forwardRoute, trail, shelters, friTeltnings, pois) = loadRealOhavsstienData(isReverse = false)
        val (reverseRoute, _, _, _, _) = loadRealOhavsstienData(isReverse = true)

        val forwardStages = StagePlanner.calculateStages(
            plannedRoute = forwardRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 7,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois
        )

        val reverseStages = StagePlanner.calculateStages(
            plannedRoute = reverseRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 7,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois
        )

        assertEquals(7, reverseStages.size)

        // Day 1 in reverse direction must start at km 0.0 in walking direction
        assertEquals(0.0, reverseStages[0].startKm, 0.001)

        // The first overnight spot in reverse direction should differ from forward Day 1
        assertNotEquals(
            "Reverse Day 1 should end at a different spot than forward Day 1",
            forwardStages[0].stopName, reverseStages[0].stopName
        )

        // Every reverse stage must end at a real shelter or fri teltning
        for (stage in reverseStages) {
            assertTrue(
                "Reverse stage ${stage.dayNumber} must end at a real shelter or fri teltning",
                stage.stopType in listOf("SHELTER", "FRI_TELTNING", "CAMPSITE")
            )
        }
    }

    @Test
    fun testChangingNight3RecomputesNights4To7Only() {
        val (plannedRoute, trail, shelters, friTeltnings, pois) = loadRealOhavsstienData(isReverse = false)

        val originalStages = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            numDays = 7,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois
        )
        assertEquals(7, originalStages.size)

        // Choose a different candidate for Night 3 (day index 2)
        val allCandidates = StagePlanner.extractCandidates(
            plannedRoute = plannedRoute,
            shelters = shelters,
            friTeltnings = friTeltnings,
            waterPois = pois.filter { it.type == "water" },
            toiletPois = pois.filter { it.type == "toilet" },
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5
        )

        // Pick a candidate around 40-48 km that is different from original night 3 stop
        val origNight3 = originalStages[2]
        val altCandidate = allCandidates.firstOrNull {
            it.name != origNight3.stopName &&
                    it.walkKm >= (originalStages[1].endKm + 6.0) &&
                    it.walkKm <= (origNight3.endKm + 5.0)
        } ?: allCandidates.first { it.name != origNight3.stopName }

        val recomputedStages = StagePlanner.recomputeStagesFrom(
            currentStages = originalStages,
            changedStageIndex = 2, // Night 3
            newCandidate = altCandidate,
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 15.0,
            overnightType = OvernightType.BOTH,
            maxDetourKm = 1.5,
            shelters = shelters,
            friTeltnings = friTeltnings,
            pois = pois
        )

        assertEquals(7, recomputedStages.size)

        // Requirement: Nights 1 and 2 (indices 0 and 1) MUST remain completely unchanged!
        assertEquals("Stage 1 startKm unchanged", originalStages[0].startKm, recomputedStages[0].startKm, 0.001)
        assertEquals("Stage 1 endKm unchanged", originalStages[0].endKm, recomputedStages[0].endKm, 0.001)
        assertEquals("Stage 1 stopName unchanged", originalStages[0].stopName, recomputedStages[0].stopName)

        assertEquals("Stage 2 startKm unchanged", originalStages[1].startKm, recomputedStages[1].startKm, 0.001)
        assertEquals("Stage 2 endKm unchanged", originalStages[1].endKm, recomputedStages[1].endKm, 0.001)
        assertEquals("Stage 2 stopName unchanged", originalStages[1].stopName, recomputedStages[1].stopName)

        // Stage 3 must be updated to altCandidate
        assertEquals(altCandidate.name, recomputedStages[2].stopName)
        assertEquals(altCandidate.walkKm, recomputedStages[2].endKm, 0.1)

        // Stages 4 to 7 must recompute starting from altCandidate.walkKm
        assertEquals(recomputedStages[2].endKm, recomputedStages[3].startKm, 0.001)
    }

    @Test
    fun testSunsetWarningLogic() {
        val plannedRoute = createSimplePlannedRoute(lengthKm = 25.0)
        val trail = TrailEntity(id = "t1", name = "Trail", description = "", region = "", parentRegion = "")

        // Case A: 25 km hike at 4.0 km/h = 6h 15m. Start at 09:00 + 6h15 + 1h break = 16:15. Sunset at 18:50.
        // 16:15 <= 18:50 -> isAfterSunset should be false.
        val stagesNormal = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 4.0,
            dailyTargetKm = 25.0,
            numDays = 1,
            startTime = "09:00",
            sunsetTime = "18:50",
            shelters = emptyList(),
            friTeltnings = emptyList(),
            pois = emptyList()
        )
        assertFalse("16:15 arrival should not warn after 18:50 sunset", stagesNormal[0].isAfterSunset)

        // Case B: 25 km hike at 3.0 km/h = 8h 20m. Start at 11:00 + 8h20 + 1h break = 20:20. Sunset at 18:50.
        // 20:20 > 18:50 -> isAfterSunset should be true!
        val stagesLate = StagePlanner.calculateStages(
            plannedRoute = plannedRoute,
            trail = trail,
            paceKmh = 3.0,
            dailyTargetKm = 25.0,
            numDays = 1,
            startTime = "11:00",
            sunsetTime = "18:50",
            shelters = emptyList(),
            friTeltnings = emptyList(),
            pois = emptyList()
        )
        assertTrue("20:20 arrival must trigger sunset warning", stagesLate[0].isAfterSunset)
    }

    @Test
    fun testFriTeltningNearestPolygonPointUsed() {
        val routePoints = listOf(
            RoutePoint(55.0, 10.0, 0.0, 0.0, 0.0),
            RoutePoint(55.0, 10.1, 0.0, 6.3, 6.3),
            RoutePoint(55.0, 10.2, 0.0, 12.6, 12.6)
        )
        val plannedRoute = PlannedRoute(
            planId = 1L, trailId = "t", trailName = "T",
            isReverse = false, startTrailKm = 0.0, endTrailKm = 12.6,
            totalWalkKm = 12.6, points = routePoints, stages = emptyList()
        )

        // Polygon with centre far away (55.02, 10.1 = ~2.2 km away)
        // But one edge point very close (55.003, 10.1 = ~330m away)
        val polygonJson = """[{"lat": 55.003, "lng": 10.1}, {"lat": 55.03, "lng": 10.1}, {"lat": 55.03, "lng": 10.12}, {"lat": 55.003, "lng": 10.12}]"""
        val friTeltning = listOf(
            FriTeltningEntity(
                id = "ft_test",
                name = "Test Skov",
                centerLat = 55.02,
                centerLng = 10.11, // Centre is >2 km detour
                polygonJson = polygonJson
            )
        )

        val candidates = StagePlanner.extractCandidates(
            plannedRoute = plannedRoute,
            shelters = emptyList(),
            friTeltnings = friTeltning,
            waterPois = emptyList(),
            toiletPois = emptyList(),
            overnightType = OvernightType.FRI_TELTNING,
            maxDetourKm = 1.5
        )

        assertEquals("Candidate must be found via nearest polygon point", 1, candidates.size)
        val cand = candidates[0]
        assertTrue("Detour to nearest polygon point should be under 500m", cand.detourMeters < 500.0)
    }

    // --- Helper to load bundled real data for Øhavsstien ---
    private fun openAssetStream(relativePath: String): InputStream {
        val candidates = listOf(
            java.io.File(relativePath),
            java.io.File("src/main/assets/$relativePath"),
            java.io.File("app/src/main/assets/$relativePath"),
            java.io.File("../app/src/main/assets/$relativePath"),
            java.io.File("../src/main/assets/$relativePath")
        )
        for (f in candidates) {
            if (f.exists()) return f.inputStream()
        }
        val clStream = javaClass.classLoader?.getResourceAsStream("assets/$relativePath")
            ?: javaClass.getResourceAsStream("/assets/$relativePath")
        if (clStream != null) return clStream
        throw java.io.FileNotFoundException("Could not find asset $relativePath in $candidates")
    }

    private fun loadRealOhavsstienData(isReverse: Boolean): Tuple5<PlannedRoute, TrailEntity, List<ShelterEntity>, List<FriTeltningEntity>, List<PoiEntity>> {
        val trailStream = openAssetStream("trails/trail_ohavsstien.json.gz")

        val rawPoints = mutableListOf<GeoPointDto>()
        GZIPInputStream(trailStream).bufferedReader().use { reader ->
            val json = reader.readText()
            val adapter = moshi.adapter(Map::class.java)
            val map = adapter.fromJson(json) as? Map<String, Any>
            val segments = map?.get("segments") as? List<Map<String, Any>> ?: emptyList()
            for (seg in segments) {
                val pts = seg["points"] as? List<Map<String, Any>> ?: continue
                for (p in pts) {
                    val lat = (p["lat"] as? Number)?.toDouble() ?: continue
                    val lng = (p["lng"] as? Number)?.toDouble() ?: continue
                    val ele = (p["ele"] as? Number)?.toDouble() ?: 0.0
                    rawPoints.add(GeoPointDto(lat, lng, ele))
                }
            }
        }

        // Build PlannedRoute
        val pointsToUse = if (isReverse) rawPoints.reversed() else rawPoints
        val routePoints = mutableListOf<RoutePoint>()
        var cumWalkM = 0.0
        for (i in pointsToUse.indices) {
            val pt = pointsToUse[i]
            if (i > 0) {
                val prev = pointsToUse[i - 1]
                val d = GeoUtils.distanceMeters(prev.lat, prev.lng, pt.lat, pt.lng)
                if (d <= 8000.0) {
                    cumWalkM += d
                }
            }
            routePoints.add(
                RoutePoint(
                    lat = pt.lat,
                    lng = pt.lng,
                    ele = pt.ele,
                    walkKm = cumWalkM / 1000.0,
                    trailKm = cumWalkM / 1000.0
                )
            )
        }
        val totalWalkKm = routePoints.lastOrNull()?.walkKm ?: 114.6

        val plannedRoute = PlannedRoute(
            planId = 1202410L,
            trailId = "trail_ohavsstien",
            trailName = "Øhavsstien",
            isReverse = isReverse,
            startTrailKm = 0.0,
            endTrailKm = totalWalkKm,
            totalWalkKm = totalWalkKm,
            points = routePoints,
            stages = emptyList()
        )

        val trail = TrailEntity(
            id = "trail_ohavsstien",
            name = "Øhavsstien",
            description = "Øhavsstien",
            region = "Fyn & Øerne",
            parentRegion = "Fyn & Øerne",
            computedWalkingKm = totalWalkKm
        )

        // Load Shelters from GeoFA
        val shelters = mutableListOf<ShelterEntity>()
        val shelterStream = openAssetStream("geofa/shelters.geojson.gz")
        GZIPInputStream(shelterStream).bufferedReader().use { reader ->
            val json = reader.readText()
            val map = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any>
            val features = map?.get("features") as? List<Map<String, Any>> ?: emptyList()
            for ((idx, f) in features.withIndex()) {
                val geom = f["geometry"] as? Map<String, Any> ?: continue
                val coords = geom["coordinates"] as? List<*> ?: continue
                if (coords.isEmpty()) continue
                val (lng, lat) = when {
                    coords[0] is Number && coords.size >= 2 -> {
                        Pair((coords[0] as Number).toDouble(), (coords[1] as Number).toDouble())
                    }
                    coords[0] is List<*> -> {
                        val inner = coords[0] as List<*>
                        if (inner.size >= 2 && inner[0] is Number) {
                            Pair((inner[0] as Number).toDouble(), (inner[1] as Number).toDouble())
                        } else continue
                    }
                    else -> continue
                }
                val props = (f["properties"] as? Map<String, Any>) ?: (f["props"] as? Map<String, Any>) ?: emptyMap()
                val name = (props["navn"] as? String) ?: (props["facil_ty"] as? String) ?: "Shelter $idx"
                shelters.add(
                    ShelterEntity(
                        id = "sh_$idx",
                        name = name,
                        shelterType = "shelter",
                        lat = lat,
                        lng = lng,
                        hasWaterNearby = true,
                        hasToiletNearby = true
                    )
                )
            }
        }

        // Load OSM POIs (shelters, campsites, water, toilets, shops)
        val pois = mutableListOf<PoiEntity>()
        val osmStream = openAssetStream("osm_pois/trail_ohavsstien.json")
        osmStream.bufferedReader().use { reader ->
            val json = reader.readText()
            val map = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any>
            val elements = map?.get("elements") as? List<Map<String, Any>> ?: emptyList()
            for (elem in elements) {
                val tags = elem["tags"] as? Map<String, Any> ?: emptyMap()
                val lat = (elem["lat"] as? Number)?.toDouble()
                    ?: ((elem["center"] as? Map<String, Any>)?.get("lat") as? Number)?.toDouble() ?: continue
                val lng = (elem["lon"] as? Number)?.toDouble()
                    ?: ((elem["center"] as? Map<String, Any>)?.get("lon") as? Number)?.toDouble() ?: continue
                val id = (elem["id"] as? Number)?.toString() ?: continue
                val name = (tags["name"] as? String) ?: (tags["shelter_type"] as? String) ?: "OSM $id"
                val amenity = tags["amenity"] as? String
                val tourism = tags["tourism"] as? String
                val shop = tags["shop"] as? String

                if (amenity == "shelter") {
                    shelters.add(
                        ShelterEntity(
                            id = "osm_sh_$id",
                            name = name,
                            shelterType = "shelter",
                            lat = lat,
                            lng = lng,
                            hasWaterNearby = tags["drinking_water"] == "yes",
                            hasToiletNearby = true
                        )
                    )
                } else if (tourism == "camp_site") {
                    shelters.add(
                        ShelterEntity(
                            id = "osm_camp_$id",
                            name = name,
                            shelterType = "campsite",
                            lat = lat,
                            lng = lng,
                            hasWaterNearby = true,
                            hasToiletNearby = true,
                            bookingRequired = true
                        )
                    )
                }

                if (amenity == "drinking_water" || tags["drinking_water"] == "yes") {
                    pois.add(PoiEntity(id = "osm_w_$id", name = name, type = "water", lat = lat, lng = lng))
                }
                if (amenity == "toilets") {
                    pois.add(PoiEntity(id = "osm_t_$id", name = name, type = "toilet", lat = lat, lng = lng))
                }
                if (shop != null) {
                    pois.add(PoiEntity(id = "osm_s_$id", name = name, type = "shop", lat = lat, lng = lng))
                }
            }
        }

        // Load Fri Teltning
        val friTeltnings = mutableListOf<FriTeltningEntity>()
        val ftStream = openAssetStream("geofa/fri_teltning.geojson.gz")
        GZIPInputStream(ftStream).bufferedReader().use { reader ->
            val json = reader.readText()
            val map = moshi.adapter(Map::class.java).fromJson(json) as? Map<String, Any>
            val features = map?.get("features") as? List<Map<String, Any>> ?: emptyList()
            for ((idx, f) in features.withIndex()) {
                val geom = f["geometry"] as? Map<String, Any> ?: continue
                val props = (f["properties"] as? Map<String, Any>) ?: (f["props"] as? Map<String, Any>) ?: emptyMap()
                val name = (props["navn"] as? String) ?: "Fri Teltning $idx"
                val coords = geom["coordinates"] as? List<*> ?: continue
                val pts = mutableListOf<GeoPointDto>()
                // Extract polygon points
                val gtype = geom["type"] as? String
                if ((gtype == "Polygon" || gtype == "MultiPolygon") && coords.isNotEmpty()) {
                    val ring = when {
                        gtype == "MultiPolygon" -> (coords[0] as? List<*>)?.firstOrNull() as? List<*>
                        else -> coords[0] as? List<*>
                    }
                    if (ring != null) {
                        for (p in ring) {
                            val ptList = p as? List<*> ?: continue
                            if (ptList.size >= 2) {
                                val lng = (ptList[0] as? Number)?.toDouble() ?: continue
                                val lat = (ptList[1] as? Number)?.toDouble() ?: continue
                                pts.add(GeoPointDto(lat, lng))
                            }
                        }
                    }
                }
                val cLat = pts.firstOrNull()?.lat ?: 55.0
                val cLng = pts.firstOrNull()?.lng ?: 10.0
                val polyJson = moshi.adapter<List<GeoPointDto>>(Types.newParameterizedType(List::class.java, GeoPointDto::class.java)).toJson(pts)
                friTeltnings.add(
                    FriTeltningEntity(
                        id = "ft_$idx",
                        name = name,
                        centerLat = cLat,
                        centerLng = cLng,
                        polygonJson = polyJson,
                        waterDistanceMeters = 200,
                        toiletDistanceMeters = 200
                    )
                )
            }
        }

        return Tuple5(plannedRoute, trail, shelters, friTeltnings, pois)
    }

    private fun createSimplePlannedRoute(lengthKm: Double): PlannedRoute {
        val points = mutableListOf<RoutePoint>()
        val count = (lengthKm * 10).toInt()
        for (i in 0..count) {
            val km = i * 0.1
            points.add(RoutePoint(55.0 + (i * 0.001), 10.0, 10.0, km, km))
        }
        return PlannedRoute(
            planId = 1L, trailId = "trail_test", trailName = "Test Trail",
            isReverse = false, startTrailKm = 0.0, endTrailKm = lengthKm,
            totalWalkKm = lengthKm, points = points, stages = emptyList()
        )
    }

    data class Tuple5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}
