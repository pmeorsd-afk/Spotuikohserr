package com.music.spotui.ui.collections

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import androidx.palette.graphics.Palette

/** Collection-only colors: retain the cover's brightness instead of the music player's dark normalization. */
@Composable
internal fun rememberCollectionColor(url: String, allowed: Boolean, radio: Boolean): Color {
    val context = LocalContext.current
    var color by remember(url, allowed, radio) { mutableStateOf(Color(0xFF333333)) }
    DisposableEffect(context, url, allowed, radio) {
        var active = true
        val glide = Glide.with(context)
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(bitmap: Bitmap, transition: Transition<in Bitmap>?) {
                if (!active) return
                val rgb = if (radio) {
                    // Radio artwork has a flat upper band; sample it without the printed title below.
                    var red = 0L; var green = 0L; var blue = 0L; var count = 0
                    for (y in 0 until maxOf(1, bitmap.height / 12)) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(pixel) >= 128) {
                            red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel)
                            blue += android.graphics.Color.blue(pixel); count++
                        }
                    }
                    if (count > 0) android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt()) else 0xff333333.toInt()
                } else {
                    val palette = Palette.from(bitmap).generate()
                    (palette.mutedSwatch ?: palette.dominantSwatch ?: palette.vibrantSwatch)?.rgb ?: 0xff333333.toInt()
                }
                color = Color(rgb)
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        // Palette/getPixel require a CPU-readable bitmap on Android O and newer.
        if (allowed && url.isNotBlank()) glide.asBitmap().load(url).disallowHardwareConfig().override(96, 96).into(target)
        onDispose { active = false; glide.clear(target) }
    }
    return color
}
