package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class CreateRunResponse {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "status")
    public String status;
}
