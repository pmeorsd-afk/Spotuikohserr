package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.music.spotui.R

/**
 * Clean, 100% image-free Composable replacing GlideImage across the entire application.
 * Never performs any network requests for images and renders a sleek music placeholder.
 */
@Composable
fun GlideImage(
    model: Any? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    loading: Any? = null,
    failure: Any? = null
) {
    NoImagePlaceholder(modifier = modifier)
}

@Composable
fun NoImagePlaceholder(
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF242424),
                        Color(0xFF141414)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        val iconSize = (maxWidth * 0.45f).coerceIn(16.dp, 72.dp)
        Icon(
            painter = painterResource(id = R.drawable.ic_library_big),
            contentDescription = null,
            tint = Color(0xFF1ED760), // Spotify Green
            modifier = Modifier.size(iconSize)
        )
    }
}
