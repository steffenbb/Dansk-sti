package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.repository.TideEvent
import com.example.data.repository.TideRepository
import com.example.data.repository.TideStation
import com.example.ui.viewmodel.NavigationRouteExtras
import com.example.ui.viewmodel.PlannerDayTides
import com.example.util.PathFeatureType
import com.example.util.PathFeatureWarning
import com.example.util.TideRelevance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** Phase E – DMI tides. Fixtures in src/test/resources/dmi are real DMI oceanObs responses (fetched 27 Nov 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "da")
class EnrichPhaseETest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "tides").deleteRecursively()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("dmi/$name")!!.bufferedReader().use { it.readText() }

    private fun utc(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    private val esbjerg = TideStation("25149", "Esbjerg Havn", 55.4602, 8.4397, null)

    @Test
    fun e1_parseStations_fromDmiFixture() {
        val stations = TideRepository.parseStations(fixture("tidewaterstation_items.json")).associateBy { it.stationId }
        assertEquals(setOf("26239", "25149", "26136", "25346", "26361"), stations.keys)
        val e = stations.getValue("25149")
        assertEquals("Esbjerg Havn", e.name)
        assertEquals(55.4602, e.lat, 1e-6)
        assertEquals(8.4397, e.lng, 1e-6)
        assertEquals(-13.8, stations.getValue("26239").lowAstronomicalTide!!, 1e-6)
    }

    @Test
    fun e1_parsePredictions_fromDmiFixture_sortedHighLowInCm() {
        val events = TideRepository.parsePredictions(fixture("tidewater_25149_esbjerg_minmax.json"), stationLowAstronomicalTide = null)
        assertEquals(7, events.size)
        assertEquals(TideEvent(utc("2026-12-01T06:16:00Z"), isHigh = true, levelCm = 88.4), events[0])
        assertEquals(TideEvent(utc("2026-12-01T12:48:00Z"), isHigh = false, levelCm = -96.5), events[1])
        assertTrue(events.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertTrue(TideRepository.parsePredictions("""{"type":"FeatureCollection"}""", null).isEmpty())
        assertTrue(TideRepository.parsePredictions("not json", null).isEmpty())
    }

    @Test
    fun e1_unitDetection() {
        assertTrue("mostly above 10 → cm", TideRepository.valuesAreCentimetres(listOf(88.4, -96.5, 53.0, -76.0), null))
        // Aabenraa: tiny tide, values mostly below 10 – the station's LAT (-13.8) shows the unit is cm
        val aabenraa = TideRepository.parsePredictions(fixture("tidewater_26239_aabenraa_minmax.json"), stationLowAstronomicalTide = -13.8)
        assertEquals(-13.4, aabenraa.first().levelCm, 1e-6)
        assertFalse("small values without a cm hint are metres", TideRepository.valuesAreCentimetres(listOf(1.2, -0.9, 1.1, -1.0), null))
        assertFalse(TideRepository.valuesAreCentimetres(listOf(1.2, -0.9), 1.5))
        val metres = TideRepository.parsePredictions(
            """{"features":[{"properties":{"predictionType":"maximum","predictionTime":"2026-12-01T06:00:00Z","value":1.2}},
                {"properties":{"predictionType":"minimum","predictionTime":"2026-12-01T12:00:00Z","value":-0.9}}]}""", null
        )
        assertEquals(120.0, metres[0].levelCm, 1e-6)
        assertEquals(-90.0, metres[1].levelCm, 1e-6)
    }

    @Test
    fun e2_relevance_largeRangeSmallRangeAndTidalOverride() {
        val esbjergDay = TideRepository.parsePredictions(fixture("tidewater_25149_esbjerg_minmax.json"), null)
            .filter { it.timeMs < utc("2026-12-01T23:00:00Z") }
        val aabenraaDay = TideRepository.parsePredictions(fixture("tidewater_26239_aabenraa_minmax.json"), -13.8)
            .filter { it.timeMs < utc("2026-12-01T23:00:00Z") }
        assertEquals(88.4 + 96.5, TideRelevance.rangeCm(esbjergDay)!!, 1e-6)
        assertTrue(TideRelevance.isRelevant(esbjergDay, hasTidalWarning = false))
        assertEquals(7.6 + 13.4, TideRelevance.rangeCm(aabenraaDay)!!, 1e-6)
        assertFalse("inner waters show nothing", TideRelevance.isRelevant(aabenraaDay, hasTidalWarning = false))
        assertTrue("a TIDAL path warning shows tides whatever the range", TideRelevance.isRelevant(aabenraaDay, hasTidalWarning = true))
        assertFalse(TideRelevance.isRelevant(emptyList(), hasTidalWarning = true))

        // Nearest station within 15 km of any point, sampled every 500 m
        val stations = TideRepository.parseStations(fixture("tidewaterstation_items.json"))
        val nearEsbjerg = listOf(GeoPointDto(55.55, 8.60), GeoPointDto(55.50, 8.50))
        val n = TideRelevance.nearestStation(nearEsbjerg, stations)!!
        assertEquals("Esbjerg Havn", n.station.name)
        assertTrue(n.distanceKm < 15.0)
        assertNull("Silkeborg is far from any tide station", TideRelevance.nearestStation(listOf(GeoPointDto(56.17, 9.55)), stations))
    }

    @Test
    fun e3_dayLine_inCopenhagenTimeWithSignedCm() {
        val day = TideRepository.parsePredictions(fixture("tidewater_25149_esbjerg_minmax.json"), null)
            .filter { it.timeMs < utc("2026-12-01T23:00:00Z") }
        assertEquals("Højvande 07:16 (+88 cm) og 20:20 (+53 cm) · Lavvande 13:48 (−97 cm)", TideRelevance.dayLine(day, app))
        assertEquals("Tidevand: højvande 07:16 og 20:20, lavvande 13:48 (DMI, Esbjerg Havn)", PlannerDayTides.tideLine(day, "Esbjerg Havn", app))
    }

    @Test
    fun e3_highWaterArrivalRule_plusMinus2h() {
        val dayStart = utc("2026-11-30T23:00:00Z") // 1 Dec 00:00 Copenhagen
        val tidal = PathFeatureWarning(PathFeatureType.TIDAL, 10.0, 10.5, 55.2, 8.6, "Kan stå under vand")
        // Day 0–20 km, 300 walking minutes from 09:00 → km 10 at 11:30
        fun warnings(hwUtc: String) = PlannerDayTides.arrivalWarnings(
            listOf(TideEvent(utc(hwUtc), true, 140.0), TideEvent(utc("2026-12-01T05:00:00Z"), false, -90.0)),
            listOf(tidal), 0.0, 20.0, 9, 300, dayStart, 0, app, Locale("da", "DK")
        )
        assertEquals(listOf("Du når km 10,0 omkring højvande (kl. 12:30) – stien kan stå under vand"), warnings("2026-12-01T11:30:00Z"))
        assertEquals("1h59 before is inside", 1, warnings("2026-12-01T08:31:00Z").size)
        assertTrue("2½ h after is outside", warnings("2026-12-01T13:00:00Z").isEmpty())
        val now = utc("2026-12-01T10:00:00Z")
        assertEquals(utc("2026-12-01T11:30:00Z"), TideRelevance.nextHighWaterWithin2h(listOf(TideEvent(utc("2026-12-01T11:30:00Z"), true, 1.0)), now)!!.timeMs)
        assertNull(TideRelevance.nextHighWaterWithin2h(listOf(TideEvent(utc("2026-12-01T12:30:00Z"), true, 1.0)), now))
    }

    @Test
    fun e1_repository_cachesAndNeverInventsOnFailure() = runBlocking {
        val repo = TideRepository(app)
        val urls = mutableListOf<String>()
        repo.customFetcher = { url -> urls.add(url); if (url.contains("tidewaterstation")) fixture("tidewaterstation_items.json") else fixture("tidewater_25149_esbjerg_minmax.json") }
        val now = utc("2026-11-27T12:00:00Z")
        assertEquals(5, repo.getStations(now).size)
        assertTrue(urls.single().endsWith("/tidewaterstation/items?limit=1000"))
        assertTrue(File(app.filesDir, "tides/stations.json").exists())

        val dec1 = utc("2026-12-01T11:00:00Z")
        val events = repo.getPredictions(esbjerg, dec1, now)
        assertEquals("only 1 Dec (Copenhagen day)", 3, events.size)
        assertTrue(urls.last().contains("stationId=25149&predictionType=minimum_maximum&datetime=2026-11-30T23:00:00Z/2026-12-01T23:00:00Z&limit=200"))
        assertTrue(File(app.filesDir, "tides/25149_20261201.json").exists())

        // Failures: the caches are used; with no cache, empty – never invented
        repo.customFetcher = { throw java.io.IOException("DMI down") }
        assertEquals(5, repo.getStations(now + 31L * 24 * 3600 * 1000).size)
        assertEquals(3, repo.getPredictions(esbjerg, dec1, now + 2L * 24 * 3600 * 1000).size)
        assertTrue(repo.getPredictions(esbjerg, utc("2026-12-05T11:00:00Z"), now).isEmpty())
        File(app.filesDir, "tides").deleteRecursively()
        assertTrue(TideRepository(app).apply { customFetcher = { throw java.io.IOException("down") } }.getStations(now).isEmpty())
    }

    @Test
    fun e3_navigation_warnsBeforeTidalStretchNearHighWater() = runBlocking {
        val extras = NavigationRouteExtras(app)
        val route = PlannedRoute(
            planId = 1L, trailId = "t", trailName = "T", isReverse = false, startTrailKm = 0.0, endTrailKm = 20.0, totalWalkKm = 20.0,
            points = listOf(RoutePoint(lat = 55.30, lng = 8.60, walkKm = 0.0, trailKm = 0.0), RoutePoint(lat = 55.30, lng = 8.9145, walkKm = 20.0, trailKm = 20.0)),
            stages = emptyList()
        )
        val tidalAt3km = PathFeatureWarning(PathFeatureType.TIDAL, 3.0, 3.5, 55.30, 8.6472, "Kan stå under vand")
        val field = NavigationRouteExtras::class.java.getDeclaredField("tideEvents").apply { isAccessible = true }
        field.set(extras, listOf(TideEvent(utc("2026-12-01T11:30:00Z"), true, 140.0)))
        val now = utc("2026-12-01T10:00:00Z")
        val items = extras.tideAheadItems(route, 0.0, listOf(tidalAt3km), now)
        assertEquals(listOf("Højvande kl. 12:30 – stien kan stå under vand"), items.map { it.title })
        assertEquals("WARNING", items.single().type)
        assertTrue("tidal stretch 8 km ahead is too far", extras.tideAheadItems(route, 0.0, listOf(tidalAt3km.copy(lng = 8.7258)), now).isEmpty())
        assertTrue("high water 3 h away", extras.tideAheadItems(route, 0.0, listOf(tidalAt3km), now - 3_600_000L * 2).isEmpty())
    }
}
