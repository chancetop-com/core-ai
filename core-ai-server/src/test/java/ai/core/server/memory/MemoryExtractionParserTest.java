package ai.core.server.memory;

import ai.core.server.trace.domain.Trace;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryExtractionParserTest {
    private static final String RESPONSE = """
            {
              "trajectories": [
                { "session_id": "session-1", "summary": "deployed the tenant" }
              ],
              "patterns": [
                { "type": "EFFICIENCY", "content": "batch the calls", "source_sessions": ["session-2"] },
                { "type": "TOOL_USAGE", "content": "prefer the mcp tool" }
              ]
            }
            """;

    @Test
    void provenanceIsResolvedPerRowInsteadOfPerBatch() {
        var memories = MemoryExtractionParser.parse(RESPONSE, "agent-1",
                List.of(trace("trace-1", "session-1"), trace("trace-2", "session-2"), trace("trace-3", "session-3")));

        assertEquals(3, memories.size());
        assertEquals(List.of("trace-1"), memories.get(0).sourceTraceIds);
        assertEquals(List.of("trace-2"), memories.get(1).sourceTraceIds);
        assertEquals(List.of(), memories.get(2).sourceTraceIds);
    }

    @Test
    void trajectoryKeepsItsSessionIdInTheContent() {
        var memories = MemoryExtractionParser.parse(RESPONSE, "agent-1", List.of(trace("trace-1", "session-1")));

        assertEquals("[session=session-1] deployed the tenant", memories.getFirst().content);
        assertEquals("TRAJECTORY", memories.getFirst().type);
    }

    @Test
    void unknownSessionIdsNeverBecomeProvenance() {
        var memories = MemoryExtractionParser.parse("""
                { "patterns": [ { "type": "EFFICIENCY", "content": "batch the calls", "source_sessions": ["session-404"] } ] }
                """, "agent-1", List.of(trace("trace-1", "session-1")));

        assertEquals(List.of(), memories.getFirst().sourceTraceIds);
    }

    @Test
    void malformedResponseYieldsNoMemories() {
        assertEquals(List.of(), MemoryExtractionParser.parse("not json at all", "agent-1", List.of(trace("trace-1", "session-1"))));
    }

    private Trace trace(String traceId, String sessionId) {
        var trace = new Trace();
        trace.traceId = traceId;
        trace.sessionId = sessionId;
        trace.agentId = "agent-1";
        trace.startedAt = ZonedDateTime.parse("2026-09-17T09:00:00Z");
        return trace;
    }
}
