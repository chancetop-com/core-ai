package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * @author Xander
 */
public class ImportWorkflowRequest {
    @NotBlank
    @Property(name = "content")
    public String content;   // raw exported envelope JSON

    @Property(name = "name")
    public String name;   // optional override for the imported workflow name
}
