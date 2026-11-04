package ai.core.server.dataset.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * Read model of one {@code dataset_records} row; {@code data} is selected as text so it maps to String.
 *
 * @author stephen
 */
public class PgDatasetRecordRow {
    @Column(name = "id")
    public String id;

    @Column(name = "dataset_id")
    public String datasetId;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "run_id")
    public String runId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "data")
    public String data;

    @Column(name = "run_started_at")
    public ZonedDateTime runStartedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "updated_by")
    public String updatedBy;
}
