package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.example.R
import java.util.concurrent.ConcurrentHashMap

object MapIconUtils {

    private val iconCache = ConcurrentHashMap<Int, Drawable>()

    fun getShelterIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_shelter, 48, 48)
    }

    fun getTentIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_tent, 48, 48)
    }

    fun getToiletIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_toilet, 44, 44)
    }

    fun getWaterIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_water, 44, 44)
    }

    fun getStartIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_start, 48, 48)
    }

    fun getEndIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_end, 48, 48)
    }

    fun getResupplyIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_resupply, 44, 44)
    }

    fun getTransitIcon(context: Context): Drawable {
        return getCachedBitmapDrawable(context, R.drawable.ic_poi_transit, 44, 44)
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

            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#FFF59E0B") // Vibrant Amber
                style = android.graphics.Paint.Style.FILL
            }
            val center = sizePx / 2f
            canvas.drawCircle(center, center, center - 2f * density, bgPaint)

            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
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
