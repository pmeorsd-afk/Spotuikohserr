package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage as RealGlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.music.spotui.R

/**
 * GlideImage Composable that renders full remote album and artist artwork across the application.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun GlideImage(
    model: Any? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loading: Any? = null,
    failure: Any? = null
) {
    if (model == null || (model is String && model.isBlank())) {
        NoImagePlaceholder(modifier = modifier)
    } else {
        RealGlideImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            requestBuilderTransform = { requestBuilder ->
                requestBuilder
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
            }
        )
    }
}

@Composable
fun NoImagePlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
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
        Icon(
            painter = painterResource(id = R.drawable.ic_library_big),
            contentDescription = null,
            tint = Color(0xFF1ED760), // Spotify Green
            modifier = Modifier.size(28.dp)
        )
    }
}
