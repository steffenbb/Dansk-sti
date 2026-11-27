package com.example.util

import com.example.R

/**
 * Phase C: labels for the new OSM place types and subtypes, shared by the POI sheet, the trail
 * page, the planner day cards and navigation. Returns string resource ids only, never names.
 */
object OsmExtraLabels {

    /** The Phase C types (the other PoiEntity types are toilet, water, shop and the transport types). */
    val EXTRA_TYPES = setOf("cafe", "water_likely", "firepit", "picnic", "highlight", "beach", "aed", "pharmacy", "accommodation")

    /** Types listed under "Undervejs" / Oplevelser & pauser. */
    val HIGHLIGHT_GROUP_TYPES = setOf("highlight", "beach", "picnic", "firepit")

    private val SUBTYPE_RES = mapOf(
        "cafe" to R.string.osm_extra_name_cafe,
        "restaurant" to R.string.osm_extra_name_restaurant,
        "fast_food" to R.string.osm_extra_name_fast_food,
        "ice_cream" to R.string.osm_extra_name_ice_cream,
        "fuel" to R.string.osm_extra_name_fuel,
        "water_point" to R.string.osm_extra_name_water_point,
        "spring" to R.string.osm_extra_name_spring,
        "churchyard" to R.string.osm_extra_name_churchyard,
        "firepit" to R.string.osm_extra_name_firepit,
        "bbq" to R.string.osm_extra_name_bbq,
        "picnic_site" to R.string.osm_extra_name_picnic_site,
        "madpakkehus" to R.string.osm_extra_name_madpakkehus,
        "viewpoint" to R.string.osm_extra_name_viewpoint,
        "observation_tower" to R.string.osm_extra_name_observation_tower,
        "bird_hide" to R.string.osm_extra_name_bird_hide,
        "historic_archaeological_site" to R.string.osm_extra_name_historic_archaeological_site,
        "historic_tumulus" to R.string.osm_extra_name_historic_tumulus,
        "historic_ruins" to R.string.osm_extra_name_historic_ruins,
        "historic_castle" to R.string.osm_extra_name_historic_castle,
        "historic_manor" to R.string.osm_extra_name_historic_manor,
        "historic_rune_stone" to R.string.osm_extra_name_historic_rune_stone,
        "historic_monument" to R.string.osm_extra_name_historic_monument,
        "historic_memorial" to R.string.osm_extra_name_historic_memorial,
        "beach" to R.string.osm_extra_name_beach,
        "bathing_place" to R.string.osm_extra_name_bathing_place,
        "swimming_area" to R.string.osm_extra_name_bathing_place,
        "defibrillator" to R.string.osm_extra_name_aed,
        "pharmacy" to R.string.osm_extra_name_pharmacy,
        "hostel" to R.string.osm_extra_name_hostel,
        "guest_house" to R.string.osm_extra_name_guest_house,
        "hotel" to R.string.osm_extra_name_hotel,
        "chalet" to R.string.osm_extra_name_chalet
    )

    private val TYPE_RES = mapOf(
        "cafe" to R.string.poi_type_cafe,
        "water_likely" to R.string.poi_type_water_likely,
        "firepit" to R.string.poi_type_firepit,
        "picnic" to R.string.poi_type_picnic,
        "highlight" to R.string.poi_type_highlight,
        "beach" to R.string.poi_type_beach,
        "aed" to R.string.poi_type_aed,
        "pharmacy" to R.string.poi_type_pharmacy,
        "accommodation" to R.string.poi_type_accommodation
    )

    /** The most specific label: the subtype when known (e.g. "Udsigtspunkt"), else the type. */
    fun labelRes(type: String, subtype: String?): Int? {
        if (type == "water_likely") return TYPE_RES["water_likely"]
        subtype?.let { SUBTYPE_RES[it] }?.let { return it }
        return TYPE_RES[type]
    }

    /** Category used for the "Undervejs" counts: viewpoints, ancient monuments, fire pits, beaches … */
    fun highlightGroup(type: String, subtype: String?): String? = when {
        type == "beach" -> "beach"
        type == "firepit" -> "firepit"
        type == "picnic" -> "picnic"
        type == "highlight" && subtype == "viewpoint" -> "viewpoint"
        type == "highlight" && (subtype == "observation_tower" || subtype == "bird_hide") -> "tower"
        type == "highlight" && subtype?.startsWith("historic_") == true -> "historic"
        type == "highlight" -> "viewpoint"
        else -> null
    }

    /**
     * `wikipedia` tag → article URL: "da:Himmelbjerget" → https://da.wikipedia.org/wiki/Himmelbjerget
     * (spaces become `_`). Values without a language prefix are not guessed and give null.
     */
    fun wikipediaUrl(tag: String?): String? {
        val raw = tag?.trim().orEmpty()
        val colon = raw.indexOf(':')
        if (colon <= 0 || colon == raw.length - 1) return null
        val lang = raw.substring(0, colon).trim().lowercase()
        if (!lang.matches(Regex("[a-z]{2,3}(-[a-z]+)?"))) return null
        val title = raw.substring(colon + 1).trim().replace(' ', '_')
        if (title.isEmpty()) return null
        return "https://$lang.wikipedia.org/wiki/$title"
    }
}
