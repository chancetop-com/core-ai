package ai.core.server.channel.openclaw;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class OcgHealthCheckJob implements Job {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgHealthCheckJob.class);

    @Inject
    OcgSandboxService ocgSandboxService;

    @Override
    public void execute(JobContext context) {
        try {
            ocgSandboxService.healthCheck();
        } catch (Exception e) {
            LOGGER.warn("OCG health check failed", e);
            throw e;
        }
    }
}
