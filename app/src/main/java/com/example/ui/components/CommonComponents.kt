package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FriTeltningEntity
import com.example.data.model.PoiEntity
import com.example.data.model.ResupplyPoint
import com.example.data.model.ResupplyType
import com.example.data.model.ShelterEntity
import com.example.data.model.TransitStation
import com.example.data.model.UserHazardNote
import com.example.data.model.HazardType
import com.example.ui.NearestPoiResult
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ForestDarkBg
import com.example.ui.theme.ForestGreenLight
import com.example.ui.theme.ForestGreenPrimary
import com.example.ui.theme.ForestSurface
import com.example.ui.theme.ForestSurfaceVariant
import com.example.ui.theme.GlassBorderSubtle
import com.example.ui.theme.NordicBlue
import com.example.ui.theme.ShelterBrown

@Composable
fun OffTrailBanner(
    distanceMeters: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("off_trail_warning_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AlertRed)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Advarsel",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Du er afveget fra ruten!",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${distanceMeters.toInt()} m fra den planlagte sti",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun NearestPoiOverlayCard(
    result: NearestPoiResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("nearest_poi_result_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ForestSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (result.type.lowercase()) {
                "toilet" -> Icons.Default.Wc
                "drikkevand" -> Icons.Default.LocalDrink
                else -> Icons.Default.Cabin
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AmberAccent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = result.type,
                    tint = AmberAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nærmeste ${result.type}",
                    color = AmberAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = result.poiName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = "${result.distanceMeters.toInt()} m væk • ${result.details}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_nearest_poi_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Luk",
                    tint = Color.LightGray
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiDetailBottomSheet(
    item: Any,
    onDismiss: () -> Unit,
    onStartAtStation: ((TransitStation) -> Unit)? = null,
    onReportWater: ((Boolean) -> Unit)? = null,
    onDeleteHazard: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ForestSurface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .testTag("poi_detail_sheet")
        ) {
            when (item) {
                is ShelterEntity -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ShelterBrown.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cabin,
                                contentDescription = "Shelter",
                                tint = ShelterBrown,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Kilde: ${item.source} (${item.ownership})",
                                color = Color(0xFFAAAAAA),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = item.description,
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    // Shelter Specifications Grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ForestDarkBg, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip(
                                label = item.shelterType,
                                isHighlight = true,
                                color = ShelterBrown,
                                modifier = Modifier.weight(1f)
                            )
                            StatusChip(
                                label = if (item.bookingRequired) "Booking påkrævet" else "Fri afbenyttelse",
                                isHighlight = item.bookingRequired,
                                color = if (item.bookingRequired) AmberAccent else ForestGreenPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip(
                                label = "Plads: ${item.capacity} personer",
                                isHighlight = false,
                                color = NordicBlue,
                                modifier = Modifier.weight(1f)
                            )
                            StatusChip(
                                label = if (item.fee) "${item.priceDkk} kr./nat" else "Gratis overnatning",
                                isHighlight = item.fee,
                                color = if (item.fee) AmberAccent else ForestGreenLight,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip(
                                label = if (item.hasWaterNearby) "💧 Drikkevand <1 km" else "💧 Ingen vandhane",
                                isHighlight = false,
                                color = if (item.hasWaterNearby) NordicBlue else Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                            StatusChip(
                                label = if (item.hasToiletNearby) "🚻 Toilet <1 km" else "🚻 Intet toilet",
                                isHighlight = false,
                                color = if (item.hasToiletNearby) ForestGreenPrimary else Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip(
                                label = if (item.hasFireRing) "🔥 Bålplads etableret" else "🔥 Ingen bålplads",
                                isHighlight = false,
                                color = if (item.hasFireRing) AmberAccent else Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                            StatusChip(
                                label = if (item.hasFirewood) "🪵 Brænde til rådighed" else "🪵 Medbring eget brænde",
                                isHighlight = false,
                                color = if (item.hasFirewood) ForestGreenLight else Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip(
                                label = if (item.dogAllowed) "🐕 Hund i snor tilladt" else "🐕 Ingen hunde",
                                isHighlight = false,
                                color = Color(0xFFAAAAAA),
                                modifier = Modifier.weight(1f)
                            )
                            StatusChip(
                                label = "🌙 Max ${item.maxNights} nætter",
                                isHighlight = false,
                                color = Color(0xFFAAAAAA),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            text = "Adgang: ${item.accessibility}",
                            color = Color(0xFF888888),
                            fontSize = 11.sp
                        )
                    }

                    // Direct Link & Deep Booking Status for Shelters
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val url = item.bookingUrl ?: ("https://udinaturen.dk/omraader?search=" + Uri.encode(item.name))
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ShelterBrown),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("book_shelter_button")
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (item.bookingRequired) "Book denne shelterplads online" else "Se pladsdetaljer på Udinaturen.dk",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is FriTeltningEntity -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ForestGreenPrimary.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forest,
                                contentDescription = "Fri Teltning",
                                tint = ForestGreenPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Naturstyrelsen • ${item.zoneType}",
                                color = ForestGreenLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Forest & Area Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(
                            label = "🌲 ${item.forestType}",
                            isHighlight = false,
                            color = ForestGreenPrimary,
                            modifier = Modifier.weight(1.2f)
                        )
                        if (item.areaHa > 0) {
                            StatusChip(
                                label = "📐 ${item.areaHa.toInt()} ha skov",
                                isHighlight = false,
                                color = NordicBlue,
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Smart Fri Teltning Rule Checklist & Buffer
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ForestDarkBg),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Tjekliste: 1-2-3 Regler for Fri Teltning",
                                color = ForestGreenLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("✓ 1 nat: Kun én overnatning på samme sted", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                            Text("✓ 2 telte: Højst ${item.maxTents} telte må rejses samlet", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                            Text("✓ 3-personers: Kun små vandretelte (max ${item.maxPersonsPerTent} pers. pr. telt)", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                            Text("✓ 50 m buffer: Hold min. 50 m afstand til beboelse og veje", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                            Text(
                                text = if (item.openFireAllowed) "✓ Bål: Tilladt i etablerede bålringe" else "✓ Stormkøkken: Kun godkendt stormkøkken med lukket brænder (Trangia). Åben ild forbudt.",
                                color = Color(0xFFDDDDDD),
                                fontSize = 12.sp
                            )
                            if (item.hammockFriendly) {
                                Text("✓ Hængekøje: Tilladt med brede remme / tree huggers for at skåne barken", color = Color(0xFFDDDDDD), fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("💧 Vand: ~${item.waterDistanceMeters}m", color = NordicBlue, fontSize = 11.sp)
                                Text("🚻 Toilet: ~${item.toiletDistanceMeters}m", color = ForestGreenLight, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.rulesUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("open_naturstyrelsen_rules_button")
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Læs officielle regler på Naturstyrelsen.dk")
                    }
                }

                is PoiEntity -> {
                    val isToilet = item.type == "toilet"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isToilet) ForestGreenPrimary.copy(alpha = 0.2f) else NordicBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isToilet) Icons.Default.Wc else Icons.Default.LocalDrink,
                                contentDescription = item.type,
                                tint = if (isToilet) ForestGreenPrimary else NordicBlue,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isToilet) "Offentligt toilet" else "Drikkevandspost",
                                color = Color(0xFFAAAAAA),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Adgang: ${item.access.replaceFirstChar { it.uppercase() }} • Gebyr: ${if (item.fee) "Betaling" else "Gratis"}",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp
                    )

                    // Water Point Reliability & Seasonal Winter Cutoff Indicators (Enhancement 5)
                    if (!isToilet) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ForestDarkBg),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Sæson-status: Helårs frostsikret post",
                                        color = NordicBlue,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "I Danmark er kirkegårds- og naturhaner normalt åbne hele året, men primitive sommerhaner kan være lukket nov–apr for at hindre frostsprængning.",
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Er der vand i hanen i dag? Hjælp andre vandrere:", color = Color.White, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onReportWater?.invoke(true) },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Icon(Icons.Default.ThumbUp, contentDescription = null, tint = ForestGreenLight, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Vand OK", color = ForestGreenLight, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onReportWater?.invoke(false) },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Icon(Icons.Default.ThumbDown, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Meld lukket", color = AlertRed, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Resupply & Provisioning Radar (New Feature 4)
                is ResupplyPoint -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AmberAccent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = "Proviant",
                                tint = AmberAccent,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (item.type) {
                                    ResupplyType.GROCERY -> "Supermarked / Købmand"
                                    ResupplyType.FARM_STALL -> "Vejbod (Selvbetjening)"
                                    ResupplyType.BAKERY_CAFE -> "Bageri / Café"
                                },
                                color = AmberAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = item.description,
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ForestDarkBg),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "Åbningstider: ${item.openingHours}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(4.dp))
                            if (item.hasMobilePay) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Payment, contentDescription = null, tint = NordicBlue, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("MobilePay understøttet i boden", color = NordicBlue, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Public Transit Station (Enhancement 13)
                is TransitStation -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF9333EA).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsTransit,
                                contentDescription = "Offentlig transport",
                                tint = Color(0xFFC084FC),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${item.type} • km ${String.format("%.1f", item.kmAlongTrail)}",
                                color = Color(0xFFC084FC),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Forbindelser: ${item.lines}",
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onStartAtStation?.invoke(item)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Start vandretur ved denne station", fontWeight = FontWeight.Bold)
                    }
                }

                // User Hazard Field Note (Enhancement 7)
                is UserHazardNote -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AlertRed.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Fare",
                                tint = AlertRed,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (item.type) {
                                    HazardType.FLOODED_BOARDWALK -> "Oversvømmet sti / spang"
                                    HazardType.FALLEN_TREE -> "Væltet træ over stien"
                                    HazardType.CATTLE_ENCLOSURE -> "Nærgående kvæg i indhegning"
                                    HazardType.TRAIL_DETOUR -> "Midlertidig omlægning"
                                },
                                color = AlertRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = item.description,
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            onDeleteHazard?.invoke(item.id)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("Fjern denne observation", color = AlertRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ForestSurfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("close_poi_sheet_button")
            ) {
                Text("Luk", color = Color.White)
            }
        }
    }
}

@Composable
fun StatusChip(
    label: String,
    isHighlight: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun LayerToggleChip(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("layer_toggle_${label.lowercase()}"),
        color = if (isActive) color.copy(alpha = 0.22f) else ForestSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isActive) color else GlassBorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) color else Color.Gray,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (isActive) Color.White else Color.Gray,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
