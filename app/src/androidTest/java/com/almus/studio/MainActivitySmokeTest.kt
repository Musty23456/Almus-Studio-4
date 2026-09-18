package com.almus.studio

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deliberately minimal: this just confirms the activity + native library
 * (`System.loadLibrary("almus_audio")` in AudioEngine.kt) load without
 * crashing on a real device/emulator. Deeper audio-engine behavior belongs in
 * JVM unit tests where possible (see app/src/test), since most CI runners
 * don't have real audio hardware for a device test to exercise meaningfully.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityLaunchesWithoutCrashing() {
        activityRule.scenario.onActivity { activity ->
            assert(!activity.isFinishing)
        }
    }
}
