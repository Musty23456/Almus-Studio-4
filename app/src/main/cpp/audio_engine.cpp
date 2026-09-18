#include "audio_engine.h"

#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "AlmusAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace almus {

namespace {
float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
float linearToDb(float lin) { return lin <= 0.0f ? -96.0f : 20.0f * std::log10(lin); }
}

AlmusAudioEngine& AlmusAudioEngine::instance() {
    static AlmusAudioEngine engine;
    return engine;
}

void AlmusAudioEngine::init(int32_t sampleRate, int32_t framesPerBurst) {
    sampleRate_ = sampleRate;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(sampleRate)
        ->setFramesPerDataCallback(framesPerBurst)
        ->setCallback(this);

    oboe::Result result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        // Exclusive-mode streams are only honored on a subset of devices
        // (typically ones with a dedicated low-latency audio HAL path) --
        // most phones reject the request outright. Retrying with Shared
        // mode is the standard Oboe fallback and is what actually gets
        // playback working on the majority of real devices.
        LOGE("Exclusive output stream failed (%s), retrying with Shared mode", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(outputStream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open output stream even with Shared mode: %s", oboe::convertToText(result));
            return;
        }
    }
    outputStream_->requestStart();
}

void AlmusAudioEngine::shutdown() {
    stop();
    cancelOfflineExport();
    if (exportThread_.joinable()) exportThread_.join();
    if (recording_.load()) stopRecording();
    if (outputStream_) {
        outputStream_->stop();
        outputStream_->close();
        outputStream_.reset();
    }
    for (auto& track : tracks_) {
        for (auto& clip : track.clips) {
            delete[] clip.buffer.interleavedStereo;
        }
    }
    tracks_.clear();
}

void AlmusAudioEngine::enqueue(const Command& cmd) {
    if (!commandQueue_.push(cmd)) {
        LOGE("Command queue full, dropping command type=%d", static_cast<int>(cmd.type));
    }
}

void AlmusAudioEngine::play() { playing_.store(true, std::memory_order_relaxed); }
void AlmusAudioEngine::pause() { playing_.store(false, std::memory_order_relaxed); }
void AlmusAudioEngine::stop() {
    playing_.store(false, std::memory_order_relaxed);
    playheadFrame_.store(0, std::memory_order_relaxed);
}
void AlmusAudioEngine::seekToFrame(int64_t frame) {
    playheadFrame_.store(std::max<int64_t>(0, frame), std::memory_order_relaxed);
}
void AlmusAudioEngine::setLoopRegion(int64_t startFrame, int64_t endFrame, bool enabled) {
    loopStartFrame_.store(startFrame, std::memory_order_relaxed);
    loopEndFrame_.store(endFrame, std::memory_order_relaxed);
    loopEnabled_.store(enabled, std::memory_order_relaxed);
}

void AlmusAudioEngine::getMasterPeaksDb(float* leftOut, float* rightOut) const {
    *leftOut = masterPeakLeftDb_.load(std::memory_order_relaxed);
    *rightOut = masterPeakRightDb_.load(std::memory_order_relaxed);
}

void AlmusAudioEngine::setTrackEffectChain(int32_t trackHandle, const EffectChain& chain) {
    Command cmd{};
    cmd.type = CommandType::SetEffectChain;
    cmd.trackHandle = trackHandle;
    cmd.effectChain = chain;
    enqueue(cmd);
}

// --- Command draining: runs on the audio thread, at the start of each callback ---

void AlmusAudioEngine::drainCommands() {
    Command cmd;
    while (commandQueue_.pop(&cmd)) {
        switch (cmd.type) {
            case CommandType::AddTrack: {
                std::lock_guard<std::mutex> lock(structureMutex_);
                Track t;
                t.handle = cmd.trackHandle; // pre-allocated via allocateTrackHandle()
                t.effects.init(sampleRate_);
                tracks_.push_back(std::move(t));
                break;
            }
            case CommandType::RemoveTrack: {
                std::lock_guard<std::mutex> lock(structureMutex_);
                auto it = std::remove_if(tracks_.begin(), tracks_.end(),
                    [&](Track& t) { return t.handle == cmd.trackHandle; });
                for (auto d = it; d != tracks_.end(); ++d)
                    for (auto& clip : d->clips) delete[] clip.buffer.interleavedStereo;
                tracks_.erase(it, tracks_.end());
                break;
            }
            case CommandType::SetTrackVolume:
                for (auto& t : tracks_) if (t.handle == cmd.trackHandle) t.volumeDb.store(cmd.floatValue);
                break;
            case CommandType::SetTrackPan:
                for (auto& t : tracks_) if (t.handle == cmd.trackHandle) t.pan.store(cmd.floatValue);
                break;
            case CommandType::SetTrackMute:
                for (auto& t : tracks_) if (t.handle == cmd.trackHandle) t.muted.store(cmd.boolValue);
                break;
            case CommandType::SetTrackSolo:
                for (auto& t : tracks_) if (t.handle == cmd.trackHandle) t.solo.store(cmd.boolValue);
                break;
            case CommandType::ScheduleClip: {
                std::lock_guard<std::mutex> lock(structureMutex_);
                for (auto& t : tracks_) {
                    if (t.handle == cmd.trackHandle) {
                        Clip clip;
                        clip.handle = cmd.clipHandle;
                        clip.trackHandle = cmd.trackHandle;
                        clip.startFrame = cmd.frameValueA;
                        clip.sourceOffsetFrames = cmd.frameValueB;
                        clip.lengthFrames = cmd.frameValueC;
                        clip.gainDb = cmd.floatValue;
                        clip.looping = cmd.boolValue;
                        clip.fadeInFrames = cmd.fadeInFrames;
                        clip.fadeOutFrames = cmd.fadeOutFrames;
                        clip.buffer = cmd.buffer; // ownership transferred
                        t.clips.push_back(clip);
                        break;
                    }
                }
                break;
            }
            case CommandType::RemoveClip: {
                std::lock_guard<std::mutex> lock(structureMutex_);
                for (auto& t : tracks_) {
                    auto it = std::remove_if(t.clips.begin(), t.clips.end(),
                        [&](Clip& c) { return c.handle == cmd.clipHandle; });
                    for (auto d = it; d != t.clips.end(); ++d) delete[] d->buffer.interleavedStereo;
                    t.clips.erase(it, t.clips.end());
                }
                break;
            }
            case CommandType::MoveClip: {
                for (auto& t : tracks_)
                    for (auto& c : t.clips)
                        if (c.handle == cmd.clipHandle) c.startFrame = cmd.frameValueA;
                break;
            }
            case CommandType::Play: playing_.store(true); break;
            case CommandType::Pause: playing_.store(false); break;
            case CommandType::Stop: playing_.store(false); playheadFrame_.store(0); break;
            case CommandType::Seek: playheadFrame_.store(cmd.frameValueA); break;
            case CommandType::SetLoopRegion:
                loopStartFrame_.store(cmd.frameValueA);
                loopEndFrame_.store(cmd.frameValueB);
                loopEnabled_.store(cmd.boolValue);
                break;
            case CommandType::SetMasterVolume: masterVolumeDb_.store(cmd.floatValue); break;
            case CommandType::SetEffectChain: {
                for (auto& t : tracks_) if (t.handle == cmd.trackHandle) t.effectParams = cmd.effectChain;
                break;
            }
        }
    }
}

// --- Mixing ---------------------------------------------------------------

void AlmusAudioEngine::mixInto(float* outputInterleavedStereo, int32_t numFrames) {
    std::fill(outputInterleavedStereo, outputInterleavedStereo + numFrames * 2, 0.0f);

    if (!playing_.load(std::memory_order_relaxed)) return;

    // thread_local: mixInto() runs on both the real-time output callback
    // thread and the offline-export worker thread, potentially concurrently.
    // A shared buffer would let them corrupt each other's in-progress mix;
    // each thread gets its own, sized once on first use (never resized on
    // the steady-state real-time path afterward).
    thread_local std::vector<float> trackScratchBuffer(static_cast<size_t>(kMaxFramesPerCallback) * 2, 0.0f);

    // Defensive clamp: trackScratchBuffer is sized to kMaxFramesPerCallback
    // frames. In the extremely unlikely case a device reports a callback
    // larger than that, we still process what fits rather than touch memory
    // out of bounds.
    const int32_t framesToProcess = std::min(numFrames, kMaxFramesPerCallback);

    const int64_t playhead = playheadFrame_.load(std::memory_order_relaxed);

    bool anySolo = std::any_of(tracks_.begin(), tracks_.end(),
                                [](const Track& t) { return t.solo.load(); });

    for (auto& track : tracks_) {
        if (track.muted.load()) continue;
        if (anySolo && !track.solo.load()) continue;

        std::fill(trackScratchBuffer.begin(), trackScratchBuffer.begin() + framesToProcess * 2, 0.0f);

        for (auto& clip : track.clips) {
            const float clipGain = dbToLinear(clip.gainDb);
            for (int32_t i = 0; i < framesToProcess; i++) {
                int64_t timelineFrame = playhead + i;
                int64_t relative = timelineFrame - clip.startFrame;
                if (relative < 0) continue;

                int64_t srcFrame;
                if (clip.looping && clip.lengthFrames > 0) {
                    srcFrame = clip.sourceOffsetFrames + (relative % clip.lengthFrames);
                } else {
                    if (relative >= clip.lengthFrames) continue;
                    srcFrame = clip.sourceOffsetFrames + relative;
                }
                if (srcFrame < 0 || srcFrame >= clip.buffer.frameCount) continue;

                // Linear fade in/out, evaluated against position within the
                // clip (not the looped source position) so looping clips
                // fade once at the very start/end of the whole loop run.
                float fadeGain = 1.0f;
                if (clip.fadeInFrames > 0 && relative < clip.fadeInFrames) {
                    fadeGain *= static_cast<float>(relative) / static_cast<float>(clip.fadeInFrames);
                }
                if (clip.fadeOutFrames > 0 && !clip.looping) {
                    int64_t framesFromEnd = clip.lengthFrames - relative;
                    if (framesFromEnd < clip.fadeOutFrames) {
                        fadeGain *= std::max(0.0f, static_cast<float>(framesFromEnd) /
                                                        static_cast<float>(clip.fadeOutFrames));
                    }
                }

                const float srcL = clip.buffer.interleavedStereo[srcFrame * 2] * clipGain * fadeGain;
                const float srcR = clip.buffer.interleavedStereo[srcFrame * 2 + 1] * clipGain * fadeGain;

                trackScratchBuffer[i * 2] += srcL;
                trackScratchBuffer[i * 2 + 1] += srcR;
            }
        }

        // Effects process the track's fully-summed signal, not individual
        // clips -- e.g. a compressor reacting to two overlapping clips
        // together is the behavior a real mixer would have.
        track.effects.process(trackScratchBuffer.data(), framesToProcess, track.effectParams);

        const float volumeLin = dbToLinear(track.volumeDb.load());
        const float pan = std::clamp(track.pan.load(), -1.0f, 1.0f);
        const float leftGain = volumeLin * std::cos((pan + 1.0f) * 0.25f * static_cast<float>(M_PI));
        const float rightGain = volumeLin * std::sin((pan + 1.0f) * 0.25f * static_cast<float>(M_PI));

        for (int32_t i = 0; i < framesToProcess; i++) {
            outputInterleavedStereo[i * 2] += trackScratchBuffer[i * 2] * leftGain;
            outputInterleavedStereo[i * 2 + 1] += trackScratchBuffer[i * 2 + 1] * rightGain;
        }
    }

    const float masterLin = dbToLinear(masterVolumeDb_.load());
    float peakL = 0.0f, peakR = 0.0f;
    for (int32_t i = 0; i < framesToProcess; i++) {
        float l = outputInterleavedStereo[i * 2] * masterLin;
        float r = outputInterleavedStereo[i * 2 + 1] * masterLin;
        // Soft-clip to avoid harsh digital overs while still signalling the
        // overage via the peak meter, per the "avoid clipping where possible
        // and provide warnings" requirement.
        l = std::clamp(l, -1.0f, 1.0f);
        r = std::clamp(r, -1.0f, 1.0f);
        outputInterleavedStereo[i * 2] = l;
        outputInterleavedStereo[i * 2 + 1] = r;
        peakL = std::max(peakL, std::fabs(l));
        peakR = std::max(peakR, std::fabs(r));
    }
    masterPeakLeftDb_.store(linearToDb(peakL));
    masterPeakRightDb_.store(linearToDb(peakR));
}

void AlmusAudioEngine::advancePlayhead(int32_t numFrames) {
    if (!playing_.load(std::memory_order_relaxed)) return;
    int64_t newFrame = playheadFrame_.load(std::memory_order_relaxed) + numFrames;

    if (loopEnabled_.load(std::memory_order_relaxed)) {
        int64_t loopStart = loopStartFrame_.load(std::memory_order_relaxed);
        int64_t loopEnd = loopEndFrame_.load(std::memory_order_relaxed);
        if (loopEnd > loopStart && newFrame >= loopEnd) {
            newFrame = loopStart + ((newFrame - loopEnd) % (loopEnd - loopStart));
        }
    }
    playheadFrame_.store(newFrame, std::memory_order_relaxed);
}

oboe::DataCallbackResult AlmusAudioEngine::onAudioReady(oboe::AudioStream* /*stream*/,
                                                         void* audioData, int32_t numFrames) {
    drainCommands();
    auto* out = static_cast<float*>(audioData);
    mixInto(out, numFrames);
    advancePlayhead(numFrames);
    return oboe::DataCallbackResult::Continue;
}

// --- Recording --------------------------------------------------------------

oboe::DataCallbackResult AlmusAudioEngine::InputCallback::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    if (!engine_->recording_.load(std::memory_order_relaxed) ||
        engine_->recordingPaused_.load(std::memory_order_relaxed)) {
        return oboe::DataCallbackResult::Continue;
    }

    auto* samples = static_cast<float*>(audioData);
    int channels = stream->getChannelCount();

    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames * channels; i++) {
        peak = std::max(peak, std::fabs(samples[i]));
    }
    engine_->inputLevelDb_.store(linearToDb(peak), std::memory_order_relaxed);
    engine_->inputClipping_.store(peak >= 0.999f, std::memory_order_relaxed);

    if (engine_->recordWriter_) {
        engine_->recordWriter_->writeFrames(samples, numFrames);
    }
    return oboe::DataCallbackResult::Continue;
}

bool AlmusAudioEngine::startRecording(int32_t monitorTrackHandle, const std::string& outputFilePath) {
    if (recording_.load()) return false;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate_)
        ->setCallback(&inputCallback_);

    oboe::Result result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        // Same Exclusive -> Shared fallback as the output stream in init():
        // exclusive-mode *input* capture is supported on even fewer devices
        // than exclusive output, so without this fallback recording simply
        // never works on most phones.
        LOGE("Exclusive input stream failed (%s), retrying with Shared mode", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(inputStream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open input stream even with Shared mode: %s", oboe::convertToText(result));
            lastRecordingError_ = std::string("This device would not open a microphone input stream (") +
                                   oboe::convertToText(result) + ").";
            return false;
        }
    }
    lastRecordingError_.clear();

    recordWriter_ = std::make_unique<WavWriter>();
    if (!recordWriter_->open(outputFilePath, sampleRate_, inputStream_->getChannelCount())) {
        LOGE("Failed to open recording output file: %s", outputFilePath.c_str());
        lastRecordingError_ = "Could not create the recording file on device storage.";
        inputStream_->close();
        inputStream_.reset();
        recordWriter_.reset();
        return false;
    }

    recordMonitorTrackHandle_ = monitorTrackHandle;
    recordingPaused_.store(false);
    recording_.store(true);

    if (inputStream_->requestStart() != oboe::Result::OK) {
        lastRecordingError_ = "The audio input stream could not be started.";
        recording_.store(false);
        recordWriter_->close();
        recordWriter_.reset();
        inputStream_->close();
        inputStream_.reset();
        return false;
    }
    return true;
}

void AlmusAudioEngine::pauseRecording() { recordingPaused_.store(true); }
void AlmusAudioEngine::resumeRecording() { recordingPaused_.store(false); }

int64_t AlmusAudioEngine::stopRecording() {
    if (!recording_.load()) return 0;
    recording_.store(false);
    if (inputStream_) {
        inputStream_->stop();
        inputStream_->close();
        inputStream_.reset();
    }
    int64_t frames = 0;
    if (recordWriter_) {
        frames = recordWriter_->framesWritten();
        recordWriter_->close();
        recordWriter_.reset();
    }
    return frames;
}

// --- Offline export ----------------------------------------------------------
//
// Renders the full arrangement to a WAV file on a dedicated worker thread, in
// chunks, calling the *same* per-frame mixing math the real-time callback
// uses (kept in mixInto) so the export is guaranteed to sound like what the
// user heard while editing. structureMutex_ is held only long enough to read
// tracks_ each chunk, never across the whole render, so a short UI edit made
// mid-export cannot corrupt memory (though it may or may not be reflected in
// the render depending on timing -- acceptable for a Phase 1 "render current
// arrangement" export).

void AlmusAudioEngine::startOfflineExport(const std::string& outputFilePath, int64_t totalFrames,
                                           ExportProgressFn onProgress, ExportDoneFn onDone) {
    if (exportThread_.joinable()) {
        exportThread_.join(); // a previous export already finished; reap it
    }
    exportCancelled_.store(false);

    exportThread_ = std::thread([this, outputFilePath, totalFrames, onProgress, onDone]() {
        constexpr int32_t kChunkFrames = 4096;
        WavWriter writer;
        if (!writer.open(outputFilePath, sampleRate_, 2)) {
            if (onDone) onDone(false, outputFilePath, "Could not create export file");
            return;
        }

        std::vector<float> chunk(kChunkFrames * 2);
        int64_t rendered = 0;
        while (rendered < totalFrames) {
            if (exportCancelled_.load()) {
                writer.close();
                if (onDone) onDone(false, outputFilePath, "Export cancelled");
                return;
            }
            int32_t framesThisChunk = static_cast<int32_t>(
                std::min<int64_t>(kChunkFrames, totalFrames - rendered));

            {
                std::lock_guard<std::mutex> lock(structureMutex_);
                // Known Phase 1 limitation: this briefly repurposes the shared
                // playhead/playing atomics, so exporting while transport is
                // also actively playing can cause a few frames of audible
                // playhead jitter on the live output. The UI should disable
                // transport controls while an export is in progress until a
                // fully independent export playhead is added in a later phase.
                int64_t savedPlayhead = playheadFrame_.exchange(rendered);
                bool savedPlaying = playing_.exchange(true);
                mixInto(chunk.data(), framesThisChunk);
                playheadFrame_.store(savedPlayhead);
                playing_.store(savedPlaying);
            }

            writer.writeFrames(chunk.data(), framesThisChunk);
            rendered += framesThisChunk;
            if (onProgress) onProgress(static_cast<float>(rendered) / static_cast<float>(totalFrames));
        }

        writer.close();
        if (onDone) onDone(true, outputFilePath, "");
    });
}

void AlmusAudioEngine::cancelOfflineExport() {
    exportCancelled_.store(true);
}

} // namespace almus
