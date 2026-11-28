package ai.core.server.skill.telemetry;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

/**
 * Flushes buffered server-side skill runtime telemetry deltas to PostgreSQL every 10 seconds.
 *
 * @author stephen
 */
public class SkillTelemetryFlushJob implements Job {
    @Inject
    SkillTelemetryBuffer buffer;

    @Override
    public void execute(JobContext context) {
        buffer.flush();
    }
}
