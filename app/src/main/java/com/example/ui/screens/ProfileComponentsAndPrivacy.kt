package com.example.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.CardShape
import com.example.ui.theme.DanskStiTheme
import com.example.ui.theme.MinTouchTargetSize
import com.example.ui.theme.Spacing
import com.example.ui.theme.TabularBodyStyle
import com.example.ui.theme.TabularLabelStyle
import com.example.ui.viewmodel.BRouterDataState
import java.util.Locale

@Composable
internal fun ProfileSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.s3))
            content()
        }
    }
}

@Composable
internal fun ApiKeyStatusRow(
    title: String,
    isConfigured: Boolean,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetSize)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = if (isConfigured) DanskStiTheme.colors.success else DanskStiTheme.colors.warning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.s2))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isConfigured) {
                        stringResource(R.string.api_key_configured)
                    } else {
                        stringResource(R.string.api_key_missing)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isConfigured) DanskStiTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isConfigured) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.api_key_configured),
                tint = DanskStiTheme.colors.success,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    val services = listOf(
        stringResource(R.string.privacy_service_brouter_title) to stringResource(R.string.privacy_service_brouter_desc),
        stringResource(R.string.privacy_service_overpass_title) to stringResource(R.string.privacy_service_overpass_desc),
        stringResource(R.string.privacy_service_openmeteo_title) to stringResource(R.string.privacy_service_openmeteo_desc),
        stringResource(R.string.privacy_service_dataforsyningen_title) to stringResource(R.string.privacy_service_dataforsyningen_desc),
        stringResource(R.string.privacy_service_tiles_title) to stringResource(R.string.privacy_service_tiles_desc)
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize().testTag("privacy_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.privacy_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.privacy_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("privacy_back_button")
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
                .imePadding()
                .testTag("privacy_screen_list"),
            contentPadding = PaddingValues(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            item {
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().testTag("privacy_no_tracking_card")
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.s4),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PrivacyTip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.s2))
                            Text(
                                text = stringResource(R.string.privacy_no_tracking_headline),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.privacy_no_tracking_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.privacy_services_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = Spacing.s2)
                )
                Spacer(modifier = Modifier.height(Spacing.s1))
                Text(
                    text = stringResource(R.string.privacy_services_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(services.size) { index ->
                val (serviceName, serviceDesc) = services[index]
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("privacy_service_card_$index")
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.s4),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s1)
                    ) {
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = serviceDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileStorageSectionCard(
    downloadedTrailsCount: Int,
    totalOfflineMapsMb: Double,
    brouterState: BRouterDataState,
    brouterDownloadedMb: Double,
    brouterTotalMb: Double,
    downloadWifiOnly: Boolean,
    onSetDownloadWifiOnly: (Boolean) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onDownloadBrouter: () -> Unit,
    onClearCache: () -> Unit,
    cacheClearedMessage: String?
) {
    ProfileSectionCard(title = stringResource(R.string.profile_section_storage)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(onClick = onOpenOfflineMaps)
                .testTag("profile_offline_maps_row"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s2))
                Column {
                    Text(
                        text = stringResource(R.string.storage_offline_maps),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.storage_saved_routes_count_format, downloadedTrailsCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = String.format(Locale.forLanguageTag("da-DK"), "%.1f MB", totalOfflineMapsMb),
                style = TabularBodyStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("profile_offline_maps_size")
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s2),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.storage_wifi_only_downloads),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = downloadWifiOnly,
                onCheckedChange = onSetDownloadWifiOnly,
                modifier = Modifier.testTag("profile_download_wifi_only_switch")
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s3),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s2))
                Column {
                    Text(
                        text = stringResource(R.string.storage_routing_data),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val statusSub = if (brouterState.isAllDownloaded) {
                        stringResource(R.string.storage_brouter_all_downloaded)
                    } else {
                        stringResource(
                            R.string.storage_brouter_segments_format,
                            brouterState.downloadedSegments.size,
                            brouterState.segments.size
                        )
                    }
                    Text(
                        text = statusSub,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = String.format(
                    Locale.forLanguageTag("da-DK"),
                    "%.1f / %.0f MB",
                    brouterDownloadedMb,
                    brouterTotalMb
                ),
                style = TabularLabelStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("profile_routing_data_size")
            )
        }

        if (brouterState.isDownloading) {
            Spacer(modifier = Modifier.height(Spacing.s2))
            LinearProgressIndicator(
                progress = { (brouterState.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(modifier = Modifier.height(Spacing.s2))
            OutlinedButton(
                onClick = onDownloadBrouter,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTargetSize)
                    .testTag("profile_download_brouter_button")
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s2))
                Text(
                    text = if (brouterState.isAllDownloaded) {
                        stringResource(R.string.storage_brouter_update)
                    } else {
                        stringResource(R.string.storage_brouter_download)
                    }
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s3),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        OutlinedButton(
            onClick = onClearCache,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .testTag("profile_clear_cache_button")
        ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.s2))
            Text(text = stringResource(R.string.storage_clear_cache))
        }

        cacheClearedMessage?.let { msg ->
            Spacer(modifier = Modifier.height(Spacing.s2))
            Text(
                text = msg,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("profile_cache_cleared_text")
            )
        }
    }
}

@Composable
internal fun ProfileApiKeysSectionCard(
    hasDataforsyningenKey: Boolean,
    hasThunderforestKey: Boolean
) {
    ProfileSectionCard(title = stringResource(R.string.profile_section_api_keys)) {
        ApiKeyStatusRow(
            title = stringResource(R.string.api_key_dataforsyningen),
            isConfigured = hasDataforsyningenKey,
            testTag = "profile_api_key_dataforsyningen"
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s2),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        ApiKeyStatusRow(
            title = stringResource(R.string.api_key_thunderforest),
            isConfigured = hasThunderforestKey,
            testTag = "profile_api_key_thunderforest"
        )
    }
}

@Composable
internal fun ProfileAboutSectionCard(
    showDiagnostics: Boolean,
    onTapVersionRow: () -> Unit,
    onOpenHowToGuide: () -> Unit = {},
    onOpenCredits: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    ProfileSectionCard(title = stringResource(R.string.profile_section_about)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(onClick = onOpenHowToGuide)
                .testTag("profile_how_to_guide_button"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s2))
                Column {
                    Text(
                        text = stringResource(R.string.how_to_guide_row_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.how_to_guide_row_subtitle),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.how_to_guide_row_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s2),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(onClick = onTapVersionRow)
                .testTag("profile_about_app_row"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s2))
                Column {
                    Text(
                        text = stringResource(R.string.ui_profilescreen_dansk_sti_v_100, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_version_tap_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s2),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(onClick = onOpenCredits)
                .testTag("profile_data_credits_button"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.about_data_credits),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.about_data_credits),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Spacing.s2),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTargetSize)
                .clickable(onClick = onOpenPrivacy)
                .testTag("profile_privacy_button"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PrivacyTip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.s2))
                Text(
                    text = stringResource(R.string.about_privacy),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.about_privacy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showDiagnostics) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.s2),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTargetSize)
                    .clickable(onClick = onOpenDiagnostics)
                    .testTag("profile_diagnostics_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = DanskStiTheme.colors.warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.s2))
                    Text(
                        text = stringResource(R.string.about_diagnostics),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.about_diagnostics),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

