package com.almus.studio

import com.almus.studio.audio.TimeConversion
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeConversionTest {

    @Test
    fun `one second at 48000hz is 48000 frames`() {
        assertEquals(48000L, TimeConversion.secondsToFrames(1.0, 48000))
    }

    @Test
    fun `framesToSeconds is the inverse of secondsToFrames`() {
        val frames = TimeConversion.secondsToFrames(2.5, 44100)
        val seconds = TimeConversion.framesToSeconds(frames, 44100)
        assertEquals(2.5, seconds, 0.001)
    }

    @Test
    fun `120 bpm has half a second per beat`() {
        val framesPerBeat = TimeConversion.framesPerBeat(120, 48000)
        assertEquals(24000.0, framesPerBeat, 0.001)
    }

    @Test
    fun `four beats at 120bpm is 96000 frames at 48khz`() {
        assertEquals(96000L, TimeConversion.beatsToFrames(4.0, 120, 48000))
    }

    @Test
    fun `formatPosition renders minutes seconds and hundredths`() {
        // 90.5 seconds = 1 minute, 30 seconds, 50 hundredths
        val frames = TimeConversion.secondsToFrames(90.5, 48000)
        assertEquals("01:30.50", TimeConversion.formatPosition(frames, 48000))
    }
}
