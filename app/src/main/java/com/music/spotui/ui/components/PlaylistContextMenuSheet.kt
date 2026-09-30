package com.music.spotui.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.LibraryEntry
import kotlinx.coroutines.launch

private val SpotifySheetBg = Color(0xFF242424)
private val TextWhite = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA7A7A7)

/**
 * Official Spotify Playlist Context Menu bottom sheet.
 * Sampled directly 1:1 from Spotify's long-press menu on playlists:
 * - Playlist header: cover (56dp rounded), name (16sp bold), subtitle ("מאת {author} • פלייליסט ציבורי")
 * - 16 Spotify items sampled exactly:
 *   1. שיתוף
 *   2. להורדה (עם תג Premium)
 *   3. הוספה לפלייליסט הזה
 *   4. עריכת הפלייליסט
 *   5. שם ופרטים (פונקציונלי - פותח מסך עריכה)
 *   6. יצירת תמונת עטיפה
 *   7. מחיקת הפלייליסט (פונקציונלי - פותח דיאלוג מחיקה)
 *   8. הצמדת הפלייליסט
 *   9. הוספה לפלייליסט אחר
 *   10. הזמנת משתפי תוכן
 *   11. לפתיחת Jam (עם תג Premium)
 *   12. העברה לתיקייה
 *   13. הסרה מהפרופיל
 *   14. הגדרה כפרטי
 *   15. לא לכלול את הפלייליסט בפרופיל הטעמים שלכם
 *   16. הציגו את קוד Spotify
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlaylistContextMenuSheet(
    entry: LibraryEntry,
    onDismiss: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditDetailsClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxSheetHeight = screenHeight * 0.92f

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
                // ── Header: Cover + Title + Subtitle ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    if (entry.coverUri.isNotBlank()) {
                        GlideImage(
                            model = entry.coverUri,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder)
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF333333))
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_library_big),
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val author = if (entry.subtitle.isNotBlank()) entry.subtitle.replace("Playlist • ", "").replace("פלייליסט • ", "") else "SpotUI"
                        Text(
                            text = "מאת $author • פלייליסט ציבורי",
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

                // ── Menu items list ──
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        ContextMenuItem(
                            title = "שיתוף",
                            icon = Icons.Default.Share,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "להורדה",
                            iconRes = R.drawable.ic_download,
                            premiumBadge = true,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הוספה לפלייליסט הזה",
                            icon = Icons.Default.Add,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "עריכת הפלייליסט",
                            icon = Icons.Default.Edit,
                            onClick = {
                                coroutineScope.launch { sheetState.hide() }
                                onEditDetailsClick()
                            }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "שם ופרטים",
                            icon = Icons.Default.Edit,
                            onClick = {
                                coroutineScope.launch { sheetState.hide() }
                                onEditDetailsClick()
                            }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "יצירת תמונת עטיפה",
                            icon = Icons.Default.Edit,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "מחיקת הפלייליסט",
                            icon = Icons.Default.Delete,
                            onClick = {
                                coroutineScope.launch { sheetState.hide() }
                                onDeleteClick()
                            }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הצמדת הפלייליסט",
                            customIcon = { ContextPinIcon() },
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הוספה לפלייליסט אחר",
                            icon = Icons.Default.Add,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הזמנת משתפי תוכן",
                            icon = Icons.Default.Person,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "לפתיחת Jam",
                            icon = Icons.Default.PlayArrow,
                            premiumBadge = true,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "העברה לתיקייה",
                            iconRes = R.drawable.ic_library_big,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הסרה מהפרופיל",
                            icon = Icons.Default.Person,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הגדרה כפרטי",
                            icon = Icons.Default.Lock,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "לא לכלול את הפלייליסט בפרופיל הטעמים שלכם",
                            icon = Icons.Default.Close,
                            onClick = { onDismiss() }
                        )
                    }
                    item {
                        ContextMenuItem(
                            title = "הציגו את קוד Spotify",
                            customIcon = { SpotifyWaveIcon() },
                            onClick = { onDismiss() }
                        )
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
            .padding(horizontal = 20.dp, vertical = 14.dp)
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
                    tint = TextWhite,
                    modifier = Modifier.size(24.dp)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextWhite,
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
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
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
private fun ContextPinIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val c = TextWhite
        drawCircle(color = c, radius = w * 0.24f, center = center.copy(y = h * 0.35f, x = w * 0.6f))
        drawLine(
            color = c,
            start = Offset(w * 0.5f, h * 0.45f),
            end = Offset(w * 0.2f, h * 0.85f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
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
