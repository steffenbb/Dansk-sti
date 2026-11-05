package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Round 10B Item 16: Helper for capturing waypoint photos using ActivityResultContracts.TakePicture
 * with a FileProvider URI in files/photos and saving a compressed full-size JPEG photo.
 */
object WaypointPhotoHelper {

    fun getPhotosDir(context: Context): File {
        return File(context.filesDir, "photos").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createPhotoFile(context: Context, prefix: String = "wp_photo"): File {
        val dir = getPhotosDir(context)
        return File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    fun getFileProviderUriForPhoto(context: Context, photoFile: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, photoFile)
    }

    fun createPhotoCaptureTarget(context: Context): Pair<File, Uri> {
        val photoFile = createPhotoFile(context)
        val uri = getFileProviderUriForPhoto(context, photoFile)
        return photoFile to uri
    }

    /**
     * Compresses a full-size captured photo in place in files/photos as a JPEG.
     */
    fun compressCapturedPhotoFile(
        photoFile: File,
        maxDimensionPx: Int = 2048,
        jpegQuality: Int = 85
    ): Boolean {
        if (!photoFile.exists() || photoFile.length() == 0L) return false
        return try {
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return photoFile.length() > 0L
            val width = bitmap.width
            val height = bitmap.height
            val maxSide = max(width, height)
            val targetBitmap = if (maxSide > maxDimensionPx && maxDimensionPx > 0) {
                val scale = maxDimensionPx.toFloat() / maxSide.toFloat()
                val newW = (width * scale).roundToInt().coerceAtLeast(1)
                val newH = (height * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            } else {
                bitmap
            }
            FileOutputStream(photoFile, false).use { out ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(50, 95), out)
                out.flush()
            }
            if (targetBitmap !== bitmap) {
                targetBitmap.recycle()
            }
            photoFile.exists() && photoFile.length() > 0L
        } catch (_: Exception) {
            photoFile.exists() && photoFile.length() > 0L
        }
    }
}
