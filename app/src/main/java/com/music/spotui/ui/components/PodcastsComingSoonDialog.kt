package com.music.spotui.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DialogAccentGreen = Color(0xFF1ED760)
private val DialogDarkTop = Color(0xFF282828)
private val DialogDarkMiddle = Color(0xFF1C1C1C)
private val DialogDarkBottom = Color(0xFF121212)
private val DialogTextWhite = Color(0xFFFFFFFF)
private val DialogTextSecondary = Color(0xFFB3B3B3)

/**
 * Premium Spotify-themed "Coming Soon" Dialog for the Podcasts feature.
 *
 * Designed with:
 * - Spotify Dark Elevation Card with subtle green gradient rim
 * - Ambient radial glow aura behind podcast broadcast centerpiece
 * - Animated pulsing "בקרוב" status pill badge
 * - RTL Hebrew layout with Spotify typography hierarchy
 * - Authentic Spotify Pill primary action button with ripple feedback
 * - Scale & Fade micro-interaction entry animation
 */
@Composable
fun PodcastsComingSoonDialog(
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
            // Fullscreen backdrop scrim with touch-to-dismiss
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Animated Dialog Card container
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.88f,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                            ),
                    exit = fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.92f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .widthIn(max = 400.dp)
                            // Prevent clicking inside the dialog card from dismissing
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        DialogDarkTop,
                                        DialogDarkMiddle,
                                        DialogDarkBottom
                                    )
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        DialogAccentGreen.copy(alpha = 0.35f),
                                        Color.White.copy(alpha = 0.08f),
                                        DialogAccentGreen.copy(alpha = 0.15f)
                                    )
                                ),
                                shape = RoundedCornerShape(28.dp)
                            )
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Top Row: Subtle Dismiss 'X' Button on Top End
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onDismiss
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "סגור",
                                        tint = DialogTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Visual Centerpiece: Glowing Green Ambient Aura + Broadcast Podcast Box
                            PodcastGlowCenterpiece()

                            Spacer(modifier = Modifier.height(18.dp))

                            // "בקרוב" (Coming Soon) Pill Badge with Pulsing Live Dot
                            ComingSoonPillBadge()

                            Spacer(modifier = Modifier.height(14.dp))

                            // Title: Bold modern Spotify typography in Hebrew
                            Text(
                                text = "מסך פודקאסט חדשני בקרוב",
                                color = DialogTextWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                lineHeight = 28.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Subtitle & Descriptive Body
                            Text(
                                text = "אנו שוקדים בימים אלו על חוויית פודקאסטים עשירה, נקייה ומותאמת אישית.",
                                color = DialogAccentGreen,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "אלפי שיעורים, הרצאות מרתקות, סיפורים ותכנים איכותיים יחכו לכם ממש כאן בעדכון הקרוב של האפליקציה.",
                                color = DialogTextSecondary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // Feature Preview Pills (Kosher & High Quality cues)
                            FeaturesRow()

                            Spacer(modifier = Modifier.height(24.dp))

                            // Primary Action Button: Authentic Spotify Green Pill
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(DialogAccentGreen)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onDismiss
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "מעולה, תודה!",
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visual centerpiece featuring an ambient green glow aura and a sleek podcast microphone
 * with radio broadcast soundwaves drawn via high-precision Compose Canvas.
 */
@Composable
private fun PodcastGlowCenterpiece() {
    Box(
        modifier = Modifier.size(110.dp),
        contentAlignment = Alignment.Center
    ) {
        // Radial Green Aura in background
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DialogAccentGreen.copy(alpha = 0.28f),
                            DialogAccentGreen.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // Glassmorphic Squircle Badge
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF2E2E2E),
                            Color(0xFF161616)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            DialogAccentGreen,
                            DialogAccentGreen.copy(alpha = 0.3f)
                        )
                    ),
                    shape = RoundedCornerShape(22.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Elegant Studio Mic + Broadcast Soundwaves Canvas
            Canvas(modifier = Modifier.size(38.dp)) {
                val w = size.width
                val h = size.height
                val cx = w / 2f

                // --- 1. Soundwaves on Left and Right ---
                val waveStroke = 2.dp.toPx()
                val waveColor = DialogAccentGreen.copy(alpha = 0.75f)

                // Left inner & outer wave arcs
                drawArc(
                    color = waveColor,
                    startAngle = 120f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(cx - 16.dp.toPx(), h * 0.12f),
                    size = Size(32.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(width = waveStroke, cap = StrokeCap.Round)
                )

                // Right inner & outer wave arcs
                drawArc(
                    color = waveColor,
                    startAngle = 300f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(cx - 16.dp.toPx(), h * 0.12f),
                    size = Size(32.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(width = waveStroke, cap = StrokeCap.Round)
                )

                // --- 2. Microphone Capsule ---
                val capW = 12.dp.toPx()
                val capH = 18.dp.toPx()
                val capTop = h * 0.15f
                drawRoundRect(
                    color = DialogAccentGreen,
                    topLeft = Offset(cx - capW / 2f, capTop),
                    size = Size(capW, capH),
                    cornerRadius = CornerRadius(capW / 2f, capW / 2f)
                )

                // Mesh divider line across mic
                drawLine(
                    color = Color(0xFF121212),
                    start = Offset(cx - capW / 2f + 1.dp.toPx(), capTop + capH * 0.5f),
                    end = Offset(cx + capW / 2f - 1.dp.toPx(), capTop + capH * 0.5f),
                    strokeWidth = 1.5.dp.toPx()
                )

                // --- 3. Cradle Arc (U-shaped stand support) ---
                val cradleW = 18.dp.toPx()
                val cradleH = 14.dp.toPx()
                val cradleTop = capTop + capH * 0.35f
                drawArc(
                    color = DialogTextWhite,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - cradleW / 2f, cradleTop),
                    size = Size(cradleW, cradleH),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // --- 4. Vertical Stem ---
                val stemTop = cradleTop + cradleH
                val stemBottom = stemTop + 5.dp.toPx()
                drawLine(
                    color = DialogTextWhite,
                    start = Offset(cx, stemTop),
                    end = Offset(cx, stemBottom),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // --- 5. Base Plate ---
                drawLine(
                    color = DialogTextWhite,
                    start = Offset(cx - 6.dp.toPx(), stemBottom),
                    end = Offset(cx + 6.dp.toPx(), stemBottom),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Spotify-styled pill badge with a rhythmic pulsing green dot and "בקרוב" label.
 */
@Composable
private fun ComingSoonPillBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(DialogAccentGreen.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = DialogAccentGreen.copy(alpha = 0.45f),
                shape = RoundedCornerShape(100.dp)
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        // Glowing animated green dot
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(DialogAccentGreen.copy(alpha = dotAlpha))
        )

        Spacer(modifier = Modifier.width(7.dp))

        Text(
            text = "בקרוב בגרסה הבאה",
            color = DialogAccentGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

/**
 * Trio of neat feature cues highlighting the upcoming kosher podcast experience.
 */
@Composable
private fun FeaturesRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeatureMiniChip(title = "שיעורים והרצאות")
        FeatureMiniChip(title = "האזנה רציפה")
    }
}

@Composable
private fun FeatureMiniChip(title: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = DialogTextWhite.copy(alpha = 0.9f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
