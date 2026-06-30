package ai.core.server.workflow;

/**
 * Metadata known when a workflow AGENT/LLM node submits its decoupled child AgentRun.
 *
 * @author Xander
 */
public record StartedAgentRun(String runId, String model, String multiModalModel) {
    public StartedAgentRun(String runId) {
        this(runId, null, null);
    }
}
