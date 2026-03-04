package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class SseToolResultEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "call_id")
    public String callId;

    @NotNull
    @Property(name = "tool_name")
    public String toolName;

    @NotNull
    @Property(name = "status")
    public String status;

    @Property(name = "result")
    public String result;
}
