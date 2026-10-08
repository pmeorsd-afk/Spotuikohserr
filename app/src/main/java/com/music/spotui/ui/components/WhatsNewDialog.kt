package com.music.spotui.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DialogGreenColor = Color(0xFF1ED760)
private val DialogDarkTop = Color(0xFF262626)
private val DialogDarkBottom = Color(0xFF121212)
private val RowPillBg = Color(0xFF1F1F1F)

@Composable
fun WhatsNewDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit
) {
    if (!showDialog) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showDialog,
                    enter = scaleIn(spring(dampingRatio = 0.82f, stiffness = 340f), initialScale = 0.88f) + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { /* prevent outside dismiss clicks inside card */ }
                            )
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(DialogDarkTop, Color(0xFF181818), DialogDarkBottom)
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        DialogGreenColor.copy(alpha = 0.7f),
                                        DialogGreenColor.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(26.dp)
                            )
                    ) {
                        // Ambient radial green glow at the top
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        DialogGreenColor.copy(alpha = 0.18f),
                                        DialogGreenColor.copy(alpha = 0.05f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width / 2f, 70.dp.toPx()),
                                    radius = 160.dp.toPx()
                                )
                            )
                        }

                        // Close button (Top-Left in RTL)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "סגור",
                                tint = Color(0xFFE0E0E0),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Content Column
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Top celebratory badge icon
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(
                                        brush = Brush.linearGradient(
                                            listOf(
                                                DialogGreenColor.copy(alpha = 0.28f),
                                                DialogGreenColor.copy(alpha = 0.08f)
                                            )
                                        )
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = DialogGreenColor.copy(alpha = 0.6f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✨",
                                    fontSize = 26.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Title
                            Text(
                                text = "מה חדש בגרסה 2.8?",
                                color = Color.White,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                letterSpacing = 0.2.sp
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "עדכון מיוחד לחוויית האזנה מושלמת",
                                color = DialogGreenColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // What's New items
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WhatsNewItemRow(
                                    emoji = "🎧",
                                    title = "עיצוב חדש לנגן",
                                    subtitle = null
                                )

                                WhatsNewItemRow(
                                    emoji = "🧭",
                                    title = "עיצוב חדש לכפתורי הניווט",
                                    subtitle = null
                                )

                                WhatsNewItemRow(
                                    emoji = "🎙️",
                                    title = "מסך פודקאסט חדשני",
                                    subtitle = "בקרוב באפליקציה!"
                                )

                                WhatsNewItemRow(
                                    emoji = "🔍",
                                    title = "כרטיסיות גילוי",
                                    subtitle = "עיצוב חדש בחיפוש"
                                )

                                WhatsNewItemRow(
                                    emoji = "⚡",
                                    title = "ביצועים",
                                    subtitle = "טעינה מהירה והאזנה חלקה"
                                )
                            }

                            Spacer(modifier = Modifier.height(26.dp))

                            // Spotify Green Action Button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(DialogGreenColor)
                                    .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "יאללה, בואו נתחיל! 🎵",
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.2.sp
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
private fun WhatsNewItemRow(
    emoji: String,
    title: String,
    subtitle: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RowPillBg)
            .border(
                width = 0.8.dp,
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Emoji icon in circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            if (subtitle != null) {
                Text(
                    text = " – $subtitle",
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}
