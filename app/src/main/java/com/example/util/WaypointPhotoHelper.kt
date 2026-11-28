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
 * Helper for capturing waypoint photos using ActivityResultContracts.TakePicture
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
     * Compresses a captured or picked photo in place in files/photos as a JPEG, at most
     * [maxDimensionPx] on the long side. Decodes with sampling (a 50 MP photo is never loaded at full
     * size) and bakes the EXIF rotation into the pixels, since re-encoding drops the EXIF tag and
     * portrait photos would otherwise show sideways. Blocking: call from a background thread
     * (see [compressInBackground]).
     */
    fun compressCapturedPhotoFile(
        photoFile: File,
        maxDimensionPx: Int = 2048,
        jpegQuality: Int = 85
    ): Boolean {
        if (!photoFile.exists() || photoFile.length() == 0L) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return photoFile.length() > 0L
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimensionPx)
            }
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, options) ?: return photoFile.length() > 0L
            val rotation = exifRotationDegrees(photoFile)
            val width = bitmap.width
            val height = bitmap.height
            val maxSide = max(width, height)
            val scale = if (maxSide > maxDimensionPx && maxDimensionPx > 0) maxDimensionPx.toFloat() / maxSide.toFloat() else 1f
            val targetBitmap = if (scale != 1f || rotation != 0) {
                val matrix = android.graphics.Matrix().apply {
                    if (scale != 1f) postScale(scale, scale)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            } else {
                bitmap
            }
            FileOutputStream(photoFile, false).use { out ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(50, 95), out)
                out.flush()
            }
            if (targetBitmap !== bitmap) targetBitmap.recycle()
            bitmap.recycle()
            photoFile.exists() && photoFile.length() > 0L
        } catch (_: OutOfMemoryError) {
            photoFile.exists() && photoFile.length() > 0L
        } catch (_: Exception) {
            photoFile.exists() && photoFile.length() > 0L
        }
    }

    /** [compressCapturedPhotoFile] on the IO dispatcher. */
    suspend fun compressInBackground(photoFile: File): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { compressCapturedPhotoFile(photoFile) }

    /** Copies a picked image into files/photos and compresses it, on the IO dispatcher. Throws on read errors. */
    suspend fun importPickedPhoto(context: Context, uri: Uri, prefix: String = "wp_photo"): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val photoFile = createPhotoFile(context, prefix)
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
            input.use { inp -> FileOutputStream(photoFile).use { out -> inp.copyTo(out) } }
            compressCapturedPhotoFile(photoFile)
            photoFile
        }

    /** Largest power-of-two sample size that keeps the long side at or above [maxDimensionPx]. */
    internal fun sampleSizeFor(width: Int, height: Int, maxDimensionPx: Int): Int {
        if (maxDimensionPx <= 0) return 1
        var sample = 1
        val longSide = max(width, height)
        while (longSide / (sample * 2) >= maxDimensionPx) sample *= 2
        return sample
    }

    /**
     * Clockwise rotation stored in the photo's EXIF orientation tag (0, 90, 180 or 270).
     * The framework ExifInterface reads this tag from a file reliably on API 24+ (only writing and
     * stream input were buggy there), so the AndroidX copy isn't needed for this one read.
     */
    @android.annotation.SuppressLint("ExifInterface")
    internal fun exifRotationDegrees(photoFile: File): Int = runCatching {
        when (android.media.ExifInterface(photoFile.absolutePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90, android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270, android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
