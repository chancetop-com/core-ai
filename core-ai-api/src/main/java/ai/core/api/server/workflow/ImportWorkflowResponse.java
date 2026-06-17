package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ImportWorkflowResponse {
    @Property(name = "workflow")
    public WorkflowView workflow;

    @Property(name = "unresolved_references")
    public List<UnresolvedReferenceView> unresolvedReferences;
}
