package ai.core.server.gateway;

/**
 * @author Stephen
 */
public record MediaJobOwner(String userId, String sessionId, String agentRunId) {
    public static final MediaJobOwner UNKNOWN = new MediaJobOwner(null, null, null);
}
