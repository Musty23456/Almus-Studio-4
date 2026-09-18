package com.almus.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.almus.studio.data.Project
import com.almus.studio.ui.theme.StudioAccent
import com.almus.studio.ui.theme.StudioSurface
import com.almus.studio.ui.theme.StudioTextSecondary
import com.almus.studio.viewmodel.StudioViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: StudioViewModel,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val projects by viewModel.recentProjects.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshRecentProjects() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Almus Studio", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewProject,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New Project") },
                containerColor = StudioAccent
            )
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = StudioTextSecondary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No projects yet", color = StudioTextSecondary)
                    Text("Tap New Project to start recording offline.", color = StudioTextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Recent Projects", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                }
                items(projects, key = { it.id }) { project ->
                    ProjectRow(project = project, onClick = { onOpenProject(project.id) })
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${project.tracks.size} tracks · ${project.bpm} BPM · ${dateFormat.format(Date(project.modifiedAtEpochMs))}",
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary
            )
        }
    }
}
