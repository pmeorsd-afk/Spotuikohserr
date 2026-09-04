package com.music.spotui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.spotui.R
import kotlinx.coroutines.delay

/**
 * Guided, in-app replacement for bouncing straight to system Settings when the Waze switch is
 * turned on. Stays open while the person hops out to grant each permission and back (Settings
 * screen's own DisposableEffect/ON_RESUME refresh - see CHANGES.md part 3 - is what makes the
 * checkmarks below update live), and auto-closes shortly after all three are granted.
 */
@Composable
fun WazePermissionsSheet(
    hasOverlay: Boolean,
    hasUsage: Boolean,
    hasScreenDetector: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onRequestScreenDetector: () -> Unit,
    onAllGranted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allGranted = hasOverlay && hasUsage && hasScreenDetector
    LaunchedEffect(allGranted) {
        if (allGranted) {
            delay(600)
            onAllGranted()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF181818),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_spotify_waze),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "חיבור ל-Waze",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "כדי שהכפתור הצף יעבוד בתוך Waze, יש לאשר שלוש הרשאות. אפשר לעבור בין " +
                        "Waze ל-SpotUI חופשי בזמן האישור - המסך הזה יישאר פתוח ויתעדכן לבד.",
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(20.dp))

                WazePermissionRow(
                    stepNumber = 1,
                    title = "הצגה מעל אפליקציות",
                    subtitle = "כדי שכפתור הנגן יוכל לצוף מעל Waze",
                    granted = hasOverlay,
                    onClick = onRequestOverlay,
                )
                Spacer(Modifier.height(14.dp))
                WazePermissionRow(
                    stepNumber = 2,
                    title = "גישה לנתוני שימוש",
                    subtitle = "כדי לזהות מתי Waze פתוחה",
                    granted = hasUsage,
                    onClick = onRequestUsage,
                )
                Spacer(Modifier.height(14.dp))
                WazePermissionRow(
                    stepNumber = 3,
                    title = "זיהוי מסך ב-Waze",
                    subtitle = "כדי שהכפתור גם ייעלם בתוך תפריטי Waze",
                    granted = hasScreenDetector,
                    onClick = onRequestScreenDetector,
                )

                Spacer(Modifier.height(22.dp))
                if (allGranted) {
                    Text(
                        text = "הכל מאושר ✓",
                        color = Color(0xFF1ED760),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("סגירה", color = Color(0xFFB3B3B3))
                    }
                }
            }
        }
    }
}

@Composable
private fun WazePermissionRow(
    stepNumber: Int,
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF222426))
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (granted) Color(0xFF1ED760) else Color(0xFF3E4246)),
        ) {
            if (granted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(text = stepNumber.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (!granted) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text("אשר", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
