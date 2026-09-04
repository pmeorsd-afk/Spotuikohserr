package com.music.spotui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.spotui.R

/**
 * Explains the single accessibility permission the Waze integration needs, then hands off to
 * system Settings - replaces the old three-step WazePermissionsSheet now that there is only
 * one permission to grant. Settings screen's own DisposableEffect/ON_RESUME refresh is what
 * makes the switch pick up the change live once the person comes back from Settings.
 */
@Composable
fun WazeAccessibilityPrompt(
    onRequestAccessibility: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "כדי שכפתור הנגן יופיע בתוך Waze - ורק על מסך המפה, לא בתוך תפריטים - " +
                        "צריך להפעיל הרשאת נגישות אחת בשם \"זיהוי מסך ב-Waze\". היא לא אוספת " +
                        "ולא שולחת מידע לשום מקום, היא רק בודקת אם המסך הפתוח כרגע הוא של Waze.",
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRequestAccessibility,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text("פתיחת הגדרות נגישות", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ביטול", color = Color(0xFFB3B3B3))
                }
            }
        }
    }
}
