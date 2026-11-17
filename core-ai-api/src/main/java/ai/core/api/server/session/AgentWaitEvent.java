package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Non-terminal progress of a runtime completion barrier: the agent parked its current turn
 * waiting for an async task to reach a terminal state.
 *
 * @author stephen
 */
public class AgentWaitEvent implements AgentEvent {
    public static final String WAITING = "waiting";
    public static final String RESUMING = "resuming";
    public static final String CLEARED = "cleared";

    public static AgentWaitEvent of(String sessionId, String phase, String taskId) {
        var event = new AgentWaitEvent();
        event.sessionId = sessionId;
        event.phase = phase;
        event.taskId = taskId;
        return event;
    }

    @NotNull
    @Property(name = "sessionId")
    public String sessionId;

    @NotNull
    @Property(name = "phase")
    public String phase;

    @NotNull
    @Property(name = "taskId")
    public String taskId;

    @Property(name = "toolCallId")
    public String toolCallId;

    @Property(name = "backend")
    public String backend;

    @Property(name = "generation")
    public Long generation;

    /** absolute epoch millis, null when the wait has no explicit deadline */
    @Property(name = "deadlineMs")
    public Long deadlineMs;

    @Property(name = "waitedMs")
    public Long waitedMs;

    @Override
    public String sessionId() {
        return sessionId;
    }
}
