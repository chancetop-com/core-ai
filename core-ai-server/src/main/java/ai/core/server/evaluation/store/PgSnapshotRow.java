package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSnapshotRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "agent_id")
    public String agentId;
    @Column(name = "source")
    public String source;
    @Column(name = "source_revision")
    public String sourceRevision;
    @Column(name = "fingerprint")
    public String fingerprint;
    @Column(name = "snapshot_json")
    public String snapshotJson;
    @Column(name = "created_by")
    public String createdBy;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "data")
    public String data;
}
