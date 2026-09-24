use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crossbeam_channel::{bounded, Receiver, Sender};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::engine;

// --- Optional auto-stop endpointing (same level heuristics as recog_service) --
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;

/// Monotonically increasing generation counter to guard against stale results
/// from previous or aborted recording sessions.
static RECORDING_GEN: AtomicU64 = AtomicU64::new(0);

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread.
struct Endpointing {
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    speech_started: AtomicBool,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_tx: Option<Sender<Vec<f32>>>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor and consumer loop exit.
    pub session_active: Arc<AtomicBool>,
    pub active_gen: u64,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_partial(env: &mut JNIEnv, obj: &JObject, committed: &str, tentative: &str) {
    if let (Ok(jcom), Ok(jten)) = (env.new_string(committed), env.new_string(tentative)) {
        let _ = env.call_method(
            obj,
            "onPartialText",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[(&jcom).into(), (&jten).into()],
        );
    }
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_tx: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        active_gen: 0,
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture and streaming transcription.
/// With `auto_stop` set, a monitor thread watches for trailing silence.
pub fn start_recording(mut env: JNIEnv, state: &mut VoiceSessionState, auto_stop: bool) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    // Increment generation token for this recording session
    let gen = RECORDING_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    state.active_gen = gen;

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    // Create channel for passing audio chunks to the background consumer
    let (tx, rx) = bounded::<Vec<f32>>(256);
    state.audio_tx = Some(tx.clone());

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            last_voice: Mutex::new(Instant::now()),
            noise_floor: Mutex::new(0.0),
            speech_started: AtomicBool::new(false),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);
            let _ = tx.try_send(data.to_vec());

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                let floor = *ep.noise_floor.lock().unwrap();
                let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;
                if is_speech {
                    *ep.last_voice.lock().unwrap() = Instant::now();
                    ep.speech_started.store(true, Ordering::SeqCst);
                } else {
                    // Slowly adapt the noise floor while no speech is present.
                    let mut nf = ep.noise_floor.lock().unwrap();
                    *nf = *nf * 0.95 + level * 0.05;
                }
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            // Spawn streaming inference consumer thread
            let jvm_consumer = state.jvm.clone();
            let target_ref_consumer = state.target_ref.clone();
            let session_active_consumer = session_active.clone();
            let buffer_for_worker = state.audio_buffer.clone();

            std::thread::spawn(move || {
                run_inference_consumer(
                    jvm_consumer,
                    target_ref_consumer,
                    rx,
                    session_active_consumer,
                    buffer_for_worker,
                    gen,
                );
            });

            // Auto-stop monitor thread if requested
            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let speech = ep.speech_started.load(Ordering::SeqCst);
                    let silence = ep.last_voice.lock().unwrap().elapsed();
                    let done = (speech
                        && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ = env.call_method(
                                    target_ref.as_obj(),
                                    "onAutoStop",
                                    "()V",
                                    &[],
                                );
                            }
                        }
                        return;
                    }
                });
            }
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

fn run_inference_consumer(
    jvm: Arc<jni::JavaVM>,
    target_ref: GlobalRef,
    rx: Receiver<Vec<f32>>,
    _session_active: Arc<AtomicBool>,
    audio_buffer: Arc<Mutex<Vec<f32>>>,
    gen: u64,
) {
    let mut env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let target = target_ref.as_obj();

    let is_current = || RECORDING_GEN.load(Ordering::SeqCst) == gen;

    // Ensure engine is loaded
    if engine::get_engine().is_none() {
        if engine::ensure_loaded(&mut env, target).is_err() {
            if is_current() {
                notify_status(&mut env, target, "Error: model failed to load");
            }
            return;
        }
    }

    if !is_current() {
        return;
    }

    let eng_arc = match engine::get_engine() {
        Some(e) => e,
        None => {
            if is_current() {
                notify_status(&mut env, target, "Error: model not available");
            }
            return;
        }
    };

    let (supports_streaming, is_r2t2, r2t2_cadence, lang, task) = {
        let guard = eng_arc.lock().unwrap_or_else(|e| e.into_inner());
        (
            guard.supports_streaming(),
            guard.is_r2t2(),
            guard.r2t2_cadence_ms,
            guard.language.clone(),
            guard.task,
        )
    };

    if supports_streaming {
        // --- Native Streaming Pipeline (Confucius4-R2T2, Parakeet, etc.) ---
        let mut session = {
            let guard = eng_arc.lock().unwrap_or_else(|e| e.into_inner());
            match guard.stream_session() {
                Ok(s) => s,
                Err(err) => {
                    log::error!("Failed to create stream session: {}", err);
                    if is_current() {
                        notify_status(&mut env, target, &format!("Error: {}", err));
                    }
                    return;
                }
            }
        };

        let stream_opts = transcribe_cpp::StreamOptions {
            commit_policy: transcribe_cpp::CommitPolicy::Auto,
            family: if is_r2t2 {
                log::info!("Starting R2T2 native stream with cadence: {} ms", r2t2_cadence);
                Some(transcribe_cpp::StreamExtension::R2T2(
                    transcribe_cpp::R2T2StreamOptions {
                        chunk_size_ms: Some(r2t2_cadence),
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

        let run_opts = transcribe_cpp::RunOptions {
            language: lang,
            task,
            ..Default::default()
        };

        let mut stream = match session.stream(&run_opts, &stream_opts) {
            Ok(s) => s,
            Err(e) => {
                log::error!("Failed to begin stream: {}", e);
                if is_current() {
                    notify_status(&mut env, target, &format!("Error: {}", e));
                }
                return;
            }
        };

        // 200 ms silent warmup (3200 samples at 16 kHz)
        let _ = stream.feed(&vec![0.0f32; 3200]);

        const FEED_CHUNK_SAMPLES: usize = 1600; // 100 ms
        const BACKLOG_TRIM_SAMPLES: usize = 48000; // 3.0 s at 16 kHz
        const BACKLOG_KEEP_TAIL_SAMPLES: usize = 16000; // 1.0 s at 16 kHz
        let mut pcm_buf: Vec<f32> = Vec::with_capacity(FEED_CHUNK_SAMPLES * 4);

        while let Ok(chunk) = rx.recv() {
            if !is_current() {
                stream.reset();
                return;
            }

            pcm_buf.extend_from_slice(&chunk);

            // Backlog trim: if the consumer stalled (post-unfreeze hiccup, page
            // faults) and audio piled up, skip forward to the recent tail rather
            // than grinding through the whole backlog while more arrives.
            if pcm_buf.len() > BACKLOG_TRIM_SAMPLES {
                let dropped = pcm_buf.len() - BACKLOG_KEEP_TAIL_SAMPLES;
                pcm_buf.drain(..dropped);
                log::warn!(
                    "audio backlog {:.1}s; dropped {:.1}s oldest",
                    (dropped + BACKLOG_KEEP_TAIL_SAMPLES) as f32 / 16000.0,
                    dropped as f32 / 16000.0
                );
            }

            while pcm_buf.len() >= FEED_CHUNK_SAMPLES {
                let feed_slice: Vec<f32> = pcm_buf.drain(..FEED_CHUNK_SAMPLES).collect();
                match stream.feed(&feed_slice) {
                    Ok(update) => {
                        if update.committed_changed || update.tentative_changed {
                            let text = stream.text();
                            if is_current() {
                                notify_partial(&mut env, target, &text.committed, &text.tentative);
                            }
                        }
                    }
                    Err(e) => {
                        log::warn!("Stream feed warning: {}", e);
                        break;
                    }
                }
            }
        }

        // Channel closed (stop_recording called)
        if !is_current() {
            stream.reset();
            return;
        }

        // Drain any remaining buffered samples
        if !pcm_buf.is_empty() {
            let _ = stream.feed(&pcm_buf);
        }

        match stream.finalize() {
            Ok(_) => {
                let text = stream.text();
                let final_text = text.display();
                if is_current() {
                    notify_status(&mut env, target, "Ready");
                    notify_text(&mut env, target, &final_text);
                }
            }
            Err(e) => {
                log::error!("Stream finalize error: {}", e);
                if is_current() {
                    notify_status(&mut env, target, &format!("Error: {}", e));
                }
            }
        }
    } else {
        // --- Offline Batch Fallback (Whisper, etc.) ---
        while let Ok(_) = rx.recv() {
            if !is_current() {
                return;
            }
        }

        if !is_current() {
            return;
        }

        let buffer = audio_buffer.lock().unwrap().clone();
        if buffer.len() < 3200 {
            if is_current() {
                notify_status(&mut env, target, "Ready");
                notify_text(&mut env, target, "");
            }
            return;
        }

        if is_current() {
            notify_status(&mut env, target, "Transcribing...");
        }

        let res = engine::transcribe_shared(&eng_arc, buffer);
        if is_current() {
            match res {
                Ok(text) => {
                    notify_status(&mut env, target, "Ready");
                    notify_text(&mut env, target, &text);
                }
                Err(e) => notify_status(&mut env, target, &format!("Error: {}", e)),
            }
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    // Dropping stream stops CPAL audio capture
    state.stream = None;
    // Dropping audio_tx closes the crossbeam channel, triggering the consumer to drain and finalize
    state.audio_tx = None;
    notify_status(&mut env, state.target_ref.as_obj(), "Processing...");
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Invalidate current generation so worker discards any results
    RECORDING_GEN.fetch_add(1, Ordering::SeqCst);
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.audio_tx = None;
    state.audio_buffer.lock().unwrap().clear();
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
