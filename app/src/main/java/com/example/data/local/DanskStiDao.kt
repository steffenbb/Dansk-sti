package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CachedRouteLegEntity
import com.example.data.model.CustomRouteEntity
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.UserHazardNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DanskStiDao {

    // Trails
    @Query("SELECT * FROM trails ORDER BY computedWalkingKm DESC")
    fun getAllTrails(): Flow<List<TrailEntity>>

    @Query("SELECT * FROM trails WHERE id = :id")
    suspend fun getTrailById(id: String): TrailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrails(trails: List<TrailEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrail(trail: TrailEntity)

    @Query("SELECT COUNT(*) FROM trails")
    suspend fun getTrailCount(): Int

    // Shelters
    @Query("SELECT * FROM shelters")
    fun getAllShelters(): Flow<List<ShelterEntity>>

    @Query("SELECT * FROM shelters")
    suspend fun getAllSheltersList(): List<ShelterEntity>

    @Query("SELECT * FROM shelters WHERE trailCorridorId = :trailId OR trailCorridorId IS NULL")
    suspend fun getSheltersForCorridor(trailId: String): List<ShelterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelters(shelters: List<ShelterEntity>)

    @Query("SELECT COUNT(*) FROM shelters")
    suspend fun getShelterCount(): Int

    // Fri Teltning
    @Query("SELECT * FROM fri_teltning")
    fun getAllFriTeltning(): Flow<List<FriTeltningEntity>>

    @Query("SELECT * FROM fri_teltning")
    suspend fun getAllFriTeltningList(): List<FriTeltningEntity>

    @Query("SELECT * FROM fri_teltning WHERE trailCorridorId = :trailId OR trailCorridorId IS NULL")
    suspend fun getFriTeltningForCorridor(trailId: String): List<FriTeltningEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriTeltning(items: List<FriTeltningEntity>)

    @Query("SELECT COUNT(*) FROM fri_teltning")
    suspend fun getFriTeltningCount(): Int

    // POIs (Toilets, Water)
    @Query("SELECT * FROM pois")
    fun getAllPois(): Flow<List<PoiEntity>>

    @Query("SELECT * FROM pois")
    suspend fun getAllPoisList(): List<PoiEntity>

    @Query("SELECT * FROM pois WHERE type = :type")
    suspend fun getPoisByType(type: String): List<PoiEntity>

    @Query("SELECT COUNT(*) FROM pois")
    suspend fun getPoiCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<PoiEntity>)

    // Trip Plans
    @Query("SELECT * FROM trip_plans ORDER BY createdAt DESC")
    fun getAllTripPlans(): Flow<List<TripPlanEntity>>

    @Query("SELECT * FROM trip_plans ORDER BY createdAt DESC")
    suspend fun getAllTripPlansList(): List<TripPlanEntity>

    @Query("SELECT * FROM trip_plans WHERE id = :id")
    suspend fun getTripPlanById(id: Long): TripPlanEntity?

    @Query("SELECT * FROM trip_plans ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestTripPlan(): TripPlanEntity?

    @Query("SELECT * FROM trip_plans WHERE trailId = :trailId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestTripPlanForTrail(trailId: String): TripPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTripPlan(plan: TripPlanEntity): Long

    @Query("DELETE FROM trip_plans WHERE id = :id")
    suspend fun deleteTripPlan(id: Long)

    // Trip Stages (Phase 5)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTripStages(stages: List<com.example.data.model.TripStageEntity>)

    @Query("SELECT * FROM trip_stages WHERE planId = :planId ORDER BY dayNumber ASC")
    fun getTripStagesForPlan(planId: Long): Flow<List<com.example.data.model.TripStageEntity>>

    @Query("SELECT * FROM trip_stages WHERE planId = :planId ORDER BY dayNumber ASC")
    suspend fun getTripStagesForPlanSync(planId: Long): List<com.example.data.model.TripStageEntity>

    @Query("DELETE FROM trip_stages WHERE planId = :planId")
    suspend fun deleteTripStages(planId: Long)

    // Trip Progress
    @Query("SELECT * FROM trip_progress WHERE planId = :planId")
    fun getTripProgress(planId: Long): Flow<TripProgressEntity?>

    @Query("SELECT * FROM trip_progress WHERE planId = :planId")
    suspend fun getTripProgressSync(planId: Long): TripProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTripProgress(progress: TripProgressEntity)

    @Update
    suspend fun updateTripProgress(progress: TripProgressEntity)

    @Query("DELETE FROM trip_progress WHERE planId = :planId")
    suspend fun deleteTripProgress(planId: Long)

    // User Hazard Notes (Mine noter)
    @Query("SELECT * FROM user_hazard_notes WHERE trailId = :trailId ORDER BY createdAt DESC")
    fun getHazardNotesForTrail(trailId: String): Flow<List<UserHazardNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHazardNote(note: UserHazardNoteEntity)

    @Query("DELETE FROM user_hazard_notes WHERE id = :id")
    suspend fun deleteHazardNote(id: String)

    // Trail POIs Join Table (Phase 2)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrailPois(items: List<com.example.data.model.TrailPoiEntity>)

    @Query("""
        SELECT s.* FROM shelters s
        INNER JOIN trail_pois tp ON s.id = tp.poiId
        WHERE (tp.trailId = :trailId OR tp.routeId = :trailId) AND tp.detourMeters <= 3000
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getSheltersForTrail(trailId: String): List<ShelterEntity>

    @Query("""
        SELECT p.* FROM pois p
        INNER JOIN trail_pois tp ON p.id = tp.poiId
        WHERE (tp.trailId = :trailId OR tp.routeId = :trailId) AND tp.detourMeters <= 3000
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getPoisForTrail(trailId: String): List<PoiEntity>

    @Query("""
        SELECT s.* FROM shelters s
        INNER JOIN trail_pois tp ON s.id = tp.poiId
        WHERE (tp.routeId = :routeId OR tp.trailId = :routeId) AND tp.detourMeters <= :maxDetour
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getSheltersForRoute(routeId: String, maxDetour: Double = 3000.0): List<ShelterEntity>

    @Query("""
        SELECT p.* FROM pois p
        INNER JOIN trail_pois tp ON p.id = tp.poiId
        WHERE (tp.routeId = :routeId OR tp.trailId = :routeId) AND tp.detourMeters <= :maxDetour
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getPoisForRoute(routeId: String, maxDetour: Double = 3000.0): List<PoiEntity>

    @Query("DELETE FROM trail_pois WHERE routeId = :routeId OR trailId = :routeId")
    suspend fun deletePoisForRoute(routeId: String)

    // Downloaded Offline Trails (Phase 4)
    @Query("SELECT * FROM downloaded_trails ORDER BY downloadedAt DESC")
    fun getAllDownloadedTrails(): kotlinx.coroutines.flow.Flow<List<com.example.data.model.DownloadedTrailEntity>>

    @Query("SELECT * FROM downloaded_trails WHERE trailId = :trailId LIMIT 1")
    suspend fun getDownloadedTrail(trailId: String): com.example.data.model.DownloadedTrailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedTrail(trail: com.example.data.model.DownloadedTrailEntity)

    @Query("DELETE FROM downloaded_trails WHERE trailId = :trailId")
    suspend fun deleteDownloadedTrail(trailId: String)

    // Saved Trails (Phase 6)
    @Query("SELECT * FROM saved_trails ORDER BY savedAt DESC")
    fun getAllSavedTrails(): kotlinx.coroutines.flow.Flow<List<com.example.data.model.SavedTrailEntity>>

    @Query("SELECT * FROM saved_trails")
    suspend fun getAllSavedTrailsList(): List<com.example.data.model.SavedTrailEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_trails WHERE trailId = :trailId)")
    fun isTrailSaved(trailId: String): kotlinx.coroutines.flow.Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedTrail(saved: com.example.data.model.SavedTrailEntity)

    @Query("DELETE FROM saved_trails WHERE trailId = :trailId")
    suspend fun deleteSavedTrail(trailId: String)

    // Trip Gear Checklist Items (Phase 6)
    @Query("SELECT * FROM trip_gear_items WHERE planId = :planId ORDER BY category ASC, title ASC")
    fun getGearItemsForPlan(planId: Long): kotlinx.coroutines.flow.Flow<List<com.example.data.model.TripGearItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGearItems(items: List<com.example.data.model.TripGearItemEntity>)

    @androidx.room.Update
    suspend fun updateGearItem(item: com.example.data.model.TripGearItemEntity)

    @Query("DELETE FROM trip_gear_items WHERE id = :id")
    suspend fun deleteGearItem(id: String)

    // Recorded GPS Track Points & Day Stats (Phase 7a)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordedTrackPoint(point: com.example.data.model.RecordedTrackPointEntity): Long

    @Query("SELECT * FROM recorded_track_points WHERE planId = :planId AND dayNumber = :dayNumber ORDER BY timestamp ASC")
    fun getRecordedTrackPointsForDay(planId: Long, dayNumber: Int): kotlinx.coroutines.flow.Flow<List<com.example.data.model.RecordedTrackPointEntity>>

    @Query("SELECT * FROM recorded_track_points WHERE planId = :planId AND dayNumber = :dayNumber ORDER BY timestamp ASC")
    suspend fun getRecordedTrackPointsForDaySync(planId: Long, dayNumber: Int): List<com.example.data.model.RecordedTrackPointEntity>

    @Query("SELECT * FROM recorded_track_points WHERE planId = :planId ORDER BY timestamp ASC")
    suspend fun getAllRecordedTrackPointsForPlanSync(planId: Long): List<com.example.data.model.RecordedTrackPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecordedDayStat(stat: com.example.data.model.RecordedDayStatEntity)

    @Query("SELECT * FROM recorded_day_stats WHERE planId = :planId AND dayNumber = :dayNumber LIMIT 1")
    fun getRecordedDayStat(planId: Long, dayNumber: Int): kotlinx.coroutines.flow.Flow<com.example.data.model.RecordedDayStatEntity?>

    @Query("SELECT * FROM recorded_day_stats WHERE planId = :planId AND dayNumber = :dayNumber LIMIT 1")
    suspend fun getRecordedDayStatSync(planId: Long, dayNumber: Int): com.example.data.model.RecordedDayStatEntity?

    @Query("SELECT * FROM recorded_day_stats WHERE planId = :planId ORDER BY dayNumber ASC")
    fun getAllRecordedDayStats(planId: Long): kotlinx.coroutines.flow.Flow<List<com.example.data.model.RecordedDayStatEntity>>

    @Query("SELECT * FROM recorded_day_stats WHERE planId = :planId ORDER BY dayNumber ASC")
    suspend fun getAllRecordedDayStatsSync(planId: Long): List<com.example.data.model.RecordedDayStatEntity>

    @Query("DELETE FROM recorded_track_points WHERE planId = :planId")
    suspend fun deleteRecordedTrackPoints(planId: Long)

    @Query("DELETE FROM recorded_day_stats WHERE planId = :planId")
    suspend fun deleteRecordedDayStats(planId: Long)

    // Routing Cache (Phase 8)
    @Query("SELECT * FROM cached_route_legs WHERE cacheKey = :key LIMIT 1")
    suspend fun getCachedRouteLeg(key: String): com.example.data.model.CachedRouteLegEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedRouteLeg(entity: com.example.data.model.CachedRouteLegEntity)

    @Query("DELETE FROM cached_route_legs WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredRouteLegs(currentTime: Long)

    // Custom Routes (Phase 9 - Rutebygger)
    @Query("SELECT * FROM custom_routes ORDER BY updatedAt DESC")
    fun getAllCustomRoutes(): kotlinx.coroutines.flow.Flow<List<com.example.data.model.CustomRouteEntity>>

    @Query("SELECT * FROM custom_routes WHERE id = :id LIMIT 1")
    suspend fun getCustomRouteById(id: Long): com.example.data.model.CustomRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomRoute(route: com.example.data.model.CustomRouteEntity): Long

    @Update
    suspend fun updateCustomRoute(route: com.example.data.model.CustomRouteEntity)

    @Delete
    suspend fun deleteCustomRoute(route: com.example.data.model.CustomRouteEntity)

    @Query("DELETE FROM custom_routes WHERE id = :id")
    suspend fun deleteCustomRouteById(id: Long)

    // Access Polygons (Phase 10)
    @Query("SELECT * FROM access_polygons WHERE minLat <= :maxLat AND maxLat >= :minLat AND minLng <= :maxLng AND maxLng >= :minLng")
    suspend fun getAccessPolygonsInBoundingBox(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<com.example.data.model.AccessPolygonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccessPolygons(polygons: List<com.example.data.model.AccessPolygonEntity>)

    @Query("SELECT * FROM fri_teltning")
    suspend fun getAllFriTeltningDirect(): List<com.example.data.model.FriTeltningEntity>

    // User Custom Waypoints (Phase 12 Feature 6: Mine punkter)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomWaypoint(waypoint: com.example.data.model.UserCustomWaypointEntity)

    @Query("SELECT * FROM user_custom_waypoints WHERE routeId = :routeId ORDER BY createdAt ASC")
    fun observeCustomWaypointsForRoute(routeId: Long): kotlinx.coroutines.flow.Flow<List<com.example.data.model.UserCustomWaypointEntity>>

    @Query("SELECT * FROM user_custom_waypoints WHERE routeId = :routeId ORDER BY createdAt ASC")
    suspend fun getCustomWaypointsForRoute(routeId: Long): List<com.example.data.model.UserCustomWaypointEntity>

    @Query("SELECT * FROM user_custom_waypoints ORDER BY createdAt ASC")
    fun getAllCustomWaypoints(): kotlinx.coroutines.flow.Flow<List<com.example.data.model.UserCustomWaypointEntity>>

    @Query("SELECT * FROM user_custom_waypoints ORDER BY createdAt ASC")
    suspend fun getAllCustomWaypointsList(): List<com.example.data.model.UserCustomWaypointEntity>

    @Query("DELETE FROM user_custom_waypoints WHERE id = :id")
    suspend fun deleteCustomWaypointById(id: String)

    // Marked Routes (Phase 13: Afmærkede ruter)
    @Query("SELECT * FROM marked_routes WHERE minLat <= :maxLat AND maxLat >= :minLat AND minLng <= :maxLng AND maxLng >= :minLng")
    suspend fun getMarkedRoutesInBbox(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<com.example.data.model.MarkedRouteEntity>

    @Query("SELECT * FROM marked_routes WHERE osmRelationId = :relationId LIMIT 1")
    suspend fun getMarkedRouteById(relationId: Long): com.example.data.model.MarkedRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkedRoutes(routes: List<com.example.data.model.MarkedRouteEntity>)

    @Query("SELECT * FROM marked_route_cache_boxes WHERE minLat <= :maxLat AND maxLat >= :minLat AND minLng <= :maxLng AND maxLng >= :minLng")
    suspend fun getMarkedRouteCacheBoxes(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<com.example.data.model.MarkedRouteCacheBoxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkedRouteCacheBox(box: com.example.data.model.MarkedRouteCacheBoxEntity)

    @Query("DELETE FROM marked_routes WHERE cachedAt < :expiredTimestamp")
    suspend fun deleteExpiredMarkedRoutes(expiredTimestamp: Long)

    @Query("DELETE FROM marked_route_cache_boxes WHERE fetchedAt < :expiredTimestamp")
    suspend fun deleteExpiredMarkedRouteCacheBoxes(expiredTimestamp: Long)
}
