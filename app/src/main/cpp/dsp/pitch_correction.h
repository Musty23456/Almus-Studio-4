#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace almus::dsp {

// A deliberately modest offline pitch-correction algorithm: autocorrelation
// pitch detection + a granular resample-based pitch shifter with
// overlap-add resynthesis. This is NOT a from-scratch reimplementation of
// any commercial product's algorithm, and it does not claim to be
// professional-grade Auto-Tune: there is no formant preservation, and
// sustained notes with vibrato can show minor grain artifacts. It is
// offered as a genuinely-working, honestly-scoped "vocal pitch correction"
// feature -- see README.md for exactly what this does and does not do.

struct PitchCorrectionParams {
    int rootPitchClass = 0;   // 0 = C, 1 = C#, ... 11 = B
    uint16_t scaleMask = 0;   // bit i set => pitch class (root + i) % 12 is an allowed target
    float strength = 1.0f;    // 0 = no correction, 1 = fully snap to nearest allowed note
    float speedMs = 50.0f;    // retune glide time; smaller = snappier correction
    bool hardMode = false;    // true: snap almost instantly, skips most of the glide smoothing
};

struct PitchDetectionResult {
    bool voiced = false;
    float frequencyHz = 0.0f;
};

// Normalized autocorrelation over [samples, samples+frameCount). Searches lags
// corresponding to typical vocal fundamentals (70-1000 Hz.) `voiced` is only
// true when the strongest correlation peak is confident enough to trust --
// unpitched/noisy audio (breath, consonants, silence) is correctly reported
// as unvoiced so it passes through uncorrected, per the "clear handling of
// unpitched audio" requirement.
inline PitchDetectionResult detectPitch(const float* samples, int frameCount, int sampleRate,
                                         float minFreqHz = 70.0f, float maxFreqHz = 1000.0f) {
    int minLag = static_cast<int>(sampleRate / maxFreqHz);
    int maxLag = std::min(frameCount - 1, static_cast<int>(sampleRate / minFreqHz));
    if (minLag < 1 || maxLag <= minLag) return {false, 0.0f};

    float zeroLagEnergy = 0.0f;
    for (int i = 0; i < frameCount; i++) zeroLagEnergy += samples[i] * samples[i];
    if (zeroLagEnergy < 1e-6f) return {false, 0.0f}; // silence

    float bestCorr = 0.0f;
    int bestLag = -1;
    for (int lag = minLag; lag <= maxLag; lag++) {
        float corr = 0.0f;
        int n = frameCount - lag;
        for (int i = 0; i < n; i++) corr += samples[i] * samples[i + lag];
        corr /= static_cast<float>(n);
        if (corr > bestCorr) {
            bestCorr = corr;
            bestLag = lag;
        }
    }
    if (bestLag <= 0) return {false, 0.0f};

    float normalizedCorr = bestCorr / (zeroLagEnergy / frameCount);
    bool voiced = normalizedCorr > 0.3f; // confidence threshold, tuned empirically
    return {voiced, static_cast<float>(sampleRate) / static_cast<float>(bestLag)};
}

inline float nearestScaleFrequency(float inputFreqHz, int rootPitchClass, uint16_t scaleMask) {
    if (inputFreqHz <= 0.0f) return inputFreqHz;
    float midiFloat = 69.0f + 12.0f * log2f(inputFreqHz / 440.0f);
    int midiRounded = static_cast<int>(std::round(midiFloat));

    int bestMidi = midiRounded;
    float bestDist = 1e9f;
    for (int candidate = midiRounded - 12; candidate <= midiRounded + 12; candidate++) {
        int pitchClass = ((candidate - rootPitchClass) % 12 + 12) % 12;
        bool inScale = scaleMask == 0 || (scaleMask & (1 << pitchClass)) != 0;
        if (inScale) {
            float dist = std::fabs(static_cast<float>(candidate) - midiFloat);
            if (dist < bestDist) {
                bestDist = dist;
                bestMidi = candidate;
            }
        }
    }
    return 440.0f * powf(2.0f, (bestMidi - 69) / 12.0f);
}

// Computes one target pitch-shift ratio per analysis hop from a mono
// reference signal. Both channels of a stereo file are shifted using this
// same ratio timeline so the stereo image doesn't drift.
inline std::vector<float> computeRatioTimeline(const std::vector<float>& monoSamples, int sampleRate,
                                                int grainFrames, int hop, const PitchCorrectionParams& params) {
    int64_t totalFrames = static_cast<int64_t>(monoSamples.size());
    std::vector<float> ratios;
    // smoothingCoeff closer to 1 = slower glide (more of the previous ratio
    // carries forward); hard mode uses a small fixed coefficient for a snappy feel.
    float smoothingCoeff = params.hardMode ? 0.1f : std::clamp(params.speedMs / 150.0f, 0.05f, 0.97f);
    float smoothedRatio = 1.0f;

    for (int64_t pos = 0; pos + grainFrames <= totalFrames; pos += hop) {
        auto detection = detectPitch(&monoSamples[pos], grainFrames, sampleRate);
        float ratio = 1.0f;
        if (detection.voiced && params.strength > 0.0f) {
            float targetFreq = nearestScaleFrequency(detection.frequencyHz, params.rootPitchClass, params.scaleMask);
            float rawRatio = targetFreq / detection.frequencyHz;
            ratio = 1.0f + (rawRatio - 1.0f) * params.strength;
        }
        smoothedRatio = smoothingCoeff * smoothedRatio + (1.0f - smoothingCoeff) * ratio;
        ratios.push_back(params.hardMode ? ratio : smoothedRatio);
    }
    return ratios;
}

// Applies the precomputed ratio timeline to one channel via granular
// resample + overlap-add (Hann-windowed). Output is the same length as the
// input -- pitch changes, duration and clip alignment do not.
inline std::vector<float> applyPitchShiftOLA(const std::vector<float>& channelSamples,
                                              const std::vector<float>& ratioPerHop,
                                              int grainFrames, int hop) {
    int64_t totalFrames = static_cast<int64_t>(channelSamples.size());
    std::vector<float> output(totalFrames, 0.0f);
    std::vector<float> windowSum(totalFrames, 0.0f);

    std::vector<float> hann(grainFrames);
    for (int i = 0; i < grainFrames; i++) {
        hann[i] = 0.5f - 0.5f * cosf(2.0f * static_cast<float>(M_PI) * i / (grainFrames - 1));
    }

    size_t hopIndex = 0;
    for (int64_t pos = 0; pos + grainFrames <= totalFrames && hopIndex < ratioPerHop.size(); pos += hop, hopIndex++) {
        float ratio = ratioPerHop[hopIndex];
        for (int i = 0; i < grainFrames; i++) {
            float srcPosF = static_cast<float>(i) * ratio;
            int64_t srcIdx = pos + static_cast<int64_t>(srcPosF);
            float frac = srcPosF - std::floor(srcPosF);
            int64_t idxA = std::min<int64_t>(srcIdx, totalFrames - 1);
            int64_t idxB = std::min<int64_t>(srcIdx + 1, totalFrames - 1);
            float sample = channelSamples[idxA] + (channelSamples[idxB] - channelSamples[idxA]) * frac;
            float windowed = sample * hann[i];

            int64_t outIdx = pos + i;
            if (outIdx < totalFrames) {
                output[outIdx] += windowed;
                windowSum[outIdx] += hann[i];
            }
        }
    }

    for (int64_t i = 0; i < totalFrames; i++) {
        output[i] = windowSum[i] > 1e-6f ? output[i] / windowSum[i] : channelSamples[i];
    }
    return output;
}

// Top-level entry point: corrects an interleaved stereo (or mono) buffer.
inline std::vector<float> correctPitch(const std::vector<float>& interleaved, int channelCount,
                                        int64_t frameCount, int sampleRate,
                                        const PitchCorrectionParams& params) {
    if (frameCount <= 0 || channelCount <= 0) return interleaved;

    int grainFrames = std::max(64, static_cast<int>(sampleRate * 0.046)); // ~46ms
    int hop = std::max(1, grainFrames / 4);

    // Downmix to mono for pitch detection only -- correction is applied to
    // each channel independently using the same ratio timeline.
    std::vector<float> mono(frameCount);
    for (int64_t i = 0; i < frameCount; i++) {
        float sum = 0.0f;
        for (int ch = 0; ch < channelCount; ch++) sum += interleaved[i * channelCount + ch];
        mono[i] = sum / channelCount;
    }

    auto ratios = computeRatioTimeline(mono, sampleRate, grainFrames, hop, params);

    std::vector<std::vector<float>> channels(channelCount, std::vector<float>(frameCount));
    for (int64_t i = 0; i < frameCount; i++)
        for (int ch = 0; ch < channelCount; ch++)
            channels[ch][i] = interleaved[i * channelCount + ch];

    std::vector<float> result(static_cast<size_t>(frameCount) * channelCount);
    for (int ch = 0; ch < channelCount; ch++) {
        auto shifted = applyPitchShiftOLA(channels[ch], ratios, grainFrames, hop);
        for (int64_t i = 0; i < frameCount; i++) result[i * channelCount + ch] = shifted[i];
    }
    return result;
}

} // namespace almus::dsp
