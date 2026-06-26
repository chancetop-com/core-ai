package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class CreateRunRequest {
    @Property(name = "input")
    public String input;

    @Property(name = "visibility")
    public String visibility;   // PRIVATE (default) | PUBLIC
}
