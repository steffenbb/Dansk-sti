package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.MapLayersState
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.PoiEntity
import com.example.data.model.RoutePoint
import com.example.data.model.ShelterEntity
import com.example.data.model.TripStage
import com.example.data.repository.DanskStiRepository
import com.example.data.repository.GeoFaFieldParser
import com.example.data.repository.OsmPoiRepository
import com.example.ui.components.PoiInfoSheetData
import com.example.ui.navigation.MapLayersPoiBuilder
import com.example.ui.viewmodel.NavigationNearbyAeds
import com.example.ui.viewmodel.PlannerDayPlaces
import com.example.ui.viewmodel.PoiSpotItem
import com.example.ui.viewmodel.TrailCorridorExtras
import com.example.ui.viewmodel.computeRouteStopsAndAhead
import com.example.util.OpenState
import com.example.util.OpeningHoursEvaluator
import com.example.util.OsmExtraLabels
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
import java.time.ZoneId
import java.time.ZonedDateTime

/** Phase C – new OpenStreetMap places along the route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "da")
class EnrichPhaseCTest {

    private lateinit var app: Application
    private lateinit var osm: OsmPoiRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        osm = OsmPoiRepository(app)
    }

    private fun cph(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("Europe/Copenhagen")).toInstant().toEpochMilli()

    private fun node(id: Long, lat: Double, lng: Double, tags: Map<String, String>): String {
        val tagJson = tags.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return """{"type":"node","id":$id,"lat":$lat,"lon":$lng,"tags":{$tagJson}}"""
    }

    private fun elements(vararg nodes: String) = """{"elements":[${nodes.joinToString(",")}]}"""

    // ---------------------------------------------------------------- C1 parser

    @Test
    fun c1_parseExtras_everyTableRow_typeSubtypeAndNameFallback() {
        data class Row(val tags: Map<String, String>, val type: String, val subtype: String, val fallback: String)
        val rows = listOf(
            Row(mapOf("amenity" to "cafe"), "cafe", "cafe", "Café"),
            Row(mapOf("amenity" to "restaurant"), "cafe", "restaurant", "Restaurant"),
            Row(mapOf("amenity" to "fast_food"), "cafe", "fast_food", "Grillbar"),
            Row(mapOf("amenity" to "ice_cream"), "cafe", "ice_cream", "Is"),
            Row(mapOf("amenity" to "fuel"), "shop", "fuel", "Tankstation"),
            Row(mapOf("amenity" to "water_point"), "water", "water_point", "Vandpost"),
            Row(mapOf("natural" to "spring", "drinking_water" to "yes"), "water", "spring", "Kilde (drikkevand)"),
            Row(mapOf("amenity" to "grave_yard"), "water_likely", "churchyard", "Kirkegård"),
            Row(mapOf("leisure" to "firepit"), "firepit", "firepit", "Bålplads"),
            Row(mapOf("amenity" to "bbq"), "firepit", "bbq", "Grillplads"),
            Row(mapOf("tourism" to "picnic_site"), "picnic", "picnic_site", "Rasteplads"),
            Row(mapOf("amenity" to "shelter", "shelter_type" to "picnic_shelter"), "picnic", "madpakkehus", "Madpakkehus"),
            Row(mapOf("tourism" to "viewpoint"), "highlight", "viewpoint", "Udsigtspunkt"),
            Row(mapOf("man_made" to "tower", "tower:type" to "observation"), "highlight", "observation_tower", "Udsigtstårn"),
            Row(mapOf("leisure" to "bird_hide"), "highlight", "bird_hide", "Fugleskjul"),
            Row(mapOf("historic" to "archaeological_site"), "highlight", "historic_archaeological_site", "Fortidsminde"),
            Row(mapOf("historic" to "tumulus"), "highlight", "historic_tumulus", "Gravhøj"),
            Row(mapOf("historic" to "ruins"), "highlight", "historic_ruins", "Ruin"),
            Row(mapOf("historic" to "castle"), "highlight", "historic_castle", "Slot"),
            Row(mapOf("historic" to "manor"), "highlight", "historic_manor", "Herregård"),
            Row(mapOf("historic" to "rune_stone"), "highlight", "historic_rune_stone", "Runesten"),
            Row(mapOf("historic" to "monument"), "highlight", "historic_monument", "Monument"),
            Row(mapOf("natural" to "beach"), "beach", "beach", "Strand"),
            Row(mapOf("leisure" to "bathing_place"), "beach", "bathing_place", "Badested"),
            Row(mapOf("leisure" to "swimming_area"), "beach", "swimming_area", "Badested"),
            Row(mapOf("emergency" to "defibrillator"), "aed", "defibrillator", "Hjertestarter"),
            Row(mapOf("amenity" to "pharmacy"), "pharmacy", "pharmacy", "Apotek"),
            Row(mapOf("tourism" to "hostel"), "accommodation", "hostel", "Vandrehjem"),
            Row(mapOf("tourism" to "guest_house"), "accommodation", "guest_house", "B&B / gæstehus"),
            Row(mapOf("tourism" to "hotel"), "accommodation", "hotel", "Hotel"),
            Row(mapOf("tourism" to "chalet"), "accommodation", "chalet", "Hytte")
        )
        // Places 1 km apart so de-duplication never interferes
        val json = elements(*rows.mapIndexed { i, r -> node(100L + i, 55.0 + i * 0.01, 10.0, r.tags) }.toTypedArray())
        val parsed = osm.parseExtrasOverpassJson(json)
        assertEquals(rows.size, parsed.size)
        rows.forEachIndexed { i, r ->
            val p = parsed.single { it.id == "osm_node_${100L + i}" }
            assertEquals("type for ${r.tags}", r.type, p.type)
            assertEquals("subtype for ${r.tags}", r.subtype, p.subtype)
            assertEquals("fallback name for ${r.tags}", r.fallback, p.name)
            assertEquals("osm", p.source)
        }

        // Memorial needs a name; churchyards with access=private/no are skipped; a real name wins over the fallback
        val more = osm.parseExtrasOverpassJson(
            elements(
                node(900, 56.0, 10.0, mapOf("historic" to "memorial")),
                node(901, 56.1, 10.0, mapOf("historic" to "memorial", "name" to "Mindesten for Genforeningen")),
                node(902, 56.2, 10.0, mapOf("landuse" to "cemetery", "access" to "private")),
                node(903, 56.3, 10.0, mapOf("amenity" to "grave_yard", "access" to "no")),
                node(904, 56.4, 10.0, mapOf("landuse" to "cemetery", "name" to "Ry Kirkegård"))
            )
        )
        assertEquals(listOf("osm_node_901", "osm_node_904"), more.map { it.id })
        assertEquals("historic_memorial", more[0].subtype)
        assertEquals("Mindesten for Genforeningen", more[0].name)
        assertEquals("water_likely", more[1].type)
        assertEquals("Ry Kirkegård", more[1].name)
    }

    @Test
    fun c1_picnicShelter_neverBecomesOvernightShelter() {
        val json = elements(node(1, 55.5, 10.5, mapOf("amenity" to "shelter", "shelter_type" to "picnic_shelter", "name" to "Madpakkehuset")))
        val extras = osm.parseExtrasOverpassJson(json)
        assertEquals(1, extras.size)
        assertEquals("picnic", extras[0].type)
        assertEquals("madpakkehus", extras[0].subtype)
        // The essentials query only asks for overnight shelter types, never picnic shelters
        val essentialsQuery = osm.buildOverpassPolylineQuery(listOf(GeoPointDto(55.0, 10.0), GeoPointDto(55.1, 10.1)))
        assertFalse(essentialsQuery.contains("picnic_shelter"))
        assertTrue(essentialsQuery.contains("basic_hut|lean_to|weather_shelter"))
        assertTrue(osm.buildOverpassExtrasQuery(listOf(GeoPointDto(55.0, 10.0))).contains("\"shelter_type\"=\"picnic_shelter\"](around:500,55.0,10.0)"))
    }

    @Test
    fun c1_dedupe_sameName150m_merges_unnamedNot_and20mSameTypeMerges() {
        val json = elements(
            // grave_yard + cemetery with the same name ~110 m apart → one
            node(1, 55.0000, 10.0000, mapOf("amenity" to "grave_yard", "name" to "Ry Kirkegård")),
            node(2, 55.0010, 10.0000, mapOf("landuse" to "cemetery", "name" to "Ry Kirkegård")),
            // unnamed grave_yard + cemetery ~110 m apart: both are churchyards → merged too
            node(3, 55.1000, 10.0000, mapOf("amenity" to "grave_yard")),
            node(4, 55.1010, 10.0000, mapOf("landuse" to "cemetery")),
            // two unnamed AEDs ~110 m apart must NOT merge (different buildings)
            node(5, 55.2000, 10.0000, mapOf("emergency" to "defibrillator")),
            node(6, 55.2010, 10.0000, mapOf("emergency" to "defibrillator")),
            // two named viewpoints with the same name 300 m apart stay separate
            node(7, 55.3000, 10.0000, mapOf("tourism" to "viewpoint", "name" to "Udsigten")),
            node(8, 55.3027, 10.0000, mapOf("tourism" to "viewpoint", "name" to "Udsigten")),
            // same type within 20 m, different names → one
            node(9, 55.4000, 10.0000, mapOf("amenity" to "cafe", "name" to "A")),
            node(10, 55.40010, 10.0000, mapOf("amenity" to "cafe", "name" to "B")),
            // different types at the same spot both stay
            node(11, 55.5000, 10.0000, mapOf("amenity" to "cafe", "name" to "Kiosken")),
            node(12, 55.5000, 10.0000, mapOf("tourism" to "viewpoint", "name" to "Kiosken"))
        )
        val ids = osm.parseExtrasOverpassJson(json).map { it.id }
        assertEquals(
            listOf("osm_node_1", "osm_node_3", "osm_node_5", "osm_node_6", "osm_node_7", "osm_node_8", "osm_node_9", "osm_node_11", "osm_node_12"),
            ids
        )
    }

    @Test
    fun c1_detailsJson_holdsOnlyWhitelistedKeys_andPrefersDanishDescription() {
        val json = elements(
            node(
                1, 55.0, 10.0,
                mapOf(
                    "emergency" to "defibrillator",
                    "description" to "English text", "description:da" to "Ved indgangen",
                    "defibrillator:location" to "I våbenhuset", "indoor" to "yes", "access" to "public",
                    "wikipedia" to "da:Ry Kirke", "wikidata" to "Q123", "wheelchair" to "limited",
                    "phone" to "+45 1234", "addr:street" to "Kirkevej", "opening_hours" to "24/7",
                    "operator" to "TrygFonden", "contact:website" to "https://example.org/aed"
                )
            )
        )
        val p = osm.parseExtrasOverpassJson(json).single()
        val details = GeoFaFieldParser.parseDetailsJson(p.detailsJson)
        assertEquals(
            setOf("description", "defibrillator:location", "indoor", "access", "wikipedia", "wikidata", "wheelchair"),
            details.keys
        )
        assertEquals("Ved indgangen", details["description"])
        assertEquals("24/7", p.openingHours)
        assertEquals("TrygFonden", p.operator)
        assertEquals("https://example.org/aed", p.website)
        // No details → null rather than "{}"
        assertNull(osm.parseExtrasOverpassJson(elements(node(2, 55.0, 10.0, mapOf("tourism" to "viewpoint")))).single().detailsJson)
    }

    @Test
    fun c1_fetchExtras_failureReturnsCacheOrEmpty_neverThrows() = runBlocking {
        val pts = listOf(GeoPointDto(55.0, 10.0), GeoPointDto(55.01, 10.01))
        File(app.filesDir, "osm_pois/test_trail_extras.json").delete()
        osm.customExtrasChunkFetcher = { throw java.io.IOException("Overpass down") }
        assertTrue(osm.fetchExtrasForPolyline("test_trail", pts).isEmpty())

        val cafe = PoiEntity(id = "osm_node_1", type = "cafe", name = "Café Ry", lat = 55.0, lng = 10.0, source = "osm", subtype = "cafe")
        osm.customExtrasChunkFetcher = { listOf(cafe) }
        assertEquals(listOf("osm_node_1"), osm.fetchExtrasForPolyline("test_trail", pts).map { it.id })

        osm.customExtrasChunkFetcher = { throw java.io.IOException("Overpass down") }
        assertEquals("live failure falls back to the cache", listOf("Café Ry"), osm.fetchExtrasForPolyline("test_trail", pts).map { it.name })
        assertEquals("offline (no points) uses the cache", 1, osm.fetchExtrasForPolyline("test_trail", emptyList()).size)
    }

    // ---------------------------------------------------------------- C2 shelters

    @Test
    fun c2_firepitWithin75m_setsFireRingOnlyWhenUnknown() {
        val sh = ShelterEntity(id = "s1", name = "Shelter", lat = 55.0, lng = 10.0)
        val firepit50m = PoiEntity(id = "f1", type = "firepit", name = "Bålplads", lat = 55.00045, lng = 10.0)
        val firepit100m = PoiEntity(id = "f2", type = "firepit", name = "Bålplads", lat = 55.0009, lng = 10.0)

        assertEquals(true, DanskStiRepository.recomputeShelterFacilities(sh, emptyList(), emptyList(), listOf(firepit50m)).hasFireRing)
        assertNull(DanskStiRepository.recomputeShelterFacilities(sh, emptyList(), emptyList(), listOf(firepit100m)).hasFireRing)
        assertEquals(
            "an explicit 'no fire ring' is never overwritten",
            false,
            DanskStiRepository.recomputeShelterFacilities(sh.copy(hasFireRing = false), emptyList(), emptyList(), listOf(firepit50m)).hasFireRing
        )
        // Water/toilet rules from Phase A are unchanged; 499 m due east still counts
        val water499mEast = PoiEntity(id = "w", type = "water", name = "Vand", lat = 55.0, lng = 10.0 + 0.499 / (111.32 * kotlin.math.cos(Math.toRadians(55.0))))
        assertTrue(DanskStiRepository.recomputeShelterFacilities(sh, listOf(water499mEast), emptyList(), emptyList()).hasWaterNearby)
        assertTrue(DanskStiRepository.recomputeShelterFacilities(sh.copy(hasOwnToilet = true), emptyList(), emptyList(), emptyList()).hasToiletNearby)
        val unchanged = sh.copy(hasWaterNearby = false, hasToiletNearby = false)
        assertTrue("returns the same instance when nothing changes", DanskStiRepository.recomputeShelterFacilities(unchanged, emptyList(), emptyList(), emptyList()) === unchanged)
    }

    @Test
    fun c2_osmCampSiteFacilityTags_fillShelterColumns() {
        val json = elements(
            node(
                1, 55.0, 10.0,
                mapOf(
                    "tourism" to "camp_site", "name" to "Naturlejren", "drinking_water" to "yes", "toilets" to "no",
                    "shower" to "hot", "power_supply" to "yes", "backcountry" to "yes"
                )
            ),
            node(2, 55.1, 10.0, mapOf("amenity" to "shelter", "shelter_type" to "lean_to", "drinking_water" to "no", "toilets" to "yes", "shower" to "no")),
            node(3, 55.2, 10.0, mapOf("tourism" to "wilderness_hut"))
        )
        val shelters = osm.parseOverpassJson(json).shelters.associateBy { it.id }
        val camp = shelters.getValue("osm_node_1")
        assertEquals(true, camp.hasOwnTap)
        assertEquals(false, camp.hasOwnToilet)
        assertEquals(true, camp.hasShower)
        assertEquals(true, camp.hasElectricity)
        assertEquals("Primitiv teltplads", camp.shelterType)
        val leanTo = shelters.getValue("osm_node_2")
        assertEquals(false, leanTo.hasOwnTap)
        assertEquals(true, leanTo.hasOwnToilet)
        assertEquals(false, leanTo.hasShower)
        assertNull(leanTo.hasElectricity)
        assertEquals("lean_to", leanTo.shelterType)
        val hut = shelters.getValue("osm_node_3")
        assertNull(hut.hasOwnTap); assertNull(hut.hasOwnToilet); assertNull(hut.hasShower)
    }

    // ---------------------------------------------------------------- C3 opening hours

    @Test
    fun c3_openingHours_supportedFormats() {
        val wed10 = cph(2026, 12, 2, 10)
        assertEquals(OpenState.Open(null), OpeningHoursEvaluator.evaluate("24/7", wed10))

        val week = "Mo-Fr 08:00-17:00; Sa 09:00-13:00; Su off"
        assertEquals(OpenState.Open("17:00"), OpeningHoursEvaluator.evaluate(week, wed10))
        assertEquals(OpenState.Closed("08:00"), OpeningHoursEvaluator.evaluate(week, cph(2026, 12, 2, 7)))
        assertEquals(OpenState.Closed(null), OpeningHoursEvaluator.evaluate(week, cph(2026, 12, 2, 17)))
        assertEquals(OpenState.Open("13:00"), OpeningHoursEvaluator.evaluate(week, cph(2026, 12, 5, 12)))
        assertEquals(OpenState.Closed(null), OpeningHoursEvaluator.evaluate(week, cph(2026, 12, 6, 12)))

        val daily = "Mo-Su 07:00-22:00"
        assertEquals(OpenState.Open("22:00"), OpeningHoursEvaluator.evaluate(daily, cph(2026, 12, 6, 21, 59)))
        assertEquals(OpenState.Closed(null), OpeningHoursEvaluator.evaluate(daily, cph(2026, 12, 6, 23)))
        assertEquals(OpenState.Closed("07:00"), OpeningHoursEvaluator.evaluate(daily, cph(2026, 12, 6, 6)))

        val summer = "Apr-Oct Mo-Su 10:00-18:00"
        assertEquals("closed in January", OpenState.Closed(null), OpeningHoursEvaluator.evaluate(summer, cph(2027, 1, 14, 12)))
        assertEquals(OpenState.Open("18:00"), OpeningHoursEvaluator.evaluate(summer, cph(2026, 7, 15, 12)))

        val night = "Fr-Sa 20:00-02:00"
        assertEquals(OpenState.Open("02:00"), OpeningHoursEvaluator.evaluate(night, cph(2026, 12, 4, 21)))
        assertEquals("Friday's span runs into Saturday", OpenState.Open("02:00"), OpeningHoursEvaluator.evaluate(night, cph(2026, 12, 5, 1)))
        assertEquals("Saturday's span runs into Sunday", OpenState.Open("02:00"), OpeningHoursEvaluator.evaluate(night, cph(2026, 12, 6, 1)))
        assertEquals(OpenState.Closed(null), OpeningHoursEvaluator.evaluate(night, cph(2026, 12, 6, 3)))

        assertEquals("PH rule ignored", OpenState.Open("16:00"), OpeningHoursEvaluator.evaluate("Mo-Fr 08:00-16:00; PH off", wed10))
        assertEquals("later rule overrides for Sunday", OpenState.Closed(null),
            OpeningHoursEvaluator.evaluate("Mo-Su 08:00-20:00; Su 10:00-14:00", cph(2026, 12, 6, 16)))
        assertEquals(OpenState.Open("12:00"), OpeningHoursEvaluator.evaluate("Mo,We-Fr 08:00-12:00,13:00-17:00", wed10))
        assertEquals(OpenState.Closed("13:00"), OpeningHoursEvaluator.evaluate("Mo,We-Fr 08:00-12:00,13:00-17:00", cph(2026, 12, 2, 12, 30)))
        assertEquals(OpenState.Closed(null), OpeningHoursEvaluator.evaluate("Mo,We-Fr 08:00-12:00", cph(2026, 12, 1, 10)))
    }

    @Test
    fun c3_openingHours_garbageIsUnknown_andDanishFormatting() {
        val t = cph(2026, 12, 2, 10)
        for (bad in listOf(null, "", "sunrise-sunset", "Mo-Fr 08:00-12:00 || closed", "bla bla", "Mo-Fr 8-17", "Jan 01 off", "Mo-Fr 08:00-17:00 \"by appointment\"")) {
            assertEquals("'$bad' must be UNKNOWN", OpenState.Unknown, OpeningHoursEvaluator.evaluate(bad, t))
        }
        assertEquals("Åben · lukker 17:00", OpeningHoursEvaluator.formatDanish(OpenState.Open("17:00"), app))
        assertEquals("Lukket · åbner 10:00", OpeningHoursEvaluator.formatDanish(OpenState.Closed("10:00"), app))
        assertEquals("Lukket", OpeningHoursEvaluator.formatDanish(OpenState.Closed(null), app))
        assertEquals("Åbningstider ukendt", OpeningHoursEvaluator.formatDanish(OpenState.Unknown, app))
        assertEquals("Åben døgnet rundt", OpeningHoursEvaluator.formatDanish(OpenState.Open(null), app))
    }

    // ---------------------------------------------------------------- C4 map

    @Test
    fun c4_mapLayers_newTypesOnTheirOwnToggles_andLikelyWaterNeverPlainWater() {
        val pois = listOf(
            PoiEntity(id = "lw", type = "water_likely", name = "Kirkegård", lat = 55.0, lng = 10.0, source = "osm"),
            PoiEntity(id = "aed", type = "aed", name = "Hjertestarter", lat = 55.1, lng = 10.0, source = "osm"),
            PoiEntity(id = "cafe", type = "cafe", name = "Café", lat = 55.2, lng = 10.0, source = "osm"),
            PoiEntity(id = "hl", type = "highlight", name = "Udsigt", lat = 55.3, lng = 10.0, source = "osm"),
            PoiEntity(id = "bed", type = "accommodation", name = "Vandrehjem", lat = 55.4, lng = 10.0, source = "osm"),
            PoiEntity(id = "unknown", type = "something_new", name = "X", lat = 55.5, lng = 10.0, source = "osm")
        )
        val defaults = MapLayersState()
        assertFalse(defaults.showHighlights); assertTrue(defaults.showSafety); assertFalse(defaults.showAccommodation)
        val byDefault = MapLayersPoiBuilder.buildClusterItems(defaults, emptyList(), emptyList(), pois, emptyList()).associate { it.id to it.type }
        assertEquals(mapOf("lw" to "water_likely", "aed" to "aed"), byDefault)

        val allOn = defaults.copy(showShops = true, showHighlights = true, showAccommodation = true)
        val items = MapLayersPoiBuilder.buildClusterItems(allOn, emptyList(), emptyList(), pois, emptyList()).associate { it.id to it.type }
        assertEquals(mapOf("lw" to "water_likely", "aed" to "aed", "cafe" to "cafe", "hl" to "highlight", "bed" to "accommodation"), items)

        val sig1 = MapLayersPoiBuilder.computePoiSignature(defaults, emptyList(), emptyList(), pois, emptyList(), emptyList())
        val sig2 = MapLayersPoiBuilder.computePoiSignature(defaults.copy(showHighlights = true), emptyList(), emptyList(), pois, emptyList(), emptyList())
        assertTrue(sig1 != sig2)
        assertTrue(com.example.util.DanskStiTileSourceHelper.getAttributionSummary(
            defaults.copy(showWater = false, showToilets = false, showShops = false, showTransport = false, showSafety = false, showHighlights = true)
        ).contains("OSM POI"))
    }

    // ---------------------------------------------------------------- C5 sheet

    @Test
    fun c5_poiSheet_labelsFacilitiesAndLinks() {
        val wed10 = cph(2026, 12, 2, 10)
        val lw = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "lw", type = "water_likely", name = "Ry Kirkegård", lat = 55.0, lng = 10.0, source = "osm", subtype = "churchyard"), app, wed10)
        assertEquals("Kirkegård (mulig vandhane)", lw.typeLabel)
        assertFalse("possible water is never listed as drinking water", lw.facilities.contains(app.getString(R.string.poi_fac_drinking_water)))
        assertTrue(lw.facilities.any { it.startsWith("Kirkegårde har ofte en vandhane") })
        assertEquals("OpenStreetMap (ODbL)", lw.dataSource)

        val aedDetails = """{"defibrillator:location":"I våbenhuset","indoor":"yes","wheelchair":"limited","description":"Døgnåben"}"""
        val aed = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "a", type = "aed", name = "Hjertestarter", lat = 55.0, lng = 10.0, source = "osm", subtype = "defibrillator", detailsJson = aedDetails), app, wed10)
        assertEquals("Hjertestarter", aed.typeLabel)
        assertTrue(aed.facilities.contains("Placering: I våbenhuset"))
        assertTrue(aed.facilities.contains("Indendørs – kan være låst uden for åbningstid"))
        assertTrue(aed.facilities.contains("Ring 112 ved hjertestop. Alarmcentralen kan guide dig til den nærmeste hjertestarter."))
        assertTrue(aed.facilities.contains("Delvist kørestolsvenlig"))
        assertEquals("Døgnåben", aed.description)

        val cafe = PoiInfoSheetData.fromPoiEntity(
            PoiEntity(id = "c", type = "cafe", name = "Café Ry", lat = 55.0, lng = 10.0, source = "osm", subtype = "restaurant",
                openingHours = "Mo-Su 09:00-17:00", detailsJson = """{"wikipedia":"da:Café Ry"}"""), app, wed10
        )
        assertEquals("Restaurant", cafe.typeLabel)
        assertTrue(cafe.facilities.contains("Åben · lukker 17:00"))
        assertTrue("the raw opening hours stay visible", cafe.facilities.any { it.contains("Mo-Su 09:00-17:00") })
        assertEquals("https://da.wikipedia.org/wiki/Café_Ry", cafe.infoUrl)

        val fuel = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "f", type = "shop", name = "Q8", lat = 55.0, lng = 10.0, source = "osm", subtype = "fuel"), app, wed10)
        assertEquals("Tankstation", fuel.typeLabel)
        val view = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "v", type = "highlight", name = "Himmelbjerget", lat = 55.0, lng = 10.0, source = "osm", subtype = "viewpoint"), app, wed10)
        assertEquals("Udsigtspunkt", view.typeLabel)
        val unknown = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "u", type = "something_new", name = "X", lat = 55.0, lng = 10.0, source = "osm"), app, wed10)
        assertEquals("unknown types are not labelled as transport", "Interessepunkt", unknown.typeLabel)
        val bus = PoiInfoSheetData.fromPoiEntity(PoiEntity(id = "b", type = "bus_stop", name = "Torvet", lat = 55.0, lng = 10.0, source = "osm"), app, wed10)
        assertEquals(app.getString(R.string.poi_type_transit), bus.typeLabel)
    }

    @Test
    fun c5_wikipediaUrl_buildsLinksAndNeverGuesses() {
        assertEquals("https://da.wikipedia.org/wiki/Ry_Kirke", OsmExtraLabels.wikipediaUrl("da:Ry Kirke"))
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Gottorf", OsmExtraLabels.wikipediaUrl("de:Schloss Gottorf"))
        assertNull(OsmExtraLabels.wikipediaUrl("Ry Kirke"))
        assertNull(OsmExtraLabels.wikipediaUrl("da:"))
        assertNull(OsmExtraLabels.wikipediaUrl(null))
    }

    // ---------------------------------------------------------------- C6 trail page

    private fun spot(id: String, type: String, km: Double, subtype: String? = null, detour: Double = 100.0) =
        PoiSpotItem(id = id, name = id, type = type, kmOnRoute = km, detourMeters = detour, lat = 0.0, lng = 0.0, subtype = subtype)

    @Test
    fun c6_longestConfirmedWaterGap_andLikelyWaterInside() {
        val gap = TrailCorridorExtras.longestGap(listOf(0.0, 4.0, 10.0, 25.0, 30.0))
        assertEquals(10.0 to 25.0, gap)
        val inside = TrailCorridorExtras.likelyWaterInGap(gap, listOf(spot("a", "water_likely", 3.0), spot("b", "water_likely", 12.0), spot("c", "water_likely", 18.0)))
        assertEquals("nearest the middle of the gap", "c", inside?.id)
        assertNull(TrailCorridorExtras.likelyWaterInGap(gap, listOf(spot("a", "water_likely", 26.0))))
        assertNull(TrailCorridorExtras.longestGap(listOf(0.0)))
    }

    @Test
    fun c6_highlightCounts_groupsInFixedOrder() {
        val counts = TrailCorridorExtras.highlightCounts(
            listOf(
                spot("1", "beach", 1.0), spot("2", "highlight", 2.0, "viewpoint"), spot("3", "highlight", 3.0, "historic_tumulus"),
                spot("4", "highlight", 4.0, "historic_ruins"), spot("5", "firepit", 5.0, "firepit"), spot("6", "highlight", 6.0, "bird_hide")
            )
        )
        assertEquals(listOf("viewpoint" to 1, "tower" to 1, "historic" to 2, "firepit" to 1, "beach" to 1), counts)
        assertEquals("4 udsigtspunkter", app.resources.getQuantityString(R.plurals.trail_highlight_count_viewpoint, 4, 4))
        assertEquals("1 strand", app.resources.getQuantityString(R.plurals.trail_highlight_count_beach, 1, 1))
    }

    // ---------------------------------------------------------------- C7 planner

    @Test
    fun c7_foodStopEta_andStatusAtArrival() {
        assertEquals("km 18 of a 20 km stage from km 10 → 40 % of 300 min", 9 * 60 + 120, PlannerDayPlaces.etaMinutes(9, 300, 10.0, 20.0, 18.0))
        assertEquals("clamped before the stage start", 9 * 60, PlannerDayPlaces.etaMinutes(9, 300, 10.0, 20.0, 5.0))
        assertEquals("11:40", PlannerDayPlaces.formatClock(700))

        val wedMidnight = cph(2026, 12, 2, 0)
        assertEquals("day 2 at 11:40 Copenhagen time", cph(2026, 12, 3, 11, 40), PlannerDayPlaces.arrivalEpochMs(wedMidnight, 1, 700))
        assertEquals("åben ved ankomst", PlannerDayPlaces.statusText("Mo-Su 08:00-12:00", cph(2026, 12, 2, 11), app))
        assertEquals("lukket ved ankomst (åbner 12:00)", PlannerDayPlaces.statusText("Mo-Su 12:00-17:00", cph(2026, 12, 2, 11), app))
        assertEquals("lukket ved ankomst", PlannerDayPlaces.statusText("Mo-Fr 08:00-17:00", cph(2026, 12, 6, 11), app))
        assertEquals("åbningstider ukendt", PlannerDayPlaces.statusText(null, cph(2026, 12, 2, 11), app))

        // A straight 20 km route east from (56.0, 9.0); one stage for the whole day, 300 walking minutes from 09:00
        val kmPerDegLng = 111.32 * kotlin.math.cos(Math.toRadians(56.0))
        val route = listOf(GeoPointDto(56.0, 9.0), GeoPointDto(56.0, 9.0 + 20.0 / kmPerDegLng))
        fun at(km: Double, offsetM: Double = 0.0) = (56.0 + offsetM / 111_320.0) to (9.0 + km / kmPerDegLng)
        val pois = listOf(
            at(8.0).let { (la, ln) -> PoiEntity(id = "brugsen", type = "shop", name = "Brugsen Ry", lat = la, lng = ln, openingHours = "Mo-Su 08:00-20:00", source = "osm") },
            at(4.0).let { (la, ln) -> PoiEntity(id = "cafe", type = "cafe", name = "Café Søen", lat = la, lng = ln, openingHours = "Mo-Su 12:00-17:00", source = "osm") },
            at(12.0, 400.0).let { (la, ln) -> PoiEntity(id = "far", type = "shop", name = "For langt væk", lat = la, lng = ln, source = "osm") },
            at(15.0).let { (la, ln) -> PoiEntity(id = "kiosk", type = "shop", name = "Kiosken", lat = la, lng = ln, source = "osm") },
            at(16.0).let { (la, ln) -> PoiEntity(id = "view", type = "highlight", name = "Himmelbjerget", lat = la, lng = ln, source = "osm", subtype = "viewpoint") },
            at(17.0).let { (la, ln) -> PoiEntity(id = "lw", type = "water_likely", name = "Kirkegård", lat = la, lng = ln, source = "osm") }
        )
        val stage = TripStage(dayNumber = 1, startKm = 0.0, endKm = 20.0, stageKm = 20.0, stopName = "Lejr", stopType = "SHELTER", stopLat = 0.0, stopLng = 0.0, estimatedWalkMinutes = 300)
        val stops = PlannerDayPlaces.foodStopsForDay(PlannerDayPlaces.projectFood(pois, route), stage, 0, 9, wedMidnight, app)
        assertEquals(listOf("Café Søen", "Brugsen Ry", "Kiosken"), stops.map { it.name })
        assertEquals("lukket ved ankomst (åbner 12:00)", stops[0].statusText) // ~10:00
        assertEquals("åben ved ankomst", stops[1].statusText)                  // ~11:00
        assertEquals("åbningstider ukendt", stops[2].statusText)
        assertTrue(stops[1].etaText in setOf("10:59", "11:00"))

        val highlights = PlannerDayPlaces.highlightsForDay(PlannerDayPlaces.projectHighlights(pois, route), stage, java.util.Locale("da")) { app.getString(it) }
        assertEquals(listOf("Himmelbjerget (udsigtspunkt)"), highlights)
    }

    // ---------------------------------------------------------------- C8 navigation

    @Test
    fun c8_aheadItems_highlightAndCafeWithin1000m_andNearestAeds() {
        val route = PlannedRoute(
            planId = 1L, trailId = "t", trailName = "T", isReverse = false, startTrailKm = 0.0, endTrailKm = 20.0, totalWalkKm = 20.0,
            points = listOf(
                RoutePoint(lat = 56.0, lng = 9.0, walkKm = 0.0, trailKm = 0.0),
                RoutePoint(lat = 56.0, lng = 9.3226, walkKm = 20.0, trailKm = 20.0)
            ),
            stages = emptyList()
        )
        val view = PoiEntity(id = "v", type = "highlight", name = "Udsigten", lat = 56.005, lng = 9.1, source = "osm")
        val farView = PoiEntity(id = "fv", type = "highlight", name = "Langt væk", lat = 56.02, lng = 9.1, source = "osm")
        val cafe = PoiEntity(id = "c", type = "cafe", name = "Caféen", lat = 56.0, lng = 9.2, source = "osm")
        val result = computeRouteStopsAndAhead(
            route = route, currentWalkKm = 1.0, currentDay = 1, currentStage = null, remainingTodayKm = 19.0,
            waters = emptyList(), shops = emptyList(), shelters = emptyList(), previousNextOvernight = null,
            highlights = listOf(view, farView), cafes = listOf(cafe)
        )
        assertEquals(listOf("Udsigten" to "HIGHLIGHT", "Caféen" to "CAFE"), result.aheadItems.map { it.title to it.type })

        val aedCorridorFar = PoiEntity(id = "a1", type = "aed", name = "Ved kirken", lat = 56.1, lng = 9.0, source = "osm", detailsJson = """{"defibrillator:location":"Våbenhuset"}""")
        val aedRoomNear = PoiEntity(id = "a2", type = "aed", name = "Brugsen", lat = 56.01, lng = 9.0, source = "osm")
        val aedRoomTooFar = PoiEntity(id = "a3", type = "aed", name = "Byen", lat = 56.06, lng = 9.0, source = "osm")
        val nearest = NavigationNearbyAeds.nearest(56.0, 9.0, listOf(aedCorridorFar), listOf(aedRoomNear, aedRoomTooFar, aedCorridorFar))
        assertEquals(listOf("a2", "a1"), nearest.map { it.id })
        assertEquals("Våbenhuset", nearest[1].locationText)
        assertTrue(NavigationNearbyAeds.nearest(null, null, listOf(aedCorridorFar), emptyList()).isEmpty())
        assertEquals("Ingen hjertestarter registreret i nærheden", app.getString(R.string.nav_sos_no_aed_nearby))
    }

    @Test
    fun c2_corridorPoiVersionRaisedTo3() {
        assertEquals(3, DanskStiRepository.CORRIDOR_POI_VERSION)
    }
}
