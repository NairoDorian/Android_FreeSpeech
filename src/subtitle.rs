//! Native backend for `LiveSubtitleService` (real-time captions for device audio).
//!
//! Audio arrives via `pushAudio` in small blocks (~64 ms). We build up a
//! *segment* of speech, send the whole segment to a worker thread for
//! transcription roughly once per tick, and display the result as a *partial*
//! (replaceable) caption. When trailing silence is detected — or the segment
//! hits a hard cap — the segment is *finalized*: transcribed once more and
//! committed, then the buffer starts fresh.
//!
//! Three things keep latency low and bounded:
//! - Partial jobs are only submitted while the worker is idle (latest-wins),
//!   so a slow device can never build up a queue and drift behind real time.
//! - Final jobs are always queued (FIFO), so committed text is never lost.
//! - When finals queue up faster than they are processed, the worker folds
//!   them into one batched run: fixed-window models (Whisper) cost nearly
//!   the same per run regardless of audio length, so batching is what lets
//!   a slow device catch up instead of dropping text.

use crossbeam_channel;
use jni::objects::{GlobalRef, JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::engine;

const SAMPLE_RATE: usize = 16_000;
/// Minimum audio between two partial-hypothesis updates (~0.7 s).
const TICK_SAMPLES: usize = 11_200;
/// Trailing silence that finalizes the current segment (~0.7 s).
const FINALIZE_SILENCE_SAMPLES: usize = 11_200;
/// Hard cap on a single segment. Partials re-transcribe the whole current
/// segment, so this also caps the per-job inference cost: the worst-case
/// caption offset is roughly one queued final plus one in-flight job, both
/// proportional to this — keep it short so captions stay close to real time
/// even in dense speech.
const MAX_SEGMENT_SAMPLES: usize = 6 * SAMPLE_RATE;
/// A queued *final* older than this is dropped (a "…" gap is shown instead).
/// This bounds the audio-to-caption offset on devices that can't transcribe
/// in real time — without it the finals queue grows and the captions drift
/// further behind the longer the audio plays. Merging (below) makes this a
/// last resort: it only fires when even batched runs can't keep up.
const MAX_FINAL_LAG_SAMPLES: u64 = (8 * SAMPLE_RATE) as u64;
/// Cap on one merged final run. Models with a fixed input window (Whisper
/// pads everything to 30 s) cost nearly the same per run no matter how short
/// the audio is, so transcribing queued finals one by one wastes most of the
/// run on padding. Folding everything that is already queued into one run
/// amortizes that fixed cost — this is what lets a device that can't keep up
/// segment-by-segment still catch up without dropping text. Kept under the
/// 30 s window so a merged run is still a single model pass.
const MAX_MERGED_SAMPLES: usize = 25 * SAMPLE_RATE;
/// A queued *partial* older than this is stale; skip it (a fresh one follows).
const MAX_PARTIAL_LAG_SAMPLES: u64 = (3 * SAMPLE_RATE) as u64;
/// A partial is only submitted when its predicted transcription time is at
/// most this. Partials are cosmetic — on a slow (or thermally throttled)
/// device the worker's time is better spent on finals, which are what keep
/// the transcript moving. Finals are never gated on cost.
const MAX_PARTIAL_COST_SECS: f32 = 2.0;
/// Audio kept while waiting for speech so the first word isn't clipped (0.4 s).
const PREROLL_SAMPLES: usize = 6_400;
/// Silence kept after the last speech when finalizing on silence (0.2 s).
const FINAL_TAIL_SAMPLES: usize = 3_200;
/// Per-block RMS at or above this counts as sound worth transcribing.
const SPEECH_RMS: f32 = 0.004;
/// Segments shorter than this are dropped as noise (0.25 s).
const MIN_SEGMENT_SAMPLES: usize = 4_000;

struct Job {
    samples: Vec<f32>,
    is_final: bool,
    /// Position of the job's last sample in the overall pushed-audio stream,
    /// used by the worker to measure how stale the job is.
    end_sample: u64,
}

struct BatchSubtitleState {
    /// Current un-finalized speech segment.
    segment: Vec<f32>,
    /// Rolling pre-speech audio, prepended once speech starts.
    preroll: Vec<f32>,
    has_speech: bool,
    /// Consecutive quiet samples at the tail of `segment`.
    silence_run: usize,
    samples_since_tick: usize,
    worker_tx: crossbeam_channel::Sender<Job>,
    worker_busy: Arc<AtomicBool>,
    /// Total samples ever pushed; shared with the worker for lag measurement.
    total_pushed: Arc<AtomicU64>,
    /// Number of final jobs queued but not yet fully processed. While > 0,
    /// partials are not submitted so the worker catches up on finals first.
    pending_finals: Arc<AtomicUsize>,
    /// Measured transcription speed as milli-RTF (compute ms per audio ms),
    /// smoothed by the worker; 0 until the first job completes. Used to
    /// predict a partial's cost before submitting it.
    rtf_milli: Arc<AtomicU32>,
}

struct LiveSubtitleState {
    is_streaming: bool,
    streaming_tx: Option<crossbeam_channel::Sender<Vec<f32>>>,
    batch: Option<BatchSubtitleState>,
}

static LIVE_STATE: Lazy<Mutex<Option<LiveSubtitleState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_initNative(
    mut env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let service_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let engine_arc = match engine::get_engine() {
        Some(arc) => Some(arc),
        None => {
            let _ = engine::ensure_loaded(&mut env, &service);
            engine::get_engine()
        }
    };

    let (supports_streaming, is_r2t2, stream_opts, lang, task) = if let Some(ref eng) = engine_arc {
        let guard = eng.lock().unwrap_or_else(|e| e.into_inner());
        (
            guard.supports_streaming(),
            guard.is_r2t2(),
            guard.default_stream_options(),
            guard.language.clone(),
            guard.task,
        )
    } else {
        (false, false, transcribe_cpp::StreamOptions::default(), None, transcribe_cpp::Task::Transcribe)
    };

    if supports_streaming {
        let (tx, rx) = crossbeam_channel::bounded::<Vec<f32>>(256);
        let eng = engine_arc.unwrap();
        run_streaming_subtitle_worker(vm, service_ref, rx, eng, is_r2t2, stream_opts, lang, task);

        *LIVE_STATE.lock().unwrap() = Some(LiveSubtitleState {
            is_streaming: true,
            streaming_tx: Some(tx),
            batch: None,
        });
        return;
    }

    // Fallback batch pipeline for Whisper / non-streaming models
    let (tx, rx) = crossbeam_channel::unbounded::<Job>();
    let worker_busy = Arc::new(AtomicBool::new(false));
    let total_pushed = Arc::new(AtomicU64::new(0));
    let pending_finals = Arc::new(AtomicUsize::new(0));
    let rtf_milli = Arc::new(AtomicU32::new(0));

    let batch = BatchSubtitleState {
        segment: Vec::new(),
        preroll: Vec::new(),
        has_speech: false,
        silence_run: 0,
        samples_since_tick: 0,
        worker_tx: tx,
        worker_busy: worker_busy.clone(),
        total_pushed: total_pushed.clone(),
        pending_finals: pending_finals.clone(),
        rtf_milli: rtf_milli.clone(),
    };

    *LIVE_STATE.lock().unwrap() = Some(LiveSubtitleState {
        is_streaming: false,
        streaming_tx: None,
        batch: Some(batch),
    });

    run_batch_subtitle_worker(vm, service_ref, rx, worker_busy, total_pushed, pending_finals, rtf_milli);
}

fn run_streaming_subtitle_worker(
    vm: Arc<jni::JavaVM>,
    service_ref: GlobalRef,
    rx: crossbeam_channel::Receiver<Vec<f32>>,
    eng_arc: Arc<Mutex<engine::Engine>>,
    is_r2t2: bool,
    stream_opts: transcribe_cpp::StreamOptions,
    lang: Option<String>,
    task: transcribe_cpp::Task,
) {
    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("Streaming subtitle worker failed to attach: {}", e);
                return;
            }
        };
        let service_obj = service_ref.as_obj();

        let deliver = |env: &mut jni::JNIEnv, text: &str, is_final: bool| {
            if let Ok(jtxt) = env.new_string(text) {
                let _ = env.call_method(
                    service_obj,
                    "onSubtitleText",
                    "(Ljava/lang/String;Z)V",
                    &[(&jtxt).into(), is_final.into()],
                );
            }
        };

        let mut session = {
            let guard = eng_arc.lock().unwrap_or_else(|e| e.into_inner());
            match guard.stream_session() {
                Ok(s) => s,
                Err(err) => {
                    log::error!("Streaming subtitle session error: {}", err);
                    return;
                }
            }
        };

        let mut cur_lang = if is_r2t2 {
            lang.as_deref().map(|l| l.split('-').next().unwrap_or(l).to_string())
        } else {
            lang.clone()
        };

        let mut cur_stream_opts = stream_opts;
        let stream_res = loop {
            let run_opts = transcribe_cpp::RunOptions {
                language: cur_lang.clone(),
                task,
                ..Default::default()
            };
            match session.stream(&run_opts, &cur_stream_opts) {
                Ok(s) => break Ok(s),
                Err(transcribe_cpp::Error::Unsupported(msg)) if cur_lang.is_some() => {
                    let old_lang = cur_lang.take().unwrap();
                    cur_lang = old_lang.split_once('-').map(|(primary, _)| primary.to_string());
                    log::warn!(
                        "LiveSubtitle stream language '{}' rejected ({}); retrying with {:?}",
                        old_lang,
                        msg,
                        cur_lang
                    );
                }
                Err(e) => {
                    if let Some(transcribe_cpp::StreamExtension::ParakeetStream(ref mut p)) = cur_stream_opts.family {
                        if p.att_context_right.is_some() {
                            log::warn!(
                                "LiveSubtitle Parakeet stream att_context_right {:?} rejected ({}); retrying with model default context",
                                p.att_context_right,
                                e
                            );
                            p.att_context_right = None;
                            continue;
                        }
                    }
                    log::error!("Failed to create subtitle stream: {}", e);
                    break Err(e);
                }
            }
        };

        let mut stream = match stream_res {
            Ok(s) => s,
            Err(_) => return,
        };

        // 200 ms silent warmup
        let _ = stream.feed(&vec![0.0f32; 3200]);

        const FEED_CHUNK_SAMPLES: usize = 1600; // 100 ms at 16 kHz
        const BACKLOG_TRIM_SAMPLES: usize = 48000; // 3.0 s at 16 kHz
        const BACKLOG_KEEP_TAIL_SAMPLES: usize = 16000; // 1.0 s at 16 kHz
        let mut pcm_buf: Vec<f32> = Vec::with_capacity(FEED_CHUNK_SAMPLES * 4);

        let mut committed_len: usize = 0;

        while let Ok(chunk) = rx.recv() {
            pcm_buf.extend_from_slice(&chunk);

            // Backlog trimming to prevent audio drift during thermal throttling or video seek
            if pcm_buf.len() > BACKLOG_TRIM_SAMPLES {
                let dropped = pcm_buf.len() - BACKLOG_KEEP_TAIL_SAMPLES;
                pcm_buf.drain(..dropped);
                log::warn!(
                    "LiveSubtitle audio backlog {:.1}s; dropped {:.1}s oldest",
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
                            if update.committed_changed {
                                if text.committed.len() > committed_len {
                                    let new_text = &text.committed[committed_len..];
                                    committed_len = text.committed.len();
                                    let trimmed = new_text.trim();
                                    if !trimmed.is_empty() {
                                        deliver(&mut env, trimmed, true);
                                    }
                                } else if text.committed.len() < committed_len {
                                    committed_len = text.committed.len();
                                }
                                deliver(&mut env, text.tentative.trim(), false);
                            } else if update.tentative_changed {
                                deliver(&mut env, text.tentative.trim(), false);
                            }
                        }
                    }
                    Err(e) => {
                        log::warn!("LiveSubtitle stream feed error: {}", e);
                        break;
                    }
                }
            }
        }

        // Finalize on exit
        if !pcm_buf.is_empty() {
            let _ = stream.feed(&pcm_buf);
        }
        if let Ok(_) = stream.finalize() {
            let text = stream.text();
            if text.committed.len() > committed_len {
                let new_text = &text.committed[committed_len..];
                let trimmed = new_text.trim();
                if !trimmed.is_empty() {
                    deliver(&mut env, trimmed, true);
                }
            }
        }
    });
}

fn run_batch_subtitle_worker(
    vm: Arc<jni::JavaVM>,
    service_ref: GlobalRef,
    rx: crossbeam_channel::Receiver<Job>,
    worker_busy: Arc<AtomicBool>,
    total_pushed: Arc<AtomicU64>,
    pending_finals: Arc<AtomicUsize>,
    rtf_milli: Arc<AtomicU32>,
) {
    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("Subtitle worker failed to attach: {}", e);
                return;
            }
        };
        let service_obj = service_ref.as_obj();
        let mut gap_pending = false;

        let deliver = |env: &mut jni::JNIEnv, text: &str, is_final: bool| {
            if let Ok(jtxt) = env.new_string(text) {
                let _ = env.call_method(
                    service_obj,
                    "onSubtitleText",
                    "(Ljava/lang/String;Z)V",
                    &[(&jtxt).into(), is_final.into()],
                );
            }
        };

        while let Ok(job) = rx.recv() {
            let mut job = job;
            if job.is_final {
                let mut merged = 0usize;
                while job.samples.len() < MAX_MERGED_SAMPLES {
                    match rx.try_recv() {
                        Ok(next) => {
                            if next.is_final {
                                job.samples.extend_from_slice(&next.samples);
                                job.end_sample = next.end_sample;
                                pending_finals.fetch_sub(1, Ordering::SeqCst);
                                merged += 1;
                            }
                        }
                        Err(_) => break,
                    }
                }
                if merged > 0 {
                    log::info!(
                        "Merged {} queued finals into one {:.1}s run",
                        merged + 1,
                        job.samples.len() as f64 / SAMPLE_RATE as f64
                    );
                }
            }

            let lag = total_pushed
                .load(Ordering::SeqCst)
                .saturating_sub(job.end_sample);
            let skip = if job.is_final {
                lag > MAX_FINAL_LAG_SAMPLES
            } else {
                lag > MAX_PARTIAL_LAG_SAMPLES
            };

            if skip {
                if job.is_final {
                    log::warn!(
                        "Dropping final {:.1}s behind real time to catch up",
                        lag as f64 / SAMPLE_RATE as f64
                    );
                    gap_pending = true;
                }
            } else {
                let engine_arc = match engine::get_engine() {
                    Some(arc) => Some(arc),
                    None => {
                        let _ = engine::ensure_loaded(&mut env, service_obj);
                        engine::get_engine()
                    }
                };

                if let Some(engine_arc) = engine_arc {
                    let audio_secs = job.samples.len() as f64 / SAMPLE_RATE as f64;
                    let started = std::time::Instant::now();
                    let res = engine::transcribe_shared(&engine_arc, job.samples);
                    let elapsed = started.elapsed().as_secs_f64();
                    log::info!(
                        "Subtitle {} job: {:.1}s audio in {:.2}s (lag {:.1}s)",
                        if job.is_final { "final" } else { "partial" },
                        audio_secs,
                        elapsed,
                        lag as f64 / SAMPLE_RATE as f64,
                    );

                    let sample = (elapsed / audio_secs * 1000.0) as u32;
                    let old = rtf_milli.load(Ordering::SeqCst);
                    let ema = if old == 0 { sample } else { (old * 7 + sample * 3) / 10 };
                    rtf_milli.store(ema, Ordering::SeqCst);

                    if let Ok(r) = res {
                        let text = r.trim();
                        if !text.is_empty() && gap_pending {
                            deliver(&mut env, "…", true);
                            gap_pending = false;
                        }
                        if !text.is_empty() || job.is_final {
                            deliver(&mut env, text, job.is_final);
                        }
                    }
                }
            }

            if job.is_final {
                pending_finals.fetch_sub(1, Ordering::SeqCst);
            }
            worker_busy.store(false, Ordering::SeqCst);
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *LIVE_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_LiveSubtitleService_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let len = length as usize;
    if len == 0 {
        return;
    }
    let mut input = vec![0.0f32; len];
    if env.get_float_array_region(&data, 0, &mut input).is_err() {
        return;
    }

    let mut guard = LIVE_STATE.lock().unwrap();
    let state = match guard.as_mut() {
        Some(s) => s,
        None => return,
    };

    if state.is_streaming {
        if let Some(ref tx) = state.streaming_tx {
            let _ = tx.try_send(input);
        }
        return;
    }

    let batch = match state.batch.as_mut() {
        Some(b) => b,
        None => return,
    };

    let stream_pos = batch.total_pushed.fetch_add(len as u64, Ordering::SeqCst) + len as u64;

    let rms = (input.iter().map(|&x| x * x).sum::<f32>() / len as f32).sqrt();
    let is_sound = rms >= SPEECH_RMS;

    if !batch.has_speech {
        if is_sound {
            batch.segment = std::mem::take(&mut batch.preroll);
            batch.segment.extend_from_slice(&input);
            batch.has_speech = true;
            batch.silence_run = 0;
            batch.samples_since_tick = batch.segment.len();
        } else {
            batch.preroll.extend_from_slice(&input);
            let excess = batch.preroll.len().saturating_sub(PREROLL_SAMPLES);
            if excess > 0 {
                batch.preroll.drain(..excess);
            }
            return;
        }
    } else {
        batch.segment.extend_from_slice(&input);
        batch.samples_since_tick += len;
        if is_sound {
            batch.silence_run = 0;
        } else {
            batch.silence_run += len;
        }
    }

    let silence_done = batch.silence_run >= FINALIZE_SILENCE_SAMPLES;
    if silence_done || batch.segment.len() >= MAX_SEGMENT_SAMPLES {
        let mut samples = std::mem::take(&mut batch.segment);
        if silence_done {
            let keep = samples.len() - batch.silence_run + FINAL_TAIL_SAMPLES;
            samples.truncate(keep.min(samples.len()));
            batch.has_speech = false;
            batch.preroll.clear();
        } else {
            let from = samples.len().saturating_sub(3 * SAMPLE_RATE);
            let split = crate::audio::find_quietest_split(&samples, from, samples.len());
            batch.segment = samples.split_off(split);
        }
        batch.silence_run = 0;
        batch.samples_since_tick = batch.segment.len();

        if samples.len() >= MIN_SEGMENT_SAMPLES {
            batch.worker_busy.store(true, Ordering::SeqCst);
            batch.pending_finals.fetch_add(1, Ordering::SeqCst);
            let _ = batch.worker_tx.send(Job {
                samples,
                is_final: true,
                end_sample: stream_pos,
            });
        }
    } else if batch.samples_since_tick >= TICK_SAMPLES
        && !batch.worker_busy.load(Ordering::SeqCst)
        && batch.pending_finals.load(Ordering::SeqCst) == 0
        && partial_affordable(batch)
    {
        batch.worker_busy.store(true, Ordering::SeqCst);
        batch.samples_since_tick = 0;
        let _ = batch.worker_tx.send(Job {
            samples: batch.segment.clone(),
            is_final: false,
            end_sample: stream_pos,
        });
    }
}

fn partial_affordable(batch: &BatchSubtitleState) -> bool {
    let rtf = batch.rtf_milli.load(Ordering::SeqCst) as f32 / 1000.0;
    let segment_secs = batch.segment.len() as f32 / SAMPLE_RATE as f32;
    segment_secs * rtf <= MAX_PARTIAL_COST_SECS
}
