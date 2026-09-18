package com.almus.studio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.EffectChainParams

/**
 * A single dialog covering every Phase 2 effect. Deliberately one screen
 * rather than five separate ones -- there are only six effects total, and
 * a track's whole chain is one mental unit ("what is this track's sound
 * doing to it") more than five unrelated settings screens.
 */
@Composable
fun EffectsDialog(
    trackName: String,
    initial: EffectChainParams,
    onDismiss: () -> Unit,
    onApply: (EffectChainParams) -> Unit
) {
    var params by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$trackName — Effects") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EffectSection(
                    title = "3-Band EQ",
                    enabled = params.eqEnabled,
                    onToggle = { params = params.copy(eqEnabled = it) }
                ) {
                    LabeledSlider("Low (250 Hz)", params.eqLowGainDb, -18f..18f, "%.0f dB") {
                        params = params.copy(eqLowGainDb = it)
                    }
                    LabeledSlider("Mid (1 kHz)", params.eqMidGainDb, -18f..18f, "%.0f dB") {
                        params = params.copy(eqMidGainDb = it)
                    }
                    LabeledSlider("High (4 kHz)", params.eqHighGainDb, -18f..18f, "%.0f dB") {
                        params = params.copy(eqHighGainDb = it)
                    }
                }

                EffectSection(
                    title = "High-pass filter",
                    enabled = params.highPassEnabled,
                    onToggle = { params = params.copy(highPassEnabled = it) }
                ) {
                    LabeledSlider("Cutoff", params.highPassCutoffHz, 20f..2000f, "%.0f Hz") {
                        params = params.copy(highPassCutoffHz = it)
                    }
                }

                EffectSection(
                    title = "Low-pass filter",
                    enabled = params.lowPassEnabled,
                    onToggle = { params = params.copy(lowPassEnabled = it) }
                ) {
                    LabeledSlider("Cutoff", params.lowPassCutoffHz, 500f..20000f, "%.0f Hz") {
                        params = params.copy(lowPassCutoffHz = it)
                    }
                }

                EffectSection(
                    title = "Compressor",
                    enabled = params.compressorEnabled,
                    onToggle = { params = params.copy(compressorEnabled = it) }
                ) {
                    LabeledSlider("Threshold", params.compressorThresholdDb, -48f..0f, "%.0f dB") {
                        params = params.copy(compressorThresholdDb = it)
                    }
                    LabeledSlider("Ratio", params.compressorRatio, 1f..20f, "%.1f:1") {
                        params = params.copy(compressorRatio = it)
                    }
                    LabeledSlider("Attack", params.compressorAttackMs, 1f..200f, "%.0f ms") {
                        params = params.copy(compressorAttackMs = it)
                    }
                    LabeledSlider("Release", params.compressorReleaseMs, 20f..1000f, "%.0f ms") {
                        params = params.copy(compressorReleaseMs = it)
                    }
                }

                EffectSection(
                    title = "Delay",
                    enabled = params.delayEnabled,
                    onToggle = { params = params.copy(delayEnabled = it) }
                ) {
                    LabeledSlider("Time", params.delayTimeMs, 20f..1000f, "%.0f ms") {
                        params = params.copy(delayTimeMs = it)
                    }
                    LabeledSlider("Feedback", params.delayFeedback, 0f..0.9f, "%.0f%%", 100f) {
                        params = params.copy(delayFeedback = it)
                    }
                    LabeledSlider("Mix", params.delayMix, 0f..1f, "%.0f%%", 100f) {
                        params = params.copy(delayMix = it)
                    }
                }

                EffectSection(
                    title = "Reverb",
                    enabled = params.reverbEnabled,
                    onToggle = { params = params.copy(reverbEnabled = it) }
                ) {
                    LabeledSlider("Room size", params.reverbRoomSize, 0f..1f, "%.0f%%", 100f) {
                        params = params.copy(reverbRoomSize = it)
                    }
                    LabeledSlider("Mix", params.reverbMix, 0f..1f, "%.0f%%", 100f) {
                        params = params.copy(reverbMix = it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(params) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EffectSection(
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Column(Modifier.padding(start = 8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    displayMultiplier: Float = 1f,
    onChange: (Float) -> Unit
) {
    Column {
        Text("$label: ${format.format(value * displayMultiplier)}", style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
