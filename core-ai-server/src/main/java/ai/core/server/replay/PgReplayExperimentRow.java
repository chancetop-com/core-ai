package ai.core.server.replay;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgReplayExperimentRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "origin")
    public String origin;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "span_id")
    public String spanId;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "agent_name")
    public String agentName;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "trace_source")
    public String traceSource;

    @Column(name = "span_name")
    public String spanName;

    @Column(name = "original_model")
    public String originalModel;

    @Column(name = "run_count")
    public Integer runCount;

    @Column(name = "note")
    public String note;

    @Column(name = "draft_request")
    public String draftRequest;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
