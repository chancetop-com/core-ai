package ai.core.telemetry;

/**
 * Explicit tracer configuration for agent, flow, and group construction.
 *
 * @author Stephen
 */
public record TracerBundle(AgentTracer agentTracer, FlowTracer flowTracer, GroupTracer groupTracer) {
}
