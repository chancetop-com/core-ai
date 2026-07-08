package ai.core.server.trace.maintenance;

import ai.core.server.task.AbstractTask;
import ai.core.server.task.TaskContext;
import core.framework.inject.Inject;

import java.time.LocalDate;

/**
 * Daily trace maintenance wrapped as a background task.
 * Aggregates per-user per-model token/cost stats from traces into
 * {@code trace_daily_stats} for fast Dashboard queries.
 *
 * @author cyril
 */
public class TraceDailyMaintenanceTask extends AbstractTask {
    public static final String TYPE = "TRACE_DAILY_MAINTENANCE";

    @Inject
    TraceDailyMaintenanceService maintenanceService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(TaskContext ctx) {
        LocalDate date = ctx.date();
        if (date == null) {
            date = LocalDate.now(java.time.ZoneId.of("UTC")).minusDays(1);
            ctx.log("no date in taskId, defaulting to yesterday: " + date);
        }

        ctx.log("starting daily maintenance for " + date);
        int count = maintenanceService.aggregateDailyStats(date);
        ctx.setStatusText("aggregated " + count + " user-day records for " + date);
        ctx.log("daily maintenance completed, date=" + date + ", records=" + count);
    }
}
