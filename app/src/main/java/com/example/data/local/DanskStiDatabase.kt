package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity

@Database(
    entities = [
        TrailEntity::class,
        ShelterEntity::class,
        FriTeltningEntity::class,
        PoiEntity::class,
        TripPlanEntity::class,
        TripProgressEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class DanskStiDatabase : RoomDatabase() {
    abstract fun dao(): DanskStiDao

    companion object {
        @Volatile
        private var INSTANCE: DanskStiDatabase? = null

        fun getInstance(context: Context): DanskStiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DanskStiDatabase::class.java,
                    "dansk_sti.db"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
