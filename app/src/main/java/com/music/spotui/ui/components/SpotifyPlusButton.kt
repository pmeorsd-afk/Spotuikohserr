package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.R
import kotlinx.coroutines.launch

val SpotifyGreen = Color(0xFF1ED760)

/** Animate real saved-state changes, never the initial/recycled state of another item. */
@Composable
fun SpotifyPlusButton(
    isLiked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    touchTargetSize: Dp = size,
    unsavedTint: Color = Color.White,
    itemKey: Any? = null,
    enabled: Boolean = true,
) = key(itemKey) {
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(isLiked) }
    LaunchedEffect(isLiked) {
        if (isLiked == previous) return@LaunchedEffect
        previous = isLiked
        rotation.snapTo(if (isLiked) -90f else 0f)
        scale.snapTo(if (isLiked) 0.72f else 0.9f)
        launch { rotation.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
        scale.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }
    Box(modifier.size(touchTargetSize).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        SavedStateIcon(isLiked, Modifier.size(size).graphicsLayer {
            scaleX = scale.value; scaleY = scale.value; rotationZ = rotation.value
        }, unsavedTint, if (isLiked) "נשמר — בחירת תיקיות" else "שמירה בספרייה")
    }
}

@Composable
internal fun SavedStateIcon(saved: Boolean, modifier: Modifier = Modifier, unsavedTint: Color = Color(0xFFB3B3B3), description: String? = null) {
    if (saved) {
        Box(modifier.background(SpotifyGreen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, description, tint = Color.Black, modifier = Modifier.size(16.dp))
        }
    } else Icon(painterResource(R.drawable.ic_add), description, modifier, tint = unsavedTint)
}
