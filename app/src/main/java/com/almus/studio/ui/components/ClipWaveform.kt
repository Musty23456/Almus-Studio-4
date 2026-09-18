package com.almus.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.ui.theme.StudioSurfaceVariant
import com.almus.studio.ui.theme.StudioWaveform

/** Draws a single audio clip's waveform as a filled min/max envelope. */
@Composable
fun ClipWaveform(
    peaks: WaveformAnalyzer.Peaks?,
    widthDp: Dp,
    heightDp: Dp = 64.dp,
    color: Color = StudioWaveform
) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp)
            .clip(RoundedCornerShape(4.dp))
            .background(StudioSurfaceVariant)
    ) {
        if (peaks != null && peaks.max.isNotEmpty()) {
            Canvas(modifier = Modifier.width(widthDp).height(heightDp)) {
                val midY = size.height / 2f
                val bucketWidth = size.width / peaks.max.size
                for (i in peaks.max.indices) {
                    val x = i * bucketWidth + bucketWidth / 2f
                    val topY = midY - (peaks.max[i] * midY)
                    val bottomY = midY - (peaks.min[i] * midY)
                    drawLine(
                        color = color,
                        start = Offset(x, topY),
                        end = Offset(x, bottomY),
                        strokeWidth = bucketWidth.coerceAtLeast(1f),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
