package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class CloneWorkflowResponse {
    @Property(name = "workflow")
    public WorkflowView workflow;

    // Publish-blocking issues the clone already has under the caller's ownership — chiefly AGENT/LLM nodes that
    // reference agents the caller does not own. Surfaced as a notice so the user knows to replace them before publish.
    @Property(name = "warnings")
    public List<String> warnings;
}
