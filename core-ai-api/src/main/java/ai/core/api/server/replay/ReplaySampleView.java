package ai.core.api.server.replay;

import core.framework.api.json.Property;

/**
 * One execution result of a replay run variant.
 *
 * @author stephen
 */
public class ReplaySampleView {
    @Property(name = "index")
    public Integer index;

    @Property(name = "status")
    public String status;

    @Property(name = "output")
    public String output;

    @Property(name = "input_tokens")
    public Long inputTokens;

    @Property(name = "output_tokens")
    public Long outputTokens;

    @Property(name = "cost_usd")
    public Double costUsd;

    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "error_message")
    public String errorMessage;

    @Property(name = "replay_trace_id")
    public String replayTraceId;
}
