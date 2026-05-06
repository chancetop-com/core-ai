package ai.core.a2a;

/**
 * HTTP/JSON binding paths used by Core-AI A2A endpoints.
 *
 * @author xander
 */
public final class A2AHttpPaths {
    public static final String AGENT_CARD = "/.well-known/agent-card.json";
    public static final String MESSAGE_SEND = "/message/send";
    public static final String MESSAGE_STREAM = "/message/stream";
    public static final String TASKS = "/tasks";
    public static final String TASK_CANCEL = "/cancel";

    private A2AHttpPaths() {
    }
}
