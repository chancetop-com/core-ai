package ai.core.api.server.replay;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Full replay run: the variant's request snapshot and all sample results.
 *
 * @author stephen
 */
public class ReplayRunView {
    @Property(name = "id")
    public String id;

    @Property(name = "experiment_id")
    public String experimentId;

    @Property(name = "label")
    public String label;

    @Property(name = "request")
    public String request;

    @Property(name = "model")
    public String model;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "reasoning_effort")
    public String reasoningEffort;

    @Property(name = "sample_count")
    public Integer sampleCount;

    @Property(name = "samples")
    public List<ReplaySampleView> samples;

    @Property(name = "status")
    public String status;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;
}
