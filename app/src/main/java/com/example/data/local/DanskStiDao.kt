package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    // POIs (Toilets, Water)
    @Query("SELECT * FROM pois")
    fun getAllPois(): Flow<List<PoiEntity>>

    @Query("SELECT * FROM pois")
    suspend fun getAllPoisList(): List<PoiEntity>

    @Query("SELECT * FROM pois WHERE type = :type")
    suspend fun getPoisByType(type: String): List<PoiEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPois(pois: List<PoiEntity>)

    // Trip Plans
    @Query("SELECT * FROM trip_plans ORDER BY createdAt DESC")
    fun getAllTripPlans(): Flow<List<TripPlanEntity>>

    @Query("SELECT * FROM trip_plans WHERE id = :id")
    suspend fun getTripPlanById(id: Long): TripPlanEntity?

    @Query("SELECT * FROM trip_plans ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestTripPlan(): TripPlanEntity?

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
        WHERE tp.trailId = :trailId AND tp.detourMeters <= 3000
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getSheltersForTrail(trailId: String): List<ShelterEntity>

    @Query("""
        SELECT p.* FROM pois p
        INNER JOIN trail_pois tp ON p.id = tp.poiId
        WHERE tp.trailId = :trailId AND tp.detourMeters <= 3000
        ORDER BY tp.kmOnRoute ASC
    """)
    suspend fun getPoisForTrail(trailId: String): List<PoiEntity>

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
}
