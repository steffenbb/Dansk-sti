package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TrailSegmentType
import com.example.ui.components.ApiKeyPromptDialog
import com.example.ui.theme.DanskStiTheme
import com.example.ui.viewmodel.TrailViewModel
import com.example.util.DanskStiTileSource
import com.example.util.GeoUtils
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

@Composable
fun TrailDetailScreen(
    trailId: String,
    viewModel: TrailViewModel = viewModel(),
    onBack: () -> Unit,
    onPlanTrip: (String) -> Unit,
    onStartNavigation: (Long) -> Unit,
    onOpenFullscreenMap: (String) -> Unit,
    onDownloadOffline: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenRouteBuilder: (destLat: Double?, destLng: Double?, destName: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val trail by viewModel.trail.collectAsState()
    val points by viewModel.trailPoints.collectAsState()
    val trailGeometry by viewModel.trailGeometry.collectAsState()
    val osmRelationTags by viewModel.osmRelationTags.collectAsState()
    val overnightSpots by viewModel.overnightSpots.collectAsState()
    val waterAndToilets by viewModel.waterAndToilets.collectAsState()
    val shops by viewModel.shops.collectAsState()
    val transports by viewModel.transports.collectAsState()
    val elevationGain by viewModel.elevationGain.collectAsState()
    val elevationLoss by viewModel.elevationLoss.collectAsState()
    val surfaceMix by viewModel.surfaceMix.collectAsState()
    val weatherStart by viewModel.weatherStart.collectAsState()
    val weatherEnd by viewModel.weatherEnd.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val routeError by viewModel.routeError.collectAsState()
    val isFetchingLiveRoute by viewModel.isFetchingLiveRoute.collectAsState()
    val isSyncingPois by viewModel.isSyncingPois.collectAsState()
    val longestGapWithoutWaterKm by viewModel.longestGapWithoutWaterKm.collectAsState()
    val longestGapWithoutOvernightKm by viewModel.longestGapWithoutOvernightKm.collectAsState()
    val downloadedTrail by viewModel.downloadedTrail.collectAsState()
    val createdNavigationPlanId by viewModel.createdNavigationPlanId.collectAsState()
    val downloadProgressMap by viewModel.downloadProgress.collectAsState()
    val activeDownloadProgress = downloadProgressMap[trailId]

    LaunchedEffect(createdNavigationPlanId) {
        val pid = createdNavigationPlanId
        if (pid != null) {
            viewModel.clearCreatedNavigationPlanId()
            onStartNavigation(pid)
        }
    }

    var showApiKeyPrompt by remember { mutableStateOf(!DanskStiTileSource.hasValidKey && !DanskStiTileSource.userConfirmedOsmFallback) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val isDark = DanskStiTheme.isDark

    if (showApiKeyPrompt) {
        ApiKeyPromptDialog(
            onDismiss = { showApiKeyPrompt = false },
            onUseOsmFallback = {
                DanskStiTileSource.userConfirmedOsmFallback = true
                showApiKeyPrompt = false
            }
        )
    }

    LaunchedEffect(trailId) {
        viewModel.loadTrail(trailId)
    }

    BackHandler {
        onBack()
    }

    if (trail == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.ui_traildetailscreen_henter_ruteoplysninger_187), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = routeError ?: "Ruten blev ikke fundet i databasen.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.ui_traildetailscreen_g_tilbage_188))
                    }
                }
            }
        }
        return
    }

    val t = trail!!
    val totalKm = t.computedWalkingKm
    val estimatedDays = (totalKm / 15.0).toInt().coerceAtLeast(1)
    val isCircuit = remember(points) {
        if (points.isEmpty()) false else {
            GeoUtils.distanceMeters(points.first().lat, points.first().lng, points.last().lat, points.last().lng) < 800.0
        }
    }
    val routeType = if (isCircuit) "Rundtur" else "Strækning"
    val windowSizeClass = DanskStiTheme.windowSizeClass

    val effectiveOsmTags = remember(osmRelationTags, trailGeometry) {
        if (osmRelationTags.isNotEmpty()) {
            osmRelationTags
        } else {
            trailGeometry?.osmTags ?: emptyMap()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        val useSideBySide = windowSizeClass.useSideBySideTrailDetail || maxWidth >= 600.dp || maxHeight < 480.dp

        val headerSegments: List<TrailSegmentDto> = remember(trailGeometry, points) {
            trailGeometry?.segments ?: if (points.isNotEmpty()) {
                listOf(TrailSegmentDto(type = TrailSegmentType.WALK, points = points))
            } else {
                emptyList()
            }
        }
        var lastHeaderRouteSig by remember(trailId) { mutableIntStateOf(0) }

        val mapSection: @Composable (Modifier) -> Unit = { mapBoxModifier ->
            Box(
                modifier = mapBoxModifier
                    .testTag("trail_detail_top_map_box")
            ) {
                com.example.ui.map.DanskStiMap(
                    modifier = Modifier.fillMaxSize(),
                    mapTestTag = "trail_detail_map",
                    isInteractive = true,
                    appIsDark = isDark,
                    controlsTopPadding = 16.dp,
                    controlsEndPadding = 16.dp,
                    layersButtonTestTag = "trail_detail_layers_button",
                    compassButtonTestTag = "trail_detail_compass_button",
                    myLocationButtonTestTag = "trail_detail_my_location_button",
                    onMapTap = {
                        if (routeError == null || points.isNotEmpty()) {
                            onOpenFullscreenMap(trailId)
                            true
                        } else {
                            false
                        }
                    },
                    onAddToRouteFromDropPin = { lat, lng, label ->
                        onOpenRouteBuilder(lat, lng, label)
                    },
                    onRouteHereFromDropPin = { lat, lng, label ->
                        onOpenRouteBuilder(lat, lng, label)
                    },
                    updateOverlaysKey = headerSegments,
                    onUpdateOverlays = { mapView, overlayManager, isDarkMap ->
                        var sig = if (isDarkMap) 1231 else 1237
                        sig = 31 * sig + headerSegments.size
                        for (seg in headerSegments) {
                            sig = 31 * sig + seg.points.size
                        }
                        if (sig != lastHeaderRouteSig) {
                            lastHeaderRouteSig = sig
                            val density = mapView.context.resources.displayMetrics.density
                            val children = ArrayList<org.osmdroid.views.overlay.Overlay>()
                            val routeColor = if (isDarkMap) {
                                com.example.util.routing.RouteMarkerHelper.COLOR_PLANNED_ROUTE_DARK
                            } else {
                                com.example.util.routing.RouteMarkerHelper.COLOR_PLANNED_ROUTE_LIGHT
                            }
                            val casingColor = if (isDarkMap) {
                                com.example.util.routing.RouteMarkerHelper.COLOR_PLANNED_CASING_DARK
                            } else {
                                com.example.util.routing.RouteMarkerHelper.COLOR_PLANNED_CASING_LIGHT
                            }

                            for (seg in headerSegments) {
                                val geoPts = seg.points.map { GeoPoint(it.lat, it.lng) }
                                if (geoPts.size >= 2) {
                                    if (seg.type == TrailSegmentType.WALK) {
                                        val casing = Polyline(mapView).apply {
                                            setPoints(geoPts)
                                            outlinePaint.color = casingColor
                                            outlinePaint.strokeWidth = 7f * density
                                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                            outlinePaint.isAntiAlias = true
                                        }
                                        children.add(casing)
                                    }
                                    val polyline = Polyline(mapView).apply {
                                        setPoints(geoPts)
                                        outlinePaint.color = routeColor
                                        outlinePaint.strokeWidth = if (seg.type == TrailSegmentType.FERRY) 3.5f * density else 4.5f * density
                                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                        if (seg.type == TrailSegmentType.FERRY) {
                                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f)
                                        }
                                    }
                                    children.add(polyline)
                                }
                            }
                            overlayManager.updateFolderOverlay(
                                com.example.ui.navigation.MapLayersOverlayManager.KEY_ROUTE,
                                children
                            )

                            val allSegmentPoints = headerSegments.flatMap { it.points }.filter { it.lat > 10.0 && it.lng > 0.0 }
                            if (allSegmentPoints.isNotEmpty()) {
                                val minLat = allSegmentPoints.minOf { it.lat }
                                val maxLat = allSegmentPoints.maxOf { it.lat }
                                val minLng = allSegmentPoints.minOf { it.lng }
                                val maxLng = allSegmentPoints.maxOf { it.lng }
                                val bbox = BoundingBox(
                                    (maxLat + 0.02).coerceAtMost(85.0),
                                    (maxLng + 0.02).coerceAtMost(180.0),
                                    (minLat - 0.02).coerceAtLeast(-85.0),
                                    (minLng - 0.02).coerceAtLeast(-180.0)
                                )
                                mapView.post {
                                    try {
                                        mapView.zoomToBoundingBox(bbox, false)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                )

                if (isFetchingLiveRoute || activeDownloadProgress != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                            .testTag("trail_download_progress_indicator")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        activeDownloadProgress == null -> {
                                            stringResource(R.string.ui_traildetailscreen_henter_live_rutedata_fra_ope_189)
                                        }
                                        activeDownloadProgress.isIndeterminate -> {
                                            activeDownloadProgress.message
                                        }
                                        else -> {
                                            stringResource(
                                                R.string.trail_download_progress_format,
                                                activeDownloadProgress.message,
                                                (activeDownloadProgress.fraction * 100).toInt().coerceIn(0, 100)
                                            )
                                        }
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (activeDownloadProgress == null || activeDownloadProgress.isIndeterminate) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .width(200.dp)
                                        .height(4.dp)
                                        .testTag("trail_download_linear_progress"),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { activeDownloadProgress.fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .width(200.dp)
                                        .height(4.dp)
                                        .testTag("trail_download_linear_progress"),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.cancelTrailDownload() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .testTag("btn_cancel_trail_download")
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_cancel),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (routeError != null && points.isEmpty() && !isLoading && !isFetchingLiveRoute) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = stringResource(R.string.ui_fullscreenmapscreen_netv_rksfejl_52),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_kunne_ikke_hente_live_ruteda_190),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_tjek_internetforbindelsen_fo_191),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.retryLoadRoute() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("retry_osm_route_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.state_Close_or_retry), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Top action buttons (Back & Fullscreen hint)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 76.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(48.dp)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("trail_detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.btn_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.clickable { onOpenDiagnostics() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = stringResource(R.string.ui_traildetailscreen_api_status_192),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_netv_rk_193),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shadowElevation = 4.dp,
                        modifier = Modifier.clickable { onOpenFullscreenMap(trailId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = stringResource(R.string.ui_traildetailscreen_fuldsk_rmskort_194),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_fuldsk_rm_195),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                val segmentsCount = trailGeometry?.segments?.size ?: 0
                if (segmentsCount == 0 && isLoading) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ui_fullscreenmapscreen_henter_rutedata_live_fra_ope_51),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else if (segmentsCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.78f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 8.dp, end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(DanskStiTheme.colors.success)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.ui_traildetailscreen_live_osm_196, segmentsCount, if (segmentsCount == 1) "del" else "dele"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                    }
                }
            }
        }

        val detailsSection: @Composable (Modifier) -> Unit = { detailsModifier ->
            TrailDetailDetailsSection(
                trailId = trailId,
                t = t,
                points = points,
                osmRelationTags = effectiveOsmTags,
                totalKm = totalKm,
                elevationGain = elevationGain,
                elevationLoss = elevationLoss,
                estimatedDays = estimatedDays,
                routeType = routeType,
                isSaved = isSaved,
                downloadedTrail = downloadedTrail,
                surfaceMix = surfaceMix,
                isSyncingPois = isSyncingPois,
                longestGapWithoutWaterKm = longestGapWithoutWaterKm,
                longestGapWithoutOvernightKm = longestGapWithoutOvernightKm,
                overnightSpots = overnightSpots,
                waterAndToilets = waterAndToilets,
                shops = shops,
                transports = transports,
                weatherStart = weatherStart,
                weatherEnd = weatherEnd,
                selectedTabIndex = selectedTabIndex,
                onSelectTab = { selectedTabIndex = it },
                useSideBySide = useSideBySide,
                viewModel = viewModel,
                onPlanTrip = onPlanTrip,
                onOpenFullscreenMap = onOpenFullscreenMap,
                onDownloadOffline = onDownloadOffline,
                modifier = detailsModifier
            )
        }

        if (useSideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trail_detail_side_by_side_row")
            ) {
                mapSection(Modifier.weight(0.45f).fillMaxHeight())
                detailsSection(Modifier.weight(0.55f).fillMaxHeight())
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trail_detail_vertical_column")
            ) {
                mapSection(Modifier.fillMaxWidth().weight(0.40f))
                detailsSection(Modifier.fillMaxWidth().weight(0.60f))
            }
        }
    }
}
