# Comprehensive Architectural Plan: Confucius4-R2T2 Streaming Model Integration

---

## Executive Summary

This document specifies the complete engineering blueprint for integrating the **Confucius4-R2T2** streaming speech-to-text model into **Android FreeSpeech**. 

Confucius4-R2T2 represents a generational leap in open-weights speech recognition: an ultra-low-latency streaming model based on NetEase Youdao's specialized `qwen3_asr` architecture that supports continuous millisecond latency tuning (80 ms – 2000 ms) and commits stable text at fine-grained character and byte boundaries.

The deployment artifact is the **Arm M** quantized GGUF (`r2t2-q4_k_m.gguf`, 1.187 GB), verified locally from the ZER0 project:
`C:\Users\Z\AppData\Roaming\com.nairodorian.zer0\models\r2t2-q4_k_m.gguf` (1,186,939,968 bytes).

---

## 1. Model Architecture & Quantization Science

### A. Network Topology
Confucius4-R2T2 utilizes a shared encoder-decoder graph derived from the Qwen3-ASR family:
- **Audio Tower / Encoder**: 24 Transformer layers, hidden dimension 1024, projected audio dimension 2048, 80-channel log-mel filterbank input.
- **Causal Decoder**: 28 decoder layers, 16 query heads, 8 key-value (KV) heads (Grouped Query Attention), 128 head width, rotary position embeddings (RoPE), SwiGLU activation.
- **Reference Precision**: BF16 base weights (707 tensors, 4.076 GB).
- **Publisher**: NetEase Youdao (`netease-youdao/Confucius4-R2T2`, checkpoint `185ce639118ad1362d049ca0d8ed04b6ec5cd6c9`).

### B. The Arm M Quantization Precision Ladder
Standard uniform quantization (e.g. Q4_0 or pure Q4_K) severely degrades R2T2: attention or tower degradation leads to empty outputs, while quantizing `down_proj` below Q6_K causes severe cross-lingual hallucination (emitting English for German/Russian audio).

Based on empirical measurements in `confucius4_r2t2-quantization.md`, the **Arm M** composite represents the strict accuracy-and-size floor:

| Sub-Module / Block | Quantization Type | Technical Rationale |
|---|---|---|
| **Audio Tower** | `MIXED{BF16, Q4_K}` | Encoder front-end retains BF16 sensitivity; bulk layers at Q4_K. |
| **Attention Query/Key/Value** | `Q4_K` | Multi-head attention tolerates 4-bit representation without WER loss. |
| **MLP Gate / Up Projections** | `Q4_K` | Error bounded by SwiGLU non-linearity. |
| **MLP Down Projection (`down_proj`)** | **`Q6_K` (Hard Floor)** | Writes directly into residual stream; error compounds across layers. Must stay at $\ge$ Q6_K. |
| **Embed Tokens (`embed_tokens`)** | `Q2_K` | Tied input/output token embeddings compressed aggressively to stay at 1.187 GB. |
| **Total Artifact Size** | **1.187 GB** | **52% smaller than reference Q8_0 (2.478 GB)**, fitting within mobile RAM budgets. |

---

## 2. Native Streaming Protocol: `TRANSCRIBE_EXT_KIND_R2T2_STREAM`

Unlike conventional streaming models that only accept coarse discrete latency presets (`Fast`/`Balanced`), Confucius4-R2T2 features a continuous cadence parameter:

```
                    16 kHz Mono Audio Input
                              │
                    ┌─────────▼─────────┐
                    │ 1 ms = 16 samples │
                    └─────────┬─────────┘
                              │
         ┌────────────────────┴────────────────────┐
         ▼                                         ▼
   Min Cadence: 80 ms                       Max Cadence: 2000 ms
  (1,280 audio samples)                    (32,000 audio samples)
         │                                         │
         └────────────────────┬────────────────────┘
                              │
                    Default Cadence: 320 ms
                     (5,120 audio samples)
```

### A. C / C++ Extension Contract (`include/transcribe/r2t2.h`)
```c
#define TRANSCRIBE_EXT_KIND_R2T2_STREAM 0x32543252u

struct transcribe_r2t2_stream_ext {
    struct transcribe_ext ext;
    uint32_t              chunk_size_ms; // Accepted range: [80, 2000] ms
};

TRANSCRIBE_API void transcribe_r2t2_stream_ext_init(struct transcribe_r2t2_stream_ext * ext);
```

### B. Safe Rust Extension Wrapper (`family.rs`)
```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct R2T2StreamOptions {
    /// Native decoding cadence in integer milliseconds [80, 2000].
    /// Defaults to 320 ms.
    pub chunk_size_ms: Option<u32>,
}

pub enum StreamExtension {
    ParakeetStream(ParakeetStreamOptions),
    R2T2(R2T2StreamOptions),
    // ...
}
```

---

## 3. Real-Time Seam Tracking & Text Reveal Mechanics

R2T2 employs a re-decoding causal scheme: on each tick, accumulated audio is processed, the decoder hypothesis is aligned, and the longest stable prefix is committed.

```
Time T1:  [  The quick brown fox jumps  ] [ over the ]
          └───────────┬───────────────┘   └────┬─────┘
                  Committed (Stable)       Tentative

Time T2:  [  The quick brown fox jumps over the lazy dog  ] [ and runs ]
          └─────────────────────┬───────────────────────┘   └────┬─────┘
                           Committed (Moved Seam)            Tentative
```

### Key Differences from Word-Level Models (e.g. Parakeet/Whisper):
1. **Character/Byte Granularity**: The seam between `committed` and `tentative` often falls inside a word (e.g. `turn le` + `ft`). For Chinese/Japanese/Korean text, characters have no inter-word spaces.
2. **Flicker-Free Android Composing Text**:
   - The IME MUST NOT insert a space between `committed` and `tentative`.
   - The combined string `committed + tentative` is passed directly to `InputConnection.setComposingText(fullText, 1)`.
   - Only upon finalization (`stream.finalize()`) is the composing region finished via `finishComposingText()` and followed by sentence-level spacing.

---

## 4. Mobile Hardware & Resource Management

### A. Memory Layout on Android

```
┌─────────────────────────────────────────────────────────────┐
│ Android FreeSpeech Process Address Space                    │
│                                                             │
│ ┌───────────────────────────┐ ┌───────────────────────────┐ │
│ │  mmap() Model Weights     │ │  KV Cache & Compute Graph │ │
│ │  (Clean pages, discardable│ │  (Dirty anonymous RAM,    │ │
│ │   by OS under RAM pressure│ │   allocated per Session)  │ │
│ │   ~1,187 MB               │ │   ~180 MB - 240 MB        │ │
│ └───────────────────────────┘ └───────────────────────────┘ │
│ ┌───────────────────────────┐ ┌───────────────────────────┐ │
│ │  Mel Filterbank & Audio   │ │  JVM Heap & UI State      │ │
│ │  ~50 MB                   │ │  ~60 MB                   │ │
│ └───────────────────────────┘ └───────────────────────────┘ │
│                                                             │
│ Total Process Footprint: ~1.45 GB Peak                      │
└─────────────────────────────────────────────────────────────┘
```

### B. Low Memory Killer (LMK) Protection
On Android, a background process holding 1.45 GB of RAM will be killed aggressively when foreground apps launch (e.g. camera, games, browser).
- **Mitigation**: Promote the voice dictation service to a **Microphone Foreground Service** (`android:foregroundServiceType="microphone"`) displaying an active recording notification while streaming is in progress.
- This drops the Linux kernel `oom_score_adj` to $\le 200$, guaranteeing the OS will not terminate the inference worker mid-sentence.

### C. CPU Core Pinning & Scheduling
Modern smartphone processors are heterogeneous (e.g. Cortex-X3 prime core + Cortex-A715 performance cores + Cortex-A510 efficiency cores).
- GGML introduces synchronization barriers after each tensor operation.
- If a worker thread is scheduled on a slow efficiency core, all performance cores stall waiting at the barrier.
- **Rule**: Cap inference threads to **3 or 4 cores** matching the count of performance/prime cores detected from `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`.

### D. Hardware Acceleration (ARMv8.2-A+dotprod+fp16)
Passed via `TRANSCRIBE_CMAKE_ARGS` to cargo-ndk:
`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`
- Enables ARM `SDOT`/`UDOT` 4-way vector multiply-accumulate instructions.
- Enables native FP16 half-precision register math on ARMv8.2-A+ architectures (Snapdragon 845+, Tensor, Dimensity 8000+).

---

## 5. End-to-End Implementation Blueprint

### A. Engine Factory Probe (`src/engine.rs`)

```rust
impl Engine {
    /// Detects whether the currently loaded model accepts the R2T2 streaming extension
    pub fn is_r2t2(&self) -> bool {
        self.model.accepts_ext(
            transcribe_cpp::ExtSlot::Stream,
            transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_R2T2_STREAM,
        )
    }

    /// Spawns an isolated session configured for the active model architecture
    pub fn stream_session(&self) -> Result<transcribe_cpp::Session, String> {
        self.model.session_with(&self.session_options).map_err(|e| e.to_string())
    }
}
```

### B. Streaming Worker Loop (`src/voice_session.rs`)

```rust
// In stream_session_body:
let is_r2t2 = engine.is_r2t2();
let r2t2_chunk_ms = read_r2t2_chunk_setting().unwrap_or(320);

let stream_opts = transcribe_cpp::StreamOptions {
    commit_policy: transcribe_cpp::CommitPolicy::Auto,
    family: if is_r2t2 {
        log::info!("Starting R2T2 native stream with cadence: {} ms", r2t2_chunk_ms);
        Some(transcribe_cpp::StreamExtension::R2T2(
            transcribe_cpp::R2T2StreamOptions {
                chunk_size_ms: Some(r2t2_chunk_ms),
            },
        ))
    } else {
        Some(transcribe_cpp::StreamExtension::ParakeetStream(
            transcribe_cpp::ParakeetStreamOptions {
                att_context_right: Some(1),
            },
        ))
    },
    enable_vad: true,
    vad_threshold: 0.50,
    ..Default::default()
};

let mut stream = session.stream(&transcribe_cpp::RunOptions::default(), &stream_opts)
    .map_err(|e| e.to_string())?;

// 200ms silent warmup
let _ = stream.feed(&vec![0.0f32; 3200]);

// Feed loop
while buf.len() >= FEED_CHUNK_SAMPLES {
    let chunk: Vec<f32> = buf.drain(..FEED_CHUNK_SAMPLES).collect();
    let update = stream.feed(&chunk).map_err(|e| e.to_string())?;
    if update.committed_changed || update.tentative_changed {
        let text = stream.text();
        if is_current() {
            notify_partial(&mut env, obj, &text.committed, &text.tentative);
        }
    }
}
```

### C. Android Settings & UI Controls

1. **Preference Persistence (`settings.gradle` / `SharedPreferences`)**:
   - `r2t2_chunk_size_ms`: Integer preference constrained between `80` and `2000` (default `320`).
2. **Continuous Slider UI**:
   - Exposed under **Settings $\to$ Speech Models $\to$ R2T2 Latency Cadence**:
   - Slider with 1 ms step and numeric input box allowing fast presets (`80 ms (Ultra)`, `160 ms (Fast)`, `320 ms (Balanced)`, `640 ms (Accurate)`).
3. **Model Selection Card**:
   - Distinct badge: `Confucius4-R2T2 1.7B (Multilingual Ultra Streaming)`.
   - Size: `1.18 GB`.

---

## 6. Model Download & Distribution Strategy

Because Google Play Store limits APK base downloads to 200 MB, the 1.187 GB model is delivered on-demand:

```
┌─────────────────────────────────────────────────────────────┐
│ Direct Wi-Fi Download                                       │
│ Source: Hugging Face LFS / Zero CDN                         │
│ Target: context.getExternalFilesDir("models")               │
│ Filename: r2t2-q4_k_m.gguf                                  │
│ Expected SHA-256: Verified against manifest                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Sideload via Android Storage Access Framework (SAF)         │
│ User imports .gguf downloaded from PC or browser            │
│ App streams file directly into private app storage          │
└─────────────────────────────────────────────────────────────┘
```

---

## 7. Verification & Acceptance Criteria

| Stage | Verification Action | Success Condition |
|---|---|---|
| **1. Model Load** | Load `r2t2-q4_k_m.gguf` via `Engine::load` | Model loads within 1.5s; reports `is_r2t2() == true`. |
| **2. Cadence Test** | Test stream across 80, 160, 320, 640, 1280, 2000 ms | Native extension accepts exact millisecond without rounding. |
| **3. Seam Tracking** | Dictate mixed English and multilingual sentences | `setComposingText` updates without letter duplication or flicker. |
| **4. LMK Stability** | Record continuously for 120 seconds while switching apps | Foreground service prevents process termination. |
| **5. Memory Clean** | Stop and cancel recording 20 times in rapid succession | Memory footprint returns to baseline (~1.2 GB mmap); zero leaks. |
