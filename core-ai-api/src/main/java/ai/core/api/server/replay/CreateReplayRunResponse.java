package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateReplayRunResponse {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "status")
    public String status;
}
