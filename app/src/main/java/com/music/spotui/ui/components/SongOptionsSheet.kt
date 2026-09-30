package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSong
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

private val SpotifySheetBg = Color(0xFF242424)
private val TextWhite = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA7A7A7)

/**
 * Official Spotify Track Context Menu bottom sheet (1:1 with Spotify):
 * Header: Track cover (56dp rounded 4dp), title (16sp bold), artist (13sp secondary).
 * Items (RTL):
 * 1. שיתוף (Share)
 * 2. הוספה לפלייליסט (Add to playlist)
 * 3. הסרה מהפלייליסט הזה (Remove from this playlist / Liked Songs)
 * 4. מעבר לתור ההשמעה (Add to queue)
 * 5. מעבר לאלבום (Go to album)
 * 6. מעבר לאמנים (Go to artist)
 * 7. לפתיחת Jam (עם תגית Premium)
 * 8. לא לכלול את הטראק בפרופיל הטעמים שלכם
 * 9. לצפייה בקרדיטים ליוצרי השיר
 * 10. הציגו את קוד Spotify
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SongOptionsSheet(
    song: SongsModel,
    navController: NavController,
    context: Context,
    onDismiss: () -> Unit,
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var liked by remember { mutableStateOf(isSongLiked(context, song)) }
    var downloaded by remember { mutableStateOf(com.music.spotui.data.preferences.isDownloaded(context, song.id.toString())) }
    var downloadingNow by remember { mutableStateOf(com.music.spotui.di.SongPlayer.isDownloading(song.url)) }
    var downloadPct by remember { mutableStateOf(com.music.spotui.di.SongPlayer.downloadProgress(song.url)) }

    androidx.compose.runtime.LaunchedEffect(downloadingNow) {
        while (downloadingNow) {
            downloadPct = com.music.spotui.di.SongPlayer.downloadProgress(song.url)
            kotlinx.coroutines.delay(300)
        }
    }

    var showSavedIn by remember { mutableStateOf(false) }
    if (showSavedIn) {
        SavedInSheet(
            song = song,
            context = context,
            onDismiss = {
                showSavedIn = false
                onDismiss()
            },
            onLikedChanged = { liked = it },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifySheetBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF5A5A5A))
                )
            }
        }
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ── Track Header: Cover + Title + Singer ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    GlideImage(
                        model = song.coverUri,
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(song)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = song.singer,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    color = Color(0xFF2E2E2E),
                    thickness = 1.dp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )

                // ── 10 Official Spotify track options matching Spotify 1:1 ──
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // 1. שיתוף
                    item {
                        ContextMenuItem(
                            title = "שיתוף",
                            icon = Icons.Default.Share,
                            onClick = {
                                val shareText = song.spotifyTrackId.takeIf { it.isNotBlank() }
                                    ?.let { "https://open.spotify.com/track/$it" }
                                    ?: "Listening to ${song.title} by ${song.singer}"
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(send, "שיתוף"))
                                onDismiss()
                            }
                        )
                    }

                    // 2. הוספה לפלייליסט
                    item {
                        ContextMenuItem(
                            title = "הוספה לפלייליסט",
                            customIcon = { AddToPlaylistIcon() },
                            onClick = {
                                showSavedIn = true
                            }
                        )
                    }

                    // 3. הסרה מהפלייליסט הזה (או מהשירים שאהבתם)
                    item {
                        val removeTitle = if (liked) "הסרה מהפלייליסט הזה" else "הוספה לפלייליסט הזה"
                        ContextMenuItem(
                            title = removeTitle,
                            customIcon = { if (liked) RemoveFromPlaylistIcon() else AddToPlaylistIcon() },
                            onClick = {
                                if (liked) {
                                    removeLikedSong(context, song)
                                    liked = false
                                    if (song.spotifyTrackId.isNotBlank()) {
                                        com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, false)
                                    }
                                    Toast.makeText(context, "הוסר מהשירים שאהבתם", Toast.LENGTH_SHORT).show()
                                } else {
                                    addLikedSong(context, song)
                                    liked = true
                                    if (song.spotifyTrackId.isNotBlank()) {
                                        com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, true)
                                    }
                                    Toast.makeText(context, "נוסף לשירים שאהבתם", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            }
                        )
                    }

                    // 4. מעבר לתור ההשמעה
                    item {
                        ContextMenuItem(
                            title = "מעבר לתור ההשמעה",
                            customIcon = { QueueIcon() },
                            onClick = {
                                playerViewModel.addToQueue(song)
                                Toast.makeText(context, "נוסף לתור ההשמעה", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }

                    // 5. מעבר לאלבום
                    item {
                        ContextMenuItem(
                            title = "מעבר לאלבום",
                            customIcon = { AlbumDiscIcon() },
                            onClick = {
                                if (song.album.isNotBlank()) {
                                    navController.navigate(albumRoute(song.album, song.singer))
                                }
                                onDismiss()
                            }
                        )
                    }

                    // 6. מעבר לאמנים
                    item {
                        ContextMenuItem(
                            title = "מעבר לאמנים",
                            customIcon = { ArtistIcon() },
                            onClick = {
                                playerViewModel.goToArtist(song.spotifyTrackId, song.singer) { route ->
                                    navController.navigate(route)
                                }
                                onDismiss()
                            }
                        )
                    }

                    // 7. לפתיחת Jam (עם תגית Premium)
                    item {
                        ContextMenuItem(
                            title = "לפתיחת Jam",
                            customIcon = { JamIcon() },
                            premiumBadge = true,
                            onClick = {
                                Toast.makeText(context, "פתיחת Jam דורשת מנוי Premium", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }

                    // 8. לא לכלול את הטראק בפרופיל הטעמים שלכם
                    item {
                        ContextMenuItem(
                            title = "לא לכלול את הטראק בפרופיל הטעמים שלכם",
                            customIcon = { ExcludeTasteIcon() },
                            onClick = {
                                Toast.makeText(context, "הטראק לא ייכלל בפרופיל הטעמים שלך", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }

                    // 9. לצפייה בקרדיטים ליוצרי השיר
                    item {
                        ContextMenuItem(
                            title = "לצפייה בקרדיטים ליוצרי השיר",
                            customIcon = { CreditsIcon() },
                            onClick = {
                                Toast.makeText(context, "${song.title} • ${song.singer}", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }

                    // 10. הציגו את קוד Spotify
                    item {
                        ContextMenuItem(
                            title = "הציגו את קוד Spotify",
                            customIcon = { SpotifyWaveIcon() },
                            onClick = {
                                Toast.makeText(context, "קוד Spotify", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        )
                    }

                    // Download / Export actions if downloaded or active
                    if (downloaded) {
                        item {
                            ContextMenuItem(
                                title = "ייצוא לתיקיית Music",
                                iconRes = R.drawable.ic_download,
                                onClick = {
                                    onDismiss()
                                    val app = context.applicationContext
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        val ok = com.music.spotui.data.preferences.exportDownload(app, song)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(
                                                app,
                                                if (ok) "נשמר ב-Music/spotui" else "הייצוא נכשל",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            ContextMenuItem(
                                title = "מחיקת הורדה",
                                icon = Icons.Default.Close,
                                onClick = {
                                    com.music.spotui.data.preferences.removeDownload(context, song.id.toString())
                                    downloaded = false
                                    Toast.makeText(context, "ההורדה נמחקה", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // Admin options
                    if (com.music.spotui.BuildConfig.IS_ADMIN) {
                        val effectiveTrackId = com.music.spotui.util.KosherWhitelistManager.canonicalTrackId(song)
                            .ifBlank { song.spotifyTrackId.ifBlank { song.url } }
                        val isTrackApproved = com.music.spotui.util.KosherWhitelistManager.isTrackInWhitelist(
                            effectiveTrackId,
                            song.title,
                            song.singer
                        )
                        if (isTrackApproved) {
                            item {
                                ContextMenuItem(
                                    title = "הסר שיר מההיתר (Admin)",
                                    icon = Icons.Default.Close,
                                    iconColor = Color(0xFFE57373),
                                    onClick = {
                                        com.music.spotui.util.KosherWhitelistManager.removeTrack(context, effectiveTrackId, song.title, song.singer)
                                        Toast.makeText(context, "השיר הוסר מההיתר", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                )
                            }
                            val isSingerApproved = com.music.spotui.util.KosherWhitelistManager.isArtistInWhitelist(null, song.singer)
                            if (isSingerApproved) {
                                item {
                                    ContextMenuItem(
                                        title = "הסר את כל שירי ${song.singer} מההיתר (Admin)",
                                        icon = Icons.Default.Close,
                                        iconColor = Color(0xFFE57373),
                                        onClick = {
                                            com.music.spotui.util.KosherWhitelistManager.removeArtist(context, name = song.singer)
                                            Toast.makeText(context, "כל שירי ${song.singer} הוסרו מההיתר", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    )
                                }
                            } else if (song.singer.isNotBlank()) {
                                item {
                                    ContextMenuItem(
                                        title = "אשר את כל שירי ${song.singer} (Admin)",
                                        icon = Icons.Default.Person,
                                        iconColor = SpotifyGreen,
                                        onClick = {
                                            com.music.spotui.util.KosherWhitelistManager.addArtist(context, name = song.singer)
                                            Toast.makeText(context, "${song.singer} נוסף לרשימת ההיתר!", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        } else {
                            item {
                                ContextMenuItem(
                                    title = "אשר שיר לרשימת ההיתר (Admin)",
                                    icon = Icons.Default.CheckCircle,
                                    iconColor = SpotifyGreen,
                                    onClick = {
                                        com.music.spotui.util.KosherWhitelistManager.addTrack(
                                            context,
                                            effectiveTrackId,
                                            song.title,
                                            song.singer
                                        )
                                        Toast.makeText(context, "השיר נוסף לרשימת ההיתר!", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    } else {
                        val isSongAllowed = com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(song)
                        if (isSongAllowed) {
                            item {
                                ContextMenuItem(
                                    title = "דיווח על היתר תמונות",
                                    icon = Icons.Default.Warning,
                                    iconColor = Color(0xFFFFA726),
                                    onClick = {
                                        com.music.spotui.util.TelegramNotifier.sendTrackReportRequest(
                                            context = context,
                                            trackTitle = song.title,
                                            artistName = song.singer,
                                            trackId = song.spotifyTrackId
                                        )
                                        Toast.makeText(context, "דיווח נשלח בהצלחה", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    title: String,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    customIcon: (@Composable () -> Unit)? = null,
    iconColor: Color = TextWhite,
    premiumBadge: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 13.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (customIcon != null) {
                customIcon()
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                color = TextWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (premiumBadge) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        val stroke = 1.dp.toPx()
                        drawArc(
                            color = Color.Black,
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            style = Stroke(width = stroke)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Premium",
                    color = SpotifyGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AddToPlaylistIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val r = size.minDimension / 2f - 1.5.dp.toPx()
        drawCircle(color = TextWhite, radius = r, style = Stroke(width = 1.6.dp.toPx()))
        val halfL = r * 0.5f
        val stroke = 1.6.dp.toPx()
        drawLine(TextWhite, Offset(center.x - halfL, center.y), Offset(center.x + halfL, center.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(center.x, center.y - halfL), Offset(center.x, center.y + halfL), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun RemoveFromPlaylistIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val r = size.minDimension / 2f - 1.5.dp.toPx()
        drawCircle(color = TextWhite, radius = r, style = Stroke(width = 1.6.dp.toPx()))
        val halfL = r * 0.5f
        val stroke = 1.6.dp.toPx()
        drawLine(TextWhite, Offset(center.x - halfL, center.y), Offset(center.x + halfL, center.y), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun QueueIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.32f), Offset(w * 0.85f, h * 0.32f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.5f), Offset(w * 0.85f, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.68f), Offset(w * 0.85f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun AlbumDiscIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val r = size.minDimension / 2f - 1.5.dp.toPx()
        drawCircle(color = TextWhite, radius = r, style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(color = TextWhite, radius = r * 0.35f, style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(color = TextWhite, radius = r * 0.12f)
    }
}

@Composable
private fun ArtistIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        drawCircle(color = TextWhite, radius = w * 0.18f, center = Offset(w * 0.42f, h * 0.32f), style = Stroke(width = stroke))
        drawArc(
            color = TextWhite,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.15f, h * 0.54f),
            size = androidx.compose.ui.geometry.Size(w * 0.54f, h * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(TextWhite, Offset(w * 0.8f, h * 0.35f), Offset(w * 0.8f, h * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color = TextWhite, radius = w * 0.09f, center = Offset(w * 0.74f, h * 0.72f))
    }
}

@Composable
private fun JamIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        drawCircle(color = TextWhite, radius = w * 0.14f, center = Offset(w * 0.35f, h * 0.38f), style = Stroke(width = stroke))
        drawCircle(color = TextWhite, radius = w * 0.14f, center = Offset(w * 0.65f, h * 0.38f), style = Stroke(width = stroke))
        drawArc(color = TextWhite, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.12f, h * 0.58f), size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.3f), style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawArc(color = TextWhite, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.42f, h * 0.58f), size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.3f), style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun ExcludeTasteIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val r = size.minDimension / 2f - 1.5.dp.toPx()
        drawCircle(color = TextWhite, radius = r, style = Stroke(width = 1.6.dp.toPx()))
        val d = r * 0.45f
        val stroke = 1.6.dp.toPx()
        drawLine(TextWhite, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(center.x + d, center.y - d), Offset(center.x - d, center.y + d), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun CreditsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = 1.6.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.55f, h * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.55f), Offset(w * 0.55f, h * 0.55f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(w * 0.15f, h * 0.75f), Offset(w * 0.45f, h * 0.75f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(TextWhite, Offset(w * 0.8f, h * 0.28f), Offset(w * 0.8f, h * 0.65f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color = TextWhite, radius = w * 0.1f, center = Offset(w * 0.73f, h * 0.65f))
    }
}

@Composable
private fun SpotifyWaveIcon(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.size(width = 24.dp, height = 18.dp)
    ) {
        val heights = listOf(8.dp, 14.dp, 18.dp, 12.dp, 16.dp, 10.dp)
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(TextWhite)
            )
        }
    }
}
