package ai.core.server.replay.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * A replay experiment: snapshot of one LLM span's request/response plus the
 * editable draft and the runs executed against it.
 * <p>
 * The experiment owns all payload snapshots (traces are archived and deleted
 * after the retention window), runs live in a separate collection because a
 * run's full request snapshot can reach MB scale.
 *
 * @author stephen
 */
@Collection(name = "replay_experiments")
public class ReplayExperiment {
    @Id
    public String id;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "origin")
    public ReplayExperimentOrigin origin;

    // ── Source references (display / jump-back only, not required to survive) ──
    @Field(name = "trace_id")
    public String traceId;

    @Field(name = "span_id")
    public String spanId;

    // ── Denormalized from Trace/Span for list filtering ──
    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "agent_name")
    public String agentName;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "trace_source")
    public String traceSource;

    @Field(name = "span_name")
    public String spanName;

    @Field(name = "original_model")
    public String originalModel;

    // ── Payload snapshots (survive trace archiving) ──
    @Field(name = "original_input")
    public String originalInput;

    @Field(name = "original_output")
    public String originalOutput;

    @Field(name = "original_params")
    public String originalParams;

    @Field(name = "original_usage")
    public ReplayUsage originalUsage;

    @Field(name = "trace_snapshot")
    public String traceSnapshot;

    // ── Editing state ──
    @Field(name = "draft_request")
    public String draftRequest;

    @Field(name = "note")
    public String note;

    @Field(name = "run_count")
    public Integer runCount;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
