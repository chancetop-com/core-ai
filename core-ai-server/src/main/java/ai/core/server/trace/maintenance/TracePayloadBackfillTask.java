package ai.core.server.trace.maintenance;

import ai.core.server.task.AbstractTask;
import ai.core.server.task.TaskContext;
import ai.core.server.trace.service.TracePayloadStore;
import core.framework.inject.Inject;

/**
 * Moves a bounded batch of legacy inline trace payloads to object storage. Task ids are per
 * five-minute slot, so at most one pod executes a slot and the next slot resumes with the oldest
 * remaining inline rows.
 *
 * @author core-ai
 */
public class TracePayloadBackfillTask extends AbstractTask {
    public static final String TYPE = "TRACE_PAYLOAD_BACKFILL";

    @Inject
    TracePayloadStore payloadStore;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void execute(TaskContext ctx) {
        int updated = payloadStore.backfillBatch(0);
        ctx.log("backfilled " + updated + " trace payload rows");
        ctx.setStatusText("backfilled " + updated + " trace payload rows");
    }
}