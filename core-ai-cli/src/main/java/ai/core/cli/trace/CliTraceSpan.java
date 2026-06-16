package ai.core.cli.trace;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * Span payload mirroring server IngestSpanRequest field names for JSON compatibility.
 *
 * <p>Fields carry {@link Property} annotations so the class is registered for reflection in the
 * GraalVM native image; without them JsonUtil.toJson fails at runtime with "No serializer found".
 *
 * @author Xander
 */
public class CliTraceSpan {
    @Property(name = "traceId")
    public String traceId;
    @Property(name = "spanId")
    public String spanId;
    @Property(name = "parentSpanId")
    public String parentSpanId;
    @Property(name = "name")
    public String name;
    @Property(name = "type")
    public String type;            // AGENT | LLM | TOOL
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
    @Property(name = "durationMs")
    public Long durationMs;
    @Property(name = "status")
    public String status;          // OK | ERROR
    @Property(name = "attributes")
    public Map<String, String> attributes;
    @Property(name = "startedAtEpochMs")
    public long startedAtEpochMs;
    @Property(name = "completedAtEpochMs")
    public long completedAtEpochMs;
}
