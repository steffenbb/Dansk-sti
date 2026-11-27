package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.repository.RestrictedArea
import com.example.util.SafetyAccess

/** Phase D3: small sheet shown when a military area or wildlife reserve is tapped on the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestrictedAreaInfoSheet(area: RestrictedArea, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = SafetyAccess.areaLink(area.kind, area.website)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("restricted_area_sheet")) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = SafetyAccess.areaTitle(area.kind, area.name, context),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = SafetyAccess.areaAdvice(area.kind, context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (link != null) {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, context.getString(R.string.error_no_app_to_open_link), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("restricted_area_link")
                ) {
                    Text(text = if (area.website != null) stringResource(R.string.restricted_area_open_website) else stringResource(R.string.restricted_area_open_forsvaret))
                }
            }
            Text(
                text = stringResource(R.string.path_details_source_osm),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
