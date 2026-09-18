package com.almus.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.almus.studio.ui.theme.StudioAccent
import com.almus.studio.ui.theme.StudioRecord
import com.almus.studio.ui.theme.StudioSurfaceVariant
import com.almus.studio.viewmodel.TransportState

@Composable
fun TransportBar(
    transportState: TransportState,
    isRecording: Boolean,
    positionLabel: String,
    bpm: Int,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportButton(
                icon = Icons.Filled.FiberManualRecord,
                tint = if (isRecording) StudioRecord else StudioRecord.copy(alpha = 0.7f),
                onClick = onRecord,
                contentDescription = "Record"
            )
            Spacer(Modifier.width(8.dp))
            TransportButton(
                icon = if (transportState is TransportState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                tint = StudioAccent,
                onClick = { if (transportState is TransportState.Playing) onPause() else onPlay() },
                contentDescription = "Play/Pause"
            )
            Spacer(Modifier.width(8.dp))
            TransportButton(
                icon = Icons.Filled.Stop,
                tint = Color.White,
                onClick = onStop,
                contentDescription = "Stop"
            )
        }

        Text(positionLabel, style = MaterialTheme.typography.titleMedium)

        Text("$bpm BPM", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = tint)
        }
    }
}
