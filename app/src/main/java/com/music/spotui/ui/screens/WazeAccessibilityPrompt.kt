package com.music.spotui.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.music.spotui.R

/**
 * Explains the single accessibility permission the Waze integration needs and displays
 * a looping demonstration video inside a rounded, glowing Spotify frame.
 */
@Composable
fun WazeAccessibilityPrompt(
    onRequestAccessibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = RawResourceDataSource.buildRawResourceUri(R.raw.waze_demo)
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF141414),
            border = BorderStroke(1.dp, Color(0xFF282828)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_spotify_waze),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "חיבור ל-Waze",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Demonstration Video Player with rounded corners and glowing Spotify green border
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(640f / 380f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF1ED760),
                                    Color(0xFF1DB954),
                                    Color(0xFF1ED760)
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                controllerAutoShow = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
                                setUseArtwork(false)
                                setDefaultArtwork(null)
                                hideController()
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Shortened concise description
                Text(
                    text = "כדי שכפתור הנגן יופיע בתוך Waze, יש להפעיל הרשאת נגישות אחת בשם \"זיהוי מסך ב-Waze\".",
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))

                // Action buttons
                Button(
                    onClick = onRequestAccessibility,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Text(
                        text = "פתיחת הגדרות נגישות",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(6.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "ביטול",
                        color = Color(0xFFB3B3B3),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
