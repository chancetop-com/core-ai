package ai.core.server.replay.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * One replay execution (a variant of the original request). The full request
 * snapshot is stored so the run is reproducible; samples hold each execution's
 * output and usage.
 *
 * @author stephen
 */
@Collection(name = "replay_runs")
public class ReplayRun {
    @Id
    public String id;

    @Field(name = "experiment_id")
    public String experimentId;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "label")
    public String label;

    // Full ChatML request snapshot for this variant (reproducible).
    @Field(name = "request")
    public String request;

    // Execution params; non-null values override the request JSON fields.
    @Field(name = "model")
    public String model;

    @Field(name = "temperature")
    public Double temperature;

    @Field(name = "reasoning_effort")
    public String reasoningEffort;

    @Field(name = "sample_count")
    public Integer sampleCount;

    @Field(name = "samples")
    public List<ReplaySample> samples;

    @Field(name = "status")
    public ReplayRunStatus status;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;
}
