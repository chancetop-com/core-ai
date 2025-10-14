package ai.core.telemetry;

/**
 * Global registry for tracers that enables automatic tracing setup
 * The MultiAgentModule sets these tracers during initialization
 * Builders can then auto-inject them if not explicitly provided
 *
 * @author stephen
 */
public final class TracerRegistry {
    private static volatile AgentTracer agentTracer;
    private static volatile FlowTracer flowTracer;
    private static volatile GroupTracer groupTracer;

    /**
     * Set the global agent tracer (called by MultiAgentModule)
     */
    public static void setAgentTracer(AgentTracer tracer) {
        agentTracer = tracer;
    }

    /**
     * Set the global flow tracer (called by MultiAgentModule)
     */
    public static void setFlowTracer(FlowTracer tracer) {
        flowTracer = tracer;
    }

    /**
     * Set the global group tracer (called by MultiAgentModule)
     */
    public static void setGroupTracer(GroupTracer tracer) {
        groupTracer = tracer;
    }

    /**
     * Get the global agent tracer (used by builders for auto-injection)
     */
    public static AgentTracer getAgentTracer() {
        return agentTracer;
    }

    /**
     * Get the global flow tracer (used by builders for auto-injection)
     */
    public static FlowTracer getFlowTracer() {
        return flowTracer;
    }

    /**
     * Get the global group tracer (used by builders for auto-injection)
     */
    public static GroupTracer getGroupTracer() {
        return groupTracer;
    }

    /**
     * Check if tracing is enabled
     */
    public static boolean isTracingEnabled() {
        return agentTracer != null;
    }

    /**
     * Clear all tracers (useful for testing)
     */
    public static void clear() {
        agentTracer = null;
        flowTracer = null;
        groupTracer = null;
    }

    private TracerRegistry() {
        // Prevent instantiation
    }
}
