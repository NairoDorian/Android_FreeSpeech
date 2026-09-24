# Comprehensive Migration Plan: Upgrading to NairoDorian/transcribe.cpp (v0.2.3)

---

## Executive Summary

The objective of this migration is to transition **Android FreeSpeech** from the outdated, monolithic `cjpais/transcribe.cpp` (v0.1.3) to the modern, modular, and high-performance **[NairoDorian/transcribe.cpp](https://github.com/NairoDorian/transcribe.cpp)** (v0.2.3, available locally at `C:\Users\Z\Downloads\PROJECTS\Unified_Audio.cpp\transcribe.cpp`).

This upgrade provides the essential foundation required for **Confucius4-R2T2** streaming, eliminates global mutex deadlocks, shrinks binary footprint via modular compilation, and hardens the Android JNI interface against crashes and cold restarts.

---

## 1. Architectural Evolution: 0.1.3 vs. 0.2.3

```
LEGACY ARCHITECTURE (0.1.3)
┌─────────────────────────────────────────────────────────────┐
│ Global Singleton Mutex: Arc<Mutex<Session>>                 │
│ ❌ Single Session per model: One-shot runs & streams race   │
│ ❌ Monolithic lock held during entire multi-second stream   │
│ ❌ Only Parakeet and Whisper supported                      │
│ ❌ No native VAD, no R2T2 streaming extension              │
└─────────────────────────────────────────────────────────────┘

MODERN ARCHITECTURE (0.2.3 - NairoDorian Fork)
┌─────────────────────────────────────────────────────────────┐
│ transcribe_cpp::Model (Arc-backed weight storage, Send+Sync)│
│                      │                                      │
│         ┌────────────┴────────────┐                         │
│         ▼                         ▼                         │
│  Session A (One-shot)      Session B (Streaming)            │
│  - File Transcribe         - Dictation IME (Channel-fed)    │
│  - Live Subtitles          - Real-time R2T2 Cadence         │
│         │                         │                         │
│         └────────────┬────────────┘                         │
│                      ▼                                      │
│  Per-Model Compute Lease (Non-blocking queue / Busy guard)  │
│  - Zero audio thread blocking                               │
│  - Native Earshot VAD + Stable Prefix Commits               │
└─────────────────────────────────────────────────────────────┘
```

### Detailed Feature Comparison

| Capability | Legacy `cjpais/transcribe.cpp` (0.1.3) | `NairoDorian/transcribe.cpp` (0.2.3) |
|---|---|---|
| **Model Separation** | `Model` and `Session` conflated in one handle | Clean separation: `Model` is `Send + Sync` (weights mmapped); `Session` is `Send` (per-thread compute context) |
| **Streaming Mechanics** | Monolithic lock; caller must block while recording | Per-run `Session` + `Stream` handle with lock-free channel feeding (`crossbeam-channel`) |
| **Output Semantics** | Monolithic raw string replacement | Structured `StreamText`: `committed` (stable prefix) + `tentative` (volatile tail) |
| **Voice Activity Detection** | External rudimentary amplitude threshold | Engine-native **Earshot VAD** (`enable_vad`, `vad_threshold`, `vad_prefill_ms`, `vad_hangover_ms`) |
| **Model Architectures** | Whisper, Parakeet basic | Whisper, Parakeet TDT, Nemotron Streaming, Voxtral Realtime, Moonshine, Sortformer Diarization, and **Confucius4-R2T2** (`qwen3_asr` variant) |
| **R2T2 Streaming Support** | ❌ Not available | ✅ Fully supported via `TRANSCRIBE_EXT_KIND_R2T2_STREAM` (`R2T2StreamOptions`) with continuous 80–2000 ms cadence |
| **Binary Footprint** | All architectures compiled in (~40 MB .so) | `minimal-multilingual` feature compiling only mobile-critical architectures (< 15 MB .so) |
| **SIMD & Acceleration** | Baseline ARMv8-A fallback | Native `armv8.2-a+dotprod+fp16` kernel dispatch; Vulkan mobile GPU offload ready |
| **Concurrency Contract** | Deadlocks if second thread calls engine | Compute lease with explicit `Error::Busy` instead of thread lock contention |

---

## 2. Cargo & Build System Configuration

### A. Manifest Setup (`Cargo.toml`)

In `Android_FreeSpeech/Cargo.toml`:

```toml
[package]
name = "android_transcribe_app"
version = "0.2.0"
edition = "2021"

[lib]
name = "android_transcribe_app"
crate-type = ["cdylib"]

[dependencies]
# Audio I/O and System
cpal = "0.15"
log = "0.4"
android_logger = "0.13"
anyhow = "1.0"
jni = "0.21"
libc = "0.2"
once_cell = "1.19"
crossbeam-channel = "0.5"

# Upstream NairoDorian/transcribe.cpp
transcribe-cpp = { git = "https://github.com/NairoDorian/transcribe.cpp", branch = "main", default-features = false, features = ["minimal-multilingual"] }

# Local workspace development override:
# To build against local Unified_Audio.cpp during offline dev, uncomment:
# [patch."https://github.com/NairoDorian/transcribe.cpp"]
# transcribe-cpp = { path = "../Unified_Audio.cpp/transcribe.cpp/bindings/rust/transcribe-cpp" }
# transcribe-cpp-sys = { path = "../Unified_Audio.cpp/transcribe.cpp" }
```

### B. CMake & NDK Flags (`app/build.gradle.kts`)

The cargo-ndk build task must pass the required flags through `TRANSCRIBE_CMAKE_ARGS`:

```kotlin
val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust native code via cargo-ndk"
    group = "build"
    workingDir = rootProject.projectDir

    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath

    environment("ANDROID_NDK_HOME", ndkDir)
    environment("ANDROID_NDK_ROOT", ndkDir)
    environment("ANDROID_NDK", ndkDir)

    // Hardware acceleration: Enable ARMv8.2-A with Dot Product and FP16 math
    // Model set: Build lean mobile composite (Nemotron, Parakeet, Granite, Qwen3-ASR/R2T2)
    environment(
        "TRANSCRIBE_CMAKE_ARGS",
        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DTRANSCRIBE_MODEL_SET=minimal-multilingual"
    )

    val jniLibsDir = project.file("src/main/jniLibs")
    val cargoExecutable = System.getenv("CARGO")
        ?: File(System.getProperty("user.home"), ".cargo/bin/cargo")
            .takeIf { it.isFile }
            ?.absolutePath
        ?: "cargo"

    commandLine(
        cargoExecutable, "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )
    
    // libc++_shared.so dynamic copy across host OSes (Windows/Mac/Linux)
    doLast {
        val ndkPath = environment["ANDROID_NDK_HOME"] as String
        val prebuiltRoot = File("$ndkPath/toolchains/llvm/prebuilt")
        val osName = System.getProperty("os.name").lowercase()
        val hostTag = when {
            osName.startsWith("windows") -> "windows-x86_64"
            osName.startsWith("mac") || osName.startsWith("darwin") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val candidates = buildList {
            add(File(prebuiltRoot, hostTag))
            prebuiltRoot.listFiles()?.filter { it.isDirectory }?.let { addAll(it) }
        }
        val relative = "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
        val libcpp = candidates.map { File(it, relative) }.firstOrNull { it.exists() }

        if (libcpp != null) {
            val destDir = File(jniLibsDir, "arm64-v8a")
            destDir.mkdirs()
            libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
            println("Copied libc++_shared.so from ${libcpp.parentFile}")
        } else {
            throw GradleException("libc++_shared.so not found under ${prebuiltRoot.absolutePath}")
        }
    }
}
```

---

## 3. Subsystem Refactoring Specifications

### A. Engine Singleton & Context (`src/engine.rs`)

The `Engine` struct must retain the `transcribe_cpp::Model` so that multiple `Session` instances can be produced on demand:

```rust
pub struct Engine {
    pub model: transcribe_cpp::Model,
    pub session: transcribe_cpp::Session,
    pub session_options: transcribe_cpp::SessionOptions,
    pub language: Option<String>,
    pub task: transcribe_cpp::Task,
    pub run_ext: Option<transcribe_cpp::RunExtension>,
    pub ready_status: &'static str,
}

impl Engine {
    pub fn load(
        model_path: &Path,
        language: Option<String>,
        translate: bool,
        threads: i32,
    ) -> Result<Engine, String> {
        if !model_path.is_file() {
            return Err(format!("Model file not found: {}", model_path.display()));
        }

        let model = transcribe_cpp::Model::load(model_path).map_err(|e| e.to_string())?;

        let task = if translate && model.capabilities().supports_translate {
            transcribe_cpp::Task::Translate
        } else {
            transcribe_cpp::Task::Transcribe
        };

        let ready_status = if translate && task == transcribe_cpp::Task::Transcribe {
            "Ready (this model can't translate)"
        } else {
            "Ready"
        };

        // Whisper single-pass decode optimization (avoid multi-temperature retry stall)
        let run_ext = if model.accepts_ext(
            transcribe_cpp::ExtSlot::Run,
            transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_WHISPER_RUN,
        ) {
            Some(transcribe_cpp::RunExtension::Whisper(
                transcribe_cpp::WhisperRunOptions {
                    temperature_inc: Some(0.0),
                    ..Default::default()
                },
            ))
        } else {
            None
        };

        let session_options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };

        let session = model
            .session_with(&session_options)
            .map_err(|e| e.to_string())?;

        Ok(Engine {
            model,
            session,
            session_options,
            language,
            task,
            run_ext,
            ready_status,
        })
    }

    /// Spawns an isolated Session owned by a single streaming thread
    pub fn stream_session(&self) -> Result<transcribe_cpp::Session, String> {
        self.model
            .session_with(&self.session_options)
            .map_err(|e| e.to_string())
    }

    /// Transcribes a buffer using the batch session
    pub fn transcribe(&mut self, samples: Vec<f32>) -> Result<String, String> {
        let opts = transcribe_cpp::RunOptions {
            language: self.language.clone(),
            task: self.task,
            family: self.run_ext.clone(),
            ..Default::default()
        };
        self.session
            .run(&samples, &opts)
            .map(|t| t.text)
            .map_err(|e| e.to_string())
    }
}
```

#### Panic & Poison Hardening
Wrap `transcribe_shared()` with `std::panic::catch_unwind`:
```rust
pub fn transcribe_shared(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    let audio_secs = samples.len() as f64 / 16_000.0;
    let started = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = engine.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.transcribe(samples)
    }))
    .unwrap_or_else(|_| {
        log::error!("transcription panicked; recovering poisoned lock and returning error");
        Err("transcription failed unexpectedly, please try again".to_string())
    });
    log::info!("transcribed {:.1}s audio in {:.2}s", audio_secs, started.elapsed().as_secs_f64());
    result
}
```

---

### B. Streaming Voice Session (`src/voice_session.rs`)

The streaming session handles live audio capture, chunk queueing, consumer decoding, and JNI upcalls.

#### Key Architectural Elements:
1. **SendStream Wrapper**:
   `cpal::Stream` is `!Send` due to platform thread affinity. Wrapping it in a type implementing `Send` ensures safe ownership transfers to the background session:
   ```rust
   pub struct SendStream(pub cpal::Stream);
   unsafe impl Send for SendStream {}
   ```

2. **Decoupled Audio Consumer**:
   - Audio capture thread pushes 16 kHz mono chunks (`FEED_CHUNK_SAMPLES = 1600` ~ 100ms) into a 256-slot `crossbeam_channel::bounded`.
   - Consumer thread runs `run_stream_consumer()` independently off the main thread.
   - Initial warmup with 200ms silence eliminates first-decode stall from the user's perception.

3. **Stream Lifecycle Execution**:
   ```rust
   // Prepare stream options matching model architecture
   let is_r2t2 = engine.model.accepts_ext(
       transcribe_cpp::ExtSlot::Stream,
       transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_R2T2_STREAM,
   );

   let stream_opts = transcribe_cpp::StreamOptions {
       commit_policy: transcribe_cpp::CommitPolicy::Auto,
       family: if is_r2t2 {
           Some(transcribe_cpp::StreamExtension::R2T2(
               transcribe_cpp::R2T2StreamOptions {
                   chunk_size_ms: Some(320), // 320ms default cadence
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

   let mut stream = session.stream(&transcribe_cpp::RunOptions::default(), &stream_opts)?;
   ```

4. **Live Hypothesis Delivery**:
   ```rust
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

5. **Stale Session Shielding**:
   Every recording increments a 64-bit `AtomicU64` generation counter. Consumer threads capture the active generation and discard results if a newer session has started, preventing ghost text insertions.

---

### C. Android IME Integration (`RustInputMethodService.java`)

1. **Two-Span Composing Text**:
   The native layer passes both `committed` and `tentative` strings via JNI:
   ```java
   public void onPartialText(String committed, String tentative) {
       mainHandler.post(() -> {
           if (!inputActive) return;
           InputConnection ic = getCurrentInputConnection();
           if (ic != null) {
               // Renders committed + tentative as composing text (underlined in modern IMEs)
               ic.setComposingText(committed + tentative, 1);
           }
       });
   }
   ```

2. **Utterance Finalization**:
   When the user stops speaking or auto-stop triggers:
   ```java
   public void onTextTranscribed(String finalResult) {
       mainHandler.post(() -> {
           InputConnection ic = getCurrentInputConnection();
           if (ic != null) {
               ic.finishComposingText();
               if (!finalResult.isEmpty()) {
                   // Ensure word boundary space if not already present
                   ic.commitText(finalResult + " ", 1);
               }
           }
       });
   }
   ```

---

### D. File Transcription & Subtitle Services

1. **File Transcription (`src/transcribe_file.rs`)**:
   - Clamp `jint` input lengths: ensure `length > 0` before casting to `usize` to prevent integer underflow and memory exhaustion.
   - Run file decoding on background worker threads via `engine::transcribe_shared()`.
   - Prevent OOM on multi-hour audio files by chunking audio into 60s windows with quiet-point boundary splitting.

2. **Live Subtitles (`src/subtitle.rs`)**:
   - Audio chunks from Android MediaProjection / AudioPlaybackCapture continue using `engine::transcribe_shared()`.
   - Per-model compute lease ensures subtitles gracefully wait if a dictation session is actively computing, avoiding process termination.

---

## 4. Phase-by-Phase Migration Roadmap

```
┌──────────────────────────────────────────────────────────────┐
│ Phase 1: Dependency & Build Pipeline Update                  │
│ • Update Cargo.toml to NairoDorian/transcribe.cpp v0.2.3     │
│ • Add TRANSCRIBE_MODEL_SET=minimal-multilingual to CMake args│
│ • Verify cargo-ndk compilation on arm64-v8a                  │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 2: Engine Core Refactor (src/engine.rs)                │
│ • Decouple Model and Session handles                         │
│ • Implement stream_session() factory                         │
│ • Add catch_unwind and lock recovery in transcribe_shared()  │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 3: Streaming Channel Pipeline (src/voice_session.rs)   │
│ • Implement SendStream and crossbeam-channel buffer          │
│ • Build stream_session_body with Stream.feed() & VAD         │
│ • Add generation token guards against stale deliveries       │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 4: Activity & IME Service Synchronization              │
│ • Update RustInputMethodService with setComposingText        │
│ • Harden TranscribeFileActivity & VoiceRecognitionService    │
│ • Validate theme insets retention and background FGS         │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 5: Testing, Validation & Verification                  │
│ • Run unit tests and benchmark on 16kHz WAV fixtures         │
│ • Build debug APK via ./gradlew assembleDebug                │
│ • Verify zero memory leaks across consecutive recordings     │
└──────────────────────────────────────────────────────────────┘
```

---

## 5. Risk Matrix & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **`libc++_shared.so` missing or mismatched** | App crash at launch (`UnsatisfiedLinkError`) | Gradle task dynamically searches host toolchains (`windows-x86_64`, `linux-x86_64`, `darwin-x86_64`) and bundles the matching NDK runtime. |
| **Android LMK kills process during long inference** | Dictation lost, keyboard disappears | Promote active recording to Foreground Service (`foregroundServiceType="microphone"`) so OS assigns foreground `OOM_ADJ` priority. |
| **CPAL audio stream thread affinity error** | Panics when moving `Stream` across threads | Wrap in `SendStream` with explicit unsafe `Send` implementation (guaranteed by single-threaded usage pattern). |
| **Inference stalls after process freeze/unfreeze** | Audio channel overflows and drops session | 200 ms silent warmup before real audio + dynamic backlog trim (drops oldest audio instead of crashing). |
| **Old ARM64 phones without dotprod/fp16** | SIGILL crash on older CPUs (pre-2018) | Engine checks `/proc/cpuinfo` / HWCAPs at initialization and displays clear user warning instead of native crash. |
