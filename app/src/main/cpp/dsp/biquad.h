#pragma once

#include <cmath>

namespace almus::dsp {

struct BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

// Direct Form I biquad. One instance filters one channel; stereo effects keep
// two of these (L/R) so the two channels never share filter history.
class BiquadFilter {
public:
    void setCoeffs(const BiquadCoeffs& c) { coeffs_ = c; }

    float process(float x) {
        float y = coeffs_.b0 * x + coeffs_.b1 * x1_ + coeffs_.b2 * x2_
                   - coeffs_.a1 * y1_ - coeffs_.a2 * y2_;
        x2_ = x1_; x1_ = x;
        y2_ = y1_; y1_ = y;
        return y;
    }

    void reset() { x1_ = x2_ = y1_ = y2_ = 0.0f; }

private:
    BiquadCoeffs coeffs_{};
    float x1_ = 0.0f, x2_ = 0.0f, y1_ = 0.0f, y2_ = 0.0f;
};

// Standard RBJ "Audio EQ Cookbook" formulas (well-known public-domain DSP
// reference, not any single codebase's copyrighted text) normalized so a0 = 1.
namespace biquad {

inline BiquadCoeffs lowPass(float sampleRate, float cutoffHz, float q) {
    float w0 = 2.0f * static_cast<float>(M_PI) * cutoffHz / sampleRate;
    float cosw0 = std::cos(w0), sinw0 = std::sin(w0);
    float alpha = sinw0 / (2.0f * q);
    float a0 = 1.0f + alpha;
    BiquadCoeffs c;
    c.b0 = ((1.0f - cosw0) / 2.0f) / a0;
    c.b1 = (1.0f - cosw0) / a0;
    c.b2 = c.b0;
    c.a1 = (-2.0f * cosw0) / a0;
    c.a2 = (1.0f - alpha) / a0;
    return c;
}

inline BiquadCoeffs highPass(float sampleRate, float cutoffHz, float q) {
    float w0 = 2.0f * static_cast<float>(M_PI) * cutoffHz / sampleRate;
    float cosw0 = std::cos(w0), sinw0 = std::sin(w0);
    float alpha = sinw0 / (2.0f * q);
    float a0 = 1.0f + alpha;
    BiquadCoeffs c;
    c.b0 = ((1.0f + cosw0) / 2.0f) / a0;
    c.b1 = (-(1.0f + cosw0)) / a0;
    c.b2 = c.b0;
    c.a1 = (-2.0f * cosw0) / a0;
    c.a2 = (1.0f - alpha) / a0;
    return c;
}

inline BiquadCoeffs peaking(float sampleRate, float freqHz, float gainDb, float q) {
    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * static_cast<float>(M_PI) * freqHz / sampleRate;
    float cosw0 = std::cos(w0), sinw0 = std::sin(w0);
    float alpha = sinw0 / (2.0f * q);
    float a0 = 1.0f + alpha / A;
    BiquadCoeffs c;
    c.b0 = (1.0f + alpha * A) / a0;
    c.b1 = (-2.0f * cosw0) / a0;
    c.b2 = (1.0f - alpha * A) / a0;
    c.a1 = (-2.0f * cosw0) / a0;
    c.a2 = (1.0f - alpha / A) / a0;
    return c;
}

inline BiquadCoeffs lowShelf(float sampleRate, float freqHz, float gainDb, float shelfSlope = 1.0f) {
    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * static_cast<float>(M_PI) * freqHz / sampleRate;
    float cosw0 = std::cos(w0), sinw0 = std::sin(w0);
    float alpha = sinw0 / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / shelfSlope - 1.0f) + 2.0f);
    float sqrtA = std::sqrt(A);
    float a0 = (A + 1.0f) + (A - 1.0f) * cosw0 + 2.0f * sqrtA * alpha;
    BiquadCoeffs c;
    c.b0 = (A * ((A + 1.0f) - (A - 1.0f) * cosw0 + 2.0f * sqrtA * alpha)) / a0;
    c.b1 = (2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosw0)) / a0;
    c.b2 = (A * ((A + 1.0f) - (A - 1.0f) * cosw0 - 2.0f * sqrtA * alpha)) / a0;
    c.a1 = (-2.0f * ((A - 1.0f) + (A + 1.0f) * cosw0)) / a0;
    c.a2 = ((A + 1.0f) + (A - 1.0f) * cosw0 - 2.0f * sqrtA * alpha) / a0;
    return c;
}

inline BiquadCoeffs highShelf(float sampleRate, float freqHz, float gainDb, float shelfSlope = 1.0f) {
    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * static_cast<float>(M_PI) * freqHz / sampleRate;
    float cosw0 = std::cos(w0), sinw0 = std::sin(w0);
    float alpha = sinw0 / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / shelfSlope - 1.0f) + 2.0f);
    float sqrtA = std::sqrt(A);
    float a0 = (A + 1.0f) - (A - 1.0f) * cosw0 + 2.0f * sqrtA * alpha;
    BiquadCoeffs c;
    c.b0 = (A * ((A + 1.0f) + (A - 1.0f) * cosw0 + 2.0f * sqrtA * alpha)) / a0;
    c.b1 = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosw0)) / a0;
    c.b2 = (A * ((A + 1.0f) + (A - 1.0f) * cosw0 - 2.0f * sqrtA * alpha)) / a0;
    c.a1 = (2.0f * ((A - 1.0f) - (A + 1.0f) * cosw0)) / a0;
    c.a2 = ((A + 1.0f) - (A - 1.0f) * cosw0 - 2.0f * sqrtA * alpha) / a0;
    return c;
}

} // namespace biquad
} // namespace almus::dsp
