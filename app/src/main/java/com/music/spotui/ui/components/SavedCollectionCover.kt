package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.music.spotui.R

@Composable
internal fun SavedCollectionCover(episodes: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8)).background(
        if (episodes) Brush.linearGradient(listOf(Color(0xFF006450), Color(0xFF006450)))
        else Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)))), contentAlignment = Alignment.Center) {
        if (episodes) Icon(painterResource(R.drawable.ic_saved_episode), null,
            tint = SpotifyGreen, modifier = Modifier.fillMaxSize(0.48f))
        else Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.48f))
    }
}
