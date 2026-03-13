package ai.core.server.trace.web.ingest;

import java.util.Map;

/**
 * @author Xander
 */
public class IngestSpanRequest {
    public String traceId;
    public String spanId;
    public String parentSpanId;
    public String name;
    public String type;
    public String model;
    public String input;
    public String output;
    public Long inputTokens;
    public Long outputTokens;
    public Long durationMs;
    public String status;
    public Map<String, String> attributes;
    public long startedAtEpochMs;
    public long completedAtEpochMs;
}
