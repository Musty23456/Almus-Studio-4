package com.almus.studio.data

import com.squareup.moshi.JsonClass

/**
 * On-disk project format. A project is a folder:
 *   /Android/data/com.almus.studio/files/Projects/<projectId>/
 *       project.json        <- this data, serialized
 *       audio/<clipId>.wav  <- managed copies of imported/recorded audio
 *
 * Using the app's external files directory (not internal-only storage) means
 * projects survive app reinstalls the user backs up manually and are visible
 * to a connected computer or file manager, per the "no inaccessible internal
 * paths for user projects" requirement.
 */
@JsonClass(generateAdapter = true)
data class Project(
    val id: String,
    val name: String,
    val bpm: Int = 120,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val sampleRate: Int = 48000,
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long,
    val tracks: List<Track> = emptyList(),
    val masterVolumeDb: Float = 0f
)

@JsonClass(generateAdapter = true)
data class Track(
    val id: String,
    val name: String,
    val volumeDb: Float = 0f,
    val pan: Float = 0f, // -1.0 (left) .. 1.0 (right)
    val muted: Boolean = false,
    val solo: Boolean = false,
    val armed: Boolean = false,
    val colorHex: String = "#5CE1E6",
    val clips: List<AudioClip> = emptyList(),
    val effects: List<EffectSettings> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AudioClip(
    val id: String,
    /** Path relative to the project's audio/ folder, e.g. "a1b2c3.wav" */
    val fileName: String,
    /** Where the clip begins on the track timeline, in sample frames at project sample rate */
    val startFrame: Long,
    /** Offset into the source file where playback should start, in frames */
    val sourceOffsetFrames: Long,
    /** Length of the clip as it plays on the timeline, in frames */
    val lengthFrames: Long,
    val gainDb: Float = 0f,
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val looping: Boolean = false
)

@JsonClass(generateAdapter = true)
data class EffectSettings(
    val type: EffectType,
    val enabled: Boolean = true,
    /** Simple flat parameter map, e.g. "wet" -> 0.3f, "cutoffHz" -> 4000f */
    val params: Map<String, Float> = emptyMap()
)

enum class EffectType {
    GAIN, EQ3, COMPRESSOR, LIMITER, NOISE_GATE, REVERB, DELAY, CHORUS, FLANGER,
    DISTORTION, HIGH_PASS, LOW_PASS
}
