package ai.core.media;

/**
 * Ownership scope of a media store lookup. The server uses both fields for the authorization check;
 * the CLI leaves {@code userId} null.
 *
 * @author core-ai
 */
public record SessionMediaKey(String sessionId, String userId) {
    public SessionMediaKey {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
    }
}
