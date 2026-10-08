package com.music.spotui.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette as AndroidXPalette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class Palette {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun normalizeForBackground(colorInt: Int): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(colorInt, hsl)
        // Soften saturation to avoid harsh/screaming neon
        hsl[1] = hsl[1].coerceIn(0.20f, 0.48f)
        // Deepen lightness to keep a luxurious, subtle atmospheric background
        hsl[2] = hsl[2].coerceIn(0.14f, 0.25f)
        val tunedRgb = ColorUtils.HSLToColor(hsl)
        return Color(tunedRgb)
    }

    fun extractFirstColorFromImageUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        if (imageUrl.isBlank()) {
            onColorExtracted(Color(0xFF1E1E1E))
            return
        }
        try {
            Glide.with(context)
                .asBitmap()
                .load(imageUrl)
                .override(64, 64)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        AndroidXPalette.from(resource).generate { palette ->
                            val swatch = palette?.darkMutedSwatch
                                ?: palette?.darkVibrantSwatch
                                ?: palette?.mutedSwatch
                                ?: palette?.dominantSwatch
                                ?: palette?.vibrantSwatch
                            val color = swatch?.let { normalizeForBackground(it.rgb) } ?: Color(0xFF1E1E1E)
                            mainHandler.post { onColorExtracted(color) }
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        mainHandler.post { onColorExtracted(Color(0xFF1E1E1E)) }
                    }
                })
        } catch (e: Exception) {
            onColorExtracted(Color(0xFF1E1E1E))
        }
    }

    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        if (imageUrl.isBlank()) {
            onColorExtracted(Color(0xFF141414))
            return
        }
        try {
            Glide.with(context)
                .asBitmap()
                .load(imageUrl)
                .override(64, 64)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        AndroidXPalette.from(resource).generate { palette ->
                            // Prioritize dark muted / dark vibrant for Spotify's subtle, deep player gradient
                            val swatch = palette?.darkMutedSwatch
                                ?: palette?.darkVibrantSwatch
                                ?: palette?.mutedSwatch
                                ?: palette?.dominantSwatch
                                ?: palette?.vibrantSwatch
                            val color = swatch?.let { normalizeForBackground(it.rgb) } ?: Color(0xFF141414)
                            mainHandler.post { onColorExtracted(color) }
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        mainHandler.post { onColorExtracted(Color(0xFF141414)) }
                    }
                })
        } catch (e: Exception) {
            onColorExtracted(Color(0xFF141414))
        }
    }

    fun extractDominantColorForGradient(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        if (imageUrl.isBlank()) {
            onColorExtracted(Color(0xFF141414))
            return
        }
        try {
            Glide.with(context)
                .asBitmap()
                .load(imageUrl)
                .override(96, 96)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        AndroidXPalette.from(resource).generate { palette ->
                            val swatch = palette?.vibrantSwatch
                                ?: palette?.dominantSwatch
                                ?: palette?.darkVibrantSwatch
                                ?: palette?.mutedSwatch
                                ?: palette?.darkMutedSwatch
                            val color = swatch?.let { normalizeForBackground(it.rgb) } ?: Color(0xFF141414)
                            mainHandler.post { onColorExtracted(color) }
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        mainHandler.post { onColorExtracted(Color(0xFF141414)) }
                    }
                })
        } catch (e: Exception) {
            onColorExtracted(Color(0xFF141414))
        }
    }
}