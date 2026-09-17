package ai.core.server.memory;

import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.trace.domain.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns one V2 extraction response into memory rows and resolves where each row came from.
 *
 * @author Xander
 */
public final class MemoryExtractionParser {
    public static final int TRAJECTORY_MAX_CHARS = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryExtractionParser.class);

    @SuppressFBWarnings({"VA_FORMAT_STRING_USES_NEWLINE", "REC_CATCH_EXCEPTION"})
    public static List<AgentMemory> parse(String response, String agentId, List<Trace> traces) {
        var memories = new ArrayList<AgentMemory>();
        try {
            var json = extractJson(response);
            var om = new ObjectMapper();
            var node = om.readTree(json);
            var now = ZonedDateTime.now();
            var trajectories = node.get("trajectories");
            if (trajectories != null && trajectories.isArray()) {
                for (var item : trajectories) {
                    var memory = new AgentMemory();
                    memory.id = UUID.randomUUID().toString();
                    memory.agentId = agentId;
                    memory.type = "TRAJECTORY";
                    memory.layer = MemoryLayer.TRAJECTORIES;
                    memory.content = formatTrajectoryContent(item);
                    memory.createdAt = now;
                    memory.updatedAt = now;
                    memory.sourceTraceIds = resolveSourceTraceIds(item, traces);
                    memories.add(memory);
                }
            }
            var patterns = node.get("patterns");
            if (patterns != null && patterns.isArray()) {
                for (var item : patterns) {
                    var memory = new AgentMemory();
                    memory.id = UUID.randomUUID().toString();
                    memory.agentId = agentId;
                    memory.type = item.has("type") ? item.get("type").asText() : null;
                    memory.layer = MemoryLayer.METHODS;
                    memory.content = item.get("content").asText();
                    memory.createdAt = now;
                    memory.updatedAt = now;
                    memory.sourceTraceIds = resolveSourceTraceIds(item, traces);
                    memories.add(memory);
                }
            }
        } catch (Exception e) {
            LOGGER.error("failed to parse V2 extraction response", e);
        }
        return memories;
    }

    private static String formatTrajectoryContent(JsonNode item) {
        var sessionId = item.has("session_id") ? item.get("session_id").asText() : "unknown";
        var summary = item.has("summary") ? item.get("summary").asText() : "";
        if (summary.length() > TRAJECTORY_MAX_CHARS) {
            summary = summary.substring(0, TRAJECTORY_MAX_CHARS);
        }
        return "[session=" + sessionId + "] " + summary;
    }

    // provenance is resolved per row: a pattern points at the sessions it was observed in, a trajectory at
    // its own session. Attaching the whole processed batch to every row (as this used to do) made the link
    // useless for both the memory page and the agent reading the origin of one memory.
    private static List<String> resolveSourceTraceIds(JsonNode item, List<Trace> traces) {
        var sessionIds = new ArrayList<String>();
        var sessions = item.get("source_sessions");
        if (sessions != null && sessions.isArray()) {
            for (var session : sessions) {
                addSessionId(sessionIds, session.asText());
            }
        } else {
            addSessionId(sessionIds, item.path("session_id").asText());
        }
        if (sessionIds.isEmpty()) return List.of();

        var traceIds = new ArrayList<String>();
        for (var trace : traces) {
            if (trace.traceId == null || trace.sessionId == null) continue;
            if (sessionIds.contains(trace.sessionId) && !traceIds.contains(trace.traceId)) {
                traceIds.add(trace.traceId);
            }
        }
        return traceIds;
    }

    private static void addSessionId(List<String> sessionIds, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessionIds.add(sessionId);
        }
    }

    private static String extractJson(String response) {
        if (response == null) return "{}";
        var trimmed = response.trim();
        var start = trimmed.indexOf('{');
        var end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private MemoryExtractionParser() {
    }
}
