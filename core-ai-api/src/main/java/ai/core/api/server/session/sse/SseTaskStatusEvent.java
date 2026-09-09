package ai.core.api.server.session.sse;

import core.framework.api.json.Property;

/**
 * A background task the session started — a long-running tool call or a backgrounded sub-agent — reached
 * a terminal state. Unlike every other event here this one does not belong to the turn that is streaming:
 * it arrives while the session is idle and is the only warning a client gets that a new turn is about to
 * begin on its own.
 *
 * @author stephen
 */
public class SseTaskStatusEvent extends SseBaseEvent {
    @Property(name = "task_id")
    public String taskId;

    /** the tool that owns the task, when the source knows it; sub-agent tasks have none */
    @Property(name = "tool_name")
    public String toolName;

    /** completed | failed | cancelled */
    @Property(name = "status")
    public String status;
}
