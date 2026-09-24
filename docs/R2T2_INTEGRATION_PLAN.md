# Architectural Plan: Integrating Confucius4-R2T2 Model

This document outlines the end-to-end architecture and implementation strategy for supporting the **Confucius4-R2T2** streaming speech recognition model in **Android FreeSpeech**.

Reference model file:
`C:\Users\Z\AppData\Roaming\com.nairodorian.zer0\models\r2t2-q4_k_m.gguf` (Size: ~1.18 GB / 1,186,939,968 bytes).
Reference integration: `C:\Users\Z\Downloads\PROJECTS\Handy_V2`.

---

## 1. Model Overview & Characteristics

- **Model Family**: Confucius4-R2T2 (a streaming-optimized variant of the `qwen3_asr` architecture).
- **Format**: GGUF (`r2t2-q4_k_m.gguf`), 4-bit medium k-quantization.
- **Decoding Mechanism**:
  - Re-encodes accumulated audio on each tick and commits the longest stable prefix.
  - Commits text at **character and byte granularity** (ideal for multi-lingual and CJK/Chinese text, as well as English).
- **Latency Control**:
  - Unlike models with discrete presets (`Fastest`/`Balanced`), R2T2 supports a continuous millisecond chunk cadence between **80 ms and 2000 ms**.
  - Default cadence: **320 ms** (16 kHz audio: exactly 16 samples per millisecond, mapping directly without quantization artifacts).
  - Extension probe: `TRANSCRIBE_EXT_KIND_R2T2_STREAM` (`0x32543252u`).

---

## 2. System Architecture & Component Interactions

```
                    ┌───────────────────────────────┐
                    │     Android AudioRecord       │
                    │   (16 kHz Mono PCM Stream)    │
                    └──────────────┬────────────────┘
                                   │
                                   ▼
                    ┌───────────────────────────────┐
                    │    crossbeam-channel Queue    │
                    │  (256 chunks, ~6s headroom)   │
                    └──────────────┬────────────────┘
                                   │
                                   ▼
                    ┌───────────────────────────────┐
                    │    R2T2 Native Stream Worker  │
                    │   (transcribe_r2t2_stream_ext)│
                    │    Cadence: 320ms (User-set)  │
                    └──────────────┬────────────────┘
                                   │
             ┌─────────────────────┴─────────────────────┐
             ▼                                           ▼
┌─────────────────────────┐                 ┌─────────────────────────┐
│     Committed Prefix    │                 │      Tentative Tail     │
│ (Stable, verified text) │                 │(Subject to revision on  │
│                         │                 │     subsequent ticks)   │
└────────────┬────────────┘                 └────────────┬────────────┘
             │                                           │
             └─────────────────────┬─────────────────────┘
                                   │
                                   ▼
                    ┌───────────────────────────────┐
                    │      Android IME Service      │
                    │ InputConnection.setComposing  │
                    │   Text(committed + tentative) │
                    └───────────────────────────────┘
```

---

## 3. Native Layer Implementation

### A. Extension Initialization
In Rust (`src/voice_session.rs` / `src/engine.rs`):
```rust
use transcribe_cpp::{Model, Session, StreamOptions, StreamExtension, R2T2StreamOptions, ExtSlot};

pub fn create_r2t2_stream(
    model: &Model,
    session: &mut Session,
    chunk_ms: u32,
) -> Result<transcribe_cpp::Stream, String> {
    // Probe if model accepts R2T2 streaming extension
    let is_r2t2 = model.accepts_ext(
        ExtSlot::Stream,
        transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_R2T2_STREAM,
    );

    let stream_opts = if is_r2t2 {
        let clamped_ms = chunk_ms.clamp(80, 2000);
        StreamOptions {
            extension: Some(StreamExtension::R2T2(R2T2StreamOptions {
                chunk_size_ms: Some(clamped_ms),
            })),
            ..Default::default()
        }
    } else {
        StreamOptions::default()
    };

    session.stream_with(&stream_opts).map_err(|e| e.to_string())
}
```

### B. Seam & Commit Tracking
R2T2 updates two spans:
1. `committed`: The prefix that the decoder has finalized and will not change.
2. `tentative`: The trailing hypothesis currently being decoded.

In the Android IME, the combination `committed + tentative` is rendered as **composing text** using:
```java
// In RustInputMethodService.java
public void onPartialText(String committed, String tentative) {
    InputConnection ic = getCurrentInputConnection();
    if (ic != null) {
        ic.setComposingText(committed + tentative, 1);
    }
}
```
When recording stops, `ic.finishComposingText()` commits the final transcript cleanly.

---

## 4. Mobile Hardware & Resource Considerations

### A. Memory Footprint (~1.2 GB to 1.6 GB RAM)
- Model file size: 1.18 GB.
- Runtime RAM allocation:
  - Weight mmap / buffer: ~1.2 GB
  - KV-cache & compute buffers: ~150 - 250 MB
  - Total process RAM: ~1.4 - 1.5 GB.
- **Low Memory Killer (LMK) Protection**:
  - Running a 1.5 GB process in the background on mid-range Android devices can trigger Android's LMK.
  - Solution: FreeSpeech must use a **Microphone Foreground Service (`android:foregroundServiceType="microphone"`)** while active, maintaining high process priority (`OOM_ADJ` < 200) to guarantee the OS does not kill the inference engine mid-speech.

### B. Compute Optimization & SoC Acceleration
- **CPU Acceleration**: GGML quantized matmul kernels compiled with `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`.
- **Core Thread Pinning**: Heterogeneous big.LITTLE phone CPUs require capping compute threads to the performance cores (typically 3 or 4 cores) to avoid worker stall at barrier synchronization.
- **GPU Offload (Vulkan)**: For devices with Adreno 7xx+ or Immortalis GPUs, compiling GGML with the Vulkan backend (`-DTRANSCRIBE_VULKAN=ON`) offloads encoder computation, dropping CPU power draw and heat significantly.

---

## 5. Model Distribution & On-Demand Download Strategy

Because Google Play Store limits base APK sizes to 200 MB:
1. **Unbundled Delivery**: The 1.18 GB `r2t2-q4_k_m.gguf` cannot be bundled inside the base APK.
2. **On-Demand Model Manager**:
   - `ModelsActivity.java` will feature a dedicated card for **Confucius4-R2T2 (Multilingual Ultra Streaming)**.
   - Users can download the model on Wi-Fi directly from HuggingFace / CDN to `context.getExternalFilesDir("models")`.
   - Checksum verification via SHA-256 before loading.
   - Once downloaded, it is selectable as the active streaming engine for keyboard input, live subtitles, and phone call STT.
