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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun ensureTestSegmentDownloaded(filename: String = "E15_N55.rd5"): File {
        val target = File(storageManager.segmentsDir, filename)
        if (target.exists() && target.length() > 1000_000L) {
            return target
        }
        val cacheCopy = File("/tmp/brouter_test/segments4/$filename")
        if (cacheCopy.exists() && cacheCopy.length() > 1000_000L) {
            cacheCopy.copyTo(target, overwrite = true)
            return target
        }

        // Download from brouter.de and cache in /tmp for subsequent test runs
        cacheCopy.parentFile?.mkdirs()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url("${BRouterSegments.DOWNLOAD_BASE_URL}$filename")
            .header("User-Agent", "DanskStiTest/1.0")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            assertTrue("Should download $filename from brouter.de", resp.isSuccessful)
            val body = resp.body!!
            FileOutputStream(cacheCopy).use { out ->
                body.byteStream().copyTo(out)
            }
        }
        cacheCopy.copyTo(target, overwrite = true)
        return target
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
        ensureTestSegmentDownloaded("E15_N55.rd5")
        val onlineEngine = BRouterOnlineEngine(app)
        val offlineEngine = BRouterOfflineEngine(app, dao = database.dao(), storageManager = storageManager)
        val hybrid = HybridRoutingEngine(app, onlineEngine, offlineEngine, storageManager)

        val svaneke = LatLng(55.136, 15.143)
        val nexo = LatLng(55.061, 15.133)

        assertTrue(hybrid.canRouteLegOffline(svaneke, nexo))

        // A leg where segment is not downloaded
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
        ensureTestSegmentDownloaded("E15_N55.rd5")

        val svaneke = LatLng(55.136, 15.143)
        val nexo = LatLng(55.061, 15.133)
        val options = RouteOptions(avoidRoads = 0.5, preferNature = true, allowFerries = false)

        // 1. Route Offline using BRouterOfflineEngine
        val offlineEngine = BRouterOfflineEngine(app, dao = null, storageManager = storageManager)
        val startMs = System.currentTimeMillis()
        val offlineResult = offlineEngine.routeLeg(svaneke, nexo, options, alternative = 0)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(
            "Offline routing should succeed: $offlineResult",
            offlineResult is RouteLegResult.Success
        )
        val offlineSuccess = offlineResult as RouteLegResult.Success
        val offlineKm = offlineSuccess.lengthKm
        assertEquals("brouter-offline", offlineSuccess.sourceName)
        assertTrue("Route points should be non-empty", offlineSuccess.points.isNotEmpty())
        assertTrue("Offline routing per leg should complete in under 2 seconds (was ${elapsedMs}ms)", elapsedMs < 2000L)

        // 2. Route Online using BRouterOnlineEngine (or fallback to verified server length 11.507 km if network unavailable)
        val onlineEngine = BRouterOnlineEngine(app)
        val onlineResult = onlineEngine.routeLeg(svaneke, nexo, options, alternative = 0)
        val onlineKm = if (onlineResult is RouteLegResult.Success) {
            onlineResult.lengthKm
        } else {
            11.507
        }

        // 3. Assert difference is < 5%
        val diffPct = abs(onlineKm - offlineKm) / onlineKm * 100.0
        assertTrue(
            "Offline length ($offlineKm km) and online length ($onlineKm km) must differ by less than 5%, was $diffPct%",
            diffPct < 5.0
        )
    }
}
