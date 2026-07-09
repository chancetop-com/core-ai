package ai.core.server.trace.maintenance;

import ai.core.server.task.AbstractTask;
import ai.core.server.task.TaskContext;
import core.framework.inject.Inject;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Archives traces older than the retention window to object storage
 * and deletes them from MongoDB.
 *
 * <p>Cutoff date = today - retentionDays. The taskId date suffix is used
 * for deduplication (one execution per day).</p>
 *
 * @author cyril
 */
public class TraceArchivingTask extends AbstractTask {
    public static final String TYPE = "TRACE_ARCHIVE";
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Inject
    TraceDailyMaintenanceService maintenanceService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(TaskContext ctx) {
        LocalDate today = LocalDate.now(UTC);
        LocalDate cutoff = today.minusDays(maintenanceService.retentionDays);

        ctx.log("archiving traces with started_at < " + cutoff);
        int count = maintenanceService.archiveTraces(cutoff.atStartOfDay(UTC));

        if (count < 0) {
            ctx.setStatusText("archive skipped (storage not configured or stats missing), cutoff=" + cutoff);
        } else {
            ctx.setStatusText("archived " + count + " traces, cutoff=" + cutoff);
        }
        ctx.log("archive completed, cutoff=" + cutoff + ", count=" + count);
    }
}
