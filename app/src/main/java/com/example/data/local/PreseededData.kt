package com.example.data.local

import com.example.data.model.TrailEntity

object PreseededData {

    fun getInitialTrails(): List<TrailEntity> {
        return listOf(
            TrailEntity(
                id = "trail_ohavsstien",
                name = "Øhavsstien",
                description = "Danmarks berømte 220 km vandrerute rundt om det Sydfynske Øhav, gennem Tåsinge, Langeland, Ærø og Fyns smukkeste bakker og kyster.",
                region = "Fyn & Øerne",
                parentRegion = "Fyn & Øerne",
                osmRelationId = 1202410L
            ),
            TrailEntity(
                id = "trail_haervejen",
                name = "Hærvejen (Jylland)",
                description = "Den historiske vandrerute langs Jyllands højderyg fra Viborg til den tyske grænse ved Padborg med tusind års pilgrimstraditioner.",
                region = "Jylland",
                parentRegion = "Jylland",
                osmRelationId = 217482L
            ),
            TrailEntity(
                id = "trail_camoenoen",
                name = "Camønoen (Kongerigets venligste vandrerute)",
                description = "175 km vandrerute over Møn, Nyord og Bogø med dramatisk kridtklint, Dark Sky stjernehimmel og hyggelige landsbyer.",
                region = "Møn & Sydsjælland",
                parentRegion = "Sjælland",
                osmRelationId = 2839163L
            ),
            TrailEntity(
                id = "trail_bornholm_kyststi",
                name = "Kyststien Bornholm",
                description = "120 km spektakulær kystvandring hele vejen rundt om Bornholm med rå granitklipper, sandstrande og historiske røgerier.",
                region = "Bornholm",
                parentRegion = "Bornholm",
                osmRelationId = 13045749L
            ),
            TrailEntity(
                id = "trail_gendarmstien",
                name = "Gendarmstien",
                description = "84 km certificeret vandrerute langs Flensborg Fjord, hvor grænsegendarmerne patruljerede til fods.",
                region = "Sønderjylland",
                parentRegion = "Jylland",
                osmRelationId = 443124L
            ),
            TrailEntity(
                id = "trail_himmerlandsstien",
                name = "Himmerlandsstien",
                description = "69 km natursti anlagt på den nedlagte jernbane gennem det bakkede Himmerland fra Limfjorden til Viborg.",
                region = "Nordjylland",
                parentRegion = "Jylland",
                osmRelationId = 3031529L
            ),
            TrailEntity(
                id = "trail_amarminoen",
                name = "Amarminoen",
                description = "27 km populær københavnsk vandrerute gennem Amager Fælled, det åbne Kalvebod Fælled, Kongelunden og til historiske Dragør.",
                region = "Hovedstaden",
                parentRegion = "Sjælland",
                osmRelationId = 11741012L
            ),
            TrailEntity(
                id = "trail_mols_bjerge",
                name = "Mols Bjerge Stien",
                description = "60 km førsteklasses kuperet vandring gennem istidslandskabet i Nationalpark Mols Bjerge og forbi Kalø Slotsruin.",
                region = "Østjylland",
                parentRegion = "Jylland",
                osmRelationId = 6849200L
            ),
            TrailEntity(
                id = "trail_traekstien",
                name = "Trækstien Gudenåen",
                description = "70 km historisk sti langs Danmarks længste å, hvor pramdragerne førhen trak pramme mellem Silkeborg og Randers.",
                region = "Midtjylland",
                parentRegion = "Jylland",
                osmRelationId = 281902L
            ),
            TrailEntity(
                id = "trail_skjoldungestien",
                name = "Skjoldungestien",
                description = "40 km vandresti gennem vikingehistorie, fjordlandskab og dybe skove i Nationalpark Skjoldungernes Land.",
                region = "Midtsjælland",
                parentRegion = "Sjælland",
                osmRelationId = 1458788L
            ),
            TrailEntity(
                id = "trail_drivvejen",
                name = "Drivvejen (Vestkystens handelsrute)",
                description = "200 km historisk drivvej gennem vestjysk marsk, hede og klit fra Limfjorden til Vadehavet.",
                region = "Vestjylland",
                parentRegion = "Jylland",
                osmRelationId = 1166772L
            ),
            TrailEntity(
                id = "trail_nordsjaelland",
                name = "Nordsjællands Kyststi (Sjællandsleden)",
                description = "110 km vandring langs den Danske Riviera forbi Kronborg, kystbyer, Troldeskoven og sandklitterne i Tisvilde.",
                region = "Nordsjælland",
                parentRegion = "Sjælland",
                osmRelationId = 1970035L
            )
        )
    }
}
