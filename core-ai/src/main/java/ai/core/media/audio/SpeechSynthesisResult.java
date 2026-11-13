package ai.core.media.audio;

/**
 * @author stephen
 */
public record SpeechSynthesisResult(byte[] audio, String contentType, String voice) {
    public SpeechSynthesisResult {
        if (audio == null) audio = new byte[0];
    }
}
