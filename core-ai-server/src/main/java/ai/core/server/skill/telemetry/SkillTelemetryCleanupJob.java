package ai.core.server.skill.telemetry;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically removes expired telemetry batch idempotency keys and aged closed papercut records.
 *
 * @author stephen
 */
public class SkillTelemetryCleanupJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillTelemetryCleanupJob.class);

    @Inject
    SkillTelemetryService telemetryService;

    @Override
    public void execute(JobContext context) {
        try {
            var result = telemetryService.purgeExpiredData();
            if (result.purgedBatches() > 0 || result.purgedPapercuts() > 0) {
                LOGGER.info("skill telemetry cleanup completed, purgedBatches={}, purgedPapercuts={}",
                        result.purgedBatches(), result.purgedPapercuts());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("skill telemetry cleanup failed", e);
        }
    }
}
