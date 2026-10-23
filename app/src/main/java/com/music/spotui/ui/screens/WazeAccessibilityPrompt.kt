package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.music.spotui.R
import com.music.spotui.data.preferences.setWazeOverlayEnabled
import com.music.spotui.ui.components.WelcomeButton
import com.music.spotui.ui.components.WelcomeDialog
import com.music.spotui.ui.components.WelcomeGreen
import com.music.spotui.utils.WazeDetector

/** Same permission flow from onboarding and Settings; never enable on launch alone. */
@Composable
fun WazeSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var awaitingSettings by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingSettings) {
                awaitingSettings = false
                if (WazeDetector.hasScreenDetectorPermission(context)) {
                    setWazeOverlayEnabled(context, true)
                    dismiss()
                } else notice = "ההרשאה עדיין כבויה. אפשר לנסות שוב או לבחור ״אולי אחר כך״."
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    WazeAccessibilityPrompt(onRequestAccessibility = {
        awaitingSettings = true
        runCatching { WazeDetector.requestScreenDetectorPermission(context) }
            .onFailure { awaitingSettings = false; notice = "לא ניתן לפתוח את ההגדרות כרגע. נסו שוב." }
    }, onDismiss = onDismiss, notice = notice)
}

@Composable
fun WazeAccessibilityPrompt(onRequestAccessibility: () -> Unit, onDismiss: () -> Unit, notice: String = "") {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var aspect by remember { mutableFloatStateOf(640f / 380f) }
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.waze_demo)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
        }
    }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, _ -> player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    WelcomeDialog(onDismiss) {
        Text("המוזיקה איתכם בדרך", color = WelcomeGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("להפעיל נגן צף ב־Waze?", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("כפתור קטן שמופיע ב־Waze ומאפשר להשהות, להמשיך ולעבור שיר. אפשר לגרור אותו למקום שנוח לכם.",
            color = Color(0xFFB3B3B3), fontSize = 15.sp)
        AndroidView(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).aspectRatio(aspect), factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.waze_demo_player, null) as PlayerView).apply {
                this.player = player
                useController = false
            }
        }, onRelease = { it.player = null })
        Text("בהגדרות הנגישות הפעילו את ״זיהוי מסך ב־Waze״. ההרשאה משמשת לזיהוי Waze ולהצגת הנגן מעליו.",
            color = Color(0xFFB3B3B3), fontSize = 14.sp)
        if (notice.isNotBlank()) Text(notice, color = WelcomeGreen, fontSize = 14.sp)
        WelcomeButton("הפעלה בהגדרות", onRequestAccessibility)
        TextButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("אולי אחר כך", color = Color.White, fontSize = 15.sp)
        }
    }
}
