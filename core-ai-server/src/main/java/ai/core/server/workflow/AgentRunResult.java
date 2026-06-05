package ai.core.server.workflow;

/**
 * The terminal result of a child AgentRun, as the workflow node needs it: completed (with output) or failed
 * (with an error). Token usage / transcript / artifacts stay on the child AgentRun and are linked via
 * child_run_id, not copied here.
 *
 * @author Xander
 */
public record AgentRunResult(boolean completed, String output, String error) {
    public static AgentRunResult completed(String output) {
        return new AgentRunResult(true, output, null);
    }

    public static AgentRunResult failed(String error) {
        return new AgentRunResult(false, null, error);
    }
}
