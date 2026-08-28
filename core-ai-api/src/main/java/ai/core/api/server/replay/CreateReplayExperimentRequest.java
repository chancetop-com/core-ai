package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * Creates a replay experiment from a trace's LLM span.
 *
 * @author stephen
 */
public class CreateReplayExperimentRequest {
    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "span_id")
    public String spanId;
}
