package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class TraceView {
    @Property(name = "id")
    public String id;

    @Property(name = "traceId")
    public String traceId;

    @Property(name = "name")
    public String name;

    @Property(name = "model")
    public String model;

    @Property(name = "type")
    public String type;

    @Property(name = "source")
    public String source;

    @Property(name = "agentName")
    public String agentName;

    @Property(name = "agentId")
    public String agentId;

    @Property(name = "sessionId")
    public String sessionId;

    @Property(name = "userId")
    public String userId;

    @Property(name = "status")
    public TraceStatusView status;

    @Property(name = "errorMessage")
    public String errorMessage;

    @Property(name = "input")
    public String input;

    @Property(name = "output")
    public String output;

    @Property(name = "preview")
    public String preview;

    @Property(name = "metadata")
    public Map<String, String> metadata;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "totalTokens")
    public Long totalTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "durationMs")
    public Long durationMs;

    @Property(name = "startedAt")
    public ZonedDateTime startedAt;

    @Property(name = "completedAt")
    public ZonedDateTime completedAt;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;

    @Property(name = "updatedAt")
    public ZonedDateTime updatedAt;

    @Property(name = "account")
    public TraceAccountView account;
}
