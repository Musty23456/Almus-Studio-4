#pragma once

#include <atomic>
#include <array>
#include <cstdint>
#include <string>

#include "effect_chain.h"

namespace almus {

enum class CommandType {
    AddTrack,
    RemoveTrack,
    SetTrackVolume,
    SetTrackPan,
    SetTrackMute,
    SetTrackSolo,
    ScheduleClip,
    RemoveClip,
    MoveClip,
    Play,
    Pause,
    Stop,
    Seek,
    SetLoopRegion,
    SetMasterVolume,
    SetEffectChain
};

// A decoded audio buffer handed off from the loader thread to the audio
// thread. The audio thread takes ownership and frees it when the clip is
// removed or the engine shuts down.
struct DecodedBuffer {
    float* interleavedStereo = nullptr; // always converted to stereo on load
    int64_t frameCount = 0;
};

struct Command {
    CommandType type;
    int32_t trackHandle = -1;
    int32_t clipHandle = -1;
    float floatValue = 0.0f;
    bool boolValue = false;
    int64_t frameValueA = 0; // startFrame / seek target / loop start
    int64_t frameValueB = 0; // sourceOffsetFrames / loop end
    int64_t frameValueC = 0; // lengthFrames
    int64_t fadeInFrames = 0;
    int64_t fadeOutFrames = 0;
    DecodedBuffer buffer{};
    EffectChain effectChain{}; // only used by SetEffectChain; ~80 bytes, fine to carry unconditionally
};

// Single-producer (UI/JNI thread), single-consumer (audio thread) ring buffer.
// Real-time safe: push/pop never allocate and never block.
class CommandQueue {
public:
    static constexpr size_t kCapacity = 256;

    bool push(const Command& cmd) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t nextHead = (head + 1) % kCapacity;
        if (nextHead == tail_.load(std::memory_order_acquire)) {
            return false; // full — caller should retry; queue is generously sized
        }
        buffer_[head] = cmd;
        head_.store(nextHead, std::memory_order_release);
        return true;
    }

    bool pop(Command* out) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) {
            return false; // empty
        }
        *out = buffer_[tail];
        tail_.store((tail + 1) % kCapacity, std::memory_order_release);
        return true;
    }

private:
    std::array<Command, kCapacity> buffer_{};
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

} // namespace almus
