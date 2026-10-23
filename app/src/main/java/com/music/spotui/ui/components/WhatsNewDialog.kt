package com.music.spotui.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

internal val WelcomeGreen = Color(0xFF1ED760)

/** Bounded height and natural text sizes: giant accessibility fonts can scroll. */
@Composable
internal fun WelcomeDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight),
                    color = Color(0xFF161616), shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, Color(0xFF304738))) {
                    Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF1C3023), Color(0xFF161616))))
                        .verticalScroll(rememberScrollState()).padding(if (maxWidth < 340.dp) 18.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                }
            }
        }
    }
}

@Composable
internal fun WelcomeButton(text: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = WelcomeGreen, contentColor = Color.Black)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun WhatsNewDialog(showDialog: Boolean, onDismiss: () -> Unit, onContinue: () -> Unit = onDismiss) {
    if (!showDialog) return
    WelcomeDialog(onDismiss) {
        Text("חדש באפליקציה", color = WelcomeGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("יותר דרכים להאזין", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFF254332), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("עכשיו גם ב־Android Auto", color = WelcomeGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("המוזיקה והפודקאסטים שלכם, גם במסך הרכב.", color = Color.White, fontSize = 15.sp)
            }
        }
        for ((title, description) in listOf(
            "מסך פודקאסטים חדש" to "לגלות, לעקוב ולהמשיך להאזין.",
            "מסך בית בעיצוב חדש" to "המוזיקה שלכם במקום אחד.",
            "חיפוש משודרג" to "תוצאות והשלמות תוך כדי הקלדה."
        )) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(description, color = Color(0xFFB3B3B3), fontSize = 14.sp)
            }
        }
        WelcomeButton("עבור לאפליקציה", onContinue)
    }
}
