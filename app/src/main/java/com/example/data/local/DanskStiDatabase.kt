package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.example.data.model.TrailPoiEntity
import com.example.data.model.TripPlanEntity
import com.example.data.model.TripProgressEntity
import com.example.data.model.TripStageEntity
import com.example.data.model.UserHazardNoteEntity

@Database(
    entities = [
        TrailEntity::class,
        ShelterEntity::class,
        FriTeltningEntity::class,
        PoiEntity::class,
        TrailPoiEntity::class,
        TripPlanEntity::class,
        TripStageEntity::class,
        TripProgressEntity::class,
        UserHazardNoteEntity::class,
        com.example.data.model.DownloadedTrailEntity::class,
        com.example.data.model.SavedTrailEntity::class,
        com.example.data.model.TripGearItemEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class DanskStiDatabase : RoomDatabase() {
    abstract fun dao(): DanskStiDao

    companion object {
        @Volatile
        private var INSTANCE: DanskStiDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trails ADD COLUMN parentRegion TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE trails SET parentRegion = 'Jylland' WHERE region IN ('Jylland', 'Sønderjylland', 'Nordjylland', 'Østjylland', 'Midtjylland', 'Vestjylland')")
                db.execSQL("UPDATE trails SET parentRegion = 'Fyn & Øerne' WHERE region = 'Fyn & Øerne'")
                db.execSQL("UPDATE trails SET parentRegion = 'Bornholm' WHERE region = 'Bornholm'")
                db.execSQL("UPDATE trails SET parentRegion = 'Sjælland' WHERE region IN ('Sjælland', 'Møn & Sydsjælland', 'Hovedstaden', 'Midtsjælland', 'Nordsjælland')")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_hazard_notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        trailId TEXT NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trails_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        region TEXT NOT NULL,
                        parentRegion TEXT NOT NULL,
                        osmRelationId INTEGER,
                        computedWalkingKm REAL NOT NULL DEFAULT 0.0,
                        gapsOver200m INTEGER NOT NULL DEFAULT 0,
                        osmTimestamp TEXT,
                        bboxNorth REAL,
                        bboxSouth REAL,
                        bboxEast REAL,
                        bboxWest REAL,
                        isGeometryDownloaded INTEGER NOT NULL DEFAULT 0,
                        lastUpdated INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO trails_new (id, name, description, region, parentRegion, osmRelationId, bboxNorth, bboxSouth, bboxEast, bboxWest, lastUpdated)
                    SELECT id, name, description, region, parentRegion, osmRelationId, bboxNorth, bboxSouth, bboxEast, bboxWest, lastUpdated FROM trails
                """.trimIndent())
                db.execSQL("DROP TABLE trails")
                db.execSQL("ALTER TABLE trails_new RENAME TO trails")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trail_pois (
                        trailId TEXT NOT NULL,
                        poiId TEXT NOT NULL,
                        kmOnRoute REAL NOT NULL,
                        detourMeters REAL NOT NULL,
                        PRIMARY KEY(trailId, poiId)
                    )
                """.trimIndent())

                try {
                    db.execSQL("ALTER TABLE fri_teltning ADD COLUMN source TEXT NOT NULL DEFAULT 'geofa'")
                } catch (e: Exception) {}

                try {
                    db.execSQL("ALTER TABLE pois ADD COLUMN openingHours TEXT")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE pois ADD COLUMN operator TEXT")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE pois ADD COLUMN website TEXT")
                } catch (e: Exception) {}
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE shelters ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE fri_teltning ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE pois ADD COLUMN source TEXT NOT NULL DEFAULT 'geofa'")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE pois ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {}
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS downloaded_trails (
                        trailId TEXT NOT NULL PRIMARY KEY,
                        trailName TEXT NOT NULL,
                        downloadedAt INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        tileCount INTEGER NOT NULL,
                        corridorKm REAL NOT NULL,
                        minZoom INTEGER NOT NULL,
                        maxZoom INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE trip_plans ADD COLUMN paceKmh REAL NOT NULL DEFAULT 4.0")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE trip_plans ADD COLUMN maxDetourKm REAL NOT NULL DEFAULT 1.5")
                } catch (e: Exception) {}
                try {
                    db.execSQL("ALTER TABLE trip_plans ADD COLUMN startTime TEXT NOT NULL DEFAULT '09:00'")
                } catch (e: Exception) {}
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_stages (
                        planId INTEGER NOT NULL,
                        dayNumber INTEGER NOT NULL,
                        startKm REAL NOT NULL,
                        endKm REAL NOT NULL,
                        stageKm REAL NOT NULL,
                        stopName TEXT NOT NULL,
                        stopType TEXT NOT NULL,
                        stopLat REAL NOT NULL,
                        stopLng REAL NOT NULL,
                        bookingRequired INTEGER NOT NULL DEFAULT 0,
                        hasWaterNearby INTEGER NOT NULL DEFAULT 0,
                        hasToiletNearby INTEGER NOT NULL DEFAULT 0,
                        startTrailKm REAL NOT NULL DEFAULT 0.0,
                        endTrailKm REAL NOT NULL DEFAULT 0.0,
                        fromName TEXT NOT NULL DEFAULT '',
                        toName TEXT NOT NULL DEFAULT '',
                        detourMeters REAL NOT NULL DEFAULT 0.0,
                        ascentMeters REAL NOT NULL DEFAULT 0.0,
                        descentMeters REAL NOT NULL DEFAULT 0.0,
                        estimatedWalkMinutes INTEGER NOT NULL DEFAULT 0,
                        waterCount INTEGER NOT NULL DEFAULT 0,
                        shopCount INTEGER NOT NULL DEFAULT 0,
                        sunsetTime TEXT NOT NULL DEFAULT '',
                        isAfterSunset INTEGER NOT NULL DEFAULT 0,
                        spotId TEXT,
                        isWarningNoOvernight INTEGER NOT NULL DEFAULT 0,
                        warningMessage TEXT,
                        PRIMARY KEY(planId, dayNumber)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_trails (
                        trailId TEXT NOT NULL PRIMARY KEY,
                        savedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_gear_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        planId INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        title TEXT NOT NULL,
                        isPacked INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): DanskStiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DanskStiDatabase::class.java,
                    "dansk_sti.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
                )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
