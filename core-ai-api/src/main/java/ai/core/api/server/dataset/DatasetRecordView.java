package ai.core.api.server.dataset;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class DatasetRecordView {
    @Property(name = "id")
    public String id;

    @Property(name = "run_id")
    public String runId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "run_started_at")
    public String runStartedAt;

    @Property(name = "data")
    public String data;
}
