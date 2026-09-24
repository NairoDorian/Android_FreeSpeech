# The Android RecognitionService Situation

> [!NOTE]
> The full architectural analysis of Android's `RecognitionService`, upstream Issue #39, Johannes Schindelin's (`dscho`) fork implementation, and the composing-text streaming breakthrough, is located in:
>
> 📖 **[`docs/RECOGNITION_SERVICE.md`](file:///c:/Users/Z/Downloads/PROJECTS/Android_FreeSpeech/docs/RECOGNITION_SERVICE.md)**

---

## Quick Reference Summary

### 1. Upstream Issue #39
- **Link**: [notune/android_transcribe_app#39: "FR: Implement recognition service"](https://github.com/notune/android_transcribe_app/issues/39)
- **Summary**: Users requested that the offline transcription engine be exposed as a system-wide speech-to-text provider in Android system settings (`Settings -> System -> Languages -> Speech-to-text`), enabling assistive tools (such as screen readers like Corvus), third-party keyboards (HeliBoard, Samsung Keyboard), and apps (Duolingo) to use the offline engine without switching IMEs.

### 2. Johannes Schindelin's (`dscho`) Implementation
- **Compare URL**: [Compare upstream vs `dscho:android_transcribe_app:main`](https://github.com/notune/android_transcribe_app/compare/main...dscho:android_transcribe_app:main)
- **Local Clone**: `other_forks/dscho`
- **Key Breakthroughs**:
  1. **`OfflineRecognitionService.java`**: Implemented `android.speech.RecognitionService` with decibel audio level metering (`cb.rmsChanged`), streaming partial results (`cb.partialResults`), and final transcription delivery (`cb.results`).
  2. **Composing Text Streaming (`c1156a3`)**: Replaced broken token-batch streaming with cumulative buffer re-evaluation and atomic updates via `InputConnection.setComposingText()`, completely eliminating word duplication ("bag bank") and end-of-utterance word drops.

For full technical specifications, code references, and architecture diagrams, see **[`docs/RECOGNITION_SERVICE.md`](file:///c:/Users/Z/Downloads/PROJECTS/Android_FreeSpeech/docs/RECOGNITION_SERVICE.md)**.
