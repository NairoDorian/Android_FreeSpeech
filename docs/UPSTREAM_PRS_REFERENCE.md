# Upstream Pull Requests Reference & Analysis

This document provides a detailed breakdown of notable Pull Requests on [notune/android_transcribe_app/pulls](https://github.com/notune/android_transcribe_app/pulls). These PRs contain essential bug fixes, UX refinements, and performance improvements that are directly relevant to **Android FreeSpeech**.

---

## 1. High-Value Dictation & IME Improvements (Oleg Kondratev / `gagebt`)

A series of modular PRs submitted in late 2026 addressing fundamental keyboard dictation edge cases:

### PR #114: Let users choose how long to pause before dictation stops
- **PR URL**: [notune/android_transcribe_app#114](https://github.com/notune/android_transcribe_app/pull/114)
- **Status**: OPEN
- **Problem**: Fixed 2-second silence timeout is too aggressive for slow speakers and too sluggish for quick commands.
- **Solution**: Exposes configurable auto-stop pause duration in settings (e.g. 1.0s, 1.5s, 2.0s, 3.0s, or disable).
- **Recommendation for FreeSpeech**: **Adopt**. Users need explicit control over silence detection thresholds.

### PR #115: Make dictated text fit the sentence around the cursor
- **PR URL**: [notune/android_transcribe_app#115](https://github.com/notune/android_transcribe_app/pull/115)
- **Status**: OPEN
- **Problem**: Dictated text often inserts with mismatched leading spaces or capitalized words in the middle of existing sentences.
- **Solution**: Inspects the characters immediately preceding the cursor via `InputConnection.getTextBeforeCursor()`. Automatically trims leading whitespace if after a space, and lowercases initial letters unless preceded by sentence-ending punctuation (`.`, `!`, `?`).
- **Recommendation for FreeSpeech**: **Adopt**. Dramatically improves typing feel and reduces manual edits.

### PR #116: Skip clear silence when transcribing speech
- **PR URL**: [notune/android_transcribe_app#116](https://github.com/notune/android_transcribe_app/pull/116)
- **Status**: OPEN
- **Problem**: Long periods of silence at the beginning or end of audio waste compute cycles and degrade model focus.
- **Solution**: Conservative energy-based silence compactor that trims leading/trailing silence buffers before passing PCM data into the inference engine, while retaining the original audio buffer for retry.
- **Recommendation for FreeSpeech**: **Adopt**. Improves inference speed and battery efficiency on mobile.

### PR #117: Return to the previous keyboard after dictation
- **PR URL**: [notune/android_transcribe_app#117](https://github.com/notune/android_transcribe_app/pull/117)
- **Status**: OPEN
- **Problem**: When using the voice input keyboard as a dictation tool, users must manually tap the globe/keyboard switch button every time to return to Gboard, SwiftKey, or their primary keyboard.
- **Solution**: Auto-switches back using `switchToPreviousInputMethod()` (API 28+) or `switchToLastInputMethod()` (API 26/27) immediately after a successful text insertion. Keeps keyboard open if insertion fails or is uncertain.
- **Recommendation for FreeSpeech**: **Adopt as a toggleable setting**.

### PR #118: Keep dictated text available when insertion fails
- **PR URL**: [notune/android_transcribe_app#118](https://github.com/notune/android_transcribe_app/pull/118)
- **Status**: OPEN
- **Problem**: If the target input connection rejects the text or loses focus mid-insertion, the dictated transcript is lost forever.
- **Solution**: Retains transcribed text in a recovery state; surfaces compact `Copy`, `Discard`, and `Insert` buttons directly on the keyboard surface.
- **Recommendation for FreeSpeech**: **Adopt**. Critical for preventing user frustration.

---

## 2. Integration & Recognition Services

### PR #105: Transcribe caller-supplied audio so any app can use this recognizer
- **PR URL**: [notune/android_transcribe_app#105](https://github.com/notune/android_transcribe_app/pull/105)
- **Author**: Datawav
- **Status**: OPEN
- **Key Insight**: Implements the `EXTRA_AUDIO_SOURCE` URI / pipe in `VoiceRecognitionService`, allowing external voice-recording apps to send their own audio streams directly to FreeSpeech's offline recognizer.
- **Recommendation for FreeSpeech**: **Adopt**. Fundamental for modularity, interoperability, and phone call STT pipes.

### PR #25: Expose Offline Voice Input to SwiftKey + add a proper animation
- **PR URL**: [notune/android_transcribe_app#25](https://github.com/notune/android_transcribe_app/pull/25)
- **Author**: JsBergbau
- **Status**: MERGED
- **Key Insight**: Ensures SwiftKey's microphone key triggers the offline voice input service seamlessly.

### PR #19: Add auto-start recording option & fix voice recorder not started on non-English keyboards
- **PR URL**: [notune/android_transcribe_app#19](https://github.com/notune/android_transcribe_app/pull/19)
- **Author**: Noah (notune)
- **Status**: MERGED
- **Key Insight**: Auto-record on keyboard open and locale compatibility.

---

## 3. Keyboard UI & Inset Fixes

### PR #100: Fixes #99: Keep the keyboard height after a theme change
- **PR URL**: [notune/android_transcribe_app#100](https://github.com/notune/android_transcribe_app/pull/100)
- **Author**: Héctor Álvarez (`hectoraal`)
- **Status**: OPEN
- **Fix**: Calls `inputView.requestApplyInsets()` upon recreating the view when night mode toggles.
- **Status in Android FreeSpeech**: **Applied and integrated**.

### PR #104: Add simple manual text correction features (IME edit row)
- **PR URL**: [notune/android_transcribe_app#104](https://github.com/notune/android_transcribe_app/pull/104)
- **Author**: Héctor Álvarez (`hectoraal`)
- **Status**: OPEN
- **Feature**: Adds optional navigation arrows, select all, copy, and paste controls directly to the dictation bar.

### PR #102: [Accessibility] Add an optional side position for the voice input panel
- **PR URL**: [notune/android_transcribe_app#102](https://github.com/notune/android_transcribe_app/pull/102)
- **Author**: Héctor Álvarez (`hectoraal`)
- **Status**: OPEN
- **Feature**: One-handed mode alignment for the record button and panel.

### PR #80: Add floating voice input, local history, and custom words
- **PR URL**: [notune/android_transcribe_app#80](https://github.com/notune/android_transcribe_app/pull/80)
- **Author**: `djurcola`
- **Status**: OPEN
- **Feature**: Floating draggable overlay, transcript history log, and user-defined custom dictionary / vocabulary booster.

---

## 4. Native Engine & Hardware Acceleration

### PR #93: Add runtime-selected Armv8-A backend
- **PR URL**: [notune/android_transcribe_app#93](https://github.com/notune/android_transcribe_app/pull/93)
- **Author**: Andreas Kallinteris
- **Status**: OPEN
- **Key Insight**: While `armv8.2-a+dotprod+fp16` delivers optimal speed on modern chips, older ARM64 SoCs (pre-2018 or low-end cores) lack dotprod/fp16 and crash or fail load checks. PR #93 provides a runtime fallback to standard ARMv8-A.

### PR #50: Bumped transcribe-rs from 0.1.4 to 0.3.10
- **PR URL**: [notune/android_transcribe_app#50](https://github.com/notune/android_transcribe_app/pull/50)
- **Status**: CLOSED (Superseded by direct `transcribe-cpp` FFI).

### PR #27: Add optional “Pause audio while recording”
- **PR URL**: [notune/android_transcribe_app#27](https://github.com/notune/android_transcribe_app/pull/27)
- **Author**: JsBergbau
- **Status**: MERGED
- **Feature**: Automatically duck/pause background media playback using Android `AudioManager` audio focus requests.

---

## 5. Action Plan for Android FreeSpeech

1. **Step 1 (Done)**: Integrated cross-platform NDK host detection and PR #100 theme insets fix.
2. **Step 2**: Port PR #115 (cursor text fitting) and PR #117 (auto-return to previous keyboard).
3. **Step 3**: Port PR #118 (dictated text recovery) and PR #116 (energy silence trimming).
4. **Step 4**: Incorporate PR #105 (caller-supplied audio pipe) to prepare for Shizuku call recording integration.
