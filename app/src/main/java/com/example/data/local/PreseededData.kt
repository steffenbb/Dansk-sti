package com.example.data.local

import com.example.data.model.TrailEntity

object PreseededData {

    const val CATALOGUE_SOURCE = "OSM wiki Denmark Walking Routes"

    fun getInitialTrails(): List<TrailEntity> {
        return listOf(
            TrailEntity(
                id = "trail_ohavsstien",
                name = "Øhavsstien",
                osmRelationId = 1202410L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_haervejen",
                name = "Hærvejen (Jylland)",
                osmRelationId = 1792585L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_camoenoen",
                name = "Camønoen (Kongerigets venligste vandrerute)",
                osmRelationId = null,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_bornholm_kyststi",
                name = "Kyststien Bornholm",
                osmRelationId = null,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_gendarmstien",
                name = "Gendarmstien",
                osmRelationId = 443124L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_himmerlandsstien",
                name = "Himmerlandsstien",
                osmRelationId = 3031529L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_amarminoen",
                name = "Amarminoen",
                osmRelationId = null,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_mols_bjerge",
                name = "Molsruten",
                osmRelationId = 178382L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_traekstien",
                name = "Trækstien Gudenåen",
                osmRelationId = 1710241L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_skjoldungestien",
                name = "Skjoldungestien",
                osmRelationId = 1458788L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_drivvejen",
                name = "Drivvejen (Vestkystens handelsrute)",
                osmRelationId = 1166772L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_nordsjaelland",
                name = "Nordsjællandsruten",
                osmRelationId = 3481232L,
                source = CATALOGUE_SOURCE
            ),
            // Round 9 C: 39 new OSM wiki Denmark Walking Routes relations
            TrailEntity(
                id = "trail_e1",
                name = "E1",
                osmRelationId = 297398L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_e6",
                name = "E6",
                osmRelationId = 556666L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_nordsoestien",
                name = "Nordsøstien",
                osmRelationId = 2203180L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_sjaellandsleden",
                name = "Sjællandsleden",
                osmRelationId = 1970035L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_pilgrimsruten",
                name = "Pilgrimsruten",
                osmRelationId = 1792586L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_fjordstien",
                name = "Fjordstien",
                osmRelationId = 1595942L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_sjaellandsleden_2012",
                name = "Sjællandsleden 2012",
                osmRelationId = 2813821L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_sydsjaellandsleden",
                name = "Sydsjællandsleden",
                osmRelationId = 309907L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_haervejen_nordvest",
                name = "Hærvejen nordvest",
                osmRelationId = 4004055L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_moen_rundt",
                name = "Møn Rundt",
                osmRelationId = 2839163L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_haervejen_nordoest",
                name = "Hærvejen nordøst",
                osmRelationId = 4004054L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_kyst_til_kyst_stien",
                name = "Kyst til Kyst stien",
                osmRelationId = 374893L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_camino_haderslev_naes",
                name = "Camino Haderslev Næs",
                osmRelationId = 8220396L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_vestkyststien",
                name = "Vestkyststien",
                osmRelationId = 1166751L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_paradisruten",
                name = "Paradisruten",
                osmRelationId = 2836954L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_sejeroe_bugt_ruten",
                name = "Sejerø Bugt Ruten",
                osmRelationId = 2838931L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_lagunestien",
                name = "Lagunestien",
                osmRelationId = 2820402L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_gudernes_straede",
                name = "Gudernes Stræde",
                osmRelationId = 2717313L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_kongeaastien",
                name = "Kongeåstien",
                osmRelationId = 2021716L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_aarhus_silkeborg",
                name = "Vandreruten Århus-Silkeborg",
                osmRelationId = 3029875L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_alsstien",
                name = "Alsstien",
                osmRelationId = 1727731L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_haervejen_nord",
                name = "Hærvejen nord",
                osmRelationId = 4003809L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_bryrupbanestien",
                name = "Bryrupbanestien",
                osmRelationId = 3029770L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_jyderupstien",
                name = "Jyderupstien",
                osmRelationId = 2076253L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_musholmleden",
                name = "Musholmleden",
                osmRelationId = 2838766L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_nibe_hvalpsund",
                name = "Naturstien Nibe-Hvalpsund",
                osmRelationId = 3030656L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_marskstien",
                name = "Marskstien",
                osmRelationId = 8397167L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_halsninoen",
                name = "Halsninoen",
                osmRelationId = 13577498L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_alhedestien",
                name = "Alhedestien",
                osmRelationId = 3031593L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_gjernstien",
                name = "Gjernstien",
                osmRelationId = 1710329L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_trelleborgleden",
                name = "Trelleborgleden",
                osmRelationId = 2843842L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_moelleaadalen",
                name = "Mølleådalen",
                osmRelationId = 3038370L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_den_skaeve_bane",
                name = "Den Skæve Bane",
                osmRelationId = 3030189L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_gjerrildbanestien",
                name = "Gjerrildbanestien",
                osmRelationId = 2932602L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_fjord_til_fjord_naturstien",
                name = "Fjord til Fjord Naturstien",
                osmRelationId = 2466789L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_susaaleden",
                name = "Susåleden",
                osmRelationId = 2830942L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_falster_rundt",
                name = "Falster Rundt",
                osmRelationId = 2835117L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_oestersoestien",
                name = "Østersøstien",
                osmRelationId = 2836839L,
                source = CATALOGUE_SOURCE
            ),
            TrailEntity(
                id = "trail_djurslandstien",
                name = "Djurslandstien",
                osmRelationId = 14190349L,
                source = CATALOGUE_SOURCE
            )
        )
    }
}
