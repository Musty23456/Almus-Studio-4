#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "command_queue.h"
#include "effect_chain.h"
#include "track_effects.h"
#include "wav_file.h"

namespace almus {

struct Clip {
    int32_t handle = -1;
    int32_t trackHandle = -1;
    int64_t startFrame = 0;
    int64_t sourceOffsetFrames = 0;
    int64_t lengthFrames = 0;
    float gainDb = 0.0f;
    bool looping = false;
    int64_t fadeInFrames = 0;  // linear fade at the start of the clip, in frames
    int64_t fadeOutFrames = 0; // linear fade at the end of the clip, in frames
    DecodedBuffer buffer{}; // owned by this clip; freed on removal
};

struct Track {
    int32_t handle = -1;
    std::string id;
    std::atomic<float> volumeDb{0.0f};
    std::atomic<float> pan{0.0f};
    std::atomic<bool> muted{false};
    std::atomic<bool> solo{false};
    std::vector<Clip> clips; // audio-thread-owned; mutated only via CommandQueue
    EffectChain effectParams;  // updated only via SetEffectChain command
    TrackEffects effects;      // persistent DSP state; effects.init() called on AddTrack

    // std::atomic has no copy/move constructor, which by default deletes
    // Track's move constructor too -- and std::vector<Track> needs Track to
    // be move-constructible to grow (reallocate) or to compact after erase.
    // These are hand-written to move the *value* of each atomic rather than
    // the atomic object itself.
    Track() = default;
    Track(const Track&) = delete;
    Track& operator=(const Track&) = delete;

    Track(Track&& other) noexcept
        : handle(other.handle),
          id(std::move(other.id)),
          volumeDb(other.volumeDb.load(std::memory_order_relaxed)),
          pan(other.pan.load(std::memory_order_relaxed)),
          muted(other.muted.load(std::memory_order_relaxed)),
          solo(other.solo.load(std::memory_order_relaxed)),
          clips(std::move(other.clips)),
          effectParams(other.effectParams),
          effects(std::move(other.effects)) {}

    Track& operator=(Track&& other) noexcept {
        handle = other.handle;
        id = std::move(other.id);
        volumeDb.store(other.volumeDb.load(std::memory_order_relaxed), std::memory_order_relaxed);
        pan.store(other.pan.load(std::memory_order_relaxed), std::memory_order_relaxed);
        muted.store(other.muted.load(std::memory_order_relaxed), std::memory_order_relaxed);
        solo.store(other.solo.load(std::memory_order_relaxed), std::memory_order_relaxed);
        clips = std::move(other.clips);
        effectParams = other.effectParams;
        effects = std::move(other.effects);
        return *this;
    }
};

// Real-time playback + mixing callback. All track/clip state is only ever
// touched here (on the audio thread) or in drainCommands(), which also runs
// on the audio thread at the top of each callback -- this is what makes the
// engine real-time safe despite being controlled from Kotlin/JNI.
class AlmusAudioEngine : public oboe::AudioStreamCallback {
public:
    static AlmusAudioEngine& instance();

    void init(int32_t sampleRate, int32_t framesPerBurst);
    void shutdown();

    // Enqueue-only API, safe to call from any thread. See command_queue.h.
    void enqueue(const Command& cmd);

    // Handles are allocated synchronously (thread-safe atomic increment) so
    // JNI calls like addTrack()/scheduleClip() can hand a usable handle back
    // to Kotlin immediately, even though the corresponding state change is
    // applied asynchronously on the audio thread a few milliseconds later.
    int32_t allocateTrackHandle() { return nextTrackHandleAtomic_.fetch_add(1); }
    int32_t allocateClipHandle() { return nextClipHandleAtomic_.fetch_add(1); }

    void play();
    void pause();
    void stop();
    void seekToFrame(int64_t frame);
    int64_t getPlayheadFrame() const { return playheadFrame_.load(std::memory_order_relaxed); }
    void setLoopRegion(int64_t startFrame, int64_t endFrame, bool enabled);

    // Offline (non-real-time) render of the current arrangement to a WAV file.
    // Runs on its own worker thread; reports progress via callback. Structural
    // track/clip changes (add/remove) briefly take structureMutex_ so they
    // cannot race with an in-progress export -- everything on the per-sample
    // hot path (mixInto) remains lock-free.
    using ExportProgressFn = std::function<void(float)>;
    using ExportDoneFn = std::function<void(bool success, const std::string& outputPath, const std::string& error)>;
    void startOfflineExport(const std::string& outputFilePath, int64_t totalFrames,
                             ExportProgressFn onProgress, ExportDoneFn onDone);
    void cancelOfflineExport();

    bool startRecording(int32_t monitorTrackHandle, const std::string& outputFilePath);
    void pauseRecording();
    void resumeRecording();
    int64_t stopRecording();
    float getInputLevelDb() const { return inputLevelDb_.load(std::memory_order_relaxed); }
    bool isInputClipping() const { return inputClipping_.load(std::memory_order_relaxed); }
    // Human-readable reason the most recent startRecording() call failed, or
    // empty if it succeeded (or hasn't been called yet). Not thread-safe
    // against a concurrent startRecording() call, which is fine -- Kotlin
    // only reads it immediately after startRecording() returns false, on the
    // same calling thread.
    std::string getLastRecordingError() const { return lastRecordingError_; }

    void setMasterVolumeDb(float db) { masterVolumeDb_.store(db, std::memory_order_relaxed); }
    void getMasterPeaksDb(float* leftOut, float* rightOut) const;

    int32_t getSampleRate() const { return sampleRate_; }

    void setTrackEffectChain(int32_t trackHandle, const EffectChain& chain);

    // oboe::AudioStreamCallback for OUTPUT stream
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                           int32_t numFrames) override;

private:
    AlmusAudioEngine() = default;

    void drainCommands();
    void mixInto(float* outputInterleavedStereo, int32_t numFrames);
    void advancePlayhead(int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::shared_ptr<oboe::AudioStream> inputStream_;

    CommandQueue commandQueue_;
    std::vector<Track> tracks_; // audio-thread owned
    std::atomic<int32_t> nextTrackHandleAtomic_{0};
    std::atomic<int32_t> nextClipHandleAtomic_{0};

    // Note: the per-track scratch buffer used while mixing (see mixInto() in
    // audio_engine.cpp) is thread_local rather than a member here, because
    // mixInto() is called from *two* different threads that can run
    // concurrently: the real-time output callback thread, and the offline
    // export worker thread. A shared member buffer would let them corrupt
    // each other's in-progress mix.
    static constexpr int32_t kMaxFramesPerCallback = 8192;

    std::atomic<bool> playing_{false};
    std::atomic<int64_t> playheadFrame_{0};
    std::atomic<bool> loopEnabled_{false};
    std::atomic<int64_t> loopStartFrame_{0};
    std::atomic<int64_t> loopEndFrame_{0};
    std::atomic<float> masterVolumeDb_{0.0f};
    std::atomic<float> masterPeakLeftDb_{-96.0f};
    std::atomic<float> masterPeakRightDb_{-96.0f};

    int32_t sampleRate_ = 48000;

    // --- Recording state (separate input stream + writer thread) ---
    std::unique_ptr<WavWriter> recordWriter_;
    std::atomic<bool> recording_{false};
    std::atomic<bool> recordingPaused_{false};
    std::atomic<float> inputLevelDb_{-96.0f};
    std::atomic<bool> inputClipping_{false};
    int32_t recordMonitorTrackHandle_ = -1;
    std::string lastRecordingError_;

    class InputCallback : public oboe::AudioStreamCallback {
    public:
        explicit InputCallback(AlmusAudioEngine* engine) : engine_(engine) {}
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                               int32_t numFrames) override;
    private:
        AlmusAudioEngine* engine_;
    };
    InputCallback inputCallback_{this};

    // --- Offline export state (runs on its own thread, not the RT thread) ---
    std::thread exportThread_;
    std::atomic<bool> exportCancelled_{false};

    // Guards structural (add/remove track or clip) mutations of tracks_ so an
    // in-progress offline export can safely iterate the same vector. Never
    // held during per-buffer mixing on the real-time thread.
    mutable std::mutex structureMutex_;
};

} // namespace almus
