package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.studio.ui.theme.StudioTextSecondary

/**
 * Draws bar markers across [widthDp] given [pixelsPerBeat] and the project's
 * [beatsPerBar]. Purely visual — the underlying timeline unit of truth is
 * always frames (see AudioClip.startFrame), never pixels.
 */
@Composable
fun TimelineRuler(widthDp: Dp, pixelsPerBeat: Float, beatsPerBar: Int, heightDp: Dp = 28.dp) {
    val textColor = StudioTextSecondary
    Canvas(modifier = Modifier.width(widthDp).height(heightDp)) {
        val totalBeats = (size.width / pixelsPerBeat).toInt() + 1
        for (beat in 0..totalBeats) {
            val x = beat * pixelsPerBeat
            val isBarStart = beat % beatsPerBar == 0
            val lineHeight = if (isBarStart) size.height else size.height * 0.4f
            drawLine(
                color = textColor,
                start = Offset(x, size.height - lineHeight),
                end = Offset(x, size.height),
                strokeWidth = if (isBarStart) 2f else 1f
            )
            if (isBarStart) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${beat / beatsPerBar + 1}",
                    x + 4f,
                    size.height * 0.6f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 24f
                        isFakeBoldText = true
                    }
                )
            }
        }
    }
}
