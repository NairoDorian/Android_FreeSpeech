# Android FreeSpeech

**Android FreeSpeech** is a 100% Free and Open Source Software (FOSS) speech-to-text (STT) and voice typing suite for Android. It operates completely on-device, privately, with zero telemetry or network requirements.

Originating as a fresh, independent evolution of [`android_transcribe_app`](https://github.com/notune/android_transcribe_app), Android FreeSpeech synthesizes the best innovations across community forks, upgrades the native speech engine to **[NairoDorian/transcribe.cpp](https://github.com/NairoDorian/transcribe.cpp)**, and plans native support for ultra-low-latency streaming models including **Confucius4-R2T2** and call transcription via **Shizuku**.

---

## Key Features

- **Universal Voice Input:** Seamlessly integrates with your favourite keyboard (SwiftKey, HeliBoard, AnySoftKeyboard, OpenBoard, etc.) via the standard `RECOGNIZE_SPEECH` voice panel or system `RecognitionService`.
- **Dedicated Voice Keyboard (IME):** A full-screen or compact voice keyboard with streaming partial text display (`InputConnection.setComposingText`), auto-stop silence detection, and auto-return.
- **100% Offline & Private:** Models run locally on your phone using GGML and ARM NEON / Dot Product / FP16 acceleration. No sound ever leaves your device.
- **Live Device Subtitles:** Real-time captions for video, podcast, or voice audio played by any app on your device.
- **Advanced Model Support:** Built-in support for GGUF models powered by `transcribe.cpp` (Parakeet TDT, Nemotron Streaming, Whisper, and upcoming Confucius4-R2T2).
- **Cross-Platform Build System:** NDK detection and build scripts tested across Windows, macOS, and Linux.

---

## Architectural & Planning Documentation

We maintain in-depth technical plans and reference comparisons in the [`docs/`](docs/) directory:

1. **[Forks & Reference Repositories](docs/FORKS_AND_REFERENCES.md)**:
   - Direct comparison links and detailed feature analyses for the 8 primary forks:
     - `caminante-blanco` (Moonshine/Nemotron streaming, CI improvements)
     - `dmtnndxr` (LLM post-processing AI cleanup, portable NDK detection)
     - `classic-ally` (Live streaming preview, discard button)
     - `dscho` (IME composing text streaming, RecognitionService)
     - `mw-el / TRANSKRIPT` (Complete Anthropic UI redesign, audio file transcription, offline TTS via Sherpa-ONNX)
     - `Nicfox77` (Streaming IME test builds)
     - `arthow4n` (Traditional Chinese, live WPM stats, filler word filtering)
     - `space-shell / nemotron-voice-input` (`transcribe-cpp 0.2.3`, per-run session channels, mic FGS, FFI resilience)
   - Analysis of `ShizuCallRecorder` for non-root phone call capture.

2. **[Upstream Pull Requests Guide](docs/UPSTREAM_PRS_REFERENCE.md)**:
   - Comprehensive review of [notune/android_transcribe_app/pulls](https://github.com/notune/android_transcribe_app/pulls).
   - Analysis of PR #114 (auto-stop silence threshold), PR #115 (cursor-aware text fitting), PR #116 (silence trimming), PR #117 (keyboard switch-back), PR #118 (text recovery on insert failure), PR #105 (caller-supplied audio), PR #100 (theme change inset fix), and PR #93 (Armv8-A runtime fallback).

3. **[NairoDorian/transcribe.cpp Migration Plan](docs/TRANSCRIBE_CPP_MIGRATION_PLAN.md)**:
   - Technical roadmap to upgrade native Rust bindings and C++ core to `NairoDorian/transcribe.cpp` v0.2.3.
   - Per-run session architecture, lock-free channel feeding, and `minimal-multilingual` compilation flags.

4. **[Confucius4-R2T2 Model Integration Plan](docs/R2T2_INTEGRATION_PLAN.md)**:
   - Architecture for deploying `r2t2-q4_k_m.gguf` (~1.18 GB) on mobile.
   - Continuous millisecond cadence (80–2000 ms, default 320 ms) via `TRANSCRIBE_EXT_KIND_R2T2_STREAM`.
   - Character/byte-level stable prefix committing and IME composing text integration.
   - Low Memory Killer (LMK) protection via Microphone Foreground Service.

5. **[Phone Call Recording & STT Architecture](docs/CALL_RECORDING_STT_ARCHITECTURE.md)**:
   - Deep dive into adapting `ShizuCallRecorder`'s Shizuku ADB IPC and `scrcpy-server` audio pipe into Android FreeSpeech.
   - Live call captions and automated post-call meeting notes completely offline.

---

## Reference Repositories Layout

To inspect upstream forks and call recording reference code locally, clone them into the ignored reference folders:

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
```

---

## Building from Source

### Prerequisites

| Tool | Requirement | Note |
|---|---|---|
| **JDK 17** | Required | Bundled with Android Studio or OpenJDK 17 |
| **Android SDK & NDK** | API 35, NDK 28+ | Via Android Studio `sdkmanager` |
| **Rust** | 1.74+ | `rustup target add aarch64-linux-android` |
| **cargo-ndk** | 3.5+ | `cargo install cargo-ndk` |

### Build Steps

1. Clone this repository:
   ```bash
   git clone https://github.com/NairoDorian/Android_FreeSpeech.git
   cd Android_FreeSpeech
   ```

2. Create `local.properties` with your Android SDK path:
   ```properties
   sdk.dir=C:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
   ```

3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## License & Attribution

- **Android FreeSpeech** is licensed under the Apache License 2.0 / MIT.
- Core inference engine powered by [transcribe.cpp](https://github.com/NairoDorian/transcribe.cpp) and [ggml](https://github.com/ggerganov/ggml).
- Based on the foundational work of [Noah / notune](https://github.com/notune/android_transcribe_app) and inspired by the contributions of community forks (`space-shell`, `mw-el`, `arthow4n`, `dmtnndxr`, `caminante-blanco`, `dscho`, `classic-ally`, `Nicfox77`, and `kitsumed`).
