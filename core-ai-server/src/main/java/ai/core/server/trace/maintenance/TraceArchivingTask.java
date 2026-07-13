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
        var cutoffInstant = cutoff.atStartOfDay(UTC);

        String state = ctx.state();

        // Phase 1: upload traces to object storage
        if (!"UPLOADED".equals(state)) {
            ctx.log("uploading traces with started_at < " + cutoff);
            int count = maintenanceService.uploadArchive(cutoffInstant);
            if (count < 0) {
                ctx.setStatusText("archive skipped (storage not configured or stats missing), cutoff=" + cutoff);
                ctx.setState("SKIPPED");
                return;
            }
            ctx.log("upload completed, cutoff=" + cutoff + ", count=" + count);
            ctx.setState("UPLOADED");
        }

        // Phase 2: delete archived traces from MongoDB
        // Runs on first execution (just after Phase 1 above) or on retry (when state was already UPLOADED)
        if ("UPLOADED".equals(ctx.state())) {
            ctx.log("deleting archived traces from MongoDB, cutoff=" + cutoff);
            maintenanceService.deleteArchivedTraces(cutoffInstant);
            ctx.log("deletion completed");
            ctx.setState("DELETED");
            ctx.setStatusText("archived and deleted traces before " + cutoff);
        }
    }
}
