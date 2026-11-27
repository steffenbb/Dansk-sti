package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GeoPointDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.data.model.TripStage
import com.example.data.remote.CurrentWeatherDto
import com.example.data.remote.DailyWeatherDto
import com.example.data.remote.HourlyWeatherDto
import com.example.data.remote.OpenMeteoResponse
import com.example.data.remote.OverpassClient
import com.example.data.remote.WeatherInterpreter
import com.example.data.repository.AreaBbox
import com.example.data.repository.RestrictedArea
import com.example.data.repository.RestrictedAreaKind
import com.example.data.repository.RestrictedAreaRepository
import com.example.ui.viewmodel.PlannerDayWarnings
import com.example.ui.viewmodel.RouteBuilderState
import com.example.util.AreaCrossing
import com.example.util.ClosureRule
import com.example.util.DateRange
import com.example.util.PathFeatureType
import com.example.util.PathFeatureWarning
import com.example.util.RestrictedAreaChecker
import com.example.util.SafetyAccess
import com.example.util.WindAssessment
import com.example.util.WindRelation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** Phase D – military areas, wildlife reserves, safety card, better weather. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "da")
class EnrichPhaseDTest {

    private lateinit var app: Application
    private val da = Locale("da", "DK")
    private lateinit var originalConnectivity: () -> Boolean

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        originalConnectivity = OverpassClient.connectivityChecker
    }

    @After
    fun tearDown() {
        OverpassClient.customTransport = null
        OverpassClient.connectivityChecker = originalConnectivity
    }

    // A 2 km × ~2 km square around (56.0, 9.0)
    private val kmPerDegLng = 111.32 * kotlin.math.cos(Math.toRadians(56.0))
    private fun lngAtKm(km: Double) = 9.0 + km / kmPerDegLng
    private fun square(minKm: Double, maxKm: Double, latHalf: Double = 0.01) = listOf(
        GeoPointDto(56.0 - latHalf, lngAtKm(minKm)), GeoPointDto(56.0 - latHalf, lngAtKm(maxKm)),
        GeoPointDto(56.0 + latHalf, lngAtKm(maxKm)), GeoPointDto(56.0 + latHalf, lngAtKm(minKm)),
        GeoPointDto(56.0 - latHalf, lngAtKm(minKm))
    )

    private fun area(id: String, ring: List<GeoPointDto>, kind: RestrictedAreaKind = RestrictedAreaKind.MILITARY, name: String? = "Oksbøl Skydeterræn") =
        RestrictedArea(id, kind, name, null, listOf(ring), RestrictedAreaRepository.bboxOf(listOf(ring)), 0L)

    private fun geom(vararg pts: GeoPointDto) = pts.joinToString(",") { """{"lat":${it.lat},"lon":${it.lng}}""" }

    // ---------------------------------------------------------------- D1 parsing

    @Test
    fun d1_parse_wayPolygon_relationStitching_skipsAndMatching() {
        val a = GeoPointDto(56.0, 9.0); val b = GeoPointDto(56.0, 9.1); val c = GeoPointDto(56.1, 9.1); val d = GeoPointDto(56.1, 9.0)
        val json = """
            {"elements":[
              {"type":"way","id":1,"tags":{"landuse":"military","name":"Skydeterræn"},"geometry":[${geom(a, b, c, d, a)}]},
              {"type":"relation","id":2,"tags":{"military":"training_area"},"members":[
                 {"type":"way","ref":10,"role":"outer","geometry":[${geom(a, b, c)}]},
                 {"type":"way","ref":11,"role":"outer","geometry":[${geom(a, d, c)}]},
                 {"type":"way","ref":12,"role":"inner","geometry":[${geom(GeoPointDto(56.05, 9.05), GeoPointDto(56.06, 9.05))}]}
              ]},
              {"type":"relation","id":3,"tags":{"landuse":"military"},"members":[
                 {"type":"way","ref":20,"role":"outer","geometry":[${geom(a, b, c)}]}
              ]},
              {"type":"way","id":4,"tags":{"military":"danger_area","seamark:type":"restricted_area"},"geometry":[${geom(a, b, c, d, a)}]},
              {"type":"way","id":5,"tags":{"boundary":"protected_area","protection_title":"Vildtreservat"},"geometry":[${geom(a, b, c, d, a)}]},
              {"type":"way","id":6,"tags":{"boundary":"protected_area","name":"Vejlerne Vildtreservat","website":"https://example.org/vejlerne"},"geometry":[${geom(a, b, c, d, a)}]},
              {"type":"way","id":7,"tags":{"boundary":"protected_area","protection_title":"Naturpark"},"geometry":[${geom(a, b, c, d, a)}]},
              {"type":"way","id":8,"tags":{"landuse":"military"},"geometry":[${geom(a, b, c)}]}
            ]}
        """.trimIndent()
        val areas = RestrictedAreaRepository.parse(json, fetchedAt = 123L).associateBy { it.id }
        assertEquals(setOf("osm_way_1", "osm_relation_2", "osm_way_5", "osm_way_6"), areas.keys)

        val way = areas.getValue("osm_way_1")
        assertEquals(RestrictedAreaKind.MILITARY, way.kind)
        assertEquals("Skydeterræn", way.name)
        assertEquals(1, way.rings.size)
        assertEquals(way.rings[0].first(), way.rings[0].last())
        assertEquals(123L, way.fetchedAt)

        val rel = areas.getValue("osm_relation_2")
        assertEquals("training_area counts as MILITARY", RestrictedAreaKind.MILITARY, rel.kind)
        assertEquals("two outer ways (one reversed) become one closed ring", 1, rel.rings.size)
        assertEquals(setOf(a, b, c, d), rel.rings[0].toSet())
        assertEquals(rel.rings[0].first(), rel.rings[0].last())
        assertEquals(AreaBbox(56.0, 9.0, 56.1, 9.1), rel.bbox)

        assertEquals(RestrictedAreaKind.WILDLIFE_RESERVE, areas.getValue("osm_way_5").kind)
        assertNull(areas.getValue("osm_way_5").name)
        assertEquals(RestrictedAreaKind.WILDLIFE_RESERVE, areas.getValue("osm_way_6").kind)
        assertEquals("https://example.org/vejlerne", areas.getValue("osm_way_6").website)
    }

    @Test
    fun d1_simplify_dropsPointsWithin15m() {
        // 100 points along one edge, 1 m off the line: simplified away
        val edge = (0..100).map { i -> GeoPointDto(56.0 + if (i % 2 == 0) 0.0 else 0.000009, 9.0 + i * 0.001) }
        val ring = edge + listOf(GeoPointDto(56.05, 9.1), GeoPointDto(56.05, 9.0), edge.first())
        val json = """{"elements":[{"type":"way","id":1,"tags":{"landuse":"military"},"geometry":[${geom(*ring.toTypedArray())}]}]}"""
        val simplified = RestrictedAreaRepository.parse(json, 0L).single().rings.single()
        assertTrue("was ${simplified.size} points", simplified.size <= 6)
    }

    @Test
    fun d1_cache_emptyWithoutData_keepsOldCacheWhenRefreshFails_andWritesAtomically() = runBlocking {
        val dir = File(app.filesDir, "restricted_areas")
        dir.deleteRecursively()
        OverpassClient.connectivityChecker = { false }
        val repo = RestrictedAreaRepository(app)
        assertTrue(repo.getAreas().isEmpty())
        assertFalse(repo.hasCache())

        val fixture = """{"elements":[{"type":"way","id":1,"tags":{"landuse":"military","name":"A"},"geometry":[${geom(*square(0.0, 1.0).toTypedArray())}]}]}"""
        OverpassClient.connectivityChecker = { true }
        OverpassClient.customTransport = { _, _ -> OverpassClient.HttpResult(200, fixture) }
        assertEquals(listOf("A"), repo.getAreas(nowMs = 1_000L).map { it.name })
        assertTrue(File(dir, "dk.json").exists())
        assertFalse(File(dir, "dk.json.tmp").exists())

        // 31 days later the refresh fails: the old cache is kept
        OverpassClient.connectivityChecker = { false }
        val later = 1_000L + 31L * 24 * 3600 * 1000
        assertEquals(listOf("A"), RestrictedAreaRepository(app).getAreas(nowMs = later).map { it.name })
    }

    // ---------------------------------------------------------------- D2 checker

    private val route20km = listOf(GeoPointDto(56.0, 9.0), GeoPointDto(56.0, lngAtKm(20.0)))

    @Test
    fun d2_crossingKm_mergeUnder100m_andNearOnly() {
        val crossing = RestrictedAreaChecker.findCrossings(route20km, listOf(area("a", square(5.0, 7.0))))
        assertEquals(1, crossing.size)
        assertEquals(5.0, crossing[0].startKm, 0.06)
        assertEquals(7.0, crossing[0].endKm, 0.06)
        assertFalse(crossing[0].nearOnly)

        // Two parts of one area separated by a 60 m gap → one crossing; by a 400 m gap → two
        val twoPartsClose = RestrictedArea("b", RestrictedAreaKind.MILITARY, null, null,
            listOf(square(10.0, 11.0), square(11.06, 12.0)), RestrictedAreaRepository.bboxOf(listOf(square(10.0, 12.0))), 0L)
        val merged = RestrictedAreaChecker.findCrossings(route20km, listOf(twoPartsClose))
        assertEquals(1, merged.size)
        assertEquals(12.0, merged[0].endKm, 0.06)
        val twoPartsFar = twoPartsClose.copy(id = "c", rings = listOf(square(10.0, 11.0), square(11.4, 12.0)))
        assertEquals(2, RestrictedAreaChecker.findCrossings(route20km, listOf(twoPartsFar)).size)

        // An area 60 m north of the route: near only; 300 m north: nothing
        val nearRing = listOf(GeoPointDto(56.00054, lngAtKm(14.0)), GeoPointDto(56.00054, lngAtKm(15.0)), GeoPointDto(56.01, lngAtKm(15.0)), GeoPointDto(56.01, lngAtKm(14.0)), GeoPointDto(56.00054, lngAtKm(14.0)))
        val near = RestrictedAreaChecker.findCrossings(route20km, listOf(area("n", nearRing)))
        assertEquals(1, near.size)
        assertTrue(near[0].nearOnly)
        assertEquals(near[0].startKm, near[0].endKm, 0.0)
        val farRing = nearRing.map { it.copy(lat = it.lat + 0.0027) }
        assertTrue(RestrictedAreaChecker.findCrossings(route20km, listOf(area("f", farRing))).isEmpty())
    }

    @Test
    fun d2_segments_onlyWalkSegmentsCountTowardsKm() {
        val walk1 = TrailSegmentDto(type = TrailSegmentType.WALK, points = listOf(GeoPointDto(56.0, 9.0), GeoPointDto(56.0, lngAtKm(4.0))), lengthKm = 4.0)
        val ferry = TrailSegmentDto(type = TrailSegmentType.FERRY, points = listOf(GeoPointDto(56.0, lngAtKm(4.0)), GeoPointDto(56.0, lngAtKm(14.0))), lengthKm = 10.0)
        val walk2 = TrailSegmentDto(type = TrailSegmentType.WALK, points = listOf(GeoPointDto(56.0, lngAtKm(14.0)), GeoPointDto(56.0, lngAtKm(20.0))), lengthKm = 6.0)
        val c = RestrictedAreaChecker.findCrossingsOnSegments(listOf(walk1, ferry, walk2), listOf(area("a", square(15.0, 16.0))))
        assertEquals(1, c.size)
        assertEquals("walk km 4 + 1 = 5", 5.0, c[0].startKm, 0.06)
    }

    // ---------------------------------------------------------------- D4/D5 texts & placement

    private fun crossing(kind: RestrictedAreaKind, name: String?, start: Double, end: Double, near: Boolean = false) =
        AreaCrossing(area("x", square(0.0, 1.0), kind, name), start, end, 56.0, 9.0, near)

    @Test
    fun d4_safetyTexts_namedUnnamedAndNearOnly() {
        assertEquals(
            "Ruten går gennem et militært område: Oksbøl (km 4,2–6,8).",
            SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.MILITARY, "Oksbøl", 4.2, 6.8), app, da)
        )
        assertEquals("Ruten går gennem et militært område (km 4,2–6,8).", SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.MILITARY, null, 4.2, 6.8), app, da))
        assertEquals(
            "Ruten passerer tæt på et militært område: Oksbøl (km 4,2). Følg skilte og røde flag.",
            SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.MILITARY, "Oksbøl", 4.2, 4.2, near = true), app, da)
        )
        assertEquals(
            "Ruten går gennem Vejlerne, et vildtreservat (km 1,0–2,5).",
            SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.WILDLIFE_RESERVE, "Vejlerne", 1.0, 2.5), app, da)
        )
        assertEquals("Ruten går gennem et vildtreservat (km 1,0–2,5).", SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.WILDLIFE_RESERVE, null, 1.0, 2.5), app, da))
        assertNull(SafetyAccess.areaWarningText(crossing(RestrictedAreaKind.WILDLIFE_RESERVE, "Vejlerne", 1.0, 1.0, near = true), app, da))
        assertEquals("https://www.forsvaret.dk", SafetyAccess.areaLink(RestrictedAreaKind.MILITARY, null))
        assertNull(SafetyAccess.areaLink(RestrictedAreaKind.WILDLIFE_RESERVE, null))
        assertEquals("Vildtreservat", SafetyAccess.areaTitle(RestrictedAreaKind.WILDLIFE_RESERVE, null, app))
    }

    @Test
    fun d5_safetyItems_combineSortAndMarkClosedToday() {
        val mar1ToJul15 = ClosureRule(dateRange = DateRange(3, 1, 7, 15), timeText = null, raw = "no @ (Mar 01-Jul 15)")
        val seasonal = PathFeatureWarning(PathFeatureType.SEASONAL_CLOSURE, 3.0, 4.0, 56.0, 9.0, "Lukket 1. mar – 15. jul", closureRule = mar1ToJul15)
        val steps = PathFeatureWarning(PathFeatureType.STEPS, 1.0, 1.0, 56.0, 9.0, "Trapper")
        val tidal = PathFeatureWarning(PathFeatureType.TIDAL, 9.0, 9.5, 56.0, 9.0, "Kan stå under vand")
        val military = crossing(RestrictedAreaKind.MILITARY, "Oksbøl", 6.0, 7.0)
        val may1 = java.time.ZonedDateTime.of(2027, 5, 1, 12, 0, 0, 0, java.time.ZoneId.of("Europe/Copenhagen")).toInstant().toEpochMilli()
        val items = SafetyAccess.buildItems(listOf(military), listOf(steps, tidal, seasonal), may1, app, da)
        assertEquals("STEPS is not a safety item", listOf(3.0, 6.0, 9.0), items.map { it.km })
        assertEquals("km 3,0 · Lukket 1. mar – 15. jul (lukket i dag)", items[0].text)
        assertTrue(items[1].text.startsWith("Ruten går gennem et militært område: Oksbøl (km 6,0–7,0). Når der skydes"))
        val dec1 = may1 + 214L * 24 * 3600 * 1000
        assertEquals("km 3,0 · Lukket 1. mar – 15. jul", SafetyAccess.buildItems(emptyList(), listOf(seasonal), dec1, app, da).single().text)
    }

    @Test
    fun d5_plannerDay_crossingOnlyOnTheDayContainingItsStart() {
        val state = RouteBuilderState(areaCrossings = listOf(crossing(RestrictedAreaKind.MILITARY, "Oksbøl", 15.0, 18.0)))
        fun stage(s: Double, e: Double) = TripStage(dayNumber = 1, startKm = s, endKm = e, stageKm = e - s, stopName = "", stopType = "SHELTER", stopLat = 0.0, stopLng = 0.0)
        val day1 = mutableListOf<String>(); val day2 = mutableListOf<String>()
        PlannerDayWarnings.appendAreaCrossings(state, stage(0.0, 15.0), false, app, day1)
        PlannerDayWarnings.appendAreaCrossings(state, stage(15.0, 30.0), true, app, day2)
        assertTrue("a crossing starting exactly at the boundary belongs to the next day", day1.isEmpty())
        assertEquals(1, day2.size)
    }

    // ---------------------------------------------------------------- D6 wind

    @Test
    fun d6_windAssessment_compass_andTravelBearing() {
        assertEquals(WindRelation.HEADWIND, WindAssessment.assess(90, 90.0, 8.0))
        assertEquals(WindRelation.HEADWIND, WindAssessment.assess(130, 90.0, 8.0))
        assertEquals(WindRelation.CROSSWIND, WindAssessment.assess(0, 90.0, 8.0))
        assertEquals(WindRelation.TAILWIND, WindAssessment.assess(270, 90.0, 8.0))
        assertEquals("wraps around north", WindRelation.HEADWIND, WindAssessment.assess(350, 10.0, 8.0))
        assertEquals(WindRelation.NONE, WindAssessment.assess(90, 90.0, 4.9))
        assertEquals(listOf("N", "NØ", "Ø", "SØ", "S", "SV", "V", "NV", "N"), listOf(0, 45, 90, 135, 180, 225, 270, 315, 350).map { WindAssessment.compassDanish(it) })

        val east = (0..10).map { GeoPointDto(56.0, lngAtKm(it.toDouble())) }
        assertEquals(90.0, WindAssessment.dayTravelBearing(east)!!, 1.0)
        // Out 5 km east and back again: loops back on itself
        val outAndBack = (0..5).map { GeoPointDto(56.0, lngAtKm(it.toDouble())) } + (4 downTo 0).map { GeoPointDto(56.0001, lngAtKm(it.toDouble())) }
        assertNull(WindAssessment.dayTravelBearing(outAndBack))
        assertNull(WindAssessment.dayTravelBearing(listOf(GeoPointDto(56.0, 9.0))))

        val track = (0..30).map { GeoPointDto(56.0 + it * 0.0001, 9.0) } // ~11 m apart, heading north
        assertEquals(0.0, WindAssessment.recentBearing(track)!!, 1.0)
        assertNull(WindAssessment.recentBearing(track.take(5)))
    }

    // ---------------------------------------------------------------- D6 weather parsing

    private fun hours(startIso: String, n: Int) = (0 until n).map { i ->
        val h = (startIso.substring(11, 13).toInt() + i)
        val day = startIso.substring(0, 10)
        "${day}T${String.format(Locale.US, "%02d", h % 24)}:00"
    }

    @Test
    fun d6_weather_newFieldsNullAndPresent_andThunderFromTime() {
        val now = WeatherInterpreter.parseIsoToEpochMillis("2027-06-10T08:30")!!
        val times = hours("2027-06-10T08:00", 14)
        val codes = MutableList(14) { 1 }.also { it[5] = 95 } // thunder at 13:00
        val present = OpenMeteoResponse(
            current = CurrentWeatherDto(temperature = 18.0, windSpeed = 6.0, weatherCode = 1),
            hourly = HourlyWeatherDto(time = times, temperature2m = List(14) { 18.0 }, weatherCode = codes,
                windDirection10m = List(14) { 225 }, apparentTemperature = List(14) { 15.0 }),
            daily = DailyWeatherDto(time = listOf("2027-06-10"), windDirectionDominant = listOf(240), apparentTempMin = listOf(8.0), uvIndexMax = listOf(6.4))
        )
        val w = WeatherInterpreter.parseWeather(present, nowMillis = now, context = app)
        assertEquals(225, w.windDirectionDeg)
        assertEquals(15.0, w.apparentTempC!!, 0.01)
        assertEquals(6.4, w.uvIndexMaxToday!!, 0.01)
        assertEquals("13:00", w.thunderFromTime)
        assertEquals("Risiko for torden fra kl. 13:00 – undgå åbne højdedrag og enkeltstående træer", w.severeAlert)
        assertEquals(240, w.daily.single().dominantWindDeg)

        // dmi_seamless without the new variables: everything null, nothing invented
        val missing = present.copy(
            hourly = present.hourly!!.copy(windDirection10m = null, apparentTemperature = listOf(null, null), weatherCode = List(14) { 1 }),
            daily = DailyWeatherDto(time = listOf("2027-06-10"), uvIndexMax = listOf(null))
        )
        val m = WeatherInterpreter.parseWeather(missing, nowMillis = now, context = app)
        assertNull(m.windDirectionDeg); assertNull(m.apparentTempC); assertNull(m.uvIndexMaxToday); assertNull(m.thunderFromTime)
        assertNull(m.severeAlert)
        assertNull(m.daily.single().dominantWindDeg)

        // Thunder now keeps the existing "happening now" text
        val nowThunder = present.copy(current = present.current!!.copy(weatherCode = 95))
        assertEquals(app.getString(R.string.weather_alert_thunderstorm), WeatherInterpreter.parseWeather(nowThunder, nowMillis = now, context = app).severeAlert)
        // Thunder 13 hours away is outside the 12-point window
        val lateCodes = MutableList(14) { 1 }.also { it[13] = 95 }
        assertNull(WeatherInterpreter.parseWeather(present.copy(hourly = present.hourly!!.copy(weatherCode = lateCodes)), nowMillis = now, context = app).thunderFromTime)
    }

    @Test
    fun d7_forecastAtArrival_hourlyThenDailyThenNull() {
        val now = WeatherInterpreter.parseIsoToEpochMillis("2027-06-10T08:00")!!
        val resp = OpenMeteoResponse(
            hourly = HourlyWeatherDto(time = hours("2027-06-10T08:00", 10), temperature2m = (0 until 10).map { 10.0 + it },
                windSpeed10m = List(10) { 5.0 }, windDirection10m = List(10) { 270 }, weatherCode = List(10) { 2 }),
            daily = DailyWeatherDto(time = listOf("2027-06-10", "2027-06-11"), tempMax = listOf(20.0, 17.0), weatherCode = listOf(2, 61),
                windSpeedMax = listOf(7.0, 9.0), windDirectionDominant = listOf(260, 200), precipitationSum = listOf(0.0, 4.2))
        )
        val w = WeatherInterpreter.parseWeather(resp, nowMillis = now, context = app)
        val at1530 = WeatherInterpreter.parseIsoToEpochMillis("2027-06-10T15:30")!!
        val f = WeatherInterpreter.forecastAt(w, at1530)!!
        assertTrue(f.fromHourly)
        assertEquals("the 15:00 hourly point, not the weather now", 17.0, f.tempC!!, 0.01)
        assertEquals(270, f.windDirectionDeg)

        val tomorrow = WeatherInterpreter.parseIsoToEpochMillis("2027-06-11T16:00")!!
        val d = WeatherInterpreter.forecastAt(w, tomorrow)!!
        assertFalse(d.fromHourly)
        assertEquals(17.0, d.tempC!!, 0.01)
        assertEquals(200, d.windDirectionDeg)
        assertEquals(4.2, d.precipMm!!, 0.01)

        assertNull("no data for that date", WeatherInterpreter.forecastAt(w, WeatherInterpreter.parseIsoToEpochMillis("2027-06-14T12:00")!!))
    }

    @Test
    fun d7_plannerDayWeather_windLineFeelsLikeAndUv() {
        val start = WeatherInterpreter.parseIsoToEpochMillis("2027-06-10T00:00")!!
        val weather = com.example.data.remote.TrailWeatherInfo(
            daily = listOf(
                com.example.data.remote.DailyPoint("2027-06-10", dominantWindDeg = 90, windMaxMs = 8.0, apparentMinC = 4.0, uvMax = 7.2, tempMaxC = 12.0)
            )
        )
        val eastward = (0..10).map { GeoPointDto(56.0, lngAtKm(it.toDouble())) }
        val extras = com.example.ui.viewmodel.PlannerDayWeather.compute(weather, 0, start, 9, 300, eastward, shownTempC = 12.0, context = app, locale = da)
        assertEquals("Modvind det meste af dagen (8 m/s fra Ø)", extras.windLine)
        assertEquals("føles som 4°", extras.feelsLikeSuffix)
        assertEquals("UV-indeks 7 (høj) – husk solcreme", extras.uvLine)

        val calm = weather.copy(daily = listOf(weather.daily[0].copy(windMaxMs = 3.0, apparentMinC = 11.0, uvMax = 4.0)))
        val none = com.example.ui.viewmodel.PlannerDayWeather.compute(calm, 0, start, 9, 300, eastward, shownTempC = 12.0, context = app, locale = da)
        assertNull(none.windLine); assertNull(none.feelsLikeSuffix); assertNull(none.uvLine)
        assertNotNull(com.example.ui.screens.uvLevelRes(3.0))
        assertNull(com.example.ui.screens.uvLevelRes(2.9))
    }
}
