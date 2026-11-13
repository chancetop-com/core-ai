package ai.core.server.trace;

/**
 * Trace dimensions the UI can facet on.
 *
 * @author stephen
 */
public enum TraceFacet {
    MODEL("model"),
    AGENT("agent_name"),
    SOURCE("source");

    public final String field;

    TraceFacet(String field) {
        this.field = field;
    }

    public static TraceFacet from(String field) {
        if (field == null) return null;
        return switch (field) {
            case "model" -> MODEL;
            case "agentName", "agent_name" -> AGENT;
            case "source" -> SOURCE;
            default -> null;
        };
    }
}
