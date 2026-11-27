package ai.core.api.server.workspace;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class WorkspaceRetryRequest {
    @Property(name = "rerun_bootstrap")
    public Boolean rerunBootstrap;
}
