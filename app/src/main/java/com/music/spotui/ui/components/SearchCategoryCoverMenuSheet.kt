package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.R
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.SearchArtworkPermissions
import com.music.spotui.util.SearchImageApprovalRequest
import com.music.spotui.util.SearchImageApprovalRequests
import com.music.spotui.util.TelegramNotifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchCategoryCoverMenuSheet(title: String, url: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember(url) { mutableStateOf(false) }
    val revision by SearchImageApprovalRequests.revision
    val whitelistVersion by KosherWhitelistManager.versionState
    val request = remember(title, url) { SearchImageApprovalRequest.create(title, url) }
    val allowed = SearchArtworkPermissions.isAllowedInSearchCategory(url)
    val pending = SearchImageApprovalRequests.isPending(app, url)
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
            SearchCategoryCoverMenuContent(title, url, allowed, !busy, request != null, pending,
                onRequest = {
                    if (request != null && !busy && !pending && !allowed) {
                        busy = true
                        TelegramNotifier.sendSearchImageApprovalRequest(app, request) { busy = false }
                    }
                },
                onRefresh = {
                    if (!busy) {
                        busy = true
                        KosherWhitelistManager.syncWithRemote(app) { busy = false }
                    }
                })
        }
    }
}

@Composable
internal fun SearchCategoryCoverMenuContent(
    title: String, url: String, allowed: Boolean, enabled: Boolean, hasImage: Boolean, pending: Boolean,
    onRequest: () -> Unit, onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlideImage(model = url, isAllowed = allowed, contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("קטגוריה בחיפוש", color = Color(0xFFB3B3B3), fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF383838))
        SearchCoverAction(when {
            allowed -> "התמונה מותרת בקטגוריות החיפוש"
            pending -> "הבקשה נשלחה לבדיקה"
            !hasImage -> "אין תמונה זמינה לבקשת היתר"
            else -> "התר תמונה זו"
        }, ImageVector.vectorResource(R.drawable.ic_home_image), enabled && hasImage && !allowed && !pending, onRequest)
        if (pending) SearchCoverAction("בדיקת האישור", Icons.Outlined.Refresh, enabled, onRefresh)
    }
}

@Composable
private fun SearchCoverAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = Color(0xFFB3B3B3))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) Color.White else Color(0xFFB3B3B3), fontSize = 16.sp,
            lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}
