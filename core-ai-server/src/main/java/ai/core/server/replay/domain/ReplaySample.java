package ai.core.server.replay.domain;

import core.framework.mongo.Field;

/**
 * One execution result of a replay run variant. Output is the AssistantMessage JSON
 * of the first choice (same shape as the original span output) so the compare view
 * can diff like for like.
 *
 * @author stephen
 */
public class ReplaySample {
    @Field(name = "index")
    public Integer index;

    @Field(name = "status")
    public ReplaySampleStatus status;

    @Field(name = "output")
    public String output;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "cost_usd")
    public Double costUsd;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "error_message")
    public String errorMessage;

    @Field(name = "replay_trace_id")
    public String replayTraceId;
}
