package ai.core.server.trace.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author Xander
 */
@Collection(name = "spans")
public class Span {
    @Id
    public String id;

    @Field(name = "trace_id")
    public String traceId;

    @Field(name = "span_id")
    public String spanId;

    @Field(name = "parent_span_id")
    public String parentSpanId;

    @Field(name = "name")
    public String name;

    @Field(name = "type")
    public SpanType type;

    @Field(name = "model")
    public String model;

    @Field(name = "input")
    public String input;

    @Field(name = "output")
    public String output;

    @Field(name = "input_tokens")
    public Long inputTokens;

    @Field(name = "output_tokens")
    public Long outputTokens;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "status")
    public SpanStatus status;

    @Field(name = "attributes")
    public Map<String, String> attributes;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
