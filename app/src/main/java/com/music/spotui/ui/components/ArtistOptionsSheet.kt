package com.music.spotui.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.util.TelegramNotifier

/**
 * 3-dot options sheet for an artist page:
 * - Share artist link
 * - Suggest artist for Kosher whitelist review (via Telegram bot)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistOptionsSheet(
    artistName: String,
    artistId: String,
    avatarImage: String = "",
    context: Context,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Header: Artist avatar + name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp),
            ) {
                GlideImage(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    model = avatarImage,
                    contentScale = ContentScale.Crop,
                    contentDescription = artistName,
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = artistName,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "אמן",
                        color = Color.Gray,
                        fontSize = 13.sp,
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // Option 1: Share
            ArtistMenuRow(
                icon = Icons.Default.Share,
                label = "שתף אמן",
                onClick = {
                    val shareUrl = if (artistId.isNotBlank()) {
                        "https://open.spotify.com/artist/$artistId"
                    } else {
                        "Spotify Artist: $artistName"
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                    }
                    context.startActivity(Intent.createChooser(send, "שתף אמן"))
                    onDismiss()
                },
            )

            // Option 2: Suggest for Kosher Whitelist review via Telegram
            ArtistMenuRow(
                icon = Icons.Default.CheckCircle,
                label = "הצע לבדיקה והיתר תמונות",
                iconTint = Color(0xFF1ED760),
                onClick = {
                    TelegramNotifier.sendArtistApprovalRequest(
                        context = context,
                        artistName = artistName,
                        artistId = artistId,
                    )
                    onDismiss()
                },
            )

            Spacer(modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ArtistMenuRow(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color.White,
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp, 14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) iconTint else Color.Gray.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.Gray.copy(alpha = 0.4f),
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
