//! Audio capture selection and conversion helpers.

use anyhow::{anyhow, Result};
use cpal::traits::DeviceTrait;

pub const MODEL_SAMPLE_RATE: u32 = 16_000;

/// A capture configuration the device explicitly reports as supported.
pub struct InputFormat {
    pub config: cpal::StreamConfig,
    pub sample_format: cpal::SampleFormat,
}

/// Pick a supported input format, preferring mono and a rate nearest the model.
pub fn select_input_format(device: &cpal::Device) -> Result<InputFormat> {
    let mut best: Option<(u64, InputFormat)> = None;
    for range in device.supported_input_configs()? {
        let min = range.min_sample_rate().0;
        let max = range.max_sample_rate().0;
        let rate = MODEL_SAMPLE_RATE.clamp(min, max);
        let channels = range.channels();
        // Mono avoids needless device-side work; rate distance breaks ties.
        let score = (u64::from(channels.saturating_sub(1)) << 32)
            + u64::from(rate.abs_diff(MODEL_SAMPLE_RATE));
        let candidate = InputFormat {
            config: cpal::StreamConfig {
                channels,
                sample_rate: cpal::SampleRate(rate),
                buffer_size: cpal::BufferSize::Default,
            },
            sample_format: range.sample_format(),
        };
        if best.as_ref().map_or(true, |(old, _)| score < *old) {
            best = Some((score, candidate));
        }
    }
    best.map(|(_, format)| format)
        .ok_or_else(|| anyhow!("no supported input configuration"))
}

/// Stateful downmixer/resampler from a device's native capture format to the
/// mono 16 kHz samples expected by the inference engine.
pub struct CaptureConverter {
    channels: usize,
    step: f64,
    position: f64,
    samples: Vec<f32>,
}

impl CaptureConverter {
    pub fn new(channels: u16, sample_rate: u32) -> Self {
        Self {
            channels: usize::from(channels),
            step: f64::from(sample_rate) / f64::from(MODEL_SAMPLE_RATE),
            position: 0.0,
            samples: Vec::new(),
        }
    }

    /// Accept interleaved native samples and return newly available mono 16 kHz
    /// samples. Retaining the final source frame makes interpolation continuous
    /// across CPAL callbacks.
    pub fn convert(&mut self, input: &[f32]) -> Vec<f32> {
        if self.channels == 0 {
            return Vec::new();
        }
        for frame in input.chunks_exact(self.channels) {
            self.samples
                .push(frame.iter().sum::<f32>() / self.channels as f32);
        }
        let mut output = Vec::new();
        while self.position + 1.0 < self.samples.len() as f64 {
            let base = self.position as usize;
            let fraction = (self.position - base as f64) as f32;
            output.push(
                self.samples[base] + (self.samples[base + 1] - self.samples[base]) * fraction,
            );
            self.position += self.step;
        }
        let consumed = self.position as usize;
        if consumed > 0 {
            self.samples.drain(..consumed);
            self.position -= consumed as f64;
        }
        output
    }
}

pub fn i16_to_f32(input: &[i16]) -> Vec<f32> {
    input.iter().map(|&s| s as f32 / i16::MAX as f32).collect()
}

pub fn u16_to_f32(input: &[u16]) -> Vec<f32> {
    input
        .iter()
        .map(|&s| (s as f32 / u16::MAX as f32) * 2.0 - 1.0)
        .collect()
}

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        let energy: f32 = samples[i..i + WIN].iter().map(|&x| x * x).sum();
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        i += WIN / 2;
    }
    best_pos
}

#[cfg(test)]
mod tests {
    use super::CaptureConverter;

    #[test]
    fn downmixes_and_resamples_stereo_input() {
        let mut converter = CaptureConverter::new(2, 32_000);
        // Stereo frame averages are 0, 1, 2 and 3. At 32 kHz, every other
        // frame is retained for the 16 kHz model input.
        let output = converter.convert(&[0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0]);
        assert_eq!(output, vec![0.0, 2.0]);
    }

    #[test]
    fn retains_interpolation_frame_across_callbacks() {
        let mut converter = CaptureConverter::new(1, 8_000);
        assert_eq!(converter.convert(&[0.0, 1.0]), vec![0.0, 0.5]);
        assert_eq!(converter.convert(&[2.0]), vec![1.0, 1.5]);
    }
}
