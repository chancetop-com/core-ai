package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class SseToolApprovalRequestEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "call_id")
    public String callId;

    @NotNull
    @Property(name = "tool_name")
    public String toolName;

    @Property(name = "arguments")
    public String arguments;

    @Property(name = "suggested_pattern")
    public String suggestedPattern;
}
