package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.example.util.WaypointPhotoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photo decoded off the main thread and sampled down to about [maxSizePx] on its long side
 * (audit fix batch 4). Thumbnails used to decode the full 2048 px JPEG (about 16 MB) during
 * composition. Null while loading, or when the file is missing or unreadable; callers keep their
 * existing fallback (the camera icon) for that.
 */
@Composable
fun rememberPhotoThumbnail(path: String?, maxSizePx: Int): Bitmap? {
    val state: State<Bitmap?> = produceState<Bitmap?>(initialValue = null, path, maxSizePx) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            WaypointPhotoHelper.decodeSampled(path, maxSizePx)
        }
    }
    return state.value
}
