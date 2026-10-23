package com.music.spotui.ui.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.music.spotui.R

/** Fixed, people-free car covers. IDs are an allowlist, never file paths or remote URLs. */
internal object AutoLocalArtwork {
    private val colors = mapOf(
        "tile/music" to 0xFFDC008A.toInt(),
        "tile/podcasts" to 0xFF00775F.toInt(),
        "tile/new" to 0xFF648700.toInt(),
        "tile/pop" to 0xFF4A8597.toInt(),
        "tile/hiphop" to 0xFF477D91.toInt(),
        "tile/rock" to 0xFF006F55.toInt(),
        "tile/israeli" to 0xFF285987.toInt(),
        "tile/jewish" to 0xFF7358A8.toInt(),
    )

    fun render(context: Context, id: String): Bitmap? {
        if (id != "tile/liked" && id !in colors) return null
        return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            if (id == "tile/liked") {
                requireNotNull(ContextCompat.getDrawable(context, R.drawable.auto_liked_cover)).apply {
                    setBounds(0, 0, 512, 512)
                    draw(canvas)
                }
            } else canvas.drawColor(colors.getValue(id))
        }
    }
}
