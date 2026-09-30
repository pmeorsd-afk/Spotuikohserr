package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.LibraryEntry

private val SheetBg = Color(0xFF242424)
private val InputBg = Color(0xFF333333)
private val TextWhite = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA7A7A7)

/**
 * Official Spotify "שם ופרטים" (Edit name and details) bottom sheet.
 * Sampled 1:1 directly from official Spotify:
 * - Top bar: "ביטול" (right), "שם ופרטים" (center), "שמירה" (left)
 * - Row: Cover art (right, 100x100dp with edit pencil) + Name & Description input fields (left)
 * - Action rows: "הגדרה כפרטי" (lock icon) and "מחיקת הפלייליסט" (trash icon)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlaylistEditDetailsSheet(
    entry: LibraryEntry,
    onDismiss: () -> Unit,
    onSave: (newName: String, newDescription: String) -> Unit,
    onDeleteClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(entry.name) }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 24.dp)
            ) {
                // ── Top Bar: ביטול (right), שם ופרטים (center), שמירה (left) ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "ביטול",
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        )
                    )

                    Text(
                        text = "שם ופרטים",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "שמירה",
                        color = if (name.isNotBlank()) SpotifyGreen else TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (name.isNotBlank()) {
                                    onSave(name.trim(), description.trim())
                                }
                            }
                        )
                    )
                }

                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)

                Spacer(modifier = Modifier.height(18.dp))

                // ── Middle: Cover art on right, Text fields on left ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Right: Cover Image (with pencil edit icon overlay)
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF333333))
                    ) {
                        if (entry.coverUri.isNotBlank()) {
                            GlideImage(
                                model = entry.coverUri,
                                contentDescription = "Cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(104.dp),
                                failure = placeholder(R.drawable.placeholder),
                                loading = placeholder(R.drawable.placeholder)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_library_big),
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.Center)
                            )
                        }

                        // Pencil icon badge at bottom-left corner (BottomEnd in RTL is left)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit cover",
                                tint = TextWhite,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    // Left column: Name field + Description field
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Name text field
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            cursorBrush = SolidColor(SpotifyGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(InputBg)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (name.isEmpty()) {
                                        Text("שם הפלייליסט", color = TextSecondary, fontSize = 15.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Description text field
                        BasicTextField(
                            value = description,
                            onValueChange = { description = it },
                            textStyle = TextStyle(
                                color = TextWhite,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(SpotifyGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(InputBg)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (description.isEmpty()) {
                                        Text("הוספת תיאור", color = TextSecondary, fontSize = 14.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                HorizontalDivider(color = Color(0xFF2E2E2E), thickness = 1.dp)

                // ── Action rows below ──
                // הגדרה כפרטי
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isPrivate = !isPrivate }
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (isPrivate) "הגדרה כציבורי" else "הגדרה כפרטי",
                        color = TextWhite,
                        fontSize = 15.sp
                    )
                }

                // מחיקת הפלייליסט
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDeleteClick
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = TextWhite,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "מחיקת הפלייליסט",
                        color = TextWhite,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
