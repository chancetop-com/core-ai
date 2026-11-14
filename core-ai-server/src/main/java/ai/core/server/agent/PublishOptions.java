package ai.core.server.agent;

/**
 * Options of one publish. {@code expectedRevision} is {@code If-Match}; a null value keeps legacy callers
 * working, but the server still commits with the revision it read.
 *
 * @author core-ai
 */
public record PublishOptions(Integer expectedRevision, String idempotencyKey) {
    public static PublishOptions none() {
        return new PublishOptions(null, null);
    }
}
