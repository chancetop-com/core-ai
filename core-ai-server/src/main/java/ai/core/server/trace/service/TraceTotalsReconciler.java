package ai.core.server.trace.service;

import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds trace token/cost counters from the span rows. Insert-time increments are fast, but a
 * crash between the span insert and the trace update can leave a gap; a replay then inserts nothing
 * and cannot repair it. Recomputing at batch end closes that gap. The stores apply the absolute
 * values with max semantics, so a stale concurrent recompute cannot lower a newer value.
 *
 * @author core-ai
 */
public final class TraceTotalsReconciler {
    public static void recompute(SpanStore spanStore, TraceStore traceStore, Set<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return;
        for (var totals : spanStore.tokenTotals(List.copyOf(traceIds))) {
            var update = new TraceUpdate();
            update.traceId = totals.traceId();
            update.inputTokens = totals.inputTokens();
            update.outputTokens = totals.outputTokens();
            update.totalTokens = totals.inputTokens() + totals.outputTokens();
            update.cachedTokens = totals.cachedTokens();
            update.costUsd = totals.costUsd();
            update.updatedAt = ZonedDateTime.now();
            traceStore.update(update);
        }
    }

    private TraceTotalsReconciler() {
    }
}
