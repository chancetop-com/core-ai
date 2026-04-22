package ai.core.api.server.session.sse;

import ai.core.api.server.session.SandboxEventType;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SseSandboxEvent extends SseBaseEvent {
    @Property(name = "sandbox_id")
    public String sandboxId;

    @Property(name = "sandbox_type")
    public SandboxEventType sandboxType;

    @Property(name = "message")
    public String message;

    @Property(name = "duration_ms")
    public Long durationMs;
}
