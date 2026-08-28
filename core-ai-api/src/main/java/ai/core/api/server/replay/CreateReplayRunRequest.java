package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * Executes one replay variant: the modified ChatML request plus execution params.
 * Run-level params override the same-named fields inside the request JSON.
 *
 * @author stephen
 */
public class CreateReplayRunRequest {
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

    @Property(name = "label")
    public String label;
}
