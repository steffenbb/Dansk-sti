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
        com.example.data.model.TripGearItemEntity::class,
        com.example.data.model.RecordedTrackPointEntity::class,
        com.example.data.model.RecordedDayStatEntity::class,
        com.example.data.model.CachedRouteLegEntity::class,
        com.example.data.model.CustomRouteEntity::class,
        com.example.data.model.AccessPolygonEntity::class,
        com.example.data.model.UserCustomWaypointEntity::class,
        com.example.data.model.MarkedRouteEntity::class,
        com.example.data.model.MarkedRouteCacheBoxEntity::class,
        com.example.data.model.WaterFeatureEntity::class,
        com.example.data.model.WaterTileCacheEntity::class
    ],
    version = 23,
    exportSchema = true
)
abstract class DanskStiDatabase : RoomDatabase() {
    abstract fun dao(): DanskStiDao
    fun danskStiDao(): DanskStiDao = dao()

    companion object {
        @Volatile
        private var INSTANCE: DanskStiDatabase? = null

        @Volatile
        var appFilesDir: java.io.File? = null

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

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recorded_track_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        planId INTEGER NOT NULL,
                        dayNumber INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        altitude REAL,
                        accuracy REAL NOT NULL,
                        speedKmh REAL NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recorded_track_points_planId_dayNumber_timestamp ON recorded_track_points(planId, dayNumber, timestamp)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recorded_day_stats (
                        planId INTEGER NOT NULL,
                        dayNumber INTEGER NOT NULL,
                        distanceKm REAL NOT NULL DEFAULT 0.0,
                        movingTimeSeconds INTEGER NOT NULL DEFAULT 0,
                        ascentMeters INTEGER NOT NULL DEFAULT 0,
                        pointCount INTEGER NOT NULL DEFAULT 0,
                        lastTimestamp INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(planId, dayNumber)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_route_legs (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        fromLat REAL NOT NULL,
                        fromLng REAL NOT NULL,
                        toLat REAL NOT NULL,
                        toLng REAL NOT NULL,
                        avoidRoads REAL NOT NULL,
                        preferNature INTEGER NOT NULL,
                        allowFerries INTEGER NOT NULL,
                        alternative INTEGER NOT NULL,
                        lengthKm REAL NOT NULL,
                        ascentM REAL NOT NULL,
                        descentM REAL NOT NULL,
                        pointsJson TEXT NOT NULL,
                        wayInfoJson TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_route_legs_expiresAt ON cached_route_legs(expiresAt)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_routes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        optionsJson TEXT NOT NULL,
                        waypointsJson TEXT NOT NULL,
                        totalKm REAL NOT NULL,
                        ascentM REAL NOT NULL,
                        descentM REAL NOT NULL,
                        pathSharePct REAL NOT NULL,
                        roadKm REAL NOT NULL,
                        geometryFile TEXT NOT NULL,
                        sourceName TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_routes_updatedAt ON custom_routes(updatedAt)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS access_polygons (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT,
                        type TEXT NOT NULL,
                        coordinatesJson TEXT NOT NULL,
                        minLat REAL NOT NULL,
                        maxLat REAL NOT NULL,
                        minLng REAL NOT NULL,
                        maxLng REAL NOT NULL,
                        source TEXT NOT NULL,
                        operatorOrOwner TEXT,
                        fetchedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_access_polygons_bbox ON access_polygons(minLat, maxLat, minLng, maxLng)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_access_polygons_type ON access_polygons(type)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fri_teltning ADD COLUMN cvrNavn TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trail_pois ADD COLUMN routeId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trail_pois_routeId ON trail_pois(routeId)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_custom_waypoints (
                        id TEXT NOT NULL PRIMARY KEY,
                        routeId INTEGER,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        name TEXT NOT NULL,
                        note TEXT NOT NULL,
                        photoPath TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_custom_waypoints_routeId ON user_custom_waypoints(routeId)")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS marked_routes (
                        osmRelationId INTEGER PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        lengthKm REAL NOT NULL,
                        operator TEXT,
                        routeType TEXT NOT NULL,
                        network TEXT,
                        symbol TEXT,
                        colorHex TEXT NOT NULL,
                        segmentsJson TEXT NOT NULL,
                        minLat REAL NOT NULL,
                        maxLat REAL NOT NULL,
                        minLng REAL NOT NULL,
                        maxLng REAL NOT NULL,
                        cachedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_marked_routes_bbox ON marked_routes(minLat, maxLat, minLng, maxLng)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_marked_routes_routeType ON marked_routes(routeType)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS marked_route_cache_boxes (
                        boxKey TEXT PRIMARY KEY NOT NULL,
                        minLat REAL NOT NULL,
                        maxLat REAL NOT NULL,
                        minLng REAL NOT NULL,
                        maxLng REAL NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                // Requirement 1: Delete cached baseline access polygons
                db.execSQL("DELETE FROM access_polygons WHERE id LIKE 'baseline_%'")
                db.execSQL("ALTER TABLE access_polygons ADD COLUMN tileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_access_polygons_tileId ON access_polygons(tileId)")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE custom_routes ADD COLUMN baseTrailId TEXT")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE custom_routes ADD COLUMN isReverse INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {}
                try {
                    db.execSQL("ALTER TABLE trip_plans ADD COLUMN customRouteId INTEGER")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS water_features (
                        id TEXT NOT NULL PRIMARY KEY,
                        osmId INTEGER NOT NULL,
                        name TEXT,
                        featureType TEXT NOT NULL,
                        subType TEXT,
                        coordinatesJson TEXT NOT NULL,
                        minLat REAL NOT NULL,
                        maxLat REAL NOT NULL,
                        minLng REAL NOT NULL,
                        maxLng REAL NOT NULL,
                        tileId TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_water_features_bbox ON water_features(minLat, maxLat, minLng, maxLng)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_water_features_tileId ON water_features(tileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_water_features_type ON water_features(featureType)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS water_tile_cache (
                        tileId TEXT NOT NULL PRIMARY KEY,
                        minLat REAL NOT NULL,
                        maxLat REAL NOT NULL,
                        minLng REAL NOT NULL,
                        maxLng REAL NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Delete invented POIs, access polygons, water features, and marked routes
                db.execSQL("DELETE FROM pois WHERE id LIKE 'dk_shop_%' OR id LIKE 'dk_transit_%'")
                db.execSQL("DELETE FROM trail_pois WHERE poiId LIKE 'dk_shop_%' OR poiId LIKE 'dk_transit_%'")
                db.execSQL("DELETE FROM access_polygons WHERE id LIKE 'nst_forest_%' OR id LIKE 'fredning_%' OR id LIKE 'priv_forest_%'")
                db.execSQL("DELETE FROM water_features")
                db.execSQL("DELETE FROM marked_routes WHERE osmRelationId >= 900000")

                // Fix trip plans whose trailId starts with "route_trail_" (strip "route_")
                db.execSQL("UPDATE trip_plans SET trailId = SUBSTR(trailId, 7) WHERE trailId LIKE 'route_trail_%'")

                // Reset isGeometryDownloaded = false and computedWalkingKm = 0 for trails whose geometry file does not exist
                val filesDir = appFilesDir
                if (filesDir == null) {
                    db.execSQL("UPDATE trails SET isGeometryDownloaded = 0, computedWalkingKm = 0.0, bboxNorth = NULL, bboxSouth = NULL, bboxEast = NULL, bboxWest = NULL")
                } else {
                    val idsToReset = mutableListOf<String>()
                    val cursor = db.query("SELECT id FROM trails")
                    try {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val gzFile = java.io.File(java.io.File(filesDir, "trails"), "$id.json.gz")
                            val plainFile = java.io.File(filesDir, "trail_geom_$id.json")
                            val routeFile = java.io.File(java.io.File(filesDir, "routes"), "${id.removePrefix("custom_route_").removePrefix("route_")}.json.gz")
                            if (!gzFile.exists() && !plainFile.exists() && !routeFile.exists()) {
                                idsToReset.add(id)
                            }
                        }
                    } finally {
                        cursor.close()
                    }
                    for (tid in idsToReset) {
                        db.execSQL(
                            "UPDATE trails SET isGeometryDownloaded = 0, computedWalkingKm = 0.0, bboxNorth = NULL, bboxSouth = NULL, bboxEast = NULL, bboxWest = NULL WHERE id = ?",
                            arrayOf<Any>(tid)
                        )
                    }
                }
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }

        fun getInstance(context: Context): DanskStiDatabase {
            appFilesDir = context.applicationContext.filesDir
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DanskStiDatabase::class.java,
                    "dansk_sti.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                    MIGRATION_21_22, MIGRATION_22_23
                )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
