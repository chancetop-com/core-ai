package ai.core.server.replay;

import ai.core.server.replay.domain.ReplaySampleStatus;

/**
 * @author stephen
 */
public class ReplaySampleUpdate {
    public ReplaySampleStatus status;
    public String output;
    public String errorMessage;
    public String replayTraceId;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Double costUsd;
    public Long durationMs;
}
