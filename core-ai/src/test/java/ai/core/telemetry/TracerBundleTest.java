package ai.core.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * @author stephen
 */
class TracerBundleTest {
    @Test
    void retainsTheExplicitTracerConfiguration() {
        var openTelemetry = OpenTelemetry.noop();
        var agentTracer = new AgentTracer(openTelemetry, true);
        var flowTracer = new FlowTracer(openTelemetry, true);
        var groupTracer = new GroupTracer(openTelemetry, true);

        var bundle = new TracerBundle(agentTracer, flowTracer, groupTracer);

        assertSame(agentTracer, bundle.agentTracer());
        assertSame(flowTracer, bundle.flowTracer());
        assertSame(groupTracer, bundle.groupTracer());
    }
}
