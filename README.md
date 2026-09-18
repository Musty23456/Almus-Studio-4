# Almus Studio

An offline-only Android multitrack audio production app. No account, no
cloud, no network permission requested anywhere in the app — everything
(recording, playback, mixing, project storage) runs entirely on-device.

This repository currently implements **Phase 1, Phase 2, and most of Phase 3**
of the roadmap in [`ROADMAP.md`](ROADMAP.md): a working multitrack
recorder/player, a real per-track effects chain (EQ, high/low-pass,
compressor, delay, reverb), clip split/copy/cut/paste/fade, sample-rate-aware
import, export driven by the project's actual length, per-track colors,
undo/redo, and an offline vocal pitch correction tool. It is a genuinely
useful DAW foundation, not a finished professional one — see
[Known limitations](#known-limitations-be-technically-honest) and
[Vocal pitch correction](#vocal-pitch-correction-be-technically-honest)
below before you rely on it for real work.

## Why these technologies

| Concern | Choice | Why |
|---|---|---|
| App language | Kotlin + Jetpack Compose | Modern, less boilerplate than XML/Views for a UI this state-heavy (playhead, meters, per-track controls all changing constantly); first-class coroutines for the UI polling loop. |
| Real-time audio | C++ (NDK) + [Oboe](https://github.com/google/oboe) | Kotlin/JVM audio APIs (AudioTrack/AudioRecord via Java) add GC pauses and scheduling jitter that are audible as glitches in multitrack playback. Oboe wraps AAudio (API 27+) / OpenSL ES (API 26) and picks the best one automatically, giving the lowest latency Android currently offers without hand-rolling both backends. |
| Cross-thread audio control | A lock-free SPSC command queue (`command_queue.h`) | The Oboe callback runs on a real-time thread — it must never block on a mutex, allocate, or do I/O, or the OS can starve it and you hear a glitch. Every UI/JNI call *enqueues* a change instead of mutating engine state directly. |
| Project storage | Local JSON (Moshi) under `getExternalFilesDir()` | No database is warranted for the current schema (a handful of tracks/clips per project); JSON is trivially inspectable/debuggable, and `getExternalFilesDir()` needs no runtime permission on API 26+ while still being visible to a file manager or connected computer — unlike internal-only storage. |
| Audio file format | WAV (PCM/float) | Simple to decode, encode, and reason about without a codec license question; MP3/AAC encoding is left for a later export-quality phase (see roadmap). |

## Architecture

```
Kotlin/Compose UI  →  StudioViewModel  →  AudioEngine (JNI, Kotlin object)
                                              │  enqueue-only calls
                                              ▼
                                    CommandQueue (lock-free SPSC ring buffer)
                                              │  drained at top of each callback
                                              ▼
                              AlmusAudioEngine (C++, owns Oboe streams)
                               ├─ Output stream callback → mixes tracks/clips
                               └─ Input stream callback  → writes WavWriter
```

- **UI thread**: Compose screens + `StudioViewModel`. Polls playhead/meters
  every 50ms (`StateFlow`s) rather than the audio thread pushing to it, since
  the audio thread must never call back into the JVM per-buffer.
- **JNI bridge** (`jni_bridge.cpp`): translates each `AudioEngine.kt` external
  function into either a synchronous read (e.g. `getPlayheadFrame`) or an
  enqueued `Command`.
- **Real-time audio thread**: owned entirely by Oboe. `drainCommands()` runs
  first in every callback (cheap, bounded, lock-free), then `mixInto()` sums
  every unmuted track's active clips into the output buffer.
- **Structural changes** (add/remove track or clip) briefly take a
  `std::mutex` — *not* on the per-sample hot path, only around the handful of
  vector insert/erase calls — so the offline exporter can safely read
  `tracks_` from its own thread. This is documented in `audio_engine.h`.
- **Per-track effects** (`app/src/main/cpp/track_effects.h` + `dsp/`): each
  track sums its active clips into a thread-local scratch buffer, runs that
  buffer through its `EffectChain` (EQ → high-pass → low-pass → compressor →
  delay → reverb, in that fixed order), *then* applies track volume/pan into
  the master mix. Effects process a track's full signal, not each clip in
  isolation. The chain is a fixed set of slots (not a generic plugin system)
  — see `effect_chain.h`.
- **Concurrency hazard, by design, mitigated in the UI**: `mixInto()` runs on
  both the real-time output thread and the offline-export worker thread. The
  per-buffer scratch mixing buffer is `thread_local` so those two threads
  can't corrupt each other's mix — but each track's persistent effect state
  (filter history, delay lines, reverb tail) is *not* duplicated per thread,
  so exporting while also playing live would have both threads mutating the
  same effect state concurrently. `StudioViewModel.play()` refuses to start
  playback while an export is in progress specifically to avoid this; see
  the comment above `play()`.

## Resampling and clip fades

- **Sample-rate mismatches are now corrected**, not just documented as a
  known issue: `AudioEngine.scheduleClip` resamples (linear interpolation,
  not anti-aliased — see `wav_file.h`) to the project's sample rate before
  scheduling.
- **Clips support linear fade-in/fade-out** (`AudioClip.fadeInFrames` /
  `fadeOutFrames`), editable from the clip-tap dialog in `StudioScreen.kt`,
  applied per-sample in `mixInto()`.
- **Splitting a clip** creates two clips referencing the same underlying WAV
  file at different offsets — no audio data is duplicated on disk.

## Vocal pitch correction (be technically honest)

Almus Studio has an offline vocal pitch correction feature, reachable from a
clip's action dialog ("Pitch correction…"). Read this before relying on it:

- **This is not Auto-Tune.** It's autocorrelation-based pitch detection
  (`dsp/pitch_correction.h`) plus a granular resample pitch shifter with
  overlap-add resynthesis. There is no formant preservation, so larger
  corrections shift the voice's timbre along with its pitch (audible as a
  "chipmunk" or "deepened" quality) — a real, known limitation of this
  technique, not a bug.
- **Offline only, on a whole clip** — you pick a clip, choose a key/scale,
  strength, and retune speed, and it processes and replaces that clip's
  audio. There's no real-time "sing and hear it corrected live" monitoring;
  that would need this same detection running fast enough, and well enough,
  inside the real-time audio callback, which is a substantially harder
  problem left for a later phase (see ROADMAP.md).
- **Works best on a single, clearly-pitched voice.** Polyphonic audio (a
  chord, multiple singers) or unvoiced sounds (breath, consonants, sibilance)
  aren't "corrected" — the pitch detector reports them as unvoiced and passes
  them through unchanged, rather than guessing and distorting them.
- **"Hard mode"** snaps close to instantly; the default ("natural") mode
  smooths the correction over the chosen retune-speed window so sustained
  notes glide into pitch rather than snapping abruptly.

## Track colors, cut/copy/paste, and undo/redo

- Each track gets a distinct color from a fixed palette (`TrackColors.kt`)
  when created, shown as a stripe on its header and used for its clips'
  waveforms in the timeline.
- Clip actions include copy, cut, and paste (at the playhead, on whichever
  track you tap the paste icon on) alongside split/fade/delete/pitch-correct.
  Copy/cut only hold one clip at a time — copying a second clip replaces the
  first in the clipboard.
- **Undo/redo is whole-project-snapshot based**, not per-field: every
  discrete edit (add track, mute/solo, delete/split/paste a clip, apply
  effects, apply pitch correction) pushes the *previous* project state onto
  an undo stack (capped at 50 steps) before applying the change, and
  undo/redo fully tears down and rebuilds the native engine's track/clip
  state to match the snapshot (`attachProjectToEngine` in
  `StudioViewModel.kt`) rather than trying to compute an inverse for every
  possible edit. Volume/pan slider drags are a deliberate exception: only
  the value when you release the slider is undoable, not every intermediate
  value while dragging — otherwise one drag would fill the entire undo
  history in a fraction of a second.

## Settings

A minimal in-app Settings screen (gear icon on the home screen) shows the
fixed audio engine configuration (sample rate, backend, sharing-mode
fallback) and a short reminder that everything stays on-device. There's
nothing to configure yet beyond viewing this — no per-project audio settings
UI, since the engine's sample rate is currently fixed at initialization.

## Known limitations (be technically honest)

Per the project's own requirement to never claim more than is implemented:

- **Effects are a fixed set of six**, not a generic plugin system: EQ (3-band),
  high-pass, low-pass, compressor, delay, reverb. Limiter, noise gate,
  chorus, flanger, distortion, saturation, pitch shifting, and time
  stretching are **not implemented** (see `ROADMAP.md` Phase 3).
- **Filter coefficients are recomputed every audio callback** rather than
  cached and only recomputed when a parameter changes. Correct, just not
  maximally efficient — a noted Phase 4 optimization, not a bug.
- **Resampling is linear-interpolation only** (no anti-aliasing filter) —
  correct pitch/speed, but not broadcast-quality for large sample-rate
  changes.
- **No waveform-editing gestures** — split/delete/fade happen through a
  dialog after tapping a clip, not by dragging its edges directly.
- **No auto-tune / pitch correction yet.** Still a data-model placeholder
  only (Phase 3 item).
- **Recording is mono input, mixed to a single monitor track**; the DAW does
  not yet support multi-channel audio interfaces explicitly (Oboe will use
  whatever the OS reports as the default input).
- **Exporting while transport is also playing is disallowed by the UI**
  (the Play button is disabled during export), because both would otherwise
  drive the same per-track effect state from two threads at once — see the
  "Concurrency hazard" note above.

None of the above are silently stubbed with fake UI — the corresponding
controls simply aren't in the app yet, or (for effects not in the six above)
have no data model at all rather than an inert one.

## Repository layout

```
app/
  src/main/java/com/almus/studio/
    audio/        Kotlin JNI wrapper, waveform analyzer, time-conversion helpers
    data/         Project/Track/AudioClip models + local JSON repository
    ui/           Compose screens, theme, reusable components
    viewmodel/    StudioViewModel (single source of UI truth)
  src/main/cpp/    Native Oboe-based multitrack engine (see architecture above)
  src/test/        JVM unit tests (no device/emulator needed)
.github/workflows/ CI: build + test + APK artifact upload
```

## Building the APK

### Locally

You need Android Studio (Koala or newer) with the NDK and CMake components
installed (Settings → Languages & Frameworks → Android SDK → SDK Tools →
check "NDK (Side by side)" and "CMake"), or a system `gradle` install (8.7+).

```bash
# One-time, if you don't already have gradlew: generates the wrapper jar/scripts.
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Via GitHub Actions

Push to `main` or open a pull request; `.github/workflows/android-build.yml`
will:

1. Check out the repo.
2. Install JDK 17 and the Android SDK.
3. Provision Gradle 8.7 (no committed wrapper jar required).
4. Run `gradle test`.
5. Run `gradle assembleDebug` and upload the APK as a workflow artifact
   named `almus-studio-debug-apk`.
6. Optionally build and upload a signed release APK **only if** the repo has
   `ALMUS_RELEASE_KEYSTORE_BASE64`, `ALMUS_RELEASE_KEYSTORE_PASSWORD`,
   `ALMUS_RELEASE_KEY_ALIAS`, and `ALMUS_RELEASE_KEY_PASSWORD` configured as
   repository secrets. No signing key is ever committed to the repository.

Download the APK from the workflow run's "Artifacts" section.

## Installing and testing on an Android phone

1. On your phone: Settings → About phone → tap "Build number" 7 times to
   enable Developer Options, then Settings → Developer options → enable
   "USB debugging".
2. Connect the phone via USB and accept the "Allow USB debugging?" prompt.
3. From a computer with the APK downloaded:
   ```bash
   adb install app-debug.apk
   ```
   or copy the APK to the phone and open it directly (you'll need to allow
   "install unknown apps" for whichever app you copied it with).
4. Launch **Almus Studio**, tap **New Project**, then tap the record button —
   you'll be prompted for microphone permission the first time.
5. For a quick multitrack test: create two tracks, import a WAV file (or
   record one) into each via the import icon on a track's timeline lane, then
   hit play. You should hear both mixed together, and can adjust each
   track's volume/pan slider live during playback.

A physical device is strongly recommended over an emulator for anything
audio-related — emulator audio timing does not reflect real-world latency,
which matters a great deal for a DAW.

## Running tests

```bash
./gradlew test          # JVM unit tests (project model, time conversion)
./gradlew connectedCheck  # instrumented tests, requires a connected device/emulator
```

## Roadmap

See [`ROADMAP.md`](ROADMAP.md) for Phases 2–4 (editing/effects, beat maker &
MIDI & offline vocal pitch correction, and professional refinement).
# Almus-Studio-4
