package ai.core.server.schedule;

import ai.core.server.session.AgentSessionManager;
import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * @author xander
 */
public class IdleSessionCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdleSessionCleanupJob.class);
    // 60 min not 30: turns may run long (multiple LLM calls + tool runs); touchActivity
    // currently fires only on command boundaries, not per-chunk, so we leave headroom.
    private static final Duration IDLE_THRESHOLD = Duration.ofMinutes(60);

    @Inject
    AgentSessionManager sessionManager;

    @Override
    public void execute(JobContext context) {
        var closed = sessionManager.cleanupIdleSessions(IDLE_THRESHOLD);
        if (closed > 0) {
            LOGGER.info("idle-session-cleanup closed {} session(s), threshold={}min", closed, IDLE_THRESHOLD.toMinutes());
        }
    }
}
