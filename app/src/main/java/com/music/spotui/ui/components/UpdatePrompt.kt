package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.update.UpdateChecker

private const val SPOTIFY_GREEN = 0xFF1ED760

/**
 * Launch-time update prompt: checks version.json from GitHub once per app start.
 * Displays customizable title, message, and download link.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        update = UpdateChecker.check(context)
    }

    val info = update ?: return

    AlertDialog(
        onDismissRequest = {
            if (!info.forceUpdate) {
                update = null
            }
        },
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = info.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = info.message,
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (info.versionName.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "גרסה: ${info.versionName}",
                        color = Color(SPOTIFY_GREEN),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    if (!info.forceUpdate) {
                        update = null
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(SPOTIFY_GREEN),
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(
                    text = info.updateButtonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        },
        dismissButton = if (!info.forceUpdate) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            UpdateChecker.skipRelease(context, info)
                            update = null
                        }
                    ) {
                        Text("אל תציג שוב", color = Color.Gray, fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = { update = null }
                    ) {
                        Text(info.cancelButtonText, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        } else null,
    )
}
