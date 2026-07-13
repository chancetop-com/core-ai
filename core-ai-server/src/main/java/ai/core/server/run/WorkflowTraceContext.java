package ai.core.server.run;

/**
 * @author stephen
 */
public record WorkflowTraceContext(String workflowId, String workflowRunId, String workflowNodeId, String workflowNodeType) {
}
