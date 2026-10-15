package ai.core.schedule;

import java.time.ZonedDateTime;

/**
 * A scheduled task bound to a chat session. When the cron expression fires, the
 * task's {@code input} is injected into the session as a user message so the agent
 * continues executing with the full session context.
 *
 * @author stephen
 */
public class ScheduledTask {
    public String id;
    public String sessionId;
    public String userId;
    public String name;
    public String cronExpression;
    public String timezone;
    public String input;
    public Boolean enabled;
    public ZonedDateTime nextRunAt;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
    /** Owning workspace (absolute path) for local engines; null for server-created tasks and legacy files. */
    public String workspace;
    public ZonedDateTime lastRunAt;
    /** fired | queued | session_missing | failed. */
    public String lastRunStatus;
    public Integer runCount;
    /** Set when the engine disabled the task automatically (e.g. session_missing); null for user disables. */
    public String disabledReason;
}
