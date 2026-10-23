package com.music.spotui.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Font metrics and RTL must not clip or mirror this check. */
@Composable
internal fun SavedCheckBadge(modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp).semantics { contentDescription = "נשמר בספרייה" }) {
        drawCircle(Color(0xFF1ED760))
        val path = Path().apply {
            moveTo(size.width * .27f, size.height * .50f)
            lineTo(size.width * .43f, size.height * .65f)
            lineTo(size.width * .73f, size.height * .34f)
        }
        drawPath(path, Color.Black, style = Stroke(
            width = size.width * .12f, cap = StrokeCap.Round, join = StrokeJoin.Round,
        ))
    }
}

@Composable
internal fun ListeningProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val value = fraction.coerceIn(0f, 1f)
    Canvas(modifier.progressSemantics(value)) {
        val radius = size.height / 2
        val start = if (rtl) size.width - radius else radius
        val end = if (rtl) radius else size.width - radius
        val tip = start + (end - start) * value
        drawLine(Color(0xFF333333), Offset(start, radius), Offset(end, radius), size.height, StrokeCap.Round)
        if (value > 0f) drawLine(Color(0xFF1ED760), Offset(start, radius), Offset(tip, radius), size.height, StrokeCap.Round)
        drawCircle(Color(0xFF1ED760), radius, Offset(tip, radius))
    }
}
