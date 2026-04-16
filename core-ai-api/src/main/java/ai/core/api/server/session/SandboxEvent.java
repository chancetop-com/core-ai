package ai.core.api.server.session;

/**
 * @author stephen
 */
public class SandboxEvent implements AgentEvent {

    @Override
    public String sessionId() {
        return sessionId;
    }

    public String sessionId;
    public String sandboxId;
    public SandboxEventType type;
    public String message;
    public Long durationMs;

    public SandboxEvent() {}

    public static SandboxEvent creating(String sessionId, String sandboxId) {
        var event = new SandboxEvent();
        event.sessionId = sessionId;
        event.sandboxId = sandboxId;
        event.type = SandboxEventType.CREATING;
        event.message = "Creating sandbox environment...";
        return event;
    }

    public static SandboxEvent ready(String sessionId, String sandboxId, long durationMs) {
        var event = new SandboxEvent();
        event.sessionId = sessionId;
        event.sandboxId = sandboxId;
        event.type = SandboxEventType.READY;
        event.message = "Sandbox is ready";
        event.durationMs = durationMs;
        return event;
    }

    public static SandboxEvent error(String sessionId, String sandboxId, String message) {
        var event = new SandboxEvent();
        event.sessionId = sessionId;
        event.sandboxId = sandboxId;
        event.type = SandboxEventType.ERROR;
        event.message = message;
        return event;
    }

    public static SandboxEvent replacing(String sessionId, String sandboxId) {
        var event = new SandboxEvent();
        event.sessionId = sessionId;
        event.sandboxId = sandboxId;
        event.type = SandboxEventType.REPLACING;
        event.message = "Sandbox encountered error, creating replacement...";
        return event;
    }

    public static SandboxEvent terminated(String sessionId, String sandboxId) {
        var event = new SandboxEvent();
        event.sessionId = sessionId;
        event.sandboxId = sandboxId;
        event.type = SandboxEventType.TERMINATED;
        event.message = "Sandbox terminated";
        return event;
    }
}
