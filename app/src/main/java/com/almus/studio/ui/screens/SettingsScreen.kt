package com.almus.studio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(title = "Audio engine") {
                SettingsRow("Sample rate", "48,000 Hz")
                SettingsRow("Audio backend", "Oboe (AAudio / OpenSL ES)")
                SettingsRow("Sharing mode", "Exclusive, falls back to Shared")
                Text(
                    "The engine's sample rate is fixed for now — imported or recorded audio at a different rate is automatically resampled (linear interpolation) when it's added to a track.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SettingsSection(title = "Storage") {
                Text(
                    "Projects and their audio files live entirely on this device, under this app's private storage area. Nothing is uploaded anywhere — Almus Studio has no network permission at all.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SettingsSection(title = "About") {
                Text("Almus Studio", style = MaterialTheme.typography.titleMedium)
                Text("Offline multitrack recorder, editor, and mixer.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Effects: 3-band EQ, high-pass, low-pass, compressor, delay, reverb.\n" +
                        "Pitch correction is a lightweight autocorrelation + granular pitch shifter — " +
                        "not a professional-grade Auto-Tune replacement.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
