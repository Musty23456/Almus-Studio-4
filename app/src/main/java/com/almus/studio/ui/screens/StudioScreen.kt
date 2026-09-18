package com.almus.studio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.EffectChainParams
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.data.AudioClip
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.ui.components.ClipWaveform
import com.almus.studio.ui.components.EffectsDialog
import com.almus.studio.ui.components.TimelineRuler
import com.almus.studio.ui.components.TrackHeader
import com.almus.studio.ui.components.TransportBar
import com.almus.studio.viewmodel.StudioViewModel
import java.io.File

private const val PIXELS_PER_BEAT = 40f
private val TRACK_ROW_HEIGHT = 220.dp

@Composable
fun StudioScreen(viewModel: StudioViewModel, onBack: () -> Unit) {
    val project by viewModel.currentProject.collectAsState()
    val transportState by viewModel.transportState.collectAsState()
    val playheadFrame by viewModel.playheadFrame.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val context = LocalContext.current

    var micPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionGranted = granted
    }

    var importTargetTrackId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val trackId = importTargetTrackId
        if (uri != null && trackId != null) {
            viewModel.importAudioFile(trackId, uri, uri.lastPathSegment ?: "import.wav")
        }
    }

    var effectsDialogTrackId by remember { mutableStateOf<String?>(null) }
    var selectedClip by remember { mutableStateOf<Pair<String, String>?>(null) } // trackId to clipId
    val exportState by viewModel.exportState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val clipboardAvailable by viewModel.clipboardAvailable.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val currentProject = project ?: run {
        // Project was closed or not yet loaded; nothing to show.
        Scaffold(topBar = { TopAppBar(title = { Text("Almus Studio") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val bpm = currentProject.bpm
    val secondsPerBeat = 60f / bpm
    val framesPerBeat = secondsPerBeat * currentProject.sampleRate
    val pixelsPerFrame = PIXELS_PER_BEAT / framesPerBeat
    val playheadSeconds = playheadFrame / currentProject.sampleRate.toFloat()
    val positionLabel = "%02d:%02d.%02d".format(
        (playheadSeconds / 60).toInt(),
        (playheadSeconds % 60).toInt(),
        ((playheadSeconds % 1) * 100).toInt()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentProject.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { viewModel.addTrack() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add track")
                    }
                    IconButton(onClick = { viewModel.exportProject() }, enabled = exportState == null) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            TransportBar(
                transportState = transportState,
                isRecording = recordingState.isRecording,
                positionLabel = positionLabel,
                bpm = bpm,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
                onRecord = {
                    if (!micPermissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (recordingState.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                }
            )
        }
    ) { padding ->
        if (!micPermissionGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    "Microphone permission not granted yet — recording is disabled until you allow it. Editing and mixing imported audio still works fully offline.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val verticalScroll = rememberScrollState()
            val horizontalScroll = rememberScrollState()

            Row(Modifier.weight(1f)) {
                // Track headers (fixed, vertically scrolls with the timeline)
                Column(
                    modifier = Modifier
                        .verticalScroll(verticalScroll)
                        .padding(top = 28.dp) // aligns with ruler height
                ) {
                    currentProject.tracks.forEach { track ->
                        Box(Modifier.height(TRACK_ROW_HEIGHT).padding(vertical = 4.dp)) {
                            TrackHeader(
                                track = track,
                                isArmed = recordingState.armedTrackId == track.id,
                                onVolumePreview = { viewModel.previewTrackVolume(track.id, it) },
                                onVolumeCommit = { viewModel.setTrackVolume(track.id, it) },
                                onPanPreview = { viewModel.previewTrackPan(track.id, it) },
                                onPanCommit = { viewModel.setTrackPan(track.id, it) },
                                onToggleMute = { viewModel.toggleMute(track.id) },
                                onToggleSolo = { viewModel.toggleSolo(track.id) },
                                onToggleArm = { viewModel.toggleArm(track.id) },
                                onOpenEffects = { effectsDialogTrackId = track.id }
                            )
                        }
                    }
                }

                // Timeline: ruler + per-track clip lanes, scrolls both ways
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                ) {
                    TimelineRuler(
                        widthDp = 4000.dp,
                        pixelsPerBeat = PIXELS_PER_BEAT,
                        beatsPerBar = currentProject.timeSignatureNumerator
                    )
                    Column(modifier = Modifier.verticalScroll(verticalScroll)) {
                        currentProject.tracks.forEach { track ->
                            Box(Modifier.height(TRACK_ROW_HEIGHT).padding(vertical = 4.dp)) {
                                TrackTimelineLane(
                                    projectId = currentProject.id,
                                    track = track,
                                    pixelsPerFrame = pixelsPerFrame,
                                    canPaste = clipboardAvailable,
                                    onImportRequested = {
                                        importTargetTrackId = track.id
                                        importLauncher.launch("audio/*")
                                    },
                                    onClipTapped = { clipId -> selectedClip = track.id to clipId },
                                    onPasteRequested = { viewModel.pasteClip(track.id, playheadFrame) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    effectsDialogTrackId?.let { trackId ->
        val track = currentProject.tracks.find { it.id == trackId }
        if (track != null) {
            EffectsDialog(
                trackName = track.name,
                initial = viewModel.effectsFor(trackId),
                onDismiss = { effectsDialogTrackId = null },
                onApply = { params ->
                    viewModel.updateTrackEffects(trackId, params)
                    effectsDialogTrackId = null
                }
            )
        }
    }

    var pitchCorrectionTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // trackId to clipId

    selectedClip?.let { (trackId, clipId) ->
        val clip = currentProject.tracks.find { it.id == trackId }?.clips?.find { it.id == clipId }
        if (clip != null) {
            ClipActionsDialog(
                clip = clip,
                sampleRate = currentProject.sampleRate,
                onDismiss = { selectedClip = null },
                onSplitAtPlayhead = {
                    viewModel.splitClip(trackId, clipId, playheadFrame)
                    selectedClip = null
                },
                onCopy = {
                    viewModel.copyClip(trackId, clipId)
                    selectedClip = null
                },
                onCut = {
                    viewModel.cutClip(trackId, clipId)
                    selectedClip = null
                },
                onPitchCorrect = {
                    pitchCorrectionTarget = trackId to clipId
                    selectedClip = null
                },
                onDelete = {
                    viewModel.deleteClip(trackId, clipId)
                    selectedClip = null
                },
                onFadeChange = { fadeInFrames, fadeOutFrames ->
                    viewModel.setClipFades(trackId, clipId, fadeInFrames, fadeOutFrames)
                }
            )
        }
    }

    pitchCorrectionTarget?.let { (trackId, clipId) ->
        val inProgress by viewModel.pitchCorrectionInProgress.collectAsState()
        PitchCorrectionDialog(
            inProgress = inProgress,
            onDismiss = { pitchCorrectionTarget = null },
            onApply = { settings ->
                viewModel.correctClipPitch(trackId, clipId, settings)
                pitchCorrectionTarget = null
            }
        )
    }

    exportState?.let { state ->
        AlertDialog(
            onDismissRequest = { if (state.complete) viewModel.dismissExportResult() },
            title = { Text(if (state.complete) "Export finished" else "Exporting…") },
            text = {
                Column {
                    when {
                        state.error != null -> Text("Export failed: ${state.error}")
                        state.complete -> Text("Saved to ${state.outputFile.absolutePath}")
                        else -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(state.progress * 100).toInt()}%")
                        }
                    }
                }
            },
            confirmButton = {
                if (state.complete) {
                    TextButton(onClick = { viewModel.dismissExportResult() }) { Text("OK") }
                } else {
                    TextButton(onClick = { viewModel.cancelExport() }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun ClipActionsDialog(
    clip: AudioClip,
    sampleRate: Int,
    onDismiss: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPitchCorrect: () -> Unit,
    onDelete: () -> Unit,
    onFadeChange: (fadeInFrames: Long, fadeOutFrames: Long) -> Unit
) {
    val fadeStepFrames = (sampleRate * 0.1).toLong() // 100ms per tap
    var fadeIn by remember(clip.id) { mutableStateOf(clip.fadeInFrames) }
    var fadeOut by remember(clip.id) { mutableStateOf(clip.fadeOutFrames) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSplitAtPlayhead, modifier = Modifier.fillMaxWidth()) {
                    Text("Split at playhead")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
                    OutlinedButton(onClick = onCut, modifier = Modifier.weight(1f)) { Text("Cut") }
                }

                Button(onClick = onPitchCorrect, modifier = Modifier.fillMaxWidth()) {
                    Text("Pitch correction…")
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Fade in: ${fadeIn * 1000 / sampleRate} ms")
                    Row {
                        TextButton(onClick = {
                            fadeIn = (fadeIn - fadeStepFrames).coerceAtLeast(0)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("-") }
                        TextButton(onClick = {
                            fadeIn = (fadeIn + fadeStepFrames).coerceAtMost(clip.lengthFrames)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("+") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Fade out: ${fadeOut * 1000 / sampleRate} ms")
                    Row {
                        TextButton(onClick = {
                            fadeOut = (fadeOut - fadeStepFrames).coerceAtLeast(0)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("-") }
                        TextButton(onClick = {
                            fadeOut = (fadeOut + fadeStepFrames).coerceAtMost(clip.lengthFrames)
                            onFadeChange(fadeIn, fadeOut)
                        }) { Text("+") }
                    }
                }

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete clip")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PitchCorrectionDialog(
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onApply: (com.almus.studio.audio.PitchCorrectionSettings) -> Unit
) {
    var settings by remember { mutableStateOf(com.almus.studio.audio.PitchCorrectionSettings()) }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text("Pitch correction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Detects pitch and nudges it toward the nearest note in the chosen key. Works best on a single monophonic voice — it isn't a full professional pitch corrector.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text("Key: ${settings.root.label}", style = MaterialTheme.typography.labelSmall)
                LazyRowChips(
                    options = com.almus.studio.audio.RootNote.values().toList(),
                    label = { it.label },
                    selected = settings.root,
                    onSelect = { settings = settings.copy(root = it) }
                )

                Text("Scale: ${settings.scale.label}", style = MaterialTheme.typography.labelSmall)
                LazyRowChips(
                    options = com.almus.studio.audio.MusicalScale.values().toList(),
                    label = { it.label },
                    selected = settings.scale,
                    onSelect = { settings = settings.copy(scale = it) }
                )

                Text("Strength: ${(settings.strength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.strength, onValueChange = { settings = settings.copy(strength = it) }, valueRange = 0f..1f)

                Text("Retune speed: ${settings.speedMs.toInt()} ms", style = MaterialTheme.typography.labelSmall)
                Slider(value = settings.speedMs, onValueChange = { settings = settings.copy(speedMs = it) }, valueRange = 5f..200f)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Hard mode (snap instantly)")
                    Switch(checked = settings.hardMode, onCheckedChange = { settings = settings.copy(hardMode = it) })
                }

                if (inProgress) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Processing…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(settings) }, enabled = !inProgress) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) { Text("Cancel") }
        }
    )
}

@Composable
private fun <T> LazyRowChips(options: List<T>, label: (T) -> String, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
private fun TrackTimelineLane(
    projectId: String,
    track: com.almus.studio.data.Track,
    pixelsPerFrame: Float,
    canPaste: Boolean,
    onImportRequested: () -> Unit,
    onClipTapped: (clipId: String) -> Unit,
    onPasteRequested: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }
    val trackColor = remember(track.colorHex) {
        runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(track.colorHex)) }
            .getOrDefault(com.almus.studio.ui.theme.StudioWaveform)
    }

    Box(Modifier.fillMaxSize()) {
        track.clips.forEach { clip ->
            val file = remember(clip.id) { File(repository.audioDir(projectId), clip.fileName) }
            val peaks by produceState<WaveformAnalyzer.Peaks?>(initialValue = null, clip.id) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WaveformAnalyzer.analyze(file, bucketCount = 200)
                }
            }
            val widthDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                (clip.lengthFrames * pixelsPerFrame).toInt().coerceAtLeast(40).toFloat().toDp()
            }
            val offsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                (clip.startFrame * pixelsPerFrame).toDp()
            }
            Box(
                Modifier
                    .offset(x = offsetDp)
                    .clickable { onClipTapped(clip.id) }
            ) {
                ClipWaveform(peaks = peaks, widthDp = widthDp, heightDp = 92.dp, color = trackColor)
            }
        }

        Row(
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (canPaste) {
                androidx.compose.material3.IconButton(onClick = onPasteRequested) {
                    androidx.compose.material3.Icon(Icons.Filled.ContentPaste, contentDescription = "Paste clip")
                }
            }
            androidx.compose.material3.IconButton(onClick = onImportRequested) {
                androidx.compose.material3.Icon(Icons.Filled.FileUpload, contentDescription = "Import audio")
            }
        }
    }
}
