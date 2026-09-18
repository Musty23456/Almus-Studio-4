#pragma once

namespace almus {

// One fixed-slot effect chain per track. This is deliberately not a generic
// key/value plugin system -- Phase 2 ships a specific, working set of
// effects (see README/ROADMAP for what's implemented vs. still pending:
// limiter, noise gate, chorus, flanger, distortion, and pitch correction are
// NOT in this struct yet). Trivially copyable so it can be embedded directly
// in a Command (see command_queue.h) without any heap allocation.
struct EffectChain {
    bool eqEnabled = false;
    float eqLowGainDb = 0.0f;   // low shelf around 250 Hz
    float eqMidGainDb = 0.0f;   // peaking band around 1 kHz
    float eqHighGainDb = 0.0f;  // high shelf around 4 kHz

    bool highPassEnabled = false;
    float highPassCutoffHz = 80.0f;

    bool lowPassEnabled = false;
    float lowPassCutoffHz = 12000.0f;

    bool compressorEnabled = false;
    float compressorThresholdDb = -18.0f;
    float compressorRatio = 4.0f;
    float compressorAttackMs = 10.0f;
    float compressorReleaseMs = 100.0f;

    bool delayEnabled = false;
    float delayTimeMs = 300.0f;
    float delayFeedback = 0.3f;
    float delayMix = 0.25f;

    bool reverbEnabled = false;
    float reverbRoomSize = 0.5f; // 0..1
    float reverbMix = 0.2f;      // 0..1 wet amount
};

} // namespace almus
