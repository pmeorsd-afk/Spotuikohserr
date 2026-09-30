package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.R

import kotlinx.coroutines.launch

val SpotifyGreen = Color(0xFF1ED760)

/**
 * Authentic Spotify Plus (+) to Checked (✓) button with bouncy spring animation.
 * Mimics Spotify's signature micro-interaction:
 * - When saved: shrinks to 0.78x, twists, transitions to green checkmark, springs to 1.18x and settles at 1.0x.
 * - When unsaved: subtle press shrink and springs back to clean white plus icon.
 */
@Composable
fun SpotifyPlusButton(
    isLiked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val isFirstComposition = remember { androidx.compose.runtime.mutableStateOf(true) }

    LaunchedEffect(isLiked) {
        if (isFirstComposition.value) {
            isFirstComposition.value = false
            return@LaunchedEffect
        }

        if (isLiked) {
            // Anticipation: shrink & rotate
            scale.animateTo(
                targetValue = 0.75f,
                animationSpec = tween(durationMillis = 80)
            )
            rotation.snapTo(-35f)
            
            // Spring explosion / pop to 1.18x with bounce
            launch {
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.55f, // Bouncy overshoot
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            // Uncheck: gentle contraction and release
            scale.animateTo(
                targetValue = 0.82f,
                animationSpec = tween(durationMillis = 70)
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Icon(
            painter = if (isLiked) {
                painterResource(id = R.drawable.added)
            } else {
                painterResource(id = R.drawable.ic_add)
            },
            tint = if (isLiked) SpotifyGreen else Color.White,
            contentDescription = if (isLiked) "הוסר מהשירים שאהבתם" else "הוסף לשירים שאהבתם",
            modifier = Modifier.size(size)
        )
    }
}
