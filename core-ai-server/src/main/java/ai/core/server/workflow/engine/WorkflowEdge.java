package ai.core.server.workflow.engine;

/**
 * A directed edge. A BRANCH node names the chosen edges by {@code id}; all other control flow is derived
 * from the source node's fact.
 *
 * @author Xander
 */
public record WorkflowEdge(String id, String source, String target) {
}
