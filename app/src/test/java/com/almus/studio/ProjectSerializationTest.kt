package com.almus.studio

import com.almus.studio.data.AudioClip
import com.almus.studio.data.EffectSettings
import com.almus.studio.data.EffectType
import com.almus.studio.data.Project
import com.almus.studio.data.Track
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSerializationTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(Project::class.java)

    @Test
    fun `project with tracks, clips, and effects survives a JSON round trip`() {
        val original = Project(
            id = "proj-1",
            name = "Test Song",
            bpm = 128,
            timeSignatureNumerator = 3,
            timeSignatureDenominator = 4,
            sampleRate = 44100,
            createdAtEpochMs = 1000L,
            modifiedAtEpochMs = 2000L,
            masterVolumeDb = -3f,
            tracks = listOf(
                Track(
                    id = "track-1",
                    name = "Vocals",
                    volumeDb = -2f,
                    pan = 0.3f,
                    muted = false,
                    solo = true,
                    armed = false,
                    clips = listOf(
                        AudioClip(
                            id = "clip-1",
                            fileName = "abc.wav",
                            startFrame = 44100L,
                            sourceOffsetFrames = 0L,
                            lengthFrames = 88200L,
                            gainDb = 1.5f,
                            fadeInFrames = 100L,
                            fadeOutFrames = 200L,
                            looping = true
                        )
                    ),
                    effects = listOf(
                        EffectSettings(
                            type = EffectType.REVERB,
                            enabled = true,
                            params = mapOf("wet" to 0.25f, "roomSize" to 0.6f)
                        )
                    )
                )
            )
        )

        val json = adapter.toJson(original)
        val decoded = adapter.fromJson(json)

        assertEquals(original, decoded)
    }
}
