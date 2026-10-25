package com.example.ui.components
import com.example.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ForestGreenPrimary
import com.example.util.DanskStiTileSource

/**
 * Interactive Material 3 dialog shown before rendering map tiles when no Dataforsyningen API key is configured.
 * Allows the user to acknowledge or choose standard OpenStreetMap tiles as fallback.
 */
@Composable
fun ApiKeyPromptDialog(
    onDismiss: () -> Unit,
    onUseOsmFallback: () -> Unit = {
        DanskStiTileSource.userConfirmedOsmFallback = true
        onDismiss()
    },
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = stringResource(R.string.ui_apikeypromptdialog_api_n_gle_1),
                tint = ForestGreenPrimary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ui_apikeypromptdialog_dataforsyningen_api_n_gle_p__2),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ui_apikeypromptdialog_dansk_sti_benytter_danmarks__3),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.ui_apikeypromptdialog_for_at_vise_de_officielle_ko_4),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.ui_apikeypromptdialog_du_kan_forts_tte_med_standar_5),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onUseOsmFallback,
                colors = ButtonDefaults.buttonColors(containerColor = ForestGreenPrimary),
                modifier = Modifier.testTag("use_osm_button")
            ) {
                Text(stringResource(R.string.ui_apikeypromptdialog_brug_openstreetmap_6))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_key_prompt_button")
            ) {
                Text(stringResource(R.string.btn_close))
            }
        },
        modifier = modifier.testTag("api_key_prompt_dialog")
    )
}
