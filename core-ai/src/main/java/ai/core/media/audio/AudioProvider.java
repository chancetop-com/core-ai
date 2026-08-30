package ai.core.media.audio;

/**
 * Audio SPI (design §9.2): TTS covers voice-reference cold starts, narration and patch dubbing;
 * ASR is the REQUIRED subtitle-timecode step — dialogue lives inside the model-generated native
 * audio, so transcription is the only way to get word timestamps. Kept separate from MediaProvider
 * so the existing SPI stays untouched (design §5).
 *
 * @author stephen
 */
public interface AudioProvider {
    TtsResult synthesize(TtsSpec spec);

    AsrResult transcribe(AsrSpec spec);
}
