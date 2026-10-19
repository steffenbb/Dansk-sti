package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.data.repository.DanskStiRepository
import com.example.util.DanskStiTileSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanskStiDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var database: DanskStiDatabase
    private lateinit var repository: DanskStiRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DanskStiRepository(database.dao(), context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testStartupDatabaseSeedingPopulatesTrailsAndGeoFaData() = runBlocking {
        // Before seeding, tables are empty
        assertEquals(0, database.dao().getTrailCount())
        assertEquals(0, database.dao().getShelterCount())
        assertEquals(0, database.dao().getFriTeltningCount())
        assertEquals(0, database.dao().getPoiCount())

        // Execute startup seeding
        repository.initializePreseededDataIfEmpty()

        // 1. Verify all 12 core trails are seeded
        val trailCount = database.dao().getTrailCount()
        assertTrue("Expected at least 12 trails, but found $trailCount", trailCount >= 12)

        val ohavsstien = database.dao().getTrailById("trail_ohavsstien")
        assertNotNull("Øhavsstien should be seeded", ohavsstien)
        assertEquals("Øhavsstien", ohavsstien?.name)
        assertEquals(1202410L, ohavsstien?.osmRelationId)

        val haervejen = database.dao().getTrailById("trail_haervejen")
        assertNotNull("Hærvejen should be seeded", haervejen)

        val gendarmstien = database.dao().getTrailById("trail_gendarmstien")
        assertNotNull("Gendarmstien should be seeded", gendarmstien)

        // 2. Verify GeoFA shelters, fri teltning and POIs are seeded from assets
        val shelterCount = database.dao().getShelterCount()
        val friTeltningCount = database.dao().getFriTeltningCount()
        val poiCount = database.dao().getPoiCount()

        assertTrue("Expected shelters to be loaded, got $shelterCount", shelterCount > 0)
        assertTrue("Expected fri teltning to be loaded, got $friTeltningCount", friTeltningCount > 0)
        assertTrue("Expected POIs to be loaded, got $poiCount", poiCount > 0)
    }

    @Test
    fun testSeedingIsIdempotentAndDoesNotDuplicateData() = runBlocking {
        // First run
        repository.initializePreseededDataIfEmpty()
        val firstTrailCount = database.dao().getTrailCount()
        val firstShelterCount = database.dao().getShelterCount()

        // Second run
        repository.initializePreseededDataIfEmpty()
        val secondTrailCount = database.dao().getTrailCount()
        val secondShelterCount = database.dao().getShelterCount()

        assertEquals("Trail count must be unchanged after second seeding", firstTrailCount, secondTrailCount)
        assertEquals("Shelter count must be unchanged after second seeding", firstShelterCount, secondShelterCount)
    }

    @Test
    fun testTileSourceFallbackConfiguration() {
        // Verify tile source OSM fallback toggle
        val originalFallback = DanskStiTileSource.userConfirmedOsmFallback
        try {
            DanskStiTileSource.userConfirmedOsmFallback = true
            assertTrue("userConfirmedOsmFallback should be true when enabled", DanskStiTileSource.userConfirmedOsmFallback)
        } finally {
            DanskStiTileSource.userConfirmedOsmFallback = originalFallback
        }
    }
}
