package ai.core.media.audio;

/**
 * Upstream speech-to-text failure. The server maps this onto a 400 with the upstream detail preserved,
 * because almost every provider rejection (format, size, model, credential) is caller-actionable.
 *
 * @author stephen
 */
public class TranscriptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TranscriptionException(String message) {
        super(message);
    }

    public TranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
