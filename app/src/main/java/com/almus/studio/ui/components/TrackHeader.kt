package com.almus.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almus.studio.data.Track
import com.almus.studio.ui.theme.StudioAccent
import com.almus.studio.ui.theme.StudioRecord
import com.almus.studio.ui.theme.StudioSurface
import com.almus.studio.ui.theme.StudioTextSecondary

@Composable
fun TrackHeader(
    track: Track,
    isArmed: Boolean,
    onVolumePreview: (Float) -> Unit,
    onVolumeCommit: (Float) -> Unit,
    onPanPreview: (Float) -> Unit,
    onPanCommit: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onToggleArm: () -> Unit,
    onOpenEffects: () -> Unit
) {
    val trackColor = remember(track.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(track.colorHex)) }.getOrDefault(StudioAccent)
    }

    // Local drag state so the slider is smooth even though the "real" value
    // (track.volumeDb / track.pan) only updates once per commit, not per
    // frame of the drag -- see StudioViewModel's undo/redo notes on why
    // continuous drags don't push an undo snapshot on every intermediate value.
    var localVolume by remember(track.id, track.volumeDb) { mutableFloatStateOf(track.volumeDb) }
    var localPan by remember(track.id, track.pan) { mutableFloatStateOf(track.pan) }

    Row(
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(StudioSurface)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(trackColor))

        Column(modifier = Modifier.weight(1f).padding(8.dp)) {
            Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, color = trackColor)
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleChip(label = "M", active = track.muted, activeColor = StudioTextSecondary, onClick = onToggleMute)
                ToggleChip(label = "S", active = track.solo, activeColor = Color(0xFFFFD54F), onClick = onToggleSolo)
                ToggleChip(label = "R", active = isArmed, activeColor = StudioRecord, onClick = onToggleArm)
                ToggleChip(label = "FX", active = track.effects.any { it.enabled }, activeColor = StudioAccent, onClick = onOpenEffects)
            }

            Spacer(Modifier.height(6.dp))
            Text("Vol ${localVolume.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
            Slider(
                value = localVolume,
                onValueChange = { localVolume = it; onVolumePreview(it) },
                onValueChangeFinished = { onVolumeCommit(localVolume) },
                valueRange = -60f..12f
            )

            Spacer(Modifier.height(4.dp))
            Text("Pan ${"%.1f".format(localPan)}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
            Slider(
                value = localPan,
                onValueChange = { localPan = it; onPanPreview(it) },
                onValueChangeFinished = { onPanCommit(localPan) },
                valueRange = -1f..1f
            )
        }
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor else Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.Black else StudioTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
