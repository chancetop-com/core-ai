package ai.core.api.server.analytics;

import core.framework.api.json.Property;

import java.util.List;

/**
 * LLM call throughput and per-model quality metrics, read from the raw LLM spans (retained for the
 * trace retention window, so this is a live-ops view rather than the billing history).
 *
 * @author stephen
 */
public class LlmMetricsView {
    @Property(name = "windowMinutes")
    public Integer windowMinutes;

    @Property(name = "bucketMinutes")
    public Integer bucketMinutes;

    @Property(name = "points")
    public List<LlmThroughputPointView> points;

    @Property(name = "models")
    public List<LlmModelMetricsView> models;
}
