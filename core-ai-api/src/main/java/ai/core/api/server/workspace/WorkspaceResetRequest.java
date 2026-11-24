package ai.core.api.server.workspace;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class WorkspaceResetRequest {
    @Property(name = "confirm_name")
    public String confirmName;
}
