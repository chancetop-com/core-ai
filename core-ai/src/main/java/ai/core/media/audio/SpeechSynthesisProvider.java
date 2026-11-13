package ai.core.media.audio;

/**
 * Speech synthesis SPI used by gateway TTS models.
 *
 * @author stephen
 */
public interface SpeechSynthesisProvider {
    SpeechSynthesisResult synthesize(SpeechSynthesisRequest request);
}
