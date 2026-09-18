package com.almus.studio.audio

enum class RootNote(val pitchClass: Int, val label: String) {
    C(0, "C"), C_SHARP(1, "C#"), D(2, "D"), D_SHARP(3, "D#"), E(4, "E"), F(5, "F"),
    F_SHARP(6, "F#"), G(7, "G"), G_SHARP(8, "G#"), A(9, "A"), A_SHARP(10, "A#"), B(11, "B")
}

/**
 * Intervals are semitone offsets from the root that are allowed correction
 * targets. CHROMATIC allows every semitone -- i.e. "just snap to the nearest
 * in-tune note, regardless of key" rather than true chromatic transposition.
 */
enum class MusicalScale(val label: String, val intervals: List<Int>) {
    MAJOR("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    CHROMATIC("Chromatic (any note)", (0..11).toList())
}

fun MusicalScale.toMask(): Int = intervals.fold(0) { mask, interval -> mask or (1 shl interval) }

data class PitchCorrectionSettings(
    val root: RootNote = RootNote.C,
    val scale: MusicalScale = MusicalScale.MAJOR,
    val strength: Float = 0.7f,   // 0..1
    val speedMs: Float = 50f,     // retune glide time
    val hardMode: Boolean = false
)
