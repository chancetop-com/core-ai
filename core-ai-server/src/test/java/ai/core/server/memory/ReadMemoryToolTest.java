package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceStatus;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadMemoryToolTest {
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final ReadMemoryTool tool = new ReadMemoryTool("assistant:user-1", agentMemoryService);
    private final ExecutionContext context = ExecutionContext.builder().sessionId("session-1").userId("user-1").build();

    @Test
    void requiresMemoryId() {
        var result = tool.execute("{}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("memory_id is required"));
    }

    @Test
    void unknownMemoryIsRejected() {
        when(agentMemoryService.find("assistant:user-1", "memory-9")).thenReturn(null);

        var result = tool.execute("{\"memory_id\":\"memory-9\"}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("no memory memory-9"));
    }

    @Test
    void listsTheSourceTracesAndAsksForATraceIdToReadThem() {
        when(agentMemoryService.find("assistant:user-1", "memory-1")).thenReturn(memory());
        when(agentMemoryService.sourceTraces(any())).thenReturn(List.of(trace("trace-1", "user-1")));

        var result = tool.execute("{\"memory_id\":\"memory-1\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("Memory memory-1 (KNOWLEDGE/USER_PREFERENCE)"));
        assertTrue(result.getResult().contains("trace-1"));
        assertTrue(result.getResult().contains("Pass trace_id to read the conversation behind one of them."));
    }

    @Test
    void archivedTracesAreReportedAsGone() {
        when(agentMemoryService.find("assistant:user-1", "memory-1")).thenReturn(memory());
        when(agentMemoryService.sourceTraces(any())).thenReturn(List.of());

        var result = tool.execute("{\"memory_id\":\"memory-1\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("none retained"));
    }

    @Test
    void traceThatIsNotASourceIsRejected() {
        when(agentMemoryService.find("assistant:user-1", "memory-1")).thenReturn(memory());
        when(agentMemoryService.sourceTraces(any())).thenReturn(List.of(trace("trace-1", "user-1")));

        var result = tool.execute("{\"memory_id\":\"memory-1\",\"trace_id\":\"trace-9\"}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("is not a source of memory memory-1"));
        assertTrue(result.getResult().contains("available: trace-1"));
    }

    @Test
    void anotherUsersTraceIsRejected() {
        when(agentMemoryService.find("assistant:user-1", "memory-1")).thenReturn(memory());
        when(agentMemoryService.sourceTraces(any())).thenReturn(List.of(trace("trace-1", "user-2")));

        var result = tool.execute("{\"memory_id\":\"memory-1\",\"trace_id\":\"trace-1\"}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("belongs to another user"));
    }

    @Test
    void sourceTraceIsRenderedAsEvidence() {
        when(agentMemoryService.find("assistant:user-1", "memory-1")).thenReturn(memory());
        when(agentMemoryService.sourceTraces(any())).thenReturn(List.of(trace("trace-1", "user-1")));
        when(agentMemoryService.renderEvidence(any())).thenReturn("User: deploy the tenant\n");

        var result = tool.execute("{\"memory_id\":\"memory-1\",\"trace_id\":\"trace-1\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("Evidence of trace-1"));
        assertTrue(result.getResult().contains("User: deploy the tenant"));
    }

    private AgentMemory memory() {
        var memory = new AgentMemory();
        memory.id = "memory-1";
        memory.agentId = "assistant:user-1";
        memory.type = "USER_PREFERENCE";
        memory.layer = MemoryLayer.KNOWLEDGE;
        memory.content = "the user prefers metric units";
        memory.createdAt = ZonedDateTime.parse("2026-09-17T09:00:00Z");
        memory.sourceTraceIds = List.of("trace-1");
        return memory;
    }

    private Trace trace(String traceId, String userId) {
        var trace = new Trace();
        trace.traceId = traceId;
        trace.userId = userId;
        trace.source = "chat";
        trace.status = TraceStatus.COMPLETED;
        trace.startedAt = ZonedDateTime.parse("2026-09-17T09:00:00Z");
        trace.durationMs = 1200L;
        return trace;
    }
}
