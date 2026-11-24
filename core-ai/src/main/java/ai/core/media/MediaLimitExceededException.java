package ai.core.media;

/**
 * @author core-ai
 */
public class MediaLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long limitBytes;
    private final long actualBytes;

    public MediaLimitExceededException(String message, long limitBytes, long actualBytes) {
        super(message);
        this.limitBytes = limitBytes;
        this.actualBytes = actualBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long actualBytes() {
        return actualBytes;
    }
}
