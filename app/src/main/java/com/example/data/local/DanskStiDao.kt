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
import kotlinx.coroutines.flow.Flow

@Dao
interface DanskStiDao {

    // Trails
    @Query("SELECT * FROM trails ORDER BY lengthKm DESC")
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
}
