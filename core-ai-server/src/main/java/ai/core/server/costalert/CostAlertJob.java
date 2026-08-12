package ai.core.server.costalert;

import core.framework.inject.Inject;
import core.framework.scheduler.Job;
import core.framework.scheduler.JobContext;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Scheduled job that evaluates cost alert rules. Runs every 30 minutes;
 * duplicate delivery across pods is prevented by the unique event index.
 *
 * @author stephen
 */
public class CostAlertJob implements Job {
    @Inject
    CostAlertService costAlertService;

    @Override
    public void execute(JobContext context) {
        costAlertService.check(LocalDate.now(ZoneOffset.UTC));
    }
}
