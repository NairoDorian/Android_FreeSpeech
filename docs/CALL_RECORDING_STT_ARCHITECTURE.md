# Phone Call Recording & Real-Time STT Architecture

This document details the architecture for integrating phone call recording and automated speech-to-text into **Android FreeSpeech**, leveraging the techniques pioneered by **[ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)** (located locally at `reference_repos/ShizuCallRecorder`).

---

## 1. The Challenge of Call Recording on Modern Android

Starting in Android 9 (Pie) and reinforced in Android 10, 11, 12, 13, and 14:
- The standard `MediaRecorder.AudioSource.VOICE_CALL` API is blocked for third-party non-system applications.
- Apps recording audio via standard microphone APIs during a cellular call only capture the uplink (microphone) audio, missing the downlink (the remote caller's voice), or fail with security exceptions.
- Rooting the device is not acceptable for mainstream users.

---

## 2. The ShizuCallRecorder Solution: Shizuku + ADB + scrcpy-server

`ShizuCallRecorder` bypasses these limitations without root access by combining two technologies:

1. **Shizuku**:
   - Allows ordinary Android apps to execute privileged commands and use hidden system APIs with **ADB shell permissions** (`android.permission.DUMP`, `android.permission.CAPTURE_AUDIO_OUTPUT`, etc.) via a local Binder IPC daemon.
2. **scrcpy-server**:
   - `scrcpy` (developed by Genymobile) contains an Android native Java server that runs in an `app_process` shell context.
   - On Android 11+, `scrcpy-server` can capture internal device audio (submix / playback audio + microphone audio) via the system-level `AudioRecord` or `AudioPlaybackCaptureConfiguration`.
   - `ShizuCallRecorder` runs `scrcpy-server` via Shizuku's `IShellService` AIDL interface, routing raw PCM or Opus audio directly over a Unix domain socket / localhost TCP pipe into the app.

---

## 3. Architecture for FreeSpeech Integration

```
┌─────────────────────────────────────────────────────────────┐
│                       Android Device                        │
│                                                             │
│   ┌───────────────────┐               ┌─────────────────┐   │
│   │ Cellular / VoIP   │               │ Shizuku Service │   │
│   │ Call Audio Streams│               │ (ADB Privileges)│   │
│   └─────────┬─────────┘               └────────┬────────┘   │
│             │                                  │            │
│             ▼                                  ▼            │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                  scrcpy-server                      │   │
│   │     (Spawned via Shizuku Binder IPC in Shell)       │   │
│   │  Captures Dual-Channel Internal + Mic Audio Stream  │   │
│   └─────────────────────────┬───────────────────────────┘   │
│                             │ Raw PCM / Opus Stream Pipe    │
│                             ▼                               │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                Android FreeSpeech                   │   │
│   │                                                     │   │
│   │   ┌───────────────────────┐                         │   │
│   │   │   Audio Stream Pipe   │                         │   │
│   │   │   (ScrcpyAudioSource) │                         │   │
│   │   └───────────┬───────────┘                         │   │
│   │               ▼                                     │   │
│   │   ┌───────────────────────┐   ┌─────────────────┐   │   │
│   │   │  Native Audio Split   ├──▶│ Optional Audio  │   │   │
│   │   │  (16 kHz Mono Resamp) │   │ Storage (M4A)   │   │   │
│   │   └───────────┬───────────┘   └─────────────────┘   │   │
│   │               ▼                                     │   │
│   │   ┌───────────────────────┐                         │   │
│   │   │  R2T2 / Parakeet STT  │                         │   │
│   │   │  (transcribe.cpp)     │                         │   │
│   │   └───────────┬───────────┘                         │   │
│   │               │ Live Transcriptions                 │   │
│   │               ▼                                     │   │
│   │   ┌───────────────────────┐                         │   │
│   │   │ Live Call Captions UI │                         │   │
│   │   │  & Transcript Saver   │                         │   │
│   │   └───────────────────────┘                         │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Key Implementation Steps

### Phase 1: Port Shizuku Integration Subsystem
- Import Shizuku client libraries (`rikka.shizuku:api` and `rikka.shizuku:provider`) into `app/build.gradle.kts`.
- Implement `ShizukuPermissionManager` to check if Shizuku is running and request ADB shell authorization.

### Phase 2: Embed `scrcpy-server` Binary & Client
- Bundle `scrcpy-server` in `app/src/main/assets/`.
- Adapt `ScrcpyClient.kt`, `ScrcpyAudioSource.kt`, and `IShellService.aidl` from `reference_repos/ShizuCallRecorder/app/src/main`.
- Establish Unix domain socket communication to read 16-bit PCM audio.

### Phase 3: Connect Audio Pipeline to `transcribe.cpp`
- Route incoming audio chunks directly into `voice_session::start_recording()` or `recog_service.rs`.
- Feed 16 kHz audio to `Confucius4-R2T2` for real-time transcription.

### Phase 4: User Experience & Floating Call Overlay
- Implement a floating heads-up overlay window (`LiveSubtitleService` / floating dialog) displaying real-time subtitles of what the other person is saying during the call.
- After the call ends, automatically save the transcript with timestamps and caller ID to local storage.
- Optional offline LLM / on-device summarization of the phone conversation.
