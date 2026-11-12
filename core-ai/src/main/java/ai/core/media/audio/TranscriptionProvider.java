package ai.core.media.audio;

/**
 * Speech-to-text SPI. Kept separate from {@link AudioProvider}: the render pipeline needs TTS with
 * word timestamps, while clients only need one clip transcribed.
 *
 * @author stephen
 */
public interface TranscriptionProvider {
    TranscriptionResult transcribe(TranscriptionRequest request);
}
