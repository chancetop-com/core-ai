package ai.core.server.trace;

import ai.core.server.trace.domain.TraceStatus;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * A partial trace update keyed by trace id. Ingest and cleanup only ever touch a handful of columns,
 * never the whole document: concurrent requests increment the token counters while another one sets
 * the payload, and a full replace would drop one of the two.
 *
 * @author stephen
 */
public class TraceUpdate {
    public String traceId;
    // when set, the update only applies if the trace is still in this status
    public TraceStatus requireStatus;

    public TraceStatus status;
    public String errorMessage;
    public boolean clearErrorMessage;
    public String input;
    public String output;
    public Long durationMs;
    public ZonedDateTime completedAt;
    public ZonedDateTime updatedAt;

    public String name;
    public String sessionId;
    public String userId;
    public String source;
    public String type;
    public String agentName;
    public String agentId;
    public String model;
    public Map<String, String> metadata;

    // when set, only this column is written, and only while it is still null or blank
    public EmptyGuard setOnlyWhenEmpty;
    public String valueWhenEmpty;
    // NAME may also be replaced while it still holds one of these generic operation titles
    public List<String> namePlaceholders;

    public Long inputTokensDelta;
    public Long outputTokensDelta;
    public Long totalTokensDelta;
    public Long cachedTokensDelta;
    public Double costUsdDelta;

    public enum EmptyGuard {
        AGENT_NAME, AGENT_ID, MODEL, NAME
    }
}
