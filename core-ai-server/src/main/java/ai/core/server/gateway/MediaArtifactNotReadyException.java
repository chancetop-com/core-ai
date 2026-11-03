package ai.core.server.gateway;

/**
 * The video job is completed but its artifact is not stored yet; the caller should retry after a short delay.
 *
 * @author Stephen
 */
public class MediaArtifactNotReadyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MediaArtifactNotReadyException(String message) {
        super(message);
    }
}
