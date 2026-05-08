package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

public class SseBatchToolStartEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "group")
    public String group;

    @NotNull
    @Property(name = "tools")
    public List<ToolInfo> tools;

    @Property(name = "task_id")
    public String taskId;

    public static class ToolInfo {
        @NotNull
        @Property(name = "call_id")
        public String callId;

        @NotNull
        @Property(name = "tool_name")
        public String toolName;

        @NotNull
        @Property(name = "arguments")
        public String arguments;
    }
}
