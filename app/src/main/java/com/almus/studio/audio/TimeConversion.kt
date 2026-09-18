package com.almus.studio.audio

/** Pure functions for converting between frames, seconds, and beats. No Android
 * dependencies, so these are covered by fast JVM unit tests (see
 * app/src/test/.../TimeConversionTest.kt) rather than instrumented tests. */
object TimeConversion {

    fun framesToSeconds(frames: Long, sampleRate: Int): Double = frames.toDouble() / sampleRate

    fun secondsToFrames(seconds: Double, sampleRate: Int): Long = (seconds * sampleRate).toLong()

    fun framesPerBeat(bpm: Int, sampleRate: Int): Double = (60.0 / bpm) * sampleRate

    fun beatsToFrames(beats: Double, bpm: Int, sampleRate: Int): Long =
        (beats * framesPerBeat(bpm, sampleRate)).toLong()

    fun formatPosition(frames: Long, sampleRate: Int): String {
        val totalSeconds = framesToSeconds(frames, sampleRate)
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        val hundredths = ((totalSeconds % 1) * 100).toInt()
        return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
    }
}
