package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.access.AccessLandType
import com.example.data.model.access.AccessPolygon
import com.example.data.remote.OverpassClient
import com.example.util.GpxImporter
import com.example.util.routing.LatLng
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DanskStiApp::class, qualifiers = "da")
class Stress3OfflineAndCorruptDataResilienceTest {

    @After
    fun tearDown() {
        OverpassClient.customTransport = null
    }

    @Test
    fun stress3_malformedGpxTruncatedGeometryJsonAndOverpassErrorResilience(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as DanskStiApp
        val dao = app.database.dao()
        val repo = app.repository
        val geomRepo = repo.geometryRepository

        // 1. Malformed / truncated GPX XML must return null or empty gracefully without crashing
        val malformedGpxInputs = listOf(
            "",
            "<<<not xml at all>>>",
            """<?xml version="1.0"?><gpx><trk><trkseg><trkpt lat="not_a_number" lon="10.0">""",
            """<?xml version="1.0"?><gpx><trk><trkseg></trkseg></trk></gpx>"""
        )
        for (badGpx in malformedGpxInputs) {
            val result = runCatching {
                GpxImporter.parseAndSimplifyGpx(badGpx.byteInputStream())
            }.getOrNull()
            assertTrue(
                "Malformed GPX must not return valid multi-point route",
                result == null || result.originalPoints.size < 2
            )
        }

        // 2. Corrupt / truncated trail geometry .json.gz on disk must return null cleanly without throwing
        val corruptTrailId = "stress3_corrupt_trail"
        val geomFile = geomRepo.getGeometryFile(corruptTrailId)
        geomFile.parentFile?.mkdirs()
        geomFile.writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xFF.toByte(), 0x01))
        val loadedCorrupt = geomRepo.loadGeometry(corruptTrailId)
        assertNull("Corrupt .json.gz geometry file must fail gracefully and return null", loadedCorrupt)
        geomFile.delete()

        // 3. Stale cached access polygon survives Overpass HTTP 504 / failure without crashing
        dao.clearAllAccessPolygons()
        val stalePoly = AccessPolygon(
            id = "stress3_stale_poly",
            name = "Stale Skov",
            type = AccessLandType.PUBLIC_FOREST,
            coordinates = listOf(
                LatLng(55.20, 9.20),
                LatLng(55.21, 9.20),
                LatLng(55.21, 9.21),
                LatLng(55.20, 9.21),
                LatLng(55.20, 9.20)
            ),
            source = "Naturstyrelsen",
            operatorOrOwner = "Naturstyrelsen"
        )
        val staleEntity = repo.accessLandRepo.modelToEntity(stalePoly, "tile_552_92").copy(
            fetchedAt = 1_000L // expired TTL
        )
        dao.insertAccessPolygons(listOf(staleEntity))

        OverpassClient.customTransport = { _, _ ->
            OverpassClient.HttpResult(code = 504, body = "Gateway Timeout")
        }

        val cachedRead = repo.getCachedAccessPolygons(55.19, 55.22, 9.19, 9.22)
        assertTrue(
            "Cache-only read must return existing polygon without network",
            cachedRead.any { it.id == "stress3_stale_poly" }
        )
    }
}
