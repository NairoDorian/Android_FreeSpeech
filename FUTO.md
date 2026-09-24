# The FUTO Situation

> [!NOTE]
> The full, in-depth architectural and technical breakdown of the FUTO Situation, including AIDL IPC contracts, cryptographic pairing, Android 14 elevation trampolines, dynamic audio format conversion, and the two connected forks (`djurcola/android_transcribe_app` and `djurcola/android-keyboard`), is located in:
>
> 📖 **[`docs/FUTO.md`](file:///c:/Users/Z/Downloads/PROJECTS/Android_FreeSpeech/docs/FUTO.md)**

---

## Quick Reference Summary

### 1. The Conflict
- **FUTO Keyboard** bundles its own on-device voice input ("FUTO Voice Input") under a proprietary, non-FOSS license with payment nags and trial limits.
- It locks users into FUTO-only models, blocking open-source GGUF, Moonshine, Nemotron, or Confucius4-R2T2 models via `transcribe.cpp`.
- Previously, using open-source speech models required switching away from FUTO Keyboard entirely via the system IME dialog.

### 2. The Solution & Fork Pair by `djurcola`
1. **Speech Backend Fork**: [`djurcola/android_transcribe_app`](https://github.com/djurcola/android_transcribe_app)
   - Branch [`agent/futo-voice-bridge`](https://github.com/djurcola/android_transcribe_app/tree/agent/futo-voice-bridge): Introduced authenticated AIDL Binder IPC (`IOfflineVoiceBridge`), cryptographic pairing tokens, floating voice bubble overlay, accessibility auto-paste, custom words prompt biasing, and local SQLite history.
   - Branch [`agent/stable-test-signing`](https://github.com/djurcola/android_transcribe_app/tree/agent/stable-test-signing): Production hardening with Android 14 `PendingIntent` foreground elevation trampoline (`ForegroundActivationActivity`), native CPAL context bootstrapping (`ndk_context`), dynamic hardware sample rate downmixer/resampler (`CaptureConverter`), and shared CI test signing.
   - Local clone: `other_forks/djurcola`
2. **Keyboard Client Fork**: [`djurcola/android-keyboard`](https://github.com/djurcola/android-keyboard)
   - Branch [`custom/gif-and-swipe`](https://github.com/djurcola/android-keyboard/tree/custom/gif-and-swipe): Added a settings dropdown to choose external voice input engines, explicitly whitelisting `dev.notune.transcribe` (FreeSpeech) in `privacyWhitelist`. Merged PR #4 (`agent/stable-test-signing`) with matching test keystores.
   - Local clone: `reference_repos/android-keyboard`

For the complete technical specifications, sequence diagrams, and source code cross-references, see **[`docs/FUTO.md`](file:///c:/Users/Z/Downloads/PROJECTS/Android_FreeSpeech/docs/FUTO.md)**.
