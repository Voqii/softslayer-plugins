use opus2::{Application, Encoder};
use parking_lot::Mutex;
use serenity::all::ChannelId;
use songbird::{Event, EventContext, EventHandler};
use tracing::{debug, trace, warn};

use crate::audio_util::{mix_voices, OPUS_CHANNELS, SAMPLE_RATE};

/// Largest possible Opus packet (RFC 6716, section 3.4).
const MAX_OPUS_PACKET: usize = 1275;

/// Turns every 20 ms tick of Discord audio into one Opus packet for Simple Voice Chat.
///
/// Songbird decodes each Discord user separately (this also takes care of DAVE end-to-end encryption).
/// We mix everyone who is speaking and encode the result, so several people can talk at once.
/// Upstream forwarded only the first speaker's packet, which made overlapping speech glitch.
pub struct VoiceHandler {
    pub vc_id: ChannelId,
    pub received_audio_tx: flume::Sender<Vec<u8>>,
    encoder: Mutex<Encoder>,
}

impl VoiceHandler {
    pub fn new(vc_id: ChannelId, received_audio_tx: flume::Sender<Vec<u8>>) -> VoiceHandler {
        VoiceHandler {
            vc_id,
            received_audio_tx,
            encoder: Mutex::new(
                Encoder::new(SAMPLE_RATE, OPUS_CHANNELS, Application::Voip)
                    .expect("Unable to make opus encoder"),
            ),
        }
    }
}

/// Mixes and encodes one tick. `None` if nobody had audio.
pub fn encode_tick<'a>(
    encoder: &mut Encoder,
    voices: impl IntoIterator<Item = &'a [i16]>,
) -> Option<Vec<u8>> {
    let mixed = mix_voices(voices)?;
    let mut packet = vec![0u8; MAX_OPUS_PACKET];
    match encoder.encode(&mixed, &mut packet) {
        Ok(len) => {
            packet.truncate(len);
            Some(packet)
        }
        Err(error) => {
            warn!(?error, "Unable to encode mixed Discord audio: {error}");
            None
        }
    }
}

#[serenity::async_trait]
impl EventHandler for VoiceHandler {
    #[tracing::instrument(skip(self, ctx), fields(self.vc_id = %self.vc_id))]
    async fn act(&self, ctx: &EventContext<'_>) -> Option<Event> {
        if let EventContext::VoiceTick(tick) = ctx {
            if tick.speaking.is_empty() {
                trace!("No one speaking");
                return None;
            }
            if self.received_audio_tx.is_full() {
                debug!("Receive buffer is full");
                return None;
            }
            let voices = tick
                .speaking
                .values()
                .filter_map(|data| data.decoded_voice.as_deref());
            let Some(packet) = encode_tick(&mut self.encoder.lock(), voices) else {
                debug!("Speaking users had no decoded audio");
                return None;
            };
            if self.received_audio_tx.send(packet).is_err() {
                warn!("received_audio rx dropped");
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use opus2::Decoder;

    use super::*;
    use crate::audio_util::RAW_AUDIO_SIZE;

    /// 20 ms of a sine tone, starting at sample `offset` so consecutive frames are continuous.
    fn tone(freq: f64, amplitude: f64, offset: usize) -> Vec<i16> {
        (0..RAW_AUDIO_SIZE)
            .map(|i| {
                let t = (offset + i) as f64 / SAMPLE_RATE as f64;
                (amplitude * (2.0 * std::f64::consts::PI * freq * t).sin()) as i16
            })
            .collect()
    }

    /// Strength of one frequency in a block of samples (Goertzel), normalised by length.
    fn power_at(samples: &[i16], freq: f64) -> f64 {
        let w = 2.0 * std::f64::consts::PI * freq / SAMPLE_RATE as f64;
        let coeff = 2.0 * w.cos();
        let (mut s1, mut s2) = (0.0, 0.0);
        for &x in samples {
            let s0 = x as f64 + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (samples.len() as f64).powi(2)
    }

    /// Runs `frames` ticks of the given speakers through the real path: each Discord user's audio is
    /// Opus-encoded (as their client would), decoded (as songbird does), then mixed + encoded by
    /// `encode_tick`, and finally decoded again (as the Simple Voice Chat client does).
    fn round_trip(freqs: &[f64], frames: usize) -> Vec<i16> {
        let mut user_encoders: Vec<Encoder> = freqs
            .iter()
            .map(|_| Encoder::new(SAMPLE_RATE, OPUS_CHANNELS, Application::Voip).unwrap())
            .collect();
        let mut user_decoders: Vec<Decoder> = freqs
            .iter()
            .map(|_| Decoder::new(SAMPLE_RATE, OPUS_CHANNELS).unwrap())
            .collect();
        let mut bridge = Encoder::new(SAMPLE_RATE, OPUS_CHANNELS, Application::Voip).unwrap();
        let mut svc_decoder = Decoder::new(SAMPLE_RATE, OPUS_CHANNELS).unwrap();

        let mut heard = Vec::new();
        for frame in 0..frames {
            let decoded: Vec<Vec<i16>> = freqs
                .iter()
                .enumerate()
                .map(|(u, &f)| {
                    let packet = user_encoders[u]
                        .encode_vec(&tone(f, 6000.0, frame * RAW_AUDIO_SIZE), MAX_OPUS_PACKET)
                        .unwrap();
                    let mut pcm = vec![0i16; RAW_AUDIO_SIZE];
                    let n = user_decoders[u].decode(&packet, &mut pcm, false).unwrap();
                    pcm.truncate(n);
                    pcm
                })
                .collect();
            let packet = encode_tick(&mut bridge, decoded.iter().map(|v| v.as_slice()))
                .expect("speakers had audio");
            let mut pcm = vec![0i16; RAW_AUDIO_SIZE];
            let n = svc_decoder.decode(&packet, &mut pcm, false).unwrap();
            heard.extend_from_slice(&pcm[..n]);
        }
        // Skip the first 100 ms: codec start-up.
        heard.split_off(RAW_AUDIO_SIZE * 5)
    }

    /// Every speaker must come through clearly above background, and at a level close to the loudest
    /// one. (Comparing only against background isn't enough: codec distortion of one tone leaks energy
    /// into other frequencies, so a single speaker can look like several.)
    fn assert_all_heard(freqs: &[f64]) {
        let heard = round_trip(freqs, 50);
        let unrelated = power_at(&heard, 3100.0);
        let powers: Vec<f64> = freqs.iter().map(|&f| power_at(&heard, f)).collect();
        let loudest = powers.iter().cloned().fold(0.0, f64::max);
        for (f, p) in freqs.iter().zip(&powers) {
            assert!(*p > 50.0 * unrelated, "{f} Hz speaker missing: {p} vs background {unrelated}");
            assert!(loudest / p < 4.0, "{f} Hz speaker drowned out: {p} vs loudest {loudest}");
        }
    }

    #[test]
    fn two_people_talking_at_once_are_both_heard() {
        assert_all_heard(&[440.0, 1250.0]);
    }

    #[test]
    fn three_speakers_are_all_heard() {
        assert_all_heard(&[300.0, 900.0, 1700.0]);
    }

    #[test]
    fn mixing_does_not_make_anyone_quieter() {
        // Each speaker should be about as loud in the mix as when they talk alone.
        let together = round_trip(&[440.0, 1250.0], 50);
        let alone_a = round_trip(&[440.0], 50);
        let alone_b = round_trip(&[1250.0], 50);
        let ratio_a = power_at(&together, 440.0) / power_at(&alone_a, 440.0);
        let ratio_b = power_at(&together, 1250.0) / power_at(&alone_b, 1250.0);
        println!("together/alone power: 440 Hz {ratio_a:.2}, 1250 Hz {ratio_b:.2}");
        assert!(ratio_a > 0.7 && ratio_b > 0.7, "440 Hz {ratio_a:.2}, 1250 Hz {ratio_b:.2}");
    }

    #[test]
    fn single_speaker_still_works() {
        let heard = round_trip(&[700.0], 50);
        assert!(power_at(&heard, 700.0) > 50.0 * power_at(&heard, 3100.0));
    }

    #[test]
    fn nobody_speaking_sends_nothing() {
        let mut encoder = Encoder::new(SAMPLE_RATE, OPUS_CHANNELS, Application::Voip).unwrap();
        assert!(encode_tick(&mut encoder, std::iter::empty()).is_none());
        // A speaking user whose frame decoded to nothing counts as silent.
        let empty: Vec<i16> = Vec::new();
        assert!(encode_tick(&mut encoder, [empty.as_slice()]).is_none());
    }

    #[test]
    fn loud_overlapping_speech_clips_instead_of_wrapping_around() {
        let loud = vec![30000i16; RAW_AUDIO_SIZE];
        let quiet_negative = vec![-30000i16; RAW_AUDIO_SIZE];
        let mixed = mix_voices([loud.as_slice(), loud.as_slice()]).unwrap();
        assert!(mixed.iter().all(|&s| s == i16::MAX));
        let mixed = mix_voices([quiet_negative.as_slice(), quiet_negative.as_slice()]).unwrap();
        assert!(mixed.iter().all(|&s| s == i16::MIN));
    }

    #[test]
    fn odd_frame_lengths_are_padded_or_truncated() {
        let short = vec![100i16; 10];
        let long = vec![50i16; RAW_AUDIO_SIZE * 2];
        let mixed = mix_voices([short.as_slice(), long.as_slice()]).unwrap();
        assert_eq!(mixed[0], 150);
        assert_eq!(mixed[9], 150);
        assert_eq!(mixed[10], 50);
        assert_eq!(mixed[RAW_AUDIO_SIZE - 1], 50);
    }
}
