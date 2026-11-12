package ai.core.server.replay;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgReplayRunRow {
    @Column(name = "id")
    public String id;

    @Column(name = "experiment_id")
    public String experimentId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "label")
    public String label;

    @Column(name = "model")
    public String model;

    @Column(name = "temperature")
    public Double temperature;

    @Column(name = "reasoning_effort")
    public String reasoningEffort;

    @Column(name = "sample_count")
    public Integer sampleCount;

    @Column(name = "status")
    public String status;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "request")
    public String request;

    @Column(name = "data")
    public String data;
}
