package com.example.data.local

import com.example.data.model.GeoPointDto
import com.example.data.model.TrailEntity
import com.example.data.model.TrailGeometryDto
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.util.GeoUtils
import kotlin.math.sin

object PreseededData {

    fun getInitialTrails(): List<TrailEntity> {
        return listOf(
            TrailEntity(
                id = "trail_ohavsstien",
                name = "Øhavsstien",
                description = "Danmarks berømte vandrerute rundt om det Sydfynske Øhav, gennem Tåsinge, Langeland, Ærø og Fyns smukkeste bakker og kyster.",
                region = "Fyn & Øerne",
                parentRegion = "Fyn & Øerne",
                osmRelationId = 1202410L,
                computedWalkingKm = 220.0,
                bboxNorth = 55.22,
                bboxSouth = 54.82,
                bboxEast = 10.82,
                bboxWest = 10.05,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_haervejen",
                name = "Hærvejen (Jylland)",
                description = "Den historiske vandrerute langs Jyllands højderyg fra Viborg til den tyske grænse ved Rønsdam.",
                region = "Jylland",
                parentRegion = "Jylland",
                osmRelationId = 1792585L,
                computedWalkingKm = 280.0,
                bboxNorth = 56.48,
                bboxSouth = 54.82,
                bboxEast = 9.65,
                bboxWest = 9.10,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_camoenoen",
                name = "Camønoen (Kongerigets venligste vandrerute)",
                description = "Vandrerute over Møn, Nyord og Bogø med dramatisk kridtklint, Dark Sky stjernehimmel og hyggelige landsbyer.",
                region = "Møn & Sydsjælland",
                parentRegion = "Sjælland",
                osmRelationId = null,
                computedWalkingKm = 175.0,
                bboxNorth = 55.06,
                bboxSouth = 54.88,
                bboxEast = 12.56,
                bboxWest = 12.00,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_bornholm_kyststi",
                name = "Kyststien Bornholm",
                description = "Spektakulær kystvandring hele vejen rundt om Bornholm med rå granitklipper, sandstrande og historiske røgerier.",
                region = "Bornholm",
                parentRegion = "Bornholm",
                osmRelationId = null,
                computedWalkingKm = 120.0,
                bboxNorth = 55.30,
                bboxSouth = 54.98,
                bboxEast = 15.16,
                bboxWest = 14.68,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_gendarmstien",
                name = "Gendarmstien",
                description = "Certificeret vandrerute langs Flensborg Fjord, hvor grænsegendarmerne patruljerede til fods.",
                region = "Sønderjylland",
                parentRegion = "Jylland",
                osmRelationId = 443124L,
                computedWalkingKm = 84.0,
                bboxNorth = 54.96,
                bboxSouth = 54.82,
                bboxEast = 10.02,
                bboxWest = 9.35,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_himmerlandsstien",
                name = "Himmerlandsstien",
                description = "Natursti anlagt på den nedlagte jernbane gennem det bakkede Himmerland fra Limfjorden til Viborg.",
                region = "Nordjylland",
                parentRegion = "Jylland",
                osmRelationId = 3031529L,
                computedWalkingKm = 79.0,
                bboxNorth = 56.97,
                bboxSouth = 56.45,
                bboxEast = 9.65,
                bboxWest = 9.24,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_amarminoen",
                name = "Amarminoen",
                description = "Populær københavnsk vandrerute gennem Amager Fælled, det åbne Kalvebod Fælled, Kongelunden og til historiske Dragør.",
                region = "Hovedstaden",
                parentRegion = "Sjælland",
                osmRelationId = null,
                computedWalkingKm = 27.0,
                bboxNorth = 55.66,
                bboxSouth = 55.56,
                bboxEast = 12.68,
                bboxWest = 12.52,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_mols_bjerge",
                name = "Molsruten",
                description = "Førsteklasses kuperet vandring gennem istidslandskabet i Nationalpark Mols Bjerge og forbi Kalø Slotsruin.",
                region = "Østjylland",
                parentRegion = "Jylland",
                osmRelationId = 178382L,
                computedWalkingKm = 80.0,
                bboxNorth = 56.42,
                bboxSouth = 56.18,
                bboxEast = 10.92,
                bboxWest = 10.42,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_traekstien",
                name = "Trækstien Gudenåen",
                description = "Historisk sti langs Danmarks længste å, hvor pramdragerne førhen trak pramme mellem Silkeborg og Randers.",
                region = "Midtjylland",
                parentRegion = "Jylland",
                osmRelationId = 1710241L,
                computedWalkingKm = 70.0,
                bboxNorth = 56.46,
                bboxSouth = 56.16,
                bboxEast = 10.04,
                bboxWest = 9.54,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_skjoldungestien",
                name = "Skjoldungestien",
                description = "Vandresti gennem vikingehistorie, fjordlandskab og dybe skove i Nationalpark Skjoldungernes Land.",
                region = "Midtsjælland",
                parentRegion = "Sjælland",
                osmRelationId = 1458788L,
                computedWalkingKm = 40.0,
                bboxNorth = 55.66,
                bboxSouth = 55.54,
                bboxEast = 12.12,
                bboxWest = 11.88,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_drivvejen",
                name = "Drivvejen (Vestkystens handelsrute)",
                description = "Historisk drivvej gennem vestjysk marsk, hede og klit fra Limfjorden til Vadehavet.",
                region = "Vestjylland",
                parentRegion = "Jylland",
                osmRelationId = 1166772L,
                computedWalkingKm = 210.0,
                bboxNorth = 56.60,
                bboxSouth = 55.30,
                bboxEast = 8.95,
                bboxWest = 8.20,
                isGeometryDownloaded = true
            ),
            TrailEntity(
                id = "trail_nordsjaelland",
                name = "Nordsjællandsruten",
                description = "Vandring langs den Danske Riviera forbi Kronborg, kystbyer, Troldeskoven og sandklitterne i Tisvilde.",
                region = "Nordsjælland",
                parentRegion = "Sjælland",
                osmRelationId = 3481232L,
                computedWalkingKm = 75.0,
                bboxNorth = 56.13,
                bboxSouth = 55.95,
                bboxEast = 12.62,
                bboxWest = 11.85,
                isGeometryDownloaded = true
            )
        )
    }

    fun getFallbackTrailGeometry(trailId: String): TrailGeometryDto? {
        val anchorPoints: List<Pair<Double, Double>> = when (trailId) {
            "trail_ohavsstien" -> listOf(
                55.125 to 10.155, // Faldsled
                55.138 to 10.242, // Svanninge Bakker
                55.095 to 10.245, // Faaborg
                55.075 to 10.380, // Fjællebroen
                55.055 to 10.520, // Egebjerg Bakker
                55.060 to 10.610, // Svendborg
                55.015 to 10.625, // Tåsinge / Valdemars Slot
                54.965 to 10.665, // Siø
                54.937 to 10.713, // Rudkøbing
                54.865 to 10.725, // Lindelse Nor (Langeland)
                55.025 to 10.785, // Tranekær
                55.145 to 10.720, // Lohals
                55.175 to 10.745, // Lundeborg (Fyn)
                54.885 to 10.410, // Ærøskøbing
                54.855 to 10.515  // Marstal
            )
            "trail_haervejen" -> listOf(
                56.452 to 9.403, // Viborg
                56.340 to 9.375, // Hald Sø / Dollerup Bakker
                56.245 to 9.410, // Thorning
                56.165 to 9.545, // Silkeborg / Sepstrup
                56.025 to 9.415, // Vrads Sande
                55.905 to 9.380, // Nørre Snede
                55.756 to 9.418, // Jelling
                55.640 to 9.310, // Randbøl Hede
                55.475 to 9.180, // Vejen / Kongeåen
                55.245 to 9.360, // Vojens / Jels
                55.040 to 9.385, // Rødekro / Immervad Bro
                54.835 to 9.365  // Padborg / Grænsen
            )
            "trail_camoenoen" -> listOf(
                54.985 to 12.285, // Stege
                55.042 to 12.195, // Nyord
                55.015 to 12.390, // Keldby / Ulvshale
                54.996 to 12.441, // Borre
                54.965 to 12.548, // Møns Klint
                54.945 to 12.470, // Klintholm Havn
                54.895 to 12.260, // Fanefjord
                54.925 to 12.050, // Bogø
                54.985 to 12.285  // Stege
            )
            "trail_bornholm_kyststi" -> listOf(
                55.101 to 14.700, // Rønne
                55.185 to 14.705, // Hasle
                55.265 to 14.755, // Hammerknuden
                55.276 to 14.801, // Allinge-Sandvig
                55.215 to 14.915, // Gudhjem
                55.135 to 15.142, // Svaneke
                55.061 to 15.131, // Nexø
                54.992 to 15.075, // Dueodde
                55.045 to 14.845, // Arnager
                55.101 to 14.700  // Rønne
            )
            "trail_gendarmstien" -> listOf(
                54.830 to 9.368, // Padborg
                54.842 to 9.455, // Kollund
                54.902 to 9.595, // Gråsten
                54.865 to 9.675, // Broagerland / Gammelmark
                54.895 to 9.740, // Vemmingbund
                54.910 to 9.791, // Sønderborg
                54.882 to 9.965  // Høruphav / Skovby
            )
            "trail_himmerlandsstien" -> listOf(
                56.966 to 9.255, // Løgstør
                56.865 to 9.375, // Hornum
                56.800 to 9.518, // Aars
                56.675 to 9.515, // Aalestrup
                56.615 to 9.485, // Møldrup
                56.535 to 9.445, // Skals
                56.452 to 9.403  // Viborg
            )
            "trail_amarminoen" -> listOf(
                55.656 to 12.589, // DR Byen
                55.635 to 12.578, // Amager Fælled
                55.602 to 12.555, // Kalvebod Fælled
                55.568 to 12.575, // Kongelunden
                55.575 to 12.635, // Søvang
                55.594 to 12.672  // Dragør Fort
            )
            "trail_mols_bjerge" -> listOf(
                56.275 to 10.482, // Kalø Slotsruin
                56.235 to 10.525, // Agri Bavnehøj
                56.212 to 10.485, // Trehøje
                56.196 to 10.677, // Ebeltoft
                56.265 to 10.755, // Stubbe Sø
                56.355 to 10.825, // Bønnerup / Djursland
                56.415 to 10.885  // Grenaa
            )
            "trail_traekstien" -> listOf(
                56.169 to 9.546, // Silkeborg
                56.205 to 9.645, // Svostrup Kro
                56.265 to 9.685, // Kongensbro
                56.345 to 9.745, // Bjerringbro
                56.385 to 9.865, // Langå
                56.458 to 10.035 // Randers
            )
            "trail_skjoldungestien" -> listOf(
                55.641 to 12.081, // Roskilde
                55.625 to 12.015, // Boserup Skov
                55.605 to 11.970, // Lejre / Sagnlandet
                55.565 to 11.925, // Bidstrup Skovene
                55.555 to 11.895  // Hvalsø
            )
            "trail_drivvejen" -> listOf(
                56.555 to 8.315, // Lemvig / Klosterheden
                56.358 to 8.625, // Holstebro
                56.155 to 8.535, // Ulfborg / Stråsø Plantage
                55.955 to 8.495, // Skjern Å
                55.745 to 8.425, // Nørre Nebel
                55.545 to 8.485, // Varde Å
                55.328 to 8.763  // Ribe
            )
            "trail_nordsjaelland" -> listOf(
                56.038 to 12.615, // Helsingør / Kronborg
                56.085 to 12.455, // Hornbæk Plantage
                56.122 to 12.305, // Gilleleje
                56.058 to 12.085, // Tisvilde Hegn
                56.015 to 11.975, // Liseleje
                55.965 to 11.855  // Hundested
            )
            else -> return null
        }

        val trailMeta = getInitialTrails().find { it.id == trailId }
        val densePoints = mutableListOf<GeoPointDto>()
        for (i in 0 until anchorPoints.size - 1) {
            val (lat0, lng0) = anchorPoints[i]
            val (lat1, lng1) = anchorPoints[i + 1]
            val steps = 16
            val limit = if (i == anchorPoints.size - 2) steps else steps - 1
            for (s in 0..limit) {
                val t = s.toDouble() / steps.toDouble()
                val lat = lat0 + (lat1 - lat0) * t
                val lng = lng0 + (lng1 - lng0) * t
                val ele = 15.0 + 25.0 * kotlin.math.abs(sin((i * steps + s) * 0.25))
                densePoints.add(GeoPointDto(lat = lat, lng = lng, ele = ele))
            }
        }

        val walkKm = GeoUtils.calculatePolylineLengthKm(densePoints)
        val effectiveKm = trailMeta?.computedWalkingKm?.takeIf { it > 0.0 } ?: walkKm
        val minLat = densePoints.minOf { it.lat }
        val maxLat = densePoints.maxOf { it.lat }
        val minLng = densePoints.minOf { it.lng }
        val maxLng = densePoints.maxOf { it.lng }

        return TrailGeometryDto(
            trailId = trailId,
            osmRelationId = trailMeta?.osmRelationId ?: 0L,
            segments = listOf(
                TrailSegmentDto(
                    type = TrailSegmentType.WALK,
                    points = densePoints,
                    lengthKm = effectiveKm
                )
            ),
            totalWalkingKm = effectiveKm,
            totalAscentM = (effectiveKm * 6.5).toInt(),
            totalDescentM = (effectiveKm * 6.5).toInt(),
            gapsOver200mCount = 0,
            bboxNorth = maxLat,
            bboxSouth = minLat,
            bboxEast = maxLng,
            bboxWest = minLng,
            fetchedAt = System.currentTimeMillis()
        )
    }
}
