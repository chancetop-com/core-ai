package ai.core.schedule;

import java.time.ZonedDateTime;

/**
 * One execution record of a {@link ScheduledTask}: written every time the task fires
 * (automatic or via Run now) so the desktop page can show what actually happened.
 *
 * @author stephen
 */
public class ScheduledTaskRun {
    public String id;
    public String taskId;
    public String sessionId;
    /** The cron occurrence this run was meant for; null for a manual run. */
    public ZonedDateTime scheduledFor;
    public ZonedDateTime firedAt;
    /** firedAt - scheduledFor in milliseconds; null for a manual run. */
    public Long lateMillis;
    public Boolean manual;
    /** fired | queued | session_missing | failed. */
    public String status;
    public String error;
}
