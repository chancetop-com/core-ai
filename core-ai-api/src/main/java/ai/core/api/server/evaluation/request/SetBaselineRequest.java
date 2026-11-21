package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SetBaselineRequest {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "reason")
    public String reason;

    @Property(name = "acknowledge_unpinned")
    public Boolean acknowledgeUnpinned;

    @Property(name = "expected_generation")
    public Integer expectedGeneration;
}
