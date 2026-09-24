# Android FreeSpeech: Forks, References, and Inspirations

This document catalogs the upstream forks, comparison URLs, and reference repositories curated to guide the development, debugging, and evolution of **Android FreeSpeech** (`https://github.com/NairoDorian/Android_FreeSpeech`).

---

## 1. Upstream Comparison References

| Fork / Author | Comparison URL | Primary Contributions & Focus |
|---|---|---|
| **caminante-blanco** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...caminante-blanco:android_transcribe_app:main) | Real-time streaming support for Moonshine and Nemotron models; CI build improvements skipping signing when secrets are absent. |
| **dmtnndxr** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...dmtnndxr:android_transcribe_app:main) | Optional LLM post-processing of transcriptions (AI cleanup via OpenRouter/API); portable NDK host path detection (Windows/macOS/Linux); independently installable Plus build. |
| **classic-ally** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...classic-ally:android_transcribe_app:main) | Opt-in live streaming preview to the voice keyboard; discard button and configurable preview refresh interval. |
| **dscho** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...dscho:android_transcribe_app:main) | IME streaming dictation rendered as composing text (`InputConnection.setComposingText`); `android.speech.RecognitionService` implementation; R8 release minification. |
| **mw-el (TRANSKRIPT)** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...mw-el:TRANSKRIPT:main) | Complete Anthropic/Claude-inspired UI redesign; dedicated audio file transcription with chunk decoding (avoiding Java-heap OOM); DictateActivity with waveform; recordings manager; offline TTS via Sherpa-ONNX + Thorsten-Medium (Piper VITS); Voice Chat mode; multi-language post-processing. |
| **Nicfox77** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...Nicfox77:android_transcribe_app:main) | Inspectable streaming IME builds, self-reporting CI workflows, and PR verification builds. |
| **arthow4n** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...arthow4n:android_transcribe_app:main) | Traditional Chinese language conversion (Taiwan); keyboard streaming dictation with live speed/WPM stats; configurable filler word filter and punctuation cleanup; buffered streaming dictation with Parakeet Unified EN; model memory per language. |
| **space-shell (nemotron-voice-input)** | [Compare with upstream main](https://github.com/notune/android_transcribe_app/compare/main...space-shell:nemotron-voice-input:main) | Migration to `transcribe-cpp 0.2.3`; per-run `Session` architecture with lock-free channel audio feeding; floating keyboard mode with insets fix; microphone foreground service (FGS) for background recording; survival of freezer cold-restarts; FFI hardening (`catch_unwind`, no aborts on JNI error, `SendStream`). |

---

## 2. Phone Call Recording Reference: ShizuCallRecorder

- **Repository**: [https://github.com/kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)
- **Local clone**: `reference_repos/ShizuCallRecorder`
- **Core Technology**:
  - Leverages **Shizuku** (ADB IPC service) to execute privileged shell commands on non-rooted Android devices.
  - Spawns and manages **scrcpy-server** via ADB shell to capture the device's internal playback / submix / microphone audio streams, bypassing Android 10+ restrictions on the `VOICE_CALL` audio source.
  - Uses `ScrcpyClient`, `ScrcpyAudioSource`, and `ScrcpyAudioMuxer` to stream and record clean call audio without requiring root access.
- **Vision for Android FreeSpeech**:
  - In a future phase, adapt and modernize the Shizuku/scrcpy audio capture subsystem.
  - Pipe real-time call audio directly into the native STT engine (`transcribe.cpp` / R2T2) to provide **live call transcription, instant captions, and automated post-call meeting notes/summaries**, completely offline and private.

---

## 3. Local Clones Layout

All reference repositories have been cloned locally for direct inspection, diffing, and code extraction:

```
Android_FreeSpeech/
├── other_forks/
│   ├── caminante-blanco/
│   ├── classic-ally/
│   ├── dmtnndxr/
│   ├── dscho/
│   ├── mw-el/
│   ├── Nicfox77/
│   ├── arthow4n/
│   └── space-shell/
├── reference_repos/
│   └── ShizuCallRecorder/
└── docs/
    ├── FORKS_AND_REFERENCES.md
    ├── UPSTREAM_PRS_REFERENCE.md
    ├── TRANSCRIBE_CPP_MIGRATION_PLAN.md
    ├── R2T2_INTEGRATION_PLAN.md
    └── CALL_RECORDING_STT_ARCHITECTURE.md
```

*(Note: `other_forks/` and `reference_repos/` are ignored in `.gitignore` so they remain on disk for developer reference without bloating the main Git repository).*
