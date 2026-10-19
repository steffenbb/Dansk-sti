package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.NordicBlue

data class DataCreditItem(
    val title: String,
    val subtitle: String,
    val license: String,
    val dateInApp: String,
    val description: String,
    val url: String,
    val icon: ImageVector,
    val iconTint: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCreditsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    BackHandler {
        onBack()
    }

    val credits = listOf(
        DataCreditItem(
            title = "OpenStreetMap (ODbL)",
            subtitle = "Stinetværk, ruter og interessepunkter",
            license = "Open Database License (ODbL) © OpenStreetMap contributors",
            dateInApp = "Oktober 2024 (Synkroniseret via OSM Overpass / 0.6 API)",
            description = "Indeholder Danmarks officielle vandreruter (Hærvejen, Øhavsstien, Gendarmstien, Trækstien, Camønoen, m.fl.), stiklassifikationer (sti, grusvej, asfalt), rutespor og POI'er som vandposter, toiletter og togstationer.",
            url = "https://www.openstreetmap.org/copyright",
            icon = Icons.Default.Explore,
            iconTint = ForestGreenPrimary
        ),
        DataCreditItem(
            title = "GeoFA / Naturstyrelsen",
            subtitle = "Shelters, primitive teltpladser & fri teltning",
            license = "Offentlige geodata / Fri anvendelse (Miljø- og Ligestillingsministeriet)",
            dateInApp = "Oktober 2024 (GeoFA WFS datasæt gemt i appens Room-database)",
            description = "Officielle data for over 1.000 danske shelterpladser, bålpladser samt 275+ fri teltningszoner i statsskovene efter 1-2-3 reglen. Bookinghenvisninger åbnes via udbyderens link eller udinaturen.dk.",
            url = "https://udinaturen.dk",
            icon = Icons.Default.Forest,
            iconTint = AmberAccent
        ),
        DataCreditItem(
            title = "Copernicus DEM via Open-Meteo",
            subtitle = "Højdemodel & terrænprofiler",
            license = "Copernicus Sentinel data (ESA) / Open-Meteo Elevation API",
            dateInApp = "2024 (Copernicus GLO-30 30-meters højdegrid)",
            description = "Benyttes til beregning af samlet stigning, fald og højdekurver langs ruterne. Giver præcise højdeprofiler på alle danske vandreetaper uden behov for tunge sensorer.",
            url = "https://spacedata.copernicus.eu/",
            icon = Icons.Default.Landscape,
            iconTint = ForestGreenLight
        ),
        DataCreditItem(
            title = "Open-Meteo Vejr",
            subtitle = "Lokale vejrudsigter & vejrvarsler",
            license = "Creative Commons Attribution 4.0 International (CC BY 4.0)",
            dateInApp = "Hentet live ved planlægning (DMI ICON-D2 & ECMWF modeller)",
            description = "Time-for-time vejrudsigter specifikt for hver vandrerute og dags-etape i Danmark, inklusive temperatur, nedbørsmængder, vindhastighed, vindstød og beregning af solnedgangstidspunkt.",
            url = "https://open-meteo.com/",
            icon = Icons.Default.Cloud,
            iconTint = NordicBlue
        ),
        DataCreditItem(
            title = "Kortfliseudbydere (Dataforsyningen / OSM)",
            subtitle = "Topografiske kortfliser & offline-cache",
            license = "SDFI (Dataforsyningen) / OpenStreetMap",
            dateInApp = "2024 (SDFI WMTS / WMS Skærmkort & OSM fliser)",
            description = "Højtopløselige topografiske danmarkskort fra Styrelsen for Dataforsyning og Infrastruktur (SDFI) samt OpenStreetMap. Gemmes lokalt i appens SQLite-cache for 100% offline navigation i felten.",
            url = "https://dataforsyningen.dk/",
            icon = Icons.Default.Map,
            iconTint = ForestGreenPrimary
        ),
        DataCreditItem(
            title = "GeoDanmark Ortofoto (Luftfoto)",
            subtitle = "Højtopløselige forårsortofotos (SDFI WMS)",
            license = "Dataforsyningen / GeoDanmark (Vilkår for frie geografiske data)",
            dateInApp = "2024 (WMS 1.1.1 EPSG:3857 orto_foraar)",
            description = "Officielle forårsortofotos dækkende hele Danmark fra GeoDanmark og Styrelsen for Dataforsyning og Infrastruktur. Giver et præcist luftfotobillede af skovlysninger, hegn og terræn.",
            url = "https://dataforsyningen.dk/",
            icon = Icons.Default.Landscape,
            iconTint = NordicBlue
        ),
        DataCreditItem(
            title = "Friluftskort (Thunderforest / OpenTopoMap)",
            subtitle = "OSM outdoor-kort med trampestier og højdekurver",
            license = "ODbL / Thunderforest Terms of Service (Tillader app-brug og offline cache)",
            dateInApp = "2024 (OpenStreetMap & SRTM konturlinjer)",
            description = "Specialiseret friluftskort beregnet til vandring og trekking. Viser små skovstier, trampestier og højdekurver, der ikke altid optræder på officielle matrikel- og topokort.",
            url = "https://www.thunderforest.com/maps/outdoors/",
            icon = Icons.Default.Explore,
            iconTint = ForestGreenLight
        ),
        DataCreditItem(
            title = "Afmærkede ruter (Overpass API)",
            subtitle = "Danske vandreruter (hiking/foot relations)",
            license = "Open Database License (ODbL) © OpenStreetMap-bidragydere",
            dateInApp = "Live Overpass forespørgsler med 7-dages lokal Room-cache",
            description = "Forespørger dynamisk på alle afmærkede ruter og stier i synsfeltet ved zoom 10 eller tættere. Rutedata kan gemmes med 1 klik som permanente vandreruter via appens Phase 1 geometri-pipeline.",
            url = "https://wiki.openstreetmap.org/wiki/Tag:route%3Dhiking",
            icon = Icons.Default.Explore,
            iconTint = AmberAccent
        ),
        DataCreditItem(
            title = "Danmarks Miljøportal",
            subtitle = "Naturstyrelsens arealoversigt (WFS)",
            license = "Betingelser for brug af data fra Danmarks Miljøportal (Åbne data)",
            dateInApp = "2024 (Live WFS synkronisering & Room-cache)",
            description = "Officielle grænser for statsejede skove og naturarealer administreret af Naturstyrelsen. Anvendes til adgangsklassifikation (Naturbeskyttelsesloven kapitel 4) og supplerer OpenStreetMap med præcise statsskove.",
            url = "https://arealdata.miljoeportal.dk/",
            icon = Icons.Default.Forest,
            iconTint = ForestGreenPrimary
        )
    )

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("data_credits_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Datakreditter & Kilder",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Åbne og officielle geodata i Dansk Sti",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("credits_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Tilbage"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Dansk Sti er drevet af frie og åbne data fra danske og europæiske myndigheder og fællesskaber. Herunder kan du se de anvendte datakilder, licenser og dateringer:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(credits) { credit ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("credit_card_${credit.title.take(10).replace(" ", "_")}")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = credit.iconTint.copy(alpha = 0.15f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = credit.icon,
                                    contentDescription = null,
                                    tint = credit.iconTint,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = credit.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = credit.subtitle,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Date in app tag
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Datering i appen: ",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = credit.dateInApp,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // License
                        Text(
                            text = "Licens: ${credit.license}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = ForestGreenPrimary
                        )

                        // Description
                        Text(
                            text = credit.description,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // Link button
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(credit.url))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.util.Log.e("DataCredits", "Cannot open URL: ${e.message}")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.End).testTag("credit_link_${credit.title.take(10).replace(" ", "_")}"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = "Læs mere & licens", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Dansk Sti overholder alle krav til kreditering og licensvilkår fastsat af OpenStreetMap Foundation, Miljøministeriet og Copernicus/ESA.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}
