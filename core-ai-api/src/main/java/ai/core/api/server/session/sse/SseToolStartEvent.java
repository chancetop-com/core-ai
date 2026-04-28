package ai.core.api.server.session.sse;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.Map;

/**
 * @author stephen
 */
public class SseToolStartEvent extends SseBaseEvent {
    @NotNull
    @Property(name = "call_id")
    public String callId;

    @NotNull
    @Property(name = "tool_name")
    public String toolName;

    @NotNull
    @Property(name = "tool_args")
    public Map<String, Object> toolArgs;

    @Property(name = "tool_notes")
    public String toolNotes;

    @Property(name = "task_id")
    public String taskId;

    @Property(name = "run_in_background")
    public Boolean runInBackground;
}
