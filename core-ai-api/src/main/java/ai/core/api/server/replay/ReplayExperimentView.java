package ai.core.api.server.replay;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Full replay experiment: payload snapshots, editable draft, note and run summaries.
 *
 * @author stephen
 */
public class ReplayExperimentView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "origin")
    public String origin;

    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "span_id")
    public String spanId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "trace_source")
    public String traceSource;

    @Property(name = "span_name")
    public String spanName;

    @Property(name = "original_model")
    public String originalModel;

    @Property(name = "original_input")
    public String originalInput;

    @Property(name = "original_output")
    public String originalOutput;

    @Property(name = "original_params")
    public String originalParams;

    @Property(name = "original_usage")
    public ReplayUsageView originalUsage;

    @Property(name = "trace_snapshot")
    public String traceSnapshot;

    @Property(name = "draft_request")
    public String draftRequest;

    @Property(name = "note")
    public String note;

    @Property(name = "run_count")
    public Integer runCount;

    @Property(name = "runs")
    public List<ReplayRunSummaryView> runs;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
