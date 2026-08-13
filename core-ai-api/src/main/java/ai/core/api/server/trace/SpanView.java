package ai.core.api.server.trace;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class SpanView {
    @Property(name = "id")
    public String id;

    @Property(name = "traceId")
    public String traceId;

    @Property(name = "userId")
    public String userId;

    @Property(name = "spanId")
    public String spanId;

    @Property(name = "parentSpanId")
    public String parentSpanId;

    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public SpanTypeView type;

    @Property(name = "model")
    public String model;

    @Property(name = "input")
    public String input;

    @Property(name = "output")
    public String output;

    @Property(name = "inputTokens")
    public Long inputTokens;

    @Property(name = "outputTokens")
    public Long outputTokens;

    @Property(name = "cachedTokens")
    public Long cachedTokens;

    @Property(name = "costUsd")
    public Double costUsd;

    @Property(name = "costSource")
    public String costSource;

    @Property(name = "pricingModelId")
    public String pricingModelId;

    @Property(name = "inputPricePer1MTokens")
    public Double inputPricePer1MTokens;

    @Property(name = "outputPricePer1MTokens")
    public Double outputPricePer1MTokens;

    @Property(name = "durationMs")
    public Long durationMs;

    @Property(name = "status")
    public SpanStatusView status;

    @Property(name = "errorMessage")
    public String errorMessage;

    @Property(name = "attributes")
    public Map<String, String> attributes;

    @Property(name = "startedAt")
    public ZonedDateTime startedAt;

    @Property(name = "completedAt")
    public ZonedDateTime completedAt;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;
}
