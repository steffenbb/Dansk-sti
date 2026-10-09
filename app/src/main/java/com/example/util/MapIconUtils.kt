package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.R
import java.util.concurrent.ConcurrentHashMap

object MapIconUtils {

    private val iconCache = ConcurrentHashMap<Int, Drawable>()
    private val clusterIconCache = ConcurrentHashMap<String, Drawable>()

    fun getShelterIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "shelter", isSelected = false)
    }

    fun getTentIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "tent", isSelected = false)
    }

    fun getToiletIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "toilet", isSelected = false)
    }

    fun getWaterIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "water", isSelected = false)
    }

    fun getStartIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_start, 48, 48)
    }

    fun getEndIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_end, 48, 48)
    }

    fun getResupplyIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "shop", isSelected = false)
    }

    fun getTransitIcon(context: Context): Drawable {
        return getClusterPoiIcon(context, "transit", isSelected = false)
    }

    fun getHazardIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_hazard, 46, 46)
    }

    fun getCrosshairIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_crosshair, 40, 40)
    }

    fun getNightCampIcon(context: Context): Drawable {
        return iconCache.getOrPut(-999) {
            val density = context.resources.displayMetrics.density
            val sizePx = (52 * density).toInt().coerceAtLeast(40)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#FFF59E0B") // Vibrant Amber
                style = Paint.Style.FILL
            }
            val center = sizePx / 2f
            canvas.drawCircle(center, center, center - 2f * density, bgPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.5f * density
            }
            canvas.drawCircle(center, center, center - 2f * density, borderPaint)

            val shelterDrawable = ContextCompat.getDrawable(context, R.drawable.ic_poi_shelter)
            if (shelterDrawable != null) {
                val iconPadding = (10 * density).toInt()
                shelterDrawable.setBounds(iconPadding, iconPadding, sizePx - iconPadding, sizePx - iconPadding)
                shelterDrawable.draw(canvas)
            }
            BitmapDrawable(context.resources, bitmap)
        }
    }

    /**
     * Requirement B:
     * Icons 32 dp, white circle background, one colour per type; the selected marker grows to 40 dp.
     */
    fun getClusterPoiIcon(
        context: Context,
        type: String,
        isSelected: Boolean
    ): Drawable {
        val normalizedType = when (type.lowercase()) {
            "shelter" -> "shelter"
            "tent", "fri_teltning" -> "tent"
            "water", "drinking_water", "vand" -> "water"
            "toilet", "toilets" -> "toilet"
            "shop", "supermarket", "convenience", "indkøb", "resupply" -> "shop"
            "transit", "bus_stop", "station" -> "transit"
            else -> type.lowercase()
        }

        val cacheKey = "${normalizedType}_selected_${isSelected}"
        return clusterIconCache.getOrPut(cacheKey) {
            val density = context.resources.displayMetrics.density
            val dpSize = if (isSelected) 40 else 32
            val sizePx = (dpSize * density).toInt().coerceAtLeast(32)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val center = sizePx / 2f
            val radius = center - (if (isSelected) 3f else 1.5f) * density

            // 1. Draw white circular background with subtle shadow
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#33000000")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center + 1f * density, radius, shadowPaint)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center, radius, bgPaint)

            // 2. Stroke / border
            val (iconColor, resId) = when (normalizedType) {
                "shelter" -> android.graphics.Color.parseColor("#D97706") to R.drawable.ic_poi_shelter
                "tent" -> android.graphics.Color.parseColor("#16A34A") to R.drawable.ic_poi_tent
                "water" -> android.graphics.Color.parseColor("#0288D1") to R.drawable.ic_poi_water
                "toilet" -> android.graphics.Color.parseColor("#0D9488") to R.drawable.ic_poi_toilet
                "shop" -> android.graphics.Color.parseColor("#EA580C") to R.drawable.ic_poi_resupply
                "transit" -> android.graphics.Color.parseColor("#4F46E5") to R.drawable.ic_poi_transit
                else -> android.graphics.Color.parseColor("#4B5563") to R.drawable.ic_poi_hazard
            }

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                if (isSelected) {
                    color = iconColor
                    strokeWidth = 2.8f * density
                } else {
                    color = android.graphics.Color.parseColor("#E5E7EB") // Light grey ring
                    strokeWidth = 1.2f * density
                }
                style = Paint.Style.STROKE
            }
            canvas.drawCircle(center, center, radius, borderPaint)

            // 3. Draw colored vector glyph inside
            val iconDrawable = ContextCompat.getDrawable(context, resId)
            if (iconDrawable != null) {
                val padding = (if (isSelected) 8.5f else 6.5f) * density
                iconDrawable.setBounds(
                    padding.toInt(),
                    padding.toInt(),
                    (sizePx - padding).toInt(),
                    (sizePx - padding).toInt()
                )
                iconDrawable.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
                iconDrawable.draw(canvas)
            }

            BitmapDrawable(context.resources, bitmap)
        }
    }

    /**
     * Draws a clustered count circle (below zoom 12).
     */
    fun getClusterCountDrawable(context: Context, count: Int): Drawable {
        val key = "cluster_count_${count}"
        return clusterIconCache.getOrPut(key) {
            val density = context.resources.displayMetrics.density
            val sizeDp = when {
                count < 10 -> 36
                count < 100 -> 40
                else -> 46
            }
            val sizePx = (sizeDp * density).toInt()
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val center = sizePx / 2f
            val radius = center - 2f * density

            // Shadow
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#44000000")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center + 1.5f * density, radius, shadowPaint)

            // Dark Forest Green circle
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#2E7D32")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center, radius, circlePaint)

            // Crisp white ring
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * density
            }
            canvas.drawCircle(center, center, radius, borderPaint)

            // Count text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (if (count < 100) 14f else 12f) * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val countStr = count.toString()
            val textBounds = Rect()
            textPaint.getTextBounds(countStr, 0, countStr.length, textBounds)
            val yOffset = center + textBounds.height() / 2f - 1f * density
            canvas.drawText(countStr, center, yOffset, textPaint)

            BitmapDrawable(context.resources, bitmap)
        }
    }

    private fun getCachedBitmapDrawable(
        context: Context,
        @DrawableRes resId: Int,
        dpWidth: Int,
        dpHeight: Int
    ): Drawable {
        return iconCache.getOrPut(resId) {
            createBitmapDrawableFromRes(context, resId, dpWidth, dpHeight)
        }
    }

    private fun createBitmapDrawableFromRes(
        context: Context,
        @DrawableRes resId: Int,
        dpWidth: Int,
        dpHeight: Int
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val widthPx = (dpWidth * density).toInt().coerceAtLeast(32)
        val heightPx = (dpHeight * density).toInt().coerceAtLeast(32)

        val drawable = ContextCompat.getDrawable(context, resId)
            ?: return BitmapDrawable(context.resources, Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888))

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return BitmapDrawable(context.resources, bitmap)
    }
}
