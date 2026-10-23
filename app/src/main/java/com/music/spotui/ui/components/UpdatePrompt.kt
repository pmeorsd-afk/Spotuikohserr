package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.music.spotui.data.update.AndroidUpdateDownloads
import com.music.spotui.data.update.UpdateChecker
import com.music.spotui.data.update.UpdateDownloadState
import com.music.spotui.data.update.UpdateManifest
import com.music.spotui.data.update.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val UpdateGreen = Color(0xFF1ED760)

@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val downloads = remember(context.applicationContext) { AndroidUpdateDownloads(context.applicationContext) }
    val state by downloads.session.state.collectAsState()
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var autoInstall by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { update = UpdateChecker.check(context) }
    LaunchedEffect(downloads, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) { downloads.session.refresh() }
            if (downloads.session.state.value.info != null) hidden = false
            while (true) {
                downloads.session.state.first { it.active }
                delay(750)
                withContext(Dispatchers.IO) { downloads.session.refresh() }
            }
        }
    }

    val launchInstaller = {
        scope.launch {
            busy = true
            try {
                val pending = withContext(Dispatchers.IO) { downloads.session.verifiedForInstall() }
                if (pending != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    context.startActivity(downloads.installIntent(pending))
                    notice = "אשרו את העדכון בחלון ההתקנה של Android."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "לא ניתן לפתוח את ההתקנה. אפשר לנסות שוב." }
            finally { busy = false }
        }
        Unit
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) autoInstall = true
        else notice = "כדי להתקין את העדכון, אפשרו התקנה מהאפליקציה במסך ההרשאות."
    }
    val requestInstall = {
        notice = ""
        if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
        else runCatching {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }.onFailure { notice = "פתחו את הגדרות המכשיר ואפשרו התקנה מהאפליקציה, ואז נסו שוב." }
        Unit
    }
    LaunchedEffect(state.phase, lifecycle, autoInstall, hidden) {
        if (state.phase == UpdatePhase.READY && autoInstall && !hidden && lifecycle == Lifecycle.State.RESUMED) {
            autoInstall = false
            requestInstall()
        }
    }

    val info = state.info ?: update ?: return
    if (hidden) return
    UpdateDialog(info, state, busy, notice,
        onPrimary = {
            when {
                state.phase == UpdatePhase.READY -> requestInstall()
                info.apkUrl.isNotBlank() -> scope.launch {
                    busy = true; notice = ""; autoInstall = true
                    try { withContext(Dispatchers.IO) { downloads.session.start(info) } }
                    finally { busy = false }
                }
                else -> {
                    // A manifest without apkUrl retains the previous release-page behavior.
                    runCatching {
                        check(UpdateManifest.httpsUrl(info.downloadUrl))
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                        if (!info.forceUpdate) hidden = true
                    }.onFailure { notice = "לא ניתן לפתוח את קישור העדכון כרגע." }
                }
            }
        },
        onCancelDownload = {
            scope.launch {
                busy = true; autoInstall = false
                try {
                    withContext(Dispatchers.IO) { downloads.session.cancel() }
                    update = info; notice = ""
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "לא ניתן לבטל את ההורדה כרגע. נסו שוב." }
                finally { busy = false }
            }
        },
        onDismiss = { if (!info.forceUpdate && !busy) { hidden = true; autoInstall = false } },
        onSkip = { UpdateChecker.skipRelease(context, info); hidden = true; autoInstall = false },
    )
}

@Composable
private fun UpdateDialog(
    info: UpdateChecker.UpdateInfo, state: UpdateDownloadState, busy: Boolean, notice: String,
    onPrimary: () -> Unit, onCancelDownload: () -> Unit, onDismiss: () -> Unit, onSkip: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        dismissOnBackPress = !info.forceUpdate && !busy, dismissOnClickOutside = !info.forceUpdate && !busy,
        usePlatformDefaultWidth = false,
    )) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight),
                    shape = RoundedCornerShape(24.dp), color = Color(0xFF181818)) {
                    UpdateDialogContent(info, state, busy, notice, maxWidth < 340.dp,
                        onPrimary, onCancelDownload, onDismiss, onSkip)
                }
            }
        }
    }
}

@Composable
internal fun UpdateDialogContent(
    info: UpdateChecker.UpdateInfo, state: UpdateDownloadState, busy: Boolean = false, notice: String = "", compact: Boolean = false,
    onPrimary: () -> Unit = {}, onCancelDownload: () -> Unit = {}, onDismiss: () -> Unit = {}, onSkip: () -> Unit = {},
) {
    val hasDownload = state.phase != UpdatePhase.IDLE
    Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(42.dp).background(UpdateGreen, CircleShape).padding(12.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(Color.Black, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * .7f), stroke, StrokeCap.Round)
                drawLine(Color.Black, Offset(size.width * .2f, size.height * .4f), Offset(size.width / 2, size.height * .7f), stroke, StrokeCap.Round)
                drawLine(Color.Black, Offset(size.width * .8f, size.height * .4f), Offset(size.width / 2, size.height * .7f), stroke, StrokeCap.Round)
                drawLine(Color.Black, Offset(0f, size.height), Offset(size.width, size.height), stroke, StrokeCap.Round)
            }
            Text("גרסה ${info.versionName}", color = UpdateGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(info.title, color = Color.White, fontSize = if (compact) 20.sp else 23.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
        if (info.message.isNotBlank()) Text(info.message, color = Color(0xFFB3B3B3), fontSize = 14.sp, lineHeight = 21.sp)
        if (hasDownload) {
            Surface(color = Color(0xFF242424), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val text = when (state.phase) {
                        UpdatePhase.DOWNLOADING -> "מורידים את העדכון…"
                        UpdatePhase.READY -> "העדכון מוכן להתקנה"
                        else -> state.message
                    }
                    Text(text, color = if (state.phase == UpdatePhase.ERROR) Color(0xFFFFB4AB) else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (state.active || state.phase == UpdatePhase.READY) {
                        val progress = if (state.phase == UpdatePhase.READY) 1f else state.progress
                        if (progress != null && state.phase != UpdatePhase.VERIFYING) {
                            val animated by animateFloatAsState(progress, label = "updateDownloadProgress")
                            LinearProgressIndicator(progress = animated, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = UpdateGreen, trackColor = Color(0xFF404040))
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = UpdateGreen, trackColor = Color(0xFF404040))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(progress?.let { "${(it * 100).toInt()}%" } ?: "", color = UpdateGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(downloadSizeText(state), color = Color(0xFFB3B3B3), fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, color = Color(0xFFB3B3B3), fontSize = 13.sp)
        if (!state.active) {
            Button(onClick = onPrimary, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = UpdateGreen, contentColor = Color.Black),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when {
                    busy -> "רק רגע…"
                    state.phase == UpdatePhase.READY -> "להתקנת העדכון"
                    state.phase == UpdatePhase.ERROR -> "נסה שוב"
                    info.apkUrl.isNotBlank() -> "עדכן עכשיו"
                    else -> info.updateButtonText
                }, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        } else TextButton(onClick = onCancelDownload, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("ביטול ההורדה", color = Color.White)
        }
        if (!info.forceUpdate) {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.active) "המשך ברקע" else info.cancelButtonText, color = Color.White, textAlign = TextAlign.Center)
            }
            if (!hasDownload) TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("אל תציג שוב לגרסה זו", color = Color(0xFFB3B3B3), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun downloadSizeText(state: UpdateDownloadState): String {
    fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
    return if (state.total > 0) "${mb(state.downloaded)} / ${mb(state.total)} MB" else "${mb(state.downloaded)} MB"
}

@Preview(name = "Update · small", widthDp = 280, heightDp = 400)
@Preview(name = "Update · phone", widthDp = 393, heightDp = 780)
@Preview(name = "Update · large text", widthDp = 320, heightDp = 480, fontScale = 1.6f)
@Composable
private fun UpdateDownloadPreview() {
    val info = UpdateChecker.UpdateInfo(29, "2.9", "עדכון חדש מחכה לכם", "שיפורים חדשים באפליקציה, ממשק מעודכן וחוויית האזנה טובה יותר.", "", apkUrl = "https://example.com/app.apk")
    MaterialTheme {
        UpdateDialog(info, UpdateDownloadState(info, UpdatePhase.DOWNLOADING, 24_000_000, 60_000_000), false, "", {}, {}, {}, {})
    }
}
