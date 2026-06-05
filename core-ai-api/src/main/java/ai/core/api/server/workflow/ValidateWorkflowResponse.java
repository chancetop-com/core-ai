package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ValidateWorkflowResponse {
    @Property(name = "valid")
    public Boolean valid;

    @Property(name = "errors")
    public List<String> errors;
}
