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

    @Property(name = "user_id")
    public String userId;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "created_at")
    public String createdAt;

    @Property(name = "updated_at")
    public String updatedAt;

    @Property(name = "updated_by")
    public String updatedBy;
}
