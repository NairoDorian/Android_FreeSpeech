# Migration Plan: Upgrading to NairoDorian/transcribe.cpp

This document specifies the technical roadmap and implementation steps to replace the legacy `cjpais/transcribe.cpp` (v0.1.3) with the modern, high-performance fork **[NairoDorian/transcribe.cpp](https://github.com/NairoDorian/transcribe.cpp)** (v0.2.3), available locally at `C:\Users\Z\Downloads\PROJECTS\Unified_Audio.cpp\transcribe.cpp`.

---

## 1. Motivations & Key Upgrades in NairoDorian/transcribe.cpp

| Feature | Legacy `cjpais/transcribe.cpp` (v0.1.3) | `NairoDorian/transcribe.cpp` (v0.2.3) |
|---|---|---|
| **Architecture Support** | Whisper and basic Parakeet only | Whisper, Parakeet TDT, Nemotron Streaming, Voxtral, Moonshine, Sortformer Diarization, and **Confucius4-R2T2** (`qwen3_asr` variant) |
| **Streaming Engine** | Primitive, monolithic lock holding shared session | True per-run `Session` architecture (`model.session_with(&opts)`), `Send` sessions, lock-free channel feeding |
| **R2T2 Native Streaming** | Unsupported | Fully supported via `TRANSCRIBE_EXT_KIND_R2T2_STREAM` (`include/transcribe/r2t2.h`) and `R2T2StreamOptions` with continuous millisecond latency (80..=2000 ms) |
| **Binary Footprint** | Monolithic C++ compilation of all architectures | `minimal-multilingual` feature flag compiling only high-value architectures (< 1 MB core library) |
| **C++ & GGML Core** | Outdated GGML snapshot | Modern GGML with optimized ARMv8.2-A+dotprod+fp16 quantized matmuls and Vulkan/GPU acceleration |
| **Concurrency Safety** | Global session mutex blocking all threads | Per-model native compute lock; multiple sessions can coexist cleanly |

---

## 2. Cargo Dependency Configuration

In `Android_FreeSpeech/Cargo.toml`, update the dependency to point to `NairoDorian/transcribe.cpp`:

```toml
[dependencies]
cpal = "0.15"
log = "0.4"
android_logger = "0.13"
anyhow = "1.0"
jni = "0.21"
libc = "0.2"
once_cell = "1.19"
crossbeam-channel = "0.5"

# Upstream NairoDorian/transcribe.cpp fork
transcribe-cpp = { git = "https://github.com/NairoDorian/transcribe.cpp", branch = "main", default-features = false, features = ["minimal-multilingual"] }

# For local development with Unified_Audio.cpp/transcribe.cpp:
# [patch."https://github.com/NairoDorian/transcribe.cpp"]
# transcribe-cpp = { path = "../Unified_Audio.cpp/transcribe.cpp/bindings/rust/transcribe-cpp" }
# transcribe-cpp-sys = { path = "../Unified_Audio.cpp/transcribe.cpp" }
```

---

## 3. Rust Code Refactoring (Session & Stream Lifecycle)

Under `transcribe-cpp 0.2.3`:

### A. Engine Model & Session Storage
In `src/engine.rs`:
```rust
pub struct Engine {
    pub model: transcribe_cpp::Model,
    pub session: transcribe_cpp::Session,
    pub session_options: transcribe_cpp::SessionOptions,
}

impl Engine {
    pub fn load(model_path: &Path, threads: i32) -> Result<Engine, String> {
        let model = transcribe_cpp::Model::load(model_path).map_err(|e| e.to_string())?;
        let session_options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };
        let session = model.session_with(&session_options).map_err(|e| e.to_string())?;
        Ok(Engine {
            model,
            session,
            session_options,
        })
    }

    /// Spawns an independent session for a streaming dictation run
    pub fn stream_session(&self) -> Result<transcribe_cpp::Session, String> {
        self.model.session_with(&self.session_options).map_err(|e| e.to_string())
    }
}
```

### B. Streaming Dictation with Channel Queue
In `src/voice_session.rs`, adopt the `space-shell` channel-driven architecture:
1. Audio capture callback pushes 16 kHz PCM chunks (`FEED_CHUNK_SAMPLES = 1600` ~ 100ms) into a bounded crossbeam channel (`CHANNEL_CHUNKS = 256`).
2. Dedicated consumer thread owns `transcribe_cpp::Session`.
3. If model accepts R2T2 streaming:
   ```rust
   let r2t2_opts = transcribe_cpp::R2T2StreamOptions {
       chunk_size_ms: Some(320),
   };
   let stream_opts = transcribe_cpp::StreamOptions {
       extension: Some(transcribe_cpp::StreamExtension::R2T2(r2t2_opts)),
       ..Default::default()
   };
   let mut stream = session.stream_with(&stream_opts)?;
   ```
4. As audio arrives, `stream.feed(&chunk)`.
5. Read partial results (`stream.partial()`) and dispatch to Java UI via `onPartialText`.
6. When recording ends, feed remaining audio, call `stream.finish()`, and dispatch `onTextTranscribed`.

---

## 4. CMake & Android NDK Compilation

In `app/build.gradle.kts`:
- Forward `TRANSCRIBE_CMAKE_ARGS` to CMake via cargo-ndk:
  - `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (leverages hardware dot product instructions on ARM Cortex-A75, Cortex-A76, Cortex-X1+, Kryo, etc.)
  - Future GPU acceleration flag: `-DTRANSCRIBE_VULKAN=ON` for Adreno / Mali mobile GPUs.
