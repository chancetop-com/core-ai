package ai.core.media.audio;

/**
 * @author stephen
 */
public class SpeechSynthesisException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SpeechSynthesisException(String message) {
        super(message);
    }

    public SpeechSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
