package com.example.data.model.access

import com.example.util.routing.LatLng

/**
 * Types of land access governed by the Danish Nature Protection Act (Naturbeskyttelsesloven kapitel 4).
 */
enum class AccessLandType(
    val title: String,
    val summary: String,
    val fillColorArgb: Int,
    val strokeColorArgb: Int
) {
    /**
     * Public forest (stat eller kommune):
     * Færdsel er tilladt til fods og på cykel på veje og stier, samt i skovbunden, døgnet rundt.
     */
    PUBLIC_FOREST(
        title = "Offentlig skov",
        summary = "Færdsel døgnet rundt, både på og uden for stier.",
        fillColorArgb = 0x334CAF50, // Soft green
        strokeColorArgb = 0xFF2E7D32.toInt()
    ),

    /**
     * Private or unknown forest:
     * Færdsel kun tilladt på veje og gode stier fra kl. 06:00 til solnedgang.
     * Ingen adgang i skovbunden. Ikke ophold inden for 150 m fra beboelse/driftsbygninger.
     */
    PRIVATE_FOREST(
        title = "Privat skov",
        summary = "Kun på veje og stier, kl. 06:00 til solnedgang.",
        fillColorArgb = 0x38FFA000, // Amber
        strokeColorArgb = 0xFFF57C00.toInt()
    ),

    /**
     * Private uncultivated land (heath, dunes, meadow, bog):
     * Færdsel til fods tilladt fra kl. 06:00 til solnedgang, både på og uden for stier.
     */
    UNCULTIVATED_LAND(
        title = "Udyrket areal (hede, klit, eng)",
        summary = "Til fods på og uden for stier, kl. 06:00 til solnedgang.",
        fillColorArgb = 0x40FFF59D, // Light yellow
        strokeColorArgb = 0xFFFBC02D.toInt()
    ),

    /**
     * Beach / coastal shore:
     * Færdsel til fods og kortvarigt ophold (op til 1 døgn uden telt) er tilladt døgnet rundt.
     */
    BEACH(
        title = "Strand og kyst",
        summary = "Færdsel til fods og kortvarigt ophold tilladt døgnet rundt.",
        fillColorArgb = 0x4081D4FA, // Light blue
        strokeColorArgb = 0xFF0288D1.toInt()
    ),

    /**
     * Cultivated fields (farmland, orchards, farmyards):
     * Ingen adgang uden ejerens tilladelse.
     */
    CULTIVATED_FIELD(
        title = "Dyrket mark",
        summary = "Ingen adgang for offentligheden.",
        fillColorArgb = 0x00000000, // No fill
        strokeColorArgb = 0x608D6E63.toInt()
    );

    val fullRulesDanish: String
        get() = when (this) {
            PUBLIC_FOREST -> """
                Offentlig skov (stat/kommune)
                • Færdsel er tilladt døgnet rundt, både på veje, stier og i skovbunden.
                • Cykling tilladt på veje og stier egnede til almindelig cykel.
                • Teltning kun tilladt i udpegede fri teltning-skove eller på primitive lejrpladser/shelters.
                
                Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.
            """.trimIndent()

            PRIVATE_FOREST -> """
                Privat skov
                • Færdsel er kun tilladt på befæstede veje og gode stier fra kl. 06:00 til solnedgang.
                • Færdsel i skovbunden er ikke tilladt.
                • Ophold må ikke finde sted inden for 150 meter fra beboelses- og driftsbygninger.
                • Overnatning og teltning er ikke tilladt uden ejerens tilladelse.
                
                Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.
            """.trimIndent()

            UNCULTIVATED_LAND -> """
                Udyrket areal (hede, klit, mose, eng)
                • Færdsel til fods er tilladt fra kl. 06:00 til solnedgang, både på og uden for stier.
                • Hegnede arealer må kun betrædes ad låger, stenter eller overgange.
                • Overnatning er ikke tilladt uden ejerens tilladelse.
                
                Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.
            """.trimIndent()

            BEACH -> """
                Strand og kyststrækning
                • Færdsel til fods og kortvarigt ophold er tilladt døgnet rundt.
                • Ophold i kortere tid (bade, hvile, op til 1 døgn uden telt) er tilladt.
                • Ophold må ikke finde sted inden for 50 meter fra beboelsesbygninger.
                
                Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.
            """.trimIndent()

            CULTIVATED_FIELD -> """
                Dyrket mark
                • Færdsel er ikke tilladt på dyrkede marker eller indhegnede landbrugsarealer.
                
                Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger.
            """.trimIndent()
        }

    companion object {
        const val GENERAL_DISCLAIMER = "Følg altid skilte på stedet - ejeren kan have lovlige begrænsninger."
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
