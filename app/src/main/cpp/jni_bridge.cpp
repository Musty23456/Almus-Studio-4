#include <jni.h>
#include <algorithm>
#include <string>
#include <memory>

#include "audio_engine.h"
#include "dsp/pitch_correction.h"
#include "effect_chain.h"
#include "wav_file.h"

using almus::AlmusAudioEngine;
using almus::Command;
using almus::CommandType;
using almus::DecodedBuffer;

namespace {

std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Holds a global ref to the Kotlin ExportProgressListener plus the JavaVM so
// callbacks from the export worker thread (not a JNI-attached thread) can
// attach and invoke back into Kotlin.
struct ExportListenerBridge {
    JavaVM* vm = nullptr;
    jobject listenerGlobalRef = nullptr;
};

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_nativeInit(JNIEnv*, jobject, jint sampleRate, jint framesPerBurst) {
    AlmusAudioEngine::instance().init(sampleRate, framesPerBurst);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_nativeShutdown(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().shutdown();
}

JNIEXPORT jint JNICALL
Java_com_almus_studio_audio_AudioEngine_addTrack(JNIEnv* env, jobject, jstring trackId) {
    (void) jstringToStdString(env, trackId); // kept for future per-track diagnostics/logging
    auto& engine = AlmusAudioEngine::instance();
    int32_t handle = engine.allocateTrackHandle();
    Command cmd{};
    cmd.type = CommandType::AddTrack;
    cmd.trackHandle = handle;
    engine.enqueue(cmd);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_removeTrack(JNIEnv*, jobject, jint trackHandle) {
    Command cmd{};
    cmd.type = CommandType::RemoveTrack;
    cmd.trackHandle = trackHandle;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackVolumeDb(JNIEnv*, jobject, jint trackHandle, jfloat db) {
    Command cmd{}; cmd.type = CommandType::SetTrackVolume; cmd.trackHandle = trackHandle; cmd.floatValue = db;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackPan(JNIEnv*, jobject, jint trackHandle, jfloat pan) {
    Command cmd{}; cmd.type = CommandType::SetTrackPan; cmd.trackHandle = trackHandle; cmd.floatValue = pan;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackMuted(JNIEnv*, jobject, jint trackHandle, jboolean muted) {
    Command cmd{}; cmd.type = CommandType::SetTrackMute; cmd.trackHandle = trackHandle; cmd.boolValue = muted;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setTrackSolo(JNIEnv*, jobject, jint trackHandle, jboolean solo) {
    Command cmd{}; cmd.type = CommandType::SetTrackSolo; cmd.trackHandle = trackHandle; cmd.boolValue = solo;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT jint JNICALL
Java_com_almus_studio_audio_AudioEngine_scheduleClip(JNIEnv* env, jobject, jint trackHandle,
        jstring filePath, jlong startFrame, jlong sourceOffsetFrames, jlong lengthFrames,
        jfloat gainDb, jboolean looping, jlong fadeInFrames, jlong fadeOutFrames) {
    std::string path = jstringToStdString(env, filePath);
    almus::WavLoadResult loaded = almus::loadWavAsStereoFloat(path);
    if (!loaded.success) {
        return -1;
    }

    auto& engine = AlmusAudioEngine::instance();

    // Fix up sample-rate mismatches here rather than silently mis-playing the
    // clip (see wav_file.h's resampleStereoLinear doc comment).
    std::vector<float> resampled;
    int64_t frameCount = loaded.frameCount;
    const float* samples = loaded.interleavedStereo.data();
    if (loaded.sourceSampleRate > 0 && loaded.sourceSampleRate != engine.getSampleRate()) {
        int64_t resampledFrames = 0;
        resampled = almus::resampleStereoLinear(loaded.interleavedStereo, loaded.frameCount,
                                                 loaded.sourceSampleRate, engine.getSampleRate(),
                                                 &resampledFrames);
        frameCount = resampledFrames;
        samples = resampled.data();
    }

    // Ownership of this buffer transfers to the engine (freed on clip removal
    // or engine shutdown) -- see Clip::buffer in audio_engine.h.
    auto* buf = new float[static_cast<size_t>(frameCount) * 2];
    std::copy(samples, samples + static_cast<size_t>(frameCount) * 2, buf);

    int32_t clipHandle = engine.allocateClipHandle();

    Command cmd{};
    cmd.type = CommandType::ScheduleClip;
    cmd.trackHandle = trackHandle;
    cmd.clipHandle = clipHandle;
    cmd.frameValueA = startFrame;
    cmd.frameValueB = sourceOffsetFrames;
    cmd.frameValueC = (lengthFrames > 0) ? lengthFrames : frameCount;
    cmd.floatValue = gainDb;
    cmd.boolValue = looping;
    cmd.fadeInFrames = fadeInFrames;
    cmd.fadeOutFrames = fadeOutFrames;
    cmd.buffer = DecodedBuffer{buf, frameCount};
    engine.enqueue(cmd);
    return clipHandle;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_removeClip(JNIEnv*, jobject, jint clipHandle) {
    Command cmd{}; cmd.type = CommandType::RemoveClip; cmd.clipHandle = clipHandle;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_moveClip(JNIEnv*, jobject, jint clipHandle, jlong newStartFrame) {
    Command cmd{}; cmd.type = CommandType::MoveClip; cmd.clipHandle = clipHandle; cmd.frameValueA = newStartFrame;
    AlmusAudioEngine::instance().enqueue(cmd);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_play(JNIEnv*, jobject) { AlmusAudioEngine::instance().play(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_pause(JNIEnv*, jobject) { AlmusAudioEngine::instance().pause(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_stop(JNIEnv*, jobject) { AlmusAudioEngine::instance().stop(); }

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_seekToFrame(JNIEnv*, jobject, jlong frame) {
    AlmusAudioEngine::instance().seekToFrame(frame);
}

JNIEXPORT jlong JNICALL
Java_com_almus_studio_audio_AudioEngine_getPlayheadFrame(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().getPlayheadFrame();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setLoopRegion(JNIEnv*, jobject, jlong startFrame, jlong endFrame, jboolean enabled) {
    AlmusAudioEngine::instance().setLoopRegion(startFrame, endFrame, enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_almus_studio_audio_AudioEngine_startRecording(JNIEnv* env, jobject, jint monitorTrackHandle, jstring outputFilePath) {
    std::string path = jstringToStdString(env, outputFilePath);
    return AlmusAudioEngine::instance().startRecording(monitorTrackHandle, path);
}

JNIEXPORT jstring JNICALL
Java_com_almus_studio_audio_AudioEngine_getLastRecordingError(JNIEnv* env, jobject) {
    return env->NewStringUTF(AlmusAudioEngine::instance().getLastRecordingError().c_str());
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_pauseRecording(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().pauseRecording();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_resumeRecording(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().resumeRecording();
}

JNIEXPORT jlong JNICALL
Java_com_almus_studio_audio_AudioEngine_stopRecording(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().stopRecording();
}

JNIEXPORT jfloat JNICALL
Java_com_almus_studio_audio_AudioEngine_getInputLevelDb(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().getInputLevelDb();
}

JNIEXPORT jboolean JNICALL
Java_com_almus_studio_audio_AudioEngine_isInputClipping(JNIEnv*, jobject) {
    return AlmusAudioEngine::instance().isInputClipping();
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setMasterVolumeDb(JNIEnv*, jobject, jfloat db) {
    AlmusAudioEngine::instance().setMasterVolumeDb(db);
}

JNIEXPORT jfloatArray JNICALL
Java_com_almus_studio_audio_AudioEngine_getMasterPeaksDb(JNIEnv* env, jobject) {
    float l = 0, r = 0;
    AlmusAudioEngine::instance().getMasterPeaksDb(&l, &r);
    jfloatArray result = env->NewFloatArray(2);
    jfloat values[2] = {l, r};
    env->SetFloatArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_setEffectChain(
        JNIEnv*, jobject, jint trackHandle,
        jboolean eqEnabled, jfloat eqLowGainDb, jfloat eqMidGainDb, jfloat eqHighGainDb,
        jboolean highPassEnabled, jfloat highPassCutoffHz,
        jboolean lowPassEnabled, jfloat lowPassCutoffHz,
        jboolean compressorEnabled, jfloat compressorThresholdDb, jfloat compressorRatio,
        jfloat compressorAttackMs, jfloat compressorReleaseMs,
        jboolean delayEnabled, jfloat delayTimeMs, jfloat delayFeedback, jfloat delayMix,
        jboolean reverbEnabled, jfloat reverbRoomSize, jfloat reverbMix) {
    almus::EffectChain chain{};
    chain.eqEnabled = eqEnabled;
    chain.eqLowGainDb = eqLowGainDb;
    chain.eqMidGainDb = eqMidGainDb;
    chain.eqHighGainDb = eqHighGainDb;
    chain.highPassEnabled = highPassEnabled;
    chain.highPassCutoffHz = highPassCutoffHz;
    chain.lowPassEnabled = lowPassEnabled;
    chain.lowPassCutoffHz = lowPassCutoffHz;
    chain.compressorEnabled = compressorEnabled;
    chain.compressorThresholdDb = compressorThresholdDb;
    chain.compressorRatio = compressorRatio;
    chain.compressorAttackMs = compressorAttackMs;
    chain.compressorReleaseMs = compressorReleaseMs;
    chain.delayEnabled = delayEnabled;
    chain.delayTimeMs = delayTimeMs;
    chain.delayFeedback = delayFeedback;
    chain.delayMix = delayMix;
    chain.reverbEnabled = reverbEnabled;
    chain.reverbRoomSize = reverbRoomSize;
    chain.reverbMix = reverbMix;
    AlmusAudioEngine::instance().setTrackEffectChain(trackHandle, chain);
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_startOfflineExport(JNIEnv* env, jobject, jstring outputFilePath, jlong totalFramesArg, jobject listener) {
    std::string path = jstringToStdString(env, outputFilePath);

    auto* bridge = new ExportListenerBridge();
    env->GetJavaVM(&bridge->vm);
    bridge->listenerGlobalRef = env->NewGlobalRef(listener);

    int64_t totalFrames = totalFramesArg;

    AlmusAudioEngine::instance().startOfflineExport(
        path, totalFrames,
        [bridge](float fraction) {
            JNIEnv* threadEnv = nullptr;
            if (bridge->vm->AttachCurrentThread(&threadEnv, nullptr) != JNI_OK) return;
            jclass cls = threadEnv->GetObjectClass(bridge->listenerGlobalRef);
            jmethodID mid = threadEnv->GetMethodID(cls, "onProgress", "(F)V");
            if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, fraction);
            bridge->vm->DetachCurrentThread();
        },
        [bridge](bool success, const std::string& outputPath, const std::string& error) {
            JNIEnv* threadEnv = nullptr;
            if (bridge->vm->AttachCurrentThread(&threadEnv, nullptr) == JNI_OK) {
                jclass cls = threadEnv->GetObjectClass(bridge->listenerGlobalRef);
                if (success) {
                    jmethodID mid = threadEnv->GetMethodID(cls, "onComplete", "(Ljava/lang/String;)V");
                    jstring jpath = threadEnv->NewStringUTF(outputPath.c_str());
                    if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, jpath);
                } else {
                    jmethodID mid = threadEnv->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
                    jstring jmsg = threadEnv->NewStringUTF(error.c_str());
                    if (mid) threadEnv->CallVoidMethod(bridge->listenerGlobalRef, mid, jmsg);
                }
                threadEnv->DeleteGlobalRef(bridge->listenerGlobalRef);
                bridge->vm->DetachCurrentThread();
            }
            delete bridge;
        });
}

JNIEXPORT void JNICALL
Java_com_almus_studio_audio_AudioEngine_cancelOfflineExport(JNIEnv*, jobject) {
    AlmusAudioEngine::instance().cancelOfflineExport();
}

// --- Offline vocal pitch correction -----------------------------------------
// Deliberately independent of AlmusAudioEngine: this reads a WAV file,
// processes it, and writes a new WAV file, entirely off the real-time audio
// path. Called from a background thread on the Kotlin side (see
// StudioViewModel.correctClipPitch), so there is no need to route this
// through the command queue at all.

JNIEXPORT jboolean JNICALL
Java_com_almus_studio_audio_AudioEngine_correctPitch(
        JNIEnv* env, jobject, jstring inputPath, jstring outputPath,
        jint rootPitchClass, jint scaleMask, jfloat strength, jfloat speedMs, jboolean hardMode) {
    std::string inPath = jstringToStdString(env, inputPath);
    std::string outPath = jstringToStdString(env, outputPath);

    almus::WavLoadResult loaded = almus::loadWavAsStereoFloat(inPath);
    if (!loaded.success || loaded.frameCount <= 0) return false;

    almus::dsp::PitchCorrectionParams params;
    params.rootPitchClass = rootPitchClass;
    params.scaleMask = static_cast<uint16_t>(scaleMask);
    params.strength = strength;
    params.speedMs = speedMs;
    params.hardMode = hardMode;

    // loadWavAsStereoFloat always converts to interleaved stereo (2 channels)
    // regardless of the source file's channel count -- see wav_file.h.
    auto corrected = almus::dsp::correctPitch(loaded.interleavedStereo, 2, loaded.frameCount,
                                               loaded.sourceSampleRate, params);

    return almus::writeWavFloat(outPath, corrected.data(), loaded.frameCount,
                                 loaded.sourceSampleRate, 2);
}

} // extern "C"
