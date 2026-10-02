package com.example.data.local

import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PoiEntity
import com.example.data.model.ShelterEntity
import com.example.data.model.TrailEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

object PreseededData {

    private val moshi = Moshi.Builder().build()
    private val pointListType = Types.newParameterizedType(List::class.java, GeoPointDto::class.java)
    private val pointAdapter = moshi.adapter<List<GeoPointDto>>(pointListType)

    fun getInitialTrails(): List<TrailEntity> {
        val list = mutableListOf<TrailEntity>()

        // 1. Øhavsstien (Mandatory - South Funen Archipelago, 220 km)
        list.add(
            TrailEntity(
                id = "trail_ohavsstien",
                name = "Øhavsstien",
                description = "Danmarks berømte 220 km vandrerute rundt om det Sydfynske Øhav, gennem Tåsinge, Langeland, Ærø og Fyns smukkeste bakker og kyster.",
                lengthKm = 220.0,
                region = "Fyn & Øerne",
                elevationGainM = 1450,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.ohavsstienPoints),
                bboxNorth = 55.15,
                bboxSouth = 54.72,
                bboxEast = 10.82,
                bboxWest = 10.08,
                osmRelationId = 1108253L
            )
        )

        // 2. Hærvejen (Jyllands rygrad, Viborg til Padborg, 280 km)
        list.add(
            TrailEntity(
                id = "trail_haervejen",
                name = "Hærvejen (Jylland)",
                description = "Den historiske vandrerute langs Jyllands højderyg fra Viborg til den tyske grænse ved Padborg med tusind års pilgrimstraditioner.",
                lengthKm = 280.0,
                region = "Jylland",
                elevationGainM = 1680,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.haervejenPoints),
                bboxNorth = 56.48,
                bboxSouth = 54.80,
                bboxEast = 9.45,
                bboxWest = 9.18,
                osmRelationId = 217482L
            )
        )

        // 3. Camønoen (Møn, Bogø, Nyord, 175 km)
        list.add(
            TrailEntity(
                id = "trail_camoenoen",
                name = "Camønoen (Kongerigets venligste vandrerute)",
                description = "175 km vandrerute over Møn, Nyord og Bogø med dramatisk kridtklint, Dark Sky stjernehimmel og hyggelige landsbyer.",
                lengthKm = 175.0,
                region = "Møn & Sydsjælland",
                elevationGainM = 1200,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.camoenoenPoints),
                bboxNorth = 55.05,
                bboxSouth = 54.88,
                bboxEast = 12.58,
                bboxWest = 12.02,
                osmRelationId = 6492100L
            )
        )

        // 4. Kyststien Bornholm (Bornholm Rundt, 120 km)
        list.add(
            TrailEntity(
                id = "trail_bornholm_kyststi",
                name = "Kyststien Bornholm",
                description = "120 km spektakulær kystvandring hele vejen rundt om Bornholm med rå granitklipper, sandstrande og historiske røgerier.",
                lengthKm = 120.0,
                region = "Bornholm",
                elevationGainM = 1100,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.bornholmPoints),
                bboxNorth = 55.30,
                bboxSouth = 54.98,
                bboxEast = 15.16,
                bboxWest = 14.68,
                osmRelationId = 2390142L
            )
        )

        // 5. Gendarmstien (Danmarks første europæiske kvalitetsvandrevej, 84 km)
        list.add(
            TrailEntity(
                id = "trail_gendarmstien",
                name = "Gendarmstien",
                description = "84 km certificeret vandrerute langs Flensborg Fjord, hvor grænsegendarmerne patruljerede til fods.",
                lengthKm = 84.0,
                region = "Sønderjylland",
                elevationGainM = 580,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.gendarmstienPoints),
                bboxNorth = 54.97,
                bboxSouth = 54.82,
                bboxEast = 9.98,
                bboxWest = 9.34,
                osmRelationId = 241982L
            )
        )

        // 6. Himmerlandsstien (Løgstør til Viborg, 69 km)
        list.add(
            TrailEntity(
                id = "trail_himmerlandsstien",
                name = "Himmerlandsstien",
                description = "69 km natursti anlagt på den nedlagte jernbane gennem det bakkede Himmerland fra Limfjorden til Viborg.",
                lengthKm = 69.0,
                region = "Nordjylland",
                elevationGainM = 390,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.himmerlandsstienPoints),
                bboxNorth = 56.98,
                bboxSouth = 56.43,
                bboxEast = 9.48,
                bboxWest = 9.22,
                osmRelationId = 294821L
            )
        )

        // 7. Amarminoen (København til Dragør, 27 km)
        list.add(
            TrailEntity(
                id = "trail_amarminoen",
                name = "Amarminoen",
                description = "27 km populær københavnsk vandrerute gennem Amager Fælled, det åbne Kalvebod Fælled, Kongelunden og til historiske Dragør.",
                lengthKm = 27.0,
                region = "Hovedstaden",
                elevationGainM = 45,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.amarminoenPoints),
                bboxNorth = 55.67,
                bboxSouth = 55.54,
                bboxEast = 12.69,
                bboxWest = 12.53,
                osmRelationId = 11849201L
            )
        )

        // 8. Mols Bjerge Stien (Nationalpark Mols Bjerge, 60 km)
        list.add(
            TrailEntity(
                id = "trail_mols_bjerge",
                name = "Mols Bjerge Stien",
                description = "60 km førsteklasses kuperet vandring gennem istidslandskabet i Nationalpark Mols Bjerge og forbi Kalø Slotsruin.",
                lengthKm = 60.0,
                region = "Østjylland",
                elevationGainM = 920,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.molsPoints),
                bboxNorth = 56.26,
                bboxSouth = 56.16,
                bboxEast = 10.70,
                bboxWest = 10.55,
                osmRelationId = 6849200L
            )
        )

        // 9. Trækstien (Silkeborg til Randers langs Gudenåen, 70 km)
        list.add(
            TrailEntity(
                id = "trail_traekstien",
                name = "Trækstien Gudenåen",
                description = "70 km historisk sti langs Danmarks længste å, hvor pramdragerne førhen trak pramme mellem Silkeborg og Randers.",
                lengthKm = 70.0,
                region = "Midtjylland",
                elevationGainM = 240,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.traekstienPoints),
                bboxNorth = 56.47,
                bboxSouth = 56.15,
                bboxEast = 10.05,
                bboxWest = 9.53,
                osmRelationId = 281902L
            )
        )

        // 10. Skjoldungestien (Nationalpark Skjoldungernes Land, Roskilde til Hvalsø, 40 km)
        list.add(
            TrailEntity(
                id = "trail_skjoldungestien",
                name = "Skjoldungestien",
                description = "40 km vandresti gennem vikingehistorie, fjordlandskab og dybe skove i Nationalpark Skjoldungernes Land.",
                lengthKm = 40.0,
                region = "Midtsjælland",
                elevationGainM = 310,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.skjoldungestienPoints),
                bboxNorth = 55.67,
                bboxSouth = 55.56,
                bboxEast = 12.10,
                bboxWest = 11.83,
                osmRelationId = 4129034L
            )
        )

        // 11. Drivvejen (Vestjylland, Thyborøn til Tønder, 200 km)
        list.add(
            TrailEntity(
                id = "trail_drivvejen",
                name = "Drivvejen (Vestkystens handelsrute)",
                description = "200 km historisk drivvej gennem vestjysk marsk, hede og klit fra Limfjorden til Vadehavet.",
                lengthKm = 200.0,
                region = "Vestjylland",
                elevationGainM = 480,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.drivvejenPoints),
                bboxNorth = 56.72,
                bboxSouth = 54.91,
                bboxEast = 8.90,
                bboxWest = 8.18,
                osmRelationId = 918230L
            )
        )

        // 12. Nordsjællandsruten / Sjællandsleden (Helsingør til Hundested, 110 km)
        list.add(
            TrailEntity(
                id = "trail_nordsjaelland",
                name = "Nordsjællands Kyststi (Sjællandsleden)",
                description = "110 km vandring langs den Danske Riviera forbi Kronborg, kystbyer, Troldeskoven og sandklitterne i Tisvilde.",
                lengthKm = 110.0,
                region = "Nordsjælland",
                elevationGainM = 520,
                pointsJson = pointAdapter.toJson(RealDanishTrailTracks.nordsjaellandPoints),
                bboxNorth = 56.14,
                bboxSouth = 55.95,
                bboxEast = 12.63,
                bboxWest = 11.82,
                osmRelationId = 1849202L
            )
        )

        return list
    }

    fun getInitialShelters(): List<ShelterEntity> {
        return listOf(
            // --- 1. Øhavsstien Shelters ---
            ShelterEntity(
                id = "sh_svendborg_skov",
                name = "Christiansminde Shelterplads",
                lat = 55.0530,
                lng = 10.6480,
                description = "Offentlig shelterplads i Christiansminde skov med udsigt over Svendborgsund. Bålplads og brænde.",
                bookingRequired = false,
                ownership = "Svendborg Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Christiansminde",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                accessibility = "God adgang for vandrere og cyklister"
            ),
            ShelterEntity(
                id = "sh_egebjerg_bakker",
                name = "Egebjerg Mølle Naturlejrplads",
                lat = 55.0460,
                lng = 10.5180,
                description = "2 primitive træsheltere beliggende ved foden af Egebjerg Mølle. Offentlig adgang for vandrere.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://bookenshelter.dk/fyn/shelter/egebjerg-moelle/",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_svanninge_bjerge",
                name = "Svanninge Bjerge Naturstyrelsen Shelter",
                lat = 55.1220,
                lng = 10.2460,
                description = "Beliggende i 'De Fynske Alper'. Fantastisk udsigt, godkendt til primitive vandrere. Kræver forudgående booking på udinaturen.dk.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Svanninge+Bjerge",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_langeland_trane",
                name = "Tranekær Skovlejrplads",
                lat = 54.8960,
                lng = 10.6890,
                description = "Hyggelig shelterplads i Tranekær Fredskov nær slotsparken. Gratis for vandrere.",
                bookingRequired = false,
                ownership = "Langeland Kommune",
                capacity = 6,
                hasWaterNearby = false,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://bookenshelter.dk/fyn/shelter/tranekaer/",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_aero_voderup",
                name = "Voderup Klint Shelter",
                lat = 54.8640,
                lng = 10.3280,
                description = "Udsigt over Østersøen på Ærø. Offentlig naturlejrplads etableret af Ærø Kommune.",
                bookingRequired = false,
                ownership = "Ærø Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Voderup+Klint",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_ohav_dovns",
                name = "Dovns Klint Shelterplads",
                lat = 54.7460,
                lng = 10.7040,
                description = "Langelands sydligste punkt ved vilde heste og klintkyst. Først-til-mølle.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Dovns+Klint",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_ohav_soeby",
                name = "Søby Volde Shelter",
                lat = 54.8950,
                lng = 10.2540,
                description = "Historisk voldsted på Nordærø. Tæt på færgelejet og Vitsø Nor.",
                bookingRequired = true,
                ownership = "Ærø Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "bookenshelter",
                trailCorridorId = "trail_ohavsstien",
                bookingUrl = "https://bookenshelter.dk/fyn/shelter/soeby-volde/",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 40,
                maxNights = 2
            ),

            // --- 2. Hærvejen Shelters ---
            ShelterEntity(
                id = "sh_hald_hovedgaard",
                name = "Hald Ege Primitive Lejrplads",
                lat = 56.3840,
                lng = 9.3850,
                description = "Naturstyrelsen shelterplads ved Hald Sø. Rent vand og multtoilet inden for 200m.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Hald+S%C3%B8",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_tinnet_krat",
                name = "Tinnet Krat / Gudenåens Kilder Shelter",
                lat = 55.9340,
                lng = 9.4010,
                description = "Vandrestop ved Gudenåens udspring. 3 shelters med bålplads. Booking på bookenshelter.dk.",
                bookingRequired = true,
                ownership = "Vejle Kommune",
                capacity = 14,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://bookenshelter.dk/shelter/tinnet-krat/",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_jelling_skov",
                name = "Fårup Sø Naturlejrplads",
                lat = 55.7580,
                lng = 9.4180,
                description = "Offentlig lejrplads nær Jelling Monumenterne. Velegnet til pilgrimsvandrere.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Jelling",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_jels_soerne",
                name = "Jels Voldsted Shelterplads",
                lat = 55.3980,
                lng = 9.2480,
                description = "Placeret ved Nedersøen i Jels. Bålhytte, vandpost og handicapvenligt toilet.",
                bookingRequired = true,
                ownership = "Vejen Kommune",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Jels+Voldsted",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_haervej_randboel",
                name = "Randbøldal Hede Pilgrimsshelter",
                lat = 55.6740,
                lng = 9.3240,
                description = "Primitive shelters ved Danmarks største indlandsklit og hede. Fred og ro.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Randb%C3%B8l+Hede",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_haervej_baekke",
                name = "Bække Klebæk Høje Shelter",
                lat = 55.5830,
                lng = 9.2980,
                description = "Vandrestop tæt ved runestenen og skibssætningen ved Klebæk Høje.",
                bookingRequired = false,
                ownership = "Vejen Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://haervej.dk/shelters/baekke",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_haervej_vojens",
                name = "Tørning Mølle Shelterplads",
                lat = 55.2830,
                lng = 9.2840,
                description = "Beliggende i tunneldalen ved Tørning Mølle og Christiansdal Vandkraftværk.",
                bookingRequired = false,
                ownership = "Haderslev Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=T%C3%B8rning+M%C3%B8lle",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_haervej_padborg",
                name = "Padborg Frøslev Plantage Lejr",
                lat = 54.8270,
                lng = 9.3620,
                description = "Sidste etape mod den dansk-tyske grænse i Frøslev Plantage. Åben for alle.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_haervejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Padborg+Skov",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 3. Camønoen Shelters ---
            ShelterEntity(
                id = "sh_nyord_havn",
                name = "Nyord Enghave Shelter",
                lat = 55.0440,
                lng = 12.1980,
                description = "Rolig placering i udkanten af Nyord by med udsigt over fuglereservatet.",
                bookingRequired = false,
                ownership = "Vordingborg Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "camoenoen",
                trailCorridorId = "trail_camoenoen",
                bookingUrl = "https://camoenoen.dk/shelters/nyord",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_moens_klint",
                name = "Klinteskoven Mandemarke Bakker",
                lat = 54.9720,
                lng = 12.5450,
                description = "Statslig shelterplads i Klinteskoven lige ved Møns Klint. Dark Sky certificeret.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_camoenoen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Mandemarke+Bakker",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_camoen_ulvshale",
                name = "Ulvshale Strandskov Shelter",
                lat = 55.0320,
                lng = 12.2640,
                description = "Gemt i den krogede egeskov på Ulvshale tæt ved børnevenlig strand.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_camoenoen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Ulvshale",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_camoen_fanefjord",
                name = "Fanefjord Skov Shelterplads",
                lat = 54.9010,
                lng = 12.1520,
                description = "Placeret i bøgeskov helt ud til Grønsund med storslået solnedgang.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_camoenoen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Fanefjord+Skov",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 4. Bornholm Kyststi Shelters ---
            ShelterEntity(
                id = "sh_hammeren_fyr",
                name = "Hammerknuden Kystplads",
                lat = 55.2920,
                lng = 14.7750,
                description = "Granitklipper, vild kystudsigt mod Sverige. Offentlig statsshelter under Naturstyrelsen Bornholm.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_bornholm_kyststi",
                bookingUrl = "https://udinaturen.dk/omraader?search=Hammerknuden",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_dueodde_klit",
                name = "Dueodde Fyrreskov Shelter",
                lat = 55.0020,
                lng = 15.0780,
                description = "Ligger i nåleskoven 300 meter fra Danmarks fineste sandstrand. Fri afbenyttelse.",
                bookingRequired = false,
                ownership = "Bornholms Regionskommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_bornholm_kyststi",
                bookingUrl = "https://udinaturen.dk/omraader?search=Dueodde",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_bornholm_helligdom",
                name = "Helligdomsklipperne Kystly",
                lat = 55.2240,
                lng = 14.8960,
                description = "Hævet over de dramatiske klipper med kig mod Christiansø. Bålplads.",
                bookingRequired = false,
                ownership = "Bornholms Regionskommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_bornholm_kyststi",
                bookingUrl = "https://udinaturen.dk/omraader?search=Helligdomsklipperne",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_bornholm_jonskapel",
                name = "Jons Kapel Granitshelter",
                lat = 55.2350,
                lng = 14.7180,
                description = "Vestkystens vilde granitlandskab. Solnedgang over havet mod Sverige.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 6,
                hasWaterNearby = false,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_bornholm_kyststi",
                bookingUrl = "https://udinaturen.dk/omraader?search=Jons+Kapel",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 5. Gendarmstien Shelters ---
            ShelterEntity(
                id = "sh_kollund_skov",
                name = "Kollund Skov Shelter",
                lat = 54.8650,
                lng = 9.4240,
                description = "Den Danske Naturfonds lejrplads i Kollund Skov ved Flensborg Fjord.",
                bookingRequired = false,
                ownership = "Den Danske Naturfond",
                capacity = 6,
                hasWaterNearby = false,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_gendarmstien",
                bookingUrl = "https://naturfonden.dk/natur/kollund-skov/",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_rendbjerg",
                name = "Rendbjerg Teglværk Shelter",
                lat = 54.8940,
                lng = 9.6880,
                description = "Strandnært shelter langs Gendarmstien ved Broagerland. Booking på bookenshelter.dk.",
                bookingRequired = true,
                ownership = "Sønderborg Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_gendarmstien",
                bookingUrl = "https://bookenshelter.dk/shelter/rendbjerg/",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_gendarm_krusaamolle",
                name = "Kruså Mølle Naturlejrplads",
                lat = 54.8480,
                lng = 9.4050,
                description = "Lige ved den gamle grænseovergang i Kruså og Møllesøen. Roligt og beskyttet.",
                bookingRequired = false,
                ownership = "Aabenraa Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_gendarmstien",
                bookingUrl = "https://gendarmsti.dk/shelters/krusaamolle",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 6. Amarminoen Shelters ---
            ShelterEntity(
                id = "sh_pinseskoven",
                name = "Pinseskoven / Birkelunden Shelter",
                lat = 55.5790,
                lng = 12.5480,
                description = "I Danmarks største birkeskov på Vestamager. Bålplads og fugletårn tæt ved.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_amarminoen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Pinseskoven",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_amar_kongelunden",
                name = "Kongelunden Sydvestlejr",
                lat = 55.5680,
                lng = 12.5760,
                description = "Under trækronerne i Kongelunden tæt på Sydvestpynten og kysten.",
                bookingRequired = false,
                ownership = "Dragør Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_amarminoen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Kongelunden",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 7. Mols Bjerge Stien Shelters ---
            ShelterEntity(
                id = "sh_mols_trehoeje",
                name = "Trehøje Enebærdalen Naturstyrelsen",
                lat = 56.2280,
                lng = 10.5840,
                description = "Hjertet af Nationalpark Mols Bjerge. Udsigt over istidslandskab og Ebeltoft Vig.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_mols_bjerge",
                bookingUrl = "https://nationalparkmolsbjerge.dk/shelters/trehoeje",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_mols_femmoeller",
                name = "Femmøller Skovlejrplads",
                lat = 56.2380,
                lng = 10.6020,
                description = "I den dybe slugt ved Femmøller Strand og Mølleåen. Bålplads.",
                bookingRequired = false,
                ownership = "Syddjurs Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_mols_bjerge",
                bookingUrl = "https://udinaturen.dk/omraader?search=Femm%C3%B8ller+Skov",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_mols_bogens",
                name = "Bogens Sø Primitive Lejrplads",
                lat = 56.2520,
                lng = 10.5480,
                description = "Uforstyrret natur ved søbredden i Mols Bjerge. Først-til-mølle.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 6,
                hasWaterNearby = false,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_mols_bjerge",
                bookingUrl = "https://udinaturen.dk/omraader?search=Bogens+S%C3%B8",
                hasFireRing = true,
                hasFirewood = false,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 8. Trækstien Shelters ---
            ShelterEntity(
                id = "sh_traek_svostrup",
                name = "Svostrup Pramdragerlejr v/ Gudenåen",
                lat = 56.2420,
                lng = 9.6580,
                description = "Historisk træksti-shelter lige ved åbrinken over for Svostrup Kro. Kano- og vandrestop.",
                bookingRequired = true,
                ownership = "Silkeborg Kommune",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://traekstien.dk/shelters/svostrup",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 40,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_traek_kongensbro",
                name = "Kongensbro Åbrink Shelter",
                lat = 56.2950,
                lng = 9.6650,
                description = "Shelterplads midtvejs på Trækstien mellem Silkeborg og Randers.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Kongensbro",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_traek_tange",
                name = "Tange Sø Naturcenter Shelter",
                lat = 56.3520,
                lng = 9.6020,
                description = "Ved Danmarks største kunstige sø og Elmuseet. Gode bade- og fiskemuligheder.",
                bookingRequired = false,
                ownership = "Viborg Kommune",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Tange+S%C3%B8",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 9. Skjoldungestien Shelters ---
            ShelterEntity(
                id = "sh_skjold_boserup",
                name = "Boserup Skov Strandly",
                lat = 55.6620,
                lng = 11.9920,
                description = "Smukt beliggende shelterplads i Boserup Skov ud til Kattinge Vig i Nationalpark Skjoldungernes Land.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_skjoldungestien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Boserup+Skov",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_skjold_tadre",
                name = "Tadre Mølle Naturlejrplads",
                lat = 55.5860,
                lng = 11.8740,
                description = "Idyllisk shelter ved Danmarks sidste fungerende vandmølle i Elverdamsdalen.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_skjoldungestien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Tadre+M%C3%B8lle",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_skjold_lejre",
                name = "Lejre Ådal Fredsshelter",
                lat = 55.6040,
                lng = 11.9720,
                description = "Tæt ved Sagnlandet Lejre og kongehallerne. Fantastisk udsigt over ådalen.",
                bookingRequired = false,
                ownership = "Lejre Kommune",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_skjoldungestien",
                bookingUrl = "https://skjoldungestien.dk/shelters/lejre",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),

            // --- 10. Himmelbjergruten Shelters ---
            ShelterEntity(
                id = "sh_himmel_ryenorreskov",
                name = "Rye Nørreskov Fyrreshelter",
                lat = 56.1080,
                lng = 9.7120,
                description = "I Danmarks største sammenhængende skovområde syd for Julsø. Højt til himlen.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_himmelbjergruten",
                bookingUrl = "https://udinaturen.dk/omraader?search=Rye+N%C3%B8rreskov",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2
            ),
            ShelterEntity(
                id = "sh_himmel_bjerget",
                name = "Himmelbjerget Udsigtslejr",
                lat = 56.1040,
                lng = 9.6840,
                description = "Placeret tæt på Himmelbjergets tårn med panorama over Silkeborgsøerne.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Himmelbjerget",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2,
                shelterType = "Traditionelt træshelter"
            ),

            // --- 11. Gudenåen Kano & Træksti Shelters ---
            ShelterEntity(
                id = "sh_kano_toerring",
                name = "Tørring Telt & Kanolejrplads",
                lat = 55.8560,
                lng = 9.4880,
                description = "Startstedet for Gudenåsejlads. Store shelters, varmt brusebad og bålplads.",
                bookingRequired = true,
                ownership = "Hedensted Kommune",
                capacity = 16,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://toerring-kanoudlejning.dk/teltplads/",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 50,
                maxNights = 2,
                shelterType = "Kombineret telt- og shelterplads"
            ),
            ShelterEntity(
                id = "sh_kano_klostermoelle",
                name = "Klostermølle ved Mossø Lejr",
                lat = 56.0380,
                lng = 9.6840,
                description = "Historisk klostermølleanlæg ved bredden af Danmarks dybeste sø Mossø.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_traekstien",
                bookingUrl = "https://udinaturen.dk/omraader?search=Klosterm%C3%B8lle",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2,
                shelterType = "Primitiv lejrplads med sheltergruppe"
            ),

            // --- 12. Himmerlandsstien Shelters ---
            ShelterEntity(
                id = "sh_himmer_loegstoer",
                name = "Løgstør Limfjordsblik Shelter",
                lat = 56.9620,
                lng = 9.2540,
                description = "Ved muslingebyen Løgstør med kig til Limfjorden og Frederik VII's Kanal.",
                bookingRequired = false,
                ownership = "Vesthimmerlands Kommune",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_himmerlandsstien",
                bookingUrl = "https://vesthimmerland.dk/shelters/loegstoer",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Klassisk træshelter"
            ),
            ShelterEntity(
                id = "sh_himmer_aars",
                name = "Aars Gamle Banesti Lejrplads",
                lat = 56.8040,
                lng = 9.5120,
                description = "Primitive shelters ved den nedlagte jernbane i Aars. Tæt på proviant.",
                bookingRequired = false,
                ownership = "Vesthimmerlands Kommune",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_himmerlandsstien",
                bookingUrl = "https://vesthimmerland.dk/shelters/aars",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Primitiv lejrplads med sheltergruppe"
            ),

            // --- 13. Drivvejen Shelters ---
            ShelterEntity(
                id = "sh_driv_ribe",
                name = "Ribe Marsk & Ådal Shelter",
                lat = 55.3280,
                lng = 8.7420,
                description = "Primitiv lejrplads ved Ribe Vesterå med fantastisk udsigt over marsken og domkirkens tårn.",
                bookingRequired = false,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_drivvejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Ribe+Marsk",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Åbent bjælkeshelter"
            ),
            ShelterEntity(
                id = "sh_driv_skjern",
                name = "Skjern Å Delta Pumpestation Shelter",
                lat = 55.9180,
                lng = 8.4250,
                description = "I hjertet af Danmarks største naturgenopretning ved Skjern Å med trækfærger og fugletårne.",
                bookingRequired = false,
                ownership = "Naturstyrelsen Vadehavet",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_drivvejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Skjern+Enge",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Kombineret telt- og shelterplads"
            ),
            ShelterEntity(
                id = "sh_driv_blaabjerg",
                name = "Blåbjerg Klitplantage Shelter",
                lat = 55.7530,
                lng = 8.2420,
                description = "Omgivet af høje klitter og nåleskov tæt ved Danmarks højeste klit Blåbjerg.",
                bookingRequired = true,
                ownership = "Naturstyrelsen Blåvandshuk",
                capacity = 12,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_drivvejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Blaabjerg",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 30,
                maxNights = 2,
                shelterType = "Shelter med myggenet & låge"
            ),
            ShelterEntity(
                id = "sh_driv_toender",
                name = "Tønder Marsk & Vidå Shelter",
                lat = 54.9350,
                lng = 8.8620,
                description = "Fredeligt beliggende ved Vidåen tæt på den dansk-tyske grænse.",
                bookingRequired = false,
                ownership = "Tønder Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_drivvejen",
                bookingUrl = "https://udinaturen.dk/omraader?search=Toender+Marsk",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Klassisk træshelter"
            ),

            // --- 14. Nordsjællands Kyststi Shelters ---
            ShelterEntity(
                id = "sh_nord_tisvilde",
                name = "Tisvilde Hegn Harehøj Shelter",
                lat = 56.0420,
                lng = 12.0650,
                description = "I den fortryllende Troldeskov i Tisvilde Hegn tæt ved Kattegatkysten.",
                bookingRequired = true,
                ownership = "Naturstyrelsen Nordsjælland",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_nordsjaelland",
                bookingUrl = "https://udinaturen.dk/omraader?search=Tisvilde+Hegn",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2,
                shelterType = "Lukket shelter med låge"
            ),
            ShelterEntity(
                id = "sh_nord_hornbaek",
                name = "Hornbæk Plantage Kystlejr",
                lat = 56.0910,
                lng = 12.4820,
                description = "Kun 150 meter fra Øresunds kyst med havudsigt til Kullen i Sverige.",
                bookingRequired = false,
                ownership = "Helsingør Kommune",
                capacity = 10,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_nordsjaelland",
                bookingUrl = "https://udinaturen.dk/omraader?search=Hornbaek+Plantage",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Klassisk træshelter"
            ),
            ShelterEntity(
                id = "sh_nord_teglstrup",
                name = "Teglstrup Hegn Shelter",
                lat = 56.0480,
                lng = 12.5620,
                description = "Fredfyldt skovshelter ved Klaresø og Hammermøllen nordvest for Helsingør.",
                bookingRequired = true,
                ownership = "Naturstyrelsen",
                capacity = 8,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_nordsjaelland",
                bookingUrl = "https://udinaturen.dk/omraader?search=Teglstrup+Hegn",
                hasFireRing = true,
                hasFirewood = true,
                fee = true,
                priceDkk = 35,
                maxNights = 2,
                shelterType = "Primitiv lejrplads med sheltergruppe"
            ),
            ShelterEntity(
                id = "sh_nord_asserbo",
                name = "Asserbo Slotsruin Lejrplads",
                lat = 55.9980,
                lng = 12.0450,
                description = "Historisk atmosfære ved ruinerne af den gamle middelalderborg og voldgrav i Asserbo.",
                bookingRequired = false,
                ownership = "Gribskov Kommune",
                capacity = 6,
                hasWaterNearby = true,
                hasToiletNearby = true,
                source = "udinaturen",
                trailCorridorId = "trail_nordsjaelland",
                bookingUrl = "https://udinaturen.dk/omraader?search=Asserbo+Slotsruin",
                hasFireRing = true,
                hasFirewood = true,
                fee = false,
                priceDkk = 0,
                maxNights = 2,
                shelterType = "Klassisk træshelter"
            )
        )
    }

    fun getInitialFriTeltning(): List<FriTeltningEntity> {
        val rules = "1-2-3 reglen: 1 nat samme sted, max 2 stk 3-personers telte, kun åben ild på bålpladser. Efterlad ingen spor."
        val url = "https://naturstyrelsen.dk/aktiviteter-i-naturen/overnatning-i-naturen/fri-teltning/"

        return listOf(
            // 1. Svanninge Bakker (Øhavsstien)
            FriTeltningEntity(
                id = "ft_svanninge",
                name = "Svanninge Bakker Fri Teltning Zone",
                centerLat = 55.1240,
                centerLng = 10.2440,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.1320, 10.2350),
                    GeoPointDto(55.1310, 10.2580),
                    GeoPointDto(55.1180, 10.2550),
                    GeoPointDto(55.1190, 10.2320),
                    GeoPointDto(55.1320, 10.2350)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 140.0,
                trailCorridorId = "trail_ohavsstien",
                forestType = "Bøgeskov og hedebakker",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 400,
                toiletDistanceMeters = 600
            ),
            // 2. Langeland Nørreskov (Øhavsstien)
            FriTeltningEntity(
                id = "ft_langeland_nord",
                name = "Langeland Nørreskov Fri Teltningsskov",
                centerLat = 55.0120,
                centerLng = 10.8840,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.0250, 10.8720),
                    GeoPointDto(55.0240, 10.8980),
                    GeoPointDto(55.0020, 10.8950),
                    GeoPointDto(55.0040, 10.8700),
                    GeoPointDto(55.0250, 10.8720)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 320.0,
                trailCorridorId = "trail_ohavsstien",
                forestType = "Kystnær bøgeskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 700,
                toiletDistanceMeters = 900
            ),
            // 3. Tinnet Krat (Hærvejen)
            FriTeltningEntity(
                id = "ft_tinnet",
                name = "Tinnet Krat & Uldum Kær Fri Teltning",
                centerLat = 55.9320,
                centerLng = 9.3980,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.9450, 9.3820),
                    GeoPointDto(55.9440, 9.4120),
                    GeoPointDto(55.9220, 9.4100),
                    GeoPointDto(55.9230, 9.3800),
                    GeoPointDto(55.9450, 9.3820)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 480.0,
                trailCorridorId = "trail_haervejen",
                forestType = "Egekrat og mose",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 300,
                toiletDistanceMeters = 500
            ),
            // 4. Stenderup Nørreskov (Hærvejen)
            FriTeltningEntity(
                id = "ft_stenderup_skovene",
                name = "Stenderup Nørreskov Fri Teltning",
                centerLat = 55.4850,
                centerLng = 9.6520,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.5020, 9.6350),
                    GeoPointDto(55.4980, 9.6750),
                    GeoPointDto(55.4720, 9.6680),
                    GeoPointDto(55.4750, 9.6300),
                    GeoPointDto(55.5020, 9.6350)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 780.0,
                trailCorridorId = "trail_haervejen",
                forestType = "Gammel ege- og bøgeskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 600,
                toiletDistanceMeters = 800
            ),
            // 5. Mols Bjerge (Femmøller Skov)
            FriTeltningEntity(
                id = "ft_mols_bjerge",
                name = "Femmøller & Lyngsbæk Skov Fri Teltning",
                centerLat = 56.2360,
                centerLng = 10.6080,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.2480, 10.5950),
                    GeoPointDto(56.2460, 10.6220),
                    GeoPointDto(56.2250, 10.6180),
                    GeoPointDto(56.2260, 10.5920),
                    GeoPointDto(56.2480, 10.5950)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 350.0,
                trailCorridorId = "trail_mols_bjerge",
                forestType = "Kuperet skov og slugter",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 500,
                toiletDistanceMeters = 700
            ),
            // 6. Silkeborg Sønderskov (Trækstien & Himmelbjerget)
            FriTeltningEntity(
                id = "ft_silkeborg_skove",
                name = "Silkeborg Sønderskov Fri Teltningsskov",
                centerLat = 56.1420,
                centerLng = 9.5850,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.1600, 9.5600),
                    GeoPointDto(56.1580, 9.6200),
                    GeoPointDto(56.1250, 9.6100),
                    GeoPointDto(56.1280, 9.5550),
                    GeoPointDto(56.1600, 9.5600)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 1850.0,
                trailCorridorId = "trail_traekstien",
                forestType = "Højstammede bøge og søbredder",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 400,
                toiletDistanceMeters = 600
            ),
            // 7. Rye Nørreskov (Himmelbjergruten)
            FriTeltningEntity(
                id = "ft_rye_noerreskov",
                name = "Rye Nørreskov Fri Teltning",
                centerLat = 56.1080,
                centerLng = 9.7120,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.1220, 9.6950),
                    GeoPointDto(56.1200, 9.7300),
                    GeoPointDto(56.0950, 9.7280),
                    GeoPointDto(56.0960, 9.6920),
                    GeoPointDto(56.1220, 9.6950)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 1200.0,
                trailCorridorId = "trail_himmelbjergruten",
                forestType = "Fyrreskov og bakket terræn",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 600,
                toiletDistanceMeters = 800
            ),
            // 8. Boserup Skov (Skjoldungestien)
            FriTeltningEntity(
                id = "ft_boserup_skov",
                name = "Boserup Skov (Nationalpark Skjoldungerne)",
                centerLat = 55.6590,
                centerLng = 11.9880,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.6690, 11.9750),
                    GeoPointDto(55.6680, 12.0050),
                    GeoPointDto(55.6490, 12.0010),
                    GeoPointDto(55.6510, 11.9720),
                    GeoPointDto(55.6690, 11.9750)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 224.0,
                trailCorridorId = "trail_skjoldungestien",
                forestType = "Bøgeskov ved Roskilde Fjord",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 350,
                toiletDistanceMeters = 500
            ),
            // 9. Ulvshale Skov (Camønoen)
            FriTeltningEntity(
                id = "ft_ulvshale",
                name = "Ulvshale Urskov Fri Teltningsområde",
                centerLat = 55.0320,
                centerLng = 12.2640,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.0420, 12.2500),
                    GeoPointDto(55.0400, 12.2800),
                    GeoPointDto(55.0220, 12.2780),
                    GeoPointDto(55.0240, 12.2480),
                    GeoPointDto(55.0420, 12.2500)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 310.0,
                trailCorridorId = "trail_camoenoen",
                forestType = "Urskovsagtig egeskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 500,
                toiletDistanceMeters = 650
            ),
            // 10. Almindingen (Bornholm)
            FriTeltningEntity(
                id = "ft_almindingen",
                name = "Almindingen Midtbornholm Fri Teltningsskov",
                centerLat = 55.1250,
                centerLng = 14.9250,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.1480, 14.8980),
                    GeoPointDto(55.1460, 14.9520),
                    GeoPointDto(55.1050, 14.9480),
                    GeoPointDto(55.1080, 14.8950),
                    GeoPointDto(55.1480, 14.8980)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 3800.0,
                trailCorridorId = "trail_bornholm_kyststi",
                forestType = "Klippeskov og sprækkedale",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 800,
                toiletDistanceMeters = 950
            ),
            // 11. Gråsten Skovene (Gendarmstien)
            FriTeltningEntity(
                id = "ft_graasten",
                name = "Gråsten Skovene Fri Teltning",
                centerLat = 54.9280,
                centerLng = 9.5980,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(54.9400, 9.5820),
                    GeoPointDto(54.9380, 9.6150),
                    GeoPointDto(54.9180, 9.6120),
                    GeoPointDto(54.9200, 9.5800),
                    GeoPointDto(54.9400, 9.5820)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 520.0,
                trailCorridorId = "trail_gendarmstien",
                forestType = "Løvskov ved søer",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 450,
                toiletDistanceMeters = 600
            ),
            // 12. Kongelunden (Amarminoen)
            FriTeltningEntity(
                id = "ft_kongelunden",
                name = "Kongelunden Fri Teltningszone",
                centerLat = 55.5680,
                centerLng = 12.5760,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.5780, 12.5650),
                    GeoPointDto(55.5760, 12.5880),
                    GeoPointDto(55.5580, 12.5850),
                    GeoPointDto(55.5600, 12.5620),
                    GeoPointDto(55.5780, 12.5650)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 180.0,
                trailCorridorId = "trail_amarminoen",
                forestType = "Fyrre- og egeskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 300,
                toiletDistanceMeters = 400
            ),
            // 13. Rold Skov (Himmerlandsstien)
            FriTeltningEntity(
                id = "ft_rold_skov",
                name = "Rold Skov (Himmerland)",
                centerLat = 56.8120,
                centerLng = 9.8520,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.8500, 9.8100),
                    GeoPointDto(56.8450, 9.9100),
                    GeoPointDto(56.7800, 9.8900),
                    GeoPointDto(56.7850, 9.8050),
                    GeoPointDto(56.8500, 9.8100)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 8000.0,
                trailCorridorId = "trail_himmerlandsstien",
                forestType = "Danmarks største skovområde",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 500,
                toiletDistanceMeters = 750
            ),
            // 14. Gribskov (Sjælland)
            FriTeltningEntity(
                id = "ft_gribskov",
                name = "Gribskov Fri Teltningsskov",
                centerLat = 55.9850,
                centerLng = 12.3150,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.0200, 12.2800),
                    GeoPointDto(56.0250, 12.3500),
                    GeoPointDto(55.9550, 12.3600),
                    GeoPointDto(55.9500, 12.2900),
                    GeoPointDto(56.0200, 12.2800)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 5600.0,
                trailCorridorId = "trail_skjoldungestien",
                forestType = "Urskovsagtig bøge- og granskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 400,
                toiletDistanceMeters = 600,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            ),
            // 15. Blåbjerg & Kærgård Klitplantage (Drivvejen)
            FriTeltningEntity(
                id = "ft_blaabjerg",
                name = "Blåbjerg & Kærgård Klitplantage Fri Teltning",
                centerLat = 55.7480,
                centerLng = 8.2320,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(55.7700, 8.2100),
                    GeoPointDto(55.7680, 8.2550),
                    GeoPointDto(55.7250, 8.2450),
                    GeoPointDto(55.7280, 8.2050),
                    GeoPointDto(55.7700, 8.2100)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 2400.0,
                trailCorridorId = "trail_drivvejen",
                forestType = "Klitfyr, lyng og sandklitter",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 600,
                toiletDistanceMeters = 850,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            ),
            // 16. Klosterheden Bæverskov (Drivvejen)
            FriTeltningEntity(
                id = "ft_klosterheden",
                name = "Klosterheden Plantage Fri Teltningsskov",
                centerLat = 56.4650,
                centerLng = 8.3550,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.4950, 8.3100),
                    GeoPointDto(56.4900, 8.4100),
                    GeoPointDto(56.4350, 8.3950),
                    GeoPointDto(56.4400, 8.3050),
                    GeoPointDto(56.4950, 8.3100)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 6400.0,
                trailCorridorId = "trail_drivvejen",
                forestType = "Danmarks 3. største skov • Bæverland",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 500,
                toiletDistanceMeters = 700,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            ),
            // 17. Tisvilde Hegn & Troldeskoven (Nordsjælland)
            FriTeltningEntity(
                id = "ft_tisvilde_hegn",
                name = "Tisvilde Hegn & Troldeskoven Fri Teltning",
                centerLat = 56.0380,
                centerLng = 12.0480,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.0600, 12.0150),
                    GeoPointDto(56.0580, 12.0850),
                    GeoPointDto(56.0150, 12.0750),
                    GeoPointDto(56.0180, 12.0100),
                    GeoPointDto(56.0600, 12.0150)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 2050.0,
                trailCorridorId = "trail_nordsjaelland",
                forestType = "Troldeskov og kystfyr",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 400,
                toiletDistanceMeters = 550,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            ),
            // 18. Hornbæk Plantage (Nordsjælland)
            FriTeltningEntity(
                id = "ft_hornbaek",
                name = "Hornbæk Plantage Fri Teltningsskov",
                centerLat = 56.0880,
                centerLng = 12.4750,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.0980, 12.4500),
                    GeoPointDto(56.0960, 12.5000),
                    GeoPointDto(56.0780, 12.4950),
                    GeoPointDto(56.0800, 12.4480),
                    GeoPointDto(56.0980, 12.4500)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 380.0,
                trailCorridorId = "trail_nordsjaelland",
                forestType = "Kystnær løv- og fyrreskov",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 300,
                toiletDistanceMeters = 400,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            ),
            // 19. Teglstrup Hegn (Nordsjælland)
            FriTeltningEntity(
                id = "ft_teglstrup",
                name = "Teglstrup Hegn Fri Teltningszone",
                centerLat = 56.0450,
                centerLng = 12.5550,
                polygonJson = pointAdapter.toJson(listOf(
                    GeoPointDto(56.0600, 12.5300),
                    GeoPointDto(56.0580, 12.5800),
                    GeoPointDto(56.0300, 12.5750),
                    GeoPointDto(56.0320, 12.5250),
                    GeoPointDto(56.0600, 12.5300)
                )),
                rulesSummary = rules,
                rulesUrl = url,
                areaHa = 900.0,
                trailCorridorId = "trail_nordsjaelland",
                forestType = "Gammel bøgeskov ved Klaresø",
                maxTents = 2,
                maxPersonsPerTent = 3,
                openFireAllowed = false,
                waterDistanceMeters = 350,
                toiletDistanceMeters = 500,
                zoneType = "Fri teltningsskov (1-2-3 reglen)",
                hammockFriendly = true
            )
        )
    }

    fun getInitialPois(): List<PoiEntity> {
        return listOf(
            // --- Toilets along Øhavsstien ---
            PoiEntity("poi_wc_svendborg", "toilet", "Offentligt toilet Svendborg Havn", 55.0595, 10.6120, "public", false, "trail_ohavsstien"),
            PoiEntity("poi_wc_lundeborg", "toilet", "Lundeborg Havnetoilet", 55.1375, 10.7885, "public", false, "trail_ohavsstien"),
            PoiEntity("poi_wc_svanninge", "toilet", "Svanninge Bakker Velkomstcenter Toilet", 55.1125, 10.2505, "public", false, "trail_ohavsstien"),
            PoiEntity("poi_wc_faaborg", "toilet", "Faaborg Havn Offentligt Toilet", 55.0968, 10.2442, "public", false, "trail_ohavsstien"),
            PoiEntity("poi_wc_aero", "toilet", "Søby Færgehavn Toilet", 54.9420, 10.2660, "public", false, "trail_ohavsstien"),
            PoiEntity("poi_wc_rudkoebing", "toilet", "Rudkøbing Havneplads Toilet", 54.9380, 10.7080, "public", false, "trail_ohavsstien"),

            // --- Water points along Øhavsstien ---
            PoiEntity("poi_water_svendborg", "water", "Drikkevandspost Christiansminde", 55.0520, 10.6420, "yes", false, "trail_ohavsstien"),
            PoiEntity("poi_water_egebjerg", "water", "Vandpost Egebjerg Mølle", 55.0455, 10.5125, "yes", false, "trail_ohavsstien"),
            PoiEntity("poi_water_svanninge", "water", "Vandpost Restaurant Skovlyst Svanninge", 55.1130, 10.2495, "yes", false, "trail_ohavsstien"),
            PoiEntity("poi_water_faaborg", "water", "Drikkevandstank ved Havnekontor Faaborg", 55.0975, 10.2435, "yes", false, "trail_ohavsstien"),
            PoiEntity("poi_water_voderup", "water", "Vandpost v/ Voderup Klint P-plads", 54.8645, 10.3265, "yes", false, "trail_ohavsstien"),

            // --- Toilets & Water along Hærvejen ---
            PoiEntity("poi_wc_viborg", "toilet", "Offentligt toilet Viborg Domkirkeplads", 56.4525, 9.4015, "public", false, "trail_haervejen"),
            PoiEntity("poi_water_tinnet", "water", "Vandpost Tinnet Krat Naturcenter", 55.9325, 9.3985, "yes", false, "trail_haervejen"),
            PoiEntity("poi_wc_jelling", "toilet", "Toilet Kongernes Jelling Besøgscenter", 55.7555, 9.4205, "public", false, "trail_haervejen"),
            PoiEntity("poi_water_jelling", "water", "Drikkevandspost Jelling Højene", 55.7560, 9.4195, "yes", false, "trail_haervejen"),
            PoiEntity("poi_wc_jels", "toilet", "Jels Voldsted Offentligt Toilet", 55.3975, 9.2475, "public", false, "trail_haervejen"),
            PoiEntity("poi_water_padborg", "water", "Vandpost Frøslev Skovlejr", 54.8280, 9.3630, "yes", false, "trail_haervejen"),

            // --- Toilets & Water along Bornholm ---
            PoiEntity("poi_wc_hammershus", "toilet", "Hammershus Besøgscenter Toilet", 55.2725, 14.7555, "public", false, "trail_bornholm_kyststi"),
            PoiEntity("poi_water_dueodde", "water", "Drikkevand Dueodde Parkeringsplads", 54.9985, 15.0825, "yes", false, "trail_bornholm_kyststi"),
            PoiEntity("poi_water_helligdom", "water", "Vandpost Bornholms Kunstmuseum", 55.2235, 14.8945, "yes", false, "trail_bornholm_kyststi"),
            PoiEntity("poi_wc_svaneke", "toilet", "Svaneke Torv Toilet", 55.1365, 15.1425, "public", false, "trail_bornholm_kyststi"),

            // --- Toilets & Water along Camønoen ---
            PoiEntity("poi_wc_geocenter", "toilet", "Offentligt toilet Geocenter Møns Klint", 54.9755, 12.5525, "public", false, "trail_camoenoen"),
            PoiEntity("poi_water_stege", "water", "Vandpost Stege Havnepromenade", 54.9855, 12.2855, "yes", false, "trail_camoenoen"),
            PoiEntity("poi_water_nyord", "water", "Vandpost Nyord Landsby", 55.0435, 12.1975, "yes", false, "trail_camoenoen"),

            // --- Toilets & Water along Mols Bjerge ---
            PoiEntity("poi_wc_femmoeller", "toilet", "Velkomstcenter Femmøller Toilet", 56.2375, 10.6015, "public", false, "trail_mols_bjerge"),
            PoiEntity("poi_water_trehoeje", "water", "Drikkevandspost Trehøje P-plads", 56.2275, 10.5835, "yes", false, "trail_mols_bjerge"),
            PoiEntity("poi_wc_agri", "toilet", "Agri Bavnehøj Toilet", 56.2305, 10.5275, "public", false, "trail_mols_bjerge"),

            // --- Toilets & Water along Trækstien ---
            PoiEntity("poi_wc_svostrup", "toilet", "Svostrup Kro Åhavn Toilet", 56.2415, 9.6575, "public", false, "trail_traekstien"),
            PoiEntity("poi_water_kongensbro", "water", "Vandpost Kongensbro Teltplads", 56.2945, 9.6645, "yes", false, "trail_traekstien"),

            // --- Toilets & Water along Skjoldungestien ---
            PoiEntity("poi_wc_boserup", "toilet", "Boserup Skov Traktørsted Toilet", 55.6615, 11.9915, "public", false, "trail_skjoldungestien"),
            PoiEntity("poi_water_lejre", "water", "Drikkevand Lejre Museum", 55.6035, 11.9715, "yes", false, "trail_skjoldungestien"),

            // --- Toilets & Water along Drivvejen ---
            PoiEntity("poi_wc_ribe", "toilet", "Offentligt toilet Ribe Domkirkeplads", 55.3285, 8.7615, "public", false, "trail_drivvejen"),
            PoiEntity("poi_water_skjern", "water", "Drikkevandspost Pumpestation Nord Skjern", 55.9220, 8.4310, "yes", false, "trail_drivvejen"),
            PoiEntity("poi_wc_blaabjerg", "toilet", "Blåbjerg Klitplantage P-plads Toilet", 55.7510, 8.2450, "public", false, "trail_drivvejen"),

            // --- Toilets & Water along Nordsjælland ---
            PoiEntity("poi_wc_tisvildeleje", "toilet", "Tisvildeleje Strand Offentligt Toilet", 56.0590, 12.0720, "public", false, "trail_nordsjaelland"),
            PoiEntity("poi_water_tisvilde", "water", "Drikkevandspost Tisvilde Hegn P-plads", 56.0440, 12.0620, "yes", false, "trail_nordsjaelland"),
            PoiEntity("poi_wc_gilleleje", "toilet", "Gilleleje Havn Offentligt Toilet", 56.1280, 12.3080, "public", false, "trail_nordsjaelland"),
            PoiEntity("poi_water_hornbaek", "water", "Drikkevand Hornbæk Havnepromenade", 56.0940, 12.4580, "yes", false, "trail_nordsjaelland"),
            PoiEntity("poi_wc_kronborg", "toilet", "Kronborg Slot / Kulturværftet Toilet", 56.0390, 12.6170, "public", false, "trail_nordsjaelland")
        )
    }
}
