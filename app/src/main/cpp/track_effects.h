#pragma once

#include <cstdint>

#include "dsp/biquad.h"
#include "dsp/compressor.h"
#include "dsp/delay_reverb.h"
#include "effect_chain.h"

namespace almus {

// Owns every effect's persistent state (filter history, delay buffers,
// reverb tail, compressor envelope) for one track. Filter coefficients are
// recomputed from EffectChain at the top of every process() call -- a few
// trig calls per ~5ms audio buffer, which is cheap enough not to matter, but
// caching them (only recomputing when a parameter actually changes) is a
// noted Phase 4 optimization rather than a correctness issue today.
class TrackEffects {
public:
    void init(int sampleRate) {
        sampleRate_ = sampleRate;
        delayL_.init(sampleRate, 2.0f); // 2 second max delay
        delayR_.init(sampleRate, 2.0f);
        reverbL_.init(sampleRate);
        reverbR_.init(sampleRate);
    }

    // In-place stereo-interleaved processing, applied in this fixed order:
    // EQ -> high-pass -> low-pass -> compressor -> delay -> reverb.
    void process(float* interleavedStereo, int32_t numFrames, const EffectChain& p) {
        if (p.eqEnabled) {
            auto low = dsp::biquad::lowShelf(sampleRate_, 250.0f, p.eqLowGainDb);
            auto mid = dsp::biquad::peaking(sampleRate_, 1000.0f, p.eqMidGainDb, 1.0f);
            auto high = dsp::biquad::highShelf(sampleRate_, 4000.0f, p.eqHighGainDb);
            eqLowL_.setCoeffs(low); eqLowR_.setCoeffs(low);
            eqMidL_.setCoeffs(mid); eqMidR_.setCoeffs(mid);
            eqHighL_.setCoeffs(high); eqHighR_.setCoeffs(high);
        }
        if (p.highPassEnabled) {
            auto c = dsp::biquad::highPass(sampleRate_, p.highPassCutoffHz, 0.707f);
            highPassL_.setCoeffs(c); highPassR_.setCoeffs(c);
        }
        if (p.lowPassEnabled) {
            auto c = dsp::biquad::lowPass(sampleRate_, p.lowPassCutoffHz, 0.707f);
            lowPassL_.setCoeffs(c); lowPassR_.setCoeffs(c);
        }

        const float delaySamples = p.delayTimeMs * 0.001f * sampleRate_;

        for (int32_t i = 0; i < numFrames; i++) {
            float l = interleavedStereo[i * 2];
            float r = interleavedStereo[i * 2 + 1];

            if (p.eqEnabled) {
                l = eqHighL_.process(eqMidL_.process(eqLowL_.process(l)));
                r = eqHighR_.process(eqMidR_.process(eqLowR_.process(r)));
            }
            if (p.highPassEnabled) {
                l = highPassL_.process(l);
                r = highPassR_.process(r);
            }
            if (p.lowPassEnabled) {
                l = lowPassL_.process(l);
                r = lowPassR_.process(r);
            }
            if (p.compressorEnabled) {
                float outL, outR;
                compressor_.process(l, r, p.compressorThresholdDb, p.compressorRatio,
                                     p.compressorAttackMs, p.compressorReleaseMs, sampleRate_,
                                     &outL, &outR);
                l = outL; r = outR;
            }
            if (p.delayEnabled) {
                float wetL = delayL_.process(l, delaySamples, p.delayFeedback);
                float wetR = delayR_.process(r, delaySamples, p.delayFeedback);
                l = l * (1.0f - p.delayMix) + wetL * p.delayMix;
                r = r * (1.0f - p.delayMix) + wetR * p.delayMix;
            }
            if (p.reverbEnabled) {
                float wetL = reverbL_.process(l, p.reverbRoomSize, 0.5f);
                float wetR = reverbR_.process(r, p.reverbRoomSize, 0.5f);
                l = l * (1.0f - p.reverbMix) + wetL * p.reverbMix;
                r = r * (1.0f - p.reverbMix) + wetR * p.reverbMix;
            }

            interleavedStereo[i * 2] = l;
            interleavedStereo[i * 2 + 1] = r;
        }
    }

private:
    int sampleRate_ = 48000;

    dsp::BiquadFilter eqLowL_, eqLowR_, eqMidL_, eqMidR_, eqHighL_, eqHighR_;
    dsp::BiquadFilter highPassL_, highPassR_;
    dsp::BiquadFilter lowPassL_, lowPassR_;
    dsp::Compressor compressor_;
    dsp::DelayLine delayL_, delayR_;
    dsp::SimpleReverb reverbL_, reverbR_;
};

} // namespace almus
