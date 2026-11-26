package com.example.data.model.access

import com.example.util.routing.LatLng

/**
 * Types of land access governed by the Danish Nature Protection Act (Naturbeskyttelsesloven kapitel 4).
 */
enum class AccessLandType(
    val fillColorArgb: Int,
    val strokeColorArgb: Int
) {
    PUBLIC_FOREST(
        fillColorArgb = 0x334CAF50, // Soft green
        strokeColorArgb = 0xFF2E7D32.toInt()
    ),
    PRIVATE_FOREST(
        fillColorArgb = 0x38FFA000, // Amber
        strokeColorArgb = 0xFFF57C00.toInt()
    ),
    UNCULTIVATED_LAND(
        fillColorArgb = 0x40FFF59D, // Light yellow
        strokeColorArgb = 0xFFFBC02D.toInt()
    ),
    BEACH(
        fillColorArgb = 0x4081D4FA, // Light blue
        strokeColorArgb = 0xFF0288D1.toInt()
    ),
    CULTIVATED_FIELD(
        fillColorArgb = 0x00000000, // No fill
        strokeColorArgb = 0x608D6E63.toInt()
    );

    val title: String
        get() = when (this) {
            PUBLIC_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_public_forest_title) ?: "Offentlig skov"
            PRIVATE_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_private_forest_title) ?: "Privat/ukendt skov"
            UNCULTIVATED_LAND -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_uncultivated_title) ?: "Udyrket areal (hede, klit, eng)"
            BEACH -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_beach_title) ?: "Strand og kyst"
            CULTIVATED_FIELD -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_cultivated_title) ?: "Dyrket mark"
        }

    val summary: String
        get() = when (this) {
            PUBLIC_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_public_forest_summary) ?: "Færdsel døgnet rundt, både på og uden for stier."
            PRIVATE_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_private_forest_summary) ?: "Kun på veje og stier, kl. 06:00 til solnedgang."
            UNCULTIVATED_LAND -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_uncultivated_summary) ?: "Til fods på og uden for stier, kl. 06:00 til solnedgang."
            BEACH -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_beach_summary) ?: "Færdsel til fods og kortvarigt ophold tilladt døgnet rundt."
            CULTIVATED_FIELD -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_cultivated_summary) ?: "Ingen adgang for offentligheden."
        }

    val fullRulesDanish: String
        get() = when (this) {
            PUBLIC_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_public_forest_rules) ?: "Offentlig skov"
            PRIVATE_FOREST -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_private_forest_rules) ?: "Privat skov"
            UNCULTIVATED_LAND -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_uncultivated_rules) ?: "Udyrket areal"
            BEACH -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_beach_rules) ?: "Strand og kyststrækning"
            CULTIVATED_FIELD -> com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_type_cultivated_rules) ?: "Dyrket mark"
        }

    companion object {
        val PRIVATE_OR_UNKNOWN = PRIVATE_FOREST
        val GENERAL_DISCLAIMER: String
            get() = com.example.util.AppLocaleHelper.strOrNull(null, com.example.R.string.access_general_disclaimer)
                ?: "Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger."
    }
}

/**
 * Geometric polygon boundary representing an area with specific access rules.
 */
data class AccessPolygon(
    val id: String,
    val name: String?,
    val type: AccessLandType,
    val coordinates: List<LatLng>,
    val source: String,
    val operatorOrOwner: String? = null,
    val rawTags: Map<String, String> = emptyMap()
)

/**
 * Classification logic strictly enforcing Danish rules and user specifications:
 *
 * Mark a forest PUBLIC only if:
 * 1. OSM says operator or owner = Naturstyrelsen (case-insensitive)
 * 2. OSM says ownership = national, state, municipal, or public
 * 3. It overlaps a GeoFA fri teltning area whose owner field (cvr_navn) is Naturstyrelsen or a kommune.
 * Everything else is marked PRIVATE_OR_UNKNOWN (AccessLandType.PRIVATE_FOREST).
 */
object AccessClassifier {

    fun classify(
        tags: Map<String, String>,
        overlapsPublicGeoFa: Boolean = false,
        publicGeoFaOwner: String? = null
    ): Pair<AccessLandType, String> {
        val landuse = tags["landuse"] ?: ""
        val natural = tags["natural"] ?: ""
        val leisure = tags["leisure"] ?: ""
        val operator = (tags["operator"] ?: "").lowercase()
        val owner = (tags["owner"] ?: "").lowercase()
        val ownership = (tags["ownership"] ?: "").lowercase()

        val isPublicOwnership = operator.contains("naturstyrelsen") ||
                owner.contains("naturstyrelsen") ||
                operator.contains("kommune") ||
                owner.contains("kommune") ||
                ownership in listOf("national", "state", "municipal", "public") ||
                overlapsPublicGeoFa

        // 1. Forest / Wood
        if (landuse == "forest" || natural == "wood") {
            return if (isPublicOwnership) {
                val source = when {
                    operator.contains("naturstyrelsen") -> "OSM (operator=Naturstyrelsen)"
                    owner.contains("naturstyrelsen") -> "OSM (owner=Naturstyrelsen)"
                    ownership in listOf("national", "state") -> "OSM (statsejet skov)"
                    ownership in listOf("municipal", "public") || operator.contains("kommune") -> "OSM (kommunal skov)"
                    overlapsPublicGeoFa -> "GeoFA offentligt areal (${publicGeoFaOwner ?: "Naturstyrelsen"})"
                    else -> "OSM (offentlig skov)"
                }
                AccessLandType.PUBLIC_FOREST to source
            } else {
                AccessLandType.PRIVATE_FOREST to "OSM (privat/ukendt ejer)"
            }
        }

        // 2. Beach / Coastal shore
        if (natural == "beach" || natural == "sand") {
            return AccessLandType.BEACH to "OSM (strand/kyst)"
        }

        // 3. Uncultivated land (heath, scrub, grassland, moor, meadow)
        if (natural in listOf("heath", "scrub", "grassland", "moor") || landuse == "meadow") {
            return AccessLandType.UNCULTIVATED_LAND to "OSM (udyrket natur)"
        }

        // 4. Cultivated fields
        if (landuse in listOf("farmland", "farmyard", "orchard", "greenhouse_horticulture")) {
            return AccessLandType.CULTIVATED_FIELD to "OSM (dyrket mark)"
        }

        // Nature reserve fallback
        if (leisure == "nature_reserve") {
            return if (isPublicOwnership) {
                AccessLandType.PUBLIC_FOREST to "OSM (offentligt naturreservat)"
            } else {
                AccessLandType.UNCULTIVATED_LAND to "OSM (privat naturreservat)"
            }
        }

        return AccessLandType.UNCULTIVATED_LAND to "OSM (naturareal)"
    }
}
