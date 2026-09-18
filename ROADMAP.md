# Roadmap

Phase 1, Phase 2, and most of Phase 3 (this repository's current state) are
described in the main [README.md](README.md). The items below are not yet
implemented; they're recorded here so scope is explicit and nothing is
silently claimed as done before it is.

## Phase 3 — remaining items

- Step-sequencer drum machine + basic sample playback engine, reusing the
  existing clip-scheduling machinery in `AlmusAudioEngine` with very short,
  looping clips.
- Piano roll / MIDI-style note grid for programming melodic patterns.
- Track volume automation (the `EffectSettings`/track model would grow an
  automation lane type; the mixer would interpolate gain per-buffer instead
  of reading a single atomic).
- MIDI file import/export for patterns.
- Remaining effects not in Phase 2's fixed-slot chain: limiter, noise gate,
  chorus, flanger, distortion, saturation, time stretching.
- Real-time (monitoring-while-singing) pitch correction. What's implemented
  is offline processing of an already-recorded/imported clip only — see
  README's "Vocal pitch correction" section for exactly what this does and
  does not do.
- Vibrato preservation in pitch correction beyond what the retune-speed
  smoothing already gives you (no explicit vibrato detection/exclusion).

## Phase 4 — Professional refinement

- Cache effect filter coefficients (recompute only when a parameter actually
  changes, instead of every process() call) -- a real but minor CPU-usage
  optimization, not a correctness fix; see `track_effects.h`.
- A dedicated, independent export playhead (removing the current "briefly
  borrow the live playhead" approach documented in `audio_engine.cpp`) so
  exporting no longer risks a few frames of jitter on live playback.
- Anti-aliased resampling (the Phase 2 resampler is linear-interpolation
  only, adequate but not broadcast-quality — see `wav_file.h`).
- Replace the fixed 10ms UI polling loop with an event-driven meter update
  where practical, to reduce battery use.
- Project autosave/recovery after a crash or forced app kill.
- Broader device testing (matrix of sample rates, framesPerBurst values,
  Bluetooth vs. wired monitoring latency).
- Drag-based clip trim/move in the UI (currently split/delete/fade/copy/cut/
  paste/pitch-correction all go through a dialog, not direct-manipulation
  gestures on the waveform).
- Formant-preserving pitch shifting (the current pitch correction changes
  formants along with pitch, which is audible as a "chipmunk/deep voice"
  effect on larger corrections).

