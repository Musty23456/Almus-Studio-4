package com.almus.studio.viewmodel
import com.almus.studio.audio.toMask
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.studio.audio.AudioDecoder
import com.almus.studio.audio.AudioEngine
import com.almus.studio.audio.EffectChainParams
import com.almus.studio.audio.EffectChainParams.Companion.toEffectSettings
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.data.AudioClip
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.data.Track
import com.almus.studio.data.TrackColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed interface TransportState { data object Stopped : TransportState; data object Playing : TransportState; data object Paused : TransportState }

data class RecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val armedTrackId: String? = null,
    val inputLevelDb: Float = -96f,
    val isClipping: Boolean = false
)

/** Non-null only while an offline export is running; see exportProject()/cancelExport(). */
data class ExportState(
    val outputFile: File,
    val progress: Float = 0f,
    val error: String? = null,
    val complete: Boolean = false
)

/** Maps stable [Track.id]/[AudioClip.id] strings to native integer handles. */
private class HandleTable {
    val trackHandles = mutableMapOf<String, Int>()
    val clipHandles = mutableMapOf<String, Int>()
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val handles = HandleTable()
    private val sampleRate = 48000

    private val _recentProjects = MutableStateFlow<List<Project>>(emptyList())
    val recentProjects: StateFlow<List<Project>> = _recentProjects.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Stopped)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _playheadFrame = MutableStateFlow(0L)
    val playheadFrame: StateFlow<Long> = _playheadFrame.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _masterPeaksDb = MutableStateFlow(floatArrayOf(-96f, -96f))
    val masterPeaksDb: StateFlow<FloatArray> = _masterPeaksDb.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState?>(null)
    val exportState: StateFlow<ExportState?> = _exportState.asStateFlow()

    /** One-shot user-facing error messages (failed import, failed recording, etc). */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun dismissError() { _errorMessage.value = null }

    init {
        AudioEngine.nativeInit(sampleRate, 256)
        refreshRecentProjects()
        startUiPollingLoop()
    }

    private fun startUiPollingLoop() {
        viewModelScope.launch {
            while (coroutineContext.isActive) {
                _playheadFrame.value = AudioEngine.getPlayheadFrame()
                _masterPeaksDb.value = AudioEngine.getMasterPeaksDb()
                if (_recordingState.value.isRecording) {
                    _recordingState.value = _recordingState.value.copy(
                        inputLevelDb = AudioEngine.getInputLevelDb(),
                        isClipping = AudioEngine.isInputClipping()
                    )
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    fun refreshRecentProjects() {
        _recentProjects.value = repository.listProjects()
    }

    fun createProject(name: String, bpm: Int) {
        val project = repository.createProject(name.ifBlank { "Untitled Project" }, bpm)
        openProject(project.id)
        refreshRecentProjects()
    }

    fun openProject(projectId: String) {
        val project = repository.load(projectId) ?: return
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        attachProjectToEngine(project)
    }

    /** Tears down any currently-attached native track state and rebuilds it
     * to exactly match [project]. Used both for opening a project and for
     * undo/redo, where reverting to a snapshot is simplest done as a full
     * resync rather than trying to compute and reverse individual deltas. */
    private fun attachProjectToEngine(project: Project) {
        handles.trackHandles.values.forEach { AudioEngine.removeTrack(it) }
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        _currentProject.value = project
        project.tracks.forEach { track ->
            val handle = AudioEngine.addTrack(track.id)
            handles.trackHandles[track.id] = handle
            AudioEngine.setTrackVolumeDb(handle, track.volumeDb)
            AudioEngine.setTrackPan(handle, track.pan)
            AudioEngine.setTrackMuted(handle, track.muted)
            AudioEngine.setTrackSolo(handle, track.solo)
            EffectChainParams.fromEffectSettings(track.effects).pushTo(handle)
            track.clips.forEach { clip -> scheduleClipOnEngine(track.id, clip, project) }
        }
    }

    private fun scheduleClipOnEngine(trackId: String, clip: AudioClip, project: Project) {
        val trackHandle = handles.trackHandles[trackId] ?: return
        val file = File(repository.audioDir(project.id), clip.fileName)
        if (!file.exists()) return
        val clipHandle = AudioEngine.scheduleClip(
            trackHandle, file.absolutePath, clip.startFrame, clip.sourceOffsetFrames,
            clip.lengthFrames, clip.gainDb, clip.looping, clip.fadeInFrames, clip.fadeOutFrames
        )
        if (clipHandle >= 0) handles.clipHandles[clip.id] = clipHandle
    }

    fun closeProject() {
        handles.trackHandles.values.forEach { AudioEngine.removeTrack(it) }
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        clipboard = null
        _clipboardAvailable.value = false
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
        _currentProject.value = null
        _transportState.value = TransportState.Stopped
    }

    // --- Undo / redo (Phase 3) --------------------------------------------------
    // Whole-project-snapshot based rather than per-field command objects: a
    // DAW's "one edit" already spans a model change (Project) plus native
    // engine state (scheduled clips, track params) that has to move together,
    // so reverting to a full prior snapshot and re-attaching it to the engine
    // (see attachProjectToEngine) is simpler and more robust than hand-writing
    // an inverse for every operation. Depth-capped so it can't grow forever.
    // Continuous drags (volume/pan sliders) intentionally do NOT push a
    // snapshot on every intermediate value -- see previewTrackVolume/Pan --
    // only the final committed value does, or one slider drag would eat the
    // whole undo history in a fraction of a second.

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndoSnapshot(project: Project) {
        undoStack.addLast(project)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    fun undo() {
        val current = _currentProject.value ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
        attachProjectToEngine(previous)
        persist()
    }

    fun redo() {
        val current = _currentProject.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
        attachProjectToEngine(next)
        persist()
    }

    // --- Transport ----------------------------------------------------------
    // Play/record are disabled while an export is running -- the offline
    // export worker and the live playback thread would otherwise process the
    // same per-track effect state concurrently (see audio_engine.h's note on
    // why the mixing scratch buffer is thread_local: the effect chain state
    // has the same hazard and isn't given the same protection, so the UI is
    // what keeps these two mutually exclusive).

    fun play() {
        if (_exportState.value != null) return
        AudioEngine.play(); _transportState.value = TransportState.Playing
    }
    fun pause() { AudioEngine.pause(); _transportState.value = TransportState.Paused }
    fun stop() { AudioEngine.stop(); _transportState.value = TransportState.Stopped }
    fun seekTo(frame: Long) { AudioEngine.seekToFrame(frame) }

    // --- Track editing --------------------------------------------------------

    fun setTrackVolume(trackId: String, volumeDb: Float) =
        updateTrack(trackId) { it.copy(volumeDb = volumeDb) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackVolumeDb(h, volumeDb) }
        }

    fun setTrackPan(trackId: String, pan: Float) =
        updateTrack(trackId) { it.copy(pan = pan) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackPan(h, pan) }
        }

    /** Live audio feedback while dragging, with no model/undo change -- see setTrackVolume for the commit. */
    fun previewTrackVolume(trackId: String, volumeDb: Float) {
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackVolumeDb(it, volumeDb) }
    }

    /** Live audio feedback while dragging, with no model/undo change -- see setTrackPan for the commit. */
    fun previewTrackPan(trackId: String, pan: Float) {
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackPan(it, pan) }
    }

    fun toggleMute(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.muted
        updateTrack(trackId) { it.copy(muted = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackMuted(it, newValue) }
    }

    fun toggleSolo(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.solo
        updateTrack(trackId) { it.copy(solo = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackSolo(it, newValue) }
    }

    fun toggleArm(trackId: String) {
        val current = _recordingState.value
        _recordingState.value = current.copy(
            armedTrackId = if (current.armedTrackId == trackId) null else trackId
        )
    }

    fun addTrack() {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val newTrack = Track(
            id = UUID.randomUUID().toString(),
            name = "Track ${project.tracks.size + 1}",
            colorHex = TrackColors.forIndex(project.tracks.size)
        )
        val updated = project.copy(tracks = project.tracks + newTrack)
        _currentProject.value = updated
        val handle = AudioEngine.addTrack(newTrack.id)
        handles.trackHandles[newTrack.id] = handle
        persist()
    }

    private fun updateTrack(trackId: String, transform: (Track) -> Track) {
        val project = _currentProject.value ?: return
        pushUndoSnapshot(project)
        val updated = project.copy(tracks = project.tracks.map { if (it.id == trackId) transform(it) else it })
        _currentProject.value = updated
        persist()
    }

    private fun persist() {
        _currentProject.value?.let { repository.save(it) }
    }

    // --- Recording ------------------------------------------------------------

    fun startRecording() {
        val project = _currentProject.value ?: return
        val armedTrackId = _recordingState.value.armedTrackId ?: project.tracks.firstOrNull()?.id ?: return
        val trackHandle = handles.trackHandles[armedTrackId] ?: return
        val outFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
        val started = AudioEngine.startRecording(trackHandle, outFile.absolutePath)
        if (started) {
            lastRecordedFile = outFile
            _recordingState.value = _recordingState.value.copy(isRecording = true, isPaused = false)
        } else {
            val reason = AudioEngine.getLastRecordingError()
            _errorMessage.value = "Couldn't start recording" + if (reason.isNotBlank()) ": $reason" else " on this device."
        }
    }

    fun pauseRecording() {
        AudioEngine.pauseRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = true)
    }

    fun resumeRecording() {
        AudioEngine.resumeRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = false)
    }

    fun stopRecording() {
        val framesWritten = AudioEngine.stopRecording()
        val project = _currentProject.value
        val armedTrackId = _recordingState.value.armedTrackId
        _recordingState.value = _recordingState.value.copy(isRecording = false, isPaused = false)

        if (project != null && armedTrackId != null && framesWritten > 0) {
            val track = project.tracks.find { it.id == armedTrackId } ?: return
            // The file was already written by the native engine to audioDir();
            // we just need to know its name, which we don't have here since it
            // was generated inside startRecording(). In a full implementation
            // this filename would be threaded back through a callback; Phase 1
            // keeps the UI responsible for remembering it via lastRecordedFile.
            lastRecordedFile?.let { file ->
                val clip = AudioClip(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    startFrame = 0L,
                    sourceOffsetFrames = 0L,
                    lengthFrames = framesWritten
                )
                updateTrack(armedTrackId) { it.copy(clips = it.clips + clip) }
                scheduleClipOnEngine(armedTrackId, clip, project)
            }
        }
    }

    private var lastRecordedFile: File? = null

    // --- Import -----------------------------------------------------------

    /** Copies an externally-picked audio file into the project and schedules it on [trackId]. */
    fun importAudioFile(trackId: String, sourceUri: android.net.Uri, displayName: String) {
        val project = _currentProject.value ?: return
        val context = getApplication<Application>()
        val destFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Fast path: copy as-is and check whether it's already a real WAV
            // (common if the person picked a file they recorded elsewhere).
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }

            val isRealWav = destFile.exists() && AudioDecoder.looksLikeWav(destFile)
            if (!isRealWav) {
                // Most audio on a phone is MP3/M4A/OGG, not WAV -- the native
                // engine only reads WAV (see wav_file.h), so without this the
                // import would silently do nothing. Decode via MediaCodec
                // instead of just rejecting the file.
                destFile.delete()
                val decoded = AudioDecoder.decodeToWav(context, sourceUri, destFile)
                if (!decoded) {
                    _errorMessage.value = "Couldn't import \"$displayName\" — this file's audio format isn't supported on this device."
                    return@launch
                }
            }

            val trackHandle = handles.trackHandles[trackId] ?: return@launch
            val clipHandle = AudioEngine.scheduleClip(trackHandle, destFile.absolutePath, 0L, 0L, -1L, 0f, false)
            if (clipHandle < 0) {
                destFile.delete()
                _errorMessage.value = "Couldn't import \"$displayName\" — the decoded audio couldn't be read."
                return@launch
            }
            val realFrameCount = WaveformAnalyzer.frameCount(destFile) ?: 0L
            val clip = AudioClip(
                id = UUID.randomUUID().toString(), fileName = destFile.name,
                startFrame = 0L, sourceOffsetFrames = 0L, lengthFrames = realFrameCount
            )
            handles.clipHandles[clip.id] = clipHandle
            updateTrack(trackId) { it.copy(clips = it.clips + clip) }
        }
    }

    // --- Clip editing (Phase 2) ------------------------------------------------

    /** Removes the clip both from the native engine and the persisted project. */
    fun deleteClip(trackId: String, clipId: String) {
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { it.copy(clips = it.clips.filterNot { c -> c.id == clipId }) }
    }

    // --- Clipboard: copy / cut / paste (Phase 3) --------------------------------
    // The clipboard holds an AudioClip's data plus which project it came from,
    // since the fileName is only meaningful relative to that project's audio/
    // folder. Pasting into a different project isn't supported (the source
    // file lives in the other project's private folder) -- paste is a no-op
    // across projects, since only one project can be open at a time anyway.

    private var clipboard: AudioClip? = null
    private val _clipboardAvailable = MutableStateFlow(false)
    val clipboardAvailable: StateFlow<Boolean> = _clipboardAvailable.asStateFlow()

    fun copyClip(trackId: String, clipId: String) {
        val clip = _currentProject.value?.tracks?.find { it.id == trackId }?.clips?.find { it.id == clipId } ?: return
        clipboard = clip
        _clipboardAvailable.value = true
    }

    fun cutClip(trackId: String, clipId: String) {
        copyClip(trackId, clipId)
        deleteClip(trackId, clipId)
    }

    /** Pastes the clipboard clip onto [trackId] starting at [atFrame]. */
    fun pasteClip(trackId: String, atFrame: Long) {
        val project = _currentProject.value ?: return
        val source = clipboard ?: return
        val pasted = source.copy(id = UUID.randomUUID().toString(), startFrame = atFrame.coerceAtLeast(0))
        updateTrack(trackId) { it.copy(clips = it.clips + pasted) }
        scheduleClipOnEngine(trackId, pasted, project)
    }

    /**
     * Splits [clipId] at [atFrame] (an absolute timeline frame) into two
     * clips that together reproduce the original exactly -- both reference
     * the same underlying audio file, just with different source offsets.
     * No-op if [atFrame] isn't strictly inside the clip's span.
     */
    fun splitClip(trackId: String, clipId: String, atFrame: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val clipEnd = clip.startFrame + clip.lengthFrames
        if (atFrame <= clip.startFrame || atFrame >= clipEnd) return

        val firstLength = atFrame - clip.startFrame
        val secondLength = clip.lengthFrames - firstLength

        val first = clip.copy(
            id = UUID.randomUUID().toString(),
            lengthFrames = firstLength,
            fadeOutFrames = 0L // the cut point shouldn't inherit the original's fade-out
        )
        val second = clip.copy(
            id = UUID.randomUUID().toString(),
            startFrame = atFrame,
            sourceOffsetFrames = clip.sourceOffsetFrames + firstLength,
            lengthFrames = secondLength,
            fadeInFrames = 0L
        )

        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.filterNot { it.id == clipId } + first + second) }
        scheduleClipOnEngine(trackId, first, project)
        scheduleClipOnEngine(trackId, second, project)
    }

    /** Sets fade lengths (in frames) on an existing clip and reschedules it on the engine. */
    fun setClipFades(trackId: String, clipId: String, fadeInFrames: Long, fadeOutFrames: Long) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(
            fadeInFrames = fadeInFrames.coerceIn(0L, clip.lengthFrames),
            fadeOutFrames = fadeOutFrames.coerceIn(0L, clip.lengthFrames)
        )
        handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
        updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
        scheduleClipOnEngine(trackId, updated, project)
    }

    // --- Offline vocal pitch correction (Phase 3) -------------------------------
    // Not "Auto-Tune": autocorrelation pitch detection + granular pitch shift,
    // no formant preservation. See ROADMAP.md/README.md for the honest scope.

    private val _pitchCorrectionInProgress = MutableStateFlow(false)
    val pitchCorrectionInProgress: StateFlow<Boolean> = _pitchCorrectionInProgress.asStateFlow()

    fun correctClipPitch(trackId: String, clipId: String, settings: com.almus.studio.audio.PitchCorrectionSettings) {
        val project = _currentProject.value ?: return
        val track = project.tracks.find { it.id == trackId } ?: return
        val clip = track.clips.find { it.id == clipId } ?: return
        val inputFile = File(repository.audioDir(project.id), clip.fileName)
        if (!inputFile.exists()) return

        _pitchCorrectionInProgress.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outputFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
            val success = AudioEngine.correctPitch(
                inputFile.absolutePath, outputFile.absolutePath,
                settings.root.pitchClass, settings.scale.toMask(),
                settings.strength, settings.speedMs, settings.hardMode
            )
            _pitchCorrectionInProgress.value = false
            if (!success) {
                outputFile.delete()
                _errorMessage.value = "Pitch correction failed — the clip's audio couldn't be processed."
                return@launch
            }
            // Correction preserves duration and alignment exactly, so the
            // clip's frame fields stay the same; only the file changes.
            val updated = clip.copy(fileName = outputFile.name)
            handles.clipHandles.remove(clipId)?.let { AudioEngine.removeClip(it) }
            updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) updated else it }) }
            scheduleClipOnEngine(trackId, updated, project)
        }
    }

    // --- Effects (Phase 2) -----------------------------------------------------
    // Ships EQ, high/low pass, compressor, delay, and reverb. Limiter, noise
    // gate, chorus, flanger, distortion, and pitch correction are not
    // implemented -- see ROADMAP.md.

    fun updateTrackEffects(trackId: String, params: EffectChainParams) {
        updateTrack(trackId) { it.copy(effects = params.toEffectSettings()) }
        handles.trackHandles[trackId]?.let { params.pushTo(it) }
    }

    fun effectsFor(trackId: String): EffectChainParams {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return EffectChainParams()
        return EffectChainParams.fromEffectSettings(track.effects)
    }

    // --- Offline export (Phase 2) -----------------------------------------------

    /** End of the furthest clip across all tracks, or a short default for an empty project. */
    private fun computeProjectLengthFrames(project: Project): Long {
        val furthest = project.tracks.flatMap { it.clips }
            .maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
        return if (furthest > 0L) furthest else project.sampleRate.toLong() * 4 // 4 seconds of silence, minimum
    }

    fun exportProject() {
        val project = _currentProject.value ?: return
        if (_exportState.value != null) return // an export is already running
        val outputDir = getApplication<Application>().getExternalFilesDir("Exports") ?: return
        outputDir.mkdirs()
        val outputFile = File(outputDir, "${project.name.ifBlank { "export" }}-${System.currentTimeMillis()}.wav")
        val totalFrames = computeProjectLengthFrames(project)

        _exportState.value = ExportState(outputFile = outputFile)
        AudioEngine.startOfflineExport(outputFile.absolutePath, totalFrames, object : AudioEngine.ExportProgressListener {
            override fun onProgress(fractionComplete: Float) {
                _exportState.value = _exportState.value?.copy(progress = fractionComplete)
            }
            override fun onComplete(outputFilePath: String) {
                _exportState.value = _exportState.value?.copy(progress = 1f, complete = true)
            }
            override fun onError(message: String) {
                _exportState.value = _exportState.value?.copy(error = message, complete = true)
            }
        })
    }

    fun cancelExport() {
        AudioEngine.cancelOfflineExport()
    }

    fun dismissExportResult() {
        _exportState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        AudioEngine.nativeShutdown()
    }
}
