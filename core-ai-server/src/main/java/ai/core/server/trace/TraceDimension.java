package ai.core.server.trace;

/**
 * Trace dimensions the admin analytics screen ranks by.
 *
 * @author stephen
 */
public enum TraceDimension {
    SOURCE("source"),
    AGENT("agent_id"),
    USER("user_id"),
    PROVIDER("provider_id"),
    MODEL("model");

    public final String field;

    TraceDimension(String field) {
        this.field = field;
    }
}
