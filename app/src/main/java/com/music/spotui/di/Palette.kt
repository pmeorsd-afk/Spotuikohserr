package com.music.spotui.di

import android.content.Context
import androidx.compose.ui.graphics.Color

class Palette {
    fun extractFirstColorFromImageUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        onColorExtracted(Color(0xFF1E1E1E))
    }

    fun extractSecondColorFromCoverUrl(context: Context, imageUrl: String, onColorExtracted: (Color) -> Unit) {
        onColorExtracted(Color(0xFF121212))
    }
}