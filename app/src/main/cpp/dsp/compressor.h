#pragma once

#include <algorithm>
#include <cmath>

namespace almus::dsp {

// Simple feed-forward compressor: peak-detects both channels together (so
// stereo material doesn't drift off-center), smooths the gain-reduction
// envelope with independent attack/release times, and applies the same
// reduction to both channels. Not a mastering-grade limiter -- see
// ROADMAP.md for the separate Limiter/NoiseGate items still pending.
class Compressor {
public:
    void process(float left, float right,
                 float thresholdDb, float ratio, float attackMs, float releaseMs,
                 int sampleRate, float* outLeft, float* outRight) {
        float peak = std::max(std::fabs(left), std::fabs(right));
        float inputDb = peak <= 0.0f ? -96.0f : 20.0f * std::log10(peak);
        float overDb = inputDb - thresholdDb;
        float targetReductionDb = overDb > 0.0f ? overDb * (1.0f - 1.0f / std::max(1.0f, ratio)) : 0.0f;
        float targetGainDb = -targetReductionDb;

        float coeff = targetGainDb < envelopeDb_
            ? timeConstant(attackMs, sampleRate)
            : timeConstant(releaseMs, sampleRate);
        envelopeDb_ = coeff * envelopeDb_ + (1.0f - coeff) * targetGainDb;

        float gainLin = std::pow(10.0f, envelopeDb_ / 20.0f);
        *outLeft = left * gainLin;
        *outRight = right * gainLin;
    }

private:
    float envelopeDb_ = 0.0f;

    static float timeConstant(float ms, int sampleRate) {
        float seconds = std::max(0.0001f, ms * 0.001f);
        return std::exp(-1.0f / (seconds * sampleRate));
    }
};

} // namespace almus::dsp
