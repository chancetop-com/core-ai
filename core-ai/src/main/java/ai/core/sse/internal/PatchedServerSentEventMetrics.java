package ai.core.sse.internal;

import core.framework.internal.stat.Metrics;
import core.framework.internal.stat.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * @author miller
 */
public class PatchedServerSentEventMetrics implements Metrics {
    public final List<PatchedServerSentEventContextImpl<?>> contexts = new ArrayList<>();

    @Override
    public void collect(Stats stats) {
        int count = 0;
        for (PatchedServerSentEventContextImpl<?> context : contexts) {
            count += context.channels.size();
        }
        stats.put("sse_active_channels", count);
    }
}
