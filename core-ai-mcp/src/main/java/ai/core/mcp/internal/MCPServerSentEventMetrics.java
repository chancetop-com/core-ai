package ai.core.mcp.internal;

import core.framework.internal.stat.Metrics;
import core.framework.internal.stat.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * @author miller
 */
public class MCPServerSentEventMetrics implements Metrics {
    public final List<MCPServerSentEventContextImpl<?>> contexts = new ArrayList<>();

    @Override
    public void collect(Stats stats) {
        int count = 0;
        for (MCPServerSentEventContextImpl<?> context : contexts) {
            count += context.channels.size();
        }
        stats.put("sse_active_channels", count);
    }
}
