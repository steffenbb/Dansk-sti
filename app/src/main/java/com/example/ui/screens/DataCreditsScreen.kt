package com.example.ui.screens
import com.example.R

import androidx.compose.ui.res.stringResource

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.example.ui.theme.DanskStiTheme

data class DataCreditItem(
    val title: String,
    val subtitle: String,
    val license: String,
    val dateInApp: String? = null,
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
    val colorScheme = MaterialTheme.colorScheme
    val danskStiColors = DanskStiTheme.colors

    BackHandler {
        onBack()
    }

    val credits = listOf(
        DataCreditItem(
            title = "OpenStreetMap (ODbL)",
            subtitle = stringResource(R.string.credit_osm_subtitle),
            license = "Open Database License (ODbL) © OpenStreetMap contributors",
            description = stringResource(R.string.credit_osm_desc),
            url = "https://www.openstreetmap.org/copyright",
            icon = Icons.Default.Explore,
            iconTint = colorScheme.primary
        ),
        DataCreditItem(
            title = "GeoFA / Naturstyrelsen",
            subtitle = stringResource(R.string.credit_geofa_subtitle),
            license = stringResource(R.string.credit_geofa_license),
            description = stringResource(R.string.credit_geofa_desc),
            url = "https://udinaturen.dk",
            icon = Icons.Default.Forest,
            iconTint = danskStiColors.warning
        ),
        DataCreditItem(
            title = "Copernicus DEM via Open-Meteo",
            subtitle = stringResource(R.string.credit_copernicus_subtitle),
            license = "Copernicus Sentinel data (ESA) / Open-Meteo",
            description = stringResource(R.string.credit_copernicus_desc),
            url = "https://spacedata.copernicus.eu/",
            icon = Icons.Default.Landscape,
            iconTint = danskStiColors.success
        ),
        DataCreditItem(
            title = stringResource(R.string.credit_openmeteo_title),
            subtitle = stringResource(R.string.credit_openmeteo_subtitle),
            license = "Creative Commons Attribution 4.0 International (CC BY 4.0)",
            description = stringResource(R.string.credit_openmeteo_desc),
            url = "https://open-meteo.com/",
            icon = Icons.Default.Cloud,
            iconTint = danskStiColors.plannedRoute
        ),
        DataCreditItem(
            title = "Klimadatastyrelsen / Dataforsyningen",
            subtitle = stringResource(R.string.credit_kds_subtitle),
            license = stringResource(R.string.credit_kds_license),
            description = stringResource(R.string.credit_kds_desc),
            url = "https://dataforsyningen.dk/",
            icon = Icons.Default.Map,
            iconTint = colorScheme.primary
        ),
        DataCreditItem(
            title = stringResource(R.string.credit_friluftskort_title),
            subtitle = stringResource(R.string.credit_friluftskort_subtitle),
            license = "Open Database License (ODbL) © OpenStreetMap contributors • CC-BY-SA OpenTopoMap • Thunderforest Terms of Service",
            description = stringResource(R.string.credit_friluftskort_desc),
            url = "https://www.thunderforest.com/maps/outdoors/",
            icon = Icons.Default.Explore,
            iconTint = danskStiColors.success
        ),
        DataCreditItem(
            title = stringResource(R.string.credit_marked_routes_title),
            subtitle = stringResource(R.string.credit_marked_routes_subtitle),
            license = stringResource(R.string.credit_marked_routes_license),
            description = stringResource(R.string.credit_marked_routes_desc),
            url = "https://wiki.openstreetmap.org/wiki/Tag:route%3Dhiking",
            icon = Icons.Default.Explore,
            iconTint = danskStiColors.warning
        ),
        DataCreditItem(
            title = "Danmarks Miljøportal",
            subtitle = stringResource(R.string.credit_miljoportal_subtitle),
            license = stringResource(R.string.credit_miljoportal_license),
            description = stringResource(R.string.credit_miljoportal_desc),
            url = "https://arealdata.miljoeportal.dk/",
            icon = Icons.Default.Forest,
            iconTint = colorScheme.primary
        ),
        DataCreditItem(
            title = "BRouter",
            subtitle = stringResource(R.string.credit_brouter_subtitle),
            license = "MIT License © Arndt Brenschede / BRouter contributors (data © OpenStreetMap ODbL)",
            description = stringResource(R.string.credit_brouter_desc),
            url = "https://brouter.de/",
            icon = Icons.Default.Explore,
            iconTint = colorScheme.primary
        ),
        DataCreditItem(
            title = "Photon (Komoot)",
            subtitle = stringResource(R.string.credit_photon_subtitle),
            license = stringResource(R.string.credit_photon_license),
            description = stringResource(R.string.credit_photon_desc),
            url = "https://photon.komoot.io/",
            icon = Icons.Default.Map,
            iconTint = danskStiColors.plannedRoute
        ),
        DataCreditItem(
            title = stringResource(R.string.credit_wikimedia_title),
            subtitle = stringResource(R.string.credit_wikimedia_subtitle),
            license = stringResource(R.string.credit_wikimedia_license),
            description = stringResource(R.string.credit_wikimedia_desc),
            url = "https://commons.wikimedia.org/",
            icon = Icons.Default.Landscape,
            iconTint = colorScheme.primary
        )
    )
    val noBrowserText = stringResource(R.string.error_no_browser_found)
    val openLinkErrorText = stringResource(R.string.error_could_not_open_link)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize().testTag("data_credits_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.about_data_credits),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.ui_datacreditsscreen_bne_og_officielle_geodata_i__45),
                            style = MaterialTheme.typography.labelMedium,
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
                            contentDescription = stringResource(R.string.btn_back)
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
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.ui_datacreditsscreen_dansk_sti_er_drevet_af_frie__46),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = credit.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!credit.dateInApp.isNullOrBlank()) {
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
                                        text = stringResource(R.string.ui_datacreditsscreen_datering_i_appen_47),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = credit.dateInApp,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // License
                        Text(
                            text = stringResource(R.string.ui_datacreditsscreen_licens_48, credit.license),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Description
                        Text(
                            text = credit.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // Link button
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(credit.url))
                                    context.startActivity(intent)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    android.widget.Toast.makeText(
                                        context,
                                        noBrowserText,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        openLinkErrorText,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.End).testTag("credit_link_${credit.title.take(10).replace(" ", "_")}"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ui_datacreditsscreen_l_s_mere_licens_49),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ui_datacreditsscreen_dansk_sti_overholder_alle_kr_50),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}
