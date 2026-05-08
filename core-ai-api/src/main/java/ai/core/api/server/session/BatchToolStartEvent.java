package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

public record BatchToolStartEvent(
        @Property(name = "session_id") String sessionId,
        @Property(name = "group") String group,
        @Property(name = "tools") List<ToolInfo> tools,
        @Property(name = "task_id") String taskId) implements AgentEvent {

    public record ToolInfo(
            @Property(name = "call_id") String callId,
            @Property(name = "tool_name") String toolName,
            @Property(name = "arguments") String arguments) {
    }
}
