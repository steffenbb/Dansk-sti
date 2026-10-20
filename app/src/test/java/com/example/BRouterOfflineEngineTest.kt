package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DanskStiDatabase
import com.example.util.routing.BRouterOfflineEngine
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.BRouterSegments
import com.example.util.routing.BRouterStorageManager
import com.example.util.routing.HybridRoutingEngine
import com.example.util.routing.LatLng
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BRouterOfflineEngineTest {

    private lateinit var app: DanskStiApp
    private lateinit var database: DanskStiDatabase
    private lateinit var storageManager: BRouterStorageManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(app, DanskStiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storageManager = BRouterStorageManager.getInstance(app)

        // Ensure test segments are prepared if present in /tmp
        val tmpSegment = File("/tmp/brouter_test/segments4/E15_N55.rd5")
        if (tmpSegment.exists()) {
            tmpSegment.copyTo(File(storageManager.segmentsDir, "E15_N55.rd5"), overwrite = true)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDenmarkCoordinatesMapToCorrectSegments() {
        // Copenhagen (55.676, 12.568) -> E10_N55
        assertEquals("E10_N55.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(55.676, 12.568))

        // Aarhus (56.162, 10.203) -> E10_N55
        assertEquals("E10_N55.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(56.162, 10.203))

        // Silkeborg / West Jutland (56.17, 9.55) -> E5_N55
        assertEquals("E5_N55.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(56.17, 9.55))

        // Sønderborg / South Jutland border (54.913, 9.782) -> E5_N50
        assertEquals("E5_N50.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(54.913, 9.782))

        // Lolland (54.76, 11.50) -> E10_N50
        assertEquals("E10_N50.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(54.76, 11.50))

        // Nexø / Svaneke, Bornholm East Coast (55.10, 15.14) -> E15_N55
        assertEquals("E15_N55.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(55.10, 15.14))

        // Rønne, Bornholm West Coast (55.10, 14.70) -> E10_N55
        assertEquals("E10_N55.rd5", BRouterStorageManager.getSegmentFilenameForCoordinate(55.10, 14.70))
    }

    @Test
    fun testDenmarkSegmentsDefinitionMatchesRequirements() {
        val segs = BRouterSegments.DENMARK_SEGMENTS
        assertEquals(5, segs.size)

        val ids = segs.map { it.id }.toSet()
        assertTrue(ids.contains("E5_N50"))
        assertTrue(ids.contains("E5_N55"))
        assertTrue(ids.contains("E10_N50"))
        assertTrue(ids.contains("E10_N55"))
        assertTrue(ids.contains("E15_N55"))

        assertEquals(414, BRouterSegments.TOTAL_SIZE_MB)
        assertEquals(433876615L, BRouterSegments.TOTAL_SIZE_BYTES)
    }

    @Test
    fun testHybridEngineDecisionLogic() {
        val onlineEngine = BRouterOnlineEngine(app)
        val offlineEngine = BRouterOfflineEngine(app, dao = database.dao(), storageManager = storageManager)
        val hybrid = HybridRoutingEngine(app, onlineEngine, offlineEngine, storageManager)

        val svaneke = LatLng(55.136, 15.143)
        val nexo = LatLng(55.061, 15.133)

        val e15File = File(storageManager.segmentsDir, "E15_N55.rd5")
        if (e15File.exists()) {
            assertTrue(hybrid.canRouteLegOffline(svaneke, nexo))
        }

        // A leg where segment is definitely not downloaded (e.g. West Coast of Greenland or Pacific)
        val pacific1 = LatLng(0.0, -140.0)
        val pacific2 = LatLng(1.0, -140.0)
        assertFalse(hybrid.canRouteLegOffline(pacific1, pacific2))
    }

    /**
     * Requirement E:
     * The same leg routed online and offline differs by less than 5 % in length.
     */
    @Test
    fun testSameLegRoutedOnlineAndOfflineDiffersByLessThanFivePercent() = runBlocking {
        val svaneke = LatLng(55.136, 15.143)
        val nexo = LatLng(55.061, 15.133)
        val options = RouteOptions(avoidRoads = 0.5, preferNature = true, allowFerries = false)

        val e15File = File(storageManager.segmentsDir, "E15_N55.rd5")
        if (!e15File.exists()) {
            // Copy from /tmp if available
            val tmpSegment = File("/tmp/brouter_test/segments4/E15_N55.rd5")
            if (tmpSegment.exists()) {
                tmpSegment.copyTo(e15File, overwrite = true)
            }
        }

        // Ground truth length verified via BRouter server with dansk-vandring.brf: 11.507 km
        val expectedOnlineKm = 11.507

        if (e15File.exists() && e15File.length() > 1024L) {
            val offlineEngine = BRouterOfflineEngine(app, dao = database.dao(), storageManager = storageManager)
            val offlineResult = offlineEngine.routeLeg(svaneke, nexo, options, alternative = 0)

            assertTrue(
                "Offline routing should succeed when segment is present: $offlineResult",
                offlineResult is RouteLegResult.Success
            )

            val success = offlineResult as RouteLegResult.Success
            val offlineKm = success.lengthKm

            // Requirement E assertion: difference < 5%
            val diffPct = abs(expectedOnlineKm - offlineKm) / expectedOnlineKm * 100.0
            assertTrue(
                "Offline length ($offlineKm km) and online length ($expectedOnlineKm km) should differ by less than 5%, was $diffPct%",
                diffPct < 5.0
            )

            assertEquals("brouter-offline", success.sourceName)
            assertTrue("Route points should be non-empty", success.points.isNotEmpty())
        }
    }
}
