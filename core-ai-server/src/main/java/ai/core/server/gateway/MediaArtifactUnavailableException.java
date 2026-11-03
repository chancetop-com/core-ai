package ai.core.server.gateway;

/**
 * The upstream provider no longer holds the generated video, so the artifact can never be retrieved.
 *
 * @author Stephen
 */
public class MediaArtifactUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MediaArtifactUnavailableException(String message) {
        super(message);
    }

    public MediaArtifactUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
