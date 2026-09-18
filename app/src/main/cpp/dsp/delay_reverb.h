#pragma once

#include <cstdint>
#include <vector>

namespace almus::dsp {

// Single-channel feedback delay line with a fixed maximum delay, sized once
// at init() (not on the real-time thread's per-buffer path).
class DelayLine {
public:
    void init(int sampleRate, float maxDelaySeconds) {
        size_t size = static_cast<size_t>(sampleRate * maxDelaySeconds) + 1;
        buffer_.assign(size, 0.0f);
        writeIndex_ = 0;
    }

    float process(float input, float delaySamples, float feedback) {
        if (buffer_.empty()) return input;
        auto delaySamplesInt = static_cast<size_t>(delaySamples) % buffer_.size();
        size_t readIndex = (writeIndex_ + buffer_.size() - delaySamplesInt) % buffer_.size();
        float delayed = buffer_[readIndex];
        buffer_[writeIndex_] = input + delayed * feedback;
        writeIndex_ = (writeIndex_ + 1) % buffer_.size();
        return delayed;
    }

private:
    std::vector<float> buffer_;
    size_t writeIndex_ = 0;
};

// One feedback comb filter with a damping low-pass in the feedback path --
// the classic building block of a Schroeder/Freeverb-style reverb.
class CombFilter {
public:
    void init(int sampleRate, float delayMs) {
        size_t size = static_cast<size_t>(sampleRate * delayMs / 1000.0f) + 1;
        buffer_.assign(size, 0.0f);
        index_ = 0;
        dampState_ = 0.0f;
    }

    float process(float input, float feedback, float damp) {
        if (buffer_.empty()) return input;
        float output = buffer_[index_];
        dampState_ = output * (1.0f - damp) + dampState_ * damp;
        buffer_[index_] = input + dampState_ * feedback;
        index_ = (index_ + 1) % buffer_.size();
        return output;
    }

private:
    std::vector<float> buffer_;
    size_t index_ = 0;
    float dampState_ = 0.0f;
};

class AllpassFilter {
public:
    void init(int sampleRate, float delayMs) {
        size_t size = static_cast<size_t>(sampleRate * delayMs / 1000.0f) + 1;
        buffer_.assign(size, 0.0f);
        index_ = 0;
    }

    float process(float input, float feedback = 0.5f) {
        if (buffer_.empty()) return input;
        float bufOut = buffer_[index_];
        float output = -input + bufOut;
        buffer_[index_] = input + bufOut * feedback;
        index_ = (index_ + 1) % buffer_.size();
        return output;
    }

private:
    std::vector<float> buffer_;
    size_t index_ = 0;
};

// A small, deliberately modest algorithmic reverb: four parallel damped comb
// filters summed, then two series allpass filters to diffuse the tail. This
// is the well-known Schroeder/Freeverb *structure* reimplemented from the
// public technique, not a copy of any specific codebase. It trades ultimate
// realism for being cheap enough to run per-track on a phone.
class SimpleReverb {
public:
    void init(int sampleRate) {
        static constexpr float kCombDelaysMs[4] = {29.7f, 37.1f, 41.1f, 43.7f};
        static constexpr float kAllpassDelaysMs[2] = {5.0f, 1.7f};
        for (int i = 0; i < 4; i++) combs_[i].init(sampleRate, kCombDelaysMs[i]);
        for (int i = 0; i < 2; i++) allpasses_[i].init(sampleRate, kAllpassDelaysMs[i]);
    }

    float process(float input, float roomSize, float damp) {
        float feedback = 0.28f + roomSize * 0.7f; // roomSize in [0,1] -> feedback in [0.28, 0.98]
        float out = 0.0f;
        for (auto& comb : combs_) out += comb.process(input, feedback, damp);
        out *= 0.25f;
        for (auto& allpass : allpasses_) out = allpass.process(out);
        return out;
    }

private:
    CombFilter combs_[4];
    AllpassFilter allpasses_[2];
};

} // namespace almus::dsp
