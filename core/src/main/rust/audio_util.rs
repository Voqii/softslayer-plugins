use opus2::Channels;
use tracing::{trace, warn};

/// 1 second of RawAudio
pub const MAX_AUDIO_BUFFER: usize = 50;
pub const RAW_AUDIO_SIZE: usize = 960;
/// 20 ms of 16-bit PCM
pub type RawAudio = [i16; RAW_AUDIO_SIZE];

pub const SAMPLE_RATE: u32 = 48000;

pub const OPUS_CHANNELS: Channels = Channels::Mono;
pub const CHANNELS: u32 = OPUS_CHANNELS as u32;

pub fn combine_audio_parts(parts: Vec<RawAudio>) -> RawAudio {
    // Based on https://github.com/DV8FromTheWorld/JDA/blob/11c5bf02a1f4df3372ab68e0ccb4a94d0db368df/src/main/java/net/dv8tion/jda/internal/audio/AudioConnection.java#L529
    let Some(max_length) = parts.iter().map(|p| p.len()).max() else {
        // .max() gives None on empty
        return [0; RAW_AUDIO_SIZE];
    };
    trace!(max_length);
    let mut mixed = [0; RAW_AUDIO_SIZE];
    let mut sample: i32;
    for i in 0..max_length {
        if i >= mixed.len() {
            warn!(len = mixed.len(), "Audio parts are bigger than 20ms! Some audio may be lost. Please report to GitHub Issues!");
            break;
        }
        sample = 0;
        for part in &parts {
            // We don't need to check part.len() against i, because,
            // unlike Java, we have a guarantee part is of length
            // RAW_AUDIO_SIZE and we already checked that i isn't above that 😎
            sample += part[i] as i32;
        }
        if sample > i16::MAX as i32 {
            mixed[i] = i16::MAX;
        } else if sample < i16::MIN as i32 {
            mixed[i] = i16::MIN;
        } else {
            mixed[i] = sample as i16
        }
    }
    mixed
}

/// Mixes one 20 ms frame of decoded audio from every Discord user who is speaking this tick.
///
/// Each voice should be [`RAW_AUDIO_SIZE`] mono samples (songbird is configured to decode to 48 kHz mono).
/// Shorter voices are padded with silence and longer ones are truncated, so one odd frame can't take the
/// others down with it. Returns `None` when nobody has audio, so silence isn't sent to Simple Voice Chat.
pub fn mix_voices<'a>(voices: impl IntoIterator<Item = &'a [i16]>) -> Option<RawAudio> {
    let parts: Vec<RawAudio> = voices
        .into_iter()
        .filter(|voice| !voice.is_empty())
        .map(|voice| {
            if voice.len() != RAW_AUDIO_SIZE {
                warn!(len = voice.len(), "Decoded Discord audio is not 20 ms of mono audio");
            }
            let mut frame = [0i16; RAW_AUDIO_SIZE];
            let len = voice.len().min(RAW_AUDIO_SIZE);
            frame[..len].copy_from_slice(&voice[..len]);
            frame
        })
        .collect();
    if parts.is_empty() {
        None
    } else {
        Some(combine_audio_parts(parts))
    }
}

/// volume should be between 0 and 1
pub fn adjust_volume(audio: &mut RawAudio, volume: f64) {
    for sample in audio {
        let res = (*sample as f64 * volume).round();
        if res > i16::MAX as f64 {
            *sample = i16::MAX;
        } else if res < i16::MIN as f64 {
            *sample = i16::MIN;
        } else {
            *sample = res as i16;
        }
    }
}
