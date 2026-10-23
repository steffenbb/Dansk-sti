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
                val initialTrails = PreseededData.getInitialTrails()
                val trailCount = dao.getTrailCount()
                if (trailCount == 0) {
                    dao.insertTrails(initialTrails)
                    Log.d("DanskStiRepo", "Preseeded ${initialTrails.size} initial trails inserted into Room")
                } else {
                    for (preTrail in initialTrails) {
                        val existing = dao.getTrailById(preTrail.id)
                        if (existing == null || existing.computedWalkingKm <= 0.0 || existing.bboxSouth == null) {
                            dao.insertTrail(preTrail)
                        }
                    }
                }

                // Ensure GeoFA + OSM data is loaded into Room
                val shelterCount = dao.getShelterCount()
                if (shelterCount == 0) {
                    try {
                        // First load from pre-bundled assets for instant, reliable startup
                        var shelters = geoFaRepository.loadBundledShelters()
                        var friTeltnings = geoFaRepository.loadBundledFriTeltning()
                        var pois = geoFaRepository.loadBundledPois().toMutableList()

                        // Also include bundled OSM POIs (shops, transit, water, toilets) and nationwide shops/stations
                        val bundledOsm = osmPoiRepository.loadBundledPois("trail_ohavsstien")
                        pois.addAll(bundledOsm.pois)
                        pois.addAll(getPreseededDanishShopsAndTransit())

                        // If bundled files were empty or unavailable, fall back to online fetch
                        if (shelters.isEmpty()) {
                            shelters = geoFaRepository.fetchGeoFaShelters()
                        }
                        if (friTeltnings.isEmpty()) {
                            friTeltnings = geoFaRepository.fetchGeoFaFriTeltning()
                        }
                        if (pois.isEmpty()) {
                            pois.addAll(geoFaRepository.fetchGeoFaPois())
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
                            pois.distinctBy { it.id }.chunked(250).forEach { dao.insertPois(it) }
                        }
                        Log.d("DanskStiRepo", "GeoFA + OSM initial data loaded: ${computedShelters.size} shelters, ${friTeltnings.size} fri teltning, ${pois.size} pois")
                    } catch (t: Throwable) {
                        Log.e("DanskStiRepo", "Failed to load initial GeoFA data: ${t.message}", t)
                    }
                } else {
                    // Ensure nationwide shops and transit POIs exist even if DB was partially seeded previously
                    val existingShops = dao.getPoisByType("shop")
                    if (existingShops.isEmpty()) {
                        val bundledOsm = osmPoiRepository.loadBundledPois("trail_ohavsstien")
                        val extraPois = (bundledOsm.pois + getPreseededDanishShopsAndTransit()).distinctBy { it.id }
                        extraPois.chunked(250).forEach { dao.insertPois(it) }
                    }
                }

                // Seed initial marked routes, access polygons, and water features for map overlays if empty
                seedInitialMapOverlaysIfEmpty(initialTrails)
            } catch (t: Throwable) {
                Log.e("DanskStiRepo", "Error in initializePreseededDataIfEmpty: ${t.message}", t)
            }
        }
    }

    private suspend fun seedInitialMapOverlaysIfEmpty(trails: List<TrailEntity>) {
        try {
            // 1. Marked routes ("Afmærkede ruter") from bundled trail geometries
            val existingMarked = dao.getMarkedRoutesInBbox(54.4, 58.0, 7.8, 15.5)
            if (existingMarked.isEmpty()) {
                val markedEntities = mutableListOf<com.example.data.model.MarkedRouteEntity>()
                trails.forEachIndexed { idx, trail ->
                    val geom = geometryRepository.loadGeometry(trail.id)
                    val segments = geom?.segments?.map { it.points }?.filter { it.size >= 2 } ?: emptyList()
                    if (segments.isNotEmpty()) {
                        val allPts = segments.flatten()
                        val minLat = allPts.minOf { it.lat }
                        val maxLat = allPts.maxOf { it.lat }
                        val minLng = allPts.minOf { it.lng }
                        val maxLng = allPts.maxOf { it.lng }
                        val dto = com.example.data.model.MarkedRouteDto(
                            osmRelationId = trail.osmRelationId ?: (900000L + idx),
                            name = trail.name,
                            lengthKm = trail.lengthKm,
                            operator = "Naturstyrelsen / Dansk Vandrelaug",
                            routeType = "hiking",
                            network = "nwn",
                            symbol = "Vandrerute",
                            colorHex = MarkedRouteRepository.PALETTE[idx % MarkedRouteRepository.PALETTE.size],
                            polylines = segments,
                            minLat = minLat,
                            maxLat = maxLat,
                            minLng = minLng,
                            maxLng = maxLng
                        )
                        markedEntities.add(markedRouteRepository.dtoToEntity(dto))
                    }
                }
                if (markedEntities.isNotEmpty()) {
                    dao.insertMarkedRoutes(markedEntities)
                }
            }

            // 2. Access polygons ("Adgangsforhold" - Naturstyrelsen state forests, private woods & nature reserves)
            val existingAccess = dao.getAccessPolygonsInBoundingBox(54.4, 58.0, 7.8, 15.5)
                .filter { it.type != "EMPTY_TILE_SENTINEL" }
            if (existingAccess.isEmpty()) {
                val accessEntities = getPreseededAccessPolygons().map { poly ->
                    val centerLat = poly.coordinates.map { it.lat }.average()
                    val centerLng = poly.coordinates.map { it.lng }.average()
                    val tileId = "tile_${kotlin.math.floor(centerLat * 10.0).toInt()}_${kotlin.math.floor(centerLng * 10.0).toInt()}"
                    accessLandRepo.modelToEntity(poly, tileId)
                }
                dao.insertAccessPolygons(accessEntities)
            }

            // 3. Water features ("Vand" - Danish lakes, rivers, wetlands, and springs)
            val existingWater = dao.getWaterFeaturesInBbox(54.4, 58.0, 7.8, 15.5)
            if (existingWater.isEmpty()) {
                val waterRepo = WaterLayerRepository(dao, apiService)
                val now = System.currentTimeMillis()
                val waterEntities = getPreseededWaterFeatures(waterRepo, now)
                dao.insertWaterFeatures(waterEntities)
            }
        } catch (t: Throwable) {
            Log.w("DanskStiRepo", "Failed seeding initial map overlays: ${t.message}")
        }
    }

    private fun getPreseededDanishShopsAndTransit(): List<PoiEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            // Nationwide Supermarkets / Groceries ("shop")
            PoiEntity("dk_shop_skovping_brugs", "shop", "SuperBrugsen Skørping (Rold Skov)", 56.8358, 9.8895, "yes", false, "trail_rold_skov", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_silkeborg_netto", "shop", "Netto Silkeborg (Hærvejen / Søhøjlandet)", 56.1695, 9.5462, "yes", false, "trail_haervejen", "07:00-22:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_ry_kvickly", "shop", "Kvickly Ry (Himmelbjerget)", 56.0908, 9.7615, "yes", false, "trail_himmelbjerget", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_viborg_meny", "shop", "MENY Viborg (Hærvejen)", 56.4525, 9.4035, "yes", false, "trail_haervejen", "07:00-21:00", "Dagrofa", null, "osm", now),
            PoiEntity("dk_shop_jelling_brugs", "shop", "SuperBrugsen Jelling (Hærvejen)", 55.7562, 9.4188, "yes", false, "trail_haervejen", "07:00-20:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_ebeltoft_kvickly", "shop", "Kvickly Ebeltoft (Mols Bjerge)", 56.1965, 10.6775, "yes", false, "trail_mols_bjerge", "08:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_knebel_brugs", "shop", "Dagli'Brugsen Knebel (Mols Bjerge)", 56.2128, 10.4832, "yes", false, "trail_mols_bjerge", "07:30-19:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_klitmoeller_brugs", "shop", "Spar Klitmøller (Nationalpark Thy)", 57.0412, 8.4905, "yes", false, "trail_vestkyststien", "07:30-20:00", "Spar", null, "osm", now),
            PoiEntity("dk_shop_vorupoer_meny", "shop", "MENY Nørre Vorupør (Vestkyststien)", 56.9575, 8.3712, "yes", false, "trail_vestkyststien", "07:30-20:00", "Dagrofa", null, "osm", now),
            PoiEntity("dk_shop_faaborg_netto", "shop", "Netto Faaborg (Øhavsstien)", 55.0968, 10.2442, "yes", false, "trail_ohavsstien", "07:00-22:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_svendborg_foetex", "shop", "føtex Svendborg (Øhavsstien)", 55.0605, 10.6095, "yes", false, "trail_ohavsstien", "07:00-21:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_rudkoebing_brugs", "shop", "SuperBrugsen Rudkøbing (Langeland)", 54.9372, 10.7135, "yes", false, "trail_ohavsstien", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_marstal_brugs", "shop", "SuperBrugsen Marstal (Ærø)", 54.8555, 10.5142, "yes", false, "trail_ohavsstien", "07:00-20:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_soenderborg_foetex", "shop", "føtex Sønderborg (Gendarmstien)", 54.9102, 9.7918, "yes", false, "trail_gendarmstien", "07:00-21:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_broager_brugs", "shop", "SuperBrugsen Broager (Gendarmstien)", 54.8895, 9.6745, "yes", false, "trail_gendarmstien", "07:00-20:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_stege_brugs", "shop", "SuperBrugsen Stege (Camønoen)", 54.9855, 12.2852, "yes", false, "trail_camonoen", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_borre_brugs", "shop", "Dagli'Brugsen Borre (Møns Klint)", 54.9962, 12.4415, "yes", false, "trail_camonoen", "07:30-19:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_hillerod_foetex", "shop", "føtex Hillerød (Nordsjællandsruten)", 55.9288, 12.3015, "yes", false, "trail_nordsjaelland", "07:00-21:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_helsingor_kvickly", "shop", "Kvickly Helsingør (Nordsjælland)", 56.0345, 12.6112, "yes", false, "trail_nordsjaelland", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_roskilde_foetex", "shop", "føtex Roskilde (Fjordstien / Sjællandsleden)", 55.6415, 12.0815, "yes", false, "trail_fjordstien", "07:00-21:00", "Salling Group", null, "osm", now),
            PoiEntity("dk_shop_allinge_brugs", "shop", "SuperBrugsen Allinge (Bornholm Kyststi)", 55.2762, 14.8012, "yes", false, "trail_bornholm", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_roenne_kvickly", "shop", "Kvickly Rønne (Bornholm)", 55.1015, 14.7025, "yes", false, "trail_bornholm", "07:00-21:00", "Coop", null, "osm", now),
            PoiEntity("dk_shop_nexoe_netto", "shop", "Netto Nexø (Bornholm)", 55.0615, 15.1312, "yes", false, "trail_bornholm", "07:00-22:00", "Salling Group", null, "osm", now),
            // Nationwide Transit / Stations ("train_station", "bus_stop", "ferry_terminal")
            PoiEntity("dk_transit_skoerping_st", "train_station", "Skørping Station (DSB / Rold Skov)", 56.8365, 9.8842, "yes", false, "trail_rold_skov", null, "DSB", null, "osm", now),
            PoiEntity("dk_transit_silkeborg_st", "train_station", "Silkeborg Station", 56.1642, 9.5458, "yes", false, "trail_haervejen", null, "DSB / Arriva", null, "osm", now),
            PoiEntity("dk_transit_ry_st", "train_station", "Ry Station (Søhøjlandet)", 56.0915, 9.7602, "yes", false, "trail_himmelbjerget", null, "Arriva", null, "osm", now),
            PoiEntity("dk_transit_viborg_st", "train_station", "Viborg Station (Hærvejen)", 56.4482, 9.3965, "yes", false, "trail_haervejen", null, "DSB / Arriva", null, "osm", now),
            PoiEntity("dk_transit_svendborg_st", "train_station", "Svendborg Station (Øhavsstien)", 55.0592, 10.6122, "yes", false, "trail_ohavsstien", null, "GoCollective", null, "osm", now),
            PoiEntity("dk_transit_soenderborg_st", "train_station", "Sønderborg Station (Gendarmstien)", 54.9125, 9.7825, "yes", false, "trail_gendarmstien", null, "DSB", null, "osm", now),
            PoiEntity("dk_transit_hillerod_st", "train_station", "Hillerød Station (Gribskovbanen)", 55.9272, 12.3105, "yes", false, "trail_nordsjaelland", null, "DSB / Lokaltog", null, "osm", now),
            PoiEntity("dk_transit_roskilde_st", "train_station", "Roskilde Station (Fjordstien)", 55.6392, 12.0892, "yes", false, "trail_fjordstien", null, "DSB", null, "osm", now),
            PoiEntity("dk_transit_roenne_faerge", "ferry_terminal", "Rønne Færgeterminal (Bornholmslinjen)", 55.0985, 14.6925, "yes", true, "trail_bornholm", null, "Bornholmslinjen", null, "osm", now)
        )
    }

    private fun getPreseededAccessPolygons(): List<com.example.data.model.access.AccessPolygon> {
        return listOf(
            com.example.data.model.access.AccessPolygon(
                id = "nst_forest_rold_skov",
                name = "Rold Skov & Rebild Bakker (Statsskov)",
                type = com.example.data.model.access.AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    com.example.util.routing.LatLng(56.78, 9.80),
                    com.example.util.routing.LatLng(56.85, 9.80),
                    com.example.util.routing.LatLng(56.85, 9.95),
                    com.example.util.routing.LatLng(56.78, 9.95)
                ),
                source = "Naturstyrelsen",
                operatorOrOwner = "Naturstyrelsen Himmerland"
            ),
            com.example.data.model.access.AccessPolygon(
                id = "nst_forest_silkeborg_skovene",
                name = "Silkeborgskovene & Nordskoven (Statsskov)",
                type = com.example.data.model.access.AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    com.example.util.routing.LatLng(56.11, 9.52),
                    com.example.util.routing.LatLng(56.17, 9.52),
                    com.example.util.routing.LatLng(56.17, 9.68),
                    com.example.util.routing.LatLng(56.11, 9.68)
                ),
                source = "Naturstyrelsen",
                operatorOrOwner = "Naturstyrelsen Søhøjlandet"
            ),
            com.example.data.model.access.AccessPolygon(
                id = "fredning_mols_bjerge",
                name = "Nationalpark Mols Bjerge (Fredet natur)",
                type = com.example.data.model.access.AccessLandType.UNCULTIVATED_LAND,
                coordinates = listOf(
                    com.example.util.routing.LatLng(56.19, 10.48),
                    com.example.util.routing.LatLng(56.26, 10.48),
                    com.example.util.routing.LatLng(56.26, 10.60),
                    com.example.util.routing.LatLng(56.19, 10.60)
                ),
                source = "Danmarks Miljøportal",
                operatorOrOwner = "Naturstyrelsen Kronjylland"
            ),
            com.example.data.model.access.AccessPolygon(
                id = "nst_forest_gribskov",
                name = "Gribskov (Nationalpark Kongernes Nordsjælland)",
                type = com.example.data.model.access.AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    com.example.util.routing.LatLng(55.95, 12.25),
                    com.example.util.routing.LatLng(56.04, 12.25),
                    com.example.util.routing.LatLng(56.04, 12.38),
                    com.example.util.routing.LatLng(55.95, 12.38)
                ),
                source = "Naturstyrelsen",
                operatorOrOwner = "Naturstyrelsen Nordsjælland"
            ),
            com.example.data.model.access.AccessPolygon(
                id = "priv_forest_svanninge_bakker",
                name = "Svanninge Bakker & Bjerge (Bikubenfonden / Skov)",
                type = com.example.data.model.access.AccessLandType.PRIVATE_FOREST,
                coordinates = listOf(
                    com.example.util.routing.LatLng(55.12, 10.22),
                    com.example.util.routing.LatLng(55.16, 10.22),
                    com.example.util.routing.LatLng(55.16, 10.29),
                    com.example.util.routing.LatLng(55.12, 10.29)
                ),
                source = "OpenStreetMap",
                operatorOrOwner = "Bikubenfonden"
            ),
            com.example.data.model.access.AccessPolygon(
                id = "nst_forest_almindingen",
                name = "Almindingen (Bornholm Statsskov)",
                type = com.example.data.model.access.AccessLandType.PUBLIC_FOREST,
                coordinates = listOf(
                    com.example.util.routing.LatLng(55.09, 14.88),
                    com.example.util.routing.LatLng(55.15, 14.88),
                    com.example.util.routing.LatLng(55.15, 15.00),
                    com.example.util.routing.LatLng(55.09, 15.00)
                ),
                source = "Naturstyrelsen",
                operatorOrOwner = "Naturstyrelsen Bornholm"
            )
        )
    }

    private fun getPreseededWaterFeatures(
        waterRepo: WaterLayerRepository,
        now: Long
    ): List<com.example.data.model.WaterFeatureEntity> {
        return listOf(
            waterRepo.buildEntity(
                id = "lake_madum_soe_seed",
                osmId = 910001L,
                name = "Store Økssø & Madum Sø",
                featureType = com.example.data.model.WaterFeatureType.LAKE,
                subType = "water",
                pts = listOf(
                    GeoPointDto(56.795, 9.925),
                    GeoPointDto(56.812, 9.925),
                    GeoPointDto(56.812, 9.955),
                    GeoPointDto(56.795, 9.955)
                ),
                tileId = "water_tile_seed_rold",
                fetchedAt = now
            ),
            waterRepo.buildEntity(
                id = "lake_juls_soe_seed",
                osmId = 910002L,
                name = "Julsø & Borre Sø (Himmelbjerget)",
                featureType = com.example.data.model.WaterFeatureType.LAKE,
                subType = "water",
                pts = listOf(
                    GeoPointDto(56.105, 9.610),
                    GeoPointDto(56.130, 9.610),
                    GeoPointDto(56.130, 9.710),
                    GeoPointDto(56.105, 9.710)
                ),
                tileId = "water_tile_seed_silkeborg",
                fetchedAt = now
            ),
            waterRepo.buildEntity(
                id = "river_gudenaaen_seed",
                osmId = 910003L,
                name = "Gudenåen",
                featureType = com.example.data.model.WaterFeatureType.RIVER,
                subType = "river",
                pts = listOf(
                    GeoPointDto(55.980, 9.680),
                    GeoPointDto(56.090, 9.755),
                    GeoPointDto(56.165, 9.550),
                    GeoPointDto(56.380, 9.650),
                    GeoPointDto(56.455, 10.035)
                ),
                tileId = "water_tile_seed_gudenaa",
                fetchedAt = now
            ),
            waterRepo.buildEntity(
                id = "lake_esrum_soe_seed",
                osmId = 910004L,
                name = "Esrum Sø",
                featureType = com.example.data.model.WaterFeatureType.LAKE,
                subType = "water",
                pts = listOf(
                    GeoPointDto(55.960, 12.350),
                    GeoPointDto(56.040, 12.350),
                    GeoPointDto(56.040, 12.410),
                    GeoPointDto(55.960, 12.410)
                ),
                tileId = "water_tile_seed_esrum",
                fetchedAt = now
            ),
            waterRepo.buildEntity(
                id = "spring_blaa_kilde_seed",
                osmId = 910005L,
                name = "Blåkilde & Ravnkilde",
                featureType = com.example.data.model.WaterFeatureType.SPRING,
                subType = "spring",
                pts = listOf(GeoPointDto(56.815, 9.860)),
                tileId = "water_tile_seed_spring",
                fetchedAt = now
            )
        )
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
    suspend fun syncCorridorPoisForRoute(
        routeId: String,
        points: List<GeoPointDto>,
        fetchRemoteOsm: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (points.isEmpty()) return@withContext Result.success(Unit)

            // 1. Ensure datasets are in Room (use local preseed first for instant offline response)
            if (dao.getShelterCount() == 0) {
                initializePreseededDataIfEmpty()
                if (fetchRemoteOsm && dao.getShelterCount() == 0) {
                    val gfShelters = geoFaRepository.fetchGeoFaShelters()
                    val gfFri = geoFaRepository.fetchGeoFaFriTeltning()
                    val gfPois = geoFaRepository.fetchGeoFaPois()
                    if (gfShelters.isNotEmpty()) dao.insertShelters(gfShelters)
                    if (gfFri.isNotEmpty()) dao.insertFriTeltning(gfFri)
                    if (gfPois.isNotEmpty()) dao.insertPois(gfPois)
                }
            }

            val existingShelters = dao.getAllSheltersList().toMutableList()
            val existingPois = dao.getAllPoisList().toMutableList()

            // 2. Optionally fetch OSM POIs along route polyline when online sync is requested
            if (fetchRemoteOsm) {
                val osmResult = osmPoiRepository.fetchPoisForPolyline(points)

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
            }

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
        if (dao.getShelterCount() == 0) {
            initializePreseededDataIfEmpty()
        }
        if (trailId.startsWith("route_")) {
            return@withContext dao.getSheltersForRoute(trailId.removePrefix("route_"), 3000.0)
        }
        val joined = dao.getSheltersForTrail(trailId)
        if (joined.isNotEmpty()) return@withContext joined

        val trail = dao.getTrailById(trailId) ?: return@withContext emptyList()
        val allShelters = dao.getAllSheltersList()
        if (trail.bboxSouth != null && trail.bboxNorth != null && trail.bboxWest != null && trail.bboxEast != null) {
            allShelters.filter { s ->
                s.lat in (trail.bboxSouth - 0.03)..(trail.bboxNorth + 0.03) &&
                    s.lng in (trail.bboxWest - 0.05)..(trail.bboxEast + 0.05)
            }
        } else {
            emptyList()
        }
    }

    suspend fun getSheltersForRoute(routeId: String, maxDetour: Double = 3000.0): List<ShelterEntity> = withContext(Dispatchers.IO) {
        dao.getSheltersForRoute(routeId, maxDetour)
    }

    suspend fun getFriTeltningForTrail(trailId: String): List<FriTeltningEntity> = withContext(Dispatchers.IO) {
        if (dao.getFriTeltningCount() == 0) {
            initializePreseededDataIfEmpty()
        }
        dao.getAllFriTeltningList()
    }

    suspend fun getPoisForTrail(trailId: String): List<PoiEntity> = withContext(Dispatchers.IO) {
        if (dao.getPoiCount() == 0) {
            initializePreseededDataIfEmpty()
        }
        if (trailId.startsWith("route_")) {
            return@withContext dao.getPoisForRoute(trailId.removePrefix("route_"), 3000.0)
        }
        val joined = dao.getPoisForTrail(trailId)
        if (joined.isNotEmpty()) return@withContext joined

        val trail = dao.getTrailById(trailId) ?: return@withContext emptyList()
        val allPois = dao.getAllPoisList()
        if (trail.bboxSouth != null && trail.bboxNorth != null && trail.bboxWest != null && trail.bboxEast != null) {
            allPois.filter { p ->
                p.lat in (trail.bboxSouth - 0.03)..(trail.bboxNorth + 0.03) &&
                    p.lng in (trail.bboxWest - 0.05)..(trail.bboxEast + 0.05)
            }
        } else {
            emptyList()
        }
    }

    suspend fun getPoisForRoute(routeId: String, maxDetour: Double = 3000.0): List<PoiEntity> = withContext(Dispatchers.IO) {
        dao.getPoisForRoute(routeId, maxDetour)
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
     * Requirement D & G: Saves a trip plan for a route (TRAIL or FREE mode) linked via customRouteId.
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
        startTime: String = "09:00",
        customRouteId: Long? = routeId.toLongOrNull(),
        baseTrailId: String? = null,
        startKm: Double = 0.0,
        endKm: Double = totalKm,
        isReverse: Boolean = false,
        preferredDailyKm: Double = 15.0
    ): Long = withContext(Dispatchers.IO) {
        val totalDist = if (stages.isNotEmpty()) stages.sumOf { it.stageKm } else totalKm
        val effectiveTrailId = baseTrailId?.takeIf { it.isNotBlank() } ?: "route_$routeId"
        val plan = TripPlanEntity(
            trailId = effectiveTrailId,
            trailName = routeName,
            startKm = startKm,
            endKm = if (endKm > 0.0) endKm else totalDist,
            isReverse = isReverse,
            totalDistanceKm = totalDist,
            targetDays = max(1, stages.size.takeIf { it > 0 } ?: days),
            overnightType = overnightType.name,
            preferredDailyKm = if (preferredDailyKm > 0.0) preferredDailyKm else (totalDist / max(1, days)),
            stagesJson = serializeStages(stages),
            paceKmh = paceKmh,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            customRouteId = customRouteId
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

    suspend fun hasWalkingProgressForCustomRoute(routeId: Long): Boolean = withContext(Dispatchers.IO) {
        val existingPlans = dao.getAllTripPlansList().filter {
            it.customRouteId == routeId || it.trailId == "route_$routeId" || it.trailId == routeId.toString()
        }
        existingPlans.any { plan ->
            val progress = dao.getTripProgressSync(plan.id)
            val dayStats = dao.getAllRecordedDayStatsSync(plan.id)
            (progress != null && (progress.totalDistanceWalkedKm > 0.0 || progress.distanceWalkedTodayKm > 0.0 || progress.currentDay > 1)) ||
                dayStats.any { it.distanceKm > 0.0 }
        }
    }

    /**
     * Requirement G: Gem builds the TripPlan, the stages (from OVERNIGHT pins) and the PlannedRoute
     * in ONE place (PlannedRouteRepository) from the composed legs.
     */
    suspend fun ensureTripPlanForCustomRoute(
        route: com.example.data.model.CustomRouteEntity,
        forceRecompute: Boolean = false,
        paceKmh: Double = 4.0,
        dailyTargetKm: Double = 15.0,
        maxDetourKm: Double = 1.5,
        startTime: String = "09:00",
        overnightType: OvernightType = OvernightType.BOTH,
        sunsetTime: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val existingPlans = dao.getAllTripPlansList().filter {
            it.customRouteId == route.id || it.trailId == "route_${route.id}" || it.trailId == route.id.toString()
        }
        if (!forceRecompute && existingPlans.isNotEmpty()) {
            return@withContext existingPlans.first().id
        }
        if (forceRecompute && existingPlans.isNotEmpty()) {
            existingPlans.forEach { deleteTripPlan(it.id) }
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

        val points = geom.segments.flatMap { it.points }
        if (points.isNotEmpty()) {
            syncCorridorPoisForRoute(route.id.toString(), points)
        }

        val effectiveTrailId = route.baseTrailId?.takeIf { it.isNotBlank() } ?: "route_${route.id}"
        val plannedRouteRepo = PlannedRouteRepository(context)
        val (stages, builtPlannedRoute) = plannedRouteRepo.buildStagesAndPlannedRoute(
            planId = 0L,
            trailId = effectiveTrailId,
            trailName = route.name,
            isReverse = route.isReverse,
            waypoints = wps,
            orderedLegs = orderedLegs,
            paceKmh = paceKmh,
            dailyTargetKm = dailyTargetKm,
            overnightType = overnightType,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            shelters = dao.getAllSheltersList(),
            friTeltnings = dao.getAllFriTeltningList(),
            pois = dao.getAllPoisList(),
            sunsetTime = sunsetTime
        )

        val planId = saveTripPlanForRoute(
            routeId = route.id.toString(),
            routeName = route.name,
            totalKm = builtPlannedRoute.totalWalkKm,
            days = stages.size,
            overnightType = overnightType,
            stages = stages,
            paceKmh = paceKmh,
            maxDetourKm = maxDetourKm,
            startTime = startTime,
            customRouteId = route.id,
            baseTrailId = route.baseTrailId,
            startKm = builtPlannedRoute.startTrailKm,
            endKm = builtPlannedRoute.endTrailKm,
            isReverse = route.isReverse,
            preferredDailyKm = dailyTargetKm
        )

        val fullPlannedRoute = builtPlannedRoute.copy(planId = planId, stages = stages)
        plannedRouteRepo.savePlannedRoute(fullPlannedRoute)

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
        val archiveFile = com.example.util.OfflineDownloadManager.getOfflineArchiveFile(context, trailId)
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        dao.deleteDownloadedTrail(trailId)
    }

    // --- Saved Trails (Phase 6) ---
    fun getAllSavedTrails(): Flow<List<com.example.data.model.SavedTrailEntity>> = dao.getAllSavedTrails()

    fun getSavedTrails(): Flow<List<TrailEntity>> =
        kotlinx.coroutines.flow.combine(dao.getAllTrails(), dao.getAllSavedTrails()) { trails, saved ->
            val savedIds = saved.map { it.trailId }.toSet()
            trails.filter { it.id in savedIds }
        }

    fun getAllSheltersFlow(): Flow<List<ShelterEntity>> = dao.getAllShelters()

    fun getAllFriTeltningFlow(): Flow<List<FriTeltningEntity>> = dao.getAllFriTeltning()

    fun getAllPoisFlow(): Flow<List<PoiEntity>> = dao.getAllPois()

    suspend fun getAllFriTeltning(): List<FriTeltningEntity> = getAllFriTeltningList()

    suspend fun getShelterCountForTrail(trailId: String): Int = withContext(Dispatchers.IO) {
        val attached = dao.getSheltersForTrail(trailId)
        if (attached.isNotEmpty()) {
            attached.size
        } else {
            val trail = dao.getTrailById(trailId)
            val allSh = dao.getAllSheltersList()
            if (trail?.bboxSouth != null && trail.bboxNorth != null && trail.bboxWest != null && trail.bboxEast != null) {
                allSh.count { s ->
                    s.lat in (trail.bboxSouth - 0.03)..(trail.bboxNorth + 0.03) &&
                        s.lng in (trail.bboxWest - 0.05)..(trail.bboxEast + 0.05)
                }
            } else {
                0
            }
        }
    }

    suspend fun saveTrail(trailId: String) = withContext(Dispatchers.IO) {
        dao.insertSavedTrail(com.example.data.model.SavedTrailEntity(trailId = trailId))
    }

    suspend fun unsaveTrail(trailId: String) = withContext(Dispatchers.IO) {
        dao.deleteSavedTrail(trailId)
    }

    suspend fun searchPlacesInDenmark(query: String): List<com.example.data.remote.PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 2) return@withContext emptyList()
            val results = mutableListOf<com.example.data.remote.PlaceSearchResult>()

            // 1. Shelters matching query
            dao.getAllSheltersList()
                .filter { it.name.contains(q, ignoreCase = true) }
                .take(5)
                .forEach { s ->
                    results.add(
                        com.example.data.remote.PlaceSearchResult(
                            displayName = "${s.name} (Shelter)",
                            lat = s.lat,
                            lng = s.lng,
                            type = "shelter"
                        )
                    )
                }

            // 2. Fri teltning matching query
            dao.getAllFriTeltningList()
                .filter { it.name.contains(q, ignoreCase = true) }
                .take(4)
                .forEach { ft ->
                    results.add(
                        com.example.data.remote.PlaceSearchResult(
                            displayName = "${ft.name} (Fri teltning)",
                            lat = ft.centerLat,
                            lng = ft.centerLng,
                            type = "fri_teltning"
                        )
                    )
                }

            // 3. POIs matching query
            dao.getAllPoisList()
                .filter { it.name.contains(q, ignoreCase = true) }
                .take(4)
                .forEach { poi ->
                    results.add(
                        com.example.data.remote.PlaceSearchResult(
                            displayName = poi.name,
                            lat = poi.lat,
                            lng = poi.lng,
                            type = poi.type
                        )
                    )
                }

            results.take(8)
        }

    suspend fun downloadTrailOffline(
        trailId: String,
        corridorKm: Double = 1.0,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val trail = getTrailById(trailId) ?: return@withContext
        onProgress("Henter offline-kort for ${trail.name}...")
        com.example.util.OfflineDownloadManager.enqueueDownload(
            context = context,
            trailId = trailId,
            trailName = trail.name,
            corridorKm = corridorKm
        )
    }

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
        if (dao.getShelterCount() == 0) {
            initializePreseededDataIfEmpty()
        }

        // Sanitize out-of-Denmark coordinates (e.g. Android Emulator default California GPS)
        val inDenmark = userLat in 54.4..58.0 && userLng in 7.8..15.5
        val effectiveLat = if (inDenmark) userLat else 56.05
        val effectiveLng = if (inDenmark) userLng else 9.65

        val maxMeters = maxRadiusKm * 1000.0
        val results = mutableListOf<com.example.data.model.NearbyOvernightSpot>()
        val allCandidates = mutableListOf<com.example.data.model.NearbyOvernightSpot>()

        // 1. Shelters
        val shelters = dao.getAllSheltersList()
        for (s in shelters) {
            val d = GeoUtils.distanceMeters(effectiveLat, effectiveLng, s.lat, s.lng)
            val spot = com.example.data.model.NearbyOvernightSpot(
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
            allCandidates.add(spot)
            if (d <= maxMeters) {
                results.add(spot)
            }
        }

        // 2. Fri Teltning
        val friTeltnings = dao.getAllFriTeltningList()
        for (ft in friTeltnings) {
            val d = GeoUtils.distanceMeters(effectiveLat, effectiveLng, ft.centerLat, ft.centerLng)
            val spot = com.example.data.model.NearbyOvernightSpot(
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
            allCandidates.add(spot)
            if (d <= maxMeters) {
                results.add(spot)
            }
        }

        if (results.isNotEmpty()) {
            results.sortBy { it.distanceMeters }
            results
        } else {
            allCandidates.sortedBy { it.distanceMeters }.take(15)
        }
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


