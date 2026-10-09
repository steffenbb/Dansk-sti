with open('app/src/main/java/com/example/ui/screens/NavigationScreen.kt', 'r') as f:
    text = f.read()

# 1. Add LazyColumn imports if not present
if 'import androidx.compose.foundation.lazy.LazyColumn' not in text:
    text = text.replace(
        'import androidx.compose.foundation.layout.width',
        'import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items'
    )

# 2. Replace update block in AndroidView
old_update_start = text.find('            update = { mapView ->')
old_update_end = text.find('                mapView.invalidate()\n            }\n        )')
assert old_update_start != -1, 'update start not found'
assert old_update_end != -1, 'update end not found'
old_update_end += len('                mapView.invalidate()\n            }')

new_update = '''            update = { mapView ->
                val manager = overlayManagerRef ?: return@AndroidView
                val activeRoute = navState.plannedRoute
                if (activeRoute != null) {
                    manager.setPlannedRoute(activeRoute)
                }
                manager.updateTonightCamp(currentStage, todayStagePoints)
                manager.updatePois(
                    shelters = shelters,
                    showShelters = showShelters,
                    toilets = pois.filter { it.type == "toilet" },
                    showToilets = showToilets,
                    waters = pois.filter { it.type == "water" },
                    showWater = showWater,
                    hazards = hazards,
                    showHazards = showHazards,
                    parsedFriTeltning = parsedFriTeltningPolygons,
                    showFriTeltning = showFriTeltning
                )
                manager.onLocationTick(navState, followUserLocation)
                if (isHeadingUp) {
                    mapView.mapOrientation = -navState.bearing
                }
            }'''

text = text[:old_update_start] + new_update + text[old_update_end:]

# 3. Replace OffTrailBanner call
old_offtrail = 'OffTrailBanner(distanceMeters = navState.offTrailDistanceMeters)'
new_offtrail = '''OffTrailBanner(
                    distanceMeters = navState.offTrailDistanceMeters,
                    bearingToPointAhead = navState.bearingToPointAhead,
                    userBearing = navState.bearing
                )'''
assert old_offtrail in text, 'offtrail not found'
text = text.replace(old_offtrail, new_offtrail)

# 4. Replace compass button
old_compass = '''            FloatingMapButton(
                icon = Icons.Default.Explore,
                contentDescription = "Nord-orienter",
                tint = AmberAccent,
                onClick = {
                    mapViewRef?.mapOrientation = 0f
                }
            )'''
new_compass = '''            FloatingMapButton(
                icon = Icons.Default.Explore,
                contentDescription = if (isHeadingUp) "Skift til Nord-op" else "Skift til Kurs-op",
                tint = if (isHeadingUp) AmberAccent else Color.White,
                onClick = {
                    isHeadingUp = !isHeadingUp
                    if (!isHeadingUp) {
                        mapViewRef?.mapOrientation = 0f
                    } else {
                        val b = navState.bearing
                        if (b != 0f) {
                            mapViewRef?.mapOrientation = -b
                        }
                    }
                }
            )'''
assert old_compass in text, 'old compass not found'
text = text.replace(old_compass, new_compass)

# 5. Replace HUD card header with Line 1 & Line 2
old_hud_header = '''                    // Drag/Expand Handle Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHudExpanded = !isHudExpanded }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Næste mål: ",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = navState.currentStage?.stopName ?: "På ruten",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isHudExpanded) "Mindre" else "Detaljer & SOS",
                                color = ForestGreenLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isHudExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = ForestGreenLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }'''

new_hud_header = '''                    // Bottom Navigation Card (Line 1 & Line 2) - Tapping opens "Foran dig på ruten"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAheadSheet = true }
                            .padding(vertical = 4.dp)
                            .testTag("bottom_nav_card")
                    ) {
                        // Line 1: next overnight spot name, km to it along the route, ETA from user's pace
                        val pace = if (navState.speedKmh > 1.0) navState.speedKmh else 4.2
                        val etaMinutes = (navState.distanceRemainingTodayKm / pace * 60.0).toInt()
                        val etaTimestamp = System.currentTimeMillis() + (etaMinutes * 60000L)
                        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                        val etaString = "kl. ${timeFormat.format(Date(etaTimestamp))}"
                        val nextOvernightName = currentStage?.stopName ?: "Overnatning"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (tonightCampType == "FRI_TELTNING") "⛺" else "🛖", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = nextOvernightName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${String.format(Locale.getDefault(), "%.1f", navState.distanceRemainingTodayKm)} km",
                                    color = ForestGreenLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "($etaString)",
                                    color = AmberAccent,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Line 2: "Næste vand / toilet / butik" with km along the route and detour
                        val nextWater = itemsAhead.firstOrNull { it.type == "water" }
                        val nextToilet = itemsAhead.firstOrNull { it.type == "toilet" }
                        val nextShop = itemsAhead.firstOrNull { it.type in listOf("shop", "convenience", "supermarket", "grocery") }

                        val summaryParts = mutableListOf<String>()
                        if (nextWater != null) {
                            summaryParts.add("💧 Vand ${String.format(Locale.getDefault(), "%.1f", nextWater.kmAhead)} km (+${nextWater.detourMeters.toInt()}m)")
                        }
                        if (nextToilet != null) {
                            summaryParts.add("🚻 Toilet ${String.format(Locale.getDefault(), "%.1f", nextToilet.kmAhead)} km (+${nextToilet.detourMeters.toInt()}m)")
                        }
                        if (nextShop != null) {
                            summaryParts.add("🛒 Butik ${String.format(Locale.getDefault(), "%.1f", nextShop.kmAhead)} km (+${nextShop.detourMeters.toInt()}m)")
                        }

                        val line2Text = if (summaryParts.isNotEmpty()) {
                            summaryParts.joinToString(" • ")
                        } else {
                            "Næste faciliteter foran: Ingen inden for 2,5 km"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = line2Text,
                                color = Color(0xFFCFD8DC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Foran dig på ruten",
                                tint = ForestGreenLight,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Drawer Toggle for details & SOS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHudExpanded = !isHudExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isHudExpanded) "Skjul detaljer & SOS" else "Vis detaljer & SOS",
                            color = ForestGreenLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isHudExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = ForestGreenLight,
                            modifier = Modifier.size(16.dp)
                        )
                    }'''

assert old_hud_header in text, 'old hud header not found'
text = text.replace(old_hud_header, new_hud_header)

# 6. Add AheadOnRouteBottomSheet invocation and definition
old_sheet_insert = '''        // POI Detail Bottom Sheet when tapping a Marker
        if (selectedPoi != null) {'''
new_sheet_insert = '''        // "Foran dig på ruten" Modal Bottom Sheet
        if (showAheadSheet) {
            AheadOnRouteBottomSheet(
                itemsAhead = itemsAhead,
                currentWalkKm = navState.totalDistanceWalkedKm,
                onDismiss = { showAheadSheet = false },
                onSelectPoi = { item ->
                    showAheadSheet = false
                    followUserLocation = false
                    mapViewRef?.controller?.animateTo(GeoPoint(item.lat, item.lng))
                    mapViewRef?.controller?.setZoom(16.0)
                    item.rawEntity?.let { viewModel.selectPoiForDetails(it) }
                }
            )
        }

        // POI Detail Bottom Sheet when tapping a Marker
        if (selectedPoi != null) {'''

assert old_sheet_insert in text, 'old sheet insert not found'
text = text.replace(old_sheet_insert, new_sheet_insert)

# 7. Add AheadOnRouteBottomSheet Composable at bottom of file
ahead_bottom_sheet_def = '''
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AheadOnRouteBottomSheet(
    itemsAhead: List<RoutePoiItem>,
    currentWalkKm: Double,
    onDismiss: () -> Unit,
    onSelectPoi: (RoutePoiItem) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ForestDarkBg,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(GlassBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Foran dig på ruten",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Sorteret efter afstand langs ruten • ${itemsAhead.size} faciliteter",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Luk", tint = Color.LightGray)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (itemsAhead.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ingen registrerede faciliteter foran dig på denne rute.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(itemsAhead) { item ->
                        Surface(
                            color = ForestSurfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GlassBorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPoi(item) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val (icon, bgCol) = when (item.type.lowercase()) {
                                    "water" -> Icons.Default.LocalDrink to NordicBlue
                                    "toilet" -> Icons.Default.Wc to Color(0xFF42A5F5)
                                    "shop", "convenience", "supermarket", "grocery" -> Icons.Default.ShoppingBag to AmberAccent
                                    "shelter" -> Icons.Default.Cabin to ShelterBrown
                                    "tent", "fri_teltning" -> Icons.Default.Forest to ForestGreenPrimary
                                    else -> Icons.Default.Explore to Color.Gray
                                }
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(bgCol.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = bgCol,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val detourText = if (item.detourMeters > 30.0) {
                                        "Afstikker: ${item.detourMeters.toInt()} m"
                                    } else {
                                        "Lige ved stien"
                                    }
                                    val typeText = when (item.type.lowercase()) {
                                        "water" -> "Drikkevand"
                                        "toilet" -> "Toilet"
                                        "shop" -> "Butik / Indkøb"
                                        "shelter" -> "Shelterplads"
                                        "tent" -> "Fri teltning"
                                        else -> item.type.replaceFirstChar { it.uppercase() }
                                    }
                                    Text(
                                        text = "$typeText • $detourText",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "om ${String.format(Locale.getDefault(), "%.1f", item.kmAhead)} km",
                                        color = ForestGreenLight,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "km ${String.format(Locale.getDefault(), "%.1f", item.walkKm)}",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}
'''

text = text + ahead_bottom_sheet_def

with open('app/src/main/java/com/example/ui/screens/NavigationScreen.kt', 'w') as f:
    f.write(text)

print('Updated NavigationScreen.kt successfully!')
