package com.music.spotui.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.music.spotui.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.spotify.Spotify
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.cacheLibraryEntries
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.HomeImageApprovalRequest
import com.music.spotui.util.HomeImageApprovalRequests
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.TelegramNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Collection identities are explicit; a song/episode never becomes a playlist or album. */
internal fun homeCollectionUri(item: HomeItem): String? {
    val (type, raw) = when (item) {
        is HomeItem.Playlist -> "playlist" to item.id
        is HomeItem.Album -> "album" to item.spotifyId
        is HomeItem.Artist -> "artist" to item.id
        else -> return null
    }
    val id = raw.removePrefix("spotify:$type:")
    return if (id.matches(Regex("[a-zA-Z0-9]{22}"))) "spotify:$type:$id" else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCoverMenuSheet(item: HomeItem, onOpen: () -> Unit, onSongOptions: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember(item) { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val revision by HomeImageApprovalRequests.revision
    val whitelistVersion by KosherWhitelistManager.versionState
    val request = remember(item.imageUrl, item.name) { HomeImageApprovalRequest.create(item.name, item.imageUrl) }
    val allowed = HomeArtworkPermissions.isAllowedOnHome(item)
    val pending = HomeImageApprovalRequests.isPending(app, item.imageUrl)
    val uri = homeCollectionUri(item)
    fun closeThen(action: () -> Unit) {
        if (closing || busy) return
        closing = true
        scope.launch { sheet.hide(); onDismiss(); action() }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet,
            containerColor = Color(0xFF1F1F1F), contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF777777)))
                }
            }) {
            HomeCoverMenuContent(item, allowed, !busy && !closing, request != null, pending,
                onShare = if (uri != null) ({ closeThen {
                    val link = "https://open.spotify.com/" + uri.removePrefix("spotify:").replace(':', '/')
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                    }, "שיתוף")) }.onFailure { Toast.makeText(app, "לא ניתן לפתוח שיתוף", Toast.LENGTH_SHORT).show() }
                } }) else null,
                onSave = if (item is HomeItem.Playlist && uri != null) ({
                    busy = true
                    scope.launch {
                        val success = try { withContext(Dispatchers.IO) {
                            if (!SpotifyTokenProvider.ensureToken(app)) false else {
                                val saved = Spotify.addToLibrary(listOf(uri)).isSuccess
                                if (saved) {
                                    val id = uri.substringAfterLast(':')
                                    val current = getCachedLibraryEntries(app).filterNot { it.spotifyId == id }
                                    // Home grants must never become a Library image grant.
                                    val cover = item.imageUrl.takeIf { KosherWhitelistManager.isHomeItemWhitelisted(item) }.orEmpty()
                                    cacheLibraryEntries(app, listOf(LibraryEntry(id, item.name, item.subtitle, cover, true)) + current)
                                    Api.HomeCache.library = null
                                }
                                saved
                            }
                        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
                        busy = false
                        Toast.makeText(app, if (success) "נוסף לספרייה" else "לא ניתן לשמור כרגע. נסו שוב.", Toast.LENGTH_SHORT).show()
                        if (success) closeThen {}
                    }
                }) else null,
                onOpen = { closeThen(onOpen) },
                onSongOptions = if (item is HomeItem.Track) ({ closeThen(onSongOptions) }) else null,
                onRequest = {
                    if (request != null && !busy && !pending && !allowed) {
                        busy = true
                        TelegramNotifier.sendHomeImageApprovalRequest(app, request,
                            isAllowed = { HomeArtworkPermissions.isAllowedOnHome(item) }) { busy = false }
                    }
                },
                onRefresh = {
                    busy = true
                    KosherWhitelistManager.syncWithRemote(app) { busy = false }
                })
        }
    }
}

@Composable
internal fun HomeCoverMenuContent(
    item: HomeItem, allowed: Boolean, enabled: Boolean, hasImage: Boolean, pending: Boolean,
    onShare: (() -> Unit)?, onSave: (() -> Unit)?, onOpen: () -> Unit,
    onSongOptions: (() -> Unit)?, onRequest: () -> Unit, onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlideImage(model = item.imageUrl, isAllowed = allowed, contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = when(item) {
                    is HomeItem.Playlist -> item.subtitle
                    is HomeItem.Album -> item.subtitle
                    is HomeItem.Artist -> "אמן"
                    is HomeItem.Track -> item.subtitle
                    is HomeItem.LikedSongs -> "שירים שאהבתם"
                }
                Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Color(0xFF383838))
        onShare?.let { HomeCoverAction("שיתוף", Icons.Outlined.Share, enabled, it) }
        onSave?.let { HomeCoverAction("הוספה לספרייה", ImageVector.vectorResource(R.drawable.ic_auto_add), enabled, it) }
        HomeCoverAction("פתיחת הפריט", Icons.AutoMirrored.Filled.ArrowForward, enabled, onOpen)
        onSongOptions?.let { HomeCoverAction("אפשרויות נוספות", Icons.Outlined.MoreVert, enabled, it) }
        HomeCoverAction(when {
            allowed -> "התמונה מותרת"
            pending -> "הבקשה נשלחה לבדיקה"
            !hasImage -> "אין תמונה זמינה לבקשת היתר"
            else -> "התר תמונה זו"
        }, ImageVector.vectorResource(R.drawable.ic_home_image), enabled && hasImage && !allowed && !pending, onRequest)
        if (pending) HomeCoverAction("בדיקת האישור", Icons.Outlined.Refresh, enabled, onRefresh)
    }
}

@Composable
private fun HomeCoverAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = Color(0xFFB3B3B3))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) Color.White else Color(0xFFB3B3B3), fontSize = 16.sp,
            lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}
